package dev.waterflex.scheduler.api;

import static org.junit.jupiter.api.Assertions.*;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.optimizer.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StudyReceiptsTest {
    private static DayPlan plan() {
        Instant start = Required.value(Instant.parse("2030-01-01T14:00:00Z"));
        var route = new TechRoute("t", start, Required.value(start.plusSeconds(480 * 60L)), 480, 120, Required.value(Set.of("s")));
        var a = new PlanVisit("a", "s", start, Required.value(start.plusSeconds(28800)), 40, "t", start);
        var b = new PlanVisit("b", "s", start, Required.value(start.plusSeconds(28800)), 40, "t", Required.value(start.plusSeconds(6600)));
        route.getVisits().addAll(List.of(a, b));
        Map<String, DayPlan.RoadLeg> roads = new HashMap<>();
        for (String id : List.of("a", "b")) { roads.put("t>" + id, new DayPlan.RoadLeg(0, 0)); roads.put(id + ">t:return", new DayPlan.RoadLeg(0, 0)); }
        roads.put("a>b", new DayPlan.RoadLeg(4200, 0)); roads.put("b>a", new DayPlan.RoadLeg(0, 0));
        return new DayPlan(Required.value(List.of(route)), Required.value(List.of(a, b)), roads, 30, 45, 0, 0, 0);
    }

    private static DailyCalculation.Result calculate(DayPlan baseline, DailySolver solver) {
        return DailyOperation.execute(new SearchAdmission(1, 16), Required.value(Duration.ofSeconds(20)),
                () -> DailyCalculation.run(baseline, SchedulingPolicy.Rules.defaults(), solver,
                        new DailyCalculation.Allocation(300, 300, 600, true)), _ -> { });
    }

    /** A receipt carries every phase that ran and the policy's own DECIMAL128 readings, and writes as JSON. */
    @Test void aReceiptReportsThePhasesAndTheMeasuredPlans() {
        var solver = new DailySolver("TABU_KOPT", 17);
        DayPlan baseline = plan();
        var calculation = calculate(baseline, solver);
        var policy = SchedulingPolicy.Rules.defaults();
        var receipt = StudyReceipts.receipt("p1", Required.value(LocalDate.of(2030, 1, 1)), solver, policy, baseline, calculation);
        assertEquals("TABU_KOPT", receipt.get("variant"));
        assertEquals(17L, receipt.get("seed"));
        assertEquals(1, receipt.get("technicians"));
        assertEquals(2, receipt.get("visits"));
        assertEquals(calculation.diagnostics().phases().size(), ((List<?>) Required.value(receipt.get("phases"))).size());
        assertEquals(calculation.accepted(), receipt.get("accepted"));
        var reference = SchedulingPolicy.measure(calculation.reference());
        var read = (Map<?, ?>) Required.value(receipt.get("reference"));
        assertEquals(reference.costCents(), read.get("costCents"));
        assertEquals(reference.fairness().variance().toPlainString(), read.get("variance"));
        assertEquals(policy.costCeiling(reference.costCents()), receipt.get("costCeilingCents"));
        assertFalse(((Map<?, ?>) Required.value(receipt.get("proposed"))).containsKey("unavailable"));
        assertTrue(CalculationJson.write(receipt).contains("\"proposalId\":\"p1\""));
    }

    /** A plan the policy cannot measure is reported with its reason, never as zero readings. */
    @Test void anUnmeasurablePlanIsNamedNotZeroFilled() {
        DayPlan incomplete = plan();
        incomplete.getRoutes().getFirst().getVisits().removeLast();
        assertNotNull(StudyReceipts.unmeasurable(incomplete));
        assertNull(StudyReceipts.unmeasurable(plan()));
    }

    /** Off is the default, and an off receipt does not measure anything. */
    @Test void receiptsAreOffUnlessTurnedOn() {
        assertFalse(StudyReceipts.OFF.enabled());
        assertTrue(new StudyReceipts(true).enabled());
    }
}
