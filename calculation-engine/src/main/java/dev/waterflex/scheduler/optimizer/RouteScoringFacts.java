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
    /** Scores one route against the revision: minutes, meters and violations, without arrivals. */
    ScoredRoute evaluate(TechRoute route) {
        var metrics = RouteTimeline.evaluate(facts, route, false);
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
    /** Units of variance per one on the long fairness level: the variance is carried to nine decimal places. */
    static final long VARIANCE_UNITS = 1_000_000_000L;
    /**
     * {@link #fairness} in {@link #VARIANCE_UNITS}, rounded half up, for the long score (treatment S). The same
     * workloads and the same formula as {@link SchedulingPolicy#fairness}, in binary64 instead of DECIMAL128: the two
     * agree to well under one unit, and rounding is monotone, so the long level can tie two plans whose variances
     * differ by less than one unit but never orders them the other way round beyond that.
     */
    long varianceUnits(List<@NonNull ScoredRoute> routes) {
        long capacity = 0, paid = 0;
        for (ScoredRoute route : routes) if (route.eligible() && route.capacity() > 0) {
            PlanFacts.check(route.metrics().paidMinutes() >= 0, "Invalid workload");
            capacity = Math.addExact(capacity, route.capacity()); paid = Math.addExact(paid, route.metrics().paidMinutes());
        }
        if (capacity == 0) return 0;
        double mean = (double) paid / capacity, sum = 0;
        for (ScoredRoute route : routes) if (route.eligible() && route.capacity() > 0) {
            double difference = (double) route.metrics().paidMinutes() / route.capacity() - mean;
            sum += difference * difference * route.capacity();
        }
        double units = sum / capacity * VARIANCE_UNITS;
        // A utilization is at most a day's paid minutes over one minute of capacity, so this bound is never near.
        if (!(units >= 0 && units < 0x1p62)) throw new ArithmeticException("Fairness variance outside the long score range");
        return Math.round(units);
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
