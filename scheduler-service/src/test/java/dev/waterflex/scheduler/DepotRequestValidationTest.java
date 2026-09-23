package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DepotRequestValidationTest {
    @Test
    void missingPolicyEndpointsRejectBeforeDatabaseUse() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var controller = new DealershipPolicyController(jdbc, mock(BookingService.class));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.update("depot", new DealershipPolicyController.Policy(null, "HOME"))).getStatusCode().value());
        verifyNoInteractions(jdbc);
    }

    @Test
    void missingAssignmentFieldsRejectBeforeDatabaseUse() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var controller = new TechnicianDepotController(jdbc, mock(BookingService.class));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.assign("tech", new TechnicianDepotController.Assignment(null, "2026-09-23"))).getStatusCode().value());
        verifyNoInteractions(jdbc);
    }

    @Test
    void missingLocationPartsRejectBeforeDatabaseUse() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var controller = new DepotDetailsController(jdbc, mock(BookingService.class));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.update("depot", null)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.update("depot", new DepotDetailsController.Details("Depot", new DepotDetailsController.Address("1 Main", "Omaha", "NE", "68102"), null, null))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.update("depot", new DepotDetailsController.Details("Depot", new DepotDetailsController.Address("1 Main", "Omaha", "NE", "68102"),
                        new DepotDetailsController.Point(null, -95.9), new DepotDetailsController.Candidate(41.2, -95.9, "ROOFTOP")))).getStatusCode().value());
        verifyNoInteractions(jdbc);
    }
}
