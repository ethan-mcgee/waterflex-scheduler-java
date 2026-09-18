package dev.waterflex.scheduler;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class ScheduleCutoff {
    private static final ZoneId LOCAL = ZoneId.of("America/Chicago");

    private ScheduleCutoff() { }

    public static boolean frozen(LocalDate serviceDate, Instant now) {
        return !now.isBefore(serviceDate.minusDays(1).atTime(6, 0).atZone(LOCAL).toInstant());
    }

    public static Instant localMinute(LocalDate day, int minute, boolean endBoundary) {
        LocalDateTime local = day.atStartOfDay().plusMinutes(minute);
        var offsets = LOCAL.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) return local.atZone(LOCAL).toInstant();
        ZoneOffset chosen = endBoundary ? offsets.getLast() : offsets.getFirst();
        return local.toInstant(chosen);
    }
}
