package dev.waterflex.scheduler.optimizer;
import dev.waterflex.scheduler.*;
import java.time.Duration;
import java.util.*;
import org.jspecify.annotations.NonNull;
/** One database-free reference, target, fairness and independently validated policy operation. */
public final class DailyCalculation {
    public record Result(DayPlan plan, DayPlan reference, boolean accepted, String reason, int solveMs, DailySolver.Diagnostics diagnostics) { }
    /**
     * Runs one phase's solve, named REPAIR, REFERENCE or FAIRNESS, on the calculation's thread. A study binds its own
     * with {@link #recording} around the calculation it observes; everything else runs {@link #NONE}, which only solves.
     */
    @FunctionalInterface
    public interface Recorder { SolverEngine.Result record(String phase, java.util.function.Supplier<SolverEngine.Result> solve); }
    public static final Recorder NONE = (_, solve) -> Required.value(solve.get());
    private static final ScopedValue<@NonNull Recorder> RECORDER = Required.value(ScopedValue.newInstance());
    private DailyCalculation() { }
    /** Runs {@code work} on this thread with {@code recorder} receiving each phase of the calculations it makes. */
    public static <T> T recording(Recorder recorder, java.util.function.Supplier<T> work) {
        return Required.value(ScopedValue.where(RECORDER, recorder).call(work::get));
    }
    /** The recorder bound on this thread, or {@link #NONE} outside a study. */
    public static Recorder recorder() { return Required.value(RECORDER.orElse(NONE)); }
    private static SolverEngine.Result recorded(String phase, java.util.function.Supplier<SolverEngine.Result> solve) {
        return recorder().record(phase, solve);
    }
    /**
     * Phase budgets. A fairness allocation of zero skips the fairness phase; reference and repair must search.
     * {@code unimprovedMs}, when present, also ends the reference and fairness phases once the best score has not
     * improved for that long; a repair always spends its budget, since it searches for a feasible day.
     */
    public record Allocation(long referenceMs, long fairnessMs, long repairMs, boolean transferUnusedReference, @org.jspecify.annotations.Nullable Long unimprovedMs) {
        /** Bounds of a client's chosen search times, in whole seconds. */
        public static final int MIN_COST_SECONDS = 5, MAX_SEARCH_SECONDS = 240;
        /** The allocation used before clients chose their own times: 10 s cost, 5 s fairness, 15 s repair. */
        public static final Allocation DEFAULT = client(10, 5);
        public Allocation {
            if (referenceMs < 1 || fairnessMs < 0 || repairMs < 1) throw new IllegalArgumentException("Positive reference and repair allocations required");
            Math.addExact(referenceMs, fairnessMs);
            if (unimprovedMs != null && (unimprovedMs < 1 || unimprovedMs > referenceMs))
                throw new IllegalArgumentException("An unimproved limit must be positive and at most the reference phase");
        }
        /** Phase budgets without an unimproved limit: every phase spends its whole budget. */
        public Allocation(long referenceMs, long fairnessMs, long repairMs, boolean transferUnusedReference) {
            this(referenceMs, fairnessMs, repairMs, transferUnusedReference, null);
        }
        /** A client's cost and fairness search seconds. A repair has one phase, so it may spend both. */
        public static Allocation client(int costSeconds, int fairnessSeconds) {
            return client(costSeconds, fairnessSeconds, null);
        }
        /** As {@link #client(int, int)}, with the client's optional unimproved limit in whole seconds, 1 to the cost search time. */
        public static Allocation client(int costSeconds, int fairnessSeconds, @org.jspecify.annotations.Nullable Integer unimprovedSeconds) {
            if (costSeconds < MIN_COST_SECONDS || costSeconds > MAX_SEARCH_SECONDS)
                throw new IllegalArgumentException("Cost search time must be between " + MIN_COST_SECONDS + " and " + MAX_SEARCH_SECONDS + " seconds");
            if (fairnessSeconds < 0 || fairnessSeconds > MAX_SEARCH_SECONDS)
                throw new IllegalArgumentException("Fairness search time must be between 0 and " + MAX_SEARCH_SECONDS + " seconds");
            if (costSeconds + fairnessSeconds > MAX_SEARCH_SECONDS)
                throw new IllegalArgumentException("Cost and fairness search time together must be at most " + MAX_SEARCH_SECONDS + " seconds");
            if (unimprovedSeconds != null && (unimprovedSeconds < 1 || unimprovedSeconds > costSeconds))
                throw new IllegalArgumentException("Unimproved search time must be between 1 second and the cost search time");
            long cost = costSeconds * 1000L, fairness = fairnessSeconds * 1000L;
            return new Allocation(cost, fairness, cost + fairness, true, unimprovedSeconds == null ? null : unimprovedSeconds * 1000L);
        }
        /** The most search time any mode of this allocation may spend. */
        public long searchMs() { return Math.max(Math.addExact(referenceMs, fairnessMs), repairMs); }
        /** The unimproved limit of a reference or fairness phase, or null when every phase spends its whole budget. */
        public java.time.@org.jspecify.annotations.Nullable Duration unimproved() { Long ms = unimprovedMs; return ms == null ? null : Duration.ofMillis(ms); }
    }
    public static Result run(DayPlan baseline, SchedulingPolicy.Rules policy, DailySolver solver) {
        return run(baseline, policy, solver, Allocation.DEFAULT);
    }
    public static Result run(DayPlan baseline, SchedulingPolicy.Rules policy, DailySolver solver, Allocation allocation) {
        long started = System.nanoTime();
        if (baseline.getMode() == DayPlan.Mode.REPAIR) {
            var search = recorded("REPAIR",() -> solver.solve(baseline,Required.value(Duration.ofMillis(allocation.repairMs()))));
            SearchDeadline.checkpoint(); var validation = RouteEvaluator.evaluate(search.plan());
            boolean accepted = valid(search.plan()) && validation.overtimeMinutes() == 0;
            return new Result(search.plan(),search.plan(),accepted,accepted ? "Repair feasible" : "Repair infeasible",
                    (int)((System.nanoTime()-started)/1_000_000),solver.diagnostics(Required.value(List.of(new DailySolver.Phase("REPAIR",search.statistics()))))
                            .withPolicy(DailyPolicyDiagnostics.assess(search.plan(),search.plan(),accepted,DailyPolicyDiagnostics.Fairness.NOT_APPLICABLE_REPAIR)));
        }
        var referenceSearch = recorded("REFERENCE",() -> solver.solve(baseline,Required.value(Duration.ofMillis(allocation.referenceMs())),allocation.unimproved()));
        SearchDeadline.checkpoint();
        List<DailySolver.Phase> phases = new ArrayList<>(); phases.add(new DailySolver.Phase("REFERENCE",referenceSearch.statistics()));
        DayPlan solved = referenceSearch.plan();
        boolean candidateValid = valid(solved);
        boolean baselineValid = valid(baseline);
        if (!candidateValid && !baselineValid) return new Result(solved,baseline,false,"INCOMPLETE_OR_INFEASIBLE_DEMAND",
                (int)((System.nanoTime()-started)/1_000_000),solver.diagnostics(phases).withPolicy(DailyPolicyDiagnostics.assess(baseline,solved,false,DailyPolicyDiagnostics.Fairness.SKIPPED_INVALID_REFERENCE)));
        var baselinePolicy = SchedulingPolicy.measure(baselineValid ? baseline : solved);
        var candidatePolicy = candidateValid ? SchedulingPolicy.measure(solved) : baselinePolicy;
        var reference = SchedulingPolicy.referenceOrder(candidatePolicy, baselinePolicy) < 0 ? candidatePolicy : baselinePolicy;
        DayPlan referencePlan = !baselineValid || reference == candidatePolicy ? solved : baseline;
        var fairnessStatus = reference.overtimeMinutes() > 0 ? DailyPolicyDiagnostics.Fairness.SKIPPED_REFERENCE_OVERTIME
                : DailyPolicyDiagnostics.Fairness.SKIPPED_NO_SEARCH_ALLOWANCE;
        if (candidateValid && reference.overtimeMinutes() == 0) {
            DayPlan fairnessSeed = PlanCopies.copy(referencePlan);
            // A constructed cold result is now a populated search seed, not another empty cold input.
            if (fairnessSeed.getMode() == DayPlan.Mode.COLD) fairnessSeed.setMode(DayPlan.Mode.PARTIAL);
            fairnessSeed.setScoringFacts(fairnessSeed.getScoringFacts().withTarget(referencePlan,policy.costCeiling(reference.costCents())));
            DailyOperation operation = DailyOperation.current();
            if (allocation.fairnessMs() > 0 && operation != null && operation.canSearch()) {
                fairnessStatus = DailyPolicyDiagnostics.Fairness.SEARCHED;
                var fairnessSearch = recorded("FAIRNESS",() -> solver.solve(fairnessSeed,Required.value(Duration.ofMillis(allocation.transferUnusedReference()
                        ? Math.addExact(allocation.referenceMs(),allocation.fairnessMs()) : allocation.fairnessMs())),allocation.unimproved()));
                SearchDeadline.checkpoint(); phases.add(new DailySolver.Phase("FAIRNESS",fairnessSearch.statistics()));
                DayPlan fair = fairnessSearch.plan();
                fair.setMode(baseline.getMode());
                if (valid(fair)) {
                    var fairMetrics = SchedulingPolicy.measure(fair);
                    if (fairMetrics.overtimeMinutes() == reference.overtimeMinutes() && fairMetrics.bufferSeconds() <= reference.bufferSeconds()
                            && fairMetrics.costCents() <= policy.costCeiling(reference.costCents())
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
        // The reason is the policy's own code whenever a valid candidate was judged; a sentence would hide which rule decided.
        String reason = accepted ? (baselineValid ? decision.reason() : "COMPLETE_ZERO_OVERTIME_DEMAND")
                : candidateValid ? decision.reason() : "NO_VALID_CANDIDATE";
        return new Result(solved,referencePlan,accepted,reason,
                (int)((System.nanoTime()-started)/1_000_000),solver.diagnostics(phases)
                        .withPolicy(DailyPolicyDiagnostics.assess(referencePlan,solved,accepted,fairnessStatus)));
    }
    public static boolean valid(DayPlan plan) {
        var score = DayScoreCalculator.evaluate(plan); var validation = RouteEvaluator.evaluate(plan);
        return score.hardPenalty() == 0 && validation.feasible() && score.costCents() == validation.costCents() && score.arrivals().equals(validation.arrivals())
                && score.bufferSeconds() == validation.bufferSeconds();
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
