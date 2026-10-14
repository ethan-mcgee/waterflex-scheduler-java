package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.ReplicaRole;
import dev.waterflex.scheduler.Required;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Settings of the asynchronous daily queue, and the signal that wakes this replica's workers when it queues a job (so
 * a job queued here starts without waiting for the next poll; jobs queued on other replicas are found by polling).
 */
@Component
public class DailyQueue {
    /** Workers on this replica; 0 (always on a booking replica) leaves the queue to other replicas. */
    private final int workers;
    /** How often an idle worker looks for a job queued on another replica. */
    private final Duration poll;
    /** A job that waited this long without starting ends with 429 BUSY instead of running late. */
    private final Duration maxWait;
    /** Jobs one tenant may have waiting or running. */
    private final int tenantLimit;
    private final Semaphore wake = new Semaphore(0);

    @Autowired
    public DailyQueue(@Value("${scheduler.daily.queue.workers:1}") int workers, @Value("${scheduler.daily.queue.poll-ms:500}") long pollMillis,
                      @Value("${scheduler.daily.queue.max-wait-minutes:15}") long maxWaitMinutes, @Value("${scheduler.daily.queue.tenant-limit:50}") int tenantLimit,
                      ReplicaRole role) {
        this(role.runsDailyQueue() ? workers : 0, Required.value(Duration.ofMillis(pollMillis)), Required.value(Duration.ofMinutes(maxWaitMinutes)), tenantLimit);
        if (workers < 0 || workers > 8) throw new IllegalArgumentException("scheduler.daily.queue.workers must be 0 to 8");
    }

    public DailyQueue(int workers, Duration poll, Duration maxWait, int tenantLimit) {
        if (workers < 0 || workers > 8) throw new IllegalArgumentException("scheduler.daily.queue.workers must be 0 to 8");
        if (poll.toMillis() < 50 || poll.toMillis() > 60_000) throw new IllegalArgumentException("scheduler.daily.queue.poll-ms must be 50 to 60000");
        if (maxWait.toMinutes() < 1 || maxWait.toMinutes() > 24 * 60) throw new IllegalArgumentException("scheduler.daily.queue.max-wait-minutes must be 1 to 1440");
        if (tenantLimit < 1 || tenantLimit > 10_000) throw new IllegalArgumentException("scheduler.daily.queue.tenant-limit must be 1 to 10000");
        this.workers = workers; this.poll = poll; this.maxWait = maxWait; this.tenantLimit = tenantLimit;
    }

    public int workers() { return workers; }
    public Duration poll() { return poll; }
    public Duration maxWait() { return maxWait; }
    public int tenantLimit() { return tenantLimit; }

    /** Wakes one idle worker on this replica. */
    void queued() { wake.release(); }

    /** Waits up to {@code timeout} for a job queued on this replica; true when woken. */
    boolean awaitQueued(Duration timeout) throws InterruptedException {
        boolean woken = wake.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
        wake.drainPermits();
        return woken;
    }
}
