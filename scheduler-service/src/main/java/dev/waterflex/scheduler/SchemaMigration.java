package dev.waterflex.scheduler;

import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.ValidateResult;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The scheduler's own schema, versioned in {@code db/migration} and applied with Flyway. Only the schema owner migrates
 * ({@code --command=migrate}); the serving scheduler validates with its least-privilege login and refuses work against
 * a schema that is behind, ahead or altered.
 */
public final class SchemaMigration {
    static final String LOCATION = "classpath:db/migration";
    /** A Postgres role name the login statements can quote safely. */
    static final Pattern LOGIN = Required.value(Pattern.compile("[a-z_][a-z0-9_]{0,62}"));
    /** Characters that need no quoting inside a SQL string literal. */
    static final Pattern PASSWORD = Required.value(Pattern.compile("[A-Za-z0-9._~-]{16,128}"));

    /** Thrown when the database's schema is not the one this build expects. */
    public static final class SchemaNotCurrent extends RuntimeException {
        private static final long serialVersionUID = 1L;
        SchemaNotCurrent(String message) { super(message); }
    }

    private SchemaMigration() { }

    private static Flyway flyway(DataSource source) {
        return Required.value(Flyway.configure().dataSource(source).locations(LOCATION).failOnMissingLocations(true).load());
    }

    /** Applies every pending migration and returns how many ran. The connection must be the schema owner's. */
    public static int migrate(DataSource owner) {
        return Required.value(flyway(owner).migrate()).migrationsExecuted;
    }

    /** Refuses a schema that differs from this build's migrations: pending, unknown, failed or changed after it ran. */
    public static void validate(DataSource source) {
        ValidateResult result = Required.value(flyway(source).validateWithResult());
        if (!result.validationSuccessful)
            throw new SchemaNotCurrent("The scheduler database schema does not match this build; run --command=migrate as the schema owner ("
                    + (result.errorDetails == null ? "no detail" : result.errorDetails.errorMessage) + ")");
    }

    /**
     * Creates the login the scheduler serves with, a member of scheduler_app, or sets its password when it exists. It
     * refuses the migrating user and any role with powers beyond a login, since scheduler_app would not confine it.
     */
    public static void ensureLogin(JdbcTemplate owner, String login, String password) {
        if (!LOGIN.matcher(login).matches())
            throw new IllegalArgumentException("SCHEDULER_DB_LOGIN must be a lowercase Postgres role name (letters, digits, underscore)");
        if (!PASSWORD.matcher(password).matches())
            throw new IllegalArgumentException("SCHEDULER_DB_PASSWORD must be 16 to 128 characters of letters, digits, '.', '_', '~' or '-'");
        for (String statement : List.of(
                """
                DO $$
                BEGIN
                  IF '%1$s' = current_user THEN
                    RAISE EXCEPTION 'The scheduler login must not be the migrating user %%', current_user;
                  END IF;
                  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%1$s' AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb)) THEN
                    RAISE EXCEPTION 'Role %1$s has privileges beyond a login; the scheduler needs a plain login';
                  END IF;
                  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%1$s') THEN
                    ALTER ROLE "%1$s" LOGIN INHERIT PASSWORD '%2$s';
                  ELSE
                    CREATE ROLE "%1$s" LOGIN INHERIT NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '%2$s';
                  END IF;
                END $$""".formatted(login, password),
                "GRANT scheduler_app TO \"" + login + "\""))
            owner.execute(Required.value(statement));
    }
}
