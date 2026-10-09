package dev.waterflex.scheduler;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BookingCalendarTest {
    @Test void availableWeekendStaysInsideTenthWeekdaySpan() {
        List<LocalDate> dates = BookingCalendar.bookingDates(Required.value(Instant.parse("2026-10-23T17:00:00Z")));
        assertEquals(LocalDate.parse("2026-10-24"), dates.getFirst());
        assertTrue(dates.contains(LocalDate.parse("2026-10-25")));
        assertTrue(dates.contains(LocalDate.parse("2026-11-01")));
        assertEquals(LocalDate.parse("2026-11-06"), dates.getLast());
        assertFalse(dates.contains(LocalDate.parse("2026-11-07")));
    }
}
