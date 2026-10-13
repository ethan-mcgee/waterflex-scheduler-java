"""Reproducible charts from the validated follow-up cases and the first study; no network or benchmark runs."""
import json
from pathlib import Path
from statistics import mean

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.colors import LinearSegmentedColormap, TwoSlopeNorm

SOLVERS = ("TABU", "TABU_KOPT", "TABU_KOPT_SIZE_3", "TABU_SIZE_3", "KOPT")
ALTS = SOLVERS[1:]
LONG_SOLVERS = ("TABU_KOPT", "TABU", "KOPT", "LATE_ACCEPTANCE")
LABEL = {"TABU": "Tabu", "TABU_KOPT": "Tabu + K opt", "TABU_KOPT_SIZE_3": "Tabu + K opt size 3", "TABU_SIZE_3": "Tabu size 3",
         "KOPT": "K opt", "LATE_ACCEPTANCE": "Late acceptance"}
# Shared solvers keep the colors, markers and line styles of the 5d15129b report.
COLOR = {"TABU": "#444444", "TABU_KOPT": "#2E8B57", "TABU_KOPT_SIZE_3": "#8064A2", "TABU_SIZE_3": "#5B9BD5", "KOPT": "#B34F75", "LATE_ACCEPTANCE": "#C37E12"}
MARK = {"TABU": "D", "TABU_KOPT": "*", "TABU_KOPT_SIZE_3": "X", "TABU_SIZE_3": "v", "KOPT": "o", "LATE_ACCEPTANCE": "P"}
LINE = {"TABU": "-", "TABU_KOPT": "-", "TABU_KOPT_SIZE_3": "-", "TABU_SIZE_3": "--", "KOPT": ":", "LATE_ACCEPTANCE": ":"}
FLEETS = (5, 10, 20, 30, 50)
EARLIER_FLEETS = (5, 10, 20, 50)
BUDGETS = (60, 120)
LONG_FLEETS = (30, 50)
LONG_BUDGETS = (240, 480, 600)
EARLIER_BUDGETS = (60, 90, 120, 240)
WORKLOADS = ("CLUSTERED", "DISPERSED", "MIXED_SKILL", "TIGHT_WINDOW")
WORK_LABEL = dict(zip(WORKLOADS, ("Clustered", "Dispersed", "Mixed skill", "Tight window")))


def select(rows, **kwargs):
    out = [r for r in rows if all(r[k] == v for k, v in kwargs.items())]
    if not out:
        raise ValueError(f"Empty group: {kwargs}")
    return out


def avg(rows, field):
    if not rows:
        raise ValueError("Cannot average an empty group")
    return mean(float(r[field]) for r in rows)


def pair(rows, control):
    """Adds every derived measure once, including signed differences against the matched control case."""
    twins = {r["key"][:-1]: r["raw"] for r in rows if r["solver"] == control}
    out = []
    for r in rows:
        raw, twin = r["raw"], twins[r["key"][:-1]]
        stats = raw["referencePhase"]
        out.append({**r, "accepted_cost": raw["after"]["costCents"] / 100, "reference_cost": raw["reference"]["costCents"] / 100,
                    "start_cost": raw["before"]["costCents"] / 100,
                    "saving": (twin["after"]["costCents"] - raw["after"]["costCents"]) / 100,
                    "reference_saving": (twin["reference"]["costCents"] - raw["reference"]["costCents"]) / 100,
                    "fairness": raw["after"]["fairness"]["variance"],
                    "fairness_gain": twin["after"]["fairness"]["variance"] - raw["after"]["fairness"]["variance"],
                    "steps_rate": stats["steps"] * 1000 / stats["solveMs"], "move_rate": stats["moveEvaluations"] / stats["solveMs"],
                    "moves_per_step": stats["moveEvaluations"] / stats["steps"], "best_fraction": stats["timeToBestMs"] / stats["solveMs"],
                    "late_best": stats["timeToBestMs"] / stats["solveMs"] > .9,
                    "kept_start": raw["after"] == raw["before"] and raw["reference"] != raw["before"]})
    return out


def versus_tabu(long_rows, solver, fleet, budget_ms):
    """Long arm savings against Tabu on the same pair; the arm's control is Tabu + K opt, so both sides are read from raw cost."""
    tabu = {r["key"][:-1]: r for r in select(long_rows, solver="TABU", fleet=fleet, budget_ms=budget_ms)}
    return [(tabu[r["key"][:-1]]["raw"]["reference"]["costCents"] - r["raw"]["reference"]["costCents"]) / 100
            for r in select(long_rows, solver=solver, fleet=fleet, budget_ms=budget_ms)]


def time_curve(main, long, earlier):
    """Saving vs Tabu at 50 technicians by seconds of cost search, from all three sources. Fixtures differ between sources."""
    curve = []
    for s in ("TABU_KOPT", "KOPT", "LATE_ACCEPTANCE"):
        for b in EARLIER_BUDGETS:
            group = select(earlier, solver=s, fleet=50, budget_ms=b * 1000)
            curve.append({"source": "5d15129b", "solver": s, "seconds": b * 2 // 3, "n": len(group), "saving": avg(group, "reference_saving")})
        if s != "LATE_ACCEPTANCE":
            for b in BUDGETS:
                group = select(main, solver=s, fleet=50, budget_ms=b * 1000)
                curve.append({"source": "main", "solver": s, "seconds": b, "n": len(group), "saving": avg(group, "reference_saving")})
        for b in LONG_BUDGETS:
            values = versus_tabu(long, s, 50, b * 1000)
            curve.append({"source": "long", "solver": s, "seconds": b, "n": len(values), "saving": mean(values)})
    return curve


def make_charts(main, long, confirm, earlier, folder):
    folder.mkdir(exist_ok=True)
    font = next((p for p in (Path("C:/Windows/Fonts/calibri.ttf"), Path("/usr/share/fonts/truetype/crosextra/Carlito-Regular.ttf")) if p.exists()), None)
    if font is None:
        raise RuntimeError("Calibri or Carlito is required")
    font_manager.fontManager.addfont(str(font))
    plt.rcParams.update({"font.family": font_manager.FontProperties(fname=str(font)).get_name(),
                         "font.size": 11, "axes.spines.top": False, "axes.spines.right": False,
                         "axes.unicode_minus": False, "savefig.dpi": 200})
    data = {}
    signed = LinearSegmentedColormap.from_list("signed", ["#DDAA76", "#FFFFFF", "#7DAFCB"])

    def save(fig, name):
        fig.savefig(folder / f"{name}.png", facecolor="white", metadata={"Software": "WaterFlex report"})
        plt.close(fig)

    def lines(ax, x, y, s, **kw):
        ax.plot(x, y, marker=MARK[s], color=COLOR[s], linestyle=LINE[s], label=LABEL[s], markersize=8 if MARK[s] in "*X" else 6, **kw)

    # 1. Where the new fixtures save cost.
    values = [[avg(select(main, solver=s, fleet=f), "saving") for f in FLEETS] + [avg(select(main, solver=s), "saving")] for s in ALTS]
    fig, ax = plt.subplots(figsize=(9, 3.2), layout="constrained")
    im = ax.imshow(values, cmap=signed, norm=TwoSlopeNorm(vmin=-25, vcenter=0, vmax=25), aspect="auto")
    ax.set_yticks(range(len(ALTS)), [LABEL[s] for s in ALTS]); ax.set_xticks(range(len(FLEETS) + 1), [f"{f} techs" for f in FLEETS] + ["All fleets"])
    ax.tick_params(length=0)
    for i, row in enumerate(values):
        for j, v in enumerate(row):
            ax.text(j, i, f"{v:+.2f}", ha="center", va="center", fontweight="bold" if ALTS[i] == "TABU_KOPT_SIZE_3" else "normal")
    fig.colorbar(im, ax=ax, label="Mean saving vs Tabu ($ per case)", shrink=.85)
    data["cost_fleet"] = {"columns": [str(f) for f in FLEETS] + ["all"], "solvers": ALTS, "n_per_cell": 48, "values": values}
    save(fig, "cost_fleet")

    # 2. Replication at the same 60 seconds of cost search: first study's 90 second budget vs this study's 60 seconds.
    fig, axes = plt.subplots(1, 3, figsize=(9, 3.3), sharey=True, layout="constrained")
    data["replication"] = []
    width = .38
    for ax, s in zip(axes, ("TABU_KOPT", "TABU_SIZE_3", "KOPT")):
        before = [avg(select(earlier, solver=s, fleet=f, budget_ms=90000), "reference_saving") for f in EARLIER_FLEETS]
        now = [avg(select(main, solver=s, fleet=f, budget_ms=60000), "reference_saving") for f in EARLIER_FLEETS]
        x = range(len(EARLIER_FLEETS))
        ax.bar([i - width / 2 for i in x], before, width, facecolor="white", edgecolor=COLOR[s], hatch="///", linewidth=1, label="First study fixtures")
        ax.bar([i + width / 2 for i in x], now, width, color=COLOR[s], label="New fixtures")
        ax.axhline(0, color="#444444", linewidth=.8); ax.grid(axis="y", alpha=.2)
        ax.set_xticks(list(x), [str(f) for f in EARLIER_FLEETS]); ax.set_xlabel("Technicians"); ax.set_title(LABEL[s])
        ax.legend(fontsize=8, loc="lower left" if s == "KOPT" else "upper left")
        data["replication"].append({"solver": s, "fleets": EARLIER_FLEETS, "earlier_n": 40, "new_n": 24, "earlier": before, "new": now})
    axes[0].set_ylabel("Cost search saving vs Tabu ($)")
    save(fig, "replication")

    # 3. Step economics.
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.6), layout="constrained")
    data["steps"] = []
    for s in SOLVERS:
        steps = [avg(select(main, solver=s, fleet=f), "steps_rate") for f in FLEETS]
        per = [avg(select(main, solver=s, fleet=f), "moves_per_step") for f in FLEETS]
        data["steps"].append({"solver": s, "fleets": FLEETS, "n_per_point": 48, "steps_per_second": steps, "moves_per_step": per})
        lines(axes[0], FLEETS, steps, s); lines(axes[1], FLEETS, per, s)
    for ax, title in zip(axes, ("Steps taken per second", "Moves evaluated per step")):
        ax.set_yscale("log"); ax.set_xticks(FLEETS); ax.set_xlabel("Technicians"); ax.set_title(title); ax.grid(alpha=.2, which="both")
    axes[0].set_ylabel("Cost search mean (log scale)")
    axes[1].legend(fontsize=8.5, loc="lower left")
    save(fig, "steps")

    # 4. Do the two improvements add up?
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.6), layout="constrained")
    tk = [avg(select(main, solver="TABU_KOPT", fleet=f), "saving") for f in FLEETS]
    t3 = [avg(select(main, solver="TABU_SIZE_3", fleet=f), "saving") for f in FLEETS]
    both = [avg(select(main, solver="TABU_KOPT_SIZE_3", fleet=f), "saving") for f in FLEETS]
    for s, y in (("TABU_KOPT", tk), ("TABU_SIZE_3", t3), ("TABU_KOPT_SIZE_3", both)):
        lines(axes[0], FLEETS, y, s)
    axes[0].plot(FLEETS, [a + b for a, b in zip(tk, t3)], color="#999999", linestyle="--", linewidth=1.2, label="Sum of the two")
    axes[0].axhline(0, color="#444444", linewidth=.8); axes[0].grid(axis="y", alpha=.2); axes[0].set_xticks(FLEETS)
    axes[0].set_xlabel("Technicians"); axes[0].set_ylabel("Mean saving vs Tabu ($)"); axes[0].set_title("Saving vs Tabu"); axes[0].legend(fontsize=8.5, loc="upper left")
    twins = {r["key"][:-1]: r for r in select(main, solver="TABU_KOPT")}
    direct = {f: [twins[r["key"][:-1]]["accepted_cost"] - r["accepted_cost"] for r in select(main, solver="TABU_KOPT_SIZE_3", fleet=f)] for f in FLEETS}
    counts = {name: [sum(test(v) for v in direct[f]) for f in FLEETS] for name, test in
              (("wins", lambda v: v > 0), ("ties", lambda v: v == 0), ("losses", lambda v: v < 0))}
    bottom = [0] * len(FLEETS)
    for name, color in (("wins", COLOR["TABU_KOPT_SIZE_3"]), ("ties", "#CCCCCC"), ("losses", COLOR["TABU_KOPT"])):
        axes[1].bar(range(len(FLEETS)), counts[name], .6, bottom=bottom, color=color, label=name.capitalize())
        bottom = [a + b for a, b in zip(bottom, counts[name])]
    axes[1].set_xticks(range(len(FLEETS)), [str(f) for f in FLEETS]); axes[1].set_xlabel("Technicians"); axes[1].set_ylabel("Matched cases")
    axes[1].set_title("Size 3 + K opt vs Tabu + K opt, case by case"); axes[1].legend(fontsize=8.5, loc="lower right", framealpha=.9)
    data["combination"] = {"fleets": FLEETS, "n_per_point": 48, "tabu_kopt": tk, "tabu_size_3": t3, "tabu_kopt_size_3": both,
                           "direct_counts": counts, "direct_means": [mean(direct[f]) for f in FLEETS]}
    save(fig, "combination")

    # 5. Fleet size and budget.
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.6), sharey=True, layout="constrained")
    data["fleet_budget"] = []
    for ax, b in zip(axes, BUDGETS):
        for s in ALTS:
            y = [avg(select(main, solver=s, fleet=f, budget_ms=b * 1000), "saving") for f in FLEETS]
            data["fleet_budget"].append({"solver": s, "seconds": b, "fleets": FLEETS, "n_per_point": 24, "savings": y})
            lines(ax, FLEETS, y, s)
        ax.axhline(0, color="#444444", linewidth=.8); ax.grid(axis="y", alpha=.2); ax.set_xticks(FLEETS)
        ax.set_xlabel("Technicians"); ax.set_title(f"{b} seconds of cost search")
    axes[0].set_ylabel("Mean saving vs Tabu ($)"); axes[1].legend(fontsize=8.5, loc="lower left")
    save(fig, "fleet_budget")

    # 6. Did the search finish in time?
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.6), sharex=True, layout="constrained")
    data["convergence"] = []
    for s in SOLVERS:
        best = [avg(select(main, solver=s, fleet=f), "best_fraction") * 100 for f in FLEETS]
        late = [avg(select(main, solver=s, fleet=f), "late_best") * 100 for f in FLEETS]
        data["convergence"].append({"solver": s, "fleets": FLEETS, "n_per_point": 48, "best_percent": best, "late_best_percent": late})
        lines(axes[0], FLEETS, best, s); lines(axes[1], FLEETS, late, s)
    axes[0].set_ylabel("Best found at % of search"); axes[0].set_title("When the best schedule appeared")
    axes[1].set_ylabel("Cases (%)"); axes[1].set_title("Best found in the final 10%")
    for ax in axes:
        ax.set_xticks(FLEETS); ax.set_xlabel("Technicians"); ax.set_ylim(0, 102); ax.grid(alpha=.2)
    axes[0].legend(fontsize=8.5, loc="lower right")
    save(fig, "convergence")

    # 7. Long budgets against Tabu + K opt.
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.6), sharey=True, layout="constrained")
    data["long_budget"] = []
    for ax, f in zip(axes, LONG_FLEETS):
        for s in LONG_SOLVERS[1:]:
            y = [avg(select(long, solver=s, fleet=f, budget_ms=b * 1000), "saving") for b in LONG_BUDGETS]
            data["long_budget"].append({"solver": s, "fleet": f, "seconds": LONG_BUDGETS, "n_per_point": 8, "savings": y})
            lines(ax, LONG_BUDGETS, y, s)
        ax.axhline(0, color=COLOR["TABU_KOPT"], linewidth=1.4, label="Tabu + K opt (control)")
        ax.grid(axis="y", alpha=.2); ax.set_xticks(LONG_BUDGETS); ax.set_xlabel("Seconds of cost search"); ax.set_title(f"{f} technicians")
    axes[0].set_ylabel("Mean saving vs Tabu + K opt ($)")
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="outside lower center", ncol=4, fontsize=9, frameon=False)
    save(fig, "long_budget")

    # 8. One time axis across both studies at 50 technicians.
    curve = time_curve(main, long, earlier)
    data["time_curve"] = curve
    fig, ax = plt.subplots(figsize=(9, 3.9), layout="constrained")
    for s in ("TABU_KOPT", "KOPT", "LATE_ACCEPTANCE"):
        old = [c for c in curve if c["solver"] == s and c["source"] == "5d15129b"]
        new = sorted((c for c in curve if c["solver"] == s and c["source"] != "5d15129b"), key=lambda c: c["seconds"])
        ax.plot([c["seconds"] for c in old], [c["saving"] for c in old], color=COLOR[s], linestyle=":", marker=MARK[s], markerfacecolor="white", markersize=7)
        ax.plot([c["seconds"] for c in new], [c["saving"] for c in new], color=COLOR[s], linestyle="-", marker=MARK[s], markersize=8 if MARK[s] in "*X" else 6, label=LABEL[s])
    ax.plot([], [], color="#777777", linestyle=":", marker="o", markerfacecolor="white", label="Hollow, dotted: first study")
    ax.plot([], [], color="#777777", linestyle="-", marker="o", label="Filled, solid: this study")
    ax.axhline(0, color=COLOR["TABU"], linewidth=1.2); ax.text(42, 1.5, "Tabu", color=COLOR["TABU"], fontsize=9)
    ax.set_xscale("log"); ticks = (40, 60, 80, 120, 160, 240, 480, 600); ax.set_xticks(ticks, [str(t) for t in ticks]); ax.minorticks_off()
    ax.set_xlabel("Seconds of cost search (log scale)"); ax.set_ylabel("Mean saving vs Tabu ($)\n50 technicians"); ax.grid(alpha=.2)
    ax.legend(fontsize=8.5, loc="lower right")
    save(fig, "time_curve")

    # 9. Balance.
    fig, ax = plt.subplots(figsize=(9, 3.7), layout="constrained")
    data["fairness"] = []
    for s in ALTS:
        x = [avg(select(main, solver=s, fleet=f), "saving") for f in FLEETS]
        y = [avg(select(main, solver=s, fleet=f), "fairness_gain") * 1000 for f in FLEETS]
        ax.plot(x, y, color=COLOR[s], marker=MARK[s], markersize=8, linestyle=LINE[s], linewidth=.8, label=LABEL[s])
        for f, xx, yy in zip(FLEETS, x, y):
            ax.annotate(str(f), (xx, yy), xytext=(4, 4), textcoords="offset points", fontsize=8, color=COLOR[s])
        data["fairness"].append({"solver": s, "fleets": FLEETS, "n_per_point": 48, "savings": x, "variance_reduction_x1000": y})
    ax.axhline(0, color="#444444", linewidth=.8); ax.axvline(0, color="#444444", linewidth=.8)
    ax.set_xlabel("Mean saving vs Tabu ($ per case); right is cheaper")
    ax.set_ylabel("Variance reduction vs Tabu (x1000)\nup is more balanced"); ax.grid(alpha=.15)
    ax.legend(fontsize=9, loc="lower left")
    save(fig, "fairness")

    # 10. Variation by fixture and seed, and the spread of every case.
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.6), layout="constrained")
    data["variation"] = []
    combos = [(x, s) for x in (1, 2) for s in (211, 223, 227)]
    for s in ALTS:
        group = select(main, solver=s)
        combo_means = [avg(select(group, fixture_seed=x, seed=seed), "saving") for x, seed in combos]
        values = sorted(r["saving"] for r in group)
        axes[0].plot(range(len(combos)), combo_means, marker=MARK[s], color=COLOR[s], linestyle=LINE[s], label=LABEL[s])
        axes[1].step(values, [(i + 1) / len(values) * 100 for i in range(len(values))], where="post", color=COLOR[s], linestyle=LINE[s], label=LABEL[s])
        data["variation"].append({"solver": s, "combinations": combos, "n_per_point": 40, "means": combo_means, "case_savings": values})
    axes[0].set_xticks(range(len(combos)), [f"F{x}\n{seed}" for x, seed in combos], fontsize=9)
    axes[0].set_xlabel("Fixture seed and search seed"); axes[0].set_ylabel("Mean saving vs Tabu ($)")
    axes[0].axhline(0, color="#444444", linewidth=.8); axes[0].grid(axis="y", alpha=.2)
    axes[1].set_xlabel("Case saving vs Tabu ($)"); axes[1].set_ylabel("Cumulative cases (%)"); axes[1].set_xlim(-60, 50)
    axes[1].set_ylim(0, 102); axes[1].axvline(0, color="#444444", linewidth=.8); axes[1].grid(alpha=.2)
    axes[1].legend(fontsize=8.5, loc="upper left")
    save(fig, "variation")

    # 11. Sequential confirmation.
    parallel = {r["key"][:-1]: r for r in select(main, solver="TABU_KOPT_SIZE_3")}
    points = [{"key": list(r["key"][:-1]), "sequential": r["saving"], "parallel": parallel[r["key"][:-1]]["saving"]}
              for r in select(confirm, solver="TABU_KOPT_SIZE_3")]
    data["confirmation"] = points
    fig, ax = plt.subplots(figsize=(9, 3.2), layout="constrained")
    for f, marker in zip((20, 50), ("^", "s")):
        sub = [p for p in points if p["key"][0] == f]
        ax.scatter([p["parallel"] for p in sub], [p["sequential"] for p in sub], marker=marker, s=46, color=COLOR["TABU_KOPT_SIZE_3"], alpha=.8, label=f"{f} techs")
    every = [v for p in points for v in (p["parallel"], p["sequential"])]
    low, top = min(0, min(every)) * 1.1, max(every) * 1.1
    ax.plot([low, top], [low, top], color="#999999", linewidth=.8)
    ax.set_xlim(low, top); ax.set_ylim(low, top)
    ax.set_xlabel("Six at once: Tabu + K opt size 3 saving vs Tabu ($)"); ax.set_ylabel("One at a time: saving ($)")
    ax.grid(alpha=.15); ax.legend(fontsize=9, loc="upper left")
    save(fig, "confirmation")

    (folder / "chart_data.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    return data
