package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** One immutable DailySolver per client-selectable DailySearch, all with the same seed. Each request picks one. */
@Service
public final class DailySolvers {
    private final Map<DailySearch, DailySolver> solvers = new EnumMap<>(DailySearch.class);
    private final long seed;

    @Autowired
    public DailySolvers(@Value("${scheduler.optimizer.seed:17}") long seed) {
        this(seed, production());
    }

    /**
     * Diagnostic wiring that runs another variant for a search, such as the step-capped CURRENT_CAPPED whose replays are
     * deterministic. Every search must be mapped; services use the production constructor.
     */
    public DailySolvers(long seed, Map<DailySearch, SolverEngine.Variant> variants) {
        this.seed = seed;
        for (DailySearch search : DailySearch.values()) {
            SolverEngine.Variant variant = variants.get(search);
            if (variant == null) throw new IllegalArgumentException("No variant for daily search " + search);
            solvers.put(search, new DailySolver(Required.value(variant.name()), seed));
        }
    }

    private static Map<DailySearch, SolverEngine.Variant> production() {
        Map<DailySearch, SolverEngine.Variant> variants = new EnumMap<>(DailySearch.class);
        for (DailySearch search : DailySearch.values()) variants.put(search, search.variant());
        return variants;
    }

    public DailySolver get(DailySearch search) { return Required.value(solvers.get(search), "daily solver for " + search); }

    /** The solver a remote dataset asked for. Anything but one of these solvers' variants with this seed is refused. */
    public DailySolver matching(@Nullable String variant, long requestedSeed) {
        for (DailySolver solver : solvers.values()) if (solver.matches(variant, requestedSeed)) return solver;
        throw new IllegalArgumentException("Daily search configuration mismatch");
    }

    public long seed() { return seed; }
}
