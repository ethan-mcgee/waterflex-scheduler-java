package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class RoadClient {
    public record Leg(long seconds, long meters) { }
    public record Pair(String id, RoadPoint origin, RoadPoint destination) { }
    public static class RoadUnavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public RoadUnavailable(String message) { super(message); }
    }
    private record Cached(@Nullable Leg leg, Instant expiresAt) { }
    private final DirectedLegFlights flights = new DirectedLegFlights();
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;
    private final HttpClient http = Required.value(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    private final Map<String, Cached> memory;
    private final Duration cacheTtl;
    private final TransactionTemplate cacheTransactions;
    private volatile String routingIdentity = "";
    private final java.util.concurrent.atomic.AtomicLong legRequests = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong requestedPairs = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong identityRequests = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong geometryRequests = new java.util.concurrent.atomic.AtomicLong();
    public record HttpMeasurements(long legRequests, long requestedPairs, long identityRequests, long geometryRequests) { }
    public HttpMeasurements httpMeasurements() {
        return new HttpMeasurements(legRequests.get(), requestedPairs.get(), identityRequests.get(), geometryRequests.get());
    }

    public RoadClient(JdbcTemplate jdbc, @Value("${routing.url}") String url,
                      @Value("${routing.cache.max-entries:100000}") int maxEntries,
                      @Value("${routing.cache.ttl-minutes:60}") int ttlMinutes, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.url = url;
        this.cacheTtl = Required.value(Duration.ofMinutes(Math.max(1, ttlMinutes)));
        cacheTransactions = new TransactionTemplate(transactions);
        cacheTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cacheTransactions.setTimeout(5);
        int limit = Math.max(1, maxEntries);
        this.memory = Required.value(Collections.synchronizedMap(new LinkedHashMap<>(limit, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.@Nullable Entry<String, Cached> eldest) { return size() > limit; }
        }));
    }

    @jakarta.annotation.PreDestroy
    public void closeRoutingWork() { flights.close(); }

    public String currentVersion() { return routingIdentity; }
    public String activeIdentity() { return healthIdentity(); }
    void clearMemoryForIsolatedBenchmark() { memory.clear(); }

    public JsonNode routeGeometry(List<RoadPoint> points, String expectedIdentity) {
        String identity = healthIdentity();
        if (!identity.equals(expectedIdentity)) throw new RoadUnavailable("Routing identity changed");
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of("points", points, "expectedRoutingIdentity", identity));
            var request = HttpRequest.newBuilder(URI.create(url + "/internal/route"))
                    .timeout(SearchDeadline.networkTimeout(Required.value(Duration.ofSeconds(10)))).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            geometryRequests.incrementAndGet();
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
          catch (Exception e) { throw requestFailure(e, "Road geometry unavailable"); }
    }

    public Leg leg(RoadPoint origin, RoadPoint destination) {
        Map<String, Leg> result = matrix(Required.value(Map.<String, RoadPoint>of("origin", origin, "destination", destination)));
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
        SearchDeadline deadline = SearchDeadline.current();
        if (deadline != null) deadline.telemetry().routing(unique.size(), found.size(), 0, 0);
        for (int offset = 0; offset < missing.size(); offset += 256) {
            List<Pair> batch = missing.subList(offset, Math.min(offset + 256, missing.size()));
            List<Object> arguments = new ArrayList<>();
            arguments.add(identity);
            for (Pair pair : batch) { arguments.add(key(pair.origin())); arguments.add(key(pair.destination())); }
            String values = String.join(",", Collections.nCopies(batch.size(), "(?,?)"));
            cache(() -> jdbc.query("SELECT \"originKey\",\"destinationKey\",seconds,meters,routable FROM road_route_cache WHERE \"mapVersion\"=? AND profile='car' AND (\"originKey\",\"destinationKey\") IN (" + values + ") AND \"fetchedAt\">CURRENT_TIMESTAMP-INTERVAL '30 days'",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        String id = dev.waterflex.scheduler.DatabaseFacts.string(rs, 1) + ">" + dev.waterflex.scheduler.DatabaseFacts.string(rs, 2);
                        boolean routable = dev.waterflex.scheduler.DatabaseFacts.bool(rs, 5);
                        Long seconds = dev.waterflex.scheduler.DatabaseFacts.nullableLong(rs, 3), meters = dev.waterflex.scheduler.DatabaseFacts.nullableLong(rs, 4);
                        if (routable && (seconds == null || meters == null || seconds < 0 || meters < 0)) {
                            org.slf4j.LoggerFactory.getLogger(RoadClient.class).warn("Discarding malformed cached directed leg for routing identity {}", identity);
                            return;
                        }
                        Cached cached = new Cached(routable ? new Leg(Required.value(seconds), Required.value(meters)) : null,
                                Required.value(Instant.now().plus(cacheTtl)));
                        found.put(id, cached); memory.put(id + ":" + identity, cached);
                    }, Required.value(arguments.toArray(new @Nullable Object[0]))));
            List<Pair> requested = batch.stream().filter(pair -> !found.containsKey(pair.id())).toList();
            if (deadline != null) deadline.telemetry().routing(0, 0, batch.size() - requested.size(), requested.size());
            if (requested.isEmpty()) continue;
            Map<String, Leg> shared = flights.resolve(Required.value(requested), identity, batchPairs -> requestSparse(Required.value(batchPairs), identity));
            for (Pair pair : requested) found.put(pair.id(), new Cached(shared.get(pair.id()), Required.value(Instant.now().plus(cacheTtl))));
        }
        Map<String, Leg> result = new LinkedHashMap<>();
        ids.forEach((id, coordinatePair) -> {
            Leg leg = Required.value(found.get(coordinatePair), "resolved directed pair").leg();
            if (leg != null) result.put(id, leg);
        });
        return Required.value(Map.copyOf(result));
    }

    private Map<String, Leg> requestSparse(List<Pair> pairs, String identity) {
        Map<String, Cached> found = new HashMap<>();
        Map<String, Pair> unique = new LinkedHashMap<>();
        List<Pair> requested = new ArrayList<>();
        for (Pair pair : pairs) {
            unique.put(pair.id(), pair);
            Cached cached = memory.get(pair.id() + ":" + identity);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) found.put(pair.id(), cached);
            else requested.add(pair);
        }
        if (!requested.isEmpty()) {
            try {
                byte[] body = mapper.writeValueAsBytes(Map.of("pairs", requested, "expectedRoutingIdentity", identity));
                var request = HttpRequest.newBuilder(URI.create(url + "/internal/legs")).timeout(SearchDeadline.networkTimeout(Required.value(Duration.ofSeconds(10))))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                legRequests.incrementAndGet(); requestedPairs.addAndGet(requested.size());
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
                cache(() -> jdbc.batchUpdate("INSERT INTO road_route_cache (id,\"originKey\",\"destinationKey\",profile,\"mapVersion\",seconds,meters,routable) VALUES (?,?,?,'car',?,?,?,?) ON CONFLICT (\"originKey\",\"destinationKey\",profile,\"mapVersion\") DO UPDATE SET seconds=EXCLUDED.seconds,meters=EXCLUDED.meters,routable=EXCLUDED.routable,\"fetchedAt\"=CURRENT_TIMESTAMP", writes));
                for (Pair pair : requested) memory.put(pair.id() + ":" + identity, Required.value(found.get(pair.id()), "resolved road pair"));
            } catch (RoadUnavailable e) { throw e; }
              catch (org.springframework.dao.DataAccessException e) { throw e; }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); SearchDeadline.checkpoint(); throw new RoadUnavailable("Road request interrupted"); }
          catch (Exception e) { throw requestFailure(e, "Sparse routing unavailable"); }
        }
        Map<String, Leg> result = new LinkedHashMap<>();
        for (Pair pair : pairs) {
            Leg leg = Required.value(found.get(pair.id()), "resolved shared directed leg").leg();
            if (leg != null) result.put(pair.id(), leg);
        }
        return Required.value(Map.copyOf(result));
    }

    /** Returns directed legs by caller ID; missing entries are proven unreachable. */
    public Map<String, Leg> matrix(Map<String, RoadPoint> locations) {
        if (locations.isEmpty()) return Required.value(Map.of());
        return matrix(locations, healthIdentity());
    }

    public Map<String, Leg> matrix(Map<String, RoadPoint> locations, String identity) {
        SearchDeadline.checkpoint();
        List<Pair> pairs = new ArrayList<>();
        for (var from : locations.entrySet()) for (var to : locations.entrySet())
            pairs.add(new Pair(from.getKey() + ">" + to.getKey(), Required.value(from.getValue()), Required.value(to.getValue())));
        return sparse(pairs, identity);
    }

    private String healthIdentity() {
        try {
            var request = HttpRequest.newBuilder(URI.create(url + "/health")).timeout(SearchDeadline.networkTimeout(Required.value(Duration.ofSeconds(5)))).GET().build();
            identityRequests.incrementAndGet();
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
          catch (Exception e) { throw requestFailure(e, "Road routing unavailable"); }
    }

    private static RoadUnavailable requestFailure(Exception failure, String message) {
        // HTTP timer rounding can report a request timeout just before the monotonic checkpoint.
        // Booking request timeouts are always bounded by its shorter exploration/commit budget.
        if (SearchDeadline.current() != null && failure instanceof java.net.http.HttpTimeoutException
                && !(failure instanceof java.net.http.HttpConnectTimeoutException)) throw new SearchDeadline.Expired();
        SearchDeadline.checkpoint();
        return new RoadUnavailable(message + ": " + failure.getClass().getSimpleName());
    }

    private static String key(@Nullable RoadPoint point) {
        if (point == null || !Double.isFinite(point.lat()) || !Double.isFinite(point.lng()) ||
                Math.abs(point.lat()) > 90 || Math.abs(point.lng()) > 180) throw new IllegalArgumentException("Invalid coordinate");
        return Required.value(String.format(Locale.ROOT, "%.5f,%.5f", point.lat(), point.lng()));
    }
    private static RoadPoint normalized(String key) {
        String[] parts = key.split(",");
        return new RoadPoint(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
    }

    private void cache(Runnable operation) {
        cacheTransactions.executeWithoutResult(_ -> {
            dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc);
            operation.run();
            SearchDeadline.checkpoint();
        });
    }

    @Scheduled(cron = "${routing.cache.cleanup-cron:0 30 3 * * SUN}", zone = "America/Chicago")
    public void cleanPersistentCache() {
        jdbc.update("DELETE FROM road_route_cache WHERE \"fetchedAt\" < CURRENT_TIMESTAMP - INTERVAL '30 days'");
        trimPersistentCache(500_000, 50_000);
    }

    /** Deletes the oldest rows in bounded batches until the table is within the cap; one batch alone could fall behind growth. */
    int trimPersistentCache(int cap, int batch) {
        if (cap < 0 || batch < 1) throw new IllegalArgumentException("Invalid route cache trim bounds");
        int removed = 0;
        while (true) {
            int deleted = jdbc.update("DELETE FROM road_route_cache WHERE id IN (SELECT id FROM road_route_cache ORDER BY \"fetchedAt\" DESC, id DESC OFFSET ? LIMIT ?)", cap, batch);
            removed += deleted;
            if (deleted < batch) return removed;
        }
    }
}
