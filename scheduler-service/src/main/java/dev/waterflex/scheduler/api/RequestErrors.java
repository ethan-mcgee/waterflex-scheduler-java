package dev.waterflex.scheduler.api;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import dev.waterflex.scheduler.Required;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The message of a 400 INVALID_REQUEST (spec 1.11.0): what is wrong and where, in the host's terms. A JSON path names the
 * place ({@code $.snapshot.technicians[0]}) and a line and column locate malformed JSON. Java types and parser
 * internals never reach the host, which branches on the error code and shows this text to a developer.
 */
final class RequestErrors {
    private RequestErrors() { }

    static String describe(IllegalArgumentException invalid) {
        if (invalid.getCause() instanceof JsonProcessingException parsing) return describe(parsing);
        return firstLine(invalid.getMessage(), "Invalid request");
    }

    static String describe(JsonProcessingException failure) {
        String what = what(failure);
        String where;
        if (failure instanceof UnrecognizedPropertyException unknown) where = path(unknown, 1);
        else if (failure instanceof JsonMappingException mapping) where = path(mapping, 0);
        else where = location(failure.getLocation());
        return where.isEmpty() ? what : what + " at " + where;
    }

    private static String what(JsonProcessingException failure) {
        if (failure instanceof UnrecognizedPropertyException unknown)
            return "Unknown field \"" + firstLine(unknown.getPropertyName(), "?") + "\"";
        // A record's own check (a missing value, a value out of range) already speaks the contract's language.
        if (failure instanceof JsonMappingException && failure.getCause() instanceof IllegalArgumentException host
                && !(host instanceof NumberFormatException))
            return firstLine(host.getMessage(), "Invalid value");
        if (failure instanceof MismatchedInputException mismatch) {
            if (firstLine(mismatch.getOriginalMessage(), "").startsWith("Trailing token")) return "Unexpected content after the JSON value";
            @Nullable Class<?> target = mismatch.getTargetType();
            return target == null ? "Value has the wrong JSON type" : "Expected " + expected(target);
        }
        if (failure instanceof JsonMappingException) return failure.getCause() instanceof NumberFormatException ? "Expected a decimal string" : "Invalid value";
        String parser = parserText(failure.getOriginalMessage());
        return parser.startsWith("Duplicate field") ? parser : "Malformed JSON: " + parser;
    }

    /** What a value of this type looks like in the contract. */
    static String expected(Class<?> type) {
        if (type == LocalDate.class) return "a date (YYYY-MM-DD)";
        if (type == Instant.class || type == OffsetDateTime.class || type == ZonedDateTime.class) return "an RFC 3339 instant with an offset";
        if (type == LocalTime.class) return "a time of day (HH:MM)";
        if (type == UUID.class) return "a UUID";
        if (type == BigDecimal.class) return "a decimal string";
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class) return "a whole number";
        if (type == Double.class || type == double.class) return "a number";
        if (type == Boolean.class || type == boolean.class) return "true or false";
        if (type == String.class) return "a string";
        if (type.isEnum())
            return "one of " + Arrays.stream(Required.value(type.getEnumConstants(), "enum constants")).map(String::valueOf).collect(Collectors.joining(", "));
        if (type.isArray() || Collection.class.isAssignableFrom(type)) return "an array";
        if (Map.class.isAssignableFrom(type) || type.isRecord()) return "an object";
        return "a different JSON value";
    }

    /** The JSON path of a mapping failure, without its last {@code drop} steps; empty at the document's root. */
    private static String path(JsonMappingException mapping, int drop) {
        List<JsonMappingException.Reference> steps = mapping.getPath();
        var path = new StringBuilder("$");
        for (int index = 0; index < steps.size() - drop; index++) {
            JsonMappingException.Reference step = steps.get(index);
            @Nullable String field = step.getFieldName();
            if (field != null) path.append('.').append(field);
            else if (step.getIndex() >= 0) path.append('[').append(step.getIndex()).append(']');
        }
        return path.length() == 1 ? "" : Required.value(path.toString());
    }

    private static String location(@Nullable JsonLocation location) {
        if (location == null || location.getLineNr() < 1) return "";
        return "line " + location.getLineNr() + ", column " + location.getColumnNr();
    }

    /** The parser's own words, without the source excerpt it appends. */
    private static String parserText(@Nullable String original) {
        String text = firstLine(original, "");
        int source = text.indexOf("[Source:");
        if (source >= 0) text = text.substring(0, source);
        int marker = text.indexOf("(start marker");
        if (marker >= 0) text = text.substring(0, marker);
        text = text.strip();
        while (text.endsWith(";") || text.endsWith(":") || text.endsWith("(")) text = text.substring(0, text.length() - 1).strip();
        return text.isEmpty() ? "unexpected input" : text;
    }

    private static String firstLine(@Nullable String text, String otherwise) {
        if (text == null || text.isBlank()) return otherwise;
        return Required.value(text.strip().lines().findFirst().orElse(otherwise));
    }
}
