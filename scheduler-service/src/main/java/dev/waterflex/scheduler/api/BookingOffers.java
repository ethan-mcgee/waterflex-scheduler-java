package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.BookingCalculation;
import dev.waterflex.scheduler.BookingSearchPipeline;
import dev.waterflex.scheduler.BookingSnapshot;
import dev.waterflex.scheduler.BoundedBookingSearch;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.CalculationTransport;
import dev.waterflex.scheduler.HttpCalculation;
import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.ReservationOffers;
import dev.waterflex.scheduler.ReservationState;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.ScheduleCutoff;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.SearchDeadline;
import dev.waterflex.scheduler.SnapshotRouting;
import dev.waterflex.scheduler.api.BookingDayState.HoldState;
import dev.waterflex.scheduler.api.BookingDayState.TechnicianState;
import dev.waterflex.scheduler.api.BookingStore.DayKey;
import dev.waterflex.scheduler.api.BookingStore.StoredDay;
import dev.waterflex.scheduler.api.DailyProposals.Reply;
import dev.waterflex.scheduler.api.PublicApiStore.*;
import dev.waterflex.scheduler.api.PublicRequests.BookingOffersRequest;
import dev.waterflex.scheduler.api.PublicResponses.*;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * POST /api/v1/booking/offers. Parses strictly, claims the request ID, reconciles each date's stored holds with the
 * host's snapshot, runs the portal's bounded booking search on the host's horizon, and publishes up to the offer limit
 * with a 10 minute hold each. See "Booking (Decided 2026-10-08)" in docs/stateless-api-design.md.
 */
@Service
public class BookingOffers {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(BookingOffers.class));
    /** The portal's offer expiry. */
    static final Duration HOLD = Required.value(Duration.ofMinutes(10));
    private static final Set<String> VARIANTS = Required.value(Set.of("INSERTION", "BOUNDED", "EXPANDED", "RUIN_RECREATE", "SHARED"));

    /** A request that will fail the same way every time it is repeated. */
    private static final class Final extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;
        final ErrorCode code;
        Final(int status, ErrorCode code, String message) { super(message); this.status = status; this.code = code; }
    }

    private final PublicApiStore store;
    private final BookingStore bookings;
    private final MetroRouting routing;
    private final DailyPreparation.AddressLocator locator;
    private final CalculationTransport transport;
    private final SearchAdmission admission;
    private final JdbcTemplate jdbc;
    private final String variant;
    private final int refinementMillis;
    private final Clock clock;

    @Autowired
    public BookingOffers(PublicApiStore store, BookingStore bookings, MetroRouting routing, AddressLocation locator, CalculationTransport transport,
                         SearchAdmission admission, JdbcTemplate jdbc,
                         @Value("${booking.search.variant:BOUNDED}") String variant, @Value("${booking.search.refinement-ms:250}") int refinementMillis) {
        this(store, bookings, routing, locator, transport, admission, jdbc, variant, refinementMillis, Required.value(Clock.systemUTC()));
    }

    public BookingOffers(PublicApiStore store, BookingStore bookings, MetroRouting routing, DailyPreparation.AddressLocator locator, CalculationTransport transport,
                         SearchAdmission admission, JdbcTemplate jdbc, String variant, int refinementMillis, Clock clock) {
        if (!VARIANTS.contains(variant)) throw new IllegalArgumentException("Unknown booking search variant " + variant);
        if (refinementMillis < 0 || refinementMillis > 1000) throw new IllegalArgumentException("Invalid optional refinement budget");
        this.store = store; this.bookings = bookings; this.routing = routing; this.locator = locator; this.transport = transport;
        this.admission = admission; this.jdbc = jdbc; this.variant = variant;
        this.refinementMillis = refinementMillis; this.clock = clock;
    }

    public Reply create(String tenantId, String body) {
        BookingOffersRequest request;
        try { request = PublicRequests.read(body, BookingOffersRequest.class); }
        catch (IllegalArgumentException invalid) { return Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, DailyProposals.invalidRequest(invalid))); }
        return switch (store.claim(tenantId, request.requestId(), Operation.BOOKING_OFFERS, DailyProposals.sha256(CalculationJson.write(request)))) {
            case Replay replay -> replayed(replay);
            case Conflict _ -> Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, "requestId was already used with a different request"));
            case Busy _ -> Reply.of(429, new Problem(ErrorCode.BUSY, "A request with this requestId is still running"));
            case Started started -> run(tenantId, request, started.ownerToken());
        };
    }

    private static Reply replayed(Replay replay) {
        Object body;
        try { body = replay.status() == 201 ? PublicRequests.read(replay.json(), OfferSet.class) : PublicRequests.read(replay.json(), Problem.class); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("A stored response failed validation", invalid); }
        return Reply.of(replay.status(), body);
    }

    private Reply run(String tenantId, BookingOffersRequest request, String owner) {
        try {
            SchedulingPolicy.Rules shared = DailyProposals.sharedPolicy(jdbc);
            SearchDeadline deadline = new SearchDeadline(Required.value(Duration.ofMillis(shared.bookingDeadlineMs())));
            OfferSet offers = deadline.within(() -> {
                try (var _ = admission.acquire(SearchAdmission.Kind.BOOKING, deadline)) {
                    try { return search(tenantId, request, owner, shared); }
                    catch (BookingStore.DayMoved moved) {
                        // One retry within the same deadline, as the portal does after a concurrent change.
                        LOG.info("Retrying booking search for job {}: {}", request.job().id(), moved.getMessage());
                        SearchDeadline.beginExploration();
                        return search(tenantId, request, owner, shared);
                    }
                }
            });
            return Reply.of(201, offers);
        } catch (Final failure) {
            Problem problem = new Problem(failure.code, Required.value(failure.getMessage()));
            store.completeWithError(tenantId, request.requestId(), owner, failure.status, problem);
            return Reply.of(failure.status, problem);
        } catch (RoadClient.RoadUnavailable unavailable) {
            store.release(tenantId, request.requestId(), owner);
            return Reply.of(503, new Problem(ErrorCode.ROUTING_UNAVAILABLE, "Road routing unavailable"));
        } catch (SearchAdmission.Busy | BookingStore.DayMoved busy) {
            store.release(tenantId, request.requestId(), owner);
            return Reply.of(429, new Problem(ErrorCode.BUSY, busy instanceof BookingStore.DayMoved
                    ? "Other bookings kept changing these dates; retry" : "Search capacity exhausted"));
        } catch (SearchDeadline.Expired | HttpCalculation.Unavailable expired) {
            store.release(tenantId, request.requestId(), owner);
            return Reply.of(503, new Problem(ErrorCode.CALCULATION_UNAVAILABLE, "The booking search found nothing before its deadline"));
        } catch (RuntimeException unexpected) {
            store.release(tenantId, request.requestId(), owner);
            throw unexpected;
        }
    }

    /** The reconciled state of one date, and how it was read. */
    private record Reconciled(DayKey key, StoredDay stored, BookingReconciliation.Result result) { }

    private OfferSet search(String tenantId, BookingOffersRequest request, String owner, SchedulingPolicy.Rules shared) {
        PublicTypes.Snapshot facts = request.snapshot();
        Instant now = Required.value(clock.instant());
        ZoneId zone = Required.value(ZoneId.of(facts.timeZone()));
        for (LocalDate date = request.horizon().firstDate(); !date.isAfter(request.horizon().lastDate()); date = Required.value(date.plusDays(1)))
            if (ScheduleCutoff.frozen(date, now, zone)) throw new Final(422, ErrorCode.INCOMPLETE_FACTS, "Routes for " + date + " are frozen from 6 a.m. local time; book from a later date");
        RoadClient roads;
        try { roads = routing.client(facts.metroId()); }
        catch (MetroRouting.UnknownMetro unknown) { throw new Final(422, ErrorCode.INCOMPLETE_FACTS, Required.value(unknown.getMessage())); }
        String identity = roads.activeIdentity();
        RequestBooking.Built built;
        try { built = RequestBooking.build(request, shared, identity, now, locator); }
        catch (RequestBooking.JobUnlocatable | RequestBooking.Contradictory refused) { throw new Final(422, ErrorCode.INCOMPLETE_FACTS, Required.value(refused.getMessage())); }
        BoundedBookingSearch.Request job = built.request();
        String metro = facts.metroId();

        // Reconcile every horizon date, and end this job's holds on any other date.
        Map<LocalDate, StoredDay> stored = bookings.days(tenantId, metro, built.snapshot().horizon());
        Map<LocalDate, Reconciled> days = new TreeMap<>();
        for (var entry : stored.entrySet()) {
            LocalDate date = Required.value(entry.getKey());
            BookingSnapshot.Day host = Required.value(built.snapshot().days().get(date), "horizon day");
            days.put(date, new Reconciled(new DayKey(metro, date), Required.value(entry.getValue()),
                    BookingReconciliation.reconcile(Required.value(entry.getValue()).state(), host, job.jobId(), now)));
        }
        Map<DayKey, Integer> read = new HashMap<>();
        Map<DayKey, BookingDayState> writes = new HashMap<>();
        Map<String, BookingReconciliation.Ending> ended = new TreeMap<>();
        for (var entry : bookings.heldBy(tenantId, job.jobId()).entrySet()) {
            DayKey key = Required.value(entry.getKey());
            if (key.metroId().equals(metro) && days.containsKey(key.serviceDate())) continue;
            StoredDay elsewhere = Required.value(bookings.days(tenantId, key.metroId(), Required.value(List.of(key.serviceDate()))).get(key.serviceDate()));
            BookingDayState state = elsewhere.state();
            read.put(key, elsewhere.version());
            if (state == null) continue;
            Set<String> superseded = new java.util.TreeSet<>(Required.value(entry.getValue()));
            superseded.retainAll(state.holds().keySet());
            superseded.forEach(hold -> ended.put(hold, BookingReconciliation.Ending.SUPERSEDED));
            if (!superseded.isEmpty()) writes.put(key, state.without(superseded));
        }

        // Route the reconciled days; a date whose holds no longer fit loses them and is routed again without them.
        BookingSnapshot routed = route(roads, built, days, job);
        boolean refit = false;
        for (var entry : days.entrySet()) {
            Reconciled day = Required.value(entry.getValue());
            if (day.result().holds().isEmpty()) continue;
            BookingSnapshot.Day placed = Required.value(routed.days().get(entry.getKey()));
            if (placed.evaluate(placed.baseline(), placed.visits(), routed.rates()).feasible()) continue;
            BookingSnapshot.Day host = Required.value(built.snapshot().days().get(entry.getKey()), "horizon day");
            entry.setValue(new Reconciled(day.key(), day.stored(), BookingReconciliation.infeasible(day.result(), host)));
            refit = true;
        }
        if (refit) routed = route(roads, built, days, job);
        if (!identity.equals(roads.activeIdentity())) throw new RoadClient.RoadUnavailable("Routing identity changed");

        Searched searched = calculate(roads, routed, job);
        // Validation and offers use every leg the search fetched, including the refinement's neighborhoods.
        routed = searched.snapshot();
        BoundedBookingSearch.Result result = searched.result();
        SearchDeadline.beginCommit();
        BookingCalculation.validate(routed, job, result);
        Instant expiresAt = Required.value(now.truncatedTo(ChronoUnit.MILLIS).plus(HOLD));
        Map<LocalDate, Map<String, ReservationState.Hold>> holds = new TreeMap<>();
        days.forEach((date, day) -> holds.put(date, day.result().holds()));
        var bundle = ReservationOffers.prepare(routed, job, holds, result, expiresAt, request.offerLimit(), SearchDeadline::checkpoint);

        String offerSetId = BookingStore.newOfferSetId();
        List<BookingStore.NewOffer> newOffers = new ArrayList<>();
        List<Offer> offers = new ArrayList<>();
        Map<LocalDate, Map<String, HoldState>> newHolds = new TreeMap<>();
        for (ReservationOffers.Reserved reserved : bundle.offers()) {
            var candidate = reserved.candidate();
            LocalDate date = candidate.window().day();
            ReservationOffers.Prepared prepared = Required.value(bundle.dates().get(date), "prepared offer date");
            Instant arrival = Required.value(prepared.validation().arrivals().get(reserved.holdId()), "reserved arrival");
            String technician = technicianOf(prepared.day().baseline(), reserved.holdId());
            var location = job.location();
            newHolds.computeIfAbsent(date, _ -> new TreeMap<>()).put(reserved.holdId(), new HoldState(job.jobId(), reserved.offerId(), offerSetId, expiresAt,
                    job.serviceId(), candidate.window().start(), candidate.window().end(), job.durationMinutes(), location.lat(), location.lng(), arrival, technician));
            newOffers.add(new BookingStore.NewOffer(reserved.offerId(), reserved.holdId(), date, technician, candidate.window().start(), candidate.window().end()));
            offers.add(new Offer(reserved.offerId(), date, new PublicTypes.Window(candidate.window().start(), candidate.window().end())));
        }
        offers.sort(java.util.Comparator.comparing((Offer offer) -> offer.window().start()).thenComparing(offer -> offer.offerId()));

        for (var entry : days.entrySet()) {
            LocalDate date = Required.value(entry.getKey());
            Reconciled day = Required.value(entry.getValue());
            ended.putAll(day.result().ended());
            ReservationOffers.Prepared prepared = bundle.dates().get(date);
            BookingDayState next = prepared == null ? day.result().state()
                    : withOffers(day.result().state(), prepared.day().baseline(), Required.value(newHolds.getOrDefault(date, Required.value(Map.of()))));
            read.put(day.key(), day.stored().version());
            if (!next.equals(day.stored().state())) writes.put(day.key(), next);
        }
        var body = new OfferSet(offerSetId, expiresAt, Required.value(List.copyOf(offers)), result.complete() && bundle.completed(), built.skipped());
        SearchDeadline.beforeCommit();
        bookings.publish(tenantId, owner, read, writes, ended, new BookingStore.Publication(offerSetId, request.requestId(), job.jobId(), metro, expiresAt, body, newOffers));
        return body;
    }

    private static BookingSnapshot route(RoadClient roads, RequestBooking.Built built, Map<LocalDate, Reconciled> days, BoundedBookingSearch.Request job) {
        BookingSnapshot host = built.snapshot();
        Map<LocalDate, BookingSnapshot.Day> placed = new TreeMap<>();
        days.forEach((date, day) -> placed.put(date, day.result().day()));
        var snapshot = new BookingSnapshot(host.metroId(), host.capturedAt(), host.calendarReference(), host.configurationFingerprint(), host.routingIdentity(),
                host.policy(), host.rates(), placed, host.horizon());
        return new SnapshotRouting(roads).insertion(snapshot, job);
    }

    /** A search result and the snapshot holding every leg it was found with. */
    private record Searched(BookingSnapshot snapshot, BoundedBookingSearch.Result result) { }

    /** The portal pipeline's insertion and optional bounded refinement (BookingSearchPipeline.prepare), without its overflow dates. */
    private Searched calculate(RoadClient roads, BookingSnapshot snapshot, BoundedBookingSearch.Request job) {
        var inserted = transport.booking(new BookingCalculation.Input(snapshot, job, BookingCalculation.Stage.INSERTION, variant,
                Required.value(Set.of()), null, 0));
        BoundedBookingSearch.Result result = inserted.result();
        if (variant.equals("INSERTION") || "DEADLINE".equals(result.stopReason())) return new Searched(snapshot, result);
        boolean optional = result.complete() && result.distinctRegularWindows() > snapshot.policy().regularWindowThreshold();
        long started = System.nanoTime();
        Runnable checkpoint = () -> {
            SearchDeadline.checkpoint();
            if (optional && (System.nanoTime() - started) / 1_000_000 >= refinementMillis) throw new BoundedBookingSearch.RefinementLimit();
        };
        BookingSnapshot widened = snapshot;
        try {
            checkpoint.run();
            widened = new SnapshotRouting(roads).neighborhoods(snapshot, inserted.neighborhoods(), checkpoint);
            int remaining = optional ? Math.max(1, refinementMillis - (int) ((System.nanoTime() - started) / 1_000_000)) : 0;
            var refined = transport.booking(new BookingCalculation.Input(widened, job, BookingCalculation.Stage.REFINEMENT, variant,
                    Required.value(Set.of()), result, remaining));
            return new Searched(widened, BookingSearchPipeline.combine(result, refined.result(), snapshot.policy()));
        } catch (BoundedBookingSearch.RefinementLimit limit) { return new Searched(widened, incomplete(result, "REFINEMENT_TIME_LIMIT")); }
        catch (SearchDeadline.Expired expired) { return new Searched(widened, incomplete(result, "DEADLINE")); }
        catch (RoadClient.RoadUnavailable unavailable) { return new Searched(widened, incomplete(result, "ROUTING_UNAVAILABLE")); }
        catch (HttpCalculation.Unavailable unavailable) { return new Searched(widened, incomplete(result, "REFINEMENT_UNAVAILABLE")); }
    }

    private static BoundedBookingSearch.Result incomplete(BoundedBookingSearch.Result result, String reason) {
        return new BoundedBookingSearch.Result(result.candidates(), result.coverage(), false, result.distinctRegularWindows(),
                result.confirmedRegularMinutes(), result.regularCapacityMinutes(), false, reason);
    }

    /** The day after publishing: the prepared common arrangement, its earlier holds (perhaps moved) and the new ones. */
    private static BookingDayState withOffers(BookingDayState reconciled, BookingSnapshot.Arrangement arrangement, Map<String, HoldState> added) {
        Map<String, TechnicianState> technicians = new TreeMap<>();
        reconciled.technicians().forEach((id, technician) -> technicians.put(id, new TechnicianState(technician.version(), technician.expected(),
                Required.value(arrangement.routes().get(id), "prepared route"))));
        Map<String, HoldState> holds = new HashMap<>();
        reconciled.holds().forEach((id, hold) -> {
            String technician = technicianOf(arrangement, Required.value(id));
            holds.put(id, technician.equals(hold.technicianId()) ? hold : new HoldState(hold.jobId(), hold.offerId(), hold.offerSetId(), hold.expiresAt(),
                    hold.serviceId(), hold.windowStart(), hold.windowEnd(), hold.durationMinutes(), hold.lat(), hold.lng(), hold.plannedStart(), technician));
        });
        holds.putAll(added);
        return new BookingDayState(technicians, holds);
    }

    private static String technicianOf(BookingSnapshot.Arrangement arrangement, String visit) {
        @Nullable String found = null;
        for (var route : arrangement.routes().entrySet()) if (Required.value(route.getValue()).contains(visit)) found = route.getKey();
        return Required.value(found, "technician of " + visit);
    }
}
