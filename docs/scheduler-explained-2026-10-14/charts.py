"""Reproducible figures for the scheduler explainer: diagrams drawn from the documents and charts from the validated studies."""
import json
from pathlib import Path
from statistics import mean, median

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.colors import LinearSegmentedColormap, TwoSlopeNorm
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch

from report_data import per_fleet

HOST, SCHED, PRIVATE, STORE, INK = "#DCE6EF", "#7DAFCB", "#F4F6F8", "#C9C9C9", "#1F2A33"
# One color per thread of the work, kept across every figure.
THREAD = {"daily": "#2E8B57", "booking": "#5B9BD5", "routing": "#B22222", "admission": "#C37E12", "engine": "#6B4C9A", "remote": "#E07B39", "before": "#444444", "navy": "#0B3A66"}
SOLVER_LABEL = {"TABU": "Tabu", "TABU_SIZE_3": "Tabu size 3", "TABU_SIZE_15": "Tabu size 15", "TABU_KOPT": "Tabu + K opt", "TABU_KOPT_SIZE_3": "Tabu + K opt size 3",
                "KOPT": "K opt", "SUBLIST": "Sublist", "LATE_ACCEPTANCE": "Late acceptance"}
OFFERS, CONFIRM = "POST /api/v1/booking/offers 201", "POST /api/v1/booking/holds/{holdId}/confirm 200"
PART = {"queueMs": ("Slot wait", "#C9C9C9"), "routingMs": ("Routing", THREAD["routing"]), "calculationMs": ("Engine", THREAD["daily"]), "dbMs": ("Database", THREAD["admission"])}


def by_label(runs, label):
    out = [r for r in runs if r["label"] == label]
    assert len(out) == 1, label
    return out[0]


def by_run(runs, run, scenario):
    out = [r for r in runs if r.get("run") == run and r["scenario"] == scenario]
    assert len(out) == 1, (run, scenario)
    return out[0]


def box(ax, x, y, w, h, text, color, size=9.5, bold=False):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.02,rounding_size=0.15", facecolor=color, edgecolor="#5A6B7A", linewidth=.9))
    ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=size, color=INK, fontweight="bold" if bold else "normal", linespacing=1.25)


def arrow(ax, a, b, text="", color="#444444", style="-|>", dy=.12, size=8.5):
    ax.add_patch(FancyArrowPatch(a, b, arrowstyle=style, mutation_scale=12, color=color, linewidth=1.1, shrinkA=2, shrinkB=2))
    if text:
        ax.text((a[0] + b[0]) / 2, (a[1] + b[1]) / 2 + dy, text, ha="center", va="bottom", fontsize=size, color=color)


def make_charts(data, folder):
    folder.mkdir(exist_ok=True)
    font = next((p for p in (Path("C:/Windows/Fonts/calibri.ttf"), Path("/usr/share/fonts/truetype/crosextra/Carlito-Regular.ttf")) if p.exists()), None)
    if font is None:
        raise RuntimeError("Calibri or Carlito is required")
    font_manager.fontManager.addfont(str(font))
    plt.rcParams.update({"font.family": font_manager.FontProperties(fname=str(font)).get_name(), "font.size": 11,
                         "axes.spines.top": False, "axes.spines.right": False, "axes.unicode_minus": False, "savefig.dpi": 200})
    recorded = {}
    perf, runs, extras, benchmarks = data["perf"], data["perf"]["runs"], data["perf"]["extras"], data["perf"]["benchmarks"]
    per_minute, queue_wait = perf["per_minute"], perf["queue_wait"]
    signed = LinearSegmentedColormap.from_list("signed", ["#DDAA76", "#FFFFFF", "#7DAFCB"])

    def save(fig, name):
        fig.savefig(folder / f"{name}.png", facecolor="white", metadata={"Software": "WaterFlex report"})
        plt.close(fig)

    def canvas(w=9, h=3.9):
        fig, ax = plt.subplots(figsize=(w, h), layout="constrained")
        ax.set_xlim(0, 10); ax.set_ylim(0, 10); ax.axis("off")
        return fig, ax

    # 1. Topology: authored as an architecture diagram in figures/topology.html (inline SVG, the diagram-design format) and rasterized here.
    from figures.render import render
    source = Path(__file__).resolve().parent / "figures" / "topology.html"
    render(source, folder / "topology.png", 3)
    recorded["topology"] = {"source": "figures/topology.html", "boxes": ["host backend", "scheduler-proxy", "booking pool", "daily pool", "routing-service per metro", "Nominatim", "scheduler-db", "management port"]}

    # 2. The booking sequence with its outcomes.
    fig, ax = canvas(9, 3.6)
    steps = [("offers", "search: job, horizon,\nsnapshot of every\ntechnician-day", 201), ("select", "customer picks\none window", 200), ("confirm", "fresh snapshot of\nthe hold's date", 200), ("write receipt", "compare-and-set on\nthe listed lastModified", None)]
    for i, (name, text, status) in enumerate(steps):
        x = .3 + i * 2.45
        box(ax, x, 5.2, 2.1, 3.4, f"{name}\n\n{text}", SCHED if status else HOST, 8.5)
        if status: ax.text(x + 1.05, 8.9, f"{status}", ha="center", fontsize=9, color=THREAD["daily"], fontweight="bold")
        if i < 3: arrow(ax, (x + 2.1, 6.9), (x + 2.45, 6.9))
    box(ax, .3, .8, 9.4, 2.4, "", PRIVATE)
    ax.text(5.0, 2.75, "Any 409 or a failed write: release the set (or let the hold expire in 10 minutes) and search again with a new requestId", ha="center", fontsize=8.5, color=INK, fontweight="bold")
    for x, text in ((1.35, "409 STALE: snapshot\nolder than records\nalready used"), (3.8, "409 HOLD_UNAVAILABLE:\nhold expired, lost or\nanother offer selected"), (6.25, "409 HOLD_UNAVAILABLE:\nday no longer fits\nor would add overtime"), (8.7, "write fails:\na timestamp moved\nsince the receipt")):
        ax.text(x, 1.7, text, ha="center", va="center", fontsize=8, color=THREAD["routing"])
    for i in range(4):
        arrow(ax, (.3 + i * 2.45 + 1.05, 5.2), (.3 + i * 2.45 + 1.05, 3.2), color=THREAD["routing"])
    recorded["booking"] = {"steps": [s[0] for s in steps], "refusals": ["STALE", "HOLD_UNAVAILABLE", "HOLD_UNAVAILABLE", "host write conflict"]}
    save(fig, "booking")

    # 3. Daily and repair proposals: synchronous and queued, then commit.
    fig, ax = canvas(9, 3.6)
    box(ax, .3, 6.0, 2.2, 3.2, "POST daily or repair\nproposal\n\nsnapshot of one\nmetro day", SCHED, 8.5)
    box(ax, 3.4, 7.4, 2.6, 1.9, "synchronous\nanswers 201 after the\nsearch (cost + fairness\nseconds, + up to 5 s)", PRIVATE, 8)
    box(ax, 3.4, 4.6, 2.6, 2.2, "Prefer: respond-async\n202 at once; any replica\ntakes it in turn across\nclients; poll GET\n/requests/{requestId}", PRIVATE, 8)
    box(ax, 6.9, 6.0, 2.8, 3.2, "DailyProposal\n\nIMPROVED: commit it\nNO_IMPROVEMENT or\nREJECTED_BY_POLICY:\ncurrent routes, reason", SCHED, 8.5)
    box(ax, 6.9, .8, 2.8, 2.8, "POST .../commit\ncurrent lastModified of\nevery technician-day\n\n200 receipt: write with a\ncompare-and-set", SCHED, 8.5)
    box(ax, .3, .8, 5.7, 2.8, "409 STALE: a technician-day changed, ask for a new proposal\n409 NOT_COMMITTABLE: already committed or not IMPROVED\n422: the day is frozen (after 6 a.m. local)\n429 BUSY with Retry-After: the calculations ahead would not finish in time\nDELETE /requests/{requestId}: best-effort cancel, 409 CANCELLED to the call", PRIVATE, 8)
    arrow(ax, (2.5, 8.2), (3.4, 8.3)); arrow(ax, (2.5, 6.8), (3.4, 5.7)); arrow(ax, (6.0, 8.3), (6.9, 8.2)); arrow(ax, (6.0, 5.7), (6.9, 6.8))
    arrow(ax, (8.3, 6.0), (8.3, 3.6), "IMPROVED", dy=-.7)
    recorded["daily"] = {"modes": ["synchronous", "queued"], "decisions": ["IMPROVED", "NO_IMPROVEMENT", "REJECTED_BY_POLICY"]}
    save(fig, "daily")

    # 4. The route cache tiers, in lookup order, from the design document's table.
    fig, ax = canvas(9, 3.3)
    tiers = data["docs"]["tiers"][1]
    for i, row in enumerate(tiers):
        x = .3 + i * 2.4
        name = row[0].split(". ", 1)[1]
        box(ax, x, 3.0, 2.2, 5.6, f"{row[0].split('.')[0]}. {name}\n\n{row[1].replace('`', '').replace(' in ', chr(10) + 'in ')}\n\nshared by:\n{row[4]}", SCHED if i in (1, 3) else (STORE if i == 2 else PRIVATE), 8)
        if i < 3: arrow(ax, (x + 2.2, 5.8), (x + 2.4, 5.8))
    ax.text(5.0, 1.6, "Every entry is bound to a routing identity (a hash of the road graph and map version), so a changed map can never be mixed with old legs.\nKeys are coordinates rounded to 5 decimals (about 1 m); a changed identity clears the memory tiers and starts the caches cold.", ha="center", va="center", fontsize=8.5, color=INK)
    recorded["tiers"] = [row[0] for row in tiers]
    save(fig, "tiers")

    # 5. The daily search studies: mean saving against Tabu by fleet, first study (16 fixtures) and follow-up (40 new fixtures, with Tabu + K opt size 3).
    first, follow = data["daily"]["first"]["rows"], data["daily"]["follow"]["rows"]
    panels = [("first", first, (5, 10, 20, 50), ("TABU_SIZE_3", "TABU_SIZE_15", "TABU_KOPT", "KOPT", "SUBLIST", "LATE_ACCEPTANCE"), "TABU_KOPT", "Tabu variant study (Oct 11), 160 cases per cell"),
              ("follow", follow, (5, 10, 20, 30, 50), ("TABU_KOPT", "TABU_KOPT_SIZE_3", "TABU_SIZE_3", "KOPT"), "TABU_KOPT_SIZE_3", "Follow-up on 40 new fixtures (Oct 13), 48 cases per cell")]
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.9), layout="constrained", width_ratios=[1.15, 1])
    recorded["daily_fleet"] = {}
    for ax, (key, rows, fleets, solvers, bold, title) in zip(axes, panels):
        values = [per_fleet(rows, s, fleets) + [mean(r["saving"] for r in rows if r["solver"] == s)] for s in solvers]
        im = ax.imshow(values, cmap=signed, norm=TwoSlopeNorm(vmin=-30, vcenter=0, vmax=30), aspect="auto")
        ax.set_yticks(range(len(solvers)), [SOLVER_LABEL[s] for s in solvers], fontsize=9); ax.set_xticks(range(len(fleets) + 1), [f"{f}" for f in fleets] + ["all"], fontsize=9)
        ax.set_xlabel("Technicians"); ax.set_title(title, fontsize=9.5); ax.tick_params(length=0)
        for i, row in enumerate(values):
            for j, v in enumerate(row):
                ax.text(j, i, f"{v:+.2f}", ha="center", va="center", fontsize=8.5, fontweight="bold" if solvers[i] == bold else "normal")
        recorded["daily_fleet"][key] = {"fleets": list(fleets), "solvers": list(solvers), "values": values}
    fig.colorbar(im, ax=axes, label="Mean saving vs Tabu ($ per case)", shrink=.9)
    save(fig, "daily_fleet")

    # 6. Booking search: service in the September held-out matrix, and the sequential cost against the latency of bounded search.
    b24, f29 = data["booking24"], data["field29"]
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.6), layout="constrained")
    held = [("INSERTION", "Insertion"), ("BOUNDED", "Bounded"), ("SHARED", "Shared")]
    recorded["booking_study"] = {"held": [{"variant": v, "served": f29["booking"][("held", v)]["served"], "requests": f29["booking"][("held", v)]["requests"],
                                           "mean_case_p95_ms": f29["booking"][("held", v)]["meanCaseP95Ms"]} for v, _ in held],
                                 "latency": [{"solver": s, "concurrency": c, "p95": b24["latency"][(s, c)]["p95"]} for s in ("INSERTION", "BOUNDED") for c in (1, 5, 10)]}
    axes[0].bar(range(3), [h["served"] for h in recorded["booking_study"]["held"]], .6, color=[THREAD["before"], THREAD["booking"], "#999999"])
    for i, h in enumerate(recorded["booking_study"]["held"]):
        axes[0].text(i, h["served"] + 6, f"{h['served']} of {h['requests']}\np95 {h['mean_case_p95_ms'] / 1000:.1f} s", ha="center", fontsize=8.5)
    axes[0].set_xticks(range(3), [n for _, n in held]); axes[0].set_ylim(0, 480); axes[0].set_ylabel("Requests served of 480"); axes[0].set_title("Held-out service, Sept 29 (48 cases each)"); axes[0].grid(axis="y", alpha=.2)
    for s, color, label in (("INSERTION", THREAD["before"], "Insertion"), ("BOUNDED", THREAD["booking"], "Bounded")):
        axes[1].plot([1, 5, 10], [b24["latency"][(s, c)]["p95"] / 1000 for c in (1, 5, 10)], marker="o", color=color, label=label)
    axes[1].set_xticks([1, 5, 10]); axes[1].set_xlabel("Simultaneous requests"); axes[1].set_ylabel("Search p95 (s)"); axes[1].set_title("Latency, Sept 24 (1,800 requests per method)")
    axes[1].legend(fontsize=9); axes[1].grid(alpha=.2)
    save(fig, "booking_study")

    # 7. Routing alone: flexible against CH on 132 Omaha pairs, two maps and two dates.
    fig, ax = plt.subplots(figsize=(9, 3.6), layout="constrained")
    recorded["ch"] = []
    for date, doc in benchmarks.items():
        for engine, style in (("flexible", "--"), ("CH-car-v1", "-")):
            rows = [l for l in doc["latency"] if l["engine"] == engine]
            recorded["ch"].append({"date": date, "engine": engine, "concurrency": [l["concurrency"] for l in rows], "p50": [l["p50Ms"] for l in rows], "p95": [l["p95Ms"] for l in rows]})
            ax.plot([l["concurrency"] for l in rows], [l["p50Ms"] for l in rows], marker="o" if date == "2026-10-14" else "s", linestyle=style,
                    color=THREAD["routing"] if engine == "CH-car-v1" else THREAD["before"], label=f"{'CH' if engine == 'CH-car-v1' else 'Flexible'}, map {doc['engines'][0]['mapVersion']} ({date})")
    ax.set_yscale("log"); ax.set_xticks([1, 5, 10]); ax.set_xlabel("Concurrent callers"); ax.set_ylabel("p50 per 16-pair request (ms, log)")
    ax.set_title("Routing service alone, caches off"); ax.grid(alpha=.2, which="both"); ax.legend(fontsize=9)
    save(fig, "ch")

    # 8. Where a booking call's server time goes, from the VM baseline to the shipped build and the remote pool.
    attribution = [("J0 baseline", by_run(runs["vm"], "base-spread2", "booking")), ("J flexible", by_run(runs["routing"], "j-flex", "booking")), ("J CH", by_run(runs["routing"], "j-ch", "booking")),
                   ("J2 shipped", by_run(runs["priority"], "j2-slot", "booking")), ("K remote", by_run(runs["remote"], "k-remote5", "booking"))]
    recorded["attribution"] = []
    fig, ax = plt.subplots(figsize=(9, 4.0), layout="constrained")
    for i, (stage, r) in enumerate(attribution):
        for op, y in ((OFFERS, i + .18), (CONFIRM, i - .18)):
            entry = r["breakdown"][op]
            parts = {k: entry[k]["p50"] for k in PART}
            recorded["attribution"].append({"stage": stage, "operation": op, "elapsed_p50": entry["elapsedMs"]["p50"], **parts})
            left = 0
            for k, (name, color) in PART.items():
                ax.barh(y, parts[k], .34, left=left, color=color, label=name if i == 0 and op == OFFERS else None); left += parts[k]
            ax.text(left + 8, y, f"{entry['elapsedMs']['p50']} ms {'offers' if op == OFFERS else 'confirm'}", va="center", fontsize=8.5)
    ax.set_yticks(range(5), [s for s, _ in attribution]); ax.set_xlabel("Server time of the median call (ms)"); ax.set_xlim(0, 1000)
    ax.set_title("Where a booking call's time goes (p50 of each part)"); ax.legend(fontsize=9, loc="lower right"); ax.grid(axis="x", alpha=.2); ax.invert_yaxis()
    save(fig, "attribution")

    # 9. Five desks at each stage of the admission and concurrency work.
    stages = [("#151", by_label(runs["baseline"], "booking-cold-c5")), ("#152", by_label(extras["coldRemeasurement"], "cold-recheck-c5 (seed 91)")),
              ("#154", by_label(runs["outcome"], "booking-cold-c5")), ("#155", by_label(runs["shedding"], "after-c5 (seed 55)")),
              ("J0", by_run(runs["vm"], "base-spread2", "booking")), ("J flex", by_run(runs["routing"], "j-flex", "booking")),
              ("J CH", by_run(runs["routing"], "j-ch", "booking")), ("J2", by_run(runs["priority"], "j2-slot", "booking"))]
    colors = [THREAD["before"], THREAD["admission"], THREAD["booking"], THREAD["booking"], THREAD["navy"], "#7DAFCB", THREAD["routing"], THREAD["admission"]]
    recorded["progression"] = [{"stage": s, "label": r["label"], "searches_per_minute": per_minute(r), "p50": r["ops"]["booking offers"]["ms"]["p50"], "p95": r["ops"]["booking offers"]["ms"]["p95"]} for s, r in stages]
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.7), layout="constrained")
    x = range(len(stages))
    axes[0].bar(x, [d["searches_per_minute"] for d in recorded["progression"]], color=colors)
    axes[0].set_ylabel("Booking searches answered per minute"); axes[0].set_title("Throughput at five desks")
    axes[1].bar([i - .2 for i in x], [d["p50"] for d in recorded["progression"]], .4, color="#7DAFCB", label="p50")
    axes[1].bar([i + .2 for i in x], [d["p95"] for d in recorded["progression"]], .4, color=THREAD["navy"], label="p95")
    axes[1].axhline(5500, color=THREAD["routing"], linewidth=1, linestyle="--"); axes[1].text(7.4, 5600, "5.5 s bound", color=THREAD["routing"], fontsize=9, ha="right")
    axes[1].set_ylabel("Caller latency of offers (ms)"); axes[1].set_title("Latency at five desks"); axes[1].legend(fontsize=9)
    for ax in axes:
        ax.set_xticks(list(x), [s for s, _ in stages], fontsize=9); ax.grid(axis="y", alpha=.2); ax.axvline(3.5, color="#999999", linewidth=.8, linestyle=":")
    axes[0].text(1.5, axes[0].get_ylim()[1] * .95, "one machine, desks alone", ha="center", fontsize=8.5, color="#666666")
    axes[0].text(5.4, axes[0].get_ylim()[1] * .95, "VM shape, with daily planners", ha="center", fontsize=8, color="#666666")
    save(fig, "progression")

    # 10. Daily proposals: answered or refused, fairness between clients, and replica pools.
    fig, axes = plt.subplots(1, 3, figsize=(9, 3.6), layout="constrained")
    recorded["daily_load"] = {"admission": [], "fairness": [], "roles": []}
    for i, (name, b, a) in enumerate((("1 planner", by_label(runs["baseline"], "daily-c1"), by_label(runs["admission"], "daily-c1")), ("4 planners", by_label(runs["baseline"], "daily-c4"), by_label(runs["admission"], "daily-c4")))):
        for j, (stage, r) in enumerate((("#151", b), ("#153", a))):
            ok = r["ops"]["daily proposal"]["statuses"].get("201", 0); bad = r["ops"]["daily proposal"]["statuses"].get("503", 0)
            recorded["daily_load"]["admission"].append({"planners": name, "stage": stage, "answered_201": ok, "answered_503": bad})
            xx = i * 2.4 + j
            axes[0].bar(xx, ok, .8, color=THREAD["before"] if stage == "#151" else THREAD["admission"]); axes[0].bar(xx, bad, .8, bottom=ok, color="#DDAA76", hatch="//")
            axes[0].text(xx, ok + bad + .4, stage, ha="center", fontsize=8)
    axes[0].set_xticks([.5, 2.9], ["1 planner", "4 planners"]); axes[0].set_ylabel("Proposals in 60 s"); axes[0].set_title("Answered (solid) or 503 (hatched)")
    fair = {(r["note"], r["desks"]): r for r in runs["queue"] if r["note"] in ("sync", "async")}
    for i, (m, t, c) in enumerate(((m, t, c) for m in ("sync", "async") for t, c in (("A", 4), ("B", 1)))):
        recorded["daily_load"]["fairness"].append({"mode": m, "client": t, "planners": c, "proposals": fair[(m, c)]["daily"]["proposals"]})
        axes[1].bar(i, fair[(m, c)]["daily"]["proposals"], .7, color=THREAD["admission"] if m == "async" else THREAD["before"])
    axes[1].set_xticks(range(4), ["A x4\nsync", "B x1\nsync", "A x4\nqueued", "B x1\nqueued"], fontsize=8.5); axes[1].set_ylabel("Proposals in 90 s"); axes[1].set_title("Two clients, one replica (#157)")
    for i, (run, shape) in enumerate((("all-1", "one all"), ("split-1", "split"), ("split-2", "split"), ("all-2", "one all"))):
        d = by_run(runs["roles"], run, "daily")
        recorded["daily_load"]["roles"].append({"run": run, "shape": shape, "proposals": d["daily"]["proposals"], "queue_wait_p50": queue_wait(d)[0]})
        axes[2].bar(i, d["daily"]["proposals"], .7, color=THREAD["admission"] if shape == "split" else THREAD["before"])
    axes[2].set_xticks(range(4), ["all 1", "split 1", "split 2", "all 2"], fontsize=9); axes[2].set_ylabel("Proposals in 60 s"); axes[2].set_title("One replica or two pools (#158)")
    for ax in axes:
        ax.grid(axis="y", alpha=.2)
    save(fig, "daily_load")

    # 11. J2: confirm and offers at the caller, before and after confirm priority and the confirm slot.
    j2 = [("J CH", by_run(runs["routing"], "j-ch", "booking")), ("priority", by_run(runs["priority"], "j2-confirm", "booking")), ("priority,\nZGC / Parallel", by_run(runs["priority"], "j2-gc", "booking")),
          ("priority +\nconfirm slot", by_run(runs["priority"], "j2-slot", "booking")), ("slot, compact\nheaders", by_run(runs["priority"], "j2-coh2", "booking"))]
    recorded["confirm"] = [{"stage": s, "run": r.get("run"), "confirm_p50": r["ops"]["confirm"]["ms"]["p50"], "confirm_p95": r["ops"]["confirm"]["ms"]["p95"], "confirm_wait_p50": r["breakdown"][CONFIRM]["queueMs"]["p50"],
                           "offers_p50": r["ops"]["booking offers"]["ms"]["p50"], "offers_p95": r["ops"]["booking offers"]["ms"]["p95"]} for s, r in j2]
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.7), layout="constrained")
    x = range(len(j2))
    for ax, key, title in ((axes[0], "confirm", "Confirm at the caller"), (axes[1], "offers", "Offers at the caller")):
        ax.bar([i - .2 for i in x], [d[f"{key}_p50"] for d in recorded["confirm"]], .4, color="#7DAFCB", label="p50")
        ax.bar([i + .2 for i in x], [d[f"{key}_p95"] for d in recorded["confirm"]], .4, color=THREAD["admission"], label="p95")
        ax.set_xticks(list(x), [s for s, _ in j2], fontsize=8.5); ax.set_ylabel("Caller latency (ms)"); ax.set_title(title); ax.grid(axis="y", alpha=.2)
        ax.set_ylim(0, max(d[f"{key}_p95"] for d in recorded["confirm"]) * 1.25); ax.legend(fontsize=9, loc="upper right", ncol=2)
    for i, d in enumerate(recorded["confirm"]):
        axes[0].text(i - .2, d["confirm_p50"] + 3, f"wait {d['confirm_wait_p50']}", ha="center", fontsize=7.5, color="#444444")
    save(fig, "confirm")

    # 12. Inside the engine: the 24 paired cases of study L, and embedded against remote (K).
    prof = extras["profiling"]
    recorded["paired"] = [{"technicians": p["technicians"], "workload": p["workload"], "seed": p["seed"], "before": p["costPhaseMovesPerSecond"]["before"], "after": p["costPhaseMovesPerSecond"]["after"],
                           "speedup": p["costPhaseSpeedup"], "cost_before": p["afterCostCents"]["before"], "cost_after": p["afterCostCents"]["after"]} for p in prof["pairs"]]
    k = [("Embedded", "k-embedded"), ("Remote, old\npool defaults", "k-remote"), ("Remote,\ncapacity 4", "k-remote4"), ("Remote, cap. 4,\nevery slot daily", "k-remote5")]
    recorded["remote"] = []
    for name, run in k:
        b, d = by_run(runs["remote"], run, "booking"), by_run(runs["remote"], run, "daily")
        recorded["remote"].append({"run": run, "offers_p50": b["ops"]["booking offers"]["ms"]["p50"], "offers_p95": b["ops"]["booking offers"]["ms"]["p95"], "searches": b["booking"]["searches"],
                                   "answered_201": d["ops"]["daily proposal"]["statuses"].get("201", 0), "answered_503": d["ops"]["daily proposal"]["statuses"].get("503", 0),
                                   "daily_cpu_mean": [c for c in b["containers"] if "daily" in c["name"]][0]["cpuPercent"]["mean"]})
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.8), layout="constrained")
    for fleet, color, marker in ((20, "#7DAFCB", "o"), (50, THREAD["engine"], "s")):
        pts = [d for d in recorded["paired"] if d["technicians"] == fleet]
        axes[0].scatter([d["before"] / 1000 for d in pts], [d["after"] / 1000 for d in pts], s=40, color=color, marker=marker, label=f"{fleet} technicians")
    lim = max(d["after"] for d in recorded["paired"]) / 1000 * 1.05
    axes[0].plot([0, lim], [0, lim], color="#999999", linewidth=.8, linestyle=":"); axes[0].text(lim * .97, lim * .91, "equal", fontsize=8, ha="right", color="#666666")
    axes[0].plot([0, lim / 2], [0, lim], color=THREAD["routing"], linewidth=.8, linestyle="--"); axes[0].text(lim * .22, lim * .5, "2x", fontsize=8, ha="right", color=THREAD["routing"])
    axes[0].set_xlim(0, lim); axes[0].set_ylim(0, lim); axes[0].set_xlabel("Before: cost-phase moves per second (thousands)"); axes[0].set_ylabel("After RoadMatrix (thousands)")
    axes[0].set_title("Study L: 24 paired solves"); axes[0].legend(fontsize=9, loc="lower right"); axes[0].grid(alpha=.2)
    x = range(4)
    axes[1].bar([i - .2 for i in x], [d["offers_p50"] for d in recorded["remote"]], .4, color="#7DAFCB", label="offers p50")
    axes[1].bar([i + .2 for i in x], [d["offers_p95"] for d in recorded["remote"]], .4, color=[THREAD["engine"], *[THREAD["remote"]] * 3], label="offers p95")
    for i, d in enumerate(recorded["remote"]):
        axes[1].text(i, d["offers_p95"] + 10, f"daily {d['answered_201']} of {d['answered_201'] + d['answered_503']}\nCPU {d['daily_cpu_mean']:.0f}%", ha="center", fontsize=7.5, color="#444444")
    axes[1].set_xticks(list(x), [n for n, _ in k], fontsize=8); axes[1].set_ylabel("Offers at the caller (ms)"); axes[1].set_title("Study K: embedded against a remote pool")
    axes[1].set_ylim(0, max(d["offers_p95"] for d in recorded["remote"]) * 1.35); axes[1].legend(fontsize=8.5, loc="upper left", ncol=2); axes[1].grid(axis="y", alpha=.2)
    save(fig, "engine")

    (folder / "chart_data.json").write_text(json.dumps(recorded, indent=2) + "\n", encoding="utf-8")
    return recorded


if __name__ == "__main__":
    from report_data import load
    out = make_charts(load(), Path(__file__).resolve().parent / "generated")
    print(sorted(out))
