package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.DatabaseFacts;
import dev.waterflex.scheduler.Required;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The booking part of the thin store: each metro-day's common arrangement and active holds, and the offer sets and
 * offers that created them. Every call runs through {@link PublicApiStore#asTenant}, so row-level security applies.
 */
@Component
public class BookingStore {
    /** A metro-day; holds are kept per metro-day because routes are. */
    public record DayKey(String metroId, LocalDate serviceDate) {
        /** Orders locks: every caller locks days in this one order, so two bookings never wait on each other in a cycle. */
        String order() { return metroId.length() + ":" + metroId + serviceDate; }
    }

    /** A metro-day as stored: version 0 and no state when nothing was ever booked on it. */
    public record StoredDay(int version, @Nullable BookingDayState state) {
        public StoredDay {
            if (version < 0 || (version == 0) != (state == null)) throw new IllegalArgumentException("A day has state exactly when its version is positive");
        }
        static final StoredDay NONE = new StoredDay(0, null);
    }

    public record NewOffer(String offerId, String holdId, LocalDate serviceDate, String technicianId, Instant windowStart, Instant windowEnd) { }

    /** A new offer set and the request it answers. */
    public record Publication(String offerSetId, String requestId, String jobId, String metroId, Instant expiresAt,
                              PublicResponses.OfferSet body, List<NewOffer> offers) {
        public Publication { offers = Required.value(List.copyOf(offers)); }
    }

    /** Another booking changed one of the days after it was read; nothing was written. */
    public static final class DayMoved extends RuntimeException {
        private static final long serialVersionUID = 1L;
        DayMoved(DayKey day) { super("Booking state of " + day.metroId() + " on " + day.serviceDate() + " changed during the search"); }
    }

    private final PublicApiStore store;
    private final JdbcTemplate jdbc;

    public BookingStore(PublicApiStore store, JdbcTemplate jdbc) { this.store = store; this.jdbc = jdbc; }

    public static String newOfferSetId() { return "set-" + UUID.randomUUID(); }

    /** The stored state of each date; a date never booked is {@link StoredDay#NONE}. Persisted JSON is validated again. */
    public Map<LocalDate, StoredDay> days(String tenantId, String metroId, Collection<LocalDate> dates) {
        return store.asTenant(tenantId, () -> {
            Map<LocalDate, StoredDay> days = new TreeMap<>();
            for (LocalDate date : dates) days.put(Required.value(date), read(tenantId, new DayKey(metroId, Required.value(date)), false));
            return days;
        });
    }

    /** Where this job holds offers, by metro-day, so a new search can end those holds wherever they are. */
    public Map<DayKey, Set<String>> heldBy(String tenantId, String jobId) {
        return store.asTenant(tenantId, () -> {
            Map<DayKey, Set<String>> held = new java.util.HashMap<>();
            jdbc.query("SELECT s.\"metroId\",o.\"serviceDate\",o.\"holdId\" FROM api_booking_offer o JOIN api_booking_offer_set s ON s.\"tenantId\"=o.\"tenantId\" AND s.id=o.\"offerSetId\" "
                    + "WHERE o.\"tenantId\"=? AND s.\"jobId\"=? AND o.status='HELD'", (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                    held.computeIfAbsent(new DayKey(DatabaseFacts.string(rs, 1), date(rs, 2)), _ -> new TreeSet<>()).add(DatabaseFacts.string(rs, 3)), tenantId, jobId);
            return held;
        });
    }

    /**
     * Locks every day read, in one order, and publishes atomically: the days' new states, the ended holds, the job's
     * superseded offer sets, the new offer set and its offers, and the request's answer (201). Throws {@link DayMoved}
     * and writes nothing when a day's version is not the one the search read.
     */
    public void publish(String tenantId, String ownerToken, Map<DayKey, Integer> read, Map<DayKey, BookingDayState> writes,
                        Map<String, BookingReconciliation.Ending> ended, Publication publication) {
        if (!read.keySet().containsAll(writes.keySet())) throw new IllegalArgumentException("Every written day must have been read");
        store.asTenant(tenantId, () -> {
            Map<String, DayKey> ordered = new TreeMap<>();
            for (DayKey day : read.keySet()) ordered.put(Required.value(day).order(), Required.value(day));
            for (DayKey day : ordered.values()) {
                jdbc.update("INSERT INTO api_booking_day (\"tenantId\",\"metroId\",\"serviceDate\",version,\"stateJson\") VALUES (?,?,?,0,NULL) ON CONFLICT DO NOTHING",
                        tenantId, day.metroId(), Date.valueOf(day.serviceDate()));
                int version = read(tenantId, day, true).version();
                if (version != Required.value(read.get(day), "read version")) throw new DayMoved(day);
            }
            writes.forEach((day, state) -> jdbc.update("UPDATE api_booking_day SET \"stateJson\"=?::jsonb,version=version+1,\"updatedAt\"=clock_timestamp() "
                    + "WHERE \"tenantId\"=? AND \"metroId\"=? AND \"serviceDate\"=?", CalculationJson.write(Required.value(state)), tenantId, Required.value(day).metroId(),
                    Date.valueOf(Required.value(day).serviceDate())));
            ended.forEach((hold, ending) -> {
                if (ending != BookingReconciliation.Ending.EXPIRED)
                    jdbc.update("UPDATE api_booking_offer SET status=? WHERE \"tenantId\"=? AND \"holdId\"=? AND status='HELD'", ending.name(), tenantId, hold);
            });
            jdbc.update("UPDATE api_booking_offer_set SET status='SUPERSEDED' WHERE \"tenantId\"=? AND \"jobId\"=? AND status='ACTIVE'", tenantId, publication.jobId());
            String body = CalculationJson.write(publication.body());
            jdbc.update("INSERT INTO api_booking_offer_set (\"tenantId\",id,\"requestId\",\"jobId\",\"metroId\",\"expiresAt\",status,\"offerSetJson\") VALUES (?,?,?,?,?,?,'ACTIVE',?::jsonb)",
                    tenantId, publication.offerSetId(), publication.requestId(), publication.jobId(), publication.metroId(), Timestamp.from(publication.expiresAt()), body);
            for (NewOffer offer : publication.offers())
                jdbc.update("INSERT INTO api_booking_offer (\"tenantId\",id,\"offerSetId\",\"holdId\",\"serviceDate\",\"technicianId\",\"windowStart\",\"windowEnd\",status) VALUES (?,?,?,?,?,?,?,?,'HELD')",
                        tenantId, offer.offerId(), publication.offerSetId(), offer.holdId(), Date.valueOf(offer.serviceDate()), offer.technicianId(),
                        Timestamp.from(offer.windowStart()), Timestamp.from(offer.windowEnd()));
            store.complete(tenantId, publication.requestId(), ownerToken, 201, body);
            return Boolean.TRUE;
        });
    }

    private StoredDay read(String tenantId, DayKey day, boolean lock) {
        List<StoredDay> rows = Required.value(jdbc.query("SELECT version,\"stateJson\"::text FROM api_booking_day WHERE \"tenantId\"=? AND \"metroId\"=? AND \"serviceDate\"=?"
                + (lock ? " FOR UPDATE" : ""), (rs, _) -> {
                    int version = DatabaseFacts.integer(rs, 1);
                    String json = rs.getString(2);
                    return new StoredDay(version, json == null ? null : PublicRequests.read(json, BookingDayState.class));
                }, tenantId, day.metroId(), Date.valueOf(day.serviceDate())));
        if (rows.isEmpty()) return StoredDay.NONE;
        return Required.value(rows.getFirst());
    }

    private static LocalDate date(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        return Required.value(rs.getObject(column, LocalDate.class), "date column " + column);
    }
}
