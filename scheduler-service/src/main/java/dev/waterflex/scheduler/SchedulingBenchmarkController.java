package dev.waterflex.scheduler;

import dev.waterflex.scheduler.optimizer.RouteEvaluator;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import dev.waterflex.scheduler.optimizer.TravelBreakdown;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Read-only independent measurement, installed only in the explicit isolated benchmark profile. */
@Profile("benchmark")
@RestController
public final class SchedulingBenchmarkController {
    public record Request(String metroId, List<String> dates) {
        public Request {
            metroId = RequestChecks.text(metroId, "metroId");
            if (dates == null || dates.isEmpty() || dates.size() > 21 || new HashSet<>(dates).size() != dates.size())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Distinct benchmark dates required");
            for (String date : dates) RequestChecks.date(date);
            dates = Required.value(List.copyOf(dates));
        }
    }
    public record DayMetrics(String date, SchedulingPolicy.Metrics policy, long waitingMinutes,
            long roadSeconds, java.math.BigDecimal configuredBufferSeconds, java.math.BigDecimal roundingSeconds,
            int confirmedAppointments, int reservedStops, Map<String, List<RouteEvaluator.WorkingSegment>> segments) { }
    private final JdbcTemplate jdbc;
    private final BookingSnapshotLoader loader;
    private final SnapshotRouting routing;
    private final RoadClient roads;
    public SchedulingBenchmarkController(JdbcTemplate jdbc, BookingSnapshotLoader loader, SnapshotRouting routing, RoadClient roads) {
        this.jdbc = jdbc; this.loader = loader; this.routing = routing; this.roads = roads;
    }

    public record CacheRequest(boolean warm, List<RoadClient.Point> points) {
        public CacheRequest {
            if (points == null || points.isEmpty() || points.size() > 64) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Benchmark points required");
            for (RoadClient.Point point : points) if (point == null || !Double.isFinite(point.lat()) || !Double.isFinite(point.lng())
                    || Math.abs(point.lat()) > 90 || Math.abs(point.lng()) > 180) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid benchmark point");
            points = Required.value(List.copyOf(points));
        }
    }
    @PostMapping("/internal/benchmark/cache")
    public Map<String, Object> cache(@RequestBody CacheRequest request) {
        isolated();
        String schema = Required.query(jdbc, "SELECT current_schema()", String.class);
        if (!schema.startsWith("benchmark_")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cache experiments require a benchmark_ schema");
        String identity = roads.activeIdentity();
        jdbc.update("DELETE FROM road_route_cache WHERE \"mapVersion\"=?", identity);
        roads.clearMemoryForIsolatedBenchmark();
        if (request.warm()) {
            Map<String, RoadClient.Point> points = new LinkedHashMap<>();
            for (int i = 0; i < request.points().size(); i++) points.put("point-" + i, Required.value(request.points().get(i)));
            roads.matrix(points);
        }
        return Required.value(Map.<String, Object>of("routingIdentity", identity, "warm", request.warm()));
    }

    private void isolated() {
        if (!"waterflex_test".equals(Required.query(jdbc, "SELECT current_database()", String.class)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Benchmark requires isolated waterflex_test");
    }

    @PostMapping("/internal/benchmark/audit")
    public Map<String, Object> audit(@RequestBody Request request) {
        isolated();
        String identity = roads.activeIdentity();
        List<LocalDate> dates = request.dates().stream().map(value -> Required.value(LocalDate.parse(value))).toList();
        var facts = loader.loadDates(request.metroId(), Required.value(dates), Required.value(Instant.now()), identity);
        List<DayMetrics> measurements = new ArrayList<>();
        for (var entry : facts.days().entrySet()) {
            var day = entry.getValue();
            var arrangement = day.actualArrangement();
            var routed = routing.arrangements(Required.value(entry.getKey()), day, day.visits(),
                    Required.value(List.<BookingSnapshot.Arrangement>of(arrangement, day.baseline())), identity);
            var reservation = routed.evaluate(routed.baseline(), routed.visits(), facts.rates());
            var confirmed = routed.plan(arrangement, routed.visits(), facts.rates(), true);
            var result = RouteEvaluator.evaluate(confirmed);
            if (!reservation.feasible() || !result.feasible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Independent benchmark route validation failed");
            long roadSeconds = 0; var buffer = java.math.BigDecimal.ZERO; var rounding = java.math.BigDecimal.ZERO;
            for (var route : confirmed.getRoutes()) {
                var travel = TravelBreakdown.forRoute(confirmed, route, Required.value(result.segments().get(route.getId())));
                roadSeconds = Math.addExact(roadSeconds, travel.road_seconds());
                buffer = buffer.add(travel.configured_buffer_seconds()); rounding = rounding.add(travel.rounding_seconds());
            }
            measurements.add(new DayMetrics(Required.value(entry.getKey().toString()), SchedulingPolicy.measure(confirmed), result.waitingMinutes(),
                    roadSeconds, Required.value(buffer), Required.value(rounding), confirmed.getVisits().size(), routed.visits().size() - confirmed.getVisits().size(), result.segments()));
        }
        var current = loader.loadDates(request.metroId(), Required.value(dates), Required.value(Instant.now()), identity);
        if (!facts.configurationFingerprint().equals(current.configurationFingerprint())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Benchmark configuration changed");
        for (var entry : facts.days().entrySet()) {
            var latest = Required.value(current.days().get(entry.getKey()));
            if (latest.reservationVersion() != entry.getValue().reservationVersion() || !latest.visits().equals(entry.getValue().visits())
                    || !latest.technicians().equals(entry.getValue().technicians()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Benchmark schedule changed during validation");
        }
        return Required.value(Map.<String, Object>of("routingIdentity", identity, "configurationFingerprint", facts.configurationFingerprint(),
                "days", measurements, "independentlyValidated", true));
    }
}
