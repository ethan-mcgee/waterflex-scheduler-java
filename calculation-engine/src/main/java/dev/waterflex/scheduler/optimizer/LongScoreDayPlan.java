package dev.waterflex.scheduler.optimizer;

import org.jspecify.annotations.Nullable;
import dev.waterflex.scheduler.Required;
import ai.timefold.solver.core.api.domain.solution.*;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.BendableScore;
import java.util.List;

/**
 * The score representation study's planning solution (treatment S, experiment-only): the same routes, visits and fact
 * revision as {@link DayPlan}, scored with a long {@link BendableScore} by {@link LongScoreConstraintProvider}. Only
 * {@link SolverEngine} builds one, for {@link SolverEngine.Variant#TABU_KOPT_LONG_SCORE}, and turns the solved one back
 * into a {@link DayPlan} with its decimal score, so no reader outside the solve sees this class.
 */
@PlanningSolution
public class LongScoreDayPlan {
    @PlanningEntityCollectionProperty private @Nullable List<TechRoute> routes;
    @PlanningEntityCollectionProperty @ValueRangeProvider(id = "visits") private @Nullable List<PlanVisit> visits;
    @PlanningScore(bendableHardLevelsSize = 5, bendableSoftLevelsSize = 2) private @Nullable BendableScore score;
    @ProblemFactProperty private @Nullable RouteScoringFacts scoringFacts;

    public LongScoreDayPlan() { }
    /** Shares the plan's entities: the caller hands over a working copy it no longer reads. */
    LongScoreDayPlan(DayPlan working) {
        routes = working.getRoutes(); visits = working.getVisits(); scoringFacts = working.getScoringFacts();
    }
    public List<TechRoute> getRoutes() { return Required.value(routes, "LongScoreDayPlan.routes before initialization"); }
    public List<PlanVisit> getVisits() { return Required.value(visits, "LongScoreDayPlan.visits before initialization"); }
    public RouteScoringFacts getScoringFacts() { return Required.value(scoringFacts, "LongScoreDayPlan.scoringFacts before initialization"); }
    public @Nullable BendableScore getScore() { return score; }
    public void setScore(@Nullable BendableScore score) { this.score = score; }
    /** The solved arrangement as a plan of the given mode; its score is left for the decimal score director to set. */
    DayPlan toDayPlan(DayPlan.Mode mode) {
        DayPlan plan = new DayPlan(getRoutes(), getVisits(), getScoringFacts());
        plan.setMode(mode); return plan;
    }
}
