package dev.waterflex.scheduler.api;

import static org.junit.jupiter.api.Assertions.*;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.optimizer.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StudyProfilesTest {
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

    private static DailyCalculation.Result calculate(DayPlan baseline, DailySolver solver, DailyCalculation.@NonNull Recorder recorder) {
        return DailyOperation.execute(new SearchAdmission(1, 16), Required.value(Duration.ofSeconds(20)),
                () -> DailyCalculation.recording(recorder, () -> DailyCalculation.run(baseline, SchedulingPolicy.Rules.defaults(), solver,
                        new DailyCalculation.Allocation(300, 300, 600, true))), _ -> { });
    }

    private static List<String> names(DailyCalculation.Result calculation) {
        List<String> names = new ArrayList<>();
        for (DailySolver.Phase phase : calculation.diagnostics().phases()) names.add(Required.value(phase).name());
        return names;
    }

    private static String temporary() { return Required.value(System.getProperty("java.io.tmpdir")); }

    /** Each phase runs inside its own flight recording, dumped under the proposal's name, and the receipt names them and the thread. */
    @Test void everyPhaseIsRecordedAndNamedInTheReceipt(@TempDir Path directory) {
        var profiles = new StudyProfiles(Required.value(directory.toString()), "EMBEDDED");
        assertTrue(profiles.enabled());
        var recorder = Required.value(profiles.recorder("p1"));
        var solver = new DailySolver("TABU_KOPT", 17);
        DayPlan baseline = plan();
        List<String> threads = new ArrayList<>();
        // The operation runs the phases on a thread of its own; the receipt must name that thread, not the caller's.
        var calculation = calculate(baseline, solver, (phase, solve) -> { threads.add(Required.value(Thread.currentThread().getName())); return recorder.record(phase, solve); });
        List<String> phases = names(calculation);
        assertEquals(List.of("REFERENCE", "FAIRNESS"), phases);
        for (String phase : phases) {
            Path file = directory.resolve("p1-" + phase + ".jfr");
            assertTrue(Files.isRegularFile(file), phase + " recorded");
            assertTrue(Required.value(file.toFile()).length() > 0, phase + " recording has content");
        }
        var receipt = StudyReceipts.receipt("p1", Required.value(LocalDate.of(2030, 1, 1)), solver, SchedulingPolicy.Rules.defaults(), baseline, calculation, recorder);
        var profile = (Map<?, ?>) Required.value(receipt.get("profile"));
        assertEquals(2, threads.size());
        assertEquals(threads.getFirst(), profile.get("thread"));
        assertNotEquals(Thread.currentThread().getName(), profile.get("thread"));
        assertEquals(Map.of("REFERENCE", "p1-REFERENCE.jfr", "FAIRNESS", "p1-FAIRNESS.jfr"), profile.get("recordings"));
    }

    /** The calculation names each phase to the bound recorder in order; unbound, it only solves. */
    @Test void theCalculationNamesItsPhasesToTheRecorder() {
        List<String> seen = new ArrayList<>();
        var solver = new DailySolver("TABU_KOPT", 17);
        var calculation = calculate(plan(), solver, (phase, solve) -> { seen.add(phase); return Required.value(solve.get()); });
        assertEquals(List.of("REFERENCE", "FAIRNESS"), seen);
        assertEquals(seen, names(calculation));
        assertEquals(DailyCalculation.NONE, DailyCalculation.recorder(), "unbound outside a study");
    }

    /** A receipt whose phases outrun its recordings names the gap, and a receipt without a profile carries none. */
    @Test void missingRecordingsAreNamedNotInvented() {
        var solver = new DailySolver("TABU_KOPT", 17);
        DayPlan baseline = plan();
        var calculation = calculate(baseline, solver, DailyCalculation.NONE);
        var unrecorded = Required.value(new StudyProfiles(temporary(), "EMBEDDED").recorder("p2"));
        assertEquals(Map.of("unavailable", "NO_PHASE_RECORDED"), unrecorded.receipt(calculation.diagnostics().phases()));
        var receipt = StudyReceipts.receipt("p2", Required.value(LocalDate.of(2030, 1, 1)), solver, SchedulingPolicy.Rules.defaults(), baseline, calculation);
        assertFalse(receipt.containsKey("profile"));
    }

    /** Off is the default; a remote calculation cannot be profiled here and refuses to start with profiles on. */
    @Test void profilesAreOffUnlessADirectoryIsNamedAndRefuseARemoteCalculation() {
        assertFalse(StudyProfiles.OFF.enabled());
        assertNull(StudyProfiles.OFF.recorder("p3"));
        assertThrows(IllegalStateException.class, () -> new StudyProfiles(temporary(), "REMOTE"));
    }
}
