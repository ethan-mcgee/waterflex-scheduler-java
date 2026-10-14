package dev.waterflex.scheduler;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded instance-wide expensive-work admission. Queued bookings precede background work. */
@Component
public final class SearchAdmission {
    public enum Kind { BOOKING, BACKGROUND }
    public static final class Busy extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient @Nullable Duration retryAfter;
        public Busy(String message) { this(message, null); }
        /** A refusal that knows when a retry could start: the estimated wait for the work ahead of it. */
        public Busy(String message, @Nullable Duration retryAfter) { super(message); this.retryAfter = retryAfter; }
        /** The estimated wait before a retry could start, or null when the refusal does not know it. */
        public @Nullable Duration retryAfter() { return retryAfter; }
    }
    /**
     * A queued request, with how long background work expects to hold its slot (zero for a booking). Compared by
     * identity, so two requests expecting the same time are never mistaken for each other in a queue.
     */
    private static final class Ticket {
        private final long expectedNanos;
        Ticket(long expectedNanos) { this.expectedNanos = expectedNanos; }
        long expectedNanos() { return expectedNanos; }
    }
    private final int capacity;
    private final int queueLimit;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = Required.value(lock.newCondition());
    private final Deque<Ticket> bookings = new ArrayDeque<>();
    private final Deque<Ticket> background = new ArrayDeque<>();
    private int active;
    private boolean backgroundActive;
    /** When the running background work expects to finish (System.nanoTime), while one runs. */
    private long backgroundEndsAt;
    private final java.util.concurrent.atomic.LongAdder rejections = new java.util.concurrent.atomic.LongAdder();
    /**
     * Requests refused because capacity and queue were full, because background work could not start in time, or
     * because the deadline passed while waiting.
     */
    public long rejections() { return rejections.sum(); }
    public record State(int active, int queuedBookings, int queuedBackground, boolean backgroundActive) { }
    public State state() {
        lock.lock();
        try { return new State(active, bookings.size(), background.size(), backgroundActive); }
        finally { lock.unlock(); }
    }

    public SearchAdmission(@Value("${scheduler.search.capacity:0}") int configuredCapacity,
                           @Value("${scheduler.search.queue-limit:16}") int queueLimit) {
        if (configuredCapacity < 0 || queueLimit < 0 || queueLimit > 16)
            throw new IllegalArgumentException("Invalid search admission limits");
        this.capacity = configuredCapacity == 0 ? (Runtime.getRuntime().availableProcessors() <= 1 ? 1 : 2) : configuredCapacity;
        this.queueLimit = queueLimit;
    }

    public Lease acquire(Kind kind, SearchDeadline deadline) {
        return acquire(kind, deadline, Required.value(Duration.ZERO));
    }

    /**
     * Admits work of this kind within the deadline. Background work names how long it expects to hold its slot: one that
     * could not start while that much of its deadline remains is refused at once, with the estimated wait as its retry
     * time, instead of queueing until its deadline passes.
     */
    public Lease acquire(Kind kind, SearchDeadline deadline, Duration expected) {
        if (expected.isNegative() || (kind == Kind.BOOKING && !expected.isZero())) throw new IllegalArgumentException("Invalid expected duration");
        long started = System.nanoTime();
        Ticket ticket = new Ticket(expected.toNanos());
        boolean locked = false;
        Deque<Ticket> queue = kind == Kind.BOOKING ? bookings : background;
        try {
            deadline.requireTime();
            locked = lock.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            if (!locked) throw new Busy("Search admission deadline exhausted");
            deadline.requireTime();
            if (canStart(kind) && queue.isEmpty()) return start(kind, started, ticket);
            if (bookings.size() + background.size() >= queueLimit) throw new Busy("Search queue is full");
            if (kind == Kind.BACKGROUND) {
                long wait = backgroundWaitNanos(System.nanoTime());
                if (wait > deadline.remainingNanos() - ticket.expectedNanos())
                    throw new Busy("Background work ahead would not finish in time", Required.value(Duration.ofNanos(wait)));
            }
            queue.addLast(ticket);
            while (true) {
                deadline.requireTime();
                // Background work that can no longer finish once it starts (queued bookings go first) leaves the queue
                // now with a retry time, rather than waiting until its deadline passes.
                if (kind == Kind.BACKGROUND && !(queue.peekFirst() == ticket && canStart(kind)) && deadline.remainingNanos() < ticket.expectedNanos())
                    throw new Busy("Background work could not start in time", Required.value(Duration.ofNanos(Math.max(TimeUnit.SECONDS.toNanos(1),
                            backgroundWaitNanos(System.nanoTime()) - ticket.expectedNanos()))));
                if (queue.peekFirst() == ticket && canStart(kind)) {
                    queue.removeFirst();
                    return start(kind, started, ticket);
                }
                changed.awaitNanos(Math.min(deadline.remainingNanos(), TimeUnit.MILLISECONDS.toNanos(50)));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Busy("Search admission cancelled");
        } catch (Busy busy) {
            rejections.increment();
            throw busy;
        } catch (SearchDeadline.Expired expired) {
            // The deadline passed before the work could start: a refusal like a full queue, though it took longer.
            rejections.increment();
            throw expired;
        } finally {
            if (locked) {
                queue.remove(ticket);
                changed.signalAll();
                lock.unlock();
            }
        }
    }

    /**
     * Refuses background work that could not start within {@code wait}, before its caller spends time preparing it;
     * the refusal carries the estimated wait.
     */
    public void requireBackgroundStartWithin(Duration wait) {
        lock.lock();
        try {
            long estimate = backgroundWaitNanos(System.nanoTime());
            if (estimate > wait.toNanos()) {
                rejections.increment();
                throw new Busy("Background work ahead would not finish in time", Required.value(Duration.ofNanos(estimate)));
            }
        } finally { lock.unlock(); }
    }

    /**
     * The estimated wait before new background work could start: what the running background work expects to have left,
     * plus what the queued background work expects. Background work runs one at a time.
     */
    private long backgroundWaitNanos(long now) {
        long wait = backgroundActive ? Math.max(0, backgroundEndsAt - now) : 0;
        for (Ticket queued : background) wait += queued.expectedNanos();
        return wait;
    }

    private boolean canStart(Kind kind) {
        return active < capacity && (kind == Kind.BOOKING || (!backgroundActive && bookings.isEmpty()));
    }
    private Lease start(Kind kind, long started, Ticket ticket) {
        active++;
        if (kind == Kind.BACKGROUND) { backgroundActive = true; backgroundEndsAt = System.nanoTime() + ticket.expectedNanos(); }
        return new Lease(kind, (System.nanoTime() - started) / 1_000_000);
    }
    public final class Lease implements AutoCloseable {
        private final Kind kind;
        private final long queueMillis;
        private boolean closed;
        private Lease(Kind kind, long queueMillis) { this.kind = kind; this.queueMillis = queueMillis; }
        public long queueMillis() { return queueMillis; }
        @Override public void close() {
            lock.lock();
            try {
                if (closed) return;
                closed = true;
                active--;
                if (kind == Kind.BACKGROUND) backgroundActive = false;
                changed.signalAll();
            } finally { lock.unlock(); }
        }
    }
}
