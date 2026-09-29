package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Worker cadence only; does not change analysis budgets or approval policy. */
@Component
public final class TimeOffPolling {
    private final long delayMs;

    public TimeOffPolling(@Value("${time-off.poll-delay-ms:30000}") @Nullable String configured) {
        if (configured == null || !configured.matches("[0-9]+")) throw invalid();
        try { delayMs = Long.parseLong(configured); }
        catch (NumberFormatException exception) { throw invalid(); }
        if (delayMs <= 0) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("time-off.poll-delay-ms must be a positive integer in milliseconds");
    }

    public long delayMs() { return delayMs; }
}
