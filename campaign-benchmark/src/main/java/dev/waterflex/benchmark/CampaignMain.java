package dev.waterflex.benchmark;

import static dev.waterflex.benchmark.Protocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import ai.timefold.solver.benchmark.config.statistic.ProblemStatisticType;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.optimizer.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** One fresh JVM per declared campaign case. All raw artifacts live beside the immutable request. */
public final class CampaignMain {
    public record RepairEvidence(@org.jspecify.annotations.Nullable Long timeToFeasibilityMs, String unavailableReason,
            List<String> unresolvedCandidate, List<String> unresolvedRetained, boolean terminalCandidateEligible, boolean terminalRetainedEligible) {
        static RepairEvidence assess(DailyOutcome candidate,DailyOutcome retained) {
            return new RepairEvidence(null,"Public adapter validates final proposals; intermediate best-score CSV is scoring evidence, not independently validated time-to-feasibility",
                    candidate.unassignedVisitIds(),retained.unassignedVisitIds(),candidate.policyEligible(),retained.policyEligible());
        }
    }
    private CampaignMain() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected immutable request and create-new receipt paths");
        Path requestPath = Path.of(args[0]).toAbsolutePath();
        JsonNode request = CalculationJson.tree(SnapshotFileIO.readText(requestPath.toFile()));
        if (request.has("kind")) {
            SnapshotFileIO.writeText(Path.of(args[1]).toFile(),CalculationJson.write(DatasetTool.execute(request)));
            return;
        }
        fields(request,"protocol","case","layer","dataset","configuration","budget","warmup","instrumentation",
                "applicationLoad","jvmFlags","affinityCpus","runtimeHash","policy");
        if (!text(get(request,"protocol")).equals("waterflex-campaign-jvm-v1")) throw new IllegalArgumentException("Campaign protocol");
        JsonNode identity = get(request,"case"), source = get(request,"dataset");
        fields(identity,"datasetId","datasetHash","targetHash","budgetId","budgetHash","solverSeed","fork","scoreVersion",
                "modelVersion","routingIdentity","blockId","configurationId","configurationHash","id");
        fields(source,"id","input","family","role","cohort","origin","datasetSeed","scoreVersion","modelVersion","routingIdentity","target");
        var caseIdentity=(com.fasterxml.jackson.databind.node.ObjectNode)identity.deepCopy();caseIdentity.remove("id");
        if(!hash(caseIdentity).equals(text(get(identity,"id")))) throw new IllegalArgumentException("Case identity hash mismatch");
        for(String field:List.of("scoreVersion","modelVersion","routingIdentity")) if(!text(get(identity,Required.value(field))).equals(text(get(source,Required.value(field))))) throw new IllegalArgumentException("Case dataset model differs");
        String layer = text(get(request,"layer"));
        var settings = record(get(request,"configuration"),CampaignSolverConfiguration.Settings.class);
        var budget = record(get(request,"budget"),PolicyBenchmark.Budget.class);
        var policy = record(get(request,"policy"),SchedulingPolicy.Rules.class);
        long seed = integer(get(identity,"solverSeed"),0);
        if (!settings.id().equals(text(get(identity,"configurationId"))) || !budget.id().equals(text(get(identity,"budgetId")))
                || !hash(get(request,"configuration")).equals(text(get(identity,"configurationHash")))
                || !hash(get(request,"budget")).equals(text(get(identity,"budgetHash")))) throw new IllegalArgumentException("Campaign settings identity mismatch");
        String input = checkedArtifact(get(source,"input"));
        if (!text(get(source,"id")).equals(text(get(identity,"datasetId")))
                || !text(get(get(source,"input"),"sha256")).equals(text(get(identity,"datasetHash")))) throw new IllegalArgumentException("Dataset identity mismatch");
        var inst=get(request,"instrumentation");
        fields(inst,"cohort","statistics","jfr","sampleIntervalMs","internalDiagnostics","constraintProfiling");
        if (bool(get(inst,"constraintProfiling"))) throw new IllegalArgumentException("Constraint profiling is not supported in this campaign");
        boolean requiredInternals=bool(get(inst,"internalDiagnostics"));
        List<String> statistics=strings(get(inst,"statistics"));
        List<@org.jspecify.annotations.NonNull ProblemStatisticType> types = new ArrayList<>();
        for (String name : statistics) {
            if (!Set.of("BEST_SCORE","MOVE_EVALUATION_SPEED","SCORE_CALCULATION_SPEED","MEMORY_USE").contains(name)) throw new IllegalArgumentException("Statistic unsupported");
            types.add(ProblemStatisticType.valueOf(name));
        }
        List<Integer> affinity=observedAffinity();
        List<Long> declared=new ArrayList<>();
        for (JsonNode item : get(request,"affinityCpus")) declared.add(integer(Required.value(item),0));
        if (!affinity.stream().map(cpu -> Required.value(cpu).longValue()).toList().equals(declared)) throw new IllegalArgumentException("Actual affinity differs");
        var arguments=Required.value(ManagementFactory.getRuntimeMXBean().getInputArguments());
        if (!arguments.equals(strings(get(request,"jvmFlags")))) throw new IllegalArgumentException("Actual JVM arguments differ");
        Map<String,Object> receipt=new TreeMap<>();
        receipt.put("protocol","waterflex-campaign-jvm-v1"); receipt.put("caseId",text(get(identity,"id"))); receipt.put("requestHash",hash(request));
        receipt.put("evidenceKind","benchmark");
        receipt.put("runtime",Map.of("inputArguments",arguments,"availableProcessors",Runtime.getRuntime().availableProcessors(),"affinityCpus",affinity,"pid",ProcessHandle.current().pid()));
        Map<String,Object> instrumentation=new TreeMap<>(); receipt.put("instrumentation",instrumentation);
        List<String> enabled=new ArrayList<>();
        instrumentation.put("enabledStatistics",enabled);
        List<Map<String,Object>> unavailable=new ArrayList<>();
        types.clear();
        for (String name : statistics) {
            if(layer.equals("solver") && (name.equals("BEST_SCORE") || integer(get(inst,"sampleIntervalMs"),1)==1000)) {
                enabled.add(name);types.add(ProblemStatisticType.valueOf(name));continue;
            }
            Map<String,Object> value=new TreeMap<>(); value.put("name",name);value.put("value",null);
            value.put("reason",!layer.equals("solver") ? "Native statistics are available only for Layer A; complete-policy metrics are recorded separately"
                    : "Timefold 2.6.0 public benchmark configuration uses fixed 1000 ms sampling; requested cadence unavailable");unavailable.add(value);
        }
        instrumentation.put("unavailableStatistics",unavailable); instrumentation.put("internalDiagnosticsEnabled",false);
        instrumentation.put("internalDiagnosticsFailureReason",requiredInternals ? "Public native API does not expose internal selector counters" : null);
        Path directory=Required.value(requestPath.getParent());
        try {
            if (requiredInternals) throw new IllegalArgumentException("Required internal diagnostics unavailable");
            if(text(get(source,"cohort")).equals("invalid-input")) {
                if(!layer.equals("solver") || !statistics.isEmpty()) throw new IllegalArgumentException("Invalid input is contract-only evidence");
                JsonNode warm=get(request,"warmup");List<String> paths=strings(get(warm,"paths"));
                if(!paths.equals(List.of("input-contract")) || !bool(get(warm,"disposableInputs"))) throw new IllegalArgumentException("Invalid input warmup path");
                long duration=integer(get(warm,"millisecondsPerFreshJvm"),0),started=System.nanoTime();
                IllegalArgumentException rejected;
                do {
                    try { DailyDataset.parse(input);throw new IllegalStateException("Invalid input unexpectedly parsed"); }
                    catch(IllegalArgumentException expected) { rejected=expected; }
                } while((System.nanoTime()-started)/1_000_000<duration);
                receipt.put("warmup",Map.of("paths",paths,"disposableInputs",true,"elapsedMsByPath",Map.of("input-contract",(System.nanoTime()-started)/1e6)));
                receipt.put("result",Map.of("layer","input-contract","expectedFailure",true,"failureType",rejected.getClass().getName(),"failureMessage",String.valueOf(rejected.getMessage())));
                receipt.put("state","SUCCEEDED");SnapshotFileIO.writeText(Path.of(args[1]).toFile(),CalculationJson.write(receipt));return;
            }
            if (layer.equals("workflow")) {
                var result=WorkflowBenchmark.run(request,input,directory);
                receipt.put("warmup",Required.value(result.get("warmup")));receipt.put("result",result);receipt.put("state","SUCCEEDED");
                SnapshotFileIO.writeText(Path.of(args[1]).toFile(),CalculationJson.write(receipt));
                return;
            }
            if (!Set.of("solver","policy").contains(layer)) throw new IllegalArgumentException("Unsupported campaign layer");
            DailyDataset dataset=DailyDataset.parse(input);
            if (!DailyDataset.SCORE_MODEL.equals(text(get(source,"scoreVersion"))) || !DailyDataset.COST_MODEL.equals(text(get(source,"modelVersion")))) throw new IllegalArgumentException("Dataset model mismatch");
            JsonNode data=CalculationJson.tree(input);
            if (!text(get(get(data,"routing"),"identity")).equals(text(get(source,"routingIdentity")))
                    || !dataset.toDayPlan().getMode().name().toLowerCase(Locale.ROOT).equals(text(get(source,"cohort")))) throw new IllegalArgumentException("Dataset routing/cohort mismatch");
            FrozenTarget target=null;
            if (!get(source,"target").isNull()) {
                if (!layer.equals("solver") || !budget.phase().equals("fairness")) throw new IllegalArgumentException("Frozen targets belong only to Layer A fairness");
                target=CalculationJson.read(checkedArtifact(get(source,"target")),FrozenTarget.class); target.bind(dataset);
                if(!text(get(get(source,"target"),"sha256")).equals(text(get(identity,"targetHash")))) throw new IllegalArgumentException("Frozen target identity mismatch");
            }
            else if(!get(identity,"targetHash").isNull()) throw new IllegalArgumentException("Unexpected target hash");
            if (budget.phase().equals("fairness") && target == null) throw new IllegalArgumentException("Fairness target required");
            receipt.put("warmup",warmup(get(request,"warmup"),dataset,settings,seed,budget,policy,target,layer));
            Map<String,@org.jspecify.annotations.NonNull Object> result;
            if (layer.equals("solver")) {
                DayPlan seedPlan=target == null ? dataset.toDayPlan() : target.bind(dataset);
                var definition=CampaignSolverConfiguration.definition(settings,seed,!seedPlan.getUnassignedVisitIds().isEmpty(),budget.searchMs());
                SnapshotFileIO.writeText(directory.resolve("effective-solver.xml").toFile(),definition.configurationXml());
                Path nativeInput=directory.resolve("native-input.json");
                SnapshotFileIO.writeText(nativeInput.toFile(),CalculationJson.write(new SnapshotFileIO.Input(1,input,target)));
                long started=System.nanoTime();
                var config=NativeBenchmark.configuration(settings.id(),directory.resolve("native").toFile(),List.of(Required.value(nativeInput.toFile())),definition,types);
                Path report=NativeBenchmark.run(config).toPath();
                Path output;
                try (var files=Files.walk(report)) { output=files.filter(path -> path.getFileName().toString().endsWith(".proposal.json")).findFirst().orElseThrow(); }
                var proposal=CalculationJson.read(SnapshotFileIO.readText(output.toFile()),SnapshotFileIO.Proposal.class);
                DayPlan solved=SnapshotFileIO.importProposal(seedPlan,proposal);
                var measured=NativeReport.read(Required.value(report.resolve("plannerBenchmarkResult.xml")),definition,budget.searchMs());
                result=new TreeMap<>(Map.of("layer","solver","wallMs",(System.nanoTime()-started)/1_000_000,"configurationHash",definition.fingerprint(),
                        "seed",seed,"phaseCount",definition.construction() ? 2 : 1,"outcome",DailyOutcome.assess(solved),
                        "metrics",DayScoreCalculator.evaluate(solved),"proposal",proposal,"nativeReport",Required.value(directory.relativize(report).toString()),"nativeMeasurement",measured));
                if(DailyOutcome.assess(solved).complete() && DailyOutcome.assess(solved).assignedWorkFeasible()) result.put("policyMetrics",SchedulingPolicy.measure(solved));
                if(dataset.toDayPlan().getMode()==DayPlan.Mode.REPAIR) result.put("repair",RepairEvidence.assess(DailyOutcome.assess(solved),DailyOutcome.assess(solved)));
            } else {
                var measured=PolicyBenchmark.run(dataset,settings,seed,budget,policy);
                var calculation=measured.calculation();
                result=new TreeMap<>(Map.of("layer","policy","wallMs",measured.wallMs(),"operation",measured.operation(),"baseline",measured.baseline(),
                        "reference",measured.reference(),"candidate",measured.candidate(),"accepted",calculation.accepted(),
                        "retained",measured.retained(),"retainedMetrics",DayScoreCalculator.evaluate(SnapshotFileIO.importProposal(dataset.toDayPlan(),measured.retained())),
                        "diagnostics",calculation.diagnostics()));
                DayPlan retainedPlan=SnapshotFileIO.importProposal(dataset.toDayPlan(),measured.retained());
                if(DailyOutcome.assess(retainedPlan).complete() && DailyOutcome.assess(retainedPlan).assignedWorkFeasible()) result.put("policyMetrics",SchedulingPolicy.measure(retainedPlan));
                if(dataset.toDayPlan().getMode()==DayPlan.Mode.REPAIR) result.put("repair",RepairEvidence.assess(measured.candidate(),DailyOutcome.assess(SnapshotFileIO.importProposal(dataset.toDayPlan(),measured.retained()))));
            }
            receipt.put("result",result);receipt.put("state","SUCCEEDED");
            SnapshotFileIO.writeText(Path.of(args[1]).toFile(),CalculationJson.write(receipt));
        } catch (Throwable failure) {
            receipt.put("state","FAILED");receipt.put("result",Map.of("layer",layer,"failureType",failure.getClass().getName(),"failureMessage",String.valueOf(failure.getMessage())));
            SnapshotFileIO.writeText(directory.resolve("adapter-failure.json").toFile(),CalculationJson.write(receipt));
            throw failure;
        }
    }
    static String checkedArtifact(JsonNode artifact) {
        fields(artifact,"path","sha256");String value=SnapshotFileIO.readText(Path.of(text(get(artifact,"path"))).toFile());
        if (!CalculationJson.hash(value).equals(text(get(artifact,"sha256")))) throw new IllegalArgumentException("Artifact hash mismatch");return value;
    }
    private static Map<String,Object> warmup(JsonNode warm,DailyDataset dataset,CampaignSolverConfiguration.Settings settings,
            long seed,PolicyBenchmark.Budget budget,SchedulingPolicy.Rules policy,@org.jspecify.annotations.Nullable FrozenTarget target,String layer) {
        fields(warm,"millisecondsPerFreshJvm","paths","disposableInputs","calibrationMs","calibrationRepetitions","stabilityTolerancePercent");
        if (!bool(get(warm,"disposableInputs"))) throw new IllegalArgumentException("Disposable warmup required");
        long milliseconds=integer(get(warm,"millisecondsPerFreshJvm"),0);List<String> paths=strings(get(warm,"paths"));
        Map<String,Double> elapsed=new TreeMap<>();
        for (String path : paths) {
            if (!Set.of("reference","fairness","repair","daily-policy").contains(path)) throw new IllegalArgumentException("Warmup path unsupported");
            long started=System.nanoTime();
            while ((System.nanoTime()-started)/1_000_000 < milliseconds) {
                long cap=Math.max(1,Math.min(budget.searchMs(),milliseconds-(System.nanoTime()-started)/1_000_000));
                if (path.equals("daily-policy")) {
                    PolicyBenchmark.run(dataset,settings,seed,budget,policy);
                } else {
                    DayPlan plan=path.equals("fairness") ? Required.value(target,"Warmup fairness requires frozen target").bind(dataset) : dataset.toDayPlan();
                    SolverEngine.solve(CampaignSolverConfiguration.definition(settings,seed,!plan.getUnassignedVisitIds().isEmpty(),cap),plan,Required.value(Duration.ofMillis(cap)));
                }
            }
            elapsed.put(path,(System.nanoTime()-started)/1e6);
        }
        return Required.value(Map.of("paths",paths,"disposableInputs",true,"elapsedMsByPath",elapsed));
    }
    static List<Integer> observedAffinity() throws Exception {
        List<Integer> result=new ArrayList<>();
        if (System.getProperty("os.name").startsWith("Windows")) {
            long pid=ProcessHandle.current().pid();
            var process=new ProcessBuilder("powershell.exe","-NoProfile","-Command","$m=(Get-Process -Id "+pid+").ProcessorAffinity.ToInt64(); for($i=0;$i -lt 63;$i++){if($m -band (1L -shl $i)){$i}}").start();
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            if (process.waitFor()!=0) throw new IllegalStateException("Affinity probe failed");
            for (String line : output.lines().toList()) result.add(Integer.parseInt(line.strip()));
        } else {
            String list=Files.readString(Path.of("/proc/self/status")).lines().filter(line -> line.startsWith("Cpus_allowed_list:"))
                    .findFirst().orElseThrow().split(":")[1].strip();
            for (String part : list.split(",")) {
                String[] bounds=part.split("-");int start=Integer.parseInt(bounds[0]),end=bounds.length==1 ? start : Integer.parseInt(bounds[1]);
                for (int cpu=start;cpu<=end;cpu++) result.add(cpu);
            }
        }
        if (result.isEmpty()) throw new IllegalStateException("Affinity unavailable");return Required.value(List.copyOf(result));
    }
}
