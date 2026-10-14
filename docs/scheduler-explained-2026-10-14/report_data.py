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
from pathlib import Path
from statistics import mean

import yaml

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
EVIDENCE = DOCS / "evidence"
SPEC = DOCS / "api/openapi-v1.yaml"
DESIGN = DOCS / "stateless-api-design.md"
GUIDE = DOCS / "integration-guide.md"
RUNBOOK = DOCS / "operations-runbook.md"
MACHINE = "AMD Ryzen 9 7900X 12-Core Processor"

# Tables from the design document, the guide and the runbook, with the row counts they had when this report was written.
# A changed count is a changed document; the report is rebuilt deliberately, not silently.
DOCUMENT_TABLES = {
    "endpoints": (DESIGN, "### Endpoints", "### Contract changes", 10),
    "changes": (DESIGN, "### Contract changes", "### Latency target", 29),
    "stored": (DESIGN, "### What is stored", "### Tenant isolation", 5),
    "tables": (DESIGN, "## Thin store", "Every key starts with", 7),
    "tiers": (DESIGN, "## Route cache", "How a lookup works", 4),
    "settings": (RUNBOOK, "## Settings", "## Install", 18),
    "problems": (GUIDE, "## 7. Errors, idempotency and retries", "**Timeouts to set at the caller", 11),
    "ownership": (GUIDE, "## 1. Topology and ownership", "Every calculating call carries a **snapshot**", 5),
}

# The scheduler before the public API, as the design proposal of 2026-10-07 described it (git show 08dae8b:docs/stateless-api-design.md,
# "What exists today" and the retired list in today's design document under "Portal as a client"). Typed here with that source.
BEFORE_AFTER = (
    ("Master data", "Its own copy of about 40 tables in the portal's Postgres", "None; every call carries a snapshot of the facts it needs"),
    ("Policy", "A shared omaha_setting table and server settings", "Every rule arrives in the request; a missing value is refused"),
    ("Surface", "Portal-only endpoints for offers, holds, optimize, overnight, dispatch, time off", "12 operations under /api/v1 plus /health, OpenAPI 3.1"),
    ("Scheduled work", "Its own 2 a.m. overnight batch, a hold-expiry worker, road prewarming, time-off polling", "None; the host queues each day's proposal and expiry is a time applied on use"),
    ("Writes", "Appointments, previews and reservations applied under row locks", "Receipts the host writes with a compare-and-set on lastModified"),
    ("Database", "Shared with the portal", "Its own Postgres: 11 tables, tenants on every row, row-level security"),
    ("Clients", "One client per metro", "Many clients per metro and many metros per client, each a tenant"),
)


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
    cells = [[c.strip() for c in row.strip()[1:-1].split("|")] for row in (rows[0], *rows[2:])]
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


def per_fleet(rows, solver, fleets):
    return [mean(r["saving"] for r in rows if r["solver"] == solver and r["fleet"] == f) for f in fleets]


def wtl(rows):
    return sum(r["saving"] > 0 for r in rows), sum(r["saving"] == 0 for r in rows), sum(r["saving"] < 0 for r in rows)


def load():
    return {"docs": load_documents(), "perf": load_performance(), "daily": load_daily_studies(), "booking24": load_booking_september(),
            "field29": load_field_september(), "routing24": load_routing_september(), "before_after": BEFORE_AFTER}


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
