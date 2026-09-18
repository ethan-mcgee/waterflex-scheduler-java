package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleCutoffTest {
    @Test
    void freezesAtSixAmChicagoBeforeServiceDay() {
        LocalDate monday = LocalDate.parse("2026-09-21");
        assertFalse(ScheduleCutoff.frozen(monday, Instant.parse("2026-09-20T10:59:59Z")));
        assertTrue(ScheduleCutoff.frozen(monday, Instant.parse("2026-09-20T11:00:00Z")));
    }

    @Test
    void daylightSavingUsesLocalSixAm() {
        LocalDate fallBack = LocalDate.parse("2026-11-02");
        assertFalse(ScheduleCutoff.frozen(fallBack, Instant.parse("2026-11-01T11:59:59Z")));
        assertTrue(ScheduleCutoff.frozen(fallBack, Instant.parse("2026-11-01T12:00:00Z")));
    }

    @Test
    void localIntervalsCoverBothRepeatedHours() {
        LocalDate fallBack = LocalDate.parse("2026-11-01");
        assertEquals(Instant.parse("2026-11-01T14:00:00Z"), ScheduleCutoff.localMinute(fallBack, 480, false));
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), ScheduleCutoff.localMinute(fallBack, 90, false));
        assertEquals(Instant.parse("2026-11-01T07:30:00Z"), ScheduleCutoff.localMinute(fallBack, 90, true));
    }
}
