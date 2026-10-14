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

    @Test void expiredQueuedRequestCannotStartLaterAndCountsAsARejection() {
        SearchAdmission admission = new SearchAdmission(1, 16);
        try (var lease = admission.acquire(SearchAdmission.Kind.BOOKING, deadline(1000))) {
            assertTrue(lease.queueMillis() >= 0);
            assertThrows(SearchDeadline.Expired.class, () -> admission.acquire(SearchAdmission.Kind.BOOKING, deadline(20)));
            assertEquals(0, admission.state().queuedBookings());
            assertEquals(1, admission.rejections(), "a request whose deadline passed in the queue was refused, only later");
        }
        assertEquals(0, admission.state().active());
    }

    private static Duration seconds(long seconds) { return Required.value(Duration.ofSeconds(seconds)); }

    @Test void backgroundWorkThatCouldNotStartInTimeIsRefusedAtOnceWithTheEstimatedWait() {
        SearchAdmission admission = new SearchAdmission(2, 16);
        try (var running = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline(60_000), seconds(30))) {
            assertTrue(running.queueMillis() >= 0);
            long started = System.nanoTime();
            var refused = assertThrows(SearchAdmission.Busy.class,
                    () -> admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline(20_000), seconds(15)));
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1), "refused without waiting");
            Duration wait = Required.value(refused.retryAfter(), "estimated wait");
            assertTrue(wait.compareTo(seconds(25)) > 0 && wait.compareTo(seconds(30)) <= 0, "the running work's remaining 30 s: " + wait);
            assertEquals(0, admission.state().queuedBackground());
            assertEquals(1, admission.rejections());

            var early = assertThrows(SearchAdmission.Busy.class, () -> admission.requireBackgroundStartWithin(seconds(5)));
            assertNotNull(early.retryAfter());
            admission.requireBackgroundStartWithin(seconds(60));
            assertEquals(2, admission.rejections());
        }
        admission.requireBackgroundStartWithin(Required.value(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> admission.acquire(SearchAdmission.Kind.BOOKING, deadline(1000), seconds(1)));
    }

    @Test void backgroundWorkBlockedByBookingsLeavesTheQueueOnceItCouldNoLongerFinish() {
        SearchAdmission admission = new SearchAdmission(1, 16);
        try (var booking = admission.acquire(SearchAdmission.Kind.BOOKING, deadline(5000))) {
            assertTrue(booking.queueMillis() >= 0);
            long started = System.nanoTime();
            var refused = assertThrows(SearchAdmission.Busy.class,
                    () -> admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline(1500), Required.value(Duration.ofMillis(1000))));
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(waited >= 400 && waited < 1400, "left once less than its expected second remained, after " + waited + " ms");
            assertNotNull(refused.retryAfter());
            assertEquals(0, admission.state().queuedBackground());
            assertEquals(1, admission.rejections());
        }
    }

    @Test void backgroundWorkThatFitsAfterTheWorkAheadQueuesAsBefore() throws Exception {
        SearchAdmission admission = new SearchAdmission(2, 16);
        var running = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline(60_000), Required.value(Duration.ofMillis(200)));
        try (var pool = Executors.newSingleThreadExecutor()) {
            var queued = pool.submit(() -> {
                try (var lease = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline(5000), seconds(1))) { return lease.queueMillis(); }
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (admission.state().queuedBackground() == 0 && System.nanoTime() < until) Thread.sleep(1);
            assertEquals(1, admission.state().queuedBackground());
            running.close();
            assertTrue(queued.get(3, TimeUnit.SECONDS) >= 0);
        } finally { running.close(); }
        assertEquals(0, admission.rejections());
    }

    @Test void aBackgroundLimitAboveOneRunsThatManyAtOnceAndEstimatesTheFirstFreeLane() {
        SearchAdmission admission = new SearchAdmission(3, 16, 2);
        assertEquals(2, admission.backgroundLimit());
        assertTrue(admission.backgroundSlotFree());
        try (var first = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline(60_000), seconds(30))) {
            assertTrue(admission.backgroundSlotFree(), "one lane is still free");
            try (var second = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline(60_000), seconds(10))) {
                assertTrue(first.queueMillis() >= 0 && second.queueMillis() < 1000, "the second started at once");
                assertEquals(2, admission.state().active());
                assertFalse(admission.backgroundSlotFree(), "both lanes run");
                // The first lane frees after about 10 s (the shorter job), not after both jobs as with one lane.
                var refused = assertThrows(SearchAdmission.Busy.class, () -> admission.requireBackgroundStartWithin(seconds(5)));
                Duration wait = Required.value(refused.retryAfter(), "estimated wait");
                assertTrue(wait.compareTo(seconds(8)) > 0 && wait.compareTo(seconds(10)) <= 0, "the shorter job's remaining time: " + wait);
                admission.requireBackgroundStartWithin(seconds(11));
            }
            assertTrue(admission.backgroundSlotFree(), "closing a lease frees its lane");
        }
        assertEquals(new SearchAdmission.State(0, 0, 0, false), admission.state());
    }

    @Test void queuedBackgroundWorkTakesTheLaneThatFreesFirst() throws Exception {
        SearchAdmission admission = new SearchAdmission(2, 16, 2);
        var longer = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline(120_000), seconds(40));
        var shorter = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline(120_000), seconds(10));
        try (var pool = Executors.newSingleThreadExecutor()) {
            var queued = pool.submit(() -> {
                try (var lease = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline(60_000), seconds(20))) { return lease.queueMillis(); }
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (admission.state().queuedBackground() == 0 && System.nanoTime() < until) Thread.sleep(1);
            assertEquals(1, admission.state().queuedBackground());
            assertFalse(admission.backgroundSlotFree(), "work waits for a lane");
            // Lanes free at about 10 s and 40 s; the queued 20 s job takes the first, so new work waits for min(30, 40).
            var refused = assertThrows(SearchAdmission.Busy.class, () -> admission.requireBackgroundStartWithin(seconds(5)));
            Duration wait = Required.value(refused.retryAfter(), "estimated wait");
            assertTrue(wait.compareTo(seconds(28)) > 0 && wait.compareTo(seconds(30)) <= 0, "queued work fills the first free lane: " + wait);
            shorter.close();
            assertTrue(queued.get(3, TimeUnit.SECONDS) >= 0);
        } finally { shorter.close(); longer.close(); }
    }

    @Test void theBackgroundLimitMustFitTheCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new SearchAdmission(2, 16, 3));
        assertThrows(IllegalArgumentException.class, () -> new SearchAdmission(2, 16, 0));
        assertEquals(1, new SearchAdmission(2, 16).backgroundLimit(), "one at a time unless configured");
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

    /** Starts a booking for {@code client} on its own thread; it records its name once admitted, or how it was refused. */
    private static java.util.concurrent.Future<String> booking(java.util.concurrent.ExecutorService pool, SearchAdmission admission, String client, String name,
                                                               ConcurrentLinkedQueue<String> order) throws InterruptedException {
        int queued = admission.state().queuedBookings();
        var future = pool.submit(() -> {
            try (var lease = admission.acquireBooking(deadline(5000), client)) {
                order.add(name); Thread.sleep(20); return Required.value(name + " ran after " + lease.queueMillis() + " ms");
            } catch (SearchAdmission.Busy busy) { return name + " refused"; }
        });
        // Each booking joins the queue before the next is sent, so the queue order is known.
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (admission.state().queuedBookings() == queued && !future.isDone() && System.nanoTime() < until) Thread.sleep(1);
        return Required.value(future);
    }

    @Test void aWaitingBookingOfAnotherClientStartsBeforeTheBusyClientsNextOne() throws Exception {
        SearchAdmission admission = new SearchAdmission(1, 16);
        ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
        var occupied = admission.acquireBooking(deadline(5000), "client-a");
        try (var pool = Required.value(Executors.newVirtualThreadPerTaskExecutor())) {
            var a2 = booking(pool, admission, "client-a", "a2", order);
            var a3 = booking(pool, admission, "client-a", "a3", order);
            var b1 = booking(pool, admission, "client-b", "b1", order);
            assertEquals(3, admission.state().queuedBookings());
            occupied.close();
            for (var done : List.of(a2, a3, b1)) assertTrue(done.get(3, TimeUnit.SECONDS).contains("ran"));
        } finally { occupied.close(); }
        assertEquals(List.of("b1", "a2", "a3"), List.copyOf(order), "the client that had not run goes first, then the busy client's bookings in order");
        assertEquals(new SearchAdmission.State(0, 0, 0, false), admission.state());
    }

    @Test void aFullQueueGivesALighterClientThePlaceOfTheHeaviestClientsNewestBooking() throws Exception {
        SearchAdmission admission = new SearchAdmission(1, 3);
        ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
        var occupied = admission.acquireBooking(deadline(5000), "client-x");
        try (var pool = Required.value(Executors.newVirtualThreadPerTaskExecutor())) {
            var a1 = booking(pool, admission, "client-a", "a1", order);
            var a2 = booking(pool, admission, "client-a", "a2", order);
            var a3 = booking(pool, admission, "client-a", "a3", order);
            var b1 = booking(pool, admission, "client-b", "b1", order);
            assertEquals("a3 refused", a3.get(2, TimeUnit.SECONDS), "client a's newest booking gave its place to client b");
            var c1 = booking(pool, admission, "client-c", "c1", order);
            assertEquals("a2 refused", a2.get(2, TimeUnit.SECONDS));
            var d1 = booking(pool, admission, "client-d", "d1", order);
            assertEquals("d1 refused", d1.get(2, TimeUnit.SECONDS), "no client waits more than one booking, so the full queue refuses the newcomer");
            assertEquals(2, admission.evictions());
            assertEquals(3, admission.rejections());
            occupied.close();
            for (var done : List.of(a1, b1, c1)) assertTrue(done.get(3, TimeUnit.SECONDS).contains("ran"));
        } finally { occupied.close(); }
        assertEquals(java.util.Set.of("a1", "b1", "c1"), java.util.Set.copyOf(order));
    }

    /** Like {@link #booking} for a confirm. */
    private static java.util.concurrent.Future<String> confirm(java.util.concurrent.ExecutorService pool, SearchAdmission admission, String client, String name,
                                                               ConcurrentLinkedQueue<String> order) throws InterruptedException {
        int queued = admission.state().queuedBookings();
        var future = pool.submit(() -> {
            try (var lease = admission.acquireConfirm(deadline(5000), client)) {
                order.add(name); Thread.sleep(20); return Required.value(name + " ran after " + lease.queueMillis() + " ms");
            } catch (SearchAdmission.Busy busy) { return name + " refused"; }
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (admission.state().queuedBookings() == queued && !future.isDone() && System.nanoTime() < until) Thread.sleep(1);
        return Required.value(future);
    }

    @Test void aWaitingConfirmStartsBeforeEveryWaitingSearchAndConfirmsStayFairAmongClients() throws Exception {
        SearchAdmission admission = new SearchAdmission(1, 16);
        ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
        var occupied = admission.acquireBooking(deadline(5000), "client-a");
        try (var pool = Required.value(Executors.newVirtualThreadPerTaskExecutor())) {
            var a2 = booking(pool, admission, "client-a", "a2", order);
            var b1 = booking(pool, admission, "client-b", "b1", order);
            var ca = confirm(pool, admission, "client-a", "confirm-a", order);
            var cb = confirm(pool, admission, "client-b", "confirm-b", order);
            assertEquals(4, admission.state().queuedBookings(), "confirms wait in the booking queue");
            occupied.close();
            for (var done : List.of(a2, b1, ca, cb)) assertTrue(done.get(3, TimeUnit.SECONDS).contains("ran"));
        } finally { occupied.close(); }
        assertEquals(List.of("confirm-b", "confirm-a", "b1", "a2"), List.copyOf(order),
                "both confirms precede both searches; among confirms the client that had not run goes first, as among searches");
        assertEquals(0, admission.rejections());
        assertEquals(new SearchAdmission.State(0, 0, 0, false), admission.state());
    }

    @Test void aConfirmTakesASearchsPlaceInAFullQueueAndNeverGivesUpItsOwn() throws Exception {
        SearchAdmission admission = new SearchAdmission(1, 2);
        ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
        var occupied = admission.acquireBooking(deadline(5000), "client-a");
        try (var pool = Required.value(Executors.newVirtualThreadPerTaskExecutor())) {
            var a2 = booking(pool, admission, "client-a", "a2", order);
            var a3 = booking(pool, admission, "client-a", "a3", order);
            var ca = confirm(pool, admission, "client-a", "confirm-a", order);
            assertEquals("a3 refused", a3.get(2, TimeUnit.SECONDS), "the arriving confirm took the newest waiting search's place although it is the same client's");
            var b1 = booking(pool, admission, "client-b", "b1", order);
            assertEquals("b1 refused", b1.get(2, TimeUnit.SECONDS), "the waiting confirm is not counted: client a waits one search, as b would, so no client is heavier");
            var cc = confirm(pool, admission, "client-c", "confirm-c", order);
            assertEquals("a2 refused", a2.get(2, TimeUnit.SECONDS), "a second confirm takes the last search's place; the waiting confirm is never evicted");
            var cd = confirm(pool, admission, "client-d", "confirm-d", order);
            assertEquals("confirm-d refused", cd.get(2, TimeUnit.SECONDS), "a full queue of confirms refuses a confirm rather than evicting one");
            occupied.close();
            for (var done : List.of(ca, cc)) assertTrue(done.get(3, TimeUnit.SECONDS).contains("ran"));
        } finally { occupied.close(); }
        assertEquals(java.util.Set.of("confirm-a", "confirm-c"), java.util.Set.copyOf(order));
        assertEquals(2, admission.evictions());
        assertEquals(4, admission.rejections());
        assertThrows(IllegalArgumentException.class, () -> admission.acquireConfirm(deadline(1000), ""));
    }

    @Test void bookingsWithoutAClientKeepTheirArrivalOrder() throws Exception {
        SearchAdmission admission = new SearchAdmission(1, 16);
        ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
        var occupied = admission.acquire(SearchAdmission.Kind.BOOKING, deadline(5000));
        List<java.util.concurrent.Future<?>> waiting = new java.util.ArrayList<>();
        try (var pool = Required.value(Executors.newVirtualThreadPerTaskExecutor())) {
            for (String name : List.of("first", "second", "third")) {
                int queued = admission.state().queuedBookings();
                waiting.add(pool.submit(() -> {
                    try (var lease = admission.acquire(SearchAdmission.Kind.BOOKING, deadline(5000))) { order.add(name); return lease.queueMillis(); }
                }));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (admission.state().queuedBookings() == queued && System.nanoTime() < until) Thread.sleep(1);
            }
            occupied.close();
            for (var done : waiting) done.get(3, TimeUnit.SECONDS);
        } finally { occupied.close(); }
        assertEquals(List.of("first", "second", "third"), List.copyOf(order));
        assertThrows(IllegalArgumentException.class, () -> admission.acquireBooking(deadline(1000), ""));
    }
}
