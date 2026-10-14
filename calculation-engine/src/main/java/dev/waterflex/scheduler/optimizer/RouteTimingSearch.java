package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.Monetary;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.Nullable;

/**
 * Scoring timing: dynamic programming over contiguous visit groups and working intervals. Reads the fact revision's
 * indexed roads, and builds the per-visit arrival map only when the caller asked for arrivals: the score needs the
 * route's minutes, meters and departures alone, and a solve evaluates this hundreds of thousands of times.
 */
final class RouteTimingSearch {
    record Block(Instant start, Instant end) { }
    record Timing(long paid, long overtime, long drive, long waiting, long meters,
            Map<String, Instant> arrivals, List<Instant> departures) { }
    private RouteTimingSearch() { }

    static @Nullable Timing solve(PlanFacts facts, TechRoute route, List<Block> blocks, boolean withArrivals) {
        Timing empty = new Timing(0, 0, 0, 0, 0, Required.value(Map.of()), Required.value(List.of()));
        int count = route.getVisits().size();
        if (count == 0) return empty;
        if (blocks.isEmpty()) return null;
        if (blocks.size() == 1) {
            Timing timing = segments(facts, route, Required.value(blocks.getFirst()), 0, false, withArrivals).get(count);
            return timing != null && within(route, timing) ? timing : null;
        }
        Map<Integer, List<Timing>> frontier = new TreeMap<>();
        frontier.put(0, new ArrayList<>(List.of(empty)));
        for (int interval = 0; interval < blocks.size(); interval++) {
            Map<Integer, List<Timing>> next = new TreeMap<>();
            for (var entry : frontier.entrySet()) {
                int first = entry.getKey();
                for (Timing prefix : entry.getValue()) retain(next, first, Required.value(prefix), facts);
                Map<Integer, Timing> options = segments(facts, route, Required.value(blocks.get(interval)), first, true, withArrivals);
                for (var option : options.entrySet()) {
                    int end = option.getKey();
                    Timing part = Required.value(option.getValue());
                    for (Timing prefix : entry.getValue()) {
                        Timing combined = append(Required.value(prefix), part);
                        if (within(route, combined)) retain(next, end, combined, facts);
                    }
                }
            }
            frontier = next;
        }
        List<Timing> complete = frontier.get(count);
        return complete == null ? null : complete.stream().min((first, second) -> compare(Required.value(first), Required.value(second), facts)).orElse(null);
    }

    /** Reuse forward timing/resource prefixes for every possible return point in this interval. */
    private static Map<Integer, Timing> segments(PlanFacts facts, TechRoute route, Block block, int first, boolean everyEnd, boolean withArrivals) {
        RoadMatrix roads = facts.roads();
        Map<Integer, Timing> options = new LinkedHashMap<>();
        // Arrival bookkeeping exists only for a caller that reads arrivals; the timing itself never depends on it.
        Map<String, Instant> arrivals = withArrivals ? new LinkedHashMap<>() : Required.value(Map.of());
        Map<String, Long> cumulativeWaiting = withArrivals ? new HashMap<>() : Required.value(Map.of());
        Instant departure = block.start(), cursor = departure;
        int technician = roads.number(route.getId()), previous = technician;
        long drive = 0, waiting = 0, meters = 0, slack = Long.MAX_VALUE;
        for (int index = first; index < route.getVisits().size(); index++) {
            PlanVisit visit = Required.value(route.getVisits().get(index));
            int here = roads.number(visit.getId());
            RoadMatrix.Leg leg = roads.leg(previous, here);
            if (leg == null) break;
            long travel = leg.bufferedMinutes();
            if (index == first) {
                departure = later(block.start(), Required.value(visit.getWindowStart().minusSeconds(travel * 60)));
                cursor = departure;
            }
            Instant reached = Required.value(cursor.plusSeconds(travel * 60));
            Instant arrival = later(reached, visit.getWindowStart());
            if (!arrival.isBefore(visit.getWindowEnd())) break;
            cursor = Required.value(arrival.plusSeconds(visit.getDurationMinutes() * 60L));
            if (cursor.isAfter(block.end())) break;
            waiting += Duration.between(reached, arrival).toMinutes();
            slack = Math.min(slack, Duration.between(arrival, visit.getWindowEnd().minusNanos(1)).toMinutes() + waiting);
            if (withArrivals) { arrivals.put(visit.getId(), arrival); cumulativeWaiting.put(visit.getId(), waiting); }
            drive += travel; meters += leg.meters(); previous = here;
            if (!everyEnd && index + 1 != route.getVisits().size()) continue;
            RoadMatrix.Leg home = roads.home(previous, technician);
            if (home == null) continue;
            Instant returned = Required.value(cursor.plusSeconds(home.bufferedMinutes() * 60));
            if (returned.isAfter(block.end())) continue;
            long delay = Math.max(0, Math.min(waiting, slack));
            Instant actualDeparture = Required.value(departure.plusSeconds(delay * 60));
            Map<String, Instant> shifted;
            if (withArrivals) {
                shifted = new LinkedHashMap<>(arrivals);
                shifted.replaceAll((id, earliest) -> Required.value(earliest.plusSeconds(Math.max(0, delay - Required.value(cumulativeWaiting.get(id))) * 60)));
            } else shifted = Required.value(Map.of());
            options.put(index + 1, new Timing(Duration.between(actualDeparture, returned).toMinutes(), Math.max(0, Duration.between(later(actualDeparture, route.getShiftEnd()), returned).toMinutes()),
                    drive + home.bufferedMinutes(), waiting - delay, meters + home.meters(), shifted, Required.value(List.of(actualDeparture))));
        }
        return options;
    }

    private static Timing append(Timing first, Timing second) {
        Map<String, Instant> arrivals = new LinkedHashMap<>(first.arrivals()); arrivals.putAll(second.arrivals());
        List<Instant> departures = new ArrayList<>(first.departures()); departures.addAll(second.departures());
        return new Timing(first.paid() + second.paid(), first.overtime() + second.overtime(), first.drive() + second.drive(),
                first.waiting() + second.waiting(), first.meters() + second.meters(), arrivals, departures);
    }
    private static boolean within(TechRoute route, Timing timing) { return timing.paid() <= route.getMaxDailyMinutes() && timing.overtime() <= route.getMaxOvertimeMinutes(); }
    private static void retain(Map<Integer, List<Timing>> states, int visits, Timing proposed, PlanFacts facts) {
        List<Timing> existing = states.computeIfAbsent(visits, _ -> new ArrayList<>());
        for (Timing timing : existing) if (dominates(Required.value(timing), proposed, facts)) return;
        existing.removeIf(timing -> dominates(proposed, Required.value(timing), facts)); existing.add(proposed);
    }
    private static boolean dominates(Timing first, Timing second, PlanFacts facts) {
        return first.paid() <= second.paid() && first.overtime() <= second.overtime() && first.waiting() <= second.waiting()
                && cost(first, facts).compareTo(cost(second, facts)) <= 0 && first.departures().size() == second.departures().size()
                && departures(first.departures(), second.departures()) <= 0;
    }
    private static int compare(Timing first, Timing second, PlanFacts facts) {
        int order = Long.compare(first.overtime(), second.overtime());
        if (order == 0) order = cost(first, facts).compareTo(cost(second, facts));
        if (order == 0) order = Long.compare(first.waiting(), second.waiting());
        if (order == 0) order = Long.compare(first.paid(), second.paid());
        return order == 0 ? departures(first.departures(), second.departures()) : order;
    }
    private static int departures(List<Instant> first, List<Instant> second) {
        for (int index = 0; index < Math.min(first.size(), second.size()); index++) {
            int order = first.get(index).compareTo(second.get(index));
            if (order != 0) return order;
        }
        return Integer.compare(first.size(), second.size());
    }
    private static java.math.BigDecimal cost(Timing value, PlanFacts facts) {
        return Monetary.numerator(value.paid(), value.overtime(), value.meters(), facts.regularHourly(), facts.overtimeHourly(), facts.mileagePerMile());
    }
    private static Instant later(Instant first, Instant second) { return first.isAfter(second) ? first : second; }
}
