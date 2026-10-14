package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.api.PublicResponses.ErrorCode;
import dev.waterflex.scheduler.api.PublicResponses.Problem;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * {@code DELETE /api/v1/requests/{requestId}}: cancels the tenant's calculation. A queued daily or repair proposal is
 * cancelled from any replica: a waiting job ends at once, and a running one is stopped by the worker running it. Any
 * other calculation is cancelled only if it runs on this replica.
 */
@Service
public class RequestCancellation {
    private final RunningRequests running;
    private final @Nullable DailyJobs jobs;

    /** Without the durable queue: only calculations running on this replica can be cancelled. */
    public RequestCancellation(RunningRequests running) { this(running, null); }

    @Autowired
    public RequestCancellation(RunningRequests running, @Nullable DailyJobs jobs) { this.running = running; this.jobs = jobs; }

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
        return DailyProposals.Reply.of(404, new Problem(ErrorCode.NOT_FOUND, "No calculation with this requestId is queued, or running on this scheduler"));
    }
}
