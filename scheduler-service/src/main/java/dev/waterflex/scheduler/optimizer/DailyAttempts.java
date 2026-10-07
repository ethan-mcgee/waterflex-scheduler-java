package dev.waterflex.scheduler.optimizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SavedJson;
import dev.waterflex.scheduler.SearchDeadline;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Caller-owned durable claim. Every method runs in a short caller transaction. */
final class DailyAttempts {
    record Claim(String id, String key, String owner, @Nullable Map<String, Object> reused, @Nullable String rejection) { }
    private record Row(String id, String fingerprint, String owner, String state, @Nullable String result) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    DailyAttempts(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    static String fingerprint(String canonical) {
        try { return Required.value(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    Claim claim(@Nullable String suppliedKey, String fingerprint, Function<String, @Nullable Map<String, Object>> legacy) {
        return claim(suppliedKey, fingerprint, legacy, true);
    }
    Claim claim(@Nullable String suppliedKey, String fingerprint, Function<String, @Nullable Map<String, Object>> legacy, boolean allowNew) {
        String key = suppliedKey == null ? "daily:" + UUID.randomUUID() : suppliedKey;
        if (key.isBlank() || key.length() > 160) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid preview request key");
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "preview:" + key);
        jdbc.update("UPDATE daily_calculation_attempt SET state='ABANDONED', \"failureReason\"='OWNER_EXPIRED', \"updatedAt\"=clock_timestamp() WHERE \"requestKey\"=? AND state='CLAIMED' AND \"expiresAt\"<=clock_timestamp()", key);
        var rows = jdbc.query("SELECT id,\"requestFingerprint\",\"ownerToken\",state,\"resultJson\"::text FROM daily_calculation_attempt WHERE \"requestKey\"=?",
                (rs, _) -> new Row(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), dev.waterflex.scheduler.DatabaseFacts.string(rs, 2), dev.waterflex.scheduler.DatabaseFacts.string(rs, 3), dev.waterflex.scheduler.DatabaseFacts.string(rs, 4), rs.getString(5)), key);
        if (!rows.isEmpty()) {
            Row row = rows.getFirst();
            if (!fingerprint.equals(row.fingerprint())) return new Claim(row.id(), key, row.owner(), null, "Preview key belongs to different inputs");
            if (!row.state().equals("SUCCEEDED"))
                return new Claim(row.id(), key, row.owner(), null, "Daily attempt " + row.id() + " is " + row.state() + "; use a new key for a new attempt");
            return new Claim(row.id(), key, row.owner(), decode(Required.value(row.result(), "successful attempt result")), null);
        }
        Map<String, Object> prior = suppliedKey == null ? null : legacy.apply(key);
        if (prior == null && !allowNew) throw new ResponseStatusException(HttpStatus.CONFLICT, "Route is frozen after 6 a.m. local time");
        String id = Required.value(UUID.randomUUID().toString()), owner = Required.value(UUID.randomUUID().toString());
        long remaining = Math.max(1, Required.value(SearchDeadline.current(), "daily deadline").remainingNanos() / 1_000_000);
        jdbc.update("INSERT INTO daily_calculation_attempt (id,\"requestKey\",\"requestFingerprint\",\"ownerToken\",state,\"expiresAt\",\"resultRunId\",\"resultJson\") VALUES (?,?,?,?,?,clock_timestamp()+(? * interval '1 millisecond'),?,?::jsonb)",
                id, key, fingerprint, owner, prior == null ? "CLAIMED" : "SUCCEEDED", remaining,
                prior == null ? null : prior.get("run_id"), prior == null ? null : encode(prior));
        // A legacy result is imported now. The owner and expiry describe this import, never historical solving.
        return new Claim(id, key, owner, prior, null);
    }

    void bind(Claim claim, String revision, String routing) {
        owned(jdbc.update("UPDATE daily_calculation_attempt SET \"snapshotRevision\"=?,\"routingIdentity\"=?,\"updatedAt\"=clock_timestamp() WHERE id=? AND \"ownerToken\"=? AND state='CLAIMED' AND \"expiresAt\">clock_timestamp()", revision, routing, claim.id(), claim.owner()));
    }
    void complete(Claim claim, Map<String, Object> result) {
        owned(jdbc.update("UPDATE daily_calculation_attempt SET state='SUCCEEDED',\"resultRunId\"=?,\"resultJson\"=?::jsonb,\"updatedAt\"=clock_timestamp() WHERE id=? AND \"ownerToken\"=? AND state='CLAIMED' AND \"expiresAt\">clock_timestamp()", result.get("run_id"), encode(result), claim.id(), claim.owner()));
    }
    void verifyCommit(Claim claim) {
        owned(dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT count(*) FROM daily_calculation_attempt WHERE id=? AND \"ownerToken\"=? AND state='SUCCEEDED' AND \"expiresAt\">clock_timestamp()", Integer.class, claim.id(), claim.owner()));
    }
    void failed(Claim claim, String state, String reason) {
        if (!java.util.Set.of("FAILED", "CANCELLED", "STALE").contains(state)) throw new IllegalArgumentException("Invalid failure state");
        jdbc.update("UPDATE daily_calculation_attempt SET state=?,\"failureReason\"=?,\"updatedAt\"=clock_timestamp() WHERE id=? AND \"ownerToken\"=? AND state='CLAIMED'", state, reason, claim.id(), claim.owner());
    }
    private static void owned(int changed) {
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Daily attempt ownership or expiry changed");
    }
    private String encode(Map<String, Object> result) {
        try { return Required.value(mapper.writeValueAsString(result)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Cannot encode attempt result", failure); }
    }
    private Map<String, Object> decode(String json) {
        try {
            var node = SavedJson.object(Required.value(mapper.readTree(json)));
            String status = SavedJson.text(node, "status");
            if (!java.util.Set.of("PREVIEW", "REPAIR_PREVIEW", "SKIPPED", "NO_SHIFT", "APPLIED").contains(status)) throw SavedJson.invalid();
            if (node.has("run_id")) {
                SavedJson.text(node, "run_id"); SavedJson.text(node, "metro_id");
                java.time.LocalDate.parse(SavedJson.text(node, "service_date"));
                java.time.Instant.parse(SavedJson.text(node, "created_at"));
                if (node.hasNonNull("applied_at")) java.time.Instant.parse(SavedJson.text(node, "applied_at"));
                SavedJson.text(node, "solver_status");
                if (SavedJson.integer(node, "solve_ms") < 0) throw SavedJson.invalid();
                SavedJson.integer(node, "objective_improvement");
                SavedJson.summary(Required.value(node.path("route_summary_before")));
                SavedJson.summary(Required.value(node.path("route_summary_after")));
                SavedJson.array(Required.value(node.path("changes")));
                if (node.hasNonNull("policy_analysis")) SavedJson.policyAnalysis(Required.value(node.path("policy_analysis")));
            } else {
                java.time.LocalDate.parse(SavedJson.text(node, "serviceDate")); SavedJson.text(node, "reason");
            }
            if (node.hasNonNull("calculation_outcome")) SavedJson.dailyOutcome(Required.value(node.path("calculation_outcome")));
            if (node.hasNonNull("solver_analysis")) SavedJson.solverAnalysis(Required.value(node.path("solver_analysis")));
            return Required.value(mapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<@Nullable Map<String, Object>>() { }));
        } catch (ResponseStatusException failure) { throw failure; }
        catch (Exception failure) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid saved attempt result", failure); }
    }
}
