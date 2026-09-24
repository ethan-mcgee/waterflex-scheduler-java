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
    public record Diagnostics(String engine, String configurationXml, List<Phase> phases) { }
    private final SolverExperiment.Definition definition;
    public DailySolver(@Value("${scheduler.optimizer.variant:CURRENT_CAPPED}") String variant,
                       @Value("${scheduler.optimizer.seed:17}") long seed) {
        definition = SolverExperiment.configuration(SolverExperiment.Variant.valueOf(variant), seed);
    }
    public SolverExperiment.Result solve(DayPlan initial, Duration budget) { return SolverExperiment.solve(definition, initial, budget); }
    public Diagnostics diagnostics(List<Phase> phases) {
        return new Diagnostics("Timefold-2.6.0", definition.configurationXml(), Required.value(List.copyOf(phases)));
    }
}
