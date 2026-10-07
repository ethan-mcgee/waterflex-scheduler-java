package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.Monetary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** One immutable revision; planning copies own assignment and shadow state only. */
public record PlanFacts(List<Technician> technicians, List<Visit> demand,
        Map<String, DayPlan.RoadLeg> matrix, Set<String> unreachable,
        BigDecimal regularHourly, BigDecimal overtimeHourly, BigDecimal mileagePerMile,
        BigDecimal travelBufferPct, long travelBufferMinutes, Set<String> demandServices) {
    public record Technician(String id, Instant start, Instant end, int maxDaily, int maxOvertime,
            Set<String> qualifications, List<TechRoute.Unavailable> absences, List<String> pinnedVisits) {
        public Technician(String id, Instant start, Instant end, int maxDaily, int maxOvertime,
                Set<String> qualifications, List<TechRoute.Unavailable> absences) {
            this(id, start, end, maxDaily, maxOvertime, qualifications, absences, Required.value(List.of()));
        }
        public Technician {
            identity(id, "technician.id"); check(start.isBefore(end), "technician.shift must have start < end");
            check(maxDaily >= 0 && maxOvertime >= 0, "technician.capacity must be nonnegative");
            qualifications = Required.value(Set.copyOf(qualifications));
            qualifications.forEach(value -> identity(Required.value(value), "technician.qualification"));
            absences = Required.value(List.copyOf(absences));
            absences.forEach(value -> check(value.start().isBefore(value.end()), "absence must have start < end"));
            pinnedVisits = Required.value(List.copyOf(pinnedVisits));
            check(new HashSet<>(pinnedVisits).size() == pinnedVisits.size(), "duplicate pinned visit");
        }
    }
    public record Visit(String id, String service, Instant start, Instant end, int minutes,
            @Nullable String originalTechnician, @Nullable Instant originalStart) {
        public Visit {
            identity(id, "visit.id"); identity(service, "visit.service");
            check((originalTechnician == null) == (originalStart == null), "original assignment must be entirely present or absent");
            if (originalTechnician != null) identity(originalTechnician, "visit.originalTechnician");
            check(start.isBefore(end), "visit.window must have start < end"); check(minutes > 0, "visit.duration must be positive");
        }
    }
    public PlanFacts {
        technicians = Required.value(List.copyOf(technicians)); demand = Required.value(List.copyOf(demand));
        matrix = Required.value(Map.copyOf(matrix)); unreachable = Required.value(Set.copyOf(unreachable));
        demandServices = Required.value(Set.copyOf(demandServices));
        regularHourly = Monetary.rate(regularHourly); overtimeHourly = Monetary.rate(overtimeHourly);
        mileagePerMile = Monetary.rate(mileagePerMile); travelBufferPct = Monetary.rate(travelBufferPct);
        check(travelBufferMinutes >= 0 && travelBufferMinutes <= Integer.MAX_VALUE, "travelBufferMinutes out of range");
        Set<String> locations = new HashSet<>(); Set<String> ids = new HashSet<>();
        for (Technician tech : technicians) {
            check(ids.add(tech.id()), "duplicate technician identity: " + tech.id());
            check(locations.add(tech.id()) && locations.add(tech.id() + ":return"), "colliding technician endpoint identity");
        }
        ids.clear(); Set<String> services = new HashSet<>();
        for (Visit visit : demand) {
            check(ids.add(visit.id()), "duplicate visit identity: " + visit.id());
            check(locations.add(visit.id()), "colliding visit/endpoint identity: " + visit.id()); services.add(visit.service());
        }
        check(services.equals(demandServices), "demandServices must match demand");
        // Legacy booking pools may contain roads for other routes. Validate all values without dropping them.
        for (var entry : matrix.entrySet()) { roadKey(Required.value(entry.getKey())); Objects.requireNonNull(entry.getValue(), "road value"); }
        for (String key : unreachable) { roadKey(Required.value(key)); check(!matrix.containsKey(key), "road is both reachable and unreachable: " + key); }
    }
    public static PlanFacts capture(List<TechRoute> routes, List<PlanVisit> visits, Map<String, DayPlan.RoadLeg> roads,
            Set<String> unreachable, BigDecimal regular, BigDecimal overtime, BigDecimal mileage, BigDecimal pct, long minutes) {
        List<Technician> technicians = new ArrayList<>(); List<Visit> demand = new ArrayList<>(); Set<String> services = new HashSet<>();
        for (TechRoute route : routes) technicians.add(route.facts());
        for (PlanVisit visit : visits) { demand.add(visit.facts()); services.add(visit.getServiceId()); }
        PlanFacts facts = new PlanFacts(technicians, demand, roads, unreachable, regular, overtime, mileage, pct, minutes, services);
        validateAssignments(routes, visits, false); routes.forEach(route -> Required.value(route).freeze()); return facts;
    }
    public void validateEntities(DayPlan plan, boolean complete) {
        check(plan.getRoutes().stream().map(route -> Required.value(route).facts()).toList().equals(technicians), "technician facts differ from revision");
        check(plan.getVisits().stream().map(visit -> Required.value(visit).facts()).toList().equals(demand), "visit facts differ from revision");
        validateAssignments(plan.getRoutes(), plan.getVisits(), complete);
    }
    /** All current list selectors may temporarily assign any visit to any route. */
    public Set<String> requiredSearchRoads() {
        Set<String> required = new HashSet<>();
        for (Technician tech : technicians) for (Visit visit : demand) {
            required.add(tech.id() + ">" + visit.id()); required.add(visit.id() + ">" + tech.id() + ":return");
        }
        for (Visit from : demand) for (Visit to : demand) if (!from.id().equals(to.id())) required.add(from.id() + ">" + to.id());
        return Required.value(Set.copyOf(required));
    }
    public void requireSearchRoads() {
        for (String pair : requiredSearchRoads()) check(matrix.containsKey(pair) || unreachable.contains(pair), "missing required daily road: " + pair);
    }
    private static void validateAssignments(List<TechRoute> routes, List<PlanVisit> visits, boolean complete) {
        Map<String, PlanVisit> canonical = new HashMap<>(); Set<String> assigned = new HashSet<>();
        for (PlanVisit visit : visits) check(canonical.putIfAbsent(visit.getId(), visit) == null, "duplicate visit identity: " + visit.getId());
        Set<String> technicians = new HashSet<>();
        for (TechRoute route : routes) {
            check(technicians.add(route.getId()), "duplicate technician identity: " + route.getId());
            for (PlanVisit visit : route.getVisits()) {
                check(canonical.get(visit.getId()) == visit, "assignment does not reference canonical visit: " + visit.getId());
                check(assigned.add(visit.getId()), "duplicate assigned visit: " + visit.getId());
            }
        }
        if (complete) check(assigned.size() == visits.size(), "complete demand coverage is required");
    }
    public static void identity(String id, String field) {
        check(!id.isBlank() && !id.contains(">") && !id.endsWith(":return"), field + " is blank or uses reserved legacy endpoint syntax");
    }
    public static void roadKey(String key) {
        int split = key.indexOf('>'); check(split > 0 && split == key.lastIndexOf('>') && split < key.length() - 1, "invalid legacy directed road key: " + key);
    }
    public static void number(double value, String field) { check(Double.isFinite(value) && value >= 0, field + " must be finite and nonnegative"); }
    public static void check(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
