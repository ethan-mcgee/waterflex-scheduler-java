package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.util.List;

/** Coverage, independent feasibility and policy eligibility do not imply a termination cause or apply authority. */
public record DailyOutcome(DayPlan.Mode mode, String scoreModelVersion, List<String> assignedVisitIds,
        List<String> unassignedVisitIds, boolean complete, boolean assignedWorkFeasible,
        boolean scoringMatchesValidation, boolean policyEligible) {
    public static DailyOutcome assess(DayPlan plan) {
        plan.getFacts().validateEntities(plan, false);
        List<String> unassigned = plan.getUnassignedVisitIds();
        List<String> assigned = Required.value(plan.getVisits().stream().filter(visit -> !unassigned.contains(visit.getId())).map(visit -> visit.getId()).toList());
        var independent = RouteEvaluator.evaluateAssigned(plan);
        var score = DayScoreCalculator.evaluate(plan);
        boolean matches = independent.feasible() && score.costCents() == independent.costCents()
                && score.arrivals().equals(independent.arrivals());
        var target = plan.getScoringFacts().target();
        boolean targetMet = target == null || independent.overtimeMinutes() == target.overtimeMinutes() && independent.costCents() <= target.costCeilingCents();
        return new DailyOutcome(plan.getMode(), DailyDataset.SCORE_MODEL, assigned, unassigned, unassigned.isEmpty(),
                independent.feasible(), matches, unassigned.isEmpty() && matches && independent.overtimeMinutes() == 0 && targetMet);
    }
}
