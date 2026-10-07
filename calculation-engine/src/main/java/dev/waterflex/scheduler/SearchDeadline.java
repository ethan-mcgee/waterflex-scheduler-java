package dev.waterflex.scheduler;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** One monotonic budget, including admission time. Instances belong to one request. */
public final class SearchDeadline {
    private static final ThreadLocal<SearchDeadline> CURRENT = new ThreadLocal<>();
    public static final class Expired extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Expired() { super("Appointment search deadline exhausted"); }
    }
    private final LongSupplier clock;
    private final long started;
    private final SearchTelemetry telemetry = new SearchTelemetry();
    public SearchTelemetry telemetry() { return telemetry; }
    private long durationNanos;
    private boolean committing;
    private boolean durable;
    private volatile @Nullable Long bestCostDeltaCents;
    public @Nullable Long bestCostDeltaCents() { return bestCostDeltaCents; }
    public static void incumbent(long costDeltaCents) {
        SearchDeadline value = CURRENT.get();
        if (value != null) {
            Long previous = value.bestCostDeltaCents;
            if (previous == null || costDeltaCents < previous) value.bestCostDeltaCents = costDeltaCents;
        }
    }
    private final java.util.concurrent.atomic.AtomicLong work = new java.util.concurrent.atomic.AtomicLong();
    private volatile String phase = "SNAPSHOT";
    public long completedWork() { return work.get(); }
    public String phase() { return phase; }
    public static void progress(String phase) {
        SearchDeadline value = CURRENT.get();
        if (value != null) { value.phase = phase; value.work.incrementAndGet(); }
    }
    public SearchDeadline durable() { durable = true; return this; }
    public static boolean isDurable() { SearchDeadline value = CURRENT.get(); return value != null && value.durable; }
    private volatile boolean cancelled;
    private Runnable commitGuard = () -> { };
    private java.util.function.Consumer<String> reservationRecorder = _ -> { };

    public SearchDeadline(Duration duration) { this(duration, System::nanoTime); }
    SearchDeadline(Duration duration, LongSupplier clock) {
        if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("Invalid search duration");
        this.clock = clock;
        this.started = clock.getAsLong();
        this.durationNanos = duration.toNanos();
    }
    public long elapsedMillis() { return Math.max(0, clock.getAsLong() - started) / 1_000_000; }
    public long remainingNanos() { return Math.max(0, durationNanos - (clock.getAsLong() - started)); }
    public long explorationNanos() { return Math.max(0, remainingNanos() - 1_000_000_000L); }
    public void requireTime() {
        if (cancelled || remainingNanos() == 0 || Thread.currentThread().isInterrupted()) throw new Expired();
    }
    public void cancel() { cancelled = true; }
    void cancellationGuard(Runnable guard, java.util.function.Consumer<String> recorder) { commitGuard = guard; reservationRecorder = recorder; }
    public static void beforeCommit() {
        SearchDeadline current = CURRENT.get();
        if (current != null) { current.requireTime(); current.commitGuard.run(); current.requireTime(); }
    }
    public static void reservedSet(String id) {
        SearchDeadline current = CURRENT.get();
        if (current != null) { current.requireTime(); current.reservationRecorder.accept(id); }
    }
    public Duration timeout(Duration maximum) {
        requireTime();
        long remaining = committing ? remainingNanos() : explorationNanos();
        if (remaining == 0) throw new Expired();
        return Required.value(Duration.ofNanos(Math.min(remaining, maximum.toNanos())));
    }
    public <T> T within(Supplier<T> work) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested booking deadline");
        CURRENT.set(this);
        try { return work.get(); }
        finally { CURRENT.remove(); }
    }
    /** Calculation scoring must depend on facts, not a caller's request clock. */
    public static <T> T withoutRequestClock(Supplier<T> work) {
        SearchDeadline previous = CURRENT.get();
        CURRENT.remove();
        try { return work.get(); }
        finally { if (previous != null) CURRENT.set(previous); }
    }
    public static @Nullable SearchDeadline current() { return CURRENT.get(); }
    public static Duration networkTimeout(Duration maximum) {
        SearchDeadline current = CURRENT.get();
        return current == null ? maximum : current.timeout(maximum);
    }
    public static void checkpoint() {
        SearchDeadline current = CURRENT.get();
        if (current != null) current.timeout(Required.value(Duration.ofMinutes(1)));
    }
    public static void beginCommit() {
        SearchDeadline current = CURRENT.get();
        if (current != null) { current.committing = true; current.requireTime(); }
    }
    public static void beginExploration() {
        SearchDeadline current = CURRENT.get();
        if (current != null) { current.committing = false; current.bestCostDeltaCents = null; checkpoint(); }
    }
    public static void policyLimit(int millis) {
        if (millis < 1000 || millis > 5000) throw new IllegalArgumentException("Invalid booking deadline policy");
        SearchDeadline current = CURRENT.get();
        if (current != null && !current.durable) {
            current.durationNanos = Math.min(current.durationNanos, millis * 1_000_000L);
            current.requireTime();
        }
    }
}
