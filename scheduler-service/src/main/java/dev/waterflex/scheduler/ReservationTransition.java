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
    public record Confirmation(String holdId, String appointmentId, @Nullable Integer reservedOvertimeDelta) {
        public Confirmation(String holdId, String appointmentId) { this(holdId, appointmentId, null); }
    }
    public record Prepared(Day day, Map<String, ReservationState.Hold> holds, RouteEvaluator.Result validation) {
        public Prepared { holds = Required.value(Map.copyOf(holds)); }
    }
    private final SnapshotRouting routing;
    public ReservationTransition(SnapshotRouting routing) { this.routing = routing; }

    public Map<LocalDate, Prepared> cancel(BookingSnapshotLoader.Facts snapshot, String appointmentId) {
        Map<LocalDate, Prepared> result = new TreeMap<>();
        boolean found = false;
        for (var entry : snapshot.days().entrySet()) {
            Day day = Required.value(entry.getValue());
            Visit cancelled = day.visits().get(appointmentId);
            if (cancelled == null || cancelled.reservation()) continue;
            found = true;
            Set<String> removed = new HashSet<>(); removed.add(appointmentId);
            Arrangement common = ReservationOffers.without(day.baseline(), removed);
            Arrangement actual = ReservationOffers.without(day.actualArrangement(), removed);
            List<Arrangement> variants = new ArrayList<>();
            variants.add(day.baseline()); variants.add(day.actualArrangement()); variants.add(common); variants.add(actual);
            Day routed = routing.arrangements(Required.value(entry.getKey()), day, day.visits(), variants, snapshot.routingIdentity());
            Map<String, Visit> remaining = new TreeMap<>(day.visits()); remaining.remove(appointmentId);
            var before = routed.evaluate(day.baseline(), day.visits(), snapshot.rates());
            var after = routed.evaluate(common, remaining, snapshot.rates());
            var actualBefore = RouteEvaluator.evaluate(routed.plan(day.actualArrangement(), day.visits(), snapshot.rates(), true));
            var actualAfter = RouteEvaluator.evaluate(routed.plan(actual, remaining, snapshot.rates(), true));
            if (!before.feasible() || !after.feasible() || !actualBefore.feasible() || !actualAfter.feasible()
                    || after.overtimeMinutes() > before.overtimeMinutes() || actualAfter.overtimeMinutes() > actualBefore.overtimeMinutes())
                throw conflict("Cancellation requires repair of remaining appointments or reservations");
            Day next = new Day(day.technicians(), remaining, common, day.reservationVersion(), routed.roads());
            result.put(entry.getKey(), new Prepared(next, Required.value(snapshot.holds().get(entry.getKey())), after));
        }
        if (!found) throw conflict("Appointment changed before cancellation");
        return Required.value(Map.copyOf(result));
    }

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
                Integer reservedDelta = confirmation == null ? null : confirmation.reservedOvertimeDelta();
                // A regular offer may have transferred existing overtime by rearranging other
                // appointments. Its persisted net issuance delta and the current common
                // arrangement preserve that reservation, even after another customer confirms.
                // Missing historical issuance metrics retain the conservative marginal check.
                var reference = reservedDelta != null && reservedDelta <= 0
                        ? routed.evaluate(day.baseline(), day.visits(), snapshot.rates())
                        : routed.evaluate(baseline, baselineFacts, snapshot.rates());
                if (!reference.feasible() || evaluated.overtimeMinutes() > reference.overtimeMinutes())
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
