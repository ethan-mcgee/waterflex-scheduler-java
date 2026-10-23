"""Reproducible figures for the scheduler explainer: every number is computed here from the validated data, recorded in generated/chart_data.json for verify_report.py, and drawn as inline-SVG diagrams (figures/diagrams.py, the diagram-design format) that figures/render.py rasterizes."""
import json
from pathlib import Path
from statistics import mean, median

from figures import diagrams, thread, trail
from figures import later as later_figures
from figures.render import render_all
from report_data import per_fleet, wtl

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

    # 8. Where a booking call's server time goes, from the VM baseline to the shipped build.
    attribution = [("J0 baseline", by_run(runs["vm"], "base-spread2", "booking")), ("J flexible", by_run(runs["routing"], "j-flex", "booking")), ("J CH", by_run(runs["routing"], "j-ch", "booking")),
                   ("J2 shipped", by_run(runs["priority"], "j2-slot", "booking"))]
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

    # 12. Inside the engine: the 24 paired cases of study L.
    prof = extras["profiling"]
    recorded["paired"] = [{"technicians": p["technicians"], "workload": p["workload"], "seed": p["seed"], "before": p["costPhaseMovesPerSecond"]["before"], "after": p["costPhaseMovesPerSecond"]["after"],
                           "speedup": p["costPhaseSpeedup"], "cost_before": p["afterCostCents"]["before"], "cost_after": p["afterCostCents"]["after"]} for p in prof["pairs"]]
    figures["engine"] = diagrams.engine(recorded["paired"])

    # The trail edition's figures: the front matter, the architecture, and the data behind each optimization thread.
    trail_figures(data, recorded, figures)

    # Write every generated diagram beside the hand-authored topology, then rasterize them all with one browser.
    for name, fig in figures.items():
        (here / f"{name}.html").write_text(fig.html(), encoding="utf-8")
    render_all([(here / f"{name}.html", folder / f"{name}.png") for name in ["topology", *figures]], 3)
    (folder / "chart_data.json").write_text(json.dumps(recorded, indent=2) + "\n", encoding="utf-8")
    return recorded


def trail_figures(data, recorded, figures):
    """Record the data of every trail-edition figure and draw it. Schematic figures draw what the design document and contract describe."""
    perf, runs, extras, benchmarks, docs = data["perf"], data["perf"]["runs"], data["perf"]["extras"], data["perf"]["benchmarks"], data["docs"]
    per_minute = perf["per_minute"]
    F, M, L = data["daily"]["first"]["rows"], data["daily"]["follow"]["rows"], data["daily"]["follow"]["long"]
    b24, f29, r24 = data["booking24"], data["field29"], data["routing24"]

    def rows(table, solver, **kw):
        out = [r for r in table if r["solver"] == solver and all(r[k] == v for k, v in kw.items())]
        assert out, (solver, kw)
        return out

    def saving(table, solver, **kw):
        return mean(r["saving"] for r in rows(table, solver, **kw))

    b5, b1, b10 = (by_label(runs["baseline"], f"booking-cold-c{c}") for c in (5, 1, 10))
    fresh = {n: by_label(extras["coldRemeasurement"], f"cold-recheck-c{n} (seed {s})") for n, s in ((1, 93), (5, 91), (10, 92))}
    ab_before, ab_after = extras["abComparison"]
    assert ab_before["label"].startswith("before-c5") and ab_after["label"].startswith("after-c5")
    vm_b = by_run(runs["vm"], "base-spread2", "booking")
    jf_b, jc_b = by_run(runs["routing"], "j-flex", "booking"), by_run(runs["routing"], "j-ch", "booking")
    final = by_run(runs["priority"], "j2-slot", "booking")
    prof = extras["profiling"]
    ch_old, ch_new = ({(l["engine"], l["concurrency"]): l for l in benchmarks[d]["latency"]} for d in ("2026-09-24", "2026-10-14"))
    held = {v: f29["booking"][("held", v)] for v in ("INSERTION", "BOUNDED")}
    routing_cores = lambda r: [x for x in r["containers"] if "routing" in x["name"]][0]["cpuPercent"]["mean"] / 100
    per_leg = lambda r: r["breakdown"][OFFERS]["routingMs"]["p50"] / r["breakdown"][OFFERS]["fetched"]["p50"]
    speedup = prof["summary"]["costPhaseSpeedup"]["median"]
    tk = rows(F, "TABU_KOPT")

    # Part I: schematic figures over the design document's own tables (each asserts the table's shape).
    figures["stateless"] = trail.stateless()
    figures["ownership"] = trail.ownership(docs["ownership"][1])
    figures["surface"] = trail.surface(docs["operations"])
    figures["token"] = trail.token()
    figures["reconcile"] = trail.reconcile()
    figures["refusals"] = trail.refusals(docs["problems"][1])
    figures["daily_pipeline"] = trail.daily_pipeline()
    figures["booking_pipeline"] = trail.booking_pipeline()
    figures["isolation"] = trail.isolation()
    figures["replicas"] = trail.replicas()
    figures["queue_states"] = trail.queue_states()
    figures["thin_store"] = trail.thin_store(docs["tables"][1], docs["stored"][1])
    figures["geocode"] = trail.geocode()
    figures["map_lifecycle"] = trail.map_lifecycle()
    recorded["schematic_sources"] = {"operations": [o["id"] for o in docs["operations"]], "problems": len(docs["problems"][1]), "tables": len(docs["tables"][1]),
                                     "stored": len(docs["stored"][1])}

    # Part II.
    recorded["evidence"] = {"daily_cases": len(F) + len(data["daily"]["first"]["confirm"]) + len(M) + len(L) + len(data["daily"]["follow"]["confirm"]), "booking_requests": b24["requests"],
                            "load_runs": sum(len(v) for name, v in runs.items() if name != "remote") + len(extras["coldRemeasurement"]) + len(extras["abComparison"])}
    figures["evidence"] = thread.evidence(recorded["evidence"])

    # Part III, the daily search.
    figures["two_phase"] = thread.two_phase()
    variants = ("CURRENT_UNCAPPED", "KOPT", "SUBLIST", "LATE_ACCEPTANCE", "RUIN_RECREATE")
    recorded["budgets29"] = {"budgets": [15, 30, 60], "values": {v: [f29["daily"][(b, v)] for b in (15, 30, 60)] for v in variants}}
    figures["budgets29"] = thread.budgets29(recorded["budgets29"])
    recorded["wtl"] = {"chosen": {"first": "TABU_KOPT", "follow": "TABU_KOPT_SIZE_3"}}
    for key, table, solvers in (("first", F, ("TABU_SIZE_3", "TABU_SIZE_15", "TABU_KOPT", "KOPT", "SUBLIST", "LATE_ACCEPTANCE")), ("follow", M, ("TABU_KOPT", "TABU_KOPT_SIZE_3", "TABU_SIZE_3", "KOPT"))):
        recorded["wtl"][key] = [dict(zip(("wins", "ties", "losses"), wtl(rows(table, s))), solver=s, mean=saving(table, s)) for s in solvers]
    figures["wtl"] = thread.wtl(recorded["wtl"])
    fleets = (5, 10, 20, 50)
    recorded["late_mechanics"] = {"fleets": list(fleets),
                                  "best_fraction": {s: [mean(r["best_fraction"] for r in rows(F, s, fleet=fl)) for fl in fleets] for s in ("TABU", "TABU_KOPT", "LATE_ACCEPTANCE", "KOPT")},
                                  "variance": [{"label": "WITH FAIRNESS (FIRST STUDY)", "rows": [{"solver": s, "value": mean(r["variance"] for r in rows(F, s))} for s in ("TABU", "TABU_KOPT", "KOPT", "LATE_ACCEPTANCE")]},
                                               {"label": "COST ONLY (FOLLOW-UP)", "rows": [{"solver": s, "value": mean(r["variance"] for r in rows(M, s))} for s in ("TABU", "TABU_KOPT", "TABU_KOPT_SIZE_3")]}]}
    figures["late_mechanics"] = thread.late_mechanics(recorded["late_mechanics"])
    budgets = (240000, 480000, 600000)
    recorded["long_arm"] = {"budgets": [b // 1000 for b in budgets], "cases_per_point": len(rows(L, "KOPT", fleet=50, budget_ms=240000)),
                            "fleets": {str(fl): {s: [saving(L, s, fleet=fl, budget_ms=b) for b in budgets] for s in ("KOPT", "LATE_ACCEPTANCE", "TABU")} for fl in (30, 50)}}
    figures["long_arm"] = thread.long_arm(recorded["long_arm"])

    # Part III, the booking search.
    figures["booking_methods"] = thread.booking_methods()
    recorded["workload24"] = [{"workload": w, **b24["by_workload"][w]} for w in ("CLUSTERED", "DISPERSED", "SPARSE")]
    figures["workload24"] = thread.workload24(recorded["workload24"])
    figures["deadline"] = thread.deadline()
    outcome = extras["outcome"]
    recorded["api_fixes"] = {"incomplete": {"before": [b5["booking"]["searchIncomplete"], b5["booking"]["searches"]], "after": [outcome["searchCompleteFalse"], outcome["searches"]]},
                             "shedding": {stage: {"label": r["label"], "per_minute": per_minute(r), "p95": r["ops"]["booking offers"]["ms"]["p95"]} for stage, r in (("before", ab_before), ("after", ab_after))}}
    figures["api_fixes"] = thread.api_fixes(recorded["api_fixes"])

    # Part III, routing.
    omaha = r24["omaha"]
    assert omaha["flexible"]["requests"] == omaha["ch"]["requests"]
    recorded["paradox"] = {"alone": [{"engine": e, "callers": c, "p50": ch_old[(e, c)]["p50Ms"]} for c in (1, 10) for e in ("flexible", "CH-car-v1")],
                           "booking": {"requests": omaha["flexible"]["requests"], **{k: {"served": omaha[k]["served"], "p95": omaha[k]["p95Ms"]} for k in ("flexible", "ch")}}}
    figures["paradox"] = thread.paradox(recorded["paradox"])
    figures["ch_mechanism"] = thread.ch_mechanism()
    recorded["j_routing"] = [{"run": name, "searches": r["booking"]["searches"], "per_leg": per_leg(r), "routing_cores": routing_cores(r)} for name, r in (("J0", vm_b), ("J flex", jf_b), ("J CH", jc_b))]
    figures["j_routing"] = thread.j_routing(recorded["j_routing"])

    # Part III, admission.
    recorded["holds"] = [{"desks": n, "before_lost": b["booking"]["holdUnavailable"], "before_selected": b["booking"]["selected"], "after_lost": a["booking"]["holdUnavailable"],
                          "after_selected": a["booking"]["selected"], "before_p95": b["ops"]["booking offers"]["ms"]["p95"], "after_p95": a["ops"]["booking offers"]["ms"]["p95"],
                          "before_per_minute": per_minute(b), "after_per_minute": per_minute(a), "stale_per_search": a["booking"]["stale"] / a["booking"]["searches"]}
                         for n, b, a in ((1, b1, fresh[1]), (5, b5, fresh[5]), (10, b10, fresh[10]))]
    figures["holds"] = thread.holds(recorded["holds"])
    figures["race"] = thread.race()
    figures["slots"] = thread.slots()

    # Part III, inside the engine.
    groups = ["Immutable map probe", "HashMap and copies", "BigDecimal arithmetic", "Engine timing methods", "Instant and Duration", "Timefold score director"]
    assert set(groups) == set(prof["before"]["groups"]) == set(prof["after"]["groups"])
    recorded["profile"] = {"groups": groups, "before": prof["before"]["groups"], "after": prof["after"]["groups"], "samples": {"before": prof["before"]["samples"], "after": prof["after"]["samples"]}}
    figures["profile"] = thread.profile(recorded["profile"])
    figures["roadmatrix"] = thread.roadmatrix()
    recorded["paired_fleets"] = []
    for fleet in (20, 50):
        fp = [x for x in prof["pairs"] if x["technicians"] == fleet]
        recorded["paired_fleets"].append({"fleet": fleet, "before_median": median(x["costPhaseMovesPerSecond"]["before"] for x in fp), "after_median": median(x["costPhaseMovesPerSecond"]["after"] for x in fp),
                                          "speedup": prof["summary"]["costPhaseSpeedupByFleet"][str(fleet)]["medianCostPhaseSpeedup"], "fairness_speedup": median(x["fairnessPhaseSpeedup"] for x in fp),
                                          "wins": sum(x["afterCostCents"]["after"] < x["afterCostCents"]["before"] for x in fp),
                                          "ties": sum(x["afterCostCents"]["after"] == x["afterCostCents"]["before"] for x in fp),
                                          "losses": sum(x["afterCostCents"]["after"] > x["afterCostCents"]["before"] for x in fp)})
    figures["paired_fleets"] = thread.paired_fleets(recorded["paired_fleets"])

    # The work after the first edition: booking configuration and horizon, and the engine's representation chain with its stack checks.
    later = data["later"]
    h = later["horizon"]
    recorded["horizon"] = {f"{mode}/{desks}": h[("high", mode, desks)] for mode in ("fixed", "dynamic") for desks in (1, 5)}
    figures["horizon"] = later_figures.horizon(recorded["horizon"])
    recorded["refinement_frontier"] = {str(k): v for k, v in later["config"]["frontier"].items()}
    figures["refinement_frontier"] = later_figures.refinement_frontier(recorded["refinement_frontier"])
    recorded["capacity"] = later["config"]["layouts"]
    figures["capacity"] = later_figures.capacity(recorded["capacity"])
    rep, fm, nm, s3 = later["representation"]["summary"], later["fairness"]["summary"]["15000"], later["numbering"], later["quoted"]["study3"]
    recorded["engine_chain"] = [
        {"name": "RoadMatrix (#163)", "phase": "COST PHASE", "ratio": speedup, "shipped": True, "note": "study L, 24 pairs"},
        {"name": "Long timing T (#186)", "phase": "COST PHASE", "ratio": rep["T"]["referencePhaseSpeedup"]["median"], "shipped": True, "note": "not slower in either phase"},
        {"name": "Long score S (#186)", "phase": "FAIRNESS PHASE", "ratio": rep["S"]["fairnessPhaseSpeedup"]["median"], "shipped": False, "note": f"{rep['S']['cases']['dearerBeyondNoise']} cases dearer beyond noise"},
        {"name": "Formula b (#187)", "phase": "FAIRNESS PHASE", "ratio": fm["b"]["fairnessPhaseSpeedup"]["median"], "shipped": True, "note": "never less fair; #189"},
        {"name": "Formula c (#187)", "phase": "FAIRNESS PHASE", "ratio": fm["c"]["fairnessPhaseSpeedup"]["median"], "shipped": False, "note": "failed cost and fairness bars"},
        {"name": "Numbering (#187)", "phase": "COST PHASE", "ratio": nm["fix"]["baseline"]["referencePhaseSpeedup"]["median"], "shipped": True, "note": "not slower in either phase"},
        {"name": "L+M (#191)", "phase": "COST PHASE", "ratio": s3["lm_over_main"][0], "shipped": True, "note": "quoted, study 3"},
        {"name": "W+A on L+M (#191)", "phase": "COST PHASE", "ratio": s3["wa_over_lm"][0], "shipped": True, "note": "quoted; owner's call, W 1.047x"},
        {"name": "CR11 cascade", "phase": "COST PHASE", "ratio": s3["cr11_c_over_lm"], "shipped": False, "note": "quoted, slower"},
    ]
    figures["engine_chain"] = later_figures.engine_chain(recorded["engine_chain"])
    recorded["fairness_b"] = {"pairs": nm["pairs"], "summary": nm["summary"]["b"]}
    figures["fairness_b"] = later_figures.fairness_b(recorded["fairness_b"])
    harness_b = nm["summary"]["b"]["fairnessPhaseSpeedupByFleet"]
    st = later["stack"]
    recorded["stack"] = [{"name": f"Formula b, {fl} techs", "harness": harness_b[str(fl)]["median"] if str(fl) in harness_b else None, "stack": st["b"][fl]["FAIRNESS"]} for fl in (10, 20, 50)] + \
                        [{"name": f"L+M+W+A, {fl} techs", "harness": None, "stack": st["shipped"][fl]["REFERENCE"]} for fl in (10, 20, 50)]
    figures["stack"] = later_figures.stack(recorded["stack"])

    # Part IV and Part V.
    recorded["today"] = {"shed_before": per_minute(ab_before), "shed_after": per_minute(ab_after), "confirm_before": jc_b["ops"]["confirm"]["ms"]["p50"], "confirm_after": final["ops"]["confirm"]["ms"]["p50"],
                         "speedup": speedup, "daily_saving": saving(F, "TABU_KOPT"), "searches_before": vm_b["booking"]["searches"], "searches_after": jc_b["booking"]["searches"],
                         "lost_before": b5["booking"]["holdUnavailable"],
                         "stack_lo": min(v["REFERENCE"] for v in st["shipped"].values()), "stack_hi": max(v["REFERENCE"] for v in st["shipped"].values())}
    figures["today"] = thread.today(recorded["today"])
    figures["roadmap_perf"] = thread.roadmap_perf()
    figures["cores"] = thread.cores()
    figures["roadmap_quality"] = thread.roadmap_quality()


def money(value):
    return f"-${-value:,.2f}" if value < 0 else f"${value:,.2f}"


if __name__ == "__main__":
    from report_data import load
    out = make_charts(load(), Path(__file__).resolve().parent / "generated")
    print(sorted(out))
