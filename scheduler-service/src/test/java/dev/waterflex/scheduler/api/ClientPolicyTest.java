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

/** Every policy rule is the client's own: fairness budget, daily search method and times, booking deadline and window threshold. */
class ClientPolicyTest {
    private static final BigDecimal BUDGET = new BigDecimal("0.02");

    private static PublicTypes.Policy policy(String budget) { return new PublicTypes.Policy(new BigDecimal(budget), DailySearch.TABU_KOPT, 10, 5, 4000, 3); }
    private static PublicTypes.Policy searchTimes(int cost, int fairness) { return new PublicTypes.Policy(BUDGET, DailySearch.TABU, cost, fairness, 5000, 2); }
    private static PublicTypes.Policy booking(int deadlineMs, int threshold) { return new PublicTypes.Policy(BUDGET, DailySearch.TABU_KOPT, 10, 5, deadlineMs, threshold); }

    @Test void theRulesAreTheClientsOwn() {
        var rules = policy("0.05").rules();
        assertEquals(new SchedulingPolicy.Rules(3, new BigDecimal("0.05"), 4000), rules);
        assertEquals(10_500L, rules.costCeiling(10_000L));
    }

    @Test void aZeroBudgetNeverAddsCost() {
        assertEquals(10_000L, policy("0").rules().costCeiling(10_000L));
    }

    @Test void aNegativeOrOversizedBudgetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> policy("-0.01"));
        assertThrows(IllegalArgumentException.class, () -> policy("1.01"));
        assertEquals(BigDecimal.ONE, policy("1").fairnessBudget());
    }

    /** Missing and null values arrive through JSON; PublicApiContractTest rejects those. */
    @Test void theSearchTimesAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> searchTimes(4, 0));
        assertThrows(IllegalArgumentException.class, () -> searchTimes(241, 0));
        assertThrows(IllegalArgumentException.class, () -> searchTimes(10, -1));
        assertThrows(IllegalArgumentException.class, () -> searchTimes(200, 41));
        assertEquals(240, searchTimes(200, 40).costSearchSeconds() + 40);
        assertEquals(240, searchTimes(240, 0).costSearchSeconds());
    }

    @Test void theBookingDeadlineIsBetweenOneAndFiveSeconds() {
        assertThrows(IllegalArgumentException.class, () -> booking(999, 2));
        assertThrows(IllegalArgumentException.class, () -> booking(5001, 2));
        assertEquals(1000, booking(1000, 2).rules().bookingDeadlineMs());
        assertEquals(5000, booking(5000, 2).rules().bookingDeadlineMs());
    }

    @Test void theWindowThresholdIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> booking(5000, -1));
        assertThrows(IllegalArgumentException.class, () -> booking(5000, PublicTypes.Policy.MAX_REGULAR_WINDOW_THRESHOLD + 1));
        assertEquals(0, booking(5000, 0).rules().regularWindowThreshold());
        assertEquals(PublicTypes.Policy.MAX_REGULAR_WINDOW_THRESHOLD, booking(5000, PublicTypes.Policy.MAX_REGULAR_WINDOW_THRESHOLD).rules().regularWindowThreshold());
    }

    @Test void theTimesBecomePhaseBudgetsAndTheEarlierFixedTimesAreTheDefault() {
        assertEquals(DailyCalculation.Allocation.DEFAULT, DailyProposals.allocation(policy("0.02")));
        assertEquals(new DailyCalculation.Allocation(10_000, 5_000, 15_000, true), DailyCalculation.Allocation.DEFAULT);
        var costOnly = DailyProposals.allocation(new PublicTypes.Policy(new BigDecimal("0"), DailySearch.TABU, 60, 0, 5000, 2));
        assertEquals(new DailyCalculation.Allocation(60_000, 0, 60_000, true), costOnly);
        assertEquals(60_000, costOnly.searchMs());
    }

    @Test void theConfigurationIdentityCoversTheMethodTimesAndEveryRule() {
        var rules = policy("0.02").rules();
        String tabuKopt = DailyProposals.configuration(rules, DailySearch.TABU_KOPT, DailyCalculation.Allocation.DEFAULT);
        assertNotEquals(tabuKopt, DailyProposals.configuration(rules, DailySearch.TABU, DailyCalculation.Allocation.DEFAULT));
        assertNotEquals(tabuKopt, DailyProposals.configuration(rules, DailySearch.TABU_KOPT, DailyCalculation.Allocation.client(10, 0)));
        assertEquals(tabuKopt, DailyProposals.configuration(rules, DailySearch.TABU_KOPT, DailyCalculation.Allocation.client(10, 5)));
        assertNotEquals(tabuKopt, DailyProposals.configuration(booking(4999, 3).rules(), DailySearch.TABU_KOPT, DailyCalculation.Allocation.DEFAULT));
        assertNotEquals(tabuKopt, DailyProposals.configuration(booking(4000, 4).rules(), DailySearch.TABU_KOPT, DailyCalculation.Allocation.DEFAULT));
    }

    @Test void aClaimLeaseOutlivesTheLongestSearch() {
        // Search, the 5 second reserve, routing (which runs before the search's own allowance) and the lease margin.
        long routing = DailyProposals.ROUTING_ALLOWANCE.toSeconds(), margin = PublicApiStore.LEASE_MARGIN.toSeconds();
        assertEquals(Duration.ofSeconds(10 + 5 + 5 + routing + margin), DailyProposals.lease(snapshot(10, 5)));
        assertTrue(DailyProposals.lease(snapshot(10, 5)).compareTo(PublicApiStore.LEASE) > 0, "routing makes even a short search outlive the standard lease");
        assertEquals(Duration.ofSeconds(240 + 5 + routing + margin), DailyProposals.lease(snapshot(240, 0)));
        assertEquals(Duration.ofSeconds(120 + 60 + 5 + routing + margin), DailyProposals.lease(snapshot(120, 60)));
    }

    @Test void theSpecExampleCarriesTheClientsBookingRules() {
        var rules = snapshot(10, 5).policy().rules();
        assertTrue(rules.bookingDeadlineMs() >= PublicTypes.Policy.MIN_BOOKING_DEADLINE_MS && rules.bookingDeadlineMs() <= PublicTypes.Policy.MAX_BOOKING_DEADLINE_MS);
        assertTrue(rules.regularWindowThreshold() >= 0);
    }

    private static PublicTypes.Snapshot snapshot(int cost, int fairness) {
        ObjectNode request = (ObjectNode) CalculationJson.tree(PublicApiContractTest.example("DailyProposalRequest"));
        ((ObjectNode) request.path("snapshot").path("policy")).put("costSearchSeconds", cost).put("fairnessSearchSeconds", fairness);
        return PublicRequests.read(CalculationJson.write(request), PublicRequests.DailyProposalRequest.class).snapshot();
    }
}
