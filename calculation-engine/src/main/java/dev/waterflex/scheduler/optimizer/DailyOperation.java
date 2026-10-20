package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.solver.Solver;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.SearchDeadline;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
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
    private final long searchLimitNanos;
    /**
     * How long the operation expects to hold its admission slot, so admission can estimate the wait for work behind it:
     * a calculation's search limit plus {@link #SETTLE} (at most its allowance), and zero for the legacy fixed
     * allowance, which only route evaluations and geometry use and which does not search.
     */
    private final long expectedNanos;
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
    private @Nullable Solver<?> activeSolver;
    private @Nullable Long cancelledAt;
    private @Nullable Long queueMs;
    private boolean cancelled;
    private boolean resultReturned;

    /**
     * A request's whole allowance and the part of it solver phases may spend. The rest covers admission, preparation
     * (routing) and validation.
     */
    public record Allowance(Duration total, Duration search) {
        /** Time beyond the search for admission, routing and validation. */
        public static final Duration RESERVE = Required.value(Duration.ofSeconds(5));
        /** The longest request: the longest client search plus the reserve. */
        public static final Duration MAXIMUM = Required.value(Duration.ofSeconds(DailyCalculation.Allocation.MAX_SEARCH_SECONDS).plus(RESERVE));
        /** The allowance used before clients chose their own search times. */
        public static final Allowance DEFAULT = new Allowance(Required.value(Duration.ofSeconds(20)), Required.value(Duration.ofSeconds(15)));
        public Allowance {
            if (search.isNegative() || search.isZero() || search.compareTo(total) > 0 || total.compareTo(MAXIMUM) > 0)
                throw new IllegalArgumentException("Daily search must be positive and fit within an allowance of at most " + MAXIMUM.toSeconds() + " seconds");
        }
        /** The allowance for a calculation that may search this long. */
        public static Allowance forSearch(DailyCalculation.Allocation allocation) {
            Duration search = Required.value(Duration.ofMillis(allocation.searchMs()));
            return new Allowance(Required.value(search.plus(RESERVE)), search);
        }
    }
    /**
     * What a calculation needs in its slot besides its search: building the solver, scoring and validating the result.
     * Measured at 0.3 to 0.7 s for a 20 technician day; the margin keeps a request that starts after a wait from running
     * out of allowance while it validates.
     */
    public static final Duration SETTLE = Required.value(Duration.ofSeconds(2));
    private DailyOperation(Allowance allowance, Consumer<Receipt> recorder) {
        this(allowance, Required.value(Collections.min(List.of(allowance.search().plus(SETTLE), allowance.total()))), recorder);
    }
    private DailyOperation(Allowance allowance, Duration expected, Consumer<Receipt> recorder) {
        deadline = new SearchDeadline(allowance.total());
        searchLimitNanos = allowance.search().toNanos();
        expectedNanos = expected.toNanos();
        this.recorder = recorder;
    }
    private DailyOperation(Duration allowance, Consumer<Receipt> recorder) {
        this(legacy(allowance), Required.value(Duration.ZERO), recorder);
    }
    /** Callers that pass only a total keep the original limits: at most 20 seconds, of which up to 15 may search. */
    private static Allowance legacy(Duration allowance) {
        if (allowance.compareTo(Allowance.DEFAULT.total()) > 0) throw new IllegalArgumentException("Daily allowance exceeds 20 seconds");
        Duration search = Allowance.DEFAULT.search();
        return new Allowance(allowance, search.compareTo(allowance) > 0 ? allowance : search);
    }
    private DailyOperation(Duration allowance, Duration searchAllowance, Duration reserve, Consumer<Receipt> recorder) {
        if (searchAllowance.isNegative() || searchAllowance.isZero() || reserve.isNegative()
                || searchAllowance.plus(reserve).compareTo(allowance) > 0)
            throw new IllegalArgumentException("Campaign search and reserve exceed operation allowance");
        deadline = new SearchDeadline(allowance, reserve);
        searchLimitNanos = searchAllowance.toNanos();
        expectedNanos = searchLimitNanos;
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
    public static <T> T execute(SearchAdmission admission, Allowance allowance, Supplier<T> work, Consumer<Receipt> recorder) {
        return launch(admission, () -> new Preparation<>(work, true, _ -> { }), new DailyOperation(allowance, recorder));
    }
    public static <T> T executePrepared(SearchAdmission admission, Allowance allowance, Supplier<Preparation<T>> prepare, Consumer<Receipt> recorder) {
        return launch(admission, prepare, new DailyOperation(allowance, recorder));
    }
    /**
     * Like {@link #executePrepared(SearchAdmission, Allowance, Supplier, Consumer)}, but the work waits in the queue for
     * as long as its deadline lets it start, and is refused early only when the work ahead could not end before that
     * deadline. For a remote solver: its caller already passed the scheduler's admission and sent its allowance with
     * little to spare beyond the search, so the scheduler's early refusal (which expects search time plus
     * {@link #SETTLE}) would turn every short wait into a failure.
     */
    public static <T> T executeQueued(SearchAdmission admission, Allowance allowance, Supplier<Preparation<T>> prepare, Consumer<Receipt> recorder) {
        return launch(admission, prepare, new DailyOperation(allowance, Required.value(Duration.ZERO), recorder));
    }
    private static <T> T launch(SearchAdmission admission, Duration allowance, Supplier<Preparation<T>> prepare, Consumer<Receipt> recorder) {
        DailyOperation operation = new DailyOperation(allowance, recorder);
        return launch(admission,prepare,operation);
    }
    public static <T> T executeBenchmark(SearchAdmission admission, Duration allowance, Duration searchAllowance,
            Duration reserve, Supplier<T> work, Consumer<Receipt> recorder) {
        return launch(admission, () -> new Preparation<>(work,true,_ -> { }),
                new DailyOperation(allowance,searchAllowance,reserve,recorder));
    }
    private static <T> T launch(SearchAdmission admission, Supplier<Preparation<T>> prepare, DailyOperation operation) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested daily operation would reset the allowance");
        CompletableFuture<T> completion = new CompletableFuture<>();
        // The worker logs with the caller's context (tenant, host request ID), so its receipt line correlates with the call.
        java.util.Map<String, String> context = org.slf4j.MDC.getCopyOfContextMap();
        // Admission bounds active work and its waiting queue. No application thread pool queue resets the clock.
        // A platform thread, not a virtual one: the solve holds the CPU for its whole search and never blocks, so on a
        // virtual thread it would pin a carrier the whole time, and two such solves on a two-core replica would leave no
        // carrier for the health checks and cancel watchers that are virtual.
        Thread.ofPlatform().daemon().name("daily-" + operation.id).start(() -> {
            if (context != null) org.slf4j.MDC.setContextMap(context);
            try { operation.run(admission, prepare, completion); }
            finally { org.slf4j.MDC.clear(); }
        });
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
                    try (var lease = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline, Required.value(Duration.ofNanos(expectedNanos)))) {
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
        long remaining = Math.min(searchLimitNanos - searchNanos, deadline.explorationNanos());
        long nanos = Math.min(maximum.toNanos(), remaining);
        if (nanos < 1_000_000) throw new SearchDeadline.Expired();
        // Timefold rejects sub-millisecond spent limits (observed on 2.6.0). Floor, never round up the remaining allowance.
        nanos = nanos / 1_000_000 * 1_000_000;
        phaseStarted = System.nanoTime(); phaseBudget = nanos;
        return Required.value(Duration.ofMillis(nanos / 1_000_000));
    }
    synchronized void started(Solver<?> solver) {
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
        return Math.min(searchLimitNanos - searchNanos, deadline.explorationNanos()) >= 1_000_000;
    }
    synchronized boolean isCancelled() { return cancelled; }
    public synchronized void cancel() {
        if (!cancelled) { cancelled = true; cancelledAt = deadline.elapsedMillis(); deadline.cancel(); }
        Solver<?> solver = activeSolver;
        if (solver != null) solver.terminateEarly();
    }
    private synchronized void tick() {
        if (deadline.remainingNanos() == 0) cancel();
        // Repeat termination to cover cancellation immediately before public solve() initializes its state.
        Solver<?> solver = activeSolver;
        if (solver != null && (cancelled || (phaseBudget != 0 && System.nanoTime() - phaseStarted >= phaseBudget)))
            solver.terminateEarly();
    }
    private synchronized Receipt receipt(String outcome, boolean late) {
        long elapsed = deadline.elapsedMillis();
        Long cancellation = cancelledAt;
        return new Receipt(id, outcome, elapsed, queueMs, searchNanos / 1_000_000, cancellation,
                cancellation == null ? null : Math.max(0, elapsed - cancellation), phaseCleanupOvershoot / 1_000_000,
                Math.max(0,searchNanos - searchLimitNanos) / 1_000_000, late);
    }
}
