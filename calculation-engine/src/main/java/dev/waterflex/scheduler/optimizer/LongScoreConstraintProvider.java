package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import org.jspecify.annotations.Nullable;

/**
 * {@link DayConstraintProvider} with long impacts (treatment S of the score representation study, experiment-only).
 * Every level but one already carries an integer; the fairness variance is carried in units of
 * {@link RouteScoringFacts#VARIANCE_UNITS} per one, rounded (docs/scheduler-policy.md, "Numeric contract").
 */
public final class LongScoreConstraintProvider implements ConstraintProvider {
    @Override public Constraint[] defineConstraints(@Nullable ConstraintFactory nullableFactory) {
        ConstraintFactory factory = Required.value(nullableFactory, "constraint factory");
        var routes = factory.forEach(TechRoute.class).join(RouteScoringFacts.class)
                .map((route, facts) -> facts.evaluate(Required.value(route)));
        var fleet = routes.groupBy(ConstraintCollectors.toList()).join(RouteScoringFacts.class);
        return new Constraint[]{
                routes.penalize(DayScores.hardLong(DayScores.CATEGORICAL), result -> result.metrics().violations().qualifications())
                        .asConstraint("Required qualifications"),
                routes.penalize(DayScores.hardLong(DayScores.CATEGORICAL), result -> result.metrics().violations().unavailableRoads())
                        .asConstraint("Reachable directed roads"),
                routes.penalize(DayScores.hardLong(DayScores.QUANTITATIVE), result -> result.metrics().violations().latenessSeconds())
                        .asConstraint("Exclusive customer window lateness seconds"),
                routes.penalize(DayScores.hardLong(DayScores.QUANTITATIVE), result -> result.metrics().violations().availabilitySeconds())
                        .asConstraint("Shift absences and return travel violation seconds"),
                routes.penalize(DayScores.hardLong(DayScores.QUANTITATIVE), result -> Math.multiplyExact(result.metrics().violations().capacityMinutes(), 60))
                        .asConstraint("Daily and overtime capacity excess seconds"),
                factory.forEachIncludingUnassigned(PlanVisit.class).filter(visit -> visit.getTechnician() == null)
                        .penalize(DayScores.hardLong(DayScores.UNASSIGNED)).asConstraint("Unassigned demand"),
                routes.join(RouteScoringFacts.class).filter((_, facts) -> facts.target() == null)
                        .penalize(DayScores.softLong(DayScores.PHASE_OBJECTIVE), (result, _) -> result.metrics().overtimeMinutes())
                        .asConstraint("Minimize overtime before operating cost"),
                fleet.penalize(DayScores.softLong(DayScores.COST), (results, facts) -> facts.cost(Required.value(results)))
                        .asConstraint("Modeled operating cost rounded at fleet level"),
                fleet.filter((_, facts) -> facts.target() != null)
                        .penalize(DayScores.hardLong(DayScores.OVERTIME_TARGET), (results, facts) -> facts.overtimeDeviation(Required.value(results)))
                        .asConstraint("Recorded overtime target deviation"),
                fleet.filter((_, facts) -> facts.target() != null)
                        .penalize(DayScores.hardLong(DayScores.COST_CEILING), (results, facts) -> facts.costExcess(Required.value(results)))
                        .asConstraint("Fairness cost ceiling excess"),
                fleet.filter((_, facts) -> facts.target() != null)
                        .penalize(DayScores.softLong(DayScores.PHASE_OBJECTIVE), (results, facts) -> facts.varianceUnits(Required.value(results)))
                        .asConstraint("Capacity weighted workload variance")
        };
    }
}
