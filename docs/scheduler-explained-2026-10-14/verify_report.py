"""Verify the built report against its raw sources, without the loader's or builder's helpers.

Recomputes the numerical tables and the chart datasets from the evidence files and experiment archives directly,
compares them cell for cell with the document, and checks the preserved template parts, figure descriptions, table
row splitting and the absence of em and en dashes.
"""
import json
import re
from hashlib import sha256
from pathlib import Path
from statistics import mean, median
from zipfile import ZipFile

from docx import Document

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
EVIDENCE = ROOT / "docs/evidence"
OUTPUT = HERE / "WaterFlex_Scheduler_Explained_2026-10-14.docx"
REFERENCE = HERE.parent / "WaterFlex_Scheduler_Algorithm_Comparison_Explained.docx"
PRESERVE = ("word/styles.xml", "word/stylesWithEffects.xml", "word/numbering.xml", "word/theme/theme1.xml", "word/fontTable.xml")
FIRST = ROOT / "experiments/runs/20261010T040557Z-daily-tabu-variants-p6-5d15129b"
FOLLOW = ROOT / "experiments/runs/20261013T052707Z-daily-search-followup-6454fb05"
OFFERS, CONFIRM = "POST /api/v1/booking/offers 201", "POST /api/v1/booking/holds/{holdId}/confirm 200"
LABEL = {"TABU": "Tabu", "TABU_SIZE_3": "Tabu size 3", "TABU_SIZE_15": "Tabu size 15", "TABU_KOPT": "Tabu + K opt", "TABU_KOPT_SIZE_3": "Tabu + K opt size 3",
         "KOPT": "K opt", "SUBLIST": "Sublist", "LATE_ACCEPTANCE": "Late acceptance"}
checked = []


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def dollars(value):
    return f"-${-value:,.2f}" if value < 0 else f"${value:,.2f}"


def cases(run, keyed_by_fixture_seed):
    """Every raw result of a run, read from the files the analysis provenance names, as (key, variant, after cost cents)."""
    analysis = sorted((run / "analysis").iterdir())[-1]
    out = []
    for item in read(analysis / "analysis-provenance.json")["inputs"]:
        path = run / item["name"].replace("\\", "/")
        assert sha256(path.read_bytes()).hexdigest() == item["sha256"], path
        raw = [r for r in map(json.loads, path.read_text().splitlines()) if r.get("type") == "result"][0]
        key = (raw["technicians"], raw["workload"], raw.get("fixtureSeed") if keyed_by_fixture_seed else None, raw["seed"], raw["budgetMs"])
        out.append((key, raw["variant"], raw["after"]["costCents"]))
    return out


def savings(rows, control):
    twins = {k: c for k, v, c in rows if v == control}
    return [(k, v, (twins[k] - c) / 100) for k, v, c in rows if v != control]


def study_table(rows, solvers, fleets):
    out = []
    for s in solvers:
        own = [(k, x) for k, v, x in rows if v == s]
        out.append([LABEL[s], dollars(mean(x for _, x in own)), f"{sum(x > 0 for _, x in own)} / {sum(x == 0 for _, x in own)} / {sum(x < 0 for _, x in own)}",
                    *[dollars(mean(x for k, x in own if k[0] == f)) for f in fleets]])
    return out


def table_rows(doc, header):
    for t in doc.tables:
        if [c.text for c in t.rows[0].cells] == header:
            return [[c.text for c in r.cells] for r in t.rows[1:]]
    raise AssertionError(header)


def check(name, expected, actual):
    assert expected == actual, (name, expected, actual)
    checked.append(name)


def per_minute(report):
    op = [o for o in report["operations"] if o["operation"] == "booking offers"][0]
    return op["statuses"].get("201", 0) * 60 / report["settings"]["durationS"]


def op(report, name):
    return [o for o in report["operations"] if o["operation"] == name][0]


def main():
    doc = Document(OUTPUT)
    charts = read(HERE / "generated/chart_data.json")

    # Template parts, figures, rows and dashes.
    with ZipFile(REFERENCE) as ref, ZipFile(OUTPUT) as built:
        for name in set(PRESERVE) | {n for n in ref.namelist() if "footer" in n}:
            assert ref.read(name) == built.read(name), name
        xml = built.read("word/document.xml").decode("utf-8")
    assert "—" not in xml and "–" not in xml
    descriptions = re.findall(r'<wp:docPr [^>]*descr="([^"]*)"', xml)
    assert len(descriptions) == 12 and all(d.startswith("Figure ") for d in descriptions), len(descriptions)
    assert xml.count("<w:tr>") + xml.count("<w:tr ") == xml.count("<w:cantSplit/>"), "every row must refuse to split"
    checked.append("template, figures, rows, dashes")

    # The daily paired means by budget (Sept 29).
    summary = read(EVIDENCE / "scheduler-field-2026-09-29/summary.json")
    paired = {(d["budgetSeconds"], d["variant"]): d["meanSavingsCents"] / 100 for d in summary["dailyPaired"]}
    check("sept 29 budgets", [[f"{b} s", *[dollars(paired[(b, v)]) for v in ("CURRENT_UNCAPPED", "KOPT", "SUBLIST", "LATE_ACCEPTANCE", "RUIN_RECREATE")]] for b in (15, 30, 60)],
          table_rows(doc, ["Budget", "Uncapped original", "K opt", "Sublist", "Late acceptance", "Ruin and recreate"]))

    # The two daily studies, from the raw result files.
    first = savings(cases(FIRST, False), "TABU")
    assert len(first) == 4480 * 6 // 7
    check("tabu variant study", study_table(first, ("TABU_SIZE_3", "TABU_SIZE_15", "TABU_KOPT", "KOPT", "SUBLIST", "LATE_ACCEPTANCE"), (5, 10, 20, 50)),
          table_rows(doc, ["Configuration", "Mean saving vs Tabu", "Wins / ties / losses", "5 techs", "10", "20", "50"]))
    follow = savings(cases(FOLLOW, True), "TABU")
    assert len(follow) == 1200 * 4 // 5
    check("follow-up study", study_table(follow, ("TABU_KOPT", "TABU_KOPT_SIZE_3", "TABU_SIZE_3", "KOPT"), (5, 10, 20, 30, 50)),
          table_rows(doc, ["Configuration", "Mean saving vs Tabu", "Wins / ties / losses", "5 techs", "10", "20", "30", "50"]))
    for key, rows, solvers, fleets in (("first", first, charts["daily_fleet"]["first"]["solvers"], (5, 10, 20, 50)), ("follow", follow, charts["daily_fleet"]["follow"]["solvers"], (5, 10, 20, 30, 50))):
        values = [[mean(x for k, v, x in rows if v == s and k[0] == f) for f in fleets] + [mean(x for k, v, x in rows if v == s)] for s in solvers]
        assert all(abs(a - b) < 1e-9 for ra, rb in zip(values, charts["daily_fleet"][key]["values"]) for a, b in zip(ra, rb)), key
    checked.append("daily_fleet chart")

    # The first load measurement (#151) and the fresh-seed comparison (#152).
    baseline = read(EVIDENCE / "api-load-2026-10-13.json")
    concurrency = read(EVIDENCE / "api-load-2026-10-13-booking-concurrency.json")
    base = {r["label"]: r for r in baseline["runs"]}
    fresh = {r["label"]: r for r in concurrency["coldRemeasurement"]["runs"]}
    lat = lambda o: f"{o['ms']['p50']:,} / {o['ms']['p95']:,} ms"
    check("#151 runs", [[name, f"{per_minute(r):.0f}", lat(op(r, "booking offers")), f"{op(r, 'confirm')['ms']['p50']:,} ms", f"{r['booking']['holdUnavailable']} of {r['booking']['selected']}", str(op(r, "booking offers")["retriedBusy"])]
                        for name, r in (("1 desk", base["booking-cold-c1"]), ("5 desks", base["booking-cold-c5"]), ("10 desks", base["booking-cold-c10"]))],
          table_rows(doc, ["Run (#151)", "Searches / min", "Offers p50 / p95", "Confirm p50", "Holds lost", "Waited on 429"]))
    check("#152 desks", [[str(n), f"{b['booking']['holdUnavailable']} of {b['booking']['selected']} / {a['booking']['holdUnavailable']} of {a['booking']['selected']}",
                          f"{op(b, 'booking offers')['ms']['p95'] / 1000:.1f} s / {op(a, 'booking offers')['ms']['p95'] / 1000:.1f} s", f"{per_minute(b):.0f} / {per_minute(a):.0f}", f"{a['booking']['stale'] / a['booking']['searches']:.2f}"]
                         for n, b, a in ((1, base["booking-cold-c1"], fresh["cold-recheck-c1 (seed 93)"]), (5, base["booking-cold-c5"], fresh["cold-recheck-c5 (seed 91)"]), (10, base["booking-cold-c10"], fresh["cold-recheck-c10 (seed 92)"]))],
          table_rows(doc, ["Desks", "Holds lost before / after", "Offers p95 before / after (fresh seeds)", "Searches / min before / after", "STALE resends per search after"]))

    # The routing runs (J0, J) and the chart datasets that use the VM runs.
    vm = {r["label"]: r for r in read(EVIDENCE / "api-load-2026-10-14-vm-baseline.json")["runs"]}
    routing = {r["label"]: r for r in read(EVIDENCE / "api-load-2026-10-14-routing.json")["runs"]}
    priority = {r["label"]: r for r in read(EVIDENCE / "api-load-2026-10-14-confirm-priority-and-jvm.json")["runs"]}
    remote = {r["label"]: r for r in read(EVIDENCE / "api-load-2026-10-14-embedded-vs-remote.json")["runs"]}
    cpu = lambda r, what: [c for c in r["booking"]["containers"] if what in c["name"]][0]["cpuPercent"]["mean"]
    per_leg = lambda r: r["serverBreakdown"][OFFERS]["routingMs"]["p50"] / r["serverBreakdown"][OFFERS]["fetched"]["p50"]
    check("J routing", [[name, str(r["booking"]["booking"]["searches"]), lat(op(r["booking"], "booking offers")), f"{r['serverBreakdown'][OFFERS]['queueMs']['p50']:,} ms", f"{r['serverBreakdown'][OFFERS]['routingMs']['p50']:,} ms", f"{per_leg(r):.2f} ms", f"{cpu(r, 'routing'):.0f}%"]
                        for name, r in (("J0 baseline, flexible", vm["base-spread2"]), ("J, flexible graph", routing["j-flex"]), ("J, CH graph", routing["j-ch"]))],
          table_rows(doc, ["Run", "Searches in 60 s", "Caller p50 / p95", "Slot wait", "Routing", "Per fetched leg", "Routing CPU"]))
    attribution = [("J0 baseline", vm["base-spread2"]), ("J flexible", routing["j-flex"]), ("J CH", routing["j-ch"]), ("J2 shipped", priority["j2-slot"]), ("K remote", remote["k-remote5"])]
    expected = [{"stage": s, "operation": o, "elapsed_p50": r["serverBreakdown"][o]["elapsedMs"]["p50"], **{k: r["serverBreakdown"][o][k]["p50"] for k in ("queueMs", "routingMs", "calculationMs", "dbMs")}} for s, r in attribution for o in (OFFERS, CONFIRM)]
    check("attribution chart", expected, charts["attribution"])
    stages = [("#151", base["booking-cold-c5"]), ("#152", fresh["cold-recheck-c5 (seed 91)"]), ("#154", read(EVIDENCE / "api-load-2026-10-13-search-outcome.json")["runs"][0]),
              ("#155", [r for r in read(EVIDENCE / "api-load-2026-10-13-refinement-shedding.json")["runs"] if r["label"] == "after-c5 (seed 55)"][0]),
              ("J0", vm["base-spread2"]["booking"]), ("J flex", routing["j-flex"]["booking"]), ("J CH", routing["j-ch"]["booking"]), ("J2", priority["j2-slot"]["booking"])]
    assert stages[2][1]["label"] == "booking-cold-c5"
    check("progression chart", [{"stage": s, "label": r["label"], "searches_per_minute": per_minute(r), "p50": op(r, "booking offers")["ms"]["p50"], "p95": op(r, "booking offers")["ms"]["p95"]} for s, r in stages], charts["progression"])
    check("confirm chart", [{"stage": s, "run": run, "confirm_p50": op(r["booking"], "confirm")["ms"]["p50"], "confirm_p95": op(r["booking"], "confirm")["ms"]["p95"], "confirm_wait_p50": r["serverBreakdown"][CONFIRM]["queueMs"]["p50"],
                             "offers_p50": op(r["booking"], "booking offers")["ms"]["p50"], "offers_p95": op(r["booking"], "booking offers")["ms"]["p95"]}
                            for s, run, r in (("J CH", "j-ch", routing["j-ch"]), ("priority", "j2-confirm", priority["j2-confirm"]), ("priority,\nZGC / Parallel", "j2-gc", priority["j2-gc"]),
                                              ("priority +\nconfirm slot", "j2-slot", priority["j2-slot"]), ("slot, compact\nheaders", "j2-coh2", priority["j2-coh2"]))], charts["confirm"])
    check("remote chart", [{"run": run, "offers_p50": op(r["booking"], "booking offers")["ms"]["p50"], "offers_p95": op(r["booking"], "booking offers")["ms"]["p95"], "searches": r["booking"]["booking"]["searches"],
                            "answered_201": op(r["daily"], "daily proposal")["statuses"].get("201", 0), "answered_503": op(r["daily"], "daily proposal")["statuses"].get("503", 0), "daily_cpu_mean": cpu(r, "daily")}
                           for run, r in (("k-embedded", remote["k-embedded"]), ("k-remote", remote["k-remote"]), ("k-remote4", remote["k-remote4"]), ("k-remote5", remote["k-remote5"]))], charts["remote"])

    # The routing benchmarks and the paired solver study.
    for date, name in (("2026-09-24", "routing-ch-omaha-2026-09-24.json"), ("2026-10-14", "routing-ch-2026-10-14.json")):
        bench = read(EVIDENCE / name)
        assert bench["checks"]["exactDirectedParity"] and bench["routable"] == 132
        for engine in ("flexible", "CH-car-v1"):
            rows = [l for l in bench["latency"] if l["engine"] == engine]
            entry = [c for c in charts["ch"] if c["date"] == date and c["engine"] == engine][0]
            assert entry["p50"] == [l["p50Ms"] for l in rows] and entry["p95"] == [l["p95Ms"] for l in rows] and entry["concurrency"] == [l["concurrency"] for l in rows]
    checked.append("ch chart")
    prof = read(EVIDENCE / "solver-profiling-2026-10-14.json")
    pairs = prof["paired"]["pairs"]
    fleet_rows = []
    for fleet in (20, 50):
        fp = [x for x in pairs if x["technicians"] == fleet]
        ratios = [x["costPhaseMovesPerSecond"]["after"] / x["costPhaseMovesPerSecond"]["before"] for x in fp]
        wins = sum(x["afterCostCents"]["after"] < x["afterCostCents"]["before"] for x in fp); ties = sum(x["afterCostCents"]["after"] == x["afterCostCents"]["before"] for x in fp)
        fleet_rows.append([f"{fleet} technicians", f"{median(x['costPhaseMovesPerSecond']['before'] for x in fp) / 1000:.0f}k to {median(x['costPhaseMovesPerSecond']['after'] for x in fp) / 1000:.0f}k ({median(round(r, 3) for r in ratios):.2f}x)",
                           f"{median(x['fairnessPhaseMovesPerSecond']['after'] / x['fairnessPhaseMovesPerSecond']['before'] for x in fp):.2f}x", f"{wins} of 12" + (f" ({ties} ties, {12 - wins - ties} dearer)" if wins < 12 else "")])
    check("paired fleets", fleet_rows, table_rows(doc, ["Fleet", "Cost-phase moves per second, median before to after", "Fairness-phase speedup, median", "Cases cheaper after at the same budget"]))
    assert len(charts["paired"]) == 24 and all(p["before"] == x["costPhaseMovesPerSecond"]["before"] and p["after"] == x["costPhaseMovesPerSecond"]["after"] for p, x in zip(charts["paired"], pairs))
    checked.append("paired chart")

    # The September booking study: service and latency.
    held = {b["variant"]: b for b in summary["booking"] if b["stage"] == "held"}
    assert [h["served"] for h in charts["booking_study"]["held"]] == [held[v]["served"] for v in ("INSERTION", "BOUNDED", "SHARED")]
    evidence = read(ROOT / "docs/booking-experiment-explainer/evidence.json")
    for entry in charts["booking_study"]["latency"]:
        values = sorted(a["elapsed_ms"] for r in evidence["cases"] if r["solver"] == entry["solver"] and r["concurrency"] == entry["concurrency"] for a in r["attempts"] if a["elapsed_ms"] is not None)
        assert values[max(0, -(-len(values) * 95 // 100) - 1)] == entry["p95"], entry  # the explainer's quantile: ceil(n * 0.95) - 1
    checked.append("booking_study chart")

    print("verified", len(checked), "items:", ", ".join(checked))


if __name__ == "__main__":
    main()
