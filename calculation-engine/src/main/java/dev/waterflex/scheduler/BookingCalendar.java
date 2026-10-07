package dev.waterflex.scheduler;
import java.time.*;
import java.util.*;
/** Booking horizon policy, independent of persistence. */
public final class BookingCalendar {
    private static final ZoneId CHICAGO = Required.value(ZoneId.of("America/Chicago"));
    private BookingCalendar() { }
    public static List<LocalDate> bookingDates(Instant now) {
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
    public static List<LocalDate> overflowDates(Instant now) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate date = Required.value(bookingDates(now).getLast().plusDays(1));
        while (dates.size() < 5) {
            if (date.getDayOfWeek().getValue() <= 5) dates.add(date);
            date = Required.value(date.plusDays(1));
        }
        return Required.value(List.copyOf(dates));
    }
}
