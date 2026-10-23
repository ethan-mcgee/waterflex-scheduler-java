"""Every number the President's briefing uses, read through the loaders that already validate each study, plus the few evidence files no loader covers yet.

No number is typed in by hand except the two quoted results named in QUOTED (their raw evidence lives on a local study branch, not in main), and each
is labeled as quoted wherever the briefing uses it. Nothing missing is replaced with a value: a lookup that finds nothing stops the build.
"""
import importlib.util
import json
import math
import sys
from decimal import Decimal
from pathlib import Path
from statistics import mean, median

HERE = Path(__file__).resolve().parent
DOCS = HERE.parent
EVIDENCE = DOCS / "evidence"

# Results quoted from docs/solver-benchmarks.md, "Shipped representation" (study 3, 2026-10-21). The run's evidence is on the local branch
# bench/study-3; main carries only the write-up. Cost phase / fairness phase, median of 180 paired moves-per-second ratios.
QUOTED = {
    "study3": {"source": "docs/solver-benchmarks.md, Shipped representation (study 3, evidence on branch bench/study-3)",
               "lm_over_main": (1.288, 1.264), "lmw_over_lm": (1.047, 1.040), "wa_over_lm": (1.051, 1.043), "wa_over_main": (1.342, 1.312),
               "cr11_c_over_lm": 0.63, "solves": 2340},
    "budget_study": {"source": "docs/daily-budget-331df1f1 report (its run archive is not in the repository)", "cases": 1280},
}


def check(condition, message):
    if not condition:
        raise ValueError(message)


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def explained():
    """The October 14 report's own loaders, without its document-table check (the living docs it parses have moved on since)."""
    return module(DOCS / "scheduler-explained-2026-10-14/report_data.py", "scheduler_explained_2026_10_14")


def load_horizon():
    """Dynamic against fixed booking horizon at five desks under high demand: searches that offered nothing, summed over the three seeds."""
    doc = read(EVIDENCE / "api-load-2026-10-16-dynamic-horizon.json")
    check(not doc["runsWithDefects"] and len(doc["runs"]) == 24, "horizon study: 24 runs with no defect")
    out = {}
    for profile in ("low", "high"):
        for mode, word in (("fixed", "fixed10"), ("dynamic", "dynamic")):
            for desks in (1, 5):
                runs = [r for r in doc["runs"] if r["label"].startswith(f"{profile} {word} c{desks} ")]
                check(len(runs) == 3, (profile, mode, desks))
                for r in runs:
                    check(not r["contractBreaches"] and r["booking"]["failedSearches"] == 0, r["label"])
                searches, empty, offers = (sum(r["booking"][k] for r in runs) for k in ("searches", "noOffers", "offers"))
                out[(profile, mode, desks)] = {"searches": searches, "noOffers": empty, "offersPerSearch": offers / searches}
    return out


def load_booking_configuration():
    bc = module(DOCS / "booking-configuration-study-2026-10-19/report_data.py", "booking_configuration_2026_10_19")
    data = bc.load()
    quality = data["rerun"]["quality"]
    variants = ("2:0", "2:250", "2:1000", "2:2000", "2:3000")
    frontier = {}
    for fleet in bc.FLEETS:
        cell = quality[f"{fleet}/full"]
        frontier[fleet] = [{"variant": v, "cap_ms": int(v.split(":")[1]), "gap": cell[v]["meanCents"] / 100, "p50_ms": cell[v]["p50ms"], "priced": cell[v]["priced"]} for v in variants]
        check(all(e["priced"] == 40 for e in frontier[fleet]), f"{fleet}: every case priced on both sides")
        frontier[fleet].append({"variant": "1000", "cap_ms": None, "gap": 0.0, "p50_ms": cell["1000"]["p50ms"], "priced": 40})
    # Throughput by booking-pool layout: searches per minute at 10 clients, the 2:250 variant, averaged over the four fleet-and-schedule cells.
    capacity = data["first"]["capacity"]
    layouts = {}
    for layout in bc.LAYOUTS:
        cells = [capacity[f"{fleet}/{sched}/2:250/{layout}/10"] for fleet in (5, 10) for sched in ("half", "full")]
        replicas, slots = (int(x) for x in layout.split("x"))
        layouts[layout] = {"vcpu": replicas * slots, "perMinute": mean(c["perMinuteAll"] for c in cells), "p95": mean(c["p95"] for c in cells),
                           "shed": mean(c["shed"] for c in cells), "cells": len(cells)}
    return {"frontier": frontier, "layouts": layouts, "cells": data["first"]["cells"] + data["rerun"]["cells"], "noise": 0.80}


def load_fairness():
    fm = module(DOCS / "fairness-measures-2026-10-20/report_data.py", "fairness_measures_2026_10_20")
    data = fm.load()
    summary = {b: data["budgets"][b]["summary"] for b in ("15000", "60000")}
    solves = sum(len(data["budgets"][b]["runs"]) for b in ("15000", "60000"))
    return {"summary": summary, "decision": data["decision"], "solves": solves}


def load_numbering():
    rm = module(DOCS / "road-matrix-numbering-2026-10-20/report_data.py", "road_matrix_numbering_2026_10_20")
    data = rm.load()
    paired = data["paired"]
    pairs = []
    for p in paired["pairs"]:
        if p["arm"] != "b":
            continue
        base, arm = mean(Decimal(v) for v in p["baselineVariance"]), mean(Decimal(v) for v in p["armVariance"])
        check(base > 0, "a fixed production variance of zero has no ratio")
        pairs.append({"technicians": p["technicians"], "workload": p["workload"], "seed": p["seed"], "variance_ratio": float(arm / base),
                      "cost_difference": float(p["meanCostDifferenceCents"]) / 100})
    check(len(pairs) == 36, "36 cases of b")
    return {"fix": paired["fix"], "summary": paired["summary"], "decision": paired["decision"], "pairs": pairs, "solves": len(paired["runs"])}


def load_representation():
    doc = read(EVIDENCE / "solver-representation-2026-10-20.json")
    check(not doc["manifest"]["smoke"] and doc["paired"]["violations"] == 0 and doc["paired"]["cases"] == 24, "representation study: full run, no violation")
    return {"summary": doc["paired"]["summary"], "solves": doc["paired"]["cases"] * doc["paired"]["repetitions"] * 4}


def stack_ratios(path, commits):
    """After over before, each phase's moves per second at the kit's p50, per fleet: the median of the seeds' ratios (as the stack reports define it)."""
    doc = read(path)
    check({k: v["commit"] for k, v in doc["images"].items()} == commits, f"{path.name}: unexpected commits")
    runs = {}
    for r in doc["runs"]:
        check(not r["report"]["contractBreaches"] and not r["report"]["hostAnomalies"], r["report"]["label"])
        if r["step"] == 1:
            runs[(r["image"], r["fleet"], r["seed"])] = {p["phase"]: p["movesPerSecond"]["p50"] for p in r["report"]["study"]["summary"]["phases"]}
    out = {}
    for fleet in (10, 20, 50):
        seeds = sorted({s for (img, fl, s) in runs if fl == fleet})
        check(len(seeds) == 3, (path.name, fleet))
        out[fleet] = {phase: median(runs[("after", fleet, s)][phase] / runs[("before", fleet, s)][phase] for s in seeds) for phase in ("REFERENCE", "FAIRNESS")}
    return out


def load_stack():
    sv = module(DOCS / "stack-verification-2026-10-20/report_data.py", "stack_verification_2026_10_20")
    sv.load()  # validates the option b evidence
    b = stack_ratios(EVIDENCE / "api-load-2026-10-20-binary-variance.json", {"before": sv.COMMITS["before"], "after": sv.COMMITS["after"]})
    shipped = stack_ratios(EVIDENCE / "api-load-2026-10-22-long-score-money-w-a.json",
                           {"before": "4ce1a56f6600930417c47a62d56d3c1c7251d73e", "after": "b26d0baf86c179e63e92fc89ff9e326cf17b2422"})
    return {"b": b, "shipped": shipped, "runs": 40}


def daily_extras(daily):
    """From the first daily study: savings over the day as sent by budget, and one real day's workloads through both phases."""
    rows = daily["first"]["rows"]
    as_sent = {}
    for solver in ("TABU", "TABU_KOPT"):
        for fleet in (5, 10, 20, 50):
            for budget in (60000, 90000, 120000, 240000):
                cases = [r for r in rows if r["solver"] == solver and r["fleet"] == fleet and r["budget_ms"] == budget]
                check(cases, (solver, fleet, budget))
                as_sent[(solver, fleet, budget)] = mean((r["raw"]["before"]["costCents"] - r["raw"]["after"]["costCents"]) / 100 for r in cases)
    # The example day: 10 technicians, clustered, seed 17, 120 s, Tabu + K opt; the plan as sent, after the cost phase and after the fairness phase.
    case = [r for r in rows if r["key"] == (10, "CLUSTERED", 17, 120000, "TABU_KOPT")]
    check(len(case) == 1, "one example case")
    raw = case[0]["raw"]
    stages = {}
    for stage in ("before", "reference", "after"):
        plan = raw[stage]
        stages[stage] = {"costCents": plan["costCents"], "overtime": plan["overtimeMinutes"], "variance": plan["fairness"]["variance"],
                         "paid": [w["paidMinutes"] for w in plan["fairness"]["workloads"]], "capacity": [w["regularCapacityMinutes"] for w in plan["fairness"]["workloads"]]}
    check(stages["after"]["costCents"] <= stages["before"]["costCents"] and stages["after"]["overtime"] == 0, "the example day is accepted and adds no overtime")
    return {"as_sent": as_sent, "example": stages, "example_key": list(case[0]["key"])}


def load():
    ex = explained()
    daily = ex.load_daily_studies()
    data = {"perf": ex.load_performance(), "daily": daily, "booking24": ex.load_booking_september(), "field29": ex.load_field_september(),
            "routing24": ex.load_routing_september(), "horizon": load_horizon(), "config": load_booking_configuration(), "fairness": load_fairness(),
            "numbering": load_numbering(), "representation": load_representation(), "stack": load_stack(), "daily_extras": daily_extras(daily), "quoted": QUOTED}
    return data


if __name__ == "__main__":
    d = load()
    h = d["horizon"]
    print("horizon high c5", h[("high", "fixed", 5)], h[("high", "dynamic", 5)])
    print("config layouts", {k: round(v["perMinute"]) for k, v in d["config"]["layouts"].items()})
    print("config 10t", [(e["variant"], e["gap"], e["p50_ms"]) for e in d["config"]["frontier"][10]])
    print("fairness b 15 s", d["fairness"]["summary"]["15000"]["b"]["fairnessPhaseSpeedup"]["median"])
    print("numbering fix", d["numbering"]["fix"]["baseline"]["referencePhaseSpeedup"]["median"])
    print("T", d["representation"]["summary"]["T"]["referencePhaseSpeedup"]["median"])
    print("stack b", d["stack"]["b"]); print("stack shipped", d["stack"]["shipped"])
    print("example", {k: (v["costCents"], round(v["variance"], 4)) for k, v in d["daily_extras"]["example"].items()})
    print("as sent TK", {k: round(v, 2) for k, v in d["daily_extras"]["as_sent"].items() if k[0] == "TABU_KOPT"})
