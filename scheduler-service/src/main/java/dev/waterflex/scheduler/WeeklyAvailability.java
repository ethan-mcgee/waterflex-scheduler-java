package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Resolves the latest weekly version for a calendar day, then its date exception. */
public final class WeeklyAvailability {
    public record Shift(int start, int end) { }
    private record Row(@Nullable String versionId, @Nullable Integer weekday, @Nullable Boolean available,
                       @Nullable Integer start, @Nullable Integer end,
                       @Nullable Boolean exceptionAvailable, @Nullable Integer exceptionStart, @Nullable Integer exceptionEnd) { }

    private WeeklyAvailability() { }

    public static @Nullable Shift resolve(JdbcTemplate jdbc, String technicianId, LocalDate day) {
        Timestamp stamp = Required.value(Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant()));
        var rows = jdbc.query("SELECT v.id,d.\"dayOfWeek\",d.available,d.\"shiftStartMin\",d.\"shiftEndMin\",o.available,o.\"shiftStartMin\",o.\"shiftEndMin\" " +
                "FROM technician t LEFT JOIN LATERAL (SELECT id FROM technician_availability_version WHERE \"technicianId\"=t.id AND \"effectiveDate\"<=? ORDER BY \"effectiveDate\" DESC LIMIT 1) v ON true " +
                "LEFT JOIN technician_availability_day d ON d.\"versionId\"=v.id AND d.\"dayOfWeek\"=? " +
                "LEFT JOIN technician_shift_override o ON o.\"technicianId\"=t.id AND o.\"serviceDate\"=? WHERE t.id=?",
                (rs, _) -> new Row(rs.getString(1), (Integer)rs.getObject(2), (Boolean)rs.getObject(3),
                        (Integer)rs.getObject(4), (Integer)rs.getObject(5), (Boolean)rs.getObject(6),
                        (Integer)rs.getObject(7), (Integer)rs.getObject(8)), stamp, day.getDayOfWeek().getValue() % 7, stamp, technicianId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician not found");
        Row row = rows.getFirst();
        if (row.versionId() == null || row.weekday() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician weekly availability is missing");
        return select(row.available(), row.start(), row.end(), row.exceptionAvailable(), row.exceptionStart(), row.exceptionEnd());
    }

    static @Nullable Shift select(@Nullable Boolean available, @Nullable Integer start, @Nullable Integer end,
                                  @Nullable Boolean exceptionAvailable, @Nullable Integer exceptionStart, @Nullable Integer exceptionEnd) {
        if (available == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician weekly availability is missing");
        boolean standardAvailable = Required.value(available);
        if (standardAvailable && !valid(start, end))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician weekly availability has invalid hours");
        if (!standardAvailable && (start != null || end != null))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician weekly availability has invalid off day");
        if (exceptionAvailable != null) {
            if (!Required.value(exceptionAvailable)) return null;
            if (!valid(exceptionStart, exceptionEnd))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician date exception has invalid hours");
            return new Shift(Required.value(exceptionStart), Required.value(exceptionEnd));
        }
        return standardAvailable ? new Shift(Required.value(start), Required.value(end)) : null;
    }

    private static boolean valid(@Nullable Integer start, @Nullable Integer end) {
        return start != null && end != null && start >= 0 && end <= 1440 && start < end;
    }
}
