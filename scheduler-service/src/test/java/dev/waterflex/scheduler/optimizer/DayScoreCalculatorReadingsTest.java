package dev.waterflex.scheduler.optimizer;

import static org.junit.jupiter.api.Assertions.*;
import dev.waterflex.scheduler.Required;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Per-route readings for the backtest: the score's own timing, in numbers, whether or not the day holds. */
class DayScoreCalculatorReadingsTest {
    private static Instant at(String time) { return Required.value(Instant.parse("2026-09-21T" + time + "Z")); }

    /** Two visits at a 10 minute leg each; the second window opens at 13:00 so a feasible route waits for it. */
    private static DayPlan plan(Instant secondWindowStart, Instant secondWindowEnd, int maxDailyMinutes) {
        TechRoute route = new TechRoute("home", at("09:00:00"), at("17:00:00"), maxDailyMinutes, 0, Required.value(Set.of("service")));
        PlanVisit first = new PlanVisit("first", "service", at("09:00:00"), at("12:00:00"), 60, "home", at("09:00:00"));
        PlanVisit second = new PlanVisit("second", "service", secondWindowStart, secondWindowEnd, 60, "home", secondWindowStart);
        route.getVisits().addAll(Required.value(List.of(first, second)));
        Map<String, DayPlan.RoadLeg> matrix = new HashMap<>();
        for (String from : List.of("home", "first", "second")) for (String to : List.of("first", "second", "home:return"))
            if (!from.equals(to)) matrix.put(from + ">" + to, new DayPlan.RoadLeg(600, 3000));
        return new DayPlan(Required.value(List.of(route)), Required.value(List.of(first, second)), matrix, 30, 45, 0.67, 0, 0);
    }

    @Test void aFeasibleRouteReadsNoViolationsAndTheEvaluatorsArrivals() {
        DayPlan day = plan(at("13:00:00"), at("15:00:00"), 480);
        var readings = DayScoreCalculator.routes(day);
        assertEquals(1, readings.size());
        var reading = Required.value(readings.getFirst());
        assertEquals("home", reading.technicianId());
        assertTrue(reading.holds());
        assertEquals(0, reading.hardPenalty());
        var independent = RouteEvaluator.evaluate(day);
        assertTrue(independent.feasible());
        assertEquals(independent.arrivals(), reading.arrivals());
        assertEquals(independent.paidMinutes(), reading.paidMinutes());
        assertEquals(independent.driveMinutes(), reading.driveMinutes());
        assertEquals(independent.meters(), reading.meters());
        assertEquals(DayScoreCalculator.evaluate(day).hardPenalty(), reading.hardPenalty());
    }

    @Test void aLateWindowReadsLatenessWithArrivalsStillReported() {
        // The second window closes before the first visit can even end, so no placement holds.
        DayPlan day = plan(at("09:00:00"), at("09:30:00"), 480);
        assertFalse(RouteEvaluator.evaluate(day).feasible());
        var reading = Required.value(DayScoreCalculator.routes(day).getFirst());
        assertFalse(reading.holds());
        assertTrue(reading.latenessSeconds() > 0);
        assertEquals(0, reading.unavailableRoads());
        assertEquals(2, reading.arrivals().size(), "the relaxed walk still times every stop");
        assertTrue(Required.value(reading.arrivals().get("second")).isAfter(at("09:30:00")));
        assertEquals(DayScoreCalculator.evaluate(day).hardPenalty(), reading.hardPenalty());
        assertEquals(DayScoreCalculator.evaluate(day).paidMinutes(), reading.paidMinutes());
    }

    @Test void workPastTheShiftReadsAvailabilityAndCapacitySeconds() {
        // The second window opens at the shift end: the walk returns after 17:00 and over the 300 paid minutes.
        DayPlan day = plan(at("17:00:00"), at("19:00:00"), 300);
        var reading = Required.value(DayScoreCalculator.routes(day).getFirst());
        assertFalse(reading.holds());
        assertTrue(reading.availabilitySeconds() > 0, reading.toString());
        assertTrue(reading.capacityMinutes() > 0, reading.toString());
        assertEquals(0, reading.latenessSeconds());
        assertEquals(DayScoreCalculator.evaluate(day).hardPenalty(), reading.hardPenalty());
    }

    @Test void aMissingRoadIsCountedWithoutInventedArrivals() {
        DayPlan day = plan(at("13:00:00"), at("15:00:00"), 480);
        Map<String, DayPlan.RoadLeg> roads = new HashMap<>(day.getMatrix());
        roads.remove("first>second");
        DayPlan broken = new DayPlan(day.getRoutes(), day.getVisits(), roads, Required.value(Set.of("first>second")), day.getRegularHourly(), day.getOvertimeHourly(),
                day.getMileagePerMile(), day.getTravelBufferPct(), day.getTravelBufferMinutes(), day.getArrivalBuffer());
        var reading = Required.value(DayScoreCalculator.routes(broken).getFirst());
        assertFalse(reading.holds());
        assertEquals(1, reading.unavailableRoads());
        assertEquals(Map.of(), reading.arrivals());
        assertEquals(0, reading.paidMinutes());
        assertEquals(DayScoreCalculator.evaluate(broken).hardPenalty(), reading.hardPenalty());
    }
}
