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
    private final long reserveNanos;
    private boolean committing;
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
    private volatile boolean cancelled;

    public SearchDeadline(Duration duration) { this(duration, System::nanoTime); }
    SearchDeadline(Duration duration, LongSupplier clock) {
        this(duration, Required.value(Duration.ofSeconds(1)), clock);
    }
    /** Explicit offline campaign reserve; normal requests retain their one second reserve. */
    public SearchDeadline(Duration duration, Duration reserve) { this(duration, reserve, System::nanoTime); }
    private SearchDeadline(Duration duration, Duration reserve, LongSupplier clock) {
        if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("Invalid search duration");
        this.clock = clock;
        this.started = clock.getAsLong();
        this.durationNanos = duration.toNanos();
        if (reserve.isNegative()) throw new IllegalArgumentException("Negative deadline reserve");
        reserveNanos = reserve.toNanos();
    }
    public long elapsedMillis() { return Math.max(0, clock.getAsLong() - started) / 1_000_000; }
    public long remainingNanos() { return Math.max(0, durationNanos - (clock.getAsLong() - started)); }
    public long explorationNanos() { return Math.max(0, remainingNanos() - reserveNanos); }
    public void requireTime() {
        if (cancelled || remainingNanos() == 0 || Thread.currentThread().isInterrupted()) throw new Expired();
    }
    public void cancel() { cancelled = true; }
    public static void beforeCommit() {
        SearchDeadline current = CURRENT.get();
        if (current != null) current.requireTime();
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
        if (current != null) {
            current.durationNanos = Math.min(current.durationNanos, millis * 1_000_000L);
            current.requireTime();
        }
    }
}
