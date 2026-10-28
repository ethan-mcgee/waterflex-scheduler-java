package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RouteTimelineTest {
    @Test void choosesLaterWorkingIntervalToAvoidAnExtraDepotTrip() {
        DayPlan day = intervalChoicePlan();
        var result = RouteEvaluator.evaluate(day);
        assertTrue(result.feasible());
        assertEquals(150, result.paidMinutes());
        assertEquals(30, result.driveMinutes());
        assertEquals(1, Required.value(result.segments().get("home")).size());
        assertEquals(at("12:00:00"), Required.value(result.segments().get("home")).getFirst().departure());
        assertEquals(at("12:10:00"), result.arrivals().get("first"));
        assertEquals(at("13:20:00"), result.arrivals().get("second"));
        assertEquals(result.arrivals(), DayScoreCalculator.evaluate(day).arrivals());
        assertEquals(result.costCents(), DayScoreCalculator.evaluate(day).costCents());
    }

    @Test void doesNotPruneAGroupBecauseAnIntermediateStopCannotReturnDirectly() {
        for (boolean unreachable : List.of(false, true)) {
            DayPlan day = intervalChoicePlan();
            Map<String, DayPlan.RoadLeg> roads = new HashMap<>(day.getMatrix());
            if (unreachable) roads.remove("first>home:return");
            else roads.put("first>home:return", new DayPlan.RoadLeg(36000, 100000));
            day = withRoads(day, roads);
            var result = RouteEvaluator.evaluate(day);
            assertTrue(result.feasible());
            assertEquals(150, result.paidMinutes());
            assertEquals(0, DayScoreCalculator.evaluate(day).hardPenalty());
            assertEquals(result.arrivals(), DayScoreCalculator.evaluate(day).arrivals());
        }
    }

    /**
     * Past 2262 a long cannot count the epoch's nanoseconds, so the visit and route have no precomputed times and the
     * search reads the instants: the day scores exactly as the same day in 2026 does, as only offsets enter the timing.
     */
    @Test void aDayPastALongsEpochNanosecondsScoresAsTheSameDayWithin() {
        DayPlan within = intervalChoicePlan(), beyond = intervalChoicePlan("2300-09-21");
        TechRoute far = Required.value(beyond.getRoutes().getFirst());
        assertNull(RouteTimeline.epochNanos(far.getShiftStart()));
        assertNull(far.clock().origin());
        for (PlanVisit visit : far.getVisits()) assertNull(visit.times(), visit.getId());
        TechRoute near = Required.value(within.getRoutes().getFirst());
        assertNotNull(near.clock().origin());
        for (PlanVisit visit : near.getVisits()) assertNotNull(visit.times(), visit.getId());
        for (boolean withArrivals : List.of(false, true)) {
            var expected = RouteTimeline.evaluate(within.getFacts(), near, withArrivals);
            var actual = RouteTimeline.evaluate(beyond.getFacts(), far, withArrivals);
            assertEquals(expected.violations(), actual.violations());
            assertEquals(List.of(expected.paidMinutes(), expected.overtimeMinutes(), expected.driveMinutes(), expected.waitingMinutes(), expected.meters()),
                    List.of(actual.paidMinutes(), actual.overtimeMinutes(), actual.driveMinutes(), actual.waitingMinutes(), actual.meters()));
            assertEquals(expected.arrivals().size(), actual.arrivals().size());
            for (var arrival : expected.arrivals().entrySet()) {
                Instant shifted = Required.value(actual.arrivals().get(arrival.getKey()));
                assertEquals(RouteTimeline.offset(near.getShiftStart(), Required.value(arrival.getValue())), RouteTimeline.offset(far.getShiftStart(), shifted));
            }
        }
    }

    /** A visit's nanosecond times are made by its constructor; a route's clock is derived per call until it is frozen, then made once. */
    @Test void timingLongsAreMadeOnceFromFactsThatNoLongerChange() {
        Instant start = at("09:00:00.25"), end = at("15:00:00");
        PlanVisit visit = new PlanVisit("visit", "service", start, end, 45, null, null);
        PlanVisit.Times times = Required.value(visit.times());
        assertEquals(List.of(start.getEpochSecond() * 1_000_000_000L + start.getNano(), end.getEpochSecond() * 1_000_000_000L, 45 * 60 * 1_000_000_000L),
                List.of(times.windowStart(), times.windowEnd(), times.duration()));
        assertSame(times, visit.times());

        TechRoute route = new TechRoute("home", at("09:00:00"), at("17:00:00"), 480, 0, Required.value(Set.of("service")));
        assertEquals(1, route.clock().blocks().size());
        assertNotSame(route.clock(), route.clock());
        // An absence added before the freeze reaches the clock, as no clock is kept until then.
        route.getUnavailable().add(new TechRoute.Unavailable(at("11:00:00"), at("12:00:00")));
        assertEquals(2, route.clock().blocks().size());
        route.freeze();
        TechRoute.Clock clock = route.clock();
        assertSame(clock, route.clock());
        assertEquals(List.of(new RouteTimingSearch.Block(0, RouteTimeline.nanos(120)), new RouteTimingSearch.Block(RouteTimeline.nanos(180), RouteTimeline.nanos(480))), clock.blocks());
        assertEquals(List.of(Required.value(RouteTimeline.epochNanos(at("09:00:00"))), RouteTimeline.nanos(480)), List.of(Required.value(clock.origin()), clock.shiftEnd()));
    }

    /** A route and its visits keep their road numbers for the revision that gave them: scored under another, they resolve again. */
    @Test void roadNumbersFollowTheRevisionTheyAreScoredAgainst() {
        DayPlan day = intervalChoicePlan();
        TechRoute route = Required.value(day.getRoutes().getFirst());
        var before = RouteTimeline.evaluate(day.getFacts(), route, false);
        // The same entities under a revision that numbers one more identity and has faster legs, still feasible.
        Map<String, DayPlan.RoadLeg> roads = new HashMap<>();
        for (String from : List.of("elsewhere", "home", "first", "second")) for (String to : List.of("elsewhere", "first", "second", "home:return"))
            if (!from.equals(to)) roads.put(from + ">" + to, new DayPlan.RoadLeg(300, 1500));
        DayPlan faster = withRoads(day, roads);
        PlanFacts facts = faster.getFacts();
        assertEquals(facts.roads().number("home"), route.roadNumber(facts));
        for (PlanVisit visit : route.getVisits()) assertEquals(facts.roads().number(visit.getId()), visit.roadNumber(facts), visit.getId());
        var after = RouteTimeline.evaluate(facts, route, false);
        var independent = RouteEvaluator.evaluate(faster);
        assertTrue(independent.feasible());
        assertEquals(0, after.hardPenalty());
        assertNotEquals(before.driveMinutes(), after.driveMinutes());
        assertEquals(independent.driveMinutes(), after.driveMinutes());
        assertEquals(independent.paidMinutes(), after.paidMinutes());
        assertEquals(independent.costCents(), dev.waterflex.scheduler.Monetary.cents(after.paidMinutes(), after.overtimeMinutes(), after.meters(), facts.rates()));
        // And back: the first revision's numbers are resolved again, not kept from the second.
        assertEquals(before, RouteTimeline.evaluate(day.getFacts(), route, false));
        // A revision that names no road numbers nothing, whatever the matrix's own order: a kept number would read a leg.
        DayPlan roadless = withRoads(day, Required.value(Map.of()));
        assertEquals(-1, route.roadNumber(roadless.getFacts()));
        for (PlanVisit visit : route.getVisits()) assertEquals(-1, visit.roadNumber(roadless.getFacts()), visit.getId());
        assertEquals(3, RouteTimeline.evaluate(roadless.getFacts(), route, false).violations().unavailableRoads());
        // A kept -1 reads back as -1 (the number's sign survives the packed cache), and a matrix with roads resolves again.
        for (PlanVisit visit : route.getVisits()) {
            assertEquals(-1, visit.roadNumber(roadless.getFacts()), visit.getId());
            assertEquals(day.getFacts().roads().number(visit.getId()), visit.roadNumber(day.getFacts()), visit.getId());
        }
        assertNotEquals(day.getFacts().roads().serial(), roadless.getFacts().roads().serial());
    }

    /** The long offset readings are Duration's own, on spans with nanoseconds and on negative spans, where floor and truncation differ. */
    @Test void offsetReadingsAreTheDurationReadings() {
        Instant origin = at("09:00:00.250000001");
        Random random = new Random(3);
        List<Instant> instants = new ArrayList<>(List.of(at("08:59:00.75"), at("08:59:00.250000001"), at("09:01:00"), at("09:00:59.999999999")));
        for (int sample = 0; sample < 2000; sample++)
            instants.add(Required.value(origin.plusNanos(random.nextLong(-3 * 86_400_000_000_000L, 3 * 86_400_000_000_000L))));
        for (Instant listed : instants) {
            Instant instant = Required.value(listed);
            long offset = RouteTimeline.offset(origin, instant);
            var duration = java.time.Duration.between(origin, instant);
            assertEquals(duration.toMinutes(), RouteTimeline.minutes(offset), instant.toString());
            assertEquals(duration.getSeconds(), RouteTimeline.seconds(offset), instant.toString());
            assertEquals(instant, RouteTimeline.instant(origin, offset));
        }
        assertEquals(-1, RouteTimeline.minutes(RouteTimeline.offset(origin, at("08:59:00.75"))), "-59.5 s floors to -60 s, one minute back");
    }

    @Test void fractionalSecondInstantsScoreAsTheIndependentEvaluator() {
        TechRoute route = new TechRoute("home", at("09:00:00.25"), at("17:00:00.5"), 480, 0, Required.value(Set.of("service")));
        route.getUnavailable().add(new TechRoute.Unavailable(at("11:00:00.125"), at("12:00:00.875")));
        PlanVisit first = new PlanVisit("first", "service", at("09:20:00.333"), at("10:00:00.5"), 45, "home", at("09:20:00.333"));
        PlanVisit second = new PlanVisit("second", "service", at("12:30:00.001"), at("15:00:00.999"), 60, "home", at("12:30:00.001"));
        route.getVisits().addAll(Required.value(List.of(first, second)));
        Map<String, DayPlan.RoadLeg> matrix = new HashMap<>();
        for (String from : List.of("home", "first", "second")) for (String to : List.of("first", "second", "home:return"))
            matrix.put(from + ">" + to, new DayPlan.RoadLeg(600, 3000));
        DayPlan day = new DayPlan(Required.value(List.of(route)), Required.value(List.of(first, second)), matrix, 30, 45, 0.67, 0, 0);
        var independent = RouteEvaluator.evaluate(day);
        var scored = DayScoreCalculator.evaluate(day);
        assertTrue(independent.feasible());
        assertEquals(0, scored.hardPenalty());
        assertEquals(independent.arrivals(), scored.arrivals());
        assertEquals(at("09:20:00.333"), scored.arrivals().get("first"));
        assertEquals(independent.paidMinutes(), scored.paidMinutes());
        assertEquals(independent.costCents(), scored.costCents());
    }

    @Test void anAbsenceEndingInsideASecondCountsTheWholeSecond() {
        // One hour visit that fits no working interval, so the relaxed trajectory scores it: out 09:00, back 10:20.
        TechRoute route = new TechRoute("home", at("09:00:00"), at("10:00:00"), 600, 0, Required.value(Set.of("service")));
        route.getUnavailable().add(new TechRoute.Unavailable(at("09:30:00.4"), at("09:40:00")));
        PlanVisit visit = new PlanVisit("visit", "service", at("09:00:00"), at("09:50:00"), 60, "home", at("09:00:00"));
        route.getVisits().add(visit);
        Map<String, DayPlan.RoadLeg> matrix = new HashMap<>(Map.of("home>visit", new DayPlan.RoadLeg(600, 3000), "visit>home:return", new DayPlan.RoadLeg(600, 3000)));
        DayPlan day = new DayPlan(Required.value(List.of(route)), Required.value(List.of(visit)), matrix, 30, 45, 0.67, 0, 0);
        var result = RouteTimeline.evaluate(day.getFacts(), Required.value(day.getRoutes().getFirst()));
        // Permitted 30:00.4 before the absence and 20:00 after it, of 80:00 away: 29:59.6 unavailable, counted as 1800 s.
        assertEquals(1800, result.violations().availabilitySeconds());
        assertEquals(20, result.violations().capacityMinutes());
        assertEquals(0, result.violations().latenessSeconds());
        assertEquals(80, result.paidMinutes());
        assertEquals(20, result.overtimeMinutes());
    }

    private static DayPlan intervalChoicePlan() { return intervalChoicePlan("2026-09-21"); }
    private static DayPlan intervalChoicePlan(String date) {
        TechRoute route = new TechRoute("home", on(date, "09:00:00"), on(date, "17:00:00"), 150, 0, Required.value(Set.of("service")));
        route.getUnavailable().add(new TechRoute.Unavailable(on(date, "11:00:00"), on(date, "12:00:00")));
        PlanVisit first = new PlanVisit("first", "service", on(date, "09:00:00"), on(date, "15:00:00"), 60, "home", on(date, "09:00:00"));
        PlanVisit second = new PlanVisit("second", "service", on(date, "13:00:00"), on(date, "15:00:00"), 60, "home", on(date, "13:00:00"));
        route.getVisits().addAll(Required.value(List.of(first, second)));
        Map<String, DayPlan.RoadLeg> matrix = new HashMap<>();
        for (String from : List.of("home", "first", "second")) for (String to : List.of("first", "second", "home:return"))
            matrix.put(from + ">" + to, new DayPlan.RoadLeg(600, 3000));
        return new DayPlan(Required.value(List.of(route)), Required.value(List.of(first, second)), matrix, 30, 45, 0.67, 0, 0);
    }

    private static DayPlan withRoads(DayPlan day, Map<String, DayPlan.RoadLeg> roads) {
        return new DayPlan(day.getRoutes(), day.getVisits(), roads, day.getRegularHourly(), day.getOvertimeHourly(), day.getMileagePerMile(), day.getTravelBufferPct(), day.getTravelBufferMinutes());
    }
    private static Instant at(String value) { return Required.value(Instant.parse("2026-09-21T" + value + "Z")); }
    private static Instant on(String date, String time) { return Required.value(Instant.parse(date + "T" + time + "Z")); }

    private static DayPlan plan(int maxDaily, Instant absenceStart, Instant absenceEnd) {
        TechRoute route = new TechRoute("home", at("13:00:00"), at("22:00:00"), maxDaily, 60, Required.value(Set.of("service")));
        route.getUnavailable().add(new TechRoute.Unavailable(absenceStart, absenceEnd));
        PlanVisit first = new PlanVisit("first", "service", at("14:00:00"), at("15:00:00"), 60, "home", at("14:00:00"));
        PlanVisit second = new PlanVisit("second", "service", at("19:00:00"), at("20:00:00"), 60, "home", at("19:00:00"));
        route.getVisits().addAll(Required.value(List.of(first, second)));
        Map<String, DayPlan.RoadLeg> matrix = new HashMap<>();
        for (String from : Required.value(List.of("home", "first", "second"))) for (String to : Required.value(List.of("home", "home:return", "first", "second")))
            if (!from.equals(to)) matrix.put(from + ">" + to, new DayPlan.RoadLeg(600, 3000));
        return new DayPlan(Required.value(List.of(route)), Required.value(List.of(first, second)), matrix, 30, 45, 0.67, 0, 0);
    }

    @Test
    void returnsHomeBeforeAbsenceAndDepartsAgainAfterward() {
        DayPlan day = plan(300, at("17:00:00"), at("18:00:00"));
        var result = RouteEvaluator.evaluate(day);
        assertTrue(result.feasible());
        assertEquals(160, result.paidMinutes());
        assertEquals(40, result.driveMinutes());
        assertEquals(at("14:00:00"), result.arrivals().get("first"));
        assertEquals(at("19:00:00"), result.arrivals().get("second"));
        var score = DayScoreCalculator.evaluate(day);
        assertEquals(0, score.hardPenalty());
        assertEquals(result.costCents(), score.costCents());
        assertEquals(result.paidMinutes(), score.paidMinutes());
        assertEquals(result.meters(), score.meters());
    }

    @Test
    void rejectsAbsenceThatPreventsHomeReturn() {
        assertFalse(RouteEvaluator.evaluate(plan(300, at("14:30:00"), at("15:30:00"))).feasible());
    }

    @Test
    void sumsPaidWorkAcrossBothIntervals() {
        assertFalse(RouteEvaluator.evaluate(plan(150, at("17:00:00"), at("18:00:00"))).feasible());
    }

    @Test
    void allEndpointPairsUseDirectedDepartureAndReturnLegsForEachSegment() {
        for (boolean depotStart : List.of(false, true)) for (boolean depotReturn : List.of(false, true)) {
            DayPlan day = plan(500, at("17:00:00"), at("18:00:00"));
            int outboundSeconds = depotStart ? 1200 : 600;
            int returnSeconds = depotReturn ? 1800 : 600;
            Map<String, DayPlan.RoadLeg> roads = new HashMap<>(day.getMatrix());
            for (String stop : List.of("first", "second")) {
                roads.put("home>" + stop, new DayPlan.RoadLeg(outboundSeconds, 1000));
                roads.put(stop + ">home:return", new DayPlan.RoadLeg(returnSeconds, 2000));
            }
            day = withRoads(day, roads);
            var independent = RouteEvaluator.evaluate(day);
            var score = DayScoreCalculator.evaluate(day);
            assertTrue(independent.feasible(), depotStart + " to " + depotReturn);
            assertEquals(0, score.hardPenalty());
            assertEquals(120 + 2 * (outboundSeconds + returnSeconds) / 60, independent.paidMinutes());
            assertEquals(independent.driveMinutes(), score.driveMinutes());
            assertEquals(independent.meters(), score.meters());
        }
    }

    @Test
    void rejectsAnUnknownVisitEvenWhenTheVisitCountMatches() {
        DayPlan plan = plan(300, at("17:00:00"), at("18:00:00"));
        TechRoute route = plan.getRoutes().getFirst();
        PlanVisit unknown = new PlanVisit("unknown", "service", at("19:00:00"), at("20:00:00"),
                60, "home", at("19:00:00"));
        route.getVisits().set(1, unknown);
        assertThrows(IllegalArgumentException.class, () -> RouteEvaluator.evaluate(plan));
        assertThrows(IllegalArgumentException.class, () -> PlanCopies.copy(plan));
    }

    @Test
    void fleetCostRoundsOnceAfterCombiningTechnicianRoutes() {
        List<TechRoute> routes = new ArrayList<>();
        List<PlanVisit> visits = new ArrayList<>();
        Map<String, DayPlan.RoadLeg> matrix = new HashMap<>();
        for (int i = 1; i <= 2; i++) {
            String home = "home" + i, stop = "stop" + i;
            TechRoute route = new TechRoute(home, at("13:00:00"), at("14:00:00"), 60, 0, Required.value(Set.of("service")));
            PlanVisit visit = new PlanVisit(stop, "service", at("13:00:00"), at("14:00:00"), 1, home, at("13:00:00"));
            route.getVisits().add(visit);
            routes.add(route); visits.add(visit);
            matrix.put(home + ">" + stop, new DayPlan.RoadLeg(0, 0));
            matrix.put(stop + ">" + home + ":return", new DayPlan.RoadLeg(0, 0));
        }
        DayPlan plan = new DayPlan(routes, visits, matrix, .30, .45, 0, 0, 0);
        assertEquals(1, RouteEvaluator.evaluate(plan).costCents());
        assertEquals(1, DayScoreCalculator.evaluate(plan).costCents());
    }

    @Test void laterDepartureAbsorbsWaitingWithoutCrossingExclusiveWindowEnd() {
        DayPlan day = plan(600, at("22:00:00"), at("23:00:00"));
        var result = RouteEvaluator.evaluate(day);
        assertTrue(result.feasible());
        assertEquals(at("14:59:00"), result.arrivals().get("first"));
        assertEquals(at("19:00:00"), result.arrivals().get("second"));
        assertEquals(at("14:49:00"), Required.value(result.segments().get("home")).getFirst().departure());
        assertEquals(at("20:10:00"), Required.value(result.segments().get("home")).getFirst().returnedAt());
        assertEquals(171, result.waitingMinutes());
        assertEquals(321, result.paidMinutes());
        var full = DayScoreCalculator.evaluate(day);
        assertEquals(result.arrivals(), full.arrivals());
        assertEquals(result.costCents(), full.costCents());
    }

    private static DayPlan withBuffer(DayPlan day, PlanFacts.ArrivalBuffer buffer) {
        return new DayPlan(day.getRoutes(), day.getVisits(), day.getMatrix(), Required.value(Set.of()), day.getRegularHourly(), day.getOvertimeHourly(),
                day.getMileagePerMile(), day.getTravelBufferPct(), day.getTravelBufferMinutes(), buffer);
    }

    /** The case above with a 15-minute buffer (25 percent of the hour): the later departure stops before 14:45. */
    @Test void laterDepartureStopsBeforeTheLateArrivalBuffer() {
        DayPlan day = withBuffer(plan(600, at("22:00:00"), at("23:00:00")), new PlanFacts.ArrivalBuffer(25, 15, 60));
        var result = RouteEvaluator.evaluate(day);
        assertTrue(result.feasible());
        assertEquals(at("14:44:00"), result.arrivals().get("first"));
        assertEquals(at("19:00:00"), result.arrivals().get("second"));
        assertEquals(at("14:34:00"), Required.value(result.segments().get("home")).getFirst().departure());
        assertEquals(186, result.waitingMinutes());
        assertEquals(336, result.paidMinutes());
        assertEquals(0, result.bufferSeconds());
        var full = DayScoreCalculator.evaluate(day);
        assertEquals(result.arrivals(), full.arrivals());
        assertEquals(result.costCents(), full.costCents());
        assertEquals(0, full.bufferSeconds());
    }

    /** An arrival the shift allows only inside the buffer is still on time: scored, never infeasible, and not delayed further. */
    @Test void anUnavoidableArrivalInsideTheBufferStaysFeasible() {
        DayPlan day = withBuffer(plan(600, at("13:00:00"), at("14:40:00")), new PlanFacts.ArrivalBuffer(25, 15, 60));
        var result = RouteEvaluator.evaluate(day);
        assertTrue(result.feasible());
        assertEquals(at("14:50:00"), result.arrivals().get("first"));
        assertEquals(at("14:40:00"), Required.value(result.segments().get("home")).getFirst().departure());
        assertEquals(5 * 60 + 1, result.bufferSeconds());
        var full = DayScoreCalculator.evaluate(day);
        assertEquals(0, full.hardPenalty());
        assertEquals(result.arrivals(), full.arrivals());
        assertEquals(result.bufferSeconds(), full.bufferSeconds());
        assertTrue(DailyCalculation.valid(day));
    }

    @Test void broadFirstWindowRemovesAllAvoidableWaiting() {
        DayPlan day = plan(600, at("22:00:00"), at("23:00:00"));
        PlanVisit first = new PlanVisit("first", "service", at("14:00:00"), at("19:00:00"), 60, "home", at("14:00:00"));
        day.getRoutes().getFirst().getVisits().set(0, first);
        day = new DayPlan(day.getRoutes(), Required.value(List.of(first, day.getVisits().get(1))), day.getMatrix(), day.getRegularHourly(), day.getOvertimeHourly(), day.getMileagePerMile(), day.getTravelBufferPct(), day.getTravelBufferMinutes());
        var result = RouteEvaluator.evaluate(day);
        assertTrue(result.feasible());
        assertEquals(0, result.waitingMinutes());
        assertEquals(150, result.paidMinutes());
        assertEquals(at("17:50:00"), result.arrivals().get("first"));
        assertEquals(result.arrivals(), DayScoreCalculator.evaluate(day).arrivals());
    }
}
