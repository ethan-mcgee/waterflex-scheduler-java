package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Independently recomputes route timing, coverage, qualifications, limits, and cost. */
public final class RouteEvaluator {
    public record Result(boolean feasible, long costCents, Map<String, Instant> arrivals,
                         long paidMinutes, long overtimeMinutes, long driveMinutes, long waitingMinutes, long meters,
                         Map<String, List<WorkingSegment>> segments) { }
    public record WorkingSegment(Instant departure, Instant returnedAt, List<String> visitIds) { }
    private record Segment(String previous, Instant departure, Instant done) { }
    private record Timing(PlanVisit visit, Instant arrival, long waitingBefore) { }
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
        Map<String, List<WorkingSegment>> segments = new LinkedHashMap<>();
        for (TechRoute route : plan.getRoutes()) {
            for (PlanVisit visit : route.getVisits())
                if (!seen.add(visit.getId()) || !expected.contains(visit.getId())) feasible = false;
            List<WorkingSegment> routeSegments = new ArrayList<>();
            RouteResult result = validateRoute(plan, route, arrivals, routeSegments);
            segments.put(route.getId(), Required.value(List.copyOf(routeSegments)));
            feasible &= result.feasible();
            paid += result.paid(); overtime += result.overtime(); drive += result.drive();
            waiting += result.waiting(); meters += result.meters();
        }
        feasible &= seen.equals(expected) && arrivals.keySet().equals(expected);
        long cents = Math.round((paid - overtime) * plan.getRegularHourly() * 100 / 60.0
                + overtime * plan.getOvertimeHourly() * 100 / 60.0
                + meters / 1609.344 * plan.getMileagePerMile() * 100);
        return new Result(feasible, cents, Required.value(Map.copyOf(arrivals)), paid, overtime, drive, waiting, meters, Required.value(Map.copyOf(segments)));
    }

    private static RouteResult validateRoute(DayPlan plan, TechRoute route, Map<String, Instant> arrivals, List<WorkingSegment> segments) {
        List<Work> work = workingIntervals(route);
        long paid = 0, overtime = 0, drive = 0, waiting = 0, meters = 0;
        long segmentDrive = 0, segmentWaiting = 0, segmentMeters = 0;
        List<Timing> segmentTimings = new ArrayList<>();
        int interval = 0;
        Segment segment = null;
        boolean feasible = true;
        for (PlanVisit visit : route.getVisits()) {
            if (!route.getQualifiedServiceIds().contains(visit.getServiceId())) feasible = false;
            boolean placed = false;
            while (interval < work.size()) {
                Work shift = work.get(interval);
                String from = segment == null ? route.getId() : segment.previous();
                DayPlan.RoadLeg road = plan.getMatrix().get(from + ">" + visit.getId());
                DayPlan.RoadLeg returnHome = plan.getMatrix().get(visit.getId() + ">" + route.getId() + ":return");
                if (road == null || returnHome == null) { feasible = false; break; }
                long travel = travel(plan, road);
                Instant depart = segment == null ? latest(shift.start(), Required.value(visit.getWindowStart().minus(Duration.ofMinutes(travel)))) : segment.departure();
                Instant fromTime = segment == null ? depart : segment.done();
                Instant arrival = latest(Required.value(fromTime.plus(Duration.ofMinutes(travel))), visit.getWindowStart());
                Instant doneAtHome = arrival.plus(Duration.ofMinutes(visit.getDurationMinutes() + travel(plan, returnHome)));
                if (arrival.isBefore(visit.getWindowEnd()) && !doneAtHome.isAfter(shift.end())) {

                    segmentWaiting += Math.max(0, Duration.between(fromTime.plus(Duration.ofMinutes(travel)), arrival).toMinutes());
                    segmentTimings.add(new Timing(visit, arrival, segmentWaiting));
                    segmentDrive += travel; segmentMeters += road.meters();
                    segment = new Segment(visit.getId(), depart, Required.value(arrival.plus(Duration.ofMinutes(visit.getDurationMinutes())))); arrivals.put(visit.getId(), arrival);
                    placed = true;
                    break;
                }
                if (segment != null) {
                    DayPlan.RoadLeg home = plan.getMatrix().get(segment.previous() + ">" + route.getId() + ":return");
                    if (home == null) { feasible = false; break; }
                    Instant back = segment.done().plus(Duration.ofMinutes(travel(plan, home)));
                    if (back.isAfter(shift.end())) feasible = false;
                    long delay = delayDeparture(segmentTimings, segmentWaiting, arrivals);
                    Instant departure = Required.value(segment.departure().plus(Duration.ofMinutes(delay)));
                    segments.add(new WorkingSegment(departure, Required.value(back),
                            Required.value(segmentTimings.stream().<String>map((Timing timing) -> timing.visit().getId()).toList())));
                    paid += Duration.between(departure, back).toMinutes();
                    overtime += Math.max(0, Duration.between(latest(departure, route.getShiftEnd()), back).toMinutes());
                    drive += segmentDrive + travel(plan, home); waiting += segmentWaiting - delay; meters += segmentMeters + home.meters();
                    segmentTimings.clear();
                    segment = null;
                    segmentDrive = segmentWaiting = segmentMeters = 0;
                }
                interval++;
            }
            if (!placed) feasible = false;
        }
        if (segment != null) {
            DayPlan.RoadLeg home = plan.getMatrix().get(segment.previous() + ">" + route.getId() + ":return");
            if (home == null || interval >= work.size()) feasible = false;
            else {
                Instant back = segment.done().plus(Duration.ofMinutes(travel(plan, home)));
                if (back.isAfter(work.get(interval).end())) feasible = false;
                long delay = delayDeparture(segmentTimings, segmentWaiting, arrivals);
                Instant departure = Required.value(segment.departure().plus(Duration.ofMinutes(delay)));
                segments.add(new WorkingSegment(departure, Required.value(back),
                        Required.value(segmentTimings.stream().<String>map((Timing timing) -> timing.visit().getId()).toList())));
                paid += Duration.between(departure, back).toMinutes();
                overtime += Math.max(0, Duration.between(latest(departure, route.getShiftEnd()), back).toMinutes());
                drive += segmentDrive + travel(plan, home); waiting += segmentWaiting - delay; meters += segmentMeters + home.meters();
            }
        }
        if (paid > route.getMaxDailyMinutes() || overtime > route.getMaxOvertimeMinutes()) feasible = false;
        return new RouteResult(feasible, paid, overtime, drive, waiting, meters);
    }

    // Absorb waiting without moving the segment's return time. The earliest equivalent
    // departure is the first point at which all removable waiting has disappeared.
    private static long delayDeparture(List<Timing> timings, long waiting, Map<String, Instant> arrivals) {
        long delay = waiting;
        for (Timing timing : timings) {
            long slack = Duration.between(timing.arrival(), timing.visit().getWindowEnd().minusNanos(1)).toMinutes();
            delay = Math.min(delay, slack + timing.waitingBefore());
        }
        delay = Math.max(0, delay);
        for (Timing timing : timings) arrivals.put(timing.visit().getId(), Required.value(timing.arrival()
                .plus(Duration.ofMinutes(Math.max(0, delay - timing.waitingBefore())))));
        return delay;
    }

    private static List<Work> workingIntervals(TechRoute route) {
        List<Work> work = new ArrayList<>();
        Instant end = route.getShiftEnd().plus(Duration.ofMinutes(route.getMaxOvertimeMinutes()));
        Instant cursor = route.getShiftStart();
        List<TechRoute.Unavailable> absences = new ArrayList<>(route.getUnavailable());
        absences.sort(Comparator.comparing((TechRoute.Unavailable absence) -> absence.start()));
        for (TechRoute.Unavailable absence : absences) {
            Instant beforeEnd = absence.start().isBefore(end) ? absence.start() : end;
            if (cursor.isBefore(beforeEnd)) work.add(new Work(cursor, Required.value(beforeEnd)));
            if (cursor.isBefore(absence.end())) cursor = absence.end();
            if (!cursor.isBefore(end)) break;
        }
        if (cursor.isBefore(end)) work.add(new Work(cursor, Required.value(end)));
        return work;
    }

    private static long travel(DayPlan plan, DayPlan.RoadLeg road) {
        return (long) Math.ceil(road.seconds() * (1 + plan.getTravelBufferPct()) / 60.0) + plan.getTravelBufferMinutes();
    }
    private static Instant latest(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
}
