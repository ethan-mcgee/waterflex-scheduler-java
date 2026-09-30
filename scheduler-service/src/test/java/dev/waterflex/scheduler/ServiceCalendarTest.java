package dev.waterflex.scheduler;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceCalendarTest {
    private MockEnvironment environment(String reference) {
        var env = new MockEnvironment(); env.setActiveProfiles("benchmark");
        env.setProperty("benchmark.calendar-reference", reference);
        env.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5433/waterflex_test?currentSchema=benchmark_calendar");
        return env;
    }
    private JdbcTemplate database() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT current_database()", String.class, new Object[0])).thenReturn("waterflex_test");
        when(jdbc.queryForObject("SELECT current_schema()", String.class, new Object[0])).thenReturn("benchmark_calendar");
        return jdbc;
    }
    @Test void productionIsRealTimeAndFixedCalendarRequiresIsolation() {
        var jdbc = database(); var real = new ServiceCalendar(new MockEnvironment(), jdbc);
        Instant start = Required.value(Instant.now()); assertFalse(real.now().isBefore(start));
        assertEquals("real-time", real.reference());
        var env = environment("2026-09-30T04:59:59Z"); env.setActiveProfiles("production");
        assertThrows(IllegalArgumentException.class, () -> new ServiceCalendar(env, jdbc));
        env.setActiveProfiles("benchmark");
        env.setProperty("spring.datasource.url", "jdbc:postgresql://remote:5432/waterflex_test");
        assertThrows(IllegalArgumentException.class, () -> new ServiceCalendar(env, jdbc));
        env.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/waterflex_test");
        when(jdbc.queryForObject("SELECT current_schema()", String.class, new Object[0])).thenReturn("public");
        assertThrows(IllegalArgumentException.class, () -> new ServiceCalendar(env, jdbc));
        env.setProperty("benchmark.calendar-reference", "invalid");
        assertThrows(IllegalArgumentException.class, () -> new ServiceCalendar(env, jdbc));
    }
    @Test void horizonAndCutoffRemainStableAcrossMidnightWeekendAndDst() {
        for (String reference : new String[]{"2026-09-30T04:59:59Z", "2026-09-30T10:59:59Z",
                "2026-10-03T04:59:59Z", "2026-03-08T07:59:59Z", "2026-11-01T06:59:59Z"}) {
            var calendar = new ServiceCalendar(environment(Required.value(reference)), database());
            Instant frozen = Required.value(Instant.parse(reference));
            var dates = BookingService.bookingDates(frozen);
            for (long hours : new long[]{1, 6, 24, 72}) {
                Instant later = Required.value(frozen.plusSeconds(hours * 3600));
                assertEquals(dates, BookingService.bookingDates(calendar.at(later)));
                assertEquals(BookingService.overflowDates(frozen), BookingService.overflowDates(calendar.at(later)));
                var day = Required.value(frozen.atZone(java.time.ZoneId.of("America/Chicago")).toLocalDate());
                assertEquals(ScheduleCutoff.frozen(day, frozen), ScheduleCutoff.frozen(day, calendar.at(later)));
            }
        }
    }
    @Test void snapshotValidatesEntireHorizonAgainstCalendarAndPreservesCaptureTime() {
        Instant reference = Required.value(Instant.parse("2026-09-30T04:59:59Z"));
        Instant capture = Required.value(reference.plusSeconds(86400 * 3));
        var days = new java.util.TreeMap<java.time.LocalDate, BookingSnapshot.Day>();
        var empty = new BookingSnapshot.Day(Required.value(Map.of()), Required.value(Map.of()), new BookingSnapshot.Arrangement(Required.value(Map.of())), 0,
                new BookingSnapshot.Roads(Required.value(Map.of()), Required.value(java.util.Set.of())));
        BookingService.bookingDates(reference).forEach(day -> days.put(day, empty));
        var rates = new BookingSnapshot.Rates(1, 2, 1, 0, 0);
        var policy = dev.waterflex.scheduler.optimizer.SchedulingPolicy.Rules.defaults();
        var snapshot = new BookingSnapshot("metro", capture, reference, "config", "roads", policy, rates, days);
        assertEquals(capture, snapshot.capturedAt()); assertEquals(reference, snapshot.calendarReference());
        assertThrows(BookingSnapshot.Incomplete.class, () -> new BookingSnapshot("metro", capture, "config", "roads", policy, rates, days));
        days.remove(days.firstKey());
        assertThrows(BookingSnapshot.Incomplete.class, () -> new BookingSnapshot("metro", capture, reference, "config", "roads", policy, rates, days));
    }
}
