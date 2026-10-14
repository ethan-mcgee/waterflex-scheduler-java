package dev.waterflex.scheduler;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The scheduler's schema comes only from its Flyway migrations: a migrated database validates, an unmigrated or altered
 * one is refused, and the serving login is created with exactly the scheduler_app membership.
 */
class SchemaMigrationDatabaseIT {
    private final String suffix = Required.value(UUID.randomUUID().toString().substring(0, 8));
    private final JdbcTemplate owner = new JdbcTemplate(TestDatabase.owner());

    /** A connection whose current schema is a new, empty schema of the test database. */
    private DriverManagerDataSource scratch(String schema) {
        owner.execute("CREATE SCHEMA " + schema);
        String url = TestDatabase.url();
        return new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, TestDatabase.ownerUser(), TestDatabase.ownerPassword());
    }

    @Test void aMigratedDatabaseValidatesAndAMigrationRerunsNothing() {
        var source = TestDatabase.owner();
        SchemaMigration.validate(source);
        assertEquals(0, SchemaMigration.migrate(source), "every migration already ran");
    }

    @Test void anUnmigratedOrAlteredSchemaIsRefused() {
        String empty = "it_empty_" + suffix, altered = "it_altered_" + suffix;
        try {
            var unmigrated = scratch(empty);
            assertThrows(SchemaMigration.SchemaNotCurrent.class, () -> SchemaMigration.validate(unmigrated), "a pending migration is refused");

            var changed = scratch(altered);
            assertEquals(2, SchemaMigration.migrate(changed), "every migration runs on an empty schema");
            SchemaMigration.validate(changed);
            owner.update("UPDATE " + altered + ".flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'");
            var refused = assertThrows(SchemaMigration.SchemaNotCurrent.class, () -> SchemaMigration.validate(changed), "a changed migration is refused");
            assertTrue(String.valueOf(refused.getMessage()).contains("--command=migrate"), "the message names the fix");
        } finally {
            owner.execute("DROP SCHEMA IF EXISTS " + empty + " CASCADE");
            owner.execute("DROP SCHEMA IF EXISTS " + altered + " CASCADE");
        }
    }

    @Test void theServingLoginIsAPlainMemberOfSchedulerAppAndItsPasswordCanRotate() {
        String login = "it_login_" + suffix, power = "it_power_" + suffix;
        try {
            SchemaMigration.ensureLogin(owner, login, "first-password-0123");
            SchemaMigration.ensureLogin(owner, login, "second-password-0123");
            assertEquals(1, Required.value(owner.queryForObject("""
                    SELECT count(*)::int FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member JOIN pg_roles g ON g.oid = m.roleid
                    WHERE r.rolname = ? AND g.rolname = 'scheduler_app'""", Integer.class, login)));
            assertEquals(Boolean.TRUE, owner.queryForObject("SELECT rolcanlogin AND NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname = ?", Boolean.class, login));
            var app = new JdbcTemplate(new DriverManagerDataSource(TestDatabase.url(), login, "second-password-0123"));
            SchemaMigration.validate(Required.value(app.getDataSource()));
            assertEquals(1, Required.value(app.queryForObject("SELECT 1", Integer.class)), "the rotated password logs in");

            assertThrows(RuntimeException.class, () -> SchemaMigration.ensureLogin(owner, TestDatabase.ownerUser(), "any-password-01234"), "never the migrating user");
            owner.execute("CREATE ROLE " + power + " LOGIN CREATEROLE");
            assertThrows(RuntimeException.class, () -> SchemaMigration.ensureLogin(owner, power, "any-password-01234"), "never a role with powers");
            assertThrows(IllegalArgumentException.class, () -> SchemaMigration.ensureLogin(owner, "Bad\"Name", "any-password-01234"));
            assertThrows(IllegalArgumentException.class, () -> SchemaMigration.ensureLogin(owner, login, "it's-not-quotable"));
        } finally {
            for (String role : new String[] {login, power}) {
                owner.execute("DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + role + "') THEN EXECUTE 'DROP OWNED BY " + role + "'; EXECUTE 'DROP ROLE " + role + "'; END IF; END $$");
            }
        }
    }
}
