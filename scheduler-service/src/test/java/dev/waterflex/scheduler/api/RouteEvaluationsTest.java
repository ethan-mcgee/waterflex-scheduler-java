package dev.waterflex.scheduler.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.SearchAdmission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RouteEvaluationsTest {
    private static final DailyPreparation.AddressLocator LOCATOR = _ -> new RoadPoint(41.2587, -95.9378);

    /** Every ordered pair takes `seconds` to drive, as RoadClient.matrix answers. */
    private static RoadClient roads(int seconds) {
        return mock(RoadClient.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("activeIdentity")) return "omaha-map-v7";
            if (!method.equals("matrix")) throw new AssertionError("Unexpected routing call " + method);
            Map<String, RoadPoint> points = Required.value(invocation.getArgument(0));
            Map<String, RoadClient.Leg> legs = new HashMap<>();
            for (String from : points.keySet()) for (String to : points.keySet())
                legs.put(from + ">" + to, from.equals(to) ? new RoadClient.Leg(0, 0) : new RoadClient.Leg(seconds, 1000));
            return legs;
        });
    }

    private static RouteEvaluations evaluations(int seconds) {
        return new RouteEvaluations(new MetroRouting(Required.value(Map.of("omaha", "http://a:1")), _ -> roads(seconds)), LOCATOR, new SearchAdmission(2, 16));
    }

    private static String request() { return PublicApiContractTest.example("RouteEvaluationRequest"); }

    @Test void routesAreTimedInTheirGivenOrderAndNothingIsOptimized() {
        var reply = evaluations(600).evaluate("acme", request());
        assertEquals(200, reply.status(), reply.json());
        var evaluation = PublicRequests.read(reply.json(), PublicResponses.RouteEvaluation.class);
        assertTrue(evaluation.feasible());
        assertEquals(List.of("tech-1", "tech-2"), evaluation.routes().stream().map(route -> Required.value(route).technicianId()).toList());
        var snapshot = PublicRequests.read(request(), PublicRequests.RouteEvaluationRequest.class).snapshot();
        for (PublicResponses.PlannedRoute route : evaluation.routes()) {
            var given = snapshot.appointments().stream().filter(item -> Required.value(item).technicianId().equals(route.technicianId())).map(item -> Required.value(item).id()).toList();
            assertEquals(given, route.stops().stream().map(stop -> Required.value(stop).appointmentId()).toList(), "Each technician keeps exactly its own appointments, in order");
            for (PublicResponses.PlannedStop stop : route.stops()) assertTrue(stop.plannedEnd().isAfter(stop.plannedStart()));
        }
        assertEquals(List.of(), evaluation.skippedTechnicianDays());
    }

    @Test void aDayThatNoLongerHoldsIsReportedNotRefused() {
        // Twelve hours each way: no appointment can be reached inside its window.
        var reply = evaluations(43_200).evaluate("acme", request());
        assertEquals(200, reply.status(), reply.json());
        var evaluation = PublicRequests.read(reply.json(), PublicResponses.RouteEvaluation.class);
        assertFalse(evaluation.feasible());
        assertEquals(List.of(), evaluation.routes(), "A day that does not hold has no timings to report");
        assertThrows(IllegalArgumentException.class, () -> new PublicResponses.RouteEvaluation(false,
                List.of(new PublicResponses.PlannedRoute("tech-1", Required.value(java.time.LocalDate.parse("2026-10-12")), Required.value(List.of()))), Required.value(List.of()), 0L, 0));
    }

    @Test void unknownFieldsAnUnknownMetroAndAnUnlocatableDayAreReported() {
        ObjectNode withId = (ObjectNode) Required.value(CalculationJson.tree(request()));
        withId.put("requestId", "3b1f6c1e-2a7d-4f0e-8c52-9a1d7e6b4c21");
        assertEquals(400, evaluations(600).evaluate("acme", CalculationJson.write(withId)).status(), "Nothing is stored, so no requestId is accepted");
        var lincolnOnly = new RouteEvaluations(new MetroRouting(Required.value(Map.of("lincoln", "http://b:1")), _ -> roads(600)), LOCATOR, new SearchAdmission(2, 16));
        assertEquals(422, lincolnOnly.evaluate("acme", request()).status());
        var unlocatable = new RouteEvaluations(new MetroRouting(Required.value(Map.of("omaha", "http://a:1")), _ -> roads(600)), _ -> null, new SearchAdmission(2, 16));
        var reply = unlocatable.evaluate("acme", request());
        assertEquals(200, reply.status(), reply.json());
        var evaluation = PublicRequests.read(reply.json(), PublicResponses.RouteEvaluation.class);
        assertEquals(List.of("tech-1"), evaluation.skippedTechnicianDays().stream().map(day -> Required.value(day).technicianId()).toList());
        assertEquals(List.of("tech-2"), evaluation.routes().stream().map(route -> Required.value(route).technicianId()).toList());
    }

    @Test void routingThatIsDownIsRetryable() {
        RoadClient down = mock(RoadClient.class, invocation -> {
            if (invocation.getMethod().getName().equals("activeIdentity")) return "omaha-map-v7";
            throw new RoadClient.RoadUnavailable("down");
        });
        var reply = new RouteEvaluations(new MetroRouting(Required.value(Map.of("omaha", "http://a:1")), _ -> down), LOCATOR, new SearchAdmission(2, 16))
                .evaluate("acme", request());
        assertEquals(503, reply.status());
        assertEquals(PublicResponses.ErrorCode.ROUTING_UNAVAILABLE, PublicRequests.read(reply.json(), PublicResponses.Problem.class).error());
    }
}
