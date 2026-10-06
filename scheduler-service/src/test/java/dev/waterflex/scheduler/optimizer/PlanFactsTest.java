package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanFactsTest {
    @Test void copiedAssignmentsAndShadowsAreIsolatedWhileFactsAreShared() {
        DayPlan source = DayConstraintProviderTest.fixture(), copy = PlanCopies.copy(source);
        var before = RouteEvaluator.evaluate(source); assertSame(source.getFacts(), copy.getFacts());
        assertNotSame(source.getVisits().getFirst(), copy.getVisits().getFirst());
        assertNotSame(source.getRoutes().getFirst(), copy.getVisits().getFirst().getTechnician());
        PlanVisit moved = copy.getRoutes().getFirst().getVisits().removeFirst(); copy.getRoutes().get(1).getVisits().add(moved);
        moved.setTechnician(copy.getRoutes().get(1)); assertEquals(before, RouteEvaluator.evaluate(source));
        assertThrows(UnsupportedOperationException.class, () -> copy.getMatrix().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.getRoutes().getFirst().getUnavailable().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.getRoutes().getFirst().getQualifiedServiceIds().clear());
        assertThrows(IllegalArgumentException.class, () -> copy.getRoutes().getFirst().setUnavailable(Required.value(List.of())));
        assertThrows(IllegalArgumentException.class, () -> copy.setScoringFacts(DayConstraintProviderTest.fixture().getScoringFacts()));
    }
    @Test void callerFactChangeCreatesNewRevisionAndClearsIncompatibleTarget() {
        DayPlan source = DayConstraintProviderTest.fixture(); source.setScoringFacts(source.getScoringFacts().withTarget(source, 100000));
        var before = RouteEvaluator.evaluate(source);
        DayPlan changed = PlanCopies.withAbsence(source, "t0", new TechRoute.Unavailable(Required.value(Instant.parse("2026-09-23T08:00:00Z")), Required.value(Instant.parse("2026-09-23T09:00:00Z"))));
        assertNotSame(source.getFacts(), changed.getFacts()); assertNull(changed.getScoringFacts().target());
        assertEquals(before, RouteEvaluator.evaluate(source)); assertEquals(1, changed.getRoutes().getFirst().getUnavailable().size());
    }
    @Test void duplicateAssignmentAndDifferentObjectWithSameIdentityCannotBeCopied() {
        DayPlan source = DayConstraintProviderTest.fixture();
        source.getRoutes().get(1).getVisits().add(source.getVisits().getFirst());
        assertThrows(IllegalArgumentException.class, () -> PlanCopies.copy(source));
        assertThrows(IllegalArgumentException.class, () -> RouteEvaluator.evaluate(source));
        source.getRoutes().get(1).getVisits().clear();
        var original = source.getVisits().getFirst();
        source.getRoutes().getFirst().getVisits().set(0, new PlanVisit(original.getId(), original.getServiceId(), original.getWindowStart(), original.getWindowEnd(), original.getDurationMinutes(), original.getOriginalTechnicianId(), original.getOriginalPlannedStart()));
        assertThrows(IllegalArgumentException.class, () -> PlanCopies.copy(source));
    }
    @Test void inputCollectionsCannotMutateCapturedFacts() {
        DayPlan base = DayConstraintProviderTest.fixture(); Map<String, DayPlan.RoadLeg> roads = new HashMap<>(base.getMatrix());
        List<TechRoute> routes = new ArrayList<>(base.getRoutes()); List<PlanVisit> demand = new ArrayList<>(base.getVisits());
        DayPlan plan = new DayPlan(routes, demand, roads, 30, 45, .67, .2, 5);
        var before = RouteEvaluator.evaluate(plan); roads.clear(); routes.clear(); demand.clear();
        assertEquals(before, RouteEvaluator.evaluate(plan)); assertEquals(6, plan.getVisits().size());
        assertThrows(IllegalArgumentException.class, () -> new DayPlan.RoadLeg(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DayPlan.RoadLeg(0, Long.MAX_VALUE));
    }
    @Test void dailySolveRequiresEveryNeighborhoodRoadButKeepsExplicitUnreachable() {
        DayPlan base = DayConstraintProviderTest.fixture(); Map<String, DayPlan.RoadLeg> roads = new HashMap<>(base.getMatrix());
        roads.remove("t2>v0");
        DayPlan missing = new DayPlan(base.getRoutes(), base.getVisits(), roads, 30, 45, .67, .2, 5);
        assertThrows(IllegalArgumentException.class, () -> new DailySolver("TABU", 17).solve(missing, Required.value(java.time.Duration.ofMillis(100))));
        DayPlan explicit = new DayPlan(base.getRoutes(), base.getVisits(), roads, Required.value(Set.of("t2>v0")), 30, 45, .67, .2, 5);
        explicit.getFacts().requireSearchRoads();
        assertThrows(IllegalArgumentException.class, () -> new DayPlan(base.getRoutes(), base.getVisits(), base.getMatrix(), Required.value(Set.of("t2>v0")), 30, 45, .67, .2, 5));
    }
}
