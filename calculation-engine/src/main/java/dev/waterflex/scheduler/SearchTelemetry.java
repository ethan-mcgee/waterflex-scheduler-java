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
            @Nullable Long foregroundCpuNanos, long routingNanos, long calculationNanos, long admissionNanos, @Nullable Long commitNanos) { }
    private final long cpuStarted = cpu();
    private final long ownerThread = Thread.currentThread().threadId();
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
    /** Wall time the call spent in the calculation engine (embedded or remote). */
    public void calculation(long nanos) { calculationNanos += Math.max(0, nanos); }
    /** Wall time the call waited for a search slot. */
    public void admission(long nanos) { admissionNanos += Math.max(0, nanos); }
    /** Wall time the call spent in its commit phase, inside the deadline's reserve (validating, preparing and publishing). */
    public void commit(long nanos) { commitNanos = (commitNanos == null ? 0 : commitNanos) + Math.max(0, nanos); }
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
