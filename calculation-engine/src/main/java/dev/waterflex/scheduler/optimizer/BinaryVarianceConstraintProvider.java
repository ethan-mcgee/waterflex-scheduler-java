package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * The variance formula study's option (b), experiment-only: the decimal score of {@link DayConstraintProvider} with
 * the fairness variance computed in binary64 and carried as its rounded units of 1e-9 ({@link RouteScoringFacts#varianceUnits}),
 * the numeric contract's representation (docs/scheduler-policy.md). Every other level is the production one.
 */
public final class BinaryVarianceConstraintProvider extends DayConstraintProvider {
    @Override protected BigDecimal variance(List<RouteScoringFacts.@NonNull ScoredRoute> results, RouteScoringFacts facts) {
        return Required.value(BigDecimal.valueOf(facts.varianceUnits(results), 9));
    }
}
