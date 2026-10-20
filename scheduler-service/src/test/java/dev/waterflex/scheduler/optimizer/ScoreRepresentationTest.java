package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.score.BendableBigDecimalScore;
import ai.timefold.solver.core.api.score.BendableScore;
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
 * The equivalence gate of treatment S (docs/scheduler-policy.md, "Numeric contract"): the long score must carry every
 * integral level exactly and the fairness variance as rounded units, so it ranks any two plans of one revision as the
 * decimal score does, except that it may tie two plans whose variances differ by less than one unit. Any other
 * difference fails with both plans' scores in the message. Option (b) of the variance formula study keeps the decimal
 * score and carries the same rounded units ({@link BinaryVarianceConstraintProvider}), so it must equal the decimal
 * score on every other level and rank exactly as the long score does.
 */
class ScoreRepresentationTest {
    private static final SolverEngine.Definition DECIMAL = SolverEngine.configuration(SolverEngine.Variant.TABU_KOPT, 17);
    private static final SolverEngine.Definition LONG = SolverEngine.configuration(SolverEngine.Variant.TABU_KOPT_LONG_SCORE, 17);
    private static final SolverEngine.Definition BINARY = SolverEngine.configuration(SolverEngine.Variant.TABU_KOPT_BINARY_VARIANCE, 17);
    private static final BigDecimal UNITS = Required.value(BigDecimal.valueOf(RouteScoringFacts.VARIANCE_UNITS));
    private static final BigDecimal HALF_UNIT = Required.value(new BigDecimal("0.5000001"));

    /** {@code targeted}: a fairness-phase plan, whose soft objective level is the variance rather than overtime minutes. */
    private record Scored(String label, boolean targeted, BendableBigDecimalScore decimal, BendableScore integral, BendableBigDecimalScore binary) { }

    static BendableBigDecimalScore decimal(DayPlan plan) {
        DayPlan copy = PlanCopies.copy(plan);
        SolutionManager.<DayPlan, BendableBigDecimalScore>create(DECIMAL.factory()).update(copy);
        return Required.value(copy.getScore());
    }
    static BendableBigDecimalScore binary(DayPlan plan) {
        DayPlan copy = PlanCopies.copy(plan);
        SolutionManager.<DayPlan, BendableBigDecimalScore>create(BINARY.factory()).update(copy);
        return Required.value(copy.getScore());
    }
    static BendableScore integral(DayPlan plan) {
        LongScoreDayPlan copy = new LongScoreDayPlan(PlanCopies.copy(plan));
        SolutionManager.<LongScoreDayPlan, BendableScore>create(Required.value(LONG.longScore())).update(copy);
        return Required.value(copy.getScore());
    }

    /**
     * One fixture's groups of plans of one scoring target each: the fixture, its fairness-phase targets (the policy
     * ceiling and the reference cost itself), each with moves of it.
     */
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
    private static DayPlan moved(DayPlan start, Random random, int count) {
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
    private static List<List<Scored>> families() {
        List<List<Scored>> families = new ArrayList<>();
        Object[][] fixtures = {{20, SolverBenchmarkData.Workload.CLUSTERED, 7001L}, {20, SolverBenchmarkData.Workload.ABSENCE, 7002L},
                {20, SolverBenchmarkData.Workload.TIGHT_WINDOW, 7004L}, {50, SolverBenchmarkData.Workload.DISPERSED, 7003L}};
        long seed = 1;
        for (Object[] fixture : fixtures) {
            DayPlan base = SolverBenchmarkData.create((Integer) Required.value(fixture[0]), (SolverBenchmarkData.Workload) Required.value(fixture[1]), (Long) Required.value(fixture[2]));
            families.addAll(scored(fixture[0] + "-" + fixture[1], family(base, seed++)));
        }
        families.addAll(scored("small", family(DayConstraintProviderTest.fixture(), seed)));
        return families;
    }
    private static List<List<Scored>> scored(String name, List<List<DayPlan>> groups) {
        List<List<Scored>> scored = new ArrayList<>();
        for (int group = 0; group < groups.size(); group++) {
            List<Scored> plans = new ArrayList<>();
            List<DayPlan> members = Required.value(groups.get(group));
            for (int index = 0; index < members.size(); index++) {
                DayPlan plan = Required.value(members.get(index));
                plans.add(new Scored(name + "/" + group + "#" + index, plan.getScoringFacts().target() != null, decimal(plan), integral(plan), binary(plan)));
            }
            scored.add(plans);
        }
        return scored;
    }
    /** The decimal fairness level in units, as the long level must carry it to within half a unit. */
    private static BigDecimal units(BendableBigDecimalScore score) { return Required.value(score.softScore(DayScores.PHASE_OBJECTIVE).multiply(UNITS)); }

    @Test void everyIntegralLevelIsExactAndTheVarianceIsRoundedUnits() {
        boolean negative = false, zero = false, variance = false;
        for (List<Scored> family : families()) for (Scored plan : family) {
            for (int level = 0; level < 5; level++)
                assertEquals(plan.decimal().hardScore(level).longValueExact(), plan.integral().hardScore(level), plan.label() + " hard " + level + ": " + plan.decimal() + " / " + plan.integral());
            assertEquals(plan.decimal().softScore(DayScores.COST).longValueExact(), plan.integral().softScore(DayScores.COST), plan.label() + " cost");
            negative |= plan.integral().hardScore(DayScores.UNASSIGNED) < 0;
            zero |= Arrays.stream(plan.integral().hardScores()).allMatch(value -> value == 0);
            if (!plan.targeted()) {
                assertEquals(plan.decimal().softScore(DayScores.PHASE_OBJECTIVE).longValueExact(), plan.integral().softScore(DayScores.PHASE_OBJECTIVE), plan.label() + " overtime");
                continue;
            }
            BigDecimal difference = units(plan.decimal()).subtract(BigDecimal.valueOf(plan.integral().softScore(DayScores.PHASE_OBJECTIVE))).abs();
            assertTrue(difference.compareTo(HALF_UNIT) <= 0, plan.label() + " fairness level off by " + difference + " units: " + plan.decimal() + " / " + plan.integral());
            variance |= plan.decimal().softScore(DayScores.PHASE_OBJECTIVE).stripTrailingZeros().scale() > 0;
        }
        assertTrue(negative && zero && variance, "the plans must include negative, zero and fractional variance scores");
    }

    /** Plans are compared within one scoring target: across targets the soft objective level is a different quantity. */
    @Test void everyPairOfPlansIsRankedTheSameWay() {
        List<String> differences = new ArrayList<>();
        int ties = 0, pairs = 0;
        for (List<Scored> family : families()) for (Scored first : family) for (Scored second : family) {
            if (first == second) continue;
            pairs++;
            int decimal = Integer.signum(first.decimal().compareTo(second.decimal())), integral = Integer.signum(first.integral().compareTo(second.integral()));
            if (decimal == integral) continue;
            // The one permitted difference: a tie on the long level where the decimal variances differ by less than one unit.
            boolean sameElsewhere = Arrays.equals(first.integral().hardScores(), second.integral().hardScores())
                    && first.integral().softScore(DayScores.COST) == second.integral().softScore(DayScores.COST);
            BigDecimal gap = units(first.decimal()).subtract(units(second.decimal())).abs();
            if (integral == 0 && sameElsewhere && gap.compareTo(BigDecimal.ONE) < 0) { ties++; continue; }
            differences.add(first.label() + " " + first.decimal() + " / " + first.integral() + " against " + second.label() + " " + second.decimal() + " / " + second.integral());
        }
        assertEquals(List.of(), differences, "ranking differences (" + ties + " permitted ties)");
        assertTrue(pairs >= 9_000, "too few pairs compared: " + pairs);
    }

    /** Option (b): every level but the variance is the decimal one, and the variance level is the long level's units exactly. */
    @Test void theBinaryVarianceScoreIsTheDecimalScoreWithTheLongVarianceLevel() {
        int targeted = 0;
        for (List<Scored> family : families()) for (Scored plan : family) {
            for (int level = 0; level < 5; level++) assertEquals(0, plan.decimal().hardScore(level).compareTo(plan.binary().hardScore(level)), plan.label() + " hard " + level);
            assertEquals(0, plan.decimal().softScore(DayScores.COST).compareTo(plan.binary().softScore(DayScores.COST)), plan.label() + " cost");
            BigDecimal objective = plan.binary().softScore(DayScores.PHASE_OBJECTIVE);
            if (plan.targeted()) {
                targeted++;
                assertEquals(plan.integral().softScore(DayScores.PHASE_OBJECTIVE), objective.multiply(UNITS).longValueExact(), plan.label() + " variance units");
            } else assertEquals(0, plan.decimal().softScore(DayScores.PHASE_OBJECTIVE).compareTo(objective), plan.label() + " overtime");
        }
        assertTrue(targeted > 0);
        for (List<Scored> family : families()) for (Scored first : family) for (Scored second : family)
            assertEquals(Integer.signum(first.integral().compareTo(second.integral())), Integer.signum(first.binary().compareTo(second.binary())),
                    first.label() + " against " + second.label());
    }

    @Test void theCostCeilingBoundaryIsTheSameLevel() {
        DayPlan base = SolverBenchmarkData.create(20, SolverBenchmarkData.Workload.CLUSTERED, 7001);
        long cost = RouteEvaluator.evaluate(base).costCents();
        DayPlan atCeiling = PlanCopies.copy(base);
        atCeiling.setScoringFacts(atCeiling.getScoringFacts().withTarget(base, cost));
        assertEquals(0, integral(atCeiling).hardScore(DayScores.COST_CEILING));
        assertEquals(0, decimal(atCeiling).hardScore(DayScores.COST_CEILING).signum());
        Random random = new Random(5);
        int dearer = 0;
        for (int attempt = 0; attempt < 200 && dearer < 10; attempt++) {
            DayPlan plan = moved(atCeiling, random, 1);
            var decimal = decimal(plan); var integral = integral(plan);
            if (decimal.softScore(DayScores.COST).negate().longValueExact() <= cost) continue;
            dearer++;
            long excess = decimal.softScore(DayScores.COST).negate().longValueExact() - cost;
            assertEquals(-excess, decimal.hardScore(DayScores.COST_CEILING).longValueExact());
            assertEquals(-excess, integral.hardScore(DayScores.COST_CEILING));
        }
        assertTrue(dearer > 0, "no plan above the ceiling was found");
    }

    /** A solve capped at {@code steps}, with the decimal factory beside the long one for the long variant. */
    private static SolverEngine.Result stepLimited(SolverEngine.Definition base, DayPlan plan, int steps) {
        String limit = "<termination><spentLimit>PT10M</spentLimit><stepCountLimit>" + steps + "</stepCountLimit></termination>";
        String xml = Required.value(base.configurationXml().replace("<termination><spentLimit>PT15S</spentLimit></termination>", limit));
        // The decimal factory runs the variant's own constraints, unless the search runs on the long score beside it.
        String decimalXml = base.longScore() == null ? xml
                : Required.value(DECIMAL.configurationXml().replace("<termination><spentLimit>PT15S</spentLimit></termination>", limit));
        assertNotEquals(base.configurationXml(), xml, "the generated configuration changed shape");
        var decimal = Required.value(SolverFactory.<DayPlan>create(Required.value(SolverConfig.createFromXmlReader(new StringReader(decimalXml)).withRandomSeed(17L))));
        @org.jspecify.annotations.Nullable SolverFactory<LongScoreDayPlan> integral = base.longScore() == null ? null
                : Required.value(SolverFactory.<LongScoreDayPlan>create(Required.value(SolverConfig.createFromXmlReader(new StringReader(xml)).withRandomSeed(17L))));
        var definition = new SolverEngine.Definition(base.variant(), 17, base.environmentMode(), false, xml, base.fingerprint() + "|steps", steps, decimal, integral);
        return SolverExperiment.solve(definition, PlanCopies.copy(plan), Required.value(Duration.ofMinutes(10)));
    }
    private static String trajectory(SolverEngine.Result result) {
        List<String> arrangement = new ArrayList<>();
        for (TechRoute route : result.plan().getRoutes())
            arrangement.add(route.getId() + "=" + String.join(",", route.getVisits().stream().map(visit -> visit.getId()).toList()));
        return "score=" + result.plan().getScore() + " moves=" + result.statistics().moveEvaluations() + " steps=" + result.statistics().steps() + " " + arrangement;
    }

    @Test void aStepLimitedSearchTakesTheSameMovesInBothPhases() {
        for (Object[] fixture : new Object[][]{{20, SolverBenchmarkData.Workload.CLUSTERED, 7001L}, {20, SolverBenchmarkData.Workload.ABSENCE, 7002L}}) {
            DayPlan base = SolverBenchmarkData.create((Integer) Required.value(fixture[0]), (SolverBenchmarkData.Workload) Required.value(fixture[1]), (Long) Required.value(fixture[2]));
            var decimalCost = stepLimited(DECIMAL, base, 30);
            var longCost = stepLimited(LONG, base, 30);
            assertEquals(trajectory(decimalCost), trajectory(longCost), "cost phase " + fixture[1]);
            DayPlan reference = decimalCost.plan();
            DayPlan seed = PlanCopies.copy(reference);
            seed.setScoringFacts(seed.getScoringFacts().withTarget(reference, SchedulingPolicy.Rules.defaults().costCeiling(RouteEvaluator.evaluate(reference).costCents())));
            assertEquals(trajectory(stepLimited(DECIMAL, seed, 30)), trajectory(stepLimited(LONG, seed, 30)), "fairness phase " + fixture[1]);
            // Option (b) orders plans exactly as the long score does, so it takes the same moves; its final score is the decimal one.
            String binary = trajectory(stepLimited(BINARY, seed, 30)), integral = trajectory(stepLimited(LONG, seed, 30));
            assertEquals(integral.substring(integral.indexOf(" moves=")), binary.substring(binary.indexOf(" moves=")), "fairness phase moves " + fixture[1]);
            assertEquals(trajectory(decimalCost), trajectory(stepLimited(BINARY, base, 30)), "cost phase " + fixture[1]);
        }
    }

    @Test void theLongLevelsStayInRangeAtFiftyTechniciansAndTheOvertimeLimit() {
        // Every visit of a 50 technician day on one route: the largest lateness, availability and capacity excess the fixtures make.
        DayPlan base = SolverBenchmarkData.create(50, SolverBenchmarkData.Workload.DISPERSED, 7003);
        DayPlan crowded = PlanCopies.copy(base);
        TechRoute first = Required.value(crowded.getRoutes().getFirst());
        for (TechRoute route : crowded.getRoutes()) if (route != first) {
            for (PlanVisit visit : route.getVisits()) { first.getVisits().add(visit); visit.setTechnician(first); }
            route.getVisits().clear();
        }
        var decimal = decimal(crowded); var integral = integral(crowded);
        for (int level = 0; level < 5; level++) assertEquals(decimal.hardScore(level).longValueExact(), integral.hardScore(level));
        assertTrue(integral.hardScore(DayScores.QUANTITATIVE) < 0);
        // The variance at its widest: 50 technicians, one with a day's paid minutes and its 240 overtime minutes on one minute of capacity.
        List<RouteScoringFacts.@NonNull ScoredRoute> routes = new ArrayList<>();
        List<SchedulingPolicy.Workload> workloads = new ArrayList<>();
        Random random = new Random(11);
        for (int technician = 0; technician < 50; technician++) {
            long capacity = technician == 0 ? 1 : 1 + random.nextInt(1440), paid = technician == 0 ? 1440 + 240 : random.nextInt(1681);
            var metrics = new RouteTimeline.Result(new RouteTimeline.Violations(0, 0, 0, 0, 0), 0, Required.value(Map.of()), paid, 0, 0, 0, 0);
            routes.add(new RouteScoringFacts.ScoredRoute("t" + technician, capacity, true, metrics));
            workloads.add(new SchedulingPolicy.Workload("t" + technician, paid, capacity, Required.value(BigDecimal.ZERO)));
        }
        long units = base.getScoringFacts().varianceUnits(routes);
        BigDecimal expected = SchedulingPolicy.fairness(workloads).variance().multiply(UNITS);
        assertTrue(expected.subtract(BigDecimal.valueOf(units)).abs().compareTo(HALF_UNIT) <= 0, expected + " against " + units);
        assertTrue(units > 0 && units < Long.MAX_VALUE / 1_000, "the widest variance must leave the level far inside the long range: " + units);
    }
}
