package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.HardSoftScore;

import java.util.List;
import java.util.Map;

@PlanningSolution
public class DayPlan {
    @PlanningEntityCollectionProperty
    private List<TechRoute> routes;
    @PlanningEntityCollectionProperty
    @ValueRangeProvider(id = "visits")
    private List<PlanVisit> visits;
    private Map<String, RoadLeg> matrix;
    private double regularHourly;
    private double overtimeHourly;
    private double mileagePerMile;
    private double travelBufferPct;
    private long travelBufferMinutes;
    @PlanningScore
    private HardSoftScore score;

    public record RoadLeg(long seconds, long meters) { }
    public DayPlan() { }
    public DayPlan(List<TechRoute> routes, List<PlanVisit> visits, Map<String, RoadLeg> matrix,
                   double regularHourly, double overtimeHourly, double mileagePerMile,
                   double travelBufferPct, long travelBufferMinutes) {
        this.routes = routes; this.visits = visits; this.matrix = matrix;
        this.regularHourly = regularHourly; this.overtimeHourly = overtimeHourly;
        this.mileagePerMile = mileagePerMile; this.travelBufferPct = travelBufferPct;
        this.travelBufferMinutes = travelBufferMinutes;
    }
    public List<TechRoute> getRoutes() { return routes; }
    public void setRoutes(List<TechRoute> routes) { this.routes = routes; }
    public List<PlanVisit> getVisits() { return visits; }
    public void setVisits(List<PlanVisit> visits) { this.visits = visits; }
    public Map<String, RoadLeg> getMatrix() { return matrix; }
    public void setMatrix(Map<String, RoadLeg> matrix) { this.matrix = matrix; }
    public double getRegularHourly() { return regularHourly; }
    public double getOvertimeHourly() { return overtimeHourly; }
    public double getMileagePerMile() { return mileagePerMile; }
    public double getTravelBufferPct() { return travelBufferPct; }
    public long getTravelBufferMinutes() { return travelBufferMinutes; }
    public HardSoftScore getScore() { return score; }
    public void setScore(HardSoftScore score) { this.score = score; }
}
