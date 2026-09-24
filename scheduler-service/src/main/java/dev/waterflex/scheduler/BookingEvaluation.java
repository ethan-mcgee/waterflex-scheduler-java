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
    private final Map<String, Long> capacities = new HashMap<>();
    private final Map<Key, RouteEvaluator.Result> routes = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.@Nullable Entry<Key, RouteEvaluator.Result> eldest) { return size() > 2048; }
    };
    private long evaluations, hits;

    BookingEvaluation(Day day, Rates rates, String requestedService, Runnable checkpoint) {
        this.day = day; this.rates = rates; this.requestedService = requestedService; this.checkpoint = checkpoint;
        day.technicians().forEach((id, technician) -> capacities.put(id, technician.regularCapacity()));
    }
    long capacity(String technician) { return Required.value(capacities.get(technician), "regular capacity"); }
    long evaluations() { return evaluations; }
    long hits() { return hits; }

    RouteEvaluator.Result route(String technician, List<String> order, Map<String, Visit> facts, boolean confirmedOnly) {
        checkpoint.run();
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
        for (var entry : arrangement.routes().entrySet()) {
            Technician technician = Required.value(day.technicians().get(entry.getKey()));
            long capacity = capacity(technician.id());
            if (capacity == 0 || Collections.disjoint(services, technician.services())) continue;
            var measured = route(technician.id(), Required.value(entry.getValue()), facts, true);
            if (!measured.feasible()) throw new Incomplete("Confirmed workload cannot be evaluated");
            workloads.add(new SchedulingPolicy.Workload(technician.id(), measured.paidMinutes(), capacity, Required.value(BigDecimal.ZERO)));
        }
        return SchedulingPolicy.fairness(workloads).variance();
    }
}
