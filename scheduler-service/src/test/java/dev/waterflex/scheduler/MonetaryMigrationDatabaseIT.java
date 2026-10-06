package dev.waterflex.scheduler;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the actual migration in private disposable schemas of waterflex_test only. */
class MonetaryMigrationDatabaseIT {
    @Test void migrationPreservesLegacyDecimalReceiptsAndConfirmedPromises() throws Exception {
        fixture(20.02, connection -> {
            migration(connection);
            try (Statement sql = connection.createStatement()) {
                try (ResultSet rows = sql.executeQuery("SELECT value FROM omaha_setting WHERE key='regular_hourly_dollars'")) {
                    assertTrue(rows.next()); assertEquals(0, Required.decimal(rows, 1).compareTo(new BigDecimal("20.02")));
                }
                try (ResultSet rows = sql.executeQuery("SELECT \"legacyRendering\",\"legacyBits\",\"decimalValue\" FROM monetary_migration_receipt WHERE \"sourceIdentity\"='regular_hourly_dollars'")) {
                    assertTrue(rows.next()); assertEquals(0, new BigDecimal(Required.string(rows, 1)).compareTo(BigDecimal.valueOf(20.02)));
                    assertEquals("4034051eb851eb85", Required.string(rows, 2)); assertEquals(0, Required.decimal(rows, 3).compareTo(new BigDecimal("20.02")));
                }
                try (ResultSet rows = sql.executeQuery("SELECT \"legacyRendering\",\"decimalValue\" FROM monetary_migration_receipt WHERE \"sourceIdentity\"='adjacent'")) {
                    assertTrue(rows.next()); assertEquals(0, new BigDecimal(Required.string(rows, 1)).compareTo(BigDecimal.valueOf(Math.nextUp(.1))));
                    assertEquals(0, Required.decimal(rows, 2).compareTo(BigDecimal.valueOf(Math.nextUp(.1))));
                }
                try (ResultSet rows = sql.executeQuery("SELECT count(*) FROM monetary_migration_receipt")) { assertTrue(rows.next()); assertEquals(6, rows.getInt(1)); }
                try (ResultSet rows = sql.executeQuery("SELECT status,reason FROM optimization_run ORDER BY id")) {
                    assertTrue(rows.next()); assertEquals("APPLIED", rows.getString(1)); assertNull(rows.getString(2));
                    assertTrue(rows.next()); assertEquals("STALE", rows.getString(1)); assertEquals("COST_MODEL_CHANGED", rows.getString(2));
                    assertTrue(rows.next()); assertEquals("STALE", rows.getString(1));
                }
                try (ResultSet rows = sql.executeQuery("SELECT id,\"supersededAt\" IS NULL FROM booking_offer_set ORDER BY id")) {
                    assertTrue(rows.next()); assertEquals("confirmed", rows.getString(1)); assertTrue(rows.getBoolean(2));
                    assertTrue(rows.next()); assertEquals("pending", rows.getString(1)); assertFalse(rows.getBoolean(2));
                }
                try (ResultSet rows = sql.executeQuery("SELECT id,\"releasedAt\" IS NULL FROM slot_hold ORDER BY id")) {
                    assertTrue(rows.next()); assertEquals("confirmed", rows.getString(1)); assertTrue(rows.getBoolean(2));
                    assertTrue(rows.next()); assertEquals("pending", rows.getString(1)); assertFalse(rows.getBoolean(2));
                }
                try (ResultSet rows = sql.executeQuery("SELECT state,\"stopReason\",\"bestCostDeltaCents\" FROM booking_search_request")) {
                    assertTrue(rows.next()); assertEquals("FAILED", rows.getString(1)); assertEquals("COST_MODEL_CHANGED", rows.getString(2)); assertNull(rows.getObject(3));
                }
                try (ResultSet rows = sql.executeQuery("SELECT \"windowStart\",\"windowEnd\" FROM appointment")) {
                    assertTrue(rows.next()); assertEquals(Timestamp.valueOf("2030-01-01 08:00:00"), rows.getTimestamp(1)); assertEquals(Timestamp.valueOf("2030-01-01 12:00:00"), rows.getTimestamp(2));
                }
                assertThrows(SQLException.class, () -> sql.execute("UPDATE omaha_setting SET value=-1 WHERE key='regular_hourly_dollars'"));
                assertThrows(SQLException.class, () -> sql.execute("UPDATE omaha_setting SET value='NaN'::numeric WHERE key='regular_hourly_dollars'"));
                sql.execute("INSERT INTO booking_offer (id,\"jobId\",\"expiresAt\",\"incrementalCostDollars\") VALUES ('new','pending',CURRENT_TIMESTAMP,85.09)");
                try (ResultSet rows = sql.executeQuery("SELECT \"incrementalCostDollars\",\"costModelVersion\" FROM booking_offer WHERE id='new'")) {
                    assertTrue(rows.next()); assertEquals(0, Required.decimal(rows, 1).compareTo(new BigDecimal("85.09"))); assertEquals(Monetary.COST_MODEL, rows.getString(2));
                }
            }
        });
    }
    @Test void invalidLegacyValuesAbortWithoutPartialConversionOrReceipts() throws Exception {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -.01, 1e-31, 1e36}) {
            fixture(value, connection -> {
                assertThrows(SQLException.class, () -> migration(connection));
                try (Statement sql = connection.createStatement()) {
                    sql.execute("ROLLBACK");
                    try (ResultSet rows = sql.executeQuery("SELECT data_type FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='omaha_setting' AND column_name='value'")) {
                        assertTrue(rows.next()); assertEquals("double precision", rows.getString(1));
                    }
                    try (ResultSet rows = sql.executeQuery("SELECT to_regclass('monetary_migration_receipt')")) { assertTrue(rows.next()); assertNull(rows.getString(1)); }
                    try (ResultSet rows = sql.executeQuery("SELECT status FROM optimization_run WHERE id='preview'")) { assertTrue(rows.next()); assertEquals("PREVIEW", rows.getString(1)); }
                }
            });
        }
    }
    private static void migration(Connection connection) throws Exception {
        Path root = Path.of(Required.value(System.getProperty("user.dir")));
        Path migration = root.resolve("web/prisma/migrations/20261006194000_exact_monetary_contract/migration.sql");
        if (!Files.exists(migration)) migration = Required.value(root.getParent(), "repository parent").resolve("web/prisma/migrations/20261006194000_exact_monetary_contract/migration.sql");
        try (Statement sql = connection.createStatement()) { sql.execute(Files.readString(migration)); }
    }
    private interface Check { void run(Connection connection) throws Exception; }
    private static void fixture(double value, Check check) throws Exception {
        String url = Required.value(System.getenv("JDBC_DATABASE_URL"), "integration database URL");
        assertEquals("/waterflex_test", URI.create(url.substring("jdbc:".length())).getPath());
        String schema = "money_it_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(schema.matches("money_it_[a-f0-9]{32}"));
        try (Connection connection = DriverManager.getConnection(url, "waterflex", "waterflex"); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema); sql.execute("SET search_path TO " + schema);
            try {
                sql.execute("CREATE TABLE omaha_setting (key text primary key,value double precision NOT NULL)");
                sql.execute("INSERT INTO omaha_setting VALUES ('overtime_hourly_dollars',30.03),('mileage_dollars_per_mile',0.67),('adjacent',0.10000000000000002)");
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO omaha_setting VALUES ('regular_hourly_dollars',?)")) { insert.setDouble(1, value); insert.executeUpdate(); }
                sql.execute("CREATE TABLE job (id text primary key,status text); INSERT INTO job VALUES ('pending','PENDING'),('confirmed','SCHEDULED')");
                sql.execute("CREATE TABLE booking_offer (id text primary key,\"jobId\" text,\"incrementalCostDollars\" double precision,\"expiresAt\" timestamp); INSERT INTO booking_offer VALUES ('pending','pending',85.08,CURRENT_TIMESTAMP+interval '1 day'),('confirmed','confirmed',12.34,CURRENT_TIMESTAMP+interval '1 day')");
                sql.execute("CREATE TABLE booking_offer_set (id text,\"jobId\" text,\"supersededAt\" timestamp); INSERT INTO booking_offer_set VALUES ('pending','pending',NULL),('confirmed','confirmed',NULL)");
                for (String table : new String[]{"slot_hold","reservation_obligation"}) sql.execute("CREATE TABLE " + table + " (id text,\"jobId\" text,\"releasedAt\" timestamp); INSERT INTO " + table + " VALUES ('pending','pending',NULL),('confirmed','confirmed',NULL)");
                sql.execute("CREATE TABLE optimization_run (id text,status text,reason text,\"objectiveImprovement\" integer DEFAULT 0); INSERT INTO optimization_run (id,status,reason) VALUES ('preview','PREVIEW',NULL),('repair','REPAIR_PREVIEW',NULL),('applied','APPLIED',NULL)");
                sql.execute("CREATE TABLE booking_search_request (state text,phase text,\"stopReason\" text,\"finishedAt\" timestamp,\"cancelledAt\" timestamp,\"bestCostDeltaCents\" bigint); INSERT INTO booking_search_request (state,phase,\"bestCostDeltaCents\") VALUES ('RUNNING','SEARCH',1)");
                sql.execute("CREATE TABLE appointment (\"windowStart\" timestamp,\"windowEnd\" timestamp); INSERT INTO appointment VALUES ('2030-01-01 08:00:00','2030-01-01 12:00:00')");
                check.run(connection);
            } finally { sql.execute("ROLLBACK"); sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}
