package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservedOvertimeTest {
    @Test void reservedAuthorizationSurvivesDemandDropButLimitsAndAuthorizationRemainHard() {
        for (boolean authorized : List.of(false, true)) for (int limit : List.of(10, 60)) {
            var date = Required.value(LocalDate.parse("2026-10-26"));
            var start = Required.value(Instant.parse("2026-10-26T14:00:00Z"));
            var end = Required.value(start.plusSeconds(7200));
            var point = new RoadClient.Point(41.25, -95.93);
            var technician = new Technician("tech", start, end, 180, limit, Required.value(Set.of("service")),
                    Required.value(List.of()), point, point, 1);
            var visit = new Visit("hold", "job", "service", end, Required.value(end.plusSeconds(7200)), 30, point, "tech", end, true);
            var roads = new Roads(Required.value(Map.of("tech>hold", new DayPlan.RoadLeg(60, 100),
                    "hold>tech:return", new DayPlan.RoadLeg(60, 100), "tech>appointment", new DayPlan.RoadLeg(60, 100),
                    "appointment>tech:return", new DayPlan.RoadLeg(60, 100))), Required.value(Set.of()));
            var day = new Day(Required.value(Map.of("tech", technician)), Required.value(Map.of("hold", visit)),
                    new Arrangement(Required.value(Map.of("tech", List.of("hold")))), 1, roads);
            var hold = new ReservationState.Hold("job", "offer", Required.value(start.plusSeconds(600)), authorized);
            var policy = SchedulingPolicy.Rules.defaults();
            assertFalse(policy.authorizeOvertime(0, 0, 120, true), "Demand has fallen below the booking authorization threshold");
            var facts = new BookingSnapshotLoader.Facts("metro", start, "configuration", "roads", policy,
                    new Rates(30, 45, 0, 0, 0), Required.value(Map.of(date, day)), Required.value(Map.of(date, Map.of("hold", hold))));
            var routing = mock(SnapshotRouting.class, invocation -> {
                assertEquals("arrangements", invocation.getMethod().getName());
                return day;
            });
            var transition = new ReservationTransition(routing);
            if (authorized && limit == 60) {
                var result = Required.value(transition.prepare(facts, "job", new ReservationTransition.Confirmation("hold", "appointment")).get(date));
                assertTrue(result.validation().feasible()); assertTrue(result.validation().overtimeMinutes() > 0);
                assertTrue(result.holds().isEmpty());
                assertFalse(Required.value(result.day().visits().get("appointment")).reservation());
            } else assertThrows(ResponseStatusException.class,
                    () -> transition.prepare(facts, "job", new ReservationTransition.Confirmation("hold", "appointment")));
        }
    }
}
