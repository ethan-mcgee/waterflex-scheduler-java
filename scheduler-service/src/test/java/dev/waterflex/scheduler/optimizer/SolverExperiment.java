package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.impl.solver.DefaultSolver;
import ai.timefold.solver.core.impl.phase.event.PhaseLifecycleListenerAdapter;
import ai.timefold.solver.core.impl.phase.scope.AbstractStepScope;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** Exact-version experiment instrumentation. No production caller depends on this adapter. */
public final class SolverExperiment {
    private SolverExperiment() { }
    public static SolverEngine.Result solve(SolverEngine.Definition definition, DayPlan initial, Duration budget) {
        return SolverEngine.solve(definition, initial, budget, telemetry(EngineProvenance.loaded()), true);
    }
    static SolverEngine.Telemetry telemetry(EngineProvenance provenance) {
        return solver -> {
            if (!"2.6.0".equals(provenance.version()) || provenance.sha256() == null
                    || !(solver instanceof DefaultSolver<DayPlan> measured))
                throw new IllegalStateException("Unsupported solver diagnostics artifact");
            long[] steps = {0};
            measured.addPhaseLifecycleListener(new PhaseLifecycleListenerAdapter<DayPlan>() {
                @Override public void stepEnded(@Nullable AbstractStepScope<DayPlan> scope) { steps[0]++; }
            });
            return () -> new SolverEngine.Measurements(steps[0], measured.getMoveEvaluationCount(),
                    measured.getScoreCalculationCount(), measured.getTimeMillisSpent(), measured.getSolverScope().getBestSolutionTimeMillisSpent());
        };
    }
}
