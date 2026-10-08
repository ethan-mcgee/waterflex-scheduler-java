package dev.waterflex.scheduler.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadPoint;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The portal's geocode.test.ts cases against a stub Nominatim, with the API's stricter "exact house only" rule. */
class NominatimGeocoderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PublicTypes.Address ADDRESS = new PublicTypes.Address("2125 Crest Ridge Dr", null, "Papillion", "NE", "68133");
    private static final RoadPoint HOUSE = new RoadPoint(41.1637462, -96.0079032);

    /** The status and body the stub answers with. */
    private record Answer(int status, String body) { }

    private final List<Map<String, String>> queries = new ArrayList<>();
    private final AtomicReference<Function<Map<String, String>, Answer>> answer = new AtomicReference<>(_ -> new Answer(200, "[]"));
    private final AtomicReference<Duration> delay = new AtomicReference<>(Required.value(Duration.ZERO));
    private final HttpServer server;

    NominatimGeocoderTest() throws IOException {
        server = Required.value(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
        server.createContext("/search", exchange -> {
            Map<String, String> query = new HashMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw != null) for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                query.put(URLDecoder.decode(Required.value(parts[0]), StandardCharsets.UTF_8),
                        parts.length > 1 ? URLDecoder.decode(Required.value(parts[1]), StandardCharsets.UTF_8) : "");
            }
            synchronized (queries) { queries.add(query); }
            try { Thread.sleep(delay.get().toMillis()); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            Answer reply = answer.get().apply(query);
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private NominatimGeocoder geocoder() { return new NominatimGeocoder(url()); }

    /** The Papillion street result from the portal's tests, without a house number. */
    private static ObjectNode street() {
        ObjectNode result = JSON.createObjectNode().put("lat", "41.1637462").put("lon", "-96.0079032");
        result.putObject("address").put("road", "Crest Ridge Drive").put("town", "Papillion").put("postcode", "68133").put("country_code", "us");
        result.putArray("boundingbox").add("41.1623576").add("41.1654397").add("-96.0109311").add("-96.0046578");
        return result;
    }

    /** The same result as an exact house. */
    private static ObjectNode house() {
        ObjectNode result = street();
        detail(result).put("house_number", "2125").put("state", "Nebraska");
        return result;
    }

    /** A lambda parameter carries no nullness annotation, so this accepts one and checks it. */
    private static ObjectNode detail(@Nullable ObjectNode result) { return (ObjectNode) Required.value(Required.value(result).get("address")); }

    private void answer(ObjectNode... results) {
        var array = JSON.createArrayNode();
        for (ObjectNode result : results) array.add(result);
        String body = Required.value(array.toString());
        answer.set(_ -> new Answer(200, body));
    }

    private @Nullable RoadPoint locate(String line1, String city, String postalCode) {
        return geocoder().locate(new PublicTypes.Address(line1, null, city, "NE", postalCode));
    }

    @Test void omahaDirectionAndPlazaSpellingsAgreeWithStrictZipAndRoadChecks() {
        ObjectNode plaza = JSON.createObjectNode().put("lat", "41.23").put("lon", "-96.18");
        plaza.putObject("address").put("house_number", "2825").put("road", "South 170th Plaza").put("city", "Omaha").put("state", "Nebraska")
                .put("postcode", "68130").put("country_code", "us");
        answer(plaza);
        for (String line1 : List.of("2825 S 170th Plz", "2825 S 170th Plaza", "2825 South 170th Plaza", " 2825  s.  170TH  plz. ")) {
            assertEquals(new RoadPoint(41.23, -96.18), locate(Required.value(line1), "Omaha", "68130"), line1);
            assertNull(locate(Required.value(line1), "Omaha", "68103"), line1);
        }
        for (String line1 : List.of("2825 N 170th Plz", "2825 170th Plz", "2826 S 170th Plz", "2825 S 170th Cir", "2825 S 171st Plz"))
            assertNull(locate(Required.value(line1), "Omaha", "68130"), line1);
    }

    @Test void stagesDropTheStateThenTheCityAndStopAtTheFirstExactHouse() {
        answer.set(query -> new Answer(200, query.containsKey("state") ? "[]" : "[" + house() + "]"));
        assertEquals(HOUSE, geocoder().locate(ADDRESS));
        assertEquals(2, queries.size());
        Map<String, String> first = Required.value(queries.get(0)), second = Required.value(queries.get(1));
        assertEquals(Map.of("street", "2125 Crest Ridge Dr", "city", "Papillion", "state", "NE", "postalcode", "68133", "country", "United States",
                "format", "jsonv2", "addressdetails", "1", "limit", "5"), first);
        assertFalse(second.containsKey("state"));
        assertEquals("Papillion", second.get("city"));

        queries.clear();
        answer.set(query -> new Answer(200, query.containsKey("city") ? "[]" : "[" + house() + "]"));
        assertEquals(HOUSE, geocoder().locate(new PublicTypes.Address("2125 Crest Ridge Dr Apt 4", null, "Papillion", "NE", "68133-1234")));
        assertEquals(3, queries.size(), "a mailing city the map does not use");
        Map<String, String> third = Required.value(queries.get(2));
        assertFalse(third.containsKey("city"));
        assertEquals("2125 Crest Ridge Dr", third.get("street"), "the unit is dropped");
        assertEquals("68133", third.get("postalcode"), "ZIP+4 is searched by its five digits");
    }

    @Test void aStreetResultOrALineWithoutAHouseNumberIsNeverAHouse() {
        answer(street());
        assertNull(geocoder().locate(ADDRESS));
        assertEquals(3, queries.size(), "the portal's street-only stage cannot find a house, so it is not asked");
        queries.clear();
        answer(house());
        assertNull(locate("Crest Ridge Dr", "Papillion", "68133"));
        assertEquals(0, queries.size(), "a line without a house number is not looked up");
    }

    @Test void conflictingAddressDetailsAreNotAMatch() {
        List<java.util.function.Consumer<ObjectNode>> changes = List.of(
                detail -> detail.put("postcode", "68130"),
                detail -> { detail.remove("postcode"); detail.put("town", "Omaha"); },
                detail -> detail.put("state", "Iowa"),
                detail -> detail.put("country_code", "ca"),
                detail -> detail.put("house_number", "2126"),
                detail -> detail.put("road", "Other Drive"),
                detail -> detail.remove("country_code"));
        for (var change : changes) {
            ObjectNode result = house();
            change.accept(detail(result));
            answer(result);
            assertNull(geocoder().locate(ADDRESS), result.toString());
        }
    }

    @Test void zipHouseNumberAndStreetDecideAndAMailingCityIsAcceptedWhenTheZipAgrees() {
        ObjectNode ralston = house();
        detail(ralston).put("town", "Ralston").put("postcode", "68133-4410");
        answer(ralston);
        for (String line1 : List.of("2125 Crest Ridge Dr", "2125 Crest Ridge Drive Apt 4", "2125 Crest Ridge Dr #4"))
            assertEquals(HOUSE, locate(Required.value(line1), "Papillion", "68133"), line1);
        assertEquals(HOUSE, locate("2125 Crest Ridge Dr", "Papillion", "68133-1234"));
        assertNull(locate("2127 Crest Ridge Dr", "Papillion", "68133"));
        assertNull(locate("2125 Crest Ridge Dr", "Papillion", "68046"));
        detail(ralston).remove("postcode");
        answer(ralston);
        assertNull(geocoder().locate(ADDRESS), "without a ZIP the named town must match");
        ObjectNode half = house();
        detail(half).put("house_number", "123 1/2").put("road", "Main Street");
        answer(half);
        assertEquals(HOUSE, locate("123½ Main St", "Papillion", "68133"));
        assertNull(locate("123 Main St", "Papillion", "68133"));
    }

    @Test void twoDifferentHousesForOneAddressAreNotGuessed() {
        ObjectNode other = house().put("lat", "41.2");
        answer(house(), other);
        assertNull(geocoder().locate(ADDRESS));
        answer(house(), house());
        assertEquals(HOUSE, geocoder().locate(ADDRESS), "the same house twice is one location");
    }

    @Test void malformedAnswersAreUnavailableNotMisses() {
        List<java.util.function.Consumer<ObjectNode>> changes = List.of(
                result -> result.putNull("address"), result -> result.putNull("lat"), result -> result.put("lat", "NaN"),
                result -> result.put("lon", "181"), result -> result.put("lat", 41.16),
                result -> result.putArray("boundingbox").add("42").add("41").add("-97").add("-96"),
                result -> detail(result).putNull("road"), result -> detail(result).put("road", " "), result -> detail(result).put("postcode", 68133));
        for (var change : changes) {
            ObjectNode result = house();
            change.accept(result);
            answer(result);
            var failure = assertThrows(NominatimGeocoder.Unavailable.class, () -> geocoder().locate(ADDRESS), result.toString());
            assertEquals("Address lookup returned invalid data", failure.getMessage());
        }
        for (String body : List.of("{\"results\":null}", "not json", "[1]")) {
            answer.set(_ -> new Answer(200, Required.value(body)));
            assertThrows(NominatimGeocoder.Unavailable.class, () -> geocoder().locate(ADDRESS), body);
        }
    }

    @Test void transportFailuresTimeoutsAndGenuineMissesAreSeparate() {
        answer.set(_ -> new Answer(500, "[]"));
        assertEquals("Address lookup unavailable", assertThrows(NominatimGeocoder.Unavailable.class, () -> geocoder().locate(ADDRESS)).getMessage());
        answer(house());
        delay.set(Required.value(Duration.ofMillis(1500)));
        assertEquals("Address lookup timed out", assertThrows(NominatimGeocoder.Unavailable.class,
                () -> new NominatimGeocoder(url(), Required.value(Duration.ofMillis(200))).locate(ADDRESS)).getMessage());
        delay.set(Required.value(Duration.ZERO));
        answer.set(_ -> new Answer(200, "[]"));
        assertNull(geocoder().locate(ADDRESS), "no result is a miss, not a failure");
        String stopped = url();
        server.stop(0);
        assertEquals("Address lookup unavailable", assertThrows(NominatimGeocoder.Unavailable.class, () -> new NominatimGeocoder(stopped).locate(ADDRESS)).getMessage());
    }

    @Test void theModeDecidesWhetherAnAddressIsLookedUp() {
        answer(house());
        assertNull(new AddressLocation("COORDINATES_REQUIRED", url()).locate(ADDRESS));
        assertEquals(0, queries.size(), "the default mode never asks Nominatim");
        assertEquals(HOUSE, new AddressLocation("NOMINATIM", url()).locate(ADDRESS));
        assertThrows(IllegalArgumentException.class, () -> new AddressLocation("GOOGLE", url()));
        assertThrows(IllegalArgumentException.class, () -> new AddressLocation("NOMINATIM", "localhost:8082"));
        assertThrows(IllegalArgumentException.class, () -> new AddressLocation("NOMINATIM", "ftp://nominatim"));
    }

    @Test void anAddressLookupOutageIsReportedAsSuch() {
        assertEquals(new PublicResponses.Problem(PublicResponses.ErrorCode.ROUTING_UNAVAILABLE, "Address lookup timed out"),
                DailyProposals.routingUnavailable(new NominatimGeocoder.Unavailable("Address lookup timed out")));
        assertEquals(new PublicResponses.Problem(PublicResponses.ErrorCode.ROUTING_UNAVAILABLE, "Road routing unavailable"),
                DailyProposals.routingUnavailable(new dev.waterflex.scheduler.RoadClient.RoadUnavailable("Injected")));
    }
}
