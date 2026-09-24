package dev.waterflex.scheduler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchDatabaseTelemetryTest {
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
