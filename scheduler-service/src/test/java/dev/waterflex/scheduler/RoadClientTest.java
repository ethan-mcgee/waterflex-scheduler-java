package dev.waterflex.scheduler;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RoadClientTest {
    @Test void sparseCallerSharesMatrixWorkAndKeepsItsOwnDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        server.createContext("/internal/legs", exchange -> {
            requests.incrementAndGet();
            var body = json.readTree(exchange.getRequestBody());
            entered.countDown();
            try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            var result = json.createObjectNode().put("routingIdentity", "test");
            var pairs = result.putArray("pairs");
            for (var pair : body.path("pairs")) pairs.addObject().put("id", pair.path("id").asText()).putObject("leg")
                    .put("routable", true).put("seconds", 60).put("meters", 42);
            byte[] bytes = json.writeValueAsBytes(result);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        RoadClient roads = new RoadClient(mock(JdbcTemplate.class), "http://127.0.0.1:" + server.getAddress().getPort(), 10, 10, mock(org.springframework.transaction.PlatformTransactionManager.class));
        try (var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var origin = new RoadPoint(0, 0); var destination = new RoadPoint(1, 1);
            var matrix = callers.<Map<String, RoadClient.Leg>>submit(() -> roads.matrix(Required.value(Map.<String, RoadPoint>of("a", origin, "b", destination)), "test"));
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                var pairs = Required.value(List.<RoadClient.Pair>of(new RoadClient.Pair("selected", origin, destination)));
                var deadline = new SearchDeadline(Required.value(java.time.Duration.ofMillis(1100)));
                assertThrows(SearchDeadline.Expired.class, () -> deadline.within(() -> roads.sparse(pairs, "test")));
                assertFalse(matrix.isDone());
                release.countDown();
                assertEquals(new RoadClient.Leg(60, 42), Required.value(matrix.get(2, java.util.concurrent.TimeUnit.SECONDS)).get("a>b"));
                assertEquals(Map.of("selected", new RoadClient.Leg(60, 42)), roads.sparse(pairs, "test"));
                assertEquals(1, requests.get());
                assertEquals(1, roads.httpMeasurements().legRequests(), "Coalesced callers count one actual provider request");
                assertEquals(4, roads.httpMeasurements().requestedPairs());
            } finally { release.countDown(); }
        } finally { roads.closeRoutingWork(); server.stop(0); }
    }

    @Test void configuredRoutingTokenIsSentOnComputationAndOmittedWhenAbsent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var seen = new java.util.concurrent.LinkedBlockingQueue<String>();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        server.createContext("/internal/legs", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            seen.add(header == null ? "<none>" : header);
            var body = json.readTree(exchange.getRequestBody());
            var result = json.createObjectNode().put("routingIdentity", "test");
            var pairs = result.putArray("pairs");
            for (var pair : body.path("pairs")) pairs.addObject().put("id", pair.path("id").asText()).putObject("leg")
                    .put("routable", true).put("seconds", 60).put("meters", 42);
            byte[] bytes = json.writeValueAsBytes(result);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        String token = "routing-fixture-token-at-least-32-characters";
        var transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var secured = new RoadClient(mock(JdbcTemplate.class), url, token, 10, 10, transactions);
        var open = new RoadClient(mock(JdbcTemplate.class), url, 10, 10, transactions);
        try {
            var pairs = Required.value(List.<RoadClient.Pair>of(new RoadClient.Pair("p", new RoadPoint(0, 0), new RoadPoint(1, 1))));
            assertEquals(Map.of("p", new RoadClient.Leg(60, 42)), secured.sparse(pairs, "test"));
            assertEquals("Bearer " + token, seen.poll(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(Map.of("p", new RoadClient.Leg(60, 42)), open.sparse(pairs, "test"));
            assertEquals("<none>", seen.poll(2, java.util.concurrent.TimeUnit.SECONDS));
            for (String invalid : List.of("short", "routing token with spaces that is long enough ok"))
                assertThrows(IllegalArgumentException.class, () -> new RoadClient(mock(JdbcTemplate.class), url, Required.value(invalid), 10, 10, transactions));
        } finally { secured.closeRoutingWork(); open.closeRoutingWork(); server.stop(0); }
    }

    @Test void slowRoutingConsumesExplorationBudgetWithoutCachingAFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/health", exchange -> {
            try { release.await(2, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            RoadClient roads = new RoadClient(mock(JdbcTemplate.class), "http://127.0.0.1:" + server.getAddress().getPort(), 10, 10, mock(org.springframework.transaction.PlatformTransactionManager.class));
            SearchDeadline deadline = new SearchDeadline(Required.value(java.time.Duration.ofMillis(1200)));
            assertThrows(SearchDeadline.Expired.class, () -> deadline.within(roads::activeIdentity));
            assertTrue(deadline.remainingNanos() > 0, "The final second remains available for validation and persistence");
            assertEquals("", roads.currentVersion());
            assertNull(SearchDeadline.current());
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void sparsePairsKeepDirectionAndRejectMalformedResultsWithoutCachingThem() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/internal/legs", exchange -> {
            int attempt = requests.incrementAndGet();
            String leg = attempt == 1 ? "{\"routable\":true,\"seconds\":null,\"meters\":42}" : "{\"routable\":true,\"seconds\":60,\"meters\":42}";
            byte[] bytes = ("{\"routingIdentity\":\"test\",\"pairs\":[{\"id\":\"0.00000,0.00000>1.00000,1.00000\",\"leg\":" + leg
                    + "},{\"id\":\"1.00000,1.00000>0.00000,0.00000\",\"leg\":{\"routable\":false,\"seconds\":null,\"meters\":null}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            RoadClient roads = new RoadClient(mock(JdbcTemplate.class), "http://127.0.0.1:" + server.getAddress().getPort(), 10, 10, mock(org.springframework.transaction.PlatformTransactionManager.class));
            List<RoadClient.Pair> pairs = Required.value(List.<RoadClient.Pair>of(new RoadClient.Pair("out", new RoadPoint(0, 0), new RoadPoint(1, 1)),
                    new RoadClient.Pair("back", new RoadPoint(1, 1), new RoadPoint(0, 0))));
            assertThrows(RoadClient.RoadUnavailable.class, () -> roads.sparse(pairs, "test"));
            assertEquals(Map.of("out", new RoadClient.Leg(60, 42)), roads.sparse(pairs, "test"));
            assertEquals(Map.of("out", new RoadClient.Leg(60, 42)), roads.sparse(pairs, "test"));
            assertEquals(2, requests.get());
            assertThrows(RoadClient.RoadUnavailable.class, () -> roads.sparse(pairs, "changed"));
        } finally { server.stop(0); }
    }
    @Test void theRoutingIdentityIsAskedOncePerTtlAndAgainAfterTheServiceRefusesIt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger healthRequests = new AtomicInteger();
        AtomicInteger legStatus = new AtomicInteger(409);
        server.createContext("/health", exchange -> {
            healthRequests.incrementAndGet();
            byte[] bytes = "{\"ready\":true,\"routingIdentity\":\"test\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/internal/legs", exchange -> {
            byte[] bytes = "{\"routingIdentity\":\"test\",\"pairs\":[{\"id\":\"0.00000,0.00000>1.00000,1.00000\",\"leg\":{\"routable\":true,\"seconds\":60,\"meters\":42}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(legStatus.get(), bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            RoadClient roads = new RoadClient(mock(JdbcTemplate.class), url, "", 10, 10, transactions, 60_000, 2, 16);
            assertEquals("test", roads.activeIdentity());
            assertEquals("test", roads.activeIdentity());
            assertEquals(1, healthRequests.get(), "within the TTL the identity is answered from memory");
            assertEquals(1, roads.httpMeasurements().identityRequests());
            List<RoadClient.Pair> pairs = Required.value(List.<RoadClient.Pair>of(new RoadClient.Pair("out", new RoadPoint(0, 0), new RoadPoint(1, 1))));
            var refused = assertThrows(RoadClient.RoadUnavailable.class, () -> roads.sparse(pairs, "test"));
            assertTrue(refused.getMessage().contains("identity changed"), refused.getMessage());
            assertEquals("test", roads.activeIdentity());
            assertEquals(2, healthRequests.get(), "a 409 from the service drops the remembered identity");
            legStatus.set(200);
            assertEquals(Map.of("out", new RoadClient.Leg(60, 42)), roads.sparse(pairs, "test"));
            assertEquals(2, healthRequests.get());

            RoadClient uncached = new RoadClient(mock(JdbcTemplate.class), url, "", 10, 10, transactions, 0, 2, 16);
            int before = healthRequests.get();
            uncached.activeIdentity(); uncached.activeIdentity();
            assertEquals(before + 2, healthRequests.get(), "a TTL of 0 asks every time");
            assertThrows(IllegalArgumentException.class, () -> new RoadClient(mock(JdbcTemplate.class), url, "", 10, 10, transactions, 60_001, 2, 16));
            assertThrows(IllegalArgumentException.class, () -> new RoadClient(mock(JdbcTemplate.class), url, "", 10, 10, transactions, 2000, 0, 16));
            roads.closeRoutingWork(); uncached.closeRoutingWork();
        } finally { server.stop(0); }
    }

    @Test void outagesAreRetriedAndExplicitUnreachableRoutesAreCached() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/health", exchange -> {
            byte[] bytes = "{\"ready\":true,\"routingIdentity\":\"test\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/internal/legs", exchange -> {
            int status = requests.incrementAndGet() == 1 ? 503 : 200;
            byte[] bytes = "{\"routingIdentity\":\"test\",\"pairs\":[{\"id\":\"0.00000,0.00000>0.00000,0.00000\",\"leg\":{\"routable\":false,\"seconds\":null,\"meters\":null}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            RoadClient roads = new RoadClient(mock(JdbcTemplate.class), "http://127.0.0.1:" + server.getAddress().getPort(), 10, 10, mock(org.springframework.transaction.PlatformTransactionManager.class));
            Map<String, RoadPoint> points = Required.value(Map.<String, RoadPoint>of("p", new RoadPoint(0, 0)));
            assertThrows(RoadClient.RoadUnavailable.class, () -> roads.matrix(points));
            assertTrue(roads.matrix(points).isEmpty());
            assertTrue(roads.matrix(points).isEmpty());
            assertEquals(2, requests.get(), "An outage must not be cached as unreachable");
        } finally { server.stop(0); }
    }

    /** A routing replica that answers its health and legs with {@code identity}, or 409 when it serves another map. */
    private static HttpServer replica(String identity, boolean otherMap, AtomicInteger legs) throws java.io.IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        server.createContext("/health", exchange -> {
            byte[] bytes = ("{\"ready\":true,\"routingIdentity\":\"" + (otherMap ? "other-map" : identity) + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/internal/legs", exchange -> {
            legs.incrementAndGet();
            var body = json.readTree(exchange.getRequestBody());
            if (otherMap) { exchange.sendResponseHeaders(409, -1); exchange.close(); return; }
            var result = json.createObjectNode().put("routingIdentity", identity);
            var pairs = result.putArray("pairs");
            for (var pair : body.path("pairs")) pairs.addObject().put("id", pair.path("id").asText()).putObject("leg")
                    .put("routable", true).put("seconds", 60).put("meters", 42);
            byte[] bytes = json.writeValueAsBytes(result);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        return server;
    }

    private static String base(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private static List<RoadClient.Pair> pair(int n) {
        return Required.value(List.of(new RoadClient.Pair("p" + n, new RoadPoint(0, 0), new RoadPoint(0.001 * n + 0.01, 1))));
    }

    @Test void severalReplicasOfAMetrosRoutingShareItsCallsAndOneThatIsDownOrOnAnotherMapIsSkipped() throws Exception {
        AtomicInteger first = new AtomicInteger(), second = new AtomicInteger(), stale = new AtomicInteger();
        HttpServer a = replica("test", false, first), b = replica("test", false, second), c = replica("test", true, stale);
        var transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
        RoadClient roads = new RoadClient(mock(JdbcTemplate.class), base(a) + "|" + base(b) + "/", "", 10, 10, transactions, 0, 2, 16);
        try {
            assertEquals(2, roads.endpoints());
            for (int n = 0; n < 6; n++) assertEquals(Map.of("p" + n, new RoadClient.Leg(60, 42)), roads.sparse(pair(n), "test"));
            assertEquals(6, first.get() + second.get());
            assertTrue(first.get() > 0 && second.get() > 0, "calls are spread over both replicas: " + first + " and " + second);

            b.stop(0);
            for (int n = 6; n < 10; n++) assertEquals(Map.of("p" + n, new RoadClient.Leg(60, 42)), roads.sparse(pair(n), "test"), "a replica that is down is skipped");
            assertEquals(1, roads.availableEndpoints(), "the stopped replica is parked");
            assertEquals("test", roads.activeIdentity());
        } finally { roads.closeRoutingWork(); a.stop(0); }

        AtomicInteger good = new AtomicInteger();
        HttpServer d = replica("test", false, good);
        RoadClient rolling = new RoadClient(mock(JdbcTemplate.class), base(c) + "|" + base(d), "", 10, 10, transactions, 0, 2, 16);
        try {
            for (int n = 0; n < 4; n++) assertEquals(Map.of("p" + n, new RoadClient.Leg(60, 42)), rolling.sparse(pair(n), "test"),
                    "a replica already on the next map refuses with 409 and the call is answered by the other");
            assertEquals(4, good.get());
            assertTrue(stale.get() <= 1, "the replica on another map is parked after its first refusal: " + stale);
        } finally { rolling.closeRoutingWork(); c.stop(0); d.stop(0); }

        HttpServer e = replica("test", true, new AtomicInteger());
        RoadClient alone = new RoadClient(mock(JdbcTemplate.class), base(e), "", 10, 10, transactions, 0, 2, 16);
        try {
            var refused = assertThrows(RoadClient.RoadUnavailable.class, () -> alone.sparse(pair(0), "test"));
            assertEquals("Routing identity changed", refused.getMessage(), "a single replica keeps its answer to a changed identity");
        } finally { alone.closeRoutingWork(); e.stop(0); }
    }

    @Test void aMetroMayListSeveralRoutingReplicas() {
        assertEquals(Map.of("omaha", "http://a:1|http://b:1", "lincoln", "http://c:1"),
                MetroRouting.parse("omaha=http://a:1|http://b:1/,lincoln=http://c:1"));
        assertThrows(IllegalArgumentException.class, () -> MetroRouting.parse("omaha=http://a:1|http://a:1"), "a replica listed twice");
        assertThrows(IllegalArgumentException.class, () -> MetroRouting.parse("omaha=http://a:1|ftp://b:1"), "every replica must be http or https");
        assertThrows(IllegalArgumentException.class, () -> MetroRouting.parse("omaha=http://a:1|"), "an empty replica");
    }
}
