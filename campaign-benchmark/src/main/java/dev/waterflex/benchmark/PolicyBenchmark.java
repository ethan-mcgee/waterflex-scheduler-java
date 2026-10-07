package dev.waterflex.benchmark;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.optimizer.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Measures the complete production policy path with campaign-owned explicit allocations. */
public final class PolicyBenchmark {
    public record Budget(String id, String purpose, String phase, long operationMs, long searchMs,
            long referenceMs, long fairnessMs, long repairMs, long validationReserveMs, boolean transferUnusedToFairness) {
        public Budget {
            Required.value(id); Required.value(purpose); Required.value(phase);
            if (operationMs < 1 || operationMs > 120_000 || searchMs < 1 || referenceMs < 0 || fairnessMs < 0 || repairMs < 0
                    || validationReserveMs < 0 || Math.addExact(searchMs,validationReserveMs) > operationMs
                    || Math.addExact(Math.addExact(referenceMs,fairnessMs),repairMs) != (phase.equals("booking") ? 0 : searchMs))
                throw new IllegalArgumentException("Invalid campaign budgets");
        }
    }
    public record Measurement(DailyCalculation.Result calculation, DailyOperation.Receipt operation, long wallMs,
            DailyOutcome baseline, DailyOutcome reference, DailyOutcome candidate, SnapshotFileIO.Proposal retained,
            boolean independentlyValidRetained) { }
    private PolicyBenchmark() { }
    public static Measurement run(DailyDataset dataset, CampaignSolverConfiguration.Settings settings,
            long seed, Budget budget, SchedulingPolicy.Rules policy) {
        if (!budget.phase().equals("pipeline") && !budget.phase().equals("repair")) throw new IllegalArgumentException("Policy phase required");
        var solver = new DailySolver(CampaignSolverConfiguration.definition(settings,seed,false,budget.searchMs()),
                CampaignSolverConfiguration.definition(settings,seed,true,budget.searchMs()));
        var allocation = budget.phase().equals("repair") ? new DailyCalculation.Allocation(1,1,budget.repairMs(),false)
                : new DailyCalculation.Allocation(budget.referenceMs(),budget.fairnessMs(),1,budget.transferUnusedToFairness());
        DayPlan baseline = dataset.toDayPlan();
        CompletableFuture<DailyOperation.Receipt> receipt = new CompletableFuture<>();
        long started = System.nanoTime();
        var result = DailyOperation.executeBenchmark(new SearchAdmission(1,0),Required.value(Duration.ofMillis(budget.operationMs())),
                Required.value(Duration.ofMillis(budget.searchMs())),Required.value(Duration.ofMillis(budget.validationReserveMs())),
                () -> DailyCalculation.run(baseline,policy,solver,allocation),receipt::complete);
        DailyOperation.Receipt completed;
        try { completed = Required.value(receipt.get(5,TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Policy receipt interrupted",interrupted); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) { throw new IllegalStateException("Policy cleanup receipt missing",failed); }
        DayPlan retained = result.accepted() ? result.plan() : baseline;
        return new Measurement(result,completed,(System.nanoTime()-started)/1_000_000,DailyOutcome.assess(baseline),
                DailyOutcome.assess(result.reference()),DailyOutcome.assess(result.plan()),SnapshotFileIO.proposal(retained),DailyOutcome.assess(retained).policyEligible());
    }
}
