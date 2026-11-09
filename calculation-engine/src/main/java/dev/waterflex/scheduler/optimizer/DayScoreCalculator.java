package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.Monetary;


import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Full recomputation oracle and reporting metrics, outside the solver move loop. */
public final class DayScoreCalculator {
    private DayScoreCalculator() { }
    public record Evaluation(long hardPenalty, long costCents, Map<String, Instant> arrivals, long paidMinutes,
                             long overtimeMinutes, long meters, long driveMinutes, long waitingMinutes, long bufferSeconds) { }

    /**
     * One route as the score reads it: the canonical timing when the route holds, otherwise the relaxed forward walk
     * with each violation counted, so a plan that does not hold is still read in numbers rather than refused.
     * {@code holds} is whether the walk found no road, lateness, availability or capacity violation (a qualification
     * violation leaves the timing intact). The arrivals are empty only when a road is missing, since no travel time
     * may be invented for timing.
     */
    public record RouteReading(String technicianId, boolean holds, long qualifications, long unavailableRoads,
                               long latenessSeconds, long availabilitySeconds, long capacityMinutes, long paidMinutes,
                               long overtimeMinutes, long driveMinutes, long waitingMinutes, long meters, long bufferSeconds,
                               Map<String, Instant> arrivals) {
        public RouteReading {
            Required.value(technicianId);
            arrivals = Required.value(Map.copyOf(arrivals));
        }
        /** This route's share of {@link Evaluation#hardPenalty()}. */
        public long hardPenalty() {
            return Math.addExact(Math.addExact(qualifications, unavailableRoads),
                    Math.addExact(Math.addExact(latenessSeconds, availabilitySeconds), capacityMinutes));
        }
    }

    /** Every route's reading, in plan order, from the same timing {@link #evaluate} sums. */
    public static List<RouteReading> routes(DayPlan plan) {
        plan.getFacts().validateEntities(plan, false);
        List<RouteReading> readings = new ArrayList<>();
        for (TechRoute route : plan.getRoutes()) {
            var result = RouteTimeline.evaluate(plan.getFacts(), Required.value(route));
            var violations = result.violations();
            boolean holds = violations.unavailableRoads() == 0 && violations.latenessSeconds() == 0
                    && violations.availabilitySeconds() == 0 && violations.capacityMinutes() == 0;
            readings.add(new RouteReading(route.getId(), holds, violations.qualifications(), violations.unavailableRoads(),
                    violations.latenessSeconds(), violations.availabilitySeconds(), violations.capacityMinutes(),
                    result.paidMinutes(), result.overtimeMinutes(), result.driveMinutes(), result.waitingMinutes(),
                    result.meters(), result.bufferSeconds(), result.arrivals()));
        }
        return Required.value(List.copyOf(readings));
    }

    public static Evaluation evaluate(DayPlan plan) {
        plan.getFacts().validateEntities(plan, false);
        long hard = plan.getUnassignedVisitIds().size(), paid = 0, overtime = 0, meters = 0, drive = 0, waiting = 0, buffer = 0;
        Map<String, Instant> arrivals = new HashMap<>();
        for (TechRoute route : plan.getRoutes()) {
            var result = RouteTimeline.evaluate(plan.getFacts(), Required.value(route));
            hard += result.hardPenalty();
            paid = Math.addExact(paid, result.paidMinutes()); overtime = Math.addExact(overtime, result.overtimeMinutes());
            meters = Math.addExact(meters, result.meters()); drive += result.driveMinutes(); waiting += result.waitingMinutes();
            buffer = Math.addExact(buffer, result.bufferSeconds());
            arrivals.putAll(result.arrivals());
        }
        long cents = Monetary.cents(paid, overtime, meters, plan.getRegularHourly(), plan.getOvertimeHourly(), plan.getMileagePerMile());
        return new Evaluation(hard, cents, arrivals, paid, overtime, meters, drive, waiting, buffer);
    }

    /**
     * One technician-day's broken rules in a plan, as the score reads them, and its overtime. For a route that does not
     * hold, the minutes come from the score's relaxed walk (a measure of how far it is off, not a timing), and a route
     * with a leg that has no road reads only that.
     *
     * @param unqualifiedVisits visits of a service the technician is not qualified for
     * @param unavailableRoads legs with no road between their points
     * @param latenessSeconds seconds past the end of an arrival window, summed over the late visits
     * @param outsideHoursSeconds seconds worked outside the technician's working hours: before the shift, past the shift
     *     and its allowed overtime, or during an absence
     * @param capacityMinutes paid minutes over the day's limit, plus overtime minutes over the allowed overtime
     * @param overtimeMinutes minutes back after the shift end
     */
    public record RouteViolations(String technicianId, long unqualifiedVisits, long unavailableRoads, long latenessSeconds,
                                  long outsideHoursSeconds, long capacityMinutes, long overtimeMinutes) {
        public RouteViolations {
            Required.value(technicianId, "technicianId");
            if (unqualifiedVisits < 0 || unavailableRoads < 0 || latenessSeconds < 0 || outsideHoursSeconds < 0 || capacityMinutes < 0 || overtimeMinutes < 0)
                throw new IllegalArgumentException("Negative route violation");
        }
        /** Whether the route keeps every rule (it may still carry overtime). */
        public boolean holds() {
            return unqualifiedVisits == 0 && unavailableRoads == 0 && latenessSeconds == 0 && outsideHoursSeconds == 0 && capacityMinutes == 0;
        }
    }

    /** Every route's violations and overtime, in the plan's route order; the hard penalties sum to {@link #evaluate}'s less the unassigned visits. */
    public static List<RouteViolations> violations(DayPlan plan) {
        plan.getFacts().validateEntities(plan, false);
        List<RouteViolations> found = new ArrayList<>();
        for (TechRoute route : plan.getRoutes()) {
            var result = RouteTimeline.evaluate(plan.getFacts(), Required.value(route), false);
            var broken = result.violations();
            found.add(new RouteViolations(route.getId(), broken.qualifications(), broken.unavailableRoads(), broken.latenessSeconds(),
                    broken.availabilitySeconds(), broken.capacityMinutes(), result.overtimeMinutes()));
        }
        return Required.value(List.copyOf(found));
    }
}
