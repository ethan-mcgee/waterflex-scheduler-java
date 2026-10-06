package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.api.solver.SolutionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.waterflex.scheduler.Required;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit correctness campaign, excluded from ordinary tests and all timing comparisons. */
@EnabledIfSystemProperty(named="assertions.enabled", matches="true")
class AssertionCampaignTest {
    @Test void exerciseMovesUndoConstructionAndRepairWithRetainedCaseReceipts() throws Exception {
        var mode = EnvironmentMode.valueOf(Required.value(System.getProperty("assertions.mode")));
        assertTrue(Set.of(EnvironmentMode.FULL_ASSERT, EnvironmentMode.NON_INTRUSIVE_FULL_ASSERT, EnvironmentMode.PHASE_ASSERT).contains(mode));
        long budget = Long.parseLong(System.getProperty("assertions.budgetMs", "2000"));
        assertTrue(budget >= 100 && budget <= 10000);
        String revision = Required.value(System.getProperty("assertions.revision"), "campaign revision");
        Path output = Required.value(Path.of(Required.value(System.getProperty("assertions.output"), "new campaign output path")));
        Path parent = output.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent);
        var mapper = new ObjectMapper();
        try (var lock = new java.net.ServerSocket();
                var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            lock.setReuseAddress(false); lock.bind(new java.net.InetSocketAddress("127.0.0.1",47983));
            writer.write(mapper.writeValueAsString(Map.of("type","provenance","revision",revision,"mode",Required.value(mode.name()),"budgetMs",budget,
                    "seeds",Required.value(List.of(17,29,41)),"cohorts",Required.value(List.of("ASSIGNED","PARTIAL_PINNED","REPAIR","FAIRNESS")),
                    "artifact",EngineProvenance.loaded(),"instrumentation","TIMEFOLD_INTERNAL_2_6_0","performanceEvidence",false)));
            writer.newLine(); writer.flush();
            for (long seed : new long[]{17,29,41}) for (var variant : SolverEngine.Variant.values())
                for (String cohort : List.of("ASSIGNED","PARTIAL_PINNED","REPAIR","FAIRNESS")) {
                    DayPlan initial = cohort.equals("PARTIAL_PINNED") ? DailyConstructionTest.fixture(3,6,2,1,DayPlan.Mode.PARTIAL) : DayConstraintProviderTest.fixture();
                    if (cohort.equals("REPAIR")) initial = PlanCopies.withAbsence(initial, "t0",
                            new TechRoute.Unavailable(initial.getRoutes().getFirst().getShiftStart(), initial.getRoutes().getFirst().getShiftEnd()));
                    if (cohort.equals("FAIRNESS")) initial.setScoringFacts(initial.getScoringFacts().withTarget(initial,
                            SchedulingPolicy.Rules.defaults().costCeiling(RouteEvaluator.evaluate(initial).costCents())));
                    var definition = SolverEngine.configuration(Required.value(variant),seed,mode,!initial.getUnassignedVisitIds().isEmpty());
                    Map<String,Object> receipt = new LinkedHashMap<>();
                    receipt.put("seed",seed); receipt.put("variant",variant.name()); receipt.put("cohort",cohort);
                    receipt.put("fingerprint",definition.fingerprint()); receipt.put("xml",definition.configurationXml());
                    receipt.put("type","dispatched"); writer.write(mapper.writeValueAsString(receipt)); writer.newLine(); writer.flush();
                    try {
                        if (variant == SolverEngine.Variant.RUIN_RECREATE && cohort.equals("PARTIAL_PINNED")) {
                            DayPlan rejected = initial;
                            var failure = assertThrows(IllegalArgumentException.class, () -> SolverExperiment.solve(definition,rejected,Required.value(Duration.ofMillis(budget))));
                            assertTrue(Required.value(failure.getMessage()).contains("observed list corruption"));
                            receipt.put("type","compatibility-rejected"); receipt.put("reason",failure.getMessage());
                            writer.write(mapper.writeValueAsString(receipt)); writer.newLine(); writer.flush(); continue;
                        }
                        var result=SolverExperiment.solve(definition,initial,Required.value(Duration.ofMillis(budget)));
                        receipt.put("statistics",result.statistics()); receipt.put("outcome",result.outcome());
                        receipt.put("score",Required.value(result.plan().getScore()).toString());
                        var copy=PlanCopies.copy(result.plan()); SolutionManager.create(definition.factory()).update(copy);
                        assertEquals(result.plan().getScore(),copy.getScore());
                        var independent = RouteEvaluator.evaluateAssigned(result.plan());
                        var score = Required.value(result.plan().getScore());
                        boolean servedFeasible = score.hardScore(DayScores.CATEGORICAL).signum() == 0
                                && score.hardScore(DayScores.QUANTITATIVE).signum() == 0;
                        assertEquals(independent.feasible(), servedFeasible, "Scored served-work feasibility must match independent validation");
                        if (servedFeasible) assertTrue(result.outcome().scoringMatchesValidation());
                        else assertFalse(result.outcome().policyEligible(), "Unfinished repair cannot authorize application");
                        assertEquals(initial.getVisits().size(),result.outcome().assignedVisitIds().size()+result.outcome().unassignedVisitIds().size());
                        if (!cohort.equals("REPAIR")) assertTrue(result.outcome().complete());
                        if (cohort.equals("PARTIAL_PINNED")) assertEquals("v0",result.plan().getRoutes().getFirst().getVisits().getFirst().getId());
                        receipt.put("type","passed");
                    } catch (Exception | AssertionError failure) {
                        receipt.put("type","failed"); receipt.put("failure",failure.toString());
                        if (failure instanceof SolverEngine.DiagnosticsFailure diagnostics) receipt.put("statistics",diagnostics.result().statistics());
                        writer.write(mapper.writeValueAsString(receipt)); writer.newLine(); writer.flush(); throw failure;
                    }
                    writer.write(mapper.writeValueAsString(receipt)); writer.newLine(); writer.flush();
                }
        }
    }
}
