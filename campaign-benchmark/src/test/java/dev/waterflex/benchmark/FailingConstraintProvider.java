package dev.waterflex.benchmark;

import ai.timefold.solver.core.api.score.BendableBigDecimalScore;
import ai.timefold.solver.core.api.score.stream.*;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.optimizer.PlanVisit;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** A run-time scoring fault, after native batch construction, for report retention coverage. */
public final class FailingConstraintProvider implements ConstraintProvider {
    @Override public Constraint[] defineConstraints(@Nullable ConstraintFactory nullableFactory) {
        var factory=Required.value(nullableFactory);
        return new Constraint[]{factory.forEach(PlanVisit.class)
                .penalizeBigDecimal(BendableBigDecimalScore.ofHard(5,2,0,Required.value(BigDecimal.ONE)),_ -> {
                    throw new IllegalStateException("Deliberate native batch scoring failure");
                }).asConstraint("Deliberate failure")};
    }
}
