package dev.waterflex.scheduler;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectedLegFlightsTest {
    private static final RoadClient.Pair PAIR = new RoadClient.Pair("a>b", new RoadPoint(0, 0), new RoadPoint(1, 1));
    private static final List<RoadClient.Pair> PAIRS = Required.value(List.<RoadClient.Pair>of(PAIR));
    private static final Map<String, RoadClient.Leg> RESULT = Required.value(Map.<String, RoadClient.Leg>of("a>b", new RoadClient.Leg(60, 100)));

    @Test void callerTimeoutDoesNotCancelSharedWorkOrAnotherCaller() throws Exception {
        try (var flights = new DirectedLegFlights(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            var longCaller = callers.submit(() -> flights.resolve(PAIRS, "roads", _ -> {
                assertNull(SearchDeadline.current(), "Worker must never inherit a customer's deadline");
                calls.incrementAndGet(); entered.countDown();
                await(release); return RESULT;
            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var deadline = new SearchDeadline(Required.value(Duration.ofMillis(1100)));
                assertThrows(SearchDeadline.Expired.class, () -> deadline.within(() -> flights.resolve(PAIRS, "roads", _ -> {
                    fail("Same directed pair must reuse the outstanding work"); return RESULT;
                })));
                assertFalse(longCaller.isDone());
                assertEquals(1, flights.pendingCount());
                release.countDown();
                assertEquals(RESULT, longCaller.get(2, TimeUnit.SECONDS));
                assertEquals(1, calls.get());
                assertEquals(0, flights.pendingCount());
            } finally { release.countDown(); }
        }
    }

    @Test void failureIsNotCachedAndIdentityAndDirectionAreIndependent() throws Exception {
        try (var flights = new DirectedLegFlights(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThrows(RoadClient.RoadUnavailable.class, () -> flights.resolve(PAIRS, "roads", _ -> { throw new RoadClient.RoadUnavailable("offline"); }));
            assertEquals(0, flights.pendingCount());
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var original = callers.submit(() -> flights.resolve(PAIRS, "roads", _ -> { entered.countDown(); await(release); return RESULT; }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(Map.of(), flights.resolve(PAIRS, "new-roads", _ -> Required.value(Map.of())), "Unreachable is a successful independent result");
                var reverse = new RoadClient.Pair("b>a", PAIR.destination(), PAIR.origin());
                assertEquals(Map.of(), flights.resolve(Required.value(List.<RoadClient.Pair>of(reverse)), "roads", _ -> Required.value(Map.of())));
                release.countDown();
                assertEquals(RESULT, original.get(2, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release routing work"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
}
