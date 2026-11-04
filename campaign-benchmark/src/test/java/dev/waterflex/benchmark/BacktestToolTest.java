package dev.waterflex.benchmark;

import static org.junit.jupiter.api.Assertions.*;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.optimizer.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BacktestToolTest {
    @TempDir @org.jspecify.annotations.Nullable Path directory;
    private Path directory() { return Required.value(directory); }

    static DailyDataset dataset() throws Exception {
        return DailyDataset.parse(DailyDataset.seal(Required.value(Files.readString(Path.of("../scheduler-service/src/test/resources/daily-dataset-v1.json")))));
    }
    private static String artifact(Path path) throws Exception {
        String json = Required.value(Files.readString(path));
        return "{\"path\":" + CalculationJson.write(Required.value(path.toString())) + ",\"sha256\":\"" + CalculationJson.hash(json) + "\"}";
    }

    @Test void theDatasetsOwnAssignmentReadsFeasibleWithNoViolations() throws Exception {
        var dataset = dataset();
        var plan = BacktestTool.Plan.of(dataset.toDayPlan());
        var row = BacktestTool.score(dataset, plan, "plan");
        var independent = RouteEvaluator.evaluate(dataset.toDayPlan());
        assertTrue(row.feasible()); assertTrue(row.routesHold());
        assertEquals(0, row.hardPenalty()); assertEquals(0, row.latenessSeconds()); assertEquals(0, row.lateVisits());
        assertEquals(0, row.availabilitySeconds()); assertEquals(0, row.pastShiftMinutes()); assertEquals(0, row.unreachableLegs());
        assertEquals(independent.costCents(), row.costCents()); assertEquals(independent.paidMinutes(), row.paidMinutes());
        assertEquals(independent.driveMinutes(), row.driveMinutes()); assertEquals(independent.meters(), row.meters());
        assertEquals(dataset.contentHash(), row.datasetHash());
        assertEquals(dataset.toDayPlan().getRoutes().size(), row.routes().size());
        long visits = 0;
        for (var route : row.routes()) { visits += route.visits(); assertEquals(route.visits(), route.arrivals().size()); }
        assertEquals(dataset.toDayPlan().getVisits().size(), visits);
        assertEquals(SchedulingPolicy.measure(dataset.toDayPlan()).fairness().variance(), row.fairnessVariance());
        assertTrue(row.maximumUtilization().compareTo(BigDecimal.ZERO) > 0);
    }

    /** Two visits a 10 minute leg apart; the second window decides whether the route holds. */
    private static DayPlan day(java.time.Instant secondWindowStart, java.time.Instant secondWindowEnd) {
        TechRoute route = new TechRoute("home", at("09:00:00"), at("17:00:00"), 480, 0, Required.value(java.util.Set.of("service")));
        PlanVisit first = new PlanVisit("first", "service", at("09:00:00"), at("12:00:00"), 60, "home", at("09:00:00"));
        PlanVisit second = new PlanVisit("second", "service", secondWindowStart, secondWindowEnd, 60, "home", secondWindowStart);
        route.getVisits().addAll(Required.value(List.of(first, second)));
        Map<String, DayPlan.RoadLeg> matrix = new java.util.HashMap<>();
        for (String from : List.of("home", "first", "second")) for (String to : List.of("first", "second", "home:return"))
            if (!from.equals(to)) matrix.put(from + ">" + to, new DayPlan.RoadLeg(600, 3000));
        return new DayPlan(Required.value(List.of(route)), Required.value(List.of(first, second)), matrix, 30, 45, 0.67, 0, 0);
    }
    @Test void dayJobSealsSolvesAndReadsEveryPlanInOneRun() throws Exception {
        var dataset = dataset();
        Path day = Required.value(directory().resolve("day")), out = Required.value(directory().resolve("out"));
        Files.createDirectories(day);
        // The importer's drafts: the fixture with its hash cleared, and the same day with longer actual durations.
        var draft = (com.fasterxml.jackson.databind.node.ObjectNode) CalculationJson.tree(dataset.json(DailyDataset.Encoding.SPARSE));
        draft.putNull("contentHash");
        Files.writeString(Required.value(day.resolve("facts.json")), CalculationJson.write(draft));
        for (var visit : draft.withArray("visits")) ((com.fasterxml.jackson.databind.node.ObjectNode) visit).put("durationMinutes", 90);
        Files.writeString(Required.value(day.resolve("facts-actual-durations.json")), CalculationJson.write(draft));
        var planned = BacktestTool.Plan.of(dataset.toDayPlan());
        Files.writeString(Required.value(day.resolve("as-planned.plan.json")), CalculationJson.write(Required.value(planned)));
        Files.writeString(Required.value(day.resolve("as-executed.plan.json")), CalculationJson.write(Required.value(planned)));
        String job = "{\"version\":1,\"kind\":\"backtest-day\",\"directory\":" + CalculationJson.write(Required.value(day.toString())) + ",\"output\":" + CalculationJson.write(Required.value(out.toString()))
                + ",\"variant\":\"TABU_KOPT\",\"seed\":17,\"costMs\":700,\"fairnessMs\":300,\"policy\":" + CalculationJson.write(SchedulingPolicy.Rules.defaults()) + "}";
        var receipt = CalculationJson.tree(CalculationJson.write(DatasetTool.execute(CalculationJson.tree(job))));
        var files = receipt.get("files");
        for (String name : List.of("as-planned.row.json", "as-planned-actual.row.json", "as-executed.row.json", "as-executed-actual.row.json", "solve.json", "solver.plan.json", "solver.row.json", "solver-actual.row.json")) {
            assertTrue(Files.exists(out.resolve(name)), name);
            assertEquals(CalculationJson.hash(Required.value(Files.readString(out.resolve(name)))), Required.value(files.get(name)).asText(), name);
        }
        assertTrue(Files.exists(out.resolve("facts.sealed.json")) && Files.exists(out.resolve("facts-actual-durations.sealed.json")));
        assertEquals(dataset.contentHash(), Required.value(receipt.get("bookedHash")).asText());
        var executedActual = CalculationJson.read(Required.value(Files.readString(out.resolve("as-executed-actual.row.json"))), BacktestTool.Row.class);
        var executed = CalculationJson.read(Required.value(Files.readString(out.resolve("as-executed.row.json"))), BacktestTool.Row.class);
        assertTrue(executedActual.paidMinutes() > executed.paidMinutes(), "the actual durations lengthen the day");
        assertNotEquals(executed.datasetHash(), executedActual.datasetHash());
        var plannedRow = CalculationJson.read(Required.value(Files.readString(out.resolve("as-planned.row.json"))), BacktestTool.Row.class);
        var plannedActual = CalculationJson.read(Required.value(Files.readString(out.resolve("as-planned-actual.row.json"))), BacktestTool.Row.class);
        assertEquals(plannedRow.planHash(), plannedActual.planHash(), "the same dispatcher order, played forward");
        assertEquals(executedActual.datasetHash(), plannedActual.datasetHash(), "over the same actual-duration facts");
        // A second run reuses the seals and refuses to overwrite a result it already wrote.
        assertThrows(IllegalArgumentException.class, () -> DatasetTool.execute(CalculationJson.tree(job)));
    }

    private static java.time.Instant at(String time) { return Required.value(java.time.Instant.parse("2026-09-21T" + time + "Z")); }

    @Test void aPlanThatDoesNotHoldIsReadInNumbersNotRefused() {
        // The second window closes before the first visit can end: no placement holds, the walk still times every stop.
        DayPlan late = day(at("09:00:00"), at("09:30:00"));
        var row = assertDoesNotThrow(() -> BacktestTool.score(late, "facts", "late"));
        assertFalse(row.feasible()); assertFalse(row.routesHold());
        assertTrue(row.hardPenalty() > 0);
        assertTrue(row.latenessSeconds() > 0, row.toString());
        assertEquals(1, row.lateVisits(), row.toString());
        assertEquals(0, row.pastShiftMinutes());
        assertTrue(row.paidMinutes() > 0 && row.driveMinutes() == 30, "the relaxed walk still times the day: " + row);
        assertEquals(2, Required.value(row.routes().getFirst()).arrivals().size());
        assertEquals(row.hardPenalty(), row.routes().stream().mapToLong(route -> route.qualifications() + route.unavailableRoads()
                + route.latenessSeconds() + route.availabilitySeconds() + route.capacityMinutes()).sum());
        assertTrue(row.fairnessVariance().compareTo(BigDecimal.ZERO) == 0, "one technician, no spread");
        // Work past the shift end reads as whole minutes past the shift.
        DayPlan evening = day(at("16:50:00"), at("18:00:00"));
        var past = BacktestTool.score(evening, "facts", "evening");
        assertFalse(past.feasible());
        assertTrue(past.availabilitySeconds() > 0 && past.pastShiftMinutes() >= 1, past.toString());
        assertEquals(0, past.latenessSeconds());
        // A missing road is a count, with the arrivals left out rather than invented.
        DayPlan holds = day(at("13:00:00"), at("15:00:00"));
        assertTrue(BacktestTool.score(holds, "facts", "holds").feasible());
        var withoutRoad = new java.util.HashMap<>(holds.getMatrix());
        withoutRoad.remove("first>second");
        var missing = new DayPlan(holds.getRoutes(), holds.getVisits(), withoutRoad, Required.value(java.util.Set.of("first>second")), holds.getRegularHourly(),
                holds.getOvertimeHourly(), holds.getMileagePerMile(), holds.getTravelBufferPct(), holds.getTravelBufferMinutes(), holds.getArrivalBuffer());
        var unreachable = BacktestTool.score(missing, "facts", "missing");
        assertFalse(unreachable.feasible());
        assertEquals(1, unreachable.unreachableLegs());
        assertEquals(Map.of(), Required.value(unreachable.routes().getFirst()).arrivals());
    }

    @Test void scoreJobWritesTheRowAndRefusesAChangedArtifact() throws Exception {
        var dataset = dataset();
        Path facts = Required.value(directory().resolve("facts.json")), plan = Required.value(directory().resolve("plan.json")), output = Required.value(directory().resolve("row.json"));
        Files.writeString(facts, dataset.json(DailyDataset.Encoding.SPARSE));
        Files.writeString(plan, CalculationJson.write(Required.value(BacktestTool.Plan.of(dataset.toDayPlan()))));
        String job = "{\"version\":1,\"kind\":\"backtest-score\",\"dataset\":" + artifact(facts) + ",\"plan\":" + artifact(plan)
                + ",\"output\":" + CalculationJson.write(Required.value(output.toString())) + "}";
        var result = DatasetTool.execute(CalculationJson.tree(job));
        assertTrue(Files.exists(output));
        var row = CalculationJson.read(Required.value(Files.readString(output)), BacktestTool.Row.class);
        assertTrue(row.feasible());
        assertEquals(CalculationJson.hash(Required.value(Files.readString(plan))), row.planHash());
        assertTrue(CalculationJson.write(result).contains("\"kind\":\"backtest-score\""));
        String tampered = Required.value(job.replace(CalculationJson.hash(Required.value(Files.readString(plan))), "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> DatasetTool.execute(CalculationJson.tree(tampered)));
    }

    @Test void solveRunsTheProductionCalculationAndWritesARestorablePlan() throws Exception {
        var dataset = dataset();
        Path facts = Required.value(directory().resolve("facts.json")), output = Required.value(directory().resolve("solve.json")), planOutput = Required.value(directory().resolve("solver.plan.json"));
        Files.writeString(facts, dataset.json(DailyDataset.Encoding.SPARSE));
        String policy = CalculationJson.write(SchedulingPolicy.Rules.defaults());
        String job = "{\"version\":1,\"kind\":\"backtest-solve\",\"dataset\":" + artifact(facts) + ",\"variant\":\"TABU_KOPT\",\"seed\":17,\"costMs\":700,\"fairnessMs\":300,\"policy\":"
                + policy + ",\"output\":" + CalculationJson.write(Required.value(output.toString())) + ",\"planOutput\":" + CalculationJson.write(Required.value(planOutput.toString())) + "}";
        DatasetTool.execute(CalculationJson.tree(job));
        var solved = CalculationJson.read(Required.value(Files.readString(output)), BacktestTool.Solved.class);
        assertEquals(dataset.contentHash(), solved.datasetHash());
        assertEquals("TABU_KOPT", solved.variant()); assertEquals(17, solved.seed());
        assertTrue(solved.solveMs() >= 700, "both phases ran: " + solved.solveMs());
        assertEquals(List.of("REFERENCE", "FAIRNESS"), solved.diagnostics().phases().stream().map(phase -> Required.value(phase).name()).toList());
        assertTrue(solved.candidate().feasible()); assertEquals(0, solved.candidate().overtimeMinutes());
        assertTrue(solved.baseline().feasible());
        var plan = CalculationJson.read(Required.value(Files.readString(planOutput)), BacktestTool.Plan.class);
        assertEquals(solved.plan(), plan);
        var restored = DailyCalculation.restore(dataset.toDayPlan(), plan.routes(), plan.unassigned());
        assertEquals(solved.candidate().costCents(), BacktestTool.score(restored, dataset.contentHash(), "again").costCents());
        assertEquals(Map.of(), CalculationJson.read(CalculationJson.write(new BacktestTool.Plan(1, Required.value(Map.of()), Required.value(List.of()))), BacktestTool.Plan.class).routes());
    }

}
