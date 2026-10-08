package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.api.PublicApiStore.*;
import dev.waterflex.scheduler.api.PublicResponses.*;
import dev.waterflex.scheduler.api.PublicTypes.TechnicianDayVersion;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** POST /api/v1/daily/proposals/{proposalId}/commit below HTTP, against a real database. */
class DailyCommitsDatabaseIT {
    private static final LocalDate DAY = Required.value(LocalDate.parse("2026-10-12"));
    private static final Instant TECH_1 = Required.value(Instant.parse("2026-10-11T21:04:17.123456789Z"));
    private static final Instant TECH_2 = Required.value(Instant.parse("2026-10-10T16:30:00Z"));
    private static final Instant BEFORE_CUTOFF = Required.value(Instant.parse("2026-10-11T12:00:00Z"));
    /** 6 a.m. in Omaha on the service date. */
    private static final Instant AT_CUTOFF = Required.value(Instant.parse("2026-10-12T11:00:00Z"));
    private final String suffix = Required.value(UUID.randomUUID().toString().substring(0, 8));
    private final String tenant = "commit-it-" + suffix, other = "commit-it-other-" + suffix;
    private final JdbcTemplate jdbc;
    private final DataSourceTransactionManager manager;
    private final PublicApiStore store;

    DailyCommitsDatabaseIT() {
        String url = Required.value(System.getenv("JDBC_DATABASE_URL"), "isolated integration database URL");
        assertEquals("/waterflex_test", URI.create(url.substring(5)).getPath());
        var source = new DriverManagerDataSource(url, "waterflex", "waterflex");
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        store = new PublicApiStore(jdbc, manager);
        jdbc.update("INSERT INTO tenant (id,name) VALUES (?,?),(?,?)", tenant, "Commit IT", other, "Commit IT other");
    }

    @AfterEach void clean() {
        for (String table : List.of("api_commit_receipt", "api_proposal_technician_day", "api_daily_proposal", "api_request"))
            jdbc.update("DELETE FROM " + table + " WHERE \"tenantId\" IN (?,?)", tenant, other);
        jdbc.update("DELETE FROM tenant WHERE id IN (?,?)", tenant, other);
    }

    private DailyCommits commits(Instant now) { return new DailyCommits(store, Required.value(Clock.fixed(now, ZoneOffset.UTC))); }

    private DailyCommits commits() { return commits(BEFORE_CUTOFF); }

    /** The spec's example proposal, stored for {@code owner} with the given decision. */
    private String stored(String owner, Decision decision) {
        String requestId = Required.value(UUID.randomUUID().toString());
        String id = PublicApiStore.newProposalId();
        var example = PublicRequests.read(PublicApiContractTest.example("DailyProposal"), DailyProposal.class);
        var body = new DailyProposal(id, example.inputRevision(), decision, example.reason(), example.routes(),
                example.unresolvedAppointmentIds(), example.skippedTechnicianDays(), example.costCents(), example.overtimeMinutes());
        var proposal = new StoredProposal(id, requestId, "omaha", "America/Chicago", DAY, example.inputRevision(), "omaha-map-v7", ProposalStatus.PROPOSED,
                Required.value(List.of(new TechnicianDayVersion("tech-1", DAY, TECH_1), new TechnicianDayVersion("tech-2", DAY, TECH_2))), body);
        Started started = assertInstanceOf(Started.class, store.claim(owner, requestId, Operation.DAILY_PROPOSAL, Required.value("a".repeat(64))));
        store.completeDaily(owner, started.ownerToken(), proposal);
        return id;
    }

    private static String body(String requestId, Instant tech1, Instant tech2) {
        return CalculationJson.write(new PublicRequests.CommitRequest(requestId, Required.value(List.of(
                new TechnicianDayVersion("tech-1", DAY, tech1), new TechnicianDayVersion("tech-2", DAY, tech2)))));
    }

    private static String current() { return body(Required.value(UUID.randomUUID().toString()), TECH_1, TECH_2); }

    private static Problem problem(DailyProposals.Reply reply, int status, ErrorCode code) {
        assertEquals(status, reply.status(), reply.json());
        Problem problem = PublicRequests.read(reply.json(), Problem.class);
        assertEquals(code, problem.error());
        return problem;
    }

    private long count(String sql, @org.jspecify.annotations.Nullable Object... arguments) {
        return Required.value(jdbc.queryForObject(sql, Long.class, arguments)).longValue();
    }

    private ProposalStatus status(String proposalId) { return Required.value(store.proposal(tenant, proposalId)).status(); }

    @Test void aCurrentCommitReturnsEveryAssignmentOnceAndReplaysIt() {
        String proposal = stored(tenant, Decision.IMPROVED);
        String body = current();
        var reply = commits().commit(tenant, proposal, body);
        assertEquals(200, reply.status(), reply.json());
        CommitReceipt receipt = PublicRequests.read(reply.json(), CommitReceipt.class);
        assertTrue(receipt.receiptId().startsWith("rcpt-"));
        assertEquals(List.of(
                new Assignment("appt-7", "tech-1", DAY, 0, Required.value(Instant.parse("2026-10-12T13:24:00Z")), Required.value(Instant.parse("2026-10-12T14:24:00Z"))),
                new Assignment("appt-8", "tech-2", DAY, 0, Required.value(Instant.parse("2026-10-12T18:10:00Z")), Required.value(Instant.parse("2026-10-12T18:55:00Z")))),
                receipt.assignments());
        assertEquals(List.of(new TechnicianDayVersion("tech-1", DAY, TECH_1), new TechnicianDayVersion("tech-2", DAY, TECH_2)), receipt.technicianDays());
        assertEquals(ProposalStatus.COMMITTED, status(proposal));
        assertEquals(1L, count("SELECT count(*) FROM api_commit_receipt WHERE \"tenantId\"=? AND id=? AND \"proposalId\"=?", tenant, receipt.receiptId(), proposal));
        assertEquals(reply, commits().commit(tenant, proposal, body), "the same requestId replays the receipt");
        assertEquals(reply, commits(AT_CUTOFF).commit(tenant, proposal, body), "a replay is the stored answer, even after the cutoff");
        Problem again = problem(commits().commit(tenant, proposal, current()), 409, ErrorCode.NOT_COMMITTABLE);
        assertTrue(again.message().contains(receipt.receiptId()), again.message());
        assertEquals(1L, count("SELECT count(*) FROM api_commit_receipt WHERE \"tenantId\"=?", tenant));
    }

    @Test void aStaleCommitListsTheRecordedTimestampsChangesNothingAndIsReplayed() {
        String proposal = stored(tenant, Decision.IMPROVED);
        String body = body(Required.value(UUID.randomUUID().toString()), TECH_1, Required.value(TECH_2.plusNanos(1000)));
        var reply = commits().commit(tenant, proposal, body);
        assertEquals(409, reply.status(), reply.json());
        StaleProblem stale = PublicRequests.read(reply.json(), StaleProblem.class);
        assertEquals(List.of(new TechnicianDayVersion("tech-2", DAY, TECH_2)), stale.changed());
        assertEquals(ProposalStatus.PROPOSED, status(proposal));
        assertEquals(0L, count("SELECT count(*) FROM api_commit_receipt WHERE \"tenantId\"=?", tenant));
        assertEquals(reply, commits().commit(tenant, proposal, body));
        assertEquals(200, commits().commit(tenant, proposal, current()).status(), "a refused commit leaves the proposal committable");
    }

    @Test void nanosecondTimestampsMustMatchExactly() {
        String proposal = stored(tenant, Decision.IMPROVED);
        var reply = commits().commit(tenant, proposal, body(Required.value(UUID.randomUUID().toString()), Required.value(TECH_1.minusNanos(1)), TECH_2));
        assertEquals(409, reply.status(), reply.json());
        assertEquals(List.of(new TechnicianDayVersion("tech-1", DAY, TECH_1)), PublicRequests.read(reply.json(), StaleProblem.class).changed());
    }

    @Test void onlyImprovedProposalsOfThisTenantCanBeCommitted() {
        problem(commits().commit(tenant, PublicApiStore.newProposalId(), current()), 404, ErrorCode.NOT_FOUND);
        problem(commits().commit(tenant, stored(other, Decision.IMPROVED), current()), 404, ErrorCode.NOT_FOUND);
        for (Decision decision : List.of(Decision.NO_IMPROVEMENT, Decision.REJECTED_BY_POLICY)) {
            String proposal = stored(tenant, Required.value(decision));
            assertTrue(problem(commits().commit(tenant, proposal, current()), 409, ErrorCode.NOT_COMMITTABLE).message().contains(decision.name()));
            assertEquals(ProposalStatus.PROPOSED, status(proposal));
        }
    }

    @Test void theTechnicianDaysMustBeExactlyTheOnesTheProposalCovers() {
        String proposal = stored(tenant, Decision.IMPROVED);
        String missing = CalculationJson.write(new PublicRequests.CommitRequest(Required.value(UUID.randomUUID().toString()),
                Required.value(List.of(new TechnicianDayVersion("tech-1", DAY, TECH_1)))));
        assertTrue(problem(commits().commit(tenant, proposal, missing), 400, ErrorCode.INVALID_REQUEST).message().contains("tech-2"));
        String extra = CalculationJson.write(new PublicRequests.CommitRequest(Required.value(UUID.randomUUID().toString()), Required.value(List.of(
                new TechnicianDayVersion("tech-1", DAY, TECH_1), new TechnicianDayVersion("tech-2", DAY, TECH_2), new TechnicianDayVersion("tech-3", DAY, TECH_2)))));
        assertTrue(problem(commits().commit(tenant, proposal, extra), 400, ErrorCode.INVALID_REQUEST).message().contains("tech-3"));
        long requests = count("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", tenant);
        problem(commits().commit(tenant, proposal, "{\"requestId\":"), 400, ErrorCode.INVALID_REQUEST);
        problem(commits().commit(tenant, proposal, Required.value(current().replace("\"technicianDays\"", "\"tenantId\":\"x\",\"technicianDays\""))), 400, ErrorCode.INVALID_REQUEST);
        problem(commits().commit(tenant, " ", current()), 400, ErrorCode.INVALID_REQUEST);
        assertEquals(requests, count("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", tenant), "a malformed request claims nothing");
        String reused = current();
        assertEquals(200, commits().commit(tenant, proposal, reused).status());
        problem(commits().commit(tenant, stored(tenant, Decision.IMPROVED), reused), 400, ErrorCode.INVALID_REQUEST);
    }

    @Test void routesFrozenAtSixInTheMorningCannotBeCommitted() {
        String proposal = stored(tenant, Decision.IMPROVED);
        assertTrue(problem(commits(AT_CUTOFF).commit(tenant, proposal, current()), 422, ErrorCode.INCOMPLETE_FACTS).message().contains("frozen"));
        assertEquals(ProposalStatus.PROPOSED, status(proposal));
        assertEquals(200, commits(Required.value(AT_CUTOFF.minusSeconds(1))).commit(tenant, proposal, current()).status());
    }

    @Test void concurrentCommitsOfOneProposalCommitItExactlyOnce() throws Exception {
        String proposal = stored(tenant, Decision.IMPROVED);
        int callers = 6;
        ExecutorService pool = Required.value(Executors.newFixedThreadPool(callers));
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<DailyProposals.Reply>> replies = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                String body = current();
                replies.add(Required.value(pool.submit(() -> { start.await(); return commits().commit(tenant, proposal, body); })));
            }
            start.countDown();
            int committed = 0;
            for (Future<DailyProposals.Reply> reply : replies) {
                DailyProposals.Reply answer = Required.value(Required.value(reply).get());
                if (answer.status() == 200) committed++;
                else problem(answer, 409, ErrorCode.NOT_COMMITTABLE);
            }
            assertEquals(1, committed);
        } finally { pool.shutdownNow(); }
        assertEquals(1L, count("SELECT count(*) FROM api_commit_receipt WHERE \"tenantId\"=?", tenant));
    }

    @Test void aTamperedStoredAnswerIsNotReplayed() {
        String proposal = stored(tenant, Decision.IMPROVED);
        String body = current();
        assertEquals(200, commits().commit(tenant, proposal, body).status());
        jdbc.update("UPDATE api_request SET \"responseJson\"=jsonb_set(\"responseJson\",'{assignments,0,sequence}','-1') WHERE \"tenantId\"=? AND operation='DAILY_COMMIT'", tenant);
        assertThrows(IllegalStateException.class, () -> commits().commit(tenant, proposal, body));
    }

    @Test void aLockedProposalHasAReceiptExactlyWhenItIsCommitted() {
        StoredProposal proposal = Required.value(store.proposal(tenant, stored(tenant, Decision.IMPROVED)));
        assertThrows(IllegalStateException.class, () -> new Locked(proposal, "rcpt-1"));
        var committed = new StoredProposal(proposal.id(), proposal.requestId(), proposal.metroId(), proposal.timeZone(), proposal.serviceDate(), proposal.inputRevision(),
                proposal.routingIdentity(), ProposalStatus.COMMITTED, proposal.technicianDays(), proposal.proposal());
        assertThrows(IllegalStateException.class, () -> new Locked(committed, null));
    }

    @Test void receiptsAreInvisibleToOtherTenantsAtTheDatabase() {
        String proposal = stored(tenant, Decision.IMPROVED);
        assertEquals(200, commits().commit(tenant, proposal, current()).status());
        assertEquals(1L, rows(tenant, "SELECT count(*) FROM api_commit_receipt"));
        assertEquals(0L, rows(other, "SELECT count(*) FROM api_commit_receipt"));
    }

    /** Counts rows as scheduler_tenant with app.tenant_id set, with no tenant filter in the query. */
    private long rows(String scope, String sql) {
        Long count = new TransactionTemplate(manager).execute(_ -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, scope);
            jdbc.execute("SET LOCAL ROLE scheduler_tenant");
            return jdbc.queryForObject(sql, Long.class);
        });
        return Required.value(count).longValue();
    }
}
