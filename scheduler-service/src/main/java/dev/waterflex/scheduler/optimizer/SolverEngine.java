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
                          TABU, SUBLIST, KOPT, RUIN_RECREATE }
    private record Key(Variant variant, long seed, EnvironmentMode mode, boolean construction) { }
    private static final java.util.concurrent.ConcurrentMap<Key, Definition> DEFINITIONS = new java.util.concurrent.ConcurrentHashMap<>();
    public record Definition(Variant variant, long seed, EnvironmentMode environmentMode, boolean construction,
            String configurationXml, String fingerprint, @Nullable Integer stepLimit, SolverFactory<DayPlan> factory) { }
    public record Statistics(String variant, long seed, String configurationFingerprint, String termination,
            long budgetMs, @Nullable Integer stepLimit, @Nullable Long steps, @Nullable Long moveEvaluations,
            @Nullable Long scoreCalculations, long solveMs, @Nullable Long timeToBestMs,
            int format, String terminationBasis, @Nullable String diagnosticsUnavailableReason,
            String environmentMode, String instrumentation, EngineProvenance provenance,
            @Nullable String timeToBestUnavailableReason) { }
    public record Result(DayPlan plan, Statistics statistics, DailyOutcome outcome) { }
    public record Measurements(long steps, long moveEvaluations, long scoreCalculations, long solveMs, @Nullable Long timeToBestMs) {
        public Measurements {
            if (steps < 0 || moveEvaluations < 0 || scoreCalculations < 0 || solveMs < 0 || (timeToBestMs != null && timeToBestMs < 0))
                throw new IllegalArgumentException("Invalid diagnostics counters");
        }
    }
    @FunctionalInterface interface Probe { Measurements finish(); }
    @FunctionalInterface interface Telemetry { Probe attach(Solver<DayPlan> solver); }
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
            boolean advanced = variant == Variant.SUBLIST || variant == Variant.KOPT || variant == Variant.RUIN_RECREATE;
            String moves = "<listChangeMoveSelector><fixedProbabilityWeight>45</fixedProbabilityWeight></listChangeMoveSelector>";
            if (variant != Variant.LATE_ACCEPTANCE_CHANGE)
                moves += "<listSwapMoveSelector><fixedProbabilityWeight>45</fixedProbabilityWeight></listSwapMoveSelector>";
            if (advanced) moves += "<subListChangeMoveSelector><fixedProbabilityWeight>5</fixedProbabilityWeight><selectReversingMoveToo>true</selectReversingMoveToo><subListSelector><minimumSubListSize>2</minimumSubListSize><maximumSubListSize>4</maximumSubListSize></subListSelector></subListChangeMoveSelector>";
            if (variant == Variant.KOPT || variant == Variant.RUIN_RECREATE)
                moves += "<kOptListMoveSelector><fixedProbabilityWeight>5</fixedProbabilityWeight><minimumK>2</minimumK><maximumK>3</maximumK></kOptListMoveSelector>";
            if (variant == Variant.RUIN_RECREATE)
                moves += "<listRuinRecreateMoveSelector><fixedProbabilityWeight>1</fixedProbabilityWeight><minimumRuinedCount>2</minimumRuinedCount><maximumRuinedCount>3</maximumRuinedCount></listRuinRecreateMoveSelector>";
            String acceptor = variant == Variant.TABU ? "<entityTabuSize>7</entityTabuSize>" : "<lateAcceptanceSize>400</lateAcceptanceSize>";
            int accepted = variant == Variant.TABU ? 1000 : 1;
            xml = "<solver xmlns=\"https://timefold.ai/xsd/solver\"><environmentMode>NO_ASSERT</environmentMode>"
                    + "<solutionClass>dev.waterflex.scheduler.optimizer.DayPlan</solutionClass><entityClass>dev.waterflex.scheduler.optimizer.TechRoute</entityClass><entityClass>dev.waterflex.scheduler.optimizer.PlanVisit</entityClass>"
                    + "<scoreDirectorFactory><constraintProviderClass>dev.waterflex.scheduler.optimizer.DayConstraintProvider</constraintProviderClass></scoreDirectorFactory>"
                    + "<termination><spentLimit>15s</spentLimit></termination><localSearch><unionMoveSelector><selectedCountLimit>10000</selectedCountLimit>"
                    + moves + "</unionMoveSelector><acceptor>" + acceptor + "</acceptor><forager><acceptedCountLimit>" + accepted + "</acceptedCountLimit></forager></localSearch></solver>";
        }
        xml = xml.replace("<environmentMode>NO_ASSERT</environmentMode>", "<environmentMode>" + key.mode().name() + "</environmentMode>");
        if (construction) xml = xml.replace("<localSearch>", "<constructionHeuristic/><localSearch>");
        SolverConfig config = Required.value(SolverConfig.createFromXmlReader(new StringReader(xml)).withRandomSeed(seed));
        try {
            String fingerprint = Required.value(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((xml + "|seed=" + seed + "|score=" + DailyDataset.SCORE_MODEL).getBytes(StandardCharsets.UTF_8))));
            return new Definition(variant, seed, key.mode(), construction, Required.value(xml), fingerprint, Required.value(config.getTerminationConfig()).getStepCountLimit(), Required.value(SolverFactory.create(config)));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    public static Result solve(Definition definition, DayPlan initial, Duration budget) {
        return solve(definition, initial, budget, null, false);
    }
    static Result solve(Definition definition, DayPlan initial, Duration budget, @Nullable Telemetry telemetry, boolean required) {
        if (budget.isNegative() || budget.isZero() || budget.toMillis() == 0) throw new IllegalArgumentException("Positive millisecond solver budget required");
        initial.validateInputMode(); initial.getFacts().requireSearchRoads();
        if (definition.variant() == Variant.RUIN_RECREATE && initial.getRoutes().stream().anyMatch(route -> route.getPinnedPrefix() > 0))
            throw new IllegalArgumentException("RUIN_RECREATE with pinned prefixes is unsupported on Timefold 2.6.0: observed list corruption during undo");
        if (!initial.getUnassignedVisitIds().isEmpty() && !definition.construction())
            return solve(configuration(definition.variant(), definition.seed(), definition.environmentMode(), true), initial, budget, telemetry, required);
        DayPlan working = PlanCopies.copy(initial);
        long started = System.nanoTime();
        if (initial.getVisits().isEmpty() || initial.getRoutes().isEmpty()) {
            ai.timefold.solver.core.api.solver.SolutionManager.create(definition.factory()).update(working);
            return result(definition, working, budget, elapsed(started), "NOT_RUN", "OBSERVED", null, "NO_SEARCH", "NONE");
        }
        // The public override replaces termination: retain the effective diagnostic step cap.
        var termination = new ai.timefold.solver.core.config.solver.termination.TerminationConfig().withSpentLimit(budget);
        termination.setStepCountLimit(definition.stepLimit());
        Solver<DayPlan> solver = Required.value(definition.factory().buildSolver(new SolverConfigOverride().withTerminationConfig(termination)));
        @Nullable Probe probe = null;
        @Nullable String unavailable = telemetry == null ? "NOT_ENABLED" : null;
        if (telemetry != null) {
            try { probe = Required.value(telemetry.attach(solver)); }
            catch (RuntimeException | LinkageError failure) { unavailable = "ATTACH_FAILED:" + failure.getClass().getSimpleName(); }
        }
        started = System.nanoTime();
        DayPlan solved = Required.value(solver.solve(working));
        long solveMs = elapsed(started);
        @Nullable Measurements measured = null;
        if (probe != null) {
            try { measured = Required.value(probe.finish()); }
            catch (RuntimeException | LinkageError failure) { unavailable = "READ_FAILED:" + failure.getClass().getSimpleName(); }
        }
        // Preserve the historical experiment clock; production measures public solve() wall time.
        if (measured != null) solveMs = measured.solveMs();
        String reason = solver.isTerminateEarly() ? "TERMINATED_EARLY" : "SOLVE_RETURNED";
        String basis = "OBSERVED";
        if (!solver.isTerminateEarly() && measured != null) {
            Integer cap = definition.stepLimit();
            boolean stepLimit = cap != null && measured.steps() >= cap;
            boolean timeLimit = solveMs >= budget.toMillis();
            reason = stepLimit && timeLimit ? "STEP_AND_TIME_LIMIT" : stepLimit ? "STEP_LIMIT" : timeLimit ? "TIME_LIMIT" : "PHASE_COMPLETED";
            basis = "INFERRED";
        }
        Result result = result(definition, solved, budget, solveMs, reason, basis, measured, unavailable,
                telemetry == null ? "NONE" : "TIMEFOLD_INTERNAL_2_6_0");
        if (required && measured == null) throw new DiagnosticsFailure(result);
        return result;
    }
    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private static Result result(Definition definition, DayPlan plan, Duration budget, long solveMs, String termination,
            String basis, @Nullable Measurements measured, @Nullable String unavailable, String instrumentation) {
        return new Result(plan, new Statistics(Required.value(definition.variant().name()), definition.seed(), definition.fingerprint(), termination,
                budget.toMillis(), definition.stepLimit(), measured == null ? null : measured.steps(),
                measured == null ? null : measured.moveEvaluations(), measured == null ? null : measured.scoreCalculations(), solveMs,
                measured == null ? null : measured.timeToBestMs(), 2, basis, unavailable, Required.value(definition.environmentMode().name()),
                instrumentation, EngineProvenance.loaded(), measured == null ? unavailable : measured.timeToBestMs() == null ? "NOT_REPORTED" : null), DailyOutcome.assess(plan));
    }
}
