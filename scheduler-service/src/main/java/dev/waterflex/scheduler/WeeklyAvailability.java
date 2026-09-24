package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/** Resolves the latest weekly version for a calendar day, then its date exception. */
public final class WeeklyAvailability {
    public record Shift(int start, int end) { }
    /** A present entry with a null shift is an explicitly configured nonworking day. */
    public record Availability(@Nullable Shift shift) { }
    private record Row(@Nullable String versionId, @Nullable Integer weekday, @Nullable Boolean available,
                       @Nullable Integer start, @Nullable Integer end,
                       @Nullable Boolean exceptionAvailable, @Nullable Integer exceptionStart, @Nullable Integer exceptionEnd) { }

    private WeeklyAvailability() { }

    public static @Nullable Shift resolve(JdbcTemplate jdbc, String technicianId, LocalDate day) {
        return Required.value(resolveAll(jdbc, Required.value(List.<String>of(technicianId)), day).get(technicianId), "technician availability").shift();
    }

    public static Map<String, Availability> resolveAll(JdbcTemplate jdbc, List<String> technicianIds, LocalDate day) {
        if (technicianIds.isEmpty()) return Required.value(Map.of());
        if (technicianIds.stream().anyMatch((String id) -> id.isBlank()) || new HashSet<>(technicianIds).size() != technicianIds.size())
            throw new IllegalArgumentException("Technician identifiers must be nonblank and unique");
        Timestamp stamp = Required.value(Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant()));
        List<Object> arguments = new ArrayList<>();
        arguments.add(stamp);
        arguments.add(day.getDayOfWeek().getValue() % 7);
        arguments.add(stamp);
        arguments.addAll(technicianIds);
        Map<String, Availability> result = new HashMap<>();
        jdbc.query("SELECT v.id,d.\"dayOfWeek\",d.available,d.\"shiftStartMin\",d.\"shiftEndMin\",o.available,o.\"shiftStartMin\",o.\"shiftEndMin\",t.id " +
                "FROM technician t LEFT JOIN LATERAL (SELECT id FROM technician_availability_version WHERE \"technicianId\"=t.id AND \"effectiveDate\"<=? ORDER BY \"effectiveDate\" DESC LIMIT 1) v ON true " +
                "LEFT JOIN technician_availability_day d ON d.\"versionId\"=v.id AND d.\"dayOfWeek\"=? " +
                "LEFT JOIN technician_shift_override o ON o.\"technicianId\"=t.id AND o.\"serviceDate\"=? WHERE t.id IN (" + String.join(",", Collections.nCopies(technicianIds.size(), "?")) + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    Row row = new Row(rs.getString(1), (Integer)rs.getObject(2), (Boolean)rs.getObject(3),
                        (Integer)rs.getObject(4), (Integer)rs.getObject(5), (Boolean)rs.getObject(6),
                        (Integer)rs.getObject(7), (Integer)rs.getObject(8));
                    if (row.versionId() == null || row.weekday() == null)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician weekly availability is missing");
                    String id = Required.string(rs, 9);
                    Availability availability = new Availability(select(row.available(), row.start(), row.end(), row.exceptionAvailable(), row.exceptionStart(), row.exceptionEnd()));
                    if (result.putIfAbsent(id, availability) != null)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate technician availability");
                }, Required.value(arguments.toArray(new @Nullable Object[0])));
        if (!result.keySet().equals(new HashSet<>(technicianIds)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician not found");
        return Required.value(Map.copyOf(result));
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
            if (!Required.value(exceptionAvailable)) {
                if (exceptionStart != null || exceptionEnd != null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician date exception has invalid off day");
                return null;
            }
            if (!valid(exceptionStart, exceptionEnd))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician date exception has invalid hours");
            return new Shift(Required.value(exceptionStart), Required.value(exceptionEnd));
        }
        if (exceptionStart != null || exceptionEnd != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician date exception is missing availability");
        return standardAvailable ? new Shift(Required.value(start), Required.value(end)) : null;
    }

    private static boolean valid(@Nullable Integer start, @Nullable Integer end) {
        return start != null && end != null && start >= 0 && end <= 1440 && start < end;
    }
}
