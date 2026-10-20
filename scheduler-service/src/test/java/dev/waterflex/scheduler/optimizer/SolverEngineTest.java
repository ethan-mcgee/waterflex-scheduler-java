package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SolverEngineTest {
    /** The production-comparison TABU control must stay byte-identical while experiment-only variants are added beside it. */
    @Test void tabuControlIsPinnedAndTabuVariantsChangeOnlyTheirIntendedElements() {
        String tabu = SolverEngine.configuration(SolverEngine.Variant.TABU, 17).configurationXml();
        assertEquals("<solver xmlns=\"https://timefold.ai/xsd/solver\"><environmentMode>NO_ASSERT</environmentMode>"
                + "<solutionClass>dev.waterflex.scheduler.optimizer.DayPlan</solutionClass><entityClass>dev.waterflex.scheduler.optimizer.TechRoute</entityClass><entityClass>dev.waterflex.scheduler.optimizer.PlanVisit</entityClass>"
                + "<scoreDirectorFactory><constraintProviderClass>dev.waterflex.scheduler.optimizer.DayConstraintProvider</constraintProviderClass></scoreDirectorFactory>"
                + "<termination><spentLimit>PT15S</spentLimit></termination><localSearch><unionMoveSelector><selectedCountLimit>10000</selectedCountLimit>"
                + "<listChangeMoveSelector><fixedProbabilityWeight>45</fixedProbabilityWeight></listChangeMoveSelector>"
                + "<listSwapMoveSelector><fixedProbabilityWeight>45</fixedProbabilityWeight></listSwapMoveSelector>"
                + "</unionMoveSelector><acceptor><entityTabuSize>7</entityTabuSize></acceptor><forager><acceptedCountLimit>1000</acceptedCountLimit></forager></localSearch></solver>", tabu);
        assertEquals(tabu.replace("<entityTabuSize>7<", "<entityTabuSize>3<"),
                SolverEngine.configuration(SolverEngine.Variant.TABU_SIZE_3, 17).configurationXml());
        assertEquals(tabu.replace("<entityTabuSize>7<", "<entityTabuSize>15<"),
                SolverEngine.configuration(SolverEngine.Variant.TABU_SIZE_15, 17).configurationXml());
        assertEquals(SolverEngine.configuration(SolverEngine.Variant.KOPT, 17).configurationXml()
                        .replace("<lateAcceptanceSize>400</lateAcceptanceSize>", "<entityTabuSize>7</entityTabuSize>")
                        .replace("<acceptedCountLimit>1</acceptedCountLimit>", "<acceptedCountLimit>1000</acceptedCountLimit>"),
                SolverEngine.configuration(SolverEngine.Variant.TABU_KOPT, 17).configurationXml());
        assertEquals(SolverEngine.configuration(SolverEngine.Variant.TABU_KOPT, 17).configurationXml().replace("<entityTabuSize>7<", "<entityTabuSize>3<"),
                SolverEngine.configuration(SolverEngine.Variant.TABU_KOPT_SIZE_3, 17).configurationXml());
    }
    /** TABU_KOPT is a client choice, so its XML is pinned byte for byte like the TABU control. */
    @Test void clientSelectableTabuKoptIsPinned() {
        assertEquals("<solver xmlns=\"https://timefold.ai/xsd/solver\"><environmentMode>NO_ASSERT</environmentMode>"
                + "<solutionClass>dev.waterflex.scheduler.optimizer.DayPlan</solutionClass><entityClass>dev.waterflex.scheduler.optimizer.TechRoute</entityClass><entityClass>dev.waterflex.scheduler.optimizer.PlanVisit</entityClass>"
                + "<scoreDirectorFactory><constraintProviderClass>dev.waterflex.scheduler.optimizer.DayConstraintProvider</constraintProviderClass></scoreDirectorFactory>"
                + "<termination><spentLimit>PT15S</spentLimit></termination><localSearch><unionMoveSelector><selectedCountLimit>10000</selectedCountLimit>"
                + "<listChangeMoveSelector><fixedProbabilityWeight>45</fixedProbabilityWeight></listChangeMoveSelector>"
                + "<listSwapMoveSelector><fixedProbabilityWeight>45</fixedProbabilityWeight></listSwapMoveSelector>"
                + "<subListChangeMoveSelector><fixedProbabilityWeight>5</fixedProbabilityWeight><selectReversingMoveToo>true</selectReversingMoveToo>"
                + "<subListSelector><minimumSubListSize>2</minimumSubListSize><maximumSubListSize>4</maximumSubListSize></subListSelector></subListChangeMoveSelector>"
                + "<kOptListMoveSelector><fixedProbabilityWeight>5</fixedProbabilityWeight><minimumK>2</minimumK><maximumK>3</maximumK></kOptListMoveSelector>"
                + "</unionMoveSelector><acceptor><entityTabuSize>7</entityTabuSize></acceptor><forager><acceptedCountLimit>1000</acceptedCountLimit></forager></localSearch></solver>",
                SolverEngine.configuration(SolverEngine.Variant.TABU_KOPT, 17).configurationXml());
    }
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
    /** A small converged day stops on the unimproved limit long before its budget, and says so with or without telemetry. */
    @Test void anUnimprovedLimitEndsAConvergedSearchBeforeItsBudgetAndIsReportedAsSuch() {
        var definition = SolverEngine.configuration(SolverEngine.Variant.TABU_KOPT, 17);
        Duration budget = Required.value(Duration.ofSeconds(30)), unimproved = Required.value(Duration.ofMillis(500));
        var plain = SolverEngine.solve(definition, DayConstraintProviderTest.fixture(), budget, unimproved);
        assertTrue(plain.statistics().solveMs() < 10_000, "stopped well before the 30 s budget: " + plain.statistics().solveMs());
        assertEquals("UNIMPROVED_LIMIT", plain.statistics().termination());
        assertEquals("INFERRED", plain.statistics().terminationBasis());
        assertEquals(500L, plain.statistics().unimprovedMs());
        assertEquals(SolverEngine.Statistics.FORMAT, plain.statistics().format());
        assertTrue(plain.outcome().policyEligible());

        var measured = SolverEngine.solve(definition, DayConstraintProviderTest.fixture(), budget, unimproved,
                SolverExperiment.telemetry(EngineProvenance.loaded()), true, null);
        assertEquals("UNIMPROVED_LIMIT", measured.statistics().termination(), "telemetry shows the last improvement at least the limit before the stop");
        Long best = measured.statistics().timeToBestMs();
        assertNotNull(best);
        assertTrue(measured.statistics().solveMs() - best >= 500, "the stop came at least the limit after the best score");

        var whole = SolverEngine.solve(definition, DayConstraintProviderTest.fixture(), Required.value(Duration.ofMillis(300)));
        assertNull(whole.statistics().unimprovedMs(), "no limit, none recorded");
        assertEquals("SOLVE_RETURNED", whole.statistics().termination(), "without a limit or telemetry nothing is inferred");
        // Timefold's clock may stop the phase a few milliseconds before this measures the budget as spent.
        assertTrue(whole.statistics().solveMs() + SolverEngine.UNIMPROVED_MARGIN_MS >= 300, "it spent its budget: " + whole.statistics().solveMs());

        // A limit as long as the budget cannot end the phase first: the time limit does, and nothing is misread.
        var timed = SolverEngine.solve(definition, DayConstraintProviderTest.fixture(), Required.value(Duration.ofMillis(400)), Required.value(Duration.ofMillis(400)));
        assertEquals("SOLVE_RETURNED", timed.statistics().termination(), "a stop at the budget is not called unimproved: " + timed.statistics().solveMs());

        assertThrows(IllegalArgumentException.class, () -> SolverEngine.solve(definition, DayConstraintProviderTest.fixture(), budget, Required.value(Duration.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> SolverEngine.solve(definition, DayConstraintProviderTest.fixture(), budget, Required.value(Duration.ofMillis(-1))));
    }
    /** Every module builds against the one Timefold release the instrumentation is verified for; a bump moves them together. */
    @Test void everyPomDeclaresTheInstrumentedTimefoldVersion() throws Exception {
        java.nio.file.Path root = Required.value(java.nio.file.Path.of("").toAbsolutePath().getParent());
        var pattern = java.util.regex.Pattern.compile("<timefold[.]version>([^<]+)</timefold[.]version>");
        List<java.nio.file.Path> poms;
        try (var files = java.nio.file.Files.walk(root, 2)) {
            poms = files.filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .filter(path -> !path.toString().contains(java.io.File.separator + ".")).toList();
        }
        var declared = new java.util.TreeMap<String, String>();
        for (var pom : poms) {
            var matcher = pattern.matcher(java.nio.file.Files.readString(Required.value(pom)));
            if (matcher.find()) declared.put(root.relativize(Required.value(pom)).toString(), Required.value(matcher.group(1)));
        }
        assertTrue(declared.containsKey("pom.xml"), "the root pom declares timefold.version: " + declared);
        for (var entry : declared.entrySet())
            assertEquals(SolverEngine.INSTRUMENTED_TIMEFOLD, entry.getValue(), entry.getKey() + " declares another Timefold release");
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
        assertEquals(SolverEngine.INSTRUMENTED_TIMEFOLD, provenance.version()); assertNotNull(provenance.sha256()); assertNull(provenance.unavailableReason());
        var solver = new DailySolver("TABU", 17);
        var result = solver.solve(DailyConstructionTest.fixture(0, 1, 0, 0, DayPlan.Mode.COLD), Required.value(Duration.ofMillis(100)));
        assertEquals("NOT_RUN", result.statistics().termination()); assertEquals("NO_SEARCH", result.statistics().diagnosticsUnavailableReason());
        assertNull(result.statistics().scoreCalculations()); assertFalse(result.outcome().complete());
    }
}
