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
            RoadClient roads = new RoadClient(mock(JdbcTemplate.class), "http://127.0.0.1:" + server.getAddress().getPort(), 10, 10);
            List<RoadClient.Pair> pairs = Required.value(List.of(new RoadClient.Pair("out", new RoadClient.Point(0, 0), new RoadClient.Point(1, 1)),
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
        server.createContext("/internal/matrix", exchange -> {
            int status = requests.incrementAndGet() == 1 ? 503 : 200;
            byte[] bytes = "{\"routingIdentity\":\"test\",\"legs\":[[{\"routable\":false,\"seconds\":null,\"meters\":null}]]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            RoadClient roads = new RoadClient(mock(JdbcTemplate.class), "http://127.0.0.1:" + server.getAddress().getPort(), 10, 10);
            Map<String, RoadClient.Point> points = Required.value(Map.<String, RoadClient.Point>of("p", new RoadClient.Point(0, 0)));
            assertThrows(RoadClient.RoadUnavailable.class, () -> roads.matrix(points));
            assertTrue(roads.matrix(points).isEmpty());
            assertTrue(roads.matrix(points).isEmpty());
            assertEquals(2, requests.get(), "An outage must not be cached as unreachable");
        } finally { server.stop(0); }
    }
}
