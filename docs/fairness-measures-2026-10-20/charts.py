"""Reproducible charts from the validated fairness measure study evidence; no network or benchmark runs."""
import json
from decimal import Decimal
from pathlib import Path
from statistics import median

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.colors import TwoSlopeNorm, LinearSegmentedColormap

from report_data import ARMS, BUDGETS, MEASURES, POLICIES, REPS, allowance_used, case_mean, cases, index, own_measure, own_reduction, speedups

# One color per arm, kept across every figure.
COLOR = {"baseline": "#555555", "b": "#1F7A8C", "c": "#7DAFCB", "min-max": "#C37E12", "spread": "#E0B050", "abs-dev": "#6B4C9A", "load-balance": "#B34F75"}
LABEL = {"baseline": "baseline", "b": "b (binary64)", "c": "c (running sums)", "min-max": "min-max", "spread": "spread", "abs-dev": "abs-dev", "load-balance": "load-balance"}
MEASURE_LABEL = {"variance": "Variance", "maximumUtilization": "Highest\nutilization", "utilizationSpread": "Utilization\nspread",
                 "absoluteDeviation": "Absolute\ndeviation", "loadBalance": "Load\nbalance"}
GROUP_COLOR = {"decimal: variance": "#B22222", "variance (non-decimal)": "#E8A0A0", "decimal: score impacts": "#C37E12", "decimal: money": "#E0B050",
               "collections": "#5B9BD5", "engine": "#0B3A66", "scheduler code": "#2E8B57", "score engine": "#8064A2", "time": "#999999", "other": "#CCCCCC"}
BUDGET_LABEL = {"15000": "15 s budget", "60000": "60 s budget"}


def case_label(case):
    return f"{case[0]} {case[1].replace('TIGHT_WINDOW', 'TIGHT').title()} {case[2]}"


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
    by = {key: index(budgets[key]) for key in BUDGETS}
    all_cases = cases(budgets["15000"])

    def save(fig, name):
        fig.savefig(folder / f"{name}.png", facecolor="white", metadata={"Software": "WaterFlex report"})
        plt.close(fig)

    # 1. Fairness-phase speedup of every arm, every pair, both budgets.
    data["speed"] = {key: {arm: [round(v, 4) for v in speedups(by[key], arm)] for arm in ARMS[1:]} for key in BUDGETS}
    data["speedFleet"] = [case[0] for case in all_cases for _ in REPS]  # the fleet size of each pair, in speedups' order
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.9), layout="constrained", sharey=True)
    for ax, key in zip(axes, BUDGETS):
        for i, arm in enumerate(ARMS[1:]):
            values = data["speed"][key][arm]
            for size, marker, alpha in ((20, "o", .45), (50, "^", .9)):
                chosen = [v for v, t in zip(values, data["speedFleet"]) if t == size]
                ax.scatter([i + (j % 12 - 5.5) * .025 for j in range(len(chosen))], chosen, s=10, marker=marker, color=COLOR[arm], alpha=alpha, linewidths=0,
                           label=f"{size} technicians" if i == 0 and key == "15000" else None)
            ax.plot([i - .3, i + .3], [median(values)] * 2, color="black", linewidth=1.6)
            ax.text(i, max(values) + .25, f"{median(values):.2f}x", ha="center", fontsize=9)
        ax.axhline(1.25, color="#B22222", linestyle="--", linewidth=1); ax.axhline(1, color="#999999", linewidth=.8)
        ax.text(5.6, 1.33, "1.25x bar", color="#B22222", fontsize=9, ha="right")
        ax.set_xticks(range(6), [LABEL[a] for a in ARMS[1:]], fontsize=9, rotation=20); ax.set_title(BUDGET_LABEL[key]); ax.grid(axis="y", alpha=.2)
    axes[0].set_ylabel("Fairness-phase moves per second\nrelative to the baseline")
    save(fig, "speed")

    # 2. Where the fairness phase's CPU goes, by arm, with the baseline's cost phase for reference.
    rows = [("baseline", "cost")] + [(arm, "fairness") for arm in ARMS]
    data["profile"] = [{"arm": arm, "phase": phase, "shares": document["profiles"][f"{arm} {phase}"]["samplesPercent"]} for arm, phase in rows]
    fig, ax = plt.subplots(figsize=(9, 4.1), layout="constrained")
    order = [g for g in GROUP_COLOR]
    for y, row in enumerate(reversed(data["profile"])):
        left = 0
        for group in order:
            share = row["shares"].get(group, 0)
            if share <= 0:
                continue
            ax.barh(y, share, left=left, color=GROUP_COLOR[group], edgecolor="white", linewidth=.5, label=group if y == len(rows) - 1 else None)
            if share >= 6:
                ax.text(left + share / 2, y, f"{share:.0f}", ha="center", va="center", fontsize=8.5, color="white" if group in ("decimal: variance", "engine", "collections") else "black")
            left += share
    ax.set_yticks(range(len(rows)), [f"{LABEL[r['arm']]}, {r['phase']} phase" for r in reversed(data["profile"])], fontsize=9.5)
    ax.set_xlim(0, 100); ax.set_xlabel("Share of CPU samples in the phase (%)")
    handles, labels = ax.get_legend_handles_labels()
    ax.legend(handles, labels, fontsize=8.5, ncol=5, loc="upper center", bbox_to_anchor=(.5, -.16), frameon=False)
    save(fig, "profile")

    # 3. The leaf frames where option b's fairness phase now spends its time.
    leaves = list(document["profiles"]["b fairness"]["leafFramesPercent"].items())[:12]
    data["leaves"] = [{"frame": frame, "percent": pct} for frame, pct in leaves]
    kind = lambda f: ("#5B9BD5" if f.startswith("java.util") else "#C37E12" if f.startswith("java.math") else "#2E8B57" if f.startswith("dev.waterflex") else "#8064A2")
    short = lambda f: ".".join(f.split(".")[-2:]).replace("ImmutableCollections$", "").replace("BendableBigDecimalScoreContext$", "")
    fig, ax = plt.subplots(figsize=(9, 3.9), layout="constrained")
    ax.barh(range(len(leaves)), [p for _, p in leaves][::-1], color=[kind(f) for f, _ in leaves][::-1])
    ax.set_yticks(range(len(leaves)), [short(f) for f, _ in leaves][::-1], fontsize=9)
    for y, (_, p) in enumerate(leaves[::-1]):
        ax.text(p + .2, y, f"{p:.1f}", va="center", fontsize=8.5)
    ax.set_xlabel("Share of option b's fairness-phase samples at this leaf frame (%)")
    for color, text in (("#5B9BD5", "java.util collections"), ("#C37E12", "java.math BigDecimal"), ("#2E8B57", "WaterFlex scheduler code"), ("#8064A2", "Timefold")):
        ax.bar(0, 0, color=color, label=text)
    ax.legend(fontsize=8.5, loc="lower right", frameon=False)
    save(fig, "leaves")

    # 4. Per-case variance of b and c relative to the baseline, with the baseline's own spread between repetitions.
    data["pairs"] = {}
    fig, axes = plt.subplots(1, 2, figsize=(9, 4.2), layout="constrained", sharey=True)
    for ax, key in zip(axes, BUDGETS):
        rows = []
        for case in all_cases:
            base = [Decimal(by[key][("baseline", case, rep)]["measures"]["variance"]) for rep in REPS]
            mean = sum(base) / 2
            rows.append({"case": case_label(case), "noise": float((max(base) - min(base)) / mean),
                         **{arm: float(case_mean(by[key], arm, case, "variance") / mean) for arm in ("b", "c")}})
        data["pairs"][key] = rows
        x = range(len(rows))
        ax.bar(x, [1 + r["noise"] for r in rows], bottom=0, color="#EEEEEE", width=.8, label="baseline + its own spread")
        ax.axhline(1, color="#555555", linewidth=1)
        ax.scatter([i - .15 for i in x], [r["b"] for r in rows], color=COLOR["b"], s=18, label=LABEL["b"], zorder=3)
        ax.scatter([i + .15 for i in x], [r["c"] for r in rows], color=COLOR["c"], s=18, marker="D", label=LABEL["c"], zorder=3)
        ax.set_xticks(list(x), [r["case"] for r in rows], rotation=60, ha="right", rotation_mode="anchor", fontsize=7.5); ax.set_title(BUDGET_LABEL[key]); ax.grid(axis="y", alpha=.2)
    axes[0].set_ylabel("Final variance relative to the baseline\n(mean of 2 repetitions)"); axes[1].legend(fontsize=8.5, loc="upper left")
    save(fig, "pairs")

    # 5. Per-case cost difference of b and c against the baseline.
    data["cost"] = {key: {arm: [float(case_mean(by[key], arm, case, "costCents") - case_mean(by[key], "baseline", case, "costCents")) for case in all_cases]
                          for arm in ("b", "c")} for key in BUDGETS}
    fig, ax = plt.subplots(figsize=(9, 3.7), layout="constrained")
    positions = []
    for i, (key, arm) in enumerate((k, a) for k in BUDGETS for a in ("b", "c")):
        values = [v / 100 for v in data["cost"][key][arm]]
        ax.scatter([i + (j % 8 - 3.5) * .04 for j in range(len(values))], values, color=COLOR[arm], s=16, alpha=.75)
        ax.plot([i - .3, i + .3], [median(values)] * 2, color="black", linewidth=1.6)
        positions.append(f"{LABEL[arm]}\n{BUDGET_LABEL[key]}\nmedian {median(values) * 100:+.1f} cents")
    ax.axhline(0, color="#555555", linewidth=1)
    ax.set_xticks(range(4), positions, fontsize=9); ax.set_ylabel("Final cost minus the baseline's ($)"); ax.grid(axis="y", alpha=.2)
    ax.set_xlim(-.6, 3.6)
    save(fig, "cost")

    # 6. What the extra fairness-phase moves buy: median variance per arm and budget, and b at 15 s against the baseline at 60 s.
    variance_arms = ("baseline", "b", "c", "abs-dev", "load-balance")
    data["budget"] = {"median": {arm: {key: float(Decimal(budgets[key]["comparison"][arm]["measures"]["variance"]["median"])) for key in BUDGETS} for arm in variance_arms},
                      "b15_vs_baseline60": [{"case": case_label(c), "baseline60": float(case_mean(by["60000"], "baseline", c, "variance")),
                                             "b15": float(case_mean(by["15000"], "b", c, "variance"))} for c in all_cases]}
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.8), layout="constrained")
    for i, arm in enumerate(variance_arms):
        for j, key in enumerate(BUDGETS):
            value = data["budget"]["median"][arm][key]
            axes[0].bar(i + (j - .5) * .38, value * 1000, .36, color=COLOR[arm], alpha=1 if key == "60000" else .55, edgecolor="black" if key == "60000" else "none", linewidth=.6)
    axes[0].set_xticks(range(len(variance_arms)), [LABEL[a] for a in variance_arms], fontsize=8.5, rotation=15)
    axes[0].set_ylabel("Median final variance (x 1e-3)"); axes[0].set_title("Pale: 15 s; outlined: 60 s", fontsize=10); axes[0].grid(axis="y", alpha=.2)
    points = data["budget"]["b15_vs_baseline60"]
    axes[1].scatter([p["baseline60"] * 1000 for p in points], [p["b15"] * 1000 for p in points], color=COLOR["b"], s=20)
    top = max(max(p["baseline60"], p["b15"]) for p in points) * 1000 * 1.05
    axes[1].plot([0, top], [0, top], color="#999999", linestyle="--", linewidth=1); axes[1].text(top * .55, top * .62, "equal", color="#777777", fontsize=9, rotation=35)
    axes[1].set_xlabel("Baseline at 60 s (x 1e-3)"); axes[1].set_ylabel("Option b at 15 s (x 1e-3)"); axes[1].set_title("Each case's final variance", fontsize=10)
    axes[1].set_xlim(0, top); axes[1].set_ylim(0, top)
    save(fig, "budget")

    # 7. Every arm on every measure: the median per-case ratio to the baseline.
    data["heatmap"] = {key: {arm: {m: budgets[key]["comparison"][arm]["measures"][m]["medianRatioToBaseline"] for m in MEASURES} for arm in ARMS[1:]} for key in BUDGETS}
    cmap = LinearSegmentedColormap.from_list("fair", ["#1F7A8C", "#F7F7F7", "#B22222"])
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.9), layout="constrained")
    for ax, key in zip(axes, BUDGETS):
        grid = [[data["heatmap"][key][arm][m] for m in MEASURES] for arm in ARMS[1:]]
        image = ax.imshow(grid, cmap=cmap, norm=TwoSlopeNorm(vmin=.5, vcenter=1, vmax=2.5), aspect="auto")
        for i, row in enumerate(grid):
            for j, value in enumerate(row):
                own = MEASURES[j] == own_measure(ARMS[1:][i])
                ax.text(j, i, f"{value:.2f}", ha="center", va="center", fontsize=9, fontweight="bold" if own else "normal", color="white" if value > 1.9 or value < .62 else "black")
        ax.set_xticks(range(len(MEASURES)), [MEASURE_LABEL[m] for m in MEASURES], fontsize=8)
        ax.set_yticks(range(6), [LABEL[a] for a in ARMS[1:]], fontsize=9); ax.set_title(BUDGET_LABEL[key])
        ax.spines[:].set_visible(False)
    fig.colorbar(image, ax=axes, shrink=.8, label="Median ratio to the baseline (lower is fairer)")
    save(fig, "heatmap")

    # 8. Cost against variance for every policy arm and case at 60 s.
    data["tradeoff"] = {arm: [{"case": case_label(c), "costDifferenceCents": float(case_mean(by["60000"], arm, c, "costCents") - case_mean(by["60000"], "baseline", c, "costCents")),
                               "varianceRatio": float(case_mean(by["60000"], arm, c, "variance") / case_mean(by["60000"], "baseline", c, "variance"))}
                              for c in all_cases] for arm in ("b",) + POLICIES}
    fig, ax = plt.subplots(figsize=(9, 4.0), layout="constrained")
    floor = .05  # a ratio this low or lower, including a final variance of zero, is drawn on the floor with a down marker
    for arm, points in data["tradeoff"].items():
        shown = [p for p in points if p["varianceRatio"] > floor]
        ax.scatter([p["costDifferenceCents"] / 100 for p in shown], [p["varianceRatio"] for p in shown], color=COLOR[arm], s=22, alpha=.8, label=LABEL[arm],
                   marker="o" if arm in ("b", "abs-dev", "load-balance") else "s")
        low = [p for p in points if p["varianceRatio"] <= floor]
        ax.scatter([p["costDifferenceCents"] / 100 for p in low], [floor] * len(low), color=COLOR[arm], s=30, marker="v")
    ax.axhline(1, color="#555555", linewidth=1); ax.axvline(0, color="#555555", linewidth=1)
    ax.set_yscale("log"); ax.set_ylim(floor * .85, 80)
    ax.set_yticks([.05, .1, .25, .5, 1, 2, 4, 8, 16, 32, 64], ["0.05 or less", "0.1", "0.25", "0.5", "1", "2", "4", "8", "16", "32", "64"]); ax.minorticks_off()
    ax.set_xlabel("Final cost minus the baseline's ($), 60 s"); ax.set_ylabel("Final variance relative to the baseline")
    ax.text(.01, .97, "less fair and cheaper", transform=ax.transAxes, fontsize=9, color="#777777", va="top")
    ax.legend(fontsize=9, loc="center left"); ax.grid(alpha=.2)
    save(fig, "tradeoff")

    # 9. How much of the 2 percent allowance each arm spent, and how far it moved its own measure.
    data["allowance"] = {key: {arm: {"used": [u for u in (allowance_used(r) for r in budgets[key]["runs"] if r["arm"] == arm) if u is not None],
                                     "own": [o for o in (own_reduction(r) for r in budgets[key]["runs"] if r["arm"] == arm) if o is not None]}
                               for arm in ARMS} for key in BUDGETS}
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.9), layout="constrained")
    for panel, field, title in ((0, "used", "Allowance spent above the reference plan"), (1, "own", "Own measure, final over reference")):
        for i, arm in enumerate(ARMS):
            for j, key in enumerate(BUDGETS):
                values = data["allowance"][key][arm][field]
                x = i + (j - .5) * .36
                axes[panel].scatter([x + (k % 6 - 2.5) * .02 for k in range(len(values))], [v * 100 for v in values], s=6, color=COLOR[arm], alpha=.45 if key == "15000" else .8, linewidths=0)
                axes[panel].plot([x - .14, x + .14], [median(values) * 100] * 2, color="black", linewidth=1.3)
        axes[panel].set_xticks(range(len(ARMS)), [LABEL[a].split(" ")[0] for a in ARMS], fontsize=8.5, rotation=30)
        axes[panel].set_title(title, fontsize=10); axes[panel].grid(axis="y", alpha=.2)
    axes[0].set_ylabel("Percent of the allowance"); axes[1].set_ylabel("Percent of the reference plan's value")
    axes[0].text(.02, .03, "left of each pair: 15 s; right: 60 s", transform=axes[0].transAxes, fontsize=8.5, color="#666666")
    save(fig, "allowance")

    # 10. Acceptance reasons per arm and budget.
    reasons = ("FAIRNESS_IMPROVEMENT", "COST_REDUCTION")
    data["acceptance"] = {key: {arm: budgets[key]["comparison"][arm]["acceptance"] for arm in ARMS} for key in BUDGETS}
    fig, ax = plt.subplots(figsize=(9, 3.3), layout="constrained")
    labels = []
    for y, (key, arm) in enumerate((k, a) for a in ARMS for k in BUDGETS):
        counts = data["acceptance"][key][arm]
        check_total = sum(counts.values())
        assert check_total == 48 and set(counts) <= set(reasons), (key, arm, counts)
        ax.barh(y, counts.get(reasons[0], 0), color=COLOR[arm])
        ax.barh(y, counts.get(reasons[1], 0), left=counts.get(reasons[0], 0), color="#DDDDDD", edgecolor=COLOR[arm], hatch="///", linewidth=.6)
        labels.append(f"{LABEL[arm]}, {BUDGET_LABEL[key][:4]}")
    ax.set_yticks(range(len(labels)), labels, fontsize=8); ax.invert_yaxis(); ax.set_xlim(0, 48)
    ax.set_xlabel("Runs out of 48: fairness improvement (solid) or cost reduction (hatched)")
    save(fig, "acceptance")

    # 11. The two cases where option c was less fair than the baseline.
    focus = (("15000", (20, "ABSENCE", 17)), ("60000", (20, "DISPERSED", 41)))
    data["c_cases"] = [{"budget": key, "case": case_label(case), **{arm: [float(Decimal(by[key][(arm, case, rep)]["measures"]["variance"])) for rep in REPS] for arm in ("baseline", "b", "c")}}
                       for key, case in focus]
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.4), layout="constrained", sharey=True)
    for ax, row in zip(axes, data["c_cases"]):
        for i, arm in enumerate(("baseline", "b", "c")):
            for rep in (0, 1):
                ax.bar(i + (rep - .5) * .36, row[arm][rep] * 1000, .34, color=COLOR[arm], alpha=.6 if rep == 0 else 1)
        ax.set_xticks(range(3), [LABEL[a] for a in ("baseline", "b", "c")], fontsize=9)
        ax.set_title(f"{row['case']}, {BUDGET_LABEL[row['budget']]}", fontsize=10); ax.grid(axis="y", alpha=.2)
    axes[0].set_ylabel("Final variance (x 1e-3)\nrepetition 1 pale, 2 solid")
    save(fig, "c_cases")

    (folder / "chart_data.json").write_text(json.dumps(data, indent=1), encoding="utf-8")
    return data
