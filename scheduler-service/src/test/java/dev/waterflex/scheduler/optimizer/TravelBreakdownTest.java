package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.BookingSnapshot;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SavedJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TravelBreakdownTest {
    @Test void directedRoadSecondsBufferAndRoundingReconcileWithoutChangingModel() {
        var plan = DayConstraintProviderTest.fixture(); var route = Required.value(plan.getRoutes().getFirst());
        var roads = Required.value(Map.<String, DayPlan.RoadLeg>of(route.getId() + ">visit", new DayPlan.RoadLeg(61, 100),
                "visit>" + route.getId() + ":return", new DayPlan.RoadLeg(119, 200)));
        var configured = new DayPlan(plan.getRoutes(), plan.getVisits(), roads, 30, 45, .67, .2, 5);
        Instant now = Required.value(Instant.parse("2026-10-26T08:00:00Z"));
        var segments = Required.value(List.<RouteEvaluator.WorkingSegment>of(new RouteEvaluator.WorkingSegment(now, now, Required.value(List.of("visit")))));
        var metrics = TravelBreakdown.forRoute(configured, route, segments);
        assertEquals(180, metrics.road_seconds()); assertEquals(15, metrics.modeled_travel_minutes()); assertEquals(2, metrics.leg_count());
        assertEquals(0, new BigDecimal("636").compareTo(metrics.configured_buffer_seconds()));
        assertEquals(0, new BigDecimal("84").compareTo(metrics.rounding_seconds()));
        var missingRoads = new DayPlan(plan.getRoutes(), plan.getVisits(), Required.value(Map.of()), 30, 45, .67, .2, 5);
        assertThrows(RuntimeException.class, () -> TravelBreakdown.forRoute(missingRoads, route, segments));
    }

    @Test void missingAndMalformedRequiredRatesNeverReceiveDefaults() {
        Map<String, Double> settings = new HashMap<>(Map.of("regular_hourly_dollars", 30.0, "overtime_hourly_dollars", 45.0,
                "mileage_dollars_per_mile", .67, "travel_buffer_pct", .2, "travel_buffer_minutes_per_leg", 5.0));
        for (String key : Required.value(List.copyOf(settings.keySet()))) {
            Double saved = settings.remove(key);
            assertThrows(RuntimeException.class, () -> BookingSnapshot.Rates.read(settings)); settings.put(key, Required.value(saved));
        }
        settings.put("travel_buffer_minutes_per_leg", 1.5);
        assertThrows(IllegalArgumentException.class, () -> BookingSnapshot.Rates.read(settings));
        settings.put("travel_buffer_minutes_per_leg", 5.0); settings.put("regular_hourly_dollars", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> BookingSnapshot.Rates.read(settings));
    }

    @Test void persistedTravelRejectsNullComponentsAndInconsistentTotalsButAllowsHistory() throws Exception {
        var mapper = new ObjectMapper();
        var summary = mapper.createArrayNode(); var route = summary.addObject();
        route.put("technician_id", "tech"); route.putArray("appointment_ids");
        for (String key : List.of("stop_count", "route_minutes", "drive_minutes", "waiting_minutes", "distance_meters", "modeled_cost_cents", "workload_minutes", "overtime_minutes")) route.put(key, 0);
        SavedJson.summary(summary);
        var travel = route.putObject("travel_breakdown").put("road_seconds", 0).put("modeled_travel_minutes", 0).put("leg_count", 0)
                .put("configured_buffer_seconds", 0).put("rounding_seconds", 0);
        SavedJson.summary(summary);
        travel.putNull("road_seconds"); assertThrows(RuntimeException.class, () -> SavedJson.summary(summary));
        travel.put("road_seconds", 0).put("rounding_seconds", 1); assertThrows(RuntimeException.class, () -> SavedJson.summary(summary));
    }
}
