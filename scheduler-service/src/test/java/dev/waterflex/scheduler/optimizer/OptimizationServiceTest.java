package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;

import ai.timefold.solver.core.api.solver.SolverFactory;
import dev.waterflex.scheduler.RoadClient;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OptimizationServiceTest {
    private interface DaySolverFactory extends SolverFactory<DayPlan> { }
    @Test
    void frozenPreviewIsAScheduleConflict() {
        OptimizationService service = new OptimizationService(
                mock(JdbcTemplate.class), mock(RoadClient.class), mock(DaySolverFactory.class));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.preview(new OptimizationService.Request("metro", "2000-01-01")));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test
    void overnightRunIncludesTodayAndNextNineEligibleWeekdays() {
        List<@NonNull LocalDate> expected = Required.value(List.of(
                Required.value(LocalDate.parse("2026-09-21")),
                Required.value(LocalDate.parse("2026-09-22")),
                Required.value(LocalDate.parse("2026-09-23")),
                Required.value(LocalDate.parse("2026-09-24")),
                Required.value(LocalDate.parse("2026-09-25")),
                Required.value(LocalDate.parse("2026-09-28")),
                Required.value(LocalDate.parse("2026-09-29")),
                Required.value(LocalDate.parse("2026-09-30")),
                Required.value(LocalDate.parse("2026-10-01")),
                Required.value(LocalDate.parse("2026-10-02"))
        ));
        assertTrue(expected.equals(OptimizationService.overnightDates(
                Required.value(Instant.parse("2026-09-21T07:00:00Z")))));
    }
}
