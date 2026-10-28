package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.Nullable;

/**
 * Exact feasible timing, with an explicitly diagnostic relaxed trajectory for repair gradients. Evaluates one route
 * against a fact revision; the score path asks for no arrivals and gets a result whose arrival map is empty.
 * Time runs on long nanoseconds after the route's shift start ({@link #offset}), so a leg allocates nothing; the
 * minute and second readings are {@link java.time.Duration}'s own, and arrivals are instants again on the way out.
 */
final class RouteTimeline {
    static final long NANOS_PER_SECOND = 1_000_000_000L, NANOS_PER_MINUTE = 60 * NANOS_PER_SECOND;
    /** This instant as nanoseconds after the origin; exact while the two are within 292 years of each other, else it throws. */
    static long offset(Instant origin, Instant instant) {
        return Math.addExact(Math.multiplyExact(Math.subtractExact(instant.getEpochSecond(), origin.getEpochSecond()), NANOS_PER_SECOND),
                instant.getNano() - origin.getNano());
    }
    /** This instant as nanoseconds after the epoch, or null beyond the 292 years either side of 1970 that a long reaches. */
    static @Nullable Long epochNanos(Instant instant) {
        try { return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), NANOS_PER_SECOND), instant.getNano()); }
        catch (ArithmeticException beyond) { return null; }
    }
    static Instant instant(Instant origin, long offset) { return Required.value(origin.plusNanos(offset)); }
    static long nanos(long minutes) { return Math.multiplyExact(minutes, NANOS_PER_MINUTE); }
    /** {@code Duration.getSeconds}: whole seconds, floored. */
    static long seconds(long nanos) { return Math.floorDiv(nanos, NANOS_PER_SECOND); }
    /** {@code Duration.toMinutes}: the floored whole seconds divided toward zero, as a span of these nanoseconds reads. */
    static long minutes(long nanos) { return seconds(nanos) / 60; }
    record Violations(long qualifications, long unavailableRoads, long latenessSeconds,
                      long availabilitySeconds, long capacityMinutes) {
        long total() { return Math.addExact(Math.addExact(qualifications, unavailableRoads),
                Math.addExact(Math.addExact(latenessSeconds, availabilitySeconds), capacityMinutes)); }
    }
    /** {@code arrivals} is empty when the evaluation was asked for the score alone. */
    /**
     * A route's resources; only the fleet's summed resources are priced, rounded once (RouteScoringFacts.cost).
     * {@code bufferSeconds} is the late-arrival buffer the arrivals use ({@link PlanFacts.ArrivalBuffer}): scored, but
     * no violation, since the window end alone decides whether an arrival is on time.
     */
    record Result(Violations violations, Map<String, Instant> arrivals,
                  long paidMinutes, long overtimeMinutes, long driveMinutes, long waitingMinutes, long meters, long bufferSeconds) {
        long hardPenalty() { return violations.total(); }
    }
    private RouteTimeline() { }
    /** The full evaluation with arrivals. */
    static Result evaluate(PlanFacts facts, TechRoute route) { return evaluate(facts, route, true); }
    static Result evaluate(PlanFacts facts, TechRoute route, boolean withArrivals) {
        RoadMatrix roads = facts.roads();
        Instant origin = route.getShiftStart();
        TechRoute.Clock clock = route.clock();
        List<RouteTimingSearch.Block> blocks = clock.blocks();
        var canonical = RouteTimingSearch.solve(facts, route, origin, clock, withArrivals);
        long qualifications = 0;
        for (PlanVisit visit : route.getVisits()) if (!route.getQualifiedServiceIds().contains(visit.getServiceId())) qualifications++;
        if (canonical != null) {
            return result(new Violations(qualifications, 0, 0, 0, 0), canonical.arrivals(),
                    canonical.paid(), canonical.overtime(), canonical.drive(), canonical.waiting(), canonical.meters(), canonical.buffer());
        }
        // This trajectory is a direction of improvement, never an independent feasible-placement proof.
        // A categorical road failure has no known travel duration. Do not invent one for timing.
        long unavailableRoads = 0;
        int technician = route.roadNumber(facts), previous = technician;
        for (PlanVisit visit : route.getVisits()) {
            int here = visit.roadNumber(facts);
            if (roads.leg(previous, here) == null) unavailableRoads++;
            previous = here;
        }
        if (!route.getVisits().isEmpty() && roads.home(previous, technician) == null) unavailableRoads++;
        if (unavailableRoads > 0) return result(new Violations(qualifications, unavailableRoads, 0, 0, 0), Required.value(Map.of()), 0, 0, 0, 0, 0, 0);
        if (route.getVisits().isEmpty()) return result(new Violations(qualifications, 0, 0, 0, 0), Required.value(Map.of()), 0, 0, 0, 0, 0, 0);
        PlanFacts.ArrivalBuffer reserve = facts.arrivalBuffer();
        PlanVisit first = Required.value(route.getVisits().getFirst());
        long initialTravel = Required.value(roads.leg(technician, first.roadNumber(facts))).bufferedMinutes();
        // The shift starts at offset 0.
        long departure = Math.max(0, Math.subtractExact(offset(origin, first.getWindowStart()), nanos(initialTravel)));
        long cursor = departure;
        previous = technician;
        long drive = 0, waiting = 0, meters = 0, lateness = 0, buffer = 0;
        Map<String, Instant> arrivals = withArrivals ? new LinkedHashMap<>() : Required.value(Map.of());
        for (PlanVisit visit : route.getVisits()) {
            int here = visit.roadNumber(facts);
            RoadMatrix.Leg road = Required.value(roads.leg(previous, here));
            long travel = road.bufferedMinutes();
            long earliest = Math.addExact(cursor, nanos(travel));
            long windowStart = offset(origin, visit.getWindowStart());
            long arrival = Math.max(earliest, windowStart), windowEnd = offset(origin, visit.getWindowEnd());
            if (arrival >= windowEnd) lateness = Math.addExact(lateness, Math.addExact(seconds(arrival - windowEnd), 1));
            long deadline = windowEnd - reserve.nanos(windowEnd - windowStart);
            if (arrival >= deadline) buffer = Math.addExact(buffer, Math.addExact(seconds(arrival - deadline), 1));
            waiting = Math.addExact(waiting, minutes(arrival - earliest));
            drive = Math.addExact(drive, travel); meters = Math.addExact(meters, road.meters());
            if (withArrivals) arrivals.put(visit.getId(), instant(origin, arrival));
            cursor = Math.addExact(arrival, nanos(visit.getDurationMinutes())); previous = here;
        }
        RoadMatrix.Leg home = Required.value(roads.home(previous, technician));
        long returnMinutes = home.bufferedMinutes();
        long returned = Math.addExact(cursor, nanos(returnMinutes));
        drive = Math.addExact(drive, returnMinutes); meters = Math.addExact(meters, home.meters());
        long paid = minutes(returned - departure);
        long overtime = Math.max(0, minutes(returned - Math.max(departure, offset(origin, route.getShiftEnd()))));
        long permitted = 0;
        for (RouteTimingSearch.Block block : blocks) {
            long start = Math.max(departure, block.start()), end = Math.min(returned, block.end());
            if (start < end) permitted = Math.addExact(permitted, end - start);
        }
        long unavailable = returned - departure - permitted;
        long unavailableSeconds = unavailable == 0 ? 0 : Math.addExact(seconds(unavailable), Math.floorMod(unavailable, NANOS_PER_SECOND) == 0 ? 0 : 1);
        long capacity = Math.addExact(Math.max(0, paid - route.getMaxDailyMinutes()), Math.max(0, overtime - route.getMaxOvertimeMinutes()));
        return result(new Violations(qualifications, 0, lateness, unavailableSeconds, capacity), arrivals, paid, overtime, drive, waiting, meters, buffer);
    }
    private static Result result(Violations violations, Map<String, Instant> arrivals,
            long paid, long overtime, long drive, long waiting, long meters, long buffer) {
        return new Result(violations, arrivals.isEmpty() ? Required.value(Map.of()) : Required.value(Map.copyOf(arrivals)), paid, overtime, drive, waiting, meters, buffer);
    }
    /** The working intervals from the shift start (offset 0) to the overtime limit, absences removed, as offsets from {@code origin}. */
    static List<RouteTimingSearch.Block> available(TechRoute route, Instant origin) {
        long end = Math.addExact(offset(origin, route.getShiftEnd()), nanos(route.getMaxOvertimeMinutes()));
        List<RouteTimingSearch.Block> blocks = new ArrayList<>();
        long cursor = 0;
        List<TechRoute.Unavailable> unavailable = route.getUnavailable();
        if (unavailable.size() > 1) {
            unavailable = new ArrayList<>(unavailable);
            unavailable.sort(Comparator.comparing((TechRoute.Unavailable absence) -> absence.start()));
        }
        for (TechRoute.Unavailable absence : unavailable) {
            long start = offset(origin, absence.start()), finish = offset(origin, absence.end());
            if (start > cursor) blocks.add(new RouteTimingSearch.Block(cursor, Math.min(start, end)));
            if (finish > cursor) cursor = finish;
            if (cursor >= end) break;
        }
        if (cursor < end) blocks.add(new RouteTimingSearch.Block(cursor, end));
        blocks.removeIf(block -> block.start() >= block.end()); return Required.value(List.copyOf(blocks));
    }
}
