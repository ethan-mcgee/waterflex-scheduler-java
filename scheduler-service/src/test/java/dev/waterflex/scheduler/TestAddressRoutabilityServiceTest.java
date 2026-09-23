package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestAddressRoutabilityServiceTest {
    @Test
    void requiresOneQualifiedTechnicianWithBothDirectedLegs() {
        var candidates = List.of(new TestAddressRoutabilityService.Candidate("a", "FILTER", 41.2, -95.9),
                new TestAddressRoutabilityService.Candidate("b", "INSTALL", 41.3, -96.0));
        var endpoints = new RouteEndpoints(new RoadClient.Point(41, -96), new RoadClient.Point(41.1, -96.1));
        var technicians = List.of(new TestAddressRoutabilityService.Technician("one", "FILTER", endpoints),
                new TestAddressRoutabilityService.Technician("two", "INSTALL", endpoints));
        var legs = Map.of(
                "tech:one>candidate:a", new RoadClient.Leg(1, 1), "candidate:a>return:one", new RoadClient.Leg(1, 1),
                "tech:two>candidate:b", new RoadClient.Leg(1, 1));
        var results = TestAddressRoutabilityService.evaluate(candidates, technicians, legs);
        assertTrue(results.get(0).routable());
        assertFalse(results.get(1).routable());
    }
}
