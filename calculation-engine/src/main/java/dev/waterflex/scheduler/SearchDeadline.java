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
    /** The longest request clock: a client's longest daily search with its reserve fits, as do two minute bookings. */
    static final Duration MAXIMUM = Required.value(Duration.ofMinutes(5));
    private final LongSupplier clock;
    private final long started;
    private final SearchTelemetry telemetry = new SearchTelemetry();
    public SearchTelemetry telemetry() { return telemetry; }
    private long durationNanos;
    private final long reserveNanos;
    private boolean committing;
    /** When the current commit phase began, or -1 outside one; its length is attributed when it ends. */
    private long commitStarted = -1;
    private volatile boolean cancelled;

    /** The reserve kept back from exploration for the commit phase when none is given. */
    public static final Duration DEFAULT_RESERVE = Required.value(Duration.ofSeconds(1));

    public SearchDeadline(Duration duration) { this(duration, System::nanoTime); }
    SearchDeadline(Duration duration, LongSupplier clock) {
        this(duration, DEFAULT_RESERVE, clock);
    }
    /** An explicit reserve: the operator's publish reserve for a booking, or an offline campaign's. */
    public SearchDeadline(Duration duration, Duration reserve) { this(duration, reserve, System::nanoTime); }
    SearchDeadline(Duration duration, Duration reserve, LongSupplier clock) {
        if (duration.isNegative() || duration.isZero() || duration.compareTo(MAXIMUM) > 0)
            throw new IllegalArgumentException("Invalid search duration");
        this.clock = clock;
        this.started = clock.getAsLong();
        this.durationNanos = duration.toNanos();
        if (reserve.isNegative()) throw new IllegalArgumentException("Negative deadline reserve");
        reserveNanos = reserve.toNanos();
    }
    /** The reserve kept back from exploration, in nanoseconds. */
    public long reserveNanos() { return reserveNanos; }
    /** Closes an open commit phase, attributing its wall time to the telemetry. */
    private void endCommit() {
        if (commitStarted < 0) return;
        telemetry.commit(clock.getAsLong() - commitStarted);
        commitStarted = -1;
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
        finally {
            CURRENT.remove();
            endCommit();
            java.util.function.Consumer<SearchTelemetry.Measurements> listener = observer;
            if (listener != null) listener.accept(telemetry.snapshot());
        }
    }

    private static volatile java.util.function.@Nullable Consumer<SearchTelemetry.Measurements> observer;

    /**
     * Receives every finished deadline's measurements on the thread that ran it, whether the work returned or threw
     * (the request log reads them). One observer per process; null removes it.
     */
    public static void observe(java.util.function.@Nullable Consumer<SearchTelemetry.Measurements> listener) { observer = listener; }
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
        if (current == null) return;
        current.committing = true;
        if (current.commitStarted < 0) current.commitStarted = current.clock.getAsLong();
        current.requireTime();
    }
    public static void beginExploration() {
        SearchDeadline current = CURRENT.get();
        if (current != null) { current.committing = false; current.endCommit(); checkpoint(); }
    }
}
