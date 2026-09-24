package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.BoundedBookingSearch.Candidate;
import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.RouteEvaluator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Builds a common, independently checked arrangement before any offer is persisted. */
public final class ReservationOffers {
    public record Reserved(String offerId, String holdId, Candidate candidate, Visit visit, boolean overtimeAuthorized) { }
    public record Prepared(Day day, ReservationState state, RouteEvaluator.Result validation) { }
    public record Bundle(List<Reserved> offers, Map<LocalDate, Prepared> dates, boolean completed) {
        public Bundle { offers = Required.value(List.copyOf(offers)); dates = Required.value(Map.copyOf(dates)); }
    }
    private ReservationOffers() { }

    public static Bundle prepare(BookingSnapshot snapshot, BoundedBookingSearch.Request request,
            Map<LocalDate, Map<String, ReservationState.Hold>> existingHolds, BoundedBookingSearch.Result search,
            Instant expiresAt, Runnable checkpoint) {
        List<Candidate> pending = new ArrayList<>(search.candidates());
        pending.removeIf(candidate -> candidate.overtimeDelta() > 0 && !search.overtimeAuthorized());
        List<Reserved> offers = new ArrayList<>();
        Map<LocalDate, Prepared> prepared = new TreeMap<>();
        Set<BoundedBookingSearch.Window> chosen = new HashSet<>();
        boolean completed = true;
        try {
        while (!pending.isEmpty() && offers.size() < 4) {
            checkpoint.run();
            List<Candidate> regular = Required.value(pending.stream().filter(candidate -> candidate.overtimeDelta() <= 0).toList());
            Candidate candidate = Required.value(BoundedBookingSearch.choose(regular.isEmpty() ? pending : regular, snapshot.policy()));
            pending.remove(candidate);
            if (chosen.contains(candidate.window())) continue;
            LocalDate date = candidate.window().day();
            Day original = Required.value(snapshot.days().get(date), "offer date");
            Prepared previous = prepared.get(date);
            Day current = previous == null ? original : previous.day();
            Arrangement proposedExisting = without(candidate.arrangement(), Required.value(Set.<String>of(request.jobId())));
            if (previous != null) {
                Set<String> siblings = new HashSet<>(current.visits().keySet()); siblings.removeAll(original.visits().keySet());
                if (!without(current.baseline(), siblings).equals(proposedExisting)) continue;
            }
            String offerId = Required.value(UUID.randomUUID().toString()), holdId = Required.value(UUID.randomUUID().toString());
            Visit visit = new Visit(holdId, request.jobId(), request.serviceId(), candidate.window().start(), candidate.window().end(),
                    request.durationMinutes(), request.location(), candidate.technicianId(), candidate.window().start(), true);
            Map<String, Visit> facts = new TreeMap<>(current.visits()); facts.put(holdId, visit);
            Arrangement arrangement;
            if (previous == null) arrangement = replace(candidate.arrangement(), request.jobId(), holdId);
            else {
                List<String> reference = Required.value(candidate.arrangement().routes().get(candidate.technicianId()));
                List<String> target = Required.value(current.baseline().routes().get(candidate.technicianId()));
                int index = reference.indexOf(request.jobId());
                if (index < 0) throw new BookingSnapshot.Incomplete("Candidate does not assign requesting job");
                int position = index + 1 == reference.size() ? target.size() : target.indexOf(reference.get(index + 1));
                if (position < 0) throw new BookingSnapshot.Incomplete("Candidate route anchor changed");
                arrangement = current.baseline().insert(candidate.technicianId(), holdId, position);
            }
            Roads aliased = alias(original.roads(), request.jobId(), facts, request.jobId());
            Day day = new Day(current.technicians(), facts, arrangement, current.reservationVersion(), aliased);
            var validated = day.evaluate(arrangement, facts, snapshot.rates());
            if (!validated.feasible()) continue;
            var baseline = previous == null ? original.evaluate(original.baseline(), original.visits(), snapshot.rates()) : previous.validation();
            if (!baseline.feasible()) throw new BookingSnapshot.Incomplete("Reservation baseline is infeasible");
            boolean overtime = candidate.overtimeDelta() > 0 && search.overtimeAuthorized();
            if (validated.overtimeMinutes() > baseline.overtimeMinutes() && !overtime) continue;
            Map<String, ReservationState.Hold> holds = new TreeMap<>(previous == null
                    ? Required.value(existingHolds.get(date), "existing reservation metadata") : previous.state().holds());
            holds.put(holdId, new ReservationState.Hold(request.jobId(), offerId, expiresAt, overtime));
            var state = ReservationState.validate(day, snapshot.rates(), arrangement, facts, holds,
                    snapshot.configurationFingerprint(), snapshot.routingIdentity());
            prepared.put(date, new Prepared(day, state, validated));
            offers.add(new Reserved(offerId, holdId, candidate, visit, overtime));
            chosen.add(candidate.window());
        }
        }
        catch (SearchDeadline.Expired exception) { completed = false; }
        return new Bundle(offers, prepared, completed);
    }

    public static Arrangement without(Arrangement source, Set<String> removed) {
        Map<String, List<String>> routes = new TreeMap<>();
        source.routes().forEach((technician, visits) -> routes.put(technician,
                Required.value(visits.stream().filter(id -> !removed.contains(id)).toList())));
        return new Arrangement(routes);
    }

    public static Arrangement replace(Arrangement source, String oldId, String newId) {
        Map<String, List<String>> routes = new TreeMap<>();
        source.routes().forEach((technician, visits) -> routes.put(technician,
                Required.value(visits.stream().<String>map(id -> oldId.equals(id) ? newId : id).toList())));
        return new Arrangement(routes);
    }

    /** All alternatives for this customer share the routed job coordinate, including directed self legs. */
    private static Roads alias(Roads source, String routedId, Map<String, Visit> facts, String jobId) {
        Map<String, List<String>> replacements = new HashMap<>();
        List<String> alternatives = new ArrayList<>();
        facts.values().stream().filter(visit -> visit.jobId().equals(jobId)).forEach(visit -> alternatives.add(visit.id()));
        replacements.put(routedId, alternatives);
        Map<String, DayPlan.RoadLeg> legs = new HashMap<>(source.legs());
        Set<String> unreachable = new HashSet<>(source.unreachable());
        Set<String> keys = new HashSet<>(source.legs().keySet()); keys.addAll(source.unreachable());
        for (String key : keys) {
            String[] parts = key.split(">", -1);
            if (parts.length != 2) throw new BookingSnapshot.Incomplete("Malformed directed leg identifier");
            List<String> from = replacements.get(parts[0]), to = replacements.get(parts[1]);
            if (from == null) from = List.of(Required.value(parts[0]));
            if (to == null) to = List.of(Required.value(parts[1]));
            for (String origin : from) for (String destination : to) {
                String pair = origin + ">" + destination;
                DayPlan.RoadLeg leg = source.legs().get(key);
                if (leg == null) unreachable.add(pair); else legs.put(pair, leg);
            }
        }
        return new Roads(legs, unreachable);
    }
}
