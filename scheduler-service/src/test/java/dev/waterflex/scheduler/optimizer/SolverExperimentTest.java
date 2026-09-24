package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SolverExperimentTest {
    @Test void everyCommunityConfigurationBuildsAndReportsMeasuredSearchWork() {
        for (var variant : SolverExperiment.Variant.values()) {
            var definition = SolverExperiment.configuration(Required.value(variant), 17, true);
            var initial = DayConstraintProviderTest.fixture();
            var before = RouteEvaluator.evaluate(initial);
            var result = SolverExperiment.solve(definition, initial, Required.value(Duration.ofMillis(100)));
            assertTrue(RouteEvaluator.evaluate(result.plan()).feasible(), variant.name());
            assertEquals(before, RouteEvaluator.evaluate(initial));
            assertTrue(result.statistics().scoreCalculations() > 0);
            assertTrue(result.statistics().solveMs() >= 0);
            assertEquals(64, result.statistics().configurationFingerprint().length());
            assertFalse(definition.configurationXml().contains("nearbySelection"));
            assertFalse(definition.configurationXml().contains("enablePreviewFeature"));
        }
    }
    @Test void allFleetSizesAndWorkloadsStartFromIndependentlyFeasibleSchedules() {
        for (int size : new int[]{20, 30, 50}) for (var workload : SolverBenchmarkData.Workload.values()) {
            var plan = SolverBenchmarkData.create(size, Required.value(workload));
            assertEquals(size, plan.getRoutes().size());
            var independent = RouteEvaluator.evaluate(plan);
            var full = DayScoreCalculator.evaluate(plan);
            assertTrue(independent.feasible()); assertEquals(0, full.hardPenalty());
            assertEquals(independent.costCents(), full.costCents()); assertEquals(independent.arrivals(), full.arrivals());
        }
    }
}
