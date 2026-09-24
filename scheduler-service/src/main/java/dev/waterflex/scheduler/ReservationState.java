package dev.waterflex.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.waterflex.scheduler.BookingSnapshot.Arrangement;
import dev.waterflex.scheduler.optimizer.RouteEvaluator;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Restart-safe common arrangement. Persisted JSON is validated before any schedule use. */
public record ReservationState(String configurationFingerprint, String routingIdentity,
        Map<String, Long> scheduleVersions, Arrangement arrangement, Map<String, Hold> holds,
        Map<String, List<RouteEvaluator.WorkingSegment>> segments) {
    public static final int FORMAT = 1;
    public record Hold(String jobId, String offerId, Instant expiresAt, boolean overtimeAuthorized) {
        public Hold {
            if (jobId.isBlank() || offerId.isBlank()) throw invalid();
        }
    }
    public ReservationState {
        if (configurationFingerprint.isBlank() || routingIdentity.isBlank()) throw invalid();
        scheduleVersions = Required.value(Map.copyOf(scheduleVersions));
        holds = Required.value(Map.copyOf(holds));
        if (!scheduleVersions.keySet().equals(arrangement.routes().keySet()) || !segments.keySet().equals(arrangement.routes().keySet())) throw invalid();
        for (Long version : scheduleVersions.values()) if (version < 0) throw invalid();
        Set<String> assigned = new HashSet<>();
        Map<String, List<RouteEvaluator.WorkingSegment>> copied = new TreeMap<>();
        for (var entry : arrangement.routes().entrySet()) {
            assigned.addAll(entry.getValue());
            List<String> segmented = new ArrayList<>();
            List<RouteEvaluator.WorkingSegment> routeSegments = new ArrayList<>();
            Instant previous = Instant.MIN;
            for (RouteEvaluator.WorkingSegment segment : Required.value(segments.get(entry.getKey()))) {
                if (segment.visitIds().isEmpty() || segment.departure().isAfter(segment.returnedAt()) || segment.departure().isBefore(previous)) throw invalid();
                previous = segment.returnedAt();
                var copy = new RouteEvaluator.WorkingSegment(segment.departure(), segment.returnedAt(), Required.value(List.copyOf(segment.visitIds())));
                routeSegments.add(copy);
                segmented.addAll(copy.visitIds());
            }
            if (!segmented.equals(entry.getValue())) throw invalid();
            copied.put(entry.getKey(), Required.value(List.copyOf(routeSegments)));
        }
        if (!assigned.containsAll(holds.keySet())) throw invalid();
        segments = Required.value(Collections.unmodifiableMap(copied));
    }

    /** The only factory used before saving new arrangements independently evaluates all stops. */
    public static ReservationState validate(BookingSnapshot.Day day, BookingSnapshot.Rates rates, Arrangement arrangement,
            Map<String, BookingSnapshot.Visit> visits, Map<String, Hold> holds, String configuration, String routingIdentity) {
        Set<String> reservations = new HashSet<>();
        for (var visit : visits.values()) if (visit.reservation()) {
            reservations.add(visit.id());
            Hold hold = Required.value(holds.get(visit.id()), "reservation metadata");
            if (!visit.jobId().equals(hold.jobId())) throw invalid();
        }
        if (!reservations.equals(holds.keySet())) throw invalid();
        var result = day.evaluate(arrangement, visits, rates);
        if (!result.feasible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation arrangement is infeasible");
        Map<String, Long> versions = new TreeMap<>();
        day.technicians().forEach((id, technician) -> versions.put(id, technician.scheduleVersion()));
        return new ReservationState(configuration, routingIdentity, versions, arrangement, holds, result.segments());
    }

    public String encode(ObjectMapper mapper) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("configurationFingerprint", configurationFingerprint);
        root.put("routingIdentity", routingIdentity);
        root.put("scheduleVersions", scheduleVersions);
        root.put("routes", arrangement.routes());
        Map<String, Object> holdJson = new TreeMap<>();
        holds.forEach((id, hold) -> holdJson.put(id, Map.of("jobId", hold.jobId(), "offerId", hold.offerId(),
                "expiresAt", Required.value(hold.expiresAt().toString()), "overtimeAuthorized", hold.overtimeAuthorized())));
        root.put("holds", holdJson);
        Map<String, Object> segmentJson = new TreeMap<>();
        segments.forEach((id, values) -> segmentJson.put(id, values.stream().map(segment -> Map.of(
                "departure", Required.value(segment.departure().toString()), "returnedAt", Required.value(segment.returnedAt().toString()), "visitIds", segment.visitIds())).toList()));
        root.put("segments", segmentJson);
        try { return Required.value(mapper.writeValueAsString(root)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException("Cannot encode reservation state", exception); }
    }

    public static ReservationState decode(ObjectMapper mapper, String json) {
        try {
            JsonNode root = SavedJson.object(Required.value(mapper.readTree(json), "reservation state"));
            if (SavedJson.integer(root, "format") != FORMAT) throw invalid();
            String configuration = SavedJson.text(root, "configurationFingerprint"), identity = SavedJson.text(root, "routingIdentity");
            Map<String, Long> versions = new TreeMap<>();
            JsonNode versionJson = SavedJson.object(Required.value(root.path("scheduleVersions")));
            for (var entry : versionJson.properties()) {
                if (entry.getKey().isBlank() || !entry.getValue().isIntegralNumber() || !entry.getValue().canConvertToLong()
                        || entry.getValue().longValue() < 0) throw invalid();
                versions.put(entry.getKey(), entry.getValue().longValue());
            }
            Map<String, List<String>> routes = new TreeMap<>();
            for (var entry : SavedJson.object(Required.value(root.path("routes"))).properties())
                routes.put(entry.getKey(), strings(Required.value(entry.getValue())));
            Map<String, Hold> holds = new TreeMap<>();
            for (var entry : SavedJson.object(Required.value(root.path("holds"))).properties()) {
                JsonNode value = SavedJson.object(Required.value(entry.getValue()));
                if (entry.getKey().isBlank() || !value.path("overtimeAuthorized").isBoolean()) throw invalid();
                holds.put(entry.getKey(), new Hold(SavedJson.text(value, "jobId"), SavedJson.text(value, "offerId"),
                        Required.value(Instant.parse(SavedJson.text(value, "expiresAt"))), value.path("overtimeAuthorized").booleanValue()));
            }
            Map<String, List<RouteEvaluator.WorkingSegment>> segments = new TreeMap<>();
            for (var entry : SavedJson.object(Required.value(root.path("segments"))).properties()) {
                List<RouteEvaluator.WorkingSegment> values = new ArrayList<>();
                for (JsonNode item : SavedJson.array(Required.value(entry.getValue()))) {
                    JsonNode value = SavedJson.object(Required.value(item));
                    values.add(new RouteEvaluator.WorkingSegment(Required.value(Instant.parse(SavedJson.text(value, "departure"))),
                            Required.value(Instant.parse(SavedJson.text(value, "returnedAt"))), strings(Required.value(value.path("visitIds")))));
                }
                segments.put(entry.getKey(), values);
            }
            return new ReservationState(configuration, identity, versions, new Arrangement(routes), holds, segments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | java.time.DateTimeException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid persisted reservation arrangement", exception);
        }
    }

    private static List<String> strings(JsonNode node) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : SavedJson.array(node)) {
            if (!item.isTextual() || item.textValue().isBlank()) throw invalid();
            result.add(Required.value(item.textValue()));
        }
        return Required.value(List.copyOf(result));
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Invalid persisted reservation arrangement");
    }
}
