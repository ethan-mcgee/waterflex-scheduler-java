package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RouteTimelineTest {
    private static Instant at(String value) { return Required.value(Instant.parse("2026-09-21T" + value + "Z")); }

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
            for (String stop : List.of("first", "second")) {
                day.getMatrix().put("home>" + stop, new DayPlan.RoadLeg(outboundSeconds, 1000));
                day.getMatrix().put(stop + ">home:return", new DayPlan.RoadLeg(returnSeconds, 2000));
            }
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
        plan.getMatrix().put("home>unknown", new DayPlan.RoadLeg(600, 3000));
        plan.getMatrix().put("unknown>home:return", new DayPlan.RoadLeg(600, 3000));
        assertFalse(RouteEvaluator.evaluate(plan).feasible());
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

    @Test void broadFirstWindowRemovesAllAvoidableWaiting() {
        DayPlan day = plan(600, at("22:00:00"), at("23:00:00"));
        PlanVisit first = new PlanVisit("first", "service", at("14:00:00"), at("19:00:00"), 60, "home", at("14:00:00"));
        day.getRoutes().getFirst().getVisits().set(0, first);
        day.setVisits(Required.value(List.of(first, day.getVisits().get(1))));
        var result = RouteEvaluator.evaluate(day);
        assertTrue(result.feasible());
        assertEquals(0, result.waitingMinutes());
        assertEquals(150, result.paidMinutes());
        assertEquals(at("17:50:00"), result.arrivals().get("first"));
        assertEquals(result.arrivals(), DayScoreCalculator.evaluate(day).arrivals());
    }
}
