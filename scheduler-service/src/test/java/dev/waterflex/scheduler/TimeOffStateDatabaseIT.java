package dev.waterflex.scheduler;

import dev.waterflex.scheduler.optimizer.OptimizationService;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No background worker: transient states are asserted inside a rolled-back transaction. */
class TimeOffStateDatabaseIT {
    @Test void submissionRetryAndDenialDuringAnalysisPreserveTheirStateContracts() {
        String url = Required.value(System.getenv("JDBC_DATABASE_URL"), "isolated integration database URL");
        assertEquals("/waterflex_test", URI.create(url.substring("jdbc:".length())).getPath());
        var source = new DriverManagerDataSource(url, "waterflex", "waterflex");
        var jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        var optimizer = mock(OptimizationService.class);
        var service = new TimeOffService(jdbc, optimizer, mock(ScheduleGuardService.class), manager);
        new TransactionTemplate(manager).executeWithoutResult(transaction -> {
            transaction.setRollbackOnly();
            String technician = "timeoff-state-" + UUID.randomUUID();
            jdbc.update("INSERT INTO technician (id,name,color,\"homeLat\",\"homeLng\",\"shiftStartMin\",\"shiftEndMin\",\"updatedAt\") VALUES (?,?,'#000000',43.7,7.4,480,1020,CURRENT_TIMESTAMP)", technician, technician);
            String date = Required.value(LocalDate.now().plusDays(30).toString());
            var submitted = service.submit(new TimeOffService.Request(technician, date, date, 480, 1020, "Other", "State fixture"));
            String id = (String) Required.value(submitted.get("requestId"));
            assertEquals("PENDING", submitted.get("status"));
            assertEquals("QUEUED", status(jdbc, id));
            assertThrows(ResponseStatusException.class, () -> service.retry(id));
            for (String failure : new String[] {"ANALYSIS_FAILURE", "ROUTING_FAILURE"}) {
                jdbc.update("UPDATE time_off_report SET status=?,data='{}'::jsonb,progress=80 WHERE \"requestId\"=?", failure, id);
                assertEquals("PENDING", service.retry(id).get("status"));
                assertEquals("QUEUED", status(jdbc, id));
                assertEquals(1, Required.query(jdbc, "SELECT count(*) FROM time_off_report WHERE \"requestId\"=? AND data IS NULL AND progress=0", Integer.class, id));
            }
            jdbc.update("UPDATE time_off_report SET status='ANALYZING' WHERE \"requestId\"=?", id);
            assertEquals("DENIED", service.deny(id).get("status"));
            assertEquals("DENIED", status(jdbc, id));
            assertEquals(1, Required.query(jdbc, "SELECT count(*) FROM time_off_request WHERE id=? AND status='DENIED' AND \"decidedAt\" IS NOT NULL", Integer.class, id));
            assertThrows(ResponseStatusException.class, () -> service.retry(id));
            service.processQueued();
            assertEquals("DENIED", status(jdbc, id));
            verifyNoInteractions(optimizer);
        });
    }

    private static String status(JdbcTemplate jdbc, String id) {
        return Required.query(jdbc, "SELECT status FROM time_off_report WHERE \"requestId\"=?", String.class, id);
    }
}
