package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.solver.SolverFactory;
import dev.waterflex.scheduler.RoadClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class OptimizationServiceTest {
    @Test
    void frozenPreviewIsAScheduleConflict() {
        OptimizationService service = new OptimizationService(
                mock(JdbcTemplate.class), mock(RoadClient.class), mock(SolverFactory.class));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.preview(new OptimizationService.Request("metro", "2000-01-01")));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test
    void overnightRunIncludesTodayAndNextNineEligibleWeekdays() {
        assertEquals(List.of(
                LocalDate.parse("2026-09-21"),
                LocalDate.parse("2026-09-22"),
                LocalDate.parse("2026-09-23"),
                LocalDate.parse("2026-09-24"),
                LocalDate.parse("2026-09-25"),
                LocalDate.parse("2026-09-28"),
                LocalDate.parse("2026-09-29"),
                LocalDate.parse("2026-09-30"),
                LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-02")
        ), OptimizationService.overnightDates(Instant.parse("2026-09-21T07:00:00Z")));
    }
}
