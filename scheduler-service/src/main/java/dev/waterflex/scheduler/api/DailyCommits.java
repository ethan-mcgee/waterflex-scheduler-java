package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.ScheduleCutoff;
import dev.waterflex.scheduler.api.DailyProposals.Reply;
import dev.waterflex.scheduler.api.PublicApiStore.*;
import dev.waterflex.scheduler.api.PublicRequests.CommitRequest;
import dev.waterflex.scheduler.api.PublicResponses.*;
import dev.waterflex.scheduler.api.PublicTypes.Key;
import dev.waterflex.scheduler.api.PublicTypes.TechnicianDayVersion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * POST /api/v1/daily/proposals/{proposalId}/commit. The host sends its current timestamps for every technician-day
 * the proposal covers; when they all match the ones the proposal was computed from, the facts are unchanged, so the
 * proposal is still valid. The scheduler records a receipt and returns every assignment the host must write. A
 * commit that is refused changes nothing, and its answer is stored and replayed like any other final answer.
 */
@Service
public class DailyCommits {
    private final PublicApiStore store;
    private final Clock clock;

    @Autowired
    public DailyCommits(PublicApiStore store) { this(store, Required.value(Clock.systemUTC())); }

    public DailyCommits(PublicApiStore store, Clock clock) { this.store = store; this.clock = clock; }

    public Reply commit(String tenantId, String proposalId, String body) {
        CommitRequest request;
        try {
            Input.id(proposalId, "proposalId");
            request = PublicRequests.read(body, CommitRequest.class);
        } catch (IllegalArgumentException invalid) {
            return Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, DailyProposals.invalidRequest(invalid)));
        }
        String fingerprint = DailyProposals.sha256(proposalId + "\n" + CalculationJson.write(request));
        return switch (store.claim(tenantId, request.requestId(), Operation.DAILY_COMMIT, fingerprint)) {
            case Replay replay -> replayed(replay);
            case Conflict _ -> Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, "requestId was already used with a different request"));
            case Busy _ -> Reply.of(429, new Problem(ErrorCode.BUSY, "A request with this requestId is still running"));
            case Started started -> run(tenantId, proposalId, request, started.ownerToken());
        };
    }

    private Reply run(String tenantId, String proposalId, CommitRequest request, String owner) {
        try {
            return switch (store.commitDaily(tenantId, request.requestId(), owner, proposalId,
                    locked -> decide(locked, request, Required.value(clock.instant())))) {
                case Committed committed -> Reply.of(200, committed.receipt());
                case Refused refused -> new Reply(refused.status(), refused.json(), null);
            };
        } catch (RuntimeException unexpected) {
            store.release(tenantId, request.requestId(), owner);
            throw unexpected;
        }
    }

    /** A stored answer is validated into its typed body before it is sent again. */
    private static Reply replayed(Replay replay) {
        Object body;
        try {
            if (replay.status() == 200) body = PublicRequests.read(replay.json(), CommitReceipt.class);
            else if (CalculationJson.tree(replay.json()).has("changed")) body = PublicRequests.read(replay.json(), StaleProblem.class);
            else body = PublicRequests.read(replay.json(), Problem.class);
        } catch (IllegalArgumentException invalid) { throw new IllegalStateException("A stored response failed validation", invalid); }
        return Reply.of(replay.status(), body);
    }

    /** Judges a locked proposal. Every refusal is final: repeating the same request meets the same facts. */
    static CommitOutcome decide(@Nullable Locked locked, CommitRequest request, Instant now) {
        if (locked == null) return refused(404, ErrorCode.NOT_FOUND, "Proposal not found");
        StoredProposal stored = locked.proposal();
        String receiptId = locked.receiptId();
        if (receiptId != null) return refused(409, ErrorCode.NOT_COMMITTABLE, "Proposal was already committed as " + receiptId);
        if (stored.status() != ProposalStatus.PROPOSED) return refused(409, ErrorCode.NOT_COMMITTABLE, "Proposal is " + stored.status());
        Decision decision = stored.proposal().decision();
        if (decision != Decision.IMPROVED)
            return refused(409, ErrorCode.NOT_COMMITTABLE, "Only IMPROVED proposals can be committed; this one is " + decision);
        Map<Key, TechnicianDayVersion> recorded = new TreeMap<>(DailyCommits::compare);
        for (TechnicianDayVersion day : stored.technicianDays()) recorded.put(day.key(), day);
        Map<Key, TechnicianDayVersion> submitted = new TreeMap<>(DailyCommits::compare);
        for (TechnicianDayVersion day : request.technicianDays()) submitted.put(day.key(), day);
        if (!recorded.keySet().equals(submitted.keySet())) {
            TreeSet<String> missing = new TreeSet<>(), extra = new TreeSet<>();
            for (TechnicianDayVersion day : stored.technicianDays()) if (!submitted.containsKey(day.key())) missing.add(label(day.key()));
            for (TechnicianDayVersion day : request.technicianDays()) if (!recorded.containsKey(day.key())) extra.add(label(day.key()));
            return refused(400, ErrorCode.INVALID_REQUEST, "technicianDays must list exactly the technician-days the proposal covers; missing "
                    + missing + ", not covered " + extra);
        }
        if (ScheduleCutoff.frozen(stored.serviceDate(), now, Required.value(ZoneId.of(stored.timeZone()))))
            return refused(422, ErrorCode.INCOMPLETE_FACTS, "Routes for " + stored.serviceDate() + " are frozen from 6 a.m. local time");
        List<TechnicianDayVersion> changed = new ArrayList<>();
        recorded.forEach((key, day) -> {
            if (!day.lastModified().equals(Required.value(submitted.get(key), "submitted technician-day").lastModified())) changed.add(day);
        });
        if (!changed.isEmpty())
            return new Refused(409, CalculationJson.write(new StaleProblem("STALE", "Technician-day changed since the proposal was computed",
                    Required.value(List.copyOf(changed)))));
        List<Assignment> assignments = new ArrayList<>();
        for (PlannedRoute route : stored.proposal().routes())
            for (PlannedStop stop : route.stops())
                assignments.add(new Assignment(stop.appointmentId(), route.technicianId(), route.serviceDate(), stop.sequence(), stop.plannedStart(), stop.plannedEnd()));
        return new Committed(new CommitReceipt(PublicApiStore.newReceiptId(), Required.value(List.copyOf(assignments)),
                Required.value(List.copyOf(recorded.values()))));
    }

    private static Refused refused(int status, ErrorCode code, String message) {
        return new Refused(status, CalculationJson.write(new Problem(code, message)));
    }

    private static int compare(Key left, Key right) {
        int byTechnician = left.technicianId().compareTo(right.technicianId());
        return byTechnician != 0 ? byTechnician : left.serviceDate().compareTo(right.serviceDate());
    }

    private static String label(Key key) { return key.technicianId() + " on " + key.serviceDate(); }
}
