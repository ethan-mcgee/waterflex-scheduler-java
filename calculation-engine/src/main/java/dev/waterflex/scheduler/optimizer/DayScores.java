package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.score.BendableBigDecimalScore;
import ai.timefold.solver.core.api.score.BendableScore;
import dev.waterflex.scheduler.Required;
import java.math.BigDecimal;

/** Versioned lexicographic priorities. No lower level can compensate a higher violation. */
public final class DayScores {
    public static final int CATEGORICAL = 0, QUANTITATIVE = 1, UNASSIGNED = 2, OVERTIME_TARGET = 3, COST_CEILING = 4;
    public static final int PHASE_OBJECTIVE = 0, COST = 1;
    private DayScores() { }
    static BendableBigDecimalScore hard(int level) { return Required.value(BendableBigDecimalScore.ofHard(5, 2, level, Required.value(BigDecimal.ONE))); }
    static BendableBigDecimalScore soft(int level) { return Required.value(BendableBigDecimalScore.ofSoft(5, 2, level, Required.value(BigDecimal.ONE))); }
    /** The same levels as long weights, for the score representation study only ({@link LongScoreDayPlan}). */
    static BendableScore hardLong(int level) { return Required.value(BendableScore.ofHard(5, 2, level, 1)); }
    static BendableScore softLong(int level) { return Required.value(BendableScore.ofSoft(5, 2, level, 1)); }
}
