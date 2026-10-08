package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.DatabaseFacts;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.api.PublicTypes.TechnicianDayVersion;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The thin store behind the public API. Every call runs in its own transaction as the scheduler_tenant database
 * role with app.tenant_id set, so row-level security limits it to the caller's tenant even if a query forgets its
 * tenant filter. Nothing here holds master data.
 */
@Component
public class PublicApiStore {
    public enum Operation { DAILY_PROPOSAL }
    public enum ProposalStatus { PROPOSED, COMMITTED, STALE }

    /** The outcome of claiming a request ID. */
    public sealed interface Claim permits Started, Replay, Conflict, Busy { }
    /** This caller owns the request and must finish it with {@link #completeDaily} or {@link #release}. */
    public record Started(String ownerToken) implements Claim { }
    /** The request already finished; send the stored response again. */
    public record Replay(int status, String json) implements Claim { }
    /** The request ID was used with a different operation or body. */
    public record Conflict() implements Claim { }
    /** Another caller is still working on the request. */
    public record Busy() implements Claim { }

    /** A saved daily proposal and the host timestamps it was computed from. */
    public record StoredProposal(String id, String requestId, String metroId, LocalDate serviceDate, String inputRevision,
                                 String routingIdentity, ProposalStatus status, List<TechnicianDayVersion> technicianDays,
                                 PublicResponses.DailyProposal proposal) {
        public StoredProposal {
            technicianDays = Input.list(technicianDays, "technicianDays");
            if (technicianDays.isEmpty()) throw new IllegalArgumentException("A proposal covers at least one technician-day");
            Set<PublicTypes.Key> keys = new HashSet<>();
            for (TechnicianDayVersion day : technicianDays)
                if (!keys.add(day.key())) throw new IllegalArgumentException("Duplicate technician-day " + day.key());
            if (!id.equals(proposal.proposalId())) throw new IllegalArgumentException("Proposal ID differs from its body");
            if (!inputRevision.equals(proposal.inputRevision())) throw new IllegalArgumentException("Input revision differs from its body");
        }
    }

    private static final Pattern SHA256 = Required.value(Pattern.compile("[0-9a-f]{64}"));
    /** Long enough for routing plus the 20 second daily solve; an abandoned claim can be taken over after it. */
    static final Duration LEASE = Required.value(Duration.ofSeconds(60));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PublicApiStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions);
    }

    private interface Work<T extends Object> { T run(); }

    private <T extends Object> T asTenant(String tenantId, Work<T> work) {
        return Required.value(transactions.execute(_ -> {
            DatabaseFacts.query(jdbc, "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
            jdbc.execute("SET LOCAL ROLE scheduler_tenant");
            return work.run();
        }));
    }

    public static String newProposalId() { return "prop-" + UUID.randomUUID(); }

    public Claim claim(String tenantId, String requestId, Operation operation, String requestSha256) {
        Input.requestId(requestId);
        if (!SHA256.matcher(requestSha256).matches()) throw new IllegalArgumentException("requestSha256 must be a SHA-256 hex digest");
        String owner = Required.value(UUID.randomUUID().toString());
        return asTenant(tenantId, () -> {
            int inserted = jdbc.update("INSERT INTO api_request (\"tenantId\",\"requestId\",operation,\"requestSha256\",state,\"ownerToken\",\"leaseExpiresAt\") "
                    + "VALUES (?,?,?,?,'IN_PROGRESS',?,clock_timestamp()+?::interval) ON CONFLICT DO NOTHING",
                    tenantId, requestId, operation.name(), requestSha256, owner, LEASE.toSeconds() + " seconds");
            if (inserted == 1) return new Started(owner);
            List<Claim> existing = jdbc.query("SELECT operation,\"requestSha256\",state,\"responseStatus\",\"responseJson\"::text,\"leaseExpiresAt\"<clock_timestamp() "
                    + "FROM api_request WHERE \"tenantId\"=? AND \"requestId\"=? FOR UPDATE", (rs, _) -> {
                if (!operation.name().equals(DatabaseFacts.string(rs, 1)) || !requestSha256.equals(DatabaseFacts.string(rs, 2))) return new Conflict();
                if ("COMPLETED".equals(DatabaseFacts.string(rs, 3))) return new Replay(DatabaseFacts.integer(rs, 4), DatabaseFacts.string(rs, 5));
                if (!DatabaseFacts.bool(rs, 6)) return new Busy();
                return new Started(owner);
            }, tenantId, requestId);
            if (existing.size() != 1) throw new IllegalStateException("Request claim disappeared");
            Claim found = Required.value(existing.getFirst());
            if (found instanceof Started)
                jdbc.update("UPDATE api_request SET \"ownerToken\"=?,\"leaseExpiresAt\"=clock_timestamp()+?::interval WHERE \"tenantId\"=? AND \"requestId\"=?",
                        owner, LEASE.toSeconds() + " seconds", tenantId, requestId);
            return found;
        });
    }

    /** Gives up a claim without a stored response, so a retry runs again (for example after routing was unavailable). */
    public void release(String tenantId, String requestId, String ownerToken) {
        asTenant(tenantId, () -> {
            jdbc.update("DELETE FROM api_request WHERE \"tenantId\"=? AND \"requestId\"=? AND \"ownerToken\"=? AND state='IN_PROGRESS'", tenantId, requestId, ownerToken);
            return Boolean.TRUE;
        });
    }

    /** Stores a final error response for a request that will fail the same way on every retry. */
    public void completeWithError(String tenantId, String requestId, String ownerToken, int status, PublicResponses.Problem problem) {
        if (status < 400 || status > 599) throw new IllegalArgumentException("Error status must be 4xx or 5xx");
        asTenant(tenantId, () -> { complete(tenantId, requestId, ownerToken, status, CalculationJson.write(problem)); return Boolean.TRUE; });
    }

    /** Saves the proposal and its technician-day timestamps and completes the request with it (201), atomically. */
    public void completeDaily(String tenantId, String ownerToken, StoredProposal stored) {
        asTenant(tenantId, () -> {
            if (stored.status() != ProposalStatus.PROPOSED) throw new IllegalArgumentException("A new proposal must be PROPOSED");
            String body = CalculationJson.write(stored.proposal());
            jdbc.update("INSERT INTO api_daily_proposal (\"tenantId\",id,\"requestId\",\"metroId\",\"serviceDate\",\"inputRevision\",\"routingIdentity\",status,\"proposalJson\") "
                    + "VALUES (?,?,?,?,?,?,?,'PROPOSED',?::jsonb)", tenantId, stored.id(), stored.requestId(), stored.metroId(),
                    Date.valueOf(stored.serviceDate()), stored.inputRevision(), stored.routingIdentity(), body);
            for (TechnicianDayVersion day : stored.technicianDays())
                jdbc.update("INSERT INTO api_proposal_technician_day (\"tenantId\",\"proposalId\",\"technicianId\",\"serviceDate\",\"lastModified\") VALUES (?,?,?,?,?)",
                        tenantId, stored.id(), day.technicianId(), Date.valueOf(day.serviceDate()), day.lastModified().toString());
            complete(tenantId, stored.requestId(), ownerToken, 201, body);
            return Boolean.TRUE;
        });
    }

    private void complete(String tenantId, String requestId, String ownerToken, int status, String json) {
        int updated = jdbc.update("UPDATE api_request SET state='COMPLETED',\"responseStatus\"=?,\"responseJson\"=?::jsonb,\"completedAt\"=clock_timestamp() "
                + "WHERE \"tenantId\"=? AND \"requestId\"=? AND \"ownerToken\"=? AND state='IN_PROGRESS'", status, json, tenantId, requestId, ownerToken);
        if (updated != 1) throw new IllegalStateException("The request claim was lost before completion");
    }

    private record ProposalRow(String id, String requestId, String metroId, LocalDate serviceDate, String inputRevision,
                               String routingIdentity, String status, String json) { }

    /** A proposal of this tenant, or null when it has none with that ID. Persisted JSON is validated again before use. */
    public @Nullable StoredProposal proposal(String tenantId, String proposalId) {
        return asTenant(tenantId, () -> {
            List<ProposalRow> rows = jdbc.query("SELECT id,\"requestId\",\"metroId\",\"serviceDate\",\"inputRevision\",\"routingIdentity\",status,\"proposalJson\"::text "
                    + "FROM api_daily_proposal WHERE \"tenantId\"=? AND id=?", (rs, _) -> new ProposalRow(DatabaseFacts.string(rs, 1), DatabaseFacts.string(rs, 2),
                    DatabaseFacts.string(rs, 3), day(rs, 4), DatabaseFacts.string(rs, 5), DatabaseFacts.string(rs, 6), DatabaseFacts.string(rs, 7), DatabaseFacts.string(rs, 8)),
                    tenantId, proposalId);
            if (rows.isEmpty()) return Found.NONE;
            ProposalRow row = Required.value(rows.getFirst());
            List<TechnicianDayVersion> days = jdbc.query("SELECT \"technicianId\",\"serviceDate\",\"lastModified\" FROM api_proposal_technician_day "
                    + "WHERE \"tenantId\"=? AND \"proposalId\"=? ORDER BY \"technicianId\",\"serviceDate\"",
                    (rs, _) -> new TechnicianDayVersion(DatabaseFacts.string(rs, 1), day(rs, 2), Required.value(Instant.parse(DatabaseFacts.string(rs, 3)))),
                    tenantId, row.id());
            return new Found(new StoredProposal(row.id(), row.requestId(), row.metroId(), row.serviceDate(), row.inputRevision(), row.routingIdentity(),
                    ProposalStatus.valueOf(row.status()), Input.list(days, "technicianDays"), PublicRequests.read(row.json(), PublicResponses.DailyProposal.class)));
        }).value();
    }

    private static LocalDate day(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        return Required.value(rs.getObject(column, LocalDate.class), "date column " + column);
    }

    /** Lets {@link #asTenant} carry an absent proposal, since the transaction result itself is never null. */
    private record Found(@Nullable StoredProposal value) {
        static final Found NONE = new Found(null);
    }
}
