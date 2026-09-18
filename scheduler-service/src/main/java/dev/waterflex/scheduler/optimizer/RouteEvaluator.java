package dev.waterflex.scheduler.optimizer;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Independently recomputes route timing, coverage, qualifications, limits, and cost. */
public final class RouteEvaluator {
    public record Result(boolean feasible, long costCents, Map<String, Instant> arrivals,
                         long paidMinutes, long overtimeMinutes, long driveMinutes, long waitingMinutes, long meters) { }
    private record Work(Instant start, Instant end) { }
    private record RouteResult(boolean feasible, long paid, long overtime, long drive, long waiting, long meters) { }
    private RouteEvaluator() { }

    public static Result evaluate(DayPlan plan) {
        Set<String> expected = new HashSet<>();
        boolean feasible = true;
        for (PlanVisit visit : plan.getVisits()) if (!expected.add(visit.getId())) feasible = false;
        Set<String> seen = new HashSet<>();
        Map<String, Instant> arrivals = new HashMap<>();
        long paid = 0, overtime = 0, drive = 0, waiting = 0, meters = 0;
        for (TechRoute route : plan.getRoutes()) {
            for (PlanVisit visit : route.getVisits())
                if (!seen.add(visit.getId()) || !expected.contains(visit.getId())) feasible = false;
            RouteResult result = validateRoute(plan, route, arrivals);
            feasible &= result.feasible();
            paid += result.paid(); overtime += result.overtime(); drive += result.drive();
            waiting += result.waiting(); meters += result.meters();
        }
        feasible &= seen.equals(expected) && arrivals.keySet().equals(expected);
        long cents = Math.round((paid - overtime) * plan.getRegularHourly() * 100 / 60.0
                + overtime * plan.getOvertimeHourly() * 100 / 60.0
                + meters / 1609.344 * plan.getMileagePerMile() * 100);
        return new Result(feasible, cents, arrivals, paid, overtime, drive, waiting, meters);
    }

    private static RouteResult validateRoute(DayPlan plan, TechRoute route, Map<String, Instant> arrivals) {
        List<Work> work = workingIntervals(route);
        long paid = 0, overtime = 0, drive = 0, waiting = 0, meters = 0;
        long segmentDrive = 0, segmentWaiting = 0, segmentMeters = 0;
        int interval = 0;
        String previous = null;
        Instant departure = null, serviceDone = null;
        boolean feasible = true;
        for (PlanVisit visit : route.getVisits()) {
            if (!route.getQualifiedServiceIds().contains(visit.getServiceId())) feasible = false;
            boolean placed = false;
            while (interval < work.size()) {
                Work shift = work.get(interval);
                String from = previous == null ? route.getId() : previous;
                DayPlan.RoadLeg road = plan.getMatrix().get(from + ">" + visit.getId());
                DayPlan.RoadLeg returnHome = plan.getMatrix().get(visit.getId() + ">" + route.getId());
                if (road == null || returnHome == null) { feasible = false; break; }
                long travel = travel(plan, road);
                Instant depart = previous == null ? latest(shift.start(), visit.getWindowStart().minus(Duration.ofMinutes(travel))) : departure;
                Instant fromTime = previous == null ? depart : serviceDone;
                Instant arrival = latest(fromTime.plus(Duration.ofMinutes(travel)), visit.getWindowStart());
                Instant doneAtHome = arrival.plus(Duration.ofMinutes(visit.getDurationMinutes() + travel(plan, returnHome)));
                if (arrival.isBefore(visit.getWindowEnd()) && !doneAtHome.isAfter(shift.end())) {
                    if (previous == null) departure = depart;
                    segmentWaiting += Math.max(0, Duration.between(fromTime.plus(Duration.ofMinutes(travel)), arrival).toMinutes());
                    segmentDrive += travel; segmentMeters += road.meters();
                    serviceDone = arrival.plus(Duration.ofMinutes(visit.getDurationMinutes()));
                    previous = visit.getId(); arrivals.put(visit.getId(), arrival);
                    placed = true;
                    break;
                }
                if (previous != null) {
                    DayPlan.RoadLeg home = plan.getMatrix().get(previous + ">" + route.getId());
                    if (home == null) { feasible = false; break; }
                    Instant back = serviceDone.plus(Duration.ofMinutes(travel(plan, home)));
                    if (back.isAfter(shift.end())) feasible = false;
                    paid += Duration.between(departure, back).toMinutes();
                    overtime += Math.max(0, Duration.between(latest(departure, route.getShiftEnd()), back).toMinutes());
                    drive += segmentDrive + travel(plan, home); waiting += segmentWaiting; meters += segmentMeters + home.meters();
                    previous = null; departure = null; serviceDone = null;
                    segmentDrive = segmentWaiting = segmentMeters = 0;
                }
                interval++;
            }
            if (!placed) feasible = false;
        }
        if (previous != null) {
            DayPlan.RoadLeg home = plan.getMatrix().get(previous + ">" + route.getId());
            if (home == null || interval >= work.size()) feasible = false;
            else {
                Instant back = serviceDone.plus(Duration.ofMinutes(travel(plan, home)));
                if (back.isAfter(work.get(interval).end())) feasible = false;
                paid += Duration.between(departure, back).toMinutes();
                overtime += Math.max(0, Duration.between(latest(departure, route.getShiftEnd()), back).toMinutes());
                drive += segmentDrive + travel(plan, home); waiting += segmentWaiting; meters += segmentMeters + home.meters();
            }
        }
        if (paid > route.getMaxDailyMinutes() || overtime > route.getMaxOvertimeMinutes()) feasible = false;
        return new RouteResult(feasible, paid, overtime, drive, waiting, meters);
    }

    private static List<Work> workingIntervals(TechRoute route) {
        List<Work> work = new ArrayList<>();
        Instant end = route.getShiftEnd().plus(Duration.ofMinutes(route.getMaxOvertimeMinutes()));
        Instant cursor = route.getShiftStart();
        List<TechRoute.Unavailable> absences = new ArrayList<>(route.getUnavailable());
        absences.sort(Comparator.comparing(TechRoute.Unavailable::start));
        for (TechRoute.Unavailable absence : absences) {
            Instant beforeEnd = absence.start().isBefore(end) ? absence.start() : end;
            if (cursor.isBefore(beforeEnd)) work.add(new Work(cursor, beforeEnd));
            if (cursor.isBefore(absence.end())) cursor = absence.end();
            if (!cursor.isBefore(end)) break;
        }
        if (cursor.isBefore(end)) work.add(new Work(cursor, end));
        return work;
    }

    private static long travel(DayPlan plan, DayPlan.RoadLeg road) {
        return (long) Math.ceil(road.seconds() * (1 + plan.getTravelBufferPct()) / 60.0) + plan.getTravelBufferMinutes();
    }
    private static Instant latest(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
}
