package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.score.BendableBigDecimalScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import dev.waterflex.scheduler.Required;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The fairness measure study's readings of a plan (PR 187): each alternative measure exactly as its provider carries it
 * at the phase objective, scored against the fairness phase's target, beside the production DECIMAL128 variance.
 */
final class FairnessReadings {
    /** The measure each policy arm searches and is accepted on; the variance arms (baseline, b, c) are accepted on the production variance. */
    static final Map<SolverEngine.Variant, String> POLICY = Required.value(Map.of(
            SolverEngine.Variant.TABU_KOPT_MIN_MAX_UTILIZATION, "maximumUtilization",
            SolverEngine.Variant.TABU_KOPT_UTILIZATION_SPREAD, "utilizationSpread",
            SolverEngine.Variant.TABU_KOPT_ABSOLUTE_DEVIATION, "absoluteDeviation",
            SolverEngine.Variant.TABU_KOPT_LOAD_BALANCE, "loadBalance"));
    private static final Map<String, SolverEngine.Variant> PROVIDERS = providers();
    private static Map<String, SolverEngine.Variant> providers() {
        Map<String, SolverEngine.Variant> providers = new LinkedHashMap<>();
        for (var entry : POLICY.entrySet()) providers.put(Required.value(entry.getValue()), Required.value(entry.getKey()));
        return providers;
    }
    private FairnessReadings() { }

    /** The measure {@code variant} is accepted on, or null for the arms accepted on the production variance. */
    static @Nullable String accepted(SolverEngine.Variant variant) { return POLICY.get(variant); }

    /** One measure of {@code plan}, scored with that measure's provider against the target of {@code reference} and {@code ceiling}. */
    static BigDecimal reading(String measure, DayPlan plan, DayPlan reference, long ceiling) {
        SolverEngine.Variant variant = Required.value(PROVIDERS.get(measure), "known fairness measure");
        DayPlan copy = PlanCopies.copy(plan);
        copy.setScoringFacts(copy.getScoringFacts().withTarget(reference, ceiling));
        SolutionManager.<DayPlan, BendableBigDecimalScore>create(SolverEngine.configuration(variant, 0).factory()).update(copy);
        BendableBigDecimalScore score = Required.value(copy.getScore(), "scored plan");
        return Required.value(score.softScore(DayScores.PHASE_OBJECTIVE).negate());
    }

    /** Every measure of {@code plan}: the production variance, then each alternative in the order of {@link #PROVIDERS}. */
    static Map<String, BigDecimal> readings(DayPlan plan, DayPlan reference, long ceiling) {
        Map<String, BigDecimal> readings = new LinkedHashMap<>();
        readings.put("variance", Required.value(SchedulingPolicy.measure(plan).fairness().variance()));
        for (String measure : PROVIDERS.keySet()) readings.put(measure, reading(Required.value(measure), plan, reference, ceiling));
        return readings;
    }
}
