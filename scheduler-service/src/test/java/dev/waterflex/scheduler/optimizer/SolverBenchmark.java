package dev.waterflex.scheduler.optimizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.waterflex.scheduler.Required;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Explicit standalone benchmark, not a timing assertion in the unit suite. */
public final class SolverBenchmark {
    private SolverBenchmark() { }
    public static void main(String[] args) throws Exception {
        String revision = Required.value(System.getProperty("benchmark.revision"), "exact benchmark revision");
        Path output = Required.value(Path.of(Required.value(System.getProperty("benchmark.output"), "benchmark output")));
        long budget = Long.parseLong(System.getProperty("benchmark.durationMs", "15000"));
        validateBudget(budget);
        var sizes = values("benchmark.sizes", "20,30,50").stream().mapToInt(Integer::parseInt).toArray();
        var workloads = values("benchmark.workloads", "SPARSE,CLUSTERED,DISPERSED,MIXED_SKILL,TIGHT_WINDOW,ABSENCE,NEAR_CAPACITY")
                .stream().map(value -> SolverBenchmarkData.Workload.valueOf(Required.value(value))).toList();
        var variants = values("benchmark.variants", "CURRENT_CAPPED,CURRENT_UNCAPPED,LATE_ACCEPTANCE_CHANGE,LATE_ACCEPTANCE,TABU,SUBLIST,KOPT,RUIN_RECREATE")
                .stream().map(value -> SolverExperiment.Variant.valueOf(Required.value(value))).toList();
        var seeds = values("benchmark.seeds", "17").stream().mapToLong(Long::parseLong).toArray();
        ObjectMapper json = new ObjectMapper();
        @Nullable Path parent = output.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent);
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("type", "provenance"); provenance.put("format", 1); provenance.put("revision", revision);
            provenance.put("startedAt", Instant.now().toString()); provenance.put("java", Required.value(System.getProperty("java.runtime.version")));
            provenance.put("os", Required.value(System.getProperty("os.name")) + " " + Required.value(System.getProperty("os.version")));
            provenance.put("processors", Runtime.getRuntime().availableProcessors()); provenance.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
            provenance.put("budgetMs", budget); provenance.put("routingIdentity", "deterministic-directed-fixtures-v1");
            provenance.put("roadEvidence", false); provenance.put("travelBufferPct", .2); provenance.put("travelBufferMinutes", 5);
            writer.write(json.writeValueAsString(provenance)); writer.newLine(); writer.flush();
            Map<String, SolverExperiment.Definition> definitions = new HashMap<>();
            for (long seed : seeds) for (var variant : variants) {
                var definition = SolverExperiment.configuration(Required.value(variant), seed);
                definitions.put(variant + ":" + seed, definition);
                writer.write(json.writeValueAsString(Map.<String, Object>of("type", "configuration", "variant", Required.value(variant.name()), "seed", seed,
                        "fingerprint", definition.fingerprint(), "xml", definition.configurationXml()))); writer.newLine();
                // Identical short warmup for every strategy before recording any measured dataset.
                SolverExperiment.solve(definition, SolverBenchmarkData.create(20, SolverBenchmarkData.Workload.SPARSE), Required.value(Duration.ofMillis(200)));
            }
            writer.flush(); int round = 0;
            for (int size : sizes) for (var workload : workloads) for (long seed : seeds) {
                DayPlan fixture = SolverBenchmarkData.create(size, Required.value(workload));
                String fingerprint = fingerprint(fixture);
                var ordered = new ArrayList<>(variants); Collections.rotate(ordered, round++ % ordered.size());
                for (var variant : ordered) {
                    var definition = Required.value(definitions.get(variant + ":" + seed));
                    Map<String, Object> row = run(definition, PlanCopies.copy(fixture), budget);
                    if (!fingerprint.equals(fingerprint(fixture))) throw new IllegalStateException("Starting fixture changed");
                    row.put("type", "result"); row.put("technicians", size); row.put("workload", workload.name());
                    row.put("datasetFingerprint", fingerprint); row.put("appointments", fixture.getVisits().size());
                    row.put("completedAt", Instant.now().toString());
                    writer.write(json.writeValueAsString(row)); writer.newLine(); writer.flush();
                    System.out.println(size + "/" + workload + "/" + variant + "/" + seed + " " + row.get("acceptanceReason"));
                }
            }
        }
    }

    static Map<String, Object> run(SolverExperiment.Definition definition, DayPlan fixture, long budgetMs) {
        validateBudget(budgetMs);
        var beforeValidation = validate(fixture);
        var before = SchedulingPolicy.measure(fixture);
        var rules = SchedulingPolicy.Rules.defaults();
        for (var pool : ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage();
        long cpuBefore = cpuNanos(); long started = System.nanoTime();
        var cost = SolverExperiment.solve(definition, fixture, Required.value(Duration.ofMillis(Math.max(1, budgetMs * 2 / 3))));
        validate(cost.plan());
        var costMetrics = SchedulingPolicy.measure(cost.plan());
        DayPlan reference = costMetrics.overtimeMinutes() < before.overtimeMinutes()
                || costMetrics.overtimeMinutes() == before.overtimeMinutes() && costMetrics.costCents() < before.costCents() ? cost.plan() : fixture;
        var referenceMetrics = SchedulingPolicy.measure(reference);
        DayPlan chosen = reference;
        SolverExperiment.@Nullable Statistics fairStatistics = null;
        long remaining = budgetMs - (System.nanoTime() - started) / 1_000_000;
        if (remaining > 0) {
            DayPlan fairnessSeed = PlanCopies.copy(reference);
            fairnessSeed.setScoringFacts(fairnessSeed.getScoringFacts().withTarget(new RouteScoringFacts.Target(referenceMetrics.overtimeMinutes(), rules.costCeiling(referenceMetrics.costCents()))));
            var fair = SolverExperiment.solve(definition, fairnessSeed, Required.value(Duration.ofMillis(remaining)));
            validate(fair.plan()); fairStatistics = fair.statistics();
            var metrics = SchedulingPolicy.measure(fair.plan());
            if (metrics.overtimeMinutes() == referenceMetrics.overtimeMinutes() && metrics.costCents() <= rules.costCeiling(referenceMetrics.costCents())
                    && (metrics.fairness().variance().compareTo(referenceMetrics.fairness().variance()) < 0
                    || metrics.fairness().variance().compareTo(referenceMetrics.fairness().variance()) == 0
                    && (metrics.costCents() < referenceMetrics.costCents() || metrics.costCents() == referenceMetrics.costCents()
                    && SchedulingPolicy.compareArrangements(fair.plan(), reference) < 0))) chosen = fair.plan();
        }
        var decision = SchedulingPolicy.compare(before, SchedulingPolicy.measure(chosen), referenceMetrics, rules);
        if (!decision.accepted()) chosen = fixture;
        var afterValidation = validate(chosen);
        var after = SchedulingPolicy.measure(chosen);
        if (after.overtimeMinutes() > before.overtimeMinutes()) throw new IllegalStateException("Benchmark increased ordinary overtime");
        long changed = 0, retimed = 0;
        for (TechRoute route : chosen.getRoutes()) for (PlanVisit visit : route.getVisits()) {
            if (!route.getId().equals(visit.getOriginalTechnicianId())) changed++;
            if (!Objects.equals(beforeValidation.arrivals().get(visit.getId()), afterValidation.arrivals().get(visit.getId()))) retimed++;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("variant", definition.variant().name()); row.put("seed", definition.seed());
        row.put("budgetMs", budgetMs);
        row.put("referencePhase", cost.statistics());
        if (fairStatistics != null) row.put("fairnessPhase", fairStatistics);
        row.put("before", before); row.put("after", after); row.put("reference", referenceMetrics);
        row.put("costCeilingCents", rules.costCeiling(referenceMetrics.costCents())); row.put("acceptanceReason", decision.reason());
        row.put("paidWaitingBefore", beforeValidation.waitingMinutes()); row.put("paidWaitingAfter", afterValidation.waitingMinutes());
        row.put("changedAssignments", changed); row.put("retimedAppointments", retimed);
        row.put("elapsedMs", (System.nanoTime() - started) / 1_000_000); row.put("cpuNanos", cpuNanos() - cpuBefore);
        row.put("peakHeapBytes", ManagementFactory.getMemoryPoolMXBeans().stream().filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                .mapToLong(pool -> Required.value(pool.getPeakUsage()).getUsed()).sum());
        row.put("violations", 0);
        return row;
    }
    static void validateBudget(long budgetMs) {
        if (budgetMs < 100 || budgetMs > 240000) throw new IllegalArgumentException("Benchmark budget must be 100..240000 ms");
    }
    private static RouteEvaluator.Result validate(DayPlan plan) {
        var independent = RouteEvaluator.evaluate(plan); var scored = DayScoreCalculator.evaluate(plan);
        if (!independent.feasible() || scored.hardPenalty() != 0 || independent.costCents() != scored.costCents()
                || !independent.arrivals().equals(scored.arrivals())) throw new IllegalStateException("Benchmark independent validation mismatch");
        return independent;
    }
    private static long cpuNanos() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        if (!(bean instanceof com.sun.management.OperatingSystemMXBean measured)) throw new IllegalStateException("Process CPU measurement unavailable");
        long value = measured.getProcessCpuTime(); if (value < 0) throw new IllegalStateException("Process CPU measurement unavailable"); return value;
    }
    private static List<String> values(String key, String fallback) {
        return Required.value(Arrays.stream(Required.value(System.getProperty(key, fallback)).split(",")).map(value -> Required.value(value.trim())).toList());
    }
    private static String fingerprint(DayPlan plan) throws Exception {
        StringBuilder raw = new StringBuilder();
        for (TechRoute route : plan.getRoutes()) raw.append(route.getId()).append(route.getShiftStart()).append(route.getShiftEnd())
                .append(route.getMaxDailyMinutes()).append(route.getMaxOvertimeMinutes()).append(new TreeSet<>(route.getQualifiedServiceIds())).append(route.getUnavailable());
        for (PlanVisit visit : plan.getVisits()) raw.append(visit.getId()).append(visit.getServiceId()).append(visit.getWindowStart())
                .append(visit.getWindowEnd()).append(visit.getDurationMinutes()).append(visit.getOriginalTechnicianId());
        raw.append(new TreeMap<>(plan.getMatrix()));
        return Required.value(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.toString().getBytes(StandardCharsets.UTF_8))));
    }
}
