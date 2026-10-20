package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.solver.SolverConfigOverride;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import dev.waterflex.scheduler.Required;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/** Public Community API boundary. Cached factories never share mutable solver or planning state. */
public final class SolverEngine {
    public enum Variant { CURRENT_CAPPED, CURRENT_UNCAPPED, LATE_ACCEPTANCE_CHANGE, LATE_ACCEPTANCE,
                          TABU, SUBLIST, KOPT, RUIN_RECREATE,
                          /** TABU tuning: entity tabu size 3 or 15 (experiment-only), or TABU with the KOPT move set (a client choice, see DailySearch). */
                          TABU_SIZE_3, TABU_SIZE_15, TABU_KOPT,
                          /** TABU_KOPT with entity tabu size 3, both improvements of the Tabu variant study combined (a client choice, see DailySearch). */
                          TABU_KOPT_SIZE_3,
                          /**
                           * TABU_KOPT scored with long levels ({@link LongScoreDayPlan}), experiment-only: the score representation
                           * study's treatment S. Never a client choice; a solve returns a {@link DayPlan} with its decimal score.
                           */
                          TABU_KOPT_LONG_SCORE }
    private record Key(Variant variant, long seed, EnvironmentMode mode, boolean construction) { }
    private static final java.util.concurrent.ConcurrentMap<Key, Definition> DEFINITIONS = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * {@code factory} scores with the decimal levels every reader uses. {@code longScore} is present only for
     * {@link Variant#TABU_KOPT_LONG_SCORE}, whose search runs on it, and is null for every other variant.
     */
    public record Definition(Variant variant, long seed, EnvironmentMode environmentMode, boolean construction,
            String configurationXml, String fingerprint, @Nullable Integer stepLimit, SolverFactory<DayPlan> factory,
            @Nullable SolverFactory<LongScoreDayPlan> longScore) {
        public Definition(Variant variant, long seed, EnvironmentMode environmentMode, boolean construction,
                String configurationXml, String fingerprint, @Nullable Integer stepLimit, SolverFactory<DayPlan> factory) {
            this(variant, seed, environmentMode, construction, configurationXml, fingerprint, stepLimit, factory, null);
        }
    }
    public record Statistics(String variant, long seed, String configurationFingerprint, String termination,
            long budgetMs, @Nullable Integer stepLimit, @Nullable Long steps, @Nullable Long moveEvaluations,
            @Nullable Long scoreCalculations, long solveMs, @Nullable Long timeToBestMs,
            int format, String terminationBasis, @Nullable String diagnosticsUnavailableReason,
            String environmentMode, String instrumentation, EngineProvenance provenance,
            @Nullable String timeToBestUnavailableReason, @Nullable Long unimprovedMs) {
        /** Format 3 adds {@code unimprovedMs}, the phase's unimproved stop when the client set one, and the UNIMPROVED_LIMIT reason. */
        public static final int FORMAT = 3;
    }
    public record Result(DayPlan plan, Statistics statistics, DailyOutcome outcome) { }
    public record Measurements(long steps, long moveEvaluations, long scoreCalculations, long solveMs, @Nullable Long timeToBestMs) {
        public Measurements {
            if (steps < 0 || moveEvaluations < 0 || scoreCalculations < 0 || solveMs < 0 || (timeToBestMs != null && timeToBestMs < 0))
                throw new IllegalArgumentException("Invalid diagnostics counters");
        }
    }
    @FunctionalInterface interface Probe { Measurements finish(); }
    /** The one Timefold release the experiment instrumentation is verified against; it attaches to that exact artifact only. */
    public static final String INSTRUMENTED_TIMEFOLD = "2.7.1";
    /** Diagnostics' name for that instrumentation. Evidence recorded on 2.6.0 says TIMEFOLD_INTERNAL_2_6_0, which readers still accept. */
    public static final String INSTRUMENTATION = "TIMEFOLD_INTERNAL_" + INSTRUMENTED_TIMEFOLD.replace('.', '_');
    /**
     * Timefold's time limit runs on its own clock, so a phase it stopped on the budget can measure a few milliseconds
     * short of it here. Without telemetry, only a return at least this much before the budget is read as the unimproved
     * limit; a client's limit is whole seconds, so a real unimproved stop is far earlier.
     */
    static final long UNIMPROVED_MARGIN_MS = 100;
    /** A solver of either score representation; the instrumentation reads counters, never the solution. */
    @FunctionalInterface interface Telemetry { Probe attach(Solver<?> solver); }
    interface Cancellation {
        void started(Solver<?> solver);
        void stopped();
        /** True when the enclosing operation was cancelled, as opposed to a phase budget stop. */
        boolean cancelled();
    }
    public static final class DiagnosticsFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final transient Result result;
        DiagnosticsFailure(Result result) { super("Required experiment diagnostics unavailable: " + result.statistics().diagnosticsUnavailableReason()); this.result = result; }
        public Result result() { return result; }
    }
    private SolverEngine() { }
    public static Definition configuration(Variant variant, long seed) {
        return configuration(variant, seed, false);
    }
    public static Definition configuration(Variant variant, long seed, boolean verifyMoves) {
        return configuration(variant, seed, verifyMoves, false);
    }
    public static Definition configuration(Variant variant, long seed, boolean verifyMoves, boolean construction) {
        return configuration(variant, seed, verifyMoves ? EnvironmentMode.FULL_ASSERT : EnvironmentMode.NO_ASSERT, construction);
    }
    public static Definition configuration(Variant variant, long seed, EnvironmentMode mode, boolean construction) {
        if (!java.util.Set.of(EnvironmentMode.NO_ASSERT, EnvironmentMode.FULL_ASSERT,
                EnvironmentMode.NON_INTRUSIVE_FULL_ASSERT, EnvironmentMode.PHASE_ASSERT).contains(mode))
            throw new IllegalArgumentException("Unsupported environment mode");
        return Required.value(DEFINITIONS.computeIfAbsent(new Key(variant, seed, mode, construction), key -> build(Required.value(key))));
    }
    private static Definition build(Key key) {
        Variant variant = key.variant(); long seed = key.seed(); boolean construction = key.construction();
        String xml;
        if (variant == Variant.CURRENT_CAPPED || variant == Variant.CURRENT_UNCAPPED) {
            try (var stream = Required.value(SolverEngine.class.getResourceAsStream("/solverConfig.xml"))) {
                xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException failure) { throw new IllegalStateException("Solver configuration unavailable", failure); }
            if (variant == Variant.CURRENT_UNCAPPED) xml = xml.replace("<stepCountLimit>1000</stepCountLimit>", "");
        } else {
            boolean longScore = variant == Variant.TABU_KOPT_LONG_SCORE;
            boolean tabu = variant == Variant.TABU || variant == Variant.TABU_SIZE_3 || variant == Variant.TABU_SIZE_15 || variant == Variant.TABU_KOPT
                    || variant == Variant.TABU_KOPT_SIZE_3 || longScore;
            boolean kOpt = variant == Variant.KOPT || variant == Variant.RUIN_RECREATE || variant == Variant.TABU_KOPT || variant == Variant.TABU_KOPT_SIZE_3
                    || longScore;
            boolean advanced = variant == Variant.SUBLIST || kOpt;
            String moves = "<listChangeMoveSelector><fixedProbabilityWeight>45</fixedProbabilityWeight></listChangeMoveSelector>";
            if (variant != Variant.LATE_ACCEPTANCE_CHANGE)
                moves += "<listSwapMoveSelector><fixedProbabilityWeight>45</fixedProbabilityWeight></listSwapMoveSelector>";
            if (advanced) moves += "<subListChangeMoveSelector><fixedProbabilityWeight>5</fixedProbabilityWeight><selectReversingMoveToo>true</selectReversingMoveToo><subListSelector><minimumSubListSize>2</minimumSubListSize><maximumSubListSize>4</maximumSubListSize></subListSelector></subListChangeMoveSelector>";
            if (kOpt)
                moves += "<kOptListMoveSelector><fixedProbabilityWeight>5</fixedProbabilityWeight><minimumK>2</minimumK><maximumK>3</maximumK></kOptListMoveSelector>";
            if (variant == Variant.RUIN_RECREATE)
                moves += "<listRuinRecreateMoveSelector><fixedProbabilityWeight>1</fixedProbabilityWeight><minimumRuinedCount>2</minimumRuinedCount><maximumRuinedCount>3</maximumRuinedCount></listRuinRecreateMoveSelector>";
            int tabuSize = variant == Variant.TABU_SIZE_3 || variant == Variant.TABU_KOPT_SIZE_3 ? 3 : variant == Variant.TABU_SIZE_15 ? 15 : 7;
            String acceptor = tabu ? "<entityTabuSize>" + tabuSize + "</entityTabuSize>" : "<lateAcceptanceSize>400</lateAcceptanceSize>";
            int accepted = tabu ? 1000 : 1;
            xml = "<solver xmlns=\"https://timefold.ai/xsd/solver\"><environmentMode>NO_ASSERT</environmentMode>"
                    + "<solutionClass>dev.waterflex.scheduler.optimizer.DayPlan</solutionClass><entityClass>dev.waterflex.scheduler.optimizer.TechRoute</entityClass><entityClass>dev.waterflex.scheduler.optimizer.PlanVisit</entityClass>"
                    + "<scoreDirectorFactory><constraintProviderClass>dev.waterflex.scheduler.optimizer.DayConstraintProvider</constraintProviderClass></scoreDirectorFactory>"
                    + "<termination><spentLimit>PT15S</spentLimit></termination><localSearch><unionMoveSelector><selectedCountLimit>10000</selectedCountLimit>"
                    + moves + "</unionMoveSelector><acceptor>" + acceptor + "</acceptor><forager><acceptedCountLimit>" + accepted + "</acceptedCountLimit></forager></localSearch></solver>";
        }
        xml = xml.replace("<environmentMode>NO_ASSERT</environmentMode>", "<environmentMode>" + key.mode().name() + "</environmentMode>");
        if (construction) xml = xml.replace("<localSearch>", "<constructionHeuristic/><localSearch>");
        SolverConfig config = Required.value(SolverConfig.createFromXmlReader(new StringReader(xml)).withRandomSeed(seed));
        @Nullable SolverFactory<LongScoreDayPlan> longScore = null;
        if (variant == Variant.TABU_KOPT_LONG_SCORE) {
            // The recorded configuration is the one the search runs on; the decimal factory scores the result.
            xml = xml.replace("<solutionClass>dev.waterflex.scheduler.optimizer.DayPlan</solutionClass>",
                            "<solutionClass>dev.waterflex.scheduler.optimizer.LongScoreDayPlan</solutionClass>")
                    .replace("<constraintProviderClass>dev.waterflex.scheduler.optimizer.DayConstraintProvider</constraintProviderClass>",
                            "<constraintProviderClass>dev.waterflex.scheduler.optimizer.LongScoreConstraintProvider</constraintProviderClass>");
            if (!xml.contains("LongScoreDayPlan") || !xml.contains("LongScoreConstraintProvider")) throw new IllegalStateException("Long score configuration changed shape");
            longScore = Required.value(SolverFactory.<LongScoreDayPlan>create(Required.value(SolverConfig.createFromXmlReader(new StringReader(xml)).withRandomSeed(seed))));
        }
        try {
            String fingerprint = Required.value(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((xml + "|seed=" + seed + "|score=" + DailyDataset.SCORE_MODEL).getBytes(StandardCharsets.UTF_8))));
            return new Definition(variant, seed, key.mode(), construction, Required.value(xml), fingerprint, Required.value(config.getTerminationConfig()).getStepCountLimit(),
                    Required.value(SolverFactory.create(config)), longScore);
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    public static Result solve(Definition definition, DayPlan initial, Duration budget) {
        return solve(definition, initial, budget, null, null, false, null);
    }
    /** {@code unimproved}, when present, also stops the phase once the best score has not improved for that long. */
    public static Result solve(Definition definition, DayPlan initial, Duration budget, @Nullable Duration unimproved) {
        return solve(definition, initial, budget, unimproved, null, false, null);
    }
    static Result solve(Definition definition, DayPlan initial, Duration budget, @Nullable Telemetry telemetry, boolean required) {
        return solve(definition, initial, budget, null, telemetry, required, null);
    }
    static Result solve(Definition definition, DayPlan initial, Duration budget, @Nullable Telemetry telemetry, boolean required,
            @Nullable Cancellation cancellation) {
        return solve(definition, initial, budget, null, telemetry, required, cancellation);
    }
    static Result solve(Definition definition, DayPlan initial, Duration budget, @Nullable Duration unimproved, @Nullable Telemetry telemetry,
            boolean required, @Nullable Cancellation cancellation) {
        if (budget.isNegative() || budget.isZero() || budget.toMillis() == 0) throw new IllegalArgumentException("Positive millisecond solver budget required");
        if (unimproved != null && (unimproved.isNegative() || unimproved.toMillis() == 0))
            throw new IllegalArgumentException("Positive millisecond unimproved limit required");
        initial.validateInputMode(); initial.getFacts().requireSearchRoads();
        if (definition.variant() == Variant.RUIN_RECREATE && initial.getRoutes().stream().anyMatch(route -> route.getPinnedPrefix() > 0))
            throw new IllegalArgumentException("RUIN_RECREATE with pinned prefixes is unsupported: observed list corruption during undo on Timefold 2.6.0, not re-tested since");
        if (!initial.getUnassignedVisitIds().isEmpty() && !definition.construction())
            return solve(configuration(definition.variant(), definition.seed(), definition.environmentMode(), true), initial, budget, unimproved, telemetry, required, cancellation);
        DayPlan working = PlanCopies.copy(initial);
        long started = System.nanoTime();
        if (initial.getVisits().isEmpty() || initial.getRoutes().isEmpty()) {
            ai.timefold.solver.core.api.solver.SolutionManager.create(definition.factory()).update(working);
            return result(definition, working, budget, unimproved, elapsed(started), "NOT_RUN", "OBSERVED", null, "NO_SEARCH", "NONE");
        }
        // The public override replaces termination: retain all declared early stopping rules.
        var termination = Required.value(ai.timefold.solver.core.config.solver.SolverConfig.createFromXmlReader(
                new java.io.StringReader(definition.configurationXml())).getTerminationConfig()).copyConfig();
        termination.overwriteSpentLimit(budget);
        // Two limits in one termination stop the phase at whichever is reached first.
        if (unimproved != null) termination.overwriteUnimprovedSpentLimit(unimproved);
        termination.setStepCountLimit(definition.stepLimit());
        @Nullable SolverFactory<LongScoreDayPlan> longScore = definition.longScore();
        // Exactly one of the two is built: the decimal search, or the long score search of TABU_KOPT_LONG_SCORE.
        @Nullable Solver<DayPlan> decimal = longScore != null ? null
                : Required.value(definition.factory().buildSolver(new SolverConfigOverride().withTerminationConfig(termination)));
        @Nullable Solver<LongScoreDayPlan> search = longScore == null ? null
                : Required.value(longScore.buildSolver(new SolverConfigOverride().withTerminationConfig(termination)));
        Solver<?> solver = decimal != null ? decimal : Required.value(search, "long score solver");
        @Nullable Probe probe = null;
        @Nullable String unavailable = telemetry == null ? "NOT_ENABLED" : null;
        if (telemetry != null) {
            try { probe = Required.value(telemetry.attach(solver)); }
            catch (RuntimeException | LinkageError failure) { unavailable = "ATTACH_FAILED:" + failure.getClass().getSimpleName(); }
        }
        started = System.nanoTime();
        @Nullable DayPlan decimalBest = null;
        @Nullable LongScoreDayPlan longBest = null;
        try {
            if (cancellation != null) cancellation.started(solver);
            if (decimal != null) decimalBest = Required.value(decimal.solve(working));
            else longBest = Required.value(Required.value(search, "long score solver").solve(new LongScoreDayPlan(working)));
        } finally { if (cancellation != null) cancellation.stopped(); }
        long solveMs = elapsed(started);
        // Outside the measured solve: a long score search's arrangement is rescored with the decimal levels.
        DayPlan solved = decimalBest != null ? decimalBest : decimal(Required.value(longBest, "long score solution"), working.getMode(), definition);
        @Nullable Measurements measured = null;
        if (probe != null) {
            try { measured = Required.value(probe.finish()); }
            catch (RuntimeException | LinkageError failure) { unavailable = "READ_FAILED:" + failure.getClass().getSimpleName(); }
        }
        // Preserve the historical experiment clock; production measures public solve() wall time.
        if (measured != null) solveMs = measured.solveMs();
        String reason = !solver.isTerminateEarly() ? "SOLVE_RETURNED"
                : cancellation != null && cancellation.cancelled() ? "CANCELLED" : "TERMINATED_EARLY";
        String basis = "OBSERVED";
        Integer cap = definition.stepLimit();
        if (!solver.isTerminateEarly() && measured != null) {
            boolean stepLimit = cap != null && measured.steps() >= cap;
            boolean timeLimit = solveMs >= budget.toMillis();
            Long best = measured.timeToBestMs();
            // The best score last improved at timeToBestMs: a stop at least the unimproved limit after it, before the budget, is that limit.
            boolean unimprovedLimit = unimproved != null && !stepLimit && !timeLimit && best != null && solveMs - best >= unimproved.toMillis();
            reason = stepLimit && timeLimit ? "STEP_AND_TIME_LIMIT" : stepLimit ? "STEP_LIMIT" : timeLimit ? "TIME_LIMIT"
                    : unimprovedLimit ? "UNIMPROVED_LIMIT" : "PHASE_COMPLETED";
            basis = "INFERRED";
        } else if (!solver.isTerminateEarly() && unimproved != null && cap == null && solveMs + UNIMPROVED_MARGIN_MS < budget.toMillis()) {
            // Without telemetry: a local search with no step cap returns before its budget only on the unimproved limit.
            reason = "UNIMPROVED_LIMIT";
            basis = "INFERRED";
        }
        Result result = result(definition, solved, budget, unimproved, solveMs, reason, basis, measured, unavailable,
                telemetry == null ? "NONE" : INSTRUMENTATION);
        if (required && measured == null) throw new DiagnosticsFailure(result);
        return result;
    }
    /** A long score search's best arrangement as a plan scored by the decimal levels every reader uses. */
    private static DayPlan decimal(LongScoreDayPlan best, DayPlan.Mode mode, Definition definition) {
        DayPlan solved = best.toDayPlan(mode);
        ai.timefold.solver.core.api.solver.SolutionManager.create(definition.factory()).update(solved);
        return solved;
    }
    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private static Result result(Definition definition, DayPlan plan, Duration budget, @Nullable Duration unimproved, long solveMs, String termination,
            String basis, @Nullable Measurements measured, @Nullable String unavailable, String instrumentation) {
        return new Result(plan, new Statistics(Required.value(definition.variant().name()), definition.seed(), definition.fingerprint(), termination,
                budget.toMillis(), definition.stepLimit(), measured == null ? null : measured.steps(),
                measured == null ? null : measured.moveEvaluations(), measured == null ? null : measured.scoreCalculations(), solveMs,
                measured == null ? null : measured.timeToBestMs(), Statistics.FORMAT, basis, unavailable, Required.value(definition.environmentMode().name()),
                instrumentation, EngineProvenance.loaded(), measured == null ? unavailable : measured.timeToBestMs() == null ? "NOT_REPORTED" : null,
                unimproved == null ? null : unimproved.toMillis()), DailyOutcome.assess(plan));
    }
}
