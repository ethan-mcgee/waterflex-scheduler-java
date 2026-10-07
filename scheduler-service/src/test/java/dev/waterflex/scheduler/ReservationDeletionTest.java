package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationDeletionTest {
    @Test void releaseAndCancellationRejectAnInfeasibleDirectedShortcutWithoutMutatingTheSnapshot() {
        for (boolean reservation : List.of(true, false)) {
            var date = Required.value(LocalDate.parse("2026-10-26"));
            var start = Required.value(Instant.parse("2026-10-26T13:00:00Z"));
            var end = Required.value(start.plusSeconds(7200));
            var point = new RoadPoint(41.25, -95.93);
            var technician = new Technician("tech", start, end, 60, 0, Required.value(Set.of("service")),
                    Required.value(List.of()), point, point, 1);
            var a = new Visit("a", "first", "service", start, end, 10, point, "tech", start, reservation);
            var b = new Visit("b", "second", "service", start, end, 10, point, "tech", start, true);
            var roads = new Roads(Required.value(Map.of("tech>a", new DayPlan.RoadLeg(60, 100),
                    "a>tech:return", new DayPlan.RoadLeg(60, 100), "a>b", new DayPlan.RoadLeg(60, 100),
                    "tech>b", new DayPlan.RoadLeg(5400, 100), "b>tech:return", new DayPlan.RoadLeg(60, 100))), Required.value(Set.of()));
            var day = new Day(Required.value(Map.of("tech", technician)), Required.value(Map.of("a", a, "b", b)),
                    new Arrangement(Required.value(Map.of("tech", List.of("a", "b")))), 1, roads);
            var rates = new Rates(30, 45, 0, 0, 0);
            assertTrue(day.evaluate(day.baseline(), day.visits(), rates).feasible());
            var now = Required.value(Instant.now());
            var expiry = Required.value(now.plusSeconds(600));
            Map<String, ReservationState.Hold> holds = new HashMap<>();
            holds.put("b", new ReservationState.Hold("second", "second-offer", expiry, false));
            if (reservation) holds.put("a", new ReservationState.Hold("first", "first-offer", expiry, false));
            var facts = new BookingSnapshotLoader.Facts("metro", now, "configuration", "roads", SchedulingPolicy.Rules.defaults(),
                    rates, Required.value(Map.of(date, day)), Required.value(Map.of(date, holds)));
            var routing = mock(SnapshotRouting.class, invocation -> {
                assertEquals("arrangements", invocation.getMethod().getName()); return day;
            });
            var transition = new ReservationTransition(routing);
            var failure = assertThrows(ResponseStatusException.class, () -> {
                if (reservation) transition.prepare(facts, "first", null); else transition.cancel(facts, "a");
            });
            assertEquals(409, failure.getStatusCode().value());
            assertEquals(List.of("a", "b"), day.baseline().routes().get("tech"));
            assertEquals(Set.of("a", "b"), day.visits().keySet());
            assertTrue(day.evaluate(day.baseline(), day.visits(), rates).feasible());
        }
    }
}
