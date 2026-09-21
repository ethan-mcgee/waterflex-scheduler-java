package dev.waterflex.scheduler;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@RestController
public class DispatchGeometryController {
    public record LineString(String type, List<List<Double>> coordinates) { }
    public record RoadProperties(String technicianId, String interval, int legIndex, long seconds, long meters) { }
    public record RoadFeature(String type, LineString geometry, RoadProperties properties) { }
    private record SavedGeometry(String before, String after, String weights) { }
    private record Stop(String id, String technicianId, int sequence, Instant plannedStart, RoadClient.Point point) { }
    private record Absence(Instant start, Instant end) { }
    private final JdbcTemplate jdbc;
    private final RoadClient roads;
    private final ObjectMapper mapper = new ObjectMapper();

    public DispatchGeometryController(JdbcTemplate jdbc, RoadClient roads) {
        this.jdbc = jdbc;
        this.roads = roads;
    }

    @ExceptionHandler(RoadClient.RoadUnavailable.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> roadUnavailable(RoadClient.RoadUnavailable error) {
        return Required.value(Map.of("detail", error.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> requestFailure(ResponseStatusException error) {
        @Nullable String reason = error.getReason();
        String detail = reason == null || reason.isBlank() ? "Dispatch geometry request failed" : reason;
        return ResponseEntity.status(error.getStatusCode()).body(Required.value(Map.of("detail", detail)));
    }

    private static LineString lineString(JsonNode geometry) {
        JsonNode positions = geometry.path("coordinates");
        if (!"LineString".equals(geometry.path("type").asText()) || !positions.isArray() || positions.size() < 2)
            throw new RoadClient.RoadUnavailable("Malformed road geometry");
        List<List<Double>> coordinates = new ArrayList<>();
        for (JsonNode position : positions) {
            if (!position.isArray() || position.size() != 2 || !position.get(0).isNumber() || !position.get(1).isNumber())
                throw new RoadClient.RoadUnavailable("Malformed road geometry");
            double lng = position.get(0).asDouble(), lat = position.get(1).asDouble();
            if (!Double.isFinite(lng) || !Double.isFinite(lat) || Math.abs(lng) > 180 || Math.abs(lat) > 90)
                throw new RoadClient.RoadUnavailable("Malformed road geometry");
            coordinates.add(List.of(lng, lat));
        }
        return new LineString("LineString", coordinates);
    }

    @GetMapping("/v1/dispatch/geometry")
    public Map<String, @NonNull Object> geometry(@RequestParam("metro_id") String metroId, @RequestParam String date,
                                         @RequestParam(value = "run_id", required = false) @Nullable String runId,
                                         @RequestParam(value = "phase", defaultValue = "current") String phase) {
        LocalDate day;
        try { day = LocalDate.parse(date); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid service date"); }
        if (!Set.of("current", "before", "after").contains(phase))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid route phase");
        if (phase.equals("after") && (runId == null || runId.isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Optimization run required");
        Timestamp serviceDate = Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
        Map<String, RoadClient.Point> homes = new LinkedHashMap<>();
        jdbc.query("SELECT id, \"homeLat\", \"homeLng\" FROM technician WHERE \"metroId\"=? ORDER BY id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> homes.put(Required.string(rs, 1),
                        Required.location(rs, 2, 3, HttpStatus.CONFLICT)), metroId);
        List<Stop> stops;
        if (phase.equals("current")) {
            stops = jdbc.query("SELECT a.id, a.\"technicianId\", a.sequence, a.\"plannedStart\", ad.lat, ad.lng FROM appointment a JOIN job j ON j.id=a.\"jobId\" JOIN address ad ON ad.id=j.\"addressId\" JOIN technician t ON t.id=a.\"technicianId\" WHERE t.\"metroId\"=? AND a.\"serviceDate\"=? AND a.\"cancelledAt\" IS NULL ORDER BY a.\"technicianId\", a.sequence",
                    (rs, _) -> new Stop(Required.string(rs, 1), Required.string(rs, 2), Required.integer(rs, 3), Required.value(Required.timestamp(rs, 4).toInstant()),
                            Required.location(rs, 5, 6, HttpStatus.CONFLICT)), metroId, serviceDate);
        } else {
            if (runId == null || runId.isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Optimization run required");
            var runs = jdbc.query("SELECT \"baselineAssignments\"::text, \"proposedAssignments\"::text, weights::text FROM optimization_run WHERE id=? AND \"metroId\"=? AND \"serviceDate\"=?",
                    (rs, _) -> new SavedGeometry(Required.string(rs, 1), Required.string(rs, 2), Required.string(rs, 3)), runId, metroId, serviceDate);
            if (runs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Optimization run not found");
            try {
                if (!SavedJson.provenance(Required.value(mapper.readTree(runs.getFirst().weights()))).path("mapVersion").asText().equals(roads.activeIdentity()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Routing graph changed; generate a new preview");
                List<Stop> savedStops = new ArrayList<>();
                for (JsonNode assignment : SavedJson.assignments(Required.value(mapper.readTree(phase.equals("before") ? runs.getFirst().before() : runs.getFirst().after())))) {
                    String technicianId = SavedJson.text(Required.value(assignment), "technicianId");
                    if (!homes.containsKey(technicianId))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Saved route technician is unavailable");
                    savedStops.add(new Stop(SavedJson.text(Required.value(assignment), "appointmentId"), technicianId,
                            Math.toIntExact(SavedJson.integer(Required.value(assignment), "sequence")),
                            Required.value(Instant.parse(SavedJson.text(Required.value(assignment), "plannedStart"))),
                            new RoadClient.Point(assignment.path("locationLat").asDouble(), assignment.path("locationLng").asDouble())));
                }
                stops = savedStops;
            } catch (ResponseStatusException e) { throw e; }
              catch (Exception e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid optimization assignments"); }
        }
        Map<String, List<Absence>> absences = new HashMap<>();
        jdbc.query("SELECT r.\"technicianId\", i.\"startMin\", i.\"endMin\" FROM time_off_request r JOIN time_off_interval i ON i.\"requestId\"=r.id WHERE r.status='APPROVED' AND i.\"serviceDate\"=? ORDER BY i.\"startMin\"",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> absences.computeIfAbsent(Required.string(rs, 1), _ -> new ArrayList<>())
                        .add(new Absence(ScheduleCutoff.localMinute(day, Required.integer(rs, 2), false),
                                ScheduleCutoff.localMinute(day, Required.integer(rs, 3), true))), serviceDate);
        Map<String, List<Stop>> groups = new LinkedHashMap<>();
        for (Stop stop : stops) {
            int interval = 0;
            for (Absence absence : absences.getOrDefault(stop.technicianId(), List.of())) {
                if (!stop.plannedStart().isBefore(absence.start()) && stop.plannedStart().isBefore(absence.end()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Visit overlaps approved absence");
                if (!stop.plannedStart().isBefore(absence.end())) interval++;
            }
            groups.computeIfAbsent(stop.technicianId() + ":" + interval, _ -> new ArrayList<>()).add(stop);
        }
        String identity = roads.activeIdentity();
        List<RoadFeature> features = new ArrayList<>();
        for (var group : groups.entrySet()) {
            List<Stop> route = group.getValue();
            route.sort(Comparator.comparingInt((Stop stop) -> stop.sequence())
                    .thenComparing((Stop stop) -> stop.id()));
            String techId = route.getFirst().technicianId();
            RoadClient.Point home = homes.get(techId);
            if (home == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician unavailable");
            List<RoadClient.Point> points = new ArrayList<>();
            points.add(home);
            route.forEach(stop -> points.add(stop.point()));
            points.add(home);
            for (int offset = 0; offset < points.size() - 1; offset += 64) {
                JsonNode legs = roads.routeGeometry(Required.value(points.subList(offset, Math.min(points.size(), offset + 65))), identity).path("legs");
                for (int i = 0; i < legs.size(); i++) {
                    JsonNode leg = legs.get(i);
                    features.add(new RoadFeature("Feature", lineString(Required.value(leg.path("geometry"))),
                            new RoadProperties(techId, Required.value(group.getKey()), offset + i,
                                    leg.path("seconds").asLong(), leg.path("meters").asLong())));
                }
            }
        }
        List<Map<String, Object>> displayedStops = stops.stream().map(stop -> Map.<String, Object>of(
                "id", stop.id(), "technicianId", stop.technicianId(), "sequence", stop.sequence(),
                "plannedStart", stop.plannedStart().toString(), "lat", stop.point().lat(), "lng", stop.point().lng())).toList();
        return Required.value(Map.of("type", "FeatureCollection", "features", features, "stops", Required.value(displayedStops),
                "routingIdentity", identity, "serviceDate", Required.value(day.toString()), "phase", phase));
    }
}
