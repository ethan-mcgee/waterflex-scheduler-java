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
            if (!SolverEngine.INSTRUMENTED_TIMEFOLD.equals(provenance.version()) || provenance.sha256() == null
                    || !(solver instanceof DefaultSolver<?> measured))
                throw new IllegalStateException("Unsupported solver diagnostics artifact");
            return probe(measured);
        };
    }
    /** Either score representation's solver: the counters do not depend on the solution class. */
    private static <S> SolverEngine.Probe probe(DefaultSolver<S> measured) {
        long[] steps = {0};
        measured.addPhaseLifecycleListener(new PhaseLifecycleListenerAdapter<S>() {
            @Override public void stepEnded(@Nullable AbstractStepScope<S> scope) { steps[0]++; }
        });
        return () -> new SolverEngine.Measurements(steps[0], measured.getMoveEvaluationCount(),
                measured.getScoreCalculationCount(), measured.getTimeMillisSpent(), measured.getSolverScope().getBestSolutionTimeMillisSpent());
    }
}
