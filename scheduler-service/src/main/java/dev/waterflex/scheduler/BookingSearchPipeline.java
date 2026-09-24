package dev.waterflex.scheduler;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Request orchestration: snapshot, sparse legs, insertion, bounded moves, independent offer validation. */
@Component
public final class BookingSearchPipeline {
    public record Prepared(BookingSnapshotLoader.Loaded loaded, BookingSnapshot routed, BoundedBookingSearch.Result search,
            ReservationOffers.Bundle reservations, Instant expiresAt, String stopReason) { }
    private final BookingSnapshotLoader loader;
    private final RoadClient roads;
    private final SnapshotRouting routing;
    private final boolean bounded;
    public BookingSearchPipeline(BookingSnapshotLoader loader, RoadClient roads, SnapshotRouting routing,
            @Value("${booking.search.bounded:false}") boolean bounded) {
        this.loader = loader; this.roads = roads; this.routing = routing; this.bounded = bounded;
    }

    public Prepared prepare(String metroId, BoundedBookingSearch.Request request) {
        Instant captured = Required.value(Instant.now());
        String identity = roads.activeIdentity();
        var loaded = loader.load(metroId, request.jobId(), captured, identity);
        BookingSnapshot snapshot = routing.insertion(loaded.snapshot(), request);
        var insertion = new BoundedBookingSearch(snapshot, request, BoundedBookingSearch.Limits.defaults(), SearchDeadline::checkpoint);
        var result = insertion.search(false);
        String reason = result.stopReason();
        if (bounded && !result.complete() && !"DEADLINE".equals(result.stopReason())) {
            try {
                snapshot = routing.neighborhoods(snapshot, insertion.neighborhoodRoutes());
                result = new BoundedBookingSearch(snapshot, request, BoundedBookingSearch.Limits.defaults(), SearchDeadline::checkpoint).search(true);
                reason = result.stopReason();
            } catch (SearchDeadline.Expired exception) {
                reason = "DEADLINE";
            } catch (RoadClient.RoadUnavailable exception) {
                // Completed insertion candidates remain independently valid. Failure never establishes scarcity.
                reason = "ROUTING_UNAVAILABLE";
            }
        }
        SearchDeadline.beginCommit();
        Instant expiry = Required.value(Instant.now().plusSeconds(600));
        var reservations = ReservationOffers.prepare(snapshot, request, loaded.holds(), result, expiry, SearchDeadline::checkpoint);
        return new Prepared(loaded, snapshot, result, reservations, expiry, reservations.completed() ? reason : "DEADLINE");
    }
}
