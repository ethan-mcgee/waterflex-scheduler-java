package dev.waterflex.scheduler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Database-owned work survives HTTP disconnects; expired workers cannot publish. */
@Service
public final class DurableBookingSearch {
    public record Start(String jobId, String requestId, boolean refresh) {
        public Start { jobId = RequestChecks.text(jobId, "jobId"); BookingSearchControl.token(requestId); }
    }
    public record Status(String id, String jobId, String state, String phase, long elapsedMs,
            @Nullable Long queueMs, long completedWork, @Nullable String stopReason, List<BookingService.Offer> offers, @Nullable Long bestCostDeltaCents) { }
    private record Saved(Status status, @Nullable String offerSetId) { }
    private record Work(String id, String jobId, boolean refresh) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BookingCoordinator coordinator;
    private final SearchAdmission admission;
    private final BookingSearchControl control;
    private final String owner = Required.value(UUID.randomUUID().toString());
    private final Map<String, SearchDeadline> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService workers = Required.value(Executors.newScheduledThreadPool(3, work -> {
        Thread thread = new Thread(work, "durable-booking-search"); thread.setDaemon(true); return thread;
    }));

    public DurableBookingSearch(JdbcTemplate jdbc, PlatformTransactionManager manager, BookingCoordinator coordinator,
            SearchAdmission admission, BookingSearchControl control) {
        // Polling and lease maintenance also need bounded queries outside publication transactions.
        this.jdbc = new JdbcTemplate(Required.value(jdbc.getDataSource(), "durable search database"));
        this.jdbc.setQueryTimeout(3);
        this.tx = new TransactionTemplate(manager); tx.setTimeout(3);
        this.coordinator = coordinator; this.admission = admission; this.control = control;
    }
    @PostConstruct void startWorkers() {
        workers.scheduleWithFixedDelay(this::heartbeat, 1, 1, TimeUnit.SECONDS);
        for (int i = 0; i < 2; i++) workers.scheduleWithFixedDelay(this::next, 250, 250, TimeUnit.MILLISECONDS);
    }
    @PreDestroy void stopWorkers() { active.values().forEach(deadline -> Required.value(deadline).cancel()); workers.shutdownNow(); }

    public Status start(Start request) {
        String id = Required.value(tx.execute(_ -> {
            // Serialize admission counts across instances; the transaction performs no search or routing.
            jdbc.queryForList("SELECT pg_advisory_xact_lock(209292200)");
            if (jdbc.query("SELECT id FROM job WHERE id=? AND status='PENDING' FOR UPDATE", (rs, _) -> dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), request.jobId()).size() != 1)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not pending");
            var prior = jdbc.query("SELECT \"jobId\" FROM booking_search_request WHERE id=?", (rs, _) -> dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), request.requestId());
            if (!prior.isEmpty()) {
                if (!request.jobId().equals(prior.getFirst())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Search token belongs to another job");
                return request.requestId();
            }
            var running = jdbc.query("SELECT id FROM booking_search_request WHERE \"jobId\"=? AND state IN ('QUEUED','RUNNING')", (rs, _) -> dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), request.jobId());
            if (!running.isEmpty()) return Required.value(running.getFirst());
            if (dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT count(*) FROM booking_search_request WHERE state IN ('QUEUED','RUNNING')", Integer.class) >= 16)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Search queue is full");
            jdbc.update("INSERT INTO booking_search_request (id,\"jobId\",\"deadlineAt\",state,refresh) VALUES (?,?,clock_timestamp()+interval '2 minutes','QUEUED',?)",
                    request.requestId(), request.jobId(), request.refresh());
            return request.requestId();
        }));
        return status(id, request.jobId());
    }

    public Status status(String id, String jobId) {
        BookingSearchControl.token(id);
        var rows = jdbc.query("SELECT state,phase,GREATEST(0,EXTRACT(EPOCH FROM (COALESCE(\"finishedAt\",clock_timestamp())-\"createdAt\"))*1000)::bigint,\"queueMs\",\"completedWork\",\"stopReason\",\"offerSetId\",\"bestCostDeltaCents\" FROM booking_search_request WHERE id=? AND \"jobId\"=? AND state IS NOT NULL",
                (rs, _) -> new Saved(new Status(id, jobId, dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), dev.waterflex.scheduler.DatabaseFacts.string(rs, 2),
                        dev.waterflex.scheduler.DatabaseFacts.longValue(rs, 3), dev.waterflex.scheduler.DatabaseFacts.nullableLong(rs, 4), dev.waterflex.scheduler.DatabaseFacts.longValue(rs, 5), rs.getString(6), Required.value(List.of()), dev.waterflex.scheduler.DatabaseFacts.nullableLong(rs, 8)), rs.getString(7)), id, jobId);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Search not found");
        Saved saved = Required.value(rows.getFirst());
        Status result = saved.status();
        String setId = saved.offerSetId();
        if (result.state().equals("AVAILABLE")) {
            if (setId == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Published search has no reservation");
            var offers = jdbc.query("SELECT o.id,o.\"serviceDate\",o.\"windowStart\",o.\"windowEnd\",o.\"expiresAt\" FROM booking_offer o JOIN booking_offer_set s ON s.id=o.\"offerSetId\" WHERE s.id=? AND s.\"supersededAt\" IS NULL AND o.\"expiresAt\">clock_timestamp() ORDER BY o.id",
                    (offer, _) -> new BookingService.Offer(dev.waterflex.scheduler.DatabaseFacts.string(offer, 1), Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(offer, 2).toLocalDateTime().toLocalDate().toString()),
                            Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(offer, 3).toInstant()), Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(offer, 4).toInstant()), Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(offer, 5).toInstant())), setId);
            result = new Status(id, jobId, offers.isEmpty() ? "INCOMPLETE" : "AVAILABLE", result.phase(), result.elapsedMs(), result.queueMs(), result.completedWork(),
                    offers.isEmpty() ? "OFFER_EXPIRED_OR_RELEASED" : result.stopReason(), Required.value(offers), result.bestCostDeltaCents());
            if (!offers.isEmpty()) control.acknowledge(id, jobId);
        }
        return result;
    }

    private void next() {
        try {
            var rows = jdbc.query("UPDATE booking_search_request SET state='RUNNING',owner=?,\"leaseUntil\"=clock_timestamp()+interval '15 seconds',\"startedAt\"=clock_timestamp(),phase='SNAPSHOT',\"queueMs\"=(EXTRACT(EPOCH FROM (clock_timestamp()-\"createdAt\"))*1000)::bigint WHERE id=(SELECT id FROM booking_search_request WHERE state='QUEUED' AND \"cancelledAt\" IS NULL AND \"deadlineAt\">clock_timestamp() ORDER BY \"createdAt\",id FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING id,\"jobId\",refresh",
                    (rs, _) -> new Work(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), dev.waterflex.scheduler.DatabaseFacts.string(rs, 2), dev.waterflex.scheduler.DatabaseFacts.bool(rs, 3)), owner);
            if (!rows.isEmpty()) run(Required.value(rows.getFirst()));
        } catch (RuntimeException failure) { log("Durable search worker unavailable", failure); }
    }
    private void run(Work work) {
        SearchDeadline deadline = new SearchDeadline(Required.value(Duration.ofMinutes(2))).durable();
        active.put(work.id(), deadline);
        deadline.cancellationGuard(() -> guard(work.id()), setId -> {
            guard(work.id());
            jdbc.update("UPDATE booking_search_request SET \"offerSetId\"=? WHERE id=? AND owner=?", setId, work.id(), owner);
        });
        try {
            deadline.within(() -> {
                try (var lease = admission.acquire(SearchAdmission.Kind.BOOKING, deadline)) {
                    jdbc.update("UPDATE booking_search_request SET \"queueMs\"=\"queueMs\"+? WHERE id=? AND owner=?", lease.queueMillis(), work.id(), owner);
                    var result = coordinator.offers(work.jobId(), work.refresh());
                    String state = !result.offers().offers().isEmpty() ? "AVAILABLE" : result.completed() ? "NO_CANDIDATE" : "INCOMPLETE";
                    // A reused set must also be owned by this request for cancellation/cleanup.
                    if (!result.offers().offers().isEmpty()) jdbc.update("UPDATE booking_search_request SET \"offerSetId\"=(SELECT \"offerSetId\" FROM booking_offer WHERE id=?) WHERE id=? AND owner=?",
                            Required.value(result.offers().offers().getFirst()).offerId(), work.id(), owner);
                    finish(work.id(), state, result.stopReason(), deadline);
                    return true;
                }
            });
        } catch (SearchDeadline.Expired failure) { finish(work.id(), "INCOMPLETE", "WORK_LIMIT_OR_CANCELLATION", deadline); }
          catch (RuntimeException failure) { log("Durable search failed: " + work.id(), failure); finish(work.id(), "FAILED", Required.value(failure.getClass().getSimpleName()), deadline); }
        finally { active.remove(work.id()); }
    }
    private void guard(String id) {
        if (dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT count(*) FROM (SELECT id FROM booking_search_request WHERE id=? AND owner=? AND state='RUNNING' AND \"cancelledAt\" IS NULL AND \"leaseUntil\">clock_timestamp() AND \"deadlineAt\">clock_timestamp() FOR UPDATE) owned", Integer.class, id, owner) != 1)
            throw new SearchDeadline.Expired();
    }
    private void finish(String id, String state, String reason, SearchDeadline deadline) {
        jdbc.update("UPDATE booking_search_request SET state=?,\"stopReason\"=?,phase='FINISHED',\"completedWork\"=?,\"bestCostDeltaCents\"=?,\"finishedAt\"=clock_timestamp(),\"deadlineAt\"=CASE WHEN ?='AVAILABLE' THEN clock_timestamp()+interval '10 minutes' ELSE \"deadlineAt\" END WHERE id=? AND owner=? AND state='RUNNING' AND \"cancelledAt\" IS NULL AND \"leaseUntil\">clock_timestamp()",
                state, reason, deadline.completedWork(), deadline.bestCostDeltaCents(), state, id, owner);
    }
    private void heartbeat() {
        try {
            for (var entry : active.entrySet()) {
                SearchDeadline deadline = Required.value(entry.getValue());
                if (jdbc.update("UPDATE booking_search_request SET \"leaseUntil\"=clock_timestamp()+interval '15 seconds',phase=?,\"completedWork\"=?,\"bestCostDeltaCents\"=? WHERE id=? AND owner=? AND state='RUNNING' AND \"cancelledAt\" IS NULL AND \"deadlineAt\">clock_timestamp() AND \"leaseUntil\">clock_timestamp()",
                        deadline.phase(), deadline.completedWork(), deadline.bestCostDeltaCents(), entry.getKey(), owner) != 1) deadline.cancel();
            }
            // Lost ownership is terminal, never silently rerun a search against a stale snapshot.
            jdbc.update("UPDATE booking_search_request SET state='INCOMPLETE',phase='FINISHED',\"stopReason\"='WORKER_LOST_OR_WORK_LIMIT',\"finishedAt\"=clock_timestamp(),\"cancelledAt\"=clock_timestamp() WHERE state IN ('QUEUED','RUNNING') AND (\"deadlineAt\"<=clock_timestamp() OR (state='RUNNING' AND \"leaseUntil\"<=clock_timestamp()))");
        } catch (RuntimeException failure) { log("Durable search heartbeat unavailable", failure); }
    }
    private static void log(String message, RuntimeException failure) {
        org.slf4j.LoggerFactory.getLogger(DurableBookingSearch.class).warn(message, failure);
    }

    @RestController
    public static final class Controller {
        private final DurableBookingSearch searches;
        private final BookingSearchControl control;
        public Controller(DurableBookingSearch searches, BookingSearchControl control) { this.searches = searches; this.control = control; }
        @PostMapping("/v1/booking-searches") public Status start(@RequestBody Start request) { return searches.start(request); }
        @GetMapping("/v1/booking-searches/{id}") public Status status(@PathVariable String id, @RequestParam String jobId) { return searches.status(id, RequestChecks.text(jobId, "jobId")); }
        @DeleteMapping("/v1/booking-searches/{id}") public Status cancel(@PathVariable String id, @RequestParam String jobId) {
            control.cancel(id, RequestChecks.text(jobId, "jobId")); return searches.status(id, jobId);
        }
    }
}
