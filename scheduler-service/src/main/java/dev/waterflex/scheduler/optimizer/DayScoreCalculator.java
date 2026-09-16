package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.calculator.EasyScoreCalculator;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class DayScoreCalculator implements EasyScoreCalculator<DayPlan, HardSoftScore> {
    public record Evaluation(long hardPenalty, long costCents, Map<String, Instant> arrivals, long paidMinutes,
                             long overtimeMinutes, long meters) { }

    @Override
    public HardSoftScore calculateScore(DayPlan plan) {
        Evaluation result = evaluate(plan);
        return HardSoftScore.of(-result.hardPenalty(), -result.costCents());
    }

    public static Evaluation evaluate(DayPlan plan) {
        long hard = 0, cents = 0, totalPaid = 0, totalOvertime = 0, totalMeters = 0;
        Map<String, Instant> arrivals = new HashMap<>();
        for (TechRoute route : plan.getRoutes()) {
            if (route.getVisits() == null || route.getVisits().isEmpty()) continue;
            String previous = route.getId();
            Instant now = route.getShiftStart();
            Instant departure = now;
            long meters = 0;
            for (int index = 0; index < route.getVisits().size(); index++) {
                PlanVisit visit = route.getVisits().get(index);
                DayPlan.RoadLeg leg = plan.getMatrix().get(previous + ">" + visit.getId());
                if (leg == null) { hard += 1_000_000; continue; }
                long travel = (long) Math.ceil(leg.seconds() * (1 + plan.getTravelBufferPct()) / 60.0) + plan.getTravelBufferMinutes();
                if (index == 0) {
                    departure = visit.getWindowStart().minus(Duration.ofMinutes(travel));
                    if (departure.isBefore(route.getShiftStart())) departure = route.getShiftStart();
                    now = departure;
                }
                now = now.plus(Duration.ofMinutes(travel));
                if (now.isBefore(visit.getWindowStart())) now = visit.getWindowStart();
                arrivals.put(visit.getId(), now);
                if (!now.isBefore(visit.getWindowEnd())) hard += 1_000_000 + Duration.between(visit.getWindowEnd(), now).toMinutes();
                if (!route.getQualifiedServiceIds().contains(visit.getServiceId())) hard += 1_000_000;
                if (!route.getId().equals(visit.getOriginalTechnicianId())) cents += 100;
                else if (Math.abs(Duration.between(visit.getOriginalPlannedStart(), now).toMinutes()) > 15) cents += 20;
                now = now.plus(Duration.ofMinutes(visit.getDurationMinutes()));
                meters += leg.meters();
                previous = visit.getId();
            }
            DayPlan.RoadLeg home = plan.getMatrix().get(previous + ">" + route.getId());
            if (home == null) { hard += 1_000_000; continue; }
            now = now.plus(Duration.ofMinutes((long) Math.ceil(home.seconds() * (1 + plan.getTravelBufferPct()) / 60.0) + plan.getTravelBufferMinutes()));
            meters += home.meters();
            long paid = Duration.between(departure, now).toMinutes();
            long overtime = Math.max(0, Duration.between(route.getShiftEnd(), now).toMinutes());
            if (paid > route.getMaxDailyMinutes()) hard += 1_000_000 + paid - route.getMaxDailyMinutes();
            if (overtime > route.getMaxOvertimeMinutes()) hard += 1_000_000 + overtime - route.getMaxOvertimeMinutes();
            cents += Math.round((paid - overtime) * plan.getRegularHourly() * 100 / 60.0
                    + overtime * plan.getOvertimeHourly() * 100 / 60.0
                    + meters / 1609.344 * plan.getMileagePerMile() * 100);
            totalPaid += paid; totalOvertime += overtime; totalMeters += meters;
        }
        return new Evaluation(hard, cents, arrivals, totalPaid, totalOvertime, totalMeters);
    }
}
