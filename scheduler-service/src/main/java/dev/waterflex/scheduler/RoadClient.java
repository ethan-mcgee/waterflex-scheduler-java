package dev.waterflex.scheduler;

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
    public static class RoadUnavailable extends RuntimeException {
        public RoadUnavailable(String message) { super(message); }
    }
    private record Cached(Leg leg, Instant expiresAt) { }
    private static final int MAX_MATRIX_SIDE = 64;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, Cached> memory;
    private final Duration cacheTtl;
    private volatile String routingIdentity = "";

    public RoadClient(JdbcTemplate jdbc, @Value("${routing.url}") String url,
                      @Value("${routing.cache.max-entries:100000}") int maxEntries,
                      @Value("${routing.cache.ttl-minutes:60}") int ttlMinutes) {
        this.jdbc = jdbc;
        this.url = url;
        this.cacheTtl = Duration.ofMinutes(Math.max(1, ttlMinutes));
        int limit = Math.max(1, maxEntries);
        this.memory = Collections.synchronizedMap(new LinkedHashMap<>(limit, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) { return size() > limit; }
        });
    }

    public String currentVersion() { return routingIdentity; }
    public String activeIdentity() { return healthIdentity(); }

    public JsonNode routeGeometry(List<Point> points, String expectedIdentity) {
        String identity = healthIdentity();
        if (!identity.equals(expectedIdentity)) throw new RoadUnavailable("Routing identity changed");
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of("points", points, "expectedRoutingIdentity", identity));
            var request = HttpRequest.newBuilder(URI.create(url + "/internal/route"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) throw new RoadUnavailable("Routing identity changed");
            if (response.statusCode() != 200) throw new RoadUnavailable("Road geometry unavailable: HTTP " + response.statusCode());
            JsonNode data = mapper.readTree(response.body());
            if (!identity.equals(data.path("routingIdentity").asText()) || !data.path("legs").isArray()
                    || data.path("legs").size() != points.size() - 1)
                throw new RoadUnavailable("Malformed road geometry");
            for (JsonNode leg : data.path("legs")) if (!leg.path("seconds").isIntegralNumber()
                    || !leg.path("meters").isIntegralNumber() || !"LineString".equals(leg.path("geometry").path("type").asText())
                    || !leg.path("geometry").path("coordinates").isArray())
                throw new RoadUnavailable("Malformed road geometry");
            return data;
        } catch (RoadUnavailable e) { throw e; }
          catch (Exception e) { throw new RoadUnavailable("Road geometry unavailable: " + e.getClass().getSimpleName()); }
    }

    public Leg leg(Point origin, Point destination) {
        Map<String, Leg> result = matrix(Map.of("origin", origin, "destination", destination));
        Leg leg = result.get("origin>destination");
        if (leg == null) throw new RoadUnavailable("No road route");
        return leg;
    }

    /** Returns directed legs by caller ID; missing entries are proven unreachable. */
    public Map<String, Leg> matrix(Map<String, Point> locations) {
        if (locations.isEmpty()) return Map.of();
        String identity = healthIdentity();
        Map<String, Point> unique = new LinkedHashMap<>();
        Map<String, String> locationKeys = new LinkedHashMap<>();
        for (var entry : locations.entrySet()) {
            String key = key(entry.getValue());
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
                            String from = rs.getString(1), to = rs.getString(2), pair = from + ">" + to;
                            if (!missing.remove(pair)) return;
                            Leg leg = rs.getBoolean(5) ? new Leg(rs.getLong(3), rs.getLong(4)) : null;
                            memory.put(pair + ":" + identity, new Cached(leg, Instant.now().plus(cacheTtl)));
                            if (leg != null) byCoordinate.put(pair, leg);
                        }, args.toArray());
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
                        if (!cell.path("seconds").isIntegralNumber() || !cell.path("meters").isIntegralNumber())
                            throw new RoadUnavailable("Malformed road matrix");
                        long seconds = cell.path("seconds").asLong(), meters = cell.path("meters").asLong();
                        if (seconds < 0 || meters < 0) throw new RoadUnavailable("Malformed road matrix");
                        leg = new Leg(seconds, meters);
                        byCoordinate.put(pair, leg);
                    }
                    memory.put(pair + ":" + identity, new Cached(leg, Instant.now().plus(cacheTtl)));
                    jdbc.update("INSERT INTO road_route_cache (id, \"originKey\", \"destinationKey\", profile, \"mapVersion\", seconds, meters, routable) VALUES (?, ?, ?, 'car', ?, ?, ?, ?) ON CONFLICT (\"originKey\", \"destinationKey\", profile, \"mapVersion\") DO NOTHING",
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
            byte[] body = mapper.writeValueAsBytes(Map.of("origins", origins.stream().map(unique::get).toList(),
                    "destinations", destinations.stream().map(unique::get).toList(), "expectedRoutingIdentity", identity));
            var request = HttpRequest.newBuilder(URI.create(url + "/internal/matrix"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
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
          catch (Exception e) { throw new RoadUnavailable("Road routing unavailable: " + e.getClass().getSimpleName()); }
    }

    private String healthIdentity() {
        try {
            var request = HttpRequest.newBuilder(URI.create(url + "/health")).timeout(Duration.ofSeconds(5)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new RoadUnavailable("Road routing unavailable");
            JsonNode health = mapper.readTree(response.body());
            if (!health.path("ready").asBoolean(false)) throw new RoadUnavailable("Road graph not ready");
            String identity = health.path("routingIdentity").asText("");
            if (identity.isBlank()) throw new RoadUnavailable("Road routing identity unavailable");
            if (!identity.equals(routingIdentity)) { memory.clear(); routingIdentity = identity; }
            return identity;
        } catch (RoadUnavailable e) { throw e; }
          catch (Exception e) { throw new RoadUnavailable("Road routing unavailable: " + e.getClass().getSimpleName()); }
    }

    private static String key(Point point) {
        if (point == null || !Double.isFinite(point.lat()) || !Double.isFinite(point.lng()) ||
                Math.abs(point.lat()) > 90 || Math.abs(point.lng()) > 180) throw new IllegalArgumentException("Invalid coordinate");
        return String.format(Locale.ROOT, "%.5f,%.5f", point.lat(), point.lng());
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
