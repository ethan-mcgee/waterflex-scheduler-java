package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SearchDeadline;
import dev.waterflex.scheduler.optimizer.DailyOperation;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The calculations this replica is running, by tenant and host request ID, so the host can cancel one. A cancel that
 * reaches another replica is recorded on the request's claim and found here by {@link CancelWatcher}. Cancellation is
 * best effort: a calculation that is not cancelled in time still stops at its own deadline.
 */
@Component
public class RunningRequests {
    /** A running request: the tenant and the host's request ID. */
    public record Key(String tenantId, String requestId) { }

    /** One running request. Cancelling it ends whatever calculation is attached, now or once one is. */
    public static final class Running {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile @Nullable DailyOperation daily;
        private volatile @Nullable SearchDeadline deadline;

        Running() { }

        public boolean cancelled() { return cancelled.get(); }

        /** Attaches the daily operation running this request; a cancel that came first ends it at once. */
        public void attach(@Nullable DailyOperation operation) {
            daily = operation;
            if (operation != null && cancelled.get()) operation.cancel();
        }

        /** Attaches the deadline bounding this request; a cancel that came first ends it at once. */
        public void attach(SearchDeadline searchDeadline) {
            deadline = searchDeadline;
            if (cancelled.get()) searchDeadline.cancel();
        }

        void cancel() {
            cancelled.set(true);
            DailyOperation operation = daily;
            if (operation != null) operation.cancel();
            SearchDeadline searchDeadline = deadline;
            if (searchDeadline != null) searchDeadline.cancel();
        }
    }

    /** The request ID is already running here, which its claim should have prevented; the caller retries. */
    public static final class AlreadyRunning extends RuntimeException {
        private static final long serialVersionUID = 1L;
        AlreadyRunning() { super("A request with this requestId is still running"); }
    }

    private final ConcurrentHashMap<Key, Running> running = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.LongAdder cancellations = new java.util.concurrent.atomic.LongAdder();

    /** Cancellations that found their request running here. */
    public long cancellations() { return cancellations.sum(); }

    /** Registers a request that has claimed its request ID. Always {@link #finish} it. */
    public Running start(String tenantId, String requestId) {
        Running started = new Running();
        if (running.putIfAbsent(new Key(tenantId, requestId), started) != null) throw new AlreadyRunning();
        return started;
    }

    public void finish(String tenantId, String requestId, Running finished) {
        running.remove(new Key(tenantId, requestId), finished);
    }

    /** Cancels the tenant's request if it is running on this replica; false when it is not. */
    public boolean cancel(String tenantId, String requestId) {
        Running found = running.get(new Key(tenantId, requestId));
        if (found == null) return false;
        cancellations.increment();
        found.cancel();
        return true;
    }

    /**
     * Stops the tenant's request running here because this replica is shutting down. Not a host cancellation, so it is
     * not counted as one; false when the request is not running here.
     */
    public boolean stop(String tenantId, String requestId) {
        Running found = running.get(new Key(tenantId, requestId));
        if (found == null) return false;
        found.cancel();
        return true;
    }

    /** The requests running here now. */
    public java.util.List<Key> keys() { return Required.value(java.util.List.copyOf(running.keySet())); }

    /** How many requests are running here. */
    int size() { return running.size(); }
}
