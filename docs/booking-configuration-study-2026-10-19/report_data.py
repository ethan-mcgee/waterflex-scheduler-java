"""Validate and load the booking configuration study that the report explains.

Every number in the report comes from the two evidence files under docs/evidence that the study recorded: the first
study (every part) and the full-schedule rerun (quality and the publish reserve after 530d356). Each report is checked
before use: no contract breach or host anomaly, statuses that add up to their calls, ordered percentiles, and the
cells the protocol promised. The figures are pooled with infra/summarize_booking_configuration.py's own functions, so
the report and the summarizer's tables in docs/api-load.md are one computation. Nothing missing is replaced with a
value: a figure the evidence does not hold stays None and prints as n/a.
"""
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "docs/evidence"
sys.path.insert(0, str(ROOT / "infra"))
import summarize_booking_configuration as s  # noqa: E402

FIRST_FILE = "api-load-2026-10-19-booking-configuration.json.gz"
RERUN_FILE = "api-load-2026-10-19-booking-configuration-full.json.gz"
FLEETS = (5, 10, 15, 20, 30)
CAPS = (0, 250, 1000, 2000, 3000)
THRESHOLDS = (0, 2, 5, 10)
LAYOUTS = ("1x2", "2x1", "1x3", "3x1", "1x4", "2x2", "4x1")
REFERENCE = s.REFERENCE
# The cells each part of each file must hold, from the protocol: quality 5 fleets x 4 profiles x 2 schedules, load
# 5 x 4 x 21 variants x 2 repeats, capacity 2 fleets x 2 profiles x 4 variants x 7 layouts x 2 client counts, and
# the reserve arms; the rerun repeats quality and the reserve on full schedules only.
EXPECTED = {FIRST_FILE: {"quality": 40, "load": 840, "capacity": 224, "reserve": 80}, RERUN_FILE: {"quality": 10, "reserve": 25}}
MACHINE_CPUS = 24


def check_report(report):
    """One kit report, load or quality, checked before any figure is read from it."""
    label = report["label"]
    assert report["contractBreaches"] == [], label
    assert report.get("hostAnomalies") in (None, []), label
    assert report["machine"]["cpus"] == MACHINE_CPUS, label
    for op in report.get("operations") or []:
        assert sum(op["statuses"].values()) == op["calls"], (label, op["operation"])
        if op["ms"]:
            assert op["ms"]["p50"] <= op["ms"]["p95"] <= op["ms"]["p99"] <= op["ms"]["max"], (label, op["operation"])
    if report.get("booking") is not None:
        offers = s.operation(report, "booking offers")
        assert offers is not None, label
        assert report["booking"]["searches"] == offers["calls"], label
    for case in report.get("cases") or []:
        assert len({entry["variant"] for entry in case["searched"]}) == len(case["searched"]), label


def read(name):
    evidence = s.read_evidence(EVIDENCE / name)
    assert evidence["runsWithDefects"] == [], name
    assert Counter(cell["part"] for cell in evidence["cells"]) == Counter(EXPECTED[name]), name
    for cell in evidence["cells"]:
        assert cell["reports"], (name, cell["part"], cell["fleet"], cell["profile"], cell["variant"])
        for report in cell["reports"]:
            check_report(report)
    return evidence


def quality(evidence, part="quality"):
    """Per fleet and profile, each variant against the uncapped reference on the same cases."""
    out = {}
    for (fleet, profile), cells in s.group(s.cells(evidence, part), "fleet", "profile").items():
        cells = [c for c in cells if part != "reserve" or c["variant"] is None]
        if not cells:
            continue
        reports = [r for c in cells for r in c["reports"]]
        out[f"{fleet}/{profile}"] = {name: {
            "meanCents": s.mean(t["changes"]), "medianCents": s.median(t["changes"]), "priced": t["priced"],
            "cheaper": t["cheaper"], "dearer": t["dearer"], "same": t["same"], "p50ms": s.median(t["ms"]),
            "incomplete": t["incomplete"], "answered": t["answered"], "searches": t["searches"],
            "stops": dict(t["stops"]), "refinements": dict(t["refinements"]),
            "windowsMin": min(t["windows"]) if t["windows"] else None, "windowsP50": s.median(t["windows"])}
            for name, t in s.quality_pairs(reports).items()}
    return out


def load_cells(evidence, part="load"):
    """Per fleet, profile and variant (and reserve, for the reserve part): one desk's figures pooled over repeats."""
    out = {}
    for (fleet, profile), cells in s.group(s.cells(evidence, part), "fleet", "profile").items():
        cells = [c for c in cells if c["variant"] is not None]
        groups = s.group(cells, "reserveMs", "variant").items() if part == "reserve" else [((None, k[0]), v) for k, v in s.group(cells, "variant").items()]
        for (reserve, name), vc in groups:
            row = s.load_row([r for c in vc for r in c["reports"]])
            server = row["server"]
            pick = lambda field, stat: None if server is None or server.get(field) is None else server[field][stat]
            out[f"{fleet}/{profile}/{name}" + ("" if reserve is None else f"/{reserve}")] = {
                "runs": row["runs"], "perMinute": row["perMinute"], "p50": row["p50"], "p95": row["p95"], "p99": row["p99"],
                "unavailable": row["unavailable"], "busy": row["busy"], "incomplete": row["incomplete"], "shed": s.shed_share(row),
                "stops": dict(row["stops"]), "refinements": dict(row["refinements"]), "searches": row["searches"],
                "confirmP50": row["confirmP50"], "holdsLost": row["holdsLost"], "confirmed": row["confirmed"],
                "commitP50": pick("commitMs", "p50"), "commitP95": pick("commitMs", "p95"), "routingP50": pick("routingMs", "p50"),
                "engineP50": pick("calculationMs", "p50"), "queueP50": pick("queueMs", "p50"), "dbP50": pick("dbMs", "p50"),
                "cpuP50": pick("cpuMs", "p50"), "fetchedP50": pick("fetched", "p50")}
    return out


def capacity(evidence):
    """Per fleet, profile, variant, layout and client count: the clients' figures together."""
    out = {}
    for cell in s.cells(evidence, "capacity"):
        rows = [s.load_row([r]) for r in cell["reports"]]
        answered = sum(r["answered"] for r in rows)
        shed = sum(r["refinements"].get("SHED", 0) for r in rows)
        lay = cell["layout"]
        key = f"{cell['fleet']}/{cell['profile']}/{cell['variant']}/{lay['replicas']}x{lay['slots']}/{cell['clients']}"
        assert key not in out, key
        server = s.server_offers(cell["reports"])
        out[key] = {"replicas": lay["replicas"], "slots": lay["slots"], "threads": lay["replicas"] * lay["cpus"], "clients": cell["clients"],
                    "perMinuteAll": sum(r["perMinute"] for r in rows if r["perMinute"] is not None),
                    "p50": s.mean([r["p50"] for r in rows if r["p50"] is not None]), "p95": s.mean([r["p95"] for r in rows if r["p95"] is not None]),
                    "shed": None if answered == 0 else 100 * shed / answered, "unavailable": sum(r["unavailable"] for r in rows),
                    "searches": sum(r["searches"] for r in rows),
                    "queueP50": None if server is None else server["queueMs"]["p50"], "queueP95": None if server is None else server["queueMs"]["p95"]}
    return out


def load():
    """Both studies' figures, validated, keyed the way the report reads them."""
    first, rerun = read(FIRST_FILE), read(RERUN_FILE)
    return {
        "first": {"quality": quality(first), "load": load_cells(first), "capacity": capacity(first), "reserveLoad": load_cells(first, "reserve"),
                  "unanswered": first.get("unansweredRuns", []), "defects": first["runsWithDefects"], "cells": len(first["cells"]),
                  "reports": sum(len(c["reports"]) for c in first["cells"]), "shape": first["shape"], "hypotheses": first["hypotheses"],
                  "revision": first["runnerRevision"], "tokens": len(first["clientTokens"])},
        "rerun": {"quality": quality(rerun), "reserveQuality": quality(rerun, "reserve"), "reserveLoad": load_cells(rerun, "reserve"),
                  "unanswered": rerun.get("unansweredRuns", []), "defects": rerun["runsWithDefects"], "cells": len(rerun["cells"]),
                  "reports": sum(len(c["reports"]) for c in rerun["cells"]), "shape": rerun["shape"], "revision": rerun["runnerRevision"],
                  "verified": rerun["shapesVerified"]},
    }


if __name__ == "__main__":
    data = load()
    print(f"PASS: first study {data['first']['cells']} cells ({data['first']['reports']} reports), "
          f"rerun {data['rerun']['cells']} cells ({data['rerun']['reports']} reports); every report checked.")
