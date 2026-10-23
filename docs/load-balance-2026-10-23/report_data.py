"""Load and validate the load balance head-to-head evidence (PR J) before any table or chart reads it.

Run directly to validate only. No network, services or benchmark execution. `load(path, full=False)` reads another
evidence file of the same shape (the smoke run) with the counts taken from its own manifest; the report uses the full one.
"""
import json
import random
from decimal import Decimal
from pathlib import Path
from statistics import median

HERE = Path(__file__).resolve().parent
EVIDENCE = HERE.parent / "evidence" / "solver-load-balance-2026-10-23.json"
REVISION = "15da8a0f0ca5802c817849f07c29b6f03921130f"
ARMS = ("b", "load-balance")
MEASURES = ("variance", "maximumUtilization", "utilizationSpread", "absoluteDeviation", "loadBalance")
PHASES = ("referencePhase", "fairnessPhase")
FLEETS = (5, 10, 20, 50)
WORKLOADS = ("ABSENCE", "CLUSTERED", "DISPERSED", "MIXED_SHIFT", "NEAR_CAPACITY", "TIGHT_WINDOW")
FIXTURES = (7001, 7002, 7003, 7004, 7005)
ACCEPTED = {"OVERTIME_REDUCTION", "FAIRNESS_IMPROVEMENT", "COST_REDUCTION"}
PROFILE_GROUPS = ("decimal: variance", "variance (non-decimal)", "decimal: score impacts", "decimal: money", "collections", "engine",
                  "scheduler code", "score engine", "time", "other")
# The switching rule as written before the run (infra/load_balance_study.py).
TOLERANCE, LESS_FAIR_ALLOWED, COST_ALLOWANCE, RESAMPLES = Decimal("0.05"), Decimal("0.10"), Decimal("0.001"), 10_000


def check(condition, message):
    if not condition:
        raise ValueError(message)


def case_of(run):
    return run["technicians"], run["workload"], run["fixtureSeed"], run["seed"]


def index(budget):
    """(arm, case, rep) to run, each combination present exactly once."""
    out = {}
    for run in budget["runs"]:
        key = (run["arm"], case_of(run), run["rep"])
        check(key not in out, f"duplicate run {key}")
        out[key] = run
    return out


def cases(budget):
    return sorted({case_of(run) for run in budget["runs"]})


def reps(budget):
    return sorted({run["rep"] for run in budget["runs"]})


def value(run, field):
    """A final measure (exact Decimal) or a whole-cent field; a missing one is refused, never read as zero."""
    raw = run["measures"][field] if field in MEASURES else run[field]
    check(raw is not None, f"{field} missing")
    return Decimal(raw)


def case_mean(by, arm, case, field, repetitions):
    values = [value(by[(arm, case, rep)], field) for rep in repetitions]
    return sum(values) / len(values)


def ratios(by, all_cases, repetitions, field, keep=lambda case: True):
    """Each case's load balance over b on a field, leaving out cases where b reads zero."""
    out = []
    for case in all_cases:
        if keep(case):
            base = case_mean(by, "b", case, field, repetitions)
            if base > 0:
                out.append(float(case_mean(by, "load-balance", case, field, repetitions) / base))
    return out


def interval(values):
    """The 95 percent bootstrap interval of the median, as the summarizer draws it (seed 0, 10,000 resamples)."""
    if len(values) < 2:
        return None
    draw = random.Random(0)
    medians = sorted(median(draw.choices(values, k=len(values))) for _ in range(RESAMPLES))
    return [round(medians[int(RESAMPLES * 0.025)], 3), round(medians[int(RESAMPLES * 0.975) - 1], 3)]


def cost_differences(by, all_cases, repetitions, keep=lambda case: True):
    return [float(case_mean(by, "load-balance", c, "costCents", repetitions) - case_mean(by, "b", c, "costCents", repetitions)) for c in all_cases if keep(c)]


def relative_costs(by, all_cases, repetitions):
    return [(case_mean(by, "load-balance", c, "costCents", repetitions) - case_mean(by, "b", c, "costCents", repetitions))
            / case_mean(by, "b", c, "costCents", repetitions) for c in all_cases]


def speedups(by, all_cases, repetitions, phase, keep=lambda case: True):
    field = "referenceMovesPerSecond" if phase == "referencePhase" else "fairnessMovesPerSecond"
    return [by[("load-balance", c, r)][field] / by[("b", c, r)][field] for c in all_cases if keep(c) for r in repetitions]


def same_plan(by, case, repetitions, stage):
    """Both arms ended a stage (the reference plan or the final plan) on the same cost and the same five measures in every repetition."""
    cost, measures = ("referenceCostCents", "referenceMeasures") if stage == "reference" else ("costCents", "measures")
    return all(by[("b", case, r)][cost] == by[("load-balance", case, r)][cost] and by[("b", case, r)][measures] == by[("load-balance", case, r)][measures]
               for r in repetitions)


def perfectly_even(by, case, repetitions, arm):
    return all(Decimal(by[(arm, case, r)]["measures"]["variance"]) == 0 for r in repetitions)


EARLIER = HERE.parent / "evidence" / "solver-matrix-2026-10-20.json"


def earlier_ratio():
    """The road matrix study read the way this one is: each case's load balance variance over b's (both on its fixed tree),
    the median over cases where b is above zero, the case count and how many cases ended on the same plan."""
    document = json.loads(EARLIER.read_text(encoding="utf-8-sig"))
    runs = document["paired"]["runs"]
    check(document["manifest"]["smoke"] is False and document["budgetMs"] == 15000, "the road matrix study at 15 s expected")
    by = {(r["arm"], r["technicians"], r["workload"], r["seed"], r["rep"]): r for r in runs}
    cases_ = sorted({(r["technicians"], r["workload"], r["seed"]) for r in runs})
    repetitions = sorted({r["rep"] for r in runs})
    mean_of = lambda arm, c: sum(Decimal(by[(arm, *c, r)]["measures"]["variance"]) for r in repetitions) / len(repetitions)
    values = [float(mean_of("load-balance", c) / mean_of("b", c)) for c in cases_ if mean_of("b", c) > 0]
    same = sum(1 for c in cases_ if all(by[("b", *c, r)]["measures"] == by[("load-balance", *c, r)]["measures"]
                                        and by[("b", *c, r)]["costCents"] == by[("load-balance", *c, r)]["costCents"] for r in repetitions))
    return {"median": median(values), "cases": len(cases_), "ratios": len(values), "same": same,
            "fleets": sorted({c[0] for c in cases_}), "repetitions": len(repetitions)}


def allowance_used(run):
    """Share of the fairness allowance the final plan spent above its reference plan; None when there is no allowance."""
    room = run["costCeilingCents"] - run["referenceCostCents"]
    return None if room <= 0 else (run["costCents"] - run["referenceCostCents"]) / room


def load(path=EVIDENCE, full=True):
    document = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    manifest = document["manifest"]
    budgets_ms = [str(b) for b in manifest["budgetsMs"]]
    check(sorted(document["budgets"], key=int) == budgets_ms, "every budget of the manifest expected")
    if full:
        check(manifest["smoke"] is False and manifest["revision"] == REVISION, "not the full study run on the pinned revision")
        check(manifest["budgetsMs"] == [15000, 30000] and manifest["repetitions"] == 1, "budgets 15 s and 30 s, one repetition expected")
        check([int(s) for s in manifest["sizes"]] == list(FLEETS) and sorted(manifest["workloads"]) == list(WORKLOADS)
              and [int(f) for f in manifest["fixtureSeeds"]] == list(FIXTURES) and manifest["seeds"] == ["17"], "the planned matrix expected")
    expected_cases = len(manifest["sizes"]) * len(manifest["workloads"]) * len(manifest["fixtureSeeds"]) * len(manifest["seeds"])
    for key, budget in document["budgets"].items():
        by, all_cases, repetitions = index(budget), cases(budget), reps(budget)
        check(len(all_cases) == expected_cases == budget["cases"] and len(repetitions) == manifest["repetitions"] == budget["repetitions"], key + ": case and repetition counts")
        check(len(by) == len(ARMS) * len(all_cases) * len(repetitions), key + ": every arm, case and repetition expected")
        check(budget["violations"] == {"b": 0, "load-balance": 0} and budget["outsideCeiling"] == [], key + ": no violation and nothing outside a ceiling")
        for run in budget["runs"]:
            if run["acceptanceReason"] in ACCEPTED:
                check(run["costCents"] <= run["costCeilingCents"], f"{key}: accepted run over its ceiling")
            check(sorted(run["measures"]) == sorted(MEASURES) == sorted(run["referenceMeasures"]), f"{key}: every measure expected")
        # The readings the rule rests on, recomputed from the runs.
        for name in MEASURES:
            recorded = budget["measures"][name]
            values = ratios(by, all_cases, repetitions, name)
            check((round(median(values), 3) if values else None) == recorded["medianRatioToB"], f"{key} {name}: median ratio")
            check(interval(values) == recorded["medianRatioInterval"], f"{key} {name}: bootstrap interval")
            means = [(case_mean(by, "load-balance", c, name, repetitions), case_mean(by, "b", c, name, repetitions)) for c in all_cases]
            check(sum(a < b for a, b in means) == recorded["lower"] and sum(a > b for a, b in means) == recorded["higher"], f"{key} {name}: lower and higher")
            check(sum(a > b * (1 + TOLERANCE) for a, b in means) == recorded["higherBeyondTolerance"], f"{key} {name}: beyond tolerance")
        differences = cost_differences(by, all_cases, repetitions)
        check(median(differences) == budget["cost"]["medianDifferenceCents"], f"{key}: cost median")
        relative = median(relative_costs(by, all_cases, repetitions))
        check(float(round(relative, 6)) == budget["cost"]["medianRelativeDifference"], f"{key}: relative cost median")
        for phase in PHASES:
            check(abs(round(median(speedups(by, all_cases, repetitions, phase)), 3) - budget["speed"][phase]["all"]["median"]) <= .001, f"{key} {phase}: speed")
        for arm in ARMS:
            counted = {}
            for run in budget["runs"]:
                if run["arm"] == arm:
                    counted[run["acceptanceReason"]] = counted.get(run["acceptanceReason"], 0) + 1
            check(counted == budget["acceptance"][arm], f"{key} {arm}: acceptance reasons")
        variance = budget["measures"]["variance"]
        rule = {"variance": variance["medianRatioInterval"] is not None and variance["medianRatioInterval"][1] < 1
                            and variance["higherBeyondTolerance"] <= LESS_FAIR_ALLOWED * len(all_cases),
                "maximum": budget["measures"]["maximumUtilization"]["medianRatioToB"] is not None and budget["measures"]["maximumUtilization"]["medianRatioToB"] <= 1,
                "cost": relative <= COST_ALLOWANCE, "ceiling": True}
        check(rule == budget["rule"] and all(rule.values()) == budget["passes"], f"{key}: the rule, recomputed")
    check(document["decision"]["switch"] == all(b["passes"] for b in document["budgets"].values()), "the decision follows the budgets")
    for name, profile in document.get("profiles", {}).items():
        for part in [profile["all"], *profile["byFleet"].values()]:
            check(set(part["samples"]) <= set(PROFILE_GROUPS), name + ": unknown profile group")
            check(abs(sum(part["samplesPercent"].values()) - 100) < .5, name + ": shares must add to 100")
        check(profile["all"]["recordings"] == sum(p["recordings"] for p in profile["byFleet"].values()), name + ": fleets add up")
    return document


if __name__ == "__main__":
    load()
    print("evidence valid:", EVIDENCE.name)
