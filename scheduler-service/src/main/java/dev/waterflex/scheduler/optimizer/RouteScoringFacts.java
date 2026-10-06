package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NonNull;

/** Request-local facts; scoring never loads scheduling data or road legs. */
public record RouteScoringFacts(PlanFacts facts, @Nullable Target target) {
    public record Target(long overtimeMinutes, long costCeilingCents) {
        public Target { PlanFacts.check(overtimeMinutes >= 0 && costCeilingCents >= 0, "invalid fairness target"); }
    }
    record ScoredRoute(String technicianId, long capacity, boolean eligible, RouteTimeline.Result metrics) { }
    ScoredRoute evaluate(TechRoute route) {
        var metrics = RouteTimeline.evaluate(new DayPlan(Required.value(List.<TechRoute>of(route)), route.getVisits(), this), route);
        return new ScoredRoute(route.getId(), SchedulingPolicy.regularCapacity(route),
                !Collections.disjoint(facts.demandServices(), route.getQualifiedServiceIds()), metrics);
    }
    long cost(List<@NonNull ScoredRoute> routes) {
        long paid = 0, overtime = 0, meters = 0;
        for (ScoredRoute item : routes) {
            RouteTimeline.Result route = item.metrics();
            paid += route.paidMinutes(); overtime += route.overtimeMinutes(); meters += route.meters();
        }
        return Math.round((paid - overtime) * facts.regularHourly() * 100 / 60.0
                + overtime * facts.overtimeHourly() * 100 / 60.0 + meters / 1609.344 * facts.mileagePerMile() * 100);
    }
    BigDecimal fairness(List<@NonNull ScoredRoute> routes) {
        List<SchedulingPolicy.Workload> work = new ArrayList<>();
        for (ScoredRoute route : routes) if (route.eligible() && route.capacity() > 0)
            work.add(new SchedulingPolicy.Workload(route.technicianId(), route.metrics().paidMinutes(), route.capacity(), Required.value(BigDecimal.ZERO)));
        return SchedulingPolicy.fairness(work).variance();
    }
    long targetViolation(List<@NonNull ScoredRoute> routes) {
        Target fixed = target;
        if (fixed == null) return 0;
        long overtime = 0;
        for (ScoredRoute route : routes) overtime += route.metrics().overtimeMinutes();
        return Math.abs(overtime - fixed.overtimeMinutes()) + Math.max(0, cost(routes) - fixed.costCeilingCents());
    }
    public RouteScoringFacts withTarget(Target fixed) {
        PlanFacts.check(fixed.overtimeMinutes() >= 0 && fixed.costCeilingCents() >= 0, "invalid fairness target");
        return new RouteScoringFacts(facts, fixed);
    }
}
