package dev.waterflex.scheduler;


import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class ScheduleCutoff {
    private static final ZoneId LOCAL = Required.value(ZoneId.of("America/Chicago"));

    private ScheduleCutoff() { }

    public static boolean frozen(LocalDate serviceDate, Instant now) {
        return frozen(serviceDate, now, LOCAL);
    }

    /** The same 6 a.m. cutoff in the metro's own time zone, for requests that carry it. */
    public static boolean frozen(LocalDate serviceDate, Instant now, ZoneId zone) {
        return !now.isBefore(serviceDate.atTime(6, 0).atZone(zone).toInstant());
    }

    public static Instant localMinute(LocalDate day, int minute, boolean endBoundary) {
        LocalDateTime local = day.atStartOfDay().plusMinutes(minute);
        var offsets = LOCAL.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) return Required.value(local.atZone(LOCAL).toInstant());
        ZoneOffset chosen = endBoundary ? offsets.getLast() : offsets.getFirst();
        return Required.value(local.toInstant(chosen));
    }
}
