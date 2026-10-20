package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.optimizer.DailyCalculation;
import dev.waterflex.scheduler.optimizer.DailySolver;
import dev.waterflex.scheduler.optimizer.SolverEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A study's flight recordings of each daily phase, off by default ({@code SCHEDULER_STUDY_PROFILES} names a directory
 * inside the replica). When on, every phase of every daily or repair calculation runs inside its own Java Flight
 * Recorder recording on the JDK's {@code profile} configuration, dumped as {@code <proposalId>-<phase>.jfr}: the
 * same recording the workstation harness makes around each solve (SolverBenchmark), so the two instruments profile
 * the same events. The study receipt names each phase's file and the thread the phases ran on; the summarizer keeps
 * that thread's samples, since a recording is JVM-wide and two queued calculations overlap. Recording is the
 * embedded calculation's: a remote calculation mode refuses to start with profiles on rather than record nothing.
 * Nothing here changes a proposal; the JDK's own sampling costs about one percent of the search.
 */
@Component
public final class StudyProfiles {
    /** Profiles off: the production default, and the wiring of tests that construct the proposals directly. */
    public static final StudyProfiles OFF = new StudyProfiles("", "EMBEDDED");
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(StudyProfiles.class));
    private final @Nullable Path directory;

    @Autowired
    public StudyProfiles(@Value("${scheduler.study.profiles:}") String directory, @Value("${scheduler.calculation.mode:EMBEDDED}") String mode) {
        if (directory.isBlank()) { this.directory = null; return; }
        if (!mode.equals("EMBEDDED"))
            throw new IllegalStateException("Study profiles record the embedded calculation; calculation mode " + mode + " runs it elsewhere");
        Path path = Required.value(Path.of(directory));
        try { Files.createDirectories(path); }
        catch (IOException failure) { throw new IllegalStateException("Cannot create the study profiles directory " + path, failure); }
        this.directory = path;
        LOG.warn("Study profiles are on: every daily phase is flight-recorded into {}", path);
    }

    public boolean enabled() { return directory != null; }

    /** A recorder for one calculation's phases when profiles are on, else null: the calculation then runs unrecorded. */
    public @Nullable Recorded recorder(String proposalId) {
        Path path = directory;
        return path == null ? null : new Recorded(path, proposalId);
    }

    /** One calculation's recordings: each phase's file in the directory, and the thread the phases ran on. */
    public static final class Recorded implements DailyCalculation.Recorder {
        private final Path directory;
        private final String proposalId;
        private final Map<String, String> files = new LinkedHashMap<>();
        private @Nullable String thread;

        Recorded(Path directory, String proposalId) { this.directory = directory; this.proposalId = proposalId; }

        @Override
        public SolverEngine.Result record(String phase, Supplier<SolverEngine.Result> solve) {
            String name = proposalId + "-" + phase + ".jfr";
            String current = Required.value(Thread.currentThread().getName());
            String seen = thread;
            if (seen != null && !seen.equals(current))
                throw new IllegalStateException("The phases of " + proposalId + " ran on two threads (" + seen + ", " + current + ")");
            thread = current;
            try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
                recording.setName(name);
                recording.start();
                SolverEngine.Result result = Required.value(solve.get());
                recording.stop();
                recording.dump(directory.resolve(name));
                files.put(phase, name);
                return result;
            } catch (IOException | ParseException failure) {
                throw new IllegalStateException("Could not record the " + phase + " phase of " + proposalId, failure);
            }
        }

        /** The receipt's view: the thread and every phase's file, or why the recordings cannot stand for the phases. */
        Map<String, Object> receipt(List<DailySolver.Phase> phases) {
            Map<String, Object> entry = new LinkedHashMap<>();
            String ran = thread;
            List<String> missing = new java.util.ArrayList<>();
            for (DailySolver.Phase phase : phases) if (!files.containsKey(Required.value(phase).name())) missing.add(phase.name());
            // A phase that ran without a recording, or recordings without a thread, cannot be given one: name the gap.
            if (ran == null || !missing.isEmpty()) { entry.put("unavailable", ran == null ? "NO_PHASE_RECORDED" : "PHASES_NOT_RECORDED"); return entry; }
            entry.put("thread", ran);
            entry.put("recordings", Map.copyOf(files));
            return entry;
        }
    }
}
