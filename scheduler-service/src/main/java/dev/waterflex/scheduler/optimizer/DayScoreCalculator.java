package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.Monetary;


import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Full recomputation oracle and reporting metrics, outside the solver move loop. */
public final class DayScoreCalculator {
    private DayScoreCalculator() { }
    public record Evaluation(long hardPenalty, long costCents, Map<String, Instant> arrivals, long paidMinutes,
                             long overtimeMinutes, long meters, long driveMinutes, long waitingMinutes) { }

    public static Evaluation evaluate(DayPlan plan) {
        plan.getFacts().validateEntities(plan, false);
        long hard = 0, paid = 0, overtime = 0, meters = 0, drive = 0, waiting = 0;
        Map<String, Instant> arrivals = new HashMap<>();
        for (TechRoute route : plan.getRoutes()) {
            var result = RouteTimeline.evaluate(plan, Required.value(route));
            hard += result.hardPenalty();
            paid = Math.addExact(paid, result.paidMinutes()); overtime = Math.addExact(overtime, result.overtimeMinutes());
            meters = Math.addExact(meters, result.meters()); drive += result.driveMinutes(); waiting += result.waitingMinutes();
            arrivals.putAll(result.arrivals());
        }
        long cents = Monetary.cents(paid, overtime, meters, plan.getRegularHourly(), plan.getOvertimeHourly(), plan.getMileagePerMile());
        return new Evaluation(hard, cents, arrivals, paid, overtime, meters, drive, waiting);
    }
}
