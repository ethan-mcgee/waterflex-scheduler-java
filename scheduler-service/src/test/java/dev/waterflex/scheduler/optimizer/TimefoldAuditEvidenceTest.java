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

/** Boundary regressions plus retained money and score-plateau characterizations for later phases. */
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
    @Test void duplicateTechnicianIdentityRejectedBeforeValidation() {
        TechRoute first = route(), second = route();
        PlanVisit one = visit("one"), two = visit("two");
        first.getVisits().add(one); second.getVisits().add(two);
        assertThrows(IllegalArgumentException.class, () -> plan(Required.value(List.of(first, second)), Required.value(List.of(one, two)), 30));
    }
    @Test void duplicateVisitFactsRejectedBeforeCopying() {
        TechRoute route = route(); PlanVisit one = visit("same"), two = visit("same"); route.getVisits().add(one);
        assertThrows(IllegalArgumentException.class, () -> plan(Required.value(List.of(route)), Required.value(List.of(one, two)), 30));
        assertEquals(2, List.of(one, two).size());
    }
    @Test void nonFiniteRateRejectedBeforeScoring() {
        TechRoute route = route(); PlanVisit visit = visit("one"); route.getVisits().add(visit);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1})
            assertThrows(IllegalArgumentException.class, () -> plan(Required.value(List.of(route)), Required.value(List.of(visit)), invalid));
    }
    @Test void unassignedDailyDemandRejectedBeforeSolve() {
        DayPlan unassigned = plan(Required.value(List.of(route())), Required.value(List.of(visit("one"))), 30);
        assertThrows(IllegalArgumentException.class, () -> new DailySolver("TABU", 17).solve(unassigned, Required.value(Duration.ofSeconds(2))));
        assertEquals(1, unassigned.getVisits().size());
    }
    @Test void matrixAndScoringShareAnImmutableRevision() {
        TechRoute route = route(); PlanVisit visit = visit("one"); route.getVisits().add(visit);
        DayPlan plan = plan(Required.value(List.of(route)), Required.value(List.of(visit)), 30);
        assertThrows(UnsupportedOperationException.class, () -> plan.getMatrix().clear());
        DayPlan copy = PlanCopies.copy(plan);
        assertSame(plan.getFacts(), copy.getFacts());
        assertSame(plan.getMatrix(), copy.getMatrix());
        assertTrue(RouteEvaluator.evaluate(plan).feasible());
        assertEquals(0, plan.getScoringFacts().evaluate(route).metrics().hardPenalty());
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
    @Test void exactDecimalHalfCentRoundsUpInBothEvaluators() {
        TechRoute route = route();
        PlanVisit visit = new PlanVisit("one", "service", at(10), at(14), 255, "tech", at(10));
        route.getVisits().add(visit);
        DayPlan plan = plan(Required.value(List.of(route)), Required.value(List.of(visit)), 20.02);
        var result = RouteEvaluator.evaluate(plan);
        assertTrue(result.feasible());
        assertEquals(8509, result.costCents());
        assertEquals(result.costCents(), DayScoreCalculator.evaluate(plan).costCents());
        long decimal = new java.math.BigDecimal("20.02").multiply(java.math.BigDecimal.valueOf(25500))
                .divide(java.math.BigDecimal.valueOf(60), 0, java.math.RoundingMode.HALF_UP).longValueExact();
        assertEquals(8509, decimal);
    }
}
