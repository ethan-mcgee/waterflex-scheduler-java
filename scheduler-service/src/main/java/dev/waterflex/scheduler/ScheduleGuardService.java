package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class ScheduleGuardService {
    private final JdbcTemplate jdbc;
    public ScheduleGuardService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lockTechnician(String id) {
        if (jdbc.query("SELECT id FROM technician WHERE id=? FOR UPDATE", (rs, _) -> Required.string(rs, 1), id).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Technician not found");
    }

    public void lockDay(String technicianId, LocalDate day) {
        jdbc.update("INSERT INTO schedule_day (id, \"technicianId\", \"serviceDate\", version) VALUES (?, ?, ?, 0) ON CONFLICT (\"technicianId\", \"serviceDate\") DO NOTHING",
                UUID.randomUUID().toString(), technicianId, stamp(day));
        Required.query(jdbc, "SELECT version FROM schedule_day WHERE \"technicianId\"=? AND \"serviceDate\"=? FOR UPDATE", Integer.class, technicianId, stamp(day));
    }

    public void noHolds(String technicianId, LocalDate day) {
        if (Required.query(jdbc, "SELECT count(*) FROM reservation_obligation WHERE \"technicianId\"=? AND \"serviceDate\"=? AND \"releasedAt\" IS NULL AND \"expiresAt\">CURRENT_TIMESTAMP",
                Integer.class, technicianId, stamp(day)) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Active reservations require a fresh proposal");
    }

    public void unfrozen(LocalDate day) {
        if (ScheduleCutoff.frozen(day, Required.value(Instant.now()))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Frozen date requires CSR coordination");
    }

    @Transactional
    public void availability(String technicianId, LocalDate day, boolean available, @Nullable Integer start, @Nullable Integer end) {
        if (available && (start == null || end == null || start < 0 || end > 1440 || start >= end))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid shift hours");
        unfrozen(day);
        lockTechnician(technicianId);
        lockDay(technicianId, day);
        noHolds(technicianId, day);
        if (Required.query(jdbc, "SELECT count(*) FROM appointment WHERE \"technicianId\"=? AND \"serviceDate\"=? AND \"cancelledAt\" IS NULL",
                Integer.class, technicianId, stamp(day)) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Existing appointments require schedule repair");
        jdbc.update("INSERT INTO technician_shift_override (id, \"technicianId\", \"serviceDate\", available, \"shiftStartMin\", \"shiftEndMin\") VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (\"technicianId\", \"serviceDate\") DO UPDATE SET available=EXCLUDED.available, \"shiftStartMin\"=EXCLUDED.\"shiftStartMin\", \"shiftEndMin\"=EXCLUDED.\"shiftEndMin\"",
                UUID.randomUUID().toString(), technicianId, stamp(day), available, available ? start : null, available ? end : null);
        jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\"=?", technicianId, stamp(day));
    }

    @Transactional
    public void deleteAvailability(String technicianId, LocalDate day) {
        unfrozen(day);
        lockTechnician(technicianId);
        lockDay(technicianId, day);
        noHolds(technicianId, day);
        if (Required.query(jdbc, "SELECT count(*) FROM appointment WHERE \"technicianId\"=? AND \"serviceDate\"=? AND \"cancelledAt\" IS NULL",
                Integer.class, technicianId, stamp(day)) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Existing appointments require schedule repair");
        jdbc.update("DELETE FROM technician_shift_override WHERE \"technicianId\"=? AND \"serviceDate\"=?", technicianId, stamp(day));
        jdbc.update("UPDATE schedule_day SET version=version+1 WHERE \"technicianId\"=? AND \"serviceDate\"=?", technicianId, stamp(day));
    }

    @Transactional
    public void qualification(String technicianId, String serviceId, boolean qualified) {
        lockTechnician(technicianId);
        if (!qualified) {
            int appointments = Required.query(jdbc, "SELECT count(*) FROM appointment a JOIN job j ON j.id=a.\"jobId\" WHERE a.\"technicianId\"=? AND j.\"serviceId\"=? AND a.\"cancelledAt\" IS NULL",
                    Integer.class, technicianId, serviceId);
            int holds = Required.query(jdbc, "SELECT count(*) FROM reservation_obligation WHERE \"technicianId\"=? AND \"serviceId\"=? AND \"releasedAt\" IS NULL AND \"expiresAt\">CURRENT_TIMESTAMP",
                    Integer.class, technicianId, serviceId);
            if (appointments + holds > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Existing schedule requires qualification repair");
            jdbc.update("DELETE FROM technician_qualification WHERE \"technicianId\"=? AND \"serviceId\"=?", technicianId, serviceId);
        } else jdbc.update("INSERT INTO technician_qualification (\"technicianId\", \"serviceId\") VALUES (?, ?) ON CONFLICT DO NOTHING", technicianId, serviceId);
    }

    private static Timestamp stamp(LocalDate day) { return Required.value(Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant())); }
}
