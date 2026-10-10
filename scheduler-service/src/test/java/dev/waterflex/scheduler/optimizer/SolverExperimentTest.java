package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SolverExperimentTest {
    @Test void timeOverridePreservesTheExplicitDiagnosticStepCap() {
        var definition = SolverEngine.configuration(SolverEngine.Variant.CURRENT_CAPPED, 17);
        var result = SolverExperiment.solve(definition, DayConstraintProviderTest.fixture(), Required.value(Duration.ofSeconds(10)));
        assertEquals(1000, result.statistics().steps());
        assertEquals("STEP_LIMIT", result.statistics().termination());
        assertTrue(result.statistics().solveMs() < 10000);
    }
    @Test void productionStatisticsReportExplicitUnavailableMetrics() {
        var solver = new DailySolver("CURRENT_CAPPED", 17);
        var result = solver.solve(DayConstraintProviderTest.fixture(), Required.value(Duration.ofMillis(100)));
        assertEquals("NOT_ENABLED", result.statistics().diagnosticsUnavailableReason());
        assertNull(result.statistics().moveEvaluations());
        assertEquals("OBSERVED", result.statistics().terminationBasis());
        assertThrows(IllegalArgumentException.class, () -> new DailySolver("unknown", 17));
    }
    @Test void everyCommunityConfigurationBuildsAndReportsMeasuredSearchWork() {
        for (var variant : SolverEngine.Variant.values()) {
            var definition = SolverEngine.configuration(Required.value(variant), 17, true);
            var initial = DayConstraintProviderTest.fixture();
            var before = RouteEvaluator.evaluate(initial);
            var result = SolverExperiment.solve(definition, initial, Required.value(Duration.ofMillis(100)));
            assertTrue(RouteEvaluator.evaluate(result.plan()).feasible(), variant.name());
            assertEquals(before, RouteEvaluator.evaluate(initial));
            assertTrue(Required.value(result.statistics().scoreCalculations()) > 0);
            assertTrue(result.statistics().solveMs() >= 0);
            assertEquals(64, result.statistics().configurationFingerprint().length());
            assertFalse(definition.configurationXml().contains("nearbySelection"));
            assertFalse(definition.configurationXml().contains("enablePreviewFeature"));
        }
    }
    @Test void allFleetSizesAndWorkloadsStartFromIndependentlyFeasibleSchedules() {
        for (int size : new int[]{5, 10, 20, 30, 50}) for (var workload : SolverBenchmarkData.Workload.values()) {
            var plan = SolverBenchmarkData.create(size, Required.value(workload));
            assertEquals(size, plan.getRoutes().size());
            var independent = RouteEvaluator.evaluate(plan);
            var full = DayScoreCalculator.evaluate(plan);
            assertTrue(independent.feasible()); assertEquals(0, full.hardPenalty());
            assertEquals(independent.costCents(), full.costCents()); assertEquals(independent.arrivals(), full.arrivals());
            if (workload == SolverBenchmarkData.Workload.NEAR_CAPACITY) {
                var workloadMetrics = SchedulingPolicy.measure(plan).fairness().workloads();
                long paid = workloadMetrics.stream().mapToLong(value -> value.paidMinutes()).sum();
                long capacity = workloadMetrics.stream().mapToLong(value -> value.regularCapacityMinutes()).sum();
                assertTrue(paid >= capacity * .9, "Near-capacity fixture must consume at least 90 percent of regular capacity");
            }
        }
    }
}
