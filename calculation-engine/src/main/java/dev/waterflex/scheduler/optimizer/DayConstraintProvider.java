package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.bi.BiConstraintStream;
import ai.timefold.solver.core.api.score.stream.uni.UniConstraintStream;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import java.util.List;

/**
 * List moves update affected route tuples; unchanged routes retain their metrics. Open for one reason only: a study
 * measure ({@link FairnessMeasures}) replaces the fairness phase's objective, {@link #fairness}, to score a plan on it.
 */
public class DayConstraintProvider implements ConstraintProvider {
    /**
     * The fairness phase's objective at the soft phase-objective level: the capacity-weighted workload variance over
     * the whole fleet, in units of 1e-9 ({@link RouteScoringFacts#fairnessUnits}).
     */
    protected Constraint fairness(UniConstraintStream<RouteScoringFacts.@NonNull ScoredRoute> routes,
            BiConstraintStream<List<RouteScoringFacts.@NonNull ScoredRoute>, RouteScoringFacts> fleet) {
        return fleet.filter((_, facts) -> facts.target() != null)
                .penalize(DayScores.soft(DayScores.PHASE_OBJECTIVE), (results, facts) -> facts.fairnessUnits(Required.value(results)))
                .asConstraint("Capacity weighted workload variance");
    }
    @Override public Constraint[] defineConstraints(@Nullable ConstraintFactory nullableFactory) {
        ConstraintFactory factory = Required.value(nullableFactory, "constraint factory");
        var routes = factory.forEach(TechRoute.class).join(RouteScoringFacts.class)
                .map((route, facts) -> facts.evaluate(Required.value(route)));
        var fleet = routes.groupBy(ConstraintCollectors.toList()).join(RouteScoringFacts.class);
        return new Constraint[]{
                routes.penalize(DayScores.hard(DayScores.CATEGORICAL), result -> result.metrics().violations().qualifications())
                        .asConstraint("Required qualifications"),
                routes.penalize(DayScores.hard(DayScores.CATEGORICAL), result -> result.metrics().violations().unavailableRoads())
                        .asConstraint("Reachable directed roads"),
                routes.penalize(DayScores.hard(DayScores.QUANTITATIVE), result -> result.metrics().violations().latenessSeconds())
                        .asConstraint("Exclusive customer window lateness seconds"),
                routes.penalize(DayScores.hard(DayScores.QUANTITATIVE), result -> result.metrics().violations().availabilitySeconds())
                        .asConstraint("Shift absences and return travel violation seconds"),
                routes.penalize(DayScores.hard(DayScores.QUANTITATIVE), result -> Math.multiplyExact(result.metrics().violations().capacityMinutes(), 60))
                        .asConstraint("Daily and overtime capacity excess seconds"),
                factory.forEachIncludingUnassigned(PlanVisit.class).filter(visit -> visit.getTechnician() == null)
                        .penalize(DayScores.hard(DayScores.UNASSIGNED)).asConstraint("Unassigned demand"),
                routes.join(RouteScoringFacts.class).filter((_, facts) -> facts.target() == null)
                        .penalize(DayScores.soft(DayScores.PHASE_OBJECTIVE), (result, _) -> result.metrics().overtimeMinutes())
                        .asConstraint("Minimize overtime before operating cost"),
                // Most routes, and every route of a plan without a buffer, use none; filtered out, they never reach the scorer.
                routes.filter(result -> result.metrics().bufferSeconds() > 0)
                        .penalize(DayScores.soft(DayScores.BUFFER), result -> result.metrics().bufferSeconds())
                        .asConstraint("Late-arrival buffer seconds"),
                fleet.penalize(DayScores.soft(DayScores.COST), (results, facts) -> facts.cost(Required.value(results)))
                        .asConstraint("Modeled operating cost rounded at fleet level"),
                fleet.filter((_, facts) -> facts.target() != null)
                        .penalize(DayScores.hard(DayScores.OVERTIME_TARGET), (results, facts) -> facts.overtimeDeviation(Required.value(results)))
                        .asConstraint("Recorded overtime target deviation"),
                fleet.filter((_, facts) -> facts.target() != null)
                        .penalize(DayScores.hard(DayScores.BUFFER_CEILING), (results, facts) -> facts.bufferExcess(Required.value(results)))
                        .asConstraint("Late-arrival buffer ceiling excess"),
                fleet.filter((_, facts) -> facts.target() != null)
                        .penalize(DayScores.hard(DayScores.COST_CEILING), (results, facts) -> facts.costExcess(Required.value(results)))
                        .asConstraint("Fairness cost ceiling excess"),
                fairness(routes, fleet)
        };
    }
}
