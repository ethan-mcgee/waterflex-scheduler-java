package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.api.PublicRequests.DailyProposalRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DailyPreparationTest {
    private static final DailyPreparation.AddressLocator LOCATOR = _ -> new RoadPoint(41.2587, -95.9378);

    private static DailyProposalRequest request() {
        return PublicRequests.read(PublicApiContractTest.example("DailyProposalRequest"), DailyProposalRequest.class);
    }

    /** Answers every ordered pair, the way RoadClient.matrix does, and records the identity each matrix call used. */
    private static RoadClient roads(AtomicReference<String> identity, List<String> requested, @Nullable Runnable duringMatrix) {
        return mock(RoadClient.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("activeIdentity")) return identity.get();
            if (!method.equals("matrix")) throw new AssertionError("Unexpected routing call " + method);
            requested.add(Required.value((String) invocation.getArgument(1)));
            if (duringMatrix != null) duringMatrix.run();
            Map<String, RoadPoint> points = Required.value(invocation.getArgument(0));
            Map<String, RoadClient.Leg> legs = new HashMap<>();
            for (String from : points.keySet()) for (String to : points.keySet())
                legs.put(from + ">" + to, from.equals(to) ? new RoadClient.Leg(0, 0) : new RoadClient.Leg(60, 100));
            return legs;
        });
    }

    @Test void routesThroughTheRequestMetroAndRecordsItsIdentity() {
        var identity = new AtomicReference<>("omaha-map-v7");
        List<String> requested = new ArrayList<>();
        RoadClient omaha = roads(identity, requested, null), lincoln = mock(RoadClient.class);
        var routing = new MetroRouting(Required.value(Map.of("omaha", "http://a:1", "lincoln", "http://b:1")), url -> "http://a:1".equals(url) ? omaha : lincoln);
        var prepared = DailyPreparation.prepare(request(), routing, LOCATOR);
        assertEquals("omaha-map-v7", prepared.routingIdentity());
        assertEquals(36, prepared.plan().getFacts().matrix().size());
        assertEquals(new dev.waterflex.scheduler.optimizer.DayPlan.RoadLeg(60, 100), prepared.plan().getFacts().matrix().get("tech-1>appt-7"));
        assertEquals(List.of("omaha-map-v7"), requested);
        verifyNoInteractions(lincoln);
    }

    @Test void anUnknownMetroFailsBeforeAnyGeocoding() {
        var located = new AtomicInteger();
        var routing = new MetroRouting(Required.value(Map.of("lincoln", "http://b:1")), _ -> mock(RoadClient.class));
        assertThrows(MetroRouting.UnknownMetro.class, () -> DailyPreparation.prepare(request(), routing, address -> { located.incrementAndGet(); return LOCATOR.locate(address); }));
        assertEquals(0, located.get());
    }

    @Test void aRoutingIdentityChangeDuringPreparationIsRejected() {
        var identity = new AtomicReference<>("omaha-map-v7");
        RoadClient omaha = roads(identity, new ArrayList<>(), () -> identity.set("omaha-map-v8"));
        var routing = new MetroRouting(Required.value(Map.of("omaha", "http://a:1")), _ -> omaha);
        assertThrows(RoadClient.RoadUnavailable.class, () -> DailyPreparation.prepare(request(), routing, LOCATOR));
    }

    @Test void aTechnicianDayWithAnUnlocatableAddressIsSkippedAndTheRestIsRouted() {
        var identity = new AtomicReference<>("omaha-map-v7");
        RoadClient omaha = roads(identity, new ArrayList<>(), null);
        var routing = new MetroRouting(Required.value(Map.of("omaha", "http://a:1")), _ -> omaha);
        var prepared = DailyPreparation.prepare(request(), routing, _ -> null);
        assertEquals(1, prepared.skipped().size());
        var skipped = Required.value(prepared.skipped().getFirst());
        assertEquals("tech-1", skipped.technicianId());
        assertEquals(PublicResponses.SkipReason.LOCATION_UNRESOLVED, skipped.reason());
        assertTrue(skipped.message().contains("appointment appt-8"), skipped.message());
        assertEquals(List.of("tech-2"), prepared.plan().getRoutes().stream().map(route -> Required.value(route).getId()).toList());
        assertEquals(List.of(), prepared.plan().getVisits());
        assertEquals(java.util.Set.of("tech-2", "tech-2:return"), prepared.points().keySet());
    }
}
