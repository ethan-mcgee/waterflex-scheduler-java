package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.CalculationTransport;
import dev.waterflex.scheduler.DatabaseFacts;
import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.ScheduleCutoff;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.SearchDeadline;
import dev.waterflex.scheduler.api.PublicApiStore.*;
import dev.waterflex.scheduler.api.PublicRequests.DailyProposalRequest;
import dev.waterflex.scheduler.api.PublicResponses.*;
import dev.waterflex.scheduler.api.PublicTypes.TechnicianDay;
import dev.waterflex.scheduler.api.PublicTypes.TechnicianDayVersion;
import dev.waterflex.scheduler.optimizer.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * POST /api/v1/daily/proposals. Parses strictly, claims the request ID, routes through the metro's routing service,
 * runs the same daily calculation as the portal path, and stores the proposal with the host timestamps it was
 * computed from. A failure that would repeat on retry is stored and replayed; a passing one releases the claim.
 */
@Service
public class DailyProposals {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(DailyProposals.class));

    /** The HTTP reply: status, JSON body, and Retry-After seconds for 429. */
    public record Reply(int status, String json, @Nullable Integer retryAfterSeconds) {
        static Reply of(int status, Object body) { return new Reply(status, CalculationJson.write(body), status == 429 ? 1 : null); }
    }

    /** A request that will fail the same way every time it is repeated. */
    private static final class Final extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;
        final ErrorCode code;
        Final(int status, ErrorCode code, String message) { super(message); this.status = status; this.code = code; }
    }

    private final PublicApiStore store;
    private final MetroRouting routing;
    private final DailyPreparation.AddressLocator locator;
    private final CalculationTransport transport;
    private final DailySolver solver;
    private final SearchAdmission admission;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public DailyProposals(PublicApiStore store, MetroRouting routing, AddressLocation locator, CalculationTransport transport,
                          DailySolver solver, SearchAdmission admission, JdbcTemplate jdbc) {
        this(store, routing, locator, transport, solver, admission, jdbc, Required.value(Clock.systemUTC()));
    }

    public DailyProposals(PublicApiStore store, MetroRouting routing, DailyPreparation.AddressLocator locator, CalculationTransport transport,
                          DailySolver solver, SearchAdmission admission, JdbcTemplate jdbc, Clock clock) {
        this.store = store; this.routing = routing; this.locator = locator; this.transport = transport;
        this.solver = solver; this.admission = admission; this.jdbc = jdbc; this.clock = clock;
    }

    public Reply create(String tenantId, String body) {
        DailyProposalRequest request;
        try { request = PublicRequests.read(body, DailyProposalRequest.class); }
        catch (IllegalArgumentException invalid) { return Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, invalidRequest(invalid))); }
        Claim claim = store.claim(tenantId, request.requestId(), Operation.DAILY_PROPOSAL, sha256(CalculationJson.write(request)));
        return switch (claim) {
            case Replay replay -> replayed(replay);
            case Conflict _ -> Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, "requestId was already used with a different request"));
            case Busy _ -> Reply.of(429, new Problem(ErrorCode.BUSY, "A request with this requestId is still running"));
            case Started started -> run(tenantId, request, started.ownerToken());
        };
    }

    /**
     * A stored response is validated into its typed body before it is sent again. Writing it back out also restores
     * the exact original bytes, which the JSONB column does not keep (it reorders keys).
     */
    private static Reply replayed(Replay replay) {
        Object body;
        try { body = replay.status() == 201 ? PublicRequests.read(replay.json(), DailyProposal.class) : PublicRequests.read(replay.json(), Problem.class); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("A stored response failed validation", invalid); }
        return Reply.of(replay.status(), body);
    }

    /** Road routing, or the address lookup that serves it, was unavailable; a retry runs again. */
    static Problem routingUnavailable(RoadClient.RoadUnavailable unavailable) {
        return new Problem(ErrorCode.ROUTING_UNAVAILABLE, unavailable instanceof NominatimGeocoder.Unavailable
                ? Required.value(unavailable.getMessage()) : "Road routing unavailable");
    }

    private Reply run(String tenantId, DailyProposalRequest request, String owner) {
        try {
            StoredProposal stored = propose(request);
            store.completeDaily(tenantId, owner, stored);
            return new Reply(201, CalculationJson.write(stored.proposal()), null);
        } catch (Final failure) {
            Problem problem = new Problem(failure.code, message(failure));
            store.completeWithError(tenantId, request.requestId(), owner, failure.status, problem);
            return Reply.of(failure.status, problem);
        } catch (RoadClient.RoadUnavailable unavailable) {
            store.release(tenantId, request.requestId(), owner);
            return Reply.of(503, routingUnavailable(unavailable));
        } catch (SearchAdmission.Busy busy) {
            store.release(tenantId, request.requestId(), owner);
            return Reply.of(429, new Problem(ErrorCode.BUSY, "Search capacity exhausted"));
        } catch (SearchDeadline.Expired expired) {
            store.release(tenantId, request.requestId(), owner);
            return Reply.of(503, new Problem(ErrorCode.CALCULATION_UNAVAILABLE, "The calculation deadline passed"));
        } catch (RuntimeException unexpected) {
            store.release(tenantId, request.requestId(), owner);
            throw unexpected;
        }
    }

    private StoredProposal propose(DailyProposalRequest request) {
        PublicTypes.Snapshot snapshot = request.snapshot();
        if (ScheduleCutoff.frozen(request.serviceDate(), Required.value(clock.instant()), Required.value(ZoneId.of(snapshot.timeZone()))))
            throw new Final(422, ErrorCode.INCOMPLETE_FACTS, "Routes for " + request.serviceDate() + " are frozen from 6 a.m. local time");
        String revision = sha256(CalculationJson.write(snapshot));
        String proposalId = PublicApiStore.newProposalId();
        SchedulingPolicy.Rules policy = rules(sharedPolicy(jdbc), snapshot.policy());
        Map<String, String> tokens = new LinkedHashMap<>();
        for (TechnicianDay day : snapshot.technicianDays()) tokens.put(day.technicianId(), day.lastModified().toString());
        Outcome outcome = DailyOperation.execute(admission, Required.value(Duration.ofSeconds(20)), () -> calculate(request, policy, proposalId, revision, tokens),
                receipt -> LOG.info("Public daily operation {}", receipt));
        DailyProposal proposal = response(request, proposalId, revision, outcome);
        List<TechnicianDayVersion> days = new ArrayList<>();
        for (TechnicianDay day : snapshot.technicianDays()) days.add(new TechnicianDayVersion(day.technicianId(), day.serviceDate(), day.lastModified()));
        return new StoredProposal(proposalId, request.requestId(), snapshot.metroId(), snapshot.timeZone(), request.serviceDate(), revision,
                outcome.prepared().routingIdentity(), ProposalStatus.PROPOSED, days, proposal);
    }

    private record Outcome(DailyPreparation.Prepared prepared, DayPlan chosen, Decision decision, String reason) { }

    /** The portal path's preview decision (OptimizationService.createPreview), applied to request-fed facts. */
    private Outcome calculate(DailyProposalRequest request, SchedulingPolicy.Rules policy, String proposalId, String revision, Map<String, String> tokens) {
        DailyPreparation.Prepared prepared;
        try { prepared = DailyPreparation.prepare(request, routing, locator); }
        catch (MetroRouting.UnknownMetro unknown) { throw new Final(422, ErrorCode.INCOMPLETE_FACTS, message(unknown)); }
        DayPlan baseline = prepared.plan();
        if (baseline.getVisits().isEmpty()) return new Outcome(prepared, baseline, Decision.NO_IMPROVEMENT, "No appointments to optimize");
        var before = DayScoreCalculator.evaluate(baseline);
        SearchDeadline.checkpoint();
        var validated = RouteEvaluator.evaluate(baseline);
        if (before.hardPenalty() != 0 || !validated.feasible() || before.costCents() != validated.costCents() || !before.arrivals().equals(validated.arrivals()))
            return new Outcome(prepared, baseline, Decision.NO_IMPROVEMENT, "The current routes are infeasible or cannot be scored consistently; nothing was proposed");
        Map<String, String> kept = new LinkedHashMap<>();
        for (TechRoute route : baseline.getRoutes()) kept.put(route.getId(), Required.value(tokens.get(route.getId()), "technician-day token"));
        String configuration = sha256(CalculationJson.write(policy));
        var calculation = transport.dailyTokens(baseline, policy, solver, prepared.points(), proposalId, revision, prepared.routingIdentity(), configuration, kept);
        SearchDeadline.checkpoint();
        if (calculation.accepted()) return new Outcome(prepared, calculation.plan(), Decision.IMPROVED, calculation.reason());
        Decision decision = DailyCalculation.valid(calculation.plan()) ? Decision.REJECTED_BY_POLICY : Decision.NO_IMPROVEMENT;
        return new Outcome(prepared, baseline, decision, calculation.reason());
    }

    private static DailyProposal response(DailyProposalRequest request, String proposalId, String revision, Outcome outcome) {
        DayPlan plan = outcome.chosen();
        var evaluation = RouteEvaluator.evaluate(plan);
        if (evaluation.overtimeMinutes() != 0) throw new IllegalStateException("A proposal must never carry overtime");
        List<PlannedRoute> routes = new ArrayList<>();
        for (TechRoute route : plan.getRoutes()) {
            List<PlannedStop> stops = new ArrayList<>();
            for (int index = 0; index < route.getVisits().size(); index++) {
                PlanVisit visit = Required.value(route.getVisits().get(index));
                Instant start = Required.value(evaluation.arrivals().get(visit.getId()), "arrival for " + visit.getId());
                stops.add(new PlannedStop(visit.getId(), index, start, Required.value(start.plus(Duration.ofMinutes(visit.getDurationMinutes())))));
            }
            routes.add(new PlannedRoute(route.getId(), request.serviceDate(), Required.value(List.copyOf(stops))));
        }
        return new DailyProposal(proposalId, revision, outcome.decision(), outcome.reason(), Required.value(List.copyOf(routes)),
                plan.getUnassignedVisitIds(), outcome.prepared().skipped(), evaluation.costCents(), 0);
    }

    /** The scheduler's policy settings with the client's own fairness budget in place of the shared one. */
    static SchedulingPolicy.Rules rules(SchedulingPolicy.Rules shared, PublicTypes.Policy client) {
        return new SchedulingPolicy.Rules(shared.regularWindowThreshold(), shared.utilizationThreshold(), client.fairnessBudget(), shared.bookingDeadlineMs());
    }

    /** The scheduler's own policy settings, the same ones the portal path reads. */
    static SchedulingPolicy.Rules sharedPolicy(JdbcTemplate jdbc) {
        Map<String, BigDecimal> settings = new HashMap<>();
        jdbc.query("SELECT key,value FROM omaha_setting", (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                settings.put(DatabaseFacts.string(rs, 1), DatabaseFacts.decimal(rs, 2)));
        return PolicySettings.read(settings);
    }

    /** The parser's own first line for malformed JSON, since the wrapper's message alone says only that it was invalid. */
    static String invalidRequest(IllegalArgumentException invalid) {
        Throwable cause = invalid.getCause();
        String detail = cause == null ? null : cause.getMessage();
        if (detail == null || detail.isBlank()) return message(invalid);
        return message(invalid) + ": " + Required.value(detail.lines().findFirst().orElse(detail), "parser detail");
    }

    private static String message(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? Required.value(failure.getClass().getSimpleName()) : message;
    }

    static String sha256(String value) {
        try { return Required.value(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))); }
        catch (java.security.NoSuchAlgorithmException missing) { throw new IllegalStateException(missing); }
    }
}
