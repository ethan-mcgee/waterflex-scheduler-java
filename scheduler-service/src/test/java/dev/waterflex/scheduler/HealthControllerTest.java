package dev.waterflex.scheduler;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Readiness reports each dependency and answers 503 when any is down or slow; liveness never reads a dependency. */
class HealthControllerTest {
    private static final ReplicaRole ROLE = new ReplicaRole(ReplicaRole.Role.DAILY);

    private static Map<String, HealthController.Check> checks(HealthController.Check routing) {
        Map<String, HealthController.Check> checks = new LinkedHashMap<>();
        checks.put("database", () -> null);
        checks.put("routing:metro-omaha", routing);
        return checks;
    }

    @Test void everyComponentUpIsReadyAndShowsTheRoutingIdentity() {
        var reply = new HealthController(checks(() -> "omaha-2026-10-13"), ROLE).health();
        assertEquals(200, reply.getStatusCode().value());
        var body = Required.value(reply.getBody());
        assertEquals("ok", body.status());
        assertEquals("daily", body.role(), "the replica names its role");
        assertEquals(new HealthController.Component("up", "omaha-2026-10-13"), body.components().get("routing:metro-omaha"));
        assertEquals(new HealthController.Component("up", null), body.components().get("database"));
    }

    @Test void aFailedComponentIsDegradedWithoutLeakingItsMessage() {
        var reply = new HealthController(checks(() -> { throw new RoadClient.RoadUnavailable("http://secret-host:8001 refused"); }), ROLE).health();
        assertEquals(503, reply.getStatusCode().value());
        var body = Required.value(reply.getBody());
        assertEquals("degraded", body.status());
        var routing = Required.value(body.components().get("routing:metro-omaha"));
        assertEquals("down", routing.status());
        assertFalse(String.valueOf(routing.detail()).contains("secret-host"), "no URL reaches the health body");
        assertEquals("up", Required.value(body.components().get("database")).status(), "the other components are still reported");
    }

    @Test void aSlowComponentIsDownAfterOneSecond() {
        long started = System.nanoTime();
        var reply = new HealthController(checks(() -> { Thread.sleep(5_000); return "late"; }), ROLE).health();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertEquals(503, reply.getStatusCode().value());
        assertEquals("down", Required.value(Required.value(reply.getBody()).components().get("routing:metro-omaha")).status());
        assertTrue(elapsedMs < 2_500, "readiness answers within its check timeout, took " + elapsedMs + " ms");
    }

    @Test void livenessReadsNothing() {
        var health = new HealthController(checks(() -> { throw new AssertionError("liveness must not check routing"); }), ROLE);
        assertEquals(Map.of("status", "ok"), health.live());
    }
}
