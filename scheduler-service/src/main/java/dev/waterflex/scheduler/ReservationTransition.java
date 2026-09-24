package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.RouteEvaluator;
import java.time.LocalDate;
import java.util.*;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Prepares release/confirmation outside the commit transaction, including all sibling dates. */
@Component
public final class ReservationTransition {
    public record Confirmation(String holdId, String appointmentId) { }
    public record Prepared(Day day, Map<String, ReservationState.Hold> holds, RouteEvaluator.Result validation) {
        public Prepared { holds = Required.value(Map.copyOf(holds)); }
    }
    private final SnapshotRouting routing;
    public ReservationTransition(SnapshotRouting routing) { this.routing = routing; }

    public Map<LocalDate, Prepared> prepare(BookingSnapshotLoader.Facts snapshot, String jobId, @Nullable Confirmation confirmation) {
        Map<LocalDate, Prepared> result = new TreeMap<>();
        boolean selected = false;
        for (var entry : snapshot.days().entrySet()) {
            SearchDeadline.checkpoint();
            LocalDate date = Required.value(entry.getKey());
            Day day = Required.value(entry.getValue());
            Map<String, ReservationState.Hold> holds = new TreeMap<>(Required.value(snapshot.holds().get(date), "reservation metadata"));
            Set<String> removed = new HashSet<>();
            holds.forEach((id, hold) -> { if (hold.jobId().equals(jobId)) removed.add(id); });
            Map<String, Visit> facts = new TreeMap<>(day.visits());
            Arrangement arrangement = day.baseline();
            ReservationState.Hold authorization = null;
            if (confirmation != null && removed.contains(confirmation.holdId())) {
                Visit old = Required.value(facts.get(confirmation.holdId()), "selected reservation");
                String assigned = day.baseline().routes().entrySet().stream().filter(route -> route.getValue().contains(old.id()))
                        .map(route -> route.getKey()).findFirst().orElseThrow();
                var appointment = new Visit(confirmation.appointmentId(), jobId, old.serviceId(), old.windowStart(), old.windowEnd(),
                        old.durationMinutes(), old.location(), Required.value(assigned), old.plannedStart(), false);
                if (facts.putIfAbsent(appointment.id(), appointment) != null) throw conflict("Appointment identifier already exists");
                arrangement = ReservationOffers.replace(arrangement, old.id(), appointment.id());
                authorization = Required.value(holds.get(old.id()), "selected authorization");
                selected = true;
            }
            arrangement = ReservationOffers.without(arrangement, removed);
            removed.forEach(holds::remove);
            Map<String, Visit> baselineFacts = new TreeMap<>(day.visits());
            removed.forEach(baselineFacts::remove);
            Arrangement baseline = ReservationOffers.without(day.baseline(), removed);
            // Keep removed and replacement coordinates while fetching all before/after directed legs.
            List<Arrangement> variants = new ArrayList<>(); variants.add(day.baseline()); variants.add(baseline); variants.add(arrangement);
            Day routed = routing.arrangements(date, day, facts, variants, snapshot.routingIdentity());
            removed.forEach(facts::remove);
            var evaluated = routed.evaluate(arrangement, facts, snapshot.rates());
            if (!evaluated.feasible()) throw conflict("Remaining appointments or reservations require repair");
            if (authorization != null && !authorization.overtimeAuthorized()) {
                var withoutCustomer = routed.evaluate(baseline, baselineFacts, snapshot.rates());
                if (!withoutCustomer.feasible() || evaluated.overtimeMinutes() > withoutCustomer.overtimeMinutes())
                    throw conflict("Reserved offer does not authorize additional overtime");
            }
            Day next = new Day(day.technicians(), facts, arrangement, day.reservationVersion(), routed.roads());
            ReservationState.validate(next, snapshot.rates(), arrangement, facts, holds,
                    snapshot.configurationFingerprint(), snapshot.routingIdentity());
            result.put(date, new Prepared(next, holds, evaluated));
        }
        if (confirmation != null && !selected) throw conflict("Selected reservation is no longer active");
        return Required.value(Map.copyOf(result));
    }

    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
