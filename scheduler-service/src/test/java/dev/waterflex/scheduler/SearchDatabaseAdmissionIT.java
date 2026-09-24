package dev.waterflex.scheduler;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit real-pool gate, alongside BookingSnapshotDatabaseIT, against waterflex_test only. */
class SearchDatabaseAdmissionIT {
    @Test void exhaustedPoolHasBoundedWaitAndLateAdmissionDoesNotBorrow() throws Exception {
        String url = Required.value(System.getenv("JDBC_DATABASE_URL"), "isolated integration database URL");
        assertEquals("/waterflex_test", URI.create(url.substring("jdbc:".length())).getPath());
        var configuration = new HikariConfig();
        configuration.setJdbcUrl(url); configuration.setUsername("waterflex"); configuration.setPassword("waterflex");
        configuration.setMaximumPoolSize(1); configuration.setMinimumIdle(1);
        configuration.setConnectionTimeout(250); configuration.setValidationTimeout(250);
        try (var pool = new HikariDataSource(configuration); Connection occupied = pool.getConnection()) {
            assertTrue(occupied.isValid(1));
            var observed = (DataSource) new SearchDatabaseTelemetry().postProcessAfterInitialization(pool, "fixturePool");
            var deadline = new SearchDeadline(Required.value(Duration.ofSeconds(2)));
            long started = System.nanoTime();
            deadline.within(() -> {
                SearchDeadline.beginCommit();
                assertThrows(SQLTransientConnectionException.class, observed::getConnection);
                return true;
            });
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
            assertTrue(elapsedMillis >= 200 && elapsedMillis < 1500, "Pool timeout stays inside the request budget: " + elapsedMillis);
            var late = new SearchDeadline(Required.value(Duration.ofMillis(400)));
            late.within(() -> {
                SearchDeadline.beginCommit();
                assertThrows(SearchAdmission.Busy.class, observed::getConnection);
                return true;
            });
            assertEquals(1, Required.value(pool.getHikariPoolMXBean()).getActiveConnections());
            assertEquals(0, Required.value(pool.getHikariPoolMXBean()).getThreadsAwaitingConnection());
        }
    }
}
