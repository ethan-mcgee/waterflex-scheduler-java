package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.math.BigDecimal;
import java.util.Map;

/** Required persisted policy, shared by booking and dispatch and fingerprinted with settings. */
public final class PolicySettings {
    private PolicySettings() { }
    public static SchedulingPolicy.Rules read(Map<String, BigDecimal> values) {
        return new SchedulingPolicy.Rules(number(values, "regular_window_threshold").intValueExact(),
                number(values, "confirmed_utilization_threshold"), number(values, "fairness_cost_allowance"),
                number(values, "booking_deadline_ms").intValueExact());
    }
    private static BigDecimal number(Map<String, BigDecimal> values, String key) {
        return Required.value(values.get(key), "scheduling policy " + key);
    }
}
