package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.BookingSnapshot;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.HttpCalculation;
import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.ReservationTransition;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.ScheduleCutoff;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.SearchDeadline;
import dev.waterflex.scheduler.SnapshotRouting;
import dev.waterflex.scheduler.api.BookingDayState.HoldState;
import dev.waterflex.scheduler.api.BookingDayState.TechnicianState;
import dev.waterflex.scheduler.api.BookingStore.DayKey;
import dev.waterflex.scheduler.api.BookingStore.HoldToConfirm;
import dev.waterflex.scheduler.api.BookingStore.StoredDay;
import dev.waterflex.scheduler.api.DailyProposals.Reply;
import dev.waterflex.scheduler.api.PublicApiStore.*;
import dev.waterflex.scheduler.api.PublicRequests.ConfirmRequest;
import dev.waterflex.scheduler.api.PublicResponses.*;
import dev.waterflex.scheduler.api.PublicTypes.TechnicianDay;
import dev.waterflex.scheduler.api.PublicTypes.TechnicianDayVersion;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * POST /api/v1/booking/holds/{holdId}/confirm. The host sends its current snapshot of the hold's date. The scheduler
 * reconciles the day with it, turns the selected hold into the job's appointment (the appointment ID is the job ID) with
 * the portal's checks (ReservationTransition), and answers with a receipt of every appointment on each technician-day
 * whose route or planned times change. See "Booking (Decided 2026-10-08)" in docs/stateless-api-design.md.
 */
@Service
public class BookingConfirm {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(BookingConfirm.class));

    /** A refusal: the answer is already stored, so it is final. */
    private static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Reply reply;
        Refused(Reply reply) { super(null, null, false, false); this.reply = reply; }
    }

    private final PublicApiStore store;
    private final BookingStore bookings;
    private final MetroRouting routing;
    private final DailyPreparation.AddressLocator locator;
    private final SearchAdmission admission;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public BookingConfirm(PublicApiStore store, BookingStore bookings, MetroRouting routing, AddressLocation locator, SearchAdmission admission, JdbcTemplate jdbc) {
        this(store, bookings, routing, locator, admission, jdbc, Required.value(Clock.systemUTC()));
    }

    public BookingConfirm(PublicApiStore store, BookingStore bookings, MetroRouting routing, DailyPreparation.AddressLocator locator, SearchAdmission admission,
                          JdbcTemplate jdbc, Clock clock) {
        this.store = store; this.bookings = bookings; this.routing = routing; this.locator = locator; this.admission = admission; this.jdbc = jdbc; this.clock = clock;
    }

    public Reply confirm(String tenantId, String holdId, String body) {
        ConfirmRequest request;
        try {
            Input.id(holdId, "holdId");
            request = PublicRequests.read(body, ConfirmRequest.class);
        } catch (IllegalArgumentException invalid) {
            return Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, DailyProposals.invalidRequest(invalid)));
        }
        String fingerprint = DailyProposals.sha256(holdId + "\n" + CalculationJson.write(request));
        return switch (store.claim(tenantId, request.requestId(), Operation.BOOKING_CONFIRM, fingerprint)) {
            case Replay replay -> replayed(replay);
            case Conflict _ -> Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, "requestId was already used with a different request"));
            case Busy _ -> Reply.of(429, new Problem(ErrorCode.BUSY, "A request with this requestId is still running"));
            case Started started -> run(tenantId, holdId, request, started.ownerToken());
        };
    }

    private static Reply replayed(Replay replay) {
        Object body;
        try { body = replay.status() == 200 ? PublicRequests.read(replay.json(), CommitReceipt.class) : PublicRequests.read(replay.json(), Problem.class); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("A stored response failed validation", invalid); }
        return Reply.of(replay.status(), body);
    }

    private Reply run(String tenantId, String holdId, ConfirmRequest request, String owner) {
        try {
            SchedulingPolicy.Rules shared = DailyProposals.sharedPolicy(jdbc);
            SearchDeadline deadline = new SearchDeadline(Required.value(Duration.ofMillis(shared.bookingDeadlineMs())));
            return deadline.within(() -> {
                try (var _ = admission.acquire(SearchAdmission.Kind.BOOKING, deadline)) {
                    try { return attempt(tenantId, holdId, request, owner, shared); }
                    catch (BookingStore.DayMoved moved) {
                        // One retry within the same deadline, as a search does after a concurrent change.
                        LOG.info("Retrying confirm of hold {}: {}", holdId, moved.getMessage());
                        return attempt(tenantId, holdId, request, owner, shared);
                    }
                } catch (Refused refused) { return refused.reply; }
            });
        } catch (RoadClient.RoadUnavailable unavailable) {
            store.release(tenantId, request.requestId(), owner);
            return Reply.of(503, DailyProposals.routingUnavailable(unavailable));
        } catch (SearchAdmission.Busy | BookingStore.DayMoved busy) {
            store.release(tenantId, request.requestId(), owner);
            return Reply.of(429, new Problem(ErrorCode.BUSY, busy instanceof BookingStore.DayMoved
                    ? "Other bookings kept changing this date; retry" : "Search capacity exhausted"));
        } catch (SearchDeadline.Expired | HttpCalculation.Unavailable expired) {
            store.release(tenantId, request.requestId(), owner);
            return Reply.of(503, new Problem(ErrorCode.CALCULATION_UNAVAILABLE, "The confirm did not finish before its deadline"));
        } catch (RuntimeException unexpected) {
            store.release(tenantId, request.requestId(), owner);
            throw unexpected;
        }
    }

    private Reply attempt(String tenantId, String holdId, ConfirmRequest request, String owner, SchedulingPolicy.Rules shared) {
        Instant now = Required.value(clock.instant());
        List<HoldToConfirm> found = bookings.holdToConfirm(tenantId, holdId);
        if (found.isEmpty()) throw refuse(tenantId, request, owner, 404, ErrorCode.NOT_FOUND, "Hold not found");
        HoldToConfirm hold = Required.value(found.getFirst());
        String stored = hold.receiptJson();
        if (stored != null) {
            // Confirming a confirmed hold again answers with its receipt.
            CommitReceipt receipt = PublicRequests.read(stored, CommitReceipt.class);
            store.asTenant(tenantId, () -> { store.complete(tenantId, request.requestId(), owner, 200, stored); return Boolean.TRUE; });
            return Reply.of(200, receipt);
        }
        if (!hold.expiresAt().isAfter(now)) throw refuse(tenantId, request, owner, 409, ErrorCode.HOLD_UNAVAILABLE, "The hold expired");
        if (hold.status().equals("HELD") && hold.setStatus().equals("ACTIVE"))
            throw refuse(tenantId, request, owner, 409, ErrorCode.HOLD_UNAVAILABLE, "Select the offer before confirming its hold");
        if (!hold.status().equals("SELECTED") || !hold.setStatus().equals("SELECTED"))
            throw refuse(tenantId, request, owner, 409, ErrorCode.HOLD_UNAVAILABLE, "The hold is " + hold.status().toLowerCase(Locale.ROOT));

        PublicTypes.Snapshot facts = request.snapshot();
        LocalDate date = hold.serviceDate();
        String metro = hold.metroId();
        if (!facts.metroId().equals(metro))
            throw refuse(tenantId, request, owner, 400, ErrorCode.INVALID_REQUEST, "snapshot.metroId must be the hold's metro " + metro);
        for (TechnicianDay day : facts.technicianDays())
            if (!day.serviceDate().equals(date))
                throw refuse(tenantId, request, owner, 400, ErrorCode.INVALID_REQUEST, "Every technician-day must be on the hold's date " + date);
        if (ScheduleCutoff.frozen(date, now, Required.value(ZoneId.of(facts.timeZone()))))
            throw refuse(tenantId, request, owner, 409, ErrorCode.HOLD_UNAVAILABLE, "Routes for " + date + " are frozen from 6 a.m. local time");
        RoadClient roads;
        try { roads = routing.client(metro); }
        catch (MetroRouting.UnknownMetro unknown) { throw refuse(tenantId, request, owner, 422, ErrorCode.INCOMPLETE_FACTS, Required.value(unknown.getMessage())); }

        StoredDay day = Required.value(bookings.days(tenantId, metro, Required.value(List.of(date))).get(date), "stored day");
        BookingDayState state = day.state();
        HoldState held = state == null ? null : state.holds().get(holdId);
        if (state == null || held == null) throw refuse(tenantId, request, owner, 409, ErrorCode.HOLD_UNAVAILABLE, "The hold no longer stands");

        // The host's day, built exactly as a search for the held job on this one date would build it.
        PublicRequests.BookingOffersRequest search;
        try {
            search = new PublicRequests.BookingOffersRequest(request.requestId(), new PublicRequests.Job(held.jobId(), held.serviceId(), held.durationMinutes(),
                    new PublicTypes.Location(held.lat(), held.lng(), null)), new PublicRequests.Horizon(date, date), 1, facts);
        } catch (IllegalArgumentException invalid) {
            throw refuse(tenantId, request, owner, 400, ErrorCode.INVALID_REQUEST, Required.value(invalid.getMessage()));
        }
        String identity = roads.activeIdentity();
        RequestBooking.Built built;
        try { built = RequestBooking.build(search, shared, identity, now, locator); }
        catch (RequestBooking.JobUnlocatable | RequestBooking.Contradictory refused) {
            throw refuse(tenantId, request, owner, 422, ErrorCode.INCOMPLETE_FACTS, Required.value(refused.getMessage()));
        }
        BookingSnapshot snapshot = built.snapshot();
        BookingSnapshot.Day host = Required.value(snapshot.days().get(date), "hold date");
        DayKey key = new DayKey(metro, date);

        var reconciled = BookingReconciliation.reconcile(state, host, null, now);
        if (!reconciled.holds().containsKey(holdId))
            throw settle(tenantId, request, owner, key, day.version(), reconciled, "The hold was lost: the host changed a route it depended on");
        SnapshotRouting routes = new SnapshotRouting(roads);
        BookingSnapshot.Day placed = routes.arrangements(date, reconciled.day(), reconciled.day().visits(),
                Required.value(List.of(reconciled.day().baseline())), identity);
        if (!placed.evaluate(placed.baseline(), placed.visits(), snapshot.rates()).feasible())
            throw settle(tenantId, request, owner, key, day.version(), BookingReconciliation.infeasible(reconciled, host),
                    "The hold was lost: the day no longer fits the host's current facts");

        Map<LocalDate, BookingSnapshot.Day> days = new TreeMap<>(); days.put(date, placed);
        Map<LocalDate, Map<String, dev.waterflex.scheduler.ReservationState.Hold>> holds = new TreeMap<>(); holds.put(date, reconciled.holds());
        var transition = new ReservationTransition.Facts(metro, snapshot.capturedAt(), snapshot.configurationFingerprint(), identity, snapshot.policy(),
                snapshot.rates(), days, holds);
        ReservationTransition.Prepared prepared;
        try {
            prepared = Required.value(new ReservationTransition(routes).prepare(transition, held.jobId(),
                    new ReservationTransition.Confirmation(holdId, held.jobId())).get(date), "confirmed date");
        } catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode().value() != 409) throw conflict;
            throw settle(tenantId, request, owner, key, day.version(), reconciled, Required.value(conflict.getReason(), "conflict reason"));
        }
        if (!identity.equals(roads.activeIdentity())) throw new RoadClient.RoadUnavailable("Routing identity changed");

        CommitReceipt receipt = receipt(prepared, reconciled.state(), host, facts, date);
        BookingDayState next = after(prepared, reconciled.state(), holdId);
        SearchDeadline.beforeCommit();
        String body = CalculationJson.write(receipt);
        bookings.settle(tenantId, request.requestId(), owner, key, day.version(), next, reconciled.ended(),
                new BookingStore.Confirmation(hold.offerId(), hold.offerSetId(), receipt), 200, body);
        return Reply.of(200, receipt);
    }

    /**
     * Every appointment on each technician-day whose appointments, order or planned times change, with those
     * technician-days' timestamps for the host's compare-and-set. Writing it brings the host to the common arrangement
     * without its remaining holds, which is what the scheduler then expects.
     */
    static CommitReceipt receipt(ReservationTransition.Prepared prepared, BookingDayState reconciled, BookingSnapshot.Day host,
                                 PublicTypes.Snapshot facts, LocalDate date) {
        Map<String, Instant> arrivals = prepared.validation().arrivals();
        Map<String, TechnicianDay> hostDays = new HashMap<>();
        for (TechnicianDay day : facts.technicianDays()) hostDays.put(day.technicianId(), day);
        List<Assignment> assignments = new ArrayList<>();
        List<TechnicianDayVersion> technicianDays = new ArrayList<>();
        for (var route : new TreeMap<>(prepared.day().baseline().routes()).entrySet()) {
            String technician = Required.value(route.getKey());
            List<String> appointments = written(Required.value(route.getValue()), prepared.holds());
            boolean changed = !appointments.equals(Required.value(reconciled.technicians().get(technician), "reconciled technician").expected());
            for (String id : appointments) {
                BookingSnapshot.Visit before = host.visits().get(id);
                if (before == null || !before.plannedStart().equals(arrivals.get(id))) changed = true;
            }
            if (!changed) continue;
            for (int sequence = 0; sequence < appointments.size(); sequence++) {
                String id = Required.value(appointments.get(sequence));
                Instant start = Required.value(arrivals.get(id), "planned arrival of " + id);
                int minutes = Required.value(prepared.day().visits().get(id), "visit " + id).durationMinutes();
                assignments.add(new Assignment(id, technician, date, sequence, start, Required.value(start.plusSeconds(minutes * 60L))));
            }
            TechnicianDay day = Required.value(hostDays.get(technician), "technician-day of " + technician);
            technicianDays.add(new TechnicianDayVersion(technician, date, day.lastModified()));
        }
        return new CommitReceipt(PublicApiStore.newReceiptId(), Required.value(List.copyOf(assignments)), Required.value(List.copyOf(technicianDays)));
    }

    /** The day after the confirm: the job is an appointment the host is expected to have, and the other holds stay. */
    static BookingDayState after(ReservationTransition.Prepared prepared, BookingDayState reconciled, String holdId) {
        Map<String, TechnicianState> technicians = new TreeMap<>();
        prepared.day().baseline().routes().forEach((id, route) -> technicians.put(id, new TechnicianState(
                Required.value(reconciled.technicians().get(id), "reconciled technician").version(), written(Required.value(route), prepared.holds()),
                Required.value(List.copyOf(route)))));
        Map<String, HoldState> holds = new TreeMap<>(reconciled.holds());
        holds.remove(holdId);
        return new BookingDayState(technicians, holds);
    }

    /** A route without its holds: the appointments the host writes, in order. */
    private static List<String> written(List<String> route, Map<String, ?> holds) {
        return Required.value(route.stream().filter(visit -> !holds.containsKey(visit)).toList());
    }

    private Refused refuse(String tenantId, ConfirmRequest request, String owner, int status, ErrorCode code, String message) {
        Problem problem = new Problem(code, message);
        store.completeWithError(tenantId, request.requestId(), owner, status, problem);
        return new Refused(Reply.of(status, problem));
    }

    /** A 409 that also records the reconciled day, so holds found lost or expired are recorded as such. */
    private Refused settle(String tenantId, ConfirmRequest request, String owner, DayKey key, int version, BookingReconciliation.Result reconciled, String message) {
        Problem problem = new Problem(ErrorCode.HOLD_UNAVAILABLE, message);
        bookings.settle(tenantId, request.requestId(), owner, key, version, reconciled.state(), reconciled.ended(), null, 409, CalculationJson.write(problem));
        return new Refused(Reply.of(409, problem));
    }
}
