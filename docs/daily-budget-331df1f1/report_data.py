"""Validate and summarize the immutable 331df1f1 daily experiment archive."""
from collections import Counter
from csv import DictReader
from hashlib import sha256
import json
from pathlib import Path
from statistics import mean
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "infra"))
from experiment_config import digest

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "experiments/runs/20261001T065847Z-daily-budget-331df1f1"
ANALYSIS = RUN / "analysis/20261001T113647Z-4d5d82e8"


def load():
    manifest = json.loads((RUN / "manifest.json").read_text())
    config = json.loads((RUN / "original-config.json").read_text())
    cases = json.loads((RUN / "cases.json").read_text())
    assert digest(config) == manifest["config_hash"]
    assert digest(cases) == manifest["cases_hash"]
    assert config == json.loads((RUN / "config.json").read_text())
    assert len(cases) == len({c["id"] for c in cases}) == 1280
    expected = {(fleet, workload, seed, budget * 1000, solver)
                for fleet in config["daily"]["fleets"]
                for workload in config["daily"]["workloads"]
                for seed in config["daily"]["seeds"]
                for budget in config["daily"]["budgets_seconds"]
                for solver in config["daily"]["solvers"]}
    assert {(c["fleet"], c["workload"], c["seed"], c["budget_ms"], c["solver"])
            for c in cases} == expected
    provenance = json.loads((ANALYSIS / "analysis-provenance.json").read_text())
    assert len(provenance["inputs"]) == 1280
    assert manifest["files"]
    raw_by_id = {}
    for item in provenance["inputs"]:
        path = RUN / item["name"].replace("\\", "/")
        assert sha256(path.read_bytes()).hexdigest() == item["sha256"], path
        result = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
        result = [entry for entry in result if entry.get("type") == "result"]
        assert len(result) == 1, path
        raw_by_id[path.parts[-3]] = result[0]
    with (ANALYSIS / "cases.csv").open(newline="") as file:
        rows = list(DictReader(file))
    assert len(rows) == 1280
    by_case = {(c["fleet"], c["workload"], c["seed"], c["budget_ms"], c["solver"]): c for c in cases}
    for row in rows:
        key = (int(row["fleet"]), row["workload"], int(row["seed"]), int(row["budget_ms"]), row["solver"])
        assert key in by_case
        raw = raw_by_id[by_case[key]["id"]]
        assert raw["variant"] == row["solver"] and raw["seed"] == int(row["seed"])
        assert raw["budgetMs"] == int(row["budget_ms"])
        assert raw["violations"] == 0
        assert raw["referencePhase"]["termination"] == "TIME_LIMIT"
        assert raw["fairnessPhase"]["termination"] == "TIME_LIMIT"
        assert row["fixture"] == raw["datasetFingerprint"]
        assert int(row["accepted_cost"]) == raw["after"]["costCents"]
        assert int(row["reference_cost"]) == raw["reference"]["costCents"]
        assert abs(float(row["fairness"]) - float(raw["after"]["fairness"]["variance"])) < 1e-12
        assert row["pair_status"] == "paired"
        assert row["accepted_cost"] and row["reference_cost"] and row["fairness"] and row["elapsed_ms"]
        row["raw"] = raw
        row["key"] = key
    assert len({r["fixture"] for r in rows}) == 8
    return config, rows


def summary():
    config, rows = load()
    index = {(r["fleet"], r["workload"], r["seed"], r["budget_ms"], r["solver"]): r for r in rows}
    assert len(index) == 1280
    print("cases", len(rows), "violations", sum(r["raw"]["violations"] for r in rows))
    print("termination", Counter((r["raw"]["referencePhase"]["termination"], r["raw"]["fairnessPhase"]["termination"]) for r in rows))
    print("reasons", Counter(r["raw"]["acceptanceReason"] for r in rows))
    print("fixtures", len({r["fixture"] for r in rows}))
    for solver in config["daily"]["solvers"][1:]:
        pairs = []
        for r in rows:
            if r["solver"] != solver: continue
            control = index[(r["fleet"], r["workload"], r["seed"], r["budget_ms"], "TABU")]
            assert control["fixture"] == r["fixture"]
            delta = int(control["accepted_cost"]) - int(r["accepted_cost"])
            pairs.append((r, control, delta))
        print("\n", solver, "overall", len(pairs), "mean saving cents", round(mean(p[2] for p in pairs), 2),
              "wins ties losses", sum(p[2]>0 for p in pairs), sum(p[2]==0 for p in pairs), sum(p[2]<0 for p in pairs),
              "range", min(p[2] for p in pairs), max(p[2] for p in pairs))
        for fleet in (5,10,20,50):
            for workload in ("CLUSTERED","DISPERSED"):
                sub=[p for p in pairs if int(p[0]["fleet"])==fleet and p[0]["workload"]==workload]
                print(fleet,workload,"saving",round(mean(p[2] for p in sub),2),"wins",sum(p[2]>0 for p in sub),"losses",sum(p[2]<0 for p in sub),
                      "fairness delta",round(mean(float(p[1]["fairness"])-float(p[0]["fairness"]) for p in sub),5),
                      "ref rate",round(mean(float(p[0]["reference_move_rate"]) for p in sub)))
        for budget in (60000,90000,120000,240000):
            sub=[p for p in pairs if int(p[0]["budget_ms"])==budget]
            print("budget",budget//1000,"saving",round(mean(p[2] for p in sub),2),"wins",sum(p[2]>0 for p in sub))
    print("elapsed range",min(int(r["elapsed_ms"]) for r in rows),max(int(r["elapsed_ms"]) for r in rows))
    print("reference vs accepted",round(mean(int(r["accepted_cost"])-int(r["reference_cost"]) for r in rows),2))


if __name__ == "__main__":
    summary()
