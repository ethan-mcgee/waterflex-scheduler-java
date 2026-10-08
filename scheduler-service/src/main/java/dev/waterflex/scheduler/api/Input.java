package dev.waterflex.scheduler.api;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import dev.waterflex.scheduler.Required;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Validation for public request fields. Every failure is an IllegalArgumentException, which the public API reports
 * as 400. Missing values are rejected, never replaced with zero, false or an empty collection.
 */
final class Input {
    private static final Pattern ID = Required.value(Pattern.compile("\\S(.*\\S)?", Pattern.DOTALL));
    private Input() { }

    static <T extends @NonNull Object> T present(@Nullable T value, String field) {
        if (value == null) throw new IllegalArgumentException("Missing " + field);
        return value;
    }

    static String id(@Nullable String value, String field) {
        String id = present(value, field);
        if (id.length() > 128 || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid " + field);
        return id;
    }

    static String requestId(@Nullable String value) {
        String id = present(value, "requestId");
        try {
            if (id.equals(UUID.fromString(id).toString())) return id;
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("requestId must be a lowercase canonical UUID", malformed);
        }
        throw new IllegalArgumentException("requestId must be a lowercase canonical UUID");
    }

    static int integer(@Nullable Integer value, String field, int min, int max) {
        int number = present(value, field);
        if (number < min || number > max) throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        return number;
    }

    static BigDecimal nonNegative(@Nullable BigDecimal value, String field) {
        BigDecimal number = present(value, field);
        if (number.signum() < 0) throw new IllegalArgumentException(field + " must not be negative");
        return number;
    }

    static String zone(@Nullable String value) {
        String zone = present(value, "timeZone");
        try {
            ZoneId.of(zone);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Unknown timeZone", invalid);
        }
        return zone;
    }

    /** A JSON array can hold null even where the Java type says it cannot, so each element is checked. */
    static <T extends @NonNull Object> List<T> list(@Nullable List<? extends @Nullable T> value, String field) {
        List<? extends @Nullable T> items = present(value, field);
        List<T> checked = new java.util.ArrayList<>(items.size());
        for (@Nullable T item : items) checked.add(present(item, field + " item"));
        return Required.value(List.copyOf(checked));
    }

    static List<String> uniqueIds(@Nullable List<String> value, String field) {
        List<String> items = list(value, field);
        for (String item : items) id(item, field + " item");
        if (new HashSet<>(items).size() != items.size()) throw new IllegalArgumentException("Duplicate " + field + " item");
        return items;
    }
}
