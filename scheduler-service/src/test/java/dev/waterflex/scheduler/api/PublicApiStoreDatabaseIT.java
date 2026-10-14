package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.TestDatabase;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.api.PublicApiStore.*;
import dev.waterflex.scheduler.api.PublicTypes.TechnicianDayVersion;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** The public API thin store against a real database, including the row-level security guard. */
class PublicApiStoreDatabaseIT {
    private static final String SHA_A = Required.value("a".repeat(64)), SHA_B = Required.value("b".repeat(64));
    private final String suffix = Required.value(UUID.randomUUID().toString().substring(0, 8));
    private final String tenantA = "store-it-a-" + suffix, tenantB = "store-it-b-" + suffix;
    private final JdbcTemplate jdbc;
    private final DataSourceTransactionManager manager;
    private final PublicApiStore store;

    PublicApiStoreDatabaseIT() {
        var source = TestDatabase.owner();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        store = new PublicApiStore(jdbc, manager);
        jdbc.update("INSERT INTO tenant (id,name) VALUES (?,?),(?,?)", tenantA, "Store IT A", tenantB, "Store IT B");
    }

    @AfterEach void clean() {
        for (String table : List.of("api_commit_receipt", "api_proposal_technician_day", "api_daily_proposal", "api_request"))
            jdbc.update("DELETE FROM " + table + " WHERE \"tenantId\" IN (?,?)", tenantA, tenantB);
        jdbc.update("DELETE FROM tenant WHERE id IN (?,?)", tenantA, tenantB);
    }

    private static String requestId() { return Required.value(UUID.randomUUID().toString()); }

    private static StoredProposal proposal(String requestId) {
        String id = PublicApiStore.newProposalId();
        var example = PublicRequests.read(PublicApiContractTest.example("DailyProposal"), PublicResponses.DailyProposal.class);
        var body = new PublicResponses.DailyProposal(id, example.inputRevision(), example.decision(), example.reason(), example.routes(),
                example.unresolvedAppointmentIds(), example.skippedTechnicianDays(), example.costCents(), example.overtimeMinutes());
        LocalDate day = Required.value(LocalDate.parse("2026-10-12"));
        return new StoredProposal(id, requestId, "omaha", "America/Chicago", day, example.inputRevision(), "omaha-map-v7", ProposalStatus.PROPOSED,
                Required.value(List.of(new TechnicianDayVersion("tech-1", day, Required.value(Instant.parse("2026-10-11T21:04:17.123456789Z"))),
                        new TechnicianDayVersion("tech-2", day, Required.value(Instant.parse("2026-10-10T16:30:00Z"))))), body);
    }

    private static String owner(Claim claim) {
        return assertInstanceOf(Started.class, claim).ownerToken();
    }

    @Test void aRequestIsClaimedOnceAndItsStoredResponseIsReplayed() {
        String request = requestId();
        String owner = owner(store.claim(tenantA, request, Operation.DAILY_PROPOSAL, SHA_A));
        assertInstanceOf(Busy.class, store.claim(tenantA, request, Operation.DAILY_PROPOSAL, SHA_A));
        assertInstanceOf(Conflict.class, store.claim(tenantA, request, Operation.DAILY_PROPOSAL, SHA_B));
        StoredProposal stored = proposal(request);
        store.completeDaily(tenantA, owner, stored);
        Replay replay = assertInstanceOf(Replay.class, store.claim(tenantA, request, Operation.DAILY_PROPOSAL, SHA_A));
        assertEquals(201, replay.status());
        assertEquals(stored.proposal(), PublicRequests.read(replay.json(), PublicResponses.DailyProposal.class));
        assertInstanceOf(Conflict.class, store.claim(tenantA, request, Operation.DAILY_PROPOSAL, SHA_B));
        assertEquals(stored, store.proposal(tenantA, stored.id()));
        assertNull(store.proposal(tenantA, PublicApiStore.newProposalId()));
    }

    @Test void nanosecondHostTimestampsSurviveExactly() {
        String request = requestId();
        StoredProposal stored = proposal(request);
        store.completeDaily(tenantA, owner(store.claim(tenantA, request, Operation.DAILY_PROPOSAL, SHA_A)), stored);
        StoredProposal read = Required.value(store.proposal(tenantA, stored.id()));
        assertEquals(Instant.parse("2026-10-11T21:04:17.123456789Z"), read.technicianDays().getFirst().lastModified());
    }

    @Test void aCancelRecordedOnAnotherReplicaIsFoundByTheReplicaRunningTheRequest() {
        String request = requestId(), other = requestId(), done = requestId();
        owner(store.claim(tenantA, request, Operation.BOOKING_OFFERS, SHA_A));
        owner(store.claim(tenantB, other, Operation.DAILY_PROPOSAL, SHA_A));
        store.completeDaily(tenantA, owner(store.claim(tenantA, done, Operation.DAILY_PROPOSAL, SHA_A)), proposal(done));
        var running = Required.value(List.of(new RunningRequests.Key(tenantA, request), new RunningRequests.Key(tenantB, other), new RunningRequests.Key(tenantA, done)));
        assertEquals(List.of(), store.cancelRequested(running), "nothing was cancelled yet");

        assertTrue(store.requestCancel(tenantA, request));
        assertFalse(store.requestCancel(tenantB, request), "another tenant cannot cancel it");
        assertFalse(store.requestCancel(tenantA, done), "a completed request has nothing to stop");
        assertFalse(store.requestCancel(tenantA, requestId()), "an unknown request is not recorded");
        assertEquals(List.of(new RunningRequests.Key(tenantA, request)), store.cancelRequested(running));
        assertEquals(List.of(), store.cancelRequested(Required.value(List.of())));

        // A claim taken over after its lease passed is a new run: the earlier cancel does not stop it.
        jdbc.update("UPDATE api_request SET \"leaseExpiresAt\"=clock_timestamp()-interval '1 second' WHERE \"tenantId\"=? AND \"requestId\"=?", tenantA, request);
        owner(store.claim(tenantA, request, Operation.BOOKING_OFFERS, SHA_A));
        assertEquals(List.of(), store.cancelRequested(running));
    }

    @Test void theWatcherStopsOnlyTheCancelledRequestsRunningHere() {
        String request = requestId(), elsewhere = requestId();
        owner(store.claim(tenantA, request, Operation.DAILY_PROPOSAL, SHA_A));
        owner(store.claim(tenantA, elsewhere, Operation.DAILY_PROPOSAL, SHA_A));
        var running = new RunningRequests();
        var watcher = new CancelWatcher(running, store);
        assertEquals(0, watcher.check(), "nothing runs here, so nothing is read");
        var here = running.start(tenantA, request);
        assertEquals(0, watcher.check());
        assertTrue(store.requestCancel(tenantA, elsewhere));
        assertEquals(0, watcher.check(), "a cancel for a request running on another replica is left to it");
        assertTrue(store.requestCancel(tenantA, request));
        assertEquals(1, watcher.check());
        assertTrue(here.cancelled());
        running.finish(tenantA, request, here);
        assertEquals(0, watcher.check());
    }

    @Test void cancellingFromAReplicaThatDoesNotRunTheRequestRecordsIt() {
        String request = requestId();
        owner(store.claim(tenantA, request, Operation.BOOKING_CONFIRM, SHA_A));
        var cancellation = new RequestCancellation(new RunningRequests(), null, store);
        assertNull(cancellation.cancel(tenantA, request), "202: the cancel is recorded for the replica running it");
        assertEquals(List.of(new RunningRequests.Key(tenantA, request)), store.cancelRequested(Required.value(List.of(new RunningRequests.Key(tenantA, request)))));
        assertEquals(404, Required.value(cancellation.cancel(tenantA, requestId())).status());
        assertEquals(404, Required.value(cancellation.cancel(tenantB, request)).status(), "another tenant's request is not found");
    }

    @Test void anAbandonedClaimIsTakenOverAndItsFormerOwnerCannotComplete() {
        String request = requestId();
        String first = owner(store.claim(tenantA, request, Operation.DAILY_PROPOSAL, SHA_A));
        jdbc.update("UPDATE api_request SET \"leaseExpiresAt\"=clock_timestamp()-interval '1 second' WHERE \"tenantId\"=? AND \"requestId\"=?", tenantA, request);
        String second = owner(store.claim(tenantA, request, Operation.DAILY_PROPOSAL, SHA_A));
        assertNotEquals(first, second);
        StoredProposal late = proposal(request);
        assertThrows(IllegalStateException.class, () -> store.completeDaily(tenantA, first, late));
        assertNull(store.proposal(tenantA, late.id()), "a failed completion stores nothing");
        store.completeDaily(tenantA, second, proposal(request));
    }

    @Test void releasedClaimsRunAgainAndFinalErrorsAreReplayed() {
        String request = requestId();
        store.release(tenantA, request, owner(store.claim(tenantA, request, Operation.DAILY_PROPOSAL, SHA_A)));
        String owner = owner(store.claim(tenantA, request, Operation.DAILY_PROPOSAL, SHA_A));
        var problem = new PublicResponses.Problem(PublicResponses.ErrorCode.INCOMPLETE_FACTS, "appt-8 has no coordinates");
        store.completeWithError(tenantA, request, owner, 422, problem);
        Replay replay = assertInstanceOf(Replay.class, store.claim(tenantA, request, Operation.DAILY_PROPOSAL, SHA_A));
        assertEquals(422, replay.status());
        assertEquals(problem, PublicRequests.read(replay.json(), PublicResponses.Problem.class));
        assertThrows(IllegalArgumentException.class, () -> store.completeWithError(tenantA, request, owner, 200, problem));
    }

    @Test void tenantsNeverSeeEachOthersRequestsOrProposals() {
        String request = requestId();
        StoredProposal stored = proposal(request);
        store.completeDaily(tenantA, owner(store.claim(tenantA, request, Operation.DAILY_PROPOSAL, SHA_A)), stored);
        // The same host request ID in another tenant is a different request.
        owner(store.claim(tenantB, request, Operation.DAILY_PROPOSAL, SHA_B));
        assertNull(store.proposal(tenantB, stored.id()));
        // The database guard alone, with no tenant filter in the query.
        var tx = new TransactionTemplate(manager);
        assertEquals(0L, rows(tenantB, "SELECT count(*) FROM api_daily_proposal"));
        assertEquals(1L, rows(tenantA, "SELECT count(*) FROM api_daily_proposal"));
        assertEquals(0L, rows(null, "SELECT count(*) FROM api_request"));
        Integer updated = tx.execute(_ -> { scope(tenantB); return jdbc.update("UPDATE api_daily_proposal SET status='STALE' WHERE id=?", stored.id()); });
        assertEquals(0, Required.value(updated).intValue());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> tx.execute(_ -> { scope(tenantB);
            return jdbc.update("INSERT INTO api_request (\"tenantId\",\"requestId\",operation,\"requestSha256\",state,\"ownerToken\",\"leaseExpiresAt\") VALUES (?,?,'DAILY_PROPOSAL',?,'IN_PROGRESS','x',clock_timestamp())",
                    tenantA, requestId(), SHA_A); }));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> tx.execute(_ -> { scope(tenantA); return jdbc.queryForObject("SELECT count(*) FROM technician", Long.class); }));
        assertEquals(ProposalStatus.PROPOSED, Required.value(store.proposal(tenantA, stored.id())).status());
    }

    /** Counts rows as scheduler_tenant, with app.tenant_id set to {@code tenant} or left unset. */
    private long rows(@org.jspecify.annotations.Nullable String tenant, String sql) {
        Long count = new TransactionTemplate(manager).execute(_ -> {
            if (tenant != null) scope(tenant); else jdbc.execute("SET LOCAL ROLE scheduler_tenant");
            return jdbc.queryForObject(sql, Long.class);
        });
        return Required.value(count).longValue();
    }

    private void scope(String tenant) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant);
        jdbc.execute("SET LOCAL ROLE scheduler_tenant");
    }

    @Test void aChangedPersistedProposalIsRejectedNotTrusted() {
        String request = requestId();
        StoredProposal stored = proposal(request);
        store.completeDaily(tenantA, owner(store.claim(tenantA, request, Operation.DAILY_PROPOSAL, SHA_A)), stored);
        jdbc.update("UPDATE api_daily_proposal SET \"proposalJson\"=jsonb_set(\"proposalJson\",'{overtimeMinutes}','30') WHERE \"tenantId\"=? AND id=?", tenantA, stored.id());
        assertThrows(IllegalArgumentException.class, () -> store.proposal(tenantA, stored.id()));
        jdbc.update("UPDATE api_daily_proposal SET \"proposalJson\"=?::jsonb WHERE \"tenantId\"=? AND id=?", CalculationJson.write(stored.proposal()), tenantA, stored.id());
        assertEquals(stored, store.proposal(tenantA, stored.id()));
    }
}
