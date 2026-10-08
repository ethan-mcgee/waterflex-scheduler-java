package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.BookingSnapshot;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.ReservationState;
import dev.waterflex.scheduler.api.BookingDayState.HoldState;
import dev.waterflex.scheduler.api.BookingDayState.TechnicianState;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Brings a metro-day's stored booking state up to date with the host's current snapshot of that day. See "Booking
 * (Decided 2026-10-08)" in docs/stateless-api-design.md. Nothing is guessed: each hold either still stands exactly as
 * stored, or it is lost and reported. Pure; the caller routes the result and checks it is still feasible.
 */
public final class BookingReconciliation {
    private BookingReconciliation() { }

    /** Why a hold is no longer active. Expired and superseded holds end normally; a lost hold could not be kept. */
    public enum Ending { EXPIRED, SUPERSEDED, LOST }

    /**
     * @param day   the host's day with every remaining hold placed as a reservation, in the common arrangement
     * @param state the reconciled state, which matches {@code day}
     * @param holds the remaining holds as the booking engine's reservation metadata
     * @param ended every stored hold that is no longer active, and why
     */
    public record Result(BookingSnapshot.Day day, BookingDayState state, Map<String, ReservationState.Hold> holds, Map<String, Ending> ended) {
        public Result {
            holds = Required.value(Map.copyOf(holds));
            ended = Required.value(Map.copyOf(ended));
        }
    }

    /**
     * @param stored        the stored state, or null when the day has none
     * @param host          the host's day as {@link RequestBooking} built it: no holds, baseline in host order
     * @param requestingJob holds of this job end as superseded, since a new search replaces them
     */
    public static Result reconcile(@Nullable BookingDayState stored, BookingSnapshot.Day host, @Nullable String requestingJob, Instant now) {
        if (stored == null) return fresh(host, Required.value(Map.of()));
        Map<String, Ending> ended = new TreeMap<>();
        Map<String, HoldState> holds = new TreeMap<>();
        stored.holds().forEach((id, hold) -> {
            if (!hold.expiresAt().isAfter(now)) ended.put(id, Ending.EXPIRED);
            else if (hold.jobId().equals(requestingJob)) ended.put(id, Ending.SUPERSEDED);
            else holds.put(id, hold);
        });
        // An ended hold leaves the arrangement; the moves made for it stay, as in the portal.
        Map<String, List<String>> routes = new TreeMap<>(), expected = new TreeMap<>();
        Map<String, Long> versions = new TreeMap<>();
        stored.technicians().forEach((id, technician) -> {
            routes.put(id, Required.value(technician.route().stream().filter(visit -> !ended.containsKey(visit)).toList()));
            expected.put(id, technician.expected());
            versions.put(id, technician.version());
        });
        Set<String> technicians = new TreeSet<>(stored.technicians().keySet());
        technicians.addAll(host.technicians().keySet());
        for (String id : technicians) {
            BookingSnapshot.Technician current = host.technicians().get(id);
            List<String> hostRoute = current == null ? null : Required.value(host.baseline().routes().get(id), "host route");
            List<String> wanted = expected.get(id);
            // Rules 1 and 2: the host has exactly the appointments, in order, that the scheduler expects.
            if (current != null && hostRoute != null && hostRoute.equals(wanted)) { versions.put(id, current.scheduleVersion()); continue; }
            // Rule 3: any other change may only replace a route that no hold and no pending move depends on.
            List<String> route = routes.get(id);
            if (route != null && !route.equals(wanted)) return fresh(host, lose(ended, holds));
            if (current == null || hostRoute == null) { routes.remove(id); expected.remove(id); versions.remove(id); continue; }
            routes.put(id, hostRoute); expected.put(id, hostRoute); versions.put(id, current.scheduleVersion());
        }
        Set<String> placed = new HashSet<>();
        for (List<String> route : routes.values()) for (String visit : route) if (!holds.containsKey(visit) && !placed.add(visit)) return fresh(host, lose(ended, holds));
        if (!placed.equals(host.visits().keySet())) return fresh(host, lose(ended, holds));
        Map<String, TechnicianState> states = new TreeMap<>();
        routes.forEach((id, route) -> states.put(id, new TechnicianState(Required.value(versions.get(id), "technician version"),
                Required.value(expected.get(id), "expected route"), Required.value(route))));
        Map<String, HoldState> placedHolds = new TreeMap<>();
        holds.forEach((id, hold) -> {
            String technician = null;
            for (var entry : routes.entrySet()) if (Required.value(entry.getValue()).contains(id)) technician = entry.getKey();
            placedHolds.put(id, Required.value(technician, "technician of a remaining hold").equals(hold.technicianId()) ? hold : relocated(hold, Required.value(technician)));
        });
        var state = new BookingDayState(states, placedHolds);
        return new Result(day(host, state), state, metadata(placedHolds), ended);
    }

    /**
     * Rule 4: the reconciled arrangement no longer fits the host's current facts, so every remaining hold on the day is
     * lost and the day restarts from the host's routes.
     */
    public static Result infeasible(Result reconciled, BookingSnapshot.Day host) {
        Map<String, Ending> ended = new TreeMap<>(reconciled.ended());
        reconciled.holds().keySet().forEach(id -> ended.put(id, Ending.LOST));
        return fresh(host, ended);
    }

    /** The host's day with the state's holds placed as reservations in the common arrangement. */
    static BookingSnapshot.Day day(BookingSnapshot.Day host, BookingDayState state) {
        Map<String, BookingSnapshot.Visit> visits = new TreeMap<>(host.visits());
        state.holds().forEach((id, hold) -> visits.put(id, Required.value(hold).visit(Required.value(id))));
        return new BookingSnapshot.Day(host.technicians(), visits, state.arrangement(), host.reservationVersion(), host.roads());
    }

    static Map<String, ReservationState.Hold> metadata(Map<String, HoldState> holds) {
        Map<String, ReservationState.Hold> metadata = new TreeMap<>();
        holds.forEach((id, hold) -> metadata.put(id, new ReservationState.Hold(hold.jobId(), hold.offerId(), hold.expiresAt(), false)));
        return metadata;
    }

    private static HoldState relocated(HoldState hold, String technician) {
        return new HoldState(hold.jobId(), hold.offerId(), hold.offerSetId(), hold.expiresAt(), hold.serviceId(), hold.windowStart(), hold.windowEnd(),
                hold.durationMinutes(), hold.lat(), hold.lng(), hold.plannedStart(), technician);
    }

    private static Map<String, Ending> lose(Map<String, Ending> ended, Map<String, HoldState> remaining) {
        Map<String, Ending> all = new TreeMap<>(ended);
        remaining.keySet().forEach(id -> all.put(id, Ending.LOST));
        return all;
    }

    private static Result fresh(BookingSnapshot.Day host, Map<String, Ending> ended) {
        return new Result(host, BookingDayState.of(host), Required.value(Map.of()), ended);
    }
}
