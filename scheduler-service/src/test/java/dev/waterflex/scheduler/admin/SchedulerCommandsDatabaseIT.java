package dev.waterflex.scheduler.admin;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.TenantTokens;
import dev.waterflex.scheduler.TestDatabase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** The operator commands against the scheduler's own database: tokens issued by the CLI authenticate, and only then. */
class SchedulerCommandsDatabaseIT {
    private final String suffix = Required.value(UUID.randomUUID().toString().substring(0, 8));
    private final Map<String, String> env = Map.of("JDBC_DATABASE_URL", TestDatabase.url(),
            "DATABASE_USER", TestDatabase.ownerUser(), "DATABASE_PASSWORD", TestDatabase.ownerPassword(), "LOCAL_TOKEN_BOOTSTRAP", "true");
    private final TenantTokens tokens = new TenantTokens(new JdbcTemplate(TestDatabase.owner()));

    private record Run(int status, String out, String err) { }

    private Run run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int status = SchedulerCommands.run(args, env, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(status, Required.value(out.toString(StandardCharsets.UTF_8)), Required.value(err.toString(StandardCharsets.UTF_8)));
    }

    private static String field(String output, String name) {
        Matcher match = Required.value(Pattern.compile("(?m)^" + name + ": (\\S+)$").matcher(output));
        assertTrue(match.find(), name + " in " + output);
        return Required.value(match.group(1));
    }

    @Test void anIssuedTokenAuthenticatesUntilRevokedOrTheTenantIsDisabled() {
        String tenant = "cli-it-" + suffix;
        Run issued = run("--command=issue-token", tenant, "CLI tenant", "first");
        assertEquals(0, issued.status(), issued.err());
        String id = field(issued.out(), "id"), token = field(issued.out(), "token");
        assertEquals(tenant, tokens.tenantFor(token));
        assertTrue(run("--command=list-tenants").out().contains(tenant + "\tCLI tenant\tenabled\t1 active token(s)"));

        assertEquals(0, run("--command=disable-tenant", tenant).status());
        assertNull(tokens.tenantFor(token), "a disabled tenant's token is refused");
        Run refused = run("--command=issue-token", tenant, "CLI tenant", "second");
        assertEquals(1, refused.status());
        assertTrue(refused.err().contains("is disabled"));
        assertEquals(0, run("--command=enable-tenant", tenant).status());
        assertEquals(tenant, tokens.tenantFor(token));

        assertEquals(0, run("--command=revoke-token", id).status());
        assertNull(tokens.tenantFor(token), "a revoked token is refused");
        assertEquals(1, run("--command=revoke-token", id).status(), "a token is revoked once");
        assertEquals(1, run("--command=disable-tenant", "no-such-" + suffix).status());
    }

    @Test void badArgumentsAreRefusedWithoutTouchingTheDatabase() {
        assertEquals(2, run("--command=issue-token", "only-one").status());
        assertEquals(2, run("--command=unknown").status());
        Run invalid = run("--command=issue-token", "Not_Valid", "Name", "label");
        assertEquals(1, invalid.status());
        assertTrue(invalid.err().contains("Tenant ID"));
        assertEquals(1, SchedulerCommands.run(new String[] {"--command=list-tenants"}, Required.value(Map.<String, String>of()), Required.value(System.out), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)),
                "missing connection settings are an error, never a default");
    }

    @Test void theLocalBootstrapKeepsWorkingTokensAndReissuesOnesThisSchedulerRefuses() throws IOException {
        Path dir = Files.createTempDirectory("wf-tokens-");
        String kept = "boot-kept-" + suffix, fresh = "boot-new-" + suffix, stale = "boot-stale-" + suffix;
        try {
            Path clients = Required.value(dir.resolve("clients.json"));
            Files.writeString(clients, "[{\"id\":\"%s\",\"name\":\"Kept\"},{\"id\":\"%s\",\"name\":\"New\"},{\"id\":\"%s\",\"name\":\"Stale\"}]".formatted(kept, fresh, stale));
            Path tokenDir = Required.value(dir.resolve("tokens"));
            Files.createDirectories(tokenDir);
            String keptToken = new TenantAdministration(TestDatabase.owner()).issue(kept, "Kept", LocalTokenBootstrap.LABEL, null).token();
            Files.writeString(tokenDir.resolve(kept), keptToken + "\n");
            Files.writeString(tokenDir.resolve(stale), "wfs_" + "s".repeat(43) + "\n");

            Run first = run("--command=bootstrap-local-tokens", clients.toString(), tokenDir.toString());
            assertEquals(0, first.status(), first.err());
            assertTrue(first.out().contains("issued for [" + fresh + ", " + stale + "], kept for [" + kept + "]"), first.out());
            assertEquals(keptToken, Files.readString(tokenDir.resolve(kept)).strip(), "a working token is never rotated");
            for (String client : new String[] {kept, fresh, stale})
                assertEquals(client, tokens.tenantFor(Required.value(Files.readString(tokenDir.resolve(client)).strip())), client + " authenticates");
            assertFalse(Files.exists(tokenDir.resolve(fresh + ".tmp")));

            Run again = run("--command=bootstrap-local-tokens", clients.toString(), tokenDir.toString());
            assertTrue(again.out().contains("issued for [], kept for [" + kept + ", " + fresh + ", " + stale + "]"), "a rerun issues nothing: " + again.out());

            Files.writeString(clients, "[{\"id\":\"../escape\",\"name\":\"Bad\"}]");
            assertEquals(1, run("--command=bootstrap-local-tokens", clients.toString(), tokenDir.toString()).status());
            assertEquals(1, SchedulerCommands.run(new String[] {"--command=bootstrap-local-tokens", clients.toString(), tokenDir.toString()},
                    Required.value(Map.of("JDBC_DATABASE_URL", TestDatabase.url(), "DATABASE_USER", TestDatabase.ownerUser(), "DATABASE_PASSWORD", TestDatabase.ownerPassword())),
                    Required.value(System.out), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)), "refused unless LOCAL_TOKEN_BOOTSTRAP=true");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
