package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import org.springframework.web.server.ResponseStatusException;

class BookingWeeklyAvailabilityTest {
    @Test void availableWeekendStaysInsideTenthWeekdaySpan() {
        List<LocalDate> dates = BookingService.bookingDates(Required.value(Instant.parse("2026-10-23T17:00:00Z")));
        assertEquals(LocalDate.parse("2026-10-24"), dates.getFirst());
        assertTrue(dates.contains(LocalDate.parse("2026-10-25")));
        assertTrue(dates.contains(LocalDate.parse("2026-11-01")));
        assertEquals(LocalDate.parse("2026-11-06"), dates.getLast());
        assertFalse(dates.contains(LocalDate.parse("2026-11-07")));
    }

    @Test void windowsFitEachDaysShift() {
        assertEquals(List.of(600, 660, 720), BookingService.windowStartMinutes(600, 840));
        assertEquals(List.of(510, 570), BookingService.windowStartMinutes(510, 720));
        assertEquals(List.of(), BookingService.windowStartMinutes(600, 690));
        assertThrows(IllegalArgumentException.class, () -> BookingService.windowStartMinutes(900, 600));
    }

    @Test void missingHoursFailAndDateExceptionsTakePrecedence() {
        assertThrows(ResponseStatusException.class, () -> WeeklyAvailability.select(null, null, null, null, null, null));
        assertThrows(ResponseStatusException.class, () -> WeeklyAvailability.select(true, null, 900, null, null, null));
        assertNull(WeeklyAvailability.select(false, null, null, null, null, null));
        assertNull(WeeklyAvailability.select(true, 480, 1020, false, null, null));
        assertEquals(new WeeklyAvailability.Shift(600, 840), WeeklyAvailability.select(false, null, null, true, 600, 840));
        assertThrows(ResponseStatusException.class, () -> WeeklyAvailability.select(true, 480, 1020, true, null, 840));
    }
}
