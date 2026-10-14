package dev.waterflex.scheduler;

import dev.waterflex.scheduler.api.AddressLocation;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Readiness and liveness. {@code /health} checks every dependency a calculation needs, each within one second and all
 * at once: the database and its schema version, each configured metro's routing service, the remote solver pool when calculations are
 * remote, and the address lookup when it is on. It answers 200 when every one is up and 503 otherwise. The body names
 * components and routing identities only, never URLs or credentials, and this replica's {@code role} (booking, daily
 * or all) so a load balancer's view can be checked. {@code /health/live} reads nothing.
 */
@RestController
public class HealthController {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(HealthController.class));
    static final Duration CHECK_TIMEOUT = Required.value(Duration.ofSeconds(1));
    private static final ExecutorService CHECKS = Required.value(Executors.newVirtualThreadPerTaskExecutor());

    /** One dependency check: returns a detail worth showing (a routing identity) or null, and throws when the dependency is down. */
    @FunctionalInterface
    interface Check { @Nullable String run() throws Exception; }

    /** A component's state; {@code detail} is a routing identity when up, or a short reason when down. */
    public record Component(String status, @Nullable String detail) { }
    public record Health(String status, String role, Map<String, Component> components) { }

    private final Map<String, Check> checks;
    private final String role;

    @Autowired
    public HealthController(JdbcTemplate jdbc, SchemaCheck schema, MetroRouting routing, CalculationTransport transport, AddressLocation geocoding,
                            ReplicaRole role) {
        this(checks(jdbc, schema, routing, transport, geocoding), role);
    }

    HealthController(Map<String, Check> checks, ReplicaRole role) { this.checks = Required.value(Map.copyOf(checks)); this.role = role.label(); }

    private static Map<String, Check> checks(JdbcTemplate jdbc, SchemaCheck schema, MetroRouting routing, CalculationTransport transport, AddressLocation geocoding) {
        Map<String, Check> checks = new LinkedHashMap<>();
        JdbcTemplate bounded = new JdbcTemplate(Required.value(jdbc.getDataSource(), "scheduler data source"));
        bounded.setQueryTimeout((int) CHECK_TIMEOUT.toSeconds());
        checks.put("database", () -> {
            Integer one = bounded.queryForObject("SELECT 1", Integer.class);
            if (one == null || one != 1) throw new IllegalStateException("Unexpected database answer");
            return null;
        });
        // Down until the owner has migrated the database to this build's schema (--command=migrate).
        checks.put("schema", () -> { schema.verify(); return null; });
        for (String listed : routing.metros()) {
            String metro = Required.value(listed);
            checks.put("routing:" + metro, () -> routing.client(metro).activeIdentity());
        }
        if (transport.remote()) checks.put("calculation", () -> { transport.checkRemote(CHECK_TIMEOUT); return null; });
        if (geocoding.looksUpAddresses()) checks.put("geocoding", () -> { geocoding.checkLookup(CHECK_TIMEOUT); return null; });
        return checks;
    }

    @GetMapping("/health")
    public ResponseEntity<Health> health() {
        Map<String, Future<@Nullable String>> running = new LinkedHashMap<>();
        checks.forEach((name, check) -> running.put(name, CHECKS.submit(check::run)));
        Map<String, Component> components = new LinkedHashMap<>();
        boolean ready = true;
        long deadline = System.nanoTime() + CHECK_TIMEOUT.toNanos();
        for (var entry : running.entrySet()) {
            String name = Required.value(entry.getKey());
            Future<@Nullable String> result = Required.value(entry.getValue());
            Component component;
            try {
                component = new Component("up", result.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (TimeoutException slow) {
                result.cancel(true);
                component = new Component("down", "no answer within " + CHECK_TIMEOUT.toMillis() + " ms");
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                LOG.warn("Readiness check {} failed", name, cause);
                component = new Component("down", cause == null ? "failed" : cause.getClass().getSimpleName());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                result.cancel(true);
                component = new Component("down", "interrupted");
            }
            ready &= component.status().equals("up");
            components.put(name, component);
        }
        Health health = new Health(ready ? "ok" : "degraded", role, components);
        return Required.value(ResponseEntity.status(ready ? 200 : 503).body(health));
    }

    /** Liveness: the process answers. It reads nothing, so a dependency outage never restarts the scheduler. */
    @GetMapping("/health/live")
    public Map<String, String> live() { return Required.value(Map.of("status", "ok")); }
}
