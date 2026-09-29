package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;

class BookingLocationTest {
    @Test void outsideCoverageUnroutableAndRoutingFailureStayDistinct() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
            if (!"query".equals(invocation.getMethod().getName())) throw new AssertionError("Unexpected database operation");
            return List.of(new BookingLocationController.Circle(new RoadClient.Point(41.2, -96), 65));
        });
        RoadClient roads = mock(RoadClient.class);
        var point = new RoadClient.Point(41.2, -96);
        var pairs = Required.value(List.<RoadClient.Pair>of(new RoadClient.Pair("location", point, point)));
        var controller = new BookingLocationController(jdbc, roads);
        assertEquals(BookingLocationController.Status.OUTSIDE_COVERAGE, controller.validate(new BookingLocationController.Request(0.0, 0.0)).status());
        verifyNoInteractions(roads);
        when(roads.activeIdentity()).thenReturn("fixture-version");
        when(roads.sparse(pairs, "fixture-version")).thenReturn(Required.value(Map.of()));
        assertEquals(BookingLocationController.Status.UNROUTABLE, controller.validate(new BookingLocationController.Request(41.2, -96.0)).status());
        when(roads.sparse(pairs, "fixture-version")).thenThrow(new RoadClient.RoadUnavailable("offline"));
        assertEquals(BookingLocationController.Status.ROUTING_UNAVAILABLE, controller.validate(new BookingLocationController.Request(41.2, -96.0)).status());
    }
    @Test void missingCoverageConfigurationFailsInsteadOfDeclaringAnAddressOutside() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
            if (!"query".equals(invocation.getMethod().getName())) throw new AssertionError("Unexpected database operation");
            return List.of();
        });
        var controller = new BookingLocationController(jdbc, mock(RoadClient.class));
        assertEquals(503, assertThrows(ResponseStatusException.class,
            () -> controller.validate(new BookingLocationController.Request(41.2, -96.0))).getStatusCode().value());
    }
    @Test void missingAndNonfiniteCoordinatesFailAtBoundary() {
        assertThrows(ResponseStatusException.class, () -> new BookingLocationController.Request(null, -96.0));
        assertThrows(ResponseStatusException.class, () -> new BookingLocationController.Request(41.0, null));
        assertThrows(ResponseStatusException.class, () -> new BookingLocationController.Request(Double.NaN, -96.0));
        assertThrows(ResponseStatusException.class, () -> new BookingLocationController.Request(91.0, -96.0));
    }
    @Test void allBearingsRespectSixtyFiveMileBoundary() {
        var center = new RoadClient.Point(41.2, -96.0);
        var circle = new BookingLocationController.Circle(center, 65);
        for (int bearing = 0; bearing < 360; bearing += 5) {
            assertTrue(BookingLocationController.within(destination(center, bearing, 64.99), circle));
            assertFalse(BookingLocationController.within(destination(center, bearing, 65.01), circle));
        }
        assertTrue(BookingLocationController.within(new RoadClient.Point(40.45, -95.6), circle), "Northwest Missouri");
        assertTrue(BookingLocationController.within(new RoadClient.Point(41.3, -95.4), circle), "Iowa");
    }
    private static RoadClient.Point destination(RoadClient.Point center, double bearing, double miles) {
        double angle = miles / 3958.8, heading = Math.toRadians(bearing), lat = Math.toRadians(center.lat()), lng = Math.toRadians(center.lng());
        double targetLat = Math.asin(Math.sin(lat) * Math.cos(angle) + Math.cos(lat) * Math.sin(angle) * Math.cos(heading));
        double targetLng = lng + Math.atan2(Math.sin(heading) * Math.sin(angle) * Math.cos(lat), Math.cos(angle) - Math.sin(lat) * Math.sin(targetLat));
        return new RoadClient.Point(Math.toDegrees(targetLat), Math.toDegrees(targetLng));
    }
}
