package dev.waterflex.scheduler;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded instance-wide expensive-work admission. Queued bookings precede background work. */
@Component
public final class SearchAdmission {
    public enum Kind { BOOKING, BACKGROUND }
    public static final class Busy extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Busy(String message) { super(message); }
    }
    private final int capacity;
    private final int queueLimit;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = Required.value(lock.newCondition());
    private final Deque<Object> bookings = new ArrayDeque<>();
    private final Deque<Object> background = new ArrayDeque<>();
    private int active;
    private boolean backgroundActive;
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
        long started = System.nanoTime();
        Object ticket = new Object();
        boolean locked = false;
        Deque<Object> queue = kind == Kind.BOOKING ? bookings : background;
        try {
            deadline.requireTime();
            locked = lock.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            if (!locked) throw new Busy("Search admission deadline exhausted");
            if (canStart(kind) && queue.isEmpty()) return start(kind, started);
            if (bookings.size() + background.size() >= queueLimit) throw new Busy("Search queue is full");
            queue.addLast(ticket);
            while (true) {
                deadline.requireTime();
                if (queue.peekFirst() == ticket && canStart(kind)) {
                    queue.removeFirst();
                    return start(kind, started);
                }
                changed.awaitNanos(deadline.remainingNanos());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Busy("Search admission cancelled");
        } finally {
            if (locked) {
                queue.remove(ticket);
                changed.signalAll();
                lock.unlock();
            }
        }
    }

    private boolean canStart(Kind kind) {
        return active < capacity && (kind == Kind.BOOKING || (!backgroundActive && bookings.isEmpty()));
    }
    private Lease start(Kind kind, long started) {
        active++;
        if (kind == Kind.BACKGROUND) backgroundActive = true;
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
