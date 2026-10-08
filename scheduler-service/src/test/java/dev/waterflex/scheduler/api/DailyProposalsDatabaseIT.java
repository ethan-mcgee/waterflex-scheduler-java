package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.CalculationTransport;
import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.api.PublicResponses.*;
import dev.waterflex.scheduler.optimizer.DailySolver;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** POST /api/v1/daily/proposals end to end below HTTP: strict parsing, claim, routing, solve, store and replay. */
class DailyProposalsDatabaseIT {
    private final String tenant = "daily-it-" + UUID.randomUUID().toString().substring(0, 8);
    private final JdbcTemplate jdbc;
    private final PublicApiStore store;
    private final AtomicInteger solves = new AtomicInteger();
    private final AtomicBoolean routingDown = new AtomicBoolean();
    private final DailySolver solver;
    private final RoadClient omaha;
    private final DataSourceTransactionManager manager;

    DailyProposalsDatabaseIT() {
        String url = Required.value(System.getenv("JDBC_DATABASE_URL"), "isolated integration database URL");
        assertEquals("/waterflex_test", URI.create(url.substring(5)).getPath());
        var source = new DriverManagerDataSource(url, "waterflex", "waterflex");
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        store = new PublicApiStore(jdbc, manager);
        jdbc.update("INSERT INTO tenant (id,name) VALUES (?,?)", tenant, "Daily IT");
        DailySolver actual = new DailySolver("TABU", 17);
        solver = mock(DailySolver.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("diagnostics")) return actual.diagnostics(Required.value(invocation.getArgument(0)));
            if (!method.equals("solve")) return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            solves.incrementAndGet();
            return actual.solve(Required.value(invocation.getArgument(0)), Required.value(Duration.ofMillis(200)));
        });
        omaha = mock(RoadClient.class, invocation -> {
            if (routingDown.get()) throw new RoadClient.RoadUnavailable("Injected routing outage");
            String method = invocation.getMethod().getName();
            if (method.equals("activeIdentity")) return "omaha-map-v7";
            if (!method.equals("matrix")) throw new AssertionError("Unexpected routing call " + method);
            Map<String, RoadPoint> points = Required.value(invocation.getArgument(0));
            Map<String, RoadClient.Leg> legs = new HashMap<>();
            for (String from : points.keySet()) for (String to : points.keySet())
                legs.put(from + ">" + to, from.equals(to) ? new RoadClient.Leg(0, 0) : new RoadClient.Leg(600, 8000));
            return legs;
        });
    }

    @AfterEach void clean() {
        for (String table : List.of("api_commit_receipt", "api_proposal_technician_day", "api_daily_proposal", "api_request"))
            jdbc.update("DELETE FROM " + table + " WHERE \"tenantId\"=?", tenant);
        jdbc.update("DELETE FROM tenant WHERE id=?", tenant);
    }

    private DailyProposals proposals(Instant now, Map<String, String> metros) {
        return new DailyProposals(store, new MetroRouting(metros, _ -> omaha), _ -> null, new CalculationTransport("EMBEDDED", "http://127.0.0.1:1", ""),
                solver, new SearchAdmission(2, 16), jdbc, Required.value(Clock.fixed(now, ZoneOffset.UTC)));
    }

    private static final Instant BEFORE_CUTOFF = Required.value(Instant.parse("2026-10-11T12:00:00Z"));

    private DailyProposals proposals() { return proposals(BEFORE_CUTOFF, Required.value(Map.of("omaha", "http://routing-omaha:8001"))); }

    /** The spec example with a fresh request ID and, unless kept as an address, coordinates for appt-8. */
    private static String request(boolean appointmentAddressOnly) {
        ObjectNode request = object(CalculationJson.tree(PublicApiContractTest.example("DailyProposalRequest")));
        request.put("requestId", UUID.randomUUID().toString());
        if (!appointmentAddressOnly) {
            ArrayNode appointments = (ArrayNode) Required.value(object(request.get("snapshot")).get("appointments"));
            object(appointments.get(1)).putObject("location").put("lat", 41.2587).put("lng", -95.9378);
        }
        return CalculationJson.write(request);
    }

    private static ObjectNode object(@Nullable JsonNode node) {
        assertInstanceOf(ObjectNode.class, node);
        return (ObjectNode) Required.value(node);
    }

    private static DailyProposal proposal(DailyProposals.Reply reply) {
        assertEquals(201, reply.status(), reply.json());
        return PublicRequests.read(reply.json(), DailyProposal.class);
    }

    private static Problem problem(DailyProposals.Reply reply, int status, ErrorCode code) {
        assertEquals(status, reply.status(), reply.json());
        Problem problem = PublicRequests.read(reply.json(), Problem.class);
        assertEquals(code, problem.error());
        return problem;
    }

    @Test void aProposalIsComputedStoredAndReplayedWithoutSolvingAgain() {
        String body = request(false);
        var reply = proposals().create(tenant, body);
        DailyProposal proposal = proposal(reply);
        assertEquals(0, proposal.overtimeMinutes());
        assertEquals(List.of(), proposal.skippedTechnicianDays());
        assertEquals(List.of(), proposal.unresolvedAppointmentIds());
        assertEquals(2, proposal.routes().stream().mapToInt(route -> Required.value(route).stops().size()).sum());
        assertTrue(solves.get() > 0);
        assertEquals(DailyProposals.sha256(CalculationJson.write(PublicRequests.read(body, PublicRequests.DailyProposalRequest.class).snapshot())), proposal.inputRevision());
        var stored = Required.value(store.proposal(tenant, proposal.proposalId()));
        assertEquals(proposal, stored.proposal());
        assertEquals("omaha-map-v7", stored.routingIdentity());
        assertEquals("America/Chicago", stored.timeZone());
        assertEquals(List.of("tech-1", "tech-2"), stored.technicianDays().stream().map(day -> Required.value(day).technicianId()).toList());
        assertEquals(Instant.parse("2026-10-11T21:04:17.123456Z"), stored.technicianDays().getFirst().lastModified());
        int solved = solves.get();
        var replay = proposals().create(tenant, body);
        assertEquals(new DailyProposals.Reply(201, reply.json(), null), replay);
        assertEquals(solved, solves.get());
    }

    @Test void anAddressOnlyAppointmentLeavesOnlyItsTechnicianDayUnchanged() {
        DailyProposal proposal = proposal(proposals().create(tenant, request(true)));
        assertEquals(1, proposal.skippedTechnicianDays().size());
        SkippedTechnicianDay skipped = Required.value(proposal.skippedTechnicianDays().getFirst());
        assertEquals("tech-1", skipped.technicianId());
        assertEquals(SkipReason.LOCATION_UNRESOLVED, skipped.reason());
        assertEquals(List.of("tech-2"), proposal.routes().stream().map(route -> Required.value(route).technicianId()).toList());
        assertEquals(Decision.NO_IMPROVEMENT, proposal.decision());
    }

    @Test void badRequestsAreRejectedAndARequestIdCannotBeReusedForDifferentFacts() {
        problem(proposals().create(tenant, "{\"requestId\":"), 400, ErrorCode.INVALID_REQUEST);
        problem(proposals().create(tenant, Required.value(request(false).replace("\"travelBufferPct\":\"0.2\"", "\"travelBufferPct\":0.2"))), 400, ErrorCode.INVALID_REQUEST);
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", Long.class, tenant));
        String body = request(false);
        proposal(proposals().create(tenant, body));
        String changed = Required.value(body.replace("\"maxPaidMinutes\":540", "\"maxPaidMinutes\":541"));
        assertNotEquals(body, changed);
        problem(proposals().create(tenant, changed), 400, ErrorCode.INVALID_REQUEST);
    }

    @Test void finalFailuresAreStoredAndReplayed() {
        String unknownMetro = request(false);
        var reply = proposals(BEFORE_CUTOFF, Required.value(Map.of("lincoln", "http://routing-lincoln:8001"))).create(tenant, unknownMetro);
        assertTrue(problem(reply, 422, ErrorCode.INCOMPLETE_FACTS).message().contains("omaha"));
        assertEquals(reply, proposals().create(tenant, unknownMetro), "the stored failure is replayed even once the metro is configured");
        var frozen = proposals(Required.value(Instant.parse("2026-10-12T11:00:00Z")), Required.value(Map.of("omaha", "http://routing-omaha:8001"))).create(tenant, request(false));
        assertTrue(problem(frozen, 422, ErrorCode.INCOMPLETE_FACTS).message().contains("frozen"));
        assertEquals(0, solves.get());
    }

    @Test void aTamperedStoredResponseIsNotReplayed() {
        String body = request(false);
        proposal(proposals().create(tenant, body));
        jdbc.update("UPDATE api_request SET \"responseJson\"=jsonb_set(\"responseJson\",'{overtimeMinutes}','45') WHERE \"tenantId\"=?", tenant);
        assertThrows(IllegalStateException.class, () -> proposals().create(tenant, body));
    }

    @Test void aRoutingOutageReleasesTheClaimSoTheRetryRuns() {
        String body = request(false);
        routingDown.set(true);
        problem(proposals().create(tenant, body), 503, ErrorCode.ROUTING_UNAVAILABLE);
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", Long.class, tenant));
        routingDown.set(false);
        proposal(proposals().create(tenant, body));
    }
}
