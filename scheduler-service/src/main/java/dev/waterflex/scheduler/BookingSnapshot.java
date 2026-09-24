package dev.waterflex.scheduler;

import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.PlanVisit;
import dev.waterflex.scheduler.optimizer.RouteEvaluator;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import dev.waterflex.scheduler.optimizer.TechRoute;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Immutable search facts. Creating mutable evaluator plans never mutates this snapshot. */
public record BookingSnapshot(String metroId, Instant capturedAt, String configurationFingerprint,
        String routingIdentity, SchedulingPolicy.Rules policy, Rates rates, Map<LocalDate, Day> days) {
    public BookingSnapshot {
        text(metroId); text(configurationFingerprint); text(routingIdentity);
        days = Required.value(Map.copyOf(days));
        if (!days.keySet().equals(new HashSet<>(BookingService.bookingDates(capturedAt))))
            throw new Incomplete("Snapshot does not cover the complete booking horizon");
    }

    public record Rates(double regularHourly, double overtimeHourly, double mileagePerMile,
                        double travelBufferPct, long travelBufferMinutes) {
        public Rates {
            if (!Double.isFinite(regularHourly) || regularHourly < 0 || !Double.isFinite(overtimeHourly)
                    || overtimeHourly < 0 || !Double.isFinite(mileagePerMile) || mileagePerMile < 0
                    || !Double.isFinite(travelBufferPct) || travelBufferPct < 0 || travelBufferMinutes < 0)
                throw new IllegalArgumentException("Invalid operating cost or travel settings");
        }
        public static Rates read(Map<String, Double> settings) {
            double minutes = Required.value(settings.get("travel_buffer_minutes_per_leg"), "travel buffer minutes");
            if (!Double.isFinite(minutes) || minutes < 0 || minutes != Math.rint(minutes) || minutes > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid travel buffer minutes");
            return new Rates(Required.value(settings.get("regular_hourly_dollars"), "regular hourly rate"),
                    Required.value(settings.get("overtime_hourly_dollars"), "overtime hourly rate"),
                    Required.value(settings.get("mileage_dollars_per_mile"), "mileage rate"),
                    Required.value(settings.get("travel_buffer_pct"), "travel buffer percentage"), (long) minutes);
        }
    }

    public record Technician(String id, Instant shiftStart, Instant shiftEnd, int maxDailyMinutes,
            int maxOvertimeMinutes, Set<String> services, List<TechRoute.Unavailable> absences,
            RoadClient.Point departure, RoadClient.Point returnTo, long scheduleVersion) {
        public Technician {
            text(id);
            if (!shiftStart.isBefore(shiftEnd) || maxDailyMinutes <= 0 || maxOvertimeMinutes < 0 || scheduleVersion < 0)
                throw new IllegalArgumentException("Invalid technician scheduling facts");
            services = Required.value(Set.copyOf(services));
            absences = Required.value(List.copyOf(absences));
            for (TechRoute.Unavailable absence : absences) if (!absence.start().isBefore(absence.end()))
                throw new IllegalArgumentException("Invalid approved absence");
        }
        TechRoute route() {
            TechRoute route = new TechRoute(id, shiftStart, shiftEnd, maxDailyMinutes, maxOvertimeMinutes, services);
            route.setUnavailable(new ArrayList<>(absences));
            return route;
        }
        public long regularCapacity() { return SchedulingPolicy.regularCapacity(route()); }
    }

    public record Visit(String id, String jobId, String serviceId, Instant windowStart, Instant windowEnd,
            int durationMinutes, RoadClient.Point location, String originalTechnicianId,
            Instant plannedStart, boolean reservation) {
        public Visit {
            text(id); text(jobId); text(serviceId); text(originalTechnicianId);
            if (!windowStart.isBefore(windowEnd) || durationMinutes <= 0)
                throw new IllegalArgumentException("Invalid appointment promise");
        }
        PlanVisit planned() { return new PlanVisit(id, serviceId, windowStart, windowEnd, durationMinutes, originalTechnicianId, plannedStart); }
    }

    /** Explicit unreachable pairs differ from pairs not loaded into the snapshot yet. */
    public record Roads(Map<String, DayPlan.RoadLeg> legs, Set<String> unreachable) {
        public Roads {
            legs = Required.value(Map.copyOf(legs));
            unreachable = Required.value(Set.copyOf(unreachable));
            if (!Collections.disjoint(legs.keySet(), unreachable)) throw new IllegalArgumentException("Contradictory road pair");
            for (DayPlan.RoadLeg leg : legs.values()) if (leg.seconds() < 0 || leg.meters() < 0)
                throw new IllegalArgumentException("Malformed road pair");
        }
        void require(String from, String to) {
            String pair = from + ">" + to;
            if (!legs.containsKey(pair) && !unreachable.contains(pair))
                throw new Incomplete("Directed road pair missing: " + pair);
        }
    }

    public record Arrangement(Map<String, List<String>> routes) {
        public Arrangement {
            Map<String, List<String>> copy = new TreeMap<>();
            Set<String> seen = new HashSet<>();
            for (var entry : routes.entrySet()) {
                text(Required.value(entry.getKey()));
                List<String> ids = Required.value(List.copyOf(entry.getValue()));
                for (String id : ids) {
                    text(Required.value(id));
                    if (!seen.add(id)) throw new IllegalArgumentException("Visit assigned more than once");
                }
                copy.put(entry.getKey(), ids);
            }
            routes = Required.value(Collections.unmodifiableMap(copy));
        }
        public Arrangement insert(String technician, String visit, int position) {
            Map<String, List<String>> copy = new TreeMap<>(routes);
            List<String> ids = new ArrayList<>(Required.value(routes.get(technician), "arrangement route"));
            ids.add(position, visit);
            copy.put(technician, ids);
            return new Arrangement(copy);
        }
        public String signature() { return Required.value(routes.toString()); }
    }

    public record Day(Map<String, Technician> technicians, Map<String, Visit> visits,
                      Arrangement baseline, long reservationVersion, Roads roads) {
        public Day {
            technicians = Required.value(Map.copyOf(technicians));
            visits = Required.value(Map.copyOf(visits));
            if (reservationVersion < 0 || !technicians.keySet().equals(baseline.routes().keySet()))
                throw new IllegalArgumentException("Incomplete reservation arrangement");
            Set<String> assigned = new HashSet<>();
            baseline.routes().values().forEach(assigned::addAll);
            if (!assigned.equals(visits.keySet())) throw new IllegalArgumentException("Incomplete reservation coverage");
            for (var entry : technicians.entrySet()) if (!entry.getKey().equals(entry.getValue().id()))
                throw new IllegalArgumentException("Technician key mismatch");
            for (var entry : visits.entrySet()) if (!entry.getKey().equals(entry.getValue().id()))
                throw new IllegalArgumentException("Visit key mismatch");
        }

        public DayPlan plan(Arrangement arrangement, Map<String, Visit> facts, Rates rates, boolean confirmedOnly) {
            if (!arrangement.routes().keySet().equals(technicians.keySet()))
                throw new Incomplete("Arrangement technician coverage changed");
            Set<String> assigned = new HashSet<>();
            arrangement.routes().values().forEach(assigned::addAll);
            if (!assigned.equals(facts.keySet())) throw new Incomplete("Arrangement visit coverage changed");
            List<TechRoute> routes = new ArrayList<>();
            List<PlanVisit> all = new ArrayList<>();
            for (var entry : arrangement.routes().entrySet()) {
                TechRoute route = Required.value(technicians.get(entry.getKey()), "arrangement technician").route();
                String previous = route.getId();
                for (String id : entry.getValue()) {
                    Visit fact = Required.value(facts.get(id), "arrangement visit");
                    if (confirmedOnly && fact.reservation()) continue;
                    roads.require(previous, fact.id());
                    // Absence boundaries can require a depot departure or return at any stop.
                    roads.require(route.getId(), fact.id());
                    roads.require(fact.id(), route.getId() + ":return");
                    PlanVisit visit = fact.planned();
                    route.getVisits().add(visit);
                    all.add(visit);
                    previous = fact.id();
                }
                routes.add(route);
            }
            return new DayPlan(routes, all, roads.legs(), rates.regularHourly(), rates.overtimeHourly(),
                    rates.mileagePerMile(), rates.travelBufferPct(), rates.travelBufferMinutes());
        }
        public RouteEvaluator.Result evaluate(Arrangement arrangement, Map<String, Visit> facts, Rates rates) {
            return RouteEvaluator.evaluate(plan(arrangement, facts, rates, false));
        }
    }

    public static final class Incomplete extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Incomplete(String message) { super(message); }
    }
    private static void text(String value) {
        if (value.isBlank()) throw new IllegalArgumentException("Blank scheduling identifier");
    }
}
