package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SchedulingPolicyTest {
    private static final SchedulingPolicy.Rules RULES = SchedulingPolicy.Rules.defaults();
    private static SchedulingPolicy.Workload work(String id, long paid, long capacity) {
        return new SchedulingPolicy.Workload(id, paid, capacity, Required.value(BigDecimal.ZERO));
    }
    private static SchedulingPolicy.Metrics metrics(long overtime, long cost, long a, long b) {
        return new SchedulingPolicy.Metrics(overtime, cost, SchedulingPolicy.fairness(Required.value(List.<SchedulingPolicy.Workload>of(work("a", a, 480), work("b", b, 480)))));
    }
    @Test void scarcityNeverAuthorizesOvertime() {
        assertFalse(RULES.authorizeOvertime(3, 100, 100, true));
        assertFalse(RULES.authorizeOvertime(2, 89, 100, true));
        assertFalse(RULES.authorizeOvertime(2, 90, 100, true));
        assertFalse(RULES.authorizeOvertime(1, 91, 100, true));
        assertFalse(RULES.authorizeOvertime(0, 100, 100, false));
        assertFalse(RULES.authorizeOvertime(0, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> RULES.authorizeOvertime(0, 101, 100, true));
    }
    @Test void fairnessUsesCapacityWeightsAndIncludesIdleTechnicians() {
        var equal = SchedulingPolicy.fairness(Required.value(List.<SchedulingPolicy.Workload>of(work("a", 240, 480), work("b", 120, 240))));
        assertEquals(0, equal.variance().compareTo(BigDecimal.ZERO));
        var idle = SchedulingPolicy.fairness(Required.value(List.<SchedulingPolicy.Workload>of(work("a", 240, 480), work("b", 0, 480))));
        assertEquals(0, idle.variance().compareTo(new BigDecimal("0.0625")));
        assertEquals(0, idle.maximumUtilization().compareTo(new BigDecimal("0.5")));
    }
    @Test void allowanceFloorsCentsAndDoesNotExpandNonpositiveReferences() {
        assertEquals(102, RULES.costCeiling(100));
        assertEquals(103, RULES.costCeiling(101));
        assertEquals(0, RULES.costCeiling(0));
        assertEquals(-100, RULES.costCeiling(-100));
        var baseline = metrics(0, 10000, 400, 0);
        assertTrue(SchedulingPolicy.compare(baseline, metrics(0, 10200, 200, 200), baseline, RULES).accepted());
        assertFalse(SchedulingPolicy.compare(baseline, metrics(0, 10201, 200, 200), baseline, RULES).accepted());
    }
    @Test void overtimeHasPriorityOverCostButCannotIncrease() {
        var baseline = metrics(10, 10000, 400, 0);
        var reference = metrics(0, 20000, 200, 200);
        assertTrue(SchedulingPolicy.compare(baseline, reference, reference, RULES).accepted());
        assertFalse(SchedulingPolicy.compare(baseline, metrics(11, 1, 200, 200), baseline, RULES).accepted());
        assertFalse(SchedulingPolicy.compare(baseline, baseline, baseline, RULES).accepted());
    }
    @Test void lowerCostDoesNotDisplaceFairerBaselineInsideTheAllowance() {
        var reference = metrics(0, 10000, 300, 100);
        var atCeiling = metrics(0, 10200, 200, 200);
        var rejected = SchedulingPolicy.compare(atCeiling, reference, reference, RULES);
        assertFalse(rejected.accepted());
        assertEquals("FAIRNESS_REGRESSION_WITHIN_ALLOWANCE", rejected.reason());
        assertTrue(SchedulingPolicy.compare(metrics(0, 10201, 200, 200), reference, reference, RULES).accepted(),
                "A baseline above the ceiling is outside the fairness allowance");
        assertTrue(SchedulingPolicy.compare(atCeiling, metrics(0, 10000, 200, 200), reference, RULES).accepted(),
                "Equal fairness still uses lower cost");
    }
    @Test void equalPolicyPlansPreferFewerChangedAssignmentsThenStableRouteOrder() {
        DayPlan original = SolverBenchmarkData.create(20, SolverBenchmarkData.Workload.CLUSTERED);
        DayPlan changed = PlanCopies.copy(original);
        TechRoute source = Required.value(changed.getRoutes().stream().filter(route -> !route.getVisits().isEmpty()).findFirst().orElseThrow());
        TechRoute target = Required.value(changed.getRoutes().stream().filter(route -> route != source).findFirst().orElseThrow());
        target.getVisits().add(source.getVisits().removeFirst());
        assertTrue(SchedulingPolicy.compareArrangements(original, changed) < 0);
        assertTrue(SchedulingPolicy.compareArrangements(changed, original) > 0);
        assertEquals(0, SchedulingPolicy.compareArrangements(original, PlanCopies.copy(original)));
        DayPlan reversed = PlanCopies.copy(original);
        TechRoute multi = Required.value(reversed.getRoutes().stream().filter(route -> route.getVisits().size() > 1).findFirst().orElseThrow());
        java.util.Collections.reverse(multi.getVisits());
        int comparison = SchedulingPolicy.compareArrangements(original, reversed);
        assertNotEquals(0, comparison);
        assertEquals(Integer.signum(comparison), -Integer.signum(SchedulingPolicy.compareArrangements(reversed, original)));
    }
    @Test void overlappingAbsencesAreClippedAndUnionedBeforeDailyCap() {
        TechRoute route = new TechRoute("tech", at("08"), at("16"), 450, 60, Required.value(Set.of("service")));
        route.getUnavailable().add(new TechRoute.Unavailable(at("07"), at("09")));
        route.getUnavailable().add(new TechRoute.Unavailable(at("08"), at("10")));
        route.getUnavailable().add(new TechRoute.Unavailable(at("15"), at("17")));
        assertEquals(300, SchedulingPolicy.regularCapacity(route));
    }
    @Test void invalidOrUninitializedCapacityFailsExplicitly() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> SchedulingPolicy.regularCapacity(new TechRoute()));
        assertThrows(IllegalArgumentException.class, () -> SchedulingPolicy.fairness(Required.value(List.<SchedulingPolicy.Workload>of(work("a", 0, 0)))));
    }
    private static Instant at(String hour) { return Required.value(Instant.parse("2026-09-23T" + hour + ":00:00Z")); }
}
