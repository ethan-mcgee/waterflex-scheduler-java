package dev.waterflex.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimeOffTravelTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String ROUTE = """
        {"technician_id":"t","stop_count":1,"route_minutes":60,"drive_minutes":15,
        "waiting_minutes":0,"distance_meters":100,"modeled_cost_cents":3000,
        "workload_minutes":60,"overtime_minutes":0,"appointment_ids":["a"],
        "travel_breakdown":{"road_seconds":180,"configured_buffer_seconds":636,
        "rounding_seconds":84,"modeled_travel_minutes":15,"leg_count":2}}
        """;

    @Test void combinesDecimalAccountingAndPreservesUnavailableHistory() throws Exception {
        var totals = Required.value(TimeOffService.travelTotals(Required.value(mapper.readTree("[" + ROUTE + "," + ROUTE + "]"))));
        assertEquals(360, totals.road_seconds());
        assertEquals(1272, totals.configured_buffer_seconds().intValueExact());
        assertEquals(168, totals.rounding_seconds().intValueExact());
        assertEquals(30, totals.modeled_travel_minutes());
        var historical = Required.value(mapper.readTree(ROUTE));
        ((com.fasterxml.jackson.databind.node.ObjectNode) historical).remove("travel_breakdown");
        assertNull(TimeOffService.travelTotals(Required.value(mapper.createArrayNode().add(historical))));
    }

    @Test void rejectsNullAndInconsistentPersistedTravel() throws Exception {
        for (String malformed : new String[]{ROUTE.replace("\"road_seconds\":180", "\"road_seconds\":null"),
                ROUTE.replace("\"rounding_seconds\":84", "\"rounding_seconds\":85")}) {
            var routes = Required.value(mapper.readTree("[" + malformed + "]"));
            assertThrows(RuntimeException.class, () -> TimeOffService.travelTotals(routes));
        }
    }
    @Test void dailyCostUsesFleetBoundaryInsteadOfSumOfRoundedRoutes() throws Exception {
        var route = ROUTE.replace("\"modeled_cost_cents\":3000", "\"modeled_cost_cents\":1");
        var totals = TimeOffService.totals(Required.value(mapper.readTree("[" + route + "," + route + "]")), 1);
        assertEquals(1L, totals.get("modeled_cost_cents")); assertEquals(120L, totals.get("route_minutes"));
        assertThrows(RuntimeException.class, () -> TimeOffService.totals(Required.value(mapper.createArrayNode()), -1));
    }
}
