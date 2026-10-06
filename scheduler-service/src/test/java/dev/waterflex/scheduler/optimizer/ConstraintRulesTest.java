package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import dev.waterflex.scheduler.Required;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Known penalties independent of solve quality and of the route evaluator's own expected values. */
class ConstraintRulesTest {
    private static final ConstraintVerifier<DayConstraintProvider, @org.jspecify.annotations.NonNull DayPlan> VERIFIER = ConstraintVerifier.build(
            new DayConstraintProvider(), DayPlan.class, TechRoute.class, PlanVisit.class);
    private static Instant at(int hour) { return Required.value(Instant.parse("2030-01-01T%02d:00:00Z".formatted(hour))); }
    private static void penalty(String name, DayPlan plan, BigDecimal expected) {
        VERIFIER.verifyThat((provider, factory) -> Arrays.stream(provider.defineConstraints(factory))
                .filter(c -> c.getConstraintRef().id().equals(name)).findFirst().orElseThrow())
                .givenSolution(plan).settingAllShadowVariables().penalizesBy(expected);
    }
    private static DayPlan single(boolean qualified, int windowEnd, int duration, int maxDaily, int returnSeconds, boolean absent) {
        var route = new TechRoute("t", at(8), at(9), maxDaily, 0, Required.value(Set.of(qualified ? "s" : "other")));
        if (absent) route.getUnavailable().add(new TechRoute.Unavailable(at(8), at(9)));
        var visit = new PlanVisit("v", "s", at(7), at(windowEnd), duration, null, null);
        route.getVisits().add(visit);
        return new DayPlan(Required.value(List.<TechRoute>of(route)), Required.value(List.<PlanVisit>of(visit)),
                Required.value(Map.<String, DayPlan.RoadLeg>of("t>v", new DayPlan.RoadLeg(0,0), "v>t:return", new DayPlan.RoadLeg(returnSeconds,0))), 30,45,0,0,0);
    }
    @Test void qualificationsAndExclusiveWindowBoundaryHaveIndependentPenalties() {
        penalty("Required qualifications", single(false,9,30,60,0,false), Required.value(BigDecimal.ONE));
        penalty("Required qualifications", single(true,9,30,60,0,false), Required.value(BigDecimal.ZERO));
        penalty("Exclusive customer window lateness seconds", single(true,8,30,60,0,false), Required.value(BigDecimal.ONE));
        penalty("Exclusive customer window lateness seconds", single(true,9,30,60,0,false), Required.value(BigDecimal.ZERO));
    }
    @Test void absenceReturnTravelAndCapacityUseSeconds() {
        penalty("Shift absences and return travel violation seconds", single(true,9,30,60,0,true), Required.value(BigDecimal.valueOf(1800)));
        penalty("Shift absences and return travel violation seconds", single(true,9,60,120,60,false), Required.value(BigDecimal.valueOf(60)));
        penalty("Daily and overtime capacity excess seconds", single(true,9,30,20,0,false), Required.value(BigDecimal.valueOf(600)));
        penalty("Shift absences and return travel violation seconds", single(true,9,30,60,0,false), Required.value(BigDecimal.ZERO));
    }
    @Test void explicitlyUnreachableReturnIsCategorical() {
        var source = single(true,9,30,60,0,false);
        var blocked = new DayPlan(source.getRoutes(),source.getVisits(),
                Required.value(Map.<String,DayPlan.RoadLeg>of("t>v",new DayPlan.RoadLeg(0,0))),
                Required.value(Set.of("v>t:return")),30,45,0,0,0);
        penalty("Reachable directed roads",blocked,Required.value(BigDecimal.ONE));
        penalty("Reachable directed roads",single(true,9,30,60,0,false),Required.value(BigDecimal.ZERO));
    }
    @Test void unassignedDemandIsCountedAndTargetLevelsDominateObjectives() {
        penalty("Unassigned demand", DailyConstructionTest.fixture(2,3,1,0,DayPlan.Mode.PARTIAL), Required.value(BigDecimal.valueOf(2)));
        penalty("Unassigned demand", DailyConstructionTest.fixture(2,3,3,0,DayPlan.Mode.ASSIGNED), Required.value(BigDecimal.ZERO));
        for (int level=0; level<4; level++) assertTrue(DayScores.hard(level).negate().compareTo(DayScores.hard(level+1).multiply(-1000000)) < 0);
        assertTrue(DayScores.hard(4).negate().compareTo(DayScores.soft(0).multiply(-1000000)) < 0);
    }
    @Test void weightedFairnessAndReferenceTargetsUseFrozenFacts() {
        var a = new TechRoute("a", at(8), at(9),120,60,Required.value(Set.of("s")));
        var b = new TechRoute("b", at(8), at(11),180,0,Required.value(Set.of("s")));
        var visit = new PlanVisit("v","s",at(8),at(11),90,null,null); b.getVisits().add(visit);
        var roads = Required.value(Map.<String, DayPlan.RoadLeg>of("a>v",new DayPlan.RoadLeg(0,0),"b>v",new DayPlan.RoadLeg(0,0),
                "v>a:return",new DayPlan.RoadLeg(0,0),"v>b:return",new DayPlan.RoadLeg(0,0)));
        var reference = new DayPlan(Required.value(List.<TechRoute>of(a,b)),Required.value(List.<PlanVisit>of(visit)),roads,30,45,0,0,0);
        reference.setScoringFacts(reference.getScoringFacts().withTarget(reference,4500));
        // Capacity weights 1/4 and 3/4, utilizations 0 and 1/2, mean 3/8, variance 3/64.
        penalty("Capacity weighted workload variance", reference, new BigDecimal("0.046875"));
        penalty("Recorded overtime target deviation", reference, Required.value(BigDecimal.ZERO));
        penalty("Fairness cost ceiling excess", reference, Required.value(BigDecimal.ZERO));
        var changed=PlanCopies.copy(reference); var moved=changed.getRoutes().get(1).getVisits().removeFirst();
        changed.getRoutes().getFirst().getVisits().add(moved); moved.setTechnician(changed.getRoutes().getFirst());
        penalty("Recorded overtime target deviation",changed,Required.value(BigDecimal.valueOf(30)));
        penalty("Fairness cost ceiling excess",changed,Required.value(BigDecimal.valueOf(750)));
        penalty("Modeled operating cost rounded at fleet level",changed,Required.value(BigDecimal.valueOf(5250)));
    }
}
