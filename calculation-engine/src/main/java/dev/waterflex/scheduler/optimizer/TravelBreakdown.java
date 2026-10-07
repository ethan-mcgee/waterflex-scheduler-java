package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.math.BigDecimal;
import java.util.List;

/** Road seconds remain distinct from policy allowance and per-leg whole-minute rounding. */
public record TravelBreakdown(long road_seconds, BigDecimal configured_buffer_seconds,
                             BigDecimal rounding_seconds, long modeled_travel_minutes, int leg_count) {
    public static TravelBreakdown forRoute(DayPlan plan, TechRoute route, List<RouteEvaluator.WorkingSegment> segments) {
        long road = 0, modeled = 0; int count = 0;
        BigDecimal buffer = BigDecimal.ZERO;
        for (var segment : segments) {
            String previous = route.getId();
            for (String visit : segment.visitIds()) {
                var leg = Required.value(plan.getMatrix().get(previous + ">" + visit), "reported road leg");
                road = Math.addExact(road, leg.seconds()); modeled = Math.addExact(modeled, minutes(plan, leg));
                buffer = buffer.add(allowance(plan, leg)); count++; previous = visit;
            }
            if (!segment.visitIds().isEmpty()) {
                var leg = Required.value(plan.getMatrix().get(previous + ">" + route.getId() + ":return"), "reported return leg");
                road = Math.addExact(road, leg.seconds()); modeled = Math.addExact(modeled, minutes(plan, leg));
                buffer = buffer.add(allowance(plan, leg)); count++;
            }
        }
        BigDecimal rounding = BigDecimal.valueOf(Math.multiplyExact(modeled, 60)).subtract(BigDecimal.valueOf(road)).subtract(buffer);
        if (rounding.signum() < 0) throw new IllegalStateException("Invalid travel accounting");
        return new TravelBreakdown(road, Required.value(buffer), Required.value(rounding), modeled, count);
    }
    private static BigDecimal allowance(DayPlan plan, DayPlan.RoadLeg leg) {
        return Required.value(BigDecimal.valueOf(leg.seconds()).multiply(plan.getTravelBufferPct())
                .add(BigDecimal.valueOf(Math.multiplyExact(plan.getTravelBufferMinutes(), 60))));
    }
    private static long minutes(DayPlan plan, DayPlan.RoadLeg leg) {
        return dev.waterflex.scheduler.Monetary.bufferedMinutes(leg.seconds(), plan.getTravelBufferPct(), plan.getTravelBufferMinutes());
    }
}
