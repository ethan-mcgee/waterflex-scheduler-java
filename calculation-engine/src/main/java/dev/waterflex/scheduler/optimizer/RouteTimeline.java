package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.Monetary;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Exact feasible timing, with an explicitly diagnostic relaxed trajectory for repair gradients. */
final class RouteTimeline {
    record Violations(long qualifications, long unavailableRoads, long latenessSeconds,
                      long availabilitySeconds, long capacityMinutes) {
        long total() { return Math.addExact(Math.addExact(qualifications, unavailableRoads),
                Math.addExact(Math.addExact(latenessSeconds, availabilitySeconds), capacityMinutes)); }
    }
    record Result(Violations violations, long costCents, Map<String, Instant> arrivals,
                  long paidMinutes, long overtimeMinutes, long driveMinutes, long waitingMinutes, long meters) {
        long hardPenalty() { return violations.total(); }
    }
    private RouteTimeline() { }
    static Result evaluate(DayPlan plan, TechRoute route) {
        List<RouteTimingSearch.Block> blocks = available(route);
        var canonical = RouteTimingSearch.solve(plan, route, blocks);
        long qualifications = route.getVisits().stream().filter(visit -> !route.getQualifiedServiceIds().contains(visit.getServiceId())).count();
        if (canonical != null) {
            return result(plan, new Violations(qualifications, 0, 0, 0, 0), canonical.arrivals(),
                    canonical.paid(), canonical.overtime(), canonical.drive(), canonical.waiting(), canonical.meters());
        }
        // This trajectory is a direction of improvement, never an independent feasible-placement proof.
        // A categorical road failure has no known travel duration. Do not invent one for timing.
        long unavailableRoads = 0;
        String previous = route.getId();
        for (PlanVisit visit : route.getVisits()) {
            if (!plan.getMatrix().containsKey(previous + ">" + visit.getId())) unavailableRoads++;
            previous = visit.getId();
        }
        if (!route.getVisits().isEmpty() && !plan.getMatrix().containsKey(previous + ">" + route.getId() + ":return")) unavailableRoads++;
        if (unavailableRoads > 0) return result(plan, new Violations(qualifications, unavailableRoads, 0, 0, 0), Required.value(Map.of()), 0, 0, 0, 0, 0);
        if (route.getVisits().isEmpty()) return result(plan, new Violations(qualifications, 0, 0, 0, 0), Required.value(Map.of()), 0, 0, 0, 0, 0);
        PlanVisit first = Required.value(route.getVisits().getFirst());
        long initialTravel = buffered(plan, Required.value(plan.getMatrix().get(route.getId() + ">" + first.getId())));
        Instant departure = later(route.getShiftStart(), Required.value(first.getWindowStart().minusSeconds(Math.multiplyExact(initialTravel, 60))));
        Instant cursor = departure;
        previous = route.getId();
        long drive = 0, waiting = 0, meters = 0, lateness = 0;
        Map<String, Instant> arrivals = new LinkedHashMap<>();
        for (PlanVisit visit : route.getVisits()) {
            DayPlan.RoadLeg road = Required.value(plan.getMatrix().get(previous + ">" + visit.getId()));
            long travel = buffered(plan, road);
            Instant earliest = Required.value(cursor.plusSeconds(Math.multiplyExact(travel, 60)));
            Instant arrival = later(earliest, visit.getWindowStart());
            if (!arrival.isBefore(visit.getWindowEnd())) lateness = Math.addExact(lateness,
                    Math.addExact(Duration.between(visit.getWindowEnd(), arrival).getSeconds(), 1));
            waiting = Math.addExact(waiting, Duration.between(earliest, arrival).toMinutes());
            drive = Math.addExact(drive, travel); meters = Math.addExact(meters, road.meters());
            arrivals.put(visit.getId(), arrival);
            cursor = Required.value(arrival.plusSeconds(visit.getDurationMinutes() * 60L)); previous = visit.getId();
        }
        DayPlan.RoadLeg home = Required.value(plan.getMatrix().get(previous + ">" + route.getId() + ":return"));
        long returnMinutes = buffered(plan, home);
        Instant returned = Required.value(cursor.plusSeconds(Math.multiplyExact(returnMinutes, 60)));
        drive = Math.addExact(drive, returnMinutes); meters = Math.addExact(meters, home.meters());
        long paid = Duration.between(departure, returned).toMinutes();
        long overtime = Math.max(0, Duration.between(later(departure, route.getShiftEnd()), returned).toMinutes());
        Duration permitted = Duration.ZERO;
        for (RouteTimingSearch.Block block : blocks) {
            Instant start = later(departure, block.start()), end = earlier(returned, block.end());
            if (start.isBefore(end)) permitted = permitted.plus(Duration.between(start, end));
        }
        Duration unavailable = Duration.between(departure, returned).minus(permitted);
        long unavailableSeconds = unavailable.isZero() ? 0 : Math.addExact(unavailable.getSeconds(), unavailable.getNano() == 0 ? 0 : 1);
        long capacity = Math.addExact(Math.max(0, paid - route.getMaxDailyMinutes()), Math.max(0, overtime - route.getMaxOvertimeMinutes()));
        return result(plan, new Violations(qualifications, 0, lateness, unavailableSeconds, capacity), arrivals, paid, overtime, drive, waiting, meters);
    }
    private static Result result(DayPlan plan, Violations violations, Map<String, Instant> arrivals,
            long paid, long overtime, long drive, long waiting, long meters) {
        long cents = Monetary.cents(paid, overtime, meters, plan.getRegularHourly(), plan.getOvertimeHourly(), plan.getMileagePerMile());
        return new Result(violations, cents, Required.value(Map.copyOf(arrivals)), paid, overtime, drive, waiting, meters);
    }
    private static List<RouteTimingSearch.Block> available(TechRoute route) {
        Instant end = Required.value(route.getShiftEnd().plusSeconds(route.getMaxOvertimeMinutes() * 60L));
        List<RouteTimingSearch.Block> blocks = new ArrayList<>();
        Instant cursor = route.getShiftStart();
        List<TechRoute.Unavailable> unavailable = new ArrayList<>(route.getUnavailable());
        unavailable.sort(Comparator.comparing((TechRoute.Unavailable absence) -> absence.start()));
        for (TechRoute.Unavailable absence : unavailable) {
            if (absence.start().isAfter(cursor)) blocks.add(new RouteTimingSearch.Block(cursor, earlier(absence.start(), end)));
            if (absence.end().isAfter(cursor)) cursor = absence.end();
            if (!cursor.isBefore(end)) break;
        }
        if (cursor.isBefore(end)) blocks.add(new RouteTimingSearch.Block(cursor, end));
        blocks.removeIf(block -> !block.start().isBefore(block.end())); return blocks;
    }
    private static long buffered(DayPlan plan, DayPlan.RoadLeg leg) {
        return dev.waterflex.scheduler.Monetary.bufferedMinutes(leg.seconds(), plan.getTravelBufferPct(), plan.getTravelBufferMinutes());
    }
    private static Instant later(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
