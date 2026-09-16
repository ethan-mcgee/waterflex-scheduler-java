package dev.waterflex.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoadClient {
    public record Point(double lat, double lng) { }
    public record Leg(long seconds, long meters) { }
    public static class RoadUnavailable extends RuntimeException { public RoadUnavailable(String message) { super(message); } }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, Leg> memory = new ConcurrentHashMap<>();
    private volatile String mapVersion = "";
    public String currentVersion() { return mapVersion; }

    public RoadClient(JdbcTemplate jdbc, @Value("${routing.url}") String url) {
        this.jdbc = jdbc; this.url = url;
    }

    public Leg leg(Point origin, Point destination) {
        String originKey = key(origin), destinationKey = key(destination);
        String lookup = originKey + ">" + destinationKey + ":" + mapVersion;
        Leg hit = memory.get(lookup);
        if (hit != null) return hit;
        if (!mapVersion.isEmpty()) {
            var rows = jdbc.query("SELECT seconds, meters, routable FROM road_route_cache WHERE \"originKey\"=? AND \"destinationKey\"=? AND profile='car' AND \"mapVersion\"=?",
                    (rs, n) -> rs.getBoolean("routable") ? new Leg(rs.getLong("seconds"), rs.getLong("meters")) : null,
                    originKey, destinationKey, mapVersion);
            if (!rows.isEmpty()) {
                if (rows.getFirst() == null) throw new RoadUnavailable("No road route");
                memory.put(lookup, rows.getFirst());
                return rows.getFirst();
            }
        }
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of("origins", new Point[]{origin}, "destinations", new Point[]{destination}));
            var request = HttpRequest.newBuilder(URI.create(url + "/internal/matrix"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new RoadUnavailable("Road routing unavailable");
            JsonNode data = mapper.readTree(response.body());
            String currentVersion = data.path("mapVersion").asText("");
            if (currentVersion.isBlank() || currentVersion.equals("unprepared")) throw new RoadUnavailable("Road map unprepared");
            if (!currentVersion.equals(mapVersion)) { memory.clear(); mapVersion = currentVersion; }
            JsonNode node = data.path("legs").path(0).path(0);
            boolean routable = node.path("routable").asBoolean(false);
            Leg result = routable ? new Leg(node.path("seconds").asLong(), node.path("meters").asLong()) : null;
            jdbc.update("INSERT INTO road_route_cache (id, \"originKey\", \"destinationKey\", profile, \"mapVersion\", seconds, meters, routable) VALUES (?, ?, ?, 'car', ?, ?, ?, ?) ON CONFLICT (\"originKey\", \"destinationKey\", profile, \"mapVersion\") DO NOTHING",
                    UUID.randomUUID().toString(), originKey, destinationKey, currentVersion,
                    result == null ? null : result.seconds(), result == null ? null : result.meters(), routable);
            if (result == null) throw new RoadUnavailable("No road route");
            memory.put(originKey + ">" + destinationKey + ":" + currentVersion, result);
            return result;
        } catch (RoadUnavailable e) { throw e; }
          catch (Exception e) { throw new RoadUnavailable("Road routing unavailable"); }
    }

    private static String key(Point p) { return String.format(java.util.Locale.ROOT, "%.5f,%.5f", p.lat(), p.lng()); }
}
