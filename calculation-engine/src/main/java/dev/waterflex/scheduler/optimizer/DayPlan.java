package dev.waterflex.scheduler.optimizer;

import org.jspecify.annotations.Nullable;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.Monetary;
import java.math.BigDecimal;
import ai.timefold.solver.core.api.domain.solution.*;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.BendableBigDecimalScore;
import java.util.*;

@PlanningSolution
public class DayPlan {
    @PlanningEntityCollectionProperty private @Nullable List<TechRoute> routes;
    @PlanningEntityCollectionProperty @ValueRangeProvider(id = "visits") private @Nullable List<PlanVisit> visits;
    @PlanningScore(bendableHardLevelsSize = 5, bendableSoftLevelsSize = 2) private @Nullable BendableBigDecimalScore score;
    @ProblemFactProperty private @Nullable RouteScoringFacts scoringFacts;
    public enum Mode { ASSIGNED, COLD, PARTIAL, REPAIR }
    private Mode mode = Mode.ASSIGNED;

    public record RoadLeg(long seconds, long meters) {
        public RoadLeg {
            PlanFacts.check(seconds >= 0 && seconds <= Integer.MAX_VALUE && meters >= 0 && meters <= Integer.MAX_VALUE,
                    "road seconds/meters must be in [0, 2147483647]");
        }
    }
    public DayPlan() { }
    public DayPlan(List<TechRoute> routes, List<PlanVisit> visits, Map<String, RoadLeg> matrix,
            double regular, double overtime, double mileage, double pct, long minutes) {
        this(routes, visits, matrix, Monetary.legacy(regular), Monetary.legacy(overtime), Monetary.legacy(mileage), pct, minutes);
    }
    public DayPlan(List<TechRoute> routes, List<PlanVisit> visits, Map<String, RoadLeg> matrix, Set<String> unreachable,
            double regular, double overtime, double mileage, double pct, long minutes) {
        this(routes, visits, matrix, unreachable, Monetary.legacy(regular), Monetary.legacy(overtime), Monetary.legacy(mileage), pct, minutes);
    }
    public DayPlan(List<TechRoute> routes, List<PlanVisit> visits, Map<String, RoadLeg> matrix,
            BigDecimal regular, BigDecimal overtime, BigDecimal mileage, double pct, long minutes) {
        this(routes, visits, matrix, Required.value(Set.of()), regular, overtime, mileage, pct, minutes);
    }
    public DayPlan(List<TechRoute> routes, List<PlanVisit> visits, Map<String, RoadLeg> matrix, Set<String> unreachable,
            BigDecimal regular, BigDecimal overtime, BigDecimal mileage, double pct, long minutes) {
        this.routes = Required.value(List.copyOf(routes)); this.visits = Required.value(List.copyOf(visits));
        this.scoringFacts = new RouteScoringFacts(PlanFacts.capture(routes, visits, matrix, unreachable, regular, overtime, mileage, pct, minutes), null);
    }
    DayPlan(List<TechRoute> routes, List<PlanVisit> visits, RouteScoringFacts facts) {
        this.routes = Required.value(List.copyOf(routes)); this.visits = Required.value(List.copyOf(visits)); this.scoringFacts = facts;
    }
    public List<TechRoute> getRoutes() { return Required.value(routes, "DayPlan.routes before initialization"); }
    public List<PlanVisit> getVisits() { return Required.value(visits, "DayPlan.visits before initialization"); }
    public PlanFacts getFacts() { return getScoringFacts().facts(); }
    public Map<String, RoadLeg> getMatrix() { return getFacts().matrix(); }
    public BigDecimal getRegularHourly() { return getFacts().regularHourly(); }
    public BigDecimal getOvertimeHourly() { return getFacts().overtimeHourly(); }
    public BigDecimal getMileagePerMile() { return getFacts().mileagePerMile(); }
    public double getTravelBufferPct() { return getFacts().travelBufferPct(); }
    public long getTravelBufferMinutes() { return getFacts().travelBufferMinutes(); }
    public @Nullable BendableBigDecimalScore getScore() { return score; }
    public void setScore(@Nullable BendableBigDecimalScore score) { this.score = score; }
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public List<String> getUnassignedVisitIds() {
        Set<String> assigned = new HashSet<>();
        for (TechRoute route : getRoutes()) for (PlanVisit visit : route.getVisits()) assigned.add(visit.getId());
        return Required.value(getVisits().stream().filter(visit -> !assigned.contains(visit.getId())).map(visit -> visit.getId()).toList());
    }
    public void validateInputMode() {
        getFacts().validateEntities(this, mode == Mode.ASSIGNED);
        if (mode == Mode.COLD) PlanFacts.check(getUnassignedVisitIds().size() == getVisits().size(), "cold input must have no assignments");
    }
    public RouteScoringFacts getScoringFacts() { return Required.value(scoringFacts, "scoring facts before initialization"); }
    public void setScoringFacts(RouteScoringFacts facts) {
        PlanFacts.check(facts.facts() == getFacts(), "scoring target must use the same immutable fact revision");
        this.scoringFacts = facts;
    }
}
