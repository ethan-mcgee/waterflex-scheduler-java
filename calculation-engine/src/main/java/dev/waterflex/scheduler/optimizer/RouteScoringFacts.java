package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Monetary;
import java.util.List;
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
        var scoring = route.scoring(facts);
        return new ScoredRoute(route.getId(), scoring.capacity(), scoring.eligible(), metrics);
    }
    long cost(List<@NonNull ScoredRoute> routes) {
        long paid = 0, overtime = 0, meters = 0;
        for (ScoredRoute item : routes) {
            RouteTimeline.Result route = item.metrics();
            paid = Math.addExact(paid, route.paidMinutes()); overtime = Math.addExact(overtime, route.overtimeMinutes()); meters = Math.addExact(meters, route.meters());
        }
        return Monetary.cents(paid, overtime, meters, facts.rates());
    }
    /** Units of the fairness objective per one of variance: the numeric contract's 1e-9 resolution (docs/scheduler-policy.md). */
    static final long FAIRNESS_UNITS = 1_000_000_000L;
    /** A technician the variance counts: qualified for the day's demand, with regular capacity. */
    static boolean counted(ScoredRoute route) { return route.eligible() && route.capacity() > 0; }
    static long paid(ScoredRoute route) {
        long paid = route.metrics().paidMinutes();
        PlanFacts.check(paid >= 0, "Invalid workload");
        return paid;
    }
    /**
     * The fairness phase's objective: the capacity-weighted workload variance of {@link SchedulingPolicy#fairness},
     * computed in binary64 over the counted technicians and carried as {@code round(variance * 1e9)} (option b of the
     * fairness measure study). The sums are exact longs; the one rounding happens here, so moves never accumulate
     * binary error, as Timefold's guidance on scaled scores asks (docs/timefold-documentation, 12.1.10 and 15.2.1).
     * Acceptance still reads the DECIMAL128 variance ({@link SchedulingPolicy#measure}).
     */
    long fairnessUnits(List<@NonNull ScoredRoute> routes) {
        long capacity = 0, paid = 0;
        for (ScoredRoute route : routes) if (counted(route)) { capacity = Math.addExact(capacity, route.capacity()); paid = Math.addExact(paid, paid(route)); }
        if (capacity == 0) return 0;
        double mean = (double) paid / capacity, sum = 0;
        for (ScoredRoute route : routes) if (counted(route)) {
            double difference = (double) paid(route) / route.capacity() - mean;
            sum += difference * difference * route.capacity();
        }
        double units = sum / capacity * FAIRNESS_UNITS;
        // A variance is at least zero and far below 2^62 units for any fleet the API admits; outside that, refuse rather than wrap.
        if (!(units > -0.5 && units < 0x1p62)) throw new ArithmeticException("Fairness variance outside the long range");
        return Math.max(0, Math.round(units));
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
