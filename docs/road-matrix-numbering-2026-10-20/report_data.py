"""Load and validate the road matrix numbering study evidence (PR 187) before any table or chart reads it.

Run directly to validate only. No network, services or benchmark execution.
"""
import json
from decimal import Decimal
from pathlib import Path
from statistics import median

HERE = Path(__file__).resolve().parent
EVIDENCE = HERE.parent / "evidence" / "solver-matrix-2026-10-20.json"
ARMS = ("old", "old-b", "baseline", "b", "c", "load-balance")
FIX = {"baseline": "old", "b": "old-b"}
MEASURES = ("variance", "maximumUtilization", "utilizationSpread", "absoluteDeviation", "loadBalance")
ACCEPTED_ON = {"load-balance": "loadBalance"}
FLEETS = (10, 30, 50)
REPS = (1, 2)
PHASES = {"referencePhase": "referenceMovesPerSecond", "fairnessPhase": "fairnessMovesPerSecond"}
REVISIONS = {"o": "d571d834f4eb9fd829ea9406ff5a948ac929b4b8", "n": "ed97d8b6c6d4c5956e60d972a8ed99cacd2b596c"}
PROFILE_GROUPS = ("decimal: variance", "variance (non-decimal)", "decimal: score impacts", "decimal: money", "collections", "engine",
                  "scheduler code", "score engine", "time", "other")


def check(condition, message):
    if not condition:
        raise ValueError(message)


def case_of(run):
    return run["technicians"], run["workload"], run["seed"]


def index(runs):
    """(arm, case, rep) to run, with every combination required exactly once."""
    out = {}
    for run in runs:
        key = (run["arm"], case_of(run), run["rep"])
        check(key not in out, f"duplicate run {key}")
        out[key] = run
    return out


def cases(by):
    return sorted({key[1] for key in by})


def case_mean(by, arm, case, field):
    """The mean over both repetitions of a final measure (Decimal) or a number in the run."""
    values = [Decimal(by[(arm, case, rep)]["measures"][field]) if field in MEASURES else Decimal(by[(arm, case, rep)][field]) for rep in REPS]
    return sum(values) / len(values)


def reference_mean(by, arm, case, field):
    return sum(Decimal(by[(arm, case, rep)]["referenceMeasures"][field]) for rep in REPS) / len(REPS)


def ratios(by, arm, against, rate, fleet=None):
    """Paired moves-per-second ratios of {@code arm} over {@code against}, case by case and repetition by repetition."""
    return [by[(arm, case, rep)][rate] / by[(against, case, rep)][rate] for case in cases(by) if fleet is None or case[0] == fleet for rep in REPS]


def beyond_noise(by, arm, against, field):
    """Cases where the arm's mean is above, and below, the other arm's by more than that arm's own spread between repetitions."""
    worse = better = 0
    for case in cases(by):
        values = [Decimal(by[(against, case, rep)]["measures"][field]) if field in MEASURES else Decimal(by[(against, case, rep)][field]) for rep in REPS]
        gap = case_mean(by, arm, case, field) - case_mean(by, against, case, field)
        noise = max(values) - min(values)
        worse += gap > noise
        better += gap < -noise
    return worse, better


def load():
    document = json.loads(EVIDENCE.read_text(encoding="utf-8-sig"))
    manifest = document["manifest"]
    check(manifest["smoke"] is False and manifest["repetitions"] == 2 and manifest["budgetMs"] == 15000 == document["budgetMs"], "not the full study run")
    check(manifest["revisions"] == REVISIONS, "unexpected revisions")
    check([int(s) for s in manifest["sizes"]] == list(FLEETS), "fleets 10, 30 and 50 expected")
    paired = document["paired"]
    by = index(paired["runs"])
    all_cases = cases(by)
    check(len(all_cases) == 36 == paired["cases"] and paired["repetitions"] == 2, "36 cases and 2 repetitions expected")
    check(len(by) == len(ARMS) * 36 * 2 and {k[0] for k in by} == set(ARMS), "every arm, case and repetition expected")
    check(paired["violations"] == 0 and paired["outsideCeiling"] == [], "no violation and nothing outside a ceiling")
    for run in paired["runs"]:
        check(run["costCents"] <= run["costCeilingCents"], "run over its ceiling")
        check(run["referenceCostCents"] <= run["costCeilingCents"], "reference over its own ceiling")
        check(sorted(run["measures"]) == sorted(MEASURES) == sorted(run["referenceMeasures"]), "every measure expected")
    # The fix: paired speedups overall and by fleet, recomputed from the runs.
    for arm, old in FIX.items():
        entry = paired["fix"][arm]
        check(entry["against"] == old, arm + ": fix pair")
        for phase, rate in PHASES.items():
            values = ratios(by, arm, old, rate)
            check(abs(round(median(values), 3) - entry[phase + "Speedup"]["median"]) <= .001 and len(values) == entry[phase + "Speedup"]["pairs"], f"fix {arm} {phase}")
            for fleet in FLEETS:
                check(abs(round(median(ratios(by, arm, old, rate, fleet)), 3) - entry["byFleet"][phase][str(fleet)]["median"]) <= .001, f"fix {arm} {phase} {fleet}")
        differences = [float(case_mean(by, arm, c, "costCents") - case_mean(by, old, c, "costCents")) for c in all_cases]
        check(median(differences) == entry["cost"]["medianDifferenceCents"], f"fix {arm}: cost median")
    check(paired["decision"]["fix"]["promoted"] == all(paired["fix"]["baseline"][p + "Speedup"]["median"] >= 1 for p in PHASES), "fix decision")
    # b and c against the bars.
    for arm in ("b", "c"):
        summary = paired["summary"][arm]
        values = ratios(by, arm, "baseline", "fairnessMovesPerSecond")
        check(abs(round(median(values), 3) - summary["fairnessPhaseSpeedup"]["median"]) <= .001, arm + ": speed median")
        differences = [float(case_mean(by, arm, c, "costCents") - case_mean(by, "baseline", c, "costCents")) for c in all_cases]
        check(median(differences) == summary["cost"]["medianDifferenceCents"], arm + ": cost median")
        pairs = [p for p in paired["pairs"] if p["arm"] == arm]
        check(len(pairs) == 36, arm + ": 36 pairs expected")
        beyond = sum(1 for p in pairs if Decimal(p["meanVarianceDifference"]) > Decimal(p["baselineVarianceSpread"]))
        check(beyond == summary["fairness"]["lessFairBeyondNoise"], arm + ": fairness bar count")
        check(paired["decision"][arm]["promoted"] == summary["passes"], arm + ": decision")
    # Every arm on every measure, and the acceptance reasons.
    for arm in ARMS:
        entry = paired["comparison"][arm]
        check(entry["acceptedOn"] == ACCEPTED_ON.get(arm, "variance"), arm + ": accepted measure")
        for name in MEASURES:
            values = [float(case_mean(by, arm, c, name) / case_mean(by, "baseline", c, name)) for c in all_cases if case_mean(by, "baseline", c, name) > 0]
            check(round(median(values), 3) == entry["measures"][name]["medianRatioToBaseline"], f"{arm} {name}: ratio")
        reasons = {}
        for run in paired["runs"]:
            if run["arm"] == arm:
                reasons[run["acceptanceReason"]] = reasons.get(run["acceptanceReason"], 0) + 1
        check(reasons == entry["acceptance"], arm + ": acceptance reasons")
    for name, profile in document["profiles"].items():
        check(set(profile["samples"]) <= set(PROFILE_GROUPS), name + ": unknown profile group")
        check(abs(sum(profile["samplesPercent"].values()) - 100) < .5, name + ": shares must add to 100")
        check(profile["recordings"] == 12, name + ": twelve recordings expected")
    check(sorted(document["profiles"]) == sorted(f"{arm} {phase}" for arm in ARMS for phase in ("cost", "fairness")), "every arm and phase profiled")
    return document


if __name__ == "__main__":
    load()
    print("evidence valid:", EVIDENCE.name)
