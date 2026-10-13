package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.api.PublicResponses.ErrorCode;
import dev.waterflex.scheduler.api.PublicResponses.Problem;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** {@code DELETE /api/v1/requests/{requestId}}: cancels the tenant's calculation if it is running on this replica. */
@Service
public class RequestCancellation {
    private final RunningRequests running;

    public RequestCancellation(RunningRequests running) { this.running = running; }

    /**
     * Null when the cancellation was recorded (202 without a body); otherwise the problem to answer. The cancelled call
     * itself answers 409 CANCELLED once its calculation stops.
     */
    public DailyProposals.@Nullable Reply cancel(String tenantId, String requestId) {
        try { Input.requestId(requestId); }
        catch (IllegalArgumentException invalid) { return DailyProposals.Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, "requestId must be a lowercase canonical UUID")); }
        if (running.cancel(tenantId, requestId)) return null;
        return DailyProposals.Reply.of(404, new Problem(ErrorCode.NOT_FOUND, "No calculation with this requestId is running on this scheduler"));
    }
}
