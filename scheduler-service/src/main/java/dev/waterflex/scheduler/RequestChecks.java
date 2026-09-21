package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;

public final class RequestChecks {
    private RequestChecks() { }
    public record Empty() { }
    public static String text(@Nullable String value, String name) {
        if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing " + name);
        return value;
    }
    public static String date(@Nullable String value) {
        String text = text(value, "date");
        try { LocalDate.parse(text); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date"); }
        return text;
    }
}
