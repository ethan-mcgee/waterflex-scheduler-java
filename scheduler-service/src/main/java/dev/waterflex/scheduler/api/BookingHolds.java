package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.api.DailyProposals.Reply;
import dev.waterflex.scheduler.api.PublicApiStore.*;
import dev.waterflex.scheduler.api.PublicResponses.*;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * POST /api/v1/booking/offers/{offerId}/select and /release. Neither searches: each edits the stored day states under
 * row locks. Every answer is final, so it is stored and replayed for a repeated requestId.
 */
@Service
public class BookingHolds {
    private final PublicApiStore store;
    private final BookingStore bookings;
    private final Clock clock;

    @Autowired
    public BookingHolds(PublicApiStore store, BookingStore bookings) { this(store, bookings, Required.value(Clock.systemUTC())); }

    public BookingHolds(PublicApiStore store, BookingStore bookings, Clock clock) { this.store = store; this.bookings = bookings; this.clock = clock; }

    public Reply select(String tenantId, String offerId, String body) {
        return handle(tenantId, offerId, body, Operation.BOOKING_SELECT);
    }

    public Reply release(String tenantId, String offerId, String body) {
        return handle(tenantId, offerId, body, Operation.BOOKING_RELEASE);
    }

    private Reply handle(String tenantId, String offerId, String body, Operation operation) {
        PublicRequests.RequestOnly request;
        try {
            Input.id(offerId, "offerId");
            request = PublicRequests.read(body, PublicRequests.RequestOnly.class);
        } catch (IllegalArgumentException invalid) {
            return Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, DailyProposals.invalidRequest(invalid)));
        }
        String fingerprint = DailyProposals.sha256(offerId + "\n" + CalculationJson.write(request));
        return switch (store.claim(tenantId, request.requestId(), operation, fingerprint)) {
            case Replay replay -> replayed(replay, operation);
            case Conflict _ -> Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, "requestId was already used with a different request"));
            case Busy _ -> Reply.of(429, new Problem(ErrorCode.BUSY, "A request with this requestId is still running"));
            case Started started -> run(tenantId, request.requestId(), started.ownerToken(), offerId, operation);
        };
    }

    private Reply run(String tenantId, String requestId, String owner, String offerId, Operation operation) {
        try {
            BookingStore.HoldOutcome outcome = operation == Operation.BOOKING_SELECT
                    ? bookings.select(tenantId, requestId, owner, offerId, Required.value(clock.instant()))
                    : bookings.release(tenantId, requestId, owner, offerId);
            return switch (outcome) {
                case BookingStore.Kept kept -> Reply.of(200, kept.hold());
                case BookingStore.Released _ -> Reply.of(200, new Released(true));
                case BookingStore.NotFound _ -> Reply.of(404, new Problem(ErrorCode.NOT_FOUND, "Offer not found"));
                case BookingStore.Unavailable unavailable -> Reply.of(409, new Problem(ErrorCode.HOLD_UNAVAILABLE, unavailable.reason()));
            };
        } catch (RuntimeException unexpected) {
            store.release(tenantId, requestId, owner);
            throw unexpected;
        }
    }

    /** A stored answer is validated into its typed body before it is sent again. */
    private static Reply replayed(Replay replay, Operation operation) {
        Object body;
        try {
            if (replay.status() != 200) body = PublicRequests.read(replay.json(), Problem.class);
            else if (operation == Operation.BOOKING_SELECT) body = PublicRequests.read(replay.json(), Hold.class);
            else body = PublicRequests.read(replay.json(), Released.class);
        } catch (IllegalArgumentException invalid) { throw new IllegalStateException("A stored response failed validation", invalid); }
        return Reply.of(replay.status(), body);
    }
}
