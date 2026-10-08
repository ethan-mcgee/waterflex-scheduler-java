package dev.waterflex.benchmark;

import static org.junit.jupiter.api.Assertions.*;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.optimizer.*;
import java.io.StringReader;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkContractTest {
    @TempDir @org.jspecify.annotations.Nullable Path directory;
    private Path directory() { return Required.value(directory); }
    static CampaignSolverConfiguration.Settings settings(CampaignSolverConfiguration.Termination termination) {
        return new CampaignSolverConfiguration.Settings("test","TABU",7,1,100,
                Required.value(List.of(new CampaignSolverConfiguration.Move("listChange",1),new CampaignSolverConfiguration.Move("listSwap",1))),
                "NO_ASSERT","NONE",1,termination);
    }
    static CampaignSolverConfiguration.Settings settings() {
        return settings(new CampaignSolverConfiguration.Termination("fixed","local-search",true,3,null,null,null));
    }
    static DailyDataset dataset() throws Exception {
        return DailyDataset.parse(DailyDataset.seal(Required.value(Files.readString(Path.of("../scheduler-service/src/test/resources/daily-dataset-v1.json")))));
    }
    @Test void effectivePhasesAndTerminationArePinnedTo260() {
        var caller = SolverConfig.createFromXmlReader(new StringReader(SolverEngine.configuration(SolverEngine.Variant.TABU,17).configurationXml()));
        assertEquals(15000L,Required.value(caller.getTerminationConfig()).calculateTimeMillisSpentLimit());
        for (boolean construction : List.of(false,true)) {
            var definition = CampaignSolverConfiguration.definition(settings(),23,construction,50);
            var config = SolverConfig.createFromXmlReader(new StringReader(definition.configurationXml()));
            assertEquals(construction ? 2 : 1,Required.value(config.getPhaseConfigList()).size());
            assertEquals(23L,config.getRandomSeed()); assertEquals("NONE",config.getMoveThreadCount());
            assertEquals(50L,Required.value(config.getTerminationConfig()).getMillisecondsSpentLimit());
            var local=(LocalSearchPhaseConfig)Required.value(config.getPhaseConfigList()).getLast();
            assertEquals(3,Required.value(local.getTerminationConfig()).getStepCountLimit());
        }
        var diminished=settings(new CampaignSolverConfiguration.Termination("diminished-returns","solver",true,null,20L,0.01,null));
        assertDoesNotThrow(() -> CampaignSolverConfiguration.definition(diminished,17,false,80));
        var unimproved=settings(new CampaignSolverConfiguration.Termination("unimproved-time","solver",true,null,null,null,20L));
        assertDoesNotThrow(() -> CampaignSolverConfiguration.definition(unimproved,17,false,80));
        for(String scope:Required.value(List.of("solver","local-search"))) for(String kind:Required.value(List.of("diminished-returns","unimproved-time"))) {
            var configured=settings(new CampaignSolverConfiguration.Termination(Required.value(kind),Required.value(scope),true,null,
                    kind.equals("diminished-returns") ? 20L : null,kind.equals("diminished-returns") ? 0.01 : null,kind.equals("unimproved-time") ? 20L : null));
            assertDoesNotThrow(() -> CampaignSolverConfiguration.definition(configured,17,false,80).factory().buildSolver());
        }
        // Native inherited phase lists append in this release. Explicit complete definitions avoid double LS.
        var inherited=new SolverConfig().withPhases(new ConstructionHeuristicPhaseConfig(),new LocalSearchPhaseConfig());
        var child=new SolverConfig().withPhases(new LocalSearchPhaseConfig()).inherit(inherited);
        assertEquals(3,Required.value(child.getPhaseConfigList()).size());
    }
    @Test void nativeRetainsReportAndIndependentlyImportableOutput() throws Exception {
        var dataset=dataset();
        Path input=directory().resolve("daily.json");
        Files.writeString(input,CalculationJson.write(new SnapshotFileIO.Input(1,dataset.json(DailyDataset.Encoding.SPARSE),null)));
        var config=NativeBenchmark.configuration("native",directory().resolve("reports").toFile(),List.of(Required.value(input.toFile())),
                CampaignSolverConfiguration.definition(settings(),17,false,100),Required.value(List.of(ai.timefold.solver.benchmark.config.statistic.ProblemStatisticType.BEST_SCORE)));
        assertEquals("1",config.getParallelBenchmarkCount());
        assertEquals(1,Required.value(Required.value(config.getSolverBenchmarkConfigList()).get(0)).getSubSingleCount());
        Path report=NativeBenchmark.run(config).toPath();
        var measured=NativeReport.read(Required.value(report.resolve("plannerBenchmarkResult.xml")),CampaignSolverConfiguration.definition(settings(),17,false,100),100);
        assertEquals(17,measured.seed());assertEquals(1,measured.phaseCount());assertTrue(measured.moveEvaluationCount()>0);
        try(var files=Files.walk(report)) { assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".html"))); }
        try(var files=Files.walk(report)) { assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".csv"))); }
        Path output;
        try(var files=Files.walk(report)) { output=files.filter(path -> path.getFileName().toString().endsWith(".proposal.json")).findFirst().orElseThrow(); }
        var proposal=CalculationJson.read(Required.value(Files.readString(output)),SnapshotFileIO.Proposal.class);
        var restored=SnapshotFileIO.importProposal(dataset.toDayPlan(),proposal);
        assertTrue(DailyOutcome.assess(restored).policyEligible());
    }
    @Test void proposalsRejectMissingFactsAndNeverTrustScoreText() throws Exception {
        var dataset=dataset(); var input=dataset.toDayPlan(); var proposal=SnapshotFileIO.proposal(input);
        var lied=new SnapshotFileIO.Proposal(1,proposal.factsHash(),proposal.routes(),proposal.unassigned(),proposal.mode(),"fake-score");
        assertTrue(DailyCalculation.valid(SnapshotFileIO.importProposal(input,lied)));
        var wrong=new SnapshotFileIO.Proposal(1,"wrong",proposal.routes(),proposal.unassigned(),proposal.mode(),null);
        assertThrows(IllegalArgumentException.class,() -> SnapshotFileIO.importProposal(input,wrong));
        assertThrows(RuntimeException.class,() -> CalculationJson.read("{\"version\":1,\"datasetJson\":null,\"target\":null}",SnapshotFileIO.Input.class));
        assertThrows(RuntimeException.class,() -> new SnapshotFileIO().read(null));
    }
    @Test void requestCanonicalHashUsesSortedAscii() {
        assertEquals("{\"a\":1,\"z\":\"\\u00e9\\ud83d\\ude00\"}",Protocol.canonical(CalculationJson.tree("{\"z\":\"é😀\",\"a\":1}")));
    }
    @Test void completePolicyRetainsCallerBaselineUnlessItsMeasuredCandidateIsAccepted() throws Exception {
        var measured=PolicyBenchmark.run(dataset(),settings(),17,
                new PolicyBenchmark.Budget("short","contract-test","pipeline",6000,2000,1000,1000,0,100,false),SchedulingPolicy.Rules.defaults());
        assertEquals("COMPLETED",measured.operation().outcome());
        assertTrue(measured.independentlyValidRetained());
        assertTrue(measured.reference().policyEligible());
        assertTrue(measured.calculation().diagnostics().phases().stream().anyMatch(phase -> phase.name().equals("REFERENCE")));
        assertTrue(measured.calculation().diagnostics().phases().stream().anyMatch(phase -> phase.name().equals("FAIRNESS")));
    }
    @Test void corpusRandomnessIsIndependentOfSolverSeedAndRoundTripsAllCohorts() {
        var first=new DatasetTool.Corpus(1,"corpus","unused",Required.value(java.time.Instant.parse("2026-10-12T12:00:00Z")),480,
                41,-96,111000,15,0.1,new java.math.BigDecimal("20"),new java.math.BigDecimal("30"),new java.math.BigDecimal("0.67"),Required.value(java.math.BigDecimal.ZERO),0,
                Required.value(List.of(1L,2L)),Required.value(List.of("assigned","cold","partial","repair")),Required.value(List.of(new DatasetTool.Family("test",3,8,2,0.02,15,25,240,120,0.2,60,0.4,0))),"TABU",17,20000);
        var second=new DatasetTool.Corpus(first.version(),first.kind(),first.outputDirectory(),first.shiftStart(),first.shiftMinutes(),
                first.latitude(),first.longitude(),first.roadMetersPerDegree(),first.roadMetersPerSecond(),first.directionBias(),first.regularHourly(),
                first.overtimeHourly(),first.mileagePerMile(),first.travelBufferPct(),first.travelBufferMinutes(),first.datasetSeeds(),first.cohorts(),first.families(),"TABU",23,20000);
        for(String cohort:first.cohorts()) {
            var a=DatasetTool.generate(first,Required.value(first.families().getFirst()),1,Required.value(cohort));
            var b=DatasetTool.generate(second,Required.value(second.families().getFirst()),1,Required.value(cohort));
            assertEquals(a.facts(),b.facts());
            assertEquals(a.facts(),DailyDataset.parse(a.json(DailyDataset.Encoding.DENSE)).facts());
            assertNotEquals(a.facts(),DatasetTool.generate(first,Required.value(first.families().getFirst()),2,Required.value(cohort)).facts());
        }
    }
    @Test void corpusConfigsDeclareTravelBufferAsFraction() throws Exception {
        // corpus-v1 is frozen phase 11 evidence. It declared the number 10 (a +1000% buffer), which the exact decimal
        // contract now rejects; every later corpus must declare a canonical decimal string fraction.
        try(var files=Files.list(Path.of("../experiments/configs"))) {
            var corpora=files.filter(path -> path.getFileName().toString().matches("corpus-v\\d+\\.json")).sorted().toList();
            assertTrue(corpora.size() >= 2);
            for(Path path:corpora) {
                String json=Required.value(Files.readString(path));
                if(path.getFileName().toString().equals("corpus-v1.json")) {
                    assertThrows(IllegalArgumentException.class,() -> CalculationJson.read(json,DatasetTool.Corpus.class));
                    continue;
                }
                var spec=CalculationJson.read(json,DatasetTool.Corpus.class);
                assertTrue(spec.travelBufferPct().signum() >= 0 && spec.travelBufferPct().compareTo(java.math.BigDecimal.ONE) < 0,path::toString);
            }
        }
        var current=CalculationJson.read(Required.value(Files.readString(Path.of("../experiments/configs/corpus-v2.json"))),DatasetTool.Corpus.class);
        assertEquals(0,current.travelBufferPct().compareTo(new java.math.BigDecimal("0.2")));
    }
    @Test void nativeFailedBatchFinishesReportsAndPreservesSuccessfulSibling() throws Exception {
        var dataset=dataset();Path input=directory().resolve("daily.json");
        Files.writeString(input,CalculationJson.write(new SnapshotFileIO.Input(1,dataset.json(DailyDataset.Encoding.SPARSE),null)));
        var config=NativeBenchmark.configuration("failed-batch",directory().resolve("reports").toFile(),List.of(Required.value(input.toFile())),
                CampaignSolverConfiguration.definition(settings(),17,false,100),Required.value(List.of()));
        var good=Required.value(Required.value(config.getSolverBenchmarkConfigList()).get(0));
        var bad=good.copyConfig().withName("broken");
        Required.value(Required.value(bad.getSolverConfig()).getScoreDirectorFactoryConfig()).setConstraintProviderClass(FailingConstraintProvider.class);
        config.withSolverBenchmarkConfigs(good,bad);
        assertThrows(ai.timefold.solver.benchmark.api.PlannerBenchmarkException.class,() -> NativeBenchmark.run(config));
        try(var files=Files.walk(directory().resolve("reports"))) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".html")));
        }
        try(var files=Files.walk(directory().resolve("reports"))) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".proposal.json")));
        }
        try(var files=Files.walk(directory().resolve("reports"))) {
            Path xml=files.filter(path -> path.getFileName().toString().equals("plannerBenchmarkResult.xml")).findFirst().orElseThrow();
            assertTrue(Files.readString(xml).contains("<succeeded>false</succeeded>"),Files.readString(xml));
        }
    }
    @Test void historicalAnonymizationPreservesExactMetricsAndFreezesOneValidatedTarget() throws Exception {
        var original=dataset();String source=original.json(DailyDataset.Encoding.SPARSE);
        Path input=directory().resolve("source.json"),anonymized=directory().resolve("anonymous.json"),targetFile=directory().resolve("target.json");
        Files.writeString(input,source);
        Map<String,Object> anonymize=new java.util.TreeMap<>();anonymize.put("version",1);anonymize.put("kind","anonymize");
        anonymize.put("input",java.util.Map.of("path",Required.value(input.toString()),"sha256",CalculationJson.hash(source)));anonymize.put("output",anonymized.toString());
        anonymize.put("dateShiftDays",7);anonymize.put("latitude",40);anonymize.put("longitude",-90);
        DatasetTool.execute(CalculationJson.tree(CalculationJson.write(anonymize)));
        var imported=DailyDataset.parse(Required.value(Files.readString(anonymized)));
        var before=DayScoreCalculator.evaluate(original.toDayPlan());var after=DayScoreCalculator.evaluate(imported.toDayPlan());
        assertEquals(before.costCents(),after.costCents());assertEquals(before.hardPenalty(),after.hardPenalty());
        assertEquals(before.overtimeMinutes(),after.overtimeMinutes());assertEquals(before.paidMinutes(),after.paidMinutes());
        assertEquals(before.meters(),after.meters());
        Map<String,Object> freeze=new java.util.TreeMap<>();freeze.put("version",1);freeze.put("kind","freeze-target");
        freeze.put("input",java.util.Map.of("path",Required.value(input.toString()),"sha256",CalculationJson.hash(source)));freeze.put("output",targetFile.toString());
        freeze.put("configuration",settings());freeze.put("seed",17);freeze.put("budgetMs",100);freeze.put("policy",SchedulingPolicy.Rules.defaults());
        DatasetTool.execute(CalculationJson.tree(CalculationJson.write(freeze)));
        var target=CalculationJson.read(Required.value(Files.readString(targetFile)),FrozenTarget.class);
        assertTrue(DailyOutcome.assess(target.bind(original)).policyEligible());
        assertThrows(IllegalArgumentException.class,() -> target.bind(imported));
        assertEquals(100,target.setupBudgetMs());
        assertThrows(RuntimeException.class,() -> DatasetTool.execute(CalculationJson.tree(CalculationJson.write(freeze))));
    }
}
