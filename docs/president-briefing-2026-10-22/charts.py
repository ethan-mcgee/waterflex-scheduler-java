"""Compute every figure's data from the validated sources, record it in generated/chart_data.json for verify_report.py, and draw and rasterize the figures.

The briefing reuses five mechanism and data figures of the October 14 report unchanged (two_phase, booking_methods, ch_mechanism, wtl, engine); the rest are
drawn by briefing_figures.py. Every page is HTML with inline SVG in the diagram-design format, rasterized by the October 14 report's render.py.
"""
import json
import math
import random
from pathlib import Path
from statistics import mean, median, stdev

import briefing_figures as bf
from figures import diagrams, thread
from figures.render import render_all

HERE = Path(__file__).resolve().parent
OFFERS = "POST /api/v1/booking/offers 201"


def by_label(runs, label):
    out = [r for r in runs if r["label"] == label]
    assert len(out) == 1, label
    return out[0]


def by_run(runs, run, scenario):
    out = [r for r in runs if r.get("run") == run and r["scenario"] == scenario]
    assert len(out) == 1, (run, scenario)
    return out[0]


def saving(rows, solver, **kw):
    out = [r["saving"] for r in rows if r["solver"] == solver and all(r[k] == v for k, v in kw.items())]
    assert out, (solver, kw)
    return mean(out)


def wtl(rows):
    return sum(r["saving"] > 0 for r in rows), sum(r["saving"] == 0 for r in rows), sum(r["saving"] < 0 for r in rows)


def sign_p(wins, losses):
    """Two-sided exact sign test: the chance of a split at least this lopsided if wins and losses were equally likely."""
    n, k = wins + losses, min(wins, losses)
    return min(1.0, 2 * sum(math.comb(n, i) for i in range(k + 1)) / 2 ** n)


def paired_stats(rows, solver, fleet=None):
    """The paired saving of one solver over Tabu, with an interval that resamples whole synthetic days.

    One day runs at several budgets, so its cases are not independent: the bootstrap draws days (the case key without its budget and
    method: fleet, workload, the fixture where a study has several, and seed) with all their cases, and the sign test counts days by
    their mean saving."""
    cases = [r for r in rows if r["solver"] == solver and (fleet is None or r["fleet"] == fleet)]
    assert cases, (solver, fleet)
    days = {}
    for r in cases:
        assert r["key"][-2] == r["budget_ms"] and r["key"][-1] == solver, r["key"]
        days.setdefault(tuple(r["key"][:-2]), []).append(r["saving"])
    groups = list(days.values())
    rng = random.Random(20261022)
    means = sorted(mean(x for g in (groups[rng.randrange(len(groups))] for _ in groups) for x in g) for _ in range(BOOT))
    day_means = [mean(g) for g in groups]
    wins, losses = sum(v > 0 for v in day_means), sum(v < 0 for v in day_means)
    control = [r["accepted_cost"] for r in rows if r["solver"] == "TABU" and (fleet is None or r["fleet"] == fleet)]
    savings = [r["saving"] for r in cases]
    return {"cases": len(cases), "days": len(groups), "mean": mean(savings), "median": median(savings), "lo": means[int(BOOT * 0.025)], "hi": means[int(BOOT * 0.975) - 1],
            "day_wins": wins, "day_losses": losses, "sign_p": sign_p(wins, losses), "sd_cost": stdev(control), "sd_diff": stdev(savings)}


BOOT = 10000


def compute(data):
    """Every number a figure draws, keyed by figure."""
    perf, runs, extras, benchmarks = data["perf"], data["perf"]["runs"], data["perf"]["extras"], data["perf"]["benchmarks"]
    per_minute = perf["per_minute"]
    F, M = data["daily"]["first"]["rows"], data["daily"]["follow"]["rows"]
    b24, f29, r24 = data["booking24"], data["field29"], data["routing24"]
    rec = {}

    b1, b5, b10 = (by_label(runs["baseline"], f"booking-cold-c{c}") for c in (1, 5, 10))
    fresh = {n: by_label(extras["coldRemeasurement"], f"cold-recheck-c{n} (seed {s})") for n, s in ((1, 93), (5, 91), (10, 92))}
    vm_b = by_run(runs["vm"], "base-spread2", "booking")
    jf_b, jc_b = by_run(runs["routing"], "j-flex", "booking"), by_run(runs["routing"], "j-ch", "booking")
    final = by_run(runs["priority"], "j2-slot", "booking")
    shipped = data["stack"]["shipped"]
    h = data["horizon"]

    rec["headline"] = {"lost_before": b5["booking"]["holdUnavailable"] / b5["booking"]["selected"] * 100, "lost_after": fresh[5]["booking"]["holdUnavailable"],
                       "vm_before": round(per_minute(vm_b)), "vm_after": round(per_minute(final)),
                       "empty_fixed": h[("high", "fixed", 5)]["noOffers"] / h[("high", "fixed", 5)]["searches"] * 100,
                       "empty_dynamic": h[("high", "dynamic", 5)]["noOffers"] / h[("high", "dynamic", 5)]["searches"] * 100,
                       "tk_saving": saving(F, "TABU_KOPT"), "tk_cases": sum(1 for r in F if r["solver"] == "TABU_KOPT"),
                       "speed_lo": min(v["REFERENCE"] for v in shipped.values()), "speed_hi": max(v["REFERENCE"] for v in shipped.values())}
    rec["example_day"] = data["daily_extras"]["example"]

    # The studies on the timeline, with sizes from their own loaders (the budget study and study 3 are quoted and labeled so).
    q = data["quoted"]
    load_runs = sum(len(v) for name, v in runs.items() if name != "remote") + len(extras["coldRemeasurement"]) + len(extras["abComparison"])
    rec["timeline"] = [
        {"name": "Booking methods", "start": "2026-09-23", "end": "2026-09-24", "kind": "booking", "size": f"{b24['requests']:,} requests"},
        {"name": "Field validation", "start": "2026-09-28", "end": "2026-09-29", "kind": "daily", "size": f"{f29['daily_cases']} days, {3 * 480:,} requests"},
        {"name": "Search time budgets", "start": "2026-10-01", "end": "2026-10-01", "kind": "daily", "size": f"{q['budget_study']['cases']:,} days (quoted)"},
        {"name": "Daily search variants", "start": "2026-10-09", "end": "2026-10-10", "kind": "daily", "size": f"{len(F) + len(data['daily']['first']['confirm']):,} days"},
        {"name": "Follow-up on new days", "start": "2026-10-12", "end": "2026-10-13", "kind": "daily",
         "size": f"{len(M) + len(data['daily']['follow']['long']) + len(data['daily']['follow']['confirm']):,} days"},
        {"name": "Load and admission", "start": "2026-10-13", "end": "2026-10-14", "kind": "load", "size": f"{load_runs} runs"},
        {"name": "Engine profile", "start": "2026-10-14", "end": "2026-10-14", "kind": "engine", "size": f"{len(extras['profiling']['pairs'])} paired days"},
        {"name": "Booking horizon", "start": "2026-10-16", "end": "2026-10-16", "kind": "booking", "size": "24 runs"},
        {"name": "Booking configuration", "start": "2026-10-17", "end": "2026-10-19", "kind": "booking", "size": f"{data['config']['cells']:,} cells"},
        {"name": "Time and score types", "start": "2026-10-20", "end": "2026-10-20", "kind": "engine", "size": f"{data['representation']['solves']} solves"},
        {"name": "Fairness measures", "start": "2026-10-20", "end": "2026-10-20", "kind": "daily", "size": f"{data['fairness']['solves']} solves"},
        {"name": "Road matrix numbering", "start": "2026-10-20", "end": "2026-10-20", "kind": "engine", "size": f"{data['numbering']['solves']} solves"},
        {"name": "Long score and money", "start": "2026-10-21", "end": "2026-10-21", "kind": "engine", "size": f"{q['study3']['solves']:,} solves (quoted)"},
        {"name": "Through the public API", "start": "2026-10-20", "end": "2026-10-22", "kind": "load", "size": f"{data['stack']['runs']} runs"},
    ]

    rec["booking_choice"] = {"held": [{"variant": v, "served": f29["booking"][("held", v)]["served"], "requests": f29["booking"][("held", v)]["requests"],
                                       "p95_ms": f29["booking"][("held", v)]["meanCaseP95Ms"]} for v in ("INSERTION", "BOUNDED", "SHARED")],
                             "workload": [{"workload": w, "paid_min": b24["by_workload"][w]["paid_min"], "cost": b24["by_workload"][w]["cost"]} for w in ("CLUSTERED", "DISPERSED", "SPARSE")]}
    rec["refinement_frontier"] = {str(k): v for k, v in data["config"]["frontier"].items()}
    rec["horizon"] = {f"{mode}/{desks}": h[("high", mode, desks)] for mode in ("fixed", "dynamic") for desks in (1, 5)}

    solvers = ("TABU_SIZE_3", "TABU_SIZE_15", "TABU_KOPT", "KOPT", "SUBLIST", "LATE_ACCEPTANCE")
    fleets = (5, 10, 20, 50)
    rec["daily_heatmap"] = {"fleets": list(fleets), "solvers": list(solvers),
                            "values": [[saving(F, s, fleet=fl) for fl in fleets] + [saving(F, s)] for s in solvers]}
    rec["wtl"] = {"chosen": {"first": "TABU_KOPT", "follow": "TABU_KOPT_SIZE_3"}}
    for key, table, ss in (("first", F, solvers), ("follow", M, ("TABU_KOPT", "TABU_KOPT_SIZE_3", "TABU_SIZE_3", "KOPT"))):
        rec["wtl"][key] = [dict(zip(("wins", "ties", "losses"), wtl([r for r in table if r["solver"] == s])), solver=s, mean=saving(table, s)) for s in ss]
    oos = (5, 10, 20, 30, 50)
    rec["out_of_sample"] = {"fleets": list(oos), "first": [paired_stats(F, "TABU_KOPT", fl) if fl != 30 else None for fl in oos],
                            "follow": [paired_stats(M, "TABU_KOPT", fl) for fl in oos],
                            "all": {"first": paired_stats(F, "TABU_KOPT"), "follow": paired_stats(M, "TABU_KOPT")}}
    # The paired savings case by case, in $2.50 bins, one panel per fleet size of the study that chose the method.
    width = 2.5
    tk = [r for r in F if r["solver"] == "TABU_KOPT"]
    lo, hi = math.floor(min(r["saving"] for r in tk) / width), math.ceil(max(r["saving"] for r in tk) / width)
    rec["paired_hist"] = {"width": width, "lo": lo * width, "hi": hi * width, "fleets": list(fleets),
                          "counts": {str(fl): [sum(1 for r in tk if r["fleet"] == fl and lo * width + k * width <= r["saving"] < lo * width + (k + 1) * width) for k in range(hi - lo)]
                                     for fl in fleets},
                          "stats": {str(fl): paired_stats(F, "TABU_KOPT", fl) for fl in fleets}}
    assert all(sum(v) == 160 for v in rec["paired_hist"]["counts"].values())
    # When each search found its best plan, as a share of the cost phase (mean over the study's cases).
    rec["convergence"] = {"fleets": list(fleets), "solvers": ["TABU", "TABU_KOPT", "KOPT", "LATE_ACCEPTANCE"],
                          "best_fraction": {s: [mean(r["best_fraction"] for r in F if r["solver"] == s and r["fleet"] == fl) for fl in fleets]
                                            for s in ("TABU", "TABU_KOPT", "KOPT", "LATE_ACCEPTANCE")}}
    as_sent = data["daily_extras"]["as_sent"]
    budgets = (60000, 90000, 120000, 240000)
    rec["diminishing"] = {"budgets": [b // 1000 for b in budgets],
                          "extra": {str(fl): [as_sent[("TABU_KOPT", fl, b)] - as_sent[("TABU_KOPT", fl, 60000)] for b in budgets] for fl in fleets},
                          "base": {str(fl): as_sent[("TABU_KOPT", fl, 60000)] for fl in fleets},
                          "tabu_base": {str(fl): as_sent[("TABU", fl, 60000)] for fl in fleets}}
    rec["fairness_trade"] = {"pairs": data["numbering"]["pairs"], "summary": data["numbering"]["summary"]["b"]}

    # Study L, the 24 paired solves, as the October 14 report recorded them.
    prof = extras["profiling"]
    rec["engine"] = [{"technicians": p["technicians"], "workload": p["workload"], "seed": p["seed"], "before": p["costPhaseMovesPerSecond"]["before"],
                      "after": p["costPhaseMovesPerSecond"]["after"], "speedup": p["costPhaseSpeedup"], "cost_before": p["afterCostCents"]["before"],
                      "cost_after": p["afterCostCents"]["after"]} for p in prof["pairs"]]
    stages = [(["First", "measurement"], 1, by_label(runs["baseline"], "booking-cold-c5")), (["Lost holds", "fixed"], 1, fresh[5]),
              (["Honest", "status flag"], 1, by_label(runs["outcome"], "booking-cold-c5")), (["Optional pass", "shed"], 1, by_label(runs["shedding"], "after-c5 (seed 55)")),
              (["Rehearsal", "baseline"], 2, vm_b), (["Parallel,", "old graph"], 2, jf_b), (["Fast road", "graph"], 2, jc_b), (["Confirms", "go first"], 2, final)]
    rec["booking_staircase"] = [{"name": n, "half": half, "label": r["label"], "searches_per_minute": per_minute(r), "p50": r["ops"]["booking offers"]["ms"]["p50"],
                                 "p95": r["ops"]["booking offers"]["ms"]["p95"], "p99": r["ops"]["booking offers"]["ms"]["p99"], "max": r["ops"]["booking offers"]["ms"]["max"],
                                 "confirm": {k: r["ops"]["confirm"]["ms"][k] for k in ("p50", "p95", "p99", "max")}} for n, half, r in stages]
    rec["time_split"] = [{"stage": s, **{k: r["breakdown"][OFFERS][k]["p50"] for k in ("queueMs", "routingMs", "calculationMs", "dbMs")}}
                         for s, r in (("Rehearsal baseline", vm_b), ("Parallel, old graph", jf_b), ("Fast road graph", jc_b), ("Confirms go first", final))]
    ch_old = {(l["engine"], l["concurrency"]): l for l in benchmarks["2026-09-24"]["latency"]}
    omaha = r24["omaha"]
    rec["bottleneck"] = {"alone": {"flexible": ch_old[("flexible", 1)]["p50Ms"], "ch": ch_old[("CH-car-v1", 1)]["p50Ms"]},
                         "booking": {"requests": omaha["flexible"]["requests"], "flexible": omaha["flexible"]["served"], "ch": omaha["ch"]["served"]},
                         "october": {"before": vm_b["booking"]["searches"], "after": jc_b["booking"]["searches"]}}
    rec["holds"] = [{"desks": n, "before_lost": b["booking"]["holdUnavailable"], "before_selected": b["booking"]["selected"], "after_lost": a["booking"]["holdUnavailable"],
                     "after_selected": a["booking"]["selected"]} for n, b, a in ((1, b1, fresh[1]), (5, b5, fresh[5]), (10, b10, fresh[10]))]

    rep, fm, nm, s3 = data["representation"]["summary"], data["fairness"]["summary"]["15000"], data["numbering"], q["study3"]
    rec["engine_steps"] = [
        {"name": "Road lookup tables", "phase": "COST PHASE", "ratio": prof["summary"]["costPhaseSpeedup"]["median"], "shipped": True, "note": "24 paired days"},
        {"name": "Time as whole numbers", "phase": "COST PHASE", "ratio": rep["T"]["referencePhaseSpeedup"]["median"], "shipped": True, "note": "not slower in either phase"},
        {"name": "Whole-number score", "phase": "FAIRNESS PHASE", "ratio": rep["S"]["fairnessPhaseSpeedup"]["median"], "shipped": False, "note": "3 days dearer beyond noise"},
        {"name": "Fairness formula b", "phase": "FAIRNESS PHASE", "ratio": fm["b"]["fairnessPhaseSpeedup"]["median"], "shipped": True, "note": "never less fair"},
        {"name": "Fairness formula c", "phase": "FAIRNESS PHASE", "ratio": fm["c"]["fairnessPhaseSpeedup"]["median"], "shipped": False, "note": "failed cost and fairness bars"},
        {"name": "Road matrix numbering", "phase": "COST PHASE", "ratio": nm["fix"]["baseline"]["referencePhaseSpeedup"]["median"], "shipped": True, "note": "not slower in either phase"},
        {"name": "Long score and money", "phase": "COST PHASE", "ratio": s3["lm_over_main"][0], "shipped": True, "note": "quoted, study 3"},
        {"name": "W and A on top", "phase": "COST PHASE", "ratio": s3["wa_over_lm"][0], "shipped": True, "note": "quoted; owner's call, W alone 1.047x"},
        {"name": "Route-walk cascade", "phase": "COST PHASE", "ratio": s3["cr11_c_over_lm"], "shipped": False, "note": "quoted, slower"},
    ]
    harness_b = nm["summary"]["b"]["fairnessPhaseSpeedupByFleet"]
    rec["harness_vs_stack"] = [{"name": f"Formula b, {fl} techs", "harness": harness_b[str(fl)]["median"] if str(fl) in harness_b else None, "stack": data["stack"]["b"][fl]["FAIRNESS"]}
                               for fl in (10, 20, 50)] + \
                              [{"name": f"Shipped engine, {fl} techs", "harness": None, "stack": shipped[fl]["REFERENCE"]} for fl in (10, 20, 50)]
    rec["capacity"] = data["config"]["layouts"]

    # The illustrative year: Tabu + K opt over Tabu by fleet size, one study only, times stated working days.
    rec["illustrative_year"] = {"source": "daily search variant study, Tabu + K opt over Tabu, mean of all budgets", "days": [200, 250, 300],
                                "by_fleet": {str(fl): saving(F, "TABU_KOPT", fleet=fl) for fl in fleets}}
    return rec


def make_charts(data, folder):
    folder.mkdir(exist_ok=True)
    rec = json.loads(json.dumps(compute(data), default=str))
    (folder / "chart_data.json").write_text(json.dumps(rec, indent=2) + "\n", encoding="utf-8")
    draw(rec, folder)
    return rec


def draw(rec, folder, only=None):
    """Draw and rasterize the figures from recorded data; the JSON round trip makes a redraw identical to a full build."""
    pages = HERE / "pages"
    pages.mkdir(exist_ok=True)
    figures = {
        "headline": bf.headline(rec["headline"]), "two_jobs": bf.two_jobs(), "example_day": bf.example_day(rec["example_day"]), "architecture": bf.architecture(),
        "booking_life": bf.booking_life(), "paired_study": bf.paired_study(), "timeline": bf.timeline(rec["timeline"]), "booking_methods": thread.booking_methods(),
        "booking_choice": bf.booking_choice(rec["booking_choice"]), "refinement_frontier": bf.refinement_frontier({k: v for k, v in rec["refinement_frontier"].items()}),
        "horizon": bf.horizon({(k.split("/")[0], int(k.split("/")[1])): v for k, v in rec["horizon"].items()}), "two_phase": thread.two_phase(),
        "daily_heatmap": bf.daily_heatmap(rec["daily_heatmap"]), "wtl": thread.wtl(rec["wtl"]), "out_of_sample": bf.forest(rec["out_of_sample"]),
        "score_ladder": bf.score_ladder(), "deadline_budget": bf.deadline_budget(), "admission": bf.admission(),
        "paired_distribution": bf.paired_distribution(rec["paired_hist"]), "convergence": bf.convergence(rec["convergence"]),
        "diminishing": bf.diminishing(rec["diminishing"]), "fairness_trade": bf.fairness_trade(rec["fairness_trade"]), "engine": diagrams.engine(rec["engine"]),
        "booking_staircase": bf.booking_staircase(rec["booking_staircase"]), "time_split": bf.time_split(rec["time_split"]), "bottleneck": bf.bottleneck(rec["bottleneck"]),
        "ch_mechanism": thread.ch_mechanism(), "holds": bf.holds(rec["holds"]), "engine_steps": bf.engine_steps(rec["engine_steps"]),
        "harness_vs_stack": bf.harness_vs_stack(rec["harness_vs_stack"]), "capacity": bf.capacity(rec["capacity"]), "roadmap": bf.roadmap(),
    }
    names = [n for n in figures if not only or n in only]
    for name in names:
        figures[name].eyebrow = bf.EYEBROW
        (pages / f"{name}.html").write_text(figures[name].html(), encoding="utf-8")
    render_all([(pages / f"{name}.html", folder / f"{name}.png") for name in names], 3)
    return list(figures)


if __name__ == "__main__":
    import sys
    if sys.argv[1:2] == ["--redraw"]:
        draw(json.loads((HERE / "generated/chart_data.json").read_text(encoding="utf-8")), HERE / "generated", set(sys.argv[2:]))
    else:
        from report_data import load
        print(sorted(make_charts(load(), HERE / "generated")))
