package dev.waterflex.scheduler;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoadPrewarmingTest {
    @Test void disabledAndBusyPrewarmingPerformNoDatabaseOrNetworkWork() {
        var jdbc = mock(JdbcTemplate.class); var roads = mock(RoadClient.class);
        var transactions = mock(PlatformTransactionManager.class);
        var admission = new SearchAdmission(1, 16);
        new RoadPrewarming(jdbc, roads, admission, transactions, false).poll();
        try (var lease = admission.acquire(SearchAdmission.Kind.BOOKING, new SearchDeadline(Required.value(java.time.Duration.ofSeconds(1))))) {
            assertTrue(lease.queueMillis() >= 0);
            new RoadPrewarming(jdbc, roads, admission, transactions, true).poll();
        }
        verifyNoInteractions(jdbc, roads, transactions);
    }

    @Test void directedShortcutsAndBothSegmentEndpointsArePrewarmed() {
        var home = new RoadClient.Point(41, -96); var depot = new RoadClient.Point(42, -97);
        var a = new RoadClient.Point(41.1, -96.1); var b = new RoadClient.Point(41.2, -96.2); var c = new RoadClient.Point(41.3, -96.3);
        var pairs = RoadPrewarming.pairs(new RouteEndpoints(home, depot), Required.value(List.<RoadClient.Point>of(a, b, c)));
        assertEquals(12, pairs.size());
        var ids = pairs.stream().map(pair -> Required.value(pair).id()).collect(Collectors.toSet());
        assertEquals(pairs.size(), ids.size());
        assertTrue(pairs.contains(new RoadClient.Pair("0>2", a, c)));
        assertTrue(pairs.contains(new RoadClient.Pair("2>0", c, a)));
        assertTrue(pairs.contains(new RoadClient.Pair("departure>2", home, c)));
        assertTrue(pairs.contains(new RoadClient.Pair("0>return", a, depot)));
        assertTrue(RoadPrewarming.pairs(new RouteEndpoints(home, depot), Required.value(List.of())).isEmpty());
    }
}
