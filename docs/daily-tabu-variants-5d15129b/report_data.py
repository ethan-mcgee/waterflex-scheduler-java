"""Validate and load the immutable TABU variant study, its contention checks and its sequential confirmation."""
from collections import Counter
from hashlib import sha256
import json
import math
from pathlib import Path
from statistics import mean
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "infra"))
from experiment_config import digest

RUNS = ROOT / "experiments/runs"
RUN = RUNS / "20261010T040557Z-daily-tabu-variants-p6-5d15129b"
ANALYSIS = RUN / "analysis/20261011T070344Z-a8941f51"
CONFIRM = RUNS / "20261011T070444Z-daily-tabu-confirm-tabu-kopt-3a77f220"
CONFIRM_ANALYSIS = CONFIRM / "analysis/20261011T133321Z-7e74d4fc"
SEQUENTIAL = RUNS / "20261010T024747Z-daily-contention-1-772fc235"
CONTENTION = {10: RUNS / "20261010T033659Z-daily-contention-10-575575c0/contention/20261010T034327Z-9e662ac4.json",
              6: RUNS / "20261010T034327Z-daily-contention-6-df602432/contention/20261010T035157Z-df099bf5.json",
              4: RUNS / "20261010T035157Z-daily-contention-4-bdad2801/contention/20261010T040432Z-740c3450.json"}
REVISION = "99be00b031678a353ad445222b07ccb5f3f9ef92"


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def archive(run, analysis, count):
    """Every raw result of one run, checked against its manifest, matrix and analysis input hashes."""
    manifest, config, cases = read(run / "manifest.json"), read(run / "original-config.json"), read(run / "cases.json")
    assert manifest["revision"] == REVISION
    assert digest(config) == manifest["config_hash"] and digest(cases) == manifest["cases_hash"]
    assert config == read(run / "config.json")
    assert len(cases) == len({c["id"] for c in cases}) == count
    daily = config["daily"]
    expected = {(f, w, s, round(b * 1000), v) for f in daily["fleets"] for w in daily["workloads"]
                for s in daily["seeds"] for b in daily["budgets_seconds"] for v in daily["solvers"]}
    assert {(c["fleet"], c["workload"], c["seed"], c["budget_ms"], c["solver"]) for c in cases} == expected
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
        assert (path.parent / "completed.json").exists()
        results = [r for r in map(json.loads, path.read_text().splitlines()) if r.get("type") == "result"]
        assert len(results) == 1, path
        raw, case = results[0], by_id[path.parts[-3]]
        key = (case["fleet"], case["workload"], case["seed"], case["budget_ms"], case["solver"])
        assert (raw["technicians"], raw["workload"], raw["seed"], raw["budgetMs"], raw["variant"]) == key
        assert raw["violations"] == 0 and raw["after"]["costCents"] <= raw["costCeilingCents"]
        assert raw["costCeilingCents"] == raw["reference"]["costCents"] * 102 // 100
        for phase in ("referencePhase", "fairnessPhase"):
            stats = raw[phase]
            assert stats["termination"] == "TIME_LIMIT" and stats["solveMs"] > 0
            assert stats["steps"] > 0 and stats["moveEvaluations"] > 0 and stats["timeToBestMs"] is not None
        assert raw["referencePhase"]["budgetMs"] == case["budget_ms"] * 2 // 3
        for stage in ("before", "reference", "after"):
            fairness = raw[stage]["fairness"]
            assert raw[stage]["overtimeMinutes"] == 0 and len(fairness["workloads"]) == raw["technicians"]
            capacity = sum(w["regularCapacityMinutes"] for w in fairness["workloads"])
            utilization = sum(w["paidMinutes"] for w in fairness["workloads"]) / capacity
            variance = sum(w["regularCapacityMinutes"] * (w["paidMinutes"] / w["regularCapacityMinutes"] - utilization) ** 2
                           for w in fairness["workloads"]) / capacity
            assert math.isclose(variance, fairness["variance"], abs_tol=1e-12)
        rows.append({"key": key, "fleet": key[0], "workload": key[1], "seed": key[2], "budget_ms": key[3],
                     "solver": key[4], "raw": raw, "receipt": read(path.parent / "completed.json")})
    assert {r["key"] for r in rows} == expected
    control = {r["key"][:-1]: r for r in rows if r["solver"] == daily["control"]}
    for r in rows:
        twin = control[r["key"][:-1]]["raw"]
        assert twin["datasetFingerprint"] == r["raw"]["datasetFingerprint"] and twin["before"] == r["raw"]["before"]
    return config, rows


def load():
    config, rows = archive(RUN, ANALYSIS, 4480)
    assert config["daily"]["parallel_cases"] == 6
    assert all(r["receipt"]["full_load_fraction"] is not None for r in rows)
    confirm_config, confirm_rows = archive(CONFIRM, CONFIRM_ANALYSIS, 128)
    assert confirm_config["daily"]["solvers"] == ["TABU", "TABU_KOPT"] and confirm_config["daily"]["parallel_cases"] == 1
    contention = {}
    for parallel, path in CONTENTION.items():
        report = read(path)
        assert report["parallel_cases"] == parallel and Path(report["sequential_run"]).name == SEQUENTIAL.name
        assert report["accepted"] is False and report["issues"] == [] and len(report["groups"]) == 8
        contention[parallel] = report
    return config, rows, confirm_rows, contention


def summary():
    config, rows, confirm_rows, contention = load()
    print("cases", len(rows), "fixtures", len({r["raw"]["datasetFingerprint"] for r in rows}))
    print("acceptance", Counter(r["raw"]["acceptanceReason"] for r in rows))
    control = {r["key"][:-1]: r for r in rows if r["solver"] == "TABU"}
    for solver in config["daily"]["solvers"][1:]:
        savings = [(control[r["key"][:-1]]["raw"]["after"]["costCents"] - r["raw"]["after"]["costCents"]) / 100
                   for r in rows if r["solver"] == solver]
        print(solver, len(savings), round(mean(savings), 2), sum(s > 0 for s in savings), sum(s == 0 for s in savings), sum(s < 0 for s in savings))
    print("full load >= 0.95", sum(r["receipt"]["full_load_fraction"] >= .95 for r in rows),
          "min", round(min(r["receipt"]["full_load_fraction"] for r in rows), 3))
    print("confirmation", len(confirm_rows), "contention", {p: round(min(g["reference_median_ratio"] for g in c["groups"]), 3) for p, c in contention.items()})


if __name__ == "__main__":
    summary()
