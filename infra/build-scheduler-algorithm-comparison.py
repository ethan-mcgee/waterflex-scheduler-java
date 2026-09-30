"""Build and validate the September 29 scheduler comparison from retained evidence.

Usage: py -3 infra/build-scheduler-algorithm-comparison.py [--check]
Requires reportlab, matplotlib, and pymupdf. --check validates inputs and outputs.
"""
from collections import Counter
from collections import defaultdict
from hashlib import sha256
from html import escape
from io import BytesIO
from pathlib import Path
import gzip
import json
import re
from statistics import mean, median
from math import isclose, ceil, isfinite
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
plt.rcParams.update({"font.size": 12})
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import inch
from reportlab.platypus import SimpleDocTemplate, Paragraph, Table, TableStyle, Image, KeepTogether, PageBreak

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "docs/evidence/scheduler-field-2026-09-29"
PDF = ROOT / "docs/scheduler-algorithm-comparison-2026-09-29.pdf"
MD = ROOT / "docs/scheduler-algorithm-comparison-2026-09-29.md"
STAGES = ("original", "screen", "held", "stress", "browser")
BOOKING = ("INSERTION", "BOUNDED", "EXPANDED", "RUIN_RECREATE", "SHARED")
DAILY = ("CURRENT_CAPPED", "CURRENT_UNCAPPED", "LATE_ACCEPTANCE_CHANGE", "LATE_ACCEPTANCE", "TABU", "SUBLIST", "KOPT", "RUIN_RECREATE")
NAVY = "#15334a"
TEAL = "#087e8b"
ORANGE = "#c86b23"


def read(name):
    return json.loads((DATA / name).read_text(encoding="utf-8-sig"))


def money(cents):
    return f"${cents / 100:,.2f}"


def signed(cents):
    return f"{'+' if cents > 0 else ''}{cents / 100:,.2f}"


def interval_cell(s, variant, budget):
    if variant == "TABU":
        return "baseline"
    p = next(x for x in s["dailyPaired"] if x["variant"] == variant and x["budgetSeconds"] == budget)
    return f'{signed(p["meanSavingsCents"])} [{signed(p["exploratory95PercentInterval"][0])}, {signed(p["exploratory95PercentInterval"][1])}]'


def validate():
    s, manifest, validation = read("summary.json"), read("experiment-manifest.json"), read("validation.json")
    expected = {k: manifest["expectedCases"][k] for k in STAGES}
    assert s["executionComplete"] and s["expectedBookingCases"] == expected
    assert s["actualBookingCases"] == expected and sum(expected.values()) == 218
    assert sum(x["cases"] for x in s["booking"]) == 218
    assert s["dailyResults"] == manifest["expectedCases"]["daily"] == 240
    assert len(s["dailyPaired"]) == 21
    assert len(s["failures"]) == 1 and s["failures"][0]["file"] == "booking-shared-83-held.jsonl.gz"
    assert len(s["auditScopeExclusions"]) == 8
    assert all(c["result"] == "passed" for c in validation["checks"])
    assert set(x["variant"] for x in s["booking"]) == set(BOOKING)
    assert all(x["requests"] == x["cases"] * (3 if x["stage"] in ("original", "screen") else 10) for x in s["booking"])
    assert len(s["qualityOverTime"]) == 5 * len(s["booking"])
    assert all(x["visibleIncumbents"] <= x["searches"] for x in s["qualityOverTime"])
    assert {x["budgetSeconds"] for x in s["dailyPaired"]} == {15, 30, 60}
    assert all(x["independentSeeds"] == len(manifest["heldOutSeeds"]) == 2 for x in s["dailyPaired"])
    # Check every retained file and count original rows, including the failed audit.
    booking_counts, daily_counts, failures, exclusions = Counter(), Counter(), 0, 0
    booking_rows = defaultdict(list)
    daily_rows = []
    for item in s["rawFiles"]:
        path = DATA / item["file"]
        raw = path.read_bytes()
        assert sha256(raw).hexdigest() == item["sha256"], path
        if ".jsonl" not in path.name:
            continue
        content = gzip.decompress(raw) if path.suffix == ".gz" else raw
        assert sha256(content).hexdigest() == item["uncompressedSha256"], path
        provenance = None
        for line in content.decode("utf-8-sig").splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            if row.get("type") == "provenance":
                provenance = row
            if row.get("type") == "case":
                if "stress-normal-only" in path.name:
                    exclusions += 1
                else:
                    stage = next((x for x in STAGES if x in path.stem), "screen")
                    assert provenance is not None and type(provenance["seed"]) is int
                    row["seed"] = provenance["seed"]
                    booking_counts[stage] += 1
                    booking_rows[stage, row["variant"]].append(row)
            elif row.get("type") == "result":
                assert row["violations"] == 0 and row["after"]["overtimeMinutes"] == 0
                for field in ("paidWaitingBefore", "paidWaitingAfter", "changedAssignments", "retimedAppointments", "elapsedMs"):
                    numeric(row[field])
                numeric(row["after"]["fairness"]["variance"])
                daily_counts[row["variant"]] += 1
                match = re.search(r"(screen|held)-(15|30|60)", path.name)
                assert match, path
                daily_rows.append((match.group(1), int(match.group(2)), row))
            elif row.get("type") not in ("provenance", "configuration"):
                failures += 1
    assert dict(booking_counts) == expected, booking_counts
    for item in s["booking"]:
        rows = booking_rows[item["stage"], item["variant"]]
        assert len(rows) == item["cases"]
        assert sum(len(r["attempts"]) for r in rows) == item["requests"]
        assert sum(r["served"] for r in rows) == item["served"]
        assert sum(r["incomplete"] for r in rows) == item["incomplete"]
        assert sum(sum(day["policy"]["costCents"] for day in r["after"]["days"]) for r in rows) == item["finalCostCents"]
        assert isclose(mean(r["p95Ms"] for r in rows), item["meanCaseP95Ms"], rel_tol=1e-10)
    assert sum(daily_counts.values()) == 240 and set(daily_counts) == set(DAILY), daily_counts
    controls = {(stage, budget, r["seed"], r["technicians"], r["datasetFingerprint"]): r
                for stage, budget, r in daily_rows if r["variant"] == "TABU"}
    for item in s["dailyPaired"]:
        units = defaultdict(list)
        for stage, budget, r in daily_rows:
            if stage == "held" and budget == item["budgetSeconds"] and r["variant"] == item["variant"]:
                control = controls[stage, budget, r["seed"], r["technicians"], r["datasetFingerprint"]]
                units[r["seed"]].append(control["after"]["costCents"] - r["after"]["costCents"])
        assert len(units) == item["independentSeeds"] == 2
        assert isclose(mean(mean(v) for v in units.values()), item["meanSavingsCents"], rel_tol=1e-10, abs_tol=1e-8)
    assert failures == 1 and exclusions == 8
    return s, manifest, validation, daily_rows, booking_rows


ASSETS = ROOT / "docs/scheduler-algorithm-comparison-2026-09-29-assets"
IDENTITY = dict(zip(dict.fromkeys(BOOKING + DAILY), zip(
    ("#2864a0", "#db701b", "#319647", "#9a5d42", "#8252ac", "#707070", "#108c91", "#b6437c", "#b39812", "#15334a", "#6c78c7", "#d14442"),
    ("o", "s", "^", "D", "P", "v", "X", "<", ">", "*", "h", "8"))))


def chart(fig, name):
    path = ASSETS / (name + ".png")
    buf = BytesIO()
    fig.savefig(buf, format="png", dpi=180, bbox_inches="tight", facecolor="white")
    plt.close(fig)
    if "--check" in sys.argv:
        assert path.is_file(), f"Missing figure: {path}"
        assert path.read_bytes() == buf.getvalue(), f"Stale figure: {path}"
    else:
        ASSETS.mkdir(exist_ok=True)
        path.write_bytes(buf.getvalue())
    return path


def legend(fig, axes):
    handles, labels = [], []
    for axis in axes:
        h, l = axis.get_legend_handles_labels()
        for handle, label in zip(h, l):
            if label not in labels:
                handles.append(handle); labels.append(label)
    fig.legend(handles, labels, loc="lower center", ncol=min(5, len(labels)), bbox_to_anchor=(.5, -.05), fontsize=11)


def booking_chart(s):
    fig, ax = plt.subplots(1, 2, figsize=(9, 4.0))
    stages = ["original", "screen", "held", "stress", "browser"]
    for v in BOOKING:
        rows = {r["stage"]: r for r in s["booking"] if r["variant"] == v}
        xs = [i for i, st in enumerate(stages) if st in rows]
        ax[0].plot([x + (BOOKING.index(v)-2)*.06 for x in xs], [100 * rows[stages[i]]["served"] / rows[stages[i]]["requests"] for i in xs], linestyle="none", marker=IDENTITY[v][1], color=IDENTITY[v][0], label=v)
        ax[1].plot([x + (BOOKING.index(v)-2)*.06 for x in xs], [rows[stages[i]]["meanCaseP95Ms"] / 1000 for i in xs], linestyle="none", marker=IDENTITY[v][1], color=IDENTITY[v][0], label=v)
    for a in ax:
        a.set_xticks(range(5), stages, rotation=25)
        a.grid(alpha=.2)
    ax[0].set(ylabel="Served requests (%)", ylim=(0, 105), title="Demand served")
    ax[1].set(ylabel="Mean case p95 (seconds)", title="Harness search latency")
    ax[1].set_yscale("log")
    legend(fig, ax)
    fig.tight_layout(rect=(0, .08, 1, 1))
    return chart(fig, "booking-stages")


def quality_chart(s):
    fig, ax = plt.subplots(2, 2, figsize=(9, 5.6), sharex="col")
    for v in BOOKING:
        for stage, col in (("screen", 0), ("held", 1)):
            rows = sorted((r for r in s["qualityOverTime"] if r["variant"] == v and r["stage"] == stage), key=lambda r: r["seconds"])
            if rows:
                ax[0, col].plot([r["seconds"] for r in rows], [r["visibleIncumbents"] / r["searches"] * 100 for r in rows], marker=IDENTITY[v][1], color=IDENTITY[v][0], label=v)
                ax[1, col].plot([r["seconds"] for r in rows], [None if r["meanVisibleCostDeltaCents"] is None else r["meanVisibleCostDeltaCents"] / 100 for r in rows], marker=IDENTITY[v][1], color=IDENTITY[v][0], label=v)
    for col, title in enumerate(("Screen, 18 searches per variant", "Held out, 480 searches per variant")):
        ax[0, col].set(title=title, ylabel="Visible incumbent (%)", ylim=(0, 105))
        ax[1, col].set(xlabel="Elapsed seconds including queue", ylabel="Mean visible cost ($)")
        for a in ax[:, col]:
            a.set_xticks([1, 5, 15, 30, 60]); a.grid(alpha=.2)
    legend(fig, ax[0, :])
    fig.tight_layout(rect=(0, .08, 1, 1))
    return chart(fig, "booking-quality")


def daily_chart(s, raw_daily):
    fig, ax = plt.subplots(figsize=(9, 4.3))
    matrix = [[0 if v == "TABU" else next(p["meanSavingsCents"] / 100 for p in s["dailyPaired"]
               if p["variant"] == v and p["budgetSeconds"] == b) for b in (15, 30, 60)] for v in DAILY]
    limit = max(abs(x) for row in matrix for x in row)
    ax.imshow(matrix, cmap="RdBu", vmin=-limit, vmax=limit, aspect="auto")
    for i, row in enumerate(matrix):
        for j, value in enumerate(row):
            ax.text(j, i, f"{value:+.2f}" if DAILY[i] != "TABU" else "baseline", ha="center", va="center",
                    color="white" if abs(value) > limit * .6 else "black", fontsize=11)
    ax.set_yticks(range(len(DAILY)), DAILY, fontsize=10)
    ax.set_xticks(range(3), ["15 seconds", "30 seconds", "60 seconds"])
    ax.set_title("Paired savings vs TABU ($ per case); positive favors candidate")
    fig.tight_layout()
    return chart(fig, "daily-savings")


def fleet_chart(pairs):
    fig, axes = plt.subplots(1, 3, figsize=(9, 3.8), sharey=True)
    for ax, budget in zip(axes, (15, 30, 60)):
        for variant in ("CURRENT_UNCAPPED", "KOPT", "LATE_ACCEPTANCE"):
            values = [mean(p["savings"] / 100 for p in pairs if p["budget"] == budget and
                           p["variant"] == variant and p["fleet"] == size) for size in (5, 10, 20, 50)]
            ax.plot(range(4), values, color=IDENTITY[variant][0], marker=IDENTITY[variant][1], label=variant)
        ax.axhline(0, color="black", linewidth=.6)
        ax.set_xticks(range(4), (5, 10, 20, 50))
        ax.set(title=f"{budget} second budget", xlabel="Technicians (2 seeds each)")
        ax.grid(alpha=.2)
    axes[0].set_ylabel("Paired savings vs TABU ($ / case)")
    legend(fig, axes)
    fig.tight_layout(rect=(0, .14, 1, 1))
    return chart(fig, "daily-fleet")


def styles():
    base = getSampleStyleSheet()
    base.add(ParagraphStyle(name="TitleX", parent=base["Title"], fontName="Helvetica-Bold", fontSize=19, leading=23, textColor=colors.HexColor(NAVY), spaceAfter=10))
    base.add(ParagraphStyle(name="H1X", parent=base["Heading1"], fontSize=12, leading=15, textColor=colors.HexColor(NAVY), spaceBefore=11, spaceAfter=5))
    base.add(ParagraphStyle(name="H2X", parent=base["Heading2"], fontSize=10, leading=13, textColor=colors.HexColor(TEAL), spaceBefore=8, spaceAfter=3))
    base.add(ParagraphStyle(name="BodyX", parent=base["BodyText"], fontSize=10.5, leading=14, spaceAfter=5))
    base.add(ParagraphStyle(name="SmallX", parent=base["BodyText"], fontSize=9, leading=12, spaceAfter=3))
    base.add(ParagraphStyle(name="CellX", parent=base["BodyText"], fontSize=9, leading=11.5))
    base.add(ParagraphStyle(name="HeadX", parent=base["CellX"], textColor=colors.white, fontName="Helvetica-Bold"))
    return base


def para(text, style="BodyX"):
    return Paragraph(text, ST[style])


def tbl(headers, rows, widths=None):
    cells = [[para(str(x), "HeadX") for x in headers]] + [[para(str(x), "CellX") for x in row] for row in rows]
    t = Table(cells, colWidths=widths, repeatRows=1, hAlign="LEFT")
    t.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, 0), colors.HexColor(NAVY)),
                           ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
                           ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.whitesmoke, colors.white]),
                           ("GRID", (0, 0), (-1, -1), .25, colors.HexColor("#d2dce2")),
                           ("VALIGN", (0, 0), (-1, -1), "TOP"),
                           ("LEFTPADDING", (0, 0), (-1, -1), 5), ("RIGHTPADDING", (0, 0), (-1, -1), 5),
                           ("TOPPADDING", (0, 0), (-1, -1), 2.5), ("BOTTOMPADDING", (0, 0), (-1, -1), 2.5)]))
    return t


def footer(canvas, doc):
    canvas.setFont("Helvetica", 7)
    canvas.setFillColor(colors.HexColor("#667786"))
    canvas.drawString(36, 28, "WaterFlex | retained modeled field validation | 29 Sep 2026")
    canvas.drawRightString(576, 28, str(doc.page))


def numeric(value):
    if type(value) not in (int, float) or not isfinite(value):
        raise ValueError(f"Required finite number, got {value!r}")
    return value


def percentile(values, fraction=.95):
    if not values:
        raise ValueError("Latency sample is unavailable")
    ordered = sorted(numeric(v) for v in values)
    return ordered[ceil(len(ordered) * fraction) - 1]


def booking_metrics(rows):
    if not rows:
        raise ValueError("Unsupported booking comparison")
    attempts = [a for r in rows for a in r["attempts"]]
    for a in attempts:
        if type(a["served"]) is not bool or a["completed"] is not None and type(a["completed"]) is not bool:
            raise ValueError("Invalid service or completion flag")
        numeric(a["elapsedMs"])
    for r in rows:
        assert r["served"] == sum(a["served"] for a in r["attempts"])
        assert r["incomplete"] == sum(a["completed"] is False for a in r["attempts"])
        assert r["unknownSearchCompletion"] == sum(a["completed"] is None for a in r["attempts"])
    served = sum(a["served"] for a in attempts)
    return dict(n=len(attempts), served=served, unsuccessful=len(attempts)-served,
                rate=100 * served / len(attempts), incomplete=sum(a["completed"] is False for a in attempts),
                overlap=sum(a["served"] and a["completed"] is False for a in attempts),
                unknown=sum(a["completed"] is None for a in attempts),
                case_p95=mean(numeric(r["p95Ms"]) for r in rows)/1000,
                median=median(a["elapsedMs"] for a in attempts)/1000,
                pooled_p95=percentile([a["elapsedMs"] for a in attempts])/1000)


def daily_pairs(raw_daily):
    controls = {(b, r["seed"], r["technicians"], r["datasetFingerprint"]): r
                for st, b, r in raw_daily if st == "held" and r["variant"] == "TABU"}
    pairs = []
    assert all(r["workload"] == "CLUSTERED" for _, _, r in raw_daily)
    for st, b, r in raw_daily:
        if st != "held":
            continue
        control = controls[b, r["seed"], r["technicians"], r["datasetFingerprint"]]
        assert r["before"] == control["before"], "Paired starting schedules differ"
        pairs.append(dict(variant=r["variant"], budget=b, seed=r["seed"], fleet=r["technicians"],
                          savings=numeric(control["after"]["costCents"])-numeric(r["after"]["costCents"])))
    return pairs


def matched_booking(s, bookings):
    def key(r):
        return tuple(r[k] for k in ("seed", "size", "workload", "concurrency", "cache", "datasetFingerprint"))
    def ids(r):
        return tuple(a["index"] for a in r["attempts"] if a["served"])
    def cost(r):
        return sum(numeric(d["policy"]["costCents"]) for d in r["after"]["days"])
    for p in s["paired"]:
        controls = {key(r): r for r in bookings.get((p["stage"], "INSERTION"), [])}
        units = defaultdict(list)
        matched = changed = delta = 0
        for r in bookings.get((p["stage"], p["variant"]), []):
            control = controls[key(r)]
            delta += r["served"] - control["served"]
            if ids(r) != ids(control):
                changed += 1
                continue
            matched += 1
            units[None if r["workload"] == "CLUSTERED" else r["seed"], r["workload"]].append(cost(control)-cost(r))
        assert (matched, changed, delta, len(units)) == (p["equalCustomerCases"], p["differentCustomerCases"], p["servedDelta"], p["streamUnits"])
        if not units:
            assert p["meanSavingsCents"] is None
        else:
            assert isclose(mean(mean(v) for v in units.values()), numeric(p["meanSavingsCents"]), abs_tol=1e-8)


# Both renderers consume one sequence, including every numeric narrative claim.
def report_blocks(s, manifest, raw_daily, bookings):
    matched_booking(s, bookings)
    pairs = daily_pairs(raw_daily)
    held_variants = ("INSERTION", "BOUNDED", "SHARED")
    held = {v: booking_metrics(bookings["held", v]) for v in held_variants}
    blocks = []
    def heading(title, page=False):
        if page:
            blocks.append(("break",))
        blocks.append(("heading", title))
    def paragraph(text):
        blocks.append(("paragraph", text))
    def table(headers, rows, widths=None):
        blocks.append(("table", headers, rows, widths))
    def figure(path, caption):
        blocks.append(("figure", path, caption))
    def savings(v, b, **filters):
        values = [p["savings"] for p in pairs if p["variant"] == v and p["budget"] == b and all(p[k] == x for k, x in filters.items())]
        if not values:
            raise ValueError("Unsupported daily comparison")
        return mean(values)
    heading("Decision summary")
    paragraph("Retain the current booking selection and daily TABU default. Prioritize BOUNDED booking and CURRENT_UNCAPPED daily follow-up, investigate SHARED reliability, and evaluate KOPT by fleet size and available time. These modeled fixtures do not establish production savings.")
    paragraph(f"BOUNDED confirmed {held['BOUNDED']['served']}/{held['BOUNDED']['n']} held-out requests versus INSERTION's {held['INSERTION']['served']}/{held['INSERTION']['n']}, a {held['BOUNDED']['rate']-held['INSERTION']['rate']:.2f} percentage-point gain. Its service advantage depends on concurrency. CURRENT_UNCAPPED has positive paired mean savings at every tested daily budget. KOPT's shorter-budget losses are concentrated in the largest fleet.")
    paragraph("Coverage is the main limit: all 240 daily solver cases use CLUSTERED workloads. Only two held-out seeds (59 and 83) provide independent seed replication; they do not establish geographic diversity. Held-out booking uses dispersed GraphHopper 11 roads, but that coverage must not be attributed to daily solvers.")
    heading("Experiment overview")
    paragraph("Booking chooses where to admit a new request while preserving confirmed customer arrival windows. Daily optimization rearranges already accepted work without admitting demand. Internal planned arrival times and technician assignments may change while the customer promise remains valid. A completion flag describes search work, not whether a request was served or whether all possible schedules are infeasible.")
    table(["Experiment", "Retained scope", "Comparison limit"], [
        ["Booking original", "12 cases; earlier policy", "Bundled policy/lifecycle changes; not isolated algorithm evidence"],
        ["Booking screen", "30 cases; clustered; 3 requests/case", "Repeated geometry; one independent stream"],
        ["Booking held out", "144 cases; 2 seeds; 5/10/20/50 technicians; concurrency 1/5/10; cold/warm", "48 cases and 480 requests per tested variant"],
        ["Booking stress / browser", "8 / 24 cases; 10 requests/case", "Stress: INSERTION/BOUNDED; browser: INSERTION only"],
        ["Daily", "240 clustered cases: 48 screen + 192 held out", "8 variants; held: 2 seeds x 4 fleets x 3 budgets"]], [102, 216, 222])
    heading("Mechanisms in plain language", True)
    table(["Term", "What the search does"], [
        ["Insertion", "Places a new visit among existing stops without rearranging those stops."],
        ["Relocation / swap", "Moves one visit to another position or route / exchanges two visits."],
        ["Reversal / sublist", "Reverses an ordered stretch of visits / moves a stretch together."],
        ["Beam search", "Retains a limited number of promising arrangements at each depth."],
        ["Tabu", "Temporarily discourages revisiting recently changed entities to escape local traps."],
        ["Late acceptance", "May accept a move if it beats an older solution, even when it worsens the current one."],
        ["K-opt", "Reconnects several route links to explore coordinated order changes."],
        ["Ruin/recreate", "Removes related visits, then rebuilds their placement to explore a larger change."]], [115, 425])
    paragraph("Modeled operating cost includes paid time and mileage. Retained rates are $30/hour regular labor, $45/hour overtime labor (new overtime prohibited), and $0.67/mile. Travel buffers are 20% plus five minutes per leg. Waiting and buffers can change cost even with little or no road travel. These rates and fixture routes are assumptions, not measured payroll savings.")
    heading("Booking configuration differences")
    table(["Variant", "Configuration and comparison"], [
        ["INSERTION", "Policy-only control; eligible routes and insertion positions."],
        ["BOUNDED", "Relocation/swap/reversal: 6 routes, depth 2, beam 8, 500 arrangements/window. Adds a search family, not one isolated move."],
        ["EXPANDED", "12 routes, depth 3, beam 16, 2,000 arrangements/window and ranked moves. Multiple settings change together."],
        ["RUIN_RECREATE", "EXPANDED plus complete reconstruction of removed related visits."],
        ["SHARED", "Same reconstruction search with shared route evaluation across windows. The intended evaluation-sharing comparison is against RUIN_RECREATE in the screen."]], [112, 428])
    heading("Booking comparison", True)
    figure(booking_chart(s), "Figure 1. Separate categorical stages, with no connecting trend lines. Service counts confirmed requests; latency is the arithmetic mean of each case's p95, not an overall p95. Missing variants are untested. Original policy, stress, and browser populations differ.")
    table(["Held variant", "Served / rate", "Unsuccessful", "Incomplete", "Served AND incomplete", "Unknown completion"],
          [[v, f"{m['served']}/{m['n']} ({m['rate']:.1f}%)", m['unsuccessful'], m['incomplete'], m['overlap'], m['unknown']] for v, m in held.items()], [105, 111, 80, 80, 84, 80])
    paragraph(f"Service and search completion overlap: {held['SHARED']['overlap']} SHARED requests were served despite incomplete search. Unsuccessful means no confirmed service in this attempt; it does not prove infeasibility. Completion is reported separately, so incomplete counts must not be added to unsuccessful counts. SHARED's original final audit returned HTTP 409; its recovery run is separate.")
    heading("Illustrative fixture evidence: rearrangement admits C")
    fixture=read('field-reassignment-240-BOUNDED.json')
    assert fixture['source']=='REARRANGEMENT' and fixture['overtimeMinutes']==0
    table(["Technician","Before C","After C"],[[tech,' > '.join(fixture['before'][tech]),' > '.join(fixture['after'][tech])] for tech in ('omaha','nearby')],[140,200,200])
    table(["Visit","Promised arrival window (UTC)","Planned arrival (UTC)"],[[v['id'],f"{v['start'][11:16]} to {v['end'][11:16]} (end exclusive)",v['planned'][11:16]] for v in fixture['visits']],[60,280,200])
    paragraph(f"In reassignment-240-BOUNDED, A moves to the nearby technician and B to Omaha, allowing new request C to be served. Retained arrival plans remain inside the existing promises. The resulting plan has {fixture['drivingMinutes']} driving minutes, {fixture['overtimeMinutes']} overtime minutes and {money(fixture['costCents'])} modeled cost. The no-new-request oracle is {money(fixture['noNewRequestOracleCostCents'])}; the difference includes serving C and is not a savings estimate. This constructed fixture illustrates the mechanism, not its frequency in operations.")
    heading("Held-out service by operating condition", True)
    paragraph("Each breakdown pools the other tested dimensions within the same held-out population. It describes marginal outcomes, not an isolated causal effect. Percentage-point differences use INSERTION within the same group; all group denominators are equal across the three variants.")
    for dimension, title in (("concurrency", "Concurrency"), ("size", "Fleet size"), ("cache", "Cache condition")):
        heading(title)
        rows=[]
        for value in sorted({r[dimension] for r in bookings['held','INSERTION']}):
            baseline=booking_metrics([r for r in bookings['held','INSERTION'] if r[dimension]==value])
            for v in held_variants:
                m=booking_metrics([r for r in bookings['held',v] if r[dimension]==value])
                assert m['n']==baseline['n']
                rows.append([value,v,f"{m['served']}/{m['n']}",f"{m['rate']:.2f}%", "reference" if v=='INSERTION' else f"{m['rate']-baseline['rate']:+.2f}"])
        table([title,"Variant","Served","Rate","Delta pp"], rows, [84,135,107,107,107])
    sequential={v:booking_metrics([r for r in bookings['held',v] if r['concurrency']==1]) for v in held_variants}
    paragraph(f"At concurrency 1, BOUNDED serves {sequential['BOUNDED']['served']}/{sequential['BOUNDED']['n']} versus INSERTION's {sequential['INSERTION']['served']}/{sequential['INSERTION']['n']}. BOUNDED serves more at concurrency 5 and 10. Its aggregate advantage does not mean it wins in every operating condition.")
    heading("Latency and matched-customer cost", True)
    paragraph("Request latency is the harness elapsedMs for the search, including queueing and polling, excluding selectionElapsedMs, address entry and geocoding. Request median and pooled p95 use all attempts, including unsuccessful ones. Pooled p95 uses nearest rank, ceil(0.95 x N). Mean case p95 retains the original statistic, giving each case equal weight; it is not the p95 of the pooled requests.")
    latency=[]
    for st in STAGES:
        for v in BOOKING:
            if (st,v) not in bookings:continue
            m=booking_metrics(bookings[st,v])
            latency.append([st,v,m['n'],f"{m['case_p95']:.2f}",f"{m['median']:.2f}",f"{m['pooled_p95']:.2f}"])
    table(["Stage","Variant","Requests","Mean case p95 s","Request median s","Pooled p95 s"],latency,[66,112,68,98,98,98])
    paragraph("Cost pairing requires identical served request indices on the same seed, fleet, workload, concurrency, cache condition and dataset fingerprint. First average matched case savings within a stream, then average streams equally. Clustered screen cases collapse to one stream; dispersed held-out streams are seed units. Do not extrapolate these subset estimates to all requests or production.")
    cost_rows=[]
    for p in s['paired']:
        total=p['equalCustomerCases']+p['differentCustomerCases']
        cost_rows.append([p['stage'],p['variant'],f"{p['equalCustomerCases']}/{total}" if total else 'untested',p['streamUnits'] if total else 'untested',money(p['meanSavingsCents']) if p['meanSavingsCents'] is not None else 'unavailable'])
    table(["Stage","Vs INSERTION","Matched / tested cases","Stream units","Mean savings / stream"],cost_rows,[66,125,115,100,134])
    heading("Search quality and booking implications", True)
    figure(quality_chart(s), "Figure 2. Fixed variant colors and markers across both stages, including SHARED in purple. Upper panels: fraction with a visible option by each checkpoint. Lower panels: incremental cost among visible options only; missing options are excluded, not zero. Visibility does not guarantee final confirmation.")
    paragraph("Checkpoint time includes queueing and polling. Visible incremental cost is the option's modeled cost delta, not final total inventory cost. The lower panels have changing and variant-specific denominators, so they cannot rank all-request savings.")
    implications={
        'INSERTION': 'Fast control and the only retained browser variant. Lower service than BOUNDED overall; retain as the current selection while testing changes.',
        'BOUNDED': 'Best aggregate held-out service, but slower and not better at sequential service. Prioritize replicated concurrent and sequential tests together.',
        'EXPANDED': 'Screen-only broader beam and ranked moves. Higher matched cost savings than BOUNDED with substantially longer search; no held-out service claim is supported.',
        'RUIN_RECREATE': 'Screen-only reconstruction improves matched cost further. Its runtime and limited geography require a broader test before promotion.',
        'SHARED': 'Matched cost savings coexist with lower service, long latency and a failed audit. Diagnose reliability before promotion.'}
    for v in BOOKING:
        stage='held' if v in held_variants else 'screen'
        m=booking_metrics(bookings[stage,v]); p=next(p for p in s['paired'] if p['stage']==stage and p['variant']==v) if v!='INSERTION' else None
        cost='Cost reference.' if p is None else f"Matched savings {money(p['meanSavingsCents'])}/stream ({p['equalCustomerCases']}/{p['equalCustomerCases']+p['differentCustomerCases']} cases)."
        paragraph(f"{v}: {stage} service {m['served']}/{m['n']}; mean case p95 {m['case_p95']:.1f} s. {cost} {implications[v]}")
    heading("Daily-solver comparison", True)
    paragraph("All daily results report zero violations. Held-out comparisons pair the same dataset fingerprint, seed, fleet and budget, with identical starting costs. Accepted cost is measured after the policy's reference and fairness phases. Lower accepted cost means greater cleanup from that same start; different starting costs do not explain rankings here.")
    table(["Configuration","What changes"],[
        ['CURRENT_CAPPED','Relocation/swap; 100 selected and accepted; 1,000-step cap.'],
        ['CURRENT_UNCAPPED','Same moves/counts, without cap: isolates additional work.'],
        ['LATE_ACCEPTANCE_CHANGE','Relocation only; history 400; 10,000 selected, 1 accepted. Changes acceptor and counts versus CURRENT.'],
        ['LATE_ACCEPTANCE','Adds swap to CHANGE; same acceptor/counts.'],
        ['TABU','Relocation/swap; entity tabu 7; 10,000 selected, 1,000 accepted. Changes multiple settings versus late acceptance.'],
        ['SUBLIST','Late acceptance plus sublist movement/reversal.'],
        ['KOPT','SUBLIST plus coordinated k-opt list moves.'],
        ['RUIN_RECREATE','KOPT plus list ruin/recreate.']],[150,390])
    figure(daily_chart(s,raw_daily),"Figure 3. Held-out paired mean savings in dollars/case; 8 matched cases per cell (2 seeds x 4 fleet sizes). Blue is positive, red negative. Heatmap colors encode savings, not algorithm identity. Each seed averages its four fleets, then seeds receive equal weight.")
    heading("Fleet size changes the daily conclusion", True)
    figure(fleet_chart(pairs),"Figure 4. Selected follow-up candidates by fleet and budget; each point averages 2 matched seed cases. TABU is the zero reference. Equal spacing is categorical by fleet size. Full variant results follow in the appendix.")
    for b in (15,30):
        small=[p for p in pairs if p['variant']=='KOPT' and p['budget']==b and p['fleet']<50]
        large=[p for p in pairs if p['variant']=='KOPT' and p['budget']==b and p['fleet']==50]
        paragraph(f"At {b} seconds, KOPT beats TABU in {sum(p['savings']>0 for p in small)}/{len(small)} smaller-fleet cases (5/10/20 technicians). It loses in {sum(p['savings']<0 for p in large)}/{len(large)} 50-technician cases; their mean {money(mean(p['savings'] for p in large))} paired savings drives the overall {money(savings('KOPT',b))} mean.")
    paragraph(f"LATE_ACCEPTANCE already has a small positive mean at 30 seconds: {money(savings('LATE_ACCEPTANCE',30))}/case, rising to {money(savings('LATE_ACCEPTANCE',60))} at 60 seconds. KOPT's largest overall advantage occurs at 60 seconds, but the shorter-budget smaller-fleet wins justify stratified follow-up.")
    heading("Individual seeds and paired wins", True)
    paragraph("Positive savings means the candidate's accepted cost is lower than TABU. Wins/ties/losses compare exact integer cents, with no tolerance. Each seed column averages four fleets, and W/T/L counts all eight paired cases per budget. Two seeds are insufficient for a broad population claim.")
    for b in (15,30,60):
        heading(f"{b}-second budget")
        table(['Variant','Seed 59 $','Seed 83 $','Mean $','W/T/L (n=8)'],[[v,signed(savings(v,b,seed=59)),signed(savings(v,b,seed=83)),signed(savings(v,b)), '/'.join(str(sum(test(p['savings']) for p in pairs if p['variant']==v and p['budget']==b)) for test in (lambda x:x>0,lambda x:x==0,lambda x:x<0))] for v in DAILY],[160,90,90,90,110])
    heading("Daily implications", True)
    paragraph("All daily implications below are limited to two clustered held-out seeds. Each estimate has 8/8 matched cases per budget; mean gains can conceal seed and fleet differences.")
    daily_notes={
        'CURRENT_CAPPED':'Speed reference: about three seconds regardless of allowance. Lower cost quality than TABU at all budgets; useful when little time is available.',
        'CURRENT_UNCAPPED':'Positive paired mean at every budget. Prioritize replication across dispersed and operational workloads; it consumes the full allowance.',
        'LATE_ACCEPTANCE_CHANGE':'Relocation-only trajectory approaches TABU with more time but remains behind at each tested budget. No promotion signal.',
        'LATE_ACCEPTANCE':'Adding swap improves on change-only at every budget; positive means at 30 and 60 seconds. Verify that advantage with broader workloads.',
        'TABU':'Retained production reference. Additional mean cleanup is flat from 30 to 60 seconds here; retain until broader evidence supports changing it.',
        'SUBLIST':'Positive at 60 seconds after shorter-budget losses. Longer-budget candidate; combined moves do not isolate an individual move effect.',
        'KOPT':'Largest overall 60-second mean advantage. Shorter-budget wins in smaller fleets are hidden by 50-technician losses; evaluate fleet and budget jointly.',
        'RUIN_RECREATE':'Improves its own starting schedule but remains behind TABU. Larger neighborhood search does not repay its cost in this matrix.'}
    for v in DAILY:
        paragraph(f"{v}: paired savings at 15/30/60 seconds are " + ', '.join(money(savings(v,b)) for b in (15,30,60)) + f" per case (8/8 matched at each budget). {daily_notes[v]}")
    paragraph("Historical context: the separate September 24 comparison retained 294 accepted cases across seven workload families and three fleet sizes. All eight variants ran at seed 17; capped, uncapped and TABU repeated at seeds 23 and 41. TABU had lower aggregate accepted cost than both retained alternatives in every seed, supporting the default. Its configurations, policy and populations differ from September 29; do not pool the experiments. See the linked daily solver protocol for exclusions and original measurements.")
    heading("Recommendations and follow-up", True)
    table(['Priority / question','Additional experiment','Decision informed'],[
        ['1. BOUNDED service tradeoff','More independent dispersed streams, balanced across concurrency, fleet and cache; record confirmation failures and completion separately.','Whether its aggregate service gain survives the sequential shortfall and latency cost.'],
        ['1. CURRENT_UNCAPPED generality','Matched TABU comparisons across dispersed, tight-window, skill and near-capacity work, retaining cost and fairness acceptance.','Whether to change the daily default.'],
        ['2. SHARED reliability','Reproduce the retained HTTP 409 audit with complete client/server timing; trace served/incomplete overlap and final revalidation.','Whether the candidate is reliable enough for broader quality tests.'],
        ['2. KOPT fleet/budget interaction','Replicated 5/10/20/50-technician comparisons at 15/30/60 seconds; preserve paired fingerprints.','Whether fleet-specific or longer-budget use is justified.'],
        ['3. Longer-budget alternatives','Replicate LATE_ACCEPTANCE and SUBLIST beside KOPT and TABU on the same schedules.','Whether their cost/fairness/runtime tradeoffs merit promotion.'],
        ['3. Expanded booking coverage','Test EXPANDED and RUIN_RECREATE on dispersed concurrent demand after reliability priorities.','Whether screen-only cost gains survive realistic service constraints.']],[140,240,160])
    paragraph("No new benchmark runs, API changes or production configuration changes are part of this report revision. The recommendation remains to retain defaults pending the follow-up evidence.")
    heading("Technical appendix: metric glossary", True)
    table(['Metric','Definition and denominator'],[
        ['Operating cost','Modeled paid labor plus mileage under retained rates. Includes existing workload; final totals are not automatically savings.'],
        ['Incremental cost','Visible option cost delta for adding a request; mean includes only searches with visible options at that checkpoint.'],
        ['Paired booking savings','INSERTION final cost minus candidate final cost for identical served customers. Average within stream, then across streams; matched coverage shown beside estimate.'],
        ['Paired daily savings','TABU accepted cost minus candidate accepted cost on the same case/budget. Average fleets within seed then seeds equally.'],
        ['Served / unsuccessful','Confirmed request / attempt without confirmed service. Exhaustive and mutually exclusive for service only.'],
        ['Incomplete / unknown','Explicit false / unavailable search-completion flag. Independent of service; never interpreted as proven infeasibility.'],
        ['Visible option','At least one non-null cost incumbent by checkpoint. May fail final revalidation.'],
        ['Mean case p95','Arithmetic mean of retained p95Ms for cases in a stage/variant. Not an overall request percentile.'],
        ['Request median / pooled p95','Median / nearest-rank 95th percentile of all attempt elapsedMs within the stated group.'],
        ['Fairness / waiting','Capacity-weighted utilization variance (lower is more balanced) / paid waiting minutes.'],
        ['Schedule change','Changed technician assignments and retimed appointments, reported separately. Customer arrival promises remain constraints.'],
        ['Untested / unavailable / zero','No experiment / metric cannot be estimated / measured numeric zero. These are not interchangeable.']],[140,400])
    heading("Technical appendix: complete daily fleet comparison", True)
    paragraph("Each cell is mean paired savings in dollars/case against TABU for exactly 2/2 matched seed cases at that fleet and budget. This appendix includes every variant, including the zero-cost reference.")
    for b in (15,30,60):
        heading(f"{b} seconds: savings by technician count")
        table(['Variant','5','10','20','50'],[[v]+[signed(savings(v,b,fleet=f)) for f in (5,10,20,50)] for v in DAILY],[160,95,95,95,95])
    heading("Technical appendix: accepted schedule diagnostics", True)
    paragraph("Held-out arithmetic means over eight cases per variant/budget. Cost change is accepted after minus before (negative means cleanup). Variance is accepted capacity-weighted utilization variance; waiting is paid minutes after acceptance. Assignment and timing changes count affected appointments. All 240 retained daily cases report zero violations.")
    for b in (15,30,60):
        heading(f"{b}-second diagnostics")
        rows=[]
        for v in DAILY:
            rs=[r for st,budget,r in raw_daily if st=='held' and budget==b and r['variant']==v]
            rows.append([v,money(mean(r['after']['costCents']-r['before']['costCents'] for r in rs)),f"{mean(r['elapsedMs'] for r in rs)/1000:.2f}",f"{mean(r['after']['fairness']['variance'] for r in rs):.6f}",f"{mean(r['paidWaitingAfter'] for r in rs):.2f}",f"{mean(r['changedAssignments'] for r in rs):.2f}",f"{mean(r['retimedAppointments'] for r in rs):.2f}"])
        table(['Variant','Cost change','Elapsed s','Variance','Waiting min','Assigned','Retimed'],rows,[160,70,55,70,65,60,60])
    heading("Technical appendix: exploratory intervals", True)
    paragraph("Percentile bootstrap intervals resample only two independent held-out seeds after averaging fleets (daily) or matched cases (booking). They are exploratory, not reliable population confidence intervals. Screen booking has one stream, so no interval is available. Individual seed values in the main report should receive greater weight than these intervals.")
    table(['Daily variant','15 s: mean [interval] $','30 s: mean [interval] $','60 s: mean [interval] $'],[[v]+[interval_cell(s,v,b) for b in (15,30,60)] for v in DAILY],[150,130,130,130])
    table(['Booking stage / variant','Matched / tested','Mean / interval per stream'],[[p['stage']+' / '+p['variant'],f"{p['equalCustomerCases']}/{p['equalCustomerCases']+p['differentCustomerCases']}",money(p['meanSavingsCents'])+' / '+('unavailable: one stream' if p['exploratory95PercentInterval'] is None else ' to '.join(money(x) for x in p['exploratory95PercentInterval']))] for p in s['paired'] if p['meanSavingsCents'] is not None],[180,110,250])
    heading("Technical appendix: evidence and reproducibility", True)
    paragraph("The inventory retains 218 validated booking cases and 240 daily solver cases. Every inventoried raw file is checked by SHA-256; compressed JSONL content is also checked by uncompressed SHA-256. The original failed SHARED seed-83 audit is retained and excluded from successful cases, with its recovery separately labeled. Eight original stress-normal-only audits omitted overflow dates and remain excluded; full-date reruns supply the validated stress set. No inclusion rules have changed.")
    paragraph("Failure detail: the original SHARED seed-83, 50-technician, concurrency-five cold run returned HTTP 409 at final audit. Detailed client timing and reason were not retained. Later audit evidence does not erase the failure. Frozen artifacts ran concurrently on one Windows workstation, limiting timing generalization. Browser coverage is INSERTION only and does not establish production concurrency performance.")
    paragraph("Reproduce: install reportlab, matplotlib and pymupdf for Python 3. Run py -3 infra/build-scheduler-algorithm-comparison.py to regenerate both formats and shared PNG figures. Run it with --check to reconcile raw evidence and verify report content, images and links. Run py -3 infra/test_scheduler_algorithm_comparison.py for targeted regression checks. Raw evidence remains immutable.")
    blocks.append(('links',[
        ('Retained summary','evidence/scheduler-field-2026-09-29/summary.json'),
        ('Experiment manifest','evidence/scheduler-field-2026-09-29/experiment-manifest.json'),
        ('Validation records','evidence/scheduler-field-2026-09-29/validation.json'),
        ('Illustrative fixture','evidence/scheduler-field-2026-09-29/field-reassignment-240-BOUNDED.json'),
        ('Full field validation','scheduler-field-validation.md'),
        ('September 24 daily solver protocol','solver-benchmarks.md')]))
    return blocks


def markdown(blocks):
    lines=['# Scheduler algorithm comparison | 29 September 2026','']
    for block in blocks:
        kind=block[0]
        if kind=='heading': lines.extend(['## '+block[1],''])
        elif kind=='paragraph': lines.extend([block[1],''])
        elif kind=='table':
            lines.extend(['| '+' | '.join(block[1])+' |','| '+' | '.join(['---']*len(block[1]))+' |'])
            lines.extend('| '+' | '.join(str(c) for c in row)+' |' for row in block[2]);lines.append('')
        elif kind=='figure':lines.extend([f'![{block[2]}]({block[1].relative_to(MD.parent).as_posix()})','',block[2],''])
        elif kind=='links':lines.extend([f'- [{title}]({url})' for title,url in block[1]]+[''])
    return '\n'.join(lines)


def build(blocks):
    global ST
    ST=styles()
    story=[para('Scheduler algorithm comparison','TitleX'),para('29 September 2026 | Decision brief and technical evidence')]
    for block in blocks:
        kind=block[0]
        if kind=='break':story.append(PageBreak())
        elif kind=='heading':story.append(para(escape(block[1]),'H1X'))
        elif kind=='paragraph':story.append(para(escape(block[1])))
        elif kind=='table':story.append(tbl(block[1],[[escape(str(c)) for c in row] for row in block[2]],block[3]))
        elif kind=='figure':
            img=Image(str(block[1]))
            img.drawHeight=img.imageHeight*540/img.imageWidth
            img.drawWidth=540
            story.append(KeepTogether([img,para(escape(block[2]),'SmallX')]))
        elif kind=='links':
            story.extend(para(f'<link href="{url}">{escape(title)}</link>','SmallX') for title,url in block[1])
    doc=SimpleDocTemplate(str(PDF),pagesize=(612,792),leftMargin=36,rightMargin=36,topMargin=38,bottomMargin=43,
                          title='Scheduler algorithm comparison | September 29, 2026',author='WaterFlex',invariant=1)
    doc.build(story,onFirstPage=footer,onLaterPages=footer)
    MD.write_text(markdown(blocks),encoding='utf-8',newline='\n')


def check_outputs(blocks):
    import pymupdf
    expected=markdown(blocks)
    assert MD.read_text(encoding='utf-8')==expected,'Generated Markdown is stale'
    assert '\u2014' not in expected
    for link in re.findall(r'\]\(([^)]+)\)',expected):
        assert (MD.parent/link).is_file(),link
    with pymupdf.open(PDF) as doc:
        text=' '.join(' '.join(page.get_text().split()) for page in doc)
        compact=''.join(text.split())
        for block in blocks:
            if block[0] in ('heading','paragraph'):
                assert ' '.join(block[1].split()) in text,block[1]
            elif block[0] == 'table':
                for row in block[2]:
                    assert ''.join(''.join(str(cell).split()) for cell in row) in compact, row
        embedded = {sha256(pymupdf.Pixmap(doc, item[0]).samples).hexdigest()
                    for page in doc for item in page.get_images()}
        for block in blocks:
            if block[0] == 'figure':
                source = pymupdf.Pixmap(str(block[1]))
                if source.alpha:
                    source = pymupdf.Pixmap(source, 0)
                assert sha256(source.samples).hexdigest() in embedded, block[1]
        assert all(page.get_images() or len(page.get_text())>100 for page in doc)
        for page in doc:
            for x0,y0,x1,y1,*_ in page.get_text('blocks'):
                assert 30<=x0<x1<=582 and 20<=y0<y1<=773, (page.number,(x0,y0,x1,y1))
        print(f'VALID: 218 booking cases, 240 daily cases, {len(doc)} PDF pages; hashes, pairs, content and links checked')


def main():
    s,manifest,_,raw_daily,bookings=validate()
    blocks=report_blocks(s,manifest,raw_daily,bookings)
    if '--check' not in sys.argv:
        build(blocks)
    check_outputs(blocks)


if __name__=='__main__':
    main()
