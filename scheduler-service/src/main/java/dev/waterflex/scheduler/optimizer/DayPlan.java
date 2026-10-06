package dev.waterflex.scheduler.optimizer;

import org.jspecify.annotations.Nullable;
import dev.waterflex.scheduler.Required;
import ai.timefold.solver.core.api.domain.solution.*;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.HardMediumSoftBigDecimalScore;
import java.util.*;

@PlanningSolution
public class DayPlan {
    @PlanningEntityCollectionProperty private @Nullable List<TechRoute> routes;
    @PlanningEntityCollectionProperty @ValueRangeProvider(id = "visits") private @Nullable List<PlanVisit> visits;
    @PlanningScore private @Nullable HardMediumSoftBigDecimalScore score;
    @ProblemFactProperty private @Nullable RouteScoringFacts scoringFacts;

    public record RoadLeg(long seconds, long meters) {
        public RoadLeg {
            PlanFacts.check(seconds >= 0 && seconds <= Integer.MAX_VALUE && meters >= 0 && meters <= Integer.MAX_VALUE,
                    "road seconds/meters must be in [0, 2147483647]");
        }
    }
    public DayPlan() { }
    public DayPlan(List<TechRoute> routes, List<PlanVisit> visits, Map<String, RoadLeg> matrix,
            double regular, double overtime, double mileage, double pct, long minutes) {
        this(routes, visits, matrix, Required.value(Set.of()), regular, overtime, mileage, pct, minutes);
    }
    public DayPlan(List<TechRoute> routes, List<PlanVisit> visits, Map<String, RoadLeg> matrix, Set<String> unreachable,
            double regular, double overtime, double mileage, double pct, long minutes) {
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
    public double getRegularHourly() { return getFacts().regularHourly(); }
    public double getOvertimeHourly() { return getFacts().overtimeHourly(); }
    public double getMileagePerMile() { return getFacts().mileagePerMile(); }
    public double getTravelBufferPct() { return getFacts().travelBufferPct(); }
    public long getTravelBufferMinutes() { return getFacts().travelBufferMinutes(); }
    public @Nullable HardMediumSoftBigDecimalScore getScore() { return score; }
    public void setScore(@Nullable HardMediumSoftBigDecimalScore score) { this.score = score; }
    public RouteScoringFacts getScoringFacts() { return Required.value(scoringFacts, "scoring facts before initialization"); }
    public void setScoringFacts(RouteScoringFacts facts) {
        PlanFacts.check(facts.facts() == getFacts(), "scoring target must use the same immutable fact revision");
        this.scoringFacts = facts;
    }
}
