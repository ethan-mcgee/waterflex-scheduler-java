package dev.waterflex.scheduler.optimizer;

import org.jspecify.annotations.Nullable;
import dev.waterflex.scheduler.Required;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.HardMediumSoftBigDecimalScore;
import ai.timefold.solver.core.api.domain.solution.ProblemFactProperty;

import java.util.List;
import java.util.Map;

@PlanningSolution
public class DayPlan {
    @PlanningEntityCollectionProperty
    private @Nullable List<TechRoute> routes;
    @PlanningEntityCollectionProperty
    @ValueRangeProvider(id = "visits")
    private @Nullable List<PlanVisit> visits;
    private @Nullable Map<String, RoadLeg> matrix;
    private @Nullable Double regularHourly;
    private @Nullable Double overtimeHourly;
    private @Nullable Double mileagePerMile;
    private @Nullable Double travelBufferPct;
    private @Nullable Long travelBufferMinutes;
    @PlanningScore
    private @Nullable HardMediumSoftBigDecimalScore score;
    @ProblemFactProperty
    private @Nullable RouteScoringFacts scoringFacts;

    public record RoadLeg(long seconds, long meters) { }
    public DayPlan() { }
    public DayPlan(List<TechRoute> routes, List<PlanVisit> visits, Map<String, RoadLeg> matrix,
                   double regularHourly, double overtimeHourly, double mileagePerMile,
                   double travelBufferPct, long travelBufferMinutes) {
        this.routes = routes; this.visits = visits; this.matrix = matrix;
        this.regularHourly = regularHourly; this.overtimeHourly = overtimeHourly;
        this.mileagePerMile = mileagePerMile; this.travelBufferPct = travelBufferPct;
        this.travelBufferMinutes = travelBufferMinutes;
        java.util.Set<String> demandServices = new java.util.HashSet<>();
        visits.forEach(visit -> demandServices.add(visit.getServiceId()));
        this.scoringFacts = new RouteScoringFacts(matrix, regularHourly, overtimeHourly, mileagePerMile, travelBufferPct, travelBufferMinutes, demandServices, null);
    }
    public List<TechRoute> getRoutes() { return Required.value(routes, "DayPlan.routes before initialization"); }
    public void setRoutes(List<TechRoute> routes) { this.routes = routes; }
    public List<PlanVisit> getVisits() { return Required.value(visits, "DayPlan.visits before initialization"); }
    public void setVisits(List<PlanVisit> visits) { this.visits = visits; }
    public Map<String, RoadLeg> getMatrix() { return Required.value(matrix, "DayPlan.matrix before initialization"); }
    public void setMatrix(Map<String, RoadLeg> matrix) { this.matrix = matrix; }
    public double getRegularHourly() { return Required.value(regularHourly, "DayPlan.regularHourly before initialization"); }
    public double getOvertimeHourly() { return Required.value(overtimeHourly, "DayPlan.overtimeHourly before initialization"); }
    public double getMileagePerMile() { return Required.value(mileagePerMile, "DayPlan.mileagePerMile before initialization"); }
    public double getTravelBufferPct() { return Required.value(travelBufferPct, "DayPlan.travelBufferPct before initialization"); }
    public long getTravelBufferMinutes() { return Required.value(travelBufferMinutes, "DayPlan.travelBufferMinutes before initialization"); }
    public @Nullable HardMediumSoftBigDecimalScore getScore() { return score; }
    public void setScore(@Nullable HardMediumSoftBigDecimalScore score) { this.score = score; }
    public RouteScoringFacts getScoringFacts() { return Required.value(scoringFacts, "scoring facts before initialization"); }
    public void setScoringFacts(RouteScoringFacts facts) { this.scoringFacts = facts; }
}
