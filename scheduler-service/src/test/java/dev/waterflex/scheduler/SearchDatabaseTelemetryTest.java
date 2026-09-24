package dev.waterflex.scheduler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchDatabaseTelemetryTest {
    @Test void poolAdmissionRequiresEnoughBudgetAndClosesConnectionsArrivingAfterExpiry() throws Exception {
        var pool = mock(com.zaxxer.hikari.HikariDataSource.class);
        when(pool.getConnectionTimeout()).thenReturn(250L);
        when(pool.getValidationTimeout()).thenReturn(250L);
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var deadline = new SearchDeadline(Required.value(java.time.Duration.ofSeconds(5)), clock::get);
        var observed = (javax.sql.DataSource) new SearchDatabaseTelemetry().postProcessAfterInitialization(pool, "pool");
        deadline.within(() -> {
            SearchDeadline.beginCommit(); clock.set(4_501_000_000L);
            assertThrows(SearchAdmission.Busy.class, observed::getConnection);
            return true;
        });
        verify(pool, never()).getConnection();
        var connection = mock(Connection.class);
        clock.set(0);
        when(pool.getConnection()).thenAnswer(_ -> { clock.set(5_000_000_000L); return connection; });
        deadline.within(() -> { assertThrows(SearchDeadline.Expired.class, observed::getConnection); return true; });
        verify(connection).close();
    }

    @Test void statementAndCommitTransportUseRemainingBudgetAndExpiredRollbackStillRuns() throws Exception {
        var target = mock(Connection.class); var statement = mock(PreparedStatement.class);
        when(target.prepareStatement("SELECT 1")).thenReturn(statement);
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var deadline = new SearchDeadline(Required.value(java.time.Duration.ofSeconds(5)), clock::get);
        var connection = SearchDatabaseTelemetry.connection(target, deadline.telemetry());
        deadline.within(() -> {
            try {
                connection.prepareStatement("SELECT 1").executeUpdate();
                clock.set(3_500_000_000L); SearchDeadline.beginCommit(); connection.commit();
                clock.set(5_000_000_000L);
                assertThrows(SearchDeadline.Expired.class, connection::commit);
                connection.rollback();
            } catch (SQLException failure) { throw new IllegalStateException(failure); }
            return true;
        });
        var timeouts = mockingDetails(target).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("setNetworkTimeout"))
                .map(call -> Required.value(call.getArgument(1, Integer.class))).toList();
        assertEquals(java.util.List.of(4000, 1500, 1), timeouts);
        verify(target).commit(); verify(target).rollback();
    }

    @Test void preservesNullBindingsResultsExceptionsAndConnectionLifecycle() throws Exception {
        var target = mock(Connection.class); var statement = mock(PreparedStatement.class);
        when(target.prepareStatement("SELECT id FROM job FOR UPDATE")).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(2);
        var failure = new SQLException("fixture", "40001");
        when(statement.execute()).thenThrow(failure);
        var measured = new SearchTelemetry();
        try (Connection connection = SearchDatabaseTelemetry.connection(target, measured);
                PreparedStatement observed = connection.prepareStatement("SELECT id FROM job FOR UPDATE")) {
            observed.setObject(1, null);
            assertEquals(2, observed.executeUpdate());
            assertSame(failure, assertThrows(SQLException.class, observed::execute));
            assertTrue(connection.isWrapperFor(Connection.class) == target.isWrapperFor(Connection.class));
        }
        verify(statement).setObject(1, null); verify(statement).close(); verify(target).close();
        var result = measured.snapshot();
        assertEquals(2, result.databaseExecutions());
        assertEquals(result.databaseNanos(), result.lockStatementNanos());
        assertTrue(result.databaseNanos() >= 0);
    }
}
