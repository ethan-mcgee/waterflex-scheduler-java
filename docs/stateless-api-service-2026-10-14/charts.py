"""Reproducible figures for the stateless API report: diagrams drawn from the loaded documents; no network."""
import json
from collections import Counter
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch

HOST, SCHED, PRIVATE, STORE = "#DCE6EF", "#7DAFCB", "#F4F6F8", "#C9C9C9"
INK = "#1F2A33"


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

    def save(fig, name):
        fig.savefig(folder / f"{name}.png", facecolor="white", metadata={"Software": "WaterFlex report"})
        plt.close(fig)

    def canvas(w=9, h=3.9):
        fig, ax = plt.subplots(figsize=(w, h), layout="constrained")
        ax.set_xlim(0, 10); ax.set_ylim(0, 10); ax.axis("off")
        return fig, ax

    # 1. Topology: the host, the scheduler VM and what talks to what.
    fig, ax = canvas()
    box(ax, .2, 2.2, 2.6, 5.6, "WaterFlex Software\nbackend\n\nmaster data, every\nappointment's record,\nlastModified per\ntechnician-day,\nreceipts written with\na compare-and-set", HOST, 9)
    box(ax, 3.6, .4, 6.2, 9.3, "", PRIVATE)
    ax.text(6.7, 9.3, "Scheduler VM (partitioned CPU and memory, private network)", ha="center", fontsize=9.5, color=INK, fontweight="bold")
    box(ax, 3.9, 6.2, 1.6, 1.9, "scheduler-proxy\n:8000\nnginx", SCHED, 8.5)
    box(ax, 5.9, 7.0, 1.7, 1.5, "booking pool\nreplicas, embedded\nengine", SCHED, 8)
    box(ax, 5.9, 5.1, 1.7, 1.5, "daily pool\nreplicas, embedded\nengine", SCHED, 8)
    box(ax, 7.9, 7.0, 1.7, 1.5, "routing-service\nper metro\n(CH graph)", SCHED, 8)
    box(ax, 7.9, 5.1, 1.7, 1.5, "Nominatim\n(optional)", SCHED, 8)
    box(ax, 5.9, 2.4, 3.7, 1.9, "scheduler-db (Postgres, the scheduler's own)\nthin store: holds, offers, proposals, receipts,\ntenants and tokens, route cache", STORE, 8)
    box(ax, 3.9, .7, 1.6, 1.2, "management\n:9000 private", PRIVATE, 8)
    arrow(ax, (2.8, 5.6), (3.9, 7.0)); ax.text(3.35, 8.35, "HTTPS /api/v1\nsnapshot and call", ha="center", va="bottom", fontsize=8.5, color="#444444")
    arrow(ax, (3.9, 6.6), (2.8, 4.6), color="#2E8B57"); ax.text(3.35, 4.4, "offers, routes,\nreceipts", ha="center", va="top", fontsize=8.5, color="#2E8B57")
    arrow(ax, (5.5, 7.6), (5.9, 7.7)); arrow(ax, (5.5, 6.6), (5.9, 5.9))
    arrow(ax, (7.6, 7.75), (7.9, 7.75)); arrow(ax, (7.6, 5.85), (7.9, 5.85)); arrow(ax, (7.6, 7.4), (7.9, 6.3)); arrow(ax, (7.6, 5.5), (7.9, 7.2))
    arrow(ax, (6.75, 7.0), (6.75, 4.3)); arrow(ax, (7.3, 5.1), (7.3, 4.3))
    ax.text(.2, 1.2, "Nothing else connects: no shared database,\nno client library, no callbacks.", fontsize=8.5, color="#555555", va="center")
    recorded["topology"] = {"boxes": ["host backend", "scheduler-proxy", "booking pool", "daily pool", "routing-service per metro", "Nominatim", "scheduler-db", "management port"]}
    save(fig, "topology")

    # 2. The booking sequence with its outcomes.
    fig, ax = canvas(9, 3.6)
    steps = [("offers", "search: job, horizon,\nsnapshot of every\ntechnician-day", 201), ("select", "customer picks\none window", 200), ("confirm", "fresh snapshot of\nthe hold's date", 200), ("write receipt", "compare-and-set on\nthe listed lastModified", None)]
    for i, (name, text, status) in enumerate(steps):
        x = .3 + i * 2.45
        box(ax, x, 5.2, 2.1, 3.4, f"{name}\n\n{text}", SCHED if status else HOST, 8.5, bold=False)
        if status: ax.text(x + 1.05, 8.9, f"{status}", ha="center", fontsize=9, color="#2E8B57", fontweight="bold")
        if i < 3: arrow(ax, (x + 2.1, 6.9), (x + 2.45, 6.9))
    box(ax, .3, .8, 9.4, 2.4, "", PRIVATE)
    ax.text(5.0, 2.75, "Any 409 or a failed write: release the set (or let the hold expire in 10 minutes) and search again with a new requestId", ha="center", fontsize=8.5, color=INK, fontweight="bold")
    for x, text in ((1.35, "409 STALE: snapshot\nolder than records\nalready used"), (3.8, "409 HOLD_UNAVAILABLE:\nhold expired, lost or\nanother offer selected"), (6.25, "409 HOLD_UNAVAILABLE:\nday no longer fits\nor would add overtime"), (8.7, "write fails:\na timestamp moved\nsince the receipt")):
        ax.text(x, 1.7, text, ha="center", va="center", fontsize=8, color="#B22222")
    for i in range(4):
        arrow(ax, (.3 + i * 2.45 + 1.05, 5.2), (.3 + i * 2.45 + 1.05, 3.2), color="#B22222", style="-|>")
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
    tiers = data["tiers"][1]
    for i, row in enumerate(tiers):
        x = .3 + i * 2.4
        name = row[0].split(". ", 1)[1]
        box(ax, x, 3.0, 2.2, 5.6, f"{row[0].split('.')[0]}. {name}\n\n{row[1].replace('`', '').replace(' in ', chr(10) + 'in ')}\n\nshared by:\n{row[4]}", SCHED if i in (1, 3) else (STORE if i == 2 else PRIVATE), 8)
        if i < 3: arrow(ax, (x + 2.2, 5.8), (x + 2.4, 5.8))
    ax.text(5.0, 1.6, "Every entry is bound to a routing identity (a hash of the road graph and map version), so a changed map can never be mixed with old legs.\nKeys are coordinates rounded to 5 decimals (about 1 m); a changed identity clears the memory tiers and starts the caches cold.", ha="center", va="center", fontsize=8.5, color=INK)
    recorded["tiers"] = [row[0] for row in tiers]
    save(fig, "tiers")

    # 5. How the contract and the service grew: pull requests per day by phase, with the spec versions.
    prs = data["pull_requests"]
    days = sorted({d for _, d, _, _ in prs})
    phases = []
    for _, _, ph, _ in prs:
        if ph not in phases: phases.append(ph)
    palette = ["#444444", "#5B9BD5", "#0B3A66", "#2E8B57", "#8064A2", "#B34F75", "#C37E12", "#7DAFCB", "#B22222", "#DDAA76", "#999999", "#2F4F4F", "#A0522D", "#6B8E23", "#4682B4", "#D2691E"]
    assert len(phases) <= len(palette), phases
    counts = {ph: [sum(1 for _, d, p, _ in prs if d == day and p == ph) for day in days] for ph in phases}
    fig, ax = plt.subplots(figsize=(9, 3.6), layout="constrained")
    bottom = [0] * len(days)
    for ph, color in zip(phases, palette):
        ax.bar(range(len(days)), counts[ph], .7, bottom=bottom, color=color, label=ph)
        bottom = [b + c for b, c in zip(bottom, counts[ph])]
    ax.set_xticks(range(len(days)), [d[5:] for d in days]); ax.set_xlabel("Merge date (2026)"); ax.set_ylabel("Pull requests merged")
    ax.set_title("How the service was built: pull requests per day by phase"); ax.legend(fontsize=7.5, ncol=4, loc="upper right"); ax.grid(axis="y", alpha=.2)
    by_day = {}
    for version, date, _ in data["history"]:
        by_day.setdefault(date, []).append(version)
    for date, versions in by_day.items():
        if date in days:
            i = days.index(date)
            label = f"spec {versions[-1]}" if len(versions) == 1 else f"spec {versions[-1]} to {versions[0]}"
            ax.annotate(label, (i, bottom[i] + .3), ha="center", fontsize=8, color="#B22222")
    recorded["history"] = {"days": days, "phases": phases, "counts": counts, "versions": [(v, d) for v, d, _ in data["history"]]}
    save(fig, "history")

    (folder / "chart_data.json").write_text(json.dumps(recorded, indent=2) + "\n", encoding="utf-8")
    return recorded
