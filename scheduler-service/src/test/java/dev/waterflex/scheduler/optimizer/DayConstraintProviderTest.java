package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.score.HardMediumSoftBigDecimalScore;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import dev.waterflex.scheduler.Required;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DayConstraintProviderTest {
    static DayPlan fixture() {
        List<TechRoute> routes = new ArrayList<>();
        List<PlanVisit> visits = new ArrayList<>();
        Map<String, DayPlan.RoadLeg> matrix = new HashMap<>();
        for (int i = 0; i < 3; i++) routes.add(new TechRoute("t" + i, at(8), at(16), 540, 60, Required.value(Set.of("service"))));
        routes.get(1).getUnavailable().add(new TechRoute.Unavailable(at(11), at(12)));
        for (int i = 0; i < 6; i++) {
            PlanVisit visit = new PlanVisit("v" + i, "service", at(8 + i), at(10 + i), 30, "t0", at(8 + i));
            visits.add(visit);
            routes.getFirst().getVisits().add(visit);
        }
        List<String> locations = new ArrayList<>();
        routes.forEach(route -> { locations.add(route.getId()); locations.add(route.getId() + ":return"); });
        visits.forEach(visit -> locations.add(visit.getId()));
        for (String from : locations) for (String to : locations)
            matrix.put(from + ">" + to, new DayPlan.RoadLeg(from.equals(to) ? 0 : 300 + Math.floorMod(from.hashCode() - to.hashCode(), 600), 1000));
        return new DayPlan(routes, visits, matrix, 30, 45, .67, .2, 5);
    }
    @Test void incrementalMovesAndUndoMatchFreshScoreAndIndependentEvaluation() {
        DayPlan original = fixture();
        var originalMetrics = RouteEvaluator.evaluate(original);
        DayPlan solved = solve(PlanCopies.copy(original));
        var independent = RouteEvaluator.evaluate(solved);
        var full = DayScoreCalculator.evaluate(solved);
        assertTrue(independent.feasible());
        assertEquals(independent.arrivals(), full.arrivals());
        assertEquals(independent.costCents(), full.costCents());
        assertEquals(originalMetrics, RouteEvaluator.evaluate(original), "Solver must not mutate the original");
        assertEquals(HardMediumSoftBigDecimalScore.of(Required.value(BigDecimal.ZERO), Required.value(BigDecimal.valueOf(-independent.overtimeMinutes())),
                Required.value(BigDecimal.valueOf(-independent.costCents()))), solved.getScore());
    }
    @Test void fairnessPhaseRespectsReferenceTargetAndCeiling() {
        DayPlan reference = solve(fixture());
        var metrics = SchedulingPolicy.measure(reference);
        DayPlan seed = PlanCopies.copy(reference);
        long ceiling = SchedulingPolicy.Rules.defaults().costCeiling(metrics.costCents());
        seed.setScoringFacts(seed.getScoringFacts().withTarget(new RouteScoringFacts.Target(metrics.overtimeMinutes(), ceiling)));
        DayPlan fair = solve(seed);
        var result = SchedulingPolicy.measure(fair);
        assertEquals(metrics.overtimeMinutes(), result.overtimeMinutes());
        assertTrue(result.costCents() <= ceiling);
        assertTrue(result.fairness().variance().compareTo(metrics.fairness().variance()) <= 0);
    }
    @Test void constructedEqualCapacityCaseBalancesWithoutAdditionalCostOrOvertime() {
        var first = new TechRoute("a", at(8), at(16), 480, 0, Required.value(Set.of("service")));
        var idle = new TechRoute("b", at(8), at(16), 480, 0, Required.value(Set.of("service")));
        var one = new PlanVisit("one", "service", at(8), at(16), 60, "a", at(8));
        var two = new PlanVisit("two", "service", at(8), at(16), 60, "a", at(9));
        first.getVisits().addAll(Required.value(List.of(one, two)));
        Map<String, DayPlan.RoadLeg> matrix = new HashMap<>();
        for (String from : List.of("a", "b", "one", "two")) for (String to : List.of("one", "two", "a:return", "b:return"))
            matrix.put(from + ">" + to, new DayPlan.RoadLeg(0, 0));
        DayPlan seed = new DayPlan(Required.value(List.<TechRoute>of(first, idle)), Required.value(List.<PlanVisit>of(one, two)), matrix, 30, 45, 0, 0, 0);
        var baseline = SchedulingPolicy.measure(seed);
        seed.setScoringFacts(seed.getScoringFacts().withTarget(new RouteScoringFacts.Target(0, 6000)));
        var result = SchedulingPolicy.measure(solve(seed));
        assertEquals(6000, result.costCents());
        assertEquals(0, result.overtimeMinutes());
        assertEquals(0, result.fairness().variance().compareTo(BigDecimal.ZERO));
        assertTrue(result.fairness().variance().compareTo(baseline.fairness().variance()) < 0);
    }
    private static DayPlan solve(DayPlan seed) {
        SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml")
                .withEnvironmentMode(EnvironmentMode.FULL_ASSERT).withRandomSeed(17L)
                .withTerminationConfig(new TerminationConfig().withStepCountLimit(100));
        return Required.value(SolverFactory.<DayPlan>create(config).buildSolver().solve(seed));
    }
    private static Instant at(int hour) { return Required.value(Instant.parse("2026-09-23T" + String.format(Locale.ROOT, "%02d", hour) + ":00:00Z")); }
}
