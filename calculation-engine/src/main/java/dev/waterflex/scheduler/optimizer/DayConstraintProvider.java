package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
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
                routes.penalizeBigDecimal(DayScores.hard(DayScores.CATEGORICAL), result -> BigDecimal.valueOf(result.metrics().violations().qualifications()))
                        .asConstraint("Required qualifications"),
                routes.penalizeBigDecimal(DayScores.hard(DayScores.CATEGORICAL), result -> BigDecimal.valueOf(result.metrics().violations().unavailableRoads()))
                        .asConstraint("Reachable directed roads"),
                routes.penalizeBigDecimal(DayScores.hard(DayScores.QUANTITATIVE), result -> BigDecimal.valueOf(result.metrics().violations().latenessSeconds()))
                        .asConstraint("Exclusive customer window lateness seconds"),
                routes.penalizeBigDecimal(DayScores.hard(DayScores.QUANTITATIVE), result -> BigDecimal.valueOf(result.metrics().violations().availabilitySeconds()))
                        .asConstraint("Shift absences and return travel violation seconds"),
                routes.penalizeBigDecimal(DayScores.hard(DayScores.QUANTITATIVE), result -> BigDecimal.valueOf(result.metrics().violations().capacityMinutes()).multiply(BigDecimal.valueOf(60)))
                        .asConstraint("Daily and overtime capacity excess seconds"),
                factory.forEachIncludingUnassigned(PlanVisit.class).filter(visit -> visit.getTechnician() == null)
                        .penalize(DayScores.hard(DayScores.UNASSIGNED)).asConstraint("Unassigned demand"),
                routes.join(RouteScoringFacts.class).filter((_, facts) -> facts.target() == null)
                        .penalizeBigDecimal(DayScores.soft(DayScores.PHASE_OBJECTIVE), (result, _) -> BigDecimal.valueOf(result.metrics().overtimeMinutes()))
                        .asConstraint("Minimize overtime before operating cost"),
                fleet.penalizeBigDecimal(DayScores.soft(DayScores.COST), (results, facts) -> BigDecimal.valueOf(facts.cost(Required.value(results))))
                        .asConstraint("Modeled operating cost rounded at fleet level"),
                fleet.filter((_, facts) -> facts.target() != null)
                        .penalizeBigDecimal(DayScores.hard(DayScores.OVERTIME_TARGET), (results, facts) -> BigDecimal.valueOf(facts.overtimeDeviation(Required.value(results))))
                        .asConstraint("Recorded overtime target deviation"),
                fleet.filter((_, facts) -> facts.target() != null)
                        .penalizeBigDecimal(DayScores.hard(DayScores.COST_CEILING), (results, facts) -> BigDecimal.valueOf(facts.costExcess(Required.value(results))))
                        .asConstraint("Fairness cost ceiling excess"),
                fleet.filter((_, facts) -> facts.target() != null)
                        .penalizeBigDecimal(DayScores.soft(DayScores.PHASE_OBJECTIVE), (results, facts) -> facts.fairness(Required.value(results)))
                        .asConstraint("Capacity weighted workload variance")
        };
    }
}
