package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class RoadClient {
    public record Point(double lat, double lng) { }
    public record Leg(long seconds, long meters) { }
    public record Pair(String id, Point origin, Point destination) { }
    public static class RoadUnavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public RoadUnavailable(String message) { super(message); }
    }
    private record Cached(@Nullable Leg leg, Instant expiresAt) { }
    private static final int MAX_MATRIX_SIDE = 64;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;
    private final HttpClient http = Required.value(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    private final Map<String, Cached> memory;
    private final Duration cacheTtl;
    private volatile String routingIdentity = "";

    public RoadClient(JdbcTemplate jdbc, @Value("${routing.url}") String url,
                      @Value("${routing.cache.max-entries:100000}") int maxEntries,
                      @Value("${routing.cache.ttl-minutes:60}") int ttlMinutes) {
        this.jdbc = jdbc;
        this.url = url;
        this.cacheTtl = Required.value(Duration.ofMinutes(Math.max(1, ttlMinutes)));
        int limit = Math.max(1, maxEntries);
        this.memory = Required.value(Collections.synchronizedMap(new LinkedHashMap<>(limit, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.@Nullable Entry<String, Cached> eldest) { return size() > limit; }
        }));
    }

    public String currentVersion() { return routingIdentity; }
    public String activeIdentity() { return healthIdentity(); }

    public JsonNode routeGeometry(List<Point> points, String expectedIdentity) {
        String identity = healthIdentity();
        if (!identity.equals(expectedIdentity)) throw new RoadUnavailable("Routing identity changed");
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of("points", points, "expectedRoutingIdentity", identity));
            var request = HttpRequest.newBuilder(URI.create(url + "/internal/route"))
                    .timeout(SearchDeadline.networkTimeout(Required.value(Duration.ofSeconds(10)))).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            SearchDeadline.checkpoint();
            if (response.statusCode() == 409) throw new RoadUnavailable("Routing identity changed");
            if (response.statusCode() != 200) throw new RoadUnavailable("Road geometry unavailable: HTTP " + response.statusCode());
            JsonNode data = mapper.readTree(response.body());
            if (!identity.equals(data.path("routingIdentity").asText()) || !data.path("legs").isArray()
                    || data.path("legs").size() != points.size() - 1)
                throw new RoadUnavailable("Malformed road geometry");
            for (JsonNode leg : data.path("legs")) if (!leg.path("seconds").isIntegralNumber()
                    || !leg.path("seconds").canConvertToLong() || leg.path("seconds").asLong() < 0
                    || !leg.path("meters").isIntegralNumber() || !leg.path("meters").canConvertToLong() || leg.path("meters").asLong() < 0 || !"LineString".equals(leg.path("geometry").path("type").asText())
                    || !leg.path("geometry").path("coordinates").isArray())
                throw new RoadUnavailable("Malformed road geometry");
            return data;
        } catch (RoadUnavailable e) { throw e; }
          catch (org.springframework.dao.DataAccessException e) { throw e; }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); SearchDeadline.checkpoint(); throw new RoadUnavailable("Road request interrupted"); }
          catch (Exception e) { SearchDeadline.checkpoint(); throw new RoadUnavailable("Road geometry unavailable: " + e.getClass().getSimpleName()); }
    }

    public Leg leg(Point origin, Point destination) {
        Map<String, Leg> result = matrix(Required.value(Map.<String, Point>of("origin", origin, "destination", destination)));
        Leg leg = result.get("origin>destination");
        if (leg == null) throw new RoadUnavailable("No road route");
        return leg;
    }

    /** Directed pairs only. Absence in the result means an explicitly unroutable pair. */
    public Map<String, Leg> sparse(List<Pair> pairs, String identity) {
        if (identity.isBlank()) throw new RoadUnavailable("Routing identity required");
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, Pair> unique = new LinkedHashMap<>();
        for (Pair pair : pairs) {
            if (pair.id().isBlank() || ids.containsKey(pair.id())) throw new IllegalArgumentException("Duplicate or missing pair ID");
            String origin = key(pair.origin()), destination = key(pair.destination()), coordinatePair = origin + ">" + destination;
            ids.put(pair.id(), coordinatePair);
            unique.putIfAbsent(coordinatePair, new Pair(coordinatePair, normalized(origin), normalized(destination)));
        }
        Map<String, Cached> found = new HashMap<>();
        List<Pair> missing = new ArrayList<>();
        for (Pair pair : unique.values()) {
            Cached cached = memory.get(pair.id() + ":" + identity);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) found.put(pair.id(), cached);
            else missing.add(pair);
        }
        for (int offset = 0; offset < missing.size(); offset += 256) {
            SearchDeadline.database(jdbc);
            List<Pair> batch = missing.subList(offset, Math.min(offset + 256, missing.size()));
            List<Object> arguments = new ArrayList<>();
            arguments.add(identity);
            for (Pair pair : batch) { arguments.add(key(pair.origin())); arguments.add(key(pair.destination())); }
            String values = String.join(",", Collections.nCopies(batch.size(), "(?,?)"));
            jdbc.query("SELECT \"originKey\",\"destinationKey\",seconds,meters,routable FROM road_route_cache WHERE \"mapVersion\"=? AND profile='car' AND (\"originKey\",\"destinationKey\") IN (" + values + ") AND \"fetchedAt\">CURRENT_TIMESTAMP-INTERVAL '30 days'",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        String id = Required.string(rs, 1) + ">" + Required.string(rs, 2);
                        boolean routable = Required.bool(rs, 5);
                        Long seconds = Required.nullableLong(rs, 3), meters = Required.nullableLong(rs, 4);
                        if (routable && (seconds == null || meters == null || seconds < 0 || meters < 0))
                            throw new RoadUnavailable("Malformed persisted road leg");
                        Cached cached = new Cached(routable ? new Leg(Required.value(seconds), Required.value(meters)) : null,
                                Required.value(Instant.now().plus(cacheTtl)));
                        found.put(id, cached); memory.put(id + ":" + identity, cached);
                    }, Required.value(arguments.toArray(new @Nullable Object[0])));
            List<Pair> requested = batch.stream().filter(pair -> !found.containsKey(pair.id())).toList();
            if (requested.isEmpty()) continue;
            try {
                byte[] body = mapper.writeValueAsBytes(Map.of("pairs", requested, "expectedRoutingIdentity", identity));
                var request = HttpRequest.newBuilder(URI.create(url + "/internal/legs")).timeout(SearchDeadline.networkTimeout(Required.value(Duration.ofSeconds(10))))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            SearchDeadline.checkpoint();
                if (response.statusCode() != 200) throw new RoadUnavailable("Sparse routing unavailable: HTTP " + response.statusCode());
                JsonNode data = mapper.readTree(response.body());
                if (!identity.equals(data.path("routingIdentity").asText()) || !data.path("pairs").isArray()
                        || data.path("pairs").size() != requested.size()) throw new RoadUnavailable("Malformed sparse routing response or changed identity");
                Set<String> expected = new HashSet<>();
                requested.forEach(pair -> expected.add(pair.id()));
                List<@Nullable Object[]> writes = new ArrayList<>();
                for (JsonNode item : data.path("pairs")) {
                    if (!item.path("id").isTextual() || !expected.remove(item.path("id").asText())) throw new RoadUnavailable("Unexpected sparse pair ID");
                    String id = Required.value(item.path("id").asText());
                    JsonNode leg = item.path("leg");
                    if (!leg.path("routable").isBoolean()) throw new RoadUnavailable("Missing pair routability");
                    Leg value = null;
                    if (leg.path("routable").asBoolean()) {
                        if (!leg.path("seconds").isIntegralNumber() || !leg.path("seconds").canConvertToLong() || leg.path("seconds").asLong() < 0
                                || !leg.path("meters").isIntegralNumber() || !leg.path("meters").canConvertToLong() || leg.path("meters").asLong() < 0)
                            throw new RoadUnavailable("Malformed sparse road leg");
                        value = new Leg(leg.path("seconds").asLong(), leg.path("meters").asLong());
                    }
                    Pair pair = Required.value(unique.get(id), "requested road pair");
                    Cached cached = new Cached(value, Required.value(Instant.now().plus(cacheTtl)));
                    found.put(id, cached);
                    writes.add(new @Nullable Object[]{UUID.randomUUID().toString(), key(pair.origin()), key(pair.destination()), identity,
                            value == null ? null : value.seconds(), value == null ? null : value.meters(), value != null});
                }
                jdbc.batchUpdate("INSERT INTO road_route_cache (id,\"originKey\",\"destinationKey\",profile,\"mapVersion\",seconds,meters,routable) VALUES (?,?,?,'car',?,?,?,?) ON CONFLICT (\"originKey\",\"destinationKey\",profile,\"mapVersion\") DO UPDATE SET seconds=EXCLUDED.seconds,meters=EXCLUDED.meters,routable=EXCLUDED.routable,\"fetchedAt\"=CURRENT_TIMESTAMP", writes);
                for (Pair pair : requested) memory.put(pair.id() + ":" + identity, Required.value(found.get(pair.id()), "resolved road pair"));
            } catch (RoadUnavailable e) { throw e; }
              catch (org.springframework.dao.DataAccessException e) { throw e; }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); SearchDeadline.checkpoint(); throw new RoadUnavailable("Road request interrupted"); }
          catch (Exception e) { SearchDeadline.checkpoint(); throw new RoadUnavailable("Sparse routing unavailable: " + e.getClass().getSimpleName()); }
        }
        Map<String, Leg> result = new LinkedHashMap<>();
        ids.forEach((id, coordinatePair) -> {
            Leg leg = Required.value(found.get(coordinatePair), "resolved directed pair").leg();
            if (leg != null) result.put(id, leg);
        });
        return Required.value(Map.copyOf(result));
    }

    /** Returns directed legs by caller ID; missing entries are proven unreachable. */
    public Map<String, Leg> matrix(Map<String, Point> locations) {
        if (locations.isEmpty()) return Required.value(Map.of());
        return matrix(locations, healthIdentity());
    }

    public Map<String, Leg> matrix(Map<String, Point> locations, String identity) {
        SearchDeadline.database(jdbc);
        if (identity.isBlank()) throw new RoadUnavailable("Routing identity required");
        Map<String, Point> unique = new LinkedHashMap<>();
        Map<String, String> locationKeys = new LinkedHashMap<>();
        for (var entry : locations.entrySet()) {
            String key = key(Required.value(entry.getValue()));
            locationKeys.put(entry.getKey(), key);
            unique.putIfAbsent(key, normalized(key));
        }
        List<String> keys = new ArrayList<>(unique.keySet());
        Map<String, Leg> byCoordinate = new HashMap<>();
        Set<String> missing = new HashSet<>();
        for (String from : keys) for (String to : keys) {
            String pair = from + ">" + to;
            Cached hit = memory.get(pair + ":" + identity);
            if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
                if (hit.leg() != null) byCoordinate.put(pair, hit.leg());
                continue;
            }
            missing.add(pair);
        }
        for (int originStart = 0; originStart < keys.size(); originStart += MAX_MATRIX_SIDE) {
            List<String> origins = keys.subList(originStart, Math.min(keys.size(), originStart + MAX_MATRIX_SIDE));
            for (int destinationStart = 0; destinationStart < keys.size(); destinationStart += MAX_MATRIX_SIDE) {
                List<String> destinations = keys.subList(destinationStart, Math.min(keys.size(), destinationStart + MAX_MATRIX_SIDE));
                boolean needed = origins.stream().anyMatch(from -> destinations.stream().anyMatch(to -> missing.contains(from + ">" + to)));
                if (!needed) continue;
                String originSlots = String.join(",", Collections.nCopies(origins.size(), "?"));
                String destinationSlots = String.join(",", Collections.nCopies(destinations.size(), "?"));
                List<Object> args = new ArrayList<>();
                args.add(identity); args.addAll(origins); args.addAll(destinations);
                jdbc.query("SELECT \"originKey\", \"destinationKey\", seconds, meters, routable FROM road_route_cache WHERE \"mapVersion\"=? AND profile='car' AND \"originKey\" IN (" + originSlots + ") AND \"destinationKey\" IN (" + destinationSlots + ")",
                        (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                            String from = Required.string(rs, 1), to = Required.string(rs, 2), pair = from + ">" + to;
                            if (!missing.contains(pair)) return;
                            boolean routable = Required.bool(rs, 5);
                            Long seconds = Required.nullableLong(rs, 3), meters = Required.nullableLong(rs, 4);
                            if (routable && (seconds == null || meters == null || seconds < 0 || meters < 0)) return;
                            Leg leg = routable ? new Leg(Required.value(seconds), Required.value(meters)) : null;
                            missing.remove(pair);
                            memory.put(pair + ":" + identity, new Cached(leg, Required.value(Instant.now().plus(cacheTtl))));
                            if (leg != null) byCoordinate.put(pair, leg);
                        }, Required.value(args.toArray(new @Nullable Object[0])));
                needed = origins.stream().anyMatch(from -> destinations.stream().anyMatch(to -> missing.contains(from + ">" + to)));
                if (!needed) continue;
                JsonNode rows = requestMatrix(origins, destinations, unique, identity);
                for (int i = 0; i < origins.size(); i++) for (int j = 0; j < destinations.size(); j++) {
                    String from = origins.get(i), to = destinations.get(j), pair = from + ">" + to;
                    if (!missing.contains(pair)) continue;
                    JsonNode cell = rows.get(i).get(j);
                    if (cell == null || !cell.path("routable").isBoolean()) throw new RoadUnavailable("Malformed road matrix");
                    Leg leg = null;
                    if (cell.path("routable").asBoolean()) {
                        if (!cell.path("seconds").isIntegralNumber() || !cell.path("seconds").canConvertToLong() || !cell.path("meters").isIntegralNumber() || !cell.path("meters").canConvertToLong())
                            throw new RoadUnavailable("Malformed road matrix");
                        long seconds = cell.path("seconds").asLong(), meters = cell.path("meters").asLong();
                        if (seconds < 0 || meters < 0) throw new RoadUnavailable("Malformed road matrix");
                        leg = new Leg(seconds, meters);
                        byCoordinate.put(pair, leg);
                    }
                    memory.put(pair + ":" + identity, new Cached(leg, Required.value(Instant.now().plus(cacheTtl))));
                    jdbc.update("INSERT INTO road_route_cache (id, \"originKey\", \"destinationKey\", profile, \"mapVersion\", seconds, meters, routable) VALUES (?, ?, ?, 'car', ?, ?, ?, ?) ON CONFLICT (\"originKey\", \"destinationKey\", profile, \"mapVersion\") DO UPDATE SET seconds=EXCLUDED.seconds, meters=EXCLUDED.meters, routable=EXCLUDED.routable, \"fetchedAt\"=CURRENT_TIMESTAMP",
                            UUID.randomUUID().toString(), from, to, identity,
                            leg == null ? null : leg.seconds(), leg == null ? null : leg.meters(), leg != null);
                }
            }
        }
        Map<String, Leg> result = new HashMap<>();
        for (var from : locationKeys.entrySet()) for (var to : locationKeys.entrySet()) {
            Leg leg = byCoordinate.get(from.getValue() + ">" + to.getValue());
            if (leg != null) result.put(from.getKey() + ">" + to.getKey(), leg);
        }
        return result;
    }

    private JsonNode requestMatrix(List<String> origins, List<String> destinations, Map<String, Point> unique, String identity) {
        try {
            Map<String, Object> matrixRequest = new LinkedHashMap<>();
            matrixRequest.put("origins", origins.stream().map(id -> Required.value(unique.get(id), "origin point")).toList());
            matrixRequest.put("destinations", destinations.stream().map(id -> Required.value(unique.get(id), "destination point")).toList());
            matrixRequest.put("expectedRoutingIdentity", identity);
            byte[] body = mapper.writeValueAsBytes(matrixRequest);
            var request = HttpRequest.newBuilder(URI.create(url + "/internal/matrix"))
                    .timeout(SearchDeadline.networkTimeout(Required.value(Duration.ofSeconds(10)))).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            SearchDeadline.checkpoint();
            if (response.statusCode() == 409) throw new RoadUnavailable("Routing identity changed");
            if (response.statusCode() != 200) throw new RoadUnavailable("Road routing unavailable: HTTP " + response.statusCode());
            JsonNode data = mapper.readTree(response.body());
            if (!identity.equals(data.path("routingIdentity").asText())) throw new RoadUnavailable("Routing identity changed");
            JsonNode rows = data.path("legs");
            if (!rows.isArray() || rows.size() != origins.size()) throw new RoadUnavailable("Malformed road matrix");
            for (JsonNode row : rows) if (!row.isArray() || row.size() != destinations.size())
                throw new RoadUnavailable("Malformed road matrix");
            return rows;
        } catch (RoadUnavailable e) { throw e; }
          catch (org.springframework.dao.DataAccessException e) { throw e; }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); SearchDeadline.checkpoint(); throw new RoadUnavailable("Road request interrupted"); }
          catch (Exception e) { SearchDeadline.checkpoint(); throw new RoadUnavailable("Road routing unavailable: " + e.getClass().getSimpleName()); }
    }

    private String healthIdentity() {
        try {
            var request = HttpRequest.newBuilder(URI.create(url + "/health")).timeout(SearchDeadline.networkTimeout(Required.value(Duration.ofSeconds(5)))).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            SearchDeadline.checkpoint();
            if (response.statusCode() != 200) throw new RoadUnavailable("Road routing unavailable");
            JsonNode health = mapper.readTree(response.body());
            if (!health.path("ready").isBoolean() || !health.path("ready").asBoolean()) throw new RoadUnavailable("Road graph not ready");
            if (!health.path("routingIdentity").isTextual()) throw new RoadUnavailable("Malformed road routing identity");
            String identity = health.path("routingIdentity").asText("");
            if (identity.isBlank()) throw new RoadUnavailable("Road routing identity unavailable");
            if (!identity.equals(routingIdentity)) { memory.clear(); routingIdentity = identity; }
            return identity;
        } catch (RoadUnavailable e) { throw e; }
          catch (org.springframework.dao.DataAccessException e) { throw e; }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); SearchDeadline.checkpoint(); throw new RoadUnavailable("Road request interrupted"); }
          catch (Exception e) { SearchDeadline.checkpoint(); throw new RoadUnavailable("Road routing unavailable: " + e.getClass().getSimpleName()); }
    }

    private static String key(@Nullable Point point) {
        if (point == null || !Double.isFinite(point.lat()) || !Double.isFinite(point.lng()) ||
                Math.abs(point.lat()) > 90 || Math.abs(point.lng()) > 180) throw new IllegalArgumentException("Invalid coordinate");
        return Required.value(String.format(Locale.ROOT, "%.5f,%.5f", point.lat(), point.lng()));
    }
    private static Point normalized(String key) {
        String[] parts = key.split(",");
        return new Point(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
    }

    @Scheduled(cron = "0 30 3 * * SUN", zone = "America/Chicago")
    public void cleanPersistentCache() {
        jdbc.update("DELETE FROM road_route_cache WHERE \"fetchedAt\" < CURRENT_TIMESTAMP - INTERVAL '30 days'");
        jdbc.update("DELETE FROM road_route_cache WHERE id IN (SELECT id FROM road_route_cache ORDER BY \"fetchedAt\" DESC, id DESC OFFSET 500000 LIMIT 50000)");
    }
}
