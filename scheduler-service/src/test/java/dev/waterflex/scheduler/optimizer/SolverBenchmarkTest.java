package dev.waterflex.scheduler.optimizer;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SolverBenchmarkTest {
    @Test void acceptsLongExperimentBudgetsWithoutRunningTheStudy() {
        for (long budget : new long[] {100, 15000, 90000, 120000, 240000})
            assertDoesNotThrow(() -> SolverBenchmark.validateBudget(budget));
        assertThrows(IllegalArgumentException.class, () -> SolverBenchmark.validateBudget(99));
        assertThrows(IllegalArgumentException.class, () -> SolverBenchmark.validateBudget(240001));
    }
}
