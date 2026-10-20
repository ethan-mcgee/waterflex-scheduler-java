"""Load and validate the fairness measure study evidence (PR 187) before any table or chart reads it.

Run directly to validate only. No network, services or benchmark execution.
"""
import json
from decimal import Decimal
from pathlib import Path
from statistics import median

HERE = Path(__file__).resolve().parent
EVIDENCE = HERE.parent / "evidence" / "solver-variance-2026-10-20.json"
ARMS = ("baseline", "b", "c", "min-max", "spread", "abs-dev", "load-balance")
POLICIES = ("min-max", "spread", "abs-dev", "load-balance")
MEASURES = ("variance", "maximumUtilization", "utilizationSpread", "absoluteDeviation", "loadBalance")
ACCEPTED_ON = {"min-max": "maximumUtilization", "spread": "utilizationSpread", "abs-dev": "absoluteDeviation", "load-balance": "loadBalance"}
BUDGETS = ("15000", "60000")
REPS = (1, 2)
PROFILE_GROUPS = ("decimal: variance", "variance (non-decimal)", "decimal: score impacts", "decimal: money", "collections", "engine",
                  "scheduler code", "score engine", "time", "other")


def check(condition, message):
    if not condition:
        raise ValueError(message)


def case_of(run):
    return run["technicians"], run["workload"], run["seed"]


def own_measure(arm):
    return ACCEPTED_ON.get(arm, "variance")


def index(budget):
    """(arm, case, rep) to run, with every combination required exactly once."""
    out = {}
    for run in budget["runs"]:
        key = (run["arm"], case_of(run), run["rep"])
        check(key not in out, f"duplicate run {key}")
        out[key] = run
    return out


def cases(budget):
    return sorted({case_of(run) for run in budget["runs"]})


def case_mean(by, arm, case, field):
    """The mean over both repetitions of a final measure (Decimal) or a number in the run."""
    values = [Decimal(by[(arm, case, rep)]["measures"][field]) if field in MEASURES else Decimal(by[(arm, case, rep)][field]) for rep in REPS]
    return sum(values) / len(values)


def speedups(by, arm, phase="fairnessMovesPerSecond"):
    return [by[(arm, case, rep)][phase] / by[("baseline", case, rep)][phase] for case in sorted({k[1] for k in by}) for rep in REPS]


def allowance_used(run):
    """Share of the fairness allowance the final plan spent above its own reference plan; None when the allowance is zero."""
    allowance = run["costCeilingCents"] - run["referenceCostCents"]
    return None if allowance <= 0 else (run["costCents"] - run["referenceCostCents"]) / allowance


def own_reduction(run):
    """Final over reference on the arm's own measure; None when the reference is already perfectly fair on it."""
    name = own_measure(run["arm"])
    reference = Decimal(run["referenceMeasures"][name])
    return None if reference == 0 else float(Decimal(run["measures"][name]) / reference)


def load():
    document = json.loads(EVIDENCE.read_text(encoding="utf-8-sig"))
    manifest = document["manifest"]
    check(manifest["smoke"] is False and manifest["repetitions"] == 2 and manifest["budgetsMs"] == [15000, 60000], "not the full study run")
    check(manifest["revision"] == "773f9f61d1d1e762aedc91e644d8610ed3d36fc6", "unexpected revision")
    check(sorted(document["budgets"]) == list(BUDGETS), "both budgets expected")
    for key, budget in document["budgets"].items():
        by = index(budget)
        all_cases = cases(budget)
        check(len(all_cases) == 24 == budget["cases"] and budget["repetitions"] == 2, key + ": 24 cases and 2 repetitions expected")
        check(len(by) == len(ARMS) * 24 * 2 and {k[0] for k in by} == set(ARMS), key + ": every arm, case and repetition expected")
        check(budget["violations"] == 0 and budget["outsideCeiling"] == [], key + ": no violation and nothing outside a ceiling")
        for run in budget["runs"]:
            check(run["costCents"] <= run["costCeilingCents"], f"{key}: run over its ceiling")
            check(run["referenceCostCents"] <= run["costCeilingCents"], f"{key}: reference over its own ceiling")
            check(sorted(run["measures"]) == sorted(MEASURES) == sorted(run["referenceMeasures"]), f"{key}: every measure expected")
        # The summaries the decision rests on, recomputed from the runs.
        for arm in ("b", "c"):
            recorded = budget["summary"][arm]["fairnessPhaseSpeedup"]
            values = speedups(by, arm)
            check(abs(round(median(values), 3) - recorded["median"]) <= .001 and len(values) == recorded["pairs"], f"{key} {arm}: speed median")
            differences = [float(case_mean(by, arm, case, "costCents") - case_mean(by, "baseline", case, "costCents")) for case in all_cases]
            check(median(differences) == budget["summary"][arm]["cost"]["medianDifferenceCents"], f"{key} {arm}: cost median")
            pairs = [p for p in budget["pairs"] if p["arm"] == arm]
            check(len(pairs) == 24, f"{key} {arm}: 24 pairs expected")
            beyond = sum(1 for p in pairs if Decimal(p["meanVarianceDifference"]) > Decimal(p["baselineVarianceSpread"]))
            check(beyond == budget["summary"][arm]["fairness"]["lessFairBeyondNoise"], f"{key} {arm}: fairness bar count")
        for arm in ARMS:
            entry = budget["comparison"][arm]
            check(entry["acceptedOn"] == own_measure(arm), f"{key} {arm}: accepted measure")
            for name in MEASURES:
                ratios = [float(case_mean(by, arm, case, name) / case_mean(by, "baseline", case, name)) for case in all_cases
                          if case_mean(by, "baseline", case, name) > 0]
                check(round(median(ratios), 3) == entry["measures"][name]["medianRatioToBaseline"], f"{key} {arm} {name}: ratio")
            reasons = {}
            for run in budget["runs"]:
                if run["arm"] == arm:
                    reasons[run["acceptanceReason"]] = reasons.get(run["acceptanceReason"], 0) + 1
            check(reasons == entry["acceptance"], f"{key} {arm}: acceptance reasons")
    check(document["decision"]["b"]["promoted"] == all(document["budgets"][b]["summary"]["b"]["passes"] for b in BUDGETS), "decision b")
    check(document["decision"]["c"]["promoted"] == all(document["budgets"][b]["summary"]["c"]["passes"] for b in BUDGETS), "decision c")
    for name, profile in document["profiles"].items():
        check(set(profile["samples"]) <= set(PROFILE_GROUPS), name + ": unknown profile group")
        check(abs(sum(profile["samplesPercent"].values()) - 100) < .5, name + ": shares must add to 100")
        check(profile["recordings"] == 8, name + ": eight recordings expected")
    return document


if __name__ == "__main__":
    load()
    print("evidence valid:", EVIDENCE.name)
