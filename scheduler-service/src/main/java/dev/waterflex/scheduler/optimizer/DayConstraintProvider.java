package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import ai.timefold.solver.core.api.score.HardMediumSoftBigDecimalScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import org.jspecify.annotations.Nullable;
import java.math.BigDecimal;

/** List moves update affected route tuples; unchanged routes retain their metrics. */
public final class DayConstraintProvider implements ConstraintProvider {
    @Override public Constraint[] defineConstraints(@Nullable ConstraintFactory nullableFactory) {
        ConstraintFactory factory = Required.value(nullableFactory, "constraint factory");
        var routes = factory.forEach(TechRoute.class).join(RouteScoringFacts.class)
                .map((route, facts) -> facts.evaluate(Required.value(route)));
        var fleet = routes.groupBy(ConstraintCollectors.toList()).join(RouteScoringFacts.class);
        return new Constraint[]{
                routes.penalizeBigDecimal(HardMediumSoftBigDecimalScore.ONE_HARD, result -> BigDecimal.valueOf(result.metrics().hardPenalty()))
                        .asConstraint("Qualifications windows absences and technician limits"),
                routes.join(RouteScoringFacts.class).filter((_, facts) -> facts.target() == null)
                        .penalizeBigDecimal(HardMediumSoftBigDecimalScore.ONE_MEDIUM, (result, _) -> BigDecimal.valueOf(result.metrics().overtimeMinutes()))
                        .asConstraint("Minimize overtime before operating cost"),
                fleet.penalizeBigDecimal(HardMediumSoftBigDecimalScore.ONE_SOFT, (results, facts) -> BigDecimal.valueOf(facts.cost(Required.value(results))))
                        .asConstraint("Modeled operating cost rounded at fleet level"),
                fleet.filter((_, facts) -> facts.target() != null)
                        .penalizeBigDecimal(HardMediumSoftBigDecimalScore.ONE_HARD, (results, facts) -> BigDecimal.valueOf(facts.targetViolation(Required.value(results))))
                        .asConstraint("Recorded overtime target and fairness cost ceiling"),
                fleet.filter((_, facts) -> facts.target() != null)
                        .penalizeBigDecimal(HardMediumSoftBigDecimalScore.ONE_MEDIUM, (results, facts) -> facts.fairness(Required.value(results)))
                        .asConstraint("Capacity weighted workload variance")
        };
    }
}
