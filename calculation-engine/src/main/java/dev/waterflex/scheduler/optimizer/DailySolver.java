package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** One immutable configuration, with a new solver and copied planning solution for every phase. */
@Service
public final class DailySolver {
    public record Phase(String name, SolverEngine.Statistics statistics) { }
    public record Diagnostics(String engine, String configurationXml, List<Phase> phases,
            @org.jspecify.annotations.Nullable String constructionConfigurationXml) { }
    private final SolverEngine.Definition definition;
    private final SolverEngine.Definition constructionDefinition;
    public DailySolver(@Value("${scheduler.optimizer.variant:TABU}") String variant,
                       @Value("${scheduler.optimizer.seed:17}") long seed) {
        definition = SolverEngine.configuration(SolverEngine.Variant.valueOf(variant), seed);
        constructionDefinition = SolverEngine.configuration(SolverEngine.Variant.valueOf(variant), seed, false, true);
    }
    public SolverEngine.Result solve(DayPlan initial, Duration budget) {
        var selected = initial.getUnassignedVisitIds().isEmpty() ? definition : constructionDefinition;
        DailyOperation operation = DailyOperation.current();
        if (operation == null) return SolverEngine.solve(selected, initial, budget);
        Duration allocated = operation.budget(budget);
        try {
            return dev.waterflex.scheduler.SearchDeadline.withoutRequestClock(() -> SolverEngine.solve(selected, initial, allocated,
                    null, false, new SolverEngine.Cancellation() {
                        @Override public void started(ai.timefold.solver.core.api.solver.Solver<DayPlan> solver) { operation.started(solver); }
                        @Override public void stopped() { operation.stopped(); }
                    }));
        } finally { operation.finishPhase(); }
    }
    public Diagnostics diagnostics(List<Phase> phases) {
        return new Diagnostics(EngineProvenance.loaded().label(), definition.configurationXml(), Required.value(List.copyOf(phases)), constructionDefinition.configurationXml());
    }
    public String variant() { return Required.value(definition.variant().name()); }
    public long seed() { return definition.seed(); }
    public boolean matches(@org.jspecify.annotations.Nullable String variant, long seed) {
        return definition.variant().name().equals(variant) && definition.seed() == seed;
    }
}
