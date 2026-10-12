"""Reproducible charts from validated TABU variant cases; no network or benchmark runs."""
import json
from pathlib import Path
from statistics import mean

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.colors import LinearSegmentedColormap, TwoSlopeNorm

SOLVERS = ("TABU", "TABU_SIZE_3", "TABU_SIZE_15", "TABU_KOPT", "KOPT", "SUBLIST", "LATE_ACCEPTANCE")
ALTS = SOLVERS[1:]
LABEL = dict(zip(SOLVERS, ("Tabu", "Tabu size 3", "Tabu size 15", "Tabu + K opt", "K opt", "Sublist", "Late acceptance")))
COLOR = dict(zip(SOLVERS, ("#444444", "#5B9BD5", "#0B3A66", "#2E8B57", "#B34F75", "#8064A2", "#C37E12")))
MARK = dict(zip(SOLVERS, ("D", "v", "^", "*", "o", "s", "P")))
LINE = dict(zip(SOLVERS, ("-", "--", "--", "-", ":", ":", ":")))
FLEETS = (5, 10, 20, 50)
BUDGETS = (60, 90, 120, 240)
WORKLOADS = ("CLUSTERED", "DISPERSED", "MIXED_SKILL", "TIGHT_WINDOW")
WORK_LABEL = dict(zip(WORKLOADS, ("Clustered", "Dispersed", "Mixed skill", "Tight window")))


def select(rows, **kwargs):
    out = [r for r in rows if all(r[k] == v for k, v in kwargs.items())]
    if not out:
        raise ValueError(f"Empty group: {kwargs}")
    return out


def phase(raw, name):
    stats = raw[name + "Phase"]
    return {"steps_rate": stats["steps"] * 1000 / stats["solveMs"], "move_rate": stats["moveEvaluations"] / stats["solveMs"],
            "moves_per_step": stats["moveEvaluations"] / stats["steps"], "best_fraction": stats["timeToBestMs"] / stats["solveMs"]}


def pair(rows):
    """Adds every derived measure once, including signed differences against the matched Tabu case."""
    control = {r["key"][:-1]: r["raw"] for r in rows if r["solver"] == "TABU"}
    out = []
    for r in rows:
        raw, twin = r["raw"], control[r["key"][:-1]]
        reference = phase(raw, "reference")
        out.append({**r, "accepted_cost": raw["after"]["costCents"] / 100, "reference_cost": raw["reference"]["costCents"] / 100,
                    "start_cost": raw["before"]["costCents"] / 100,
                    "saving": (twin["after"]["costCents"] - raw["after"]["costCents"]) / 100,
                    "reference_saving": (twin["reference"]["costCents"] - raw["reference"]["costCents"]) / 100,
                    "fairness": raw["after"]["fairness"]["variance"],
                    "fairness_gain": twin["after"]["fairness"]["variance"] - raw["after"]["fairness"]["variance"],
                    "fairness_spend": (raw["after"]["costCents"] - raw["reference"]["costCents"]) / 100,
                    "steps_rate": reference["steps_rate"], "move_rate": reference["move_rate"],
                    "fair_move_rate": phase(raw, "fairness")["move_rate"],
                    "moves_per_step": reference["moves_per_step"], "best_fraction": reference["best_fraction"],
                    "late_best": reference["best_fraction"] > .9})
    return out


def avg(rows, field):
    if not rows:
        raise ValueError("Cannot average an empty group")
    return mean(float(r[field]) for r in rows)


def make_charts(rows, confirm_rows, contention, folder):
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

    def heat(name, columns, labels, values, n, limit):
        fig, ax = plt.subplots(figsize=(9, 3.9), layout="constrained")
        im = ax.imshow(values, cmap=signed, norm=TwoSlopeNorm(vmin=-limit, vcenter=0, vmax=limit), aspect="auto")
        ax.set_yticks(range(len(ALTS)), [LABEL[s] for s in ALTS]); ax.set_xticks(range(len(columns)), labels); ax.tick_params(length=0)
        for i, row in enumerate(values):
            for j, v in enumerate(row):
                ax.text(j, i, f"{v:+.2f}", ha="center", va="center", fontweight="bold" if ALTS[i] == "TABU_KOPT" else "normal")
        fig.colorbar(im, ax=ax, label="Mean saving vs Tabu ($ per case)", shrink=.85)
        data[name] = {"columns": [str(c) for c in columns], "solvers": ALTS, "n_per_cell": n, "values": values}
        save(fig, name)

    heat("cost_fleet", FLEETS + ("all",), [f"{f} techs" for f in FLEETS] + ["All fleets"],
         [[avg(select(rows, solver=s, fleet=f), "saving") for f in FLEETS] + [avg(select(rows, solver=s), "saving")] for s in ALTS], 160, 30)
    heat("cost_workload", WORKLOADS, [WORK_LABEL[w] for w in WORKLOADS],
         [[avg(select(rows, solver=s, workload=w), "saving") for w in WORKLOADS] for s in ALTS], 160, 30)

    fig, axes = plt.subplots(1, 2, figsize=(9, 3.7), layout="constrained")
    data["steps"] = []
    for s in SOLVERS:
        steps = [avg(select(rows, solver=s, fleet=f), "steps_rate") for f in FLEETS]
        per = [avg(select(rows, solver=s, fleet=f), "moves_per_step") for f in FLEETS]
        data["steps"].append({"solver": s, "fleets": FLEETS, "n_per_point": 160, "steps_per_second": steps, "moves_per_step": per})
        for ax, y in zip(axes, (steps, per)):
            ax.plot(FLEETS, y, marker=MARK[s], color=COLOR[s], linestyle=LINE[s], label=LABEL[s])
    for ax, title in zip(axes, ("Steps taken per second", "Moves evaluated per step")):
        ax.set_yscale("log"); ax.set_xticks(FLEETS); ax.set_xlabel("Technicians"); ax.set_title(title); ax.grid(alpha=.2, which="both")
    axes[0].set_ylabel("Reference phase mean (log scale)")
    axes[1].legend(fontsize=8.5, ncol=2, loc="center right")
    save(fig, "steps")

    fig, axes = plt.subplots(1, 2, figsize=(9, 3.7), sharex=True, layout="constrained")
    data["convergence"] = []
    for s in SOLVERS:
        best = [avg(select(rows, solver=s, fleet=f), "best_fraction") * 100 for f in FLEETS]
        late = [avg(select(rows, solver=s, fleet=f), "late_best") * 100 for f in FLEETS]
        data["convergence"].append({"solver": s, "fleets": FLEETS, "n_per_point": 160, "best_percent": best, "late_best_percent": late})
        axes[0].plot(FLEETS, best, marker=MARK[s], color=COLOR[s], linestyle=LINE[s], label=LABEL[s])
        axes[1].plot(FLEETS, late, marker=MARK[s], color=COLOR[s], linestyle=LINE[s], label=LABEL[s])
    axes[0].set_ylabel("Best found at % of phase"); axes[0].set_title("When the best schedule appeared")
    axes[1].set_ylabel("Cases (%)"); axes[1].set_title("Best found in the final 10%")
    for ax in axes:
        ax.set_xticks(FLEETS); ax.set_xlabel("Technicians"); ax.set_ylim(0, 102); ax.grid(alpha=.2)
    axes[0].legend(fontsize=8.5, loc="lower right")
    save(fig, "convergence")

    fig, axes = plt.subplots(1, 2, figsize=(9, 3.7), layout="constrained")
    data["budgets"] = []
    for ax, fleets, title, n in zip(axes, ((5, 10, 20), (50,)), ("5, 10 and 20 technicians", "50 technicians"), (120, 40)):
        for s in ALTS:
            y = [avg([r for r in select(rows, solver=s, budget_ms=b * 1000) if r["fleet"] in fleets], "saving") for b in BUDGETS]
            data["budgets"].append({"fleets": fleets, "solver": s, "n_per_point": n, "seconds": BUDGETS, "savings": y})
            ax.plot(BUDGETS, y, marker=MARK[s], color=COLOR[s], linestyle=LINE[s], label=LABEL[s])
        ax.axhline(0, color="#444444", linewidth=.8); ax.grid(axis="y", alpha=.2)
        ax.set_title(title); ax.set_xticks(BUDGETS); ax.set_xlabel("Budget (seconds)")
    axes[0].set_ylabel("Mean saving vs Tabu ($ per case)")
    axes[1].legend(fontsize=8.5, loc="lower right")
    save(fig, "budgets")

    fig, axes = plt.subplots(1, 2, figsize=(9, 3.7), layout="constrained")
    data["phases"] = []
    for s in ALTS:
        x = [avg(select(rows, solver=s, fleet=f, workload=w), "reference_saving") for f in FLEETS for w in WORKLOADS]
        y = [avg(select(rows, solver=s, fleet=f, workload=w), "saving") for f in FLEETS for w in WORKLOADS]
        axes[0].scatter(x, y, color=COLOR[s], marker=MARK[s], s=40, label=LABEL[s])
        data["phases"].append({"solver": s, "n_per_point": 40, "reference_savings": x, "accepted_savings": y})
    limit = max(abs(v) for d in data["phases"] for v in d["reference_savings"] + d["accepted_savings"]) * 1.05
    axes[0].plot([-limit, limit], [-limit, limit], color="#999999", linewidth=.8)
    axes[0].set_xlabel("Reference saving vs Tabu ($)"); axes[0].set_ylabel("Accepted saving vs Tabu ($)")
    axes[0].set_title("Cost race is settled before fairness"); axes[0].grid(alpha=.2)
    spend = {s: [avg(select(rows, solver=s, fleet=f), "fairness_spend") for f in FLEETS] for s in SOLVERS}
    data["fairness_spend"] = {"fleets": FLEETS, "n_per_point": 160, "dollars": spend}
    width = .11
    for i, s in enumerate(SOLVERS):
        axes[1].bar([j + (i - 3) * width for j in range(4)], spend[s], width, color=COLOR[s], label=LABEL[s])
    axes[1].set_xticks(range(4), [f"{f} techs" for f in FLEETS]); axes[1].set_ylabel("Accepted minus reference ($)")
    axes[1].set_title("Cost spent to rebalance work"); axes[1].grid(axis="y", alpha=.2)
    axes[0].legend(fontsize=8, loc="upper left")
    save(fig, "phases")

    fig, ax = plt.subplots(figsize=(9, 3.9), layout="constrained")
    data["fairness"] = []
    for s in ALTS:
        x = [avg(select(rows, solver=s, fleet=f), "saving") for f in FLEETS]
        y = [avg(select(rows, solver=s, fleet=f), "fairness_gain") * 1000 for f in FLEETS]
        ax.plot(x, y, color=COLOR[s], marker=MARK[s], markersize=8, linestyle=LINE[s], linewidth=.8, label=LABEL[s])
        for f, xx, yy in zip(FLEETS, x, y):
            ax.annotate(str(f), (xx, yy), xytext=(4, 4), textcoords="offset points", fontsize=8, color=COLOR[s])
        data["fairness"].append({"solver": s, "fleets": FLEETS, "n_per_point": 160, "savings": x, "variance_reduction_x1000": y})
    ax.axhline(0, color="#444444", linewidth=.8); ax.axvline(0, color="#444444", linewidth=.8)
    ax.set_xlabel("Mean saving vs Tabu ($ per case); right is cheaper")
    ax.set_ylabel("Variance reduction vs Tabu (x1000)\nup is more balanced"); ax.grid(alpha=.15)
    ax.legend(fontsize=9, loc="upper right")
    save(fig, "fairness")

    fig, axes = plt.subplots(1, 2, figsize=(9, 3.7), layout="constrained")
    data["variation"] = []
    for s in ALTS:
        group = select(rows, solver=s)
        seeds = sorted({r["seed"] for r in group})
        seed_means = [avg(select(group, seed=seed), "saving") for seed in seeds]
        values = sorted(r["saving"] for r in group)
        axes[0].plot(range(len(seeds)), seed_means, marker=MARK[s], color=COLOR[s], linestyle=LINE[s], label=LABEL[s])
        axes[1].step(values, [(i + 1) / len(values) * 100 for i in range(len(values))], where="post", color=COLOR[s], linestyle=LINE[s], label=LABEL[s])
        data["variation"].append({"solver": s, "seeds": seeds, "n_per_seed": 64, "seed_means": seed_means, "case_savings": values})
    axes[0].set_xticks(range(len(seeds)), [str(s) for s in seeds], fontsize=9)
    axes[0].set_xlabel("Search seed (equal categorical spacing)"); axes[0].set_ylabel("Mean saving vs Tabu ($)")
    axes[0].axhline(0, color="#444444", linewidth=.8); axes[0].grid(axis="y", alpha=.2)
    axes[1].set_xlabel("Case saving vs Tabu ($)"); axes[1].set_ylabel("Cumulative cases (%)"); axes[1].set_xlim(-120, 80)
    axes[1].set_ylim(0, 102); axes[1].axvline(0, color="#444444", linewidth=.8); axes[1].grid(alpha=.2)
    axes[1].legend(fontsize=8.5, loc="upper left")
    save(fig, "variation")

    fig, ax = plt.subplots(figsize=(9, 3.4), layout="constrained")
    data["contention"] = []
    base = ("TABU", "KOPT", "SUBLIST", "LATE_ACCEPTANCE")
    for i, parallel in enumerate((4, 6, 10)):
        for group in contention[parallel]["groups"]:
            j = base.index(group["solver"])
            x = i + (j - 1.5) * .12 + (.03 if group["fleet"] == 50 else -.03)
            ax.scatter(x, group["reference_median_ratio"], color=COLOR[group["solver"]], marker="o" if group["fleet"] == 20 else "s", s=40,
                       label=f"{LABEL[group['solver']]}" if i == 0 and group["fleet"] == 20 else None)
            data["contention"].append({"parallel_cases": parallel, "solver": group["solver"], "fleet": group["fleet"],
                                       "pairs": group["pairs"], "ratio": group["reference_median_ratio"]})
    ax.axhline(.95, color="#B22222", linewidth=1, linestyle="--"); ax.text(2.45, .955, "0.95 bar", color="#B22222", fontsize=9, ha="right")
    ax.axhline(1, color="#444444", linewidth=.6)
    ax.set_xticks(range(3), ["4 at once", "6 at once (used)", "10 at once"]); ax.set_ylim(.5, 1.05); ax.set_xlim(-.5, 2.6)
    ax.set_ylabel("Parallel / sequential\nmove evaluation rate"); ax.grid(axis="y", alpha=.2); ax.legend(fontsize=9, loc="lower left")
    save(fig, "contention")

    control = {r["key"][:-1]: r for r in rows if r["solver"] == "TABU"}
    seq_control = {r["key"][:-1]: r["raw"] for r in confirm_rows if r["solver"] == "TABU"}
    points = []
    for r in confirm_rows:
        if r["solver"] != "TABU_KOPT":
            continue
        sequential = (seq_control[r["key"][:-1]]["after"]["costCents"] - r["raw"]["after"]["costCents"]) / 100
        twin = [p for p in select(rows, solver="TABU_KOPT", fleet=r["fleet"], workload=r["workload"], seed=r["seed"], budget_ms=r["budget_ms"])][0]
        points.append({"key": list(r["key"][:-1]), "sequential": sequential, "parallel": twin["saving"]})
    data["confirmation"] = points
    fig, ax = plt.subplots(figsize=(9, 3.9), layout="constrained")
    for f, marker in zip(FLEETS, ("o", "s", "^", "D")):
        sub = [p for p in points if p["key"][0] == f]
        ax.scatter([p["parallel"] for p in sub], [p["sequential"] for p in sub], marker=marker, s=40, color=COLOR["TABU_KOPT"], alpha=.75, label=f"{f} techs")
    limit = max(abs(v) for p in points for v in (p["parallel"], p["sequential"])) * 1.08
    ax.plot([-limit, limit], [-limit, limit], color="#999999", linewidth=.8)
    ax.axhline(0, color="#444444", linewidth=.6); ax.axvline(0, color="#444444", linewidth=.6)
    ax.set_xlabel("Six at once: Tabu + K opt saving vs Tabu ($)"); ax.set_ylabel("One at a time: saving ($)")
    ax.grid(alpha=.15); ax.legend(fontsize=9, loc="upper left")
    save(fig, "confirmation")
    assert len(control) == 640
    (folder / "chart_data.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    return data
