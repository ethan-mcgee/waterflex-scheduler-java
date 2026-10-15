"""Reproducible figures for the scheduler explainer: every number is computed here from the validated data, recorded in generated/chart_data.json for verify_report.py, and drawn as inline-SVG diagrams (figures/diagrams.py, the diagram-design format) that figures/render.py rasterizes."""
import json
from pathlib import Path
from statistics import mean

from figures import diagrams
from figures.render import render_all
from report_data import per_fleet

SOLVER_LABEL = {"TABU": "Tabu", "TABU_SIZE_3": "Tabu size 3", "TABU_SIZE_15": "Tabu size 15", "TABU_KOPT": "Tabu + K opt", "TABU_KOPT_SIZE_3": "Tabu + K opt size 3",
                "KOPT": "K opt", "SUBLIST": "Sublist", "LATE_ACCEPTANCE": "Late acceptance"}
OFFERS, CONFIRM = "POST /api/v1/booking/offers 201", "POST /api/v1/booking/holds/{holdId}/confirm 200"
PART = ("queueMs", "routingMs", "calculationMs", "dbMs")


def by_label(runs, label):
    out = [r for r in runs if r["label"] == label]
    assert len(out) == 1, label
    return out[0]


def by_run(runs, run, scenario):
    out = [r for r in runs if r.get("run") == run and r["scenario"] == scenario]
    assert len(out) == 1, (run, scenario)
    return out[0]


def make_charts(data, folder):
    folder.mkdir(exist_ok=True)
    here = Path(__file__).resolve().parent / "figures"
    recorded, figures = {}, {}
    perf, runs, extras, benchmarks = data["perf"], data["perf"]["runs"], data["perf"]["extras"], data["perf"]["benchmarks"]
    per_minute, queue_wait = perf["per_minute"], perf["queue_wait"]

    # 1. Topology: authored by hand as an architecture diagram in figures/topology.html.
    recorded["topology"] = {"source": "figures/topology.html", "boxes": ["host backend", "scheduler-proxy", "booking pool", "daily pool", "routing-service per metro", "Nominatim", "scheduler-db", "management port"]}

    # 2. The booking sequence with its outcomes.
    recorded["booking"] = {"steps": ["offers", "select", "confirm", "write receipt"], "refusals": ["STALE", "HOLD_UNAVAILABLE", "HOLD_UNAVAILABLE", "host write conflict"]}
    figures["booking"] = diagrams.booking(recorded["booking"])

    # 3. Daily and repair proposals: synchronous and queued, then commit.
    recorded["daily"] = {"modes": ["synchronous", "queued"], "decisions": ["IMPROVED", "NO_IMPROVEMENT", "REJECTED_BY_POLICY"]}
    figures["daily"] = diagrams.daily(recorded["daily"])

    # 4. The route cache tiers, in lookup order, from the design document's table.
    tiers = data["docs"]["tiers"][1]
    recorded["tiers"] = [row[0] for row in tiers]
    figures["tiers"] = diagrams.tiers(tiers)

    # 5. The daily search studies: mean saving against Tabu by fleet, first study (16 fixtures) and follow-up (40 new fixtures, with Tabu + K opt size 3).
    first, follow = data["daily"]["first"]["rows"], data["daily"]["follow"]["rows"]
    recorded["daily_fleet"] = {}
    for key, rows, fleets, solvers in (("first", first, (5, 10, 20, 50), ("TABU_SIZE_3", "TABU_SIZE_15", "TABU_KOPT", "KOPT", "SUBLIST", "LATE_ACCEPTANCE")),
                                       ("follow", follow, (5, 10, 20, 30, 50), ("TABU_KOPT", "TABU_KOPT_SIZE_3", "TABU_SIZE_3", "KOPT"))):
        values = [per_fleet(rows, s, fleets) + [mean(r["saving"] for r in rows if r["solver"] == s)] for s in solvers]
        recorded["daily_fleet"][key] = {"fleets": list(fleets), "solvers": list(solvers), "values": values}
    figures["daily_fleet"] = diagrams.daily_fleet(recorded["daily_fleet"], SOLVER_LABEL)

    # 6. Booking search: service in the September held-out matrix, and the latency of bounded search.
    b24, f29 = data["booking24"], data["field29"]
    recorded["booking_study"] = {"held": [{"variant": v, "served": f29["booking"][("held", v)]["served"], "requests": f29["booking"][("held", v)]["requests"],
                                           "mean_case_p95_ms": f29["booking"][("held", v)]["meanCaseP95Ms"]} for v in ("INSERTION", "BOUNDED", "SHARED")],
                                 "latency": [{"solver": s, "concurrency": c, "p95": b24["latency"][(s, c)]["p95"]} for s in ("INSERTION", "BOUNDED") for c in (1, 5, 10)]}
    figures["booking_study"] = diagrams.booking_study(recorded["booking_study"])

    # 7. Routing alone: flexible against CH on 132 Omaha pairs, two maps and two dates.
    recorded["ch"] = []
    for date, doc in benchmarks.items():
        for engine in ("flexible", "CH-car-v1"):
            rows = [l for l in doc["latency"] if l["engine"] == engine]
            recorded["ch"].append({"date": date, "engine": engine, "concurrency": [l["concurrency"] for l in rows], "p50": [l["p50Ms"] for l in rows], "p95": [l["p95Ms"] for l in rows]})
    figures["ch"] = diagrams.ch(recorded["ch"], {date: doc["engines"][0]["mapVersion"] for date, doc in benchmarks.items()})

    # 8. Where a booking call's server time goes, from the VM baseline to the shipped build and the remote pool.
    attribution = [("J0 baseline", by_run(runs["vm"], "base-spread2", "booking")), ("J flexible", by_run(runs["routing"], "j-flex", "booking")), ("J CH", by_run(runs["routing"], "j-ch", "booking")),
                   ("J2 shipped", by_run(runs["priority"], "j2-slot", "booking")), ("K remote", by_run(runs["remote"], "k-remote5", "booking"))]
    recorded["attribution"] = []
    for stage, r in attribution:
        for op in (OFFERS, CONFIRM):
            entry = r["breakdown"][op]
            recorded["attribution"].append({"stage": stage, "operation": op, "elapsed_p50": entry["elapsedMs"]["p50"], **{k: entry[k]["p50"] for k in PART}})
    figures["attribution"] = diagrams.attribution(recorded["attribution"])

    # 9. Five desks at each stage of the admission and concurrency work.
    stages = [("#151", by_label(runs["baseline"], "booking-cold-c5")), ("#152", by_label(extras["coldRemeasurement"], "cold-recheck-c5 (seed 91)")),
              ("#154", by_label(runs["outcome"], "booking-cold-c5")), ("#155", by_label(runs["shedding"], "after-c5 (seed 55)")),
              ("J0", by_run(runs["vm"], "base-spread2", "booking")), ("J flex", by_run(runs["routing"], "j-flex", "booking")),
              ("J CH", by_run(runs["routing"], "j-ch", "booking")), ("J2", by_run(runs["priority"], "j2-slot", "booking"))]
    recorded["progression"] = [{"stage": s, "label": r["label"], "searches_per_minute": per_minute(r), "p50": r["ops"]["booking offers"]["ms"]["p50"], "p95": r["ops"]["booking offers"]["ms"]["p95"]} for s, r in stages]
    figures["progression"] = diagrams.progression(recorded["progression"])

    # 10. Daily proposals: answered or refused, fairness between clients, and replica pools.
    recorded["daily_load"] = {"admission": [], "fairness": [], "roles": []}
    for name, b, a in (("1 planner", by_label(runs["baseline"], "daily-c1"), by_label(runs["admission"], "daily-c1")), ("4 planners", by_label(runs["baseline"], "daily-c4"), by_label(runs["admission"], "daily-c4"))):
        for stage, r in (("#151", b), ("#153", a)):
            statuses = r["ops"]["daily proposal"]["statuses"]
            recorded["daily_load"]["admission"].append({"planners": name, "stage": stage, "answered_201": statuses.get("201", 0), "answered_503": statuses.get("503", 0)})
    fair = {(r["note"], r["desks"]): r for r in runs["queue"] if r["note"] in ("sync", "async")}
    for m in ("sync", "async"):
        for t, c in (("A", 4), ("B", 1)):
            recorded["daily_load"]["fairness"].append({"mode": m, "client": t, "planners": c, "proposals": fair[(m, c)]["daily"]["proposals"]})
    for run, shape in (("all-1", "one all"), ("split-1", "split"), ("split-2", "split"), ("all-2", "one all")):
        d = by_run(runs["roles"], run, "daily")
        recorded["daily_load"]["roles"].append({"run": run, "shape": shape, "proposals": d["daily"]["proposals"], "queue_wait_p50": queue_wait(d)[0]})
    figures["daily_load"] = diagrams.daily_load(recorded["daily_load"])

    # 11. J2: confirm and offers at the caller, before and after confirm priority and the confirm slot.
    j2 = [("J CH", by_run(runs["routing"], "j-ch", "booking")), ("priority", by_run(runs["priority"], "j2-confirm", "booking")), ("priority,\nZGC / Parallel", by_run(runs["priority"], "j2-gc", "booking")),
          ("priority +\nconfirm slot", by_run(runs["priority"], "j2-slot", "booking")), ("slot, compact\nheaders", by_run(runs["priority"], "j2-coh2", "booking"))]
    recorded["confirm"] = [{"stage": s, "run": r.get("run"), "confirm_p50": r["ops"]["confirm"]["ms"]["p50"], "confirm_p95": r["ops"]["confirm"]["ms"]["p95"], "confirm_wait_p50": r["breakdown"][CONFIRM]["queueMs"]["p50"],
                           "offers_p50": r["ops"]["booking offers"]["ms"]["p50"], "offers_p95": r["ops"]["booking offers"]["ms"]["p95"]} for s, r in j2]
    figures["confirm"] = diagrams.confirm(recorded["confirm"])

    # 12. Inside the engine: the 24 paired cases of study L, and embedded against remote (K).
    prof = extras["profiling"]
    recorded["paired"] = [{"technicians": p["technicians"], "workload": p["workload"], "seed": p["seed"], "before": p["costPhaseMovesPerSecond"]["before"], "after": p["costPhaseMovesPerSecond"]["after"],
                           "speedup": p["costPhaseSpeedup"], "cost_before": p["afterCostCents"]["before"], "cost_after": p["afterCostCents"]["after"]} for p in prof["pairs"]]
    recorded["remote"] = []
    for run in ("k-embedded", "k-remote", "k-remote4", "k-remote5"):
        b, d = by_run(runs["remote"], run, "booking"), by_run(runs["remote"], run, "daily")
        recorded["remote"].append({"run": run, "offers_p50": b["ops"]["booking offers"]["ms"]["p50"], "offers_p95": b["ops"]["booking offers"]["ms"]["p95"], "searches": b["booking"]["searches"],
                                   "answered_201": d["ops"]["daily proposal"]["statuses"].get("201", 0), "answered_503": d["ops"]["daily proposal"]["statuses"].get("503", 0),
                                   "daily_cpu_mean": [c for c in b["containers"] if "daily" in c["name"]][0]["cpuPercent"]["mean"]})
    figures["engine"] = diagrams.engine(recorded["paired"], recorded["remote"])

    # Write every generated diagram beside the hand-authored topology, then rasterize all twelve with one browser.
    for name, fig in figures.items():
        (here / f"{name}.html").write_text(fig.html(), encoding="utf-8")
    render_all([(here / f"{name}.html", folder / f"{name}.png") for name in ["topology", *figures]], 3)
    (folder / "chart_data.json").write_text(json.dumps(recorded, indent=2) + "\n", encoding="utf-8")
    return recorded


if __name__ == "__main__":
    from report_data import load
    out = make_charts(load(), Path(__file__).resolve().parent / "generated")
    print(sorted(out))
