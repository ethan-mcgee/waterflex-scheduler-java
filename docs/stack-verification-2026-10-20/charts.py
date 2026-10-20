"""Reproducible charts from the validated stack verification evidence; no network, services or load runs."""
import json
from decimal import Decimal
from pathlib import Path
from statistics import median

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager

from report_data import FLEETS, IMAGES, PHASES, SEEDS, STEP2, fairer, harness, operation, p50, ratio, receipts, speeds

# One color per image, kept across every figure: production's grey and option b's teal from the fairness reports.
COLOR = {"before": "#555555", "after": "#1F7A8C"}
LABEL = {"before": "before (decimal variance)", "after": "after (option b)"}
PHASE_LABEL = {"REFERENCE": "Reference phase", "FAIRNESS": "Fairness phase"}


def pair_label(fleet, seed):
    return f"{fleet} techs, seed {seed}"


def make_charts(runs, folder):
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

    def report(image, fleet, seed, step=1):
        return runs[(step, image, fleet, seed)]["report"]

    def pooled(image, fleet, phase):
        return [v for seed in SEEDS for v in speeds(report(image, fleet, seed), phase)]

    # 1. Every proposal's moves per second in each phase, by fleet and image.
    data["speed"] = {phase: {image: {str(fleet): pooled(image, fleet, phase) for fleet in FLEETS} for image in IMAGES} for phase in PHASES}
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.9), layout="constrained", sharey=True)
    for ax, phase in zip(axes, PHASES):
        for i, fleet in enumerate(FLEETS):
            for offset, image in ((-.18, "before"), (.18, "after")):
                values = [v / 1000 for v in data["speed"][phase][image][str(fleet)]]
                ax.scatter([i + offset + (j % 6 - 2.5) * .02 for j in range(len(values))], values, s=14, color=COLOR[image], alpha=.8, linewidths=0,
                           label=LABEL[image] if i == 0 else None)
                ax.plot([i + offset - .13, i + offset + .13], [median(values)] * 2, color="black", linewidth=1.5)
        ax.set_xticks(range(len(FLEETS)), [f"{f} technicians" for f in FLEETS]); ax.set_title(PHASE_LABEL[phase]); ax.grid(axis="y", alpha=.2)
        ax.set_ylim(0, None)
    axes[0].set_ylabel("Moves per second (thousands)"); axes[1].legend(fontsize=9, loc="upper right")
    save(fig, "speed")

    # 2. After over before, per seed and fleet, against the harness's own ratio by fleet.
    by_fleet, _ = harness()
    data["ratio"] = {phase: {str(fleet): [round(ratio(runs, 1, fleet, seed, phase), 4) for seed in SEEDS] for fleet in FLEETS} for phase in PHASES}
    data["ratioQueued"] = {phase: round(ratio(runs, 2, *STEP2, phase), 4) for phase in PHASES}
    data["harness"] = {str(k): v for k, v in by_fleet.items()}
    fig, ax = plt.subplots(figsize=(9, 3.9), layout="constrained")
    for phase, color, marker in (("FAIRNESS", COLOR["after"], "o"), ("REFERENCE", "#999999", "s")):
        xs, ys = [], []
        for fleet in FLEETS:
            values = data["ratio"][phase][str(fleet)]
            ax.scatter([fleet] * len(values), values, color=color, marker=marker, s=26, zorder=3,
                       label=f"stack, {PHASE_LABEL[phase].lower()}, each seed" if fleet == FLEETS[0] else None)
            xs.append(fleet); ys.append(median(values))
        ax.plot(xs, ys, color=color, linewidth=1.2)
        ax.scatter([STEP2[0]], [data["ratioQueued"][phase]], color=color, marker="D" if phase == "FAIRNESS" else "x", s=34, zorder=3,
                   label=f"stack, queued, 2 planners" if phase == "FAIRNESS" else None)
    sizes = sorted(by_fleet)
    ax.plot(sizes, [by_fleet[s] for s in sizes], color="#C37E12", linestyle="--", marker="^", linewidth=1.2, label="harness, fairness phase (PR 187)")
    for s in sizes:
        ax.text(s + .8, by_fleet[s], f"{by_fleet[s]:.2f}x", fontsize=8.5, color="#C37E12", va="center")
    for fleet in FLEETS:
        ax.text(fleet + .8, median(data["ratio"]["FAIRNESS"][str(fleet)]) - .35, f"{median(data['ratio']['FAIRNESS'][str(fleet)]):.2f}x", fontsize=8.5, color=COLOR["after"], va="center")
    ax.axhline(1, color="#555555", linewidth=.8)
    ax.set_xticks([10, 20, 30, 50]); ax.set_xlim(5, 56); ax.set_ylim(0, 8)
    ax.set_xlabel("Technicians"); ax.set_ylabel("Moves per second, after over before"); ax.grid(alpha=.2); ax.legend(fontsize=8.5, loc="upper left")
    save(fig, "ratio")

    # 3. Within each proposal: the fairness phase's rate as a share of its own reference phase's.
    data["gap"] = {image: {str(fleet): [round(r["solves"][1]["movesPerSecond"] / r["solves"][0]["movesPerSecond"], 4)
                                        for seed in SEEDS for r in receipts(report(image, fleet, seed))] for fleet in FLEETS} for image in IMAGES}
    fig, ax = plt.subplots(figsize=(9, 3.4), layout="constrained")
    for i, fleet in enumerate(FLEETS):
        for offset, image in ((-.18, "before"), (.18, "after")):
            values = data["gap"][image][str(fleet)]
            ax.scatter([i + offset + (j % 6 - 2.5) * .02 for j in range(len(values))], values, s=14, color=COLOR[image], alpha=.8, linewidths=0,
                       label=LABEL[image] if i == 0 else None)
            ax.plot([i + offset - .13, i + offset + .13], [median(values)] * 2, color="black", linewidth=1.5)
            ax.text(i + offset, median(values) + .05, f"{median(values):.2f}", ha="center", fontsize=8.5)
    ax.axhline(1, color="#999999", linestyle="--", linewidth=.8)
    ax.set_xticks(range(len(FLEETS)), [f"{f} technicians" for f in FLEETS]); ax.set_ylim(0, 1.15)
    ax.set_ylabel("Fairness-phase rate over\nreference-phase rate"); ax.grid(axis="y", alpha=.2); ax.legend(fontsize=9, loc="lower left")
    save(fig, "gap")

    # 4 and 5. Each proposal's plan against its own reference plan: variance ratio and cost change.
    pairs = [(fleet, seed) for fleet in FLEETS for seed in SEEDS]
    data["variance"] = {image: {pair_label(*pair): [float(Decimal(r["body"]["proposed"]["variance"]) / Decimal(r["body"]["reference"]["variance"]))
                                                   for r in receipts(report(image, *pair))] for pair in pairs} for image in IMAGES}
    data["cost"] = {image: {pair_label(*pair): [r["body"]["proposed"]["costCents"] - r["body"]["reference"]["costCents"] for r in receipts(report(image, *pair))]
                            for pair in pairs} for image in IMAGES}
    for name, ylabel, line in (("variance", "Proposed variance over\nits reference plan's", 1), ("cost", "Proposed cost minus its\nreference plan's (cents)", 0)):
        fig, ax = plt.subplots(figsize=(9, 3.6), layout="constrained")
        for i, pair in enumerate(pairs):
            for offset, image in ((-.18, "before"), (.18, "after")):
                values = data[name][image][pair_label(*pair)]
                ax.scatter([i + offset + (j - 1.5) * .04 for j in range(len(values))], values, s=18, color=COLOR[image], alpha=.85, linewidths=0,
                           label=LABEL[image] if i == 0 else None)
        ax.axhline(line, color="#555555", linewidth=1)
        ax.set_xticks(range(len(pairs)), [f"{f} / {s}" for f, s in pairs], fontsize=9); ax.set_xlabel("Technicians / points seed")
        ax.set_ylabel(ylabel); ax.grid(axis="y", alpha=.2); ax.legend(fontsize=9, loc="lower left")
        save(fig, name)

    # 6. The calls: each run's median and slowest proposal, and whether its roads were fetched (cold) or cached.
    data["calls"] = {image: [{"pair": pair_label(*pair), "p50": operation(report(image, *pair))["ms"]["p50"], "max": operation(report(image, *pair))["ms"]["max"],
                              "fetched": report(image, *pair)["server"]["routingPairs"]} for pair in pairs] for image in IMAGES}
    fig, ax = plt.subplots(figsize=(9, 3.6), layout="constrained")
    for i, pair in enumerate(pairs):
        for offset, image in ((-.18, "before"), (.18, "after")):
            row = data["calls"][image][i]
            ax.bar(i + offset, row["p50"] / 1000, .32, color=COLOR[image], alpha=.85, label=LABEL[image] if i == 0 else None)
            cold = row["fetched"] > 0
            ax.scatter([i + offset], [row["max"] / 1000], marker="v", s=34, color="#B22222" if cold else "white", edgecolors="#B22222", zorder=3,
                       label=("slowest call, roads fetched" if cold else "slowest call, roads cached") if i == 0 else None)
    ax.set_xticks(range(len(pairs)), [f"{f} / {s}" for f, s in pairs], fontsize=9); ax.set_xlabel("Technicians / points seed")
    ax.set_ylabel("Proposal call (s)"); ax.set_ylim(0, 34); ax.grid(axis="y", alpha=.2); ax.legend(fontsize=8.5, loc="upper left", ncol=2)
    save(fig, "calls")

    # 7. The queued step: each proposal's speed per phase, and its wait in the queue.
    data["queued"] = {image: {"speed": {phase: speeds(runs[(2, image, *STEP2)]["report"], phase) for phase in PHASES},
                              "waits": runs[(2, image, *STEP2)]["report"]["daily"]["queueWaitsMs"]} for image in IMAGES}
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.5), layout="constrained", gridspec_kw={"width_ratios": [1.6, 1]})
    for i, phase in enumerate(PHASES):
        for offset, image in ((-.18, "before"), (.18, "after")):
            values = [v / 1000 for v in data["queued"][image]["speed"][phase]]
            axes[0].scatter([i + offset + (j % 6 - 2.5) * .02 for j in range(len(values))], values, s=14, color=COLOR[image], alpha=.8, linewidths=0,
                            label=LABEL[image] if i == 0 else None)
            axes[0].plot([i + offset - .13, i + offset + .13], [median(values)] * 2, color="black", linewidth=1.5)
    axes[0].set_xticks(range(2), [PHASE_LABEL[p] for p in PHASES]); axes[0].set_ylabel("Moves per second (thousands)"); axes[0].set_ylim(0, None)
    axes[0].grid(axis="y", alpha=.2); axes[0].legend(fontsize=8.5, loc="lower left"); axes[0].set_title("20 technicians, 2 planners queuing", fontsize=10)
    for offset, image in ((-.18, "before"), (.18, "after")):
        waits = data["queued"][image]["waits"]
        axes[1].scatter([offset + (j % 6 - 2.5) * .02 for j in range(len(waits))], waits, s=16, color=COLOR[image])
    axes[1].set_xticks([-.18, .18], ["before", "after"]); axes[1].set_xlim(-.6, .6); axes[1].set_ylim(0, 10)
    axes[1].set_ylabel("Wait in the queue (ms)"); axes[1].grid(axis="y", alpha=.2); axes[1].set_title("Queue wait per proposal", fontsize=10)
    save(fig, "queued")

    data["fairer"] = {image: {pair_label(*pair): sum(fairer(r) for r in receipts(report(image, *pair))) for pair in pairs} for image in IMAGES}
    (folder / "chart_data.json").write_text(json.dumps(data, indent=1), encoding="utf-8")
    return data
