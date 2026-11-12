package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.api.PublicResponses.ErrorCode;
import dev.waterflex.scheduler.api.PublicResponses.Problem;
import dev.waterflex.scheduler.api.PublicResponses.StaleProblem;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Each error code's retry rule (spec 1.11.0), and its replay from an answer stored before the field existed. */
class ProblemRetryTest {
    /** The codes the integration guide's error table says to retry with the same requestId. */
    private static final Set<ErrorCode> RETRYABLE = dev.waterflex.scheduler.Required.value(EnumSet.of(ErrorCode.BUSY, ErrorCode.ROUTING_UNAVAILABLE,
            ErrorCode.CALCULATION_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE));

    @Test void everyCodeCarriesTheGuidesRetryRule() {
        for (ErrorCode code : ErrorCode.values()) {
            assertEquals(RETRYABLE.contains(code), code.retryable(), code.name());
            assertEquals(RETRYABLE.contains(code), new Problem(code, "m").retryable(), code.name());
        }
    }

    @Test void anAnswerStoredBeforeTheFieldIsReplayedWithItsCodesRule() {
        Problem busy = PublicRequests.read("{\"error\":\"BUSY\",\"message\":\"Search capacity exhausted\"}", Problem.class);
        assertEquals(Boolean.TRUE, busy.retryable());
        assertEquals("{\"error\":\"BUSY\",\"message\":\"Search capacity exhausted\",\"retryable\":true}", CalculationJson.write(busy));
        StaleProblem stale = PublicRequests.read("{\"error\":\"STALE\",\"message\":\"m\",\"changed\":"
                + "[{\"technicianId\":\"tech-1\",\"serviceDate\":\"2026-10-12\",\"lastModified\":\"2026-10-10T16:30:00Z\"}]}", StaleProblem.class);
        assertEquals(Boolean.FALSE, stale.retryable());
    }

    @Test void aStoredRuleNeverOverridesTheCodes() {
        Problem stored = PublicRequests.read("{\"error\":\"INVALID_REQUEST\",\"message\":\"m\",\"retryable\":true}", Problem.class);
        assertEquals(Boolean.FALSE, stored.retryable());
    }
}
