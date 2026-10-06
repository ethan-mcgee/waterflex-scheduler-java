package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.Monetary;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NonNull;

/** Request-local facts; scoring never loads scheduling data or road legs. */
public record RouteScoringFacts(PlanFacts facts, @Nullable Target target) {
    public RouteScoringFacts {
        PlanFacts.check(target == null || target.facts == facts, "target fact revision mismatch");
    }
    public static final class Target {
        private final PlanFacts facts;
        private final long overtimeMinutes, costCeilingCents;
        private Target(PlanFacts facts, long overtimeMinutes, long costCeilingCents) {
            this.facts = facts; this.overtimeMinutes = overtimeMinutes; this.costCeilingCents = costCeilingCents;
        }
        public long overtimeMinutes() { return overtimeMinutes; }
        public long costCeilingCents() { return costCeilingCents; }
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
            paid = Math.addExact(paid, route.paidMinutes()); overtime = Math.addExact(overtime, route.overtimeMinutes()); meters = Math.addExact(meters, route.meters());
        }
        return Monetary.cents(paid, overtime, meters, facts.regularHourly(), facts.overtimeHourly(), facts.mileagePerMile());
    }
    BigDecimal fairness(List<@NonNull ScoredRoute> routes) {
        List<SchedulingPolicy.Workload> work = new ArrayList<>();
        for (ScoredRoute route : routes) if (route.eligible() && route.capacity() > 0)
            work.add(new SchedulingPolicy.Workload(route.technicianId(), route.metrics().paidMinutes(), route.capacity(), Required.value(BigDecimal.ZERO)));
        return SchedulingPolicy.fairness(work).variance();
    }
    long overtimeDeviation(List<@NonNull ScoredRoute> routes) {
        Target fixed = target;
        if (fixed == null) return 0;
        long overtime = 0;
        for (ScoredRoute route : routes) overtime = Math.addExact(overtime, route.metrics().overtimeMinutes());
        return Math.abs(overtime - fixed.overtimeMinutes());
    }
    long costExcess(List<@NonNull ScoredRoute> routes) {
        Target fixed = target; return fixed == null ? 0 : Math.max(0, cost(routes) - fixed.costCeilingCents());
    }
    public RouteScoringFacts withTarget(DayPlan reference, long ceiling) {
        PlanFacts.check(reference.getFacts() == facts, "reference fact revision mismatch");
        facts.validateEntities(reference, true);
        var independent = RouteEvaluator.evaluate(reference);
        var scored = DayScoreCalculator.evaluate(reference);
        PlanFacts.check(independent.feasible() && scored.hardPenalty() == 0 && independent.costCents() == scored.costCents()
                && independent.arrivals().equals(scored.arrivals()), "fairness reference must be complete and independently valid");
        PlanFacts.check(ceiling >= independent.costCents() && ceiling <= Monetary.MAX_CENTS, "invalid fairness ceiling");
        return new RouteScoringFacts(facts, new Target(facts, independent.overtimeMinutes(), ceiling));
    }
}
