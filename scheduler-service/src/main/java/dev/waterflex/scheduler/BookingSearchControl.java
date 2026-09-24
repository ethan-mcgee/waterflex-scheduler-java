package dev.waterflex.scheduler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Durable cancellation is serialized against publication, including across scheduler instances. */
@Component
public final class BookingSearchControl {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ReservationLifecycleService lifecycle;
    private final SearchAdmission admission;
    private final Map<String, SearchDeadline> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService workers = Required.value(Executors.newScheduledThreadPool(2, work -> {
        Thread thread = new Thread(work, "booking-search-control"); thread.setDaemon(true); return thread;
    }));
    public BookingSearchControl(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ReservationLifecycleService lifecycle, SearchAdmission admission) {
        this.jdbc = jdbc; transaction = new TransactionTemplate(manager); transaction.setTimeout(1);
        this.lifecycle = lifecycle; this.admission = admission;
    }
    @PostConstruct void start() {
        workers.scheduleWithFixedDelay(this::poll, 100, 100, TimeUnit.MILLISECONDS);
        workers.scheduleWithFixedDelay(this::cleanup, 1, 1, TimeUnit.SECONDS);
    }
    @PreDestroy void stop() { active.values().forEach(value -> Required.value(value).cancel()); workers.shutdownNow(); }

    public Scope open(String id, String jobId, SearchDeadline deadline) {
        token(id);
        if (active.size() >= 64 || active.putIfAbsent(id, deadline) != null) throw new SearchAdmission.Busy("Search request already active or service busy");
        try {
            transaction.executeWithoutResult(_ -> {
                SearchDeadline.database(jdbc);
                requireJob(jobId);
                jdbc.update("INSERT INTO booking_search_request (id,\"jobId\",\"deadlineAt\") VALUES (?,?,?) ON CONFLICT (id) DO NOTHING",
                        id, jobId, Timestamp.from(Instant.now().plusNanos(deadline.remainingNanos())));
                if (Required.query(jdbc, "SELECT count(*) FROM booking_search_request WHERE id=? AND \"jobId\"=? AND \"cancelledAt\" IS NULL AND \"deadlineAt\">clock_timestamp() AND \"offerSetId\" IS NULL", Integer.class, id, jobId) != 1)
                    throw new SearchDeadline.Expired();
            });
            deadline.cancellationGuard(() -> guard(id, jobId), set -> {
                guard(id, jobId);
                if (jdbc.update("UPDATE booking_search_request SET \"offerSetId\"=? WHERE id=? AND \"jobId\"=?", set, id, jobId) != 1)
                    throw new SearchDeadline.Expired();
            });
            return new Scope(id);
        } catch (RuntimeException failure) { active.remove(id, deadline); throw failure; }
    }
    private void guard(String id, String jobId) {
        SearchDeadline.database(jdbc);
        var rows = jdbc.query("SELECT \"cancelledAt\" IS NOT NULL OR \"deadlineAt\"<=clock_timestamp() FROM booking_search_request WHERE id=? AND \"jobId\"=? FOR UPDATE",
                (rs, _) -> Required.bool(rs, 1), id, jobId);
        if (rows.size() != 1 || Boolean.TRUE.equals(rows.getFirst())) throw new SearchDeadline.Expired();
    }
    public void cancel(String id, String jobId) {
        token(id);
        transaction.executeWithoutResult(_ -> {
            requireJob(jobId);
            if (jdbc.update("INSERT INTO booking_search_request (id,\"jobId\",\"deadlineAt\",\"cancelledAt\") VALUES (?,?,clock_timestamp()+interval '5 seconds',clock_timestamp()) ON CONFLICT (id) DO UPDATE SET \"cancelledAt\"=COALESCE(booking_search_request.\"cancelledAt\",clock_timestamp()) WHERE booking_search_request.\"jobId\"=EXCLUDED.\"jobId\"", id, jobId) != 1)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Search token belongs to another job");
        });
        SearchDeadline running = active.get(id); if (running != null) running.cancel();
    }
    private void requireJob(String id) {
        if (jdbc.query("SELECT id FROM job WHERE id=?", (rs, _) -> Required.string(rs, 1), id).size() != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Search job does not exist");
    }
    public void acknowledge(String id, String jobId) {
        token(id);
        if (jdbc.update("UPDATE booking_search_request SET \"acknowledgedAt\"=clock_timestamp() WHERE id=? AND \"jobId\"=? AND \"cancelledAt\" IS NULL AND \"deadlineAt\">clock_timestamp()", id, jobId) != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Appointment search expired or was cancelled before delivery");
    }
    private void poll() {
        if (active.isEmpty()) return;
        try {
            List<String> ids = new ArrayList<>(active.keySet());
            jdbc.query("SELECT id FROM booking_search_request WHERE id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ") AND (\"cancelledAt\" IS NOT NULL OR \"deadlineAt\"<=clock_timestamp())",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> { SearchDeadline running = active.get(Required.string(rs, 1)); if (running != null) running.cancel(); }, ids.toArray(new @org.jspecify.annotations.Nullable Object[0]));
        } catch (RuntimeException failure) {
            // A failed cancellation read cannot permit publication: the locked guard still fails closed.
            org.slf4j.LoggerFactory.getLogger(BookingSearchControl.class).debug("Cancellation polling unavailable", failure);
        }
    }
    private record Cleanup(String id, String jobId, String offerId) { }
    private void cleanup() {
        try {
            jdbc.update("UPDATE booking_search_request SET \"cancelledAt\"=clock_timestamp() WHERE \"offerSetId\" IS NOT NULL AND \"acknowledgedAt\" IS NULL AND \"cancelledAt\" IS NULL AND \"deadlineAt\"<=clock_timestamp()");
            var pending = jdbc.query("SELECT r.id,r.\"jobId\",o.id FROM booking_search_request r JOIN booking_offer_set s ON s.id=r.\"offerSetId\" JOIN LATERAL (SELECT id FROM booking_offer WHERE \"offerSetId\"=s.id ORDER BY id LIMIT 1) o ON true WHERE r.\"cancelledAt\" IS NOT NULL AND r.\"cleanedAt\" IS NULL ORDER BY r.\"cancelledAt\" LIMIT 8",
                    (rs, _) -> new Cleanup(Required.string(rs, 1), Required.string(rs, 2), Required.string(rs, 3)));
            for (Cleanup item : pending) {
                try {
                    SearchDeadline deadline = new SearchDeadline(Required.value(java.time.Duration.ofSeconds(5)));
                    try (var _ = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline)) {
                        deadline.within(() -> {
                            if (Required.query(jdbc, "SELECT status::text FROM job WHERE id=?", String.class, item.jobId()).equals("PENDING")) {
                                lifecycle.release(item.jobId(), item.offerId());
                            }
                            jdbc.update("UPDATE booking_search_request SET \"cleanedAt\"=clock_timestamp() WHERE id=?", item.id());
                            return true;
                        });
                    }
                } catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(BookingSearchControl.class).warn("Cancelled search {} release requires retry", item.id(), failure);
                }
            }
            jdbc.update("DELETE FROM booking_search_request WHERE \"deadlineAt\"<clock_timestamp()-interval '1 day' AND (\"cancelledAt\" IS NULL OR \"offerSetId\" IS NULL OR \"cleanedAt\" IS NOT NULL)");
        } catch (RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(BookingSearchControl.class).debug("Cancelled search cleanup unavailable", failure); }
    }
    static void token(String id) {
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (RuntimeException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search request token"); }
    }
    public final class Scope implements AutoCloseable {
        private final String id;
        private Scope(String id) { this.id = id; }
        @Override public void close() { active.remove(id); }
    }
}
