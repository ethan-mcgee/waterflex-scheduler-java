package dev.waterflex.benchmark;

import ai.timefold.solver.core.api.domain.solution.SolutionFileIO;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.optimizer.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Stateless native input reader and proposal writer. Imports always require the immutable caller input. */
public final class SnapshotFileIO implements SolutionFileIO<DayPlan> {
    public record Input(int version, String datasetJson, @Nullable FrozenTarget target) {
        public Input { if (version != 1) throw new IllegalArgumentException("Snapshot version"); Required.value(datasetJson); }
    }
    public record Proposal(int version, String factsHash, Map<String,List<String>> routes,
            List<String> unassigned, DayPlan.Mode mode, @Nullable String score) {
        public Proposal {
            if (version != 1) throw new IllegalArgumentException("Proposal version");
            Required.value(factsHash); Required.value(mode);
            Map<String,List<String>> copy=new TreeMap<>();
            routes.forEach((id,visits) -> copy.put(Required.value(id),Required.value(List.copyOf(Required.value(visits)))));
            routes = Required.value(Map.copyOf(copy)); unassigned = Required.value(List.copyOf(unassigned));
        }
    }
    @Override public String getInputFileExtension() { return "json"; }
    @Override public String getOutputFileExtension() { return "proposal.json"; }
    @Override public DayPlan read(@Nullable File file) {
        Input input = CalculationJson.read(readText(Required.value(file)),Input.class);
        DailyDataset dataset = DailyDataset.parse(input.datasetJson());
        return input.target() == null ? dataset.toDayPlan() : Required.value(input.target()).bind(dataset);
    }
    @Override public void write(@Nullable DayPlan plan, @Nullable File file) { writeText(Required.value(file),CalculationJson.write(proposal(Required.value(plan)))); }
    public static Proposal proposal(DayPlan plan) {
        plan.getFacts().validateEntities(plan,false);
        Map<String,List<String>> routes = new TreeMap<>();
        for (var route : plan.getRoutes()) {
            List<String> visits=new java.util.ArrayList<>();
            for (PlanVisit visit : route.getVisits()) visits.add(visit.getId());
            routes.put(route.getId(),visits);
        }
        var score = plan.getScore();
        return new Proposal(1,CalculationJson.hash(CalculationJson.write(plan.getFacts())),routes,plan.getUnassignedVisitIds(),plan.getMode(),score == null ? null : score.toString());
    }
    public static DayPlan importProposal(DayPlan input, Proposal output) {
        if (!output.factsHash().equals(CalculationJson.hash(CalculationJson.write(input.getFacts()))))
            throw new IllegalArgumentException("Proposal fact revision mismatch");
        DayPlan restored = DailyCalculation.restore(input,output.routes(),output.unassigned());
        if (input.getMode() != output.mode()) throw new IllegalArgumentException("Proposal mode changed");
        // Score text is retained as evidence only. Policy and cost are independently recalculated.
        return restored;
    }
    static String readText(@Nullable File inputFile) {
        File file=Required.value(inputFile);
        try {
            if (Files.size(file.toPath()) > CalculationJson.MAX_BYTES) throw new IllegalArgumentException("Oversized snapshot");
            return Required.value(Files.readString(file.toPath()));
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("Cannot read snapshot",failure); }
    }
    static void writeText(@Nullable File outputFile,String text) {
        File file=Required.value(outputFile);
        try { Files.writeString(file.toPath(),text,StandardOpenOption.CREATE_NEW); }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("Cannot retain snapshot",failure); }
    }
}
