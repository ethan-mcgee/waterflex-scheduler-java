package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.PlanFacts;
import dev.waterflex.scheduler.optimizer.PlanVisit;
import dev.waterflex.scheduler.optimizer.TechRoute;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The late-arrival buffer at the API boundary (spec 1.9.0): validation, the explicit absence, and what stops report. */
class ArrivalBufferApiTest {
    private static PublicTypes.Rates rates(PublicTypes.@org.jspecify.annotations.Nullable ArrivalBuffer buffer) {
        return new PublicTypes.Rates(new BigDecimal("30"), new BigDecimal("45"), new BigDecimal("0.67"), new BigDecimal("0.2"), 5, buffer);
    }

    @Test void outOfRangeOrMissingBoundsAreRefused() {
        assertDoesNotThrow(() -> new PublicTypes.ArrivalBuffer(25, 15, 60));
        assertDoesNotThrow(() -> new PublicTypes.ArrivalBuffer(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PublicTypes.ArrivalBuffer(51, 15, 60));
        assertThrows(IllegalArgumentException.class, () -> new PublicTypes.ArrivalBuffer(-1, 15, 60));
        assertThrows(IllegalArgumentException.class, () -> new PublicTypes.ArrivalBuffer(25, 61, 60));
        assertThrows(IllegalArgumentException.class, () -> new PublicTypes.ArrivalBuffer(25, 15, 241));
        // A missing bound is an error, never read as zero: absent properties reach the record as nulls, which it refuses.
        for (String missing : List.of("{\"minMinutes\":15,\"maxMinutes\":60}", "{\"percent\":25,\"maxMinutes\":60}", "{\"percent\":25,\"minMinutes\":15}",
                "{\"percent\":null,\"minMinutes\":15,\"maxMinutes\":60}")) {
            String json = Required.value(missing);
            var failure = assertThrows(IllegalArgumentException.class, () -> CalculationJson.readOmittable(json, PublicTypes.ArrivalBuffer.class), json);
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) cause = Required.value(cause.getCause());
            assertTrue(String.valueOf(cause.getMessage()).contains("rates.arrivalBuffer."), json + ": " + cause.getMessage());
        }
        assertEquals(new PublicTypes.ArrivalBuffer(25, 15, 60), CalculationJson.readOmittable("{\"percent\":25,\"minMinutes\":15,\"maxMinutes\":60}", PublicTypes.ArrivalBuffer.class));
    }

    @Test void anAbsentBufferIsTheExplicitNoneAndLeavesTheSnapshotJsonAsBefore() {
        assertEquals(PlanFacts.ArrivalBuffer.NONE, rates(null).arrival());
        assertEquals(new PlanFacts.ArrivalBuffer(25, 15, 60), rates(new PublicTypes.ArrivalBuffer(25, 15, 60)).arrival());
        String without = CalculationJson.write(rates(null));
        assertFalse(without.contains("arrivalBuffer"), without);
        assertTrue(CalculationJson.write(rates(new PublicTypes.ArrivalBuffer(25, 15, 60))).contains("\"arrivalBuffer\""));
    }

    /** 25 percent held to 15 to 60 minutes, by window length: the floor, the share, the cap and half the window. */
    @Test void theBufferFollowsTheWindowLength() {
        var buffer = new PlanFacts.ArrivalBuffer(25, 15, 60);
        Instant start = Required.value(Instant.parse("2026-10-27T08:00:00Z"));
        assertEquals(Instant.parse("2026-10-27T08:45:00Z"), buffer.deadline(start, Required.value(start.plusSeconds(60 * 60))));
        assertEquals(Instant.parse("2026-10-27T09:30:00Z"), buffer.deadline(start, Required.value(start.plusSeconds(120 * 60))));
        assertEquals(Instant.parse("2026-10-27T11:00:00Z"), buffer.deadline(start, Required.value(start.plusSeconds(240 * 60))));
        assertEquals(Instant.parse("2026-10-27T15:00:00Z"), buffer.deadline(start, Required.value(start.plusSeconds(480 * 60))));
        // A 20-minute window: the 15-minute floor would leave 5, so half the window (10 minutes) holds instead.
        assertEquals(Instant.parse("2026-10-27T08:10:00Z"), buffer.deadline(start, Required.value(start.plusSeconds(20 * 60))));
        // 25 percent of 70 minutes is 17.5; whole minutes round down to 17.
        assertEquals(Instant.parse("2026-10-27T08:53:00Z"), buffer.deadline(start, Required.value(start.plusSeconds(70 * 60))));
        assertEquals(Required.value(start.plusSeconds(60 * 60)), PlanFacts.ArrivalBuffer.NONE.deadline(start, Required.value(start.plusSeconds(60 * 60))));
        assertTrue(new PlanFacts.ArrivalBuffer(25, 0, 0).none());
    }

    @Test void stopsReportTheirBufferStartOnlyWhenTheSnapshotHasABuffer() {
        Instant eight = Required.value(Instant.parse("2026-10-27T08:00:00Z")), nine = Required.value(Instant.parse("2026-10-27T09:00:00Z"));
        var route = new TechRoute("t", eight, Required.value(Instant.parse("2026-10-27T16:00:00Z")), 480, 0, Required.value(Set.of("s")));
        var visit = new PlanVisit("v", "s", eight, nine, 30, null, null);
        route.getVisits().add(visit);
        var roads = Required.value(Map.of("t>v", new DayPlan.RoadLeg(0, 0), "v>t:return", new DayPlan.RoadLeg(0, 0)));
        var buffered = new DayPlan(Required.value(List.of(route)), Required.value(List.of(visit)), roads, Required.value(Set.of()),
                new BigDecimal("30"), new BigDecimal("45"), new BigDecimal("0"), new BigDecimal("0"), 0, new PlanFacts.ArrivalBuffer(25, 15, 60));
        var early = PublicResponses.stop(buffered, visit, 0, Required.value(Instant.parse("2026-10-27T08:44:00Z")));
        var late = PublicResponses.stop(buffered, visit, 0, Required.value(Instant.parse("2026-10-27T08:45:00Z")));
        assertEquals(Instant.parse("2026-10-27T08:45:00Z"), early.arrivalBufferStart());
        LocalDate day = Required.value(LocalDate.parse("2026-10-27"));
        assertEquals(0, PublicResponses.stopsInsideBuffer(buffered, List.of(new PublicResponses.PlannedRoute("t", day, List.of(early)))));
        assertEquals(1, PublicResponses.stopsInsideBuffer(buffered, List.of(new PublicResponses.PlannedRoute("t", day, List.of(late)))));
        var plain = new DayPlan(Required.value(List.of(route)), Required.value(List.of(visit)), roads, 30, 45, 0, 0, 0);
        var stop = PublicResponses.stop(plain, visit, 0, Required.value(Instant.parse("2026-10-27T08:59:00Z")));
        assertNull(stop.arrivalBufferStart());
        assertNull(PublicResponses.stopsInsideBuffer(plain, List.of(new PublicResponses.PlannedRoute("t", day, List.of(stop)))));
        assertFalse(CalculationJson.write(stop).contains("arrivalBufferStart"));
    }
}
