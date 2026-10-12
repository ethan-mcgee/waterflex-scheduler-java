package dev.waterflex.scheduler.optimizer;

import org.jspecify.annotations.Nullable;

/** The daily search methods a client may choose. Every other SolverEngine variant is for benchmarks only. */
public enum DailySearch {
    TABU(SolverEngine.Variant.TABU),
    TABU_KOPT(SolverEngine.Variant.TABU_KOPT);

    private final SolverEngine.Variant variant;
    DailySearch(SolverEngine.Variant variant) { this.variant = variant; }
    public SolverEngine.Variant variant() { return variant; }

    /** The client choice that runs this variant, or null when the variant is not one a client may choose. */
    public static @Nullable DailySearch of(@Nullable String variantName) {
        for (DailySearch search : values()) if (search.variant.name().equals(variantName)) return search;
        return null;
    }
}
