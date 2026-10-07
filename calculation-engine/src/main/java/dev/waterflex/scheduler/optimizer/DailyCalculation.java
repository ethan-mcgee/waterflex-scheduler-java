package dev.waterflex.scheduler.optimizer;
import dev.waterflex.scheduler.*;
import java.time.Duration;
import java.util.*;
/** One database-free reference, target, fairness and independently validated policy operation. */
public final class DailyCalculation {
    public record Result(DayPlan plan, DayPlan reference, boolean accepted, String reason, int solveMs, DailySolver.Diagnostics diagnostics) { }
    private DailyCalculation() { }
    public static Result run(DayPlan baseline, SchedulingPolicy.Rules policy, DailySolver solver) {
        long started = System.nanoTime();
        if (baseline.getMode() == DayPlan.Mode.REPAIR) {
            var search = solver.solve(baseline,Required.value(Duration.ofSeconds(15)));
            SearchDeadline.checkpoint(); var validation = RouteEvaluator.evaluate(search.plan());
            boolean accepted = valid(search.plan()) && validation.overtimeMinutes() == 0;
            return new Result(search.plan(),search.plan(),accepted,accepted ? "Repair feasible" : "Repair infeasible",
                    (int)((System.nanoTime()-started)/1_000_000),solver.diagnostics(Required.value(List.of(new DailySolver.Phase("REPAIR",search.statistics()))))
                            .withPolicy(DailyPolicyDiagnostics.assess(search.plan(),search.plan(),accepted,DailyPolicyDiagnostics.Fairness.NOT_APPLICABLE_REPAIR)));
        }
        var referenceSearch = solver.solve(baseline,Required.value(Duration.ofSeconds(10)));
        SearchDeadline.checkpoint();
        List<DailySolver.Phase> phases = new ArrayList<>(); phases.add(new DailySolver.Phase("REFERENCE",referenceSearch.statistics()));
        DayPlan solved = referenceSearch.plan();
        boolean candidateValid = valid(solved);
        boolean baselineValid = valid(baseline);
        if (!candidateValid && !baselineValid) return new Result(solved,baseline,false,"INCOMPLETE_OR_INFEASIBLE_DEMAND",
                (int)((System.nanoTime()-started)/1_000_000),solver.diagnostics(phases).withPolicy(DailyPolicyDiagnostics.assess(baseline,solved,false,DailyPolicyDiagnostics.Fairness.SKIPPED_INVALID_REFERENCE)));
        var baselinePolicy = SchedulingPolicy.measure(baselineValid ? baseline : solved);
        var candidatePolicy = candidateValid ? SchedulingPolicy.measure(solved) : baselinePolicy;
        var reference = candidatePolicy.overtimeMinutes() < baselinePolicy.overtimeMinutes()
                || candidatePolicy.overtimeMinutes() == baselinePolicy.overtimeMinutes() && candidatePolicy.costCents() < baselinePolicy.costCents() ? candidatePolicy : baselinePolicy;
        DayPlan referencePlan = !baselineValid || reference == candidatePolicy ? solved : baseline;
        var fairnessStatus = reference.overtimeMinutes() > 0 ? DailyPolicyDiagnostics.Fairness.SKIPPED_REFERENCE_OVERTIME
                : DailyPolicyDiagnostics.Fairness.SKIPPED_NO_SEARCH_ALLOWANCE;
        if (candidateValid && reference.overtimeMinutes() == 0) {
            DayPlan fairnessSeed = PlanCopies.copy(referencePlan);
            // A constructed cold result is now a populated search seed, not another empty cold input.
            if (fairnessSeed.getMode() == DayPlan.Mode.COLD) fairnessSeed.setMode(DayPlan.Mode.PARTIAL);
            fairnessSeed.setScoringFacts(fairnessSeed.getScoringFacts().withTarget(referencePlan,policy.costCeiling(reference.costCents())));
            DailyOperation operation = DailyOperation.current();
            if (operation != null && operation.canSearch()) {
                fairnessStatus = DailyPolicyDiagnostics.Fairness.SEARCHED;
                var fairnessSearch = solver.solve(fairnessSeed,Required.value(Duration.ofSeconds(15)));
                SearchDeadline.checkpoint(); phases.add(new DailySolver.Phase("FAIRNESS",fairnessSearch.statistics()));
                DayPlan fair = fairnessSearch.plan();
                fair.setMode(baseline.getMode());
                if (valid(fair)) {
                    var fairMetrics = SchedulingPolicy.measure(fair);
                    if (fairMetrics.overtimeMinutes() == reference.overtimeMinutes() && fairMetrics.costCents() <= policy.costCeiling(reference.costCents())
                            && (fairMetrics.fairness().variance().compareTo(reference.fairness().variance()) < 0
                            || fairMetrics.fairness().variance().compareTo(reference.fairness().variance()) == 0
                            && (fairMetrics.costCents() < reference.costCents() || fairMetrics.costCents() == reference.costCents()
                            && SchedulingPolicy.compareArrangements(fair,referencePlan) < 0))) {
                        solved = fair; candidatePolicy = fairMetrics;
                    } else { solved = referencePlan; candidatePolicy = reference; }
                }
            }
        }
        var decision = SchedulingPolicy.compare(baselinePolicy,candidatePolicy,reference,policy);
        boolean accepted = candidateValid && (baselineValid ? decision.accepted() : DailyOutcome.assess(solved).policyEligible());
        return new Result(solved,referencePlan,accepted,accepted ? baselineValid ? decision.reason() : "COMPLETE_ZERO_OVERTIME_DEMAND" : "No independently validated policy improvement",
                (int)((System.nanoTime()-started)/1_000_000),solver.diagnostics(phases)
                        .withPolicy(DailyPolicyDiagnostics.assess(referencePlan,solved,accepted,fairnessStatus)));
    }
    public static boolean valid(DayPlan plan) {
        var score = DayScoreCalculator.evaluate(plan); var validation = RouteEvaluator.evaluate(plan);
        return score.hardPenalty() == 0 && validation.feasible() && score.costCents() == validation.costCents() && score.arrivals().equals(validation.arrivals());
    }
    /** Rebuild proposals exclusively from caller facts, with exact once coverage and unchanged pinning. */
    public static DayPlan restore(DayPlan input, Map<String,List<String>> routes, List<String> unassigned) {
        DayPlan plan = PlanCopies.copy(input);
        if (!routes.keySet().equals(plan.getRoutes().stream().map(route -> Required.value(route).getId()).collect(java.util.stream.Collectors.toSet()))) throw new IllegalArgumentException("Proposal route coverage changed");
        Map<String,PlanVisit> visits = new HashMap<>(); plan.getVisits().forEach(visit -> visits.put(visit.getId(),visit));
        plan.getVisits().forEach(visit -> visit.setTechnician(null));
        for (TechRoute route : plan.getRoutes()) {
            route.getVisits().clear();
            for (String id : Required.value(routes.get(route.getId()))) {
                PlanVisit visit = Required.value(visits.remove(id),"proposal demand"); route.getVisits().add(visit); visit.setTechnician(route);
            }
        }
        for (String id : unassigned) Required.value(visits.remove(id),"unassigned demand");
        if (!visits.isEmpty()) throw new IllegalArgumentException("Missing proposal demand");
        plan.getFacts().validateEntities(plan,false); return plan;
    }
}
