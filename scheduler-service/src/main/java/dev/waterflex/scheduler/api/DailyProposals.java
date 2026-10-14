package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.CalculationTransport;
import dev.waterflex.scheduler.HttpCalculation;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * POST /api/v1/daily/proposals and POST /api/v1/repairs/proposals. Parses strictly, claims the request ID, routes through the metro's routing service,
 * runs the daily calculation, and stores the proposal with the host timestamps it was
 * computed from. A failure that would repeat on retry is stored and replayed; a passing one releases the claim. A repair
 * is the same calculation with the absence added to the technician's day, in repair mode: it is accepted
 * only when every appointment is placed, every constraint holds and no overtime is added. It is committed like any
 * daily proposal.
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
    private final DailySolvers solvers;
    private final SearchAdmission admission;
    private final RunningRequests running;
    private final Clock clock;

    @Autowired
    public DailyProposals(PublicApiStore store, MetroRouting routing, AddressLocation locator, CalculationTransport transport,
                          DailySolvers solvers, SearchAdmission admission, RunningRequests running) {
        this(store, routing, locator, transport, solvers, admission, running, Required.value(Clock.systemUTC()));
    }

    public DailyProposals(PublicApiStore store, MetroRouting routing, DailyPreparation.AddressLocator locator, CalculationTransport transport,
                          DailySolvers solvers, SearchAdmission admission, RunningRequests running, Clock clock) {
        this.store = store; this.routing = routing; this.locator = locator; this.transport = transport;
        this.solvers = solvers; this.admission = admission; this.running = running; this.clock = clock;
    }

    public Reply create(String tenantId, String body) {
        DailyProposalRequest request;
        try { request = PublicRequests.read(body, DailyProposalRequest.class); }
        catch (IllegalArgumentException invalid) { return Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, invalidRequest(invalid))); }
        dev.waterflex.scheduler.RequestLogging.requestId(request.requestId());
        Claim claim = store.claim(tenantId, request.requestId(), Operation.DAILY_PROPOSAL, sha256(CalculationJson.write(request)), lease(request.snapshot()));
        return switch (claim) {
            case Replay replay -> replayed(replay);
            case Conflict _ -> Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, "requestId was already used with a different request"));
            case Busy _ -> Reply.of(429, new Problem(ErrorCode.BUSY, "A request with this requestId is still running"));
            case Started started -> run(tenantId, request.requestId(), started.ownerToken(), registered -> propose(request, null, registered));
        };
    }

    public Reply repair(String tenantId, String body) {
        PublicRequests.RepairProposalRequest request;
        try { request = PublicRequests.read(body, PublicRequests.RepairProposalRequest.class); }
        catch (IllegalArgumentException invalid) { return Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, invalidRequest(invalid))); }
        dev.waterflex.scheduler.RequestLogging.requestId(request.requestId());
        Claim claim = store.claim(tenantId, request.requestId(), Operation.REPAIR_PROPOSAL, sha256(CalculationJson.write(request)), lease(request.day().snapshot()));
        return switch (claim) {
            case Replay replay -> replayed(replay);
            case Conflict _ -> Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, "requestId was already used with a different request"));
            case Busy _ -> Reply.of(429, new Problem(ErrorCode.BUSY, "A request with this requestId is still running"));
            case Started started -> run(tenantId, request.requestId(), started.ownerToken(), registered -> propose(request.day(), request.absence(), registered));
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

    /** A cancelled request: the claim is released, so the same requestId runs again when it is retried. */
    static Problem cancelled() {
        return new Problem(ErrorCode.CANCELLED, "The request was cancelled; nothing was stored, and retrying it with the same requestId runs it again");
    }

    /** A proposal calculation for a registered request; declared here so its parameter is non-null like the rest of the package. */
    private interface Proposer { StoredProposal propose(RunningRequests.Running registered); }

    private Reply run(String tenantId, String requestId, String owner, Proposer propose) {
        RunningRequests.Running registered;
        try { registered = running.start(tenantId, requestId); }
        catch (RunningRequests.AlreadyRunning already) {
            store.release(tenantId, requestId, owner);
            return Reply.of(429, new Problem(ErrorCode.BUSY, Required.value(already.getMessage())));
        }
        try {
            StoredProposal stored = Required.value(propose.propose(registered));
            store.completeDaily(tenantId, owner, stored);
            return new Reply(201, CalculationJson.write(stored.proposal()), null);
        } catch (Final failure) {
            Problem problem = new Problem(failure.code, message(failure));
            store.completeWithError(tenantId, requestId, owner, failure.status, problem);
            return Reply.of(failure.status, problem);
        } catch (RoadClient.RoadUnavailable unavailable) {
            store.release(tenantId, requestId, owner);
            return Reply.of(503, routingUnavailable(unavailable));
        } catch (SearchAdmission.Busy busy) {
            store.release(tenantId, requestId, owner);
            return busy(busy);
        } catch (HttpCalculation.Unavailable unavailable) {
            // A remote solver that is down, refused the work or answered an error: nothing was calculated, so the
            // claim is released and the same requestId may be sent again.
            store.release(tenantId, requestId, owner);
            LOG.warn("Remote daily calculation unavailable for request {}: {}", requestId, unavailable.getMessage());
            return Reply.of(503, new Problem(ErrorCode.CALCULATION_UNAVAILABLE, "The calculation service is unavailable"));
        } catch (SearchDeadline.Expired expired) {
            store.release(tenantId, requestId, owner);
            if (registered.cancelled()) return Reply.of(409, cancelled());
            return Reply.of(503, new Problem(ErrorCode.CALCULATION_UNAVAILABLE, "The calculation deadline passed"));
        } catch (RuntimeException unexpected) {
            store.release(tenantId, requestId, owner);
            throw unexpected;
        } finally {
            running.finish(tenantId, requestId, registered);
        }
    }

    /** The longest Retry-After a refusal names; a longer estimated wait is retried after this and refused again if still busy. */
    static final int MAX_RETRY_AFTER_SECONDS = 300;

    /** A 429: with the estimated wait for the work ahead as Retry-After when admission knows it, else one second. */
    static Reply busy(SearchAdmission.Busy busy) {
        java.time.Duration wait = busy.retryAfter();
        Problem problem = new Problem(ErrorCode.BUSY, wait == null ? "Search capacity exhausted"
                : "Daily calculations ahead of this one would not finish in time; retry after the Retry-After seconds");
        if (wait == null) return Reply.of(429, problem);
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        return new Reply(429, CalculationJson.write(problem), Math.toIntExact(Math.min(seconds, MAX_RETRY_AFTER_SECONDS)));
    }

    /**
     * Routing for a daily request (road legs and any address lookups) runs before it asks for a calculation slot, under
     * this deadline of its own, so a cold map never spends the search allowance and never holds the slot.
     */
    static final Duration ROUTING_ALLOWANCE = Required.value(Duration.ofSeconds(30));

    /** The input revision: the snapshot, and for a repair also the absence it repairs. */
    static String revision(PublicTypes.Snapshot snapshot, PublicRequests.@Nullable Absence absence) {
        return absence == null ? sha256(CalculationJson.write(snapshot)) : sha256(CalculationJson.write(absence) + "\n" + CalculationJson.write(snapshot));
    }

    private StoredProposal propose(DailyProposalRequest request, PublicRequests.@Nullable Absence absence, RunningRequests.Running registered) {
        PublicTypes.Snapshot snapshot = request.snapshot();
        if (ScheduleCutoff.frozen(request.serviceDate(), Required.value(clock.instant()), Required.value(ZoneId.of(snapshot.timeZone()))))
            throw new Final(422, ErrorCode.INCOMPLETE_FACTS, "Routes for " + request.serviceDate() + " are frozen from 6 a.m. local time");
        String revision = revision(snapshot, absence);
        String proposalId = PublicApiStore.newProposalId();
        SchedulingPolicy.Rules policy = snapshot.policy().rules();
        Map<String, String> tokens = new LinkedHashMap<>();
        for (TechnicianDay day : snapshot.technicianDays()) tokens.put(day.technicianId(), day.lastModified().toString());
        DailyCalculation.Allocation allocation = allocation(snapshot.policy());
        DailyOperation.Allowance allowance = DailyOperation.Allowance.forSearch(allocation);
        // Refused at once, before any routing, when the calculations ahead could not finish within the allowance's reserve.
        admission.requireBackgroundStartWithin(Required.value(allowance.total().minus(allowance.search())));
        DailyPreparation.Prepared prepared = route(request, registered);
        // The preparation runs on the operation's own thread, where the operation is current, so a host cancel can reach it.
        Outcome outcome = DailyOperation.executePrepared(admission, allowance, () -> {
                    registered.attach(DailyOperation.current());
                    return new DailyOperation.Preparation<>(
                            () -> calculate(prepared, policy, snapshot.policy().dailySearch(), allocation, proposalId, revision, tokens, absence),
                            true, _ -> { });
                }, receipt -> LOG.info("Public daily operation {}", receipt));
        DailyProposal proposal = response(request, proposalId, revision, outcome);
        List<TechnicianDayVersion> days = new ArrayList<>();
        for (TechnicianDay day : snapshot.technicianDays()) days.add(new TechnicianDayVersion(day.technicianId(), day.serviceDate(), day.lastModified()));
        return new StoredProposal(proposalId, request.requestId(), snapshot.metroId(), snapshot.timeZone(), request.serviceDate(), revision,
                outcome.prepared().routingIdentity(), ProposalStatus.PROPOSED, days, proposal);
    }

    private record Outcome(DailyPreparation.Prepared prepared, DayPlan chosen, Decision decision, String reason) { }

    /** The day's routed facts, fetched within {@link #ROUTING_ALLOWANCE}; a host cancel ends it like the calculation. */
    private DailyPreparation.Prepared route(DailyProposalRequest request, RunningRequests.Running registered) {
        SearchDeadline deadline = new SearchDeadline(ROUTING_ALLOWANCE);
        registered.attach(deadline);
        try { return deadline.within(() -> DailyPreparation.prepare(request, routing, locator)); }
        catch (MetroRouting.UnknownMetro unknown) { throw new Final(422, ErrorCode.INCOMPLETE_FACTS, message(unknown)); }
    }

    /**
     * The daily preview decision applied to request-fed facts; with an absence, the repair of the same facts.
     */
    private Outcome calculate(DailyPreparation.Prepared prepared, SchedulingPolicy.Rules policy, DailySearch search, DailyCalculation.Allocation allocation,
                              String proposalId, String revision, Map<String, String> tokens, PublicRequests.@Nullable Absence absence) {
        DayPlan baseline = prepared.plan();
        if (absence != null && baseline.getRoutes().stream().noneMatch(route -> route.getId().equals(absence.technicianId())))
            throw new Final(422, ErrorCode.INCOMPLETE_FACTS, "Technician-day " + absence.technicianId() + " on " + absence.serviceDate()
                    + " could not be located (see skippedTechnicianDays in a daily proposal), so it cannot be repaired");
        if (baseline.getVisits().isEmpty())
            return new Outcome(prepared, baseline, Decision.NO_IMPROVEMENT, absence == null ? "No appointments to optimize" : "No appointments to repair");
        var before = DayScoreCalculator.evaluate(baseline);
        SearchDeadline.checkpoint();
        var validated = RouteEvaluator.evaluate(baseline);
        if (before.hardPenalty() != 0 || !validated.feasible() || before.costCents() != validated.costCents() || !before.arrivals().equals(validated.arrivals()))
            return new Outcome(prepared, baseline, Decision.NO_IMPROVEMENT, "The current routes are infeasible or cannot be scored consistently; nothing was proposed");
        Map<String, String> kept = new LinkedHashMap<>();
        for (TechRoute route : baseline.getRoutes()) kept.put(route.getId(), Required.value(tokens.get(route.getId()), "technician-day token"));
        String configuration = configuration(policy, search, allocation);
        DayPlan searched = absence == null ? baseline : PlanCopies.withAbsence(baseline, absence.technicianId(),
                new TechRoute.Unavailable(absence.window().start(), absence.window().end()));
        var calculation = transport.dailyTokens(searched, policy, solvers.get(search), allocation, prepared.points(), proposalId, revision, prepared.routingIdentity(), configuration, kept);
        SearchDeadline.checkpoint();
        if (calculation.accepted()) return new Outcome(prepared, calculation.plan(), Decision.IMPROVED, ReasonText.describe(calculation.reason()));
        Decision decision = DailyCalculation.valid(calculation.plan()) ? Decision.REJECTED_BY_POLICY : Decision.NO_IMPROVEMENT;
        return new Outcome(prepared, baseline, decision, ReasonText.describe(calculation.reason()));
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

    /** The phase budgets of the client's chosen search times. */
    static DailyCalculation.Allocation allocation(PublicTypes.Policy client) {
        return DailyCalculation.Allocation.client(client.costSearchSeconds(), client.fairnessSearchSeconds());
    }

    /** The identity of everything that shapes the search besides the snapshot: policy rules, method and phase budgets. */
    record Configuration(SchedulingPolicy.Rules policy, DailySearch dailySearch, DailyCalculation.Allocation allocation) { }

    static String configuration(SchedulingPolicy.Rules policy, DailySearch search, DailyCalculation.Allocation allocation) {
        return sha256(CalculationJson.write(new Configuration(policy, search, allocation)));
    }

    /** A claim must outlive the calculation it guards, so another replica never takes over a running request. */
    static Duration lease(PublicTypes.Snapshot snapshot) {
        Duration running = Required.value(DailyOperation.Allowance.forSearch(allocation(snapshot.policy())).total().plus(ROUTING_ALLOWANCE)
                .plus(PublicApiStore.LEASE_MARGIN));
        return running.compareTo(PublicApiStore.LEASE) > 0 ? running : PublicApiStore.LEASE;
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
