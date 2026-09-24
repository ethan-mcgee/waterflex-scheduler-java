"""Create reviewable benchmark-only source artifacts without production feature bypasses.

Run from the repository root. Builds remain separate, using the emitted source and manifest.
Every replacement is exact and fails closed when the implementation changes.
"""
import argparse
import difflib
import hashlib
import io
import json
from pathlib import Path
import subprocess
import zipfile


parser = argparse.ArgumentParser()
parser.add_argument("stage", choices=["policy-insertion", "snapshot-insertion", "bounded-early"])
parser.add_argument("--revision", default="HEAD")
args = parser.parse_args()
root = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
revision = subprocess.check_output(["git", "rev-parse", args.revision + "^{commit}"], text=True).strip()
destination = root / ".scratch" / ("ablation-" + args.stage + "-" + revision[:7])
destination.mkdir(parents=True, exist_ok=False)
archive = subprocess.check_output(["git", "archive", "--format=zip", revision])
with zipfile.ZipFile(io.BytesIO(archive)) as files:
    for member in files.infolist():
        target = (destination / member.filename).resolve()
        if not target.is_relative_to(destination.resolve()):
            raise ValueError("Unsafe archive member")
    files.extractall(destination)

base = Path("scheduler-service/src/main/java/dev/waterflex/scheduler")
changes = []


def replace(relative, old, new):
    path = destination / base / relative
    before = path.read_text(encoding="utf-8")
    if before.count(old) != 1:
        raise ValueError("Expected exactly one source anchor: " + relative + ": " + old[:90])
    after = before.replace(old, new)
    path.write_text(after, encoding="utf-8", newline="\n")
    changes.append("".join(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                                               fromfile=str(base / relative), tofile=str(base / relative))))


# Both independent validation and incremental scoring must use the same timing semantics.
# Interval enumeration, exclusive promises, return travel and technician limits stay intact.
evaluator = (destination / base / "optimizer/RouteEvaluator.java").read_text(encoding="utf-8")
begin = evaluator.index("            // Backward latest-arrival bounds")
end = evaluator.index("            Instant actualDeparture", begin)
replace("optimizer/RouteEvaluator.java", evaluator[begin:end],
        "            long delay = 0; // Benchmark ablation: earliest feasible segment departure.\n")
replace("optimizer/RouteTimingSearch.java", "long delay = Math.max(0, Math.min(waiting, slack));",
        "long delay = 0; // Benchmark ablation: earliest feasible segment departure.")

if args.stage == "policy-insertion":
    path = destination / base / "BookingEvaluation.java"
    text = path.read_text(encoding="utf-8")
    start = text.index("    private RouteEvaluator.Result aggregate(")
    end = text.index("    private Baseline baseline(", start)
    replace("BookingEvaluation.java", text[start:end],
            "    private RouteEvaluator.Result aggregate(Arrangement arrangement, Map<String, Visit> facts, boolean confirmedOnly, boolean timeline) {\n"
            "        checkpoint.run(); coverage(arrangement, facts); baseline(confirmedOnly); evaluations++;\n"
            "        return RouteEvaluator.evaluate(day.plan(arrangement, facts, rates, confirmedOnly));\n"
            "    }\n\n")
    replace("SnapshotRouting.java", "            add(pairs, date, day, points, request.jobId(), request.jobId());",
            "            // Benchmark ablation: complete matrices per route, matching original insertion scope.\n"
            "            Arrangement actual = day.actualArrangement();\n"
            "            for (String technician : day.technicians().keySet()) {\n"
            "                Set<String> routePoints = new HashSet<>(Required.value(day.baseline().routes().get(technician)));\n"
            "                routePoints.addAll(Required.value(actual.routes().get(technician)));\n"
            "                routePoints.add(technician); routePoints.add(technician + \":return\"); routePoints.add(request.jobId());\n"
            "                for (String from : routePoints) for (String to : routePoints) {\n"
            "                    SearchDeadline.checkpoint();\n"
            "                    add(pairs, date, day, points, Required.value(from), Required.value(to));\n"
            "                }\n"
            "            }\n"
            "            add(pairs, date, day, points, request.jobId(), request.jobId());")

# These artifacts cannot start against a normal database or without the benchmark profile.
guard = '''package dev.waterflex.scheduler;
import java.util.Arrays;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
@Component
final class AblationArtifactGuard implements InitializingBean {
    private final Environment environment;
    AblationArtifactGuard(Environment environment) { this.environment = environment; }
    @Override public void afterPropertiesSet() {
        String url = Required.value(environment.getProperty("spring.datasource.url"), "ablation database");
        if (!Arrays.asList(environment.getActiveProfiles()).contains("benchmark")
                || !url.matches("jdbc:postgresql://(?:localhost|127\\\\.0\\\\.0\\\\.1):[0-9]+/waterflex_test\\\\?currentSchema=benchmark_[a-z0-9_]+"))
            throw new IllegalStateException("Benchmark-only artifact requires an isolated local waterflex_test benchmark schema");
        if (!"true".equals(environment.getProperty("booking.reservations.enabled")))
            throw new IllegalStateException("Ablations retain durable reservation protection");
    }
}
'''
(destination / base / "AblationArtifactGuard.java").write_text(guard, encoding="utf-8", newline="\n")
test = '''package dev.waterflex.scheduler.optimizer;
import dev.waterflex.scheduler.Required;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AblationTimingTest {
    @Test void bothEvaluatorsRetainEarlyWaitingAndValidateTheSamePromises() {
        Instant start = Required.value(Instant.parse("2026-09-21T08:00:00Z"));
        var route = new TechRoute("t", start, Required.value(start.plusSeconds(8 * 3600)), 480, 0, Required.value(Set.of("s")));
        var first = new PlanVisit("a", "s", start, Required.value(start.plusSeconds(7200)), 30, "t", start);
        var second = new PlanVisit("b", "s", Required.value(start.plusSeconds(3600)), Required.value(start.plusSeconds(10800)), 30, "t", Required.value(start.plusSeconds(3600)));
        route.getVisits().addAll(Required.value(List.of(first, second)));
        Map<String, DayPlan.RoadLeg> roads = new HashMap<>();
        for (String from : List.of("t", "a", "b")) for (String to : List.of("a", "b", "t:return"))
            roads.put(from + ">" + to, new DayPlan.RoadLeg(0, 0));
        var plan = new DayPlan(Required.value(List.of(route)), Required.value(List.of(first, second)), roads, 30, 45, 0, 0, 0);
        var independent = RouteEvaluator.evaluate(plan);
        var scored = DayScoreCalculator.evaluate(plan);
        assertTrue(independent.feasible()); assertEquals(0, scored.hardPenalty());
        assertEquals(90, independent.paidMinutes()); assertEquals(30, independent.waitingMinutes());
        assertEquals(start, Required.value(independent.segments().get("t")).getFirst().departure());
        assertEquals(independent.arrivals(), scored.arrivals());
        assertEquals(independent.paidMinutes(), scored.paidMinutes());
        assertEquals(independent.costCents(), scored.costCents());
    }
}
'''
(destination / "scheduler-service/src/test/java/dev/waterflex/scheduler/optimizer/AblationTimingTest.java").write_text(test, encoding="utf-8", newline="\n")
patch = "".join(changes)
(destination / "ablation.patch").write_text(patch, encoding="utf-8", newline="\n")
manifest = {
    "revision": revision, "stage": args.stage,
    "generatorSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    "patchSha256": hashlib.sha256(patch.encode()).hexdigest(),
    "guardSha256": hashlib.sha256(guard.encode()).hexdigest(),
    "timingTestSha256": hashlib.sha256(test.encode()).hexdigest(),
    "boundedSearch": args.stage == "bounded-early", "flexibleDeparture": False,
    "fullMatrixAndFullEvaluation": args.stage == "policy-insertion",
    "retainedSafetyInfrastructure": ["immutable snapshots", "durable common reservations", "deadlines", "independent validation"],
    "limitation": "Reconstructed feature ablation, not a historical intermediate implementation. Shared safety infrastructure is retained in every policy variant."
}
(destination / "ablation.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
print(destination)
