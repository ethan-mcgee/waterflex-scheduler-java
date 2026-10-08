package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Each client chooses its own fairness budget; the other policy settings stay the scheduler's. */
class ClientPolicyTest {
    private static final SchedulingPolicy.Rules SHARED = new SchedulingPolicy.Rules(3, new BigDecimal("0.85"), new BigDecimal("0.02"), 4000);

    @Test void theClientBudgetReplacesOnlyTheSharedFairnessAllowance() {
        var rules = DailyProposals.rules(SHARED, new PublicTypes.Policy(new BigDecimal("0.05")));
        assertEquals(new SchedulingPolicy.Rules(3, new BigDecimal("0.85"), new BigDecimal("0.05"), 4000), rules);
        assertEquals(10_500L, rules.costCeiling(10_000L));
    }

    @Test void aZeroBudgetNeverAddsCost() {
        var rules = DailyProposals.rules(SHARED, new PublicTypes.Policy(new BigDecimal("0")));
        assertEquals(10_000L, rules.costCeiling(10_000L));
    }

    @Test void aNegativeOrOversizedBudgetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PublicTypes.Policy(new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class, () -> new PublicTypes.Policy(new BigDecimal("1.01")));
        assertEquals(BigDecimal.ONE, new PublicTypes.Policy(new BigDecimal("1")).fairnessBudget());
    }
}
