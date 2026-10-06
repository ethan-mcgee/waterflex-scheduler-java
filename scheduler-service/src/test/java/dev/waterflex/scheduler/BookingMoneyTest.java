package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.*;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.config.solver.SolverConfig;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BookingMoneyTest {
    private static final Instant START = Required.value(Instant.parse("2030-01-07T08:00:00Z"));
    private static final Instant END = Required.value(START.plusSeconds(8 * 3600));
    private static final Rates RATES = new Rates(.3, .45, 0, 0, 0);
    @Test void insertionDeltaIncludesFractionalCostOfUnchangedRoutesAndMatchesFullValidation() {
        var point = new RoadClient.Point(41.25, -95.93);
        var technicians = new TreeMap<String, Technician>();
        for (String id : new String[]{"a", "b"}) technicians.put(id, new Technician(Required.value(id), START, END, 480, 0,
                Required.value(Set.of("service")), Required.value(List.of()), point, point, 0));
        Visit existing = new Visit("old", "old-job", "service", START, END, 1, point, "a", START, false);
        Visit demand = new Visit("new", "new-job", "service", START, END, 1, point, "b", START, false);
        var legs = new HashMap<String, DayPlan.RoadLeg>();
        for (String from : List.of("a", "b", "old", "new")) for (String to : List.of("old", "new", "a:return", "b:return"))
            legs.put(from + ">" + to, new DayPlan.RoadLeg(0, 0));
        Arrangement before = new Arrangement(Required.value(Map.of("a", List.of("old"), "b", List.of())));
        Day day = new Day(technicians, Required.value(Map.of("old", existing)), before, 0, new Roads(legs, Required.value(Set.of())));
        Arrangement after = before.insert("b", "new", 0);
        var facts = new InsertionFacts(day, demand);
        var evaluator = new BookingEvaluation(day, RATES, "service", () -> { });
        var baseline = evaluator.metrics(before, day.visits(), false);
        var proposed = evaluator.metrics(after, facts, false);
        assertTrue(baseline.feasible()); assertTrue(proposed.feasible());
        assertEquals(1, baseline.costCents()); assertEquals(1, proposed.costCents());
        assertEquals(0, Math.subtractExact(proposed.costCents(), baseline.costCents()));
        assertEquals(proposed.costCents(), evaluator.evaluate(after, facts, false).costCents());
        var full = day.plan(after, facts, RATES, false);
        assertEquals(proposed.costCents(), RouteEvaluator.evaluate(full).costCents());
        assertEquals(proposed.costCents(), DayScoreCalculator.evaluate(full).costCents());
        var factory = SolverFactory.<DayPlan>create(new SolverConfig().withSolutionClass(DayPlan.class)
                .withEntityClasses(TechRoute.class, PlanVisit.class).withConstraintProviderClass(DayConstraintProvider.class));
        SolutionManager.create(factory).update(full);
        assertEquals(-1, Required.value(full.getScore()).softScore().longValueExact());
        assertEquals(2, evaluator.route("a", Required.value(after.routes().get("a")), facts, false).costCents()
                + evaluator.route("b", Required.value(after.routes().get("b")), facts, false).costCents());
        Arrangement repartitioned = new Arrangement(Required.value(Map.of("a", List.of("old", "new"), "b", List.of())));
        assertEquals(proposed.costCents(), evaluator.metrics(repartitioned, facts, false).costCents());
        assertEquals(0, evaluator.fairness(after, facts).compareTo(java.math.BigDecimal.ZERO));
    }
}
