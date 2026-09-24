package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Request-local reuse of independently evaluated immutable route arrangements. No I/O. */
final class BookingEvaluation {
    private record Key(String technician, List<Visit> visits) { }
    private final Day day;
    private final Rates rates;
    private final String requestedService;
    private final Runnable checkpoint;
    private final BookingRouteBounds bounds;
    private final Map<String, Long> capacities = new HashMap<>();
    private final Map<String, RouteEvaluator.Result> emptyRoutes = new HashMap<>();
    private record Load(long paid, long capacity) { }
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
        if (!arrangement.routes().keySet().equals(day.technicians().keySet())) throw new Incomplete("Arrangement technician coverage changed");
        Set<String> coverage = new HashSet<>(); arrangement.routes().values().forEach(coverage::addAll);
        if (!coverage.equals(facts.keySet())) throw new Incomplete("Arrangement visit coverage changed");
        long paid = 0, overtime = 0, driving = 0, waiting = 0, meters = 0;
        boolean feasible = true;
        Map<String, Instant> arrivals = new HashMap<>();
        Map<String, List<RouteEvaluator.WorkingSegment>> segments = new TreeMap<>();
        for (var entry : arrangement.routes().entrySet()) {
            var evaluated = route(Required.value(entry.getKey()), Required.value(entry.getValue()), facts, confirmedOnly);
            feasible &= evaluated.feasible(); paid += evaluated.paidMinutes(); overtime += evaluated.overtimeMinutes();
            driving += evaluated.driveMinutes(); waiting += evaluated.waitingMinutes(); meters += evaluated.meters();
            arrivals.putAll(evaluated.arrivals()); segments.putAll(evaluated.segments());
        }
        long cents = Math.round((paid - overtime) * rates.regularHourly() * 100 / 60.0
                + overtime * rates.overtimeHourly() * 100 / 60.0 + meters / 1609.344 * rates.mileagePerMile() * 100);
        return new RouteEvaluator.Result(feasible, cents, Required.value(Map.copyOf(arrivals)), paid, overtime, driving, waiting, meters,
                Required.value(Map.copyOf(segments)));
    }

    BigDecimal fairness(Arrangement arrangement, Map<String, Visit> facts) {
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
}
