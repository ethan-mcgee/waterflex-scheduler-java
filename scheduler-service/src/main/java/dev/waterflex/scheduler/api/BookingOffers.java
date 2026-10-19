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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * POST /api/v1/booking/offers. Parses strictly, claims the request ID, reconciles each date's stored holds with the
 * host's snapshot, runs the bounded booking search on the host's horizon, and publishes up to the offer limit
 * with a 10 minute hold each. See "Booking (Decided 2026-10-08)" in docs/stateless-api-design.md.
 */
@Service
public class BookingOffers {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(BookingOffers.class));
    /** How long an offer holds its slot. */
    static final Duration HOLD = Required.value(Duration.ofMinutes(10));
    private static final Set<String> VARIANTS = Required.value(Set.of("INSERTION", "BOUNDED", "EXPANDED", "RUIN_RECREATE", "SHARED"));
    /** Searches per request when other bookings keep changing its dates, all within the one deadline. */
    static final int ATTEMPTS = 3;

    /** A request that will fail the same way every time it is repeated. */
    private static final class Final extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;
        final ErrorCode code;
        Final(int status, ErrorCode code, String message) { super(message); this.status = status; this.code = code; }
    }

    /** The snapshot is older than records already used: a final answer, as {@link Final} is. */
    private static final class Stale extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient StaleProblem problem;
        Stale(StaleProblem problem) { super(problem.message(), null, false, false); this.problem = problem; }
    }

    private final PublicApiStore store;
    private final BookingStore bookings;
    private final MetroRouting routing;
    private final DailyPreparation.AddressLocator locator;
    private final CalculationTransport transport;
    private final SearchAdmission admission;
    private final RunningRequests running;
    private final String variant;
    private final int refinementMillis;
    /** The part of a client's deadline kept back from exploration to validate, prepare and publish the offers. */
    private final Duration publishReserve;
    private final Clock clock;
    /** Refinement results that failed validation since this process started; each one kept the insertion offers. */
    private final LongAdder invalidRefinements = new LongAdder();
    private final LongAdder shedRefinements = new LongAdder();
    /** The largest optional refinement budget, for the operator's default and a client's own. */
    static final int MAX_REFINEMENT_MS = BookingCalculation.MAX_REFINEMENT_MS;
    /**
     * Bounds of the publish reserve, in milliseconds. Publishing takes a database connection, and the connection guard
     * (SearchDatabaseTelemetry) refuses one with less than the pool's connection plus validation timeout left (250 + 250);
     * a search that explores to the end of its time reaches publish with only the reserve, so a smaller reserve answers
     * every such search BUSY. The floor is that guard plus the slowest validation and preparation measured (270 ms p95,
     * 30 technicians on full schedules, booking configuration study). The ceiling is the second every search kept before
     * the reserve became a setting, so a deadline of 1000 ms still leaves no exploration.
     */
    static final int MIN_PUBLISH_RESERVE_MS = 800, MAX_PUBLISH_RESERVE_MS = 1000;

    @Autowired
    public BookingOffers(PublicApiStore store, BookingStore bookings, MetroRouting routing, AddressLocation locator, CalculationTransport transport,
                         SearchAdmission admission, RunningRequests running,
                         @Value("${booking.search.variant:BOUNDED}") String variant, @Value("${booking.search.refinement-ms:250}") int refinementMillis,
                         @Value("${booking.search.publish-reserve-ms:1000}") int publishReserveMillis) {
        this(store, bookings, routing, locator, transport, admission, running, variant, refinementMillis, publishReserveMillis, Required.value(Clock.systemUTC()));
    }

    /** The one second publish reserve every search had before it became a setting. */
    public BookingOffers(PublicApiStore store, BookingStore bookings, MetroRouting routing, DailyPreparation.AddressLocator locator, CalculationTransport transport,
                         SearchAdmission admission, RunningRequests running, String variant, int refinementMillis, Clock clock) {
        this(store, bookings, routing, locator, transport, admission, running, variant, refinementMillis, MAX_PUBLISH_RESERVE_MS, clock);
    }

    public BookingOffers(PublicApiStore store, BookingStore bookings, MetroRouting routing, DailyPreparation.AddressLocator locator, CalculationTransport transport,
                         SearchAdmission admission, RunningRequests running, String variant, int refinementMillis, int publishReserveMillis, Clock clock) {
        if (!VARIANTS.contains(variant)) throw new IllegalArgumentException("Unknown booking search variant " + variant);
        if (refinementMillis < 0 || refinementMillis > MAX_REFINEMENT_MS) throw new IllegalArgumentException("Invalid optional refinement budget");
        if (publishReserveMillis < MIN_PUBLISH_RESERVE_MS || publishReserveMillis > MAX_PUBLISH_RESERVE_MS)
            throw new IllegalArgumentException("booking.search.publish-reserve-ms must be " + MIN_PUBLISH_RESERVE_MS + " to " + MAX_PUBLISH_RESERVE_MS);
        this.store = store; this.bookings = bookings; this.routing = routing; this.locator = locator; this.transport = transport;
        this.admission = admission; this.running = running; this.variant = variant;
        this.refinementMillis = refinementMillis; this.publishReserve = Required.value(Duration.ofMillis(publishReserveMillis)); this.clock = clock;
    }

    /** The publish reserve this service keeps back from every booking search's exploration. */
    public Duration publishReserve() { return publishReserve; }

    public Reply create(String tenantId, String body) {
        BookingOffersRequest request;
        try { request = PublicRequests.read(body, BookingOffersRequest.class); }
        catch (IllegalArgumentException invalid) { return Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, DailyProposals.invalidRequest(invalid))); }
        dev.waterflex.scheduler.RequestLogging.requestId(request.requestId());
        return switch (store.claim(tenantId, request.requestId(), Operation.BOOKING_OFFERS, DailyProposals.sha256(CalculationJson.write(request)))) {
            case Replay replay -> replayed(replay);
            case Conflict _ -> Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, "requestId was already used with a different request"));
            case Busy _ -> Reply.of(429, new Problem(ErrorCode.BUSY, "A request with this requestId is still running"));
            case Started started -> run(tenantId, request, started.ownerToken());
        };
    }

    private static Reply replayed(Replay replay) {
        Object body;
        try {
            body = replay.status() == 201 ? PublicRequests.read(replay.json(), OfferSet.class)
                    : CalculationJson.tree(replay.json()).has("changed") ? PublicRequests.read(replay.json(), StaleProblem.class)
                    : PublicRequests.read(replay.json(), Problem.class);
        }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("A stored response failed validation", invalid); }
        return Reply.of(replay.status(), body);
    }

    private Reply run(String tenantId, BookingOffersRequest request, String owner) {
        RunningRequests.Running registered;
        try { registered = running.start(tenantId, request.requestId()); }
        catch (RunningRequests.AlreadyRunning already) {
            store.release(tenantId, request.requestId(), owner);
            return Reply.of(429, new Problem(ErrorCode.BUSY, Required.value(already.getMessage())));
        }
        try {
            // The client's own booking deadline bounds the whole search, admission and publication included; the operator's
            // publish reserve is the part of it kept back from exploration for validating, preparing and publishing.
            SearchDeadline deadline = new SearchDeadline(Required.value(Duration.ofMillis(request.snapshot().policy().bookingDeadlineMs())), publishReserve);
            registered.attach(deadline);
            OfferSet offers = deadline.within(() -> {
                // A request refused for its facts, or a late snapshot, is answered before it waits for a search slot. The
                // search checks again in its slot, since other bookings may move the dates while it waits.
                prepare(tenantId, request);
                try (var _ = admission.acquireBooking(deadline, tenantId)) {
                    for (int attempt = 1; ; attempt++) {
                        try { return search(tenantId, request, owner); }
                        catch (BookingStore.DayMoved moved) {
                            // Search again on the new state while the deadline leaves time to explore; the routes already
                            // fetched are cached, so a retry costs less than the first search.
                            if (attempt == ATTEMPTS || deadline.explorationNanos() == 0) throw moved;
                            LOG.info("Retrying booking search for job {} (attempt {}): {}", request.job().id(), attempt + 1, moved.getMessage());
                            SearchDeadline.beginExploration();
                        }
                    }
                }
            });
            return Reply.of(201, offers);
        } catch (Final failure) {
            Problem problem = new Problem(failure.code, Required.value(failure.getMessage()));
            store.completeWithError(tenantId, request.requestId(), owner, failure.status, problem);
            return Reply.of(failure.status, problem);
        } catch (Stale stale) {
            store.completeStale(tenantId, request.requestId(), owner, stale.problem);
            return Reply.of(409, stale.problem);
        } catch (RoadClient.RoadUnavailable unavailable) {
            store.release(tenantId, request.requestId(), owner);
            return Reply.of(503, DailyProposals.routingUnavailable(unavailable));
        } catch (SearchAdmission.Busy | BookingStore.DayMoved busy) {
            store.release(tenantId, request.requestId(), owner);
            return Reply.of(429, new Problem(ErrorCode.BUSY, busy instanceof BookingStore.DayMoved
                    ? "Other bookings kept changing these dates; retry" : "Search capacity exhausted"));
        } catch (SearchDeadline.Expired | HttpCalculation.Unavailable expired) {
            store.release(tenantId, request.requestId(), owner);
            if (registered.cancelled()) return Reply.of(409, DailyProposals.cancelled());
            return Reply.of(503, new Problem(ErrorCode.CALCULATION_UNAVAILABLE, "The booking search found nothing before its deadline"));
        } catch (RuntimeException unexpected) {
            store.release(tenantId, request.requestId(), owner);
            throw unexpected;
        } finally {
            running.finish(tenantId, request.requestId(), registered);
        }
    }

    /** The reconciled state of one date, and how it was read. */
    private record Reconciled(DayKey key, StoredDay stored, BookingReconciliation.Result result) { }

    /** A request's facts as built for the search, and the stored state of its horizon dates, which it does not predate. */
    private record Prepared(Instant now, RoadClient roads, String identity, RequestBooking.Built built, Map<LocalDate, StoredDay> stored) { }

    /** Builds the request's facts and reads its dates' stored state; throws {@link Final} or {@link Stale} when it is refused. */
    private Prepared prepare(String tenantId, BookingOffersRequest request) {
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
        try { built = RequestBooking.build(request, identity, now, locator); }
        catch (RequestBooking.JobUnlocatable | RequestBooking.Contradictory refused) { throw new Final(422, ErrorCode.INCOMPLETE_FACTS, Required.value(refused.getMessage())); }
        Map<LocalDate, StoredDay> stored = bookings.days(tenantId, facts.metroId(), built.snapshot().horizon());
        // A late snapshot, older than one already used for a date, would read as a host change and lose holds.
        List<PublicTypes.TechnicianDayVersion> outdated = new ArrayList<>();
        stored.forEach((date, day) -> {
            var stale = BookingReconciliation.stale(Required.value(day).state(), Required.value(built.snapshot().days().get(date), "horizon day"), now);
            if (!stale.isEmpty()) outdated.addAll(BookingReconciliation.staleProblem(Required.value(date), stale).changed());
        });
        if (!outdated.isEmpty()) throw new Stale(BookingReconciliation.staleProblem(Required.value(List.copyOf(outdated))));
        return new Prepared(now, roads, identity, built, stored);
    }

    private OfferSet search(String tenantId, BookingOffersRequest request, String owner) {
        Prepared ready = prepare(tenantId, request);
        Instant now = ready.now();
        RoadClient roads = ready.roads();
        String identity = ready.identity();
        RequestBooking.Built built = ready.built();
        BoundedBookingSearch.Request job = built.request();
        String metro = request.snapshot().metroId();

        // Reconcile every horizon date, and end this job's holds on any other date.
        Map<LocalDate, StoredDay> stored = ready.stored();
        Map<LocalDate, Reconciled> days = new TreeMap<>();
        for (var entry : stored.entrySet()) {
            LocalDate date = Required.value(entry.getKey());
            BookingSnapshot.Day host = Required.value(built.snapshot().days().get(date), "horizon day");
            days.put(date, new Reconciled(new DayKey(metro, date), Required.value(entry.getValue()),
                    BookingReconciliation.reconcile(Required.value(entry.getValue()).state(), host, job.jobId(), now)));
        }
        Map<DayKey, BookingStore.DayWrite> writes = new HashMap<>();
        Set<String> heldBefore = new java.util.TreeSet<>();
        for (var entry : bookings.heldBy(tenantId, job.jobId()).entrySet()) {
            DayKey key = Required.value(entry.getKey());
            heldBefore.addAll(Required.value(entry.getValue()));
            if (key.metroId().equals(metro) && days.containsKey(key.serviceDate())) continue;
            StoredDay elsewhere = Required.value(bookings.days(tenantId, key.metroId(), List.of(key.serviceDate())).get(key.serviceDate()));
            BookingDayState state = elsewhere.state();
            if (state == null) continue;
            Set<String> superseded = new java.util.TreeSet<>(Required.value(entry.getValue()));
            superseded.retainAll(state.holds().keySet());
            Map<String, BookingReconciliation.Ending> ended = new TreeMap<>();
            superseded.forEach(hold -> ended.put(hold, BookingReconciliation.Ending.SUPERSEDED));
            if (!superseded.isEmpty()) writes.put(key, new BookingStore.DayWrite(elsewhere.version(), state.without(superseded), ended, true));
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

        // A client's own budget replaces the operator's default for its optional refinements.
        Integer budget = request.snapshot().policy().bookingRefinementMs();
        Searched searched = calculate(roads, routed, job, budget == null ? refinementMillis : budget);
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
                    job.serviceId(), candidate.window().start(), candidate.window().end(), job.durationMinutes(), location.lat(), location.lng(), arrival, technician,
                    request.job().location().source()));
            newOffers.add(new BookingStore.NewOffer(reserved.offerId(), reserved.holdId(), date, technician, candidate.window().start(), candidate.window().end()));
            offers.add(new Offer(reserved.offerId(), date, new PublicTypes.Window(candidate.window().start(), candidate.window().end())));
        }
        offers.sort(java.util.Comparator.comparing((Offer offer) -> offer.window().start()).thenComparing(offer -> offer.offerId()));

        for (var entry : days.entrySet()) {
            LocalDate date = Required.value(entry.getKey());
            Reconciled day = Required.value(entry.getValue());
            ReservationOffers.Prepared prepared = bundle.dates().get(date);
            BookingDayState next = prepared == null ? day.result().state()
                    : withOffers(day.result().state(), prepared.day().baseline(), Required.value(newHolds.getOrDefault(date, Required.value(Map.of()))));
            Map<String, BookingReconciliation.Ending> ended = day.result().ended();
            // The stored state is null for a day the store has never written; such a day never equals a computed one.
            if (java.util.Objects.equals(next, day.stored().state())) {
                // Every ending removes a hold from the state, so an unchanged day has none.
                if (!ended.isEmpty()) throw new IllegalStateException("Holds ended on " + date + " without a change of its state");
                continue;
            }
            // The offers depend on the days they hold, and the job's earlier holds must end; any other change only refreshes.
            boolean placed = newHolds.containsKey(date) || ended.containsValue(BookingReconciliation.Ending.SUPERSEDED);
            writes.put(day.key(), new BookingStore.DayWrite(day.stored().version(), next, ended, placed));
        }
        var body = new OfferSet(offerSetId, expiresAt, Required.value(List.copyOf(offers)), searched.complete() && bundle.completed(), built.skipped(),
                searched.refinement(), Required.value(List.copyOf(searched.incompleteDates())), searched.stop(), searched.regularWindowsFound());
        SearchDeadline.beforeCommit();
        bookings.publish(tenantId, owner, writes, heldBefore, new BookingStore.Publication(offerSetId, request.requestId(), job.jobId(), metro, expiresAt, body, newOffers));
        return body;
    }

    private static BookingSnapshot route(RoadClient roads, RequestBooking.Built built, Map<LocalDate, Reconciled> days, BoundedBookingSearch.Request job) {
        BookingSnapshot host = built.snapshot();
        Map<LocalDate, BookingSnapshot.Day> placed = new TreeMap<>();
        days.forEach((date, day) -> placed.put(date, day.result().day()));
        var snapshot = new BookingSnapshot(host.metroId(), host.capturedAt(), host.configurationFingerprint(), host.routingIdentity(),
                host.policy(), host.rates(), placed, host.horizon());
        return new SnapshotRouting(roads).insertion(snapshot, job);
    }

    /**
     * A search result, the snapshot holding every leg it was found with, and how the search ended. {@code refinementRequired}:
     * the insertion pass found no more distinct regular windows than the client's threshold, so the offers are not complete
     * until the refinement finishes too. {@code incompleteDates}: the dates where a pass the offers need did not finish.
     * {@code stop}: what ended a SHORTENED refinement, null for every other outcome. {@code regularWindowsFound}: the
     * insertion pass's distinct regular windows, null when that pass did not finish and the count is only a lower bound.
     */
    record Searched(BookingSnapshot snapshot, BoundedBookingSearch.Result result, Refinement refinement, boolean refinementRequired,
                    SortedSet<LocalDate> incompleteDates, @Nullable RefinementStop stop, @Nullable Integer regularWindowsFound) {
        Searched {
            incompleteDates = Required.value(Collections.unmodifiableSortedSet(new TreeSet<>(incompleteDates)));
            if (stop != null && refinement != Refinement.SHORTENED) throw new IllegalArgumentException("Only a SHORTENED refinement has a stop");
            if (regularWindowsFound != null && regularWindowsFound < 0) throw new IllegalArgumentException("Negative regular window count");
        }

        /**
         * The search part of the public {@code searchComplete}: every date's needed passes finished. An optional refinement
         * cut short by its cap, the deadline, a failure or shedding leaves it true, and {@code refinement} says so.
         */
        boolean complete() { return incompleteDates.isEmpty(); }
    }

    /** The dates holding a window whose coverage did not finish. */
    private static SortedSet<LocalDate> unfinished(BoundedBookingSearch.Result result) {
        SortedSet<LocalDate> dates = new TreeSet<>();
        for (var item : result.coverage()) if (!item.complete()) dates.add(item.window().day());
        return dates;
    }

    /** Every date the search had a window on: a required pass that did not run leaves none of them proven. */
    private static SortedSet<LocalDate> searched(BoundedBookingSearch.Result result) {
        SortedSet<LocalDate> dates = new TreeSet<>();
        for (var item : result.coverage()) dates.add(item.window().day());
        return dates;
    }

    /**
     * The portal pipeline's insertion and optional bounded refinement (BookingSearchPipeline.prepare), without its overflow
     * dates. {@code optionalMillis}: the client's budget for an optional refinement (0 skips it).
     */
    /** Runs one engine stage and attributes its wall time to the call's calculation measurement. */
    private static <T> T timed(java.util.function.Supplier<T> stage) {
        long started = System.nanoTime();
        try { return stage.get(); }
        finally {
            SearchDeadline deadline = SearchDeadline.current();
            if (deadline != null) deadline.telemetry().calculation(System.nanoTime() - started);
        }
    }

    Searched calculate(RoadClient roads, BookingSnapshot snapshot, BoundedBookingSearch.Request job, int optionalMillis) {
        if (optionalMillis < 0 || optionalMillis > MAX_REFINEMENT_MS) throw new IllegalArgumentException("Invalid optional refinement budget");
        var inserted = timed(() -> transport.booking(new BookingCalculation.Input(snapshot, job, BookingCalculation.Stage.INSERTION, variant, null, 0)));
        BoundedBookingSearch.Result result = inserted.result();
        // The count the threshold is compared with; an insertion pass the deadline stopped only knows a lower bound.
        @Nullable Integer found = result.complete() ? result.distinctRegularWindows() : null;
        // The INSERTION variant is the whole search by configuration; nothing more is owed.
        if (variant.equals("INSERTION")) return new Searched(snapshot, result, Refinement.SKIPPED, false, unfinished(result), null, found);
        // With more distinct regular windows than the threshold the offers are already plentiful and the refinement is optional.
        boolean required = result.distinctRegularWindows() <= snapshot.policy().regularWindowThreshold();
        if ("DEADLINE".equals(result.stopReason()))
            return new Searched(snapshot, result, Refinement.SKIPPED, required, required ? searched(result) : unfinished(result), null, found);
        boolean optional = !required;
        SortedSet<LocalDate> inserting = unfinished(result);
        if (optional && optionalMillis == 0) return new Searched(snapshot, incomplete(result, "REFINEMENT_SKIPPED"), Refinement.SKIPPED, false, inserting, null, found);
        if (optional && admission.state().queuedBookings() > 0) {
            // Other customers are waiting for a booking slot: give them this one instead of improving plentiful offers.
            shedRefinements.increment();
            return new Searched(snapshot, incomplete(result, "REFINEMENT_SHED"), Refinement.SHED, false, inserting, null, found);
        }
        // A required refinement that did not return leaves every date unproven; an optional one leaves the insertion pass's.
        SortedSet<LocalDate> unreturned = optional ? inserting : searched(result);
        long started = System.nanoTime();
        Runnable checkpoint = () -> {
            SearchDeadline.checkpoint();
            if (optional && (System.nanoTime() - started) / 1_000_000 >= optionalMillis) throw new BoundedBookingSearch.RefinementLimit();
        };
        BookingSnapshot widened = snapshot;
        try {
            checkpoint.run();
            widened = new SnapshotRouting(roads).neighborhoods(snapshot, inserted.neighborhoods(), checkpoint);
            int remaining = optional ? Math.max(1, optionalMillis - (int) ((System.nanoTime() - started) / 1_000_000)) : 0;
            BoundedBookingSearch.Result refined;
            try {
                BookingCalculation.Input refinement = new BookingCalculation.Input(widened, job, BookingCalculation.Stage.REFINEMENT, variant, result, remaining);
                refined = timed(() -> transport.booking(refinement)).result();
                refined = BookingSearchPipeline.combine(result, refined, snapshot.policy());
            } catch (IllegalArgumentException invalid) {
                // The refinement answered with a plan that fails validation (a remote engine drift or a bug). The insertion
                // result was validated on its own and is validated again before publishing, so it is kept; the refinement's
                // improvements are the only loss. The insertion stage above is not covered: an invalid insertion must fail.
                invalidRefinements.increment();
                LOG.error("Refinement result for job {} failed validation ({} since this process started); keeping the insertion offers: {}",
                        job.jobId(), invalidRefinements.sum(), invalid.getMessage(), invalid);
                return new Searched(widened, incomplete(result, "REFINEMENT_INVALID"), Refinement.UNAVAILABLE, required, unreturned, null, found);
            }
            // The engine stops at its cap or the deadline by returning what it found, marked incomplete window by window.
            return new Searched(widened, refined, refined.complete() ? Refinement.COMPLETE : Refinement.SHORTENED, required,
                    optional ? inserting : unfinished(refined), refined.complete() ? null : stopOf(refined.stopReason()), found);
        } catch (BoundedBookingSearch.RefinementLimit limit) {
            return new Searched(widened, incomplete(result, "REFINEMENT_TIME_LIMIT"), Refinement.SHORTENED, required, unreturned, RefinementStop.CAP, found);
        } catch (SearchDeadline.Expired expired) {
            return new Searched(widened, incomplete(result, "DEADLINE"), Refinement.SHORTENED, required, unreturned, RefinementStop.DEADLINE, found);
        } catch (RoadClient.RoadUnavailable unavailable) {
            return new Searched(widened, incomplete(result, "ROUTING_UNAVAILABLE"), Refinement.UNAVAILABLE, required, unreturned, null, found);
        } catch (HttpCalculation.Unavailable unavailable) {
            return new Searched(widened, incomplete(result, "REFINEMENT_UNAVAILABLE"), Refinement.UNAVAILABLE, required, unreturned, null, found);
        } catch (SearchAdmission.Busy busy) {
            // The refinement's routes could not get a database connection or shared routing capacity in time (a cold
            // search on a large fleet spends most of its deadline fetching legs). The search already holds the insertion
            // pass's offers: publishing them beats answering BUSY, which a retry of the same search would answer again.
            return new Searched(widened, incomplete(result, "REFINEMENT_BUSY"), Refinement.UNAVAILABLE, required, unreturned, null, found);
        }
    }

    /**
     * What ended a refinement the engine returned unfinished: its cap ({@code REFINEMENT_TIME_LIMIT}) or the deadline. The
     * engine stops for nothing else, so any other reason is a protocol drift and is reported as neither.
     */
    private static @Nullable RefinementStop stopOf(String stopReason) {
        return switch (stopReason) {
            case "REFINEMENT_TIME_LIMIT" -> RefinementStop.CAP;
            case "DEADLINE" -> RefinementStop.DEADLINE;
            default -> null;
        };
    }

    /** Optional refinements skipped since this process started because other booking requests were waiting for a slot. */
    public long shedRefinements() { return shedRefinements.sum(); }

    /** How many refinement results failed validation since this process started. */
    /** Remote refinements that failed validation; their searches published the insertion offers. */
    public long invalidRefinements() { return invalidRefinements.sum(); }

    private static BoundedBookingSearch.Result incomplete(BoundedBookingSearch.Result result, String reason) {
        return new BoundedBookingSearch.Result(result.candidates(), result.coverage(), false, result.distinctRegularWindows(),
                result.confirmedRegularMinutes(), result.regularCapacityMinutes(), false, reason);
    }

    /** The day after publishing: the prepared common arrangement, its earlier holds (perhaps moved) and the new ones. */
    private static BookingDayState withOffers(BookingDayState reconciled, BookingSnapshot.Arrangement arrangement, Map<String, HoldState> added) {
        Map<String, TechnicianState> technicians = new TreeMap<>();
        reconciled.technicians().forEach((id, technician) -> technicians.put(id, technician.routed(Required.value(arrangement.routes().get(id), "prepared route"))));
        Map<String, HoldState> holds = new HashMap<>();
        reconciled.holds().forEach((id, hold) -> {
            String technician = technicianOf(arrangement, Required.value(id));
            holds.put(id, technician.equals(hold.technicianId()) ? hold : hold.on(technician));
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
