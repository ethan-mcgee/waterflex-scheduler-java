package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SearchAdmission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Clients choose between exactly three daily searches, each with its own phase times. */
class DailySolversTest {
    @Test void onlyTabuTabuKoptAndTabuKoptSize3AreClientChoices() {
        assertEquals(List.of(DailySearch.TABU, DailySearch.TABU_KOPT, DailySearch.TABU_KOPT_SIZE_3), List.of(DailySearch.values()));
        assertEquals(SolverEngine.Variant.TABU_KOPT, DailySearch.TABU_KOPT.variant());
        assertEquals(SolverEngine.Variant.TABU_KOPT_SIZE_3, DailySearch.TABU_KOPT_SIZE_3.variant());
        assertEquals(DailySearch.TABU_KOPT, DailySearch.of("TABU_KOPT"));
        assertEquals(DailySearch.TABU_KOPT_SIZE_3, DailySearch.of("TABU_KOPT_SIZE_3"));
        assertNull(DailySearch.of("KOPT"));
        assertNull(DailySearch.of(null));
    }

    @Test void eachSearchHasItsOwnProductionDefinition() {
        var solvers = new DailySolvers(17);
        for (DailySearch search : DailySearch.values()) {
            DailySolver solver = solvers.get(search);
            assertEquals(search.variant().name(), solver.variant());
            assertEquals(17, solver.seed());
            assertEquals(SolverEngine.configuration(search.variant(), 17).configurationXml(),
                    solver.diagnostics(Required.value(List.of())).configurationXml());
        }
    }

    @Test void aRemoteRequestRunsOnlyAClientSearchWithThisSeed() {
        var solvers = new DailySolvers(17);
        assertSame(solvers.get(DailySearch.TABU_KOPT), solvers.matching("TABU_KOPT", 17));
        assertSame(solvers.get(DailySearch.TABU), solvers.matching("TABU", 17));
        assertThrows(IllegalArgumentException.class, () -> solvers.matching("KOPT", 17));
        assertThrows(IllegalArgumentException.class, () -> solvers.matching("TABU_KOPT", 18));
        assertThrows(IllegalArgumentException.class, () -> solvers.matching(null, 17));
        Map<DailySearch, SolverEngine.Variant> partial = new EnumMap<>(DailySearch.class);
        partial.put(DailySearch.TABU, SolverEngine.Variant.CURRENT_CAPPED);
        assertThrows(IllegalArgumentException.class, () -> new DailySolvers(17, partial));
    }

    @Test void clientTimesAreBoundedAndBecomeOneAllowance() {
        assertThrows(IllegalArgumentException.class, () -> DailyCalculation.Allocation.client(4, 0));
        assertThrows(IllegalArgumentException.class, () -> DailyCalculation.Allocation.client(241, 0));
        assertThrows(IllegalArgumentException.class, () -> DailyCalculation.Allocation.client(10, -1));
        assertThrows(IllegalArgumentException.class, () -> DailyCalculation.Allocation.client(200, 41));
        assertThrows(IllegalArgumentException.class, () -> new DailyCalculation.Allocation(1000, -1, 1000, true));
        var longest = DailyCalculation.Allocation.client(200, 40);
        var allowance = DailyOperation.Allowance.forSearch(longest);
        assertEquals(Duration.ofSeconds(240), allowance.search());
        assertEquals(DailyOperation.Allowance.MAXIMUM, allowance.total());
        assertEquals(Duration.ofSeconds(20), DailyOperation.Allowance.forSearch(DailyCalculation.Allocation.DEFAULT).total());
        assertThrows(IllegalArgumentException.class, () -> new DailyOperation.Allowance(Required.value(Duration.ofSeconds(246)), Required.value(Duration.ofSeconds(240))));
        assertThrows(IllegalArgumentException.class, () -> new DailyOperation.Allowance(Required.value(Duration.ofSeconds(10)), Required.value(Duration.ofSeconds(11))));
        assertThrows(IllegalArgumentException.class, () -> new DailyOperation.Allowance(Required.value(Duration.ofSeconds(10)), Required.value(Duration.ZERO)));
    }

    @Test void anAllowanceLongerThanTwentySecondsLetsPhasesSearchItsWholeSearchTime() {
        var allowance = new DailyOperation.Allowance(Required.value(Duration.ofSeconds(65)), Required.value(Duration.ofSeconds(60)));
        Duration granted = DailyOperation.execute(new SearchAdmission(1, 16), allowance, () -> {
            var operation = Required.value(DailyOperation.current());
            Duration phase = operation.budget(Required.value(Duration.ofSeconds(90)));
            operation.finishPhase();
            return phase;
        }, _ -> { });
        assertEquals(Duration.ofSeconds(60), granted);
        assertThrows(IllegalArgumentException.class, () -> DailyOperation.execute(new SearchAdmission(1, 16), Required.value(Duration.ofSeconds(21)), () -> "too long", _ -> { }));
    }

    @Test void aZeroFairnessTimeRunsOnlyTheReferencePhase() {
        var solver = new DailySolver("TABU_KOPT", 17);
        var plan = DayConstraintProviderTest.fixture();
        var allowance = DailyOperation.Allowance.forSearch(DailyCalculation.Allocation.client(5, 0));
        var result = DailyOperation.execute(new SearchAdmission(1, 16), allowance,
                () -> DailyCalculation.run(plan, SchedulingPolicy.Rules.defaults(), solver, new DailyCalculation.Allocation(300, 0, 300, true)), _ -> { });
        List<String> phases = new ArrayList<>();
        for (DailySolver.Phase phase : result.diagnostics().phases()) phases.add(phase.name());
        assertEquals(List.of("REFERENCE"), phases);
    }
}
