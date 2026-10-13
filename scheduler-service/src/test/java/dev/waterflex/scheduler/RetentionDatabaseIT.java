package dev.waterflex.scheduler;

import dev.waterflex.scheduler.api.PublicApiStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The retention purge, run as the scheduler's least-privilege login: rows older than the period go, newer rows and
 * rows a newer row still references stay, and that login can do nothing else beyond its grants.
 */
class RetentionDatabaseIT {
    private final String suffix = Required.value(UUID.randomUUID().toString().substring(0, 8));
    private final String tenant = "retention-it-" + suffix, login = "retention_it_" + suffix;
    private final JdbcTemplate owner, app;
    private final DataSourceTransactionManager appTransactions;

    RetentionDatabaseIT() {
        String url = TestDatabase.url();
        owner = new JdbcTemplate(TestDatabase.owner());
        owner.execute("CREATE ROLE " + login + " LOGIN PASSWORD 'retention-it' IN ROLE scheduler_app");
        var appSource = new DriverManagerDataSource(url, login, "retention-it");
        app = new JdbcTemplate(appSource);
        appTransactions = new DataSourceTransactionManager(appSource);
        owner.update("INSERT INTO tenant (id,name) VALUES (?,?)", tenant, "Retention IT");
    }

    @AfterEach void clean() {
        for (String table : List.of("api_booking_receipt", "api_booking_offer", "api_booking_offer_set", "api_booking_day", "api_commit_receipt",
                "api_proposal_technician_day", "api_daily_proposal", "api_request"))
            owner.update("DELETE FROM " + table + " WHERE \"tenantId\"=?", tenant);
        owner.update("DELETE FROM tenant WHERE id=?", tenant);
        owner.execute("DROP ROLE " + login);
    }

    private static String id() { return Required.value(UUID.randomUUID().toString()); }

    /** A completed request whose completion is {@code age} ago. */
    private String request(String operation, Duration age) {
        String requestId = id();
        owner.update("INSERT INTO api_request (\"tenantId\",\"requestId\",operation,\"requestSha256\",state,\"ownerToken\",\"leaseExpiresAt\",\"responseStatus\",\"responseJson\",\"createdAt\",\"completedAt\") "
                + "VALUES (?,?,?,?, 'COMPLETED','owner', clock_timestamp() - ?::interval, 201, '{}'::jsonb, clock_timestamp() - ?::interval, clock_timestamp() - ?::interval)",
                tenant, requestId, operation, "a".repeat(64), interval(age), interval(age), interval(age));
        return requestId;
    }

    private static String interval(Duration age) { return age.toSeconds() + " seconds"; }

    /** An offer set with one offer, made {@code age} ago; returns the offer ID. */
    private String offerSet(String requestId, Duration age) {
        String set = "set-" + id(), offer = id();
        owner.update("INSERT INTO api_booking_offer_set (\"tenantId\",id,\"requestId\",\"jobId\",\"metroId\",\"expiresAt\",status,\"offerSetJson\",\"createdAt\") "
                + "VALUES (?,?,?, 'job-1', 'omaha', clock_timestamp() - ?::interval, 'ACTIVE', '{}'::jsonb, clock_timestamp() - ?::interval)",
                tenant, set, requestId, interval(age), interval(age));
        owner.update("INSERT INTO api_booking_offer (\"tenantId\",id,\"offerSetId\",\"holdId\",\"serviceDate\",\"technicianId\",\"windowStart\",\"windowEnd\",status) "
                + "VALUES (?,?,?,?, DATE '2026-10-12', 'tech-1', TIMESTAMPTZ '2026-10-12T15:00:00Z', TIMESTAMPTZ '2026-10-12T17:00:00Z', 'CONFIRMED')",
                tenant, offer, set, id());
        return offer;
    }

    private void receipt(String offer, String requestId, Duration age) {
        owner.update("INSERT INTO api_booking_receipt (\"tenantId\",id,\"offerId\",\"requestId\",\"receiptJson\",\"createdAt\") VALUES (?,?,?,?, '{}'::jsonb, clock_timestamp() - ?::interval)",
                tenant, "rcpt-" + id(), offer, requestId, interval(age));
    }

    private void bookingDay(String metro, Duration age) {
        owner.update("INSERT INTO api_booking_day (\"tenantId\",\"metroId\",\"serviceDate\",version,\"stateJson\",\"updatedAt\") VALUES (?,?, DATE '2026-10-12', 0, NULL, clock_timestamp() - ?::interval)",
                tenant, metro, interval(age));
    }

    private long count(String table) {
        return Required.value(owner.queryForObject("SELECT count(*) FROM " + table + " WHERE \"tenantId\"=?", Long.class, tenant));
    }

    private boolean exists(String requestId) {
        return Required.value(owner.queryForObject("SELECT count(*) FROM api_request WHERE \"tenantId\"=? AND \"requestId\"=?", Long.class, tenant, requestId)) == 1;
    }

    @Test void rowsOlderThanThePeriodGoAndNewerOrStillReferencedRowsStay() {
        Duration old = Required.value(Duration.ofDays(40)), recent = Required.value(Duration.ofHours(1));
        String oldAlone = request("DAILY_PROPOSAL", old), recentAlone = request("DAILY_PROPOSAL", recent);
        // A booking that aged entirely: its receipt, offer set, offer and both requests go.
        String oldSearch = request("BOOKING_OFFERS", old), oldConfirm = request("BOOKING_CONFIRM", old);
        receipt(offerSet(oldSearch, old), oldConfirm, old);
        // An old search confirmed recently: the receipt still references the offer, so the whole chain stays.
        String keptSearch = request("BOOKING_OFFERS", old), recentConfirm = request("BOOKING_CONFIRM", recent);
        receipt(offerSet(keptSearch, old), recentConfirm, recent);
        bookingDay("old-metro", old);
        bookingDay("recent-metro", recent);

        long deleted = new Retention(app, 30, Required.value(Clock.systemUTC())).purge();

        assertFalse(exists(oldAlone));
        assertTrue(exists(recentAlone));
        assertFalse(exists(oldSearch));
        assertFalse(exists(oldConfirm));
        assertTrue(exists(keptSearch), "an old request still referenced by a newer receipt's chain stays");
        assertTrue(exists(recentConfirm));
        assertEquals(1, count("api_booking_receipt"));
        assertEquals(1, count("api_booking_offer_set"));
        assertEquals(1, count("api_booking_offer"));
        assertEquals(1, count("api_booking_day"));
        assertEquals(1L, owner.queryForObject("SELECT count(*) FROM api_booking_day WHERE \"tenantId\"=? AND \"metroId\"='recent-metro'", Long.class, tenant));
        assertTrue(deleted >= 6, "deleted " + deleted);
        assertEquals(0, new Retention(app, 30, Required.value(Clock.systemUTC())).purge(), "a second purge finds nothing more");
    }

    @Test void thePurgeRefusesACutoffNewerThanOneDay() {
        assertThrows(DataAccessException.class, () -> app.queryForObject("SELECT scheduler_purge(clock_timestamp(), 10)", Integer.class));
        assertThrows(DataAccessException.class, () -> app.queryForObject("SELECT scheduler_purge(clock_timestamp() - INTERVAL '2 days', 0)", Integer.class));
    }

    @Test void theAppLoginHasOnlyItsGrants() {
        assertThrows(DataAccessException.class, () -> app.update("INSERT INTO tenant (id,name) VALUES ('rogue','Rogue')"), "it cannot create tenants");
        assertThrows(DataAccessException.class, () -> app.queryForObject("SELECT count(*) FROM api_request", Long.class), "it reads thin store rows only as scheduler_tenant");
        assertThrows(DataAccessException.class, () -> app.execute("CREATE TABLE retention_it_rogue (id int)"), "it cannot change the schema");
        assertThrows(DataAccessException.class, () -> app.update("UPDATE tenant_api_token SET \"revokedAt\" = NULL"), "it cannot change tokens");
        assertEquals(0L, app.queryForObject("SELECT count(*) FROM tenant_api_token WHERE \"tenantId\"=?", Long.class, tenant), "it can look tokens up");

        // As scheduler_tenant, through the thin store, it sees only its own tenant's rows.
        String requestId = request("DAILY_PROPOSAL", Required.value(Duration.ofHours(1)));
        var store = new PublicApiStore(app, appTransactions);
        assertInstanceOf(PublicApiStore.Replay.class, store.claim(tenant, requestId, PublicApiStore.Operation.DAILY_PROPOSAL, Required.value("a".repeat(64))));
        assertInstanceOf(PublicApiStore.Started.class, store.claim(tenant, id(), PublicApiStore.Operation.DAILY_PROPOSAL, Required.value("b".repeat(64))));
    }
}
