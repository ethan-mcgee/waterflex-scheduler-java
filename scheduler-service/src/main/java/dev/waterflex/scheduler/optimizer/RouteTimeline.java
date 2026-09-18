package dev.waterflex.scheduler.optimizer;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Evaluates paid home-to-home work intervals separated by approved absences. */
final class RouteTimeline {
    record Result(long hardPenalty, long costCents, Map<String, Instant> arrivals,
                  long paidMinutes, long overtimeMinutes, long driveMinutes, long waitingMinutes, long meters) { }
    private record Block(Instant start, Instant end) { }

    private RouteTimeline() { }

    static Result evaluate(DayPlan plan, TechRoute route) {
        List<Block> blocks = available(route);
        Map<String, Instant> arrivals = new HashMap<>();
        long hard = 0, paid = 0, overtime = 0, drive = 0, waiting = 0, meters = 0;
        int blockIndex = 0;
        String previous = null;
        Instant time = null, departure = null;
        long segmentMeters = 0, segmentDrive = 0, segmentWaiting = 0;
        for (PlanVisit visit : route.getVisits()) {
            if (!route.getQualifiedServiceIds().contains(visit.getServiceId())) hard += 1_000_000;
            boolean placed = false;
            while (blockIndex < blocks.size()) {
                Block block = blocks.get(blockIndex);
                String origin = previous == null ? route.getId() : previous;
                DayPlan.RoadLeg leg = plan.getMatrix().get(origin + ">" + visit.getId());
                if (leg == null) { hard += 1_000_000; break; }
                long travel = buffered(plan, leg);
                Instant proposedDeparture = previous == null ? max(block.start(), visit.getWindowStart().minus(Duration.ofMinutes(travel))) : departure;
                Instant start = previous == null ? proposedDeparture : time;
                Instant arrival = max(start.plus(Duration.ofMinutes(travel)), visit.getWindowStart());
                DayPlan.RoadLeg home = plan.getMatrix().get(visit.getId() + ">" + route.getId());
                if (home == null) { hard += 1_000_000; break; }
                Instant homeReturn = arrival.plus(Duration.ofMinutes(visit.getDurationMinutes() + buffered(plan, home)));
                if (arrival.isBefore(visit.getWindowEnd()) && !homeReturn.isAfter(block.end())) {
                    if (previous == null) departure = proposedDeparture;
                    segmentWaiting += Math.max(0, Duration.between(start.plus(Duration.ofMinutes(travel)), arrival).toMinutes());
                    segmentDrive += travel;
                    segmentMeters += leg.meters();
                    previous = visit.getId();
                    time = arrival.plus(Duration.ofMinutes(visit.getDurationMinutes()));
                    arrivals.put(visit.getId(), arrival);
                    placed = true;
                    break;
                }
                if (previous != null) {
                    DayPlan.RoadLeg returnHome = plan.getMatrix().get(previous + ">" + route.getId());
                    if (returnHome == null) { hard += 1_000_000; break; }
                    Instant finish = time.plus(Duration.ofMinutes(buffered(plan, returnHome)));
                    if (finish.isAfter(block.end())) hard += 1_000_000;
                    paid += Duration.between(departure, finish).toMinutes();
                    overtime += overtime(departure, finish, route.getShiftEnd());
                    drive += segmentDrive + buffered(plan, returnHome);
                    waiting += segmentWaiting;
                    meters += segmentMeters + returnHome.meters();
                    previous = null; time = null; departure = null;
                    segmentMeters = segmentDrive = segmentWaiting = 0;
                }
                blockIndex++;
            }
            if (!placed) hard += 1_000_000;
        }
        if (previous != null) {
            DayPlan.RoadLeg home = plan.getMatrix().get(previous + ">" + route.getId());
            if (home == null) hard += 1_000_000;
            else {
                Instant finish = time.plus(Duration.ofMinutes(buffered(plan, home)));
                if (finish.isAfter(blocks.get(blockIndex).end())) hard += 1_000_000;
                paid += Duration.between(departure, finish).toMinutes();
                overtime += overtime(departure, finish, route.getShiftEnd());
                drive += segmentDrive + buffered(plan, home);
                waiting += segmentWaiting;
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

    private static List<Block> available(TechRoute route) {
        Instant end = route.getShiftEnd().plus(Duration.ofMinutes(route.getMaxOvertimeMinutes()));
        List<Block> blocks = new ArrayList<>();
        Instant cursor = route.getShiftStart();
        List<TechRoute.Unavailable> unavailable = new ArrayList<>(route.getUnavailable());
        unavailable.sort(Comparator.comparing(TechRoute.Unavailable::start));
        for (TechRoute.Unavailable absence : unavailable) {
            if (absence.start().isAfter(cursor)) blocks.add(new Block(cursor, min(absence.start(), end)));
            if (absence.end().isAfter(cursor)) cursor = absence.end();
            if (!cursor.isBefore(end)) break;
        }
        if (cursor.isBefore(end)) blocks.add(new Block(cursor, end));
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
