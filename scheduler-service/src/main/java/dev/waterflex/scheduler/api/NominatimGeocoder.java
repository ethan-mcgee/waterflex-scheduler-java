package dev.waterflex.scheduler.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.SearchDeadline;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Locates an address with the scheduler's own Nominatim, matching results as the portal does (web/lib/geocode.ts):
 * ZIP, house number and street decide, and a mailing city is accepted when the ZIP agrees. Unlike the portal, which
 * lets a person confirm an approximate pin, this accepts only an exact house (the portal's ROOFTOP precision), and
 * only when the first search stage that finds one finds exactly one location. Nothing is guessed: a street line
 * without a house number, a street-level result or two different houses for one address is "not located".
 */
public final class NominatimGeocoder {
    /** Nominatim could not be asked, did not answer in time, or answered with data that fails validation. */
    public static final class Unavailable extends RoadClient.RoadUnavailable {
        private static final long serialVersionUID = 1L;
        Unavailable(String message) { super(message); }
    }

    /** The portal's whole-lookup timeout. */
    private static final Duration LOOKUP = Required.value(Duration.ofSeconds(8));
    /**
     * The portal's first three search stages: the street line with city, state and ZIP; without the state; without the
     * city, for a mailing city the map does not use. Its fourth stage, the street alone, can never find an exact house.
     */
    private static final int STAGES = 3;
    private static final Pattern COORDINATE = Required.value(Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"));
    private static final Pattern NOT_WORD = Required.value(Pattern.compile("[^a-z0-9 ]"));
    private static final Map<String, String> STATES = Required.value(Map.ofEntries(
            Map.entry("AL", "alabama"), Map.entry("AK", "alaska"), Map.entry("AZ", "arizona"), Map.entry("AR", "arkansas"),
            Map.entry("CA", "california"), Map.entry("CO", "colorado"), Map.entry("CT", "connecticut"), Map.entry("DE", "delaware"),
            Map.entry("FL", "florida"), Map.entry("GA", "georgia"), Map.entry("HI", "hawaii"), Map.entry("ID", "idaho"),
            Map.entry("IL", "illinois"), Map.entry("IN", "indiana"), Map.entry("IA", "iowa"), Map.entry("KS", "kansas"),
            Map.entry("KY", "kentucky"), Map.entry("LA", "louisiana"), Map.entry("ME", "maine"), Map.entry("MD", "maryland"),
            Map.entry("MA", "massachusetts"), Map.entry("MI", "michigan"), Map.entry("MN", "minnesota"), Map.entry("MS", "mississippi"),
            Map.entry("MO", "missouri"), Map.entry("MT", "montana"), Map.entry("NE", "nebraska"), Map.entry("NV", "nevada"),
            Map.entry("NH", "new hampshire"), Map.entry("NJ", "new jersey"), Map.entry("NM", "new mexico"), Map.entry("NY", "new york"),
            Map.entry("NC", "north carolina"), Map.entry("ND", "north dakota"), Map.entry("OH", "ohio"), Map.entry("OK", "oklahoma"),
            Map.entry("OR", "oregon"), Map.entry("PA", "pennsylvania"), Map.entry("RI", "rhode island"), Map.entry("SC", "south carolina"),
            Map.entry("SD", "south dakota"), Map.entry("TN", "tennessee"), Map.entry("TX", "texas"), Map.entry("UT", "utah"),
            Map.entry("VT", "vermont"), Map.entry("VA", "virginia"), Map.entry("WA", "washington"), Map.entry("WV", "west virginia"),
            Map.entry("WI", "wisconsin"), Map.entry("WY", "wyoming"), Map.entry("DC", "district of columbia")));

    private final String base;
    private final Duration lookup;
    private final HttpClient http = Required.value(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());

    public NominatimGeocoder(String url) { this(url, LOOKUP); }

    NominatimGeocoder(String url, Duration lookup) {
        this.lookup = lookup;
        URI uri = Required.value(URI.create(url));
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme) || uri.getHost() == null)
            throw new IllegalArgumentException("geocoding.nominatim-url must be an absolute http or https URL");
        base = url.endsWith("/") ? Required.value(url.substring(0, url.length() - 1)) : url;
    }

    /** The exact house, or null when the address has none, or more than one location. */
    public @Nullable RoadPoint locate(PublicTypes.Address address) {
        StreetAddress.StreetLine requested = StreetAddress.parseStreetLine(address.line1());
        String house = requested.houseNumber();
        if (house == null) return null;
        long deadline = System.nanoTime() + lookup.toNanos();
        for (int stage = 0; stage < STAGES; stage++) {
            Set<RoadPoint> houses = new LinkedHashSet<>();
            for (Found found : search(address, house + " " + requested.street(), stage, deadline)) {
                RoadPoint point = exact(Required.value(found), address, requested.street(), house);
                if (point != null) houses.add(point);
            }
            if (houses.size() > 1) return null;
            if (houses.size() == 1) return Required.value(houses.iterator().next());
        }
        return null;
    }

    /** One validated search result. */
    private record Found(double lat, double lng, Map<String, String> address) {
        @Nullable String detail(String key) { return address.get(key); }
    }

    private List<Found> search(PublicTypes.Address address, String street, int stage, long deadline) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("street", street);
        if (stage < 2) query.put("city", address.city());
        if (stage == 0) query.put("state", address.state());
        query.put("postalcode", zip(address.postalCode()));
        query.put("country", "United States");
        query.put("format", "jsonv2");
        query.put("addressdetails", "1");
        query.put("limit", "5");
        StringBuilder url = new StringBuilder(base).append("/search");
        char separator = '?';
        for (var entry : query.entrySet()) {
            url.append(separator).append(URLEncoder.encode(Required.value(entry.getKey()), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(Required.value(entry.getValue()), StandardCharsets.UTF_8));
            separator = '&';
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new Unavailable("Address lookup timed out");
        HttpRequest request = Required.value(HttpRequest.newBuilder(Required.value(URI.create(url.toString())))
                .timeout(SearchDeadline.networkTimeout(Required.value(Duration.ofNanos(remaining)))).GET().build());
        HttpResponse<String> response;
        try { response = Required.value(http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))); }
        catch (HttpTimeoutException timeout) { throw new Unavailable("Address lookup timed out"); }
        catch (IOException failed) { throw new Unavailable("Address lookup unavailable"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Address lookup interrupted");
        }
        if (response.statusCode() / 100 != 2) throw new Unavailable("Address lookup unavailable");
        JsonNode results;
        try { results = CalculationJson.tree(Required.value(response.body())); }
        catch (RuntimeException invalid) { throw malformed(); }
        if (!results.isArray()) throw malformed();
        List<Found> found = new ArrayList<>();
        for (JsonNode result : results) found.add(found(Required.value(result)));
        return found;
    }

    /** Validates one result as the portal's schema does; any violation makes the whole answer malformed. */
    private static Found found(JsonNode result) {
        if (!result.isObject()) throw malformed();
        double lat = coordinate(result.get("lat"), 90), lng = coordinate(result.get("lon"), 180);
        JsonNode detail = result.get("address");
        if (detail == null || !detail.isObject()) throw malformed();
        Map<String, String> address = new LinkedHashMap<>();
        for (String key : List.of("house_number", "road", "city", "town", "village", "hamlet", "suburb", "municipality", "postcode", "state", "country_code")) {
            JsonNode value = detail.get(key);
            if (value == null) continue;
            if (!value.isTextual()) throw malformed();
            String text = Required.value(value.asText());
            // The portal trims these three and requires them to be nonblank when present.
            if (key.equals("road") || key.equals("postcode") || key.equals("country_code")) {
                text = text.trim();
                if (text.isEmpty()) throw malformed();
            }
            address.put(key, text);
        }
        JsonNode bounds = result.get("boundingbox");
        if (bounds != null) {
            if (!bounds.isArray() || bounds.size() != 4) throw malformed();
            double south = coordinate(bounds.get(0), 90), north = coordinate(bounds.get(1), 90);
            double west = coordinate(bounds.get(2), 180), east = coordinate(bounds.get(3), 180);
            if (south > north || west > east) throw malformed();
        }
        return new Found(lat, lng, address);
    }

    private static double coordinate(@Nullable JsonNode node, double limit) {
        if (node == null || !node.isTextual()) throw malformed();
        String text = Required.value(node.asText()).trim();
        if (!COORDINATE.matcher(text).matches()) throw malformed();
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value) || Math.abs(value) > limit) throw malformed();
        return value;
    }

    /**
     * The result's location when it is exactly the requested house. ZIP, house number and street decide. Many addresses
     * carry a mailing city the map does not use, so a different city is accepted when the ZIP agrees; without a ZIP in
     * the result a named locality must match, and an administrative precinct alone does not disprove a postal locality.
     */
    private static @Nullable RoadPoint exact(Found found, PublicTypes.Address address, String street, String house) {
        String road = found.detail("road");
        if (road == null) return null;
        String postcode = found.detail("postcode"), state = found.detail("state"), country = found.detail("country_code"), number = found.detail("house_number");
        String expectedState = Required.value(STATES.getOrDefault(address.state().trim().toUpperCase(Locale.ROOT), address.state()));
        boolean localityMatches = false, namedLocality = false;
        for (String key : List.of("city", "town", "village", "hamlet", "suburb", "municipality")) {
            String locality = found.detail(Required.value(key));
            if (locality == null || locality.isBlank()) continue;
            if (!key.equals("municipality")) namedLocality = true;
            if (matches(locality, address.city())) localityMatches = true;
        }
        boolean postcodeMatches = postcode != null && zip(postcode).equals(zip(address.postalCode()));
        if ((country != null && !matches(country, "us")) || (postcode != null && !postcodeMatches) || (namedLocality && !localityMatches && !postcodeMatches)
                || (state != null && !matches(state, expectedState) && !matches(state, address.state()))
                || !StreetAddress.normalizeStreet(road).equals(StreetAddress.normalizeStreet(street))
                || (number != null && !StreetAddress.normalizeHouseNumber(number).equals(house))) return null;
        boolean exactHouse = number != null && postcodeMatches && matches(country, "us");
        return exactHouse ? new RoadPoint(found.lat(), found.lng()) : null;
    }

    private static String normalize(String value) {
        return Required.value(String.join(" ", NOT_WORD.matcher(value.toLowerCase(Locale.ROOT)).replaceAll(" ").trim().split("\\s+")));
    }

    private static boolean matches(@Nullable String value, String expected) { return value != null && normalize(value).equals(normalize(expected)); }

    /** ZIP codes compare on their first five digits, so "68130" and "68130-1234" agree. */
    private static String zip(String value) {
        String trimmed = value.trim();
        return trimmed.length() <= 5 ? trimmed : Required.value(trimmed.substring(0, 5));
    }

    private static Unavailable malformed() { return new Unavailable("Address lookup returned invalid data"); }
}
