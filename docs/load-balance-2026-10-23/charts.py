"""Reproducible charts from the validated load balance head-to-head evidence; no network or benchmark runs."""
import json
from pathlib import Path
from statistics import median

import matplotlib
import matplotlib.patches
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.colors import LinearSegmentedColormap, TwoSlopeNorm
from matplotlib.ticker import FuncFormatter, NullFormatter

from report_data import (ARMS, MEASURES, PHASES, TOLERANCE, allowance_used, case_mean, cases, cost_differences, index, ratios, reps,
                         speedups)

# One color per arm, as in the fairness measures report; workloads keep one color each.
COLOR = {"b": "#1F7A8C", "load-balance": "#B34F75"}
LABEL = {"b": "b (variance)", "load-balance": "load balance"}
WORKLOAD_COLOR = {"ABSENCE": "#7DAFCB", "CLUSTERED": "#0B3A66", "DISPERSED": "#2E8B57", "MIXED_SHIFT": "#B22222", "NEAR_CAPACITY": "#C37E12",
                  "TIGHT_WINDOW": "#8064A2"}
MEASURE_LABEL = {"variance": "Variance", "maximumUtilization": "Highest\nutilization", "utilizationSpread": "Utilization\nspread",
                 "absoluteDeviation": "Absolute\ndeviation", "loadBalance": "Load\nbalance"}
GROUP_COLOR = {"decimal: variance": "#B22222", "variance (non-decimal)": "#E8A0A0", "decimal: score impacts": "#C37E12", "decimal: money": "#E0B050",
               "collections": "#5B9BD5", "engine": "#0B3A66", "scheduler code": "#2E8B57", "score engine": "#8064A2", "time": "#999999", "other": "#CCCCCC"}
PHASE_LABEL = {"referencePhase": "cost phase", "fairnessPhase": "fairness phase"}
FAIR = LinearSegmentedColormap.from_list("fair", ["#1F7A8C", "#F7F7F7", "#B22222"])


def plain_log(ax, axis="y"):
    """Log-scale ticks written as plain numbers (0.5, 1, 2), not powers of ten."""
    target = ax.yaxis if axis == "y" else ax.xaxis
    target.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}"))
    target.set_minor_formatter(NullFormatter())


def log_ticks(ax, axis):
    """Plain ticks at 1-2-5 steps inside the axis's current range."""
    low, high = ax.get_ylim() if axis == "y" else ax.get_xlim()
    ticks = [t for t in (.05, .1, .2, .25, .5, .75, 1, 1.5, 2, 3, 5, 10, 20) if low <= t <= high]
    (ax.set_yticks if axis == "y" else ax.set_xticks)(ticks)
    plain_log(ax, axis)


def short(arm):
    return "b" if arm == "b" else "load bal."


def seconds(key):
    return f"{int(key) // 1000} s"


def workload_name(workload):
    return workload.replace("_", " ").title()


def case_label(case):
    return f"{case[0]} {workload_name(case[1])} {case[2]}"


def make_charts(document, folder):
    folder.mkdir(exist_ok=True)
    font = next((p for p in (Path("C:/Windows/Fonts/calibri.ttf"), Path("/usr/share/fonts/truetype/crosextra/Carlito-Regular.ttf")) if p.exists()), None)
    if font is None:
        raise RuntimeError("Calibri or Carlito is required")
    font_manager.fontManager.addfont(str(font))
    plt.rcParams.update({"font.family": font_manager.FontProperties(fname=str(font)).get_name(),
                         "font.size": 11, "axes.spines.top": False, "axes.spines.right": False,
                         "axes.unicode_minus": False, "savefig.dpi": 200})
    data = {}
    budgets = document["budgets"]
    keys = sorted(budgets, key=int)
    by = {key: index(budgets[key]) for key in keys}
    all_cases = cases(budgets[keys[0]])
    repetitions = reps(budgets[keys[0]])
    fleets = sorted({c[0] for c in all_cases})
    workloads = sorted({c[1] for c in all_cases})

    def save(fig, name):
        # Calibri at exactly 7 pt renders at the image origin in this matplotlib; keep every label off 7 pt.
        for text in fig.findobj(matplotlib.text.Text):
            assert abs(text.get_fontsize() - 7) > .01, (name, text.get_text())
        fig.savefig(folder / f"{name}.png", facecolor="white", metadata={"Software": "WaterFlex report"})
        plt.close(fig)

    # 1. Each case's final variance under load balance relative to b, sorted, with the median and its bootstrap interval.
    data["ratios"] = {key: [{"case": case_label(c), "workload": c[1], "ratio": float(case_mean(by[key], "load-balance", c, "variance", repetitions)
                                                                                     / case_mean(by[key], "b", c, "variance", repetitions))}
                            for c in all_cases if case_mean(by[key], "b", c, "variance", repetitions) > 0] for key in keys}
    fig, axes = plt.subplots(1, len(keys), figsize=(9, 4.0), layout="constrained", sharey=True)
    for ax, key in zip(axes, keys):
        points = sorted(data["ratios"][key], key=lambda p: p["ratio"])
        reading = budgets[key]["measures"]["variance"]
        low, high = reading["medianRatioInterval"] or (reading["medianRatioToB"], reading["medianRatioToB"])
        ax.axhspan(low, high, color="#DDDDDD", zorder=0, label="95% interval of the median")
        ax.axhline(1, color="#555555", linewidth=1); ax.axhline(1 + float(TOLERANCE), color="#B22222", linestyle="--", linewidth=.9)
        ax.axhline(reading["medianRatioToB"], color="black", linewidth=1.4)
        for workload in workloads:
            chosen = [(i, p["ratio"]) for i, p in enumerate(points) if p["workload"] == workload]
            ax.scatter([i for i, _ in chosen], [r for _, r in chosen], s=14, color=WORKLOAD_COLOR[workload], label=workload_name(workload), zorder=3)
        ax.set_yscale("log")
        ax.set_title(f"{seconds(key)} budget: median {reading['medianRatioToB']:.2f}", fontsize=11)
        ax.set_xlabel("Cases, sorted by ratio"); ax.set_xticks([]); ax.grid(axis="y", alpha=.2)
    for ax in axes:
        low, high = ax.get_ylim()
        ax.set_yticks([t for t in (.01, .02, .05, .1, .2, .5, 1, 2, 5, 10, 100, 1000) if low <= t <= high]); plain_log(ax)
    axes[0].set_ylabel("Final variance, load balance over b\n(log scale; below 1 is fairer)")
    axes[0].text(.02, .97, "dashed: 5% tolerance", transform=axes[0].transAxes, fontsize=9, color="#B22222", va="top")
    handles, labels = axes[-1].get_legend_handles_labels()
    fig.legend(handles, labels, fontsize=8.5, ncol=4, loc="outside lower center", frameon=False)
    save(fig, "ratios")

    # 2. Every measure: how many days load balance was fairer or less fair than b, within and beyond the tolerance.
    bands = (("fairer by more than 5%", "#1F7A8C"), ("fairer within 5%", "#9CC7D0"), ("same", "#DDDDDD"), ("less fair within 5%", "#E8A0A0"),
             ("less fair by more than 5%", "#B22222"))
    data["lean"] = {}
    for key in keys:
        data["lean"][key] = {}
        for name in MEASURES:
            counts = dict.fromkeys((b for b, _ in bands), 0)
            for c in all_cases:
                a, b = case_mean(by[key], "load-balance", c, name, repetitions), case_mean(by[key], "b", c, name, repetitions)
                band = ("same" if a == b else "fairer by more than 5%" if a < b * (1 - TOLERANCE) else "fairer within 5%" if a < b
                        else "less fair by more than 5%" if a > b * (1 + TOLERANCE) else "less fair within 5%")
                counts[band] += 1
            data["lean"][key][name] = counts
    fig, ax = plt.subplots(figsize=(9, 4.0), layout="constrained")
    labels = []
    for y, (name, key) in enumerate((m, k) for m in MEASURES for k in keys):
        counts = data["lean"][key][name]
        left = -(counts["fairer by more than 5%"] + counts["fairer within 5%"] + counts["same"] / 2)
        for band, color in bands:
            ax.barh(y, counts[band], left=left, color=color, edgecolor="white", linewidth=.5, label=band if y == 0 else None)
            if counts[band] >= 8:
                ax.text(left + counts[band] / 2, y, str(counts[band]), ha="center", va="center", fontsize=8.5, color="white" if band.endswith("5%") and "more" in band else "black")
            left += counts[band]
        labels.append(f"{MEASURE_LABEL[name].replace(chr(10), ' ')}, {seconds(key)}")
    ax.axvline(0, color="#555555", linewidth=1, zorder=0)
    ax.set_yticks(range(len(labels)), labels, fontsize=9); ax.invert_yaxis()
    ax.set_xlabel("Days: fairer under load balance to the left, less fair to the right, ties centered")
    fig.legend(*ax.get_legend_handles_labels(), fontsize=8.5, ncol=5, loc="outside lower center", frameon=False)
    save(fig, "measures")

    # 3. The variance ratio by workload and fleet, at each budget.
    data["grid"] = {key: {w: {str(f): median(ratios(by[key], all_cases, repetitions, "variance", lambda c, f=f, w=w: c[0] == f and c[1] == w) or [float("nan")])
                              for f in fleets} for w in workloads} for key in keys}
    fig, axes = plt.subplots(1, len(keys), figsize=(9, 3.9), layout="constrained")
    for ax, key in zip(axes, keys):
        grid = [[data["grid"][key][w][str(f)] for f in fleets] for w in workloads]
        image = ax.imshow(grid, cmap=FAIR, norm=TwoSlopeNorm(vmin=.3, vcenter=1, vmax=1.7), aspect="auto")
        for i, row in enumerate(grid):
            for j, v in enumerate(row):
                if v != v:  # no ratio: b made every one of the cell's days perfectly even
                    ax.add_patch(matplotlib.patches.Rectangle((j - .5, i - .5), 1, 1, color="#EEEEEE"))
                    ax.text(j, i, "even", ha="center", va="center", fontsize=8.5, color="#555555")
                else:
                    ax.text(j, i, f"{v:.2f}", ha="center", va="center", fontsize=9, color="white" if v < .55 or v > 1.5 else "black")
        ax.set_xticks(range(len(fleets)), [f"{f} techs" for f in fleets], fontsize=9)
        ax.set_yticks(range(len(workloads)), [workload_name(w) for w in workloads], fontsize=9); ax.set_title(f"{seconds(key)} budget", fontsize=11)
        ax.spines[:].set_visible(False)
    fig.colorbar(image, ax=axes, shrink=.85, label="Median variance ratio, load balance over b")
    save(fig, "grid")

    # 4. Each case's cost difference, by fleet.
    data["cost"] = {key: {str(f): cost_differences(by[key], all_cases, repetitions, lambda c, f=f: c[0] == f) for f in fleets} for key in keys}
    fig, ax = plt.subplots(figsize=(9, 3.7), layout="constrained")
    ticks = []
    for i, (key, f) in enumerate((k, f) for k in keys for f in fleets):
        values = [v / 100 for v in data["cost"][key][str(f)]]
        x = i + (i >= len(fleets)) * .6
        ax.scatter([x + (k % 10 - 4.5) * .03 for k in range(len(values))], values, s=12, color=COLOR["load-balance"], alpha=.45 if key == keys[0] else .85)
        ax.plot([x - .3, x + .3], [median(values)] * 2, color="black", linewidth=1.5)
        ticks.append((x, f"{f} techs\n{seconds(key)}"))
    ax.axhline(0, color="#555555", linewidth=1)
    ax.set_xticks([t[0] for t in ticks], [t[1] for t in ticks], fontsize=9)
    ax.set_ylabel("Final cost, load balance minus b ($)"); ax.grid(axis="y", alpha=.2)
    save(fig, "cost")

    # 5. Moves per second of load balance over b in each phase, by fleet.
    data["speed"] = {key: {phase: {str(f): [round(v, 4) for v in speedups(by[key], all_cases, repetitions, phase, lambda c, f=f: c[0] == f)] for f in fleets}
                           for phase in PHASES} for key in keys}
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.7), layout="constrained", sharey=True)
    for ax, phase in zip(axes, PHASES):
        for i, (key, f) in enumerate((k, f) for k in keys for f in fleets):
            values = data["speed"][key][phase][str(f)]
            x = i + (i >= len(fleets)) * .6
            ax.scatter([x + (k % 10 - 4.5) * .03 for k in range(len(values))], values, s=10, color=COLOR["load-balance"], alpha=.45 if key == keys[0] else .85)
            ax.plot([x - .3, x + .3], [median(values)] * 2, color="black", linewidth=1.5)
            ax.text(x, max(values) + .02, f"{median(values):.2f}", ha="center", fontsize=8.5)
        ax.axhline(1, color="#555555", linewidth=1)
        ax.set_xticks([i + (i >= len(fleets)) * .6 for i in range(len(keys) * len(fleets))],
                      [f"{f}\n{seconds(k)}" for k in keys for f in fleets], fontsize=8.5)
        ax.set_title(PHASE_LABEL[phase].capitalize(), fontsize=11); ax.grid(axis="y", alpha=.2)
    axes[0].set_ylabel("Moves per second,\nload balance over b")
    save(fig, "speed")

    # 6. How far into each phase the best plan was found.
    data["convergence"] = {key: budgets[key]["convergence"] for key in keys}
    fig, ax = plt.subplots(figsize=(9, 3.2), layout="constrained")
    labels = []
    for y, (key, arm, phase) in enumerate((k, a, p) for k in keys for p in PHASES for a in ARMS):
        share = data["convergence"][key][arm][phase]["medianShareOfBudget"]
        ax.barh(y, (share or 0) * 100, color=COLOR[arm], alpha=.55 if phase == "referencePhase" else 1)
        ax.text((share or 0) * 100 + 1, y, "n/a" if share is None else f"{share * 100:.0f}%", va="center", fontsize=8.5)
        labels.append(f"{LABEL[arm]}, {PHASE_LABEL[phase]}, {seconds(key)}")
    ax.set_yticks(range(len(labels)), labels, fontsize=8.5); ax.invert_yaxis(); ax.set_xlim(0, 105)
    ax.set_xlabel("Median share of the phase's budget spent before its best plan (%)")
    save(fig, "convergence")

    # 7. Where each arm's phases spend their CPU.
    profiles = document.get("profiles", {})
    rows = [(arm, phase) for phase in ("cost", "fairness") for arm in ARMS if f"{arm} {phase}" in profiles]
    data["profile"] = [{"arm": arm, "phase": phase, "shares": profiles[f"{arm} {phase}"]["all"]["samplesPercent"]} for arm, phase in rows]
    fig, ax = plt.subplots(figsize=(9, 3.4), layout="constrained")
    for y, row in enumerate(reversed(data["profile"])):
        left = 0
        for group, color in GROUP_COLOR.items():
            share = row["shares"].get(group, 0)
            if share <= 0:
                continue
            ax.barh(y, share, left=left, color=color, edgecolor="white", linewidth=.5, label=group if y == len(rows) - 1 else None)
            if share >= 6:
                ax.text(left + share / 2, y, f"{share:.0f}", ha="center", va="center", fontsize=8.5, color="white" if group in ("engine", "collections", "score engine", "scheduler code") else "black")
            left += share
    ax.set_yticks(range(len(rows)), [f"{LABEL[r['arm']]}, {r['phase']} phase" for r in reversed(data["profile"])], fontsize=9.5)
    ax.set_xlim(0, 100); ax.set_xlabel("Share of CPU samples in the phase (%)")
    handles, labels = ax.get_legend_handles_labels()
    ax.legend(handles, labels, fontsize=8.5, ncol=4, loc="upper center", bbox_to_anchor=(.5, -.2), frameon=False)
    save(fig, "profile")

    # 8. Load balance against the variance case by case, on the two measures the arms search on.
    data["cross"] = {key: [{"case": case_label(c), "workload": c[1],
                            "variance": float(case_mean(by[key], "load-balance", c, "variance", repetitions) / case_mean(by[key], "b", c, "variance", repetitions)),
                            "loadBalance": float(case_mean(by[key], "load-balance", c, "loadBalance", repetitions) / case_mean(by[key], "b", c, "loadBalance", repetitions))}
                           for c in all_cases if case_mean(by[key], "b", c, "variance", repetitions) > 0 and case_mean(by[key], "b", c, "loadBalance", repetitions) > 0]
                     for key in keys}
    key = keys[-1]
    fig, ax = plt.subplots(figsize=(9, 4.2), layout="constrained")
    for workload in workloads:
        chosen = [p for p in data["cross"][key] if p["workload"] == workload]
        ax.scatter([p["loadBalance"] for p in chosen], [p["variance"] for p in chosen], s=20, color=WORKLOAD_COLOR[workload], label=workload_name(workload), alpha=.85)
    ax.axhline(1, color="#555555", linewidth=1); ax.axvline(1, color="#555555", linewidth=1)
    ax.set_xscale("log"); ax.set_yscale("log"); log_ticks(ax, "x"); log_ticks(ax, "y")
    ax.set_xlabel(f"Load balance unfairness, load balance arm over b ({seconds(key)})"); ax.set_ylabel("Variance, load balance arm over b")
    fig.legend(*ax.get_legend_handles_labels(), fontsize=8.5, loc="outside right upper", frameon=False); ax.grid(alpha=.2)
    save(fig, "cross")

    # 9. How much of the 2 percent allowance each run spent.
    data["allowance"] = {key: {arm: [u for u in (allowance_used(r) for r in budgets[key]["runs"] if r["arm"] == arm) if u is not None] for arm in ARMS} for key in keys}
    data["acceptance"] = {key: budgets[key]["acceptance"] for key in keys}
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.4), layout="constrained")
    for i, (key, arm) in enumerate((k, a) for k in keys for a in ARMS):
        values = [v * 100 for v in data["allowance"][key][arm]]
        axes[0].scatter([i + (k % 10 - 4.5) * .03 for k in range(len(values))], values, s=8, color=COLOR[arm], alpha=.6, linewidths=0)
        axes[0].plot([i - .3, i + .3], [median(values)] * 2, color="black", linewidth=1.4)
    axes[0].set_xticks(range(len(keys) * len(ARMS)), [f"{short(a)}\n{seconds(k)}" for k in keys for a in ARMS], fontsize=9)
    axes[0].set_ylabel("Percent of the allowance spent"); axes[0].grid(axis="y", alpha=.2)
    shades = {"FAIRNESS_IMPROVEMENT": 1, "COST_REDUCTION": .55, "OVERTIME_REDUCTION": .35}
    # Darkest first; a reason outside the three accepted ones (a rejection) comes last and palest.
    reasons = sorted({reason for key in keys for arm in ARMS for reason in data["acceptance"][key][arm]}, key=lambda r: (-shades.get(r, .15), r))
    for y, (key, arm) in enumerate((k, a) for k in keys for a in ARMS):
        left = 0
        for reason in reasons:
            count = data["acceptance"][key][arm].get(reason, 0)
            axes[1].barh(y, count, left=left, color=COLOR[arm], alpha=shades.get(reason, .15), edgecolor="white")
            if count >= 6:
                axes[1].text(left + count / 2, y, str(count), ha="center", va="center", fontsize=8.5, color="white" if shades.get(reason, .15) > .5 else "black")
            left += count
    axes[1].set_yticks(range(len(keys) * len(ARMS)), [f"{short(a)}, {seconds(k)}" for k in keys for a in ARMS], fontsize=9)
    axes[1].invert_yaxis(); axes[1].set_xlabel("Runs: " + ", ".join(r.replace("_", " ").lower() for r in reasons) + " (dark to pale)", fontsize=9)
    save(fig, "allowance")

    (folder / "chart_data.json").write_text(json.dumps(data, indent=1), encoding="utf-8")
    return data
