package dev.waterflex.scheduler;
import java.time.*;
import java.util.*;
/** Test fixtures' booking horizon: the ten weekdays after the capture date (America/Chicago), weekends included. */
final class BookingCalendar {
    private static final ZoneId CHICAGO = Required.value(ZoneId.of("America/Chicago"));
    private BookingCalendar() { }
    static List<LocalDate> bookingDates(Instant now) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate day = now.atZone(CHICAGO).toLocalDate().plusDays(1);
        int weekdays = 0;
        while (weekdays < 10) {
            days.add(day);
            if (day.getDayOfWeek().getValue() <= 5) weekdays++;
            day = day.plusDays(1);
        }
        return days;
    }
}
