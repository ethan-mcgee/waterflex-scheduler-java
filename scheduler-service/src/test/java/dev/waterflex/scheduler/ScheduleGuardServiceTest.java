package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ScheduleGuardServiceTest {
    private static final class GuardJdbc extends JdbcTemplate {
        private int appointmentCount;
        private final List<String> updates = new ArrayList<>();
        @Override public <T> @Nullable T queryForObject(String sql, Class<T> type, @Nullable Object @Nullable ... args) {
            return type.cast(appointmentCount);
        }
        @Override public int update(String sql, @Nullable Object @Nullable ... args) { updates.add(sql); return 1; }
        boolean updated(String prefix) { return updates.stream().anyMatch(sql -> sql.startsWith(prefix)); }
    }
    @Test
    void availabilityUsesSharedDateCutoffBeforeWriting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ScheduleGuardService service = spy(new ScheduleGuardService(jdbc));
        LocalDate day = Required.value(LocalDate.parse("2026-09-21"));
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
        verify(service, never()).unfrozen(MockArguments.day());
    }

    @Test
    void deletionUsesSharedDateCutoffBeforeWriting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ScheduleGuardService service = spy(new ScheduleGuardService(jdbc));
        LocalDate day = Required.value(LocalDate.parse("2026-09-21"));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Frozen date requires CSR coordination")).when(service).unfrozen(day);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> service.deleteAvailability("tech", day)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void deletionStopsForActiveHoldsAndAppointments() {
        GuardJdbc jdbc = new GuardJdbc();
        ScheduleGuardService service = spy(new ScheduleGuardService(jdbc));
        LocalDate day = Required.value(LocalDate.parse("2026-10-21"));
        doNothing().when(service).unfrozen(day); doNothing().when(service).lockTechnician("tech"); doNothing().when(service).lockDay("tech", day);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Active reservations require a fresh proposal")).when(service).noHolds("tech", day);
        assertThrows(ResponseStatusException.class, () -> service.deleteAvailability("tech", day));
        assertEquals(false, jdbc.updated("DELETE"));

        doNothing().when(service).noHolds("tech", day);
        jdbc.appointmentCount = 1;
        ResponseStatusException appointment = assertThrows(ResponseStatusException.class, () -> service.deleteAvailability("tech", day));
        assertEquals("Existing appointments require schedule repair", appointment.getReason());
        assertEquals(false, jdbc.updated("DELETE"));
    }

    @Test
    void successfulDeletionRemovesOverrideAndBumpsScheduleVersion() {
        GuardJdbc jdbc = new GuardJdbc();
        ScheduleGuardService service = spy(new ScheduleGuardService(jdbc));
        LocalDate day = Required.value(LocalDate.parse("2026-10-21"));
        doNothing().when(service).unfrozen(day); doNothing().when(service).lockTechnician("tech"); doNothing().when(service).lockDay("tech", day); doNothing().when(service).noHolds("tech", day);
        service.deleteAvailability("tech", day);
        assertEquals(true, jdbc.updated("DELETE FROM technician_shift_override"));
        assertEquals(true, jdbc.updated("UPDATE schedule_day SET version=version+1"));
    }

    @Test
    void deletionRequestRejectsBlankTechnicianAndInvalidDate() {
        assertThrows(ResponseStatusException.class, () -> new ScheduleGuardController.DeleteAvailabilityRequest("", "2026-10-21"));
        assertThrows(ResponseStatusException.class, () -> new ScheduleGuardController.DeleteAvailabilityRequest("tech", "2026-02-30"));
    }
}
