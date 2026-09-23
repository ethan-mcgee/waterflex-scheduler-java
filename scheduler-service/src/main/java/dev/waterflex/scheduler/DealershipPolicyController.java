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
    public record Policy(String departure, String returnTo) { }
    private record BookedDay(String technicianId, LocalDate date) { }
    private final JdbcTemplate jdbc;
    private final BookingService booking;

    public DealershipPolicyController(JdbcTemplate jdbc, BookingService booking) {
        this.jdbc = jdbc; this.booking = booking;
    }

    @PostMapping("/v1/dealerships/{id}/policy")
    @Transactional
    public Map<String, Boolean> update(@PathVariable String id, @RequestBody @Nullable Policy policy) {
        if (policy == null || !Set.of("HOME", "DEPOT").contains(policy.departure()) ||
                !Set.of("HOME", "DEPOT").contains(policy.returnTo()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid route endpoints");
        var dealership = jdbc.query("SELECT d.\"depotId\",p.lat,p.lng FROM dealership d LEFT JOIN depot p ON p.id=d.\"depotId\" WHERE d.id=? FOR UPDATE OF d",
                (rs, _) -> new Object[] { rs.getString(1), rs.getObject(2), rs.getObject(3) }, id);
        if (dealership.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dealership not found");
        Object[] depot = Required.value(dealership.getFirst());
        if ((policy.departure().equals("DEPOT") || policy.returnTo().equals("DEPOT")) &&
                (depot[0] == null || depot[1] == null || depot[2] == null))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Configure a depot location first");

        List<String> technicians = jdbc.query("SELECT id FROM technician WHERE \"dealershipId\"=? ORDER BY id FOR UPDATE",
                (rs, _) -> Required.string(rs, 1), id);
        Instant now = Required.value(Instant.now());
        LocalDate today = Required.value(now.atZone(ZoneId.of("America/Chicago")).toLocalDate());
        LocalDate effectiveDate = ScheduleCutoff.frozen(Required.value(today), Required.value(now)) ? Required.value(today.plusDays(1)) : today;
        List<BookedDay> booked = new ArrayList<>();
        for (String techId : technicians) {
            var days = jdbc.query("SELECT DISTINCT \"serviceDate\" FROM (SELECT \"serviceDate\" FROM appointment WHERE \"technicianId\"=? AND \"cancelledAt\" IS NULL UNION ALL SELECT \"serviceDate\" FROM slot_hold WHERE \"technicianId\"=? AND \"releasedAt\" IS NULL AND \"expiresAt\">CURRENT_TIMESTAMP) affected WHERE \"serviceDate\">=? ORDER BY \"serviceDate\"",
                    (rs, _) -> Required.value(Required.timestamp(rs, 1).toInstant().atZone(ZoneOffset.UTC).toLocalDate()),
                    techId, techId, Timestamp.from(today.atStartOfDay(ZoneOffset.UTC).toInstant()));
            for (LocalDate day : days) {
                LocalDate serviceDay = Required.value(day);
                if (serviceDay.isBefore(effectiveDate)) continue;
                Timestamp date = Timestamp.from(serviceDay.atStartOfDay(ZoneOffset.UTC).toInstant());
                jdbc.update("INSERT INTO schedule_day (id, \"technicianId\", \"serviceDate\", version) VALUES (?, ?, ?, 0) ON CONFLICT (\"technicianId\", \"serviceDate\") DO NOTHING",
                        UUID.randomUUID().toString(), techId, date);
                jdbc.queryForList("SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=? FOR UPDATE", techId, date);
                if (Required.query(jdbc, "SELECT count(*) FROM slot_hold WHERE \"technicianId\"=? AND \"serviceDate\"=? AND \"releasedAt\" IS NULL AND \"expiresAt\">CURRENT_TIMESTAMP", Integer.class, techId, date) > 0)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Active hold on a booked day");
                if (Required.query(jdbc, "SELECT count(*) FROM appointment WHERE \"technicianId\"=? AND \"serviceDate\"=? AND \"cancelledAt\" IS NULL", Integer.class, techId, date) > 0)
                    booked.add(new BookedDay(Required.value(techId), serviceDay));
            }
        }
        if (ScheduleCutoff.frozen(Required.value(today), Required.value(Instant.now())) && !ScheduleCutoff.frozen(Required.value(today), Required.value(now)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Today's route passed the 6 a.m. cutoff");
        jdbc.update("UPDATE dealership SET departure=?::\"RouteAnchor\",\"returnTo\"=?::\"RouteAnchor\",\"updatedAt\"=CURRENT_TIMESTAMP WHERE id=?",
                policy.departure(), policy.returnTo(), id);
        jdbc.update("INSERT INTO dealership_endpoint_policy (\"dealershipId\",\"effectiveDate\",departure,\"returnTo\") VALUES (?, ?, ?::\"RouteAnchor\", ?::\"RouteAnchor\") ON CONFLICT (\"dealershipId\",\"effectiveDate\") DO UPDATE SET departure=EXCLUDED.departure,\"returnTo\"=EXCLUDED.\"returnTo\"",
                id, Timestamp.from(effectiveDate.atStartOfDay(ZoneOffset.UTC).toInstant()), policy.departure(), policy.returnTo());
        for (BookedDay day : booked) booking.replanExisting(day.technicianId(), day.date());
        if (effectiveDate.equals(today) && ScheduleCutoff.frozen(Required.value(today), Required.value(Instant.now())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Today's route passed the 6 a.m. cutoff");
        return Required.value(Map.of("success", true));
    }
}
