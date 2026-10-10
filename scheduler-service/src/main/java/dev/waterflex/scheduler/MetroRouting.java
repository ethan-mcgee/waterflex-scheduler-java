package dev.waterflex.scheduler;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * One routing service per metro for the public API. Each metro's client keeps its own in-memory cache and routing
 * identity; all share the road_route_cache table, which is keyed by routing identity. A metro that is not configured
 * has no routing: the public API never falls back to the single {@code routing.url} client, which only runs the road
 * cache cleanup.
 */
@Component
public class MetroRouting {
    /** The request names a metro this scheduler has no routing service for. */
    public static final class UnknownMetro extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public UnknownMetro(String metroId) { super("No routing service is configured for metro " + metroId); }
    }

    private final Map<String, RoadClient> clients;

    @Autowired
    public MetroRouting(JdbcTemplate jdbc, @Value("${routing.metro-urls:}") String metroUrls, @Value("${routing.auth-token:}") String token,
                        @Value("${routing.cache.max-entries:100000}") int maxEntries, @Value("${routing.cache.ttl-minutes:60}") int ttlMinutes,
                        PlatformTransactionManager transactions) {
        this(parse(metroUrls), url -> new RoadClient(jdbc, Required.value(url), token, maxEntries, ttlMinutes, transactions));
    }

    /** Builds one client per metro with {@code client}; tests supply fakes here. */
    public MetroRouting(Map<String, String> urls, Function<String, RoadClient> client) {
        Map<String, RoadClient> clients = new LinkedHashMap<>();
        urls.forEach((metro, url) -> clients.put(Required.value(metro), Required.value(client.apply(Required.value(url)))));
        this.clients = Required.value(Map.copyOf(clients));
    }

    public RoadClient client(String metroId) {
        RoadClient client = clients.get(metroId);
        if (client == null) throw new UnknownMetro(metroId);
        return client;
    }

    @jakarta.annotation.PreDestroy
    public void close() { for (RoadClient client : clients.values()) client.closeRoutingWork(); }

    /**
     * Reads {@code metro=url} entries separated by commas, for example
     * {@code omaha=http://routing-omaha:8001,lincoln=http://routing-lincoln:8001}. An empty value configures no metro.
     */
    static Map<String, String> parse(String value) {
        Map<String, String> urls = new LinkedHashMap<>();
        if (value.isBlank()) return urls;
        for (String entry : value.split(",", -1)) {
            String[] parts = Required.value(entry).split("=", -1);
            if (parts.length != 2) throw new IllegalArgumentException("routing.metro-urls entries must be metro=url");
            String metro = Required.value(Required.value(parts[0]).strip()), url = Required.value(Required.value(parts[1]).strip());
            if (metro.isEmpty() || metro.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("Invalid metro in routing.metro-urls: '" + metro + "'");
            if (urls.containsKey(metro)) throw new IllegalArgumentException("Metro " + metro + " appears twice in routing.metro-urls");
            urls.put(metro, url(metro, url));
        }
        return urls;
    }

    private static String url(String metro, String value) {
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid routing URL for metro " + metro, invalid); }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Routing URL for metro " + metro + " must be an absolute http or https URL without query or fragment");
        return value.endsWith("/") ? Required.value(value.substring(0, value.length() - 1)) : value;
    }
}
