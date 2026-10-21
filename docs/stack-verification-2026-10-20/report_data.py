"""Load and validate the stack verification evidence (PR 189) before any table or chart reads it.

Run directly to validate only. No network, services or load runs.
"""
import json
import math
from decimal import Decimal
from pathlib import Path

HERE = Path(__file__).resolve().parent
EVIDENCE = HERE.parent / "evidence" / "api-load-2026-10-20-binary-variance.json"
IMAGES = ("before", "after")
COMMITS = {"before": "cdd19a627bc445806502f202f203609e6dde6a84", "after": "68828b572425babfb8947ddb0bbde5d8fedd846e"}
FLEETS = (10, 20, 50)
SEEDS = (1, 2, 3)
PHASES = ("REFERENCE", "FAIRNESS")
STEP2 = (20, 4)  # fleet and seed of the queued step
RECEIPTS = 96


def check(condition, message):
    if not condition:
        raise ValueError(message)


def p50(values):
    """The kit's nearest-rank percentile at one half: with four values, the second smallest."""
    ordered = sorted(values)
    check(ordered, "no values for a median")
    return ordered[math.ceil(.5 * len(ordered)) - 1]


def decimal_p50(values):
    """The kit's median of decimal strings: nearest rank, compared exactly."""
    ordered = sorted(values, key=Decimal)
    return ordered[math.ceil(.5 * len(ordered)) - 1]


def key(entry):
    return entry["step"], entry["image"], entry["fleet"], entry["seed"]


def receipts(report):
    return report["study"]["receipts"]


def speeds(report, phase):
    """Each proposal's Timefold moves per second in one phase, in log order."""
    index = PHASES.index(phase)
    return [r["solves"][index]["movesPerSecond"] for r in receipts(report)]


def fairer(receipt):
    """The proposed plan's DECIMAL128 variance is below its own reference plan's."""
    body = receipt["body"]
    return Decimal(body["proposed"]["variance"]) < Decimal(body["reference"]["variance"])


def operation(report, name="daily proposal"):
    found = [o for o in report["operations"] if o["operation"] == name]
    check(len(found) == 1, f"one {name} operation expected")
    return found[0]


def container(report, prefix):
    found = [c for c in report["containers"] if c["name"].startswith(prefix)]
    check(len(found) == 1, f"one {prefix} container expected")
    return found[0]


def load():
    document = json.loads(EVIDENCE.read_text(encoding="utf-8-sig"))
    images = document["images"]
    check(sorted(images) == sorted(IMAGES), "two images expected")
    for name in IMAGES:
        check(images[name]["commit"] == COMMITS[name], name + ": unexpected commit")
        check(images[name]["tag"].endswith(COMMITS[name][:12]), name + ": tag must name its commit")
    # Every replica the run inspected ran its image; daily replicas carried the receipts and Timefold at INFO.
    verified = document["containersVerified"]
    check({v["image"] for v in verified} == set(IMAGES), "both images verified")
    for v in verified:
        # The run recorded each booking replica's name as a one-element list (a script quirk fixed after the run).
        if isinstance(v["container"], list):
            check(len(v["container"]) == 1 and isinstance(v["container"][0], str), "one container per record")
            v["container"] = v["container"][0]
        check(v["imageId"] == images[v["image"]]["imageId"], v["container"] + ": wrong image")
        check("TIMEFOLD_LOG_LEVEL=INFO" in v["environment"], v["container"] + ": Timefold not at INFO")
        if "daily" in v["container"]:
            check("SCHEDULER_STUDY_RECEIPTS=true" in v["environment"], v["container"] + ": receipts off")
            check(v["nanoCpus"] == 2_000_000_000 and v["memory"] == 2 * 1024 ** 3, v["container"] + ": not the daily pool's shape")
    runs = {}
    for entry in document["runs"]:
        check(key(entry) not in runs, f"duplicate run {key(entry)}")
        runs[key(entry)] = entry
    expected = {(1, image, fleet, seed) for image in IMAGES for fleet in FLEETS for seed in SEEDS} | {(2, image, *STEP2) for image in IMAGES}
    check(set(runs) == expected, "every run of both steps expected exactly once")
    total = 0
    for k, entry in runs.items():
        report, step = entry["report"], k[0]
        planners, mode, proposals = (1, "sync", 4) if step == 1 else (2, "async", 12)
        check((entry["planners"], entry["mode"], entry["proposals"]) == (planners, mode, proposals), f"{k}: shape")
        check(entry["commit"] == COMMITS[k[1]], f"{k}: commit")
        s = report["settings"]
        check(s["dailyTechnicians"] == k[2] and s["seed"] == k[3] and s["dailyProposals"] == proposals and s["dailyMode"] == mode
              and s["concurrency"] == planners and s["appointmentsPerDay"] == 3 and s["jobs"] == "cold", f"{k}: settings")
        check(s["policy"] == {"fairnessBudget": "0", "dailySearch": "TABU", "costSearchSeconds": 10, "fairnessSearchSeconds": 5,
                              "bookingDeadlineMs": 5000, "regularWindowThreshold": 2}, f"{k}: policy")
        check(report["contractBreaches"] == [] and report["hostAnomalies"] == [], f"{k}: defects")
        check(report["daily"]["proposals"] == proposals and report["daily"]["decisions"] == {"IMPROVED": proposals}, f"{k}: decisions")
        check(operation(report)["statuses"] == {"201": proposals} and operation(report)["errors"] == {}, f"{k}: statuses")
        check(len(report["daily"]["queueWaitsMs"]) == (proposals if mode == "async" else 0), f"{k}: queue waits")
        study = report["study"]
        check(study is not None and study["summary"]["unpairedSpeeds"] == 0, f"{k}: receipts unpaired")
        check(len(receipts(report)) == proposals == study["summary"]["receipts"], f"{k}: one receipt per proposal")
        check(study["summary"]["unmeasured"] == {}, f"{k}: every plan measured")
        for receipt in receipts(report):
            body = receipt["body"]
            check(body["variant"] == "TABU" and body["seed"] == 17 and body["technicians"] == k[2] and body["visits"] == 3 * k[2], f"{k}: receipt shape")
            check([p["name"] for p in body["phases"]] == list(PHASES) and len(receipt["solves"]) == 2, f"{k}: two phases, two speeds")
            check(body["fairness"] == "SEARCHED" and body["accepted"] is True, f"{k}: fairness searched and accepted")
            # The kit's policy has no fairness allowance, so the ceiling is the reference cost itself.
            check(body["costCeilingCents"] == body["reference"]["costCents"], f"{k}: zero allowance expected")
            check(body["proposed"]["costCents"] <= body["costCeilingCents"], f"{k}: over the ceiling")
            check(body["reference"]["overtimeMinutes"] == 0 == body["proposed"]["overtimeMinutes"], f"{k}: overtime")
        # The kit's summary, recomputed from the receipts.
        summary = study["summary"]
        for phase in summary["phases"]:
            check(phase["movesPerSecond"]["p50"] == p50(speeds(report, phase["phase"])), f"{k}: {phase['phase']} speed median")
            solve = [r["body"]["phases"][PHASES.index(phase["phase"])]["solveMs"] for r in receipts(report)]
            check(phase["solveMs"]["p50"] == p50(solve) and phase["runs"] == proposals, f"{k}: {phase['phase']} solve median")
        for side in ("reference", "proposed"):
            check(summary[f"{side}CostCents"] == p50([r["body"][side]["costCents"] for r in receipts(report)]), f"{k}: {side} cost median")
            check(summary[f"{side}Variance"] == decimal_p50([r["body"][side]["variance"] for r in receipts(report)]), f"{k}: {side} variance median")
        total += proposals
    check(total == RECEIPTS, "96 receipts expected")
    return document, runs


HARNESS = HERE.parent / "evidence" / "solver-matrix-2026-10-20.json"


def harness():
    """Option b's fairness-phase speedup on the workstation harness (PR 187's numbering study), by fleet and overall."""
    document = json.loads(HARNESS.read_text(encoding="utf-8-sig"))
    check(document["manifest"]["revisions"]["n"].startswith("ed97d8b") and document["budgetMs"] == 15000, "the numbering study's evidence expected")
    summary = document["paired"]["summary"]["b"]
    by_fleet = {int(size): entry["median"] for size, entry in summary["fairnessPhaseSpeedupByFleet"].items()}
    check(sorted(by_fleet) == [10, 30, 50] and summary["fairnessPhaseSpeedup"]["pairs"] == 72, "harness fleets 10, 30 and 50 expected")
    return by_fleet, summary["fairnessPhaseSpeedup"]["median"]


def ratio(runs, step, fleet, seed, phase):
    """After over before on one phase: each run's median proposal speed, the kit's p50."""
    before = p50(speeds(runs[(step, "before", fleet, seed)]["report"], phase))
    after = p50(speeds(runs[(step, "after", fleet, seed)]["report"], phase))
    return after / before


if __name__ == "__main__":
    load()
    print("evidence valid:", EVIDENCE.name)
