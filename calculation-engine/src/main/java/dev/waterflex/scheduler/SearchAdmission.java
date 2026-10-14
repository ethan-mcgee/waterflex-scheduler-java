package dev.waterflex.scheduler;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounded instance-wide expensive-work admission. Queued bookings precede background work. Bookings that name their
 * client share the slots fairly: a waiting booking of the client with the fewest bookings running starts first (of
 * clients with as many, the one whose last booking started longest ago), and
 * when the queue is full, a client with fewer waiting takes the place of the newest booking of the client with the
 * most, so one client's burst cannot shut another client out. Background work runs at most
 * {@code scheduler.search.background-limit} at a time (one by default; a replica that runs only daily work may allow
 * more, up to its capacity).
 */
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
        /** The client a booking runs for; null shares one unnamed client. */
        private final @Nullable String client;
        /** Set when a lighter client took this ticket's place in a full queue. */
        private boolean evicted;
        Ticket(long expectedNanos, @Nullable String client) { this.expectedNanos = expectedNanos; this.client = client; }
        long expectedNanos() { return expectedNanos; }
        String share() { String named = client; return named == null ? "" : named; }
    }
    private final int capacity;
    private final int queueLimit;
    /** Background work that may run at once. */
    private final int backgroundLimit;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = Required.value(lock.newCondition());
    private final Deque<Ticket> bookings = new ArrayDeque<>();
    private final Deque<Ticket> background = new ArrayDeque<>();
    private int active;
    /** The running background work, each with when it expects to finish. */
    private final List<Lease> runningBackground = new ArrayList<>();
    /** Bookings running per client share (the unnamed share is the empty string). */
    private final Map<String, Integer> runningBookings = new HashMap<>();
    /** When each client share's most recent booking started (System.nanoTime). */
    private final Map<String, Long> lastBookingStart = new HashMap<>();
    private final java.util.concurrent.atomic.LongAdder evictions = new java.util.concurrent.atomic.LongAdder();
    /** Queued bookings that gave their place to a booking of a client with fewer waiting (each is also a rejection). */
    public long evictions() { return evictions.sum(); }
    private final java.util.concurrent.atomic.LongAdder rejections = new java.util.concurrent.atomic.LongAdder();
    /**
     * Requests refused because capacity and queue were full, because background work could not start in time, or
     * because the deadline passed while waiting.
     */
    public long rejections() { return rejections.sum(); }
    public record State(int active, int queuedBookings, int queuedBackground, boolean backgroundActive) { }
    public State state() {
        lock.lock();
        try { return new State(active, bookings.size(), background.size(), !runningBackground.isEmpty()); }
        finally { lock.unlock(); }
    }

    /**
     * True when a background lane is free and no background work waits for one. Bookings are not counted: background
     * work taken now still waits behind them for a slot, as it should.
     */
    public boolean backgroundSlotFree() {
        lock.lock();
        try { return runningBackground.size() < backgroundLimit && background.isEmpty(); }
        finally { lock.unlock(); }
    }

    public int backgroundLimit() { return backgroundLimit; }

    /** One background job at a time. */
    public SearchAdmission(int configuredCapacity, int queueLimit) { this(configuredCapacity, queueLimit, 1); }

    @Autowired
    public SearchAdmission(@Value("${scheduler.search.capacity:0}") int configuredCapacity,
                           @Value("${scheduler.search.queue-limit:16}") int queueLimit,
                           @Value("${scheduler.search.background-limit:1}") int backgroundLimit) {
        if (configuredCapacity < 0 || queueLimit < 0 || queueLimit > 16)
            throw new IllegalArgumentException("Invalid search admission limits");
        this.capacity = configuredCapacity == 0 ? (Runtime.getRuntime().availableProcessors() <= 1 ? 1 : 2) : configuredCapacity;
        if (backgroundLimit < 1 || backgroundLimit > capacity)
            throw new IllegalArgumentException("scheduler.search.background-limit must be 1 to the search capacity (" + capacity + ")");
        this.queueLimit = queueLimit;
        this.backgroundLimit = backgroundLimit;
    }

    public Lease acquire(Kind kind, SearchDeadline deadline) {
        return acquire(kind, deadline, Required.value(Duration.ZERO));
    }

    /** Admits a booking for this client, sharing the booking slots fairly with other clients' bookings. */
    public Lease acquireBooking(SearchDeadline deadline, String client) {
        if (client.isEmpty()) throw new IllegalArgumentException("A booking's client must be named");
        return acquire(Kind.BOOKING, deadline, Required.value(Duration.ZERO), client);
    }

    /**
     * Admits work of this kind within the deadline. Background work names how long it expects to hold its slot: one that
     * could not start while that much of its deadline remains is refused at once, with the estimated wait as its retry
     * time, instead of queueing until its deadline passes.
     */
    public Lease acquire(Kind kind, SearchDeadline deadline, Duration expected) {
        return acquire(kind, deadline, expected, null);
    }

    private Lease acquire(Kind kind, SearchDeadline deadline, Duration expected, @Nullable String client) {
        if (expected.isNegative() || (kind == Kind.BOOKING && !expected.isZero())) throw new IllegalArgumentException("Invalid expected duration");
        long started = System.nanoTime();
        Ticket ticket = new Ticket(expected.toNanos(), client);
        boolean locked = false;
        Deque<Ticket> queue = kind == Kind.BOOKING ? bookings : background;
        try {
            deadline.requireTime();
            locked = lock.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            if (!locked) throw new Busy("Search admission deadline exhausted");
            deadline.requireTime();
            if (canStart(kind) && queue.isEmpty()) return start(kind, started, ticket);
            if (bookings.size() + background.size() >= queueLimit && !(kind == Kind.BOOKING && evictFor(ticket))) throw new Busy("Search queue is full");
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
                if (ticket.evicted) throw new Busy("Search queue is full; a booking of a client with fewer waiting took this place");
                if (next(kind) == ticket && canStart(kind)) {
                    queue.remove(ticket);
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
     * plus what the queued background work expects, spread over the background lanes: each queued job takes the lane
     * that frees first, and new work waits for the first lane free after them.
     */
    private long backgroundWaitNanos(long now) {
        long[] lanes = new long[backgroundLimit];
        for (int lane = 0; lane < runningBackground.size() && lane < backgroundLimit; lane++)
            lanes[lane] = Math.max(0, Required.value(runningBackground.get(lane)).endsAt - now);
        for (Ticket queued : background) {
            Arrays.sort(lanes);
            lanes[0] += queued.expectedNanos();
        }
        Arrays.sort(lanes);
        return lanes[0];
    }

    /**
     * The queued request to start next: the oldest background request, or the booking of the client with the fewest
     * bookings running; of clients with as many, the one whose last booking started longest ago (or never); within a
     * client, its oldest booking.
     */
    private @Nullable Ticket next(Kind kind) {
        if (kind == Kind.BACKGROUND) return background.peekFirst();
        Ticket chosen = null;
        int fewest = Integer.MAX_VALUE;
        long earliest = Long.MAX_VALUE;
        for (Ticket queued : bookings) {
            int count = runningBookings.getOrDefault(queued.share(), 0);
            Long last = lastBookingStart.get(queued.share());
            // Never started sorts first; nanoTime values are compared by difference, so they are made relative.
            long since = last == null ? Long.MIN_VALUE : last - startOrigin;
            if (count < fewest || (count == fewest && since < earliest)) { chosen = queued; fewest = count; earliest = since; }
        }
        return chosen;
    }

    /** The reference for {@link #lastBookingStart}, so start times compare as differences from it. */
    private final long startOrigin = System.nanoTime();

    /**
     * Makes room in a full queue for this booking when its client has fewer bookings waiting than the client with the
     * most: that client's newest waiting booking leaves with Busy. False when no client waits more than this one would.
     */
    private boolean evictFor(Ticket arriving) {
        Map<String, Integer> waiting = new HashMap<>();
        for (Ticket queued : bookings) increment(waiting, queued.share());
        String heaviest = null;
        int most = 0;
        for (var entry : waiting.entrySet()) if (entry.getValue() > most) { heaviest = entry.getKey(); most = entry.getValue(); }
        if (heaviest == null || waiting.getOrDefault(arriving.share(), 0) + 1 >= most) return false;
        var newest = bookings.descendingIterator();
        while (newest.hasNext()) {
            Ticket queued = Required.value(newest.next());
            if (queued.share().equals(heaviest)) {
                newest.remove();
                queued.evicted = true;
                evictions.increment();
                changed.signalAll();
                return true;
            }
        }
        return false;
    }

    private static void increment(Map<String, Integer> counts, String key) {
        Integer current = counts.get(key);
        counts.put(key, current == null ? 1 : current + 1);
    }

    private boolean canStart(Kind kind) {
        return active < capacity && (kind == Kind.BOOKING || (runningBackground.size() < backgroundLimit && bookings.isEmpty()));
    }
    private Lease start(Kind kind, long started, Ticket ticket) {
        active++;
        long now = System.nanoTime();
        Lease lease = new Lease(kind, ticket.share(), (now - started) / 1_000_000, now + ticket.expectedNanos());
        if (kind == Kind.BACKGROUND) runningBackground.add(lease);
        else {
            increment(runningBookings, ticket.share());
            lastBookingStart.put(ticket.share(), now);
        }
        return lease;
    }
    public final class Lease implements AutoCloseable {
        private final Kind kind;
        private final String share;
        private final long queueMillis;
        /** When background work expects to finish (System.nanoTime). */
        private final long endsAt;
        private boolean closed;
        private Lease(Kind kind, String share, long queueMillis, long endsAt) { this.kind = kind; this.share = share; this.queueMillis = queueMillis; this.endsAt = endsAt; }
        public long queueMillis() { return queueMillis; }
        @Override public void close() {
            lock.lock();
            try {
                if (closed) return;
                closed = true;
                active--;
                if (kind == Kind.BACKGROUND) runningBackground.remove(this);
                else {
                    Integer running = runningBookings.get(share);
                    if (running == null || running <= 1) runningBookings.remove(share);
                    else runningBookings.put(share, running - 1);
                }
                changed.signalAll();
            } finally { lock.unlock(); }
        }
    }
}
