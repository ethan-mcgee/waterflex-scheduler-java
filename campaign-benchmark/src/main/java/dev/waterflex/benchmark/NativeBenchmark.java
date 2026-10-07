package dev.waterflex.benchmark;

import ai.timefold.solver.benchmark.api.PlannerBenchmarkFactory;
import ai.timefold.solver.benchmark.config.*;
import ai.timefold.solver.benchmark.config.statistic.ProblemStatisticType;
import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.optimizer.SolverEngine;
import java.io.File;
import java.io.StringReader;
import java.util.List;

/** Public 2.6.0 Benchmarker API, one explicit sub-run and no inherited phase configuration. */
public final class NativeBenchmark {
    private NativeBenchmark() { }
    public static PlannerBenchmarkConfig configuration(String name, @org.jspecify.annotations.Nullable File directory, @org.jspecify.annotations.Nullable List<@org.jspecify.annotations.NonNull File> inputs,
            SolverEngine.Definition definition, List<@org.jspecify.annotations.NonNull ProblemStatisticType> statistics) {
        var problems = new ProblemBenchmarksConfig().withSolutionFileIOClass(SnapshotFileIO.class)
                .withInputSolutionFileList(Required.value(inputs)).withWriteOutputSolutionEnabled(true)
                .withProblemStatisticsEnabled(!statistics.isEmpty()).withProblemStatisticTypeList(statistics);
        var solver = new SolverBenchmarkConfig().withName(name).withSubSingleCount(1)
                .withSolverConfig(Required.value(SolverConfig.createFromXmlReader(new StringReader(definition.configurationXml()))))
                .withProblemBenchmarksConfig(problems);
        return Required.value(new PlannerBenchmarkConfig().withName(name).withBenchmarkDirectory(Required.value(directory))
                .withParallelBenchmarkCount("1").withWarmUpMillisecondsSpentLimit(0L).withSolverBenchmarkConfigs(solver));
    }
    public static File run(PlannerBenchmarkConfig configuration) {
        File destination=Required.value(configuration.getBenchmarkDirectory());
        if(java.nio.file.Files.exists(destination.toPath())) throw new IllegalArgumentException("Native report destination must be new");
        // Native benchmark() completes batch diagnostics/reporting before raising its failure exception.
        File reported=Required.value(PlannerBenchmarkFactory.create(configuration).buildPlannerBenchmark().benchmark());
        // In 2.6.0 the public return value is the report root, containing a timestamped batch directory.
        try(var files=java.nio.file.Files.walk(reported.toPath())) {
            var results=files.filter(path -> path.getFileName().toString().equals("plannerBenchmarkResult.xml")).toList();
            if(results.size()!=1) throw new IllegalArgumentException("Expected exactly one completed native batch report");
            return Required.value(Required.value(results.get(0).getParent()).toFile());
        } catch(java.io.IOException failure) { throw new IllegalArgumentException("Cannot locate completed native report",failure); }
    }
}
