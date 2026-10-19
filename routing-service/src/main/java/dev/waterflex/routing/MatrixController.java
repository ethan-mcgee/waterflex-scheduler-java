package dev.waterflex.routing;

import org.jspecify.annotations.Nullable;
import java.util.Objects;
import com.graphhopper.GHRequest;
import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.config.CHProfile;
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
    public record Point(Double lat, Double lng) {
        public Point {
            if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing or invalid coordinate");
        }
        public Point(double lat, double lng) { this(Required.value(Double.valueOf(lat)), Required.value(Double.valueOf(lng))); }
    }
    public record Request(List<Point> origins, List<Point> destinations, @Nullable String expectedRoutingIdentity) {
        public Request { origins = validPoints(origins, 1, 64); destinations = validPoints(destinations, 1, 64); }
    }
    public record Leg(boolean routable, @Nullable Long seconds, @Nullable Long meters) { }
    public record Matrix(String mapVersion, String routingIdentity, List<List<Leg>> legs) { }
    public record Pair(String id, Point origin, Point destination) {
        // JSON null still reaches these components (the strict mapper rejects null only for primitives), so each is
        // checked through a helper that declares its parameter nullable and returns the value it accepted.
        public Pair {
            id = requireId(id);
            origin = requirePoint(origin); destination = requirePoint(destination);
        }
    }
    public record SparseRequest(List<Pair> pairs, String expectedRoutingIdentity) {
        public SparseRequest {
            pairs = requirePairs(pairs);
            expectedRoutingIdentity = requireIdentity(expectedRoutingIdentity);
            var ids = new java.util.HashSet<String>();
            for (Pair pair : pairs) if (!ids.add(pair.id())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate pair");
            pairs = Required.value(List.copyOf(pairs));
        }
    }
    private static String requireId(@Nullable String id) {
        if (id == null || id.isBlank() || id.length() > 300) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pair ID");
        return id;
    }
    private static Point requirePoint(@Nullable Point point) {
        validatePoint(point);
        return Required.value(point);
    }
    private static List<Pair> requirePairs(@Nullable List<Pair> pairs) {
        if (pairs == null || pairs.isEmpty() || pairs.size() > 256) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pairs and routing identity required");
        for (Pair pair : pairs) if (pair == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate or missing pair");
        return pairs;
    }
    private static String requireIdentity(@Nullable String identity) {
        if (identity == null || identity.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pairs and routing identity required");
        return identity;
    }
    public record PairResult(String id, Leg leg) { }
    public record SparseResponse(String routingIdentity, List<PairResult> pairs) { }
    public record Geometry(String type, List<List<Double>> coordinates) { }
    public record RouteLeg(long seconds, long meters, Geometry geometry) { }
    public record RouteResponse(String routingIdentity, List<RouteLeg> legs) { }
    public record RouteRequest(List<Point> points, @Nullable String expectedRoutingIdentity) {
        public RouteRequest { points = validPoints(points, 2, 65); }
    }

    private final @Nullable GraphHopper hopper;
    private final String mapVersion;
    private final String routingIdentity;
    private final boolean chEnabled;
    private record Cached(Leg leg, Instant expiresAt) { }
    private final Map<String, Cached> cache;
    private final Duration cacheTtl;
    /**
     * Routes the pairs of one request at once, up to {@code routing.parallelism} at a time across all requests
     * (GraphHopper's route is safe to call concurrently). A batch of 256 cold pairs that took 256 queries in a row
     * now takes about 256 divided by the parallelism.
     */
    private final java.util.concurrent.ExecutorService workers;
    private final int parallelism;

    public MatrixController(String osmFile, String graphDir, String mapVersion, int cacheEntries, int cacheMinutes) {
        this(osmFile, graphDir, mapVersion, cacheEntries, cacheMinutes, false);
    }

    public MatrixController(String osmFile, String graphDir, String mapVersion, int cacheEntries, int cacheMinutes, boolean chEnabled) {
        this(osmFile, graphDir, mapVersion, cacheEntries, cacheMinutes, chEnabled, 0, 1);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MatrixController(@Value("${routing.osm-file:/data/omaha.osm.pbf}") String osmFile,
                            @Value("${routing.graph-dir:/data/graph}") String graphDir,
                            @Value("${routing.map-version:unprepared}") String mapVersion,
                            @Value("${routing.cache.max-entries:100000}") int cacheEntries,
                            @Value("${routing.cache.ttl-minutes:60}") int cacheMinutes,
                            @Value("${routing.ch.enabled:false}") boolean chEnabled,
                            @Value("${routing.parallelism:0}") int configuredParallelism,
                            @Value("${routing.ch.preparation-threads:1}") int preparationThreads) {
        if (configuredParallelism < 0 || configuredParallelism > 64) throw new IllegalArgumentException("routing.parallelism must be 0 (the processors available) to 64");
        if (preparationThreads < 1 || preparationThreads > 64) throw new IllegalArgumentException("routing.ch.preparation-threads must be 1 to 64");
        this.parallelism = configuredParallelism == 0 ? Math.max(1, Runtime.getRuntime().availableProcessors()) : configuredParallelism;
        this.workers = Required.value(java.util.concurrent.Executors.newFixedThreadPool(parallelism, task -> {
            Thread thread = new Thread(task, "routing-query"); thread.setDaemon(true); return thread;
        }));
        this.chEnabled = chEnabled;
        // Preparation is isolated from the existing flexible graph, including rollback.
        if (chEnabled) graphDir = graphDir + "-ch-car-v1";
        int limit = Math.max(1, cacheEntries);
        this.cacheTtl = Required.value(Duration.ofMinutes(Math.max(1, cacheMinutes)));
        this.cache = Required.value(Collections.synchronizedMap(new LinkedHashMap<>(limit, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.@Nullable Entry<String, Cached> eldest) { return size() > limit; }
        }));
        String activeVersion = mapVersion;
        if (activeVersion.equals("unprepared")) {
            try { activeVersion = Files.readString(Required.value(Path.of(graphDir).toAbsolutePath().getParent()).resolve("map-version")).trim(); }
            catch (Exception ignored) { }
        }
        this.mapVersion = Required.value(activeVersion);
        this.routingIdentity = identity(graphDir, Required.value(activeVersion), chEnabled);
        if (!new File(osmFile).isFile() && !new File(graphDir, "properties").isFile()) {
            this.hopper = null;
            return;
        }
        GraphHopper active = new GraphHopper();
        this.hopper = active;
        active.setOSMFile(osmFile);
        active.setGraphHopperLocation(graphDir);
        active.setEncodedValuesString("car_access, car_average_speed, road_access, road_environment, max_speed, ferry_speed");
        active.setProfiles(new Profile("car").setCustomModel(GHUtility.loadCustomModelFromJar("car.json")));
        if (chEnabled) {
            active.getCHPreparationHandler().setCHProfiles(new CHProfile("car"));
            active.getCHPreparationHandler().setPreparationThreads(preparationThreads);
        }
        active.importOrLoad();
    }

    @PostMapping("/internal/matrix")
    public Matrix matrix(@RequestBody Request request) {
        checkIdentity(request.expectedRoutingIdentity());
        if (hopper == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Road graph not imported");
        List<Point> origins = request.origins(), destinations = request.destinations();
        List<Leg> flat = legs(origins.size() * destinations.size(),
                index -> cachedLeg(Required.value(origins.get(index / destinations.size())), Required.value(destinations.get(index % destinations.size()))));
        List<List<Leg>> rows = new ArrayList<>();
        for (int from = 0; from < origins.size(); from++)
            rows.add(Required.value(List.copyOf(flat.subList(from * destinations.size(), (from + 1) * destinations.size()))));
        return new Matrix(mapVersion, routingIdentity, Required.value(rows));
    }

    @PostMapping("/internal/route")
    public RouteResponse routeGeometry(@RequestBody RouteRequest request) {
        checkIdentity(request.expectedRoutingIdentity());
        if (hopper == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Road graph not imported");
        List<RouteLeg> legs = new ArrayList<>();
        for (int i = 1; i < request.points().size(); i++) {
            var response = Objects.requireNonNull(hopper, "Road graph not imported").route(new GHRequest(toGh(Required.value(request.points().get(i - 1))), toGh(Required.value(request.points().get(i)))).setProfile("car"));
            if (response.hasErrors()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "No road route");
            var path = response.getBest();
            if (!snapped(Required.value(request.points().get(i - 1)), Required.value(request.points().get(i)), Required.value(path.getWaypoints())))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Endpoint outside road graph");
            List<List<Double>> coordinates = new ArrayList<>();
            var points = path.getPoints();
            for (int n = 0; n < points.size(); n++) coordinates.add(List.of(points.getLon(n), points.getLat(n)));
            if (coordinates.isEmpty() && path.getWaypoints().size() >= 1)
                coordinates.add(List.of(path.getWaypoints().getLon(0), path.getWaypoints().getLat(0)));
            if (coordinates.size() == 1) coordinates.add(List.copyOf(coordinates.getFirst()));
            legs.add(new RouteLeg(Math.max(0, (path.getTime() + 999) / 1000), Math.round(path.getDistance()),
                    new Geometry("LineString", Required.value(coordinates))));
        }
        return new RouteResponse(routingIdentity, Required.value(legs));
    }

    @PostMapping("/internal/legs")
    public SparseResponse sparse(@RequestBody SparseRequest request) {
        checkIdentity(request.expectedRoutingIdentity());
        if (hopper == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Road graph not imported");
        List<Pair> pairs = request.pairs();
        List<Leg> flat = legs(pairs.size(), index -> { Pair pair = Required.value(pairs.get(index)); return cachedLeg(pair.origin(), pair.destination()); });
        List<PairResult> results = new ArrayList<>();
        for (int index = 0; index < pairs.size(); index++) results.add(new PairResult(Required.value(pairs.get(index)).id(), Required.value(flat.get(index))));
        return new SparseResponse(routingIdentity, results);
    }

    /** The leg for one directed pair, from the cache or routed now. */
    private Leg cachedLeg(Point origin, Point destination) {
        String key = origin.lat() + "," + origin.lng() + ">" + destination.lat() + "," + destination.lng();
        Cached hit = cache.get(key);
        if (hit == null || !hit.expiresAt().isAfter(Instant.now())) {
            hit = new Cached(route(origin, destination), Required.value(Instant.now().plus(cacheTtl)));
            cache.put(key, hit);
        }
        return hit.leg();
    }

    /** Computes {@code count} legs on the shared workers and returns them in index order. */
    private List<Leg> legs(int count, java.util.function.IntFunction<Leg> leg) {
        if (count <= 1 || parallelism == 1) {
            List<Leg> legs = new ArrayList<>(count);
            for (int index = 0; index < count; index++) legs.add(leg.apply(index));
            return legs;
        }
        List<java.util.concurrent.Future<Leg>> futures = new ArrayList<>(count);
        for (int index = 0; index < count; index++) { int at = index; futures.add(Required.value(workers.submit(() -> leg.apply(at)))); }
        List<Leg> legs = new ArrayList<>(count);
        try {
            for (var future : futures) legs.add(Required.value(future.get()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Routing interrupted");
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof ResponseStatusException status) throw status;
            throw new IllegalStateException("Routing query failed", cause);
        }
        return legs;
    }

    private void checkIdentity(@Nullable String expected) {
        if (expected != null && !expected.isBlank() && !expected.equals(routingIdentity))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Routing identity changed");
    }

    private static List<Point> validPoints(@Nullable List<Point> points, int min, int max) {
        if (points == null || points.size() < min || points.size() > max)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid routing point count");
        points.forEach(MatrixController::validatePoint);
        return points;
    }

    private static void validatePoint(@Nullable Point point) {
        if (point == null || !Double.isFinite(point.lat()) || !Double.isFinite(point.lng()) ||
                Math.abs(point.lat()) > 90 || Math.abs(point.lng()) > 180)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid coordinate");
    }

    private static GHPoint toGh(Point point) { return new GHPoint(point.lat(), point.lng()); }

    private static boolean snapped(Point origin, Point destination, com.graphhopper.util.PointList snapped) {
        return snapped.size() >= 1 && (snapped.size() >= 2 || (Double.compare(origin.lat(), destination.lat()) == 0 && Double.compare(origin.lng(), destination.lng()) == 0)) &&
                DistanceCalcEarth.DIST_EARTH.calcDist(origin.lat(), origin.lng(), snapped.getLat(0), snapped.getLon(0)) <= 1000 &&
                DistanceCalcEarth.DIST_EARTH.calcDist(destination.lat(), destination.lng(), snapped.getLat(snapped.size() - 1), snapped.getLon(snapped.size() - 1)) <= 1000;
    }

    /** The loaded GraphHopper's own version: it is part of the routing identity, so an engine upgrade starts every cache cold. */
    static final String ENGINE_VERSION = Required.value(com.graphhopper.util.Constants.VERSION);

    private static String identity(String graphDir, String mapVersion, boolean chEnabled) {
        try {
            Path graph = Path.of(graphDir).toAbsolutePath();
            Path manifest = (Files.exists(graph) ? graph.toRealPath() : graph).getParent().resolve("manifest.json");
            String checksum = Files.isRegularFile(manifest)
                    ? new ObjectMapper().readTree(Files.readString(manifest)).path("mergedSha256").asText("") : "";
            if (checksum.isBlank()) checksum = mapVersion;
            String settings = checksum + "|car|car_access,car_average_speed,road_access,road_environment,max_speed,ferry_speed|car.json|GraphHopper-" + ENGINE_VERSION + "|snap-1000m";
            if (chEnabled) settings += "|CH-car-v1";
            return Required.value(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(settings.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) { throw new IllegalStateException("Cannot identify routing graph", e); }
    }

    private Leg route(Point origin, Point destination) {
        var response = Objects.requireNonNull(hopper, "Road graph not imported").route(new GHRequest(toGh(origin), toGh(destination)).setProfile("car"));
        if (response.hasErrors()) return new Leg(false, null, null);
        var path = response.getBest();
        if (!snapped(origin, destination, Required.value(path.getWaypoints())))
            return new Leg(false, null, null);
        return new Leg(true, Math.max(0, (path.getTime() + 999) / 1000), Math.round(path.getDistance()));
    }

    @GetMapping("/health")
    public Map<String, Object> health() { return Required.value(Map.<String, Object>of("ready", hopper != null, "mapVersion", mapVersion,
            "routingIdentity", routingIdentity, "profile", "car", "engineVersion", ENGINE_VERSION,
            "preparedConfiguration", chEnabled ? "CH-car-v1" : "flexible")); }

    @PreDestroy
    public void close() { workers.shutdownNow(); GraphHopper active = hopper; if (active != null) active.close(); }
}
