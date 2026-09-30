"""Build and validate the September 29 scheduler comparison from retained evidence.

Usage: py -3 infra/build-scheduler-algorithm-comparison.py [--check]
Requires reportlab, matplotlib, and pymupdf. --check validates inputs and outputs.
"""
from collections import Counter
from collections import defaultdict
from hashlib import sha256
from io import BytesIO
from pathlib import Path
import gzip
import json
import re
from statistics import mean
from math import isclose
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Patch
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import inch
from reportlab.platypus import SimpleDocTemplate, Paragraph, Table, TableStyle, Image, KeepTogether

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "docs/evidence/scheduler-field-2026-09-29"
PDF = ROOT / "docs/scheduler-algorithm-comparison-2026-09-29.pdf"
MD = ROOT / "docs/scheduler-algorithm-comparison-2026-09-29.md"
STAGES = ("original", "screen", "held", "stress", "browser")
BOOKING = ("INSERTION", "BOUNDED", "EXPANDED", "RUIN_RECREATE", "SHARED")
DAILY = ("CURRENT_CAPPED", "CURRENT_UNCAPPED", "LATE_ACCEPTANCE_CHANGE", "LATE_ACCEPTANCE", "TABU", "SUBLIST", "KOPT", "RUIN_RECREATE")
BOOKING_ANALYSIS = [
    ("INSERTION", "This is the policy-only search control: it tries eligible routes and insertion positions without rearranging existing visits. It is the quickest held-out option at 2.9 seconds mean case p95 and served 346 of 480 requests. Its 19.37 dollar visible incremental cost at 60 seconds is above BOUNDED's 16.94 dollars among visible searches. Those visible-cost means condition on visibility, while the matched-case table provides the fairer final cost comparison."),
    ("BOUNDED", "Relocation, swap, and reversal are limited to six routes, depth two, beam eight, and 500 arrangements per window. It served 360 of 480 held-out requests, 14 more than INSERTION, and yielded 23.44 dollars mean savings per matched stream. That result comes with 15.7 seconds mean case p95 and 120 incomplete searches. Only 15 of 48 held-out cases served identical customer sets, so its cost result describes a narrow subset."),
    ("EXPANDED", "Wider route and beam limits and ranked moves add search depth. In the small clustered screen it served all 18 requests and saved 15 dollars per matched stream, compared with 12.50 dollars for BOUNDED. Its screen mean case p95 rose from BOUNDED's 3.4 seconds to 20.0 seconds. No held-out, stress, or browser test was retained, so a larger search budget has no demonstrated dispersed-service benefit here."),
    ("RUIN_RECREATE", "This adds complete reconstruction after removing related visits from EXPANDED's search. It saved 20 dollars per matched clustered stream, a further five dollars over EXPANDED, and served all 18 screen requests. Mean case p95 was 23.4 seconds. The reconstruction hypothesis is promising for cost but lacks dispersed and concurrent coverage; its screen result does not establish the same gain under field-like routing."),
    ("SHARED", "Shared route evaluation reduced screen mean case p95 to 19.5 seconds while matching RUIN_RECREATE's 20 dollar clustered savings. In held-out conditions, equal-customer pairs saved 31.36 dollars per stream, but total service fell to 334 of 480 and 153 searches were incomplete. Mean case p95 reached 64.1 seconds, and the original audit returned HTTP 409. Cost improvement on the matched subset is insufficient evidence for promotion while service and audit behavior remain unresolved."),
]
DAILY_ANALYSIS = [
    ("CURRENT_CAPPED", "Relocation and swap stop after the cap, producing about three seconds elapsed at every budget. This is the speed reference when a full solver budget is unavailable. Its paired cost was 1.18, 4.80, and 4.79 dollars worse than TABU at 15, 30, and 60 seconds."),
    ("CURRENT_UNCAPPED", "The same move family without the step cap consumed the full budget and improved paired cost against TABU by 6.38, 3.68, and 3.99 dollars. The capped comparison suggests that additional work can pay on these fixtures, while the two held-out seeds leave its generality open."),
    ("LATE_ACCEPTANCE_CHANGE", "Relocation-only late acceptance improved as time increased, from 21.85 dollars worse than TABU at 15 seconds to 0.74 dollars worse at 60 seconds. Its trajectory shows that this configuration needs time to approach the baseline; the result does not show an advantage at the tested budgets."),
    ("LATE_ACCEPTANCE", "Adding swap to the late-acceptance configuration improved its paired result relative to change-only at every budget. It was 13.09 dollars worse than TABU at 15 seconds, near even at 30, and 5.48 dollars better at 60. This conditional comparison is informative because the acceptor is shared; it still has only two independent held-out seeds."),
    ("TABU", "Relocation and swap with a tabu acceptor are the production reference. Accepted cleanup averaged 55.64 dollars at 15 seconds and 59.26 dollars at 30 and 60, with zero reported violations. Its flat 30-to-60-second mean suggests limited additional benefit on these fixtures, while other configurations may need the longer time to catch up."),
    ("SUBLIST", "Adding sublist movement and reversal to late acceptance yielded a 6.67 dollar paired advantage at 60 seconds after trailing TABU by 15.06 and 6.68 dollars at 15 and 30. This favors a longer-budget follow-up, not a claim that one specific move caused the gain."),
    ("KOPT", "The sublist configuration plus k-opt had the largest 60-second paired advantage in this matrix, 8.38 dollars, after deficits of 15.36 and 3.35 dollars at shorter budgets. It is a candidate for replicated long-budget tests because its advantage appears only after consuming the full 60 seconds."),
    ("RUIN_RECREATE", "Adding list ruin/recreate to the k-opt configuration improved its own accepted cleanup from 15.66 to 34.85 dollars as the budget rose, but it trailed TABU by 39.98, 36.72, and 24.41 dollars. The bigger neighborhood did not repay its search cost at these budgets and fixture sizes."),
]
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
        for line in content.decode("utf-8-sig").splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            if row.get("type") == "case":
                if "stress-normal-only" in path.name:
                    exclusions += 1
                else:
                    stage = next((x for x in STAGES if x in path.stem), "screen")
                    booking_counts[stage] += 1
                    booking_rows[stage, row["variant"]].append(row)
            elif row.get("type") == "result":
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
    return s, manifest, validation, daily_rows


def chart(fig, height=2.85):
    buf = BytesIO()
    fig.savefig(buf, format="png", dpi=180, bbox_inches="tight", facecolor="white")
    plt.close(fig)
    buf.seek(0)
    return Image(buf, width=7.1 * inch, height=height * inch)


def legend(fig, axes):
    handles, labels = [], []
    for axis in axes:
        h, l = axis.get_legend_handles_labels()
        for handle, label in zip(h, l):
            if label not in labels:
                handles.append(handle); labels.append(label)
    fig.legend(handles, labels, loc="lower center", ncol=min(5, len(labels)), bbox_to_anchor=(.5, -.05), fontsize=8)


def booking_chart(s):
    fig, ax = plt.subplots(1, 2, figsize=(11, 4.2))
    stages = ["original", "screen", "held", "stress", "browser"]
    for v in BOOKING:
        rows = {r["stage"]: r for r in s["booking"] if r["variant"] == v}
        xs = [i for i, st in enumerate(stages) if st in rows]
        ax[0].plot(xs, [100 * rows[stages[i]]["served"] / rows[stages[i]]["requests"] for i in xs], marker="o", label=v)
        ax[1].plot(xs, [rows[stages[i]]["meanCaseP95Ms"] / 1000 for i in xs], marker="o", label=v)
    for a in ax:
        a.set_xticks(range(5), stages, rotation=25)
        a.grid(alpha=.2)
    ax[0].set(ylabel="Served requests (%)", ylim=(0, 105), title="Demand served")
    ax[1].set(ylabel="Mean case p95 (seconds)", title="Harness search latency")
    ax[1].set_yscale("log")
    legend(fig, ax)
    fig.tight_layout(rect=(0, .08, 1, 1))
    return chart(fig)


def paired_chart(s):
    fig, ax = plt.subplots(figsize=(11, 3.1))
    for i, stage in enumerate(("screen", "held")):
        for j, v in enumerate(BOOKING[1:]):
            p = next(x for x in s["paired"] if x["stage"] == stage and x["variant"] == v)
            if p["meanSavingsCents"] is not None:
                ax.bar(i * 5 + j, p["meanSavingsCents"] / 100, color=[TEAL, ORANGE, "#869baa", "#805ca8"][j])
                ax.text(i * 5 + j, p["meanSavingsCents"] / 100 + .5, f'{p["equalCustomerCases"]} matched', ha="center", fontsize=7)
    ax.set_xticks([1.5, 6.5], ["Screen, clustered", "Held out, dispersed"])
    ax.set(ylabel="Savings vs INSERTION ($ / stream)", title="Comparable customer cost only")
    ax.grid(axis="y", alpha=.2)
    ax.legend(handles=[Patch(color=c, label=v) for c, v in zip([TEAL, ORANGE, "#869baa", "#805ca8"], BOOKING[1:])],
              loc="upper center", bbox_to_anchor=(.5, -.12), ncol=4, fontsize=8)
    fig.tight_layout(rect=(0, .10, 1, 1))
    return chart(fig, 2.2)


def booking_held_chart(s):
    rows = [next(r for r in s["booking"] if r["stage"] == "held" and r["variant"] == v) for v in ("INSERTION", "BOUNDED", "SHARED")]
    fig, ax = plt.subplots(1, 2, figsize=(11, 3.0))
    labels = [r["variant"] for r in rows]
    ax[0].bar(labels, [r["served"] for r in rows], color=[TEAL, ORANGE, "#805ca8"])
    ax[0].set(ylabel="Served of 480 requests", ylim=(0, 480), title="Held-out service")
    ax[1].bar(labels, [r["incomplete"] for r in rows], color=[TEAL, ORANGE, "#805ca8"])
    ax[1].set(ylabel="Incomplete searches", title="Held-out completion")
    for a in ax:
        a.tick_params(axis="x", labelsize=8)
        a.grid(axis="y", alpha=.2)
    fig.tight_layout()
    return chart(fig, 2.15)


def runtime_chart(raw_daily):
    fig, ax = plt.subplots(figsize=(11, 3.2))
    for b, marker in ((15, "o"), (30, "s"), (60, "^")):
        elapsed = [mean(r["elapsedMs"] for stage, budget, r in raw_daily if stage == "held" and budget == b and r["variant"] == v) / 1000 for v in DAILY]
        ax.scatter(elapsed, range(len(DAILY)), marker=marker, s=55, label=f"{b} s budget")
    ax.set_yticks(range(len(DAILY)), DAILY, fontsize=8)
    ax.invert_yaxis()
    ax.set(xlabel="Mean held-out elapsed time (seconds)", title="Runtime cost for each tested budget")
    ax.grid(axis="x", alpha=.2)
    ax.legend(loc="lower right", fontsize=8)
    fig.tight_layout()
    return chart(fig, 2.5)


def quality_chart(s):
    fig, ax = plt.subplots(2, 2, figsize=(11, 5.6), sharex="col")
    for v in BOOKING:
        for stage, col in (("screen", 0), ("held", 1)):
            rows = sorted((r for r in s["qualityOverTime"] if r["variant"] == v and r["stage"] == stage), key=lambda r: r["seconds"])
            if rows:
                ax[0, col].plot([r["seconds"] for r in rows], [r["visibleIncumbents"] / r["searches"] * 100 for r in rows], marker="o", label=v)
                ax[1, col].plot([r["seconds"] for r in rows], [None if r["meanVisibleCostDeltaCents"] is None else r["meanVisibleCostDeltaCents"] / 100 for r in rows], marker="o", label=v)
    for col, title in enumerate(("Screen, 18 searches per variant", "Held out, 480 searches per variant")):
        ax[0, col].set(title=title, ylabel="Visible incumbent (%)", ylim=(0, 105))
        ax[1, col].set(xlabel="Elapsed seconds including queue", ylabel="Mean visible cost ($)")
        for a in ax[:, col]:
            a.set_xticks([1, 5, 15, 30, 60]); a.grid(alpha=.2)
    legend(fig, ax[0, :])
    fig.tight_layout(rect=(0, .08, 1, 1))
    return chart(fig, 3.9)


def daily_chart(s, raw_daily):
    fig, ax = plt.subplots(1, 2, figsize=(11, 4.6))
    for v in DAILY:
        changes = [mean((r["after"]["costCents"] - r["before"]["costCents"]) / 100
                        for stage, budget, r in raw_daily if stage == "held" and budget == b and r["variant"] == v)
                   for b in (15, 30, 60)]
        paired = [0 if v == "TABU" else next(p["meanSavingsCents"] / 100 for p in s["dailyPaired"] if p["variant"] == v and p["budgetSeconds"] == b)
                  for b in (15, 30, 60)]
        ax[0].plot((15, 30, 60), changes, marker="o", label=v)
        ax[1].plot((15, 30, 60), paired, marker="o", label=v)
    ax[1].axhline(0, color="black", linewidth=.7)
    ax[0].set(ylabel="Accepted cost change ($ / case)", title="After minus before; lower is better")
    ax[1].set(ylabel="Paired savings vs TABU ($ / case)", title="Positive favors candidate")
    for a in ax:
        a.set(xlabel="Total solver budget (seconds)")
        a.set_xticks([15, 30, 60]); a.grid(alpha=.2)
    legend(fig, ax)
    fig.tight_layout(rect=(0, .16, 1, 1))
    return chart(fig)


def styles():
    base = getSampleStyleSheet()
    base.add(ParagraphStyle(name="TitleX", parent=base["Title"], fontName="Helvetica-Bold", fontSize=19, leading=23, textColor=colors.HexColor(NAVY), spaceAfter=10))
    base.add(ParagraphStyle(name="H1X", parent=base["Heading1"], fontSize=12, leading=15, textColor=colors.HexColor(NAVY), spaceBefore=11, spaceAfter=5))
    base.add(ParagraphStyle(name="H2X", parent=base["Heading2"], fontSize=10, leading=13, textColor=colors.HexColor(TEAL), spaceBefore=8, spaceAfter=3))
    base.add(ParagraphStyle(name="BodyX", parent=base["BodyText"], fontSize=8.3, leading=11.3, spaceAfter=5))
    base.add(ParagraphStyle(name="SmallX", parent=base["BodyText"], fontSize=7.1, leading=9.1, spaceAfter=3))
    base.add(ParagraphStyle(name="CellX", parent=base["BodyText"], fontSize=7.1, leading=9))
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


def build(s, manifest, raw_daily):
    global ST
    ST = styles()
    story = [para("Scheduler algorithm comparison", "TitleX"),
             para("Retained field validation, 29 September 2026 | Technical decision brief", "BodyX"),
             para("<b>Decision context.</b> Modelled cost is the lead measure, subject to served demand, feasibility, runtime, and evidence quality. Keep the current booking selection and daily TABU default while evaluating candidates. These fixture results do not establish production savings.", "BodyX"),
             para("Evidence scope", "H1X"),
             para("The retained matrix has <b>218 validated booking cases</b> (12 original, 30 screen, 144 held out, 8 stress, 24 browser) and <b>240 daily solver cases</b>. The two held-out seeds are 59 and 83. Original booking cases ran under an earlier bundled policy; subsequent cases use the four-hour, one-offer, zero-new-overtime policy. Daily roads are deterministic directed fixture legs; held-out dispersed booking roads use GraphHopper 11.", "BodyX"),
             KeepTogether([para("Booking: cost with service and runtime", "H1X"), booking_chart(s)]),
             para("<b>Figure 1.</b> Served rate and latency are shown only where each variant was tested. Lines connect separate experiment stages for readability and do not imply a time trend. The original policy is not an isolated algorithm comparison. The browser point is insertion only; stress has insertion and bounded only. Held-out SHARED served 334/480 against insertion 346/480 and bounded 360/480, with 64.1 s mean case p95 and 153 incomplete searches.", "SmallX"),
             para("The two panels answer different operational questions. The served panel counts confirmed requests; the latency panel averages each case's p95 search latency and uses a log scale because the slowest configuration is more than twenty times the fastest held-out option. A lower modeled route cost has little value if the search times out or leaves demand unserved. The held-out, stress, and browser points should be read as distinct workloads with different coverage, not as a single progression.", "BodyX"),
             KeepTogether([para("Held-out demand and completion", "H2X"), booking_held_chart(s)]),
             para("<b>Figure 1b.</b> Each held-out variant has 48 cases and 480 attempted requests. Bars separate successful service from incomplete searches, which are not equivalent to proven infeasibility. BOUNDED serves 14 more requests than INSERTION, while SHARED serves 12 fewer. SHARED also leaves 33 more searches incomplete than BOUNDED, so its matched-cost result needs service and completion context.", "SmallX"),
             ]
    paired = [p for p in s["paired"] if p["stage"] in ("screen", "held") and p["variant"] in BOOKING]
    story.append(KeepTogether([para("Booking cost: matched customers only", "H2X"), tbl(["Stage", "Variant vs insertion", "Matched / changed cases", "Served delta", "Mean savings per stream", "Exploratory 95% interval"],
                     [[p["stage"], p["variant"], f'{p["equalCustomerCases"]}/{p["differentCustomerCases"]}', p["servedDelta"],
                       "n/a" if p["meanSavingsCents"] is None else money(p["meanSavingsCents"]),
                       "n/a" if p["exploratory95PercentInterval"] is None else " to ".join(money(x) for x in p["exploratory95PercentInterval"])] for p in paired],
                     [48, 94, 95, 53, 105, 122])]))
    story += [para("Final inventory cost includes existing work and different served customers, so its totals cannot be read as savings. The table uses only cases with identical served request indices and reports changed-customer cases separately. Screen cases collapse to one clustered request stream; held-out intervals use only two independent seeds and are exploratory.", "SmallX"),
              KeepTogether([para("Comparable cost by customer matched stream", "H1X"), paired_chart(s)]),
              para("<b>Figure 2.</b> Bars show savings per matched stream for each tested variant. Left to right within each stage: BOUNDED, EXPANDED, RUIN_RECREATE, SHARED. The absent held-out EXPANDED and RUIN_RECREATE bars mean untested, not zero. The numbers above bars are matched case counts; differing served identities are excluded.", "SmallX"),
              para("The screen bars are a controlled cost signal with all 18 requests served per variant. Their one clustered stream has repeated geography, so the differences may reflect cleanup of modeled waiting and buffer time rather than a repeatable field saving. The held-out bars use dispersed road travel and show larger savings among matched cases, but BOUNDED and SHARED changed customer identity in 33 and 32 of 48 cases. Figure 2 therefore cannot rank total customer outcomes by cost alone.", "BodyX"),
              KeepTogether([para("Search quality over time", "H1X"), quality_chart(s)]),
              para("<b>Figure 3.</b> Upper panels show visibility by checkpoint; lower panels show mean incremental cost among visible incumbents. Missing incumbents stay missing and are excluded from the lower means. A visible incumbent can still be invalidated at final revalidation. At 60 s, held-out visibility was 478/480 for insertion and bounded, and 399/480 for SHARED.", "SmallX"),
              para("The upper held-out panel shows INSERTION finding a visible option sooner than BOUNDED, while BOUNDED's visible cost falls further by later checkpoints. SHARED finds fewer visible options throughout the minute. The lower panels describe the quality of options already visible, so their means have changing denominators; a downward line does not prove an all-request cost improvement. Queue time and roughly one-second polling are included, making this a customer-visible progress measure rather than exact solver time-to-best.", "BodyX"),
              KeepTogether([para("Daily solver: accepted cost by budget", "H1X"), daily_chart(s, raw_daily)]),
              para("<b>Figure 4.</b> Left: mean accepted cost change from the before snapshot. Right: paired savings against TABU on the same fingerprint and budget, averaging fleet sizes inside each seed. All 240 daily results have zero reported violations. Capped runs end in about 3 seconds; other held-out runs consume approximately their 15, 30, or 60 second budget.", "SmallX")]
    story += [KeepTogether([para("The left plot answers whether a configuration improved its own starting schedule; every line remains below zero. The right plot answers which configuration produced a lower final cost on the same held-out dataset and time budget. Those rankings differ because starting costs and case mix matter. CURRENT_UNCAPPED is favorable at each tested budget, while KOPT, SUBLIST, and LATE_ACCEPTANCE cross above TABU only at 60 seconds. RUIN_RECREATE improves its own schedule as time rises but remains behind TABU on paired final cost.", "BodyX")])]
    daily_rows = []
    for b in (15, 30, 60):
        for v in DAILY:
            rows = [r for stage, budget, r in raw_daily if stage == "held" and budget == b and r["variant"] == v]
            assert len(rows) == 8 and all(r["violations"] == 0 for r in rows)
            change = mean(r["after"]["costCents"] - r["before"]["costCents"] for r in rows)
            elapsed = mean(r["elapsedMs"] for r in rows)
            pair = next((x for x in s["dailyPaired"] if x["budgetSeconds"] == b and x["variant"] == v), None)
            daily_rows.append((v, b, money(change), f"{elapsed/1000:.1f}", "0" if pair is None else signed(pair["meanSavingsCents"])))
    assert len(daily_rows) == 24 and set(v for v, *_ in daily_rows) == set(DAILY)
    daily_headers = ["Configuration", "Budget s", "Mean accepted cost change", "Mean elapsed s", "Paired savings vs TABU $"]
    daily_widths = [115, 58, 128, 88, 128]
    story += [para("Held-out daily measurements: 15 and 30 seconds", "H2X"),
              tbl(daily_headers, daily_rows[:16], daily_widths),
              KeepTogether([para("Held-out daily measurements: 60 seconds", "H2X"), tbl(daily_headers, daily_rows[16:], daily_widths)]),
              para("Cost change is after minus before, so more negative means more modeled cleanup. The paired column is the sounder comparison with TABU. Each configuration has eight held-out cases per budget: two seeds times four fleet sizes. The 15-second screen adds six cases per configuration, yielding 30 cases each and 240 total.", "SmallX"),
              para("Exploratory paired intervals", "H2X"),
              tbl(["Configuration", "15 s", "30 s", "60 s"], [[v] + [interval_cell(s, v, b) for b in (15, 30, 60)]
                  for v in DAILY], [115, 134, 134, 134]),
              para("Values are dollars per case. The percentile bootstrap resamples only two independent held-out seed units after averaging fleet sizes; its intervals are exploratory and should not be interpreted as population confidence. More complex neighborhoods at different acceptor settings do not isolate the causal effect of a single move.", "SmallX"),
              KeepTogether([para("Runtime and feasibility by configuration", "H2X"), runtime_chart(raw_daily)]),
              para("<b>Figure 5.</b> Each marker is the mean elapsed time of eight held-out cases for one configuration and budget. CURRENT_CAPPED stops near three seconds regardless of allowance; other configurations run for the allocated 15, 30, or 60 seconds. The validation reports zero violations in all 240 daily cases. This is a fixture feasibility result, not proof for every future schedule.", "SmallX"),
              ]
    story.append(para("Booking algorithm comparison", "H1X"))
    for name, explanation in BOOKING_ANALYSIS:
        story.append(KeepTogether([para(name, "H2X"), para(explanation)]))
    story.append(para("Daily solver comparison", "H1X"))
    for name, explanation in DAILY_ANALYSIS:
        story.append(KeepTogether([para(name, "H2X"), para(explanation)]))
    story += [para("Report design decisions", "H1X"),
              para("Modeled cost leads because the decision concerns operating efficiency. Served requests, feasibility, and elapsed time appear beside it because a cheaper plan that serves fewer customers or misses a search deadline is not an interchangeable outcome. Booking final inventory totals include existing ten-day work and differing served requests, so cost savings use only identical served customer indices within the same seeded dataset. Changed-customer counts remain visible as a guard against selecting only favorable pairs.", "BodyX"),
              para("Stage labels remain separate because original cases use a bundled earlier policy, screen geography repeats, held-out booking roads are dispersed, stress adds near-capacity concurrent behavior, and browser tests cover only INSERTION. No plotted line fills an untested cell. Daily paired savings use TABU as the retained default and average fleet sizes within each of the two held-out seeds before calculating intervals. The decision matrix does not combine these measures into an unsupported score; it identifies the evidence needed for the next test.", "BodyX")]
    booking_notes = [
        ("INSERTION", "Fast control; held-out 346/480 served with 2.9 s mean case p95; browser 141/240 served.", "Higher matched-customer modeled cost than bounded or shared; stress served 17/40."),
        ("BOUNDED", "Held-out 360/480 served and matched-pair savings of $23.44 per stream; 21/40 stress served.", "Held-out mean case p95 15.7 s; 120 incomplete searches; changed customer sets in 33/48 comparisons."),
        ("EXPANDED", "Screen matched-pair savings $15 per clustered stream; 18/18 served.", "Screen only; 20.0 s mean case p95; no held-out or stress evidence."),
        ("RUIN_RECREATE", "Screen matched-pair savings $20 per clustered stream; 18/18 served.", "Screen only; 23.4 s mean case p95; reconstruction cost and no independent held-out evidence."),
        ("SHARED", "Screen tied ruin/recreate at $20 matched-pair savings with lower screen latency; held matched pairs show $31.36 per stream.", "Held-out service fell to 334/480, latency reached 64.1 s mean case p95, and original SHARED audit failed.")]
    story.append(KeepTogether([para("Pros and cons: booking search", "H1X"), tbl(["Variant", "Pros", "Cons / limitation"], booking_notes, [101, 195, 221])]))
    story += [para("Pros and cons: daily solver", "H1X")]
    daily_notes = [
        ("CURRENT_CAPPED", "About 3 s runtime, zero violations.", "Slightly worse paired cost than TABU at all held budgets."),
        ("CURRENT_UNCAPPED", "Paired cost better than TABU by $6.38, $3.68, $3.99 at 15/30/60 s.", "Consumes full budget; only two held-out seeds."),
        ("LATE_ACCEPTANCE_CHANGE", "Simple relocation-only comparison, zero violations.", "Worse paired cost at 15 and 30 s; near TABU at 60 s."),
        ("LATE_ACCEPTANCE", "Improves with time; $5.48 paired advantage at 60 s.", "Worse at 15 s and no clear 30 s advantage."),
        ("TABU", "Production baseline; $55.64 to $59.26 mean accepted cleanup; zero violations.", "Consumes full budget; alternatives sometimes have lower modeled cost."),
        ("SUBLIST", "$6.67 paired advantage at 60 s.", "Worse at 15 and 30 s; combined move effect is not isolated."),
        ("KOPT", "$8.38 paired advantage at 60 s, largest in this held matrix.", "Worse at 15 and 30 s; consumes full 60 s."),
        ("RUIN_RECREATE", "Improves accepted cost as budget rises; zero violations.", "Worse paired cost than TABU at all budgets, by $24.41 at 60 s.")]
    story.append(tbl(["Configuration", "Pros", "Cons / limitation"], daily_notes, [118, 190, 209]))
    story += [KeepTogether([para("Decision matrix for next tests", "H1X"),
              tbl(["Candidate", "Cost signal", "Service / feasibility", "Speed", "Confidence / next test"], [
                  ["Booking BOUNDED", "Positive matched cost", "Best held served, 360/480", "15.7 s case p95", "Repeat dispersed concurrent streams and inspect incomplete results"],
                  ["Booking SHARED", "Positive matched cost", "334/480 held served; failed audit", "64.1 s case p95", "Investigate audit conflict before any promotion"],
                  ["Daily CURRENT_UNCAPPED", "Positive at 15/30/60 s", "0 violations", "Full budget", "Repeat on independent geographies and time budgets"],
                  ["Daily KOPT", "Best 60 s paired signal", "0 violations", "60 s", "Confirm on more seeds and operational workloads"],
                  ["Daily TABU", "Reference", "0 violations", "Full budget", "Retain baseline until replicated evidence"],
              ], [105, 99, 102, 79, 132])]),
              para("The matrix presents tradeoffs, not a winner. Cost is measured on modeled fixtures; booking service and latency can outweigh a matched-pair cost signal. Feasibility checks passed for validated cases, while a separate audit failure remains unresolved.", "SmallX"),
              para("Evidence and limits", "H1X"),
              para("Source files: <link href='evidence/scheduler-field-2026-09-29/summary.json'>summary.json</link>, <link href='evidence/scheduler-field-2026-09-29/experiment-manifest.json'>experiment-manifest.json</link>, <link href='evidence/scheduler-field-2026-09-29/validation.json'>validation.json</link>, and <link href='scheduler-field-validation.md'>field validation report</link>. The generator verifies every inventory SHA-256, raw case counts, the retained failure, and all exclusions. All 218 booking cases are independently validated and all 240 daily cases report zero violations.", "SmallX"),
              para("The original SHARED seed-83, 50-technician, concurrency-five cold run returned HTTP 409 at final audit. Its client timing and detailed reason were not retained. A separately labeled recovery run passed, and the original failure remains in the archive. Eight original stress-normal-only audits omitted overflow dates and are excluded; reruns with full served dates form the validated stress set. Neither issue is silently counted as a pass.", "SmallX"),
              para("Browser testing used insertion alone; API latency and polled progress do not measure production browser performance under concurrency. Clustered screen geometry repeats and has zero road seconds. The two held-out seeds limit generalization. The four-hour policy, one-offer selection, and durable lifecycle change together between original and screen; that comparison cannot isolate an algorithm effect. No distance total or production savings is inferred.", "SmallX")]
    doc = SimpleDocTemplate(str(PDF), pagesize=(612, 792), leftMargin=36, rightMargin=36, topMargin=38, bottomMargin=43,
                            title="Scheduler algorithm comparison | September 29, 2026", author="WaterFlex")
    doc.build(story, onFirstPage=footer, onLaterPages=footer)
    md = ["# Scheduler algorithm comparison | 29 September 2026", "", "Modeled fixture results. Keep current booking selection and daily TABU default pending further tests.", "", "## Booking", "",
          "| Stage | Variant | Cases | Served | Mean case p95 s | Incomplete |", "| --- | --- | ---: | ---: | ---: | ---: |"]
    for st in STAGES:
        for r in (x for x in s["booking"] if x["stage"] == st):
            md.append(f'| {st} | {r["variant"]} | {r["cases"]} | {r["served"]}/{r["requests"]} | {r["meanCaseP95Ms"]/1000:.1f} | {r["incomplete"]} |')
    md += ["", "Final cost totals contain existing workload and cannot be treated as savings when served customer sets differ.", "", "### Same-customer cost pairs", "",
           "| Stage | Variant vs INSERTION | Matched / changed cases | Served delta | Mean savings per stream | Exploratory interval |", "| --- | --- | ---: | ---: | ---: | --- |"]
    for p in paired:
        interval = p["exploratory95PercentInterval"]
        md.append(f'| {p["stage"]} | {p["variant"]} | {p["equalCustomerCases"]}/{p["differentCustomerCases"]} | {p["servedDelta"]} | {"n/a" if p["meanSavingsCents"] is None else money(p["meanSavingsCents"])} | {"n/a" if interval is None else " to ".join(money(x) for x in interval)} |')
    md += ["", "## Daily solvers", "", "Each configuration has 6 screen and 24 held-out cases. All daily cases reported zero violations. The table gives held-out paired modeled savings against TABU, in dollars per case; positive favors the candidate.", "",
           "| Configuration | 15 s | 30 s | 60 s |", "| --- | ---: | ---: | ---: |"]
    for v in DAILY:
        md.append("| " + v + " | " + " | ".join("baseline" if v == "TABU" else signed(next(p["meanSavingsCents"] for p in s["dailyPaired"] if p["variant"] == v and p["budgetSeconds"] == b)) for b in (15, 30, 60)) + " |")
    md += ["", "## How to read the graphs", "",
           "- Figure 1 plots served requests and mean case p95 search latency at each tested stage. The latency axis is logarithmic. Missing stages are untested, and connecting lines do not describe a continuous trend.",
           "- Figure 1b compares held-out served requests and incomplete searches across the three tested variants. Each variant has 480 attempted requests across 48 cases.",
           "- Figure 2 uses only identical served customer sets within a seeded stream. The 6 screen matched cases collapse to one clustered stream; held-out BOUNDED and SHARED have only 15 and 16 matched cases.",
           "- Figure 3 plots visible-incumbent coverage above mean incremental cost among visible incumbents. The lower panel has a changing denominator and cannot represent all requests.",
           "- Figure 4 shows accepted before-to-after cost change beside paired final-cost savings against TABU on identical held-out datasets and budgets. Figure 5 shows elapsed time; all 240 daily cases reported zero violations.", "",
           "## Algorithm comparisons", "", "### Booking search", ""]
    for name, explanation in BOOKING_ANALYSIS:
        md += [f"**{name}.** {explanation}", ""]
    md += ["### Daily solver", ""]
    for name, explanation in DAILY_ANALYSIS:
        md += [f"**{name}.** {explanation}", ""]
    md += ["## Report design decisions", "",
           "Modeled cost leads because the decision concerns operating efficiency. Served demand, feasibility, and elapsed time are adjacent because a cheaper schedule can serve fewer customers or take longer than the available decision window. Booking savings use identical served customer indices on the same seeded dataset; final inventory cost includes existing work and cannot be compared as savings when customer sets differ.", "",
           "Stages remain separate because original policy changes multiple behaviors, screen geography repeats, held-out booking roads are dispersed, stress adds concurrent near-capacity work, and browser results cover only INSERTION. Daily paired comparisons use TABU, the retained default, at the same fingerprint and budget. Exploratory intervals resample two independent held-out seeds, so the report leaves algorithm promotion open for replication.", ""]
    md += ["", "## Pros and cons", "", "### Booking", "", "| Variant | Pros | Cons |", "| --- | --- | --- |"]
    md += [f"| {a} | {b} | {c} |" for a, b, c in booking_notes]
    md += ["", "### Daily", "", "| Configuration | Pros | Cons |", "| --- | --- | --- |"]
    md += [f"| {a} | {b} | {c} |" for a, b, c in daily_notes]
    md += ["", "## Decision matrix for next tests", "", "| Candidate | Cost signal | Service and feasibility | Speed | Confidence and next test |",
           "| --- | --- | --- | --- | --- |",
           "| Booking BOUNDED | Positive matched cost | 360/480 held served | 15.7 s case p95 | Repeat dispersed concurrent streams and inspect incomplete results |",
           "| Booking SHARED | Positive matched cost | 334/480 held served; original audit failed | 64.1 s case p95 | Investigate audit conflict before promotion |",
           "| Daily CURRENT_UNCAPPED | Positive paired cost at all budgets | 0 violations | Full budget | Repeat on independent geographies |",
           "| Daily KOPT | Strongest 60 s paired signal | 0 violations | 60 s | Confirm on more seeds and operational workloads |",
           "| Daily TABU | Reference | 0 violations | Full budget | Retain baseline until replicated evidence |"]
    md += ["", "## Reproduce", "", "Install `reportlab`, `matplotlib`, and `pymupdf` for Python 3, then run `py -3 infra/build-scheduler-algorithm-comparison.py` from the repository root. Run the same command with `--check` to verify retained file hashes, denominators, paired daily values, and the generated PDF and Markdown outputs.",
           "", "## Evidence limits and test priorities", "", "- Two held-out seeds make intervals exploratory. Screen clustered geometry repeats.",
           "- Original SHARED audit failed with HTTP 409; the recovery run is separate. Eight original stress audits omitted overflow dates and are excluded from validated stress results.",
           "- Browser tests cover INSERTION only. Original policy comparison bundles multiple policy and lifecycle changes.",
           "- Prioritize further dispersed concurrent BOUNDED booking tests, SHARED audit diagnosis, and broader daily CURRENT_UNCAPPED and 60-second KOPT/TABU pairs.", "",
           "Sources: [retained summary](evidence/scheduler-field-2026-09-29/summary.json), [manifest](evidence/scheduler-field-2026-09-29/experiment-manifest.json), [validation](evidence/scheduler-field-2026-09-29/validation.json), [full field report](scheduler-field-validation.md).", ""]
    MD.write_text("\n".join(md), encoding="utf-8", newline="\n")


def main():
    s, manifest, _, raw_daily = validate()
    if "--check" in sys.argv:
        assert PDF.is_file() and PDF.stat().st_size > 10000
        assert MD.is_file() and "## Pros and cons" in MD.read_text(encoding="utf-8")
        import pymupdf
        doc = pymupdf.open(PDF)
        text = " ".join(" ".join(page.get_text().split()) for page in doc)
        assert len(doc) >= 4 and all(x in text for x in ("218 validated booking cases", "240 daily solver cases", "SHARED", "CURRENT_CAPPED", "RUIN_RECREATE", "Evidence and limits"))
        print(f"VALID: 218 booking cases, 240 daily cases, {len(doc)} PDF pages, all retained files hashed")
    else:
        build(s, manifest, raw_daily)
        print(f"Wrote {PDF} and {MD}")


if __name__ == "__main__":
    main()
