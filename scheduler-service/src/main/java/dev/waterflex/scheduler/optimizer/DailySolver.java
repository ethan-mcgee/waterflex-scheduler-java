package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** One immutable configuration, with a new solver and copied planning solution for every phase. */
@Service
public final class DailySolver {
    public record Phase(String name, SolverExperiment.Statistics statistics) { }
    public record Diagnostics(String engine, String configurationXml, List<Phase> phases,
            @org.jspecify.annotations.Nullable String constructionConfigurationXml) { }
    private final SolverExperiment.Definition definition;
    private final SolverExperiment.Definition constructionDefinition;
    public DailySolver(@Value("${scheduler.optimizer.variant:TABU}") String variant,
                       @Value("${scheduler.optimizer.seed:17}") long seed) {
        definition = SolverExperiment.configuration(SolverExperiment.Variant.valueOf(variant), seed);
        constructionDefinition = SolverExperiment.configuration(SolverExperiment.Variant.valueOf(variant), seed, false, true);
    }
    public SolverExperiment.Result solve(DayPlan initial, Duration budget) {
        return SolverExperiment.solve(initial.getUnassignedVisitIds().isEmpty() ? definition : constructionDefinition, initial, budget);
    }
    public Diagnostics diagnostics(List<Phase> phases) {
        return new Diagnostics("Timefold-2.6.0", definition.configurationXml(), Required.value(List.copyOf(phases)), constructionDefinition.configurationXml());
    }
}
