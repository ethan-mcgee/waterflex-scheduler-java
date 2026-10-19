package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.TestDatabase;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.CalculationTransport;
import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.api.PublicResponses.*;
import dev.waterflex.scheduler.optimizer.DailySearch;
import dev.waterflex.scheduler.optimizer.DailySolver;
import dev.waterflex.scheduler.optimizer.DailySolvers;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** POST /api/v1/daily/proposals and /repairs/proposals end to end below HTTP: strict parsing, claim, routing, solve, store and replay. */
class DailyProposalsDatabaseIT {
    private final String tenant = "daily-it-" + UUID.randomUUID().toString().substring(0, 8);
    private final JdbcTemplate jdbc;
    private final PublicApiStore store;
    private final RunningRequests running = new RunningRequests();
    private final AtomicInteger solves = new AtomicInteger();
    private final AtomicBoolean routingDown = new AtomicBoolean();
    /** When set, a solve waits on its deadline until the operation is cancelled, as a long search would. */
    private final AtomicBoolean slow = new AtomicBoolean();
    private final java.util.concurrent.CountDownLatch solving = new java.util.concurrent.CountDownLatch(1);
    private final DailySolvers solvers;
    private final List<DailySearch> searched = Required.value(java.util.Collections.synchronizedList(new java.util.ArrayList<>()));
    private final RoadClient omaha;
    private final DataSourceTransactionManager manager;
    private final SearchAdmission admission = new SearchAdmission(2, 16);
    private final DailyJobs jobs;
    /** One worker, a 15 minute queue wait and at most three jobs per tenant. */
    private final DailyQueue queue = new DailyQueue(1, Required.value(Duration.ofMillis(100)), Required.value(Duration.ofMinutes(15)), 3);
    /** A second tenant, created by the tests that need one. */
    private final String other = "daily-it-" + UUID.randomUUID().toString().substring(0, 8);
    /** Calculations admitted while the day was being routed, seen by each routing call. */
    private final List<Integer> activeWhileRouting = Required.value(java.util.Collections.synchronizedList(new java.util.ArrayList<>()));

    DailyProposalsDatabaseIT() {
        var source = TestDatabase.owner();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        store = new PublicApiStore(jdbc, manager);
        jobs = new DailyJobs(store, jdbc);
        jdbc.update("INSERT INTO tenant (id,name) VALUES (?,?)", tenant, "Daily IT");
        // Each client search keeps its own solver; every phase is shortened to 200 ms and counted.
        Map<DailySearch, DailySolver> shortened = new java.util.EnumMap<>(DailySearch.class);
        for (DailySearch search : DailySearch.values()) {
            DailySolver actual = new DailySolver(Required.value(search.variant().name()), 17);
            shortened.put(search, mock(DailySolver.class, invocation -> {
                String method = invocation.getMethod().getName();
                if (method.equals("diagnostics")) return actual.diagnostics(Required.value(invocation.getArgument(0)));
                if (!method.equals("solve")) return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
                solves.incrementAndGet();
                if (slow.get()) {
                    solving.countDown();
                    while (true) { dev.waterflex.scheduler.SearchDeadline.checkpoint(); Thread.sleep(10); }
                }
                return actual.solve(Required.value(invocation.getArgument(0)), Required.value(Duration.ofMillis(200)));
            }));
        }
        solvers = mock(DailySolvers.class, invocation -> {
            if (!invocation.getMethod().getName().equals("get")) return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            DailySearch search = Required.value(invocation.getArgument(0));
            searched.add(search);
            return shortened.get(search);
        });
        omaha = mock(RoadClient.class, invocation -> {
            if (routingDown.get()) throw new RoadClient.RoadUnavailable("Injected routing outage");
            String method = invocation.getMethod().getName();
            if (method.equals("activeIdentity")) return "omaha-map-v7";
            if (!method.equals("matrix")) throw new AssertionError("Unexpected routing call " + method);
            activeWhileRouting.add(admission.state().active());
            Map<String, RoadPoint> points = Required.value(invocation.getArgument(0));
            Map<String, RoadClient.Leg> legs = new HashMap<>();
            for (String from : points.keySet()) for (String to : points.keySet())
                legs.put(from + ">" + to, from.equals(to) ? new RoadClient.Leg(0, 0) : new RoadClient.Leg(600, 8000));
            return legs;
        });
    }

    @AfterEach void clean() {
        for (String id : List.of(tenant, other)) {
            for (String table : List.of("api_daily_job", "api_tenant_turn", "api_commit_receipt", "api_proposal_technician_day", "api_daily_proposal", "api_request"))
                jdbc.update("DELETE FROM " + table + " WHERE \"tenantId\"=?", id);
            jdbc.update("DELETE FROM tenant WHERE id=?", id);
        }
    }

    private DailyProposals proposals(Instant now, Map<String, String> metros) {
        return new DailyProposals(store, new MetroRouting(metros, _ -> omaha), _ -> null, new CalculationTransport("EMBEDDED", "http://127.0.0.1:1", ""),
                solvers, admission, running, jobs, queue, Required.value(Clock.fixed(now, ZoneOffset.UTC)));
    }

    private static final Instant BEFORE_CUTOFF = Required.value(Instant.parse("2026-10-11T12:00:00Z"));

    private DailyProposals proposals() { return proposals(BEFORE_CUTOFF, Required.value(Map.of("omaha", "http://routing-omaha:8001"))); }

    /** The spec example with a fresh request ID and, unless kept as an address, coordinates for appt-8. */
    private static String request(boolean appointmentAddressOnly) {
        return request(appointmentAddressOnly, _ -> { });
    }

    /** The spec example with coordinates for appt-8 unless kept as an address, and the policy changed by the caller. */
    private static String request(boolean appointmentAddressOnly, java.util.function.Consumer<ObjectNode> policy) {
        ObjectNode request = object(CalculationJson.tree(PublicApiContractTest.example("DailyProposalRequest")));
        policy.accept(object(object(request.get("snapshot")).get("policy")));
        request.put("requestId", UUID.randomUUID().toString());
        if (!appointmentAddressOnly) {
            ArrayNode appointments = (ArrayNode) Required.value(object(request.get("snapshot")).get("appointments"));
            object(appointments.get(1)).putObject("location").put("lat", 41.2587).put("lng", -95.9378);
        }
        return CalculationJson.write(request);
    }

    /** The spec's repair example (tech-1 absent all day) with a fresh request ID, coordinates for appt-8, and any change applied. */
    private static String repair(java.util.function.Consumer<ObjectNode> change) {
        ObjectNode request = object(CalculationJson.tree(PublicApiContractTest.example("RepairProposalRequest")));
        request.put("requestId", UUID.randomUUID().toString());
        ArrayNode appointments = (ArrayNode) Required.value(object(request.get("snapshot")).get("appointments"));
        object(appointments.get(1)).putObject("location").put("lat", 41.2587).put("lng", -95.9378);
        change.accept(request);
        return CalculationJson.write(request);
    }

    private static String repair() { return repair(_ -> { }); }

    /** A lambda parameter carries no nullness annotation, so this accepts one and checks it. */
    private static ObjectNode snapshot(@Nullable ObjectNode request) { return object(Required.value(request).get("snapshot")); }

    private static List<String> stopsOf(DailyProposal proposal, String technician) {
        for (PlannedRoute route : proposal.routes())
            if (route.technicianId().equals(technician)) return Required.value(route.stops().stream().map(stop -> Required.value(stop).appointmentId()).toList());
        return Required.value(List.of());
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

    @Test void aRepairMovesTheAbsentTechniciansAppointmentsAndCommitsLikeADailyProposal() {
        String body = repair();
        var reply = proposals().repair(tenant, body);
        DailyProposal proposal = proposal(reply);
        assertEquals(Decision.IMPROVED, proposal.decision(), proposal.reason());
        assertEquals(List.of(), stopsOf(proposal, "tech-1"), "the absent technician keeps nothing");
        assertEquals(java.util.Set.of("appt-7", "appt-8"), java.util.Set.copyOf(stopsOf(proposal, "tech-2")));
        assertEquals(0, proposal.overtimeMinutes());
        assertEquals(List.of(), proposal.unresolvedAppointmentIds());
        var request = PublicRequests.read(body, PublicRequests.RepairProposalRequest.class);
        assertEquals(DailyProposals.revision(request.snapshot(), request.absence()), proposal.inputRevision());
        assertNotEquals(DailyProposals.revision(request.snapshot(), null), proposal.inputRevision(), "the absence is part of the input");
        int solved = solves.get();
        assertEquals(reply, proposals().repair(tenant, body), "the same requestId replays without solving again");
        assertEquals(solved, solves.get());
        var commit = new DailyCommits(store, Required.value(Clock.fixed(BEFORE_CUTOFF, ZoneOffset.UTC))).commit(tenant, proposal.proposalId(),
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"technicianDays\":[{\"technicianId\":\"tech-1\",\"serviceDate\":\"2026-10-12\",\"lastModified\":\"2026-10-11T21:04:17.123456Z\"},"
                        + "{\"technicianId\":\"tech-2\",\"serviceDate\":\"2026-10-12\",\"lastModified\":\"2026-10-10T16:30:00Z\"}]}");
        assertEquals(200, commit.status(), commit.json());
        CommitReceipt receipt = PublicRequests.read(commit.json(), CommitReceipt.class);
        assertTrue(receipt.assignments().stream().allMatch(assignment -> Required.value(assignment).technicianId().equals("tech-2")));
    }

    @Test void aRepairThatCannotPlaceEveryAppointmentWithoutOvertimeProposesNothing() {
        // Only tech-1 does softener service, so nobody can take its appointments.
        DailyProposal proposal = proposal(proposals().repair(tenant, repair(request -> {
            for (JsonNode technician : Required.value(snapshot(request).get("technicians")))
                if (Required.value(technician).path("id").asText().equals("tech-2")) object(technician).putArray("qualifications").add("softener-install");
        })));
        assertNotEquals(Decision.IMPROVED, proposal.decision());
        assertEquals("Repair infeasible", proposal.reason());
        assertEquals(List.of("appt-7", "appt-8"), stopsOf(proposal, "tech-1"), "the current routes are reported unchanged");
        var commit = new DailyCommits(store, Required.value(Clock.fixed(BEFORE_CUTOFF, ZoneOffset.UTC))).commit(tenant, proposal.proposalId(),
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"technicianDays\":[{\"technicianId\":\"tech-1\",\"serviceDate\":\"2026-10-12\",\"lastModified\":\"2026-10-11T21:04:17.123456Z\"},"
                        + "{\"technicianId\":\"tech-2\",\"serviceDate\":\"2026-10-12\",\"lastModified\":\"2026-10-10T16:30:00Z\"}]}");
        problem(commit, 409, ErrorCode.NOT_COMMITTABLE);
    }

    @Test void aRepairIsRefusedForAnUnlocatedDayAndBadRequestsClaimNothing() {
        String addressOnly = repair(request -> object(((ArrayNode) Required.value(snapshot(request).get("appointments"))).get(1)).putObject("location")
                .putObject("address").put("line1", "1200 Example St").put("city", "Omaha").put("state", "NE").put("postalCode", "68102"));
        var unlocated = proposals().repair(tenant, addressOnly);
        assertTrue(problem(unlocated, 422, ErrorCode.INCOMPLETE_FACTS).message().contains("cannot be repaired"));
        assertEquals(0, solves.get());

        long requests = Required.value(jdbc.queryForObject("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", Long.class, tenant));
        problem(proposals().repair(tenant, repair(request -> object(request.get("absence")).put("technicianId", "tech-9"))), 400, ErrorCode.INVALID_REQUEST);
        problem(proposals().repair(tenant, repair(request -> object(request.get("absence")).put("serviceDate", "2026-10-13"))), 400, ErrorCode.INVALID_REQUEST);
        problem(proposals().repair(tenant, repair(request -> object(object(request.get("absence")).get("window")).put("end", "2026-10-12T07:00:00-05:00"))),
                400, ErrorCode.INVALID_REQUEST);
        problem(proposals().repair(tenant, repair(request -> object(request.get("absence")).put("reason", "sick"))), 400, ErrorCode.INVALID_REQUEST);
        assertEquals(requests, jdbc.queryForObject("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", Long.class, tenant), "a malformed repair claims nothing");

        String daily = request(false);
        String reused = repair(request -> request.put("requestId", Required.value(object(CalculationJson.tree(daily)).path("requestId").asText())));
        proposal(proposals().create(tenant, daily));
        problem(proposals().repair(tenant, reused), 400, ErrorCode.INVALID_REQUEST);
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
    @Test void anUnavailableRemoteSolverAnswers503AndReleasesTheClaimSoTheRetryRuns() {
        String body = request(false);
        // The remote solver refuses the work, as it answers 503 when it cannot take it.
        var unavailable = org.mockito.Mockito.mock(CalculationTransport.class,
                _ -> { throw new dev.waterflex.scheduler.HttpCalculation.Unavailable("Solver HTTP status 503", null); });
        var remote = new DailyProposals(store, new MetroRouting(Required.value(Map.of("omaha", "http://routing-omaha:8001")), _ -> omaha), _ -> null,
                Required.value(unavailable),
                solvers, admission, running, jobs, queue, Required.value(Clock.fixed(BEFORE_CUTOFF, ZoneOffset.UTC)));
        problem(remote.create(tenant, body), 503, ErrorCode.CALCULATION_UNAVAILABLE);
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", Long.class, tenant), "the claim was released");
        proposal(proposals().create(tenant, body));
    }

    @Test void theDayIsRoutedBeforeTheCalculationTakesASlot() {
        proposal(proposals().create(tenant, request(false)));
        assertFalse(activeWhileRouting.isEmpty(), "the day was routed");
        assertEquals(List.of(0), List.copyOf(new java.util.TreeSet<>(activeWhileRouting)), "no slot was held while routing");
    }

    @Test void aProposalThatCouldNotStartInTimeIsRefusedAtOnceWithRetryAfterAndRoutesNothing() {
        // Another tenant's daily calculation holds the background slot and expects 60 more seconds.
        try (var ahead = admission.acquire(SearchAdmission.Kind.BACKGROUND,
                new dev.waterflex.scheduler.SearchDeadline(Required.value(Duration.ofSeconds(90))), Required.value(Duration.ofSeconds(60)))) {
            assertTrue(ahead.queueMillis() >= 0);
            long started = System.nanoTime();
            var reply = proposals().create(tenant, request(false));
            assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(2), "refused without waiting");
            problem(reply, 429, ErrorCode.BUSY);
            Integer retryAfter = Required.value(reply.retryAfterSeconds(), "Retry-After");
            assertTrue(retryAfter > 50 && retryAfter <= 60, "Retry-After " + retryAfter);
            assertTrue(activeWhileRouting.isEmpty(), "nothing was routed for a refused proposal");
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", Long.class, tenant), "the claim was released");
            assertEquals(1, admission.rejections());
        }
        proposal(proposals().create(tenant, request(false)));
    }

    @Test void eachProposalRunsTheClientsChosenSearchMethod() {
        proposal(proposals().create(tenant, request(false, policy -> policy.put("dailySearch", "TABU"))));
        assertEquals(List.of(DailySearch.TABU), List.copyOf(searched));
        searched.clear();
        proposal(proposals().create(tenant, request(false, policy -> policy.put("dailySearch", "TABU_KOPT"))));
        assertEquals(List.of(DailySearch.TABU_KOPT), List.copyOf(searched));
    }

    @Test void aZeroFairnessTimeSearchesOnlyForCostAndAFairnessTimeAddsTheFairnessPhase() {
        proposal(proposals().create(tenant, request(false, policy -> policy.put("costSearchSeconds", 5).put("fairnessSearchSeconds", 0))));
        assertEquals(1, solves.get());
        solves.set(0);
        proposal(proposals().create(tenant, request(false, policy -> policy.put("costSearchSeconds", 5).put("fairnessSearchSeconds", 5))));
        assertEquals(2, solves.get());
    }

    @Test void aLongSearchClaimsTheRequestForItsWholeAllowance() {
        // A 240 second search must not let another replica take the request over after the standard 60 second lease.
        String body = request(false, policy -> policy.put("costSearchSeconds", 240).put("fairnessSearchSeconds", 0));
        var request = PublicRequests.read(body, PublicRequests.DailyProposalRequest.class);
        var claim = store.claim(tenant, request.requestId(), PublicApiStore.Operation.DAILY_PROPOSAL, DailyProposals.sha256(body), DailyProposals.lease(request.snapshot()));
        assertInstanceOf(PublicApiStore.Started.class, claim);
        Long seconds = jdbc.queryForObject("SELECT extract(epoch FROM \"leaseExpiresAt\"-clock_timestamp())::bigint FROM api_request WHERE \"tenantId\"=? AND \"requestId\"=?",
                Long.class, tenant, request.requestId());
        assertTrue(Required.value(seconds) > 270 + DailyProposals.ROUTING_ALLOWANCE.toSeconds(), "lease seconds " + seconds);
        assertThrows(IllegalArgumentException.class, () -> store.claim(tenant, Required.value(UUID.randomUUID().toString()), PublicApiStore.Operation.DAILY_PROPOSAL,
                DailyProposals.sha256(body), Required.value(Duration.ofSeconds(30))));
    }

    @Test void aHostCancelEndsARunningProposalWithCancelledAndTheSameRequestRunsAgain() throws Exception {
        slow.set(true);
        String body = request(false, policy -> policy.put("costSearchSeconds", 60).put("fairnessSearchSeconds", 0));
        String requestId = PublicRequests.read(body, PublicRequests.DailyProposalRequest.class).requestId();
        var cancellation = new RequestCancellation(running);
        var reply = java.util.concurrent.CompletableFuture.supplyAsync(() -> proposals().create(tenant, body));
        assertTrue(solving.await(10, java.util.concurrent.TimeUnit.SECONDS), "the search started");
        long cancelled = System.nanoTime();
        assertNull(cancellation.cancel(tenant, requestId), "the running request is found and cancelled");
        problem(Required.value(reply.get(10, java.util.concurrent.TimeUnit.SECONDS)), 409, ErrorCode.CANCELLED);
        assertTrue(System.nanoTime() - cancelled < Duration.ofSeconds(5).toNanos(), "a cancel ends the 60 second search promptly");
        assertEquals(0, running.size(), "the finished request leaves the registry");
        assertEquals(404, Required.value(cancellation.cancel(tenant, requestId)).status(), "nothing is running any more");
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", Long.class, tenant), "the claim was released");
        slow.set(false);
        proposal(proposals().create(tenant, body));
    }

    // Asynchronous proposals (spec 1.4.0): Prefer: respond-async, the durable queue, GET by requestId.

    private DailyQueueWorker worker(DailyProposals proposals, RunningRequests registry) {
        return new DailyQueueWorker(jobs, proposals, queue, admission, registry);
    }

    private static String requestId(String body) { return PublicRequests.read(body, PublicRequests.DailyProposalRequest.class).requestId(); }

    private static JsonNode status(DailyProposals.Reply reply, int httpStatus, String state) {
        assertEquals(httpStatus, reply.status(), reply.json());
        JsonNode status = CalculationJson.tree(reply.json());
        assertEquals(state, status.path("state").asText(), reply.json());
        return status;
    }

    private long jobRows(String id) {
        return Required.value(jdbc.queryForObject("SELECT count(*) FROM api_daily_job WHERE \"tenantId\"=? AND state<>'DONE'", Long.class, id));
    }

    @Test void aQueuedProposalAnswers202AndAnyWorkerStoresTheAnswerTheSynchronousCallReplays() {
        String body = request(false);
        String id = requestId(body);
        var accepted = proposals().createAsync(tenant, body);
        JsonNode queued = status(accepted, 202, "QUEUED");
        assertEquals("/api/v1/requests/" + id, accepted.location());
        assertEquals(DailyProposals.POLL_AFTER_SECONDS, accepted.retryAfterSeconds());
        assertEquals("DAILY_PROPOSAL", queued.path("operation").asText());
        assertTrue(queued.has("queuedAt") && !queued.has("startedAt") && !queued.has("result"), accepted.json());
        assertEquals(0, solves.get(), "nothing is calculated within the call");
        status(proposals().describe(tenant, id), 200, "QUEUED");
        status(proposals().createAsync(tenant, body), 202, "QUEUED");
        problem(proposals().create(tenant, body), 429, ErrorCode.BUSY);

        assertTrue(worker(proposals(), running).runOnce("it-worker"));
        var done = proposals().describe(tenant, id);
        JsonNode completed = status(done, 200, "COMPLETED");
        assertNull(done.retryAfterSeconds());
        assertEquals(201, completed.path("result").path("status").asInt());
        DailyProposal stored = PublicRequests.read(CalculationJson.write(Required.value(completed.path("result").path("body"))), DailyProposal.class);
        assertEquals(stored.proposalId(), proposal(proposals().create(tenant, body)).proposalId(), "the synchronous call replays the queued answer");
        assertEquals(stored.proposalId(), proposal(proposals().createAsync(tenant, body)).proposalId(), "so does the queued call");
        assertFalse(worker(proposals(), running).runOnce("it-worker"), "nothing is left in the queue");
    }

    @Test void aRepairCanBeQueuedLikeADailyProposal() {
        String body = repair();
        String id = PublicRequests.read(body, PublicRequests.RepairProposalRequest.class).requestId();
        assertEquals("REPAIR_PROPOSAL", status(proposals().repairAsync(tenant, body), 202, "QUEUED").path("operation").asText());
        assertTrue(worker(proposals(), running).runOnce("it-worker"));
        assertEquals(201, status(proposals().describe(tenant, id), 200, "COMPLETED").path("result").path("status").asInt());
    }

    @Test void theStatusOfAnUnknownMalformedOrNonProposalRequestIsNotReported() {
        problem(proposals().describe(tenant, Required.value(UUID.randomUUID().toString())), 404, ErrorCode.NOT_FOUND);
        problem(proposals().describe(tenant, "NOT-A-UUID"), 400, ErrorCode.INVALID_REQUEST);
        String booking = Required.value(UUID.randomUUID().toString());
        assertInstanceOf(PublicApiStore.Started.class, store.claim(tenant, booking, PublicApiStore.Operation.BOOKING_OFFERS, DailyProposals.sha256("{}")));
        problem(proposals().describe(tenant, booking), 404, ErrorCode.NOT_FOUND);
        // A queued request is the tenant's own: another tenant neither sees nor cancels it.
        jdbc.update("INSERT INTO tenant (id,name) VALUES (?,?)", other, "Daily IT other");
        String body = request(false);
        status(proposals().createAsync(tenant, body), 202, "QUEUED");
        problem(proposals().describe(other, requestId(body)), 404, ErrorCode.NOT_FOUND);
        assertEquals(404, Required.value(new RequestCancellation(new RunningRequests(), jobs).cancel(other, requestId(body))).status());
    }

    @Test void aSynchronousCallStillRunningReportsRunning() throws Exception {
        slow.set(true);
        String body = request(false, policy -> policy.put("costSearchSeconds", 60).put("fairnessSearchSeconds", 0));
        var reply = java.util.concurrent.CompletableFuture.supplyAsync(() -> proposals().create(tenant, body));
        assertTrue(solving.await(10, java.util.concurrent.TimeUnit.SECONDS), "the search started");
        status(proposals().describe(tenant, requestId(body)), 200, "RUNNING");
        status(proposals().createAsync(tenant, body), 202, "RUNNING");
        assertNull(new RequestCancellation(running, jobs).cancel(tenant, requestId(body)));
        problem(Required.value(reply.get(10, java.util.concurrent.TimeUnit.SECONDS)), 409, ErrorCode.CANCELLED);
    }

    @Test void aTenantWithManyQueuedDaysDoesNotHoldBackAnotherTenantsSingleRequest() {
        jdbc.update("INSERT INTO tenant (id,name) VALUES (?,?)", other, "Daily IT other");
        List<String> first = new java.util.ArrayList<>();
        for (int day = 0; day < 3; day++) {
            String body = request(false);
            first.add(requestId(body));
            status(proposals().createAsync(tenant, body), 202, "QUEUED");
        }
        String single = request(false);
        status(proposals().createAsync(other, single), 202, "QUEUED");
        List<String> order = new java.util.ArrayList<>();
        for (int taken = 0; taken < 4; taken++) order.add(Required.value(jobs.next("it-worker", queue.maxWait())).requestId());
        assertEquals(List.of(Required.value(first.get(0)), requestId(single), Required.value(first.get(1)), Required.value(first.get(2))), order,
                "after the first tenant's oldest day, the other tenant's request goes next");
        assertNull(jobs.next("it-worker", queue.maxWait()));
    }

    @Test void aTenantsQueueIsLimitedAndAFullQueueAnswers429WithoutKeepingTheClaim() {
        for (int day = 0; day < 3; day++) status(proposals().createAsync(tenant, request(false)), 202, "QUEUED");
        String body = request(false);
        var refused = proposals().createAsync(tenant, body);
        problem(refused, 429, ErrorCode.BUSY);
        assertEquals(DailyProposals.QUEUE_RETRY_SECONDS, refused.retryAfterSeconds());
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM api_request WHERE \"tenantId\"=? AND \"requestId\"=?", Long.class, tenant, requestId(body)));
        assertEquals(3, jobRows(tenant));
    }

    @Test void aQueuedRequestCancelledFromAnyReplicaReportsCancelledAndRunsAgainWhenSentAgain() {
        String body = request(false);
        String id = requestId(body);
        status(proposals().createAsync(tenant, body), 202, "QUEUED");
        // Another replica: its own registry, the shared queue.
        assertNull(new RequestCancellation(new RunningRequests(), jobs).cancel(tenant, id));
        JsonNode cancelled = status(proposals().describe(tenant, id), 200, "COMPLETED");
        assertEquals(409, cancelled.path("result").path("status").asInt());
        assertEquals("CANCELLED", cancelled.path("result").path("body").path("error").asText());
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", Long.class, tenant), "the claim was released");
        assertFalse(worker(proposals(), running).runOnce("it-worker"), "a cancelled job is not run");
        status(proposals().createAsync(tenant, body), 202, "QUEUED");
        assertTrue(worker(proposals(), running).runOnce("it-worker"));
        assertEquals(201, status(proposals().describe(tenant, id), 200, "COMPLETED").path("result").path("status").asInt());
    }

    @Test void aRunningQueuedRequestIsStoppedByItsWorkerWhenAnotherReplicaCancelsIt() throws Exception {
        slow.set(true);
        String body = request(false, policy -> policy.put("costSearchSeconds", 60).put("fairnessSearchSeconds", 0));
        String id = requestId(body);
        status(proposals().createAsync(tenant, body), 202, "QUEUED");
        var run = java.util.concurrent.CompletableFuture.supplyAsync(() -> worker(proposals(), running).runOnce("it-worker"));
        assertTrue(solving.await(10, java.util.concurrent.TimeUnit.SECONDS), "the search started");
        assertTrue(status(proposals().describe(tenant, id), 200, "RUNNING").has("startedAt"));
        long cancelled = System.nanoTime();
        assertNull(new RequestCancellation(new RunningRequests(), jobs).cancel(tenant, id), "the running job is flagged from another replica");
        assertTrue(Required.value(run.get(15, java.util.concurrent.TimeUnit.SECONDS)));
        assertTrue(System.nanoTime() - cancelled < Duration.ofSeconds(6).toNanos(), "the worker stops the 60 second search promptly");
        JsonNode result = status(proposals().describe(tenant, id), 200, "COMPLETED").path("result");
        assertEquals(409, result.path("status").asInt());
        assertEquals("CANCELLED", result.path("body").path("error").asText());
    }

    @Test void aJobStillRunningWhenItsReplicaStopsIsQueuedAgainForAnotherReplica() throws Exception {
        slow.set(true);
        String body = request(false, policy -> policy.put("costSearchSeconds", 60).put("fairnessSearchSeconds", 0));
        String id = requestId(body);
        status(proposals().createAsync(tenant, body), 202, "QUEUED");
        DailyQueueWorker stopping = new DailyQueueWorker(jobs, proposals(), queue, admission, running, 200);
        assertTrue(stopping.getPhase() > org.springframework.context.SmartLifecycle.DEFAULT_PHASE - 1024,
                "the worker stops before the web server drains its requests");
        stopping.start();
        assertTrue(solving.await(10, java.util.concurrent.TimeUnit.SECONDS), "the search started");
        var stopped = new java.util.concurrent.CountDownLatch(1);
        long began = System.nanoTime();
        stopping.stop(stopped::countDown);
        assertTrue(stopped.await(10, java.util.concurrent.TimeUnit.SECONDS), "the stop completes within its drain and unwind");
        assertTrue(System.nanoTime() - began < Duration.ofSeconds(6).toNanos(), "the 60 second search is not waited for");
        assertFalse(stopping.isRunning());
        JsonNode requeued = status(proposals().describe(tenant, id), 200, "QUEUED");
        assertFalse(requeued.has("result"), "the host is not told the job was cancelled");
        assertEquals(0, running.cancellations(), "a shutdown stop is not counted as a host cancellation");

        slow.set(false);
        DailyJobs.Taken again = Required.value(jobs.next("replica-b", queue.maxWait()));
        assertEquals(1, again.attempts(), "the stopped run's attempt is not counted");
        jobs.requeue(again, Required.value(Duration.ZERO), false);
        assertTrue(worker(proposals(), running).runOnce("replica-b"));
        assertEquals(201, status(proposals().describe(tenant, id), 200, "COMPLETED").path("result").path("status").asInt());
    }

    @Test void aJobLeftRunningByAStoppedReplicaIsTakenAgainAndEndsWith503AfterItsAttempts() {
        String body = request(false);
        String id = requestId(body);
        status(proposals().createAsync(tenant, body), 202, "QUEUED");
        DailyJobs.Taken taken = Required.value(jobs.next("replica-a", queue.maxWait()));
        assertEquals(1, taken.attempts());
        for (int attempt = 2; attempt <= DailyProposals.MAX_JOB_ATTEMPTS + 1; attempt++) {
            assertNull(jobs.next("replica-b", queue.maxWait()), "a running job with a live lease is left alone");
            jdbc.update("UPDATE api_daily_job SET \"leaseExpiresAt\"=clock_timestamp()-INTERVAL '1 second' WHERE \"tenantId\"=? AND \"requestId\"=?", tenant, id);
            taken = Required.value(jobs.next("replica-b", queue.maxWait()));
            assertEquals(attempt, taken.attempts());
        }
        proposals().runJob(taken);
        assertEquals(0, solves.get(), "a job past its attempts is not calculated again");
        JsonNode result = status(proposals().describe(tenant, id), 200, "COMPLETED").path("result");
        assertEquals(503, result.path("status").asInt());
        assertEquals("CALCULATION_UNAVAILABLE", result.path("body").path("error").asText());
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", Long.class, tenant), "the claim was released");
    }

    @Test void aJobThatWaitedLongerThanTheQueueAllowsEndsWithBusyWithoutRunning() {
        String body = request(false);
        String id = requestId(body);
        status(proposals().createAsync(tenant, body), 202, "QUEUED");
        jdbc.update("UPDATE api_daily_job SET \"queuedAt\"=clock_timestamp()-INTERVAL '16 minutes' WHERE \"tenantId\"=? AND \"requestId\"=?", tenant, id);
        assertTrue(worker(proposals(), running).runOnce("it-worker"));
        assertEquals(0, solves.get());
        JsonNode result = status(proposals().describe(tenant, id), 200, "COMPLETED").path("result");
        assertEquals(429, result.path("status").asInt());
        assertEquals(DailyProposals.QUEUE_RETRY_SECONDS, result.path("retryAfterSeconds").asInt());
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", Long.class, tenant), "the claim was released");
    }

    @Test void aJobThatFindsNoCalculationSlotWaitsAgainWithoutLosingAnAttempt() {
        String body = request(false);
        String id = requestId(body);
        status(proposals().createAsync(tenant, body), 202, "QUEUED");
        DailyJobs.Taken taken = Required.value(jobs.next("it-worker", queue.maxWait()));
        try (var ahead = admission.acquire(SearchAdmission.Kind.BACKGROUND,
                new dev.waterflex.scheduler.SearchDeadline(Required.value(Duration.ofSeconds(90))), Required.value(Duration.ofSeconds(60)))) {
            assertTrue(ahead.queueMillis() >= 0);
            proposals().runJob(taken);
        }
        status(proposals().describe(tenant, id), 200, "QUEUED");
        Map<String, Object> row = jdbc.queryForMap("SELECT attempts, extract(epoch FROM \"notBefore\"-clock_timestamp())::int AS wait FROM api_daily_job WHERE \"tenantId\"=? AND \"requestId\"=?", tenant, id);
        assertEquals(0, ((Number) Required.value(row.get("attempts"))).intValue());
        assertTrue(((Number) Required.value(row.get("wait"))).intValue() > 50, "it waits about as long as the work ahead: " + row);
        assertNull(jobs.next("it-worker", queue.maxWait()), "not before its time");
    }
}
