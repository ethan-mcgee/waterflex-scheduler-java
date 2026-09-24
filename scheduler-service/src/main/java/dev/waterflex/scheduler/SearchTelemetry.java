package dev.waterflex.scheduler;

import java.lang.management.ManagementFactory;
import org.jspecify.annotations.Nullable;

/** Foreground request measurements; coalesced road workers are deliberately not attributed twice. */
public final class SearchTelemetry {
    public record Measurements(long databaseExecutions, long databaseNanos, long lockStatementNanos,
            long routingPairs, long memoryHits, long persistentHits, long sharedRoutingPairs,
            @Nullable Long foregroundCpuNanos) { }
    private final long cpuStarted = cpu();
    private final long ownerThread = Thread.currentThread().threadId();
    private long databaseExecutions, databaseNanos, lockStatementNanos, routingPairs, memoryHits, persistentHits, sharedRoutingPairs;
    public void database(long nanos, boolean locking) {
        databaseExecutions++; databaseNanos += nanos; if (locking) lockStatementNanos += nanos;
    }
    public void routing(long pairs, long memory, long persistent, long shared) {
        routingPairs += pairs; memoryHits += memory; persistentHits += persistent; sharedRoutingPairs += shared;
    }
    public Measurements snapshot() {
        long now = cpu();
        return new Measurements(databaseExecutions, databaseNanos, lockStatementNanos, routingPairs, memoryHits, persistentHits,
                sharedRoutingPairs, cpuStarted < 0 || now < 0 || Thread.currentThread().threadId() != ownerThread ? null : Math.max(0, now - cpuStarted));
    }
    private static long cpu() {
        var bean = ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled() ? bean.getCurrentThreadCpuTime() : -1;
    }
}
