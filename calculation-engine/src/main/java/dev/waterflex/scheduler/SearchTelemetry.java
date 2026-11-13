package dev.waterflex.scheduler;

import java.lang.management.ManagementFactory;
import org.jspecify.annotations.Nullable;

/**
 * Foreground request measurements; coalesced road workers are deliberately not attributed twice. Wall time is kept for
 * the two parts a call waits on most, routing (identity checks, leg fetches and geometry, including the cache reads
 * around them) and the calculation engine, so a slow call can be attributed from its log line.
 */
public final class SearchTelemetry {
    /** {@code commitNanos} is null for a call that never began its commit phase (it failed or finished before it). */
    public record Measurements(long databaseExecutions, long databaseNanos, long lockStatementNanos,
            long routingPairs, long memoryHits, long persistentHits, long sharedRoutingPairs,
            @Nullable Long foregroundCpuNanos, long routingNanos, long calculationNanos, long admissionNanos, @Nullable Long commitNanos) {
        /**
         * One call's measurements from two deadlines, such as a daily proposal's routing on the request thread and its
         * search on a worker. Commit time is null only when neither committed; CPU time is null when either thread's is
         * unknown, so a partial CPU figure is never reported as the whole.
         */
        public Measurements plus(Measurements other) {
            Long commit = commitNanos, otherCommit = other.commitNanos();
            Long cpu = foregroundCpuNanos, otherCpu = other.foregroundCpuNanos();
            return new Measurements(databaseExecutions + other.databaseExecutions(), databaseNanos + other.databaseNanos(),
                    lockStatementNanos + other.lockStatementNanos(), routingPairs + other.routingPairs(), memoryHits + other.memoryHits(),
                    persistentHits + other.persistentHits(), sharedRoutingPairs + other.sharedRoutingPairs(),
                    cpu == null || otherCpu == null ? null : cpu + otherCpu,
                    routingNanos + other.routingNanos(), calculationNanos + other.calculationNanos(), admissionNanos + other.admissionNanos(),
                    commit == null && otherCommit == null ? null : (commit == null ? 0L : commit) + (otherCommit == null ? 0L : otherCommit));
        }
    }
    /** CPU time is measured on the owner thread: the one that created the telemetry, or the one that adopted it. */
    private long cpuStarted = cpu();
    private long ownerThread = Thread.currentThread().threadId();

    /**
     * Makes the calling thread the owner, so a worker that runs the whole deadline reports its own CPU time. Call it on
     * that worker before any work is measured.
     */
    public void adoptCurrentThread() {
        cpuStarted = cpu();
        ownerThread = Thread.currentThread().threadId();
    }
    private long databaseExecutions, databaseNanos, lockStatementNanos, routingPairs, memoryHits, persistentHits, sharedRoutingPairs;
    private long routingNanos, calculationNanos, admissionNanos;
    private @Nullable Long commitNanos;
    public void database(long nanos, boolean locking) {
        databaseExecutions++; databaseNanos += nanos; if (locking) lockStatementNanos += nanos;
    }
    public void routing(long pairs, long memory, long persistent, long shared) {
        routingPairs += pairs; memoryHits += memory; persistentHits += persistent; sharedRoutingPairs += shared;
    }
    /** Wall time the call spent waiting on routing. */
    public void routingWait(long nanos) { routingNanos += Math.max(0, nanos); }
    /** Wall time the call spent in the calculation engine. */
    public void calculation(long nanos) { calculationNanos += Math.max(0, nanos); }
    /** Wall time the call waited for a search slot. */
    public void admission(long nanos) { admissionNanos += Math.max(0, nanos); }
    /** Wall time the call spent in its commit phase, inside the deadline's reserve (validating, preparing and publishing). */
    public void commit(long nanos) {
        Long before = commitNanos;  // Read once: null analysis cannot carry a check on a field across the expression.
        commitNanos = (before == null ? 0L : before) + Math.max(0, nanos);
    }
    public Measurements snapshot() {
        long now = cpu();
        return new Measurements(databaseExecutions, databaseNanos, lockStatementNanos, routingPairs, memoryHits, persistentHits,
                sharedRoutingPairs, cpuStarted < 0 || now < 0 || Thread.currentThread().threadId() != ownerThread ? null : Math.max(0, now - cpuStarted),
                routingNanos, calculationNanos, admissionNanos, commitNanos);
    }
    private static long cpu() {
        var bean = ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled() ? bean.getCurrentThreadCpuTime() : -1;
    }
}
