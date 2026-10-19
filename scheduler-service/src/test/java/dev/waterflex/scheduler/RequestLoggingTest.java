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

    @Test void aFinishedDeadlineIsSummarizedInMillisecondsAndReadOnce() throws Exception {
        var logging = new RequestLogging();
        var deadline = new SearchDeadline(Required.value(java.time.Duration.ofSeconds(5)));
        deadline.telemetry().admission(40_000_000L);
        deadline.telemetry().routingWait(1_234_567_890L);
        deadline.telemetry().calculation(250_000_000L);
        deadline.telemetry().database(7_000_000L, true);
        deadline.telemetry().routing(300, 200, 50, 50);
        String line = RequestLogging.breakdown(deadline.telemetry().snapshot());
        assertTrue(line.startsWith(" queueMs=40 routingMs=1234 calculationMs=250 dbMs=7 dbCalls=1 routingPairs=300 cacheHits=250 fetched=50"), line);
        assertFalse(line.contains("commitMs"), "a call that never began its commit phase has no commit time: " + line);
        deadline.telemetry().commit(321_000_000L);
        assertTrue(RequestLogging.breakdown(deadline.telemetry().snapshot()).contains(" fetched=50 commitMs=321"), "the commit phase's time follows the fetched legs");
        assertEquals("", RequestLogging.breakdown(null), "a call without a deadline has no breakdown");

        // The observer hands the measurements to the filter on the request thread, even when the work throws.
        var request = new MockHttpServletRequest("POST", "/api/v1/booking/offers");
        assertThrows(IllegalStateException.class, () -> logging.doFilter(request, new MockHttpServletResponse(), (_, _) ->
                deadline.within(() -> { throw new IllegalStateException("search failed"); })));
        var again = new MockHttpServletRequest("POST", "/api/v1/booking/offers");
        logging.doFilter(again, new MockHttpServletResponse(), (_, _) -> { });
        SearchDeadline.observe(null);
    }

    @Test void theCommitPhaseIsTimedFromBeginCommitToTheDeadlinesEndAndTheReserveIsASetting() {
        var measured = new AtomicReference<SearchTelemetry.@Nullable Measurements>();
        SearchDeadline.observe(measured::set);
        try {
            var deadline = new SearchDeadline(Required.value(java.time.Duration.ofSeconds(5)), Required.value(java.time.Duration.ofMillis(500)));
            assertEquals(500_000_000L, deadline.reserveNanos());
            assertTrue(deadline.explorationNanos() > 4_400_000_000L && deadline.explorationNanos() <= 4_500_000_000L,
                    "exploration is the deadline minus the reserve: " + deadline.explorationNanos());
            deadline.within(() -> {
                SearchDeadline.beginCommit();
                try { Thread.sleep(20); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return null;
            });
            SearchTelemetry.Measurements finished = measured.get();
            assertNotNull(finished);
            Long commit = finished.commitNanos();
            assertNotNull(commit, "the commit phase ran");
            assertTrue(commit >= 20_000_000L && commit < 4_000_000_000L, "about the time spent after beginCommit: " + commit);

            var explored = new SearchDeadline(Required.value(java.time.Duration.ofSeconds(5)));
            assertEquals(SearchDeadline.DEFAULT_RESERVE.toNanos(), explored.reserveNanos(), "one second unless the operator says otherwise");
            explored.within(() -> { SearchDeadline.checkpoint(); return null; });
            assertNull(Required.value(measured.get()).commitNanos(), "a call that never committed has no commit time, not zero");

            var retried = new SearchDeadline(Required.value(java.time.Duration.ofSeconds(5)));
            retried.within(() -> {
                SearchDeadline.beginCommit();
                SearchDeadline.beginExploration();
                SearchDeadline.beginCommit();
                return null;
            });
            Long twice = Required.value(measured.get()).commitNanos();
            assertNotNull(twice, "a search that committed, searched again and committed counts both commit phases");
        } finally { SearchDeadline.observe(null); }
    }

    @Test void theDailyWorkerLogsWithTheCallersRequestId() {
        RequestLogging.requestId("3b1f6c1e-2a7d-4f0e-8c52-9a1d7e6b4c21");
        String onWorker = DailyOperation.execute(new SearchAdmission(1, 1), DailyOperation.Allowance.forSearch(DailyCalculation.Allocation.client(5, 0)),
                () -> String.valueOf(MDC.get(RequestLogging.REQUEST_ID)), _ -> { });
        assertEquals("3b1f6c1e-2a7d-4f0e-8c52-9a1d7e6b4c21", onWorker);
    }
}
