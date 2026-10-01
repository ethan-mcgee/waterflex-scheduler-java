"""Reproducible charts from validated daily cases; no network or benchmark runs."""
import json
from pathlib import Path
from statistics import mean

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.colors import LinearSegmentedColormap, TwoSlopeNorm

SOLVERS = ("TABU", "KOPT", "LATE_ACCEPTANCE", "SUBLIST")
ALTS = SOLVERS[1:]
LABEL = dict(zip(SOLVERS, ("Tabu", "K opt", "Late acceptance", "Sublist")))
COLOR = dict(zip(SOLVERS, ("#444444", "#276A96", "#C86B1A", "#008C91")))
MARK = dict(zip(SOLVERS, ("D", "o", "^", "s")))
FLEETS = (5, 10, 20, 50)
BUDGETS = (60, 90, 120, 240)
WORKLOADS = ("CLUSTERED", "DISPERSED")


def select(rows, **kwargs):
    out = [r for r in rows if all(str(r[k]) == str(v) for k, v in kwargs.items())]
    if not out:
        raise ValueError(f"Empty group: {kwargs}")
    return out


def pair(rows):
    control = {r["key"][:-1]: r for r in rows if r["solver"] == "TABU"}
    out = []
    for r in rows:
        c = control[r["key"][:-1]]
        out.append({**r, "saving": (int(c["accepted_cost"]) - int(r["accepted_cost"])) / 100,
                    "fairness_gain": float(c["fairness"]) - float(r["fairness"])})
    return out


def avg(rows, field):
    if not rows:
        raise ValueError("Cannot average an empty group")
    return mean(float(r[field]) for r in rows)


def make_charts(rows, folder):
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

    groups = [(f, w) for f in FLEETS for w in WORKLOADS]
    values = [[avg(select(rows, fleet=f, workload=w, solver=s), "saving") for s in ALTS] for f, w in groups]
    data["cost_groups"] = {"groups": groups, "solvers": ALTS, "n_per_cell": 40, "values": values}
    fig, ax = plt.subplots(figsize=(9, 4.4), layout="constrained")
    cmap = LinearSegmentedColormap.from_list("signed", ["#DDAA76", "#FFFFFF", "#7DAFCB"])
    im = ax.imshow(values, cmap=cmap, norm=TwoSlopeNorm(vmin=-60, vcenter=0, vmax=60), aspect="auto")
    ax.set_yticks(range(8), [f"{f} techs  {w.title()}" for f, w in groups])
    ax.set_xticks(range(3), [LABEL[s] for s in ALTS]); ax.tick_params(length=0)
    for i, row in enumerate(values):
        for j, v in enumerate(row): ax.text(j, i, f"{v:+.2f}", ha="center", va="center")
    fig.colorbar(im, ax=ax, label="Mean saving vs Tabu ($ per case)", shrink=.85)
    save(fig, "cost_groups")

    fig, axes = plt.subplots(1, 2, figsize=(9, 3.6), sharey=True, layout="constrained")
    data["budgets"] = []
    for ax, w in zip(axes, WORKLOADS):
        for s in ALTS:
            y = [avg(select(rows, solver=s, workload=w, budget_ms=b*1000), "saving") for b in BUDGETS]
            data["budgets"].append({"workload": w, "solver": s, "n_per_point": 40, "seconds": BUDGETS, "savings": y})
            ax.plot(BUDGETS, y, marker=MARK[s], color=COLOR[s], label=LABEL[s])
        ax.axhline(0, color="#444444", linewidth=.8); ax.grid(axis="y", alpha=.2)
        ax.set_title(w.title()); ax.set_xticks(BUDGETS); ax.set_xlabel("Budget (seconds)")
    axes[0].set_ylabel("Mean saving vs Tabu ($ per case)")
    axes[1].legend(fontsize=10)
    save(fig, "budgets")

    fig, axes = plt.subplots(1, 2, figsize=(9, 3.7), layout="constrained")
    data["variation"] = []
    for s in ALTS:
        group = select(rows, solver=s)
        seeds = sorted({int(r["seed"]) for r in group})
        seed_means = [avg(select(group, seed=seed), "saving") for seed in seeds]
        axes[0].plot(range(len(seeds)), seed_means, marker=MARK[s], color=COLOR[s], label=LABEL[s])
        values = sorted(r["saving"] for r in group)
        axes[1].step(values, [(i+1)/len(values)*100 for i in range(len(values))], where="post", color=COLOR[s], label=LABEL[s], linestyle={"KOPT":"-","LATE_ACCEPTANCE":"--","SUBLIST":":"}[s])
        data["variation"].append({"solver":s,"seeds":seeds,"n_per_seed":32,"seed_means":seed_means,"case_savings":values})
    axes[0].set_xticks(range(len(seeds)), [str(s) for s in seeds], fontsize=9)
    axes[0].set_xlabel("Search seed (equal categorical spacing)"); axes[0].set_ylabel("Mean saving vs Tabu ($)")
    axes[0].axhline(0,color="#444444",linewidth=.8);axes[0].grid(axis="y",alpha=.2)
    axes[1].set_xlabel("Case saving vs Tabu ($)"); axes[1].set_ylabel("Cumulative cases (%)")
    axes[1].set_ylim(0,102);axes[1].axvline(0,color="#444444",linewidth=.8);axes[1].grid(alpha=.2)
    axes[0].legend(fontsize=9)
    save(fig,"variation")

    fig, ax = plt.subplots(figsize=(9,3.9), layout="constrained")
    data["fairness"] = []
    for s in ALTS:
        x = [avg(select(rows, solver=s, fleet=f, workload=w),"saving") for f,w in groups]
        y = [avg(select(rows, solver=s, fleet=f, workload=w),"fairness_gain") for f,w in groups]
        ax.scatter(x,y,color=COLOR[s],marker=MARK[s],s=65,label=LABEL[s])
        for (f,w), xx, yy in zip(groups,x,y):
            if s=="KOPT":
                offset=(-18,-15) if (f,w)==(5,"CLUSTERED") else (5,6)
                ax.annotate(f"{f}{'C' if w=='CLUSTERED' else 'D'}",(xx,yy),xytext=offset,textcoords="offset points",fontsize=9)
        data["fairness"].append({"solver":s,"groups":groups,"n_per_point":40,"savings":x,"variance_reduction":y})
    ax.axhline(0,color="#444444",linewidth=.8);ax.axvline(0,color="#444444",linewidth=.8)
    ax.set_xlabel("Mean saving vs Tabu ($ per case); right is cheaper")
    ax.set_ylabel("Mean variance reduction vs Tabu\nup is more balanced")
    ax.grid(alpha=.15);ax.legend(loc="lower left",fontsize=10)
    save(fig,"fairness")

    fig, axes = plt.subplots(1,2,figsize=(9,3.5),sharey=True,layout="constrained")
    data["throughput"] = []
    for ax, phase in zip(axes,("reference","fairness")):
        for s in SOLVERS:
            y=[avg(select(rows,solver=s,fleet=f),phase+"_move_rate")/1000 for f in FLEETS]
            data["throughput"].append({"phase":phase,"solver":s,"fleets":FLEETS,"n_per_point":80,"thousand_moves_per_second":y})
            ax.plot(FLEETS,y,marker=MARK[s],color=COLOR[s],label=LABEL[s])
        ax.set_title(phase.title()+" phase"); ax.set_xlabel("Technicians");ax.set_xticks(FLEETS);ax.set_ylim(bottom=0);ax.grid(alpha=.2)
    axes[0].set_ylabel("Mean thousand move evaluations / second")
    axes[1].legend(fontsize=9)
    save(fig,"throughput")

    example=select(rows,fleet=5,workload="CLUSTERED",seed=17,budget_ms=60000)
    fig,ax=plt.subplots(figsize=(9,3.5),layout="constrained")
    data["worked"] = []
    for s in SOLVERS:
        r=select(example,solver=s)[0]
        workloads=r["raw"]["after"]["fairness"]["workloads"]
        vals=[w["paidMinutes"]/w["regularCapacityMinutes"]*100 for w in workloads]
        ax.plot(range(5),vals,color=COLOR[s],marker=MARK[s],label=LABEL[s])
        data["worked"].append({"solver":s,"technicians":[w["technicianId"] for w in workloads],"utilization_percent":vals})
    ax.set_xticks(range(5),[f"Tech {n}" for n in range(5)]);ax.set_ylabel("Accepted paid time / capacity (%)")
    ax.set_ylim(0,65);ax.grid(axis="y",alpha=.2);ax.legend(ncol=4,loc="upper center",fontsize=10)
    save(fig,"worked")
    (folder/"chart_data.json").write_text(json.dumps(data,indent=2)+"\n",encoding="utf-8")
    return data
