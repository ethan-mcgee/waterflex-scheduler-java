package dev.waterflex.scheduler;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Request orchestration: snapshot, sparse legs, insertion, bounded moves, independent offer validation. */
@Component
public final class BookingSearchPipeline {
    public record Prepared(BookingSnapshotLoader.Loaded loaded, BookingSnapshot routed, BoundedBookingSearch.Result search,
            ReservationOffers.Bundle reservations, Instant expiresAt, String stopReason, long evaluatedRoutes, long reusedRoutes, long prunedArrangements,
            int optionalRefinementMillis) { }
    private final BookingSnapshotLoader loader;
    private final RoadClient roads;
    private final SnapshotRouting routing;
    private final boolean bounded;
    private final int refinementMillis;
    public BookingSearchPipeline(BookingSnapshotLoader loader, RoadClient roads, SnapshotRouting routing,
            @Value("${booking.search.bounded:false}") boolean bounded,
            @Value("${booking.search.refinement-ms:250}") int refinementMillis) {
        if (refinementMillis < 0 || refinementMillis > 1000) throw new IllegalArgumentException("Invalid optional refinement budget");
        this.loader = loader; this.roads = roads; this.routing = routing; this.bounded = bounded;
        this.refinementMillis = refinementMillis;
    }

    public Prepared prepare(String metroId, BoundedBookingSearch.Request request) {
        Instant captured = Required.value(Instant.now());
        String identity = roads.activeIdentity();
        var loaded = loader.load(metroId, request.jobId(), captured, identity);
        BookingSnapshot snapshot = routing.insertion(loaded.snapshot(), request);
        var insertion = new BoundedBookingSearch(snapshot, request, BoundedBookingSearch.Limits.defaults(), SearchDeadline::checkpoint);
        var result = insertion.search(false);
        long evaluatedRoutes = insertion.evaluatedRoutes(), reusedRoutes = insertion.reusedRoutes();
        long prunedArrangements = insertion.prunedArrangements();
        String reason = result.stopReason();
        if (bounded && !"DEADLINE".equals(result.stopReason())) {
            boolean optional = result.complete() && result.distinctRegularWindows() > snapshot.policy().regularWindowThreshold();
            long refinementStarted = System.nanoTime();
            Runnable refinementCheckpoint = () -> {
                SearchDeadline.checkpoint();
                if (optional && (System.nanoTime() - refinementStarted) / 1_000_000 >= refinementMillis)
                    throw new BoundedBookingSearch.RefinementLimit();
            };
            try {
                refinementCheckpoint.run();
                snapshot = routing.neighborhoods(snapshot, insertion.neighborhoodRoutes(), refinementCheckpoint);
                var neighborhood = new BoundedBookingSearch(snapshot, request, BoundedBookingSearch.Limits.defaults(), refinementCheckpoint);
                var refined = neighborhood.refine(result);
                result = combine(result, refined, snapshot.policy());
                evaluatedRoutes += neighborhood.evaluatedRoutes(); reusedRoutes += neighborhood.reusedRoutes();
                prunedArrangements += neighborhood.prunedArrangements();
                reason = result.stopReason();
            } catch (BoundedBookingSearch.RefinementLimit exception) {
                reason = "REFINEMENT_TIME_LIMIT";
            } catch (SearchDeadline.Expired exception) {
                reason = "DEADLINE";
            } catch (RoadClient.RoadUnavailable exception) {
                // Completed insertion candidates remain independently valid. Failure never establishes scarcity.
                reason = "ROUTING_UNAVAILABLE";
            }
        }
        SearchDeadline.beginCommit();
        Instant expiry = Required.value(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plusSeconds(600));
        var reservations = ReservationOffers.prepare(snapshot, request, loaded.holds(), result, expiry, SearchDeadline::checkpoint);
        return new Prepared(loaded, snapshot, result, reservations, expiry, reservations.completed() ? reason : "DEADLINE", evaluatedRoutes, reusedRoutes, prunedArrangements, refinementMillis);
    }

    /** Preserve independently validated insertion offers when later optional refinement runs out of time. */
    static BoundedBookingSearch.Result combine(BoundedBookingSearch.Result insertion, BoundedBookingSearch.Result refined,
            dev.waterflex.scheduler.optimizer.SchedulingPolicy.Rules policy) {
        java.util.Set<BoundedBookingSearch.Candidate> candidates = new java.util.LinkedHashSet<>(insertion.candidates());
        candidates.addAll(refined.candidates());
        int regular = Math.toIntExact(candidates.stream().filter(candidate -> candidate.overtimeDelta() <= 0)
                .map(candidate -> candidate.window()).distinct().count());
        boolean complete = insertion.complete() || refined.complete();
        // The completed insertion pass already measured the full horizon's confirmed utilization.
        long confirmed = insertion.confirmedRegularMinutes(), capacity = insertion.regularCapacityMinutes();
        java.util.Map<BoundedBookingSearch.Window, BoundedBookingSearch.Coverage> coverage = new java.util.LinkedHashMap<>();
        insertion.coverage().forEach(item -> coverage.put(item.window(), item));
        for (var item : refined.coverage()) {
            var previous = coverage.get(item.window());
            coverage.put(item.window(), previous == null ? item : new BoundedBookingSearch.Coverage(item.window(),
                    Math.max(previous.routesExamined(), item.routesExamined()),
                    previous.arrangementsExamined() + Math.max(0, item.arrangementsExamined() - 1),
                    previous.movesGenerated() + item.movesGenerated(), previous.candidateEvaluations() + item.candidateEvaluations(),
                    previous.complete() || item.complete(), item.stopReason()));
        }
        return new BoundedBookingSearch.Result(Required.value(java.util.List.copyOf(candidates)), Required.value(java.util.List.copyOf(coverage.values())), complete, regular,
                confirmed, capacity, policy.authorizeOvertime(regular, confirmed, capacity, complete), refined.stopReason());
    }
}
