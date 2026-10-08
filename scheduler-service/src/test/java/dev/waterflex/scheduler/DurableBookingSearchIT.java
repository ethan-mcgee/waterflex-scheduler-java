package dev.waterflex.scheduler;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DurableBookingSearchIT {
    @Test void idempotencyCancellationPublicationAndRestartRecoveryUseDatabaseOwnership() throws Exception {
        String url = Required.value(System.getenv("JDBC_DATABASE_URL"));
        assertEquals("/waterflex_test", URI.create(url.substring(5)).getPath());
        assertEquals("currentSchema=durable_contracts", URI.create(url.substring(5)).getQuery(), "Worker ownership test needs a schema without application workers");
        var source = new DriverManagerDataSource(url, "waterflex", "waterflex");
        var jdbc = new JdbcTemplate(source); var manager = new DataSourceTransactionManager(source);
        String prefix = "durable-it-" + UUID.randomUUID();
        var coordinator = mock(BookingCoordinator.class); var admission = new SearchAdmission(2, 16);
        var lifecycle = mock(ReservationLifecycleService.class);
        var control = new BookingSearchControl(jdbc, manager, lifecycle, admission);
        var first = new DurableBookingSearch(jdbc, manager, coordinator, admission, control);
        var restarted = new DurableBookingSearch(jdbc, manager, coordinator, admission, control);
        try {
            jdbc.update("INSERT INTO service_catalog (id,code,name,\"estDurationMin\",\"updatedAt\") VALUES (?,?,?,30,CURRENT_TIMESTAMP)", prefix, prefix, prefix);
            jdbc.update("INSERT INTO customer (id,\"clientId\",\"firstName\",\"lastName\",email,phone) VALUES (?,'default','Durable','Test','durable@example.invalid','0000000000')", prefix);
            jdbc.update("INSERT INTO address (id,\"customerId\",line1,city,state,\"postalCode\",lat,lng) VALUES (?,?,'Fixture','Omaha','NE','68102',41.25,-95.93)", prefix, prefix);
            jdbc.update("INSERT INTO job (id,\"customerId\",\"addressId\",\"serviceId\",\"durationMin\",\"updatedAt\") VALUES (?,?,?,?,30,CURRENT_TIMESTAMP)", prefix, prefix, prefix, prefix);
            String token = Required.value(UUID.randomUUID().toString());
            var pending = first.start(new DurableBookingSearch.Start(prefix, token, false));
            assertEquals("QUEUED", pending.state());
            assertEquals(token, restarted.start(new DurableBookingSearch.Start(prefix, Required.value(UUID.randomUUID().toString()), false)).id());
            assertEquals(token, restarted.start(new DurableBookingSearch.Start(prefix, token, false)).id());
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> first.status(token, "wrong-job"));

            var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
            when(coordinator.offers(prefix, false)).thenAnswer(_ -> {
                entered.countDown(); assertTrue(proceed.await(5, TimeUnit.SECONDS));
                new TransactionTemplate(manager).executeWithoutResult(_ -> SearchDeadline.beforeCommit());
                return new BookingCoordinator.Result(new BookingService.Offers(prefix, Required.value(List.of())), true, "COMPLETED");
            });
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var running = executor.submit(() -> ReflectionTestUtils.invokeMethod(first, "next"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals("RUNNING", restarted.status(token, prefix).state());
                control.cancel(token, prefix); proceed.countDown(); running.get(5, TimeUnit.SECONDS);
            }
            assertEquals("CANCELLED", restarted.status(token, prefix).state());
            verify(coordinator, times(1)).offers(prefix, false);

            String lost = Required.value(UUID.randomUUID().toString());
            first.start(new DurableBookingSearch.Start(prefix, lost, false));
            jdbc.update("UPDATE booking_search_request SET state='RUNNING',owner='lost',\"leaseUntil\"=clock_timestamp()-interval '1 second' WHERE id=?", lost);
            ReflectionTestUtils.invokeMethod(restarted, "heartbeat");
            assertEquals("INCOMPLETE", restarted.status(lost, prefix).state());
            assertEquals("WORKER_LOST_OR_WORK_LIMIT", restarted.status(lost, prefix).stopReason());

            String complete = Required.value(UUID.randomUUID().toString());
            first.start(new DurableBookingSearch.Start(prefix, complete, false));
            when(coordinator.offers(prefix, false)).thenReturn(new BookingCoordinator.Result(new BookingService.Offers(prefix, Required.value(List.of())), true, "COMPLETED"));
            ReflectionTestUtils.invokeMethod(restarted, "next");
            assertEquals("NO_CANDIDATE", first.status(complete, prefix).state());
            assertEquals(complete, first.start(new DurableBookingSearch.Start(prefix, complete, false)).id());
            verify(coordinator, times(2)).offers(prefix, false);
        } finally {
            first.stopWorkers(); restarted.stopWorkers(); control.stop();
            jdbc.update("DELETE FROM booking_search_request WHERE \"jobId\"=?", prefix);
            jdbc.update("DELETE FROM job WHERE id=?", prefix); jdbc.update("DELETE FROM address WHERE id=?", prefix);
            jdbc.update("DELETE FROM customer WHERE id=?", prefix); jdbc.update("DELETE FROM service_catalog WHERE id=?", prefix);
        }
    }
}
