"""Attribute the collection work of the run's flight recordings to their scheduler callers, with full stacks.

The evidence file keeps leaf frames only. This reads the run's raw recordings (kept outside git, in the run directory)
and writes stacks.json: per arm and phase, the share of execution samples at each named site, and the SHA-256 of every
recording read, so the table in the report can be traced to its inputs. Run once after the study:

  python stacks.py --run <repository>/.scratch/matrix-study-20261020T155528 --jfr "C:/Program Files/Java/jdk-25/bin/jfr.exe"
"""
import argparse
import json
import subprocess
from hashlib import sha256
from pathlib import Path

HERE = Path(__file__).resolve().parent
# (arm, phase): tree folder and variant in the recording name.
PROFILES = {("old", "cost"): ("o", "TABU_KOPT"), ("old-b", "fairness"): ("o", "TABU_KOPT_BINARY_VARIANCE"),
            ("baseline", "cost"): ("n", "TABU_KOPT"), ("b", "fairness"): ("n", "TABU_KOPT_BINARY_VARIANCE")}
MAP_BUILDING = ("java.util.HashMap.putVal", "java.util.LinkedHashMap.afterNodeInsertion", "java.util.HashMap.resize")
TIMEFOLD_MAPS = ("java.util.HashMap.getNode", "java.util.IdentityHashMap.get", "java.util.HashMap$TreeNode.getTreeNode")


def frames(event):
    return [f"{f['method']['type']['name'].replace('/', '.')}.{f['method']['name']}" for f in event["values"]["stackTrace"]["frames"]]


def site(stack):
    """The named site of one sample, or None. Stacks run leaf first."""
    leaf, ours = stack[0], [f for f in stack if f.startswith("dev.waterflex.")]
    first = ours[0] if ours else None
    if leaf == "java.util.ImmutableCollections$MapN.probe" and first == "dev.waterflex.scheduler.optimizer.RoadMatrix.number":
        return "identity probe (RoadMatrix.number)"
    if leaf in MAP_BUILDING and first == "dev.waterflex.scheduler.optimizer.RouteTimingSearch.segments":
        return "segment options map (RouteTimingSearch.segments)"
    if leaf == "dev.waterflex.scheduler.optimizer.RouteTimingSearch.segments":
        return "timing search's own work (RouteTimingSearch.segments)"
    if leaf in TIMEFOLD_MAPS and not any(f.startswith("dev.waterflex.scheduler.optimizer.Route") for f in stack):
        return "Timefold's own maps"
    if leaf.startswith("java.math.") and any(f.startswith("dev.waterflex.scheduler.Monetary.") for f in stack):
        return "BigDecimal money (Monetary)"
    if leaf.startswith("java.math."):
        return "BigDecimal score impacts and other decimal work"
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--run", required=True, type=Path)
    parser.add_argument("--jfr", required=True)
    options = parser.parse_args()
    out = {"run": options.run.name, "profiles": {}}
    for (arm, phase), (tree, variant) in PROFILES.items():
        paths = sorted((options.run / "profiles" / tree).glob(f"*-{variant}-*-{phase}.jfr"))
        paths = [p for p in paths if p.name.split("-")[3] == variant]
        assert len(paths) == 12, (arm, phase, len(paths))
        total, sites = 0, {}
        for path in paths:
            text = subprocess.run([options.jfr, "print", "--json", "--stack-depth", "64", "--events", "jdk.ExecutionSample", str(path)],
                                  capture_output=True, text=True, encoding="utf-8", check=True).stdout
            for event in json.loads(text)["recording"]["events"]:
                if not event["values"].get("stackTrace"):
                    continue
                total += 1
                name = site(frames(event))
                if name is not None:
                    sites[name] = sites.get(name, 0) + 1
        out["profiles"][f"{arm} {phase}"] = {"samples": total, "sitesPercent": {k: round(100 * v / total, 1) for k, v in sorted(sites.items())},
                                            "recordings": {p.name: sha256(p.read_bytes()).hexdigest() for p in paths}}
    (HERE / "stacks.json").write_text(json.dumps(out, indent=1) + "\n", encoding="utf-8")
    print(json.dumps({k: v["sitesPercent"] for k, v in out["profiles"].items()}, indent=1))


if __name__ == "__main__":
    main()
