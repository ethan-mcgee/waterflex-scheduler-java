"""Validate and load the immutable daily search follow-up study: main arm, long budget arm and sequential confirmation."""
from collections import Counter
from hashlib import sha256
import importlib.util
import json
import math
from pathlib import Path
from statistics import mean
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "infra"))
from experiment_config import digest

RUNS = ROOT / "experiments/runs"
MAIN = RUNS / "20261013T052707Z-daily-search-followup-6454fb05"
MAIN_ANALYSIS = MAIN / "analysis/20261013T103623Z-5d47be01"
LONG = RUNS / "20261013T103709Z-daily-search-long-budget-a317757e"
LONG_ANALYSIS = LONG / "analysis/20261013T143907Z-ca1aefff"
CONFIRM = RUNS / "20261013T143914Z-daily-search-confirm-tabu-kopt-size-3-877fd9f7"
CONFIRM_ANALYSIS = CONFIRM / "analysis/20261013T154455Z-ee4e5912"
DRIVER_STATUS = ROOT / "experiments/runs/bench-2026-10-12/status.json"
REVISION = "c21b54b36f9c0e73ef2b17584b531a58ad74781d"
# Case key order: fleet, workload, fixture seed, search seed, budget in ms, solver. Everything but the solver identifies a matched pair.
KEY = ("fleet", "workload", "fixture_seed", "seed", "budget_ms", "solver")
ACCEPTED = ("COST_REDUCTION", "FAIRNESS_IMPROVEMENT")
REJECTED = ("FAIRNESS_REGRESSION_WITHIN_ALLOWANCE", "NO_POLICY_IMPROVEMENT")


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def variance(fairness):
    """Capacity weighted variance of utilization, recomputed from paid minutes rather than trusted from the receipt."""
    capacity = sum(w["regularCapacityMinutes"] for w in fairness["workloads"])
    utilization = sum(w["paidMinutes"] for w in fairness["workloads"]) / capacity
    return sum(w["regularCapacityMinutes"] * (w["paidMinutes"] / w["regularCapacityMinutes"] - utilization) ** 2
               for w in fairness["workloads"]) / capacity


def archive(run, analysis, count):
    """Every raw result of one COST_ONLY run, checked against its manifest, matrix and analysis input hashes."""
    manifest, config, cases = read(run / "manifest.json"), read(run / "original-config.json"), read(run / "cases.json")
    assert manifest["revision"] == REVISION
    assert digest(config) == manifest["config_hash"] and digest(cases) == manifest["cases_hash"]
    assert config == read(run / "config.json")
    assert len(cases) == len({c["id"] for c in cases}) == count
    daily = config["daily"]
    assert daily["phases"] == "COST_ONLY"
    expected = {(f, w, x, s, b * 1000, v) for f in daily["fleets"] for w in daily["workloads"] for x in daily["fixture_seeds"]
                for s in daily["seeds"] for b in daily["budgets_seconds"] for v in daily["solvers"]}
    assert {tuple(c[k] for k in KEY) for c in cases} == expected
    assert all(c["phases"] == "COST_ONLY" for c in cases)
    provenance = read(analysis / "analysis-provenance.json")
    assert len(provenance["inputs"]) == count
    summary = read(analysis / "summary.json")
    assert summary["expected_cases"] == summary["observed_cases"] == count and summary["issues"] == []
    assert summary["pair_status_counts"] == {"paired": count}
    by_id = {c["id"]: c for c in cases}
    rows = []
    for item in provenance["inputs"]:
        path = run / item["name"].replace("\\", "/")
        assert sha256(path.read_bytes()).hexdigest() == item["sha256"], path
        receipt = read(path.parent / "completed.json")
        assert receipt["raw_sha256"] == item["sha256"]
        results = [r for r in map(json.loads, path.read_text().splitlines()) if r.get("type") == "result"]
        assert len(results) == 1, path
        raw, case = results[0], by_id[path.parts[-3]]
        key = tuple(case[k] for k in KEY)
        assert (raw["technicians"], raw["workload"], raw["fixtureSeed"], raw["seed"], raw["budgetMs"], raw["variant"]) == key
        assert raw["phases"] == "COST_ONLY" and "fairnessPhase" not in raw, path
        assert raw["violations"] == 0
        stats = raw["referencePhase"]
        # COST_ONLY gives the cost search the whole budget instead of two thirds.
        assert stats["termination"] == "TIME_LIMIT" and stats["budgetMs"] == case["budget_ms"] and stats["solveMs"] > 0
        assert stats["steps"] > 0 and stats["moveEvaluations"] > 0 and stats["timeToBestMs"] is not None
        # With no fairness phase the policy compares the start with the reference only. It keeps the reference when accepted
        # and the starting schedule otherwise (SchedulingPolicy.compare: a start within the 2% ceiling may not lose balance).
        if raw["acceptanceReason"] in ACCEPTED:
            assert raw["after"] == raw["reference"], path
        else:
            assert raw["acceptanceReason"] in REJECTED and raw["after"] == raw["before"], (path, raw["acceptanceReason"])
            if raw["acceptanceReason"] == "FAIRNESS_REGRESSION_WITHIN_ALLOWANCE":
                assert raw["before"]["costCents"] <= raw["costCeilingCents"]
                assert raw["reference"]["fairness"]["variance"] > raw["before"]["fairness"]["variance"]
        assert raw["costCeilingCents"] == raw["reference"]["costCents"] * 102 // 100
        for stage in ("before", "reference", "after"):
            fairness = raw[stage]["fairness"]
            assert raw[stage]["overtimeMinutes"] == 0 and len(fairness["workloads"]) == raw["technicians"]
            assert math.isclose(variance(fairness), fairness["variance"], abs_tol=1e-12)
        assert receipt["full_load_fraction"] is not None
        rows.append({**dict(zip(KEY, key)), "key": key, "raw": raw, "receipt": receipt})
    assert {r["key"] for r in rows} == expected
    control = {r["key"][:-1]: r for r in rows if r["solver"] == daily["control"]}
    for r in rows:
        twin = control[r["key"][:-1]]["raw"]
        assert twin["datasetFingerprint"] == r["raw"]["datasetFingerprint"] and twin["before"] == r["raw"]["before"]
    # A fixture is fleet, workload and fixture seed; its fingerprint must not depend on the search seed or budget.
    fixtures = {}
    for r in rows:
        fixtures.setdefault((r["fleet"], r["workload"], r["fixture_seed"]), set()).add(r["raw"]["datasetFingerprint"])
    assert all(len(v) == 1 for v in fixtures.values())
    return config, rows


def load():
    main_config, main = archive(MAIN, MAIN_ANALYSIS, 1200)
    long_config, long = archive(LONG, LONG_ANALYSIS, 192)
    confirm_config, confirm = archive(CONFIRM, CONFIRM_ANALYSIS, 32)
    assert main_config["daily"]["parallel_cases"] == long_config["daily"]["parallel_cases"] == 6
    assert confirm_config["daily"]["parallel_cases"] == 1
    assert main_config["daily"]["control"] == "TABU" and long_config["daily"]["control"] == "TABU_KOPT"
    status = read(DRIVER_STATUS)
    assert status["stage"] == "DONE" and status["problems"] == [] and status["expected_head"] == REVISION
    decision = status["decisions"]["confirmation"]
    assert decision["candidate"] == "TABU_KOPT_SIZE_3" and decision["detail"]["control"] == "TABU"
    assert confirm_config["daily"]["solvers"] == ["TABU", decision["candidate"]]
    # The fixtures of the three arms must not overlap, except that the confirmation reuses main arm fixture seed 1.
    prints = {name: {r["raw"]["datasetFingerprint"] for r in rows} for name, rows in (("main", main), ("long", long), ("confirm", confirm))}
    assert not prints["main"] & prints["long"] and prints["confirm"] <= prints["main"]
    return {"main": (main_config, main), "long": (long_config, long), "confirm": (confirm_config, confirm), "decision": decision}


def earlier():
    """The first Tabu variant study (5d15129b), loaded and validated by its own immutable loader, for cross-study comparison only."""
    spec = importlib.util.spec_from_file_location("tabu_variants_5d15129b", ROOT / "docs/daily-tabu-variants-5d15129b/report_data.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    config, rows, _, _ = module.load()
    assert len(rows) == 4480 and config["daily"]["parallel_cases"] == 6
    return [{**dict(zip(("fleet", "workload", "seed", "budget_ms", "solver"), r["key"])), "key": r["key"], "raw": r["raw"]} for r in rows]


def summary():
    data = load()
    for arm in ("main", "long", "confirm"):
        config, rows = data[arm]
        control = config["daily"]["control"]
        twins = {r["key"][:-1]: r for r in rows if r["solver"] == control}
        print(arm, len(rows), "fixtures", len({r["raw"]["datasetFingerprint"] for r in rows}),
              "acceptance", dict(Counter(r["raw"]["acceptanceReason"] for r in rows)),
              "full load >= 0.95", sum(r["receipt"]["full_load_fraction"] >= .95 for r in rows))
        for solver in config["daily"]["solvers"]:
            if solver == control:
                continue
            savings = [(twins[r["key"][:-1]]["raw"]["after"]["costCents"] - r["raw"]["after"]["costCents"]) / 100 for r in rows if r["solver"] == solver]
            print(f"  {solver} vs {control}", len(savings), round(mean(savings), 2), sum(s > 0 for s in savings), sum(s == 0 for s in savings), sum(s < 0 for s in savings))


if __name__ == "__main__":
    summary()
