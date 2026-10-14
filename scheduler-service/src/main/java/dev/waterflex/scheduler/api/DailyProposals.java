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

    /** The HTTP reply: status, JSON body, Retry-After seconds (429, and 202 as the time to the first poll), and Location for 202. */
    public record Reply(int status, String json, @Nullable Integer retryAfterSeconds, @Nullable String location) {
        public Reply(int status, String json, @Nullable Integer retryAfterSeconds) { this(status, json, retryAfterSeconds, null); }
        static Reply of(int status, Object body) { return new Reply(status, CalculationJson.write(body), status == 429 ? 1 : null); }
    }

    /**
     * How a calculation ended. Stored: its answer is stored with the request (a proposal, or an error that repeats on
     * every retry). Released: nothing was stored and the claim must be released, so the same requestId runs again.
     * Deferred: this replica had no calculation slot in time; a queued job waits and is taken again.
     */
    sealed interface Ran permits Stored, Released, Deferred { Reply reply(); }
    record Stored(Reply reply) implements Ran { }
    record Released(Reply reply) implements Ran { }
    record Deferred(Reply reply, Duration after) implements Ran { }

    /** A queued job taken again after its run stopped this many times (its replica stopped) ends with 503. */
    static final int MAX_JOB_ATTEMPTS = 3;
    /** Seconds a host is asked to wait before polling a queued or running request. */
    static final int POLL_AFTER_SECONDS = 3;

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
    private final DailyJobs jobs;
    private final DailyQueue queue;
    private final Clock clock;

    @Autowired
    public DailyProposals(PublicApiStore store, MetroRouting routing, AddressLocation locator, CalculationTransport transport,
                          DailySolvers solvers, SearchAdmission admission, RunningRequests running, DailyJobs jobs, DailyQueue queue) {
        this(store, routing, locator, transport, solvers, admission, running, jobs, queue, Required.value(Clock.systemUTC()));
    }

    public DailyProposals(PublicApiStore store, MetroRouting routing, DailyPreparation.AddressLocator locator, CalculationTransport transport,
                          DailySolvers solvers, SearchAdmission admission, RunningRequests running, DailyJobs jobs, DailyQueue queue, Clock clock) {
        this.store = store; this.routing = routing; this.locator = locator; this.transport = transport;
        this.solvers = solvers; this.admission = admission; this.running = running; this.jobs = jobs; this.queue = queue; this.clock = clock;
    }

    /** A strictly parsed proposal request: its canonical JSON (the claim digest and the queued copy), lease, and calculation. */
    private record Parsed(String requestId, String json, Duration lease, Proposer proposer) { }

    /** Parses a daily or repair proposal request; IllegalArgumentException when it breaks the strict rules. */
    private Parsed parse(Operation operation, String body) {
        if (operation == Operation.DAILY_PROPOSAL) {
            DailyProposalRequest request = PublicRequests.read(body, DailyProposalRequest.class);
            return new Parsed(request.requestId(), CalculationJson.write(request), lease(request.snapshot()),
                    registered -> propose(request, null, registered));
        }
        if (operation != Operation.REPAIR_PROPOSAL) throw new IllegalStateException("Not a proposal operation: " + operation);
        PublicRequests.RepairProposalRequest request = PublicRequests.read(body, PublicRequests.RepairProposalRequest.class);
        return new Parsed(request.requestId(), CalculationJson.write(request), lease(request.day().snapshot()),
                registered -> propose(request.day(), request.absence(), registered));
    }

    public Reply create(String tenantId, String body) { return synchronous(tenantId, Operation.DAILY_PROPOSAL, body); }

    public Reply repair(String tenantId, String body) { return synchronous(tenantId, Operation.REPAIR_PROPOSAL, body); }

    /** {@code Prefer: respond-async}: queues the daily proposal and answers 202 at once (spec 1.4.0). */
    public Reply createAsync(String tenantId, String body) { return queued(tenantId, Operation.DAILY_PROPOSAL, body); }

    /** {@code Prefer: respond-async}: queues the repair proposal and answers 202 at once (spec 1.4.0). */
    public Reply repairAsync(String tenantId, String body) { return queued(tenantId, Operation.REPAIR_PROPOSAL, body); }

    private Reply synchronous(String tenantId, Operation operation, String body) {
        Parsed parsed;
        try { parsed = parse(operation, body); }
        catch (IllegalArgumentException invalid) { return Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, invalidRequest(invalid))); }
        dev.waterflex.scheduler.RequestLogging.requestId(parsed.requestId());
        Claim claim = store.claim(tenantId, parsed.requestId(), operation, sha256(parsed.json()), parsed.lease());
        return switch (claim) {
            case Replay replay -> replayed(replay);
            case Conflict _ -> Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, "requestId was already used with a different request"));
            case Busy _ -> Reply.of(429, new Problem(ErrorCode.BUSY, "A request with this requestId is still running"));
            case Started started -> run(tenantId, parsed.requestId(), started.ownerToken(), parsed.proposer());
        };
    }

    /** Retry-After for a tenant whose queue is full, or a job that waited too long: about one daily calculation. */
    static final int QUEUE_RETRY_SECONDS = 30;

    /**
     * Claims the request for a queued job, so the same requestId sent again finds it in progress, and answers 202 with
     * its status. A request that already finished replays its answer like the synchronous call; one still in progress
     * answers 202 with its current status.
     */
    private Reply queued(String tenantId, Operation operation, String body) {
        Parsed parsed;
        try { parsed = parse(operation, body); }
        catch (IllegalArgumentException invalid) { return Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, invalidRequest(invalid))); }
        dev.waterflex.scheduler.RequestLogging.requestId(parsed.requestId());
        // The claim covers the longest wait in the queue and then the run; the worker renews it when the run starts.
        Duration lease = Required.value(parsed.lease().plus(queue.maxWait()));
        Claim claim = store.claim(tenantId, parsed.requestId(), operation, sha256(parsed.json()), lease);
        return switch (claim) {
            case Replay replay -> replayed(replay);
            case Conflict _ -> Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, "requestId was already used with a different request"));
            case Busy _ -> accepted(tenantId, parsed.requestId());
            case Started started -> {
                try { jobs.enqueue(tenantId, parsed.requestId(), started.ownerToken(), operation, parsed.json(), parsed.lease(), queue.tenantLimit()); }
                catch (DailyJobs.Full full) {
                    store.release(tenantId, parsed.requestId(), started.ownerToken());
                    yield new Reply(429, CalculationJson.write(new Problem(ErrorCode.BUSY, Required.value(full.getMessage()))), QUEUE_RETRY_SECONDS);
                } catch (RuntimeException unexpected) {
                    store.release(tenantId, parsed.requestId(), started.ownerToken());
                    throw unexpected;
                }
                queue.queued();
                yield accepted(tenantId, parsed.requestId());
            }
        };
    }

    /** 202 with the request's status, where to poll it, and when. */
    private Reply accepted(String tenantId, String requestId) {
        RequestStatus status = status(tenantId, requestId);
        // A synchronous call with this requestId may have finished between the claim and this read.
        if (status == null) return Reply.of(429, new Problem(ErrorCode.BUSY, "A request with this requestId is still running"));
        RequestResult result = status.result();
        if (result != null) return new Reply(result.status(), CalculationJson.write(result.body()), result.retryAfterSeconds());
        return new Reply(202, CalculationJson.write(status), POLL_AFTER_SECONDS, "/api/v1/requests/" + requestId);
    }

    /** {@code GET /api/v1/requests/{requestId}}: 200 with the request's status, or 404 (spec 1.4.0). */
    public Reply describe(String tenantId, String requestId) {
        try { Input.requestId(requestId); }
        catch (IllegalArgumentException invalid) { return Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, "requestId must be a lowercase canonical UUID")); }
        RequestStatus status = status(tenantId, requestId);
        if (status == null) return Reply.of(404, new Problem(ErrorCode.NOT_FOUND, "No daily or repair proposal request with this requestId"));
        return new Reply(200, CalculationJson.write(status), status.state() == RequestState.COMPLETED ? null : POLL_AFTER_SECONDS);
    }

    /**
     * The request's status: a queued or running job first; then the request's claim (its stored answer once complete,
     * or a synchronous call still running); then the result of a job that ended without a stored answer. Null when
     * the tenant has no daily or repair proposal request with this ID.
     */
    @Nullable RequestStatus status(String tenantId, String requestId) {
        DailyJobs.Status job = jobs.status(tenantId, requestId);
        if (job != null && job.state() != DailyJobs.State.DONE) {
            boolean waiting = job.state() == DailyJobs.State.QUEUED;
            return new RequestStatus(requestId, publicOperation(job.operation()), waiting ? RequestState.QUEUED : RequestState.RUNNING,
                    job.queuedAt(), waiting ? null : job.startedAt(), null);
        }
        PublicApiStore.RequestRow row = store.request(tenantId, requestId);
        if (row != null) {
            if (row.operation() != Operation.DAILY_PROPOSAL && row.operation() != Operation.REPAIR_PROPOSAL) return null;
            Integer stored = row.responseStatus();
            if (stored == null) return new RequestStatus(requestId, publicOperation(row.operation()), RequestState.RUNNING, null, null, null);
            Reply replay = replayed(new Replay(stored, Required.value(row.responseJson(), "stored response")));
            Object body = stored == 201 ? PublicRequests.read(replay.json(), DailyProposal.class) : PublicRequests.read(replay.json(), Problem.class);
            return new RequestStatus(requestId, publicOperation(row.operation()), RequestState.COMPLETED,
                    job == null ? null : job.queuedAt(), job == null ? null : job.startedAt(), new RequestResult(stored, body, null));
        }
        DailyJobs.Result result = job == null ? null : job.result();
        if (job == null || result == null) return null;
        Problem problem;
        try { problem = PublicRequests.read(result.json(), Problem.class); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("A stored job result failed validation", invalid); }
        return new RequestStatus(requestId, publicOperation(job.operation()), RequestState.COMPLETED, job.queuedAt(), job.startedAt(),
                new RequestResult(result.status(), problem, result.retryAfterSeconds()));
    }

    private static RequestOperation publicOperation(Operation operation) {
        return switch (operation) {
            case DAILY_PROPOSAL -> RequestOperation.DAILY_PROPOSAL;
            case REPAIR_PROPOSAL -> RequestOperation.REPAIR_PROPOSAL;
            default -> throw new IllegalStateException("Not a proposal operation: " + operation);
        };
    }

    /**
     * Runs one job a worker took from the queue: the same calculation as the synchronous call. Its answer is stored
     * with the request, or recorded on the job with the claim released, or the job waits again when this replica had
     * no calculation slot in time.
     */
    public void runJob(DailyJobs.Taken job) {
        if (job.expired()) {
            jobs.finishReleased(job, DailyJobs.result(429, new Problem(ErrorCode.BUSY, "The request waited longer than "
                    + queue.maxWait().toMinutes() + " minutes in the queue without starting; send it again"), QUEUE_RETRY_SECONDS));
            return;
        }
        if (job.attempts() > MAX_JOB_ATTEMPTS) {
            jobs.finishReleased(job, DailyJobs.result(503, new Problem(ErrorCode.CALCULATION_UNAVAILABLE, "The calculation stopped "
                    + MAX_JOB_ATTEMPTS + " times before it finished; send the request again"), null));
            return;
        }
        Parsed parsed;
        try { parsed = parse(job.operation(), jobs.request(job)); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("A queued request failed validation", invalid); }
        Ran ran = execute(job.tenantId(), job.requestId(), job.ownerToken(), parsed.proposer());
        switch (ran) {
            case Stored _ -> jobs.finish(job);
            case Released released -> jobs.finishReleased(job, new DailyJobs.Result(released.reply().status(), released.reply().json(),
                    released.reply().retryAfterSeconds()));
            case Deferred deferred -> jobs.requeue(job, deferred.after(), false);
        }
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

    /** A synchronous call: runs the calculation and releases the claim when nothing was stored. */
    private Reply run(String tenantId, String requestId, String owner, Proposer propose) {
        Ran ran;
        try { ran = execute(tenantId, requestId, owner, propose); }
        catch (RuntimeException unexpected) {
            store.release(tenantId, requestId, owner);
            throw unexpected;
        }
        if (!(ran instanceof Stored)) store.release(tenantId, requestId, owner);
        return ran.reply();
    }

    /**
     * Runs a claimed request's calculation on this replica, registered so the host can cancel it, and stores its answer
     * when it is final. The caller releases the claim, or queues the job again, when it is not.
     */
    private Ran execute(String tenantId, String requestId, String owner, Proposer propose) {
        RunningRequests.Running registered;
        try { registered = running.start(tenantId, requestId); }
        catch (RunningRequests.AlreadyRunning already) {
            return new Released(Reply.of(429, new Problem(ErrorCode.BUSY, Required.value(already.getMessage()))));
        }
        try {
            StoredProposal stored = Required.value(propose.propose(registered));
            store.completeDaily(tenantId, owner, stored);
            return new Stored(new Reply(201, CalculationJson.write(stored.proposal()), null));
        } catch (Final failure) {
            Problem problem = new Problem(failure.code, message(failure));
            store.completeWithError(tenantId, requestId, owner, failure.status, problem);
            return new Stored(Reply.of(failure.status, problem));
        } catch (RoadClient.RoadUnavailable unavailable) {
            return new Released(Reply.of(503, routingUnavailable(unavailable)));
        } catch (SearchAdmission.Busy busy) {
            Duration wait = busy.retryAfter();
            return new Deferred(busy(busy), wait == null ? Required.value(Duration.ofSeconds(1)) : wait);
        } catch (HttpCalculation.Unavailable unavailable) {
            // A remote solver that is down, refused the work or answered an error: nothing was calculated, so the
            // claim is released and the same requestId may be sent again.
            LOG.warn("Remote daily calculation unavailable for request {}: {}", requestId, unavailable.getMessage());
            return new Released(Reply.of(503, new Problem(ErrorCode.CALCULATION_UNAVAILABLE, "The calculation service is unavailable")));
        } catch (SearchDeadline.Expired expired) {
            if (registered.cancelled()) return new Released(Reply.of(409, cancelled()));
            return new Released(Reply.of(503, new Problem(ErrorCode.CALCULATION_UNAVAILABLE, "The calculation deadline passed")));
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
