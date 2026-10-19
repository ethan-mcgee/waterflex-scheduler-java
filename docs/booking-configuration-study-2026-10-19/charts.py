"""Reproducible figures from the validated booking configuration study; no network or study runs.

Each figure is authored as HTML with inline SVG by figures.py from the datasets this module computes, written to the
output folder beside chart_data.json, and rasterized by render.py. Nothing is typed in: a value the evidence does not
hold stops the build instead of drawing a zero.
"""
import json

from figures import ACCENT, dollars, dotplot, heatmap, ink, line_chart, pipeline, stacked_bar, swarm
from render import render_all
from report_data import CAPS, FLEETS, LAYOUTS, THRESHOLDS

GUARD_MS = 500  # The database admission guard: Hikari connect plus validation timeout, 250 + 250 ms.
DEADLINE_MS = 5000  # The booking search deadline (SearchDeadline), fixed by owner decision.
RESERVE_DEFAULT_MS = 1000  # booking.search.publish-reserve-ms default; the study's rerun set the floor to 800.
RESERVES_MS = (250, 500, 800, 1000)  # The reserves the two studies ran, in order.
NAME = {"2:0": "2:0 insertion only", "2:250": "2:250 today", "2:1000": "2:1000", "2:2000": "2:2000", "2:3000": "2:3000", "1000": "1000 uncapped"}
PART = (("routingP50", "Routing", ink(0.55)), ("engineP50", "Engine", ink(0.30)), ("dbP50", "Database", ink(0.75)), ("commitP50", "Commit", ink(0.12)))
ms = lambda v: f"{v:,.0f} ms"
secs = lambda v: f"{v / 1000:.1f} s"


def known(value, what):
    """A plotted value must come from the evidence; a missing one stops the build instead of drawing a zero."""
    if value is None:
        raise ValueError(f"missing in the evidence: {what}")
    return value


def make_charts(data, folder):
    folder.mkdir(exist_ok=True)
    fq, fq1 = data["rerun"]["quality"], data["first"]["quality"]
    load, cap, rl = data["first"]["load"], data["first"]["capacity"], data["rerun"]["reserveLoad"]
    q = lambda fleet, variant, source=fq, profile="full": source[f"{fleet}/{profile}"][variant]
    out, pages = {}, []
    noise = max(max(q(f, f"{t}:3000")["meanCents"] for t in THRESHOLDS) - min(q(f, f"{t}:3000")["meanCents"] for t in THRESHOLDS) for f in FLEETS)
    fleet_rows = [f"{f} technicians" for f in FLEETS]
    money = lambda v: dollars(v * 100)
    gap_axis = "MEAN ADDED COST PER BOOKING AGAINST THE UNCAPPED SEARCH, DOLLARS"

    def emit(name, html):
        (folder / f"{name}.html").write_text(html, encoding="utf-8")
        pages.append((folder / f"{name}.html", folder / f"{name}.png"))

    # 1. How a search spends its time: the five stages over the deadline bar. Read from the code, not measured; labeled so.
    out["pipeline"] = {"deadlineMs": DEADLINE_MS, "reserveMs": RESERVE_DEFAULT_MS, "guardMs": GUARD_MS}
    emit("pipeline", pipeline("pipeline", "One booking search inside its deadline",
                              "A flowchart of the five stages of a booking search, admission, first pass, the shed check, the refinement and the commit, over a bar "
                              "of the 5 s deadline split into exploration, the publish reserve and the database admission guard.",
                              "ONE BOOKING SEARCH, LEFT TO RIGHT, OVER ITS DEADLINE",
                              [{"name": "Admission", "does": "takes a replica slot", "limit": "other searches"},
                               {"name": "First pass", "does": "job on every day", "limit": "horizon x fleet"},
                               {"name": "Shed check", "does": "queue empty?", "limit": ""},
                               {"name": "Refinement", "does": "moves visits for room", "limit": "cap, then deadline"},
                               {"name": "Commit", "does": "validate and publish", "limit": "reserve and guard"}],
                              DEADLINE_MS, RESERVE_DEFAULT_MS, GUARD_MS,
                              ["Stages and limits read from BookingOffers and SearchDeadline; the bar is the default reserve, the rerun's floor is 800 ms.",
                               "A refinement the window threshold makes required skips the shed check and runs uncapped to the reserve."]))

    # 2. Full schedules: the gap each cap leaves, as a heatmap with today's cap at the hardest fleet as the focal cell.
    out["gap"] = [{"fleet": f, "caps": list(CAPS), "meanCents": [known(q(f, f"2:{c}")["meanCents"], f"gap {f} 2:{c}") for c in CAPS],
                   "dearer": [q(f, f"2:{c}")["dearer"] for c in CAPS], "priced": [q(f, f"2:{c}")["priced"] for c in CAPS]} for f in FLEETS]
    focal = max(((r["fleet"], 250, r["meanCents"][list(CAPS).index(250)]) for r in out["gap"]), key=lambda x: x[2])
    out["focal"] = {"fleet": focal[0], "cap": 250, "meanCents": focal[2]}
    emit("gap", heatmap(out["gap"], (focal[0], 250), noise))

    # 2. The gap left at today's cap, 2000 and 3000 ms, one row per fleet on one dollar scale.
    out["left"] = [{"fleet": f, **{v: known(q(f, v)["meanCents"], f"gap {f} {v}") for v in ("2:250", "2:2000", "2:3000")}} for f in FLEETS]
    out["p50"] = {v: [known(q(f, v)["p50ms"], f"p50 {f} {v}") for f in FLEETS] for v in ("2:250", "2:2000", "2:3000")}
    rng = lambda v: f"{min(out['p50'][v]) / 1000:.1f} to {max(out['p50'][v]) / 1000:.1f} s"
    emit("left", dotplot("left", "The gap each cap leaves, by fleet",
                         "A dot plot with one row per fleet size showing the mean added cost per booking at today's 250 ms cap, at 2000 ms and at 3000 ms on one shared dollar scale.",
                         "GAP LEFT BY TODAY'S CAP, BY 2000 MS AND BY 3000 MS, FULL SCHEDULES",
                         [{"label": l, "hollow": r["2:250"] / 100, "solid": r["2:3000"] / 100, "tick": r["2:2000"] / 100} for l, r in zip(fleet_rows, out["left"])],
                         ("2:250, today", "2:3000", "2:2000"), 1, money, gap_axis,
                         ["Rows by fleet size. The line spans today's cap to 3000 ms; the tick marks 2000 ms.",
                          f"Search p50 at the caller in the quality runs: {rng('2:250')} at 250 ms, {rng('2:2000')} at 2000 ms, {rng('2:3000')} at 3000 ms."]))

    # 3. Routing per search at one desk on full schedules, by fleet: the uncapped search is the focal series.
    routed = ("2:0", "2:250", "2:3000", "1000")
    out["routing"] = [{"variant": v, "fleets": list(FLEETS), "routingP50": [known(load[f"{f}/full/{v}"]["routingP50"], f"routing {f} {v}") for f in FLEETS]} for v in routed]
    emit("routing", line_chart("routing", "Routing time per search, one desk",
                               "The scheduler's median routing time per booking search at one desk on full schedules, by fleet size, for four variants.",
                               "SCHEDULER ROUTING TIME PER SEARCH, P50, ONE DESK ON FULL SCHEDULES", list(FLEETS),
                               [{"name": NAME[d["variant"]], "values": d["routingP50"], "focal": d["variant"] == "1000"} for d in out["routing"]],
                               500, ms, "ROUTING P50, MS",
                               ["One desk per tenant, cold roads, 2 repeats pooled; the scheduler's own routing time from its request log.",
                                "Fleet sizes are evenly spaced, not to scale."]))

    # 4. The parts of a search at 30 technicians, stacked; the uncapped search's routing is the focal segment.
    parts_at = ("2:0", "2:250", "2:1000", "2:3000", "1000")
    out["parts30"] = [{"variant": v, **{k: known(load[f"30/full/{v}"][k], f"{k} 30 {v}") for k, _, _ in PART}, "p50": load[f"30/full/{v}"]["p50"]} for v in parts_at]
    emit("parts", stacked_bar("parts", "The parts of a search at 30 technicians",
                              "The scheduler's median time by part, routing, engine, database and commit, for five variants at 30 technicians on a full schedule, against the 5 s deadline.",
                              "SCHEDULER TIME BY PART, P50, 30 TECHNICIANS ON A FULL SCHEDULE, ONE DESK",
                              [{"label": d["variant"], "values": {k: d[k] for k, _, _ in PART}} for d in out["parts30"]], PART, 1000, ms, "SCHEDULER TIME, MS",
                              ["Each part is the median of its own distribution, so the stacks add up only roughly; the number above each bar is that sum.",
                               f"The caller's p50 for the uncapped search is {secs(out['parts30'][-1]['p50'])}, because the deadline ends it."],
                              references=[(5000, "5 s deadline", "6,4")], focal=("1000", "routingP50", "Uncapped routing (focal)")))

    # 5. The noise floor: four thresholds ran the same search at one cap; the width of each cluster is the spread.
    out["noise"] = [{"fleet": f, "at250": [q(f, f"{t}:250")["meanCents"] for t in THRESHOLDS], "at3000": [q(f, f"{t}:3000")["meanCents"] for t in THRESHOLDS],
                     "spread250": max(q(f, f"{t}:250")["meanCents"] for t in THRESHOLDS) - min(q(f, f"{t}:250")["meanCents"] for t in THRESHOLDS),
                     "spread3000": max(q(f, f"{t}:3000")["meanCents"] for t in THRESHOLDS) - min(q(f, f"{t}:3000")["meanCents"] for t in THRESHOLDS),
                     "insertion": abs(q(f, "0:0")["meanCents"] - q(f, "2:0")["meanCents"])} for f in FLEETS]
    emit("noise", swarm("noise", "The noise floor",
                        "One row per fleet size with the gap of four thresholds at cap 250 ms and at cap 3000 ms; each set of four ran the identical search, so the width of a cluster is run-to-run noise.",
                        "FOUR THRESHOLDS THAT RAN THE SAME SEARCH, AT CAP 250 AND 3000 MS, FULL SCHEDULES",
                        [{"label": l, "hollow": [c / 100 for c in d["at250"]], "solid": [c / 100 for c in d["at3000"]]} for l, d in zip(fleet_rows, out["noise"])],
                        1, money, gap_axis,
                        [f"Dots pushed above or below a row line would otherwise overprint; the offset is packing, not data. The widest cluster is {dollars(noise)} across.",
                         f"The two insertion-only variants, 0:0 and 2:0, differ by up to {dollars(max(d['insertion'] for d in out['noise']))}."]))

    # 6. Shorter horizons: today's cap on a light 2-date schedule against a full 15-weekday one, per fleet.
    out["horizons"] = [{"profile": p, "variant": v, "meanCents": [q(f, v, fq1 if p == "light" else fq, p)["meanCents"] for f in FLEETS]} for p in ("light", "full") for v in ("2:0", "2:250", "2:3000")]
    light = next(d for d in out["horizons"] if d["profile"] == "light" and d["variant"] == "2:250")
    full = next(d for d in out["horizons"] if d["profile"] == "full" and d["variant"] == "2:250")
    emit("horizons", dotplot("horizons", "Today's cap on two horizons",
                             "A dumbbell with one row per fleet size: the gap today's 250 ms cap leaves on a light schedule with a 2-date horizon against a full schedule with 15 weekdays.",
                             "GAP LEFT BY TODAY'S 250 MS CAP ON A 2-DATE HORIZON AND ON A 15-WEEKDAY ONE",
                             [{"label": l, "hollow": a / 100, "solid": b / 100} for l, a, b in zip(fleet_rows, light["meanCents"], full["meanCents"])],
                             ("light schedule, 2 dates (first study)", "full schedule, 15 weekdays (rerun)"), 1, money, gap_axis,
                             ["Rows by fleet size. The line is the distance between the two horizons, not a path between them.",
                              "Variant 2:250 on both; 40 cases per cell."], dumbbell=True))

    # 7. One desk: the latency tail by variant and fleet against the deadline; 2:3000 is the focal series.
    desk = ("2:0", "2:250", "2:1000", "2:3000", "1000")
    out["desk"] = [{"variant": v, "fleets": list(FLEETS), "perMinute": [known(load[f"{f}/full/{v}"]["perMinute"], f"rate {f} {v}") for f in FLEETS],
                    "p95": [known(load[f"{f}/full/{v}"]["p95"], f"p95 {f} {v}") for f in FLEETS]} for v in desk]
    emit("desk", line_chart("desk", "Latency tail of one desk", "Offers p95 at the caller for one desk on full schedules, by fleet size, for five variants against the 5 s deadline.",
                            "OFFERS P95 AT THE CALLER, ONE DESK ON FULL SCHEDULES", list(FLEETS),
                            [{"name": NAME[d["variant"]], "values": d["p95"], "focal": d["variant"] == "2:3000"} for d in out["desk"]],
                            1000, ms, "OFFERS P95, MS",
                            ["One desk per tenant, cold roads, the schedule filling as confirms land, 2 repeats pooled.",
                             "Fleet sizes are evenly spaced, not to scale. Searches per minute are in the table above."], reference=(5000, "5 s deadline")))

    # 8. Several clients: throughput by booking pool layout at 10 clients, for today's cap, 3000 ms and the uncapped search.
    grid = ("2:250", "2:3000", "1000")
    out["layouts"] = [{"variant": v, "layouts": list(LAYOUTS), "perMinuteAll": [cap[f"10/full/{v}/{l}/10"]["perMinuteAll"] for l in LAYOUTS],
                       "shed": [cap[f"10/full/{v}/{l}/10"]["shed"] for l in LAYOUTS], "unavailable": [cap[f"10/full/{v}/{l}/10"]["unavailable"] for l in LAYOUTS]} for v in grid]
    by = {d["variant"]: d["perMinuteAll"] for d in out["layouts"]}
    emit("layouts", dotplot("layouts", "Throughput by booking pool layout",
                            "A dot plot with one row per booking pool layout: searches per minute over ten clients for today's 250 ms cap, for 3000 ms and for the uncapped search, 10 technicians on a full schedule.",
                            "SEARCHES PER MINUTE OVER 10 CLIENTS, 10 TECHNICIANS ON A FULL SCHEDULE, BY LAYOUT",
                            [{"label": f"{l} ({cap[f'10/full/2:250/{l}/10']['threads']} vCPU)", "hollow": by["2:250"][i], "solid": by["2:3000"][i], "tick": by["1000"][i]} for i, l in enumerate(LAYOUTS)],
                            ("2:250, today", "2:3000", "1000 uncapped"), 200, lambda v: f"{v:,.0f}", "SEARCHES ANSWERED PER MINUTE, ALL CLIENTS TOGETHER",
                            ["Rows are replicas x slots, ordered by vCPU then replicas, one vCPU per slot. The line spans today's cap to 3000 ms; the tick is uncapped.",
                             "Refinements shed and searches answered 503 are in the table above."]))

    # 9. The publish reserve: the guard plus the commit against the 800 ms floor and the 1000 ms default.
    out["reserve"] = [{"fleet": f, "guard": GUARD_MS, "commitP95": max(known(rl[f"{f}/full/{v}/800"]["commitP95"], f"commit {f} {v}") for v in ("1000", "2:3000")),
                       "perMinute800": {v: rl[f"{f}/full/{v}/800"]["perMinute"] for v in ("2:3000", "1000")},
                       "perMinute1000": {v: load[f"{f}/full/{v}"]["perMinute"] for v in ("2:3000", "1000")}} for f in FLEETS]
    emit("reserve", stacked_bar("reserve", "What the reserve has to cover",
                                "A stacked bar per fleet size of the 500 ms the database admission guard demands plus the worse commit p95 at the 800 ms reserve, against the 800 ms floor and the 1000 ms default.",
                                "TIME NEEDED INSIDE THE PUBLISH RESERVE, FULL SCHEDULES AT THE 800 MS RESERVE",
                                [{"label": f"{d['fleet']} technicians", "values": {"guard": d["guard"], "commit": d["commitP95"]}} for d in out["reserve"]],
                                (("guard", "Database admission guard", ink(0.15)), ("commit", "Commit p95, worse of 1000 and 2:3000", ACCENT)), 200, ms, "MILLISECONDS",
                                [f"The guard is the pool's connect plus validation timeout ({GUARD_MS} ms), read from application.properties; the commit is the scheduler's own p95 from its request log.",
                                 "Searches per minute at each reserve are in the table above."],
                                references=[(800, "800 ms floor", "6,4"), (1000, "1000 ms default", "2,4")]))

    # 11. The reserve budget: the guard plus the worst commit p95 at 30 technicians against each reserve the studies ran.
    commit30 = max(d["commitP95"] for d in out["reserve"] if d["fleet"] == 30)
    out["budget"] = [{"reserveMs": r, "guard": GUARD_MS, "commitP95at30": commit30, "fits": GUARD_MS + commit30 <= r} for r in RESERVES_MS]
    emit("budget", stacked_bar("budget", "What each reserve can hold",
                               "A stacked bar per publish reserve of the 500 ms the database admission guard demands plus the commit p95 at 30 technicians, inside a dashed frame "
                               "of that reserve; at 250 and 500 ms the stack overflows the frame.",
                               "THE GUARD PLUS THE COMMIT AT 30 TECHNICIANS, AGAINST EACH RESERVE THE STUDIES RAN",
                               [{"label": f"{d['reserveMs']} ms reserve", "values": {"guard": d["guard"], "commit": d["commitP95at30"]}} for d in out["budget"]],
                               (("guard", "Database admission guard", ink(0.15)), ("commit", "Commit p95 at 30 technicians", ACCENT)), 200, ms, "MILLISECONDS",
                               [f"The guard refuses a database call with BUSY when less than {GUARD_MS} ms is left, so a reserve of {GUARD_MS} ms or less leaves the commit nothing.",
                                "The commit p95 is the worse of 1000 and 2:3000 at 30 technicians at 800 ms; 250 and 500 ms recorded none, every long search was refused."],
                               frames=("The reserve", [d["reserveMs"] for d in out["budget"]])))

    render_all(pages, 3)
    (folder / "chart_data.json").write_text(json.dumps(out, indent=1), encoding="utf-8")
    return out
