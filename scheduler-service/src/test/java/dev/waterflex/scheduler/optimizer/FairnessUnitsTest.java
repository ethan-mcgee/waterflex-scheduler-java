package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.score.BendableBigDecimalScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.waterflex.scheduler.Required;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The numeric contract of the fairness objective (docs/scheduler-policy.md, "Numeric contract"): the search carries the
 * capacity-weighted variance in units of 1e-9 computed in binary64 (option b of the fairness measure study, PR 187),
 * while acceptance reads the DECIMAL128 variance. The objective must carry the decimal variance to within a unit and
 * rank every pair of plans of one scoring target as the decimal variance would, but for ties closer than the
 * tolerance. Any difference fails with the plans named. Load balance, the one experiment-only objective left, must keep
 * every other level as production has it.
 */
class FairnessUnitsTest {
    private static final SolverEngine.Definition PRODUCTION = SolverEngine.configuration(SolverEngine.Variant.TABU_KOPT, 17);
    private static final SolverEngine.Definition LOAD_BALANCE = SolverEngine.configuration(SolverEngine.Variant.TABU_KOPT_LOAD_BALANCE, 17);
    private static final BigDecimal UNITS = Required.value(BigDecimal.valueOf(RouteScoringFacts.FAIRNESS_UNITS));

    /** {@code decimal} is the production score with the fairness objective replaced by the exact DECIMAL128 variance. */
    private record Scored(String label, DayPlan plan, boolean targeted, BendableBigDecimalScore production, BendableBigDecimalScore decimal,
                          BendableBigDecimalScore loadBalance) { }

    static BendableBigDecimalScore score(SolverEngine.Definition definition, DayPlan plan) {
        DayPlan copy = PlanCopies.copy(plan);
        SolutionManager.<DayPlan, BendableBigDecimalScore>create(definition.factory()).update(copy);
        return Required.value(copy.getScore());
    }

    /** One fixture's groups of plans of one scoring target each: the fixture, its fairness-phase targets, and moves of each. */
    private static List<List<DayPlan>> family(DayPlan base, long seed) {
        List<DayPlan> starts = new ArrayList<>(List.of(base));
        var reference = RouteEvaluator.evaluate(base);
        if (reference.feasible()) for (long ceiling : new long[]{SchedulingPolicy.Rules.defaults().costCeiling(reference.costCents()), reference.costCents()}) {
            DayPlan targeted = PlanCopies.copy(base);
            targeted.setScoringFacts(targeted.getScoringFacts().withTarget(base, ceiling));
            starts.add(targeted);
        }
        Random random = new Random(seed);
        List<List<DayPlan>> groups = new ArrayList<>();
        for (DayPlan start : starts) {
            List<DayPlan> plans = new ArrayList<>(List.of(start));
            for (int moves = 1; moves <= 24; moves++) plans.add(moved(Required.value(start), random, 1 + moves % 6));
            groups.add(plans);
        }
        return groups;
    }
    /** Relocates {@code count} unpinned visits to random positions; about one in ten is left unassigned instead. */
    static DayPlan moved(DayPlan start, Random random, int count) {
        DayPlan plan = PlanCopies.copy(start);
        List<TechRoute> routes = plan.getRoutes();
        for (int move = 0; move < count; move++) {
            List<TechRoute> movable = routes.stream().filter(route -> route.getVisits().size() > route.getPinnedPrefix()).toList();
            if (movable.isEmpty()) break;
            TechRoute from = Required.value(movable.get(random.nextInt(movable.size())));
            PlanVisit visit = Required.value(from.getVisits().remove(from.getPinnedPrefix() + random.nextInt(from.getVisits().size() - from.getPinnedPrefix())));
            if (random.nextInt(10) == 0) { visit.setTechnician(null); continue; }
            TechRoute to = Required.value(routes.get(random.nextInt(routes.size())));
            to.getVisits().add(to.getPinnedPrefix() + random.nextInt(to.getVisits().size() - to.getPinnedPrefix() + 1), visit);
            visit.setTechnician(to);
        }
        return plan;
    }

    /** The exact DECIMAL128 variance of the technicians the objective counts, as SchedulingPolicy.fairness computes it. */
    private static BigDecimal decimalVariance(DayPlan plan) {
        Set<String> services = plan.getFacts().demandServices();
        List<SchedulingPolicy.Workload> workloads = new ArrayList<>();
        for (TechRoute route : plan.getRoutes()) {
            long capacity = SchedulingPolicy.regularCapacity(Required.value(route));
            if (capacity == 0 || Collections.disjoint(services, route.getQualifiedServiceIds())) continue;
            workloads.add(new SchedulingPolicy.Workload(route.getId(), RouteTimeline.evaluate(plan.getFacts(), route, false).paidMinutes(), capacity, Required.value(BigDecimal.ZERO)));
        }
        return Required.value(SchedulingPolicy.fairness(workloads).variance());
    }

    private static final List<List<Scored>> FAMILIES = families();
    private static List<List<Scored>> families() {
        List<List<Scored>> families = new ArrayList<>();
        Object[][] fixtures = {{20, SolverBenchmarkData.Workload.CLUSTERED, 7001L}, {20, SolverBenchmarkData.Workload.ABSENCE, 7002L},
                {20, SolverBenchmarkData.Workload.TIGHT_WINDOW, 7004L}, {50, SolverBenchmarkData.Workload.DISPERSED, 7003L}};
        long seed = 1;
        List<Map.Entry<String, DayPlan>> bases = new ArrayList<>();
        for (Object[] fixture : fixtures)
            bases.add(Map.entry(fixture[0] + "-" + fixture[1], SolverBenchmarkData.create((Integer) Required.value(fixture[0]), (SolverBenchmarkData.Workload) Required.value(fixture[1]), (Long) Required.value(fixture[2]))));
        bases.add(Map.entry("small", DayConstraintProviderTest.fixture()));
        for (var base : bases) {
            List<List<DayPlan>> groups = family(Required.value(base.getValue()), seed++);
            for (int group = 0; group < groups.size(); group++) {
                List<Scored> plans = new ArrayList<>();
                List<DayPlan> members = Required.value(groups.get(group));
                for (int index = 0; index < members.size(); index++) {
                    DayPlan plan = Required.value(members.get(index));
                    boolean targeted = plan.getScoringFacts().target() != null;
                    BendableBigDecimalScore production = score(PRODUCTION, plan);
                    BigDecimal[] soft = production.softScores().clone();
                    if (targeted) soft[DayScores.PHASE_OBJECTIVE] = Required.value(decimalVariance(plan).negate());
                    BendableBigDecimalScore decimal = Required.value(BendableBigDecimalScore.of(production.hardScores(), soft));
                    plans.add(new Scored(base.getKey() + "/" + group + "#" + index, plan, targeted, production, decimal, score(LOAD_BALANCE, plan)));
                }
                families.add(plans);
            }
        }
        return families;
    }
    private static BigDecimal objective(BendableBigDecimalScore score) { return Required.value(score.softScore(DayScores.PHASE_OBJECTIVE).negate()); }

    @Test void theObjectiveCarriesTheDecimalVarianceToAUnit() {
        int targeted = 0;
        for (List<Scored> family : FAMILIES) for (Scored plan : family) if (plan.targeted()) {
            targeted++;
            BigDecimal carried = objective(plan.production()).multiply(UNITS), exact = objective(plan.decimal()).multiply(UNITS);
            // One rounding to the unit, plus binary64's error far below a unit.
            assertTrue(carried.subtract(exact).abs().compareTo(BigDecimal.ONE) <= 0, plan.label() + ": " + carried + " units against " + exact);
        }
        assertTrue(targeted > 100, "too few fairness-phase plans: " + targeted);
    }

    @Test void theObjectiveRanksPlansAsTheDecimalVarianceDoes() {
        List<String> differences = new ArrayList<>();
        for (List<Scored> family : FAMILIES) for (Scored first : family) for (Scored second : family) {
            if (first == second) continue;
            int exact = Integer.signum(first.decimal().compareTo(second.decimal()));
            int carried = Integer.signum(first.production().compareTo(second.production()));
            if (exact == carried) continue;
            // The tolerance: two plans equal on every other level whose variances differ by less than two units (two roundings).
            BigDecimal gap = objective(first.decimal()).subtract(objective(second.decimal())).abs().multiply(UNITS);
            boolean sameElsewhere = Arrays.equals(first.decimal().hardScores(), second.decimal().hardScores())
                    && first.decimal().softScore(DayScores.COST).compareTo(second.decimal().softScore(DayScores.COST)) == 0;
            if (sameElsewhere && gap.compareTo(BigDecimal.valueOf(2)) < 0) continue;
            differences.add(first.label() + " " + first.decimal() + " against " + second.label() + " " + second.decimal());
        }
        assertEquals(List.of(), differences);
    }

    @Test void loadBalanceKeepsEveryOtherLevel() {
        for (List<Scored> family : FAMILIES) for (Scored plan : family) {
            for (int level = 0; level < 5; level++)
                assertEquals(0, plan.production().hardScore(level).compareTo(plan.loadBalance().hardScore(level)), plan.label() + " hard " + level);
            assertEquals(0, plan.production().softScore(DayScores.COST).compareTo(plan.loadBalance().softScore(DayScores.COST)), plan.label() + " cost");
            // Without a fairness target the objective level is overtime minutes, the same for both.
            if (!plan.targeted()) assertEquals(0, plan.production().softScore(DayScores.PHASE_OBJECTIVE).compareTo(plan.loadBalance().softScore(DayScores.PHASE_OBJECTIVE)), plan.label() + " overtime");
            else assertTrue(objective(plan.loadBalance()).signum() >= 0, plan.label() + " load balance");
        }
    }

    private static RouteScoringFacts.ScoredRoute route(String id, long capacity, long paid) {
        var metrics = new RouteTimeline.Result(new RouteTimeline.Violations(0, 0, 0, 0, 0), 0, Required.value(Map.of()), paid, 0, 0, 0, 0);
        return new RouteScoringFacts.ScoredRoute(id, capacity, true, metrics);
    }
    private static final RouteScoringFacts FACTS = DayConstraintProviderTest.fixture().getScoringFacts();

    @Test void theUnitsAreExactOnAHandCheckedFleet() {
        // Two technicians of equal capacity: the same paid minutes are perfectly balanced, different ones are not.
        assertEquals(0, FACTS.fairnessUnits(Required.value(List.of(route("a", 480, 240), route("b", 480, 240)))));
        // Utilizations 0.5 and 0.75 about a mean of 0.625: variance 0.015625.
        assertEquals(15_625_000, FACTS.fairnessUnits(Required.value(List.of(route("a", 480, 240), route("b", 480, 360)))));
        // An ineligible or zero-capacity technician is not counted.
        var ineligible = new RouteScoringFacts.ScoredRoute("c", 480, false, route("c", 480, 0).metrics());
        assertEquals(15_625_000, FACTS.fairnessUnits(Required.value(List.of(route("a", 480, 240), route("b", 480, 360), ineligible, route("d", 0, 60)))));
        assertEquals(500_000, FairnessMeasures.utilization(route("a", 480, 240), 1_000_000));
    }

    @Test void theToleranceTiesVariancesCloserThanHalfAUnit() {
        // One paid minute apart on very large capacities: variance (1 / 200000)^2 = 2.5e-11, below half a unit, so it reads
        // as perfectly even. Realistic capacities keep whole-minute changes far above this (docs/scheduler-policy.md).
        assertEquals(0, FACTS.fairnessUnits(Required.value(List.of(route("a", 100_000, 50_000), route("b", 100_000, 50_001)))));
    }

    @Test void fiftyTechniciansAtTheExtremesStayInsideTheLongRange() {
        // Half the fleet idle, half at a full day plus overtime on one minute of regular capacity: utilizations 0 and 1680.
        List<RouteScoringFacts.@NonNull ScoredRoute> fleet = new ArrayList<>();
        for (int i = 0; i < 50; i++) fleet.add(i % 2 == 0 ? route("t" + i, 1, 0) : route("t" + i, 1, 1680));
        assertEquals(705_600L * RouteScoringFacts.FAIRNESS_UNITS, FACTS.fairnessUnits(fleet));
    }

    /** A solve capped at {@code steps}, under the variant's own constraints. */
    private static SolverEngine.Result stepLimited(SolverEngine.Variant variant, DayPlan plan, int steps) {
        var base = SolverEngine.configuration(variant, 17);
        String xml = Required.value(base.configurationXml().replace("<termination><spentLimit>PT15S</spentLimit></termination>",
                "<termination><spentLimit>PT10M</spentLimit><stepCountLimit>" + steps + "</stepCountLimit></termination>"));
        assertNotEquals(base.configurationXml(), xml, "the generated configuration changed shape");
        var factory = Required.value(SolverFactory.<DayPlan>create(Required.value(SolverConfig.createFromXmlReader(new StringReader(xml)).withRandomSeed(17L))));
        var definition = new SolverEngine.Definition(variant, 17, base.environmentMode(), false, xml, base.fingerprint() + "|steps", steps, factory);
        return SolverExperiment.solve(definition, PlanCopies.copy(plan), Required.value(Duration.ofMinutes(10)));
    }
    private static String moves(SolverEngine.Result result) {
        List<String> arrangement = new ArrayList<>();
        for (TechRoute route : result.plan().getRoutes())
            arrangement.add(route.getId() + "=" + String.join(",", route.getVisits().stream().map(visit -> visit.getId()).toList()));
        return "moves=" + result.statistics().moveEvaluations() + " steps=" + result.statistics().steps() + " " + arrangement;
    }

    @Test void loadBalanceTakesTheProductionCostPhaseMoves() {
        for (Object[] fixture : new Object[][]{{20, SolverBenchmarkData.Workload.CLUSTERED, 7001L}, {20, SolverBenchmarkData.Workload.ABSENCE, 7002L}}) {
            DayPlan base = SolverBenchmarkData.create((Integer) Required.value(fixture[0]), (SolverBenchmarkData.Workload) Required.value(fixture[1]), (Long) Required.value(fixture[2]));
            // The cost phase has no fairness target, so the objective never changes a move there.
            assertEquals(moves(stepLimited(SolverEngine.Variant.TABU_KOPT, base, 30)), moves(stepLimited(SolverEngine.Variant.TABU_KOPT_LOAD_BALANCE, base, 30)), "cost phase " + fixture[1]);
        }
    }
}
