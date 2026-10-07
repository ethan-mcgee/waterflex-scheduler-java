package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@RestController
public class TechnicianDepotController {
    public record Assignment(@Nullable String depotId, @Nullable String effectiveDate) { }
    private record Depot(String id, String dealershipId, String metroId) { }
    private final JdbcTemplate jdbc;
    private final BookingService booking;

    public TechnicianDepotController(JdbcTemplate jdbc, BookingService booking) {
        this.jdbc = jdbc;
        this.booking = booking;
    }

    @PostMapping("/v1/technicians/{id}/depot-assignments")
    @Transactional
    public Map<String, Boolean> assign(@PathVariable String id, @RequestBody @Nullable Assignment request) {
        if (request == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Depot and effective date are required");
        String depotId = request.depotId();
        String effectiveDate = request.effectiveDate();
        if (depotId == null || depotId.isBlank() || effectiveDate == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Depot and effective date are required");
        LocalDate date;
        try { date = LocalDate.parse(effectiveDate); }
        catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid effective date"); }
        Instant now = Instant.now();
        LocalDate today = now.atZone(ZoneId.of("America/Chicago")).toLocalDate();
        LocalDate earliest = ScheduleCutoff.frozen(Required.value(today), Required.value(now)) ? today.plusDays(1) : today;
        if (date.isBefore(earliest)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Assignment date is frozen");

        var owners = jdbc.query("SELECT id FROM technician WHERE id=? FOR UPDATE", (rs, _) -> dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), id);
        if (owners.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Technician not found");
        var targetRows = jdbc.query("SELECT id,\"dealershipId\",\"metroId\" FROM depot WHERE id=?", (rs, _) ->
                new Depot(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), dev.waterflex.scheduler.DatabaseFacts.string(rs, 2), dev.waterflex.scheduler.DatabaseFacts.string(rs, 3)), depotId);
        if (targetRows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Depot not found");
        Depot target = targetRows.getFirst();
        Timestamp stamp = Timestamp.from(date.atStartOfDay(ZoneOffset.UTC).toInstant());
        var priorRows = jdbc.query("SELECT p.id,p.\"dealershipId\",p.\"metroId\" FROM technician_depot_assignment a JOIN depot p ON p.id=a.\"depotId\" WHERE a.\"technicianId\"=? AND a.\"effectiveDate\"<=? ORDER BY a.\"effectiveDate\" DESC LIMIT 1",
                (rs, _) -> new Depot(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), dev.waterflex.scheduler.DatabaseFacts.string(rs, 2), dev.waterflex.scheduler.DatabaseFacts.string(rs, 3)), id, stamp);
        if (priorRows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Current depot assignment is missing");
        Depot prior = priorRows.getFirst();
        if (!target.dealershipId().equals(prior.dealershipId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Depot must belong to the technician's dealership");
        if (target.id().equals(prior.id())) return Required.value(Map.of("success", true));
        if (dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT count(*) FROM technician_depot_assignment WHERE \"technicianId\"=? AND \"effectiveDate\">=?", Integer.class, id, stamp) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A later depot assignment already exists");
        boolean crossMetro = !target.metroId().equals(prior.metroId());
        if (crossMetro && !date.isAfter(BookingService.bookingDates(Required.value(now)).getLast()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cross-metro moves must start after the current booking horizon");

        List<LocalDate> booked = jdbc.query("SELECT DISTINCT \"serviceDate\" FROM appointment WHERE \"technicianId\"=? AND \"serviceDate\">=? AND \"cancelledAt\" IS NULL ORDER BY \"serviceDate\"",
                (rs, _) -> dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 1).toInstant().atZone(ZoneOffset.UTC).toLocalDate(), id, stamp);
        if (crossMetro && !booked.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Booked appointments conflict with the metro move");
        List<LocalDate> held = jdbc.query("SELECT DISTINCT \"serviceDate\" FROM reservation_obligation WHERE \"technicianId\"=? AND \"serviceDate\">=? AND \"releasedAt\" IS NULL AND \"expiresAt\">CURRENT_TIMESTAMP ORDER BY \"serviceDate\"",
                (rs, _) -> dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 1).toInstant().atZone(ZoneOffset.UTC).toLocalDate(), id, stamp);
        if (crossMetro && !held.isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Active holds conflict with the depot move");
        if (dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT count(*) FROM reservation_dependency d JOIN slot_hold h ON h.id=d.\"holdId\" WHERE d.\"technicianId\"=? AND h.\"serviceDate\">=? AND h.\"releasedAt\" IS NULL AND h.\"expiresAt\">CURRENT_TIMESTAMP",
                Integer.class, id, stamp) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A reserved route arrangement depends on this depot assignment");

        jdbc.update("INSERT INTO technician_depot_assignment (\"technicianId\",\"effectiveDate\",\"depotId\") VALUES (?,?,?)", id, stamp, target.id());
        if (!crossMetro) {
            TreeSet<LocalDate> affected = new TreeSet<>(booked);
            affected.addAll(held);
            for (LocalDate day : affected) booking.replanAssignment(id, Required.value(day));
        }
        jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\">=?", id, stamp);
        return Required.value(Map.of("success", true));
    }
}
