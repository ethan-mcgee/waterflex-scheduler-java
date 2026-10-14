package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RequestLogging;
import dev.waterflex.scheduler.SearchAdmission;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * This replica's workers for the asynchronous daily queue. A worker takes a job only when no daily calculation runs or
 * waits on this replica (daily calculations run one at a time per replica), so a job never sits behind another here
 * while a replica with a free slot could take it. Bookings keep their priority in admission: the job routes its day
 * first, then waits for a slot behind any queued booking, as a synchronous call does. While a job runs, the worker checks once a second whether the host cancelled it
 * from any replica, and stops it if so.
 */
@Component
public class DailyQueueWorker implements SmartLifecycle {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(DailyQueueWorker.class));
    /** How often a running job's cancel flag is read. */
    static final Duration CANCEL_CHECK = Required.value(Duration.ofSeconds(1));
    /** The wait after a job failed unexpectedly before it is taken again. */
    static final Duration FAILURE_BACKOFF = Required.value(Duration.ofSeconds(30));

    private final DailyJobs jobs;
    private final DailyProposals proposals;
    private final DailyQueue queue;
    private final SearchAdmission admission;
    private final RunningRequests running;
    private final String name;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean stopping;
    private final LongAdder started = new LongAdder();
    private final LongAdder expired = new LongAdder();
    private final LongAdder failed = new LongAdder();

    public DailyQueueWorker(DailyJobs jobs, DailyProposals proposals, DailyQueue queue, SearchAdmission admission, RunningRequests running) {
        this.jobs = jobs; this.proposals = proposals; this.queue = queue; this.admission = admission; this.running = running;
        this.name = host() + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String host() {
        try { return Required.value(InetAddress.getLocalHost().getHostName()); }
        catch (java.io.IOException unknown) { return "scheduler"; }
    }

    /** Jobs this replica started, ended because they waited too long, and failed unexpectedly. */
    public long started() { return started.sum(); }
    public long expired() { return expired.sum(); }
    public long failed() { return failed.sum(); }

    @Override
    public synchronized void start() {
        if (!threads.isEmpty()) return;
        stopping = false;
        for (int index = 0; index < queue.workers(); index++) {
            String worker = name + "#" + index;
            Thread thread = Thread.ofPlatform().name("daily-queue-" + index).daemon(true).unstarted(() -> loop(worker));
            threads.add(thread);
            thread.start();
        }
        if (queue.workers() > 0) LOG.info("Daily queue: {} worker(s) as {}", queue.workers(), name);
        else LOG.info("Daily queue: no workers on this replica; queued jobs run on other replicas");
    }

    /**
     * Lets each worker finish the job it is running (interrupting it would record a failure the host did not cause),
     * and wakes idle ones. A job still running when the process ends is taken again elsewhere once its lease passes.
     */
    @Override
    public synchronized void stop() {
        stopping = true;
        for (int index = 0; index < threads.size(); index++) queue.queued();
        for (Thread thread : threads) {
            try { thread.join(Required.value(Duration.ofSeconds(5)).toMillis()); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        threads.clear();
    }

    @Override public synchronized boolean isRunning() { return !threads.isEmpty(); }

    private void loop(String worker) {
        while (!stopping) {
            try {
                if (!idle()) { Thread.sleep(50); continue; }
                if (!runOnce(worker)) queue.awaitQueued(queue.poll());
            } catch (InterruptedException interrupted) {
                if (stopping) return;
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                // The database or a store call failed outside any job; wait a poll interval and look again.
                LOG.error("Daily queue worker {} failed; it looks again shortly", worker, failure);
                try { Thread.sleep(queue.poll().toMillis()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    /** Whether no daily calculation runs or waits on this replica. */
    private boolean idle() {
        SearchAdmission.State state = admission.state();
        return !state.backgroundActive() && state.queuedBackground() == 0;
    }

    /** Takes and runs one job; false when none was waiting. */
    boolean runOnce(String worker) {
        DailyJobs.Taken job = jobs.next(worker, queue.maxWait());
        if (job == null) return false;
        MDC.put(RequestLogging.TENANT, job.tenantId());
        MDC.put(RequestLogging.REQUEST_ID, job.requestId());
        MDC.put(RequestLogging.OPERATION, "QUEUED " + job.operation());
        long begun = System.nanoTime();
        Thread watcher = job.expired() ? null : Thread.ofVirtual().name("daily-queue-cancel").start(() -> watch(job));
        try {
            if (job.expired()) expired.increment(); else started.increment();
            proposals.runJob(job);
            LOG.info("Daily queue job finished in {} ms (attempt {})", (System.nanoTime() - begun) / 1_000_000, job.attempts());
        } catch (RuntimeException failure) {
            failed.increment();
            LOG.error("Daily queue job failed; it is taken again after {} s", FAILURE_BACKOFF.toSeconds(), failure);
            try { jobs.requeue(job, FAILURE_BACKOFF, true); }
            catch (RuntimeException requeue) { LOG.error("Could not queue the failed job again; its lease ends it", requeue); }
        } finally {
            if (watcher != null) watcher.interrupt();
            MDC.remove(RequestLogging.TENANT); MDC.remove(RequestLogging.REQUEST_ID); MDC.remove(RequestLogging.OPERATION);
        }
        return true;
    }

    /** Stops the running job when the host cancels it from any replica; the cancel may come before the run registers. */
    private void watch(DailyJobs.Taken job) {
        boolean cancelled = false;
        while (!cancelled) {
            try { Thread.sleep(CANCEL_CHECK.toMillis()); }
            catch (InterruptedException finished) { return; }
            try { cancelled = jobs.cancelRequested(job) && running.cancel(job.tenantId(), job.requestId()); }
            catch (RuntimeException failure) { LOG.warn("Could not read the cancel flag of job {}", job.requestId(), failure); }
        }
    }

    /** The name this replica's workers give the queue. */
    String name() { return name; }
}
