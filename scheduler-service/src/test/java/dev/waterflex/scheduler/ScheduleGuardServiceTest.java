package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ScheduleGuardServiceTest {
    @Test
    void availabilityUsesSharedDateCutoffBeforeWriting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ScheduleGuardService service = spy(new ScheduleGuardService(jdbc));
        LocalDate day = LocalDate.parse("2026-09-21");
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Frozen date requires CSR coordination"))
                .when(service).unfrozen(day);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.availability("tech", day, true, 480, 1020));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void qualificationRemainsIndependentOfDateCutoff() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ScheduleGuardService service = spy(new ScheduleGuardService(jdbc));
        ResponseStatusException stopped = new ResponseStatusException(HttpStatus.NOT_FOUND, "Technician not found");
        doThrow(stopped).when(service).lockTechnician("tech");

        assertEquals(stopped, assertThrows(ResponseStatusException.class,
                () -> service.qualification("tech", "service", true)));
        verify(service, never()).unfrozen(any());
    }
}
