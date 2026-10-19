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
import java.util.concurrent.TimeUnit;
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
    private final DirectedLegFlights flights;
    /** How long a fetched routing identity is trusted before {@code /health} is asked again. */
    private final long identityTtlNanos;
    /** When the identity was last fetched (System.nanoTime), or 0 when it must be fetched. */
    private volatile long identityFetchedAt;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    /**
     * The routing service's replicas, one or more, given as base URLs separated by {@code |}. They serve one metro
     * with one map, so this client's caches and identity are shared by all of them. Calls rotate over the replicas; one
     * that fails, answers 5xx or refuses the identity (409) is parked for {@link #PARK} and tried only after the others.
     */
    private final List<String> endpoints;
    private final java.util.concurrent.atomic.AtomicInteger nextEndpoint = new java.util.concurrent.atomic.AtomicInteger();
    /** When each parked endpoint may be preferred again (System.nanoTime). */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> parkedUntil = new java.util.concurrent.ConcurrentHashMap<>();
    static final Duration PARK = Required.value(Duration.ofSeconds(5));
    /** Bearer token for routing-service computation endpoints; null only when none is configured (fixture routers). */
    private final @Nullable String authorization;
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

    public RoadClient(JdbcTemplate jdbc, String url, int maxEntries, int ttlMinutes, PlatformTransactionManager transactions) {
        this(jdbc, url, "", maxEntries, ttlMinutes, transactions);
    }

    /** The identity cache and flight limits at their defaults (2 s; 8 batches at once, 64 waiting). */
    public RoadClient(JdbcTemplate jdbc, String url, String token, int maxEntries, int ttlMinutes, PlatformTransactionManager transactions) {
        this(jdbc, url, token, maxEntries, ttlMinutes, transactions, 2000, 8, 64);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RoadClient(JdbcTemplate jdbc, @Value("${routing.url}") String url, @Value("${routing.auth-token:}") String token,
                      @Value("${routing.cache.max-entries:100000}") int maxEntries,
                      @Value("${routing.cache.ttl-minutes:60}") int ttlMinutes, PlatformTransactionManager transactions,
                      @Value("${routing.identity-ttl-ms:2000}") long identityTtlMillis,
                      @Value("${routing.flights.threads:8}") int flightThreads, @Value("${routing.flights.queue:64}") int flightQueue) {
        if (!token.isEmpty() && (token.length() < 32 || token.chars().anyMatch(Character::isWhitespace)))
            throw new IllegalArgumentException("Routing authentication token must contain at least 32 non-whitespace characters");
        if (identityTtlMillis < 0 || identityTtlMillis > 60_000) throw new IllegalArgumentException("routing.identity-ttl-ms must be 0 to 60000");
        this.identityTtlNanos = TimeUnit.MILLISECONDS.toNanos(identityTtlMillis);
        this.flights = new DirectedLegFlights(flightThreads, flightQueue);
        this.jdbc = jdbc;
        List<String> listed = new ArrayList<>();
        for (String endpoint : url.split("\\|", -1)) {
            String trimmed = Required.value(endpoint.strip());
            if (trimmed.isEmpty()) throw new IllegalArgumentException("Empty routing URL in " + url);
            listed.add(trimmed.endsWith("/") ? Required.value(trimmed.substring(0, trimmed.length() - 1)) : trimmed);
        }
        if (new HashSet<>(listed).size() != listed.size()) throw new IllegalArgumentException("A routing URL appears twice in " + url);
        this.endpoints = Required.value(List.copyOf(listed));
        this.authorization = token.isEmpty() ? null : "Bearer " + token;
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

    /** The replicas this client routes through, and how many are not parked now. */
    public int endpoints() { return endpoints.size(); }
    public int availableEndpoints() {
        long now = System.nanoTime();
        return (int) endpoints.stream().filter(endpoint -> !parked(Required.value(endpoint), now)).count();
    }

    private boolean parked(String endpoint, long now) {
        Long until = parkedUntil.get(endpoint);
        return until != null && now - until < 0;
    }

    private void park(String endpoint) {
        if (endpoints.size() > 1) parkedUntil.put(endpoint, System.nanoTime() + PARK.toNanos());
    }

    /** Every endpoint once, in the order to try them: the unparked ones from the next in turn, then the parked ones. */
    List<String> rotation() {
        int count = endpoints.size();
        if (count == 1) return endpoints;
        int start = Math.floorMod(nextEndpoint.getAndIncrement(), count);
        long now = System.nanoTime();
        List<String> ready = new ArrayList<>(count), waiting = new ArrayList<>();
        for (int offset = 0; offset < count; offset++) {
            String endpoint = Required.value(endpoints.get((start + offset) % count));
            (parked(endpoint, now) ? waiting : ready).add(endpoint);
        }
        ready.addAll(waiting);
        return ready;
    }

    /**
     * Sends a computing POST to the first replica that answers it: one that cannot be reached, answers 5xx or refuses
     * the identity (409) is parked and the next is tried. Any other answer than 200 is final. The last failure is
     * thrown when no replica answered.
     */
    private HttpResponse<String> post(String path, byte[] body, Duration timeout, String failure) throws InterruptedException {
        @Nullable RoadUnavailable last = null;
        for (String listed : rotation()) {
            String endpoint = Required.value(listed);
            var request = authorized(Required.value(HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(SearchDeadline.networkTimeout(timeout))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)))).build();
            HttpResponse<String> response;
            try { response = Required.value(http.send(request, HttpResponse.BodyHandlers.ofString())); }
            catch (java.io.IOException unreachable) { park(endpoint); last = requestFailure(unreachable, failure); continue; }
            SearchDeadline.checkpoint();
            if (response.statusCode() == 409) { identityFetchedAt = 0; park(endpoint); last = new RoadUnavailable("Routing identity changed"); continue; }
            if (response.statusCode() >= 500) { park(endpoint); last = new RoadUnavailable(failure + ": HTTP " + response.statusCode()); continue; }
            if (response.statusCode() != 200) throw new RoadUnavailable(failure + ": HTTP " + response.statusCode());
            return response;
        }
        throw Required.value(last, "routing failure");
    }
    public String activeIdentity() {
        long started = System.nanoTime();
        try { return healthIdentity(); }
        finally { routingWaited(started); }
    }

    /** Attributes the wall time since {@code started} to the current call's routing wait, when one is being measured. */
    private static void routingWaited(long started) {
        SearchDeadline deadline = SearchDeadline.current();
        if (deadline != null) deadline.telemetry().routingWait(System.nanoTime() - started);
    }

    public JsonNode routeGeometry(List<RoadPoint> points, String expectedIdentity) {
        long started = System.nanoTime();
        try { return geometry(points, expectedIdentity); }
        finally { routingWaited(started); }
    }

    private JsonNode geometry(List<RoadPoint> points, String expectedIdentity) {
        String identity = healthIdentity();
        if (!identity.equals(expectedIdentity)) throw new RoadUnavailable("Routing identity changed");
        try {
            byte[] body = Required.value(mapper.writeValueAsBytes(Map.of("points", points, "expectedRoutingIdentity", identity)));
            geometryRequests.incrementAndGet();
            var response = post("/internal/route", body, Required.value(Duration.ofSeconds(10)), "Road geometry unavailable");
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
        } catch (RoadUnavailable | SearchAdmission.Busy | SearchDeadline.Expired e) { throw e; }
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
        long started = System.nanoTime();
        try { return resolve(pairs, identity); }
        finally { routingWaited(started); }
    }

    private Map<String, Leg> resolve(List<Pair> pairs, String identity) {
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
                byte[] body = Required.value(mapper.writeValueAsBytes(Map.of("pairs", requested, "expectedRoutingIdentity", identity)));
                legRequests.incrementAndGet(); requestedPairs.addAndGet(requested.size());
                var response = post("/internal/legs", body, Required.value(Duration.ofSeconds(10)), "Sparse routing unavailable");
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
            } catch (RoadUnavailable | SearchAdmission.Busy | SearchDeadline.Expired e) { throw e; }
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

    /**
     * The routing identity: the one fetched within {@code routing.identity-ttl-ms}, else fetched now from {@code /health}.
     * The routing service checks the identity on every computing call and answers 409 when it changed, which clears
     * this cache, so a stale identity costs one refused call and never a wrong leg.
     */
    private String healthIdentity() {
        long fetched = identityFetchedAt;
        String known = routingIdentity;
        if (fetched != 0 && !known.isBlank() && System.nanoTime() - fetched < identityTtlNanos) return known;
        String identity = fetchIdentity();
        identityFetchedAt = System.nanoTime();
        return identity;
    }

    /**
     * The identity the first ready replica reports, in rotation order. A replica on another identity (a map switch
     * rolled one replica at a time) answers its computing calls with 409 and is parked until it agrees.
     */
    private String fetchIdentity() {
        @Nullable RoadUnavailable last = null;
        for (String listed : rotation()) {
            String endpoint = Required.value(listed);
            try { return fetchIdentity(endpoint); }
            catch (RoadUnavailable down) { park(endpoint); last = down; }
        }
        throw Required.value(last, "routing failure");
    }

    private String fetchIdentity(String endpoint) {
        try {
            var request = HttpRequest.newBuilder(URI.create(endpoint + "/health")).timeout(SearchDeadline.networkTimeout(Required.value(Duration.ofSeconds(5)))).GET().build();
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
        } catch (RoadUnavailable | SearchAdmission.Busy | SearchDeadline.Expired e) { throw e; }
          catch (org.springframework.dao.DataAccessException e) { throw e; }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); SearchDeadline.checkpoint(); throw new RoadUnavailable("Road request interrupted"); }
          catch (Exception e) { throw requestFailure(e, "Road routing unavailable"); }
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder request) {
        String value = authorization;
        return value == null ? request : Required.value(request.header("Authorization", value));
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
        try {
            cacheTransactions.executeWithoutResult(_ -> {
                dev.waterflex.scheduler.DatabaseDeadline.apply(jdbc);
                operation.run();
                SearchDeadline.checkpoint();
            });
        } catch (org.springframework.transaction.TransactionException | org.springframework.dao.DataAccessException failure) {
            throw DeadlineSignals.unwrap(failure);
        }
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
