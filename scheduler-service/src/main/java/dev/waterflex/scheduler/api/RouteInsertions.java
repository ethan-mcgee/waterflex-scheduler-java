package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Monetary;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.SearchDeadline;
import dev.waterflex.scheduler.api.PublicRequests.DailyProposalRequest;
import dev.waterflex.scheduler.api.PublicRequests.InsertionJob;
import dev.waterflex.scheduler.api.PublicRequests.RouteInsertionRequest;
import dev.waterflex.scheduler.api.PublicResponses.*;
import dev.waterflex.scheduler.api.PublicTypes.Appointment;
import dev.waterflex.scheduler.api.PublicTypes.Location;
import dev.waterflex.scheduler.api.PublicTypes.Snapshot;
import dev.waterflex.scheduler.api.PublicTypes.Technician;
import dev.waterflex.scheduler.api.PublicTypes.TechnicianDay;
import dev.waterflex.scheduler.api.PublicTypes.Window;
import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.PlanFacts;
import dev.waterflex.scheduler.optimizer.PlanVisit;
import dev.waterflex.scheduler.optimizer.RouteEvaluator;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * POST /api/v1/routes/insertions (spec 1.12.0). For every technician-day of the snapshot, every place one job could go
 * in the day's current order: the road legs into and out of the job, the leg it would break, the drive it adds, and the
 * planned starts at which the day still holds every window, shift and paid limit without using more late-arrival
 * buffer. Nothing is optimized, stored or claimed.
 *
 * <p>This is the interactive path behind a dispatcher's calendar, so it never waits for a calculation slot
 * ({@link SearchAdmission}): a running daily search or a queue of bookings cannot refuse it. It runs on the request
 * thread under its own deadline, and its own small limit bounds how many run at once.
 */
@Service
public class RouteInsertions {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(RouteInsertions.class));
    /** The whole call, routing included. A calendar cannot use an answer that takes longer. */
    static final Duration DEADLINE = Required.value(Duration.ofSeconds(5));
    /** Route timing reads a daily request; no request is stored here, so its identity is fixed and never leaves this class. */
    private static final String REQUEST_ID = "00000000-0000-4000-8000-000000000000";
    /** The forced window that pins the job's arrival to one planned start: timing works in whole minutes. */
    private static final Duration PIN = Required.value(Duration.ofMinutes(1));

    /** The job's own location arrived without coordinates and could not be located with certainty. */
    private static final class JobUnlocated extends RuntimeException {
        private static final long serialVersionUID = 1L;
        JobUnlocated() { super("No coordinates and no certain address match for job.location; send coordinates"); }
    }

    /** One technician-day that could be located, its appointments in route order, and its road points by identity. */
    private record Day(TechnicianDay facts, Technician technician, List<Appointment> appointments, Map<String, RoadPoint> points) { }

    /** The arrival window a planned start would promise, and whether it is the job's promised one (null when it had none). */
    private record Promise(Window window, @Nullable Boolean keeps) { }

    private final MetroRouting routing;
    private final DailyPreparation.AddressLocator locator;
    private final Semaphore slots;

    @Autowired
    public RouteInsertions(MetroRouting routing, AddressLocation locator, @Value("${scheduler.insertions.concurrent-limit:4}") int concurrentLimit) {
        this(routing, (DailyPreparation.AddressLocator) locator, concurrentLimit);
    }

    public RouteInsertions(MetroRouting routing, DailyPreparation.AddressLocator locator, int concurrentLimit) {
        if (concurrentLimit < 1 || concurrentLimit > 64) throw new IllegalArgumentException("scheduler.insertions.concurrent-limit must be 1 to 64");
        this.routing = routing; this.locator = locator; this.slots = new Semaphore(concurrentLimit);
    }

    public DailyProposals.Reply insert(String tenantId, String body) {
        RouteInsertionRequest request;
        try { request = PublicRequests.read(body, RouteInsertionRequest.class); }
        catch (IllegalArgumentException invalid) { return DailyProposals.Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, DailyProposals.invalidRequest(invalid))); }
        // Refused at once when full: a calendar retries in a second, and a queue here would only make every answer late.
        if (!slots.tryAcquire()) return DailyProposals.Reply.of(429, new Problem(ErrorCode.BUSY, "Route insertion capacity exhausted"));
        long started = System.nanoTime();
        try {
            RouteInsertionResult result = new SearchDeadline(DEADLINE, Required.value(Duration.ZERO)).within(() -> calculate(request));
            LOG.info("Public route insertions tenant={} technicianDays={} skipped={} ms={}", tenantId, result.technicianDays().size(),
                    result.skippedTechnicianDays().size(), (System.nanoTime() - started) / 1_000_000);
            return DailyProposals.Reply.of(200, result);
        } catch (MetroRouting.UnknownMetro | JobUnlocated incomplete) {
            return DailyProposals.Reply.of(422, new Problem(ErrorCode.INCOMPLETE_FACTS, Required.value(incomplete.getMessage())));
        } catch (RoadClient.RoadUnavailable unavailable) {
            return DailyProposals.Reply.of(503, DailyProposals.routingUnavailable(unavailable));
        } catch (SearchAdmission.Busy busy) {
            // The shared routing queue is full; no calculation slot is ever asked for here.
            return DailyProposals.Reply.of(429, new Problem(ErrorCode.BUSY, "Search capacity exhausted"));
        } catch (SearchDeadline.Expired expired) {
            return DailyProposals.Reply.of(503, new Problem(ErrorCode.CALCULATION_UNAVAILABLE, "The calculation deadline passed"));
        } finally {
            slots.release();
        }
    }

    private RouteInsertionResult calculate(RouteInsertionRequest request) {
        Snapshot snapshot = request.snapshot();
        InsertionJob job = request.job();
        RoadClient roads = routing.client(snapshot.metroId());
        Map<PublicTypes.Address, @Nullable RoadPoint> located = new HashMap<>();
        if (!locatable(job.location(), located)) throw new JobUnlocated();
        RoadPoint jobPoint = point(job.location(), located);

        Map<String, Technician> technicians = new HashMap<>();
        for (Technician technician : snapshot.technicians()) technicians.put(technician.id(), technician);
        Map<PublicTypes.Key, List<Appointment>> booked = new HashMap<>();
        for (Appointment appointment : snapshot.appointments()) booked.computeIfAbsent(appointment.key(), _ -> new ArrayList<>()).add(appointment);
        List<TechnicianDay> ordered = new ArrayList<>(snapshot.technicianDays());
        ordered.sort(Comparator.comparing((TechnicianDay day) -> day.serviceDate()).thenComparing(day -> day.technicianId()));

        List<Day> days = new ArrayList<>();
        List<SkippedTechnicianDay> skipped = new ArrayList<>();
        for (TechnicianDay facts : ordered) {
            List<Appointment> appointments = new ArrayList<>(booked.getOrDefault(facts.key(), Required.value(List.of())));
            appointments.sort(Comparator.comparing((Appointment appointment) -> appointment.sequence()));
            List<String> unresolved = new ArrayList<>();
            if (!locatable(facts.start(), located)) unresolved.add("start location");
            if (!locatable(facts.end(), located)) unresolved.add("end location");
            for (Appointment appointment : appointments) if (!locatable(appointment.location(), located)) unresolved.add("appointment " + appointment.id());
            if (!unresolved.isEmpty()) {
                skipped.add(new SkippedTechnicianDay(facts.technicianId(), facts.serviceDate(), SkipReason.LOCATION_UNRESOLVED,
                        "No coordinates and no certain address match for " + String.join(", ", unresolved) + "; send coordinates to include this technician-day"));
                continue;
            }
            Map<String, RoadPoint> points = new LinkedHashMap<>();
            points.put(facts.technicianId(), point(facts.start(), located));
            points.put(facts.technicianId() + ":return", point(facts.end(), located));
            for (Appointment appointment : appointments) points.put(appointment.id(), point(appointment.location(), located));
            points.put(job.id(), jobPoint);
            days.add(new Day(facts, Required.value(technicians.get(facts.technicianId()), "technician for " + facts.key()), Required.value(List.copyOf(appointments)), points));
        }

        // One routing call for the whole snapshot. Each pair's identity is its day and its two ends.
        List<RoadClient.Pair> pairs = new ArrayList<>();
        List<Map<String, String>> pairIds = new ArrayList<>();
        for (Day listed : days) {
            Day day = Required.value(listed);
            Map<String, String> ids = new LinkedHashMap<>();
            for (String pair : legsNeeded(day, job.id())) {
                String[] ends = pair.split(">", -1);
                String id = Required.value(Integer.toString(pairs.size()));
                pairs.add(new RoadClient.Pair(id, Required.value(day.points().get(ends[0]), "routing origin"), Required.value(day.points().get(ends[1]), "routing destination")));
                ids.put(pair, id);
            }
            pairIds.add(ids);
        }
        String identity = roads.activeIdentity();
        Map<String, RoadClient.Leg> routed = pairs.isEmpty() ? Required.value(Map.of()) : roads.sparse(pairs, identity);
        // As when a day is prepared: legs from a road graph that changed while they were fetched are not used.
        if (!identity.equals(roads.activeIdentity())) throw new RoadClient.RoadUnavailable("Routing identity changed");

        RequestDay.Locator known = address -> Required.value(located.get(address), "located address");
        ZoneId zone = Required.value(ZoneId.of(snapshot.timeZone()));
        List<InsertionTechnicianDay> answered = new ArrayList<>();
        for (int index = 0; index < days.size(); index++) {
            Map<String, DayPlan.RoadLeg> legs = new HashMap<>();
            pairIds.get(index).forEach((pair, id) -> {
                // A pair routing did not answer has no road route. It stays absent: it is never a leg of zero.
                RoadClient.Leg leg = routed.get(id);
                if (leg != null) legs.put(pair, new DayPlan.RoadLeg(leg.seconds(), leg.meters()));
            });
            answered.add(answer(request, Required.value(days.get(index)), legs, known, zone));
        }
        return new RouteInsertionResult(identity, answered, skipped);
    }

    /**
     * The directed legs one day needs, as "from>to" over the day's identities: every leg the day's own timing can use
     * (from the start to each stop, each stop to the end, and each stop to the next, since an absence can end or begin
     * a working segment at any stop), and the job's legs to and from every point.
     */
    private static List<String> legsNeeded(Day day, String jobId) {
        String start = day.facts().technicianId(), end = start + ":return";
        List<String> pairs = new ArrayList<>();
        List<Appointment> stops = day.appointments();
        for (int index = 0; index < stops.size(); index++) {
            String stop = Required.value(stops.get(index)).id();
            pairs.add(start + ">" + stop);
            pairs.add(stop + ">" + end);
            if (index + 1 < stops.size()) pairs.add(stop + ">" + Required.value(stops.get(index + 1)).id());
            pairs.add(stop + ">" + jobId);
            pairs.add(jobId + ">" + stop);
        }
        pairs.add(start + ">" + jobId);
        pairs.add(jobId + ">" + end);
        return pairs;
    }

    private InsertionTechnicianDay answer(RouteInsertionRequest request, Day day, Map<String, DayPlan.RoadLeg> legs, RequestDay.Locator known, ZoneId zone) {
        Snapshot snapshot = request.snapshot();
        DayPlan given = plan(snapshot, day, day.appointments(), legs, known);
        RouteEvaluator.Result baseline = RouteEvaluator.evaluate(given);
        boolean holds = baseline.feasible();
        boolean qualified = day.technician().qualifications().contains(request.job().serviceId());
        List<InsertionGap> gaps = new ArrayList<>();
        for (int position = 0; position <= day.appointments().size(); position++) {
            SearchDeadline.checkpoint();
            gaps.add(gap(request, day, position, legs, known, zone, holds && qualified ? baseline : null));
        }
        return new InsertionTechnicianDay(day.facts().technicianId(), day.facts().serviceDate(), Required.value(Boolean.valueOf(holds)), Required.value(Boolean.valueOf(qualified)),
                holds ? totals(baseline) : null, holds && request.routes() ? stops(given, baseline, null, null) : null, gaps);
    }

    /** The gap before the appointment at {@code position}; {@code baseline} is null when the day can take no job at all. */
    private InsertionGap gap(RouteInsertionRequest request, Day day, int position, Map<String, DayPlan.RoadLeg> legs, RequestDay.Locator known, ZoneId zone,
                             RouteEvaluator.@Nullable Result baseline) {
        Snapshot snapshot = request.snapshot();
        InsertionJob job = request.job();
        List<Appointment> stops = day.appointments();
        String technician = day.facts().technicianId();
        Appointment before = position == 0 ? null : stops.get(position - 1), after = position == stops.size() ? null : stops.get(position);
        String previous = before == null ? technician : before.id(), next = after == null ? technician + ":return" : after.id();
        String afterId = before == null ? null : before.id(), beforeId = after == null ? null : after.id();
        DayPlan.RoadLeg to = legs.get(previous + ">" + job.id()), from = legs.get(job.id() + ">" + next);
        // A day with no appointments has no leg for the job to break: its technician does not drive at all.
        DayPlan.RoadLeg replaced = stops.isEmpty() ? null : legs.get(previous + ">" + next);
        if (to == null || from == null || (!stops.isEmpty() && replaced == null))
            return new InsertionGap(afterId, beforeId, Required.value(Boolean.FALSE), null, null, null, null, null, null, Required.value(List.of()));
        long toMinutes = buffered(snapshot, to), fromMinutes = buffered(snapshot, from);
        long addedSeconds = to.seconds() + from.seconds(), addedMeters = to.meters() + from.meters(), addedMinutes = toMinutes + fromMinutes;
        if (replaced != null) {
            addedSeconds -= replaced.seconds(); addedMeters -= replaced.meters(); addedMinutes -= buffered(snapshot, replaced);
        }

        List<InsertionStart> starts = new ArrayList<>();
        if (baseline != null) {
            Window shift = day.facts().shift();
            Duration service = Required.value(Duration.ofMinutes(job.durationMinutes()));
            // Bounds no timing can beat: the job cannot be reached before the stop ahead of it opens and is done, and
            // the stop after it (or the end of the shift) must still be reached. The timing below decides the rest.
            Instant lower = before == null ? shift.start().plus(Duration.ofMinutes(toMinutes))
                    : before.window().start().plus(Duration.ofMinutes(before.durationMinutes() + toMinutes));
            Instant upper = (after == null ? shift.end() : after.window().end()).minus(service).minus(Duration.ofMinutes(fromMinutes));
            ZonedDateTime mark = Required.value(lower.atZone(zone).truncatedTo(ChronoUnit.HOURS));
            while (mark.toInstant().isBefore(lower)) mark = Required.value(mark.plusMinutes(request.startStepMinutes()));
            PlanFacts.ArrivalBuffer buffer = snapshot.rates().arrival();
            for (; !mark.toInstant().isAfter(upper); mark = Required.value(mark.plusMinutes(request.startStepMinutes()))) {
                SearchDeadline.checkpoint();
                Instant start = Required.value(mark.toInstant());
                Promise promise = promise(start, job.window(), buffer, zone, request.windowMinutes());
                if (promise == null) continue;
                // The job's window is narrowed to this one minute, so the timing places its arrival exactly here or
                // finds that the day does not hold; the promised window is the one reported, never this one.
                Appointment pinned = new Appointment(job.id(), technician, day.facts().serviceDate(), job.serviceId(), job.durationMinutes(),
                        new Window(start, Required.value(start.plus(PIN))), job.location(), Required.value(Integer.valueOf(position)), start);
                List<Appointment> order = new ArrayList<>(stops.subList(0, position));
                order.add(pinned);
                order.addAll(stops.subList(position, stops.size()));
                DayPlan candidate = plan(snapshot, day, order, legs, known);
                RouteEvaluator.Result timed = RouteEvaluator.evaluate(candidate);
                // The booking rule: a place is offered only when it uses no more late-arrival buffer than the day does now.
                if (!timed.feasible() || timed.bufferSeconds() > baseline.bufferSeconds() || !start.equals(timed.arrivals().get(job.id()))) continue;
                starts.add(new InsertionStart(start, promise.window(), promise.keeps(), totals(timed),
                        request.routes() ? stops(candidate, timed, job.id(), promise.window()) : null));
            }
        }
        return new InsertionGap(afterId, beforeId, Required.value(Boolean.TRUE), leg(to, toMinutes), leg(from, fromMinutes),
                replaced == null ? null : leg(replaced, buffered(snapshot, replaced)),
                Required.value(Long.valueOf(addedSeconds)), Required.value(Long.valueOf(addedMeters)), Required.value(Integer.valueOf(Math.toIntExact(addedMinutes))), starts);
    }

    /**
     * The window a planned start would promise, or null when the start is inside that window's late-arrival buffer and
     * is not offered. A start inside the job's promised window keeps that window. Any other start gets a window of the
     * client's length that opens on the hour at or before it, in the metro's time zone.
     */
    private static @Nullable Promise promise(Instant start, @Nullable Window promised, PlanFacts.ArrivalBuffer buffer, ZoneId zone, int windowMinutes) {
        if (promised != null && !start.isBefore(promised.start()) && start.isBefore(promised.end()))
            return start.isBefore(buffer.deadline(promised.start(), promised.end())) ? new Promise(promised, Required.value(Boolean.TRUE)) : null;
        Instant opens = Required.value(start.atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant());
        Window built = new Window(opens, Required.value(opens.plus(Duration.ofMinutes(windowMinutes))));
        if (!start.isBefore(buffer.deadline(built.start(), built.end()))) return null;
        return new Promise(built, promised == null ? null : Required.value(Boolean.FALSE));
    }

    /** One technician-day as the route timing reads it, with these appointments in this order and only this day's legs. */
    private static DayPlan plan(Snapshot snapshot, Day day, List<Appointment> order, Map<String, DayPlan.RoadLeg> legs, RequestDay.Locator known) {
        List<Appointment> numbered = new ArrayList<>();
        for (int index = 0; index < order.size(); index++) {
            Appointment appointment = Required.value(order.get(index));
            numbered.add(new Appointment(appointment.id(), appointment.technicianId(), appointment.serviceDate(), appointment.serviceId(), appointment.durationMinutes(),
                    appointment.window(), appointment.location(), Required.value(Integer.valueOf(index)), appointment.plannedStart()));
        }
        List<Technician> technicians = new ArrayList<>();
        technicians.add(day.technician());
        List<TechnicianDay> days = new ArrayList<>();
        days.add(day.facts());
        Snapshot one = new Snapshot(snapshot.metroId(), snapshot.timeZone(), snapshot.rates(), snapshot.policy(), Required.value(List.copyOf(technicians)),
                Required.value(List.copyOf(days)), Required.value(List.copyOf(numbered)));
        RequestDay requested = RequestDay.of(new DailyProposalRequest(REQUEST_ID, day.facts().serviceDate(), one), known);
        Map<String, DayPlan.RoadLeg> reachable = new HashMap<>();
        legs.forEach((pair, leg) -> {
            String[] ends = pair.split(">", -1);
            if (requested.points().containsKey(ends[0]) && requested.points().containsKey(ends[1])) reachable.put(pair, leg);
        });
        return requested.plan(reachable);
    }

    /** The route's stops as timed. The job's stop reports the buffer of the window it would promise, not of its pin. */
    private static List<PlannedStop> stops(DayPlan plan, RouteEvaluator.Result timed, @Nullable String jobId, @Nullable Window promised) {
        List<PlannedStop> stops = new ArrayList<>();
        List<PlanVisit> visits = Required.value(plan.getRoutes().getFirst()).getVisits();
        PlanFacts.ArrivalBuffer buffer = plan.getArrivalBuffer();
        for (int index = 0; index < visits.size(); index++) {
            PlanVisit visit = Required.value(visits.get(index));
            Instant start = Required.value(timed.arrivals().get(visit.getId()), "arrival for " + visit.getId());
            if (promised != null && visit.getId().equals(jobId))
                stops.add(new PlannedStop(visit.getId(), Required.value(Integer.valueOf(index)), start, Required.value(start.plus(Duration.ofMinutes(visit.getDurationMinutes()))),
                        buffer.none() ? null : buffer.deadline(promised.start(), promised.end())));
            else stops.add(PublicResponses.stop(plan, visit, index, start));
        }
        return stops;
    }

    private static RouteTotals totals(RouteEvaluator.Result timed) {
        return new RouteTotals(Required.value(Long.valueOf(timed.driveMinutes())), Required.value(Long.valueOf(timed.meters())), Required.value(Long.valueOf(timed.paidMinutes())),
                Required.value(Long.valueOf(timed.costCents())), Required.value(Long.valueOf(timed.bufferSeconds())));
    }

    private static InsertionLeg leg(DayPlan.RoadLeg road, long bufferedMinutes) {
        return new InsertionLeg(Required.value(Long.valueOf(road.seconds())), Required.value(Long.valueOf(road.meters())), Required.value(Integer.valueOf(Math.toIntExact(bufferedMinutes))));
    }

    /** A leg's minutes with the client's travel buffer, exactly as the route timing counts it. */
    private static long buffered(Snapshot snapshot, DayPlan.RoadLeg road) {
        return Monetary.bufferedMinutes(road.seconds(), snapshot.rates().travelBufferPct(), snapshot.rates().travelBufferMinutes());
    }

    private boolean locatable(Location location, Map<PublicTypes.Address, @Nullable RoadPoint> located) {
        if (location.lat() != null && location.lng() != null) return true;
        PublicTypes.Address address = Required.value(location.address(), "address for a location without coordinates");
        if (!located.containsKey(address)) located.put(address, locator.locate(address));
        return located.get(address) != null;
    }

    private static RoadPoint point(Location location, Map<PublicTypes.Address, @Nullable RoadPoint> located) {
        Double lat = location.lat(), lng = location.lng();
        if (lat != null && lng != null) return new RoadPoint(lat, lng);
        return Required.value(located.get(Required.value(location.address(), "address for a location without coordinates")), "located address");
    }
}
