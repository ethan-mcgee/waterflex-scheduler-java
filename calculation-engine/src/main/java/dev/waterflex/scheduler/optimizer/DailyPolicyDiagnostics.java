package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import org.jspecify.annotations.Nullable;

/** The searched candidate is diagnostic evidence, even when the caller retains the baseline. */
public record DailyPolicyDiagnostics(int schemaVersion, Fairness fairness, Decision decision,
        @Nullable Long referenceOvertimeMinutes, DailyOutcome candidate, @Nullable Long candidateOvertimeMinutes) {
    public enum Fairness { SEARCHED, SKIPPED_REFERENCE_OVERTIME, SKIPPED_INVALID_REFERENCE, SKIPPED_NO_SEARCH_ALLOWANCE, NOT_APPLICABLE_REPAIR }
    public enum Decision { ACCEPTED, REJECTED_OVERTIME, REJECTED_UNRESOLVED_DEMAND, REJECTED_INFEASIBLE_DEMAND, REJECTED_POLICY }
    public DailyPolicyDiagnostics {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported daily policy diagnostics");
        Required.value(fairness); Required.value(decision); Required.value(candidate);
        if (referenceOvertimeMinutes != null && referenceOvertimeMinutes < 0 || candidateOvertimeMinutes != null && candidateOvertimeMinutes < 0)
            throw new IllegalArgumentException("Invalid overtime diagnostic");
        if (fairness == Fairness.SKIPPED_REFERENCE_OVERTIME && (referenceOvertimeMinutes == null || referenceOvertimeMinutes == 0))
            throw new IllegalArgumentException("Overtime skip needs a nonzero reference");
        if (decision == Decision.ACCEPTED && (!candidate.policyEligible() || candidateOvertimeMinutes == null || candidateOvertimeMinutes != 0)
                || decision == Decision.REJECTED_OVERTIME && (candidateOvertimeMinutes == null || candidateOvertimeMinutes == 0)
                || decision == Decision.REJECTED_UNRESOLVED_DEMAND && candidate.complete())
            throw new IllegalArgumentException("Policy diagnostic differs from candidate outcome");
    }
    public static DailyPolicyDiagnostics assess(DayPlan reference, DayPlan proposed, boolean accepted, Fairness fairness) {
        var candidate = DailyOutcome.assess(proposed);
        Long referenceOvertime = DailyCalculation.valid(reference) ? RouteEvaluator.evaluate(reference).overtimeMinutes() : null;
        Long candidateOvertime = candidate.assignedWorkFeasible() ? RouteEvaluator.evaluateAssigned(proposed).overtimeMinutes() : null;
        Decision decision = accepted ? Decision.ACCEPTED : !candidate.complete() ? Decision.REJECTED_UNRESOLVED_DEMAND
                : !candidate.assignedWorkFeasible() || !candidate.scoringMatchesValidation() ? Decision.REJECTED_INFEASIBLE_DEMAND
                : candidateOvertime != null && candidateOvertime > 0 ? Decision.REJECTED_OVERTIME : Decision.REJECTED_POLICY;
        return new DailyPolicyDiagnostics(1,fairness,decision,referenceOvertime,candidate,candidateOvertime);
    }
}
