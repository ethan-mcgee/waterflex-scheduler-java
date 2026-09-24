package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;


import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Full recomputation oracle and reporting metrics, outside the solver move loop. */
public final class DayScoreCalculator {
    private DayScoreCalculator() { }
    public record Evaluation(long hardPenalty, long costCents, Map<String, Instant> arrivals, long paidMinutes,
                             long overtimeMinutes, long meters, long driveMinutes, long waitingMinutes) { }

    public static Evaluation evaluate(DayPlan plan) {
        long hard = 0, paid = 0, overtime = 0, meters = 0, drive = 0, waiting = 0;
        Map<String, Instant> arrivals = new HashMap<>();
        for (TechRoute route : plan.getRoutes()) {
            var result = RouteTimeline.evaluate(plan, Required.value(route));
            hard += result.hardPenalty();
            paid += result.paidMinutes(); overtime += result.overtimeMinutes();
            meters += result.meters(); drive += result.driveMinutes(); waiting += result.waitingMinutes();
            arrivals.putAll(result.arrivals());
        }
        long cents = Math.round((paid - overtime) * plan.getRegularHourly() * 100 / 60.0
                + overtime * plan.getOvertimeHourly() * 100 / 60.0
                + meters / 1609.344 * plan.getMileagePerMile() * 100);
        return new Evaluation(hard, cents, arrivals, paid, overtime, meters, drive, waiting);
    }
}
