package dev.waterflex.scheduler;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoadPrewarmingTest {
    @Test void versionChangesAndBookingArrivalStopRemainingBatches() {
        var origin = new RoadPoint(41, -96);
        var pairs = new java.util.ArrayList<RoadClient.Pair>();
        for (int i = 0; i < 65; i++) pairs.add(new RoadClient.Pair("pair-" + i, origin, new RoadPoint(41.01 + i * .001, -96)));
        var firstBatch = Required.value(List.copyOf(pairs.subList(0, 32)));
        for (boolean versionChanged : List.of(true, false)) {
            var roads = mock(RoadClient.class);
            var version = new java.util.concurrent.atomic.AtomicInteger(7);
            var bookingWaiting = new java.util.concurrent.atomic.AtomicBoolean();
            when(roads.sparse(firstBatch, "roads")).thenAnswer(_ -> {
                if (versionChanged) version.incrementAndGet(); else bookingWaiting.set(true);
                return java.util.Map.of();
            });
            assertFalse(RoadPrewarming.warmBatches(pairs, "roads", roads, () -> version.get() == 7 && !bookingWaiting.get()));
            verify(roads).sparse(firstBatch, "roads");
            verifyNoMoreInteractions(roads);
        }
    }

    @Test void finalBatchVersionAndRoutingChangesCannotMarkPrewarmingComplete() {
        var point = new RoadPoint(41, -96);
        var pairs = Required.value(List.of(new RoadClient.Pair("one", point, point)));
        var roads = mock(RoadClient.class);
        var current = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(roads.sparse(pairs, "roads")).thenAnswer(_ -> { current.set(false); return java.util.Map.of(); });
        assertFalse(RoadPrewarming.warmBatches(pairs, "roads", roads, current::get));
        when(roads.sparse(pairs, "roads")).thenReturn(java.util.Map.of());
        current.set(true);
        when(roads.currentVersion()).thenReturn("replacement");
        assertFalse(RoadPrewarming.warmBatches(pairs, "roads", roads, current::get));
        when(roads.currentVersion()).thenReturn("roads");
        assertTrue(RoadPrewarming.warmBatches(pairs, "roads", roads, current::get));
    }

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
        var home = new RoadPoint(41, -96); var depot = new RoadPoint(42, -97);
        var a = new RoadPoint(41.1, -96.1); var b = new RoadPoint(41.2, -96.2); var c = new RoadPoint(41.3, -96.3);
        var pairs = RoadPrewarming.pairs(new RouteEndpoints(home, depot), Required.value(List.<RoadPoint>of(a, b, c)));
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
