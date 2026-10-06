package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SavedJson;
import ai.timefold.solver.core.api.solver.SolutionManager;
import java.time.Instant;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DailyConstructionTest {
    private static Instant at(int hour) { return Required.value(Instant.parse("2030-01-01T%02d:00:00Z".formatted(hour))); }
    static DayPlan fixture(int technicians, int demand, int assigned, int pinned, DayPlan.Mode mode) {
        List<TechRoute> routes = new ArrayList<>(); List<PlanVisit> visits = new ArrayList<>();
        for (int t = 0; t < technicians; t++) routes.add(new TechRoute("t" + t, at(8), at(16), 480, 0, Required.value(Set.of("s"))));
        for (int v = 0; v < demand; v++) visits.add(new PlanVisit("v" + v, "s", at(8), at(16), 30, null, null));
        if (assigned > 0) routes.getFirst().getVisits().addAll(visits.subList(0, assigned));
        if (!routes.isEmpty()) routes.getFirst().setPinnedPrefix(pinned);
        Map<String, DayPlan.RoadLeg> roads = new HashMap<>();
        for (TechRoute route : routes) for (PlanVisit visit : visits) {
            roads.put(route.getId() + ">" + visit.getId(), new DayPlan.RoadLeg(0, 0));
            roads.put(visit.getId() + ">" + route.getId() + ":return", new DayPlan.RoadLeg(0, 0));
        }
        for (PlanVisit from : visits) for (PlanVisit to : visits) if (from != to) roads.put(from.getId() + ">" + to.getId(), new DayPlan.RoadLeg(0, 0));
        DayPlan plan = new DayPlan(routes, visits, roads, 30, 45, 0, 0, 0); plan.setMode(mode); return plan;
    }
    private static SolverExperiment.Result solve(DayPlan plan) {
        return SolverExperiment.solve(SolverExperiment.configuration(SolverExperiment.Variant.TABU, 17, true), plan, Required.value(Duration.ofMillis(250)));
    }
    private static void coverage(SolverExperiment.Result result, int demand) {
        Set<String> identities = new HashSet<>(result.outcome().assignedVisitIds());
        for (String id : result.outcome().unassignedVisitIds()) assertTrue(identities.add(id));
        assertEquals(demand, identities.size()); assertEquals(demand, result.plan().getVisits().size());
    }
    @Test void emptyDemandAndZeroTechniciansHaveExplicitIndependentOutcomes() {
        var empty = solve(fixture(0, 0, 0, 0, DayPlan.Mode.COLD));
        assertTrue(empty.outcome().complete()); assertTrue(empty.outcome().policyEligible());
        var impossible = solve(fixture(0, 2, 0, 0, DayPlan.Mode.COLD)); coverage(impossible, 2);
        assertEquals(List.of("v0", "v1"), impossible.outcome().unassignedVisitIds());
        assertTrue(impossible.outcome().assignedWorkFeasible()); assertFalse(impossible.outcome().policyEligible());
        assertFalse(RouteEvaluator.evaluate(impossible.plan()).feasible());
        assertEquals(-2, Required.value(impossible.plan().getScore()).hardScore(DayScores.UNASSIGNED).intValueExact());
    }
    @Test void coldAndPartialConstructionPreserveFactsDemandAndPinnedPrefix() {
        for (int assigned : new int[]{0, 1}) {
            DayPlan initial = fixture(2, 3, assigned, assigned, assigned == 0 ? DayPlan.Mode.COLD : DayPlan.Mode.PARTIAL);
            var result = solve(initial); coverage(result, 3);
            assertTrue(result.outcome().complete()); assertTrue(result.outcome().policyEligible());
            assertTrue(result.outcome().scoringMatchesValidation()); assertTrue(RouteEvaluator.evaluate(result.plan()).feasible());
            assertSame(initial.getFacts(), result.plan().getFacts()); assertEquals(3 - assigned, initial.getUnassignedVisitIds().size());
            if (assigned > 0) assertEquals("v0", result.plan().getRoutes().getFirst().getVisits().getFirst().getId());
            assertNull(initial.getVisits().getFirst().getOriginalTechnicianId()); assertNull(initial.getVisits().getFirst().getOriginalPlannedStart());
        }
    }
    @Test void impossibleWindowsReturnUnassignedWorkAndImpossiblePinnedWorkRemainsExplicitlyInvalid() {
        for (int pinned : new int[]{0, 1}) {
            TechRoute route = new TechRoute("t", at(8), at(16), 480, 0, Required.value(Set.of("s")));
            PlanVisit visit = new PlanVisit("v", "s", at(6), at(7), 30, null, null);
            if (pinned == 1) route.getVisits().add(visit);
            route.setPinnedPrefix(pinned);
            DayPlan plan = new DayPlan(Required.value(List.of(route)), Required.value(List.of(visit)),
                    Required.value(Map.of("t>v", new DayPlan.RoadLeg(0, 0), "v>t:return", new DayPlan.RoadLeg(0, 0))), 30, 45, 0, 0, 0);
            plan.setMode(pinned == 0 ? DayPlan.Mode.COLD : DayPlan.Mode.REPAIR);
            var result = solve(plan); coverage(result, 1); assertFalse(result.outcome().policyEligible());
            if (pinned == 0) { assertTrue(result.outcome().assignedWorkFeasible()); assertEquals(List.of("v"), result.outcome().unassignedVisitIds()); }
            else { assertFalse(result.outcome().assignedWorkFeasible()); assertTrue(result.outcome().complete()); assertEquals("v", result.plan().getRoutes().getFirst().getVisits().getFirst().getId()); }
            assertFalse(RouteEvaluator.evaluate(result.plan()).feasible());
        }
    }
    @Test void absenceRepairReassignsOrReturnsUsefulPartialWorkWithoutBreakingPromises() {
        for (int technicians : new int[]{1, 2}) {
            DayPlan initial = fixture(technicians, 1, 1, 0, DayPlan.Mode.ASSIGNED);
            DayPlan repair = PlanCopies.withAbsence(initial, "t0", new TechRoute.Unavailable(at(8), at(16)));
            var result = solve(repair); coverage(result, 1);
            assertTrue(result.outcome().assignedWorkFeasible());
            assertEquals(technicians == 2, result.outcome().complete());
            assertEquals(technicians == 2, result.outcome().policyEligible());
            assertEquals(1, initial.getRoutes().getFirst().getVisits().size());
        }
    }
    @Test void categoricalQualificationsAndUnreachableRoadsNeverTradeForCoverage() {
        for (boolean qualified : new boolean[]{false, true}) {
            DayPlan base = fixture(1, 1, 0, 0, DayPlan.Mode.COLD);
            TechRoute route = new TechRoute("t0", at(8), at(16), 480, 0, qualified ? Required.value(Set.of("s")) : Required.value(Set.of()));
            Map<String, DayPlan.RoadLeg> roads = new HashMap<>(base.getMatrix());
            Set<String> unreachable = new HashSet<>();
            if (qualified) { roads.remove("t0>v0"); unreachable.add("t0>v0"); }
            DayPlan plan = new DayPlan(Required.value(List.of(route)), base.getVisits(), roads, unreachable, 30, 45, 0, 0, 0);
            plan.setMode(DayPlan.Mode.COLD);
            var result = solve(plan); coverage(result, 1);
            assertEquals(List.of("v0"), result.outcome().unassignedVisitIds());
            assertTrue(result.outcome().assignedWorkFeasible()); assertFalse(result.outcome().policyEligible());
            assertEquals(0, Required.value(result.plan().getScore()).hardScore(DayScores.CATEGORICAL).signum());
            assertEquals(-1, Required.value(result.plan().getScore()).hardScore(DayScores.UNASSIGNED).intValueExact());
        }
    }
    @Test void quantitativeCapacityAndAvailabilityViolationsHaveAnImprovementDirection() {
        for (boolean capacity : new boolean[]{false, true}) {
            List<Long> penalties = new ArrayList<>();
            for (int duration : new int[]{60, 90}) {
                TechRoute route = new TechRoute("t", at(8), at(16), capacity ? 30 : 480, 0, Required.value(Set.of("s")));
                if (!capacity) route.setUnavailable(Required.value(List.of(new TechRoute.Unavailable(at(8), at(16)))));
                PlanVisit visit = new PlanVisit("v", "s", at(8), at(16), duration, null, null); route.getVisits().add(visit);
                DayPlan plan = new DayPlan(Required.value(List.of(route)), Required.value(List.of(visit)),
                        Required.value(Map.of("t>v", new DayPlan.RoadLeg(0, 0), "v>t:return", new DayPlan.RoadLeg(0, 0))), 30, 45, 0, 0, 0);
                var violations = plan.getScoringFacts().evaluate(route).metrics().violations();
                penalties.add(capacity ? violations.capacityMinutes() : violations.availabilitySeconds());
                assertFalse(RouteEvaluator.evaluate(plan).feasible());
            }
            assertTrue(penalties.getFirst() > 0); assertTrue(penalties.getLast() > penalties.getFirst());
        }
    }
    @Test void everySupportedNeighborhoodConstructsAndPreservesPinningUnderFullAssert() {
        for (var variant : SolverExperiment.Variant.values()) {
            DayPlan initial = fixture(3, 6, 2, 1, DayPlan.Mode.PARTIAL);
            var result = SolverExperiment.solve(SolverExperiment.configuration(Required.value(variant), 17, true), initial, Required.value(Duration.ofMillis(100)));
            coverage(result, 6); assertTrue(result.outcome().complete(), variant.name());
            assertTrue(result.outcome().policyEligible(), variant.name());
            assertEquals("v0", result.plan().getRoutes().getFirst().getVisits().getFirst().getId());
        }
    }
    @Test void referencesRequireCompleteValidMatchingFactsAndScoreLevelsCannotCompensateHigherViolations() {
        DayPlan partial = fixture(1, 2, 1, 0, DayPlan.Mode.PARTIAL);
        assertThrows(IllegalArgumentException.class, () -> partial.getScoringFacts().withTarget(partial, 10000));
        DayPlan reference = fixture(1, 1, 1, 0, DayPlan.Mode.ASSIGNED);
        var target = reference.getScoringFacts().withTarget(reference, 10000);
        DayPlan changed = PlanCopies.withAbsence(reference, "t0", new TechRoute.Unavailable(at(8), at(16)));
        assertThrows(IllegalArgumentException.class, () -> changed.setScoringFacts(target));
        assertThrows(IllegalArgumentException.class, () -> changed.getScoringFacts().withTarget(changed, 10000));
        assertThrows(IllegalArgumentException.class, () -> reference.getScoringFacts().withTarget(reference, 1));
        for (int higher = 0; higher < 4; higher++)
            assertTrue(DayScores.hard(higher).negate().compareTo(DayScores.hard(higher + 1).multiply(-1_000_000)) < 0);
        reference.setScoringFacts(target); SolutionManager.create(SolverExperiment.configuration(SolverExperiment.Variant.TABU, 17).factory()).update(reference);
        assertEquals(0, Required.value(reference.getScore()).hardScore(DayScores.OVERTIME_TARGET).compareTo(BigDecimal.ZERO));
    }
    @Test void nullableOriginalPairsAndPersistedCoverageAreValidatedBeforeUse() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new DayPlan(Required.value(List.of()),
                Required.value(List.of(new PlanVisit("v", "s", at(8), at(16), 30, "t", null))), Required.value(Map.of()), 30, 45, 0, 0, 0));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode node = Required.value(mapper.valueToTree(solve(fixture(0, 1, 0, 0, DayPlan.Mode.COLD)).outcome()));
        SavedJson.dailyOutcome(node);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("complete", true);
        assertThrows(RuntimeException.class, () -> SavedJson.dailyOutcome(node));
        for (String json : new String[]{"{}", "{\"scoreModelVersion\":null}", "{\"scoreModelVersion\":\"hard-medium-soft-decimal-v1\"}"})
            assertThrows(RuntimeException.class, () -> SavedJson.currentScoreModel(Required.value(mapper.readTree(json))));
    }
}
