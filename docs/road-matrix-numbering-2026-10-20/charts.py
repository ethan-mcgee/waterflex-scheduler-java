"""Reproducible charts from the validated road matrix numbering study evidence; no network or benchmark runs."""
import json
from decimal import Decimal
from pathlib import Path
from statistics import median

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.colors import TwoSlopeNorm, LinearSegmentedColormap

from report_data import ARMS, FIX, FLEETS, MEASURES, REPS, case_mean, cases, index, ratios

# One color per arm, kept across every figure; baseline, b, c and load-balance keep the fairness report's colors.
COLOR = {"old": "#A9A9A9", "old-b": "#9CC7CF", "baseline": "#555555", "b": "#1F7A8C", "c": "#7DAFCB", "load-balance": "#B34F75"}
LABEL = {"old": "old (before the fix)", "old-b": "old-b (b before the fix)", "baseline": "baseline (fixed production)", "b": "b (binary64)",
         "c": "c (running sums)", "load-balance": "load-balance"}
SHORT = {"old": "old", "old-b": "old-b", "baseline": "baseline", "b": "b", "c": "c", "load-balance": "load-balance"}
MEASURE_LABEL = {"variance": "Variance", "maximumUtilization": "Highest\nutilization", "utilizationSpread": "Utilization\nspread",
                 "absoluteDeviation": "Absolute\ndeviation", "loadBalance": "Load\nbalance"}
GROUP_COLOR = {"decimal: variance": "#B22222", "variance (non-decimal)": "#E8A0A0", "decimal: score impacts": "#C37E12", "decimal: money": "#E0B050",
               "collections": "#5B9BD5", "engine": "#0B3A66", "scheduler code": "#2E8B57", "score engine": "#8064A2", "time": "#999999", "other": "#CCCCCC"}
FLEET_MARKER = {10: "o", 30: "s", 50: "^"}
PHASE_LABEL = {"referenceMovesPerSecond": "Cost phase", "fairnessMovesPerSecond": "Fairness phase"}


def case_label(case):
    return f"{case[0]} {case[1].replace('TIGHT_WINDOW', 'TIGHT').title()} {case[2]}"


def short_frame(frame):
    """The class and method; a dollar sign is escaped so that matplotlib does not read it as mathematics."""
    return ".".join(frame.split(".")[-2:]).replace("ImmutableCollections$", "").replace("BendableBigDecimalScoreContext$", "").replace("$", r"\$")


def frame_color(frame):
    return "#5B9BD5" if frame.startswith("java.util") else "#C37E12" if frame.startswith("java.math") else "#2E8B57" if frame.startswith("dev.waterflex") else "#8064A2"


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
    paired = document["paired"]
    by = index(paired["runs"])
    all_cases = cases(by)
    profiles = document["profiles"]

    def save(fig, name):
        fig.savefig(folder / f"{name}.png", facecolor="white", metadata={"Software": "WaterFlex report"})
        plt.close(fig)

    def jitter(n, width=.05):
        return [((j % 12) - 5.5) * width / 6 for j in range(n)]

    # 1. The fix's paired speedup by fleet, in each phase, for production and for option b.
    data["fixSpeed"] = {arm: {rate: {str(f): [round(v, 4) for v in ratios(by, arm, old, rate, f)] for f in FLEETS} for rate in PHASE_LABEL} for arm, old in FIX.items()}
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.8), layout="constrained", sharey=True)
    for ax, rate in zip(axes, PHASE_LABEL):
        for i, fleet in enumerate(FLEETS):
            for k, arm in enumerate(FIX):
                values = data["fixSpeed"][arm][rate][str(fleet)]
                x = i + (k - .5) * .34
                ax.scatter([x + d for d in jitter(len(values), .24)], values, s=11, color=COLOR[arm], alpha=.75, linewidths=0,
                           label=("production: fixed over old" if arm == "baseline" else "b: fixed over old-b") if i == 0 else None)
                ax.plot([x - .14, x + .14], [median(values)] * 2, color="black", linewidth=1.5)
        ax.axhline(1, color="#999999", linewidth=.8)
        medians = {f: [f"{median(data['fixSpeed'][arm][rate][str(f)]):.2f}x" for arm in FIX] for f in FLEETS}
        ax.set_xticks(range(3), [f"{f} technicians\nmedians {medians[f][0]} / {medians[f][1]}" for f in FLEETS], fontsize=9.5)
        ax.set_title(PHASE_LABEL[rate]); ax.grid(axis="y", alpha=.2)
    axes[0].set_ylabel("Moves per second, fixed tree\nover the same variant before the fix"); axes[1].legend(fontsize=8.5, loc="upper right", frameon=False)
    save(fig, "fix_speed")

    # 2. Median moves per second by arm, phase and fleet.
    data["rates"] = {arm: {rate: {str(f): round(median(by[(arm, c, r)][rate] for c in all_cases if c[0] == f for r in REPS)) for f in FLEETS} for rate in PHASE_LABEL} for arm in ARMS}
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.8), layout="constrained", sharey=True)
    for ax, rate in zip(axes, PHASE_LABEL):
        width = .13
        for k, arm in enumerate(ARMS):
            ax.bar([i + (k - 2.5) * width for i in range(3)], [data["rates"][arm][rate][str(f)] / 1000 for f in FLEETS], width * .92, color=COLOR[arm], label=SHORT[arm])
        ax.set_xticks(range(3), [f"{f} technicians" for f in FLEETS]); ax.set_title(PHASE_LABEL[rate]); ax.grid(axis="y", alpha=.2)
    axes[0].set_ylabel("Median moves per second (thousands)")
    axes[1].legend(fontsize=8.5, ncol=2, loc="upper right", frameon=False)
    save(fig, "rates")

    # 3. Where the CPU goes, before and after the fix, in each phase.
    rows = [("old", "cost"), ("baseline", "cost"), ("old", "fairness"), ("baseline", "fairness"), ("old-b", "fairness"), ("b", "fairness")]
    data["profile"] = [{"arm": arm, "phase": phase, "shares": profiles[f"{arm} {phase}"]["samplesPercent"]} for arm, phase in rows]
    fig, ax = plt.subplots(figsize=(9, 3.9), layout="constrained")
    for y, row in enumerate(reversed(data["profile"])):
        left = 0
        for group, color in GROUP_COLOR.items():
            share = row["shares"].get(group, 0)
            if share <= 0:
                continue
            ax.barh(y, share, left=left, color=color, edgecolor="white", linewidth=.5, label=group if y == len(rows) - 1 else None)
            if share >= 6:
                ax.text(left + share / 2, y, f"{share:.0f}", ha="center", va="center", fontsize=8.5, color="white" if group in ("decimal: variance", "engine", "collections", "scheduler code") else "black")
            left += share
    ax.set_yticks(range(len(rows)), [f"{SHORT[r['arm']]}, {r['phase']} phase" for r in reversed(data["profile"])], fontsize=9.5)
    ax.set_xlim(0, 100); ax.set_xlabel("Share of CPU samples in the phase (%)")
    handles, labels = ax.get_legend_handles_labels()
    ax.legend(handles, labels, fontsize=8.5, ncol=5, loc="upper center", bbox_to_anchor=(.5, -.18), frameon=False)
    save(fig, "profile")

    # 4. The leading leaf frames of option b's fairness phase, before and after the fix.
    data["leaves"] = {arm: [{"frame": f, "percent": p} for f, p in list(profiles[f"{arm} fairness"]["leafFramesPercent"].items())[:10]] for arm in ("old-b", "b")}
    fig, axes = plt.subplots(2, 1, figsize=(9, 5.6), layout="constrained", sharex=True)
    for ax, arm in zip(axes, ("old-b", "b")):
        leaves = data["leaves"][arm][::-1]
        ax.barh(range(len(leaves)), [x["percent"] for x in leaves], color=[frame_color(x["frame"]) for x in leaves])
        ax.set_yticks(range(len(leaves)), [short_frame(x["frame"]) for x in leaves], fontsize=8.5)
        for y, x in enumerate(leaves):
            ax.text(x["percent"] + .15, y, f"{x['percent']:.1f}", va="center", fontsize=8)
        ax.set_title(("Before the fix (old-b)" if arm == "old-b" else "After the fix (b)"), fontsize=10, loc="left")
    axes[1].set_xlabel("Share of option b's fairness-phase samples at this leaf frame (%)")
    for color, text in (("#5B9BD5", "java.util collections"), ("#C37E12", "java.math BigDecimal"), ("#2E8B57", "WaterFlex scheduler code"), ("#8064A2", "Timefold")):
        axes[0].bar(0, 0, color=color, label=text)
    axes[0].legend(fontsize=8.5, loc="lower right", frameon=False)
    save(fig, "leaves")

    # 5. What the fixed production's extra moves did to each case: cost and variance against the old production.
    data["fixOutcome"] = {arm: [{"case": case_label(c), "fleet": c[0],
                                 "referenceCostDifferenceCents": float(case_mean(by, arm, c, "referenceCostCents") - case_mean(by, old, c, "referenceCostCents")),
                                 "costDifferenceCents": float(case_mean(by, arm, c, "costCents") - case_mean(by, old, c, "costCents")),
                                 "varianceRatio": float(case_mean(by, arm, c, "variance") / case_mean(by, old, c, "variance")) if case_mean(by, old, c, "variance") > 0 else None}
                                for c in all_cases] for arm, old in FIX.items()}
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.8), layout="constrained")
    for k, arm in enumerate(FIX):
        for fleet in FLEETS:
            points = [p for p in data["fixOutcome"][arm] if p["fleet"] == fleet]
            axes[0].scatter([fleet / 10 - 2 + (k - .5) * .3 + d for d in jitter(len(points), .12)], [p["costDifferenceCents"] / 100 for p in points],
                            s=18, color=COLOR[arm], marker=FLEET_MARKER[fleet], alpha=.8, label=("production" if arm == "baseline" else "b") if fleet == 10 else None)
            shown = [p for p in points if p["varianceRatio"] is not None]
            axes[1].scatter([p["costDifferenceCents"] / 100 for p in shown], [p["varianceRatio"] for p in shown], s=18, color=COLOR[arm], marker=FLEET_MARKER[fleet], alpha=.8)
    axes[0].axhline(0, color="#555555", linewidth=1); axes[0].set_xticks([-1, 1, 3], [f"{f} technicians" for f in FLEETS])
    axes[0].set_ylabel("Final cost, fixed minus old ($)"); axes[0].set_title("Cost of each case", fontsize=10); axes[0].grid(axis="y", alpha=.2)
    axes[0].legend(fontsize=8.5, frameon=False, loc="lower left")
    axes[1].axhline(1, color="#555555", linewidth=1); axes[1].axvline(0, color="#555555", linewidth=1)
    axes[1].set_xlabel("Final cost, fixed minus old ($)"); axes[1].set_ylabel("Final variance, fixed over old"); axes[1].set_title("Cost against fairness", fontsize=10)
    axes[1].grid(alpha=.2)
    save(fig, "fix_outcome")

    # 6. b, c and load-balance against the fixed production: fairness-phase speedup by fleet.
    data["speed"] = {arm: {str(f): [round(v, 4) for v in ratios(by, arm, "baseline", "fairnessMovesPerSecond", f)] for f in FLEETS} for arm in ("b", "c", "load-balance")}
    fig, ax = plt.subplots(figsize=(9, 3.6), layout="constrained")
    for i, fleet in enumerate(FLEETS):
        for k, arm in enumerate(("b", "c", "load-balance")):
            values = data["speed"][arm][str(fleet)]
            x = i + (k - 1) * .26
            ax.scatter([x + d for d in jitter(len(values), .18)], values, s=11, color=COLOR[arm], alpha=.8, linewidths=0, label=LABEL[arm] if i == 0 else None)
            ax.plot([x - .1, x + .1], [median(values)] * 2, color="black", linewidth=1.5)
            ax.text(x, max(values) + .25, f"{median(values):.2f}x", ha="center", fontsize=8.5)
    ax.axhline(1.25, color="#B22222", linestyle="--", linewidth=1); ax.text(2.45, 1.45, "1.25x bar", color="#B22222", fontsize=9, ha="right")
    ax.set_xticks(range(3), [f"{f} technicians" for f in FLEETS]); ax.grid(axis="y", alpha=.2)
    ax.set_ylabel("Fairness-phase moves per second\nover the fixed production"); ax.legend(fontsize=8.5, loc="upper left", frameon=False)
    save(fig, "speed")

    # 7. Per-case variance of b and c relative to the fixed production, with the production's own spread.
    data["pairs"] = []
    for case in all_cases:
        base = [Decimal(by[("baseline", case, rep)]["measures"]["variance"]) for rep in REPS]
        mean = sum(base) / 2
        if mean == 0:
            data["pairs"].append({"case": case_label(case), "noise": None, "b": None, "c": None})
            continue
        data["pairs"].append({"case": case_label(case), "noise": float((max(base) - min(base)) / mean),
                              **{arm: float(case_mean(by, arm, case, "variance") / mean) for arm in ("b", "c")}})
    # 7.5 pt, not 7: Calibri at 7 pt saved at 200 dpi draws no rotated tick labels.
    fig, ax = plt.subplots(figsize=(9, 4.4), layout="constrained")
    x = range(len(data["pairs"]))
    ax.bar(x, [1 + r["noise"] if r["noise"] is not None else 0 for r in data["pairs"]], color="#EEEEEE", width=.8, label="fixed production + its own spread")
    ax.axhline(1, color="#555555", linewidth=1)
    ax.scatter([i - .15 for i in x if data["pairs"][i]["b"] is not None], [r["b"] for r in data["pairs"] if r["b"] is not None], color=COLOR["b"], s=16, label=LABEL["b"], zorder=3)
    ax.scatter([i + .15 for i in x if data["pairs"][i]["c"] is not None], [r["c"] for r in data["pairs"] if r["c"] is not None], color=COLOR["c"], s=16, marker="D", label=LABEL["c"], zorder=3)
    ax.set_xticks(list(x)); ax.set_xticklabels([r["case"] for r in data["pairs"]], rotation=60, ha="right", rotation_mode="anchor", fontsize=7.5)
    ax.set_xlim(-.7, len(data["pairs"]) - .3)
    ax.set_ylabel("Final variance relative to\nthe fixed production"); ax.grid(axis="y", alpha=.2); ax.legend(fontsize=8.5, loc="upper left", frameon=False)
    save(fig, "pairs")

    # 8. Per-case cost difference of b, c and load-balance against the fixed production.
    data["cost"] = {arm: [float(case_mean(by, arm, c, "costCents") - case_mean(by, "baseline", c, "costCents")) for c in all_cases] for arm in ("b", "c", "load-balance")}
    fig, ax = plt.subplots(figsize=(9, 3.5), layout="constrained")
    ticks = []
    for i, arm in enumerate(("b", "c", "load-balance")):
        values = [v / 100 for v in data["cost"][arm]]
        ax.scatter([i + d for d in jitter(len(values), .4)], values, color=COLOR[arm], s=14, alpha=.75)
        ax.plot([i - .3, i + .3], [median(values)] * 2, color="black", linewidth=1.6)
        ticks.append(f"{LABEL[arm]}\nmedian {median(values) * 100:+.1f} cents")
    ax.axhline(0, color="#555555", linewidth=1)
    ax.set_xticks(range(3), ticks, fontsize=9); ax.set_ylabel("Final cost minus the\nfixed production's ($)"); ax.grid(axis="y", alpha=.2)
    save(fig, "cost")

    # 9. Every arm on every measure against the fixed production.
    shown = ("old", "old-b", "b", "c", "load-balance")
    data["heatmap"] = {arm: {m: paired["comparison"][arm]["measures"][m]["medianRatioToBaseline"] for m in MEASURES} for arm in shown}
    cmap = LinearSegmentedColormap.from_list("fair", ["#1F7A8C", "#F7F7F7", "#B22222"])
    fig, ax = plt.subplots(figsize=(7.5, 3.4), layout="constrained")
    grid = [[data["heatmap"][arm][m] for m in MEASURES] for arm in shown]
    image = ax.imshow(grid, cmap=cmap, norm=TwoSlopeNorm(vmin=.5, vcenter=1, vmax=1.5), aspect="auto")
    for i, row in enumerate(grid):
        for j, value in enumerate(row):
            own = (shown[i] == "load-balance" and MEASURES[j] == "loadBalance") or (shown[i] != "load-balance" and MEASURES[j] == "variance")
            ax.text(j, i, f"{value:.2f}", ha="center", va="center", fontsize=9.5, fontweight="bold" if own else "normal", color="white" if value < .62 else "black")
    ax.set_xticks(range(len(MEASURES)), [MEASURE_LABEL[m] for m in MEASURES], fontsize=8.5)
    ax.set_yticks(range(len(shown)), [LABEL[a] for a in shown], fontsize=9); ax.spines[:].set_visible(False)
    fig.colorbar(image, ax=ax, shrink=.85, label="Median ratio to the fixed\nproduction (lower is fairer)")
    save(fig, "heatmap")

    # 10. Load-balance against b, case by case, on the variance.
    data["lbVsB"] = [{"case": case_label(c), "fleet": c[0], "b": float(case_mean(by, "b", c, "variance")), "loadBalance": float(case_mean(by, "load-balance", c, "variance"))} for c in all_cases]
    fig, ax = plt.subplots(figsize=(6.4, 4.2), layout="constrained")
    for fleet in FLEETS:
        points = [p for p in data["lbVsB"] if p["fleet"] == fleet]
        ax.scatter([p["b"] * 1000 for p in points], [p["loadBalance"] * 1000 for p in points], s=26, color=COLOR["load-balance"], marker=FLEET_MARKER[fleet], alpha=.8, label=f"{fleet} technicians")
    top = max(max(p["b"], p["loadBalance"]) for p in data["lbVsB"]) * 1000 * 1.05
    ax.plot([0, top], [0, top], color="#999999", linestyle="--", linewidth=1); ax.text(top * .62, top * .55, "equal", color="#777777", fontsize=9, rotation=33)
    ax.set_xlim(0, top); ax.set_ylim(0, top); ax.grid(alpha=.2)
    ax.set_xlabel("Option b's final variance (x 1e-3)"); ax.set_ylabel("Load-balance's final variance (x 1e-3)"); ax.legend(fontsize=8.5, frameon=False)
    save(fig, "lb_vs_b")

    # 11. The case where option c failed the fairness bar.
    failed = [p for p in paired["pairs"] if p["arm"] == "c" and Decimal(p["meanVarianceDifference"]) > Decimal(p["baselineVarianceSpread"])]
    data["cCase"] = [{"case": case_label((p["technicians"], p["workload"], p["seed"])),
                      **{arm: [float(Decimal(by[(arm, (p["technicians"], p["workload"], p["seed"]), rep)]["measures"]["variance"])) for rep in REPS] for arm in ("old", "baseline", "b", "c", "load-balance")}}
                     for p in failed]
    fig, ax = plt.subplots(figsize=(7, 3.2), layout="constrained")
    row = data["cCase"][0]
    for i, arm in enumerate(("old", "baseline", "b", "c", "load-balance")):
        for rep in (0, 1):
            ax.bar(i + (rep - .5) * .36, row[arm][rep] * 1000, .34, color=COLOR[arm], alpha=.55 if rep == 0 else 1)
    ax.set_xticks(range(5), [SHORT[a] for a in ("old", "baseline", "b", "c", "load-balance")], fontsize=9)
    ax.set_ylabel("Final variance (x 1e-3)\nrepetition 1 pale, 2 solid"); ax.set_title(row["case"], fontsize=10); ax.grid(axis="y", alpha=.2)
    save(fig, "c_case")

    # 12. Acceptance reasons per arm.
    reasons = ("FAIRNESS_IMPROVEMENT", "COST_REDUCTION")
    data["acceptance"] = {arm: paired["comparison"][arm]["acceptance"] for arm in ARMS}
    fig, ax = plt.subplots(figsize=(9, 2.8), layout="constrained")
    for y, arm in enumerate(ARMS):
        counts = data["acceptance"][arm]
        assert sum(counts.values()) == 72 and set(counts) <= set(reasons), (arm, counts)
        ax.barh(y, counts.get(reasons[0], 0), color=COLOR[arm])
        ax.barh(y, counts.get(reasons[1], 0), left=counts.get(reasons[0], 0), color="#DDDDDD", edgecolor=COLOR[arm], hatch="///", linewidth=.6)
        ax.text(counts.get(reasons[0], 0) - 1, y, str(counts.get(reasons[0], 0)), ha="right", va="center", fontsize=8.5, color="white" if arm not in ("old", "old-b", "c") else "black")
    ax.set_yticks(range(len(ARMS)), [LABEL[a] for a in ARMS], fontsize=9); ax.invert_yaxis(); ax.set_xlim(0, 72)
    ax.set_xlabel("Runs out of 72: fairness improvement (solid) or cost reduction (hatched)")
    save(fig, "acceptance")

    (folder / "chart_data.json").write_text(json.dumps(data, indent=1), encoding="utf-8")
    return data


if __name__ == "__main__":
    from report_data import load
    make_charts(load(), Path(__file__).resolve().parent / "generated")
    print("charts written")
