package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.DayScoreCalculator;
import dev.waterflex.scheduler.optimizer.RouteEvaluator;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** The reading behind 422 CURRENT_ROUTES_INFEASIBLE (spec 1.10.0): each technician-day's broken rules, and the message naming them. */
class CurrentRoutesInfeasibleTest {
    private static final LocalDate DAY = Required.value(LocalDate.parse("2026-10-12"));

    /** The spec's daily example, changed by the caller, prepared with every leg taking {@code seconds}. */
    private static DayPlan plan(int seconds, Consumer<ObjectNode> change) {
        ObjectNode request = (ObjectNode) Required.value(CalculationJson.tree(PublicApiContractTest.example("DailyProposalRequest")));
        change.accept(request);
        RoadClient roads = mock(RoadClient.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("activeIdentity")) return "omaha-map-v7";
            if (!method.equals("matrix")) throw new AssertionError("Unexpected routing call " + method);
            Map<String, RoadPoint> points = Required.value(invocation.getArgument(0));
            Map<String, RoadClient.Leg> legs = new HashMap<>();
            for (String from : points.keySet()) for (String to : points.keySet())
                legs.put(from + ">" + to, from.equals(to) ? new RoadClient.Leg(0, 0) : new RoadClient.Leg(seconds, 8000));
            return legs;
        });
        var parsed = PublicRequests.read(CalculationJson.write(request), PublicRequests.DailyProposalRequest.class);
        return DailyPreparation.prepare(parsed, new MetroRouting(Required.value(Map.of("omaha", "http://a:1")), _ -> roads), _ -> new RoadPoint(41.2587, -95.9378)).plan();
    }

    private static DayPlan plan(int seconds) { return plan(seconds, _ -> { }); }

    @Test void routesThatHoldReadNoViolationsInRouteOrder() {
        DayPlan plan = plan(600);
        assertTrue(RouteEvaluator.evaluate(plan).feasible());
        var routes = DayScoreCalculator.violations(plan);
        assertEquals(List.of("tech-1", "tech-2"), routes.stream().map(route -> Required.value(route).technicianId()).toList());
        for (var route : routes) {
            assertTrue(route.holds(), route.toString());
            assertEquals(0, route.overtimeMinutes());
        }
        assertThrows(IllegalStateException.class, () -> DailyProposals.infeasible(plan, DAY), "a day that holds has nothing to name");
    }

    @Test void aLateWindowNamesOnlyItsTechnicianDayAndByHowMuch() {
        // appt-7's window closes at 08:05, but tech-1 leaves at 08:00 on a 10-minute drive (plus the travel buffer).
        DayPlan plan = plan(600, request -> {
            ArrayNode appointments = (ArrayNode) Required.value(request.path("snapshot").get("appointments"));
            ((ObjectNode) Required.value(appointments.get(0).get("window"))).put("end", "2026-10-12T08:05:00-05:00");
        });
        assertFalse(RouteEvaluator.evaluate(plan).feasible());
        var routes = DayScoreCalculator.violations(plan);
        var late = Required.value(routes.getFirst());
        assertEquals("tech-1", late.technicianId());
        assertFalse(late.holds());
        assertTrue(late.latenessSeconds() > 0, late.toString());
        assertTrue(Required.value(routes.get(1)).holds(), "tech-2's day, with no appointments, holds");
        String message = DailyProposals.infeasible(plan, DAY);
        assertTrue(message.contains("tech-1 on 2026-10-12 arrives "), message);
        assertTrue(message.contains(" min past the end of its arrival windows"), message);
        assertFalse(message.contains("tech-2"), message);
    }

    @Test void aDayFarOutOfReachNamesTheTechnicianDayWithWorkAndMatchesTheScore() {
        // Twelve hours each way: tech-1 reaches no window and is not back inside the shift; tech-2 has no work, so it holds.
        DayPlan plan = plan(43_200);
        var routes = DayScoreCalculator.violations(plan);
        assertFalse(Required.value(routes.getFirst()).holds(), routes.toString());
        assertTrue(Required.value(routes.get(1)).holds(), routes.toString());
        long total = 0;
        for (var route : routes) total += route.unqualifiedVisits() + route.unavailableRoads() + route.latenessSeconds() + route.outsideHoursSeconds() + route.capacityMinutes();
        assertEquals(DayScoreCalculator.evaluate(plan).hardPenalty(), total, "the readings are the score's own, route by route");
        String message = DailyProposals.infeasible(plan, DAY);
        assertTrue(message.contains("tech-1 on 2026-10-12") && !message.contains("tech-2"), message);
        assertTrue(message.contains("outside its shift or during an absence"), message);
    }

    @Test void aReadingIsNeverNegative() {
        assertThrows(IllegalArgumentException.class, () -> new DayScoreCalculator.RouteViolations("tech-1", 0, 0, -1, 0, 0, 0));
    }
}
