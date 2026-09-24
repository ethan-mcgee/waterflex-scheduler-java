package dev.waterflex.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.waterflex.scheduler.optimizer.RouteEvaluator;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Versioned current timing; legacy rows deliberately remain unavailable until independently replanned. */
public final class ScheduleSegments {
    public record Saved(long version, @Nullable String routingIdentity, List<RouteEvaluator.WorkingSegment> segments) { }
    private static final ObjectMapper JSON = new ObjectMapper();
    private ScheduleSegments() { }

    public static void save(JdbcTemplate jdbc, String technician, LocalDate date,
            List<RouteEvaluator.WorkingSegment> segments, String routingIdentity) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (var segment : segments) encoded.add(Required.value(Map.<String, Object>of("departure", segment.departure().toString(),
                "returnedAt", segment.returnedAt().toString(), "appointmentIds", segment.visitIds())));
        if (!segments.isEmpty() && routingIdentity.isBlank()) throw invalid();
        try {
            String json = Required.value(JSON.writeValueAsString(encoded));
            if (jdbc.update("UPDATE schedule_day SET \"routeTiming\"=jsonb_build_object('format',1,'scheduleVersion',version,'routingIdentity',?::text,'segments',?::jsonb) WHERE \"technicianId\"=? AND \"serviceDate\"=?",
                    routingIdentity.isBlank() ? null : routingIdentity, json, technician,
                    Timestamp.from(date.atStartOfDay(ZoneOffset.UTC).toInstant())) != 1) throw invalid();
        } catch (java.io.IOException exception) { throw new IllegalStateException("Current route timing could not be encoded", exception); }
    }

    public static Saved decode(String raw) {
        try {
            JsonNode root = Required.value(JSON.readTree(raw));
            if (!root.isObject() || SavedJson.integer(root, "format") != 1) throw invalid();
            long version = SavedJson.integer(root, "scheduleVersion");
            if (version < 0 || !root.path("segments").isArray()) throw invalid();
            JsonNode identity = root.path("routingIdentity");
            if (!identity.isNull() && (!identity.isTextual() || identity.asText().isBlank())) throw invalid();
            List<RouteEvaluator.WorkingSegment> segments = new ArrayList<>();
            Set<String> seen = new HashSet<>(); Instant previous = Instant.MIN;
            for (JsonNode item : root.path("segments")) {
                Instant departure = Required.value(Instant.parse(SavedJson.text(Required.value(item), "departure")));
                Instant returned = Required.value(Instant.parse(SavedJson.text(Required.value(item), "returnedAt")));
                if (!departure.isBefore(returned) || departure.isBefore(previous) || !item.path("appointmentIds").isArray()
                        || item.path("appointmentIds").isEmpty()) throw invalid();
                List<String> ids = new ArrayList<>();
                for (JsonNode id : item.path("appointmentIds")) {
                    if (!id.isTextual() || id.asText().isBlank() || !seen.add(id.asText())) throw invalid();
                    ids.add(id.asText());
                }
                segments.add(new RouteEvaluator.WorkingSegment(departure, returned, Required.value(List.copyOf(ids)))); previous = returned;
            }
            if (!segments.isEmpty() && !identity.isTextual()) throw invalid();
            return new Saved(version, identity.isNull() ? null : Required.value(identity.asText()), Required.value(List.copyOf(segments)));
        } catch (ResponseStatusException exception) { throw exception; }
          catch (Exception exception) { throw invalid(); }
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.CONFLICT, "Invalid current route timing; replan this day"); }
}
