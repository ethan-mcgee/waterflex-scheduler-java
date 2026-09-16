package dev.waterflex.scheduler.optimizer;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Recomputes a proposed route without using the Timefold score director. */
public final class RouteEvaluator {
    public record Result(boolean feasible, long costCents, Map<String, Instant> arrivals) { }

    private RouteEvaluator() { }

    public static Result evaluate(DayPlan plan) {
        Map<String, Instant> arrivals = new HashMap<>();
        Set<String> seen = new HashSet<>();
        long totalCents = 0;
        boolean feasible = true;
        for (TechRoute route : plan.getRoutes()) {
            if (route.getVisits().isEmpty()) continue;
            Instant time = route.getShiftStart();
            Instant departure = time;
            String previous = route.getId();
            long meters = 0;
            for (int index = 0; index < route.getVisits().size(); index++) {
                PlanVisit visit = route.getVisits().get(index);
                if (!seen.add(visit.getId()) || !route.getQualifiedServiceIds().contains(visit.getServiceId())) feasible = false;
                DayPlan.RoadLeg road = plan.getMatrix().get(previous + ">" + visit.getId());
                if (road == null) return new Result(false, Long.MAX_VALUE, Map.of());
                long travelMinutes = Math.round(Math.ceil(road.seconds() * (1 + plan.getTravelBufferPct()) / 60.0))
                        + plan.getTravelBufferMinutes();
                if (index == 0) {
                    departure = visit.getWindowStart().minus(Duration.ofMinutes(travelMinutes));
                    if (departure.isBefore(route.getShiftStart())) departure = route.getShiftStart();
                    time = departure;
                }
                time = time.plus(Duration.ofMinutes(travelMinutes));
                if (time.isBefore(visit.getWindowStart())) time = visit.getWindowStart();
                if (!time.isBefore(visit.getWindowEnd())) feasible = false;
                arrivals.put(visit.getId(), time);
                time = time.plus(Duration.ofMinutes(visit.getDurationMinutes()));
                meters += road.meters();
                previous = visit.getId();
                if (!route.getId().equals(visit.getOriginalTechnicianId())) totalCents += 100;
                else if (Math.abs(Duration.between(visit.getOriginalPlannedStart(), arrivals.get(visit.getId())).toMinutes()) > 15) totalCents += 20;
            }
            DayPlan.RoadLeg home = plan.getMatrix().get(previous + ">" + route.getId());
            if (home == null) return new Result(false, Long.MAX_VALUE, Map.of());
            time = time.plus(Duration.ofMinutes((long) Math.ceil(home.seconds() * (1 + plan.getTravelBufferPct()) / 60.0)
                    + plan.getTravelBufferMinutes()));
            meters += home.meters();
            long paid = Duration.between(departure, time).toMinutes();
            long overtime = Math.max(0, Duration.between(route.getShiftEnd(), time).toMinutes());
            if (paid > route.getMaxDailyMinutes() || overtime > route.getMaxOvertimeMinutes()) feasible = false;
            totalCents += Math.round((paid - overtime) * plan.getRegularHourly() * 100 / 60.0
                    + overtime * plan.getOvertimeHourly() * 100 / 60.0
                    + meters / 1609.344 * plan.getMileagePerMile() * 100);
        }
        if (seen.size() != plan.getVisits().size()) feasible = false;
        return new Result(feasible, totalCents, arrivals);
    }
}
