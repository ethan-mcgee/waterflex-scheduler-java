package dev.waterflex.scheduler;

import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.PlanVisit;
import dev.waterflex.scheduler.optimizer.RouteEvaluator;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import dev.waterflex.scheduler.optimizer.TechRoute;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.*;

/**
 * Immutable search facts. Creating mutable evaluator plans never mutates this snapshot. {@code horizon} is every date
 * the search covers, and {@code days} has exactly those dates. The scheduler's own calendar sets the horizon for the
 * portal path; a host request declares it explicitly.
 */
public record BookingSnapshot(String metroId, Instant capturedAt, Instant calendarReference, String configurationFingerprint,
        String routingIdentity, SchedulingPolicy.Rules policy, Rates rates, Map<LocalDate, Day> days, Set<LocalDate> horizon) {
    /** The most dates one search may cover; the remote dataset accepts no more. */
    public static final int MAX_HORIZON_DAYS = 32;

    public BookingSnapshot(String metroId, Instant capturedAt, String configurationFingerprint,
            String routingIdentity, SchedulingPolicy.Rules policy, Rates rates, Map<LocalDate, Day> days) {
        this(metroId, capturedAt, capturedAt, configurationFingerprint, routingIdentity, policy, rates, days);
    }
    /** A portal snapshot: its days must be the scheduler calendar's booking dates, with or without the overflow dates. */
    public BookingSnapshot(String metroId, Instant capturedAt, Instant calendarReference, String configurationFingerprint,
            String routingIdentity, SchedulingPolicy.Rules policy, Rates rates, Map<LocalDate, Day> days) {
        this(metroId, capturedAt, calendarReference, configurationFingerprint, routingIdentity, policy, rates, days, calendarHorizon(calendarReference, days));
    }
    public BookingSnapshot {
        text(metroId); text(configurationFingerprint); text(routingIdentity);
        days = Required.value(Map.copyOf(days));
        horizon = Required.value(Set.copyOf(horizon));
        if (horizon.isEmpty() || horizon.size() > MAX_HORIZON_DAYS) throw new IllegalArgumentException("A booking horizon covers 1 to " + MAX_HORIZON_DAYS + " dates");
        if (!days.keySet().equals(horizon)) throw new Incomplete("Snapshot does not cover the complete booking horizon");
    }

    private static Set<LocalDate> calendarHorizon(Instant calendarReference, Map<LocalDate, Day> days) {
        Set<LocalDate> normal = new HashSet<>(BookingCalendar.bookingDates(calendarReference));
        Set<LocalDate> extended = new HashSet<>(normal); extended.addAll(BookingCalendar.overflowDates(calendarReference));
        if (!days.keySet().equals(normal) && !days.keySet().equals(extended))
            throw new Incomplete("Snapshot does not cover the complete booking horizon");
        return Required.value(Set.copyOf(days.keySet()));
    }

    public record Rates(@JsonSerialize(using = DecimalStringSerializer.class) BigDecimal regularHourly,
                        @JsonSerialize(using = DecimalStringSerializer.class) BigDecimal overtimeHourly,
                        @JsonSerialize(using = DecimalStringSerializer.class) BigDecimal mileagePerMile,
                        @JsonSerialize(using = DecimalStringSerializer.class) BigDecimal travelBufferPct, long travelBufferMinutes) {
        public Rates(double regular, double overtime, double mileage, double pct, long minutes) {
            this(Monetary.legacy(regular), Monetary.legacy(overtime), Monetary.legacy(mileage), Monetary.legacy(pct), minutes);
        }
        public Rates {
            regularHourly = Monetary.rate(regularHourly); overtimeHourly = Monetary.rate(overtimeHourly);
            mileagePerMile = Monetary.rate(mileagePerMile); travelBufferPct = Monetary.rate(travelBufferPct);
            if (travelBufferMinutes < 0 || travelBufferMinutes > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid operating cost or travel settings");
        }
        public static Rates read(Map<String, BigDecimal> settings) {
            long minutes = Required.value(settings.get("travel_buffer_minutes_per_leg"), "travel buffer minutes").longValueExact();
            if (minutes < 0 || minutes > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid travel buffer minutes");
            return new Rates(Required.value(settings.get("regular_hourly_dollars"), "regular hourly rate"),
                    Required.value(settings.get("overtime_hourly_dollars"), "overtime hourly rate"),
                    Required.value(settings.get("mileage_dollars_per_mile"), "mileage rate"),
                    Required.value(settings.get("travel_buffer_pct"), "travel buffer percentage"), minutes);
        }
    }

    public record Technician(String id, Instant shiftStart, Instant shiftEnd, int maxDailyMinutes,
            int maxOvertimeMinutes, Set<String> services, List<TechRoute.Unavailable> absences,
            RoadPoint departure, RoadPoint returnTo, long scheduleVersion) {
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
            int durationMinutes, RoadPoint location, String originalTechnicianId,
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

    public static final class Arrangement {
        private final Map<String, List<String>> routes;
        private final Set<String> originalVisits;
        private final @org.jspecify.annotations.Nullable Arrangement parent;
        private final @org.jspecify.annotations.Nullable String insertedVisit;
        private final @org.jspecify.annotations.Nullable String insertedTechnician;
        private final int hash;
        private @org.jspecify.annotations.Nullable String signature;

        @com.fasterxml.jackson.annotation.JsonCreator
        public Arrangement(@com.fasterxml.jackson.annotation.JsonProperty("routes") Map<String, List<String>> routes) {
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
            this.routes = Required.value(Collections.unmodifiableMap(copy));
            originalVisits = Required.value(Set.copyOf(seen));
            parent = null; insertedVisit = null; insertedTechnician = null; hash = this.routes.hashCode();
        }
        private Arrangement(Arrangement parent, String technician, String visit, List<String> order) {
            Map<String, List<String>> copy = new TreeMap<>(parent.routes);
            List<String> previous = Required.value(copy.put(technician, order));
            routes = Required.value(Collections.unmodifiableMap(copy));
            originalVisits = parent.originalVisits; this.parent = parent; insertedVisit = visit;
            insertedTechnician = technician;
            hash = parent.hash - (technician.hashCode() ^ previous.hashCode()) + (technician.hashCode() ^ order.hashCode());
        }
        @com.fasterxml.jackson.annotation.JsonProperty("routes")
        public Map<String, List<String>> routes() { return routes; }
        @org.jspecify.annotations.Nullable String insertionTechnician(Arrangement original, String visit) {
            return parent == original && visit.equals(insertedVisit) ? insertedTechnician : null;
        }
        @org.jspecify.annotations.Nullable Arrangement insertionParent(String visit) {
            return visit.equals(insertedVisit) ? parent : null;
        }
        private boolean containsVisit(String visit) {
            for (Arrangement current = this; ; ) {
                if (visit.equals(current.insertedVisit)) return true;
                Arrangement previous = current.parent;
                if (previous == null) return current.originalVisits.contains(visit);
                current = previous;
            }
        }
        public Arrangement insert(String technician, String visit, int position) {
            text(technician); text(visit);
            if (containsVisit(visit)) throw new IllegalArgumentException("Visit assigned more than once");
            List<String> ids = new ArrayList<>(Required.value(routes.get(technician), "arrangement route"));
            ids.add(position, visit);
            return new Arrangement(this, technician, visit, Required.value(List.copyOf(ids)));
        }
        public String signature() {
            String cached = signature;
            if (cached == null) { cached = Required.value(routes.toString()); signature = cached; }
            return cached;
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(@org.jspecify.annotations.Nullable Object other) {
            return this == other || other instanceof Arrangement arrangement && hash == arrangement.hash && routes.equals(arrangement.routes);
        }
        @Override public String toString() { return "Arrangement[routes=" + signature() + "]"; }
    }

    /** Immutable one-visit overlay; the day already owns an immutable, validated facts map. */
    static final class InsertionFacts extends AbstractMap<String, Visit> {
        final Day day;
        final Visit visit;
        private @org.jspecify.annotations.Nullable Set<Map.Entry<String, Visit>> entries;
        InsertionFacts(Day day, Visit visit) {
            if (day.visits().containsKey(visit.id())) throw new Incomplete("Request already present in snapshot");
            this.day = day; this.visit = visit;
        }
        @Override public @org.jspecify.annotations.Nullable Visit get(@org.jspecify.annotations.Nullable Object key) {
            return visit.id().equals(key) ? visit : day.visits().get(key);
        }
        @Override public boolean containsKey(@org.jspecify.annotations.Nullable Object key) {
            return visit.id().equals(key) || day.visits().containsKey(key);
        }
        @Override public int size() { return day.visits().size() + 1; }
        @Override public Set<Map.Entry<String, Visit>> entrySet() {
            Set<Map.Entry<String, Visit>> cached = entries;
            if (cached == null) {
                Set<Map.Entry<String, Visit>> copy = new HashSet<>(day.visits().entrySet());
                copy.add(new SimpleImmutableEntry<>(visit.id(), visit));
                cached = Required.value(Set.copyOf(copy)); entries = cached;
            }
            return cached;
        }
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

        /** Persisted assignments, before any pending reservation rearrangement is applied. */
        public Arrangement actualArrangement() {
            Map<String, List<String>> routes = new TreeMap<>();
            technicians.keySet().forEach(id -> routes.put(id, new ArrayList<>()));
            List<Visit> ordered = new ArrayList<>(visits.values());
            ordered.sort(Comparator.comparing((Visit visit) -> visit.plannedStart()).thenComparing(visit -> visit.id()));
            for (Visit visit : ordered) Required.value(routes.get(visit.originalTechnicianId()), "persisted assignment technician").add(visit.id());
            return new Arrangement(routes);
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
