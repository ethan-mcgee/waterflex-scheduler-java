package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.bi.BiConstraintStream;
import ai.timefold.solver.core.api.score.stream.uni.UniConstraintStream;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * The fairness measure study (PR 187), experiment-only: alternative objectives for the fairness phase, each a
 * {@link DayConstraintProvider} that replaces only {@link DayConstraintProvider#fairness}, with every other level the
 * production one. They count the technicians the production variance counts (qualified for the day's demand, with
 * regular capacity) and carry their value at the soft phase-objective level in units of 1e-9, the resolution of the
 * numeric contract (docs/scheduler-policy.md), except Timefold's load balance, which carries its own unfairness.
 */
public final class FairnessMeasures {
    /** Units per one of every measure carried here. */
    static final long UNITS = 1_000_000_000L;
    /** The scale of the exact sum of squared paid minutes over capacity behind the incremental variance. */
    static final long SQUARE_SCALE = 100_000_000L;
    /** Load balance runs on utilization in millionths: its squared deviations must stay well inside a long. */
    static final long LOAD_UNITS = 1_000_000L;
    private FairnessMeasures() { }

    static boolean counted(RouteScoringFacts.ScoredRoute route) { return route.eligible() && route.capacity() > 0; }
    static long paid(RouteScoringFacts.ScoredRoute route) {
        long paid = route.metrics().paidMinutes();
        PlanFacts.check(paid >= 0, "Invalid workload");
        return paid;
    }
    /** Paid minutes over regular capacity in {@code scale} units per one, rounded half up, in exact integer arithmetic. */
    static long utilization(RouteScoringFacts.ScoredRoute route, long scale) {
        long twice = Math.multiplyExact(route.capacity(), 2);
        return Math.floorDiv(Math.addExact(Math.multiplyExact(Math.multiplyExact(paid(route), scale), 2), route.capacity()), twice);
    }
    static BigDecimal units(long value) { return Required.value(BigDecimal.valueOf(value, 9)); }
    private static long round(double units) {
        // Every measure is at least zero; the running-sum variance can land a hair below it from the final subtraction.
        if (!(units > -0.5 && units < 0x1p62)) throw new ArithmeticException("Fairness measure outside the long range");
        return Math.max(0, Math.round(units));
    }

    /** Option (b): the production capacity-weighted variance in binary64, as {@link RouteScoringFacts#fairness} computes it in DECIMAL128. */
    static long varianceUnits(List<RouteScoringFacts.@NonNull ScoredRoute> routes) {
        long capacity = 0, paid = 0;
        for (var route : routes) if (counted(route)) { capacity = Math.addExact(capacity, route.capacity()); paid = Math.addExact(paid, paid(route)); }
        if (capacity == 0) return 0;
        double mean = (double) paid / capacity, sum = 0;
        for (var route : routes) if (counted(route)) {
            double difference = (double) paid(route) / route.capacity() - mean;
            sum += difference * difference * route.capacity();
        }
        return round(sum / capacity * UNITS);
    }
    /** {@code paid^2 / capacity} scaled by {@link #SQUARE_SCALE}, floored: one technician's share of the exact running sum. */
    static long square(RouteScoringFacts.ScoredRoute route) {
        long paid = paid(route);
        return Math.multiplyExact(Math.multiplyExact(paid, paid), SQUARE_SCALE) / route.capacity();
    }
    /**
     * Option (c): the same variance from three running sums the solver keeps per move, {@code (sum p^2/c)/C - (P/C)^2}.
     * The sums are exact longs, so adding and removing a route leaves them as they were; only the final difference is binary64.
     */
    static long varianceUnits(long capacity, long paid, long squares) {
        if (capacity == 0) return 0;
        double mean = (double) paid / capacity;
        return round(((double) squares / SQUARE_SCALE / capacity - mean * mean) * UNITS);
    }
    /** The capacity-weighted mean absolute deviation of utilization from the fleet's mean, in binary64. */
    static long absoluteDeviationUnits(List<RouteScoringFacts.@NonNull ScoredRoute> routes) {
        long capacity = 0, paid = 0;
        for (var route : routes) if (counted(route)) { capacity = Math.addExact(capacity, route.capacity()); paid = Math.addExact(paid, paid(route)); }
        if (capacity == 0) return 0;
        double mean = (double) paid / capacity, sum = 0;
        for (var route : routes) if (counted(route)) sum += Math.abs((double) paid(route) / route.capacity() - mean) * route.capacity();
        return round(sum / capacity * UNITS);
    }

    /** (b) The production variance, computed in binary64 over the whole fleet. */
    public static final class BinaryVariance extends DayConstraintProvider {
        @Override protected Constraint fairness(UniConstraintStream<RouteScoringFacts.@NonNull ScoredRoute> routes,
                BiConstraintStream<List<RouteScoringFacts.@NonNull ScoredRoute>, RouteScoringFacts> fleet) {
            return fleet.filter((_, facts) -> facts.target() != null)
                    .penalizeBigDecimal(DayScores.soft(DayScores.PHASE_OBJECTIVE), (results, _) -> units(varianceUnits(Required.value(results))))
                    .asConstraint("Capacity weighted workload variance (binary64)");
        }
    }
    /** (c) The production variance from running sums, updated by the routes a move changes. */
    public static final class IncrementalVariance extends DayConstraintProvider {
        @Override protected Constraint fairness(UniConstraintStream<RouteScoringFacts.@NonNull ScoredRoute> routes,
                BiConstraintStream<List<RouteScoringFacts.@NonNull ScoredRoute>, RouteScoringFacts> fleet) {
            return routes.filter(FairnessMeasures::counted)
                    .groupBy(ConstraintCollectors.sum(route -> route.capacity()), ConstraintCollectors.sum(FairnessMeasures::paid),
                            ConstraintCollectors.sum(FairnessMeasures::square))
                    .join(RouteScoringFacts.class).filter((_, _, _, facts) -> facts.target() != null)
                    .penalizeBigDecimal(DayScores.soft(DayScores.PHASE_OBJECTIVE),
                            (capacity, paid, squares, _) -> units(varianceUnits(Required.value(capacity), Required.value(paid), Required.value(squares))))
                    .asConstraint("Capacity weighted workload variance (running sums)");
        }
    }
    /** The highest technician utilization. */
    public static final class MinMaxUtilization extends DayConstraintProvider {
        @Override protected Constraint fairness(UniConstraintStream<RouteScoringFacts.@NonNull ScoredRoute> routes,
                BiConstraintStream<List<RouteScoringFacts.@NonNull ScoredRoute>, RouteScoringFacts> fleet) {
            return routes.filter(FairnessMeasures::counted).groupBy(ConstraintCollectors.max((RouteScoringFacts.ScoredRoute route) -> utilization(route, UNITS)))
                    .join(RouteScoringFacts.class).filter((_, facts) -> facts.target() != null)
                    .penalizeBigDecimal(DayScores.soft(DayScores.PHASE_OBJECTIVE), (maximum, _) -> units(Required.value(maximum)))
                    .asConstraint("Highest technician utilization");
        }
    }
    /** The gap between the most and the least utilized technicians. */
    public static final class UtilizationSpread extends DayConstraintProvider {
        @Override protected Constraint fairness(UniConstraintStream<RouteScoringFacts.@NonNull ScoredRoute> routes,
                BiConstraintStream<List<RouteScoringFacts.@NonNull ScoredRoute>, RouteScoringFacts> fleet) {
            return routes.filter(FairnessMeasures::counted)
                    .groupBy(ConstraintCollectors.max((RouteScoringFacts.ScoredRoute route) -> utilization(route, UNITS)),
                            ConstraintCollectors.min((RouteScoringFacts.ScoredRoute route) -> utilization(route, UNITS)))
                    .join(RouteScoringFacts.class).filter((_, _, facts) -> facts.target() != null)
                    .penalizeBigDecimal(DayScores.soft(DayScores.PHASE_OBJECTIVE),
                            (maximum, minimum, _) -> units(Math.subtractExact(Required.value(maximum), Required.value(minimum))))
                    .asConstraint("Utilization spread");
        }
    }
    /** The capacity-weighted mean absolute deviation of utilization. */
    public static final class AbsoluteDeviation extends DayConstraintProvider {
        @Override protected Constraint fairness(UniConstraintStream<RouteScoringFacts.@NonNull ScoredRoute> routes,
                BiConstraintStream<List<RouteScoringFacts.@NonNull ScoredRoute>, RouteScoringFacts> fleet) {
            return fleet.filter((_, facts) -> facts.target() != null)
                    .penalizeBigDecimal(DayScores.soft(DayScores.PHASE_OBJECTIVE), (results, _) -> units(absoluteDeviationUnits(Required.value(results))))
                    .asConstraint("Capacity weighted absolute utilization deviation");
        }
    }
    /** Timefold's load balance over each technician's utilization, unweighted by capacity. */
    public static final class LoadBalance extends DayConstraintProvider {
        @Override protected Constraint fairness(UniConstraintStream<RouteScoringFacts.@NonNull ScoredRoute> routes,
                BiConstraintStream<List<RouteScoringFacts.@NonNull ScoredRoute>, RouteScoringFacts> fleet) {
            return routes.filter(FairnessMeasures::counted)
                    .groupBy(ConstraintCollectors.loadBalance((RouteScoringFacts.ScoredRoute route) -> route.technicianId(), route -> utilization(route, LOAD_UNITS)))
                    .join(RouteScoringFacts.class).filter((_, facts) -> facts.target() != null)
                    .penalizeBigDecimal(DayScores.soft(DayScores.PHASE_OBJECTIVE), (balance, _) -> Required.value(Required.value(balance).unfairness()))
                    .asConstraint("Utilization load balance");
        }
    }
}
