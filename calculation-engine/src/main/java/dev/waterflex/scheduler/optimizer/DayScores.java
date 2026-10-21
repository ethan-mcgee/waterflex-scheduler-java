package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.score.BendableScore;
import dev.waterflex.scheduler.Required;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Versioned lexicographic priorities. No lower level can compensate a higher violation. Every level is a long
 * (Timefold docs 12.2.4, BendableScore): each one already counts whole seconds, minutes, cents or visits, and the
 * fairness phase's objective counts units of 1e-9 of the variance ({@link RouteScoringFacts#fairnessUnits}), so no
 * level loses a place it had (12.1.10, 15.2.1).
 */
public final class DayScores {
    public static final int CATEGORICAL = 0, QUANTITATIVE = 1, UNASSIGNED = 2, OVERTIME_TARGET = 3, COST_CEILING = 4;
    public static final int PHASE_OBJECTIVE = 0, COST = 1;
    private DayScores() { }
    static BendableScore hard(int level) { return Required.value(BendableScore.ofHard(5, 2, level, 1)); }
    static BendableScore soft(int level) { return Required.value(BendableScore.ofSoft(5, 2, level, 1)); }
    /** Load balance's unfairness keeps six decimal places (Timefold docs 15.2.1); the objective counts them as units of 1e-6. */
    static final int UNFAIRNESS_SCALE = 6;
    /**
     * The unfairness in units of 1e-6, exactly. Timefold docs 15.2.1 multiply by a power of ten and round to the nearest
     * integer, keeping all six places; the collector already gives six, so that rounding never changes a value, and
     * this one refuses a seventh place rather than drop it silently. No other weight needs rescaling (15.2.1): the
     * objective shares soft 0 with nothing in the fairness phase.
     */
    static long unfairnessUnits(BigDecimal unfairness) {
        return Required.value(unfairness.movePointRight(UNFAIRNESS_SCALE).setScale(0, RoundingMode.UNNECESSARY)).longValueExact();
    }
}
