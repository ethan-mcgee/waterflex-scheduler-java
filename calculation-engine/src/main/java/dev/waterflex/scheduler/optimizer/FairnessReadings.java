package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.score.BendableScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import dev.waterflex.scheduler.Required;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every fairness measure of a plan, for studies: the production variance, the highest utilization, the utilization
 * spread and the capacity-weighted absolute deviation in DECIMAL128 from the workloads {@link SchedulingPolicy#measure}
 * counts, and Timefold's load balance exactly as its provider carries it, scored against the fairness phase's target.
 * The workstation harness (SolverBenchmark) and the service's study receipts read plans through this one definition,
 * so the two instruments report the same measures. Every plan is accepted on the production variance; the load balance
 * head-to-head (PR J, 2026-10-23) kept it, so no search runs on another measure. Nothing here scores a search;
 * production calls it only with study receipts on.
 */
public final class FairnessReadings {
    private static final MathContext DECIMAL128 = Required.value(MathContext.DECIMAL128);
    private FairnessReadings() { }

    /** Every measure of {@code plan}, in a fixed order; the load balance is scored against the target of {@code reference} and {@code ceiling}. */
    public static Map<String, BigDecimal> readings(DayPlan plan, DayPlan reference, long ceiling) {
        SchedulingPolicy.Fairness fairness = SchedulingPolicy.measure(plan).fairness();
        List<SchedulingPolicy.Workload> work = fairness.workloads();
        Map<String, BigDecimal> readings = new LinkedHashMap<>();
        readings.put("variance", fairness.variance());
        readings.put("maximumUtilization", fairness.maximumUtilization());
        // An empty fleet has no spread or deviation to measure; SchedulingPolicy reads it as perfectly even.
        BigDecimal spread = BigDecimal.ZERO, deviation = BigDecimal.ZERO;
        if (!work.isEmpty()) {
            BigDecimal minimum = Required.value(work.stream().map(item -> item.utilization()).min(Comparator.naturalOrder()).orElseThrow());
            spread = fairness.maximumUtilization().subtract(minimum);
            long capacity = 0, paid = 0;
            for (var item : work) { capacity = Math.addExact(capacity, item.regularCapacityMinutes()); paid = Math.addExact(paid, item.paidMinutes()); }
            BigDecimal mean = BigDecimal.valueOf(paid).divide(BigDecimal.valueOf(capacity), DECIMAL128), sum = BigDecimal.ZERO;
            for (var item : work) sum = sum.add(item.utilization().subtract(mean).abs().multiply(BigDecimal.valueOf(item.regularCapacityMinutes())));
            deviation = sum.divide(BigDecimal.valueOf(capacity), DECIMAL128);
        }
        readings.put("utilizationSpread", Required.value(spread));
        readings.put("absoluteDeviation", Required.value(deviation));
        readings.put("loadBalance", loadBalance(plan, reference, ceiling));
        return readings;
    }

    /** The solver factory comes from SolverEngine's scorer cache; a SolverFactory is thread-safe (Timefold docs 6.8, "Create the application"). */
    private static BigDecimal loadBalance(DayPlan plan, DayPlan reference, long ceiling) {
        DayPlan copy = PlanCopies.copy(plan);
        copy.setScoringFacts(copy.getScoringFacts().withTarget(reference, ceiling));
        SolutionManager.<DayPlan, BendableScore>create(SolverEngine.scorer(FairnessMeasures.LoadBalance.class)).update(copy);
        BendableScore score = Required.value(copy.getScore(), "scored plan");
        // The objective counts the unfairness in units of 1e-6, its six places exactly (DayScores.unfairnessUnits).
        return Required.value(BigDecimal.valueOf(Math.negateExact(score.softScore(DayScores.PHASE_OBJECTIVE)), DayScores.UNFAIRNESS_SCALE));
    }
}
