package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SolverEngineTest {
    @Test void knownPinnedRuinRecreateFailureIsRejectedBeforeEveryExecutionMode() {
        for (var mode : List.of(ai.timefold.solver.core.config.solver.EnvironmentMode.NO_ASSERT,
                ai.timefold.solver.core.config.solver.EnvironmentMode.FULL_ASSERT,
                ai.timefold.solver.core.config.solver.EnvironmentMode.NON_INTRUSIVE_FULL_ASSERT,
                ai.timefold.solver.core.config.solver.EnvironmentMode.PHASE_ASSERT)) {
            var definition = SolverEngine.configuration(SolverEngine.Variant.RUIN_RECREATE,29,Required.value(mode),false);
            var initial = DailyConstructionTest.fixture(3,6,6,1,DayPlan.Mode.ASSIGNED);
            var before = RouteEvaluator.evaluate(initial);
            assertThrows(IllegalArgumentException.class, () -> SolverEngine.solve(definition,initial,Required.value(Duration.ofMillis(100))));
            assertEquals(before,RouteEvaluator.evaluate(initial));
        }
    }
    @Test void measuredAndPublicFixedWorkPathsAgreeAndUnknownArtifactsRetainFailureReceipts() throws Exception {
        var definition = SolverEngine.configuration(SolverEngine.Variant.CURRENT_CAPPED, 17);
        var fixture = DayConstraintProviderTest.fixture();
        var publicResult = SolverEngine.solve(definition, fixture, Required.value(Duration.ofSeconds(10)));
        var measured = SolverExperiment.solve(definition, fixture, Required.value(Duration.ofSeconds(10)));
        assertEquals(publicResult.plan().getScore(), measured.plan().getScore());
        assertEquals(RouteEvaluator.evaluate(publicResult.plan()), RouteEvaluator.evaluate(measured.plan()));
        assertEquals("INFERRED", measured.statistics().terminationBasis());
        var failure = assertThrows(SolverEngine.DiagnosticsFailure.class, () -> SolverEngine.solve(definition, fixture,
                Required.value(Duration.ofMillis(100)), SolverExperiment.telemetry(new EngineProvenance("timefold-solver-core", "unsupported", "a".repeat(64), null)), true));
        var text = new java.io.StringWriter();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        SolverBenchmark.writeDiagnosticFailure(new java.io.BufferedWriter(text), mapper, failure, "measurement", definition, "b".repeat(64));
        var node = Required.value(mapper.readTree(text.toString()));
        assertEquals("diagnostics-failure", node.path("type").textValue()); assertFalse(node.path("valid").booleanValue());
        assertTrue(node.path("statistics").path("steps").isNull());
        assertTrue(Required.value(node.path("statistics").path("diagnosticsUnavailableReason").textValue()).startsWith("ATTACH_FAILED:"));
    }
    @Test void cachedFactoriesCreateIndependentConcurrentSolversAndClones() throws Exception {
        var definition = SolverEngine.configuration(SolverEngine.Variant.CURRENT_CAPPED, 17);
        assertSame(definition, SolverEngine.configuration(SolverEngine.Variant.CURRENT_CAPPED, 17));
        assertNotSame(definition.factory().buildSolver(), definition.factory().buildSolver());
        assertNotSame(definition.factory(), SolverEngine.configuration(SolverEngine.Variant.CURRENT_CAPPED, 18).factory());
        assertNotSame(definition.factory(), SolverEngine.configuration(SolverEngine.Variant.CURRENT_CAPPED, 17, false, true).factory());
        var initial = DayConstraintProviderTest.fixture(); var before = RouteEvaluator.evaluate(initial);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> SolverEngine.solve(definition, initial, Required.value(Duration.ofMillis(150))));
            var second = executor.submit(() -> SolverEngine.solve(definition, initial, Required.value(Duration.ofMillis(150))));
            var a = Required.value(first.get()).plan(); var b = Required.value(second.get()).plan();
            assertSame(initial.getFacts(), a.getFacts()); assertSame(initial.getFacts(), b.getFacts());
            assertNotSame(a.getRoutes().getFirst(), b.getRoutes().getFirst());
            assertNotSame(a.getVisits().getFirst(), b.getVisits().getFirst());
            a.getRoutes().getFirst().getVisits().clear();
            assertTrue(RouteEvaluator.evaluate(b).feasible()); assertEquals(before, RouteEvaluator.evaluate(initial));
        }
    }
    @Test void optionalTelemetryFailuresRetainIndependentOutcomeButRequiredExperimentsFailWithEvidence() {
        var definition = SolverEngine.configuration(SolverEngine.Variant.TABU, 17);
        List<SolverEngine.Telemetry> failures = List.of(
                _ -> { throw new IllegalStateException("attach unavailable"); },
                _ -> () -> { throw new NoSuchMethodError("version changed"); });
        for (var telemetry : failures) {
            var result = SolverEngine.solve(definition, DayConstraintProviderTest.fixture(), Required.value(Duration.ofMillis(100)), telemetry, false);
            assertTrue(result.outcome().policyEligible()); assertNull(result.statistics().steps());
            assertNotNull(result.statistics().diagnosticsUnavailableReason());
            var failure = assertThrows(SolverEngine.DiagnosticsFailure.class, () -> SolverEngine.solve(definition,
                    DayConstraintProviderTest.fixture(), Required.value(Duration.ofMillis(100)), telemetry, true));
            assertTrue(failure.result().outcome().scoringMatchesValidation());
            assertNotNull(failure.result().statistics().diagnosticsUnavailableReason());
        }
    }
    /** SolverEngine copies the whole declared termination; production must declare only its budget and legacy step cap. */
    @Test void productionVariantsDeclareOnlySpentLimitAndLegacyStepCap() {
        var allowed = java.util.Set.of("<spentLimit>PT15S</spentLimit>", "<spentLimit>15s</spentLimit><stepCountLimit>1000</stepCountLimit>", "<spentLimit>15s</spentLimit>");
        for (var variant : SolverEngine.Variant.values()) for (boolean construction : new boolean[]{false, true}) {
            String xml = SolverEngine.configuration(variant, 17, ai.timefold.solver.core.config.solver.EnvironmentMode.NO_ASSERT, construction).configurationXml().replaceAll("\s+", "");
            var blocks = java.util.regex.Pattern.compile("<termination>(.*?)</termination>").matcher(xml).results().map(match -> match.group(1)).toList();
            assertEquals(1, blocks.size(), variant + " must declare one solver-level termination");
            assertTrue(allowed.contains(blocks.getFirst()), variant + " declares unexpected termination " + blocks.getFirst());
        }
    }
    @Test void earlyTerminationDistinguishesOperationCancellationFromPhaseBudgetStop() {
        var definition = SolverEngine.configuration(SolverEngine.Variant.TABU, 17);
        for (boolean cancelled : new boolean[]{true, false}) {
            var result = SolverEngine.solve(definition, DayConstraintProviderTest.fixture(), Required.value(Duration.ofSeconds(30)), null, false,
                    new SolverEngine.Cancellation() {
                        @Override public void started(ai.timefold.solver.core.api.solver.Solver<DayPlan> solver) {
                            Thread.ofVirtual().start(() -> {
                                try { Thread.sleep(50); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                                solver.terminateEarly();
                            });
                        }
                        @Override public void stopped() { }
                        @Override public boolean cancelled() { return cancelled; }
                    });
            assertEquals(cancelled ? "CANCELLED" : "TERMINATED_EARLY", result.statistics().termination());
            assertEquals("OBSERVED", result.statistics().terminationBasis());
            assertTrue(result.statistics().solveMs() < 30_000);
        }
    }
    @Test void loadedArtifactIsIdentifiedAndNoSearchDoesNotInventCounters() {
        var provenance = EngineProvenance.loaded();
        assertEquals("2.6.0", provenance.version()); assertNotNull(provenance.sha256()); assertNull(provenance.unavailableReason());
        var solver = new DailySolver("TABU", 17);
        var result = solver.solve(DailyConstructionTest.fixture(0, 1, 0, 0, DayPlan.Mode.COLD), Required.value(Duration.ofMillis(100)));
        assertEquals("NOT_RUN", result.statistics().termination()); assertEquals("NO_SEARCH", result.statistics().diagnosticsUnavailableReason());
        assertNull(result.statistics().scoreCalculations()); assertFalse(result.outcome().complete());
    }
    @Test void persistedV2RejectsMissingReasonsFabricatedCountersAndMislabelledTermination() {
        var solver = new DailySolver("TABU", 17);
        var result = solver.solve(DayConstraintProviderTest.fixture(), Required.value(Duration.ofMillis(100)));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String field : List.of("diagnosticsUnavailableReason", "timeToBestUnavailableReason", "provenance", "format")) {
            com.fasterxml.jackson.databind.node.ObjectNode node = mapper.valueToTree(solver.diagnostics(Required.value(List.<DailySolver.Phase>of(new DailySolver.Phase("REFERENCE", result.statistics())))));
            var stats = (com.fasterxml.jackson.databind.node.ObjectNode) node.path("phases").get(0).path("statistics");
            stats.remove(field); assertThrows(RuntimeException.class, () -> dev.waterflex.scheduler.SavedJson.solverAnalysis(Required.value(node)));
        }
    }
}
