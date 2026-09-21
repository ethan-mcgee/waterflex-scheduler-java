package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleCutoffTest {
    @Test
    void precedingDayIsNotFrozen() {
        LocalDate monday = LocalDate.parse("2026-09-21");
        assertFalse(ScheduleCutoff.frozen(monday, Instant.parse("2026-09-20T11:00:00Z")));
    }

    @Test
    void freezesAtSixAmChicagoOnServiceDay() {
        LocalDate monday = LocalDate.parse("2026-09-21");
        assertFalse(ScheduleCutoff.frozen(monday, Instant.parse("2026-09-21T10:59:59Z")));
        assertTrue(ScheduleCutoff.frozen(monday, Instant.parse("2026-09-21T11:00:00Z")));
    }

    @Test
    void daylightSavingFallBackUsesLocalSixAm() {
        LocalDate fallBack = LocalDate.parse("2026-11-01");
        assertFalse(ScheduleCutoff.frozen(fallBack, Instant.parse("2026-11-01T11:59:59Z")));
        assertTrue(ScheduleCutoff.frozen(fallBack, Instant.parse("2026-11-01T12:00:00Z")));
    }

    @Test
    void daylightSavingSpringForwardUsesLocalSixAm() {
        LocalDate springForward = LocalDate.parse("2026-03-08");
        assertFalse(ScheduleCutoff.frozen(springForward, Instant.parse("2026-03-08T10:59:59Z")));
        assertTrue(ScheduleCutoff.frozen(springForward, Instant.parse("2026-03-08T11:00:00Z")));
    }

    @Test
    void localIntervalsCoverBothRepeatedHours() {
        LocalDate fallBack = LocalDate.parse("2026-11-01");
        assertEquals(Instant.parse("2026-11-01T14:00:00Z"), ScheduleCutoff.localMinute(fallBack, 480, false));
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), ScheduleCutoff.localMinute(fallBack, 90, false));
        assertEquals(Instant.parse("2026-11-01T07:30:00Z"), ScheduleCutoff.localMinute(fallBack, 90, true));
    }
}
