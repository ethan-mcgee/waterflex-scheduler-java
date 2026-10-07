package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.SearchDeadline;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DailyOperationTest {
    @Test void claimPreparationUsesOriginalClockBeforeAdmissionAndCleanupKeepsTheLease() {
        var admission = new SearchAdmission(1, 16);
        var cleaned = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalStateException.class, () -> DailyOperation.executePrepared(admission, () -> {
            assertNotNull(SearchDeadline.current()); assertNotNull(DailyOperation.current());
            assertEquals(0, admission.state().active());
            return new DailyOperation.Preparation<>(() -> {
                assertEquals(1, admission.state().active()); throw new IllegalStateException("fixture work failure");
            }, true, _ -> { assertEquals(1, admission.state().active()); cleaned.incrementAndGet(); });
        }));
        assertEquals(1, cleaned.get()); assertEquals(0, admission.state().active());
    }
    @Test void reusedResultDoesNotQueueBehindAnActiveWorker() {
        var admission = new SearchAdmission(1, 16);
        try (var lease = admission.acquire(SearchAdmission.Kind.BACKGROUND, new SearchDeadline(seconds(20)))) {
            assertTrue(lease.queueMillis() >= 0);
            assertEquals("cached", DailyOperation.executePrepared(admission,
                    () -> new DailyOperation.Preparation<>(() -> "cached", false, _ -> fail("No cleanup expected"))));
            assertEquals(1, admission.state().active()); assertEquals(0, admission.state().queuedBackground());
        }
    }
    private static Duration seconds(long value) { return Required.value(Duration.ofSeconds(value)); }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    @Test void timeoutWithholdsLateResultAndRetainsCapacityUntilWorkerActuallyStops() throws Exception {
        var admission = new SearchAdmission(1,16);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var receipts = new LinkedBlockingQueue<DailyOperation.Receipt>();
        try {
            assertThrows(SearchDeadline.Expired.class, () -> DailyOperation.execute(admission, Required.value(Duration.ofMillis(100)), () -> {
                entered.countDown(); await(release); return "late";
            }, receipts::add));
            await(entered);
            assertEquals(1, admission.state().active());
            assertNull(receipts.poll());
        } finally { release.countDown(); }
        var receipt = Required.value(receipts.poll(5,TimeUnit.SECONDS));
        assertTrue(receipt.lateResult()); assertNotNull(receipt.cancellationElapsedMs());
        assertNotNull(receipt.cleanupOvershootMs()); assertEquals(0,admission.state().active());
    }
    @Test void queueDelayConsumesOriginalAllowanceAndNeverExecutesExpiredWork() throws Exception {
        var admission = new SearchAdmission(1,16); var ran = new AtomicBoolean();
        var receipts = new LinkedBlockingQueue<DailyOperation.Receipt>();
        try (var lease = admission.acquire(SearchAdmission.Kind.BOOKING,new SearchDeadline(seconds(20)))) {
            assertTrue(lease.queueMillis() >= 0);
            assertThrows(SearchDeadline.Expired.class, () -> DailyOperation.execute(admission, seconds(1), () -> {
                ran.set(true); return "unexpected";
            }, receipts::add));
            var receipt = Required.value(receipts.poll(5,TimeUnit.SECONDS));
            assertNull(receipt.queueMs()); assertFalse(receipt.lateResult()); assertEquals("CANCELLED_OR_EXPIRED",receipt.outcome()); assertFalse(ran.get());
            assertEquals(0,admission.state().queuedBackground());
        }
        assertEquals(0,admission.state().active());
    }
    @Test void preparationReserveAndUnusedReferenceBudgetAreSharedWithoutClockReset() throws Exception {
        var admission = new SearchAdmission(1,16); var receipts = new LinkedBlockingQueue<DailyOperation.Receipt>();
        assertEquals("ok",DailyOperation.execute(admission,seconds(20),() -> {
            var operation = Required.value(DailyOperation.current());
            Duration reference = operation.budget(seconds(10));
            assertEquals(seconds(10),reference);
            pause(30); operation.finishPhase();
            Duration fairness = operation.budget(seconds(15));
            assertTrue(fairness.compareTo(seconds(14)) > 0); assertTrue(fairness.compareTo(seconds(15)) < 0);
            operation.finishPhase(); return "ok";
        },receipts::add));
        var receipt = Required.value(receipts.poll(5,TimeUnit.SECONDS));
        assertEquals("COMPLETED",receipt.outcome()); assertNull(receipt.cancellationElapsedMs());
        assertFalse(receipt.lateResult()); assertTrue(receipt.searchMs() >= 30);
        assertThrows(SearchDeadline.Expired.class,() -> DailyOperation.execute(admission,seconds(1),() -> {
            pause(100); Required.value(DailyOperation.current()).budget(seconds(10)); return "unexpected";
        },receipts::add));
    }
    @Test void publicTerminationIsRequestedAtPhaseCapWithoutInterruptingWorker() throws Exception {
        var admission = new SearchAdmission(1,16);
        var solver = SolverEngine.configuration(SolverEngine.Variant.TABU,17).factory().buildSolver();
        DailyOperation.execute(admission,seconds(20),() -> {
            var operation = Required.value(DailyOperation.current());
            operation.budget(Required.value(Duration.ofMillis(20))); operation.started(solver);
            try {
                long until = System.nanoTime() + seconds(5).toNanos();
                while (!solver.isTerminateEarly() && System.nanoTime() < until) pause(10);
                assertTrue(solver.isTerminateEarly()); assertFalse(Thread.currentThread().isInterrupted());
            } finally { operation.stopped(); operation.finishPhase(); }
            return "ok";
        },_ -> { });
    }
    @Test void interruptCancelsCallerButWorkerOwnsCleanupAndTransportFailureStaysDistinct() throws Exception {
        var admission = new SearchAdmission(1,16); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var receipts = new LinkedBlockingQueue<DailyOperation.Receipt>(); var restored = new AtomicBoolean();
        var caller = Thread.ofVirtual().start(() -> {
            assertThrows(SearchDeadline.Expired.class,() -> DailyOperation.execute(admission,seconds(20),() -> {
                entered.countDown(); await(release); return "late";
            },receipts::add));
            restored.set(Thread.currentThread().isInterrupted());
        });
        try { await(entered); caller.interrupt(); caller.join(5000); assertFalse(caller.isAlive());
            assertTrue(restored.get()); assertEquals(1,admission.state().active());
        } finally { release.countDown(); }
        assertTrue(Required.value(receipts.poll(5,TimeUnit.SECONDS)).lateResult());
        var failure = new IllegalStateException("transport failed");
        assertSame(failure,assertThrows(IllegalStateException.class,() -> DailyOperation.execute(admission,seconds(20),() -> { throw failure; },receipts::add)));
        assertEquals("FAILED",Required.value(receipts.poll(5,TimeUnit.SECONDS)).outcome());
    }
    @Test void scoringSplitShiftsIsIndependentOfExpiredOrCancelledRequestClock() {
        var initial = DayConstraintProviderTest.fixture();
        var route = initial.getRoutes().getFirst();
        var plan = PlanCopies.withAbsence(initial,route.getId(),new TechRoute.Unavailable(Required.value(route.getShiftStart().plusSeconds(2*3600)),
                Required.value(route.getShiftStart().plusSeconds(3*3600))));
        var expected = DayScoreCalculator.evaluate(plan);
        var deadline = new SearchDeadline(seconds(1)); deadline.cancel();
        assertEquals(expected,deadline.within(() -> DayScoreCalculator.evaluate(plan)));
        deadline.within(() -> {
            ai.timefold.solver.core.api.solver.SolutionManager.<DayPlan,ai.timefold.solver.core.api.score.BendableBigDecimalScore>create(
                    SolverEngine.configuration(SolverEngine.Variant.TABU,17).factory()).update(plan);
            return true;
        });
        assertThrows(SearchDeadline.Expired.class,() -> deadline.within(() -> { SearchDeadline.checkpoint(); return "never"; }));
        assertNull(SearchDeadline.current());
    }
    @Test void realDailySolverUsesRemainingAllowanceAndReturnsValidatedResult() throws Exception {
        var solver = new DailySolver("TABU",17); var receipts = new LinkedBlockingQueue<DailyOperation.Receipt>();
        var result = DailyOperation.execute(new SearchAdmission(1,16),seconds(3),() -> {
            pause(100);
            var value = solver.solve(DayConstraintProviderTest.fixture(),seconds(10));
            SearchDeadline.beginCommit(); SearchDeadline.beforeCommit(); return value;
        },receipts::add);
        assertTrue(result.statistics().budgetMs() <= 1900);
        assertTrue(result.statistics().budgetMs() > 0);
        assertTrue(result.outcome().complete());
        assertEquals("COMPLETED",Required.value(receipts.poll(5,TimeUnit.SECONDS)).outcome());
    }
    @Test void nestedDailyExecutionCannotStartAnotherClock() {
        var admission = new SearchAdmission(1,16);
        DailyOperation.execute(admission,seconds(20),() -> {
            assertThrows(IllegalStateException.class,() -> DailyOperation.execute(admission,() -> "nested"));
            return "ok";
        },_ -> { });
    }
}
