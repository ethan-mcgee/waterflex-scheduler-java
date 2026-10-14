"""Reproducible charts from the validated load studies and routing benchmarks; no network or benchmark runs."""
import json
from pathlib import Path
from statistics import median

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager

from report_data import per_minute, queue_wait

# One color per stage of the work, kept across every figure.
COLOR = {"#151": "#444444", "#152": "#5B9BD5", "#153": "#2E8B57", "#154": "#8064A2", "#155": "#2E8B57", "#157": "#B34F75", "#158": "#C37E12",
         "J0": "#0B3A66", "J flexible": "#7DAFCB", "J CH": "#B22222"}
PART = {"queueMs": ("Slot wait", "#C9C9C9"), "routingMs": ("Routing", "#5B9BD5"), "calculationMs": ("Engine", "#2E8B57"), "dbMs": ("Database", "#C37E12")}


def by_label(runs, label):
    out = [r for r in runs if r["label"] == label]
    assert len(out) == 1, label
    return out[0]


def by_run(runs, run, scenario):
    out = [r for r in runs if r.get("run") == run and r["scenario"] == scenario]
    assert len(out) == 1, (run, scenario)
    return out[0]


def make_charts(runs, extras, benchmarks, folder):
    folder.mkdir(exist_ok=True)
    font = next((p for p in (Path("C:/Windows/Fonts/calibri.ttf"), Path("/usr/share/fonts/truetype/crosextra/Carlito-Regular.ttf")) if p.exists()), None)
    if font is None:
        raise RuntimeError("Calibri or Carlito is required")
    font_manager.fontManager.addfont(str(font))
    plt.rcParams.update({"font.family": font_manager.FontProperties(fname=str(font)).get_name(),
                         "font.size": 11, "axes.spines.top": False, "axes.spines.right": False,
                         "axes.unicode_minus": False, "savefig.dpi": 200})
    data = {}

    def save(fig, name):
        fig.savefig(folder / f"{name}.png", facecolor="white", metadata={"Software": "WaterFlex report"})
        plt.close(fig)

    # 1. Five desks at each stage: searches per minute and caller latency.
    stages = [("#151", by_label(runs["baseline"], "booking-cold-c5")), ("#152", by_label(extras["coldRemeasurement"], "cold-recheck-c5 (seed 91)")),
              ("#154", by_label(runs["outcome"], "booking-cold-c5")), ("#155", by_label(runs["shedding"], "after-c5 (seed 55)")),
              ("J0", by_run(runs["vm"], "base-spread2", "booking")), ("J flexible", by_run(runs["routing"], "j-flex", "booking")),
              ("J CH", by_run(runs["routing"], "j-ch", "booking"))]
    data["progression"] = [{"stage": s, "label": r["label"], "searches_per_minute": per_minute(r), "p50": r["ops"]["booking offers"]["ms"]["p50"],
                            "p95": r["ops"]["booking offers"]["ms"]["p95"]} for s, r in stages]
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.7), layout="constrained")
    x = range(len(stages))
    axes[0].bar(x, [d["searches_per_minute"] for d in data["progression"]], color=[COLOR[s] for s, _ in stages])
    axes[0].set_ylabel("Booking searches answered per minute"); axes[0].set_title("Throughput at five desks")
    axes[1].bar([i - .2 for i in x], [d["p50"] for d in data["progression"]], .4, color="#7DAFCB", label="p50")
    axes[1].bar([i + .2 for i in x], [d["p95"] for d in data["progression"]], .4, color="#0B3A66", label="p95")
    axes[1].axhline(5500, color="#B22222", linewidth=1, linestyle="--"); axes[1].text(6.4, 5600, "5.5 s bound", color="#B22222", fontsize=9, ha="right")
    axes[1].set_ylabel("Caller latency of offers (ms)"); axes[1].set_title("Latency at five desks"); axes[1].legend(fontsize=9)
    for ax in axes:
        ax.set_xticks(list(x), [s for s, _ in stages], fontsize=9); ax.grid(axis="y", alpha=.2)
        ax.axvline(3.5, color="#999999", linewidth=.8, linestyle=":")
    axes[0].text(1.5, axes[0].get_ylim()[1] * .95, "one machine, desks alone", ha="center", fontsize=8.5, color="#666666")
    axes[0].text(5, axes[0].get_ylim()[1] * .95, "VM shape, with daily planners", ha="center", fontsize=8.5, color="#666666")
    save(fig, "progression")

    # 2. Latency by desk count on fresh addresses: baseline, after #152 and after #155.
    series = [("#151", [by_label(runs["baseline"], f"booking-cold-c{c}") for c in (1, 5, 10)]),
              ("#152", [by_label(extras["coldRemeasurement"], l) for l in ("cold-recheck-c1 (seed 93)", "cold-recheck-c5 (seed 91)", "cold-recheck-c10 (seed 92)")]),
              ("#155", [by_label(runs["shedding"], l) for l in ("after-c1 (seed 51)", "after-c5 (seed 55)", "after-c10 (seed 60)")])]
    data["desks"] = [{"stage": s, "desks": [1, 5, 10], "p95": [r["ops"]["booking offers"]["ms"]["p95"] for r in rs],
                      "searches_per_minute": [per_minute(r) for r in rs]} for s, rs in series]
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.7), layout="constrained")
    for d in data["desks"]:
        axes[0].plot(d["desks"], [v / 1000 for v in d["p95"]], marker="o", color=COLOR[d["stage"]], label=d["stage"])
        axes[1].plot(d["desks"], d["searches_per_minute"], marker="o", color=COLOR[d["stage"]], label=d["stage"])
    axes[0].axhline(5.5, color="#B22222", linewidth=1, linestyle="--"); axes[0].text(10, 5.7, "5.5 s bound", color="#B22222", fontsize=9, ha="right")
    axes[0].set_ylabel("Offers p95 at the caller (s)"); axes[0].set_title("Latency by number of desks")
    axes[1].set_ylabel("Searches answered per minute"); axes[1].set_title("Throughput by number of desks")
    for ax in axes:
        ax.set_xticks([1, 5, 10]); ax.set_xlabel("Booking desks on the same five dates"); ax.grid(alpha=.2); ax.legend(fontsize=9)
    save(fig, "desks")

    # 3. Holds lost at confirm before and after #152, and the STALE resends that replaced them.
    before = [by_label(runs["baseline"], l) for l in ("booking-cold-c1", "booking-cold-c5", "booking-cold-c10", "booking-warm-c5", "mixed-c5-d1")]
    after = [by_label(runs["concurrency"], l) for l in ("booking-cold-c1", "booking-cold-c5", "booking-cold-c10", "booking-warm-c5", "mixed-c5-d1")]
    data["holds"] = [{"run": b["label"], "lost_before": b["booking"]["holdUnavailable"], "selected_before": b["booking"]["selected"],
                      "lost_after": a["booking"]["holdUnavailable"], "selected_after": a["booking"]["selected"],
                      "stale_after": a["booking"]["stale"], "searches_after": a["booking"]["searches"]} for b, a in zip(before, after)]
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.7), layout="constrained")
    x = range(5)
    axes[0].bar([i - .2 for i in x], [d["lost_before"] / d["selected_before"] * 100 for d in data["holds"]], .4, color=COLOR["#151"], label="#151 before")
    axes[0].bar([i + .2 for i in x], [d["lost_after"] / d["selected_after"] * 100 for d in data["holds"]], .4, color=COLOR["#152"], label="#152 after")
    axes[0].set_ylabel("Selected holds lost at confirm (%)"); axes[0].set_title("Holds lost to late snapshots"); axes[0].legend(fontsize=9)
    axes[1].bar(list(x), [d["stale_after"] / d["searches_after"] for d in data["holds"]], .5, color=COLOR["#152"])
    axes[1].set_ylabel("STALE resends per answered search"); axes[1].set_title("The cost after #152: resends")
    for ax in axes:
        ax.set_xticks(list(x), ["1 desk", "5 desks", "10 desks", "5 warm", "mixed"], fontsize=9); ax.grid(axis="y", alpha=.2)
    save(fig, "holds")

    # 4. Daily proposals: answered or refused (#151 vs #153), per client (L4), and per replica shape (L5).
    fig, axes = plt.subplots(1, 3, figsize=(9, 3.6), layout="constrained")
    daily_pairs = [("1 planner", by_label(runs["baseline"], "daily-c1"), by_label(runs["admission"], "daily-c1")),
                   ("4 planners", by_label(runs["baseline"], "daily-c4"), by_label(runs["admission"], "daily-c4"))]
    data["daily_admission"] = []
    for i, (name, b, a) in enumerate(daily_pairs):
        for j, (stage, r) in enumerate((("#151", b), ("#153", a))):
            ok = r["ops"]["daily proposal"]["statuses"].get("201", 0); bad = r["ops"]["daily proposal"]["statuses"].get("503", 0)
            data["daily_admission"].append({"planners": name, "stage": stage, "answered_201": ok, "answered_503": bad, "retried_429": r["ops"]["daily proposal"]["retriedBusy"]})
            xx = i * 2.4 + j
            axes[0].bar(xx, ok, .8, color=COLOR[stage]); axes[0].bar(xx, bad, .8, bottom=ok, color="#DDAA76", hatch="//")
            axes[0].text(xx, ok + bad + .4, stage, ha="center", fontsize=8)
    axes[0].set_xticks([.5, 2.9], ["1 planner", "4 planners"]); axes[0].set_ylabel("Proposals in 60 s"); axes[0].set_title("Answered (solid) or 503 (hatched)")
    fair = {(r["note"], r["desks"]): r for r in runs["queue"] if r["note"] in ("sync", "async")}
    data["daily_fairness"] = [{"mode": m, "tenant": t, "planners": c, "proposals": fair[(m, c)]["daily"]["proposals"],
                               "p50": fair[(m, c)]["ops"]["daily proposal"]["ms"]["p50"]} for m in ("sync", "async") for t, c in (("A", 4), ("B", 1))]
    for i, d in enumerate(data["daily_fairness"]):
        axes[1].bar(i, d["proposals"], .7, color=COLOR["#157"] if d["mode"] == "async" else COLOR["#151"])
    axes[1].set_xticks(range(4), ["A x4\nsync", "B x1\nsync", "A x4\nqueued", "B x1\nqueued"], fontsize=8.5)
    axes[1].set_ylabel("Proposals in 90 s"); axes[1].set_title("Two clients sharing one replica (L4)")
    roles = [("all-1", "one all"), ("split-1", "split"), ("split-2", "split"), ("all-2", "one all")]
    data["daily_roles"] = [{"run": run, "shape": shape, "proposals": by_run(runs["roles"], run, "daily")["daily"]["proposals"],
                            "queue_wait_p50": queue_wait(by_run(runs["roles"], run, "daily"))[0]} for run, shape in roles]
    for i, d in enumerate(data["daily_roles"]):
        axes[2].bar(i, d["proposals"], .7, color=COLOR["#158"] if d["shape"] == "split" else COLOR["#151"])
    axes[2].set_xticks(range(4), ["all 1", "split 1", "split 2", "all 2"], fontsize=9); axes[2].set_ylabel("Proposals in 60 s")
    axes[2].set_title("One replica or two pools (L5)")
    for ax in axes:
        ax.grid(axis="y", alpha=.2)
    save(fig, "daily")

    # 5. Where a search's server time goes, before and after the routing work.
    attribution = [("J0", by_run(runs["vm"], "base-spread2", "booking")), ("J flexible", by_run(runs["routing"], "j-flex", "booking")), ("J CH", by_run(runs["routing"], "j-ch", "booking"))]
    data["attribution"] = []
    fig, ax = plt.subplots(figsize=(9, 3.2), layout="constrained")
    for i, (stage, r) in enumerate(attribution):
        for op, y in (("POST /api/v1/booking/offers 201", i + .18), ("POST /api/v1/booking/holds/{holdId}/confirm 200", i - .18)):
            entry = r["breakdown"][op]
            parts = {k: entry[k]["p50"] for k in PART}
            data["attribution"].append({"stage": stage, "operation": op, "elapsed_p50": entry["elapsedMs"]["p50"], **{k: v for k, v in parts.items()}})
            left = 0
            for k, (name, color) in PART.items():
                ax.barh(y, parts[k], .34, left=left, color=color, label=name if i == 0 and "offers" in op else None); left += parts[k]
            ax.text(left + 8, y, f"{entry['elapsedMs']['p50']} ms {'offers' if 'offers' in op else 'confirm'}", va="center", fontsize=8.5)
    ax.set_yticks(range(3), [s for s, _ in attribution]); ax.set_xlabel("Server time of the median call (ms)"); ax.set_xlim(0, 1000)
    ax.set_title("Where a booking call's time goes (p50 of each part)"); ax.legend(fontsize=9, loc="lower right"); ax.grid(axis="x", alpha=.2)
    ax.invert_yaxis()
    save(fig, "attribution")

    # 6. The routing API alone: flexible against CH on 132 Omaha pairs, two maps and two dates.
    fig, ax = plt.subplots(figsize=(9, 3.6), layout="constrained")
    data["ch"] = []
    for date, doc in benchmarks.items():
        for engine, style in (("flexible", "--"), ("CH-car-v1", "-")):
            rows = [l for l in doc["latency"] if l["engine"] == engine]
            data["ch"].append({"date": date, "engine": engine, "concurrency": [l["concurrency"] for l in rows], "p50": [l["p50Ms"] for l in rows],
                               "p95": [l["p95Ms"] for l in rows], "pairs_per_request": rows[0]["pairsPerRequest"]})
            ax.plot([l["concurrency"] for l in rows], [l["p50Ms"] for l in rows], marker="o" if date == "2026-10-14" else "s", linestyle=style,
                    color="#B22222" if engine == "CH-car-v1" else "#444444", label=f"{'CH' if engine == 'CH-car-v1' else 'Flexible'}, map {doc['engines'][0]['mapVersion']} ({date})")
    ax.set_yscale("log"); ax.set_xticks([1, 5, 10]); ax.set_xlabel("Concurrent callers"); ax.set_ylabel("p50 per 16-pair request (ms, log)")
    ax.set_title("Routing service alone, caches off"); ax.grid(alpha=.2, which="both"); ax.legend(fontsize=9)
    save(fig, "ch")

    # 7. What the routing container's CPU bought: searches per minute against routing CPU.
    fig, ax = plt.subplots(figsize=(9, 3.6), layout="constrained")
    data["cpu"] = []
    for stage, r in attribution:
        routing = [c for c in r["containers"] if "routing" in c["name"]][0]
        booking = [c for c in r["containers"] if "booking" in c["name"]][0]
        d = {"stage": stage, "searches_per_minute": per_minute(r), "routing_cpu_mean": routing["cpuPercent"]["mean"], "booking_cpu_mean": booking["cpuPercent"]["mean"]}
        data["cpu"].append(d)
        ax.scatter(d["routing_cpu_mean"], d["searches_per_minute"], s=90, color=COLOR[stage], label=stage)
        ax.annotate(f"{stage}: booking replica {d['booking_cpu_mean']:.0f}%", (d["routing_cpu_mean"], d["searches_per_minute"]), xytext=(8, -4), textcoords="offset points", fontsize=9)
    ax.set_xscale("log"); ax.set_xlabel("Routing container CPU, mean percent of one core (log)"); ax.set_ylabel("Searches answered per minute")
    ax.set_title("Throughput against routing CPU"); ax.grid(alpha=.2, which="both"); ax.set_xlim(100, 2000)
    save(fig, "cpu")

    # 8. Replica roles: booking tail and daily throughput for the four L5 runs.
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.5), layout="constrained")
    data["roles"] = []
    for i, (run, shape) in enumerate(roles):
        b, d = by_run(runs["roles"], run, "booking"), by_run(runs["roles"], run, "daily")
        entry = {"run": run, "shape": shape, "offers_p95": b["ops"]["booking offers"]["ms"]["p95"], "offers_busy": b["ops"]["booking offers"]["retriedBusy"],
                 "confirm_p50": b["ops"]["confirm"]["ms"]["p50"], "searches_per_minute": per_minute(b), "proposal_p50": d["ops"]["daily proposal"]["ms"]["p50"]}
        data["roles"].append(entry)
        color = COLOR["#158"] if shape == "split" else COLOR["#151"]
        axes[0].bar(i, entry["offers_p95"], .7, color=color); axes[1].bar(i, entry["proposal_p50"] / 1000, .7, color=color)
    axes[0].set_ylabel("Offers p95 at the caller (ms)"); axes[0].set_title("Booking tail")
    axes[1].set_ylabel("Queued proposal p50 (s)"); axes[1].set_title("Time to a daily proposal")
    for ax in axes:
        ax.set_xticks(range(4), ["one all 1", "split 1", "split 2", "one all 2"], fontsize=9); ax.grid(axis="y", alpha=.2)
    save(fig, "roles")

    (folder / "chart_data.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    return data
