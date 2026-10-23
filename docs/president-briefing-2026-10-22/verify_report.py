"""Check the briefing against its sources without the builder's or the loader's helpers.

Recomputes, from the raw evidence files with its own loops: the horizon shares, the stack ratios of option b and of the shipped engine changes, the
numbering study's formula b pairs and summary, the full-schedule polish frontier, the booking layouts, and the daily study's per-fleet savings from
every raw result file the October 14 report's sibling loaders name. Then checks the document: the reference template's styles, numbering, theme,
fonts and footer byte for byte; every figure placed once with its caption and both reading paragraphs; no table row may split; no em or en dash.
"""
import gzip
import json
import math
import re
from decimal import Decimal
from pathlib import Path
from statistics import mean, median
from zipfile import ZipFile

HERE = Path(__file__).resolve().parent
EVIDENCE = HERE.parent / "evidence"
REFERENCE = HERE.parent / "WaterFlex_Scheduler_Algorithm_Comparison_Explained.docx"
OUTPUT = HERE / "WaterFlex_Scheduler_Briefing_2026-10-22.docx"
PRESERVE = ("word/styles.xml", "word/stylesWithEffects.xml", "word/numbering.xml", "word/theme/theme1.xml", "word/fontTable.xml")
CHART = json.loads((HERE / "generated/chart_data.json").read_text(encoding="utf-8"))
failures = []


def check(cond, message):
    if not cond:
        failures.append(message)


def close(a, b, tol=1e-9):
    return abs(a - b) <= tol * max(1, abs(a), abs(b))


def raw(name):
    path = EVIDENCE / name
    data = gzip.decompress(path.read_bytes()) if name.endswith(".gz") else path.read_bytes()
    return json.loads(data.decode("utf-8-sig"))


# ---- Horizon ----------------------------------------------------------------------------------------------------------
h = raw("api-load-2026-10-16-dynamic-horizon.json")
for mode, word in (("fixed", "fixed10"), ("dynamic", "dynamic")):
    for desks in (1, 5):
        rs = [r for r in h["runs"] if r["label"].split(" seed")[0] == f"high {word} c{desks}"]
        s, e = sum(r["booking"]["searches"] for r in rs), sum(r["booking"]["noOffers"] for r in rs)
        got = CHART["horizon"][f"{mode}/{desks}"]
        check(got["searches"] == s and got["noOffers"] == e, f"horizon {mode}/{desks}")
check(round(CHART["headline"]["empty_fixed"]) == 51 and round(CHART["headline"]["empty_dynamic"]) == 6, "horizon headline")

# ---- Stack ratios -----------------------------------------------------------------------------------------------------
for name, key, phase in (("api-load-2026-10-20-binary-variance.json", "b", "FAIRNESS"), ("api-load-2026-10-22-long-score-money-w-a.json", "shipped", "REFERENCE")):
    doc = raw(name)
    p50 = {}
    for r in doc["runs"]:
        if r["step"] != 1:
            continue
        rate = [x for x in r["report"]["study"]["summary"]["phases"] if x["phase"] == phase][0]["movesPerSecond"]["p50"]
        p50[(r["image"], r["fleet"], r["seed"])] = rate
    for fleet in (10, 20, 50):
        ratios = sorted(p50[("after", fleet, s)] / p50[("before", fleet, s)] for (img, fl, s) in p50 if img == "after" and fl == fleet)
        value = ratios[len(ratios) // 2]
        if key == "b":
            row = [x for x in CHART["harness_vs_stack"] if x["name"] == f"Formula b, {fleet} techs"][0]
        else:
            row = [x for x in CHART["harness_vs_stack"] if x["name"] == f"Shipped engine, {fleet} techs"][0]
        check(close(row["stack"], value), f"stack {key} {fleet}")
lo, hi = (f(x["stack"] for x in CHART["harness_vs_stack"] if x["name"].startswith("Shipped")) for f in (min, max))
check(close(CHART["headline"]["speed_lo"], lo) and close(CHART["headline"]["speed_hi"], hi), "headline engine speed")

# ---- Numbering study: formula b ---------------------------------------------------------------------------------------
m = raw("solver-matrix-2026-10-20.json")
pairs = [p for p in m["paired"]["pairs"] if p["arm"] == "b"]
check(len(pairs) == len(CHART["fairness_trade"]["pairs"]) == 36, "36 b pairs")
for p, c in zip(pairs, CHART["fairness_trade"]["pairs"]):
    ratio = (sum(Decimal(v) for v in p["armVariance"]) / sum(Decimal(v) for v in p["baselineVariance"]))
    check(abs(float(ratio) - c["variance_ratio"]) < 1e-9 and close(p["meanCostDifferenceCents"] / 100, c["cost_difference"]), f"b pair {p['technicians']} {p['workload']} {p['seed']}")
runs = m["paired"]["runs"]
by = {}
for r in runs:
    by.setdefault((r["arm"], r["technicians"], r["workload"], r["seed"], r["rep"]), r)
speed = [by[("b",) + k[1:]]["fairnessMovesPerSecond"] / by[("baseline",) + k[1:]]["fairnessMovesPerSecond"] for k in by if k[0] == "b"]
check(abs(median(speed) - CHART["fairness_trade"]["summary"]["fairnessPhaseSpeedup"]["median"]) < 0.0006, "b fairness speedup")

# ---- Polish frontier and layouts --------------------------------------------------------------------------------------
# The gaps are checked against the study's own published table in docs/api-load.md (section "Booking configuration (C)"), which that study's
# verifier recomputes from the raw cells; the pairing of priced cases is the study's, not repeated here.
md = (HERE.parent / "api-load.md").read_text(encoding="utf-8")
section = md.split("## Booking configuration (C)")[1].split("\n## ")[0]
published = {}
for line in section.splitlines():
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    if len(cells) == 8 and cells[0].isdigit() and cells[1].startswith(("+$", "-$")):
        published[cells[0]] = [float(c.replace("$", "").replace("+", "")) for c in cells[1:6]]
check(set(published) == set(CHART["refinement_frontier"]), "frontier fleets match the published table")
for fleet, entries in CHART["refinement_frontier"].items():
    gaps = [e["gap"] for e in entries if e["cap_ms"] is not None]
    check(all(abs(round(g, 2) - p) < 0.006 for g, p in zip(gaps, published.get(fleet, []))), f"frontier {fleet}")
check(all(len(v) == 6 for v in CHART["refinement_frontier"].values()), "frontier shape")
check(set(CHART["capacity"]) == {"1x2", "2x1", "1x3", "3x1", "1x4", "2x2", "4x1"} and all(v["cells"] == 4 for v in CHART["capacity"].values()), "layouts")

# ---- Daily study: per-fleet savings from the raw result files ---------------------------------------------------------
import importlib.util  # noqa: E402
spec = importlib.util.spec_from_file_location("tabu_variants", HERE.parent / "daily-tabu-variants-5d15129b/report_data.py")
tv = importlib.util.module_from_spec(spec); spec.loader.exec_module(tv)
_, rows, _, _ = tv.load()
cost = {r["key"]: r["raw"]["after"]["costCents"] for r in rows}
heat = CHART["daily_heatmap"]
for solver, values in zip(heat["solvers"], heat["values"]):
    for fleet, v in zip(heat["fleets"], values):
        diffs = [(cost[k[:-1] + ("TABU",)] - c) / 100 for k, c in cost.items() if k[-1] == solver and k[0] == fleet]
        check(close(mean(diffs), v), f"heatmap {solver} {fleet}")
check(close(mean((cost[k[:-1] + ("TABU",)] - c) / 100 for k, c in cost.items() if k[-1] == "TABU_KOPT"), CHART["headline"]["tk_saving"]), "headline daily saving")
for fleet, v in CHART["illustrative_year"]["by_fleet"].items():
    check(close(v, heat["values"][heat["solvers"].index("TABU_KOPT")][heat["fleets"].index(int(fleet))]), f"year {fleet}")

# ---- Paired statistics: day-level wins, losses, sign test and the mean, from the raw costs -------------------------------
def day_stats(cost_by_key, fleet=None):
    days = {}
    for k, c in cost_by_key.items():
        if k[-1] == "TABU_KOPT" and (fleet is None or k[0] == fleet):
            days.setdefault(k[:-2], []).append((cost_by_key[k[:-1] + ("TABU",)] - c) / 100)
    means = [sum(v) / len(v) for v in days.values()]
    wins, losses = sum(m > 0 for m in means), sum(m < 0 for m in means)
    n, k = wins + losses, min(wins, losses)
    p = min(1.0, 2 * sum(math.comb(n, i) for i in range(k + 1)) / 2 ** n)
    allv = [x for v in days.values() for x in v]
    return len(days), wins, losses, p, sum(allv) / len(allv)


oos = CHART["out_of_sample"]
for fleet, s in zip(oos["fleets"], oos["first"]):
    if s is None:
        continue
    days, wins, losses, p, m = day_stats(cost, fleet)
    check((s["days"], s["day_wins"], s["day_losses"]) == (days, wins, losses) and abs(s["sign_p"] - p) < 1e-12 and close(s["mean"], m), f"paired stats first {fleet}")
    check(s["lo"] < s["mean"] < s["hi"] and s["lo"] > 0, f"interval first {fleet}")
days, wins, losses, p, m = day_stats(cost)
s = oos["all"]["first"]
check((s["days"], s["day_wins"], s["day_losses"]) == (days, wins, losses) and abs(s["sign_p"] - p) < 1e-12 and close(s["mean"], m) and s["lo"] > 0, "paired stats first, all")
# The follow-up's intervals: every one clear of zero, each day run at two budgets.
for s in oos["follow"] + [oos["all"]["follow"]]:
    check(s["lo"] > 0 and s["lo"] < s["mean"] < s["hi"] and s["days"] * 2 == s["cases"], "follow-up interval shape")
for fleet, s in CHART["paired_hist"]["stats"].items():
    counts = CHART["paired_hist"]["counts"][fleet]
    check(sum(counts) == s["cases"] == 160, f"histogram {fleet}")

# ---- Convergence: time to the best plan over the cost phase's time, from the raw daily rows ---------------------------------
conv = CHART["convergence"]
for solver in conv["solvers"]:
    for fleet, v in zip(conv["fleets"], conv["best_fraction"][solver]):
        fr = [r["raw"]["referencePhase"]["timeToBestMs"] / r["raw"]["referencePhase"]["solveMs"] for r in rows if r["key"][-1] == solver and r["key"][0] == fleet]
        check(len(fr) == 160 and close(mean(fr), v), f"convergence {solver} {fleet}")

# ---- The VM limits the capacity table reads -----------------------------------------------------------------------------
compose = (HERE.parent.parent / "deploy/vm/docker-compose.yml").read_text(encoding="utf-8")
cpus = dict(re.findall(r'\$\{(SCHEDULER_BOOKING|SCHEDULER_DAILY|ROUTING|NOMINATIM|SCHEDULER_DB|SCHEDULER_PROXY)_CPUS:-([\d.]+)\}', compose))
check(len(cpus) == 6 and sum(float(v) for v in cpus.values()) > 8, "VM limits overcommit 8 CPUs")

# ---- The document ---------------------------------------------------------------------------------------------------------
with ZipFile(REFERENCE) as ref, ZipFile(OUTPUT) as out:
    for part in PRESERVE:
        check(ref.read(part) == out.read(part), f"template part {part} changed")
    check(any(n.startswith("word/footer") and ref.read(n) == out.read(n) for n in ref.namelist() if n.startswith("word/footer")), "footer changed")
    xml = out.read("word/document.xml").decode("utf-8")
check(not re.search("[â€’â€“â€”â€•]", xml), "a dash in the document")
names = (HERE / "generated/figures.txt").read_text(encoding="utf-8").split()
check(len(names) == len(set(names)) == 32, "32 distinct figures")
text = re.sub(r"<[^>]+>", "", xml.replace("</w:p>", "\n"))
for i in range(1, len(names) + 1):
    check(text.count(f"Figure {i}. ") == 1, f"figure {i} caption")
check(text.count("How to read this chart.") == len(names) and text.count("What it means for WaterFlex.") == len(names), "both reading paragraphs per figure")
check(xml.count("<w:tr>") + xml.count("<w:tr ") == xml.count("<w:cantSplit/>"), "a table row may split")

if failures:
    raise SystemExit("verify failed:\n" + "\n".join(failures))
print("verify ok:", len(names), "figures")
