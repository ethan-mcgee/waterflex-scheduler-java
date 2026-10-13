package dev.waterflex.scheduler.admin;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SchemaMigration;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Operator commands, run instead of the server: {@code java -jar scheduler-service.jar --command=<name> [args]}. They
 * connect as the schema owner with JDBC_DATABASE_URL, DATABASE_USER and DATABASE_PASSWORD, which must all be set (no
 * local defaults), and exit 0 on success, 1 on failure and 2 on a usage error. Spring does not start.
 */
public final class SchedulerCommands {
    public static final String PREFIX = "--command=";
    static final String USAGE = """
            Usage: java -jar scheduler-service.jar --command=<command> [arguments]
              migrate                                   apply schema migrations, then create or update the login
                                                        SCHEDULER_DB_LOGIN with SCHEDULER_DB_PASSWORD (a scheduler_app member)
              issue-token <tenantId> <name> <label>     issue a token, creating the tenant if needed; printed once
              revoke-token <tokenId>                    revoke a token
              list-tenants                              list tenants with their active token counts
              disable-tenant <tenantId>                 refuse every token of the tenant (401)
              enable-tenant <tenantId>                  accept the tenant's active tokens again
              bootstrap-local-tokens <clients.json> <dir>
                                                        local Compose only (LOCAL_TOKEN_BOOTSTRAP=true): a working token
                                                        file per portal client
            Every command connects as the schema owner: JDBC_DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD.""";

    private SchedulerCommands() { }

    public static boolean isCommand(String[] args) { return args.length > 0 && Required.value(args[0]).startsWith(PREFIX); }

    private static String env(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static DriverManagerDataSource owner(Map<String, String> env) {
        return new DriverManagerDataSource(env(env, "JDBC_DATABASE_URL"), env(env, "DATABASE_USER"), env(env, "DATABASE_PASSWORD"));
    }

    /** Runs the command named by {@code args[0]} and returns the process exit status. */
    public static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        String command = Required.value(Required.value(args[0]).substring(PREFIX.length()));
        List<String> rest = Required.value(Arrays.asList(Arrays.copyOfRange(args, 1, args.length)));
        try {
            Integer status = dispatch(command, rest, env, out);
            if (status != null) return status;
            err.println(USAGE);
            return 2;
        } catch (IllegalArgumentException | IllegalStateException | DataAccessException | IOException | SchemaMigration.SchemaNotCurrent failure) {
            err.println("Command " + command + " failed: " + failure.getMessage());
            return 1;
        } catch (RuntimeException failure) {
            err.println("Command " + command + " failed: " + failure);
            return 1;
        }
    }

    private static String arg(List<String> args, int index) { return Required.value(args.get(index)); }

    /** The exit status, or null for an unknown command or the wrong number of arguments. */
    private static @Nullable Integer dispatch(String command, List<String> args, Map<String, String> env, PrintStream out) throws IOException {
        switch (command) {
            case "migrate" -> {
                if (!args.isEmpty()) return null;
                String login = env(env, "SCHEDULER_DB_LOGIN"), password = env(env, "SCHEDULER_DB_PASSWORD");
                var owner = owner(env);
                int applied = SchemaMigration.migrate(owner);
                SchemaMigration.ensureLogin(new JdbcTemplate(owner), login, password);
                out.println("Applied " + applied + " migration(s); login " + login + " is a member of scheduler_app");
                return 0;
            }
            case "issue-token" -> {
                if (args.size() != 3) return null;
                var issued = new TenantAdministration(owner(env)).issue(arg(args, 0), arg(args, 1), arg(args, 2), null);
                out.println("Token issued for tenant " + arg(args, 0) + ". Store it now; it cannot be shown again.");
                out.println("id: " + issued.id());
                out.println("token: " + issued.token());
                return 0;
            }
            case "revoke-token" -> {
                if (args.size() != 1) return null;
                if (!new TenantAdministration(owner(env)).revoke(arg(args, 0)))
                    throw new IllegalArgumentException("No active token " + arg(args, 0));
                out.println("Token " + arg(args, 0) + " revoked");
                return 0;
            }
            case "list-tenants" -> {
                if (!args.isEmpty()) return null;
                for (var tenant : new TenantAdministration(owner(env)).tenants())
                    out.println(tenant.id() + "\t" + tenant.name() + "\t" + (tenant.disabled() ? "disabled" : "enabled") + "\t" + tenant.activeTokens() + " active token(s)");
                return 0;
            }
            case "disable-tenant", "enable-tenant" -> {
                if (args.size() != 1) return null;
                boolean disable = command.equals("disable-tenant");
                if (!new TenantAdministration(owner(env)).setDisabled(arg(args, 0), disable))
                    throw new IllegalArgumentException("No tenant " + arg(args, 0));
                out.println("Tenant " + arg(args, 0) + (disable ? " disabled" : " enabled"));
                return 0;
            }
            case "bootstrap-local-tokens" -> {
                if (args.size() != 2) return null;
                var result = LocalTokenBootstrap.run(env, Required.value(Path.of(arg(args, 0))), Required.value(Path.of(arg(args, 1))),
                        new TenantAdministration(owner(env)));
                out.println("Local API tokens: issued for " + result.issued() + ", kept for " + result.kept());
                return 0;
            }
            default -> { return null; }
        }
    }
}
