"""Validate and load the API load studies and routing benchmarks that measured the performance work.

Every number in the report comes from the JSON files under docs/evidence that each pull request recorded. Each load
report is checked before use: its statuses add up to its calls, no response broke the contract, and the fields the
report reads are present. Nothing missing is replaced with a value.
"""
import json
from pathlib import Path
from statistics import median

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "docs/evidence"

# The load studies in the order they were measured, with the change each one judged.
STUDIES = (
    ("baseline", "api-load-2026-10-13.json", "#151", "Load tool and the first measurement"),
    ("concurrency", "api-load-2026-10-13-booking-concurrency.json", "#152", "Late snapshots keep holds; bookings publish without waiting"),
    ("admission", "api-load-2026-10-13-daily-admission.json", "#153", "Daily proposals refused early with Retry-After; routed before the slot"),
    ("outcome", "api-load-2026-10-13-search-outcome.json", "#154", "searchComplete means what the spec says"),
    ("shedding", "api-load-2026-10-13-refinement-shedding.json", "#155", "Optional refinement shed while bookings wait"),
    ("queue", "api-load-2026-10-13-daily-queue.json", "#157", "Queued proposals with per-client fair turns (L4)"),
    ("roles", "api-load-2026-10-14-replica-roles.json", "#158", "Booking and daily replica pools (L5)"),
    ("vm", "api-load-2026-10-14-vm-baseline.json", "#159", "VM-shaped baseline with the time attributed (J0)"),
    ("routing", "api-load-2026-10-14-routing.json", "#160", "CH graph, parallel pairs, identity cache (J)"),
)
ROUTING_BENCHMARKS = (("2026-09-24", "routing-ch-omaha-2026-09-24.json"), ("2026-10-14", "routing-ch-2026-10-14.json"))
MACHINE = "AMD Ryzen 9 7900X 12-Core Processor"


def read(name):
    return json.loads((EVIDENCE / name).read_text(encoding="utf-8"))


def load_report(study, report, note=""):
    """One load tool report (one process of load.ts) as the row shape the report uses, after checking it."""
    settings = report["settings"]
    assert settings["durationS"] in (60, 90) and 0 <= settings["seed"] <= 1000
    assert report["machine"]["cpuModel"].strip() == MACHINE and report["machine"]["cpus"] == 24
    assert report["contractBreaches"] == [] and report["hostAnomalies"] == [], report["label"]
    ops = {}
    for op in report["operations"]:
        assert sum(op["statuses"].values()) == op["calls"] > 0, (report["label"], op["operation"])
        assert op["ms"]["p50"] <= op["ms"]["p95"] <= op["ms"]["p99"] <= op["ms"]["max"]
        ops[op["operation"]] = op
    booking, daily = report["booking"], report["daily"]
    if booking is not None:
        offers = ops["booking offers"]
        assert booking["searches"] == offers["statuses"].get("201", 0), report["label"]
        # A confirm the scheduler answered 200 counts as confirmed only when the simulated host's receipt write succeeded.
        assert booking["confirmed"] + booking["hostWriteConflicts"] == ops["confirm"]["statuses"].get("200", 0)
        assert booking["failedSearches"] == 0 and booking["noOffers"] == 0
    if daily is not None:
        assert daily["proposals"] == ops["daily proposal"]["calls"]
        if "queueWaitsMs" in daily and daily["queueWaitsMs"]:
            assert len(daily["queueWaitsMs"]) == ops["daily proposal"]["statuses"].get("201", 0)
    return {"study": study, "label": report["label"], "note": note, "scenario": report["scenario"], "desks": settings["concurrency"],
            "planners": settings.get("dailyConcurrency"), "mode": settings.get("dailyMode"), "jobs": settings["jobs"],
            "seed": settings["seed"], "duration": settings["durationS"], "spread": settings.get("dateSpread"),
            "ops": ops, "booking": booking, "daily": daily, "server": report["server"], "containers": report.get("containers") or []}


def load():
    """Every study's runs, keyed by study, plus the two routing benchmarks."""
    runs, extras = {}, {}
    files = {key: read(name) for key, name, _, _ in STUDIES}
    for key, doc in files.items():
        if key in ("baseline", "concurrency", "admission", "outcome", "shedding"):
            runs[key] = [load_report(key, r) for r in doc["runs"]]
        elif key == "queue":
            runs[key] = [load_report(key, r, group) for group in ("sync", "async") for r in doc["fairness"][group]] \
                + [load_report(key, r, "mixed") for r in doc["mixed"]["final"]]
        else:
            runs[key] = []
            for r in doc["runs"]:
                note = r.get("shape") or r.get("note")
                runs[key].append(load_report(key, r["booking"], note) | {"run": r["label"], "breakdown": r.get("serverBreakdown")})
                runs[key].append(load_report(key, r["daily"], note) | {"run": r["label"]})
    extras["coldRemeasurement"] = [load_report("concurrency", r, "fresh seeds") for r in files["concurrency"]["coldRemeasurement"]["runs"]]
    extras["abComparison"] = [load_report("shedding", r, "A/B") for r in files["shedding"]["abComparison"]]
    extras["staleBefore"] = files["baseline"]["staleSnapshotReproduction"]
    extras["staleAfter"] = files["concurrency"]["staleSnapshotReproduction"]
    extras["outcome"] = files["outcome"]["result"]
    extras["stack"] = {key: files[key].get("scheduler") or files[key].get("stack") for key in files}
    extras["protocol"] = {key: files[key]["protocol"] for key in files}
    benchmarks = {}
    for date, name in ROUTING_BENCHMARKS:
        doc = read(name)
        assert doc["checks"]["exactDirectedParity"] and doc["checks"]["sparseEqualsFull"] and doc["routable"] == 132
        assert [e["preparedConfiguration"] for e in doc["engines"]] == ["flexible", "CH-car-v1"]
        assert doc["hardware"]["cpu"].strip() == MACHINE
        benchmarks[date] = doc
    return runs, extras, benchmarks


def per_minute(run, operation="booking offers", status="201"):
    return run["ops"][operation]["statuses"].get(status, 0) * 60 / run["duration"]


def queue_wait(run):
    """Median and maximum wait of a queued proposal before its calculation started, in ms."""
    waits = run["daily"]["queueWaitsMs"]
    return median(waits), max(waits)


def breakdown(run, operation):
    entry = run["breakdown"][operation]
    assert entry["calls"] == run["ops"]["booking offers" if "offers" in operation else "confirm"]["statuses"][operation.rsplit(" ", 1)[1]]
    return entry


if __name__ == "__main__":
    runs, extras, benchmarks = load()
    print({k: len(v) for k, v in runs.items()}, {k: len(v) for k, v in extras.items() if isinstance(v, list)}, list(benchmarks))
