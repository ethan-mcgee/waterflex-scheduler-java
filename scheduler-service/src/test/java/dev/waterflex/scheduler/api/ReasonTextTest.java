package dev.waterflex.scheduler.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReasonTextTest {
    @Test void aKnownCodeLeadsItsSentence() {
        String reason = ReasonText.describe("NO_POLICY_IMPROVEMENT");
        assertTrue(reason.startsWith("NO_POLICY_IMPROVEMENT: "), reason);
        assertEquals("NO_POLICY_IMPROVEMENT", reason.substring(0, reason.indexOf(':')), "A host may take the text before the first colon as the code");
        assertEquals("COST_REDUCTION: lower fleet cost within policy", ReasonText.describe("COST_REDUCTION"));
    }

    @Test void anUnknownCodePassesThroughUnchanged() {
        assertEquals("Repair infeasible", ReasonText.describe("Repair infeasible"));
        assertEquals("SOME_FUTURE_CODE", ReasonText.describe("SOME_FUTURE_CODE"));
    }

    @Test void aBlankReasonIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ReasonText.describe(" "));
    }
}
