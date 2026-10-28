package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.Monetary;
import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.Nullable;

/**
 * Scoring timing: dynamic programming over contiguous visit groups and working intervals. Reads the fact revision's
 * indexed roads, and builds the per-visit arrival map only when the caller asked for arrivals: the score needs the
 * route's minutes, meters and departures alone, and a solve evaluates this hundreds of thousands of times. Times are
 * long nanoseconds after {@code origin}, the route's shift start, with {@link RouteTimeline}'s readings.
 */
final class RouteTimingSearch {
    /** A working interval as offsets from the route's origin. */
    record Block(long start, long end) { }
    /**
     * {@code departures} are offsets from the route's origin, one per working interval used. {@code buffer} is the
     * whole seconds of late-arrival buffer the visits' arrivals use ({@link PlanFacts.ArrivalBuffer}).
     */
    record Timing(long paid, long overtime, long drive, long waiting, long meters, long buffer,
            Map<String, Instant> arrivals, long[] departures) { }
    private static final long[] NONE = {};
    private static final Timing EMPTY = new Timing(0, 0, 0, 0, 0, 0, Required.value(Map.of()), NONE);
    private RouteTimingSearch() { }

    static @Nullable Timing solve(PlanFacts facts, TechRoute route, Instant origin, TechRoute.Clock clock, boolean withArrivals) {
        List<Block> blocks = clock.blocks();
        int count = route.getVisits().size();
        if (count == 0) return EMPTY;
        if (blocks.isEmpty()) return null;
        if (blocks.size() == 1) {
            // One interval: only the complete route is read, so no array of every return point is made.
            Timing timing = segments(facts, route, origin, clock, Required.value(blocks.getFirst()), 0, null, withArrivals);
            return timing != null && within(route, timing) ? timing : null;
        }
        // The non-dominated timings by the number of visits they cover, walked in ascending order as a sorted map would be.
        List<List<Timing>> frontier = states(count);
        Required.value(frontier.getFirst()).add(EMPTY);
        for (int interval = 0; interval < blocks.size(); interval++) {
            List<List<Timing>> next = states(count);
            for (int first = 0; first <= count; first++) {
                List<Timing> prefixes = Required.value(frontier.get(first));
                if (prefixes.isEmpty()) continue;
                for (Timing prefix : prefixes) retain(next, first, Required.value(prefix), facts);
                @Nullable Timing[] options = new @Nullable Timing[count + 1];
                segments(facts, route, origin, clock, Required.value(blocks.get(interval)), first, options, withArrivals);
                for (int end = first + 1; end <= count; end++) {
                    Timing part = options[end];
                    if (part == null) continue;
                    for (Timing prefix : prefixes) {
                        Timing combined = append(Required.value(prefix), part);
                        if (within(route, combined)) retain(next, end, combined, facts);
                    }
                }
            }
            frontier = next;
        }
        List<Timing> complete = Required.value(frontier.get(count));
        return complete.stream().min((first, second) -> compare(Required.value(first), Required.value(second), facts)).orElse(null);
    }

    private static List<List<Timing>> states(int count) {
        List<List<Timing>> states = new ArrayList<>(count + 1);
        for (int visits = 0; visits <= count; visits++) states.add(new ArrayList<>());
        return states;
    }

    /**
     * Reuse forward timing/resource prefixes for every possible return point in this interval: with {@code options},
     * cell {@code end} receives the timing that returns after the visit before {@code end}, or stays null where none
     * fits. Without it only the complete route's timing is made, and returned (null where none fits).
     */
    private static @Nullable Timing segments(PlanFacts facts, TechRoute route, Instant origin, TechRoute.Clock clock, Block block, int first,
            @Nullable Timing @Nullable [] options, boolean withArrivals) {
        RoadMatrix roads = facts.roads();
        boolean everyEnd = options != null;
        @Nullable Timing complete = null;
        // Arrival bookkeeping exists only for a caller that reads arrivals; the timing itself never depends on it.
        Map<String, Long> arrivals = withArrivals ? new LinkedHashMap<>() : Required.value(Map.of());
        Map<String, Long> cumulativeWaiting = withArrivals ? new HashMap<>() : Required.value(Map.of());
        long shiftEnd = clock.shiftEnd();
        // Window offsets are the visit's epoch nanoseconds less the shift start's: the same exact difference offset takes.
        @Nullable Long epoch = clock.origin();
        PlanFacts.ArrivalBuffer reserve = facts.arrivalBuffer();
        boolean buffered = !reserve.none();
        long departure = block.start(), cursor = departure;
        int technician = route.roadNumber(facts), previous = technician;
        long drive = 0, waiting = 0, meters = 0, buffer = 0, slack = Long.MAX_VALUE;
        for (int index = first; index < route.getVisits().size(); index++) {
            PlanVisit visit = Required.value(route.getVisits().get(index));
            int here = visit.roadNumber(facts);
            RoadMatrix.Leg leg = roads.leg(previous, here);
            if (leg == null) break;
            long travel = leg.bufferedMinutes();
            PlanVisit.Times times = epoch == null ? null : visit.times();
            long windowStart, windowEnd, duration;
            if (times != null && epoch != null) {
                windowStart = Math.subtractExact(times.windowStart(), epoch); windowEnd = Math.subtractExact(times.windowEnd(), epoch); duration = times.duration();
            } else {
                windowStart = RouteTimeline.offset(origin, visit.getWindowStart()); windowEnd = RouteTimeline.offset(origin, visit.getWindowEnd());
                duration = RouteTimeline.nanos(visit.getDurationMinutes());
            }
            if (index == first) {
                departure = Math.max(block.start(), Math.subtractExact(windowStart, RouteTimeline.nanos(travel)));
                cursor = departure;
            }
            long reached = Math.addExact(cursor, RouteTimeline.nanos(travel));
            long arrival = Math.max(reached, windowStart);
            if (arrival >= windowEnd) break;
            cursor = Math.addExact(arrival, duration);
            if (cursor > block.end()) break;
            waiting += RouteTimeline.minutes(arrival - reached);
            // The latest start the delay below may reach is the deadline less one nanosecond: the window end without a
            // buffer. An arrival already at or past its deadline holds the delay to the waiting before it, so a later
            // departure never adds buffer seconds; this arrival's own are the least any departure of this group gives.
            long deadline = buffered ? windowEnd - reserve.nanos(windowEnd - windowStart) : windowEnd;
            if (arrival >= deadline) {
                buffer = Math.addExact(buffer, Math.addExact(RouteTimeline.seconds(arrival - deadline), 1));
                slack = Math.min(slack, waiting);
            } else slack = Math.min(slack, RouteTimeline.minutes(deadline - 1 - arrival) + waiting);
            if (withArrivals) { arrivals.put(visit.getId(), arrival); cumulativeWaiting.put(visit.getId(), waiting); }
            drive += travel; meters += leg.meters(); previous = here;
            if (!everyEnd && index + 1 != route.getVisits().size()) continue;
            RoadMatrix.Leg home = roads.home(previous, technician);
            if (home == null) continue;
            long returned = Math.addExact(cursor, RouteTimeline.nanos(home.bufferedMinutes()));
            if (returned > block.end()) continue;
            long delay = Math.max(0, Math.min(waiting, slack));
            long actualDeparture = Math.addExact(departure, RouteTimeline.nanos(delay));
            Map<String, Instant> shifted;
            if (withArrivals) {
                shifted = new LinkedHashMap<>();
                for (var earliest : arrivals.entrySet()) {
                    long postponed = Math.max(0, delay - Required.value(cumulativeWaiting.get(earliest.getKey())));
                    shifted.put(Required.value(earliest.getKey()), RouteTimeline.instant(origin, Math.addExact(Required.value(earliest.getValue()), RouteTimeline.nanos(postponed))));
                }
            } else shifted = Required.value(Map.of());
            Timing timing = new Timing(RouteTimeline.minutes(returned - actualDeparture), Math.max(0, RouteTimeline.minutes(returned - Math.max(actualDeparture, shiftEnd))),
                    drive + home.bufferedMinutes(), waiting - delay, meters + home.meters(), buffer, shifted, new long[]{actualDeparture});
            if (options != null) options[index + 1] = timing;
            else complete = timing;
        }
        return complete;
    }

    private static Timing append(Timing first, Timing second) {
        Map<String, Instant> arrivals = new LinkedHashMap<>(first.arrivals()); arrivals.putAll(second.arrivals());
        long[] departures = Required.value(Arrays.copyOf(first.departures(), first.departures().length + second.departures().length));
        System.arraycopy(second.departures(), 0, departures, first.departures().length, second.departures().length);
        return new Timing(first.paid() + second.paid(), first.overtime() + second.overtime(), first.drive() + second.drive(),
                first.waiting() + second.waiting(), first.meters() + second.meters(), Math.addExact(first.buffer(), second.buffer()), arrivals, departures);
    }
    private static boolean within(TechRoute route, Timing timing) { return timing.paid() <= route.getMaxDailyMinutes() && timing.overtime() <= route.getMaxOvertimeMinutes(); }
    private static void retain(List<List<Timing>> states, int visits, Timing proposed, PlanFacts facts) {
        List<Timing> existing = Required.value(states.get(visits));
        for (Timing timing : existing) if (dominates(Required.value(timing), proposed, facts)) return;
        existing.removeIf(timing -> dominates(proposed, Required.value(timing), facts)); existing.add(proposed);
    }
    private static boolean dominates(Timing first, Timing second, PlanFacts facts) {
        return first.paid() <= second.paid() && first.overtime() <= second.overtime() && first.waiting() <= second.waiting()
                && first.buffer() <= second.buffer() && cost(first, second, facts) <= 0 && first.departures().length == second.departures().length
                && departures(first.departures(), second.departures()) <= 0;
    }
    /** The score's order (DayScores): overtime, then buffer seconds, then cost, then the tie breaks. */
    private static int compare(Timing first, Timing second, PlanFacts facts) {
        int order = Long.compare(first.overtime(), second.overtime());
        if (order == 0) order = Long.compare(first.buffer(), second.buffer());
        if (order == 0) order = cost(first, second, facts);
        if (order == 0) order = Long.compare(first.waiting(), second.waiting());
        if (order == 0) order = Long.compare(first.paid(), second.paid());
        return order == 0 ? departures(first.departures(), second.departures()) : order;
    }
    private static int departures(long[] first, long[] second) {
        for (int index = 0; index < Math.min(first.length, second.length); index++) {
            int order = Long.compare(first[index], second[index]);
            if (order != 0) return order;
        }
        return Integer.compare(first.length, second.length);
    }
    /** The order of two timings' exact costs, on the revision's integer rates where they fit ({@link Monetary#compare}). */
    private static int cost(Timing first, Timing second, PlanFacts facts) {
        return Monetary.compare(first.paid(), first.overtime(), first.meters(), second.paid(), second.overtime(), second.meters(), facts.rates());
    }
}
