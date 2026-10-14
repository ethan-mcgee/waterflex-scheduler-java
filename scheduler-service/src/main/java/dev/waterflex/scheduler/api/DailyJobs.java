package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.DatabaseFacts;
import dev.waterflex.scheduler.DeadlineSignals;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.api.PublicApiStore.Operation;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The durable queue of asynchronous daily and repair proposals ({@code api_daily_job}). A job holds its request's
 * idempotency claim from the moment it is queued; a worker on any replica takes it with
 * {@code scheduler_next_daily_job}, which chooses round-robin across tenants and skips jobs other workers hold. Every
 * other call runs as the job's tenant, under row-level security.
 */
@Component
public class DailyJobs {
    public enum State { QUEUED, RUNNING, DONE }

    /** The tenant already has this many jobs waiting or running; the host retries later. */
    public static final class Full extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Full(int limit) { super("This tenant already has " + limit + " daily calculations queued or running"); }
    }

    /** A job a worker took: run it, or end it at once when it waited longer than the queue allows. */
    public record Taken(String tenantId, String requestId, String ownerToken, Operation operation, int attempts, boolean expired) {
        public Taken {
            Input.requestId(requestId);
            if (operation != Operation.DAILY_PROPOSAL && operation != Operation.REPAIR_PROPOSAL) throw new IllegalArgumentException("Not a queued operation: " + operation);
            if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
        }
    }

    /** A job's state for the host; {@code result} is set only for a run that ended without a stored answer. */
    public record Status(Operation operation, State state, Instant queuedAt, @Nullable Instant startedAt, @Nullable Result result) {
        public Status {
            if ((result != null) && state != State.DONE) throw new IllegalArgumentException("Only a finished job has a result");
        }
    }

    /** An answer that was not stored with the request: the same requestId sent again runs again. */
    public record Result(int status, String json, @Nullable Integer retryAfterSeconds) {
        public Result {
            if (status < 400 || status > 599) throw new IllegalArgumentException("A job result is an error status");
            if (retryAfterSeconds != null && (retryAfterSeconds < 1 || retryAfterSeconds > 300)) throw new IllegalArgumentException("retryAfterSeconds must be 1 to 300");
        }
    }

    /** What a cancellation found. */
    public enum Cancelled { QUEUED, RUNNING, NONE }

    private final PublicApiStore store;
    private final JdbcTemplate jdbc;

    public DailyJobs(PublicApiStore store, JdbcTemplate jdbc) { this.store = store; this.jdbc = jdbc; }

    /**
     * Queues a claimed request, or queues it again after an earlier run ended without a stored answer. Refused with
     * {@link Full} when the tenant already has {@code limit} jobs waiting or running.
     */
    public void enqueue(String tenantId, String requestId, String ownerToken, Operation operation, String requestJson, Duration lease, int limit) {
        if (operation != Operation.DAILY_PROPOSAL && operation != Operation.REPAIR_PROPOSAL) throw new IllegalArgumentException("Not a queued operation: " + operation);
        long seconds = lease.toSeconds();
        if (seconds < 60 || seconds > 3600) throw new IllegalArgumentException("A job lease is 60 to 3600 seconds");
        if (limit < 1) throw new IllegalArgumentException("The queue limit is at least 1");
        store.asTenant(tenantId, () -> {
            Integer waiting = DatabaseFacts.query(jdbc, "SELECT count(*)::int FROM api_daily_job WHERE \"tenantId\"=? AND state<>'DONE'", Integer.class, tenantId);
            if (waiting >= limit) throw new Full(limit);
            int written = jdbc.update("INSERT INTO api_daily_job (\"tenantId\",\"requestId\",operation,\"requestJson\",\"ownerToken\",state,\"leaseSeconds\") "
                    + "VALUES (?,?,?,?::jsonb,?,'QUEUED',?) ON CONFLICT (\"tenantId\",\"requestId\") DO UPDATE SET operation=EXCLUDED.operation,"
                    + "\"requestJson\"=EXCLUDED.\"requestJson\",\"ownerToken\"=EXCLUDED.\"ownerToken\",state='QUEUED',\"leaseSeconds\"=EXCLUDED.\"leaseSeconds\","
                    + "\"queuedAt\"=clock_timestamp(),\"notBefore\"=clock_timestamp(),\"startedAt\"=NULL,\"leaseExpiresAt\"=NULL,worker=NULL,attempts=0,"
                    + "\"cancelRequested\"=false,\"finishedAt\"=NULL,\"resultStatus\"=NULL,\"resultJson\"=NULL,\"retryAfterSeconds\"=NULL",
                    tenantId, requestId, operation.name(), requestJson, ownerToken, Math.toIntExact(seconds));
            // The claim was just granted to this owner. An older job row for the same requestId is either finished or
            // lost its claim when that claim's lease passed; the new owner token makes the old run's writes miss.
            if (written != 1) throw new IllegalStateException("The job was not queued");
            return Boolean.TRUE;
        });
    }

    /** Takes the next job for this worker, or null when none is waiting. Runs as the scheduler's own login. */
    public @Nullable Taken next(String worker, Duration maxWait) {
        try {
            List<Taken> taken = jdbc.query("SELECT job_tenant, job_request, job_owner, job_operation, job_attempts, job_expired "
                    + "FROM scheduler_next_daily_job(?, make_interval(secs => ?))", (rs, _) -> new Taken(DatabaseFacts.string(rs, 1), DatabaseFacts.string(rs, 2),
                    DatabaseFacts.string(rs, 3), Operation.valueOf(DatabaseFacts.string(rs, 4)), DatabaseFacts.integer(rs, 5), DatabaseFacts.bool(rs, 6)),
                    worker, maxWait.toSeconds());
            if (taken.size() > 1) throw new IllegalStateException("scheduler_next_daily_job returned more than one job");
            return taken.isEmpty() ? null : taken.getFirst();
        } catch (org.springframework.dao.DataAccessException failure) {
            throw DeadlineSignals.unwrap(failure);
        }
    }

    /** The request a job runs, as queued. */
    public String request(Taken job) {
        return store.asTenant(job.tenantId(), () -> {
            List<String> found = jdbc.query("SELECT \"requestJson\"::text FROM api_daily_job WHERE \"tenantId\"=? AND \"requestId\"=? AND \"ownerToken\"=? AND state='RUNNING'",
                    (rs, _) -> DatabaseFacts.string(rs, 1), job.tenantId(), job.requestId(), job.ownerToken());
            if (found.size() != 1) throw new IllegalStateException("The running job " + job.requestId() + " is gone");
            return Required.value(found.getFirst());
        });
    }

    /** Whether the host asked to cancel the running job. */
    public boolean cancelRequested(Taken job) {
        return store.asTenant(job.tenantId(), () -> {
            List<Boolean> found = jdbc.query("SELECT \"cancelRequested\" FROM api_daily_job WHERE \"tenantId\"=? AND \"requestId\"=? AND \"ownerToken\"=?",
                    (rs, _) -> DatabaseFacts.bool(rs, 1), job.tenantId(), job.requestId(), job.ownerToken());
            return !found.isEmpty() && Required.value(found.getFirst());
        });
    }

    /** Closes a job whose answer is stored with its request (201, or an error that repeats on every retry). */
    public void finish(Taken job) {
        store.asTenant(job.tenantId(), () -> {
            jdbc.update("UPDATE api_daily_job SET state='DONE',\"finishedAt\"=clock_timestamp() WHERE \"tenantId\"=? AND \"requestId\"=? AND \"ownerToken\"=? AND state='RUNNING'",
                    job.tenantId(), job.requestId(), job.ownerToken());
            return Boolean.TRUE;
        });
    }

    /**
     * Closes a job that ended without a stored answer and releases its claim, atomically, so the host sees the result
     * when it polls and the same requestId sent again runs again.
     */
    public void finishReleased(Taken job, Result result) {
        store.asTenant(job.tenantId(), () -> {
            int closed = jdbc.update("UPDATE api_daily_job SET state='DONE',\"finishedAt\"=clock_timestamp(),\"resultStatus\"=?,\"resultJson\"=?::jsonb,\"retryAfterSeconds\"=? "
                    + "WHERE \"tenantId\"=? AND \"requestId\"=? AND \"ownerToken\"=? AND state='RUNNING'",
                    result.status(), result.json(), result.retryAfterSeconds(), job.tenantId(), job.requestId(), job.ownerToken());
            if (closed == 1)
                jdbc.update("DELETE FROM api_request WHERE \"tenantId\"=? AND \"requestId\"=? AND \"ownerToken\"=? AND state='IN_PROGRESS'",
                        job.tenantId(), job.requestId(), job.ownerToken());
            return Boolean.TRUE;
        });
    }

    /**
     * Puts a job back in the queue, to be taken after {@code delay}; it keeps its claim. A run that never started (no
     * calculation slot here) does not count as an attempt.
     */
    public void requeue(Taken job, Duration delay, boolean countAttempt) {
        store.asTenant(job.tenantId(), () -> {
            jdbc.update("UPDATE api_daily_job SET state='QUEUED',worker=NULL,\"leaseExpiresAt\"=NULL,\"notBefore\"=clock_timestamp()+make_interval(secs => ?),"
                    + "attempts=CASE WHEN ? THEN attempts ELSE greatest(attempts-1,0) END "
                    + "WHERE \"tenantId\"=? AND \"requestId\"=? AND \"ownerToken\"=? AND state='RUNNING'",
                    Math.max(0, delay.toMillis()) / 1000.0, countAttempt, job.tenantId(), job.requestId(), job.ownerToken());
            return Boolean.TRUE;
        });
    }

    /**
     * Cancels the tenant's job from any replica: a waiting job ends now with {@code result} and its claim is released;
     * a running one is flagged for the worker running it, which stops it.
     */
    public Cancelled cancel(String tenantId, String requestId, Result result) {
        return store.asTenant(tenantId, () -> {
            List<String> owners = jdbc.query("UPDATE api_daily_job SET state='DONE',\"finishedAt\"=clock_timestamp(),\"resultStatus\"=?,\"resultJson\"=?::jsonb,\"retryAfterSeconds\"=NULL "
                    + "WHERE \"tenantId\"=? AND \"requestId\"=? AND state='QUEUED' RETURNING \"ownerToken\"", (rs, _) -> DatabaseFacts.string(rs, 1),
                    result.status(), result.json(), tenantId, requestId);
            if (!owners.isEmpty()) {
                jdbc.update("DELETE FROM api_request WHERE \"tenantId\"=? AND \"requestId\"=? AND \"ownerToken\"=? AND state='IN_PROGRESS'",
                        tenantId, requestId, owners.getFirst());
                return Cancelled.QUEUED;
            }
            int flagged = jdbc.update("UPDATE api_daily_job SET \"cancelRequested\"=true WHERE \"tenantId\"=? AND \"requestId\"=? AND state='RUNNING'", tenantId, requestId);
            return flagged == 1 ? Cancelled.RUNNING : Cancelled.NONE;
        });
    }

    /** The tenant's job for this requestId, or null when it has none. */
    public @Nullable Status status(String tenantId, String requestId) {
        return store.asTenant(tenantId, () -> {
            List<Status> found = jdbc.query("SELECT operation,state,\"queuedAt\",\"startedAt\",\"resultStatus\",\"resultJson\"::text,\"retryAfterSeconds\" "
                    + "FROM api_daily_job WHERE \"tenantId\"=? AND \"requestId\"=?", (rs, _) -> {
                Timestamp started = rs.getTimestamp(4);
                String json = rs.getString(6);
                Long retry = DatabaseFacts.nullableLong(rs, 7);
                Result result = json == null ? null : new Result(DatabaseFacts.integer(rs, 5), json, retry == null ? null : Math.toIntExact(retry));
                return new Status(Operation.valueOf(DatabaseFacts.string(rs, 1)), State.valueOf(DatabaseFacts.string(rs, 2)),
                        Required.value(DatabaseFacts.timestamp(rs, 3).toInstant()), started == null ? null : started.toInstant(), result);
            }, tenantId, requestId);
            return new Found(found.isEmpty() ? null : found.getFirst());
        }).value();
    }

    /** Lets {@link PublicApiStore#asTenant} carry an absent status, since the transaction result itself is never null. */
    private record Found(@Nullable Status value) { }

    /** A job result for a problem answer. */
    static Result result(int status, PublicResponses.Problem problem, @Nullable Integer retryAfterSeconds) {
        return new Result(status, CalculationJson.write(problem), retryAfterSeconds);
    }
}
