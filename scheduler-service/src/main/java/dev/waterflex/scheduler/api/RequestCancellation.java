package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.api.PublicResponses.ErrorCode;
import dev.waterflex.scheduler.api.PublicResponses.Problem;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * {@code DELETE /api/v1/requests/{requestId}}: cancels the tenant's calculation from any replica. A calculation running
 * here stops at once. A queued daily or repair proposal that is still waiting ends at once; a running one is stopped by
 * the worker running it. Any other calculation in progress elsewhere has the cancel recorded on its claim, and the
 * replica running it stops it within a second ({@link CancelWatcher}).
 */
@Service
public class RequestCancellation {
    private final RunningRequests running;
    private final @Nullable DailyJobs jobs;
    private final @Nullable PublicApiStore store;

    /** Without the database: only calculations running on this replica can be cancelled. */
    public RequestCancellation(RunningRequests running) { this(running, null, null); }

    public RequestCancellation(RunningRequests running, @Nullable DailyJobs jobs) { this(running, jobs, null); }

    @Autowired
    public RequestCancellation(RunningRequests running, @Nullable DailyJobs jobs, @Nullable PublicApiStore store) {
        this.running = running; this.jobs = jobs; this.store = store;
    }

    /**
     * Null when the cancellation was recorded (202 without a body); otherwise the problem to answer. The cancelled call
     * itself answers 409 CANCELLED once its calculation stops; a cancelled queued request reports it when polled.
     */
    public DailyProposals.@Nullable Reply cancel(String tenantId, String requestId) {
        try { Input.requestId(requestId); }
        catch (IllegalArgumentException invalid) { return DailyProposals.Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, "requestId must be a lowercase canonical UUID")); }
        boolean here = running.cancel(tenantId, requestId);
        DailyJobs queue = jobs;
        if (queue != null && queue.cancel(tenantId, requestId, DailyJobs.result(409, DailyProposals.cancelled(), null)) != DailyJobs.Cancelled.NONE) return null;
        if (here) return null;
        PublicApiStore claims = store;
        if (claims != null && claims.requestCancel(tenantId, requestId)) return null;
        return DailyProposals.Reply.of(404, new Problem(ErrorCode.NOT_FOUND, "No calculation with this requestId is queued or in progress"));
    }
}
