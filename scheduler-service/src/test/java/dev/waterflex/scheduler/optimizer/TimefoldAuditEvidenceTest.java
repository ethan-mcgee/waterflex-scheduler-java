package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Audit characterizations of existing boundary gaps, not desired behavior contracts.
 * Replace the affected assertions with rejection/initialization tests when fixing the findings.
 */
class TimefoldAuditEvidenceTest {
    private static Instant at(int hour) {
        return Required.value(Instant.parse("2026-10-05T" + hour + ":00:00Z"));
    }
    private static TechRoute route() {
        return new TechRoute("tech", at(10), at(16), 360, 0, Required.value(Set.of("service")));
    }
    private static PlanVisit visit(String id) {
        return new PlanVisit(id, "service", at(10), at(14), 60, "tech", at(10));
    }
    private static DayPlan plan(List<TechRoute> routes, List<PlanVisit> visits, double rate) {
        Map<String, DayPlan.RoadLeg> matrix = new java.util.HashMap<>();
        for (PlanVisit visit : visits) {
            matrix.put("tech>" + visit.getId(), new DayPlan.RoadLeg(0, 0));
            matrix.put(visit.getId() + ">tech:return", new DayPlan.RoadLeg(0, 0));
        }
        return new DayPlan(routes, visits, matrix, rate, 45, 0, 0, 0);
    }
    @Test void duplicateTechnicianIdentityPassesIndependentValidation() {
        TechRoute first = route(), second = route();
        PlanVisit one = visit("one"), two = visit("two");
        first.getVisits().add(one); second.getVisits().add(two);
        var result = RouteEvaluator.evaluate(plan(Required.value(List.of(first, second)), Required.value(List.of(one, two)), 30));
        assertTrue(result.feasible());
        assertEquals(result.arrivals().get("one"), result.arrivals().get("two"));
        assertEquals(1, result.segments().size(), "Duplicate route keys overwrite the segment output");
    }
    @Test void copySilentlyCollapsesDuplicateVisitFacts() {
        TechRoute route = route();
        PlanVisit one = visit("same"), two = visit("same");
        route.getVisits().add(one);
        DayPlan original = plan(Required.value(List.of(route)), Required.value(List.of(one, two)), 30);
        assertFalse(RouteEvaluator.evaluate(original).feasible());
        DayPlan copied = PlanCopies.copy(original);
        assertEquals(1, copied.getVisits().size());
        assertTrue(RouteEvaluator.evaluate(copied).feasible());
    }
    @Test void nonFiniteRateBecomesFeasibleZeroCostAtCoreBoundary() {
        TechRoute route = route(); PlanVisit visit = visit("one"); route.getVisits().add(visit);
        DayPlan malformed = plan(Required.value(List.of(route)), Required.value(List.of(visit)), Double.NaN);
        var result = RouteEvaluator.evaluate(malformed);
        assertTrue(result.feasible()); assertEquals(0, result.costCents());
        assertEquals(0, DayScoreCalculator.evaluate(malformed).costCents());
    }
    @Test void unassignedDatasetReturnsZeroScoreWithoutServingVisit() {
        DayPlan unassigned = plan(Required.value(List.of(route())), Required.value(List.of(visit("one"))), 30);
        var result = new DailySolver("TABU", 17).solve(unassigned, Required.value(Duration.ofSeconds(2)));
        assertFalse(RouteEvaluator.evaluate(result.plan()).feasible());
        assertTrue(result.plan().getRoutes().getFirst().getVisits().isEmpty());
        assertEquals("0hard/0medium/0soft", Required.value(result.plan().getScore()).toString());
        assertEquals("PHASE_COMPLETED", result.statistics().termination());
    }
    @Test void routeFactsCanDisagreeAfterReplacingMatrix() {
        TechRoute route = route(); PlanVisit visit = visit("one"); route.getVisits().add(visit);
        DayPlan plan = plan(Required.value(List.of(route)), Required.value(List.of(visit)), 30);
        plan.setMatrix(Required.value(Map.of()));
        assertFalse(RouteEvaluator.evaluate(plan).feasible());
        assertEquals(0, plan.getScoringFacts().evaluate(route).metrics().hardPenalty());
        assertTrue(PlanCopies.copy(plan).getMatrix().isEmpty(), "Solve copy reconstructs scoring facts, limiting current exposure");
    }
    @Test void hardPenaltyPlateauHidesSizeOfWindowViolation() {
        var penalties = new ArrayList<Long>();
        for (int seconds : new int[]{4 * 3600, 5 * 3600}) {
            TechRoute route = route(); PlanVisit visit = visit("one"); route.getVisits().add(visit);
            DayPlan plan = new DayPlan(Required.value(List.of(route)), Required.value(List.of(visit)),
                    Required.value(Map.of("tech>one", new DayPlan.RoadLeg(seconds, 0), "one>tech:return", new DayPlan.RoadLeg(0, 0))),
                    30, 45, 0, 0, 0);
            penalties.add(DayScoreCalculator.evaluate(plan).hardPenalty());
        }
        assertEquals(1_000_000L, penalties.getFirst());
        assertEquals(penalties.getFirst(), penalties.getLast());
    }
    @Test void binaryRateRoundsHalfCentDownInBothEvaluators() {
        TechRoute route = route();
        PlanVisit visit = new PlanVisit("one", "service", at(10), at(14), 255, "tech", at(10));
        route.getVisits().add(visit);
        DayPlan plan = plan(Required.value(List.of(route)), Required.value(List.of(visit)), 20.02);
        var result = RouteEvaluator.evaluate(plan);
        assertTrue(result.feasible());
        assertEquals(8508, result.costCents());
        assertEquals(result.costCents(), DayScoreCalculator.evaluate(plan).costCents());
        long decimal = new java.math.BigDecimal("20.02").multiply(java.math.BigDecimal.valueOf(25500))
                .divide(java.math.BigDecimal.valueOf(60), 0, java.math.RoundingMode.HALF_UP).longValueExact();
        assertEquals(8509, decimal);
    }
}
