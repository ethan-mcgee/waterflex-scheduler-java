package dev.waterflex.routing;

import com.graphhopper.GHRequest;
import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.util.shapes.GHPoint;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.DistanceCalcEarth;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class MatrixController {
    public record Point(double lat, double lng) { }
    public record Request(List<Point> origins, List<Point> destinations, String expectedRoutingIdentity) { }
    public record Leg(boolean routable, Long seconds, Long meters) { }
    public record Matrix(String mapVersion, String routingIdentity, List<List<Leg>> legs) { }
    public record Geometry(String type, List<List<Double>> coordinates) { }
    public record RouteLeg(long seconds, long meters, Geometry geometry) { }
    public record RouteResponse(String routingIdentity, List<RouteLeg> legs) { }
    public record RouteRequest(List<Point> points, String expectedRoutingIdentity) { }

    private final GraphHopper hopper;
    private final String mapVersion;
    private final String routingIdentity;
    private record Cached(Leg leg, Instant expiresAt) { }
    private final Map<String, Cached> cache;
    private final Duration cacheTtl;

    public MatrixController(@Value("${routing.osm-file:/data/omaha.osm.pbf}") String osmFile,
                            @Value("${routing.graph-dir:/data/graph}") String graphDir,
                            @Value("${routing.map-version:unprepared}") String mapVersion,
                            @Value("${routing.cache.max-entries:100000}") int cacheEntries,
                            @Value("${routing.cache.ttl-minutes:60}") int cacheMinutes) {
        int limit = Math.max(1, cacheEntries);
        this.cacheTtl = Duration.ofMinutes(Math.max(1, cacheMinutes));
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(limit, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) { return size() > limit; }
        });
        String activeVersion = mapVersion;
        if (activeVersion.equals("unprepared")) {
            try { activeVersion = Files.readString(Path.of(graphDir).getParent().resolve("map-version")).trim(); }
            catch (Exception ignored) { }
        }
        this.mapVersion = activeVersion;
        this.routingIdentity = identity(graphDir, activeVersion);
        if (!new File(osmFile).isFile() && !new File(graphDir, "properties").isFile()) {
            this.hopper = null;
            return;
        }
        this.hopper = new GraphHopper();
        hopper.setOSMFile(osmFile);
        hopper.setGraphHopperLocation(graphDir);
        hopper.setEncodedValuesString("car_access, car_average_speed, road_access, road_environment, max_speed, ferry_speed");
        hopper.setProfiles(new Profile("car").setCustomModel(GHUtility.loadCustomModelFromJar("car.json")));
        hopper.importOrLoad();
    }

    @PostMapping("/internal/matrix")
    public Matrix matrix(@RequestBody Request request) {
        if (hopper == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Road graph not imported");
        checkIdentity(request.expectedRoutingIdentity());
        if (request.origins() == null || request.destinations() == null || request.origins().isEmpty() ||
            request.destinations().isEmpty() || request.origins().size() > 64 || request.destinations().size() > 64)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid matrix dimensions");
        request.origins().forEach(this::validatePoint);
        request.destinations().forEach(this::validatePoint);
        List<List<Leg>> rows = new ArrayList<>();
        for (Point origin : request.origins()) {
            List<Leg> row = new ArrayList<>();
            for (Point destination : request.destinations()) {
                String key = origin.lat() + "," + origin.lng() + ">" + destination.lat() + "," + destination.lng();
                Cached hit = cache.get(key);
                if (hit == null || !hit.expiresAt().isAfter(Instant.now())) {
                    hit = new Cached(route(origin, destination), Instant.now().plus(cacheTtl));
                    cache.put(key, hit);
                }
                row.add(hit.leg());
            }
            rows.add(row);
        }
        return new Matrix(mapVersion, routingIdentity, rows);
    }

    @PostMapping("/internal/route")
    public RouteResponse routeGeometry(@RequestBody RouteRequest request) {
        if (hopper == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Road graph not imported");
        checkIdentity(request.expectedRoutingIdentity());
        if (request.points() == null || request.points().size() < 2 || request.points().size() > 65)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid route point count");
        request.points().forEach(this::validatePoint);
        List<RouteLeg> legs = new ArrayList<>();
        for (int i = 1; i < request.points().size(); i++) {
            var response = hopper.route(new GHRequest(toGh(request.points().get(i - 1)), toGh(request.points().get(i))).setProfile("car"));
            if (response.hasErrors()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No road route");
            var path = response.getBest();
            if (!snapped(request.points().get(i - 1), request.points().get(i), path.getWaypoints()))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Endpoint outside road graph");
            List<List<Double>> coordinates = new ArrayList<>();
            var points = path.getPoints();
            for (int n = 0; n < points.size(); n++) coordinates.add(List.of(points.getLon(n), points.getLat(n)));
            legs.add(new RouteLeg(Math.max(1, (path.getTime() + 999) / 1000), Math.round(path.getDistance()),
                    new Geometry("LineString", coordinates)));
        }
        return new RouteResponse(routingIdentity, legs);
    }

    private void checkIdentity(String expected) {
        if (expected != null && !expected.isBlank() && !expected.equals(routingIdentity))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Routing identity changed");
    }

    private void validatePoint(Point point) {
        if (point == null || !Double.isFinite(point.lat()) || !Double.isFinite(point.lng()) ||
                Math.abs(point.lat()) > 90 || Math.abs(point.lng()) > 180)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid coordinate");
    }

    private static GHPoint toGh(Point point) { return new GHPoint(point.lat(), point.lng()); }

    private static boolean snapped(Point origin, Point destination, com.graphhopper.util.PointList snapped) {
        return snapped.size() >= 2 &&
                DistanceCalcEarth.DIST_EARTH.calcDist(origin.lat(), origin.lng(), snapped.getLat(0), snapped.getLon(0)) <= 1000 &&
                DistanceCalcEarth.DIST_EARTH.calcDist(destination.lat(), destination.lng(), snapped.getLat(snapped.size() - 1), snapped.getLon(snapped.size() - 1)) <= 1000;
    }

    private static String identity(String graphDir, String mapVersion) {
        try {
            Path graph = Path.of(graphDir);
            Path manifest = (Files.exists(graph) ? graph.toRealPath() : graph).getParent().resolve("manifest.json");
            String checksum = Files.isRegularFile(manifest)
                    ? new ObjectMapper().readTree(Files.readString(manifest)).path("mergedSha256").asText("") : "";
            if (checksum.isBlank()) checksum = mapVersion;
            String settings = checksum + "|car|car_access,car_average_speed,road_access,road_environment,max_speed,ferry_speed|car.json|GraphHopper-11.0|snap-1000m";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(settings.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Cannot identify routing graph", e); }
    }

    private Leg route(Point origin, Point destination) {
        var response = hopper.route(new GHRequest(toGh(origin), toGh(destination)).setProfile("car"));
        if (response.hasErrors()) return new Leg(false, null, null);
        var path = response.getBest();
        if (!snapped(origin, destination, path.getWaypoints()))
            return new Leg(false, null, null);
        return new Leg(true, Math.max(1, (path.getTime() + 999) / 1000), Math.round(path.getDistance()));
    }

    @GetMapping("/health")
    public Map<String, Object> health() { return Map.of("ready", hopper != null, "mapVersion", mapVersion,
            "routingIdentity", routingIdentity, "profile", "car", "engineVersion", "11.0"); }

    @PreDestroy
    public void close() { if (hopper != null) hopper.close(); }
}
