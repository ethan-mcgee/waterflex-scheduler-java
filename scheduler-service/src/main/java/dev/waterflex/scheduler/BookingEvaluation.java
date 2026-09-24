package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Request-local reuse of independently evaluated immutable route arrangements. No I/O. */
final class BookingEvaluation {
    record Metrics(boolean feasible, long costCents, long overtimeMinutes) { }
    private record Key(String technician, List<Visit> visits) { }
    private final Day day;
    private final Rates rates;
    private final String requestedService;
    private final Runnable checkpoint;
    private final BookingRouteBounds bounds;
    private final boolean reservations;
    private final Map<String, Long> capacities = new HashMap<>();
    private final Map<String, RouteEvaluator.Result> emptyRoutes = new HashMap<>();
    private record Baseline(RouteEvaluator.Result total, Map<String, RouteEvaluator.Result> parts, int infeasibleRoutes) { }
    private final Map<Boolean, Baseline> baselines = new HashMap<>();
    private record Load(long paid, long capacity) { }
    private @Nullable Map<String, Load> baselineLoads;
    private final Map<Map<Load, Integer>, BigDecimal> fairnessResults = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.@Nullable Entry<Map<Load, Integer>, BigDecimal> eldest) { return size() > 2048; }
    };
    private final Map<Key, RouteEvaluator.Result> routes = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.@Nullable Entry<Key, RouteEvaluator.Result> eldest) { return size() > 2048; }
    };
    private long evaluations, hits, pruned;

    BookingEvaluation(Day day, Rates rates, String requestedService, Runnable checkpoint) {
        this.day = day; this.rates = rates; this.requestedService = requestedService; this.checkpoint = checkpoint;
        this.bounds = new BookingRouteBounds(day, rates);
        this.reservations = day.visits().values().stream().anyMatch(visit -> visit.reservation());
        day.technicians().forEach((id, technician) -> capacities.put(id, technician.regularCapacity()));
    }
    long capacity(String technician) { return Required.value(capacities.get(technician), "regular capacity"); }
    long evaluations() { return evaluations; }
    long hits() { return hits; }
    long pruned() { return pruned; }
    boolean possibleInsertion(String technician, List<String> order, Visit visit, int position) {
        checkpoint.run();
        boolean possible = bounds.insertion(technician, order, day.visits(), visit, position);
        if (!possible) pruned++;
        return possible;
    }
    boolean possible(Arrangement arrangement, Map<String, Visit> facts) {
        for (var route : arrangement.routes().entrySet()) {
            checkpoint.run();
            if (!bounds.possible(Required.value(route.getKey()), Required.value(route.getValue()), facts)) { pruned++; return false; }
        }
        return true;
    }

    RouteEvaluator.Result route(String technician, List<String> order, Map<String, Visit> facts, boolean confirmedOnly) {
        checkpoint.run();
        if (order.isEmpty()) {
            RouteEvaluator.Result empty = emptyRoutes.get(technician);
            if (empty != null) { hits++; return empty; }
        }
        List<Visit> visits = new ArrayList<>();
        for (String id : order) {
            Visit visit = Required.value(facts.get(id), "evaluated visit");
            if (!confirmedOnly || !visit.reservation()) visits.add(visit);
        }
        Key key = new Key(technician, Required.value(List.copyOf(visits)));
        RouteEvaluator.Result cached = routes.get(key);
        if (cached != null) { hits++; return cached; }
        TechRoute route = Required.value(day.technicians().get(technician), "evaluated technician").route();
        String previous = technician;
        for (Visit visit : visits) {
            day.roads().require(previous, visit.id());
            day.roads().require(technician, visit.id());
            day.roads().require(visit.id(), technician + ":return");
            route.getVisits().add(visit.planned()); previous = visit.id();
        }
        var plan = new DayPlan(Required.value(List.<TechRoute>of(route)), route.getVisits(), day.roads().legs(),
                rates.regularHourly(), rates.overtimeHourly(), rates.mileagePerMile(), rates.travelBufferPct(), rates.travelBufferMinutes());
        RouteEvaluator.Result result = RouteEvaluator.evaluate(plan);
        if (order.isEmpty()) emptyRoutes.put(technician, result);
        evaluations++; routes.put(key, result); return result;
    }

    RouteEvaluator.Result evaluate(Arrangement arrangement, Map<String, Visit> facts, boolean confirmedOnly) {
        return aggregate(arrangement, facts, confirmedOnly, true);
    }

    /** Ranking needs scalar resources only. Timeline maps are materialized for retained candidates. */
    Metrics metrics(Arrangement arrangement, Map<String, Visit> facts, boolean confirmedOnly) {
        var measured = aggregate(arrangement, facts, confirmedOnly, false);
        return new Metrics(measured.feasible(), measured.costCents(), measured.overtimeMinutes());
    }

    boolean hasReservations() { return reservations; }

    private RouteEvaluator.Result aggregate(Arrangement arrangement, Map<String, Visit> facts, boolean confirmedOnly, boolean timeline) {
        coverage(arrangement, facts);
        Baseline baseline = baseline(confirmedOnly);
        if (arrangement.equals(day.baseline()) && facts.equals(day.visits())) return baseline.total();
        var total = baseline.total();
        long paid = total.paidMinutes(), overtime = total.overtimeMinutes(), driving = total.driveMinutes();
        long waiting = total.waitingMinutes(), meters = total.meters();
        int infeasible = baseline.infeasibleRoutes();
        Map<String, Instant> arrivals = timeline ? new HashMap<>(total.arrivals()) : new HashMap<>();
        Map<String, List<RouteEvaluator.WorkingSegment>> segments = timeline ? new TreeMap<>(total.segments()) : new TreeMap<>();
        List<RouteEvaluator.Result> changed = new ArrayList<>();
        String insertion = insertionTechnician(arrangement, facts);
        Map<String, List<String>> affected = insertion == null ? arrangement.routes()
                : Required.value(Map.of(insertion, Required.value(arrangement.routes().get(insertion))));
        for (var entry : affected.entrySet()) {
            checkpoint.run();
            String technician = Required.value(entry.getKey());
            List<String> order = Required.value(entry.getValue());
            List<String> original = Required.value(day.baseline().routes().get(technician));
            if (insertion == null && order.equals(original) && order.stream().allMatch(id -> Objects.equals(facts.get(id), day.visits().get(id)))) continue;
            var before = Required.value(baseline.parts().get(technician));
            var after = route(technician, order, facts, confirmedOnly);
            changed.add(after);
            paid += after.paidMinutes() - before.paidMinutes(); overtime += after.overtimeMinutes() - before.overtimeMinutes();
            driving += after.driveMinutes() - before.driveMinutes(); waiting += after.waitingMinutes() - before.waitingMinutes();
            meters += after.meters() - before.meters();
            infeasible += (after.feasible() ? 0 : 1) - (before.feasible() ? 0 : 1);
            if (timeline) {
                before.arrivals().keySet().forEach(arrivals::remove);
                segments.putAll(after.segments());
            }
        }
        // Add arrivals after every changed route's old visits have been removed; reassignment may
        // move a visit from a route that sorts later to one that sorts earlier.
        if (timeline) changed.forEach(route -> arrivals.putAll(route.arrivals()));
        return result(infeasible == 0, paid, overtime, driving, waiting, meters, arrivals, segments);
    }

    private Baseline baseline(boolean confirmedOnly) {
        Baseline cached = baselines.get(confirmedOnly);
        if (cached != null) return cached;
        Map<String, RouteEvaluator.Result> parts = new TreeMap<>();
        long paid = 0, overtime = 0, driving = 0, waiting = 0, meters = 0; int infeasible = 0;
        Map<String, Instant> arrivals = new HashMap<>();
        Map<String, List<RouteEvaluator.WorkingSegment>> segments = new TreeMap<>();
        for (var entry : day.baseline().routes().entrySet()) {
            var measured = route(Required.value(entry.getKey()), Required.value(entry.getValue()), day.visits(), confirmedOnly);
            parts.put(entry.getKey(), measured);
            if (!measured.feasible()) infeasible++;
            paid += measured.paidMinutes(); overtime += measured.overtimeMinutes(); driving += measured.driveMinutes();
            waiting += measured.waitingMinutes(); meters += measured.meters(); arrivals.putAll(measured.arrivals()); segments.putAll(measured.segments());
        }
        var baseline = new Baseline(result(infeasible == 0, paid, overtime, driving, waiting, meters, arrivals, segments), Required.value(Map.copyOf(parts)), infeasible);
        baselines.put(confirmedOnly, baseline); return baseline;
    }

    private void coverage(Arrangement arrangement, Map<String, Visit> facts) {
        // The private structural insertion constructor preserves exactly the validated parent
        // routes and adds the overlay's one new visit. Other arrangements still require a scan.
        if (insertionTechnician(arrangement, facts) != null) return;
        if (!arrangement.routes().keySet().equals(day.technicians().keySet())) throw new Incomplete("Arrangement technician coverage changed");
        Set<String> coverage = new HashSet<>(); arrangement.routes().values().forEach(coverage::addAll);
        if (!coverage.equals(facts.keySet())) throw new Incomplete("Arrangement visit coverage changed");
    }

    private @Nullable String insertionTechnician(Arrangement arrangement, Map<String, Visit> facts) {
        return facts instanceof InsertionFacts inserted && inserted.day == day
                ? arrangement.insertionTechnician(day.baseline(), inserted.visit.id()) : null;
    }

    private RouteEvaluator.Result result(boolean feasible, long paid, long overtime, long driving, long waiting, long meters,
            Map<String, Instant> arrivals, Map<String, List<RouteEvaluator.WorkingSegment>> segments) {
        long cents = Math.round((paid - overtime) * rates.regularHourly() * 100 / 60.0
                + overtime * rates.overtimeHourly() * 100 / 60.0 + meters / 1609.344 * rates.mileagePerMile() * 100);
        return new RouteEvaluator.Result(feasible, cents, Required.value(Map.copyOf(arrivals)), paid, overtime, driving, waiting, meters,
                Required.value(Map.copyOf(segments)));
    }

    BigDecimal fairness(Arrangement arrangement, Map<String, Visit> facts) {
        String insertion = insertionTechnician(arrangement, facts);
        if (insertion != null && facts instanceof InsertionFacts inserted && inserted.visit.serviceId().equals(requestedService)) {
            Map<String, Load> loads = new HashMap<>(baselineLoads());
            var measured = route(insertion, Required.value(arrangement.routes().get(insertion)), facts, true);
            if (!measured.feasible()) throw new Incomplete("Confirmed workload cannot be evaluated");
            if (loads.containsKey(insertion)) loads.put(insertion, new Load(measured.paidMinutes(), capacity(insertion)));
            Map<Load, Integer> histogram = new HashMap<>();
            loads.values().forEach(load -> histogram.merge(load, 1, (a, b) -> Required.value(a) + Required.value(b)));
            BigDecimal cached = fairnessResults.get(histogram);
            if (cached != null) return cached;
            List<SchedulingPolicy.Workload> workloads = new ArrayList<>();
            loads.forEach((id, load) -> workloads.add(new SchedulingPolicy.Workload(Required.value(id), load.paid(), load.capacity(), Required.value(BigDecimal.ZERO))));
            BigDecimal variance = SchedulingPolicy.fairness(workloads).variance();
            fairnessResults.put(Required.value(Map.copyOf(histogram)), variance);
            return variance;
        }
        Set<String> services = new HashSet<>(); services.add(requestedService);
        facts.values().stream().filter(visit -> !visit.reservation()).forEach(visit -> services.add(visit.serviceId()));
        List<SchedulingPolicy.Workload> workloads = new ArrayList<>();
        Map<Load, Integer> histogram = new HashMap<>();
        for (var entry : arrangement.routes().entrySet()) {
            Technician technician = Required.value(day.technicians().get(entry.getKey()));
            long capacity = capacity(technician.id());
            if (capacity == 0 || Collections.disjoint(services, technician.services())) continue;
            var measured = route(technician.id(), Required.value(entry.getValue()), facts, true);
            if (!measured.feasible()) throw new Incomplete("Confirmed workload cannot be evaluated");
            workloads.add(new SchedulingPolicy.Workload(technician.id(), measured.paidMinutes(), capacity, Required.value(BigDecimal.ZERO)));
            histogram.merge(new Load(measured.paidMinutes(), capacity), 1, (a, b) -> Required.value(a) + Required.value(b));
        }
        BigDecimal cached = fairnessResults.get(histogram);
        if (cached != null) return cached;
        BigDecimal variance = SchedulingPolicy.fairness(workloads).variance();
        fairnessResults.put(Required.value(Map.copyOf(histogram)), variance);
        return variance;
    }

    private Map<String, Load> baselineLoads() {
        Map<String, Load> cached = baselineLoads;
        if (cached != null) return cached;
        Set<String> services = new HashSet<>(); services.add(requestedService);
        day.visits().values().stream().filter(visit -> !visit.reservation()).forEach(visit -> services.add(visit.serviceId()));
        Map<String, Load> loads = new HashMap<>();
        Baseline confirmed = baseline(true);
        for (Technician technician : day.technicians().values()) {
            long capacity = capacity(technician.id());
            if (capacity == 0 || Collections.disjoint(services, technician.services())) continue;
            var measured = Required.value(confirmed.parts().get(technician.id()));
            if (!measured.feasible()) throw new Incomplete("Confirmed workload cannot be evaluated");
            loads.put(technician.id(), new Load(measured.paidMinutes(), capacity));
        }
        cached = Required.value(Map.copyOf(loads)); baselineLoads = cached; return cached;
    }
}
