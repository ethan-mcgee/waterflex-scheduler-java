package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.score.BendableBigDecimalScore;
import ai.timefold.solver.core.api.score.BendableScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import dev.waterflex.scheduler.Required;
import java.math.BigDecimal;
import java.util.*;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The numeric contract of the fairness objective (docs/scheduler-policy.md, "Numeric contract"): the search carries the
 * capacity-weighted variance in units of 1e-9 computed in binary64 (option b of the fairness measure study, PR 187),
 * while acceptance reads the DECIMAL128 variance. The objective must carry the decimal variance to within a unit and
 * rank every pair of plans of one scoring target as the decimal variance would, but for ties closer than the
 * tolerance. Any difference fails with the plans named. Load balance, the one study measure left, must keep
 * every other level as production has it. Every level of the long score is also checked against one rebuilt without the
 * constraint streams, on plans made to reach each level and each part of the quantitative and categorical levels.
 */
class FairnessUnitsTest {
    private static final SolverEngine.Definition PRODUCTION = SolverEngine.configuration(SolverEngine.Variant.TABU_KOPT, 17);
    private static final BigDecimal UNITS = Required.value(BigDecimal.valueOf(RouteScoringFacts.FAIRNESS_UNITS));

    /** {@code decimal} is the production score with the fairness objective replaced by the exact DECIMAL128 variance. */
    private record Scored(String label, DayPlan plan, boolean targeted, BendableBigDecimalScore production, BendableBigDecimalScore decimal,
                          BendableBigDecimalScore loadBalance) { }

    /** The plan scored by one variant, read as decimal levels with the objective at {@code objectiveScale} ({@link DecimalScores}). */
    static BendableBigDecimalScore score(SolverEngine.Definition definition, DayPlan plan, int objectiveScale) {
        return score(definition.factory(), plan, objectiveScale);
    }

    /** The plan scored by {@code factory}: a variant's, or a study measure's scorer ({@link SolverEngine#scorer}). */
    static BendableBigDecimalScore score(SolverFactory<DayPlan> factory, DayPlan plan, int objectiveScale) {
        DayPlan copy = PlanCopies.copy(plan);
        SolutionManager.<DayPlan, BendableScore>create(factory).update(copy);
        return DecimalScores.of(copy, objectiveScale);
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
            // Random moves rarely leave a technician over capacity or change the fleet's overtime; piling visits on one does.
            for (int count : new int[]{4, 8, 16}) plans.add(piled(Required.value(start), random, count));
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

    /** Moves up to {@code count} unpinned visits from other routes to the end of one random route. */
    static DayPlan piled(DayPlan start, Random random, int count) {
        DayPlan plan = PlanCopies.copy(start);
        List<TechRoute> routes = plan.getRoutes();
        TechRoute to = Required.value(routes.get(random.nextInt(routes.size())));
        for (int move = 0; move < count; move++) {
            List<TechRoute> movable = routes.stream().filter(route -> route != to && route.getVisits().size() > route.getPinnedPrefix()).toList();
            if (movable.isEmpty()) break;
            TechRoute from = Required.value(movable.get(random.nextInt(movable.size())));
            PlanVisit visit = Required.value(from.getVisits().remove(from.getPinnedPrefix() + random.nextInt(from.getVisits().size() - from.getPinnedPrefix())));
            to.getVisits().add(visit);
            visit.setTechnician(to);
        }
        return plan;
    }
    /** The small fixture with a road its first route drives declared unreachable, so that route scores on the categorical level. */
    private static DayPlan unreachableRoad() {
        DayPlan base = DayConstraintProviderTest.fixture();
        Map<String, DayPlan.RoadLeg> roads = new HashMap<>(base.getMatrix());
        assertNotNull(roads.remove("v2>v3"), "the small fixture drives v2 to v3");
        return new DayPlan(base.getRoutes(), base.getVisits(), roads, Required.value(Set.of("v2>v3")), 30, 45, .67, .2, 5);
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
        // Added after the others so their seeds, and so their plans, are unchanged: some technicians lack a skill some
        // visits need, and one road is unreachable, so random moves reach both parts of the categorical level.
        bases.add(Map.entry("20-MIXED_SKILL", SolverBenchmarkData.create(20, SolverBenchmarkData.Workload.MIXED_SKILL, 7005L)));
        bases.add(Map.entry("small-unreachable", unreachableRoad()));
        for (var base : bases) {
            List<List<DayPlan>> groups = family(Required.value(base.getValue()), seed++);
            for (int group = 0; group < groups.size(); group++) {
                List<Scored> plans = new ArrayList<>();
                List<DayPlan> members = Required.value(groups.get(group));
                for (int index = 0; index < members.size(); index++) {
                    DayPlan plan = Required.value(members.get(index));
                    boolean targeted = plan.getScoringFacts().target() != null;
                    BendableBigDecimalScore production = score(PRODUCTION, plan, DecimalScores.VARIANCE_SCALE);
                    BigDecimal[] soft = production.softScores().clone();
                    if (targeted) soft[DayScores.PHASE_OBJECTIVE] = Required.value(decimalVariance(plan).negate());
                    BendableBigDecimalScore decimal = Required.value(BendableBigDecimalScore.of(production.hardScores(), soft));
                    plans.add(new Scored(base.getKey() + "/" + group + "#" + index, plan, targeted, production, decimal, score(SolverEngine.scorer(FairnessMeasures.LoadBalance.class), plan, DayScores.UNFAIRNESS_SCALE)));
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

    /**
     * Every level of the long score equals the one rebuilt independently of the constraint streams: the hard levels from
     * each route's full evaluation and the unassigned count, the cost from DayScoreCalculator's decimal cents, and the
     * objective as overtime minutes in the cost phase or the decimal variance to within a unit in the fairness phase.
     */
    @Test void everyLongLevelIsTheIndependentlyComputedOne() {
        // How many plans are nonzero on each level and each part of the categorical and quantitative levels, so that a
        // level no plan reaches fails here rather than passing unchecked.
        Map<String, Integer> reached = new TreeMap<>();
        for (List<Scored> family : FAMILIES) for (Scored scored : family) {
            DayPlan plan = PlanCopies.copy(scored.plan());
            BendableScore score = Required.value(SolutionManager.<DayPlan, BendableScore>create(PRODUCTION.factory()).update(plan));
            long categorical = 0, quantitative = 0, overtime = 0;
            Set<String> parts = new TreeSet<>();
            for (TechRoute route : plan.getRoutes()) {
                var evaluation = RouteTimeline.evaluate(plan.getFacts(), Required.value(route));
                var violations = evaluation.violations();
                if (violations.qualifications() > 0) parts.add("hard 0 qualifications");
                if (violations.unavailableRoads() > 0) parts.add("hard 0 roads");
                if (violations.latenessSeconds() > 0) parts.add("hard 1 lateness");
                if (violations.availabilitySeconds() > 0) parts.add("hard 1 availability");
                if (violations.capacityMinutes() > 0) parts.add("hard 1 capacity");
                categorical += violations.qualifications() + violations.unavailableRoads();
                quantitative += violations.latenessSeconds() + violations.availabilitySeconds() + violations.capacityMinutes() * 60;
                overtime += evaluation.overtimeMinutes();
            }
            long cents = DayScoreCalculator.evaluate(plan).costCents();
            var target = plan.getScoringFacts().target();
            long[] hard = {categorical, quantitative, plan.getUnassignedVisitIds().size(),
                    target == null ? 0 : Math.abs(overtime - target.overtimeMinutes()), target == null ? 0 : Math.max(0, cents - target.costCeilingCents())};
            for (int level = 0; level < hard.length; level++) {
                assertEquals(-hard[level], score.hardScore(level), scored.label() + " hard " + level);
                if (hard[level] != 0) parts.add("hard " + level);
            }
            if (overtime != 0 && target == null) parts.add("soft 0 overtime");
            if (target != null) parts.add("soft 0 variance");
            for (String part : parts) reached.merge(part, 1, (known, one) -> Required.value(known) + Required.value(one));
            assertEquals(-cents, score.softScore(DayScores.COST), scored.label() + " cost");
            long objective = -score.softScore(DayScores.PHASE_OBJECTIVE);
            if (target == null) assertEquals(overtime, objective, scored.label() + " overtime");
            else assertTrue(BigDecimal.valueOf(objective).subtract(objective(scored.decimal()).multiply(UNITS)).abs().compareTo(BigDecimal.ONE) <= 0,
                    scored.label() + ": " + objective + " units against " + objective(scored.decimal()));
        }
        // At least five plans each (the fewest today is nine, for the unreachable road): a floor, not a pin, so a new fixture
        // need not recount, but a part that no plan reaches any longer fails.
        List<String> unreached = new ArrayList<>();
        for (String part : List.of("hard 0", "hard 0 qualifications", "hard 0 roads", "hard 1", "hard 1 lateness", "hard 1 availability",
                "hard 1 capacity", "hard 2", "hard 3", "hard 4", "soft 0 overtime", "soft 0 variance"))
            if (reached.getOrDefault(part, 0) < 5) unreached.add(part + "=" + reached.getOrDefault(part, 0));
        assertEquals(List.of(), unreached, "parts reached by too few plans, of " + reached);
    }

    /** Load balance's unfairness is counted to its six places exactly; a seventh would fail rather than round (15.2.1). */
    @Test void unfairnessIsCountedToItsSixPlacesOrRefused() {
        assertEquals(46875, DayScores.unfairnessUnits(new BigDecimal("0.046875")));
        assertEquals(0, DayScores.unfairnessUnits(Required.value(BigDecimal.ZERO)));
        assertEquals(12_000_000, DayScores.unfairnessUnits(new BigDecimal("12")));
        assertThrows(ArithmeticException.class, () -> DayScores.unfairnessUnits(new BigDecimal("0.0468751")));
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
        var metrics = new RouteTimeline.Result(new RouteTimeline.Violations(0, 0, 0, 0, 0), Required.value(Map.of()), paid, 0, 0, 0, 0);
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
}
