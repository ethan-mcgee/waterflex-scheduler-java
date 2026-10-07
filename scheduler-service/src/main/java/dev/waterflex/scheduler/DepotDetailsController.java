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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class DepotDetailsController {
    public record Address(@Nullable String line1, @Nullable String city, @Nullable String state, @Nullable String postalCode) { }
    public record Point(@Nullable Double lat, @Nullable Double lng) { }
    public record Candidate(@Nullable Double lat, @Nullable Double lng, @Nullable String precision) { }
    public record Details(@Nullable String name, @Nullable Address address, @Nullable Point confirmedPin, @Nullable Candidate candidate) { }
    private record BookedDay(String technicianId, LocalDate date) { }
    private final JdbcTemplate jdbc;
    private final BookingService booking;

    public DepotDetailsController(JdbcTemplate jdbc, BookingService booking) {
        this.jdbc = jdbc;
        this.booking = booking;
    }

    @PostMapping("/v1/depots/{id}/details")
    @Transactional
    public Map<String, Boolean> update(@PathVariable String id, @RequestBody @Nullable Details details) {
        String name = details == null ? null : details.name();
        if (name == null || name.isBlank() || name.length() > 120)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid depot name");
        Address address = details == null ? null : details.address();
        Point pin = details == null ? null : details.confirmedPin();
        Candidate candidate = details == null ? null : details.candidate();
        if ((address == null) != (pin == null) || (address == null) != (candidate == null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Address, pin and geocode must be supplied together");
        if (address != null && pin != null && candidate != null) {
            if (blank(address.line1()) || blank(address.city()) || blank(address.state()) || blank(address.postalCode()) ||
                    !valid(pin.lat(), pin.lng()) || !valid(candidate.lat(), candidate.lng()) || blank(candidate.precision()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid depot address or pin");
            double distance = distanceMeters(Required.value(pin.lat()), Required.value(pin.lng()), Required.value(candidate.lat()), Required.value(candidate.lng()));
            if (distance > 250) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pin is too far from geocode");
        }
        var depots = jdbc.query("SELECT id FROM depot WHERE id=? FOR UPDATE", (rs, _) -> dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), id);
        if (depots.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Depot not found");
        if (address == null || pin == null || candidate == null) {
            jdbc.update("UPDATE depot SET name=? WHERE id=?", name, id);
            return Required.value(Map.of("success", true));
        }

        Instant now = Required.value(Instant.now());
        LocalDate today = Required.value(now.atZone(ZoneId.of("America/Chicago")).toLocalDate());
        Timestamp todayStamp = stamp(today);
        List<String> technicians = jdbc.query("SELECT t.id FROM technician t WHERE EXISTS (SELECT 1 FROM technician_depot_assignment a WHERE a.\"technicianId\"=t.id AND a.\"depotId\"=?) ORDER BY t.id FOR UPDATE OF t",
                (rs, _) -> dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), id);
        List<BookedDay> booked = new ArrayList<>();
        for (String techId : technicians) {
            var days = jdbc.query("SELECT DISTINCT \"serviceDate\" FROM (SELECT \"serviceDate\" FROM appointment WHERE \"technicianId\"=? AND \"cancelledAt\" IS NULL UNION ALL SELECT \"serviceDate\" FROM reservation_obligation WHERE \"technicianId\"=? AND \"releasedAt\" IS NULL AND \"expiresAt\">CURRENT_TIMESTAMP) affected WHERE \"serviceDate\">=? ORDER BY \"serviceDate\"",
                    (rs, _) -> Required.value(dev.waterflex.scheduler.DatabaseFacts.timestamp(rs, 1).toInstant().atZone(ZoneOffset.UTC).toLocalDate()), techId, techId, todayStamp);
            for (LocalDate day : days) {
                LocalDate serviceDay = Required.value(day);
                Timestamp date = stamp(serviceDay);
                String assigned = dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT \"depotId\" FROM technician_depot_assignment WHERE \"technicianId\"=? AND \"effectiveDate\"<=? ORDER BY \"effectiveDate\" DESC LIMIT 1", String.class, techId, date);
                if (!id.equals(assigned)) continue;
                jdbc.update("INSERT INTO schedule_day (id,\"technicianId\",\"serviceDate\",version) VALUES (?,?,?,0) ON CONFLICT (\"technicianId\",\"serviceDate\") DO NOTHING", UUID.randomUUID().toString(), techId, date);
                jdbc.queryForList("SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=? FOR UPDATE", techId, date);
                if (dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT count(*) FROM reservation_obligation WHERE \"technicianId\"=? AND \"serviceDate\"=? AND \"releasedAt\" IS NULL AND \"expiresAt\">CURRENT_TIMESTAMP", Integer.class, techId, date) > 0)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Active hold prevents changing this depot location");
                if (dev.waterflex.scheduler.DatabaseFacts.query(jdbc, "SELECT count(*) FROM appointment WHERE \"technicianId\"=? AND \"serviceDate\"=? AND \"cancelledAt\" IS NULL", Integer.class, techId, date) > 0) {
                    if (serviceDay.equals(today) && ScheduleCutoff.frozen(today, now))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Today's route passed the 6 a.m. cutoff");
                    booked.add(new BookedDay(Required.value(techId), serviceDay));
                }
            }
        }
        jdbc.update("UPDATE depot SET name=?,lat=?,lng=?,\"addressLine1\"=?,\"addressCity\"=?,\"addressState\"=?,\"addressPostalCode\"=?,\"geocodeLat\"=?,\"geocodeLng\"=?,\"geocodePrecision\"=?,\"pinConfirmedAt\"=CURRENT_TIMESTAMP WHERE id=?",
                name, pin.lat(), pin.lng(), address.line1(), address.city(), address.state(), address.postalCode(), candidate.lat(), candidate.lng(), candidate.precision(), id);
        for (BookedDay day : booked) booking.replanExisting(day.technicianId(), day.date());
        if (!booked.isEmpty() && ScheduleCutoff.frozen(today, Required.value(Instant.now())) && !ScheduleCutoff.frozen(today, now))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Today's route passed the 6 a.m. cutoff");
        return Required.value(Map.of("success", true));
    }

    private static boolean blank(@Nullable String value) { return value == null || value.isBlank(); }
    private static boolean valid(@Nullable Double lat, @Nullable Double lng) {
        return lat != null && lng != null && Double.isFinite(lat) && Double.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
    }
    private static Timestamp stamp(LocalDate date) { return Required.value(Timestamp.from(date.atStartOfDay(ZoneOffset.UTC).toInstant())); }
    private static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double a = Math.sin(Math.toRadians(lat2 - lat1) / 2) * Math.sin(Math.toRadians(lat2 - lat1) / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(Math.toRadians(lng2 - lng1) / 2) * Math.sin(Math.toRadians(lng2 - lng1) / 2);
        return 2 * 6371000 * Math.asin(Math.sqrt(a));
    }
}
