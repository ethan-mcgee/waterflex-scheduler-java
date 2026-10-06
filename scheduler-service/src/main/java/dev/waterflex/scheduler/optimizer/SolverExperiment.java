package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.solver.SolverConfigOverride;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.impl.solver.DefaultSolver;
import ai.timefold.solver.core.impl.phase.event.PhaseLifecycleListenerAdapter;
import ai.timefold.solver.core.impl.phase.scope.AbstractStepScope;
import dev.waterflex.scheduler.Required;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/** Pinned 2.6.0 diagnostic adapter. It never enables preview neighborhoods or Enterprise features. */
public final class SolverExperiment {
    public enum Variant { CURRENT_CAPPED, CURRENT_UNCAPPED, LATE_ACCEPTANCE_CHANGE, LATE_ACCEPTANCE,
                          TABU, SUBLIST, KOPT, RUIN_RECREATE }
    public record Definition(Variant variant, long seed, String configurationXml, String fingerprint,
                             @Nullable Integer stepLimit, SolverFactory<DayPlan> factory) { }
    public record Statistics(String variant, long seed, String configurationFingerprint, String termination,
            long budgetMs, @Nullable Integer stepLimit, long steps, long moveEvaluations, long scoreCalculations,
            long solveMs, @Nullable Long timeToBestMs) { }
    public record Result(DayPlan plan, Statistics statistics) { }
    private SolverExperiment() { }

    public static Definition configuration(Variant variant, long seed) {
        return configuration(variant, seed, false);
    }
    public static Definition configuration(Variant variant, long seed, boolean verifyMoves) {
        String xml;
        if (variant == Variant.CURRENT_CAPPED || variant == Variant.CURRENT_UNCAPPED) {
            try (var stream = Required.value(SolverExperiment.class.getResourceAsStream("/solverConfig.xml"))) {
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
        if (verifyMoves) xml = xml.replace("<environmentMode>NO_ASSERT</environmentMode>", "<environmentMode>FULL_ASSERT</environmentMode>");
        SolverConfig config = Required.value(SolverConfig.createFromXmlReader(new StringReader(xml)).withRandomSeed(seed));
        try {
            String fingerprint = Required.value(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((xml + "|seed=" + seed).getBytes(StandardCharsets.UTF_8))));
            return new Definition(variant, seed, Required.value(xml), fingerprint, Required.value(config.getTerminationConfig()).getStepCountLimit(), Required.value(SolverFactory.create(config)));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    public static Result solve(Definition definition, DayPlan initial, Duration budget) {
        if (budget.isNegative() || budget.isZero()) throw new IllegalArgumentException("Positive solver budget required");
        initial.getFacts().validateEntities(initial, true);
        initial.getFacts().requireSearchRoads();
        // The override replaces termination configuration; preserve the diagnostic step cap explicitly.
        var termination = new ai.timefold.solver.core.config.solver.termination.TerminationConfig().withSpentLimit(budget);
        termination.setStepCountLimit(definition.stepLimit());
        var solver = definition.factory().buildSolver(new SolverConfigOverride().withTerminationConfig(termination));
        if (!(solver instanceof DefaultSolver<DayPlan> measured)) throw new IllegalStateException("Unsupported solver diagnostics implementation");
        long[] steps = {0};
        measured.addPhaseLifecycleListener(new PhaseLifecycleListenerAdapter<DayPlan>() {
            @Override public void stepEnded(@Nullable AbstractStepScope<DayPlan> scope) { steps[0]++; }
        });
        DayPlan solved = Required.value(solver.solve(PlanCopies.copy(initial)));
        long elapsed = measured.getTimeMillisSpent();
        Integer cap = definition.stepLimit();
        boolean stepLimit = cap != null && steps[0] >= cap;
        boolean timeLimit = elapsed >= budget.toMillis();
        String reason = measured.isTerminateEarly() ? "TERMINATED_EARLY" : stepLimit && timeLimit ? "STEP_AND_TIME_LIMIT"
                : stepLimit ? "STEP_LIMIT" : timeLimit ? "TIME_LIMIT" : "PHASE_COMPLETED";
        return new Result(solved, new Statistics(Required.value(definition.variant().name()), definition.seed(), definition.fingerprint(), reason,
                budget.toMillis(), cap, steps[0], measured.getMoveEvaluationCount(), measured.getScoreCalculationCount(), elapsed,
                measured.getSolverScope().getBestSolutionTimeMillisSpent()));
    }
}
