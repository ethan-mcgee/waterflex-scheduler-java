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
