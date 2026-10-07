package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.solver.Solver;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.SearchDeadline;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** One local daily request. Only the worker releases admission, even after its caller leaves. */
public final class DailyOperation {
    private static final ThreadLocal<DailyOperation> CURRENT = new ThreadLocal<>();
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG = Required.value(Executors.newSingleThreadScheduledExecutor(
            task -> Thread.ofPlatform().daemon().name("daily-cancellation").unstarted(task)));
    private static final long SEARCH_NANOS = Duration.ofSeconds(15).toNanos();
    public record Receipt(String requestId, String outcome, long elapsedMs, @Nullable Long queueMs, long searchMs,
            @Nullable Long cancellationElapsedMs, @Nullable Long cleanupOvershootMs, long phaseCleanupOvershootMs,
            long searchOvershootMs, boolean lateResult) { }
    private final String id = Required.value(UUID.randomUUID().toString());
    private final SearchDeadline deadline;
    private final Consumer<Receipt> recorder;
    private long searchNanos;
    private long phaseStarted;
    private long phaseBudget;
    private long phaseCleanupOvershoot;
    private @Nullable Solver<DayPlan> activeSolver;
    private @Nullable Long cancelledAt;
    private @Nullable Long queueMs;
    private boolean cancelled;
    private boolean resultReturned;

    private DailyOperation(Duration allowance, Consumer<Receipt> recorder) {
        if (allowance.compareTo(Duration.ofSeconds(20)) > 0) throw new IllegalArgumentException("Daily allowance exceeds 20 seconds");
        deadline = new SearchDeadline(allowance);
        this.recorder = recorder;
    }
    public static @Nullable DailyOperation current() { return CURRENT.get(); }
    public record Preparation<T>(Supplier<T> work, boolean needsAdmission, Consumer<Throwable> failed) { }
    static <T> T executePrepared(SearchAdmission admission, Supplier<Preparation<T>> prepare) {
        return launch(admission, Required.value(Duration.ofSeconds(20)), prepare,
                receipt -> org.slf4j.LoggerFactory.getLogger(DailyOperation.class).info("Daily operation {}", receipt));
    }
    public static <T> T executePrepared(SearchAdmission admission, Duration allowance, Supplier<Preparation<T>> prepare, Consumer<Receipt> recorder) {
        return launch(admission,allowance,prepare,recorder);
    }
    static <T> T execute(SearchAdmission admission, Supplier<T> work) {
        return execute(admission, Required.value(Duration.ofSeconds(20)), work,
                receipt -> org.slf4j.LoggerFactory.getLogger(DailyOperation.class).info("Daily operation {}", receipt));
    }
    public static <T> T execute(SearchAdmission admission, Duration allowance, Supplier<T> work, Consumer<Receipt> recorder) {
        return launch(admission, allowance, () -> new Preparation<>(work, true, _ -> { }), recorder);
    }
    private static <T> T launch(SearchAdmission admission, Duration allowance, Supplier<Preparation<T>> prepare, Consumer<Receipt> recorder) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested daily operation would reset the allowance");
        DailyOperation operation = new DailyOperation(allowance, recorder);
        CompletableFuture<T> completion = new CompletableFuture<>();
        // Admission bounds active work and its waiting queue. No application thread pool queue resets the clock.
        Thread.ofVirtual().name("daily-" + operation.id).start(() -> operation.run(admission, prepare, completion));
        try {
            T result = Required.value(completion.get(operation.deadline.remainingNanos(), TimeUnit.NANOSECONDS));
            operation.deadline.requireTime();
            return result;
        } catch (TimeoutException expired) {
            operation.cancel(); throw new SearchDeadline.Expired();
        } catch (InterruptedException interrupted) {
            operation.cancel(); Thread.currentThread().interrupt(); throw new SearchDeadline.Expired();
        } catch (ExecutionException failed) {
            Throwable cause = Required.value(failed.getCause());
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Daily worker failed", cause);
        }
    }
    private <T> void run(SearchAdmission admission, Supplier<Preparation<T>> prepare, CompletableFuture<T> completion) {
        String outcome = "FAILED";
        boolean late = false;
        var watchdog = WATCHDOG.scheduleWithFixedDelay(this::tick, 0, 10, TimeUnit.MILLISECONDS);
        try {
            CURRENT.set(this);
            T result = deadline.within(() -> {
                deadline.requireTime();
                Preparation<T> prepared = prepare.get();
                T value;
                if (!prepared.needsAdmission()) value = prepared.work().get();
                else {
                    boolean acquired = false;
                    try (var lease = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline)) {
                        acquired = true;
                        queueMs = lease.queueMillis();
                        try { value = prepared.work().get(); }
                        catch (Throwable failure) { prepared.failed().accept(failure); throw failure; }
                    } catch (Throwable failure) {
                        // A failed acquisition has no lease; conditional cleanup cannot overwrite a terminal result.
                        if (!acquired) prepared.failed().accept(failure); throw failure;
                    }
                }
                value = Required.value(value, "daily operation response");
                resultReturned = true;
                deadline.requireTime();
                return value;
            });
            deadline.requireTime();
            outcome = "COMPLETED";
            completion.complete(result);
        } catch (Throwable failure) {
            boolean expired = failure instanceof SearchDeadline.Expired || isCancelled();
            late = expired && resultReturned;
            if (expired) cancel();
            outcome = expired ? "CANCELLED_OR_EXPIRED" : "FAILED";
            completion.completeExceptionally(failure);
        } finally {
            // Do not Future.cancel/interrupt the solver. Capacity closes only after actual execution unwinds.
            CURRENT.remove();
            watchdog.cancel(false);
            recorder.accept(receipt(outcome, late));
        }
    }
    synchronized Duration budget(Duration maximum) {
        if (maximum.isNegative() || maximum.isZero()) throw new IllegalArgumentException("Positive search budget required");
        deadline.requireTime();
        long remaining = Math.min(SEARCH_NANOS - searchNanos, deadline.explorationNanos());
        long nanos = Math.min(maximum.toNanos(), remaining);
        if (nanos < 1_000_000) throw new SearchDeadline.Expired();
        // Timefold 2.6.0 rejects sub-millisecond spent limits. Floor, never round up the remaining allowance.
        nanos = nanos / 1_000_000 * 1_000_000;
        phaseStarted = System.nanoTime(); phaseBudget = nanos;
        return Required.value(Duration.ofMillis(nanos / 1_000_000));
    }
    synchronized void started(Solver<DayPlan> solver) {
        deadline.requireTime(); activeSolver = solver;
    }
    synchronized void stopped() { activeSolver = null; }
    synchronized void finishPhase() {
        if (phaseBudget != 0) {
            long elapsed = Math.max(0, System.nanoTime() - phaseStarted);
            searchNanos += elapsed;
            phaseCleanupOvershoot += Math.max(0, elapsed - phaseBudget);
        }
        phaseBudget = 0;
    }
    synchronized boolean canSearch() {
        deadline.requireTime();
        return Math.min(SEARCH_NANOS - searchNanos, deadline.explorationNanos()) >= 1_000_000;
    }
    private synchronized boolean isCancelled() { return cancelled; }
    public synchronized void cancel() {
        if (!cancelled) { cancelled = true; cancelledAt = deadline.elapsedMillis(); deadline.cancel(); }
        Solver<DayPlan> solver = activeSolver;
        if (solver != null) solver.terminateEarly();
    }
    private synchronized void tick() {
        if (deadline.remainingNanos() == 0) cancel();
        // Repeat termination to cover cancellation immediately before public solve() initializes its state.
        Solver<DayPlan> solver = activeSolver;
        if (solver != null && (cancelled || (phaseBudget != 0 && System.nanoTime() - phaseStarted >= phaseBudget)))
            solver.terminateEarly();
    }
    private synchronized Receipt receipt(String outcome, boolean late) {
        long elapsed = deadline.elapsedMillis();
        Long cancellation = cancelledAt;
        return new Receipt(id, outcome, elapsed, queueMs, searchNanos / 1_000_000, cancellation,
                cancellation == null ? null : Math.max(0, elapsed - cancellation), phaseCleanupOvershoot / 1_000_000,
                Math.max(0,searchNanos - SEARCH_NANOS) / 1_000_000, late);
    }
}
