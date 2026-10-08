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
                    + "WHERE o.\"tenantId\"=? AND s.\"jobId\"=? AND o.status IN ('HELD','SELECTED')", (org.springframework.jdbc.core.RowCallbackHandler) rs ->
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
                    jdbc.update("UPDATE api_booking_offer SET status=? WHERE \"tenantId\"=? AND \"holdId\"=? AND status IN ('HELD','SELECTED')", ending.name(), tenantId, hold);
            });
            jdbc.update("UPDATE api_booking_offer_set SET status='SUPERSEDED' WHERE \"tenantId\"=? AND \"jobId\"=? AND status IN ('ACTIVE','SELECTED')", tenantId, publication.jobId());
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

    /** What a select or release did. Every outcome is final and stored as the request's answer. */
    public sealed interface HoldOutcome permits Kept, Released, NotFound, Unavailable { }
    public record Kept(PublicResponses.Hold hold) implements HoldOutcome { }
    public record Released() implements HoldOutcome { }
    public record NotFound() implements HoldOutcome { }
    public record Unavailable(String reason) implements HoldOutcome { }

    private record OfferRow(String offerId, String holdId, String offerSetId, String metroId, LocalDate serviceDate, String status,
                            String setStatus, Instant expiresAt) {
        DayKey day() { return new DayKey(metroId, serviceDate); }
    }

    /**
     * Keeps the hold of {@code offerId} and releases every other hold in its set, then completes the request: 200 with
     * the hold, 404, or 409 when the hold has expired or ended, or its set was released, superseded or already selected
     * another offer. Selecting the already selected offer again returns its hold.
     */
    public HoldOutcome select(String tenantId, String requestId, String ownerToken, String offerId, Instant now) {
        return change(tenantId, requestId, ownerToken, offerId, (chosen, offers, states) -> {
            if (!chosen.expiresAt().isAfter(now)) return new Unavailable("The hold expired");
            var hold = new PublicResponses.Hold(chosen.holdId(), chosen.offerId(), chosen.expiresAt());
            if (chosen.status().equals("SELECTED") && chosen.setStatus().equals("SELECTED")) return new Kept(hold);
            if (!chosen.setStatus().equals("ACTIVE")) return new Unavailable("The offer set is " + chosen.setStatus().toLowerCase(java.util.Locale.ROOT));
            if (!chosen.status().equals("HELD")) return new Unavailable("The hold is " + chosen.status().toLowerCase(java.util.Locale.ROOT));
            BookingDayState day = states.get(chosen.day());
            if (day == null || !day.holds().containsKey(chosen.holdId())) return new Unavailable("The hold no longer stands");
            for (OfferRow sibling : offers) if (!sibling.offerId().equals(offerId)) end(tenantId, sibling, states);
            jdbc.update("UPDATE api_booking_offer SET status='SELECTED' WHERE \"tenantId\"=? AND id=?", tenantId, offerId);
            jdbc.update("UPDATE api_booking_offer_set SET status='SELECTED' WHERE \"tenantId\"=? AND id=?", tenantId, chosen.offerSetId());
            return new Kept(hold);
        });
    }

    /** Releases every hold in the set of {@code offerId} and completes the request: 200, also when nothing was still held, or 404. */
    public HoldOutcome release(String tenantId, String requestId, String ownerToken, String offerId) {
        return change(tenantId, requestId, ownerToken, offerId, (chosen, offers, states) -> {
            for (OfferRow offer : offers) end(tenantId, Required.value(offer), states);
            jdbc.update("UPDATE api_booking_offer_set SET status='RELEASED' WHERE \"tenantId\"=? AND id=? AND status IN ('ACTIVE','SELECTED')", tenantId, chosen.offerSetId());
            return new Released();
        });
    }

    private interface Change { HoldOutcome apply(OfferRow chosen, List<OfferRow> offers, Map<DayKey, BookingDayState> states); }

    /**
     * Locks the days of the set in the one lock order, then the set, so it never waits in a cycle with a search's
     * publication, which locks days and then updates sets. Day states the change edits are written with a new version.
     */
    private HoldOutcome change(String tenantId, String requestId, String ownerToken, String offerId, Change change) {
        return store.asTenant(tenantId, () -> {
            OfferRow found = offer(tenantId, offerId);
            if (found == null) return finish(tenantId, requestId, ownerToken, new NotFound());
            Map<String, DayKey> ordered = new TreeMap<>();
            for (OfferRow offer : offers(tenantId, found.offerSetId())) ordered.put(offer.day().order(), offer.day());
            Map<DayKey, BookingDayState> states = new java.util.HashMap<>(), before = new java.util.HashMap<>();
            for (DayKey day : ordered.values()) {
                BookingDayState state = read(tenantId, Required.value(day), true).state();
                if (state != null) { states.put(Required.value(day), state); before.put(Required.value(day), state); }
            }
            jdbc.queryForList("SELECT id FROM api_booking_offer_set WHERE \"tenantId\"=? AND id=? FOR UPDATE", String.class, tenantId, found.offerSetId());
            OfferRow chosen = Required.value(offer(tenantId, offerId), "locked offer");
            HoldOutcome outcome = change.apply(chosen, offers(tenantId, chosen.offerSetId()), states);
            states.forEach((day, state) -> {
                if (!state.equals(before.get(day))) jdbc.update("UPDATE api_booking_day SET \"stateJson\"=?::jsonb,version=version+1,\"updatedAt\"=clock_timestamp() "
                        + "WHERE \"tenantId\"=? AND \"metroId\"=? AND \"serviceDate\"=?", CalculationJson.write(Required.value(state)), tenantId,
                        Required.value(day).metroId(), Date.valueOf(Required.value(day).serviceDate()));
            });
            return finish(tenantId, requestId, ownerToken, outcome);
        });
    }

    /** Ends one offer's hold if it is still held or selected: out of its day's arrangement (its moves stay), and RELEASED. */
    private void end(String tenantId, OfferRow offer, Map<DayKey, BookingDayState> states) {
        if (!offer.status().equals("HELD") && !offer.status().equals("SELECTED")) return;
        BookingDayState day = states.get(offer.day());
        if (day != null && day.holds().containsKey(offer.holdId())) states.put(offer.day(), day.without(Required.value(Set.of(offer.holdId()))));
        jdbc.update("UPDATE api_booking_offer SET status='RELEASED' WHERE \"tenantId\"=? AND id=?", tenantId, offer.offerId());
    }

    private HoldOutcome finish(String tenantId, String requestId, String ownerToken, HoldOutcome outcome) {
        switch (outcome) {
            case Kept kept -> store.complete(tenantId, requestId, ownerToken, 200, CalculationJson.write(kept.hold()));
            case Released _ -> store.complete(tenantId, requestId, ownerToken, 200, CalculationJson.write(new PublicResponses.Released(true)));
            case NotFound _ -> store.complete(tenantId, requestId, ownerToken, 404,
                    CalculationJson.write(new PublicResponses.Problem(PublicResponses.ErrorCode.NOT_FOUND, "Offer not found")));
            case Unavailable unavailable -> store.complete(tenantId, requestId, ownerToken, 409,
                    CalculationJson.write(new PublicResponses.Problem(PublicResponses.ErrorCode.HOLD_UNAVAILABLE, unavailable.reason())));
        }
        return outcome;
    }

    private static final String OFFER_COLUMNS = "SELECT o.id,o.\"holdId\",o.\"offerSetId\",s.\"metroId\",o.\"serviceDate\",o.status,s.status,s.\"expiresAt\" "
            + "FROM api_booking_offer o JOIN api_booking_offer_set s ON s.\"tenantId\"=o.\"tenantId\" AND s.id=o.\"offerSetId\" ";

    private @Nullable OfferRow offer(String tenantId, String offerId) {
        List<OfferRow> rows = Required.value(jdbc.query(OFFER_COLUMNS + "WHERE o.\"tenantId\"=? AND o.id=?", (rs, _) -> row(rs), tenantId, offerId));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<OfferRow> offers(String tenantId, String offerSetId) {
        return Required.value(jdbc.query(OFFER_COLUMNS + "WHERE o.\"tenantId\"=? AND o.\"offerSetId\"=? ORDER BY o.id", (rs, _) -> row(rs), tenantId, offerSetId));
    }

    private static OfferRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OfferRow(DatabaseFacts.string(rs, 1), DatabaseFacts.string(rs, 2), DatabaseFacts.string(rs, 3), DatabaseFacts.string(rs, 4), date(rs, 5),
                DatabaseFacts.string(rs, 6), DatabaseFacts.string(rs, 7), Required.value(DatabaseFacts.timestamp(rs, 8).toInstant()));
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
