package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import java.io.StringReader;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Explicit benchmark configuration shared by native and complete-policy adapters. */
public final class CampaignSolverConfiguration {
    public record Move(String family, double weight) {
        public Move {
            if (!Set.of("listChange", "listSwap", "subListChange", "subListSwap").contains(family)
                    || !Double.isFinite(weight) || weight <= 0) throw new IllegalArgumentException("Unsupported move");
        }
    }
    public record Termination(String kind, String scope, boolean spentCap, @Nullable Integer stepCap,
            @Nullable Long windowMs, @Nullable Double minimumImprovementRatio, @Nullable Long unimprovedMs) {
        public Termination {
            if (!Set.of("fixed", "diminished-returns", "unimproved-time").contains(kind)
                    || !Set.of("solver", "local-search").contains(scope) || !spentCap
                    || stepCap != null && stepCap <= 0) throw new IllegalArgumentException("Invalid termination");
            boolean diminished = kind.equals("diminished-returns"), unimproved = kind.equals("unimproved-time");
            if (diminished != (windowMs != null && minimumImprovementRatio != null)
                    || !diminished && (windowMs != null || minimumImprovementRatio != null)
                    || unimproved != (unimprovedMs != null)
                    || windowMs != null && windowMs <= 0 || unimprovedMs != null && unimprovedMs <= 0
                    || minimumImprovementRatio != null && (!Double.isFinite(minimumImprovementRatio) || minimumImprovementRatio <= 0))
                throw new IllegalArgumentException("Inapplicable termination fields must be null");
        }
    }
    public record Settings(String id, String acceptor, int acceptorSize, int acceptedCountLimit, int selectedCountLimit,
            List<@org.jspecify.annotations.NonNull Move> moves, String environmentMode, String moveThreads, int nativeParallelBenchmarkCount, Termination termination) {
        public Settings {
            if (id.isBlank() || !Set.of("TABU", "LATE_ACCEPTANCE").contains(acceptor) || acceptorSize < 1
                    || acceptedCountLimit < 1 || selectedCountLimit < acceptedCountLimit || moves.isEmpty()
                    || moves.stream().map(Move::family).distinct().count() != moves.size()
                    || !Set.of("NO_ASSERT", "FULL_ASSERT", "NON_INTRUSIVE_FULL_ASSERT", "PHASE_ASSERT").contains(environmentMode)
                    || !moveThreads.equals("NONE") || nativeParallelBenchmarkCount != 1)
                throw new IllegalArgumentException("Unsupported Community benchmark settings");
            moves = Required.value(List.copyOf(moves));
        }
    }
    private CampaignSolverConfiguration() { }
    public static SolverEngine.Definition definition(Settings settings, long seed, boolean construction, long capMs) {
        if (seed < 0 || capMs < 1) throw new IllegalArgumentException("Invalid seed/cap");
        StringBuilder moves = new StringBuilder();
        for (Move move : settings.moves()) moves.append('<').append(move.family()).append("MoveSelector><fixedProbabilityWeight>")
                .append(move.weight()).append("</fixedProbabilityWeight></").append(move.family()).append("MoveSelector>");
        String acceptor = settings.acceptor().equals("TABU") ? "entityTabuSize" : "lateAcceptanceSize";
        Termination policy = settings.termination();
        String diminished = policy.kind().equals("diminished-returns") ? "<diminishedReturns><slidingWindowMilliseconds>" + policy.windowMs()
                + "</slidingWindowMilliseconds><minimumImprovementRatio>" + policy.minimumImprovementRatio() + "</minimumImprovementRatio></diminishedReturns>" : "";
        String extra = policy.kind().equals("unimproved-time") ? "<unimprovedMillisecondsSpentLimit>" + policy.unimprovedMs() + "</unimprovedMillisecondsSpentLimit>" : "";
        extra += policy.stepCap() == null ? "" : "<stepCountLimit>" + policy.stepCap() + "</stepCountLimit>";
        String solverTermination = "<termination>" + (policy.scope().equals("solver") ? diminished : "")
                + "<millisecondsSpentLimit>" + capMs + "</millisecondsSpentLimit>" + (policy.scope().equals("solver") ? extra : "") + "</termination>";
        extra = diminished + extra;
        String phaseTermination = policy.scope().equals("local-search") && !extra.isEmpty() ? "<termination>" + extra + "</termination>" : "";
        String xml = "<solver xmlns=\"https://timefold.ai/xsd/solver\"><environmentMode>" + settings.environmentMode()
                + "</environmentMode><randomSeed>" + seed + "</randomSeed><moveThreadCount>NONE</moveThreadCount>"
                + "<solutionClass>dev.waterflex.scheduler.optimizer.DayPlan</solutionClass><entityClass>dev.waterflex.scheduler.optimizer.TechRoute</entityClass>"
                + "<entityClass>dev.waterflex.scheduler.optimizer.PlanVisit</entityClass><scoreDirectorFactory><constraintProviderClass>dev.waterflex.scheduler.optimizer.DayConstraintProvider</constraintProviderClass></scoreDirectorFactory>"
                + solverTermination + (construction ? "<constructionHeuristic/>" : "")
                + "<localSearch>" + phaseTermination + "<unionMoveSelector><selectedCountLimit>" + settings.selectedCountLimit() + "</selectedCountLimit>" + moves
                + "</unionMoveSelector><acceptor><" + acceptor + ">" + settings.acceptorSize() + "</" + acceptor
                + "></acceptor><forager><acceptedCountLimit>" + settings.acceptedCountLimit() + "</acceptedCountLimit></forager></localSearch></solver>";
        SolverConfig config = Required.value(SolverConfig.createFromXmlReader(new StringReader(xml)));
        var variant = settings.acceptor().equals("TABU") ? SolverEngine.Variant.TABU : SolverEngine.Variant.LATE_ACCEPTANCE;
        return new SolverEngine.Definition(variant,seed,EnvironmentMode.valueOf(settings.environmentMode()),construction,xml,
                CalculationJson.hash(xml + "|score=" + DailyDataset.SCORE_MODEL), Required.value(config.getTerminationConfig()).getStepCountLimit(),
                Required.value(SolverFactory.create(config)));
    }
}
