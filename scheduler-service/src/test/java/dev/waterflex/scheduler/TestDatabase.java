package dev.waterflex.scheduler;

import java.net.URI;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The scheduler's own throwaway database for integration tests: JDBC_DATABASE_URL must name a database called
 * {@code scheduler_test}, DATABASE_USER and DATABASE_PASSWORD its owner. The first use migrates it with this build's
 * Flyway migrations, as {@code --command=migrate} would; nothing else creates the schema.
 */
public final class TestDatabase {
    private static boolean migrated;

    private TestDatabase() { }

    public static String url() {
        String url = Required.value(System.getenv("JDBC_DATABASE_URL"), "JDBC_DATABASE_URL of the throwaway scheduler_test database");
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
