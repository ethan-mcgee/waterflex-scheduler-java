package dev.waterflex.scheduler;

import dev.waterflex.scheduler.optimizer.DailyCalculation;
import dev.waterflex.scheduler.optimizer.DailyOperation;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

/** Each public API call logs with its tenant, request ID and operation, and leaves no context behind for the next call. */
class RequestLoggingTest {
    @AfterEach void clean() { MDC.clear(); }

    @Test void theCallsContextIsSetWhileServingAndClearedAfter() throws Exception {
        var seen = new AtomicReference<@Nullable String>();
        var request = new MockHttpServletRequest("POST", "/api/v1/daily/proposals");
        new RequestLogging().doFilter(request, new MockHttpServletResponse(), (_, _) -> {
            MDC.put(RequestLogging.TENANT, "acme");
            RequestLogging.requestId("3b1f6c1e-2a7d-4f0e-8c52-9a1d7e6b4c21");
            seen.set(MDC.get(RequestLogging.OPERATION));
        });
        assertEquals("POST /api/v1/daily/proposals", seen.get());
        assertNull(MDC.get(RequestLogging.TENANT), "no tenant leaks into the next call on this thread");
        assertNull(MDC.get(RequestLogging.REQUEST_ID));
        assertNull(MDC.get(RequestLogging.OPERATION));
    }

    @Test void theContextIsClearedWhenTheCallFails() {
        var request = new MockHttpServletRequest("POST", "/api/v1/booking/offers");
        assertThrows(IllegalStateException.class, () -> new RequestLogging().doFilter(request, new MockHttpServletResponse(), (_, _) -> {
            RequestLogging.requestId("3b1f6c1e-2a7d-4f0e-8c52-9a1d7e6b4c21");
            throw new IllegalStateException("boom");
        }));
        assertNull(MDC.get(RequestLogging.REQUEST_ID));
    }

    @Test void healthIsNotLoggedAsAnApiCall() throws Exception {
        var request = new MockHttpServletRequest("GET", "/health");
        var operation = new AtomicReference<@Nullable String>("unset");
        new RequestLogging().doFilter(request, new MockHttpServletResponse(), (_, _) -> operation.set(MDC.get(RequestLogging.OPERATION)));
        assertNull(operation.get());
    }

    @Test void theDailyWorkerLogsWithTheCallersRequestId() {
        RequestLogging.requestId("3b1f6c1e-2a7d-4f0e-8c52-9a1d7e6b4c21");
        String onWorker = DailyOperation.execute(new SearchAdmission(1, 1), DailyOperation.Allowance.forSearch(DailyCalculation.Allocation.client(5, 0)),
                () -> String.valueOf(MDC.get(RequestLogging.REQUEST_ID)), _ -> { });
        assertEquals("3b1f6c1e-2a7d-4f0e-8c52-9a1d7e6b4c21", onWorker);
    }
}
