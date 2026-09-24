package dev.waterflex.scheduler;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.jspecify.annotations.Nullable;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

@RestController
public class DealershipPolicyController {
    public record Policy(@Nullable String departure, @Nullable String returnTo) { }
    private record BookedDay(String technicianId, LocalDate date) { }
    private final JdbcTemplate jdbc;
    private final BookingService booking;

    public DealershipPolicyController(JdbcTemplate jdbc, BookingService booking) {
        this.jdbc = jdbc; this.booking = booking;
    }

    @PostMapping("/v1/depots/{id}/policy")
    @Transactional
    public Map<String, Object> update(@PathVariable String id, @RequestBody @Nullable Policy policy) {
        String departure = policy == null ? null : policy.departure();
        String returnTo = policy == null ? null : policy.returnTo();
        if (!("HOME".equals(departure) || "DEPOT".equals(departure)) ||
                !("HOME".equals(returnTo) || "DEPOT".equals(returnTo)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid route endpoints");
        var depotRows = jdbc.query("SELECT p.id,p.lat,p.lng FROM depot p WHERE p.id=? FOR UPDATE OF p",
                (rs, _) -> new Object[] { rs.getString(1), rs.getObject(2), rs.getObject(3) }, id);
        if (depotRows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Depot not found");
        Object[] depot = Required.value(depotRows.getFirst());
        if (("DEPOT".equals(departure) || "DEPOT".equals(returnTo)) &&
                (depot[0] == null || depot[1] == null || depot[2] == null))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Configure a depot location first");

        List<String> technicians = jdbc.query("SELECT t.id FROM technician t WHERE EXISTS (SELECT 1 FROM technician_depot_assignment a WHERE a.\"technicianId\"=t.id AND a.\"depotId\"=?) ORDER BY t.id FOR UPDATE OF t",
                (rs, _) -> Required.string(rs, 1), id);
        Instant now = Required.value(Instant.now());
        LocalDate today = Required.value(now.atZone(ZoneId.of("America/Chicago")).toLocalDate());
        LocalDate proposedDate = ScheduleCutoff.frozen(Required.value(today), Required.value(now)) ? Required.value(today.plusDays(1)) : today;
        Timestamp nextPolicy = jdbc.query("SELECT \"effectiveDate\" FROM depot_endpoint_policy WHERE \"depotId\"=? AND \"effectiveDate\">? ORDER BY \"effectiveDate\" LIMIT 1",
                rs -> rs.next() ? Required.timestamp(rs, 1) : null,
                id, Timestamp.from(today.atStartOfDay(ZoneOffset.UTC).toInstant()));
        LocalDate effectiveDate = nextPolicy == null ? proposedDate : Required.value(nextPolicy.toInstant().atZone(ZoneOffset.UTC).toLocalDate());
        List<BookedDay> booked = new ArrayList<>();
        for (String techId : technicians) {
            var days = jdbc.query("SELECT DISTINCT \"serviceDate\" FROM (SELECT \"serviceDate\" FROM appointment WHERE \"technicianId\"=? AND \"cancelledAt\" IS NULL UNION ALL SELECT \"serviceDate\" FROM reservation_obligation WHERE \"technicianId\"=? AND \"releasedAt\" IS NULL AND \"expiresAt\">CURRENT_TIMESTAMP) affected WHERE \"serviceDate\">=? ORDER BY \"serviceDate\"",
                    (rs, _) -> Required.value(Required.timestamp(rs, 1).toInstant().atZone(ZoneOffset.UTC).toLocalDate()),
                    techId, techId, Timestamp.from(today.atStartOfDay(ZoneOffset.UTC).toInstant()));
            for (LocalDate day : days) {
                LocalDate serviceDay = Required.value(day);
                if (serviceDay.isBefore(effectiveDate)) continue;
                String assignedDepot = Required.query(jdbc, "SELECT \"depotId\" FROM technician_depot_assignment WHERE \"technicianId\"=? AND \"effectiveDate\"<=? ORDER BY \"effectiveDate\" DESC LIMIT 1", String.class, techId, Timestamp.from(serviceDay.atStartOfDay(ZoneOffset.UTC).toInstant()));
                if (!id.equals(assignedDepot)) continue;
                Timestamp date = Timestamp.from(serviceDay.atStartOfDay(ZoneOffset.UTC).toInstant());
                jdbc.update("INSERT INTO schedule_day (id, \"technicianId\", \"serviceDate\", version) VALUES (?, ?, ?, 0) ON CONFLICT (\"technicianId\", \"serviceDate\") DO NOTHING",
                        UUID.randomUUID().toString(), techId, date);
                jdbc.queryForList("SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=? FOR UPDATE", techId, date);
                if (Required.query(jdbc, "SELECT count(*) FROM reservation_obligation WHERE \"technicianId\"=? AND \"serviceDate\"=? AND \"releasedAt\" IS NULL AND \"expiresAt\">CURRENT_TIMESTAMP", Integer.class, techId, date) > 0)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Active hold on a booked day");
                if (Required.query(jdbc, "SELECT count(*) FROM appointment WHERE \"technicianId\"=? AND \"serviceDate\"=? AND \"cancelledAt\" IS NULL", Integer.class, techId, date) > 0)
                    booked.add(new BookedDay(Required.value(techId), serviceDay));
            }
        }
        if (ScheduleCutoff.frozen(Required.value(today), Required.value(Instant.now())) && !ScheduleCutoff.frozen(Required.value(today), Required.value(now)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Today's route passed the 6 a.m. cutoff");
        jdbc.update("INSERT INTO depot_endpoint_policy (\"depotId\",\"effectiveDate\",departure,\"returnTo\") VALUES (?, ?, ?::\"RouteAnchor\", ?::\"RouteAnchor\") ON CONFLICT (\"depotId\",\"effectiveDate\") DO UPDATE SET departure=EXCLUDED.departure,\"returnTo\"=EXCLUDED.\"returnTo\"",
                id, Timestamp.from(effectiveDate.atStartOfDay(ZoneOffset.UTC).toInstant()), Required.value(departure), Required.value(returnTo));
        for (BookedDay day : booked) booking.replanExisting(day.technicianId(), day.date());
        if (effectiveDate.equals(today) && ScheduleCutoff.frozen(Required.value(today), Required.value(Instant.now())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Today's route passed the 6 a.m. cutoff");
        return Required.value(Map.of("success", true, "effectiveDate", effectiveDate.toString()));
    }
}
