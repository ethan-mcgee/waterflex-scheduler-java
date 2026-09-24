package dev.waterflex.scheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchAdmissionTest {
    private static SearchDeadline deadline(long millis) { return new SearchDeadline(Required.value(Duration.ofMillis(millis))); }

    @Test void cancellationStopsQueuedWorkAndCommitWithoutReleasingAnotherPermit() throws Exception {
        SearchAdmission admission = new SearchAdmission(1, 16);
        SearchDeadline cancelled = deadline(5000);
        try (var occupied = admission.acquire(SearchAdmission.Kind.BOOKING, deadline(5000)); var pool = Executors.newSingleThreadExecutor()) {
            assertTrue(occupied.queueMillis() >= 0);
            var waiting = pool.submit(() -> assertThrows(SearchDeadline.Expired.class,
                    () -> admission.acquire(SearchAdmission.Kind.BOOKING, cancelled)));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (admission.state().queuedBookings() == 0 && System.nanoTime() < until) Thread.sleep(1);
            assertEquals(1, admission.state().queuedBookings());
            cancelled.cancel(); waiting.get(1, TimeUnit.SECONDS);
            assertEquals(1, admission.state().active()); assertEquals(0, admission.state().queuedBookings());
        }
        cancelled.within(() -> { assertThrows(SearchDeadline.Expired.class, SearchDeadline::beforeCommit); return true; });
    }

    @Test void queueBoundAndBackgroundLimitReleaseWithoutLeakingPermits() {
        SearchAdmission admission = new SearchAdmission(2, 0);
        try (var background = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline(1000))) {
            assertTrue(background.queueMillis() >= 0);
            assertThrows(SearchAdmission.Busy.class, () -> admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline(1000)));
            try (var booking = admission.acquire(SearchAdmission.Kind.BOOKING, deadline(1000))) {
                assertTrue(booking.queueMillis() >= 0);
                assertEquals(2, admission.state().active());
                assertThrows(SearchAdmission.Busy.class, () -> admission.acquire(SearchAdmission.Kind.BOOKING, deadline(1000)));
            }
        }
        assertEquals(new SearchAdmission.State(0, 0, 0, false), admission.state());
        var lease = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline(1000));
        lease.close(); lease.close();
        assertEquals(0, admission.state().active());
    }

    @Test void queuedBookingsPrecedeBackgroundWithoutSharingSolutions() throws Exception {
        SearchAdmission admission = new SearchAdmission(1, 16);
        ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
        var occupied = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline(1000));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var background = pool.submit(() -> {
                try (var lease = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline(5000))) {
                    order.add("background"); return lease.queueMillis();
                }
            });
            var booking = pool.submit(() -> {
                try (var lease = admission.acquire(SearchAdmission.Kind.BOOKING, deadline(5000))) {
                    order.add("booking"); return lease.queueMillis();
                }
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (admission.state().queuedBookings() + admission.state().queuedBackground() != 2 && System.nanoTime() < until)
                Thread.sleep(1);
            assertEquals(1, admission.state().queuedBookings());
            assertEquals(1, admission.state().queuedBackground());
            occupied.close();
            booking.get(3, TimeUnit.SECONDS); background.get(3, TimeUnit.SECONDS);
            assertEquals(List.of("booking", "background"), List.copyOf(order));
        } finally { occupied.close(); }
        assertEquals(new SearchAdmission.State(0, 0, 0, false), admission.state());
    }

    @Test void expiredQueuedRequestCannotStartLater() {
        SearchAdmission admission = new SearchAdmission(1, 16);
        try (var lease = admission.acquire(SearchAdmission.Kind.BOOKING, deadline(1000))) {
            assertTrue(lease.queueMillis() >= 0);
            assertThrows(SearchDeadline.Expired.class, () -> admission.acquire(SearchAdmission.Kind.BOOKING, deadline(20)));
            assertEquals(0, admission.state().queuedBookings());
        }
        assertEquals(0, admission.state().active());
    }

    @Test void oneBudgetReservesCommitTimeAndAlwaysClearsRequestScope() {
        AtomicLong time = new AtomicLong(0);
        SearchDeadline budget = new SearchDeadline(Required.value(Duration.ofSeconds(5)), time::get);
        assertThrows(IllegalStateException.class, () -> budget.within(() -> {
            time.set(TimeUnit.SECONDS.toNanos(4));
            assertThrows(SearchDeadline.Expired.class, SearchDeadline::checkpoint);
            SearchDeadline.beginCommit();
            assertEquals(Duration.ofSeconds(1), SearchDeadline.networkTimeout(Required.value(Duration.ofSeconds(10))));
            time.set(TimeUnit.SECONDS.toNanos(5));
            assertThrows(SearchDeadline.Expired.class, SearchDeadline::checkpoint);
            throw new IllegalStateException("test failure");
        }));
        assertNull(SearchDeadline.current());
        SearchDeadline.checkpoint();
    }

    @Test void policyShorteningIncludesAlreadySpentTime() {
        AtomicLong time = new AtomicLong(0);
        SearchDeadline budget = new SearchDeadline(Required.value(Duration.ofSeconds(5)), time::get);
        budget.within(() -> {
            time.set(TimeUnit.SECONDS.toNanos(2));
            SearchDeadline.policyLimit(3000);
            assertEquals(TimeUnit.SECONDS.toNanos(1), budget.remainingNanos());
            assertThrows(SearchDeadline.Expired.class, SearchDeadline::checkpoint);
            return true;
        });
    }
}
