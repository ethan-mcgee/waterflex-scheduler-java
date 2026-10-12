package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.optimizer.DailyCalculation;
import dev.waterflex.scheduler.optimizer.DailySearch;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Each client chooses its own fairness budget, daily search method and search times; the other policy settings stay the scheduler's. */
class ClientPolicyTest {
    private static final SchedulingPolicy.Rules SHARED = new SchedulingPolicy.Rules(3, new BigDecimal("0.85"), new BigDecimal("0.02"), 4000);

    private static PublicTypes.Policy policy(String budget) { return new PublicTypes.Policy(new BigDecimal(budget), DailySearch.TABU_KOPT, 10, 5); }

    @Test void theClientBudgetReplacesOnlyTheSharedFairnessAllowance() {
        var rules = DailyProposals.rules(SHARED, policy("0.05"));
        assertEquals(new SchedulingPolicy.Rules(3, new BigDecimal("0.85"), new BigDecimal("0.05"), 4000), rules);
        assertEquals(10_500L, rules.costCeiling(10_000L));
    }

    @Test void aZeroBudgetNeverAddsCost() {
        var rules = DailyProposals.rules(SHARED, policy("0"));
        assertEquals(10_000L, rules.costCeiling(10_000L));
    }

    @Test void aNegativeOrOversizedBudgetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> policy("-0.01"));
        assertThrows(IllegalArgumentException.class, () -> policy("1.01"));
        assertEquals(BigDecimal.ONE, policy("1").fairnessBudget());
    }

    /** Missing and null values arrive through JSON; PublicApiContractTest rejects those. */
    @Test void theSearchTimesAreBounded() {
        BigDecimal budget = new BigDecimal("0.02");
        assertThrows(IllegalArgumentException.class, () -> new PublicTypes.Policy(budget, DailySearch.TABU, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> new PublicTypes.Policy(budget, DailySearch.TABU, 241, 0));
        assertThrows(IllegalArgumentException.class, () -> new PublicTypes.Policy(budget, DailySearch.TABU, 10, -1));
        assertThrows(IllegalArgumentException.class, () -> new PublicTypes.Policy(budget, DailySearch.TABU, 200, 41));
        assertEquals(240, new PublicTypes.Policy(budget, DailySearch.TABU, 200, 40).costSearchSeconds() + 40);
        assertEquals(240, new PublicTypes.Policy(budget, DailySearch.TABU, 240, 0).costSearchSeconds());
    }

    @Test void theTimesBecomePhaseBudgetsAndTheEarlierFixedTimesAreTheDefault() {
        assertEquals(DailyCalculation.Allocation.DEFAULT, DailyProposals.allocation(policy("0.02")));
        assertEquals(new DailyCalculation.Allocation(10_000, 5_000, 15_000, true), DailyCalculation.Allocation.DEFAULT);
        var costOnly = DailyProposals.allocation(new PublicTypes.Policy(new BigDecimal("0"), DailySearch.TABU, 60, 0));
        assertEquals(new DailyCalculation.Allocation(60_000, 0, 60_000, true), costOnly);
        assertEquals(60_000, costOnly.searchMs());
    }

    @Test void theConfigurationIdentityCoversTheMethodAndTimes() {
        var rules = DailyProposals.rules(SHARED, policy("0.02"));
        String tabuKopt = DailyProposals.configuration(rules, DailySearch.TABU_KOPT, DailyCalculation.Allocation.DEFAULT);
        assertNotEquals(tabuKopt, DailyProposals.configuration(rules, DailySearch.TABU, DailyCalculation.Allocation.DEFAULT));
        assertNotEquals(tabuKopt, DailyProposals.configuration(rules, DailySearch.TABU_KOPT, DailyCalculation.Allocation.client(10, 0)));
        assertEquals(tabuKopt, DailyProposals.configuration(rules, DailySearch.TABU_KOPT, DailyCalculation.Allocation.client(10, 5)));
    }

    @Test void aClaimLeaseOutlivesTheLongestSearch() {
        assertEquals(PublicApiStore.LEASE, DailyProposals.lease(snapshot(10, 5)));
        assertEquals(Duration.ofSeconds(240 + 5 + 30), DailyProposals.lease(snapshot(240, 0)));
        assertEquals(Duration.ofSeconds(120 + 60 + 5 + 30), DailyProposals.lease(snapshot(120, 60)));
    }

    private static PublicTypes.Snapshot snapshot(int cost, int fairness) {
        ObjectNode request = (ObjectNode) CalculationJson.tree(PublicApiContractTest.example("DailyProposalRequest"));
        ((ObjectNode) request.path("snapshot").path("policy")).put("costSearchSeconds", cost).put("fairnessSearchSeconds", fairness);
        return PublicRequests.read(CalculationJson.write(request), PublicRequests.DailyProposalRequest.class).snapshot();
    }
}
