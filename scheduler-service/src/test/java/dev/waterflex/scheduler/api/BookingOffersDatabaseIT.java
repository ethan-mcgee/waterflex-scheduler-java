package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.BookingOfferLimit;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.CalculationTransport;
import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.api.PublicResponses.*;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** POST /api/v1/booking/offers below HTTP: search, holds, reconciliation, supersession and failures, on a real database. */
class BookingOffersDatabaseIT {
    private static final LocalDate DAY = Required.value(LocalDate.parse("2026-10-12"));
    private final String suffix = Required.value(UUID.randomUUID().toString().substring(0, 8));
    private final String tenant = "booking-it-" + suffix, other = "booking-it-other-" + suffix;
    private final JdbcTemplate jdbc;
    private final DataSourceTransactionManager manager;
    private final PublicApiStore store;
    private final BookingStore bookings;
    private final AtomicBoolean routingDown = new AtomicBoolean();
    private final AtomicReference<@Nullable Runnable> routingHook = new AtomicReference<>();
    private final AtomicInteger routingCalls = new AtomicInteger();
    private final AtomicReference<Instant> now = new AtomicReference<>(Required.value(Instant.parse("2026-10-11T12:00:00Z")));
    private final RoadClient omaha;

    BookingOffersDatabaseIT() {
        String url = Required.value(System.getenv("JDBC_DATABASE_URL"), "isolated integration database URL");
        assertEquals("/waterflex_test", URI.create(url.substring(5)).getPath());
        var source = new DriverManagerDataSource(url, "waterflex", "waterflex");
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        store = new PublicApiStore(jdbc, manager);
        bookings = new BookingStore(store, jdbc);
        jdbc.update("INSERT INTO tenant (id,name) VALUES (?,?),(?,?)", tenant, "Booking IT", other, "Booking IT other");
        omaha = mock(RoadClient.class, invocation -> {
            if (routingDown.get()) throw new RoadClient.RoadUnavailable("Injected routing outage");
            String method = invocation.getMethod().getName();
            if (method.equals("activeIdentity")) return "omaha-map-v7";
            if (!method.equals("sparse")) throw new AssertionError("Unexpected routing call " + method);
            routingCalls.incrementAndGet();
            Runnable hook = routingHook.get();
            if (hook != null) hook.run();
            List<RoadClient.Pair> pairs = Required.value(invocation.getArgument(0));
            Map<String, RoadClient.Leg> legs = new HashMap<>();
            for (RoadClient.Pair pair : pairs) legs.put(pair.id(), pair.origin().equals(pair.destination()) ? new RoadClient.Leg(0, 0) : new RoadClient.Leg(600, 8000));
            return legs;
        });
    }

    @AfterEach void clean() {
        for (String table : List.of("api_booking_offer", "api_booking_offer_set", "api_booking_day", "api_request"))
            jdbc.update("DELETE FROM " + table + " WHERE \"tenantId\" IN (?,?)", tenant, other);
        jdbc.update("DELETE FROM tenant WHERE id IN (?,?)", tenant, other);
    }

    private BookingOffers offers(Map<String, String> metros) {
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return Required.value(java.time.ZoneOffset.UTC); }
            @Override public Clock withZone(@Nullable ZoneId zone) { return this; }
            @Override public Instant instant() { return Required.value(now.get()); }
        };
        return new BookingOffers(store, bookings, new MetroRouting(metros, _ -> omaha), _ -> null, new CalculationTransport("EMBEDDED", "http://127.0.0.1:1", ""),
                new SearchAdmission(2, 16), new BookingOfferLimit("4"), jdbc, "BOUNDED", 250, clock);
    }

    private BookingOffers offers() { return offers(Required.value(Map.of("omaha", "http://routing-omaha:8001"))); }

    /** The spec's booking example with a fresh request ID, coordinates for appt-8, and any change applied. */
    private static String request(Consumer<ObjectNode> change) {
        ObjectNode request = object(CalculationJson.tree(PublicApiContractTest.example("BookingOffersRequest")));
        request.put("requestId", UUID.randomUUID().toString());
        object(appointments(request).get(1)).putObject("location").put("lat", 41.2587).put("lng", -95.9378);
        change.accept(request);
        return CalculationJson.write(request);
    }

    private static String request() { return request(_ -> { }); }

    private static String forJob(String job) { return request(request -> object(request.get("job")).put("id", job)); }

    private static ObjectNode object(@Nullable JsonNode node) {
        assertInstanceOf(ObjectNode.class, node);
        return (ObjectNode) Required.value(node);
    }

    private static ArrayNode appointments(@Nullable ObjectNode request) {
        return (ArrayNode) Required.value(object(Required.value(request).get("snapshot")).get("appointments"));
    }

    private static OfferSet offerSet(DailyProposals.Reply reply) {
        assertEquals(201, reply.status(), reply.json());
        return PublicRequests.read(reply.json(), OfferSet.class);
    }

    private static Problem problem(DailyProposals.Reply reply, int status, ErrorCode code) {
        assertEquals(status, reply.status(), reply.json());
        Problem problem = PublicRequests.read(reply.json(), Problem.class);
        assertEquals(code, problem.error());
        return problem;
    }

    private BookingDayState state() {
        return Required.value(Required.value(bookings.days(tenant, "omaha", Required.value(List.of(DAY))).get(DAY)).state(), "stored day state");
    }

    private Map<String, String> offerStatuses(String offerSetId) {
        Map<String, String> statuses = new HashMap<>();
        jdbc.query("SELECT id,status FROM api_booking_offer WHERE \"tenantId\"=? AND \"offerSetId\"=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> statuses.put(Required.value(rs.getString(1)), Required.value(rs.getString(2))), tenant, offerSetId);
        return statuses;
    }

    private String setStatus(String offerSetId) {
        return Required.value(jdbc.queryForObject("SELECT status FROM api_booking_offer_set WHERE \"tenantId\"=? AND id=?", String.class, tenant, offerSetId));
    }

    @Test void offersAreHeldStoredAndReplayed() {
        String body = request();
        var reply = offers().create(tenant, body);
        OfferSet set = offerSet(reply);
        assertTrue(set.searchComplete());
        assertFalse(set.offers().isEmpty());
        assertTrue(set.offers().size() <= 4);
        assertEquals(List.of(), set.skippedTechnicianDays());
        assertEquals(now.get().plusSeconds(600), set.expiresAt());
        BookingDayState state = state();
        assertEquals(set.offers().size(), state.holds().size(), "every offer holds its slot");
        for (var hold : state.holds().values()) {
            assertEquals("job-311", hold.jobId());
            assertEquals("tech-1", hold.technicianId(), "only tech-1 installs softeners");
        }
        assertEquals(set.offers().size(), offerStatuses(set.offerSetId()).values().stream().filter("HELD"::equals).count());
        assertEquals(reply, offers().create(tenant, body), "the same requestId replays the offer set");
        int routed = routingCalls.get();
        assertEquals(reply, offers().create(tenant, body));
        assertEquals(routed, routingCalls.get(), "a replay does not search again");
    }

    @Test void anotherJobSearchesAroundTheHoldsAlreadyGiven() {
        OfferSet first = offerSet(offers().create(tenant, forJob("job-a")));
        OfferSet second = offerSet(offers().create(tenant, forJob("job-b")));
        BookingDayState state = state();
        assertEquals(first.offers().size() + second.offers().size(), state.holds().size(), "both jobs' holds stand together in one arrangement");
        Set<String> jobs = new java.util.HashSet<>();
        state.holds().values().forEach(hold -> jobs.add(Required.value(hold).jobId()));
        assertEquals(Set.of("job-a", "job-b"), jobs);
        assertEquals("ACTIVE", setStatus(first.offerSetId()));
    }

    /** Regression: offers are validated with the legs the bounded refinement fetched, which moves around earlier holds need. */
    @Test void severalJobsInARowEachSearchAroundTheEarlierHolds() {
        int held = 0;
        for (String job : List.of("job-a", "job-b", "job-c", "job-d")) {
            held += offerSet(offers().create(tenant, forJob(Required.value(job)))).offers().size();
            assertEquals(held, state().holds().size(), job);
        }
    }

    @Test void aNewSearchForTheSameJobSupersedesItsEarlierOffers() {
        OfferSet first = offerSet(offers().create(tenant, request()));
        OfferSet second = offerSet(offers().create(tenant, request()));
        assertEquals("SUPERSEDED", setStatus(first.offerSetId()));
        assertTrue(offerStatuses(first.offerSetId()).values().stream().allMatch("SUPERSEDED"::equals));
        assertEquals("ACTIVE", setStatus(second.offerSetId()));
        Set<String> offerIds = new java.util.HashSet<>();
        state().holds().values().forEach(hold -> offerIds.add(Required.value(hold).offerId()));
        Set<String> secondIds = new java.util.HashSet<>();
        second.offers().forEach(offer -> secondIds.add(Required.value(offer).offerId()));
        assertEquals(secondIds, offerIds, "only the new offers hold slots");
    }

    @Test void expiredHoldsLeaveTheArrangementOnItsNextUse() {
        offerSet(offers().create(tenant, forJob("job-a")));
        now.set(Required.value(now.get().plusSeconds(601)));
        offerSet(offers().create(tenant, forJob("job-b")));
        for (var hold : state().holds().values()) assertEquals("job-b", hold.jobId());
    }

    @Test void aDispatcherChangeUnderAHoldLosesTheDaysHolds() {
        OfferSet held = offerSet(offers().create(tenant, forJob("job-a")));
        // A dispatcher adds an appointment to tech-1, whose route holds job-a's slots.
        String changed = request(request -> {
            object(request.get("job")).put("id", "job-b");
            ObjectNode tech1 = object(((ArrayNode) Required.value(object(request.get("snapshot")).get("technicianDays"))).get(0));
            tech1.put("lastModified", "2026-10-11T22:00:00Z");
            appointments(request).addObject().put("id", "appt-9").put("technicianId", "tech-1").put("serviceDate", "2026-10-12")
                    .put("serviceId", "softener-service").put("durationMinutes", 30).put("sequence", 2).put("plannedStart", "2026-10-12T15:00:00-05:00")
                    .<ObjectNode>set("window", object(CalculationJson.tree("{\"start\":\"2026-10-12T14:00:00-05:00\",\"end\":\"2026-10-12T16:00:00-05:00\"}")))
                    .putObject("location").put("lat", 41.27).put("lng", -95.95);
        });
        offerSet(offers().create(tenant, changed));
        assertTrue(offerStatuses(held.offerSetId()).values().stream().allMatch("LOST"::equals));
        for (var hold : state().holds().values()) assertEquals("job-b", hold.jobId());
        assertEquals(List.of("appt-7", "appt-8", "appt-9"), Required.value(state().technicians().get("tech-1")).expected());
    }

    @Test void finalFailuresAreStoredAndReplayedAndMalformedRequestsClaimNothing() {
        problem(offers().create(tenant, "{\"requestId\":"), 400, ErrorCode.INVALID_REQUEST);
        problem(offers().create(tenant, request(request -> request.put("tenantId", "x"))), 400, ErrorCode.INVALID_REQUEST);
        assertEquals(0L, count("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", tenant));
        String unknownMetro = request();
        var reply = offers(Required.value(Map.of("lincoln", "http://routing-lincoln:8001"))).create(tenant, unknownMetro);
        problem(reply, 422, ErrorCode.INCOMPLETE_FACTS);
        assertEquals(reply, offers().create(tenant, unknownMetro), "the stored failure is replayed");
        String unlocatable = request(request -> object(request.get("job")).putObject("location").putObject("address")
                .put("line1", "1 Main St").put("city", "Omaha").put("state", "NE").put("postalCode", "68102"));
        assertTrue(problem(offers().create(tenant, unlocatable), 422, ErrorCode.INCOMPLETE_FACTS).message().contains("job location"));
        now.set(Required.value(Instant.parse("2026-10-12T11:00:00Z")));
        assertTrue(problem(offers().create(tenant, request()), 422, ErrorCode.INCOMPLETE_FACTS).message().contains("frozen"));
        assertEquals(0L, count("SELECT count(*) FROM api_booking_offer_set WHERE \"tenantId\"=?", tenant));
    }

    @Test void aRoutingOutageReleasesTheClaimSoTheRetryRuns() {
        String body = request();
        routingDown.set(true);
        problem(offers().create(tenant, body), 503, ErrorCode.ROUTING_UNAVAILABLE);
        assertEquals(0L, count("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", tenant));
        routingDown.set(false);
        offerSet(offers().create(tenant, body));
    }

    @Test void aConcurrentChangeToTheDayIsRetriedOnceThenRefused() {
        offerSet(offers().create(tenant, forJob("job-a")));
        AtomicInteger bumps = new AtomicInteger(1);
        routingHook.set(() -> { if (bumps.getAndDecrement() > 0) bump(); });
        offerSet(offers().create(tenant, forJob("job-b")));
        assertTrue(bumps.get() < 0, "the day moved once, and the retry routed again and published");
        routingHook.set(this::bump);
        String busy = forJob("job-c");
        problem(offers().create(tenant, busy), 429, ErrorCode.BUSY);
        assertEquals(0L, count("SELECT count(*) FROM api_request WHERE \"tenantId\"=? AND operation='BOOKING_OFFERS' AND state='IN_PROGRESS'", tenant));
        routingHook.set(null);
        offerSet(offers().create(tenant, busy));
    }

    /** Another booking's write to the day, between this search's read and its publication. */
    private void bump() {
        jdbc.update("UPDATE api_booking_day SET version=version+1 WHERE \"tenantId\"=? AND \"metroId\"='omaha' AND \"serviceDate\"=?", tenant, java.sql.Date.valueOf(DAY));
    }

    @Test void bookingStateIsInvisibleToOtherTenantsAtTheDatabase() {
        offerSet(offers().create(tenant, request()));
        assertEquals(1L, rows(tenant, "SELECT count(*) FROM api_booking_day"));
        assertEquals(0L, rows(other, "SELECT count(*) FROM api_booking_day"));
        assertEquals(0L, rows(other, "SELECT count(*) FROM api_booking_offer"));
        assertEquals(0L, rows(other, "SELECT count(*) FROM api_booking_offer_set"));
        assertTrue(bookings.days(other, "omaha", Required.value(List.of(DAY))).get(DAY) == BookingStore.StoredDay.NONE);
    }

    private long count(String sql, @Nullable Object... arguments) {
        return Required.value(jdbc.queryForObject(sql, Long.class, arguments)).longValue();
    }

    private long rows(String scope, String sql) {
        Long count = new TransactionTemplate(manager).execute(_ -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, scope);
            jdbc.execute("SET LOCAL ROLE scheduler_tenant");
            return jdbc.queryForObject(sql, Long.class);
        });
        return Required.value(count).longValue();
    }
}
