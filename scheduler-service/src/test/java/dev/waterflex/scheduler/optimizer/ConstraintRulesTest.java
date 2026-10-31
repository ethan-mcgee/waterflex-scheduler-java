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
    private static void penalty(String name, DayPlan plan, long expected) {
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
        penalty("Required qualifications", single(false,9,30,60,0,false), 1);
        penalty("Required qualifications", single(true,9,30,60,0,false), 0);
        penalty("Exclusive customer window lateness seconds", single(true,8,30,60,0,false), 1);
        penalty("Exclusive customer window lateness seconds", single(true,9,30,60,0,false), 0);
    }
    @Test void absenceReturnTravelAndCapacityUseSeconds() {
        penalty("Shift absences and return travel violation seconds", single(true,9,30,60,0,true), 1800);
        penalty("Shift absences and return travel violation seconds", single(true,9,60,120,60,false), 60);
        penalty("Daily and overtime capacity excess seconds", single(true,9,30,20,0,false), 600);
        penalty("Shift absences and return travel violation seconds", single(true,9,30,60,0,false), 0);
    }
    @Test void explicitlyUnreachableReturnIsCategorical() {
        var source = single(true,9,30,60,0,false);
        var blocked = new DayPlan(source.getRoutes(),source.getVisits(),
                Required.value(Map.<String,DayPlan.RoadLeg>of("t>v",new DayPlan.RoadLeg(0,0))),
                Required.value(Set.of("v>t:return")),30,45,0,0,0);
        penalty("Reachable directed roads",blocked,1);
        penalty("Reachable directed roads",single(true,9,30,60,0,false),0);
    }
    @Test void unassignedDemandIsCountedAndTargetLevelsDominateObjectives() {
        penalty("Unassigned demand", DailyConstructionTest.fixture(2,3,1,0,DayPlan.Mode.PARTIAL), 2);
        penalty("Unassigned demand", DailyConstructionTest.fixture(2,3,3,0,DayPlan.Mode.ASSIGNED), 0);
        for (int level=0; level<DayScores.HARD_LEVELS-1; level++) assertTrue(DayScores.hard(level).negate().compareTo(DayScores.hard(level+1).multiply(-1000000)) < 0);
        assertTrue(DayScores.hard(DayScores.HARD_LEVELS-1).negate().compareTo(DayScores.soft(0).multiply(-1000000)) < 0);
        for (int level=0; level<DayScores.SOFT_LEVELS-1; level++) assertTrue(DayScores.soft(level).negate().compareTo(DayScores.soft(level+1).multiply(-1000000)) < 0);
    }
    private static final PlanFacts.ArrivalBuffer QUARTER = new PlanFacts.ArrivalBuffer(25, 15, 60);
    private static DayPlan buffered(List<TechRoute> routes, List<PlanVisit> visits, Map<String, DayPlan.RoadLeg> roads, PlanFacts.ArrivalBuffer buffer) {
        return new DayPlan(routes, visits, roads, Required.value(Set.of()), dev.waterflex.scheduler.Monetary.legacy(30), dev.waterflex.scheduler.Monetary.legacy(45),
                dev.waterflex.scheduler.Monetary.legacy(0), dev.waterflex.scheduler.Monetary.legacy(0), 0, buffer);
    }
    /** One visit, window 8:00 to 9:00, no travel: the technician arrives when the shift starts, this many seconds after 8:00. */
    private static DayPlan arriving(long seconds, PlanFacts.ArrivalBuffer buffer) {
        var route = new TechRoute("t", Required.value(at(8).plusSeconds(seconds)), at(18), 600, 0, Required.value(Set.of("s")));
        var visit = new PlanVisit("v", "s", at(8), at(9), 30, null, null);
        route.getVisits().add(visit);
        return buffered(Required.value(List.of(route)), Required.value(List.of(visit)),
                Required.value(Map.of("t>v", new DayPlan.RoadLeg(0, 0), "v>t:return", new DayPlan.RoadLeg(0, 0))), buffer);
    }
    @Test void bufferSecondsCountFromTheDeadlineInclusive() {
        // 25 percent of an hour is 15 minutes, inside 15 to 60: the deadline is 8:45.
        penalty("Late-arrival buffer seconds", arriving(44 * 60 + 59, QUARTER), 0);
        penalty("Late-arrival buffer seconds", arriving(45 * 60, QUARTER), 1);
        penalty("Late-arrival buffer seconds", arriving(45 * 60 + 30, QUARTER), 31);
        penalty("Late-arrival buffer seconds", arriving(59 * 60 + 59, QUARTER), 900);
        penalty("Exclusive customer window lateness seconds", arriving(59 * 60 + 59, QUARTER), 0);
        // At the window end the arrival is late, and the repair gradient still counts the buffer it crossed.
        penalty("Exclusive customer window lateness seconds", arriving(60 * 60, QUARTER), 1);
        penalty("Late-arrival buffer seconds", arriving(60 * 60, QUARTER), 901);
        // No buffer: nothing is counted, however close to the end.
        penalty("Late-arrival buffer seconds", arriving(59 * 60 + 59, PlanFacts.ArrivalBuffer.NONE), 0);
        penalty("Late-arrival buffer ceiling excess", arriving(59 * 60 + 59, QUARTER), 0);
    }
    @Test void aFairerPlanMayNotUseMoreBufferThanTheReference() {
        var early = new TechRoute("a", at(8), at(18), 600, 0, Required.value(Set.of("s")));
        var late = new TechRoute("b", Required.value(at(8).plusSeconds(50 * 60)), at(18), 600, 0, Required.value(Set.of("s")));
        var visit = new PlanVisit("v", "s", at(8), at(9), 30, null, null);
        early.getVisits().add(visit);
        var roads = Required.value(Map.<String, DayPlan.RoadLeg>of("a>v", new DayPlan.RoadLeg(0, 0), "b>v", new DayPlan.RoadLeg(0, 0),
                "v>a:return", new DayPlan.RoadLeg(0, 0), "v>b:return", new DayPlan.RoadLeg(0, 0)));
        var reference = buffered(Required.value(List.of(early, late)), Required.value(List.of(visit)), roads, QUARTER);
        reference.setScoringFacts(reference.getScoringFacts().withTarget(reference, 1_000_000));
        penalty("Late-arrival buffer ceiling excess", reference, 0);
        penalty("Late-arrival buffer seconds", reference, 0);
        var changed = PlanCopies.copy(reference); var moved = changed.getRoutes().getFirst().getVisits().removeFirst();
        changed.getRoutes().get(1).getVisits().add(moved); moved.setTechnician(changed.getRoutes().get(1));
        // Arriving at 8:50 against an 8:45 deadline: five minutes and one second, above a reference that used none.
        penalty("Late-arrival buffer ceiling excess", changed, 301);
        penalty("Late-arrival buffer seconds", changed, 301);
    }
    @Test void weightedFairnessAndReferenceTargetsUseFrozenFacts() {
        var a = new TechRoute("a", at(8), at(9),120,60,Required.value(Set.of("s")));
        var b = new TechRoute("b", at(8), at(11),180,0,Required.value(Set.of("s")));
        var visit = new PlanVisit("v","s",at(8),at(11),90,null,null); b.getVisits().add(visit);
        var roads = Required.value(Map.<String, DayPlan.RoadLeg>of("a>v",new DayPlan.RoadLeg(0,0),"b>v",new DayPlan.RoadLeg(0,0),
                "v>a:return",new DayPlan.RoadLeg(0,0),"v>b:return",new DayPlan.RoadLeg(0,0)));
        var reference = new DayPlan(Required.value(List.<TechRoute>of(a,b)),Required.value(List.<PlanVisit>of(visit)),roads,30,45,0,0,0);
        reference.setScoringFacts(reference.getScoringFacts().withTarget(reference,4500));
        // Capacity weights 1/4 and 3/4, utilizations 0 and 1/2, mean 3/8, variance 3/64, counted in units of 1e-9.
        penalty("Capacity weighted workload variance", reference, new BigDecimal("0.046875").movePointRight(9).longValueExact());
        penalty("Recorded overtime target deviation", reference, 0);
        penalty("Fairness cost ceiling excess", reference, 0);
        var changed=PlanCopies.copy(reference); var moved=changed.getRoutes().get(1).getVisits().removeFirst();
        changed.getRoutes().getFirst().getVisits().add(moved); moved.setTechnician(changed.getRoutes().getFirst());
        penalty("Recorded overtime target deviation",changed,30);
        penalty("Fairness cost ceiling excess",changed,750);
        penalty("Modeled operating cost rounded at fleet level",changed,5250);
    }
}
