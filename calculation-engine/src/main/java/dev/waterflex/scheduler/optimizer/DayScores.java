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
    /**
     * The fairness phase holds the reference plan's overtime, buffer use and cost as targets, in that order; the
     * reference and repair phases leave the three at zero. The late-arrival buffer ({@link PlanFacts.ArrivalBuffer})
     * ranks below overtime because the policy refuses any plan with overtime ({@link SchedulingPolicy#compare}), so
     * buying buffer with overtime would only make a plan the policy throws away.
     */
    public static final int CATEGORICAL = 0, QUANTITATIVE = 1, UNASSIGNED = 2, OVERTIME_TARGET = 3, BUFFER_CEILING = 4, COST_CEILING = 5;
    /** Reference and repair: overtime, then buffer seconds, then cost. Fairness: the objective, then buffer seconds, then cost. */
    public static final int PHASE_OBJECTIVE = 0, BUFFER = 1, COST = 2;
    public static final int HARD_LEVELS = 6, SOFT_LEVELS = 3;
    private DayScores() { }
    static BendableScore hard(int level) { return Required.value(BendableScore.ofHard(HARD_LEVELS, SOFT_LEVELS, level, 1)); }
    static BendableScore soft(int level) { return Required.value(BendableScore.ofSoft(HARD_LEVELS, SOFT_LEVELS, level, 1)); }
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
