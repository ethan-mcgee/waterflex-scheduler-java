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
            var origin = new RoadClient.Point(0, 0); var destination = new RoadClient.Point(1, 1);
            var matrix = callers.<Map<String, RoadClient.Leg>>submit(() -> roads.matrix(Required.value(Map.<String, RoadClient.Point>of("a", origin, "b", destination)), "test"));
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
            List<RoadClient.Pair> pairs = Required.value(List.<RoadClient.Pair>of(new RoadClient.Pair("out", new RoadClient.Point(0, 0), new RoadClient.Point(1, 1)),
                    new RoadClient.Pair("back", new RoadClient.Point(1, 1), new RoadClient.Point(0, 0))));
            assertThrows(RoadClient.RoadUnavailable.class, () -> roads.sparse(pairs, "test"));
            assertEquals(Map.of("out", new RoadClient.Leg(60, 42)), roads.sparse(pairs, "test"));
            assertEquals(Map.of("out", new RoadClient.Leg(60, 42)), roads.sparse(pairs, "test"));
            assertEquals(2, requests.get());
            assertThrows(RoadClient.RoadUnavailable.class, () -> roads.sparse(pairs, "changed"));
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
            Map<String, RoadClient.Point> points = Required.value(Map.<String, RoadClient.Point>of("p", new RoadClient.Point(0, 0)));
            assertThrows(RoadClient.RoadUnavailable.class, () -> roads.matrix(points));
            assertTrue(roads.matrix(points).isEmpty());
            assertTrue(roads.matrix(points).isEmpty());
            assertEquals(2, requests.get(), "An outage must not be cached as unreachable");
        } finally { server.stop(0); }
    }
}
