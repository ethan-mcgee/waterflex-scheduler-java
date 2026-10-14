package dev.waterflex.scheduler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The schema the migrations build is the one the scheduler's code reads: the columns its queries name, tenant
 * isolation on every thin store table, and exactly the grants the two runtime roles need. A migration that drops a
 * read column, a policy or a grant, or widens a grant, fails here.
 */
class SchemaContractIT {
    private final JdbcTemplate jdbc = new JdbcTemplate(TestDatabase.owner());

    /** Tables and the columns Java reads or writes by name. */
    private static final Map<String, List<String>> READ = Required.value(Map.ofEntries(
            Map.entry("tenant", List.of("id", "name", "disabledAt")),
            Map.entry("tenant_api_token", List.of("id", "tenantId", "tokenSha256", "label", "revokedAt")),
            Map.entry("api_request", List.of("tenantId", "requestId", "operation", "requestSha256", "state", "ownerToken", "leaseExpiresAt", "responseStatus", "responseJson", "createdAt", "completedAt", "cancelRequestedAt")),
            Map.entry("api_daily_proposal", List.of("tenantId", "id", "requestId", "metroId", "timeZone", "serviceDate", "inputRevision", "routingIdentity", "status", "proposalJson", "createdAt", "committedAt")),
            Map.entry("api_proposal_technician_day", List.of("tenantId", "proposalId", "technicianId", "serviceDate", "lastModified")),
            Map.entry("api_commit_receipt", List.of("tenantId", "id", "proposalId", "requestId", "receiptJson", "createdAt")),
            Map.entry("api_booking_day", List.of("tenantId", "metroId", "serviceDate", "version", "stateJson", "updatedAt")),
            Map.entry("api_booking_offer_set", List.of("tenantId", "id", "requestId", "jobId", "metroId", "expiresAt", "status", "offerSetJson", "createdAt")),
            Map.entry("api_booking_offer", List.of("tenantId", "id", "offerSetId", "holdId", "serviceDate", "technicianId", "windowStart", "windowEnd", "status")),
            Map.entry("api_booking_receipt", List.of("tenantId", "id", "offerId", "requestId", "receiptJson", "createdAt")),
            Map.entry("api_daily_job", List.of("tenantId", "requestId", "operation", "requestJson", "ownerToken", "state", "leaseSeconds", "queuedAt", "notBefore",
                    "startedAt", "leaseExpiresAt", "worker", "attempts", "cancelRequested", "finishedAt", "resultStatus", "resultJson", "retryAfterSeconds")),
            Map.entry("api_tenant_turn", List.of("tenantId", "lastStartedAt")),
            Map.entry("road_route_cache", List.of("id", "originKey", "destinationKey", "profile", "mapVersion", "seconds", "meters", "routable", "fetchedAt"))));

    private static final Set<String> THIN_STORE = Required.value(Set.of("api_request", "api_daily_proposal", "api_proposal_technician_day", "api_commit_receipt",
            "api_booking_day", "api_booking_offer_set", "api_booking_offer", "api_booking_receipt", "api_daily_job"));

    @Test void everyTableAndColumnJavaReadsExists() {
        for (var table : READ.entrySet()) {
            List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ?",
                    String.class, table.getKey());
            assertTrue(columns.containsAll(table.getValue()), table.getKey() + " has " + columns + ", Java reads " + table.getValue());
        }
        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'", String.class);
        assertEquals(new TreeSet<>(READ.keySet()), new TreeSet<>(tables.stream().filter(name -> !"flyway_schema_history".equals(name)).toList()),
                "the scheduler's database holds only the scheduler's tables");
    }

    @Test void everyThinStoreTableIsIsolatedByTenant() {
        for (String table : THIN_STORE) {
            assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid = to_regclass(?)", Boolean.class, table), table + " has row-level security");
            assertEquals(1, Required.value(jdbc.queryForObject("""
                    SELECT count(*)::int FROM pg_policies WHERE schemaname = current_schema() AND tablename = ? AND policyname = 'tenant_isolation'
                      AND qual LIKE '%app.tenant_id%' AND with_check LIKE '%app.tenant_id%'""", Integer.class, table)), table + " has the tenant policy");
        }
    }

    private Map<String, String> grants(String role) {
        Map<String, String> grants = new TreeMap<>();
        for (var row : jdbc.queryForList("""
                SELECT table_name, string_agg(privilege_type, ',' ORDER BY privilege_type) AS privileges FROM information_schema.role_table_grants
                WHERE table_schema = current_schema() AND grantee = ? GROUP BY table_name""", role))
            grants.put(String.valueOf(row.get("table_name")), String.valueOf(row.get("privileges")));
        return grants;
    }

    @Test void theRuntimeRolesHoldExactlyTheirGrants() {
        assertEquals(Map.of(
                "api_request", "DELETE,INSERT,SELECT,UPDATE",
                "api_daily_proposal", "INSERT,SELECT,UPDATE",
                "api_proposal_technician_day", "INSERT,SELECT",
                "api_commit_receipt", "INSERT,SELECT",
                "api_booking_day", "INSERT,SELECT,UPDATE",
                "api_booking_offer_set", "INSERT,SELECT,UPDATE",
                "api_booking_offer", "INSERT,SELECT,UPDATE",
                "api_booking_receipt", "INSERT,SELECT",
                "api_daily_job", "INSERT,SELECT,UPDATE"), grants("scheduler_tenant"));
        assertEquals(Map.of(
                "tenant", "SELECT",
                "tenant_api_token", "SELECT",
                "road_route_cache", "DELETE,INSERT,SELECT,UPDATE",
                "flyway_schema_history", "SELECT"), grants("scheduler_app"));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT NOT rolinherit AND NOT rolcanlogin AND NOT rolbypassrls FROM pg_roles WHERE rolname = 'scheduler_app'", Boolean.class),
                "scheduler_app is NOINHERIT, NOLOGIN and subject to row-level security");
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT pg_has_role('scheduler_app', 'scheduler_tenant', 'MEMBER')", Boolean.class));
    }

    @Test void thePurgeAndTheQueueAreDefinerFunctionsWithAPinnedSearchPathOnlySchedulerAppMayRun() {
        for (String function : List.of("scheduler_purge(timestamptz, integer)", "scheduler_next_daily_job(text, interval)", "scheduler_cancel_requested(text[], text[])")) {
            assertEquals(Boolean.TRUE, jdbc.queryForObject("""
                    SELECT p.prosecdef AND array_to_string(p.proconfig, ',') LIKE 'search_path=%'
                    FROM pg_proc p WHERE p.oid = to_regprocedure(?)""", Boolean.class, function), function);
            assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT has_function_privilege('scheduler_app', ?, 'EXECUTE')", Boolean.class, function), function);
            assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT has_function_privilege('scheduler_tenant', ?, 'EXECUTE')", Boolean.class, function), function);
        }
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid = to_regclass('api_tenant_turn')", Boolean.class),
                "the turn table is read only through the queue function, so no runtime role is granted it");
    }
}
