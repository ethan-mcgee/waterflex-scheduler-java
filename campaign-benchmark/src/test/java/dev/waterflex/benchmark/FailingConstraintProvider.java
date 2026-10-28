package dev.waterflex.benchmark;

import ai.timefold.solver.core.api.score.BendableScore;
import ai.timefold.solver.core.api.score.stream.*;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.optimizer.DayScores;
import dev.waterflex.scheduler.optimizer.PlanVisit;
import org.jspecify.annotations.Nullable;

/** A run-time scoring fault, after native batch construction, for report retention coverage. */
public final class FailingConstraintProvider implements ConstraintProvider {
    @Override public Constraint[] defineConstraints(@Nullable ConstraintFactory nullableFactory) {
        var factory=Required.value(nullableFactory);
        return new Constraint[]{factory.forEach(PlanVisit.class)
                .penalize(BendableScore.ofHard(DayScores.HARD_LEVELS,DayScores.SOFT_LEVELS,0,1),_ -> {
                    throw new IllegalStateException("Deliberate native batch scoring failure");
                }).asConstraint("Deliberate failure")};
    }
}
