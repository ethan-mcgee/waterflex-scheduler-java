"""Validate and load every source of the "WaterFlex scheduler explained" report.

Each number in the report comes from a file in the repository: the load studies, routing benchmarks and solver profiling
under docs/evidence, the two daily search studies under experiments/runs (through their own immutable loaders), the
September booking and field validation evidence, and the documents that define the service (the OpenAPI contract, the
design document, the integration guide and the runbook). Nothing missing is replaced with a value; a source whose shape
changed stops the build.
"""
import importlib.util
import json
import re
import sys
from decimal import Decimal
from pathlib import Path
from statistics import mean, median

import yaml

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
EVIDENCE = DOCS / "evidence"
SPEC = DOCS / "api/openapi-v1.yaml"
DESIGN = DOCS / "stateless-api-design.md"
GUIDE = DOCS / "integration-guide.md"
RUNBOOK = DOCS / "operations-runbook.md"
MACHINE = "AMD Ryzen 9 7900X 12-Core Processor"

# Tables from the design document, the guide and the runbook, with the row counts they had when this report was last brought up to date.
# A changed count is a changed document; the report is rebuilt deliberately, not silently.
DOCUMENT_TABLES = {
    "endpoints": (DESIGN, "### Endpoints", "### Contract changes", 10),
    "changes": (DESIGN, "### Contract changes", "### Latency target", 33),
    "stored": (DESIGN, "### What is stored", "### Tenant isolation", 5),
    "tables": (DESIGN, "## Thin store", "Every key starts with", 7),
    "tiers": (DESIGN, "## Route cache", "How a lookup works", 4),
    "settings": (RUNBOOK, "## Settings", "## Install", 27),
    "problems": (GUIDE, "## 7. Errors, idempotency and retries", "**Timeouts to set at the caller", 12),
    "ownership": (GUIDE, "## 1. Topology and ownership", "Every calculating call carries a **snapshot**", 5),
}


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def module(path, name):
    """A sibling report's loader, run from its own folder so that its relative imports resolve."""
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.path.insert(0, str(Path(path).parent))
    try:
        spec.loader.exec_module(mod)
    finally:
        sys.path.remove(str(Path(path).parent))
    return mod


def markdown_table(text, start, end):
    """The rows of the first markdown table between two markers, as lists of cell strings."""
    segment = text[text.index(start):text.index(end, text.index(start))]
    rows = [line for line in segment.splitlines() if line.startswith("| ")]
    assert rows and rows[1].startswith("| ---"), start
    # A cell may hold an escaped pipe (\|), which is text, not a column break.
    cells = [[c.strip().replace("\\|", "|") for c in re.split(r"(?<!\\)\|", row.strip()[1:-1])] for row in (rows[0], *rows[2:])]
    width = len(cells[0])
    assert all(len(r) == width for r in cells), (start, [len(r) for r in cells])
    return cells[0], cells[1:]


def plain(text):
    """Markdown inline code and emphasis removed, for table cells."""
    return re.sub(r"\*\*(.+?)\*\*", r"\1", text.replace("`", "")).replace("[docs/integration-guide.md](integration-guide.md)", "docs/integration-guide.md")


def load_documents():
    spec = yaml.safe_load(SPEC.read_text(encoding="utf-8"))
    operations = []
    for path, item in spec["paths"].items():
        for method, op in item.items():
            if method in ("get", "post", "delete", "put"):
                assert op["x-implementation"] == "implemented", op["operationId"]
                operations.append({"method": method.upper(), "path": path, "id": op["operationId"], "summary": op["summary"]})
    history = re.findall(r"^- (\d+\.\d+\.\d+) \((\d{4}-\d{2}-\d{2})\): (.+?)(?=^- \d|\Z)", spec["info"]["description"].split("Version history:")[1], re.M | re.S)
    history = [(v, d, " ".join(t.split())) for v, d, t in history]
    assert history[0][0] == spec["info"]["version"]
    errors = spec["components"]["schemas"]["Problem"]["properties"]["error"]["enum"]
    assert len(operations) == 12 and len(errors) == 10
    texts = {p: p.read_text(encoding="utf-8") for p in (DESIGN, GUIDE, RUNBOOK)}
    tables = {}
    for name, (path, start, end, count) in DOCUMENT_TABLES.items():
        header, rows = markdown_table(texts[path], start, end)
        assert len(rows) == count, (name, len(rows))
        tables[name] = (header, rows)
    for code in errors:
        assert any(code in plain(r[1]) for r in tables["problems"][1]), code
    return {"version": spec["info"]["version"], "operations": operations, "history": history, "errors": errors,
            "schemas": sorted(spec["components"]["schemas"]), **tables}


def load_performance():
    perf = module(DOCS / "performance-work-2026-10-14/report_data.py", "performance_work_2026_10_14")
    runs, extras, benchmarks = perf.load()
    return {"runs": runs, "extras": extras, "benchmarks": benchmarks, "studies": perf.STUDIES, "per_minute": perf.per_minute, "queue_wait": perf.queue_wait}


def saving_rows(rows, control):
    """Each case with its saving in dollars against the matched control case (control accepted cost minus its own)."""
    twins = {r["key"][:-1]: r["raw"] for r in rows if r["solver"] == control}
    out = []
    for r in rows:
        twin = twins[r["key"][:-1]]
        assert twin["before"] == r["raw"]["before"]
        out.append({**r, "accepted_cost": r["raw"]["after"]["costCents"] / 100, "saving": (twin["after"]["costCents"] - r["raw"]["after"]["costCents"]) / 100,
                    "variance": r["raw"]["after"]["fairness"]["variance"], "best_fraction": r["raw"]["referencePhase"]["timeToBestMs"] / r["raw"]["referencePhase"]["solveMs"],
                    "move_rate": r["raw"]["referencePhase"]["moveEvaluations"] / r["raw"]["referencePhase"]["solveMs"]})
    return out


def load_daily_studies():
    tabu = module(DOCS / "daily-tabu-variants-5d15129b/report_data.py", "tabu_variants_5d15129b")
    config, rows, confirm_rows, _ = tabu.load()
    assert len(rows) == 4480 and config["daily"]["control"] == "TABU" and config["daily"]["parallel_cases"] == 6
    first = saving_rows([{**dict(zip(("fleet", "workload", "seed", "budget_ms", "solver"), r["key"])), "key": r["key"], "raw": r["raw"]} for r in rows], "TABU")
    first_confirm = saving_rows([{**dict(zip(("fleet", "workload", "seed", "budget_ms", "solver"), r["key"])), "key": r["key"], "raw": r["raw"]} for r in confirm_rows], "TABU")
    follow = module(DOCS / "daily-search-followup-6454fb05/report_data.py", "daily_search_followup_6454fb05")
    data = follow.load()
    main_config, main = data["main"]
    long_config, long_rows = data["long"]
    confirm_config, confirm = data["confirm"]
    assert len(main) == 1200 and len(long_rows) == 192 and len(confirm) == 32
    assert data["decision"]["candidate"] == "TABU_KOPT_SIZE_3"
    return {"first": {"config": config, "rows": first, "confirm": first_confirm, "fixtures": len({r["raw"]["datasetFingerprint"] for r in rows})},
            "follow": {"config": main_config, "rows": saving_rows(main, "TABU"), "long": saving_rows(long_rows, "TABU_KOPT"), "confirm": saving_rows(confirm, "TABU"),
                       "fixtures": len({r["raw"]["datasetFingerprint"] for r in main}), "decision": data["decision"]}}


def load_booking_september():
    """The 2026-09-24 insertion against bounded study, through the explainer's own validated calculations."""
    booking = module(DOCS / "booking-experiment-explainer/report_data.py", "booking_experiment_explainer")
    evidence = read(DOCS / "booking-experiment-explainer/evidence.json")
    booking.verify(evidence)
    rows = evidence["cases"]
    pairs = booking.pairs(rows)
    comparable = [p for p in pairs if p["comparable"]]
    assert len(comparable) == 60 and all(p["bounded"]["concurrency"] == 1 for p in comparable)
    by_workload = {w: {"paid_min": booking.savings([p for p in comparable if p["bounded"]["workload"] == w], "paid_min"),
                       "cost": booking.savings([p for p in comparable if p["bounded"]["workload"] == w], "cost_cents") / 100,
                       "cases": sum(1 for p in comparable if p["bounded"]["workload"] == w)} for w in ("CLUSTERED", "DISPERSED", "SPARSE")}
    latency = {(s, c): booking.outcomes(booking.choose(rows, solver=s, concurrency=c)) for s in booking.SOLVERS for c in (1, 5, 10)}
    assert all(p["savings"]["paid_min"] >= 0 and p["savings"]["cost_cents"] >= 0 for p in comparable)
    return {"cases": len(rows), "requests": sum(len(r["attempts"]) for r in rows), "comparable": len(comparable),
            "paid_min": booking.savings(comparable, "paid_min"), "cost": booking.savings(comparable, "cost_cents") / 100,
            "by_workload": by_workload, "latency": latency}


def load_field_september():
    """The 2026-09-29 field validation summary: held-out booking service and the daily paired savings."""
    summary = read(EVIDENCE / "scheduler-field-2026-09-29/summary.json")
    assert summary["executionComplete"] and summary["expectedBookingCases"] == summary["actualBookingCases"]
    booking = {(b["stage"], b["variant"]): b for b in summary["booking"]}
    for key in (("held", "INSERTION"), ("held", "BOUNDED"), ("held", "SHARED")):
        b = booking[key]
        assert b["requests"] == 480 and b["cases"] == 48 and sum(b["outcomes"].values()) == b["requests"]
        assert b["outcomes"]["AVAILABLE"] == b["served"]
    daily = {(d["budgetSeconds"], d["variant"]): d["meanSavingsCents"] / 100 for d in summary["dailyPaired"]}
    assert len(daily) == 21 and all(d["independentSeeds"] == 2 for d in summary["dailyPaired"])
    assert summary["dailyResults"] == 240
    return {"booking": booking, "daily": daily, "daily_cases": summary["dailyResults"], "held_cases": 48}


def load_routing_september():
    sparse = read(EVIDENCE / "sparse-routing-omaha-2026-09-23.json")
    assert sparse["routing"]["mapVersion"] == "2026-09-16" and all(p["leg"]["routable"] for p in sparse["directedPairs"])
    omaha = read(EVIDENCE / "booking-omaha-routing-2026-09-24.json")
    summaries = {s["variant"].rsplit("-", 1)[1]: s for s in omaha["summaries"]}
    assert set(summaries) == {"flexible", "ch"} and all(s["requests"] == 720 and s["cases"] == 72 for s in summaries.values())
    return {"sparse": sparse["apiSamplesMs"], "sparse_pairs": len(sparse["directedPairs"]), "omaha": summaries}


# ---- The work after the first edition: booking configuration and horizon, the engine's representation, fairness and numbering
# studies, and their verification on the rehearsal stack. -------------------------------------------------------------------------

# Results quoted from docs/solver-benchmarks.md, "Shipped representation" (study 3). The run's evidence is on the local branch bench/study-3;
# main carries only the write-up. Cost phase / fairness phase, median of 180 paired moves-per-second ratios.
QUOTED = {"study3": {"source": "docs/solver-benchmarks.md, Shipped representation (study 3, evidence on branch bench/study-3)",
                     "lm_over_main": (1.288, 1.264), "lmw_over_lm": (1.047, 1.040), "wa_over_lm": (1.051, 1.043), "wa_over_main": (1.342, 1.312),
                     "cr11_c_over_lm": 0.63, "solves": 2340}}


def check(condition, message):
    if not condition:
        raise ValueError(message)


def read_bom(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def load_horizon():
    """Dynamic against fixed booking horizon (#177): searches that offered nothing and offers per search, summed over the three seeds."""
    doc = read_bom(EVIDENCE / "api-load-2026-10-16-dynamic-horizon.json")
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
    """The booking configuration study (#179): the full-schedule refinement frontier (rerun) and the booking pool layouts (first run)."""
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
    # Throughput by booking pool layout: searches per minute at 10 clients, the 2:250 variant, averaged over the four fleet-and-schedule cells.
    capacity = data["first"]["capacity"]
    layouts = {}
    for layout in bc.LAYOUTS:
        cells = [capacity[f"{fleet}/{sched}/2:250/{layout}/10"] for fleet in (5, 10) for sched in ("half", "full")]
        replicas, slots = (int(x) for x in layout.split("x"))
        layouts[layout] = {"vcpu": replicas * slots, "perMinute": mean(c["perMinuteAll"] for c in cells), "p95": mean(c["p95"] for c in cells),
                           "shed": mean(c["shed"] for c in cells), "cells": len(cells)}
    return {"frontier": frontier, "layouts": layouts, "cells": data["first"]["cells"] + data["rerun"]["cells"], "noise": 0.80}


def load_fairness():
    """The fairness measure study (#187): formulas b and c and the four other measures against the decimal variance, at 15 and 60 s."""
    fm = module(DOCS / "fairness-measures-2026-10-20/report_data.py", "fairness_measures_2026_10_20")
    data = fm.load()
    summary = {b: data["budgets"][b]["summary"] for b in ("15000", "60000")}
    return {"summary": summary, "decision": data["decision"], "solves": sum(len(data["budgets"][b]["runs"]) for b in ("15000", "60000"))}


def load_numbering():
    """The road matrix numbering study (#187): the fix, and formula b on the faster engine, case by case."""
    rm = module(DOCS / "road-matrix-numbering-2026-10-20/report_data.py", "road_matrix_numbering_2026_10_20")
    paired = rm.load()["paired"]
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
    """The score and time representation study (#186): T, the long nanosecond timing, and S, the long score."""
    doc = read_bom(EVIDENCE / "solver-representation-2026-10-20.json")
    check(not doc["manifest"]["smoke"] and doc["paired"]["violations"] == 0 and doc["paired"]["cases"] == 24, "representation study: full run, no violation")
    return {"summary": doc["paired"]["summary"], "solves": doc["paired"]["cases"] * doc["paired"]["repetitions"] * 4}


def stack_ratios(path, commits):
    """After over before, each phase's moves per second at the kit's p50, per fleet: the median of the seeds' ratios (as the stack reports define it)."""
    doc = read_bom(path)
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
    """Formula b (#189) and the long score and money with W and A (#191), each through the public API on the rehearsal stack."""
    sv = module(DOCS / "stack-verification-2026-10-20/report_data.py", "stack_verification_2026_10_20")
    sv.load()  # validates the option b evidence
    b = stack_ratios(EVIDENCE / "api-load-2026-10-20-binary-variance.json", {"before": sv.COMMITS["before"], "after": sv.COMMITS["after"]})
    shipped = stack_ratios(EVIDENCE / "api-load-2026-10-22-long-score-money-w-a.json",
                           {"before": "4ce1a56f6600930417c47a62d56d3c1c7251d73e", "after": "b26d0baf86c179e63e92fc89ff9e326cf17b2422"})
    return {"b": b, "shipped": shipped, "runs": 40}


def load_later():
    return {"horizon": load_horizon(), "config": load_booking_configuration(), "fairness": load_fairness(), "numbering": load_numbering(),
            "representation": load_representation(), "stack": load_stack(), "quoted": QUOTED}


def per_fleet(rows, solver, fleets):
    return [mean(r["saving"] for r in rows if r["solver"] == solver and r["fleet"] == f) for f in fleets]


def wtl(rows):
    return sum(r["saving"] > 0 for r in rows), sum(r["saving"] == 0 for r in rows), sum(r["saving"] < 0 for r in rows)


def load():
    return {"docs": load_documents(), "perf": load_performance(), "daily": load_daily_studies(), "booking24": load_booking_september(),
            "field29": load_field_september(), "routing24": load_routing_september(), "later": load_later()}


if __name__ == "__main__":
    d = load()
    first, follow = d["daily"]["first"]["rows"], d["daily"]["follow"]["rows"]
    print("docs", d["docs"]["version"], len(d["docs"]["operations"]), "operations", {k: len(v[1]) for k, v in d["docs"].items() if isinstance(v, tuple)})
    print("perf", {k: len(v) for k, v in d["perf"]["runs"].items()})
    print("first study", len(first), "TABU_KOPT", wtl([r for r in first if r["solver"] == "TABU_KOPT"]), round(mean(r["saving"] for r in first if r["solver"] == "TABU_KOPT"), 2))
    print("follow-up", len(follow), "TABU_KOPT", wtl([r for r in follow if r["solver"] == "TABU_KOPT"]), "SIZE_3", wtl([r for r in follow if r["solver"] == "TABU_KOPT_SIZE_3"]))
    print("booking24", d["booking24"]["paid_min"], d["booking24"]["cost"], d["booking24"]["latency"][("BOUNDED", 1)]["p95"])
    print("field29", {k: (v["served"], v["requests"]) for k, v in d["field29"]["booking"].items() if k[0] == "held"}, d["field29"]["daily"][(15, "KOPT")])
    print("routing24", d["routing24"]["sparse"], {k: v["served"] for k, v in d["routing24"]["omaha"].items()})
