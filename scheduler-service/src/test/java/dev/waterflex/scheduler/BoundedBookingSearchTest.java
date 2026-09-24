package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedBookingSearchTest {
    @Test void sharedNeighborhoodsRemainWindowIndependentAndPreserveIndependentValidation() {
        var original = fixture(60, false, true, 0);
        var oldDay = Required.value(original.days().get(DAY));
        Map<String, Technician> technicians = new HashMap<>();
        oldDay.technicians().forEach((id, tech) -> technicians.put(id, new Technician(tech.id(), tech.shiftStart(),
                Required.value(START.plusSeconds(21600)), 360, 0, tech.services(), tech.absences(), tech.departure(), tech.returnTo(), tech.scheduleVersion())));
        var day = new Day(technicians, oldDay.visits(), oldDay.baseline(), oldDay.reservationVersion(), oldDay.roads());
        Map<LocalDate, Day> days = new HashMap<>(original.days()); days.put(DAY, day);
        var snapshot = new BookingSnapshot(original.metroId(), original.capturedAt(), original.configurationFingerprint(), original.routingIdentity(), original.policy(), RATES, days);
        var request = new BoundedBookingSearch.Request("new", "new-service", 30, POINT);
        var search = new BoundedBookingSearch(snapshot, request, BoundedBookingSearch.Limits.defaults(), () -> { });
        var first = search.search(true);
        assertTrue(first.distinctRegularWindows() > 2);
        var reverse = new ArrayList<>(search.windows()); Collections.reverse(reverse);
        var second = search.search(reverse, true);
        assertEquals(new HashSet<>(first.candidates()), new HashSet<>(second.candidates()));
        for (var candidate : first.candidates()) {
            Map<String, Visit> facts = new HashMap<>(day.visits());
            facts.put("new", new Visit("new", "new", "new-service", candidate.window().start(), candidate.window().end(), 30,
                    POINT, candidate.technicianId(), candidate.window().start(), false));
            assertEquals(day.evaluate(candidate.arrangement(), facts, RATES), candidate.validation());
        }
    }

    @Test void reusedInsertionStillCompletesScarcityNeighborhoodAndFindsRelocation() {
        var snapshot = fixture(60, false, true, 0);
        var request = new BoundedBookingSearch.Request("new", "new-service", 60, POINT);
        var insertion = new BoundedBookingSearch(snapshot, request, BoundedBookingSearch.Limits.defaults(), () -> { }).search(false);
        var refined = new BoundedBookingSearch(snapshot, request, BoundedBookingSearch.Limits.defaults(), () -> { }).refine(insertion);
        var full = new BoundedBookingSearch(snapshot, request, BoundedBookingSearch.Limits.defaults(), () -> { }).search(true);
        assertTrue(refined.complete());
        assertEquals(full.candidates(), refined.candidates());
        assertEquals(full.confirmedRegularMinutes(), refined.confirmedRegularMinutes());
        assertEquals(full.regularCapacityMinutes(), refined.regularCapacityMinutes());
        var combined = BookingSearchPipeline.combine(insertion, refined, snapshot.policy());
        assertEquals(full.coverage().stream().mapToInt(item -> item.candidateEvaluations()).sum(),
                combined.coverage().stream().mapToInt(item -> item.candidateEvaluations()).sum());
        assertEquals("REARRANGEMENT", Required.value(BoundedBookingSearch.choose(refined.candidates(), snapshot.policy())).source());
    }

    @Test void interruptedScarcityRefinementNeverAuthorizesOvertime() {
        var snapshot = fixture(106, false, false, 60);
        var request = new BoundedBookingSearch.Request("new", "new-service", 13, POINT);
        var insertion = new BoundedBookingSearch(snapshot, request, BoundedBookingSearch.Limits.defaults(), () -> { }).search(false);
        var refined = new BoundedBookingSearch(snapshot, request, BoundedBookingSearch.Limits.defaults(),
                () -> { throw new BoundedBookingSearch.RefinementLimit(); }).refine(insertion);
        var combined = BookingSearchPipeline.combine(insertion, refined, snapshot.policy());
        assertEquals("REFINEMENT_TIME_LIMIT", combined.stopReason());
        assertFalse(combined.complete());
        assertFalse(combined.overtimeAuthorized());
        assertEquals(insertion.candidates(), combined.candidates());
    }

    @Test void optionalRefinementTimeoutKeepsCompletedRegularChoicesWithoutUnlockingOvertime() {
        var snapshot = fixture(30, false, false, 60);
        var search = new BoundedBookingSearch(snapshot, new BoundedBookingSearch.Request("new", "new-service", 20, POINT),
                BoundedBookingSearch.Limits.defaults(), () -> { });
        var one = Required.value(search.search(false).candidates().getFirst());
        List<BoundedBookingSearch.Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            var window = new BoundedBookingSearch.Window(DAY, Required.value(START.plusSeconds(index * 7200L)), Required.value(END.plusSeconds(index * 7200L)));
            candidates.add(new BoundedBookingSearch.Candidate(window, one.technicianId(), one.arrangement(), 0, one.costDeltaCents(),
                    one.fairnessDelta(), one.changedAssignments(), one.insertionPosition(), one.source(), one.validation()));
        }
        var insertion = new BoundedBookingSearch.Result(candidates, Required.value(List.of()), true, 3, 108, 120, false, "COMPLETED");
        var timedOut = new BoundedBookingSearch.Result(Required.value(List.of()), Required.value(List.of()), false, 0, 0, 0, false, "DEADLINE");
        var combined = BookingSearchPipeline.combine(insertion, timedOut, snapshot.policy());
        assertEquals(candidates, combined.candidates());
        assertTrue(combined.complete());
        assertEquals(3, combined.distinctRegularWindows());
        assertEquals(108, combined.confirmedRegularMinutes());
        assertFalse(combined.overtimeAuthorized());
    }

    @Test void cachedRoutesMatchFullEvaluationAcrossReassignmentUndoAndChangedPromises() {
        for (boolean hold : List.of(false, true)) {
            Day day = Required.value(fixture(30, hold, true, 60).days().get(DAY));
            var evaluation = new BookingEvaluation(day, RATES, "new-service", () -> { });
            for (int repetition = 0; repetition < 3; repetition++) for (String assigned : List.of("a", "b"))
                for (String inserted : List.of("a", "b")) for (boolean reversed : List.of(false, true)) {
                    Map<String, Visit> facts = new HashMap<>(day.visits());
                    // Reusing an ID with a changed promise must not reuse its old route metrics.
                    Instant start = Required.value(START.plusSeconds(repetition * 1200L));
                    facts.put("new", new Visit("new", "new", "new-service", start, END, 20, POINT, Required.value(inserted), start, false));
                    Map<String, List<String>> routes = new TreeMap<>(); routes.put("a", new ArrayList<>()); routes.put("b", new ArrayList<>());
                    Required.value(routes.get(assigned)).add("old"); Required.value(routes.get(inserted)).add("new");
                    if (reversed) routes.values().forEach(Collections::reverse);
                    var arrangement = new Arrangement(routes);
                    for (boolean confirmedOnly : List.of(false, true)) {
                        var full = dev.waterflex.scheduler.optimizer.RouteEvaluator.evaluate(day.plan(arrangement, facts, RATES, confirmedOnly));
                        if (confirmedOnly && full.feasible()) assertEquals(SchedulingPolicy.measure(day.plan(arrangement, facts, RATES, true)).fairness().variance(),
                                evaluation.fairness(arrangement, facts));
                        assertEquals(full, evaluation.evaluate(arrangement, facts, confirmedOnly));
                        long evaluated = evaluation.evaluations();
                        assertEquals(full, evaluation.evaluate(arrangement, facts, confirmedOnly));
                        assertEquals(evaluated, evaluation.evaluations(), "Repeated arrangement must reuse immutable metrics");
                    }
                }
            assertTrue(evaluation.hits() > evaluation.evaluations());
            assertEquals(day.evaluate(day.baseline(), day.visits(), RATES), evaluation.evaluate(day.baseline(), day.visits(), false));
        }
    }

    private static final Instant CAPTURED = Required.value(Instant.parse("2026-10-25T17:00:00Z"));
    private static final LocalDate DAY = Required.value(LocalDate.parse("2026-10-26"));
    private static final Instant START = Required.value(Instant.parse("2026-10-26T14:00:00Z"));
    private static final Instant END = Required.value(START.plusSeconds(7200));
    private static final RoadClient.Point POINT = new RoadClient.Point(41.25, -95.93);
    private static final Rates RATES = new Rates(30, 45, 0, 0, 0);

    @Test void relocationFindsRegularCapacityThatInsertionMissesWithoutChangingPromises() {
        BookingSnapshot snapshot = fixture(60, false, true, 0);
        var request = new BoundedBookingSearch.Request("new", "new-service", 60, POINT);
        var search = new BoundedBookingSearch(snapshot, request, BoundedBookingSearch.Limits.defaults(), () -> { });
        var insertion = search.search(false);
        assertTrue(insertion.candidates().isEmpty());
        assertFalse(insertion.complete());
        assertFalse(insertion.overtimeAuthorized());
        var result = search.search(true);
        assertTrue(result.complete());
        assertEquals(1, result.distinctRegularWindows());
        var candidate = Required.value(BoundedBookingSearch.choose(result.candidates(), snapshot.policy()));
        assertEquals("REARRANGEMENT", candidate.source());
        assertEquals(List.of("old"), candidate.arrangement().routes().get("b"));
        assertEquals(List.of("new"), candidate.arrangement().routes().get("a"));
        assertEquals(1, candidate.changedAssignments());
        assertEquals(0, candidate.overtimeDelta());
        assertTrue(candidate.validation().feasible());
        assertEquals(List.of("old"), Required.value(snapshot.days().get(DAY)).baseline().routes().get("a"));
        assertEquals(START, Required.value(Required.value(snapshot.days().get(DAY)).visits().get("old")).windowStart());
    }

    @Test void confirmedDemandOpensGateOnlyAfterCompletedSearchAndHoldsNeverCountAsDemand() {
        for (boolean reservation : List.of(false, true)) {
            BookingSnapshot snapshot = fixture(106, reservation, false, 60);
            var search = new BoundedBookingSearch(snapshot, new BoundedBookingSearch.Request("new", "new-service", 13, POINT),
                    BoundedBookingSearch.Limits.defaults(), () -> { });
            assertFalse(search.search(false).overtimeAuthorized());
            var result = search.search(true);
            assertTrue(result.complete());
            assertEquals(0, result.distinctRegularWindows());
            assertEquals(reservation ? 0 : 108, result.confirmedRegularMinutes());
            assertEquals(120, result.regularCapacityMinutes());
            assertEquals(!reservation, result.overtimeAuthorized());
            assertFalse(result.candidates().isEmpty());
        }
    }

    @Test void missingRoadAndDeadlineCannotEstablishScarcity() {
        BookingSnapshot snapshot = fixture(106, false, false, 60);
        var request = new BoundedBookingSearch.Request("new", "new-service", 13, POINT);
        var expired = new BoundedBookingSearch(snapshot, request, BoundedBookingSearch.Limits.defaults(), () -> { throw new SearchDeadline.Expired(); }).search(true);
        assertFalse(expired.complete());
        assertFalse(expired.overtimeAuthorized());
        assertEquals("DEADLINE", expired.stopReason());
        Map<LocalDate, Day> days = new HashMap<>(snapshot.days());
        Day day = Required.value(days.get(DAY));
        Map<String, DayPlan.RoadLeg> legs = new HashMap<>(day.roads().legs());
        legs.remove("a>new");
        days.put(DAY, new Day(day.technicians(), day.visits(), day.baseline(), 0, new Roads(legs, Required.value(Set.of()))));
        BookingSnapshot incomplete = new BookingSnapshot("metro", CAPTURED, "configuration", "roads", snapshot.policy(), RATES, days);
        assertThrows(BookingSnapshot.Incomplete.class, () -> new BoundedBookingSearch(incomplete, request,
                BoundedBookingSearch.Limits.defaults(), () -> { }).search(true));
        assertThrows(BookingSnapshot.Incomplete.class, () -> new BoundedBookingSearch(snapshot, request,
                BoundedBookingSearch.Limits.defaults(), () -> { }).search(Required.value(List.of()), true));
    }

    @Test void pendingReassignmentDoesNotChangeConfirmedDemandBeforeCustomerConfirmation() {
        BookingSnapshot original = fixture(106, false, true, 60);
        Day before = Required.value(original.days().get(DAY));
        Map<String, List<String>> routes = new TreeMap<>();
        routes.put("a", Required.value(List.of())); routes.put("b", Required.value(List.of("old")));
        Map<LocalDate, Day> days = new TreeMap<>(original.days());
        days.put(DAY, new Day(before.technicians(), before.visits(), new Arrangement(routes), before.reservationVersion(), before.roads()));
        var snapshot = new BookingSnapshot(original.metroId(), original.capturedAt(), original.configurationFingerprint(), original.routingIdentity(), original.policy(), original.rates(), days);
        var result = new BoundedBookingSearch(snapshot, new BoundedBookingSearch.Request("new", "new-service", 13, POINT),
                BoundedBookingSearch.Limits.defaults(), () -> { }).search(true);
        assertTrue(result.complete());
        assertEquals(108, result.confirmedRegularMinutes());
        assertEquals(120, result.regularCapacityMinutes());
        assertEquals(List.of("old"), before.actualArrangement().routes().get("a"));
    }

    @Test void fairnessCeilingIncludesBoundaryButNeverGrantsAllowanceOnNonpositiveCosts() {
        BookingSnapshot snapshot = fixture(30, false, false, 60);
        var search = new BoundedBookingSearch(snapshot, new BoundedBookingSearch.Request("new", "new-service", 20, POINT),
                BoundedBookingSearch.Limits.defaults(), () -> { });
        var base = Required.value(search.search(true).candidates().getFirst());
        var reference = candidate(base, 100, "0.2");
        var allowed = candidate(base, 102, "0.1");
        var expensive = candidate(base, 103, "0");
        assertEquals(allowed, BoundedBookingSearch.choose(Required.value(List.<BoundedBookingSearch.Candidate>of(reference, allowed, expensive)), snapshot.policy()));
        for (long cost : List.of(0L, -100L)) {
            var nonpositive = candidate(base, cost, "0.2");
            assertEquals(nonpositive, BoundedBookingSearch.choose(Required.value(List.<BoundedBookingSearch.Candidate>of(nonpositive, candidate(base, cost + 1, "0"))), snapshot.policy()));
        }
    }

    @Test void snapshotAndArrangementCopiesCannotBeMutatedAndMissingRatesFail() {
        var source = new ArrayList<>(List.of("old"));
        var arrangement = new Arrangement(Required.value(Map.<String, List<String>>of("a", source)));
        source.clear();
        assertEquals(List.of("old"), arrangement.routes().get("a"));
        assertThrows(UnsupportedOperationException.class, () -> Required.value(arrangement.routes().get("a")).clear());
        assertThrows(RuntimeException.class, () -> Rates.read(Required.value(Map.of())));
        assertThrows(BookingSnapshot.Incomplete.class, () -> new BookingSnapshot("metro", CAPTURED, "configuration", "roads",
                SchedulingPolicy.Rules.defaults(), RATES, Required.value(Map.of())));
    }

    @Test void duplicateTechnicianChoicesCountOnceAndIdleEligibleCapacityImprovesFairness() {
        BookingSnapshot original = fixture(60, false, true, 0);
        Day day = Required.value(original.days().get(DAY));
        Map<String, Technician> technicians = new HashMap<>(day.technicians());
        technicians.put("b", new Technician("b", START, END, 120, 0, Required.value(Set.of("old-service", "new-service")),
                Required.value(List.of()), POINT, POINT, 2));
        Map<LocalDate, Day> days = new HashMap<>(original.days());
        Map<String, DayPlan.RoadLeg> zeroRoads = new HashMap<>();
        day.roads().legs().keySet().forEach(key -> zeroRoads.put(key, new DayPlan.RoadLeg(0, 0)));
        days.put(DAY, new Day(technicians, day.visits(), day.baseline(), 1, new Roads(zeroRoads, Required.value(Set.of()))));
        var snapshot = new BookingSnapshot("metro", CAPTURED, "configuration", "roads", original.policy(), RATES, days);
        var search = new BoundedBookingSearch(snapshot, new BoundedBookingSearch.Request("new", "new-service", 20, POINT),
                BoundedBookingSearch.Limits.defaults(), () -> { });
        assertEquals(1, search.windows().size());
        var result = search.search(true);
        assertEquals(1, result.distinctRegularWindows());
        assertEquals(240, result.regularCapacityMinutes());
        assertEquals(60, result.confirmedRegularMinutes());
        var chosen = Required.value(BoundedBookingSearch.choose(result.candidates(), snapshot.policy()));
        assertEquals("b", chosen.technicianId());
        assertTrue(chosen.fairnessDelta().signum() < 0);
    }

    @Test void boundedRelocationMatchesExactAssignmentAndOrderOracleOnTinyCase() {
        BookingSnapshot snapshot = fixture(60, false, true, 0);
        Day day = Required.value(snapshot.days().get(DAY));
        var request = new BoundedBookingSearch.Request("new", "new-service", 60, POINT);
        var found = new BoundedBookingSearch(snapshot, request, BoundedBookingSearch.Limits.defaults(), () -> { }).search(true);
        var chosen = Required.value(BoundedBookingSearch.choose(found.candidates(), snapshot.policy()));
        Map<String, Visit> facts = new HashMap<>(day.visits());
        facts.put("new", new Visit("new", "new", "new-service", START, END, 60, POINT, "a", START, false));
        long bestCost = Long.MAX_VALUE;
        int feasible = 0;
        for (String oldTech : List.of("a", "b")) for (String newTech : List.of("a", "b")) for (boolean reversed : List.of(false, true)) {
            Map<String, List<String>> routes = new HashMap<>();
            routes.put("a", new ArrayList<>()); routes.put("b", new ArrayList<>());
            Required.value(routes.get(oldTech)).add("old");
            Required.value(routes.get(newTech)).add(reversed ? 0 : Required.value(routes.get(newTech)).size(), "new");
            var evaluation = day.evaluate(new Arrangement(routes), facts, RATES);
            if (evaluation.feasible()) { feasible++; bestCost = Math.min(bestCost, evaluation.costCents()); }
        }
        assertTrue(feasible > 0);
        assertEquals(bestCost, chosen.validation().costCents());
    }

    private static BoundedBookingSearch.Candidate candidate(BoundedBookingSearch.Candidate source, long cost, String fairness) {
        return new BoundedBookingSearch.Candidate(source.window(), source.technicianId(), source.arrangement(), source.overtimeDelta(), cost,
                new BigDecimal(fairness), source.changedAssignments(), source.insertionPosition(), source.source(), source.validation());
    }

    private static BookingSnapshot fixture(int duration, boolean hold, boolean secondTechnician, int overtime) {
        Map<String, Technician> technicians = new HashMap<>();
        technicians.put("a", new Technician("a", START, END, 240, overtime,
                Required.value(Set.of("old-service", "new-service")), Required.value(List.of()), POINT, POINT, 1));
        if (secondTechnician) technicians.put("b", new Technician("b", START, END, 120, 0,
                Required.value(Set.of("old-service")), Required.value(List.of()), POINT, POINT, 2));
        var visit = new Visit("old", "old-job", "old-service", START, END, duration, POINT, "a", START, hold);
        Map<String, List<String>> routes = new HashMap<>();
        routes.put("a", List.of("old"));
        if (secondTechnician) routes.put("b", List.of());
        Map<String, DayPlan.RoadLeg> legs = new HashMap<>();
        for (String from : List.of("a", "b", "old", "new")) for (String to : List.of("old", "new", "a:return", "b:return"))
            if (!from.equals(to)) legs.put(from + ">" + to, new DayPlan.RoadLeg(60, 100));
        Day day = new Day(technicians, Required.value(Map.<String, Visit>of("old", visit)), new Arrangement(routes), 1, new Roads(legs, Required.value(Set.of())));
        Day empty = new Day(Required.value(Map.of()), Required.value(Map.of()), new Arrangement(Required.value(Map.of())), 0,
                new Roads(Required.value(Map.of()), Required.value(Set.of())));
        Map<LocalDate, Day> days = new HashMap<>();
        for (LocalDate date : BookingService.bookingDates(CAPTURED)) days.put(date, date.equals(DAY) ? day : empty);
        return new BookingSnapshot("metro", CAPTURED, "configuration", "roads", SchedulingPolicy.Rules.defaults(), RATES, days);
    }
}
