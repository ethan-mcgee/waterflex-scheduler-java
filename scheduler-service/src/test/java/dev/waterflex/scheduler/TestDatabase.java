package dev.waterflex.scheduler;

import java.net.URI;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The scheduler's own throwaway database for integration tests: JDBC_DATABASE_URL must name a database called
 * {@code scheduler_test}, DATABASE_USER and DATABASE_PASSWORD its owner. The first use migrates it with this build's
 * Flyway migrations, as {@code --command=migrate} would; nothing else creates the schema. Without JDBC_DATABASE_URL a
 * local run reports these tests as skipped, not failed. Under CI (the CI variable set) a missing URL still fails them.
 */
public final class TestDatabase {
    private static boolean migrated;

    private TestDatabase() { }

    public static String url() {
        String configured = System.getenv("JDBC_DATABASE_URL");
        // Only a local run may skip: under CI (GitHub Actions sets CI) a missing database fails, so the gate never goes quiet.
        Assumptions.assumeTrue(configured != null || System.getenv("CI") != null,
                "JDBC_DATABASE_URL is not set: no scheduler_test database to run database tests against");
        String url = Required.value(configured, "JDBC_DATABASE_URL of the throwaway scheduler_test database");
        if (!"/scheduler_test".equals(URI.create(url.substring("jdbc:".length())).getPath()))
            throw new IllegalStateException("Integration tests write freely; JDBC_DATABASE_URL must name the scheduler_test database");
        return url;
    }

    public static String ownerUser() { return Required.value(System.getenv("DATABASE_USER"), "DATABASE_USER, the scheduler_test owner"); }

    public static String ownerPassword() { return Required.value(System.getenv("DATABASE_PASSWORD"), "DATABASE_PASSWORD of the scheduler_test owner"); }

    /** A connection as the schema owner, to a database migrated to this build. */
    public static synchronized DriverManagerDataSource owner() {
        var source = new DriverManagerDataSource(url(), ownerUser(), ownerPassword());
        if (!migrated) {
            SchemaMigration.migrate(source);
            migrated = true;
        }
        return source;
    }
}
