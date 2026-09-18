package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.calculator.EasyScoreCalculator;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class DayScoreCalculator implements EasyScoreCalculator<DayPlan, HardSoftScore> {
    public record Evaluation(long hardPenalty, long costCents, Map<String, Instant> arrivals, long paidMinutes,
                             long overtimeMinutes, long meters, long driveMinutes, long waitingMinutes) { }

    @Override
    public HardSoftScore calculateScore(DayPlan plan) {
        Evaluation result = evaluate(plan);
        return HardSoftScore.of(-result.hardPenalty(), -result.costCents());
    }

    public static Evaluation evaluate(DayPlan plan) {
        long hard = 0, cents = 0, paid = 0, overtime = 0, meters = 0, drive = 0, waiting = 0;
        Map<String, Instant> arrivals = new HashMap<>();
        for (TechRoute route : plan.getRoutes()) {
            var result = RouteTimeline.evaluate(plan, route);
            hard += result.hardPenalty(); cents += result.costCents();
            paid += result.paidMinutes(); overtime += result.overtimeMinutes();
            meters += result.meters(); drive += result.driveMinutes(); waiting += result.waitingMinutes();
            arrivals.putAll(result.arrivals());
        }
        return new Evaluation(hard, cents, arrivals, paid, overtime, meters, drive, waiting);
    }
}
