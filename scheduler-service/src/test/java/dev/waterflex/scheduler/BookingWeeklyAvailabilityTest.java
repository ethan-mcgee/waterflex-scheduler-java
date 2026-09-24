package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.sql.ResultSet;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import static org.mockito.Mockito.*;

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
        assertThrows(ResponseStatusException.class, () -> WeeklyAvailability.select(true, 480, 1020, false, 600, 840));
        assertThrows(ResponseStatusException.class, () -> WeeklyAvailability.select(true, 480, 1020, null, 600, 840));
    }

    @Test void regularInsertionCannotBeHiddenByCheaperOvertime() {
        Instant start = Required.value(Instant.parse("2026-10-26T14:00:00Z"));
        LocalDate day = Required.value(LocalDate.parse("2026-10-26"));
        var regular = new BookingService.Candidate("tech", day, start, Required.value(start.plusSeconds(7200)), start, 1, 35, 60, 0, 1000);
        var overtime = new BookingService.Candidate("tech", day, start, Required.value(start.plusSeconds(7200)), start, 0, 20, 20, 10, 100);
        assertTrue(BookingService.INSERTION_ORDER.compare(regular, overtime) < 0);
        var reduction = new BookingService.Candidate("tech", day, start, Required.value(start.plusSeconds(7200)), start, 2, 40, 65, -5, 1000);
        assertTrue(BookingService.INSERTION_ORDER.compare(reduction, regular) < 0);
    }

    @Test void batchedAvailabilityPreservesExplicitOffDaysAndRejectsMissingTechnicians() throws Exception {
        LocalDate day = Required.value(LocalDate.parse("2026-10-26"));
        var jdbc = new AvailabilityJdbc(Required.value(List.<ResultSet>of(availabilityRow("working", true, 480, 1020), availabilityRow("off", false, null, null))));
        Map<String, WeeklyAvailability.Availability> result = WeeklyAvailability.resolveAll(jdbc, Required.value(List.of("working", "off")), day);
        assertEquals(new WeeklyAvailability.Shift(480, 1020), Required.value(result.get("working")).shift());
        assertNull(Required.value(result.get("off")).shift());
        assertEquals(1, jdbc.calls);
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
        assertThrows(ResponseStatusException.class, () -> WeeklyAvailability.resolveAll(jdbc, Required.value(List.of("working", "off", "missing")), day));
    }

    @Test void missingWeeklyVersionCannotBeReplacedByAnException() throws Exception {
        ResultSet row = availabilityRow("tech", true, 480, 1020);
        when(row.getString(1)).thenReturn(null);
        when(row.getObject(6)).thenReturn(true);
        when(row.getObject(7)).thenReturn(600);
        when(row.getObject(8)).thenReturn(840);
        var jdbc = new AvailabilityJdbc(Required.value(List.of(row)));
        assertThrows(ResponseStatusException.class, () -> WeeklyAvailability.resolveAll(jdbc, Required.value(List.of("tech")), Required.value(LocalDate.parse("2026-10-26"))));
    }

    private static final class AvailabilityJdbc extends JdbcTemplate {
        private final List<ResultSet> rows;
        private int calls;
        AvailabilityJdbc(List<ResultSet> rows) { this.rows = rows; }
        @Override public void query(String sql, RowCallbackHandler callback, @Nullable Object @Nullable ... arguments) {
            calls++;
            assertTrue(sql.contains("WHERE t.id IN ("));
            try { for (ResultSet row : rows) callback.processRow(Required.value(row)); }
            catch (java.sql.SQLException exception) { throw new AssertionError(exception); }
        }
    }
    private static ResultSet availabilityRow(String id, boolean available,
            @Nullable Integer start, @Nullable Integer end) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn("version");
        when(row.getObject(2)).thenReturn(1);
        when(row.getObject(3)).thenReturn(available);
        when(row.getObject(4)).thenReturn(start);
        when(row.getObject(5)).thenReturn(end);
        when(row.getString(9)).thenReturn(id);
        return row;
    }
}
