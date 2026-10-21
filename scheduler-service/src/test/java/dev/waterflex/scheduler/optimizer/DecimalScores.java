package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.score.BendableBigDecimalScore;
import ai.timefold.solver.core.api.score.BendableScore;
import dev.waterflex.scheduler.Required;
import java.math.BigDecimal;

/**
 * The long score read as the decimal levels it stands for, as the decimal score carried them before representation
 * study 2: every level whole, but the fairness phase's objective, which counts units of 10^-scale (9 for the
 * production variance, 6 for load balance's unfairness). So a test compares the long score with a decimal value or
 * string written before the change, level by level.
 */
final class DecimalScores {
    static final int VARIANCE_SCALE = 9;
    private DecimalScores() { }
    static BendableBigDecimalScore of(BendableScore score, boolean targeted, int objectiveScale) {
        BigDecimal[] hard = new BigDecimal[score.hardLevelsSize()], soft = new BigDecimal[score.softLevelsSize()];
        for (int level = 0; level < hard.length; level++) hard[level] = Required.value(BigDecimal.valueOf(score.hardScore(level)));
        for (int level = 0; level < soft.length; level++) soft[level] = Required.value(BigDecimal.valueOf(score.softScore(level)));
        if (targeted) soft[DayScores.PHASE_OBJECTIVE] = Required.value(BigDecimal.valueOf(score.softScore(DayScores.PHASE_OBJECTIVE), objectiveScale));
        return Required.value(BendableBigDecimalScore.of(hard, soft));
    }
    static BendableBigDecimalScore of(DayPlan plan, int objectiveScale) {
        return of(Required.value(plan.getScore(), "scored plan"), plan.getScoringFacts().target() != null, objectiveScale);
    }
}
