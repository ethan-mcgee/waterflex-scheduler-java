package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.bi.BiConstraintStream;
import ai.timefold.solver.core.api.score.stream.uni.UniConstraintStream;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Experiment-only fairness objectives, each a {@link DayConstraintProvider} that replaces only
 * {@link DayConstraintProvider#fairness}, with every other level the production one. It counts the technicians the
 * production variance counts (qualified for the day's demand, with regular capacity). The fairness measure study
 * (PR 187) also tested option c and three other policies; they were rejected and live on branch bench/variance-formula.
 */
public final class FairnessMeasures {
    /** Load balance runs on utilization in millionths: its squared deviations must stay well inside a long. */
    static final long LOAD_UNITS = 1_000_000L;
    private FairnessMeasures() { }

    /** Paid minutes over regular capacity in {@code scale} units per one, rounded half up, in exact integer arithmetic. */
    static long utilization(RouteScoringFacts.ScoredRoute route, long scale) {
        long twice = Math.multiplyExact(route.capacity(), 2);
        return Math.floorDiv(Math.addExact(Math.multiplyExact(Math.multiplyExact(RouteScoringFacts.paid(route), scale), 2), route.capacity()), twice);
    }

    /** Timefold's load balance over each technician's utilization, unweighted by capacity (docs/timefold-documentation, chapter 15). */
    public static final class LoadBalance extends DayConstraintProvider {
        @Override protected Constraint fairness(UniConstraintStream<RouteScoringFacts.@NonNull ScoredRoute> routes,
                BiConstraintStream<List<RouteScoringFacts.@NonNull ScoredRoute>, RouteScoringFacts> fleet) {
            return routes.filter(RouteScoringFacts::counted)
                    .groupBy(ConstraintCollectors.loadBalance((RouteScoringFacts.ScoredRoute route) -> route.technicianId(), route -> utilization(route, LOAD_UNITS)))
                    .join(RouteScoringFacts.class).filter((_, facts) -> facts.target() != null)
                    .penalizeBigDecimal(DayScores.soft(DayScores.PHASE_OBJECTIVE), (balance, _) -> Required.value(Required.value(balance).unfairness()))
                    .asConstraint("Utilization load balance");
        }
    }
}
