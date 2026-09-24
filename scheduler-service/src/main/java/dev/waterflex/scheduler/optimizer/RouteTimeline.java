package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Evaluates working intervals separated by approved absences. */
final class RouteTimeline {
    record Result(long hardPenalty, long costCents, Map<String, Instant> arrivals,
                  long paidMinutes, long overtimeMinutes, long driveMinutes, long waitingMinutes, long meters) { }
    private record Segment(String previous, Instant departure, Instant done) { }
    private record Block(Instant start, Instant end) { }
    private record Timing(PlanVisit visit, long waitingBefore) { }

    private RouteTimeline() { }

    static Result evaluate(DayPlan plan, TechRoute route) {
        List<RouteTimingSearch.Block> intervals = new ArrayList<>();
        for (Block block : available(route)) intervals.add(new RouteTimingSearch.Block(block.start(), block.end()));
        var canonical = RouteTimingSearch.solve(plan, route, intervals);
        if (canonical == null) {
            Result invalid = infeasibleTiming(plan, route);
            return new Result(Math.max(1, invalid.hardPenalty()), invalid.costCents(), invalid.arrivals(), invalid.paidMinutes(),
                    invalid.overtimeMinutes(), invalid.driveMinutes(), invalid.waitingMinutes(), invalid.meters());
        }
        long hard = 0;
        for (PlanVisit visit : route.getVisits()) if (!route.getQualifiedServiceIds().contains(visit.getServiceId())) hard += 1_000_000;
        long cost = Math.round((canonical.paid() - canonical.overtime()) * plan.getRegularHourly() * 100 / 60.0
                + canonical.overtime() * plan.getOvertimeHourly() * 100 / 60.0 + canonical.meters() / 1609.344 * plan.getMileagePerMile() * 100);
        return new Result(hard, cost, Required.value(Map.copyOf(canonical.arrivals())), canonical.paid(), canonical.overtime(), canonical.drive(), canonical.waiting(), canonical.meters());
    }

    private static Result infeasibleTiming(DayPlan plan, TechRoute route) {
        List<Block> blocks = available(route);
        Map<String, Instant> arrivals = new HashMap<>();
        long hard = 0, paid = 0, overtime = 0, drive = 0, waiting = 0, meters = 0;
        int blockIndex = 0;
        Segment segment = null;
        long segmentMeters = 0, segmentDrive = 0, segmentWaiting = 0;
        List<Timing> timings = new ArrayList<>();
        long departureSlack = Long.MAX_VALUE;
        for (PlanVisit visit : route.getVisits()) {
            if (!route.getQualifiedServiceIds().contains(visit.getServiceId())) hard += 1_000_000;
            boolean placed = false;
            while (blockIndex < blocks.size()) {
                Block block = blocks.get(blockIndex);
                String origin = segment == null ? route.getId() : segment.previous();
                DayPlan.RoadLeg leg = plan.getMatrix().get(origin + ">" + visit.getId());
                if (leg == null) { hard += 1_000_000; break; }
                long travel = buffered(plan, leg);
                Instant proposedDeparture = segment == null ? max(block.start(), Required.value(visit.getWindowStart().minus(Duration.ofMinutes(travel)))) : segment.departure();
                Instant start = segment == null ? proposedDeparture : segment.done();
                Instant arrival = max(Required.value(start.plus(Duration.ofMinutes(travel))), visit.getWindowStart());
                DayPlan.RoadLeg home = plan.getMatrix().get(visit.getId() + ">" + route.getId() + ":return");
                if (home == null) { hard += 1_000_000; break; }
                Instant homeReturn = arrival.plus(Duration.ofMinutes(visit.getDurationMinutes() + buffered(plan, home)));
                if (arrival.isBefore(visit.getWindowEnd()) && !homeReturn.isAfter(block.end())) {

                    segmentWaiting += Math.max(0, Duration.between(start.plus(Duration.ofMinutes(travel)), arrival).toMinutes());
                    departureSlack = Math.min(departureSlack, Duration.between(arrival, visit.getWindowEnd().minusNanos(1)).toMinutes() + segmentWaiting);
                    timings.add(new Timing(visit, segmentWaiting));
                    segmentDrive += travel;
                    segmentMeters += leg.meters();
                    segment = new Segment(visit.getId(), proposedDeparture, Required.value(arrival.plus(Duration.ofMinutes(visit.getDurationMinutes()))));
                    arrivals.put(visit.getId(), arrival);
                    placed = true;
                    break;
                }
                if (segment != null) {
                    DayPlan.RoadLeg returnHome = plan.getMatrix().get(segment.previous() + ">" + route.getId() + ":return");
                    if (returnHome == null) { hard += 1_000_000; break; }
                    Instant finish = segment.done().plus(Duration.ofMinutes(buffered(plan, returnHome)));
                    if (finish.isAfter(block.end())) hard += 1_000_000;
                    long delay = Math.max(0, Math.min(departureSlack, segmentWaiting));
                    retime(timings, delay, arrivals);
                    Instant departure = Required.value(segment.departure().plus(Duration.ofMinutes(delay)));
                    paid += Duration.between(departure, finish).toMinutes();
                    overtime += overtime(departure, finish, route.getShiftEnd());
                    drive += segmentDrive + buffered(plan, returnHome);
                    waiting += segmentWaiting - delay;
                    timings.clear(); departureSlack = Long.MAX_VALUE;
                    meters += segmentMeters + returnHome.meters();
                    segment = null;
                    segmentMeters = segmentDrive = segmentWaiting = 0;
                }
                blockIndex++;
            }
            if (!placed) hard += 1_000_000;
        }
        if (segment != null) {
            DayPlan.RoadLeg home = plan.getMatrix().get(segment.previous() + ">" + route.getId() + ":return");
            if (home == null) hard += 1_000_000;
            else {
                Instant finish = segment.done().plus(Duration.ofMinutes(buffered(plan, home)));
                if (finish.isAfter(blocks.get(blockIndex).end())) hard += 1_000_000;
                long delay = Math.max(0, Math.min(departureSlack, segmentWaiting));
                retime(timings, delay, arrivals);
                Instant departure = Required.value(segment.departure().plus(Duration.ofMinutes(delay)));
                paid += Duration.between(departure, finish).toMinutes();
                overtime += overtime(departure, finish, route.getShiftEnd());
                drive += segmentDrive + buffered(plan, home);
                waiting += segmentWaiting - delay;
                meters += segmentMeters + home.meters();
            }
        }
        if (paid > route.getMaxDailyMinutes()) hard += 1_000_000 + paid - route.getMaxDailyMinutes();
        if (overtime > route.getMaxOvertimeMinutes()) hard += 1_000_000 + overtime - route.getMaxOvertimeMinutes();
        long cents = Math.round((paid - overtime) * plan.getRegularHourly() * 100 / 60.0
                + overtime * plan.getOvertimeHourly() * 100 / 60.0
                + meters / 1609.344 * plan.getMileagePerMile() * 100);
        return new Result(hard, cents, arrivals, paid, overtime, drive, waiting, meters);
    }

    private static void retime(List<Timing> timings, long delay, Map<String, Instant> arrivals) {
        for (Timing timing : timings) {
            Instant arrival = Required.value(arrivals.get(timing.visit().getId()), "scored arrival");
            arrivals.put(timing.visit().getId(), Required.value(arrival.plus(Duration.ofMinutes(Math.max(0, delay - timing.waitingBefore())))));
        }
    }

    private static List<Block> available(TechRoute route) {
        Instant end = route.getShiftEnd().plus(Duration.ofMinutes(route.getMaxOvertimeMinutes()));
        List<Block> blocks = new ArrayList<>();
        Instant cursor = route.getShiftStart();
        List<TechRoute.Unavailable> unavailable = new ArrayList<>(route.getUnavailable());
        unavailable.sort(Comparator.comparing((TechRoute.Unavailable absence) -> absence.start()));
        for (TechRoute.Unavailable absence : unavailable) {
            if (absence.start().isAfter(cursor)) blocks.add(new Block(cursor, min(absence.start(), Required.value(end))));
            if (absence.end().isAfter(cursor)) cursor = absence.end();
            if (!cursor.isBefore(end)) break;
        }
        if (cursor.isBefore(end)) blocks.add(new Block(cursor, Required.value(end)));
        blocks.removeIf(block -> !block.start().isBefore(block.end()));
        return blocks;
    }
    private static long buffered(DayPlan plan, DayPlan.RoadLeg leg) {
        return (long) Math.ceil(leg.seconds() * (1 + plan.getTravelBufferPct()) / 60.0) + plan.getTravelBufferMinutes();
    }
    private static long overtime(Instant departure, Instant finish, Instant shiftEnd) {
        return Math.max(0, Duration.between(max(departure, shiftEnd), finish).toMinutes());
    }
    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
