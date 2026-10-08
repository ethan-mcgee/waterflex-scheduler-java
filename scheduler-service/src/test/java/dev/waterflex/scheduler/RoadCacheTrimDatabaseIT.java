package dev.waterflex.scheduler;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** The whole table is trimmed, so the fixture runs inside a rolled-back transaction. */
class RoadCacheTrimDatabaseIT {
    @Test void trimRemovesOldestRowsAcrossBatchesUntilWithinCap() {
        String url = Required.value(System.getenv("JDBC_DATABASE_URL"), "isolated integration database URL");
        assertEquals("/waterflex_test", URI.create(url.substring("jdbc:".length())).getPath());
        var source = new DriverManagerDataSource(url, "waterflex", "waterflex");
        var jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        var roads = new RoadClient(jdbc, "http://127.0.0.1:9", 10, 60, manager);
        new TransactionTemplate(manager).executeWithoutResult(transaction -> {
            transaction.setRollbackOnly();
            jdbc.update("DELETE FROM road_route_cache");
            for (int row = 0; row < 11; row++)
                jdbc.update("INSERT INTO road_route_cache (id,\"originKey\",\"destinationKey\",profile,\"mapVersion\",seconds,meters,routable,\"fetchedAt\") VALUES (?,?,?,'car','trim-fixture',60,1000,true,TIMESTAMP '2026-10-08 00:00:00'+make_interval(mins => ?))",
                        "trim-" + row, "0.00000,0.0000" + (row % 10), "1.00000," + row, row);
            // Seven rows exceed the cap of four; batches of two need four rounds, the last one partial.
            assertEquals(7, roads.trimPersistentCache(4, 2));
            List<String> kept = jdbc.queryForList("SELECT id FROM road_route_cache ORDER BY id", String.class);
            assertEquals(List.of("trim-10", "trim-7", "trim-8", "trim-9"), kept);
            assertEquals(0, roads.trimPersistentCache(4, 2));
            assertThrows(IllegalArgumentException.class, () -> roads.trimPersistentCache(4, 0));
        });
    }
}
