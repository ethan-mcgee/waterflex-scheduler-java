package dev.waterflex.scheduler.optimizer;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RouteTimelineTest {
    private static Instant at(String value) { return Instant.parse("2026-09-21T" + value + "Z"); }

    private static DayPlan plan(int maxDaily, Instant absenceStart, Instant absenceEnd) {
        TechRoute route = new TechRoute("home", at("13:00:00"), at("22:00:00"), maxDaily, 60, Set.of("service"));
        route.getUnavailable().add(new TechRoute.Unavailable(absenceStart, absenceEnd));
        PlanVisit first = new PlanVisit("first", "service", at("14:00:00"), at("15:00:00"), 60, "home", at("14:00:00"));
        PlanVisit second = new PlanVisit("second", "service", at("19:00:00"), at("20:00:00"), 60, "home", at("19:00:00"));
        route.getVisits().addAll(List.of(first, second));
        Map<String, DayPlan.RoadLeg> matrix = new HashMap<>();
        for (String from : List.of("home", "first", "second")) for (String to : List.of("home", "first", "second"))
            if (!from.equals(to)) matrix.put(from + ">" + to, new DayPlan.RoadLeg(600, 3000));
        return new DayPlan(List.of(route), List.of(first, second), matrix, 30, 45, 0.67, 0, 0);
    }

    @Test
    void returnsHomeBeforeAbsenceAndDepartsAgainAfterward() {
        var result = RouteEvaluator.evaluate(plan(300, at("17:00:00"), at("18:00:00")));
        assertTrue(result.feasible());
        assertEquals(160, result.paidMinutes());
        assertEquals(40, result.driveMinutes());
        assertEquals(at("14:00:00"), result.arrivals().get("first"));
        assertEquals(at("19:00:00"), result.arrivals().get("second"));
    }

    @Test
    void rejectsAbsenceThatPreventsHomeReturn() {
        assertFalse(RouteEvaluator.evaluate(plan(300, at("14:30:00"), at("15:30:00"))).feasible());
    }

    @Test
    void sumsPaidWorkAcrossBothIntervals() {
        assertFalse(RouteEvaluator.evaluate(plan(150, at("17:00:00"), at("18:00:00"))).feasible());
    }
}
