package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.score.BendableBigDecimalScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.waterflex.scheduler.Required;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.util.*;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The equivalence gate of the fairness measure study (PR 187, docs/scheduler-policy.md "Numeric contract"). Every
 * measure variant must equal the production score on every level but the fairness objective. Options (b) and (c),
 * the production variance in binary64 and from running sums, must carry it to within a unit of 1e-9 of DECIMAL128 and
 * rank every pair of plans as production does, but for ties closer than that. The alternative measures must carry
 * what an independent DECIMAL128 computation of the same definition gives. Any difference fails with the plans named.
 */
class FairnessMeasureTest {
    private static final SolverEngine.Definition DECIMAL = SolverEngine.configuration(SolverEngine.Variant.TABU_KOPT, 17);
    private static final SolverEngine.Variant[] MEASURES = {SolverEngine.Variant.TABU_KOPT_BINARY_VARIANCE, SolverEngine.Variant.TABU_KOPT_INCREMENTAL_VARIANCE,
            SolverEngine.Variant.TABU_KOPT_MIN_MAX_UTILIZATION, SolverEngine.Variant.TABU_KOPT_UTILIZATION_SPREAD,
            SolverEngine.Variant.TABU_KOPT_ABSOLUTE_DEVIATION, SolverEngine.Variant.TABU_KOPT_LOAD_BALANCE};
    private static final BigDecimal UNITS = Required.value(BigDecimal.valueOf(FairnessMeasures.UNITS));
    private static final MathContext DECIMAL128 = Required.value(MathContext.DECIMAL128);

    private record Scored(String label, DayPlan plan, boolean targeted, BendableBigDecimalScore production, Map<SolverEngine.Variant, BendableBigDecimalScore> measures) { }

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
    private static final List<List<Scored>> FAMILIES = families();
    private static List<List<Scored>> families() {
        Map<SolverEngine.Variant, SolverEngine.Definition> definitions = new EnumMap<>(SolverEngine.Variant.class);
        for (var variant : MEASURES) definitions.put(variant, SolverEngine.configuration(Required.value(variant), 17));
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
                    Map<SolverEngine.Variant, BendableBigDecimalScore> measures = new EnumMap<>(SolverEngine.Variant.class);
                    for (var variant : MEASURES) measures.put(variant, score(Required.value(definitions.get(variant)), plan));
                    plans.add(new Scored(base.getKey() + "/" + group + "#" + index, plan, plan.getScoringFacts().target() != null, score(DECIMAL, plan), measures));
                }
                families.add(plans);
            }
        }
        return families;
    }
    private static BendableBigDecimalScore measure(Scored plan, SolverEngine.Variant variant) { return Required.value(plan.measures().get(variant)); }
    private static BigDecimal objective(BendableBigDecimalScore score) { return Required.value(score.softScore(DayScores.PHASE_OBJECTIVE).negate()); }

    /** The independent reading: each counted technician's DECIMAL128 utilization, as SchedulingPolicy.fairness normalizes it. */
    private static List<SchedulingPolicy.Workload> workloads(DayPlan plan) {
        Set<String> services = plan.getFacts().demandServices();
        List<SchedulingPolicy.Workload> workloads = new ArrayList<>();
        for (TechRoute route : plan.getRoutes()) {
            long capacity = SchedulingPolicy.regularCapacity(Required.value(route));
            if (capacity == 0 || Collections.disjoint(services, route.getQualifiedServiceIds())) continue;
            workloads.add(new SchedulingPolicy.Workload(route.getId(), RouteTimeline.evaluate(plan.getFacts(), route, false).paidMinutes(), capacity, Required.value(BigDecimal.ZERO)));
        }
        return Required.value(SchedulingPolicy.fairness(workloads).workloads());
    }
    private static BigDecimal expected(SolverEngine.Variant variant, DayPlan plan) {
        List<SchedulingPolicy.Workload> work = workloads(plan);
        if (work.isEmpty()) return Required.value(BigDecimal.ZERO);
        BigDecimal maximum = Required.value(work.stream().map(item -> item.utilization()).max(Comparator.naturalOrder()).orElseThrow());
        BigDecimal minimum = Required.value(work.stream().map(item -> item.utilization()).min(Comparator.naturalOrder()).orElseThrow());
        long capacity = work.stream().mapToLong(item -> item.regularCapacityMinutes()).sum(), paid = work.stream().mapToLong(item -> item.paidMinutes()).sum();
        BigDecimal mean = BigDecimal.valueOf(paid).divide(BigDecimal.valueOf(capacity), DECIMAL128);
        return switch (variant) {
            case TABU_KOPT_MIN_MAX_UTILIZATION -> maximum;
            case TABU_KOPT_UTILIZATION_SPREAD -> Required.value(maximum.subtract(minimum));
            case TABU_KOPT_ABSOLUTE_DEVIATION -> {
                BigDecimal sum = BigDecimal.ZERO;
                for (var item : work) sum = sum.add(item.utilization().subtract(mean).abs().multiply(BigDecimal.valueOf(item.regularCapacityMinutes())));
                yield Required.value(sum.divide(BigDecimal.valueOf(capacity), DECIMAL128));
            }
            default -> throw new IllegalArgumentException("no independent reading for " + variant);
        };
    }

    @Test void everyMeasureKeepsEveryOtherLevel() {
        for (List<Scored> family : FAMILIES) for (Scored plan : family) for (var variant : MEASURES) {
            BendableBigDecimalScore measured = measure(Required.value(plan), Required.value(variant));
            for (int level = 0; level < 5; level++)
                assertEquals(0, plan.production().hardScore(level).compareTo(measured.hardScore(level)), plan.label() + " " + variant + " hard " + level);
            assertEquals(0, plan.production().softScore(DayScores.COST).compareTo(measured.softScore(DayScores.COST)), plan.label() + " " + variant + " cost");
            // Without a fairness target the objective level is overtime minutes, the same for every measure.
            if (!plan.targeted()) assertEquals(0, plan.production().softScore(DayScores.PHASE_OBJECTIVE).compareTo(measured.softScore(DayScores.PHASE_OBJECTIVE)), plan.label() + " " + variant + " overtime");
        }
    }

    @Test void theBinaryAndRunningSumVariancesCarryTheDecimalVarianceToAUnit() {
        int targeted = 0;
        for (List<Scored> family : FAMILIES) for (Scored plan : family) if (plan.targeted()) {
            targeted++;
            BigDecimal exact = objective(plan.production()).multiply(UNITS);
            for (var variant : new SolverEngine.Variant[]{SolverEngine.Variant.TABU_KOPT_BINARY_VARIANCE, SolverEngine.Variant.TABU_KOPT_INCREMENTAL_VARIANCE}) {
                BigDecimal carried = objective(measure(Required.value(plan), Required.value(variant))).multiply(UNITS);
                assertTrue(carried.subtract(exact).abs().compareTo(BigDecimal.ONE) <= 0, plan.label() + " " + variant + ": " + carried + " units against " + exact);
            }
        }
        assertTrue(targeted > 100, "too few fairness-phase plans: " + targeted);
    }

    @Test void theBinaryAndRunningSumVariancesRankAsProductionDoes() {
        List<String> differences = new ArrayList<>();
        for (var variant : new SolverEngine.Variant[]{SolverEngine.Variant.TABU_KOPT_BINARY_VARIANCE, SolverEngine.Variant.TABU_KOPT_INCREMENTAL_VARIANCE})
            for (List<Scored> family : FAMILIES) for (Scored first : family) for (Scored second : family) {
                if (first == second) continue;
                int production = Integer.signum(first.production().compareTo(second.production()));
                int measured = Integer.signum(measure(Required.value(first), Required.value(variant)).compareTo(measure(Required.value(second), Required.value(variant))));
                if (production == measured) continue;
                // Permitted: two plans equal on every other level whose variances differ by less than two units (two roundings).
                BigDecimal gap = objective(first.production()).subtract(objective(second.production())).abs().multiply(UNITS);
                boolean sameElsewhere = Arrays.equals(first.production().hardScores(), second.production().hardScores())
                        && first.production().softScore(DayScores.COST).compareTo(second.production().softScore(DayScores.COST)) == 0;
                if (sameElsewhere && gap.compareTo(BigDecimal.valueOf(2)) < 0) continue;
                differences.add(variant + " " + first.label() + " " + first.production() + " against " + second.label() + " " + second.production());
            }
        assertEquals(List.of(), differences);
    }

    @Test void theAlternativeMeasuresCarryTheirDefinitions() {
        for (List<Scored> family : FAMILIES) for (Scored plan : family) if (plan.targeted()) {
            for (var variant : new SolverEngine.Variant[]{SolverEngine.Variant.TABU_KOPT_MIN_MAX_UTILIZATION, SolverEngine.Variant.TABU_KOPT_UTILIZATION_SPREAD,
                    SolverEngine.Variant.TABU_KOPT_ABSOLUTE_DEVIATION}) {
                BigDecimal carried = objective(measure(Required.value(plan), Required.value(variant))).multiply(UNITS), exact = expected(Required.value(variant), plan.plan()).multiply(UNITS);
                // Max and spread round each utilization once (spread twice); the absolute deviation rounds once in binary64.
                assertTrue(carried.subtract(exact).abs().compareTo(BigDecimal.valueOf(2)) <= 0, plan.label() + " " + variant + ": " + carried + " units against " + exact);
            }
            assertTrue(objective(measure(plan, SolverEngine.Variant.TABU_KOPT_LOAD_BALANCE)).signum() >= 0, plan.label() + " load balance");
        }
    }

    @Test void theUnitReadingsAreExactOnAHandCheckedFleet() {
        // Two technicians of equal capacity: the same paid minutes are perfectly balanced, different ones are not.
        var even = new RouteTimeline.Result(new RouteTimeline.Violations(0, 0, 0, 0, 0), 0, Required.value(Map.of()), 240, 0, 0, 0, 0);
        var heavy = new RouteTimeline.Result(new RouteTimeline.Violations(0, 0, 0, 0, 0), 0, Required.value(Map.of()), 360, 0, 0, 0, 0);
        List<RouteScoringFacts.@NonNull ScoredRoute> balanced = List.of(new RouteScoringFacts.ScoredRoute("a", 480, true, even), new RouteScoringFacts.ScoredRoute("b", 480, true, even));
        List<RouteScoringFacts.@NonNull ScoredRoute> uneven = List.of(new RouteScoringFacts.ScoredRoute("a", 480, true, even), new RouteScoringFacts.ScoredRoute("b", 480, true, heavy));
        assertEquals(0, FairnessMeasures.varianceUnits(Required.value(balanced)));
        assertEquals(500_000, FairnessMeasures.utilization(Required.value(balanced.getFirst()), 1_000_000));
        // Utilizations 0.5 and 0.75 about a mean of 0.625: variance 0.015625, absolute deviation 0.125.
        assertEquals(15_625_000, FairnessMeasures.varianceUnits(Required.value(uneven)));
        assertEquals(125_000_000, FairnessMeasures.absoluteDeviationUnits(Required.value(uneven)));
        long squares = FairnessMeasures.square(Required.value(uneven.getFirst())) + FairnessMeasures.square(Required.value(uneven.get(1)));
        assertEquals(15_625_000, FairnessMeasures.varianceUnits(960, 600, squares));
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

    @Test void theVariancesTakeTheProductionMovesAndEveryMeasureTheProductionCostMoves() {
        for (Object[] fixture : new Object[][]{{20, SolverBenchmarkData.Workload.CLUSTERED, 7001L}, {20, SolverBenchmarkData.Workload.ABSENCE, 7002L}}) {
            DayPlan base = SolverBenchmarkData.create((Integer) Required.value(fixture[0]), (SolverBenchmarkData.Workload) Required.value(fixture[1]), (Long) Required.value(fixture[2]));
            var production = stepLimited(SolverEngine.Variant.TABU_KOPT, base, 30);
            // The cost phase has no fairness target, so no measure changes a move there.
            for (var variant : MEASURES) assertEquals(moves(production), moves(stepLimited(Required.value(variant), base, 30)), "cost phase " + variant + " " + fixture[1]);
            DayPlan reference = production.plan();
            DayPlan seed = PlanCopies.copy(reference);
            seed.setScoringFacts(seed.getScoringFacts().withTarget(reference, SchedulingPolicy.Rules.defaults().costCeiling(RouteEvaluator.evaluate(reference).costCents())));
            String fair = moves(stepLimited(SolverEngine.Variant.TABU_KOPT, seed, 30));
            assertEquals(fair, moves(stepLimited(SolverEngine.Variant.TABU_KOPT_BINARY_VARIANCE, seed, 30)), "fairness phase (b) " + fixture[1]);
            assertEquals(fair, moves(stepLimited(SolverEngine.Variant.TABU_KOPT_INCREMENTAL_VARIANCE, seed, 30)), "fairness phase (c) " + fixture[1]);
        }
    }
}
