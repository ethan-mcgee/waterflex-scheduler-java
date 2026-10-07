package dev.waterflex.benchmark;

import static org.junit.jupiter.api.Assertions.*;
import dev.waterflex.scheduler.Monetary;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.optimizer.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Correctness/counterexample evidence. These short fixtures are never performance studies. */
class RemainingAuditContractTest {
    @Test void bothAlgorithmsExerciseSolverAndPhaseTerminationWithEnclosingCaps() throws Exception {
        for (String algorithm : List.of("TABU", "LATE_ACCEPTANCE"))
            for (String scope : List.of("solver", "local-search"))
                for (String kind : List.of("fixed", "diminished-returns", "unimproved-time")) {
                    var policy = new CampaignSolverConfiguration.Termination(Required.value(kind), Required.value(scope), true, 10,
                            kind.equals("diminished-returns") ? 20L : null,
                            kind.equals("diminished-returns") ? 0.1 : null,
                            kind.equals("unimproved-time") ? 20L : null);
                    var control = BenchmarkContractTest.settings(policy);
                    var settings = new CampaignSolverConfiguration.Settings("contract", algorithm,
                            algorithm.equals("TABU") ? 7 : 100, algorithm.equals("TABU") ? 1000 : 1,
                            10000, control.moves(), "FULL_ASSERT", "NONE", 1, policy);
                    var definition = CampaignSolverConfiguration.definition(settings, 17, false, 100);
                    assertTrue(definition.configurationXml().contains("<millisecondsSpentLimit>100</millisecondsSpentLimit>"));
                    assertTrue(definition.configurationXml().contains("<stepCountLimit>10</stepCountLimit>"));
                    var dataset = BenchmarkContractTest.dataset();
                    var solved = Required.value(definition.factory().buildSolver().solve(dataset.toDayPlan()));
                    dataset.facts().validateEntities(solved, false);
                    var outcome = DailyOutcome.assess(solved);
                    assertTrue(outcome.scoringMatchesValidation(), algorithm + "/" + scope + "/" + kind);
                }
    }

    @Test void roundingRouteCentsBeforeReversibleFleetReductionChangesPolicy() {
        BigDecimal rate = new BigDecimal("0.30"), zero = Required.value(BigDecimal.ZERO);
        long individual = Monetary.cents(1, 0, 0, rate, zero, zero);
        assertEquals(2, individual + individual);
        assertEquals(1, Monetary.cents(2, 0, 0, rate, zero, zero));
    }

    @Test void oneUniformSignedLongFairnessScaleCannotCoverCurrentCapacityRange() {
        var zero = Required.value(BigDecimal.ZERO);
        long maximum = Integer.MAX_VALUE;
        BigDecimal tiny = SchedulingPolicy.fairness(Required.value(List.<SchedulingPolicy.Workload>of(
                new SchedulingPolicy.Workload("a", 1, maximum, zero),
                new SchedulingPolicy.Workload("b", 0, maximum - 1, zero)))).variance();
        BigDecimal large = SchedulingPolicy.fairness(Required.value(List.<SchedulingPolicy.Workload>of(
                new SchedulingPolicy.Workload("a", maximum, 1, zero),
                new SchedulingPolicy.Workload("b", 0, 1, zero)))).variance();
        assertTrue(tiny.signum() > 0);
        // Any integer scale capable of distinguishing this positive variance from zero
        // would overflow at the other supported workload. Narrowing domains changes the contract.
        assertTrue(large.compareTo(tiny.multiply(BigDecimal.valueOf(Long.MAX_VALUE))) > 0);
    }

    @Test void localMinuteArithmeticChangesChicagoDstElapsedTimeAndExclusiveBoundaries() {
        var zone = Required.value(ZoneId.of("America/Chicago"));
        var start = Required.value(LocalDateTime.of(2026, 3, 8, 1, 30));
        var end = Required.value(LocalDateTime.of(2026, 3, 8, 3, 30));
        assertEquals(120, Duration.between(start, end).toMinutes());
        assertEquals(60, Duration.between(start.atZone(zone).toInstant(), end.atZone(zone).toInstant()).toMinutes());
        var exclusive = end.atZone(zone).toInstant();
        assertFalse(exclusive.isBefore(exclusive));
        assertTrue(exclusive.minusNanos(1).isBefore(exclusive));
    }
}
