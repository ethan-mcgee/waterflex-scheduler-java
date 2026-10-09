package dev.waterflex.scheduler.api;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.SearchAdmission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RouteGeometriesTest {
    private static final RoadPoint LOCATED = new RoadPoint(41.2587, -95.9378);

    /** Draws each consecutive pair of points as a straight two-position leg, as the routing service's route answer, and records each request's points. */
    private static RoadClient roads(AtomicReference<String> identity, List<List<RoadPoint>> requested, @Nullable Runnable whileDrawing) {
        return mock(RoadClient.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("activeIdentity")) return identity.get();
            if (!method.equals("routeGeometry")) throw new AssertionError("Unexpected routing call " + method);
            List<RoadPoint> points = Required.value(invocation.getArgument(0));
            requested.add(points);
            if (whileDrawing != null) whileDrawing.run();
            var answer = JsonNodeFactory.instance.objectNode();
            ArrayNode legs = answer.putArray("legs");
            for (int index = 0; index + 1 < points.size(); index++) {
                RoadPoint from = Required.value(points.get(index)), to = Required.value(points.get(index + 1));
                ObjectNode leg = legs.addObject().put("seconds", 600).put("meters", 3000);
                ObjectNode geometry = leg.putObject("geometry").put("type", "LineString");
                ArrayNode coordinates = geometry.putArray("coordinates");
                coordinates.addArray().add(from.lng()).add(from.lat());
                coordinates.addArray().add(to.lng()).add(to.lat());
            }
            return answer;
        });
    }

    private static RouteGeometries geometries(RoadClient roads, DailyPreparation.AddressLocator locator) {
        return new RouteGeometries(new MetroRouting(Required.value(Map.of("omaha", "http://a:1")), _ -> roads), locator, new SearchAdmission(2, 16));
    }

    /** The spec's example day, with tech-1's absences replaced. */
    private static String request(String... absence) {
        ObjectNode request = (ObjectNode) Required.value(CalculationJson.tree(PublicApiContractTest.example("RouteEvaluationRequest")));
        ObjectNode tech1 = (ObjectNode) Required.value(request.path("snapshot").path("technicianDays").get(0));
        ArrayNode absences = tech1.putArray("absences");
        if (absence.length == 2) absences.addObject().put("start", absence[0]).put("end", absence[1]);
        return CalculationJson.write(request);
    }

    private static PublicResponses.RouteGeometry drawn(DailyProposals.Reply reply) {
        assertEquals(200, reply.status(), reply.json());
        return PublicRequests.read(reply.json(), PublicResponses.RouteGeometry.class);
    }

    @Test void eachRouteIsDrawnFromItsStartThroughItsStopsInOrderToItsEnd() {
        List<List<RoadPoint>> requested = new ArrayList<>();
        var geometry = drawn(geometries(roads(new AtomicReference<>("omaha-map-v7"), requested, null), _ -> LOCATED).draw("acme", request()));
        assertEquals("omaha-map-v7", geometry.routingIdentity());
        var tech1 = Required.value(geometry.routes().getFirst());
        assertEquals(List.of("appt-7", "appt-8"), tech1.stops().stream().map(stop -> Required.value(stop).appointmentId()).toList());
        assertEquals(3, tech1.legs().size(), "start, two stops and end are three legs");
        assertEquals(List.of(new RoadPoint(41.2565, -95.9345), new RoadPoint(41.2603, -96.0731), LOCATED, new RoadPoint(41.2565, -95.9345)), requested.getFirst(),
                "The appointment sent by address is drawn where it was located");
        var tech2 = Required.value(geometry.routes().get(1));
        assertEquals(List.of(), tech2.legs(), "A day without appointments draws nothing");
        assertEquals(1, requested.size());
    }

    @Test void anAbsenceSplitsTheDayIntoSegmentsAndAStopDuringItIsRefused() {
        List<List<RoadPoint>> requested = new ArrayList<>();
        var geometry = drawn(geometries(roads(new AtomicReference<>("omaha-map-v7"), requested, null), _ -> LOCATED)
                .draw("acme", request("2026-10-12T10:00:00-05:00", "2026-10-12T11:00:00-05:00")));
        var legs = Required.value(geometry.routes().getFirst()).legs();
        assertEquals(List.of(0, 0, 1, 1), legs.stream().map(leg -> Required.value(leg).segment()).toList(), "Each segment is drawn from start to end");
        assertEquals(2, requested.size());
        var overlap = geometries(roads(new AtomicReference<>("omaha-map-v7"), new ArrayList<>(), null), _ -> LOCATED)
                .draw("acme", request("2026-10-12T12:00:00-05:00", "2026-10-12T13:00:00-05:00"));
        assertEquals(422, overlap.status(), overlap.json());
    }

    @Test void anUnlocatableDayIsSkippedAndAMapChangeWhileDrawingIsRetryable() {
        var skipped = drawn(geometries(roads(new AtomicReference<>("omaha-map-v7"), new ArrayList<>(), null), _ -> null).draw("acme", request()));
        assertEquals(List.of("tech-1"), skipped.skippedTechnicianDays().stream().map(day -> Required.value(day).technicianId()).toList());
        assertEquals(List.of("tech-2"), skipped.routes().stream().map(route -> Required.value(route).technicianId()).toList());
        var identity = new AtomicReference<>("omaha-map-v7");
        var changed = geometries(roads(identity, new ArrayList<>(), () -> identity.set("omaha-map-v8")), _ -> LOCATED).draw("acme", request());
        assertEquals(503, changed.status(), changed.json());
        assertEquals(PublicResponses.ErrorCode.ROUTING_UNAVAILABLE, PublicRequests.read(changed.json(), PublicResponses.Problem.class).error());
    }
}
