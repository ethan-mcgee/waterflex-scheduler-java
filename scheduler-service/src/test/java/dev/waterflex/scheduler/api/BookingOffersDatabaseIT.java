package dev.waterflex.scheduler.api;

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

/** The booking endpoints below HTTP (offers, select, release, confirm): search, holds, reconciliation, supersession and failures, on a real database. */
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
        for (String table : List.of("api_booking_receipt", "api_booking_offer", "api_booking_offer_set", "api_booking_day", "api_request"))
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
                new SearchAdmission(2, 16), jdbc, "BOUNDED", 250, clock);
    }

    private BookingOffers offers() { return offers(Required.value(Map.of("omaha", "http://routing-omaha:8001"))); }

    private BookingHolds holds() {
        return new BookingHolds(store, bookings, new Clock() {
            @Override public ZoneId getZone() { return Required.value(java.time.ZoneOffset.UTC); }
            @Override public Clock withZone(@Nullable ZoneId zone) { return this; }
            @Override public Instant instant() { return Required.value(now.get()); }
        });
    }

    /** A published set with two holds, one on each technician's route, as a search would publish it. */
    private OfferSet twoHolds() {
        String requestId = Required.value(UUID.randomUUID().toString()), setId = BookingStore.newOfferSetId();
        String first = Required.value(UUID.randomUUID().toString()), second = Required.value(UUID.randomUUID().toString());
        String firstHold = Required.value(UUID.randomUUID().toString()), secondHold = Required.value(UUID.randomUUID().toString());
        Instant expiresAt = Required.value(now.get().plusSeconds(600));
        Instant start = Required.value(Instant.parse("2026-10-12T15:00:00Z")), end = Required.value(Instant.parse("2026-10-12T17:00:00Z"));
        Map<String, BookingDayState.TechnicianState> technicians = new java.util.TreeMap<>();
        technicians.put("tech-1", new BookingDayState.TechnicianState(1L, Required.value(List.of("appt-7", "appt-8")), Required.value(List.of("appt-7", firstHold, "appt-8"))));
        technicians.put("tech-2", new BookingDayState.TechnicianState(1L, Required.value(List.of()), Required.value(List.of(secondHold))));
        Map<String, BookingDayState.HoldState> held = new java.util.TreeMap<>();
        held.put(firstHold, new BookingDayState.HoldState("job-311", first, setId, expiresAt, "softener-install", start, end, 90, 41.235, -96.042, start, "tech-1"));
        held.put(secondHold, new BookingDayState.HoldState("job-311", second, setId, expiresAt, "softener-install", start, end, 90, 41.235, -96.042, start, "tech-2"));
        var body = new OfferSet(setId, expiresAt, Required.value(List.of(new Offer(first, DAY, new PublicTypes.Window(start, end)), new Offer(second, DAY, new PublicTypes.Window(start, end)))),
                true, Required.value(List.of()));
        var started = assertInstanceOf(PublicApiStore.Started.class, store.claim(tenant, requestId, PublicApiStore.Operation.BOOKING_OFFERS, Required.value("c".repeat(64))));
        var key = new BookingStore.DayKey("omaha", DAY);
        bookings.publish(tenant, started.ownerToken(), Required.value(Map.of(key, 0)), Required.value(Map.of(key, new BookingDayState(technicians, held))), Required.value(Map.of()),
                new BookingStore.Publication(setId, requestId, "job-311", "omaha", expiresAt, body, Required.value(List.of(
                        new BookingStore.NewOffer(first, firstHold, DAY, "tech-1", start, end), new BookingStore.NewOffer(second, secondHold, DAY, "tech-2", start, end)))));
        return body;
    }

    /** Every active hold on the day. */
    private Set<String> heldIds() { return Required.value(state().holds().keySet()); }

    private static String requestOnly() { return "{\"requestId\":\"" + UUID.randomUUID() + "\"}"; }

    private BookingConfirm confirms() {
        return new BookingConfirm(store, bookings, new MetroRouting(Required.value(Map.of("omaha", "http://routing-omaha:8001")), _ -> omaha), _ -> null,
                new SearchAdmission(2, 16), jdbc, new Clock() {
                    @Override public ZoneId getZone() { return Required.value(java.time.ZoneOffset.UTC); }
                    @Override public Clock withZone(@Nullable ZoneId zone) { return this; }
                    @Override public Instant instant() { return Required.value(now.get()); }
                });
    }

    /** A confirm body from a booking request: its snapshot with a fresh request ID. */
    private static ObjectNode confirmation(String bookingRequest) {
        ObjectNode body = object(CalculationJson.tree(bookingRequest));
        body.remove("job"); body.remove("horizon"); body.remove("offerLimit");
        body.put("requestId", UUID.randomUUID().toString());
        return body;
    }

    private static String confirmBody() { return CalculationJson.write(confirmation(request())); }

    private static CommitReceipt receipt(DailyProposals.Reply reply) {
        assertEquals(200, reply.status(), reply.json());
        return PublicRequests.read(reply.json(), CommitReceipt.class);
    }

    private static Assignment assignment(CommitReceipt receipt, String appointment) {
        List<Assignment> found = Required.value(receipt.assignments().stream().filter(item -> Required.value(item).appointmentId().equals(appointment)).toList());
        assertEquals(1, found.size(), appointment + " in " + receipt);
        return Required.value(found.getFirst());
    }

    private String holdOf(String offerId) {
        return Required.value(jdbc.queryForObject("SELECT \"holdId\" FROM api_booking_offer WHERE \"tenantId\"=? AND id=?", String.class, tenant, offerId));
    }

    /**
     * The host after writing a receipt, as WaterFlex Software would: each listed appointment as assigned, the job added
     * under its ID, and a new timestamp on every listed technician-day.
     */
    private static ObjectNode written(ObjectNode confirm, CommitReceipt receipt, Offer offered) {
        ObjectNode snapshot = object(confirm.get("snapshot"));
        ArrayNode listed = (ArrayNode) Required.value(snapshot.get("appointments"));
        for (Assignment assignment : receipt.assignments()) {
            ObjectNode found = null;
            for (JsonNode node : listed) if (Required.value(node).path("id").asText().equals(assignment.appointmentId())) found = object(node);
            if (found == null) {
                found = listed.addObject().put("id", assignment.appointmentId()).put("serviceDate", DAY.toString()).put("serviceId", "softener-install").put("durationMinutes", 90);
                found.putObject("window").put("start", offered.window().start().toString()).put("end", offered.window().end().toString());
                found.putObject("location").put("lat", 41.235).put("lng", -96.042);
            }
            found.put("technicianId", assignment.technicianId()).put("sequence", assignment.sequence()).put("plannedStart", assignment.plannedStart().toString());
        }
        for (PublicTypes.TechnicianDayVersion day : receipt.technicianDays())
            for (JsonNode node : Required.value(snapshot.get("technicianDays")))
                if (Required.value(node).path("technicianId").asText().equals(day.technicianId())) object(node).put("lastModified", "2026-10-11T23:00:00Z");
        confirm.put("requestId", UUID.randomUUID().toString());
        return confirm;
    }

    private static Hold hold(DailyProposals.Reply reply) {
        assertEquals(200, reply.status(), reply.json());
        return PublicRequests.read(reply.json(), Hold.class);
    }

    private static Offer offer(OfferSet set, int index) { return Required.value(set.offers().get(index)); }

    private Map<String, String> statusesByOffer(OfferSet set) { return offerStatuses(set.offerSetId()); }

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

    @Test void theClientsOfferLimitCapsTheSearch() {
        OfferSet one = offerSet(offers().create(tenant, request(request -> request.put("offerLimit", 1))));
        assertEquals(1, one.offers().size());
        assertEquals(1, state().holds().size());
        OfferSet four = offerSet(offers().create(tenant, request()));
        assertTrue(four.offers().size() > 1, "the example day has room for several offers");
        assertTrue(four.offers().size() <= 4);
        long requests = count("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", tenant);
        problem(offers().create(tenant, request(request -> request.remove("offerLimit"))), 400, ErrorCode.INVALID_REQUEST);
        problem(offers().create(tenant, request(request -> request.put("offerLimit", 5))), 400, ErrorCode.INVALID_REQUEST);
        assertEquals(requests, count("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", tenant), "a missing limit claims nothing");
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

    @Test void selectingKeepsOneHoldReleasesTheOthersAndIsReplayed() {
        OfferSet set = twoHolds();
        assertTrue(set.offers().size() >= 2, "a set with siblings");
        String body = requestOnly();
        var reply = holds().select(tenant, offer(set, 0).offerId(), body);
        Hold kept = hold(reply);
        assertEquals(offer(set, 0).offerId(), kept.offerId());
        assertEquals(set.expiresAt(), kept.expiresAt(), "selecting keeps the original expiry");
        assertEquals(Set.of(kept.holdId()), heldIds());
        Map<String, String> statuses = statusesByOffer(set);
        assertEquals("SELECTED", statuses.get(kept.offerId()));
        assertEquals(set.offers().size() - 1, statuses.values().stream().filter("RELEASED"::equals).count());
        assertEquals("SELECTED", setStatus(set.offerSetId()));
        assertEquals(reply, holds().select(tenant, kept.offerId(), body), "the same requestId replays the hold");
        assertEquals(kept, hold(holds().select(tenant, kept.offerId(), requestOnly())), "selecting the selected offer again returns its hold");
        assertTrue(problem(holds().select(tenant, offer(set, 1).offerId(), requestOnly()), 409, ErrorCode.HOLD_UNAVAILABLE).message().contains("selected"));
    }

    @Test void releasingEndsTheWholeSetAndCanBeRepeated() {
        OfferSet set = twoHolds();
        String body = requestOnly();
        var reply = holds().release(tenant, offer(set, 0).offerId(), body);
        assertEquals(200, reply.status(), reply.json());
        assertEquals(new Released(true), PublicRequests.read(reply.json(), Released.class));
        assertTrue(heldIds().isEmpty());
        assertTrue(statusesByOffer(set).values().stream().allMatch("RELEASED"::equals));
        assertEquals("RELEASED", setStatus(set.offerSetId()));
        assertEquals(reply, holds().release(tenant, offer(set, 0).offerId(), body));
        assertEquals(200, holds().release(tenant, offer(set, 1).offerId(), requestOnly()).status(), "releasing again is a 200");
        problem(holds().select(tenant, offer(set, 0).offerId(), requestOnly()), 409, ErrorCode.HOLD_UNAVAILABLE);
    }

    @Test void releasingAfterSelectingEndsTheSelectedHold() {
        OfferSet set = twoHolds();
        hold(holds().select(tenant, offer(set, 0).offerId(), requestOnly()));
        assertEquals(200, holds().release(tenant, offer(set, 0).offerId(), requestOnly()).status());
        assertTrue(heldIds().isEmpty());
        assertEquals("RELEASED", statusesByOffer(set).get(offer(set, 0).offerId()));
    }

    @Test void expiredLostAndUnknownOffersCannotBeSelected() {
        OfferSet set = twoHolds();
        String unknown = requestOnly();
        var missing = holds().select(tenant, "0c8b8f0e-1a2b-4c3d-8e9f-0a1b2c3d4e5f", unknown);
        problem(missing, 404, ErrorCode.NOT_FOUND);
        assertEquals(missing, holds().select(tenant, "0c8b8f0e-1a2b-4c3d-8e9f-0a1b2c3d4e5f", unknown), "a 404 is stored and replayed");
        problem(holds().select(other, offer(set, 0).offerId(), requestOnly()), 404, ErrorCode.NOT_FOUND);
        problem(holds().release(other, offer(set, 0).offerId(), requestOnly()), 404, ErrorCode.NOT_FOUND);
        long requests = count("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", tenant);
        problem(holds().select(tenant, offer(set, 0).offerId(), "{\"requestId\":"), 400, ErrorCode.INVALID_REQUEST);
        problem(holds().select(tenant, " ", requestOnly()), 400, ErrorCode.INVALID_REQUEST);
        problem(holds().select(tenant, offer(set, 0).offerId(), "{\"requestId\":\"" + UUID.randomUUID() + "\",\"offerId\":\"x\"}"), 400, ErrorCode.INVALID_REQUEST);
        assertEquals(requests, count("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", tenant), "a malformed request claims nothing");
        Instant issued = now.get();
        now.set(Required.value(issued.plusSeconds(601)));
        assertTrue(problem(holds().select(tenant, offer(set, 0).offerId(), requestOnly()), 409, ErrorCode.HOLD_UNAVAILABLE).message().contains("expired"));
        now.set(issued);
        String reused = requestOnly();
        hold(holds().select(tenant, offer(set, 0).offerId(), reused));
        problem(holds().release(tenant, offer(set, 0).offerId(), reused), 400, ErrorCode.INVALID_REQUEST);
    }

    @Test void aLostHoldCannotBeSelected() {
        OfferSet set = offerSet(offers().create(tenant, forJob("job-a")));
        offerSet(offers().create(tenant, dispatcherChange("job-b")));
        assertTrue(problem(holds().select(tenant, offer(set, 0).offerId(), requestOnly()), 409, ErrorCode.HOLD_UNAVAILABLE).message().contains("lost"));
    }

    @Test void aNewSearchForTheJobEndsItsSelectedHold() {
        OfferSet first = offerSet(offers().create(tenant, request()));
        Hold kept = hold(holds().select(tenant, offer(first, 0).offerId(), requestOnly()));
        OfferSet second = offerSet(offers().create(tenant, request()));
        assertEquals("SUPERSEDED", setStatus(first.offerSetId()));
        assertEquals("SUPERSEDED", statusesByOffer(first).get(kept.offerId()));
        assertFalse(state().holds().containsKey(kept.holdId()));
        assertEquals(second.offers().size(), state().holds().size());
        problem(holds().select(tenant, kept.offerId(), requestOnly()), 409, ErrorCode.HOLD_UNAVAILABLE);
    }

    @Test void confirmingTurnsTheSelectedHoldIntoTheJobsAppointment() {
        OfferSet set = offerSet(offers().create(tenant, request()));
        Hold kept = hold(holds().select(tenant, offer(set, 0).offerId(), requestOnly()));
        String body = confirmBody();
        var reply = confirms().confirm(tenant, kept.holdId(), body);
        CommitReceipt receipt = receipt(reply);
        Assignment job = assignment(receipt, "job-311");
        assertEquals("tech-1", job.technicianId(), "only tech-1 installs softeners");
        assertEquals(DAY, job.serviceDate());
        assertFalse(job.plannedStart().isBefore(offer(set, 0).window().start()));
        assertFalse(job.plannedStart().isAfter(offer(set, 0).window().end()));
        assertEquals(job.plannedStart().plusSeconds(90 * 60), job.plannedEnd());
        assertEquals(new PublicTypes.TechnicianDayVersion("tech-1", DAY, Required.value(Instant.parse("2026-10-11T21:04:17.123456Z"))),
                receipt.technicianDays().stream().filter(day -> Required.value(day).technicianId().equals("tech-1")).findFirst().orElseThrow());
        for (var day : receipt.technicianDays())
            assertTrue(receipt.assignments().stream().anyMatch(item -> Required.value(item).technicianId().equals(day.technicianId())), "a listed day lists its appointments");
        assertEquals("CONFIRMED", statusesByOffer(set).get(kept.offerId()));
        assertEquals("CONFIRMED", setStatus(set.offerSetId()));
        assertTrue(state().holds().isEmpty());
        assertTrue(Required.value(state().technicians().get("tech-1")).expected().contains("job-311"), "the scheduler now expects the job on tech-1");
        assertEquals(reply, confirms().confirm(tenant, kept.holdId(), body), "the same requestId replays the receipt");
        assertEquals(receipt, receipt(confirms().confirm(tenant, kept.holdId(), confirmBody())), "confirming again returns the same receipt");
        problem(holds().select(tenant, kept.offerId(), requestOnly()), 409, ErrorCode.HOLD_UNAVAILABLE);
    }

    @Test void writingAReceiptKeepsTheOtherHoldsOfTheDay() {
        OfferSet first = offerSet(offers().create(tenant, forJob("job-a")));
        Hold a = hold(holds().select(tenant, offer(first, 0).offerId(), requestOnly()));
        OfferSet second = offerSet(offers().create(tenant, forJob("job-b")));
        Hold b = hold(holds().select(tenant, offer(second, 0).offerId(), requestOnly()));
        CommitReceipt receipt = receipt(confirms().confirm(tenant, a.holdId(), confirmBody()));
        assertEquals(Set.of(b.holdId()), state().holds().keySet(), "job-b's hold stays after job-a is confirmed");
        CommitReceipt next = receipt(confirms().confirm(tenant, b.holdId(), CalculationJson.write(written(confirmation(request()), receipt, offer(first, 0)))));
        assignment(next, "job-b");
        assertEquals("CONFIRMED", statusesByOffer(second).get(b.offerId()), "the host's written receipt is what the scheduler expected");
        assertTrue(state().holds().isEmpty());
    }

    @Test void confirmRefusesUnknownUnselectedExpiredLostAndMisdirectedHolds() {
        OfferSet set = offerSet(offers().create(tenant, forJob("job-a")));
        String holdId = holdOf(offer(set, 0).offerId());
        String unknown = confirmBody();
        var missing = confirms().confirm(tenant, "0c8b8f0e-1a2b-4c3d-8e9f-0a1b2c3d4e5f", unknown);
        problem(missing, 404, ErrorCode.NOT_FOUND);
        assertEquals(missing, confirms().confirm(tenant, "0c8b8f0e-1a2b-4c3d-8e9f-0a1b2c3d4e5f", unknown), "a 404 is stored and replayed");
        problem(confirms().confirm(other, holdId, confirmBody()), 404, ErrorCode.NOT_FOUND);
        assertTrue(problem(confirms().confirm(tenant, holdId, confirmBody()), 409, ErrorCode.HOLD_UNAVAILABLE).message().contains("Select"));

        long requests = count("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", tenant);
        problem(confirms().confirm(tenant, holdId, "{\"requestId\":"), 400, ErrorCode.INVALID_REQUEST);
        problem(confirms().confirm(tenant, " ", confirmBody()), 400, ErrorCode.INVALID_REQUEST);
        ObjectNode twoDates = confirmation(request());
        object(((ArrayNode) Required.value(object(twoDates.get("snapshot")).get("technicianDays"))).get(1)).put("serviceDate", "2026-10-13");
        problem(confirms().confirm(tenant, holdId, CalculationJson.write(twoDates)), 400, ErrorCode.INVALID_REQUEST);
        ObjectNode withJob = confirmation(request());
        withJob.putObject("job").put("id", "job-a");
        problem(confirms().confirm(tenant, holdId, CalculationJson.write(withJob)), 400, ErrorCode.INVALID_REQUEST);
        assertEquals(requests, count("SELECT count(*) FROM api_request WHERE \"tenantId\"=?", tenant), "a malformed confirm claims nothing");

        hold(holds().select(tenant, offer(set, 0).offerId(), requestOnly()));
        ObjectNode lincoln = confirmation(request());
        object(lincoln.get("snapshot")).put("metroId", "lincoln");
        assertTrue(problem(confirms().confirm(tenant, holdId, CalculationJson.write(lincoln)), 400, ErrorCode.INVALID_REQUEST).message().contains("metro"));
        Instant issued = now.get();
        now.set(Required.value(issued.plusSeconds(601)));
        assertTrue(problem(confirms().confirm(tenant, holdId, confirmBody()), 409, ErrorCode.HOLD_UNAVAILABLE).message().contains("expired"));
        now.set(issued);
        String changed = CalculationJson.write(confirmation(dispatcherChange("job-a")));
        assertTrue(problem(confirms().confirm(tenant, holdId, changed), 409, ErrorCode.HOLD_UNAVAILABLE).message().contains("lost"));
        assertEquals("LOST", statusesByOffer(set).get(offer(set, 0).offerId()), "the lost hold is recorded");
        assertTrue(state().holds().isEmpty());
        assertEquals(List.of("appt-7", "appt-8", "appt-9"), Required.value(state().technicians().get("tech-1")).expected(), "the day restarts from the host's routes");
    }

    @Test void aRoutingOutageReleasesTheConfirmForARetry() {
        OfferSet set = offerSet(offers().create(tenant, request()));
        Hold kept = hold(holds().select(tenant, offer(set, 0).offerId(), requestOnly()));
        String body = confirmBody();
        routingDown.set(true);
        problem(confirms().confirm(tenant, kept.holdId(), body), 503, ErrorCode.ROUTING_UNAVAILABLE);
        routingDown.set(false);
        assignment(receipt(confirms().confirm(tenant, kept.holdId(), body)), "job-311");
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
        offerSet(offers().create(tenant, dispatcherChange("job-b")));
        assertTrue(offerStatuses(held.offerSetId()).values().stream().allMatch("LOST"::equals));
        for (var hold : state().holds().values()) assertEquals("job-b", hold.jobId());
        assertEquals(List.of("appt-7", "appt-8", "appt-9"), Required.value(state().technicians().get("tech-1")).expected());
    }

    /** A dispatcher adds an appointment to tech-1, whose route holds the earlier jobs' slots; then {@code job} searches. */
    private static String dispatcherChange(String job) {
        return request(request -> {
            object(request.get("job")).put("id", job);
            ObjectNode tech1 = object(((ArrayNode) Required.value(object(request.get("snapshot")).get("technicianDays"))).get(0));
            tech1.put("lastModified", "2026-10-11T22:00:00Z");
            appointments(request).addObject().put("id", "appt-9").put("technicianId", "tech-1").put("serviceDate", "2026-10-12")
                    .put("serviceId", "softener-service").put("durationMinutes", 30).put("sequence", 2).put("plannedStart", "2026-10-12T15:00:00-05:00")
                    .<ObjectNode>set("window", object(CalculationJson.tree("{\"start\":\"2026-10-12T14:00:00-05:00\",\"end\":\"2026-10-12T16:00:00-05:00\"}")))
                    .putObject("location").put("lat", 41.27).put("lng", -95.95);
        });
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
