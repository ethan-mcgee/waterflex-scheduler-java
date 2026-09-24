package dev.waterflex.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.waterflex.scheduler.BookingSnapshot.Arrangement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** Storage for validated common arrangements. Mutations require the caller's short commit transaction. */
@Component
public final class ReservationStore {
    public record Locked(String id, String metroId, LocalDate day, int version, @Nullable ReservationState state) { }
    private record Dependency(String holdId, String technicianId, String serviceId) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    public ReservationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Call after acquiring all affected technician/day locks in sorted order. */
    public List<Locked> lock(String metroId, Collection<LocalDate> dates) {
        transaction();
        List<Locked> locked = new ArrayList<>();
        for (LocalDate date : new TreeSet<>(dates)) {
            SearchDeadline.database(jdbc);
            jdbc.update("INSERT INTO reservation_arrangement (id,\"metroId\",\"serviceDate\",version,\"updatedAt\") VALUES (?,?,?,0,CURRENT_TIMESTAMP) ON CONFLICT (\"metroId\",\"serviceDate\") DO NOTHING",
                    UUID.randomUUID().toString(), metroId, stamp(Required.value(date)));
            var rows = jdbc.query("SELECT id,version,state::text FROM reservation_arrangement WHERE \"metroId\"=? AND \"serviceDate\"=? FOR UPDATE",
                    (rs, _) -> {
                        String saved = rs.getString(3);
                        int version = Required.integer(rs, 2);
                        if (version < 0 || (saved == null && version != 0)) throw conflict("Invalid reservation version/state");
                        return new Locked(Required.string(rs, 1), metroId, Required.value(date), version,
                                saved == null ? null : ReservationState.decode(json, saved));
                    }, metroId, stamp(Required.value(date)));
            if (rows.size() != 1) throw conflict("Reservation lock row missing");
            locked.add(Required.value(rows.getFirst()));
        }
        return Required.value(List.copyOf(locked));
    }

    public Locked save(Locked locked, BookingSnapshot.Day day, BookingSnapshot.Rates rates, Arrangement arrangement,
            Map<String, BookingSnapshot.Visit> visits, Map<String, ReservationState.Hold> holds,
            String configuration, String routingIdentity) {
        transaction();
        SearchDeadline.checkpoint();
        ReservationState state = ReservationState.validate(day, rates, arrangement, visits, holds, configuration, routingIdentity);
        var live = jdbc.query("SELECT h.id FROM slot_hold h JOIN LATERAL (SELECT \"depotId\" FROM technician_depot_assignment WHERE \"technicianId\"=h.\"technicianId\" AND \"effectiveDate\"<=h.\"serviceDate\" ORDER BY \"effectiveDate\" DESC LIMIT 1) a ON true JOIN depot p ON p.id=a.\"depotId\" WHERE p.\"metroId\"=? AND h.\"serviceDate\"=? AND h.\"releasedAt\" IS NULL AND h.\"expiresAt\">clock_timestamp()",
                (rs, _) -> Required.string(rs, 1), locked.metroId(), stamp(locked.day()));
        if (!new HashSet<>(live).equals(holds.keySet())) throw conflict("Common arrangement must include every active reservation");
        // Never persist an arrangement against guessed or stale schedule versions.
        for (var entry : state.scheduleVersions().entrySet()) {
            long current = Required.query(jdbc, "SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=?",
                    Long.class, entry.getKey(), stamp(locked.day()));
            if (current != entry.getValue()) throw conflict("Schedule changed before reservation commit");
        }
        for (var entry : holds.entrySet()) {
            ReservationState.Hold expected = Required.value(entry.getValue());
            var current = jdbc.query("SELECT h.\"jobId\",h.\"offerToken\",h.\"expiresAt\",o.\"overtimeAuthorized\" FROM slot_hold h JOIN booking_offer o ON o.id=h.\"offerToken\" AND o.\"jobId\"=h.\"jobId\" WHERE h.id=? AND h.\"serviceDate\"=? AND h.\"releasedAt\" IS NULL AND h.\"expiresAt\">clock_timestamp()",
                    (rs, _) -> new ReservationState.Hold(Required.string(rs, 1), Required.string(rs, 2),
                            Required.value(Required.timestamp(rs, 3).toInstant()), Required.bool(rs, 4)), entry.getKey(), stamp(locked.day()));
            if (current.size() != 1 || !expected.equals(current.getFirst())) throw conflict("Reservation changed before commit");
        }
        SearchDeadline.database(jdbc);
        int version = Math.incrementExact(locked.version());
        if (jdbc.update("UPDATE reservation_arrangement SET version=?,state=?::jsonb,\"updatedAt\"=CURRENT_TIMESTAMP WHERE id=? AND version=? AND \"metroId\"=? AND \"serviceDate\"=?",
                version, state.encode(json), locked.id(), locked.version(), locked.metroId(), stamp(locked.day())) != 1)
            throw conflict("Reservation arrangement changed before commit");
        jdbc.update("DELETE FROM reservation_dependency WHERE \"arrangementId\"=?", locked.id());
        Set<Dependency> dependencies = new LinkedHashSet<>();
        // Each hold depends on the whole common arrangement, including technicians
        // receiving existing appointments and skills unrelated to the new request.
        for (String hold : holds.keySet()) for (var route : arrangement.routes().entrySet()) for (String id : route.getValue())
            dependencies.add(new Dependency(Required.value(hold), Required.value(route.getKey()), Required.value(visits.get(id), "dependency visit").serviceId()));
        List<@Nullable Object[]> writes = new ArrayList<>();
        for (Dependency dependency : dependencies) writes.add(new @Nullable Object[]{locked.id(), dependency.holdId(), dependency.technicianId(), dependency.serviceId(), stamp(locked.day())});
        if (!writes.isEmpty()) jdbc.batchUpdate("INSERT INTO reservation_dependency (\"arrangementId\",\"holdId\",\"technicianId\",\"serviceId\",\"serviceDate\") VALUES (?,?,?,?,?)", writes);
        SearchDeadline.checkpoint();
        return new Locked(locked.id(), locked.metroId(), locked.day(), version, state);
    }

    private static void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Reservation mutation requires a transaction");
    }
    private static Timestamp stamp(LocalDate day) { return Required.value(Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant())); }
    private static ResponseStatusException conflict(String detail) { return new ResponseStatusException(HttpStatus.CONFLICT, detail); }
}
