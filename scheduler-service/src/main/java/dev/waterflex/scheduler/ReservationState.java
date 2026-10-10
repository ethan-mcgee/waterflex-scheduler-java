package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.Arrangement;
import dev.waterflex.scheduler.optimizer.RouteEvaluator;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Restart-safe common arrangement. Persisted JSON is validated before any schedule use. */
public record ReservationState(String configurationFingerprint, String routingIdentity,
        Map<String, Long> scheduleVersions, Arrangement arrangement, Map<String, Hold> holds,
        Map<String, List<RouteEvaluator.WorkingSegment>> segments) {
    public record Hold(String jobId, String offerId, Instant expiresAt, boolean overtimeAuthorized) {
        public Hold {
            if (jobId.isBlank() || offerId.isBlank()) throw invalid();
        }
    }
    public ReservationState {
        if (configurationFingerprint.isBlank() || routingIdentity.isBlank()) throw invalid();
        scheduleVersions = Required.value(Map.copyOf(scheduleVersions));
        holds = Required.value(Map.copyOf(holds));
        if (!scheduleVersions.keySet().equals(arrangement.routes().keySet()) || !segments.keySet().equals(arrangement.routes().keySet())) throw invalid();
        for (Long version : scheduleVersions.values()) if (version < 0) throw invalid();
        Set<String> assigned = new HashSet<>();
        Map<String, List<RouteEvaluator.WorkingSegment>> copied = new TreeMap<>();
        for (var entry : arrangement.routes().entrySet()) {
            assigned.addAll(entry.getValue());
            List<String> segmented = new ArrayList<>();
            List<RouteEvaluator.WorkingSegment> routeSegments = new ArrayList<>();
            Instant previous = Instant.MIN;
            for (RouteEvaluator.WorkingSegment segment : Required.value(segments.get(entry.getKey()))) {
                if (segment.visitIds().isEmpty() || segment.departure().isAfter(segment.returnedAt()) || segment.departure().isBefore(previous)) throw invalid();
                previous = segment.returnedAt();
                var copy = new RouteEvaluator.WorkingSegment(segment.departure(), segment.returnedAt(), Required.value(List.copyOf(segment.visitIds())));
                routeSegments.add(copy);
                segmented.addAll(copy.visitIds());
            }
            if (!segmented.equals(entry.getValue())) throw invalid();
            copied.put(entry.getKey(), Required.value(List.copyOf(routeSegments)));
        }
        if (!assigned.containsAll(holds.keySet())) throw invalid();
        segments = Required.value(Collections.unmodifiableMap(copied));
    }

    /** The only factory used before saving new arrangements independently evaluates all stops. */
    public static ReservationState validate(BookingSnapshot.Day day, BookingSnapshot.Rates rates, Arrangement arrangement,
            Map<String, BookingSnapshot.Visit> visits, Map<String, Hold> holds, String configuration, String routingIdentity) {
        Set<String> reservations = new HashSet<>();
        for (var visit : visits.values()) if (visit.reservation()) {
            reservations.add(visit.id());
            Hold hold = Required.value(holds.get(visit.id()), "reservation metadata");
            if (!visit.jobId().equals(hold.jobId())) throw invalid();
        }
        if (!reservations.equals(holds.keySet())) throw invalid();
        var result = day.evaluate(arrangement, visits, rates);
        if (!result.feasible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation arrangement is infeasible");
        Map<String, Long> versions = new TreeMap<>();
        day.technicians().forEach((id, technician) -> versions.put(id, technician.scheduleVersion()));
        return new ReservationState(configuration, routingIdentity, versions, arrangement, holds, result.segments());
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Invalid persisted reservation arrangement");
    }
}
