package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SearchDeadline;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Scoring timing: dynamic programming over contiguous visit groups and working intervals. */
final class RouteTimingSearch {
    record Block(Instant start, Instant end) { }
    record Timing(long paid, long overtime, long drive, long waiting, long meters,
            Map<String, Instant> arrivals, List<Instant> departures) { }
    private RouteTimingSearch() { }

    static @Nullable Timing solve(DayPlan plan, TechRoute route, List<Block> blocks) {
        Timing empty = new Timing(0, 0, 0, 0, 0, Required.value(Map.of()), Required.value(List.of()));
        int count = route.getVisits().size();
        if (count == 0) return empty;
        if (blocks.isEmpty()) return null;
        if (blocks.size() == 1) {
            Timing timing = segment(plan, route, Required.value(blocks.getFirst()), 0, count);
            return timing != null && within(route, timing) ? timing : null;
        }
        Map<Integer, List<Timing>> frontier = new TreeMap<>();
        frontier.put(0, new ArrayList<>(List.of(empty)));
        for (int interval = 0; interval < blocks.size(); interval++) {
            SearchDeadline.checkpoint();
            Map<Integer, List<Timing>> next = new TreeMap<>();
            for (var entry : frontier.entrySet()) {
                int first = entry.getKey();
                for (Timing prefix : entry.getValue()) retain(next, first, Required.value(prefix), plan);
                for (int end = first + 1; end <= count; end++) {
                    SearchDeadline.checkpoint();
                    Timing part = segment(plan, route, Required.value(blocks.get(interval)), first, end);
                    if (part == null) continue;
                    for (Timing prefix : entry.getValue()) {
                        Timing combined = append(Required.value(prefix), part);
                        if (within(route, combined)) retain(next, end, combined, plan);
                    }
                }
            }
            frontier = next;
        }
        List<Timing> complete = frontier.get(count);
        return complete == null ? null : complete.stream().min((first, second) -> compare(Required.value(first), Required.value(second), plan)).orElse(null);
    }

    private static @Nullable Timing segment(DayPlan plan, TechRoute route, Block block, int first, int end) {
        Map<String, Instant> arrivals = new LinkedHashMap<>();
        Map<String, Long> cumulativeWaiting = new HashMap<>();
        Instant departure = block.start(), cursor = departure;
        String previous = route.getId();
        long drive = 0, waiting = 0, meters = 0, slack = Long.MAX_VALUE;
        for (int index = first; index < end; index++) {
            PlanVisit visit = Required.value(route.getVisits().get(index));
            DayPlan.RoadLeg leg = plan.getMatrix().get(previous + ">" + visit.getId());
            if (leg == null) return null;
            long travel = buffered(plan, leg);
            if (index == first) {
                departure = later(block.start(), Required.value(visit.getWindowStart().minusSeconds(travel * 60)));
                cursor = departure;
            }
            Instant reached = Required.value(cursor.plusSeconds(travel * 60));
            Instant arrival = later(reached, visit.getWindowStart());
            if (!arrival.isBefore(visit.getWindowEnd())) return null;
            cursor = Required.value(arrival.plusSeconds(visit.getDurationMinutes() * 60L));
            if (cursor.isAfter(block.end())) return null;
            waiting += Duration.between(reached, arrival).toMinutes();
            slack = Math.min(slack, Duration.between(arrival, visit.getWindowEnd().minusNanos(1)).toMinutes() + waiting);
            arrivals.put(visit.getId(), arrival); cumulativeWaiting.put(visit.getId(), waiting);
            drive += travel; meters += leg.meters(); previous = visit.getId();
        }
        DayPlan.RoadLeg home = plan.getMatrix().get(previous + ">" + route.getId() + ":return");
        if (home == null) return null;
        Instant returned = Required.value(cursor.plusSeconds(buffered(plan, home) * 60));
        if (returned.isAfter(block.end())) return null;
        long delay = Math.max(0, Math.min(waiting, slack));
        Instant actualDeparture = Required.value(departure.plusSeconds(delay * 60));
        arrivals.replaceAll((id, arrival) -> Required.value(arrival.plusSeconds(Math.max(0, delay - Required.value(cumulativeWaiting.get(id))) * 60)));
        return new Timing(Duration.between(actualDeparture, returned).toMinutes(), Math.max(0, Duration.between(later(actualDeparture, route.getShiftEnd()), returned).toMinutes()),
                drive + buffered(plan, home), waiting - delay, meters + home.meters(), arrivals, Required.value(List.of(actualDeparture)));
    }

    private static Timing append(Timing first, Timing second) {
        Map<String, Instant> arrivals = new LinkedHashMap<>(first.arrivals()); arrivals.putAll(second.arrivals());
        List<Instant> departures = new ArrayList<>(first.departures()); departures.addAll(second.departures());
        return new Timing(first.paid() + second.paid(), first.overtime() + second.overtime(), first.drive() + second.drive(),
                first.waiting() + second.waiting(), first.meters() + second.meters(), arrivals, departures);
    }
    private static boolean within(TechRoute route, Timing timing) { return timing.paid() <= route.getMaxDailyMinutes() && timing.overtime() <= route.getMaxOvertimeMinutes(); }
    private static void retain(Map<Integer, List<Timing>> states, int visits, Timing proposed, DayPlan plan) {
        List<Timing> existing = states.computeIfAbsent(visits, _ -> new ArrayList<>());
        for (Timing timing : existing) if (dominates(Required.value(timing), proposed, plan)) return;
        existing.removeIf(timing -> dominates(proposed, Required.value(timing), plan)); existing.add(proposed);
    }
    private static boolean dominates(Timing first, Timing second, DayPlan plan) {
        return first.paid() <= second.paid() && first.overtime() <= second.overtime() && first.waiting() <= second.waiting()
                && cost(first, plan) <= cost(second, plan) && departures(first.departures(), second.departures()) <= 0;
    }
    private static int compare(Timing first, Timing second, DayPlan plan) {
        int order = Long.compare(first.overtime(), second.overtime());
        if (order == 0) order = Double.compare(cost(first, plan), cost(second, plan));
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
    private static double cost(Timing value, DayPlan plan) {
        return (value.paid() - value.overtime()) * plan.getRegularHourly() * 100 / 60.0
                + value.overtime() * plan.getOvertimeHourly() * 100 / 60.0 + value.meters() / 1609.344 * plan.getMileagePerMile() * 100;
    }
    private static long buffered(DayPlan plan, DayPlan.RoadLeg leg) { return (long) Math.ceil(leg.seconds() * (1 + plan.getTravelBufferPct()) / 60.0) + plan.getTravelBufferMinutes(); }
    private static Instant later(Instant first, Instant second) { return first.isAfter(second) ? first : second; }
}
