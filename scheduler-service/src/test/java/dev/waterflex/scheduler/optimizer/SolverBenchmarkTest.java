package dev.waterflex.scheduler.optimizer;

import static org.junit.jupiter.api.Assertions.*;
import dev.waterflex.scheduler.Required;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SolverBenchmarkTest {
    @Test void acceptsLongExperimentBudgetsWithoutRunningTheStudy() {
        for (long budget : new long[] {100, 15000, 90000, 120000, 240000, 600000, 900000})
            assertDoesNotThrow(() -> SolverBenchmark.validateBudget(budget));
        assertThrows(IllegalArgumentException.class, () -> SolverBenchmark.validateBudget(99));
        assertThrows(IllegalArgumentException.class, () -> SolverBenchmark.validateBudget(900001));
    }

    /** Fixture seed 0 must stay the fixture every earlier study measured: these are run 5d15129b's dataset fingerprints. */
    @Test void fixtureSeedZeroIsTheArchivedCanonicalFixture() throws Exception {
        Map<String, String> archived = Map.ofEntries(
                Map.entry("5/CLUSTERED", "ba7cec1dceafb7449bdbea083d3207fbfb355552f4ec0e0366a9114c001ccb29"),
                Map.entry("5/DISPERSED", "e4034e93612fc0a912ed3ecefc3682d24fd56fc1f3989b188ea847c4e8358a17"),
                Map.entry("5/MIXED_SKILL", "1952068623f9e93927f865da3ff5f645ff861be53a497f9e31caf6be4a7262af"),
                Map.entry("5/TIGHT_WINDOW", "55b4853e2ab683793cacbffdfde6aebcd558961102e31d852f354cac56a51472"),
                Map.entry("10/CLUSTERED", "f638317c9b0fc09740376420aa2b66049625482b8d35c2928ae80a9f5b71b4e9"),
                Map.entry("10/DISPERSED", "37e0937e9b418ee725f9da1258a35e6b133ee45dee41db1b4aaf174ec87498e6"),
                Map.entry("10/MIXED_SKILL", "06ce53b8a8655f66c84fd133b02c62e9d2d1b77eff2f083cf04fe6ca08a17b6b"),
                Map.entry("10/TIGHT_WINDOW", "8a9417425b026e9c45fb2b7d2d235c9ead1c6fde9e9916d8002415581a3dbe46"),
                Map.entry("20/CLUSTERED", "adf6ab3a3fa301cce6536e194aa309d7dfff8b3fba9bf16e0d52784f1ac5278e"),
                Map.entry("20/DISPERSED", "8518462179ba9e6ded07452936d99af42c8a2779eb3879f92b49f5ad315e10ff"),
                Map.entry("20/MIXED_SKILL", "e1f661ad28768907e1e8d41fe9cc51c029bac91f22333c66dddcaed3b5ef7498"),
                Map.entry("20/TIGHT_WINDOW", "2b8fbd6e7d31d6d4b5c02de8ecb27c450c0b938ffce4180bc3c49909481eafff"),
                Map.entry("50/CLUSTERED", "d978a74ec61edc6beaca98e8fe483ec4ae3e7431ed7ed34e3538db1adf814830"),
                Map.entry("50/DISPERSED", "a78d970776b097dd291afdd198eb01a042fd86c233aa7bf58a389857a56c3245"),
                Map.entry("50/MIXED_SKILL", "7a7480c9c009cc5c775492802a665bf57515afcaa642a19ae8b8238905a94a2f"),
                Map.entry("50/TIGHT_WINDOW", "a763572cfb72afd912ae17456533be10c3c0a68a0157629a2cd83e17df8cb995"));
        for (var entry : archived.entrySet()) {
            String[] key = entry.getKey().split("/");
            int fleet = Integer.parseInt(Required.value(key[0]));
            var workload = SolverBenchmarkData.Workload.valueOf(Required.value(key[1]));
            assertEquals(entry.getValue(), SolverBenchmark.fingerprint(SolverBenchmarkData.create(fleet, workload, 0)), entry.getKey());
            assertEquals(entry.getValue(), SolverBenchmark.fingerprint(SolverBenchmarkData.create(fleet, workload)), entry.getKey());
        }
    }

    /** New fixture seeds give new, valid fixtures of the same shape, and the same seed always gives the same fixture. */
    @Test void otherFixtureSeedsDrawNewFeasibleFixturesOfTheSameShape() throws Exception {
        for (int fleet : new int[] {5, 10, 20, 30, 50}) for (var workload : List.of(SolverBenchmarkData.Workload.CLUSTERED,
                SolverBenchmarkData.Workload.DISPERSED, SolverBenchmarkData.Workload.MIXED_SKILL, SolverBenchmarkData.Workload.TIGHT_WINDOW)) {
            var shape = Required.value(workload);
            var canonical = SolverBenchmarkData.create(fleet, shape, 0);
            var fingerprints = new HashSet<String>();
            fingerprints.add(SolverBenchmark.fingerprint(canonical));
            long canonicalScarce = canonical.getRoutes().stream().filter(route -> route.getQualifiedServiceIds().contains("scarce")).count();
            for (long seed = 1; seed <= 3; seed++) {
                var drawn = SolverBenchmarkData.create(fleet, shape, seed);
                assertTrue(RouteEvaluator.evaluate(drawn).feasible());
                assertEquals(canonical.getVisits().size(), drawn.getVisits().size());
                assertEquals(canonical.getRoutes().size(), drawn.getRoutes().size());
                assertEquals(SolverBenchmark.fingerprint(drawn), SolverBenchmark.fingerprint(SolverBenchmarkData.create(fleet, shape, seed)));
                assertTrue(fingerprints.add(SolverBenchmark.fingerprint(drawn)), fleet + "/" + shape + "/" + seed);
                assertEquals(canonicalScarce, drawn.getRoutes().stream().filter(route -> route.getQualifiedServiceIds().contains("scarce")).count());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> SolverBenchmarkData.create(5, SolverBenchmarkData.Workload.CLUSTERED, -1));
    }

    /** MIXED_SHIFT gives 4, 6 and 9 hour days, and every fixture the load balance study draws is feasible and its own. */
    @Test void mixedShiftsAndTheLoadBalanceStudyFixturesAreFeasible() throws Exception {
        for (int fleet : new int[] {5, 10, 20, 50}) for (var workload : List.of(SolverBenchmarkData.Workload.CLUSTERED, SolverBenchmarkData.Workload.DISPERSED,
                SolverBenchmarkData.Workload.ABSENCE, SolverBenchmarkData.Workload.TIGHT_WINDOW, SolverBenchmarkData.Workload.NEAR_CAPACITY,
                SolverBenchmarkData.Workload.MIXED_SHIFT)) {
            var shape = Required.value(workload);
            var fingerprints = new HashSet<String>();
            for (long seed = 7001; seed <= 7005; seed++) {
                var drawn = SolverBenchmarkData.create(fleet, shape, seed);
                assertTrue(RouteEvaluator.evaluate(drawn).feasible(), fleet + "/" + shape + "/" + seed);
                assertTrue(fingerprints.add(SolverBenchmark.fingerprint(drawn)), fleet + "/" + shape + "/" + seed);
                if (shape != SolverBenchmarkData.Workload.MIXED_SHIFT) continue;
                var capacities = new HashSet<Integer>();
                for (TechRoute route : drawn.getRoutes()) {
                    capacities.add(route.getMaxDailyMinutes());
                    assertEquals(route.getMaxDailyMinutes() * 60L, route.getShiftEnd().getEpochSecond() - route.getShiftStart().getEpochSecond());
                }
                assertEquals(java.util.Set.of(240, 360, 540), capacities, fleet + "/" + seed);
            }
        }
        // The other workloads keep their nine-hour days, so earlier fixtures are unchanged (archived fingerprints above).
        for (TechRoute route : SolverBenchmarkData.create(20, SolverBenchmarkData.Workload.ABSENCE, 7001).getRoutes()) assertEquals(540, route.getMaxDailyMinutes());
    }

    /** A cost-only case spends the whole budget on the cost search and records no fairness phase. */
    @Test void costOnlyRunsOneSearchOverTheWholeBudget() {
        var definition = SolverEngine.configuration(SolverEngine.Variant.TABU_KOPT, 17);
        var row = SolverBenchmark.run(definition, SolverBenchmarkData.create(5, SolverBenchmarkData.Workload.CLUSTERED, 1), 300, SolverBenchmark.Phases.COST_ONLY);
        assertFalse(row.containsKey("fairnessPhase"));
        assertEquals(300L, ((SolverEngine.Statistics) Required.value(row.get("referencePhase"))).budgetMs());
        var legacy = SolverBenchmark.run(definition, SolverBenchmarkData.create(5, SolverBenchmarkData.Workload.CLUSTERED, 1), 300);
        assertEquals(200L, ((SolverEngine.Statistics) Required.value(legacy.get("referencePhase"))).budgetMs());
    }

    /** Every row reads all five measures, the load balance among them, and is accepted on the production variance. */
    @Test void everyRowReadsEveryMeasureAndIsAcceptedOnTheVariance() {
        var fixture = SolverBenchmarkData.create(10, SolverBenchmarkData.Workload.CLUSTERED, 1);
        var row = SolverBenchmark.run(SolverEngine.configuration(SolverEngine.Variant.TABU_KOPT, 17), fixture, 600);
        assertFalse(row.containsKey("acceptedOn"), "no arm is accepted on a measure of its own");
        for (String stage : List.of("before", "reference", "after"))
            assertEquals(List.of("absoluteDeviation", "loadBalance", "maximumUtilization", "utilizationSpread", "variance"),
                    stage(row, Required.value(stage)).keySet().stream().map(String::valueOf).sorted().toList());
        var reference = (SchedulingPolicy.Metrics) Required.value(row.get("reference"));
        assertEquals(0, reference.fairness().variance().compareTo(reading(row, "reference", "variance")));
        var after = (SchedulingPolicy.Metrics) Required.value(row.get("after"));
        assertEquals(0, after.fairness().variance().compareTo(reading(row, "after", "variance")));
    }
    private static Map<?, ?> stage(Map<String, Object> row, String stage) {
        return (Map<?, ?>) Required.value(((Map<?, ?>) Required.value(row.get("measures"))).get(stage));
    }
    private static java.math.BigDecimal reading(Map<String, Object> row, String stage, String measure) {
        return (java.math.BigDecimal) Required.value(stage(row, stage).get(measure));
    }
}
