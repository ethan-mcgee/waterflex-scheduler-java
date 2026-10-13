package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;

/**
 * The search deadline and admission signals raised while a database connection is acquired ({@link SearchDatabaseTelemetry})
 * reach callers wrapped by Spring ({@code CannotCreateTransactionException}, {@code CannotGetJdbcConnectionException}).
 * Unwrapped, they keep their meaning: the call answers 429 or 503 and releases its request claim, instead of failing
 * as an unexpected 500.
 */
public final class DeadlineSignals {
    private DeadlineSignals() { }

    /** The first {@link SearchAdmission.Busy} or {@link SearchDeadline.Expired} in the failure's cause chain, else the failure. */
    public static RuntimeException unwrap(RuntimeException failure) {
        for (@Nullable Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SearchAdmission.Busy busy) return busy;
            if (cause instanceof SearchDeadline.Expired expired) return expired;
            if (cause.getCause() == cause) break;
        }
        return failure;
    }
}
