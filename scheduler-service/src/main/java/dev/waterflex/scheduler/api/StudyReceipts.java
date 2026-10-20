package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.optimizer.DailyCalculation;
import dev.waterflex.scheduler.optimizer.DailySolver;
import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.FairnessReadings;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A study's view of each daily proposal, off by default ({@code SCHEDULER_STUDY_RECEIPTS}). When on, every daily or
 * repair calculation logs one line, "Study receipt {json}", on the request's thread and logging context: the variant
 * and seed, each phase's budget, time and termination, the decision, the reference plan's cost and ceiling, and the
 * reference and chosen plans' five fairness measures as the harness reads them ({@link FairnessReadings}): the
 * DECIMAL128 variance, highest utilization, utilization spread and absolute deviation, and Timefold's load balance. Moves per second come from
 * Timefold's own phase-end lines at INFO ({@code TIMEFOLD_LOG_LEVEL=INFO}), the measure Timefold's documentation
 * points to; the service attaches no solver instrumentation. With {@link StudyProfiles} on, the receipt also names
 * each phase's flight recording and the thread it ran on. Measuring costs a validation of each plan after the
 * search, so production leaves it off. Nothing here changes a proposal.
 */
@Component
public final class StudyReceipts {
    /** Receipts off: the production default, and the wiring of tests that construct the proposals directly. */
    public static final StudyReceipts OFF = new StudyReceipts(false);
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(StudyReceipts.class));
    private final boolean enabled;

    @Autowired
    public StudyReceipts(@Value("${scheduler.study.receipts:false}") boolean enabled) {
        this.enabled = enabled;
        if (enabled) LOG.warn("Study receipts are on: every daily calculation also measures its plans and logs a receipt");
    }

    public boolean enabled() { return enabled; }

    /** Logs the receipt of one calculation when receipts are on. */
    void record(String proposalId, LocalDate serviceDate, DailySolver solver, SchedulingPolicy.Rules policy, DayPlan baseline,
                DailyCalculation.Result calculation, StudyProfiles.@Nullable Recorded profile) {
        if (!enabled) return;
        LOG.info("Study receipt {}", CalculationJson.write(receipt(proposalId, serviceDate, solver, policy, baseline, calculation, profile)));
    }

    static Map<String, Object> receipt(String proposalId, LocalDate serviceDate, DailySolver solver, SchedulingPolicy.Rules policy,
                                       DayPlan baseline, DailyCalculation.Result calculation) {
        return receipt(proposalId, serviceDate, solver, policy, baseline, calculation, null);
    }

    /** The receipt, with the calculation's flight recordings when a study profiled it. */
    static Map<String, Object> receipt(String proposalId, LocalDate serviceDate, DailySolver solver, SchedulingPolicy.Rules policy,
                                       DayPlan baseline, DailyCalculation.Result calculation, StudyProfiles.@Nullable Recorded profile) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("proposalId", proposalId);
        receipt.put("serviceDate", serviceDate.toString());
        receipt.put("variant", solver.variant());
        receipt.put("seed", solver.seed());
        receipt.put("technicians", baseline.getRoutes().size());
        receipt.put("visits", baseline.getVisits().size());
        List<Map<String, Object>> phases = new ArrayList<>();
        for (DailySolver.Phase phase : calculation.diagnostics().phases()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", phase.name());
            entry.put("budgetMs", phase.statistics().budgetMs());
            entry.put("solveMs", phase.statistics().solveMs());
            entry.put("termination", phase.statistics().termination());
            phases.add(entry);
        }
        receipt.put("phases", phases);
        var policyDiagnostics = calculation.diagnostics().policy();
        // A diagnostics record without the policy part has no fairness status to report; say so rather than guess one.
        receipt.put("fairness", policyDiagnostics == null ? "UNREPORTED" : policyDiagnostics.fairness().name());
        receipt.put("accepted", calculation.accepted());
        receipt.put("reason", calculation.reason());
        DayPlan reference = calculation.reference();
        @Nullable String referenceGap = unmeasurable(reference);
        // The load balance is scored against the fairness phase's target, which needs the measured reference and its ceiling.
        @Nullable Long ceiling = null;
        if (referenceGap != null) receipt.put("reference", Map.of("unavailable", referenceGap));
        else {
            var metrics = SchedulingPolicy.measure(reference);
            ceiling = policy.costCeiling(metrics.costCents());
            receipt.put("reference", metrics(metrics, reference, reference, ceiling));
            receipt.put("costCeilingCents", ceiling);
        }
        DayPlan proposed = calculation.plan();
        @Nullable String proposedGap = unmeasurable(proposed);
        receipt.put("proposed", proposedGap != null ? Map.of("unavailable", proposedGap) : metrics(SchedulingPolicy.measure(proposed), proposed, reference, ceiling));
        if (profile != null) receipt.put("profile", profile.receipt(calculation.diagnostics().phases()));
        return receipt;
    }

    /** Why the policy cannot measure this plan, or null when it can: it must be valid and assign every visit. */
    static @Nullable String unmeasurable(DayPlan plan) {
        if (!DailyCalculation.valid(plan)) return "INVALID_PLAN";
        return plan.getUnassignedVisitIds().isEmpty() ? null : "UNASSIGNED_VISITS";
    }

    /**
     * A measured plan's cost, overtime and fairness measures. With the reference measured ({@code ceiling} present),
     * every one of the five; without it, the variance and highest utilization only, since the other three are read
     * together with the load balance, which needs the reference's target. A measure is never filled in.
     */
    private static Map<String, Object> metrics(SchedulingPolicy.Metrics metrics, DayPlan plan, DayPlan reference, @Nullable Long ceiling) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("costCents", metrics.costCents());
        entry.put("overtimeMinutes", metrics.overtimeMinutes());
        // Plain strings keep the DECIMAL128 digits a JSON number could round.
        entry.put("variance", metrics.fairness().variance().toPlainString());
        entry.put("maximumUtilization", metrics.fairness().maximumUtilization().toPlainString());
        if (ceiling != null) {
            Map<String, java.math.BigDecimal> readings = FairnessReadings.readings(plan, reference, ceiling);
            for (String name : List.of("utilizationSpread", "absoluteDeviation", "loadBalance"))
                entry.put(name, Required.value(readings.get(name), "fairness reading " + name).toPlainString());
        }
        return entry;
    }
}
