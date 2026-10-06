package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.score.BendableBigDecimalScore;
import dev.waterflex.scheduler.Required;
import java.math.BigDecimal;

/** Versioned lexicographic priorities. No lower level can compensate a higher violation. */
public final class DayScores {
    public static final int CATEGORICAL = 0, QUANTITATIVE = 1, UNASSIGNED = 2, OVERTIME_TARGET = 3, COST_CEILING = 4;
    public static final int PHASE_OBJECTIVE = 0, COST = 1;
    private DayScores() { }
    static BendableBigDecimalScore hard(int level) { return Required.value(BendableBigDecimalScore.ofHard(5, 2, level, Required.value(BigDecimal.ONE))); }
    static BendableBigDecimalScore soft(int level) { return Required.value(BendableBigDecimalScore.ofSoft(5, 2, level, Required.value(BigDecimal.ONE))); }
}
