"""The briefing's own figures, in the diagram-design grammar under the WaterFlex report skin of the October 14 report (its svglib).

Data figures take the values charts.py computed and recorded in generated/chart_data.json; schematic figures say in their description that they carry no data.
Labels use the reader's words (desks, technicians, dollars, seconds), not pull request numbers. One green accent per figure marks the choice made.
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.append(str(Path(__file__).resolve().parent.parent / "scheduler-explained-2026-10-14"))

from figures.svglib import (ACCENT, HOST, INK, KEY, LINK, MONO, MUTED, NAME, PAPER, PAPER2, SANS, SERIES, SERVICE, SOFT, STORE, TECH,  # noqa: E402
                            Figure, Plot, fmt, ink, mono_width, nice_ceiling, rgba)
from figures.thread import LOSS, end_labels, money, signed_axis  # noqa: E402

EYEBROW = "WaterFlex scheduler: a briefing"
GREY = "#9AA5AE"
BLUE = SERIES[1]
AMBER = SERIES[2]


def fig(slug, title, desc, height):
    return Figure(slug, title, desc, 980, height, EYEBROW)


def whole(v):
    return f"{v:,.0f}"


# ---- Front matter -----------------------------------------------------------------------------------------------------
def headline(rec: dict) -> Figure:
    f = fig("headline", "Five results in one page", "Five summary cards: holds lost at five booking desks, booking searches per minute on the rehearsal stack, the daily search's saving over the old method, searches with no offer in a booked-out week, and the engine's speed on the shipped changes.", 420)
    h = rec
    row1 = [(40, 312, "Holds lost at confirm", [("big", f"{h['lost_before']:.0f}% to 0%"), ("tech", "customers' chosen windows lost"), ("tech", "at five desks, first test vs fix"), ("muted", "BOOKING")], False),
            (364, 300, "Booking capacity", [("big", f"{h['vm_before']:,} to {h['vm_after']:,}"), ("tech", "searches a minute, five desks"), ("tech", "on the rehearsal stack"), ("muted", "BOOKING")], False),
            (676, 264, "Busy weeks", [("big", f"{h['empty_fixed']:.0f}% to {h['empty_dynamic']:.0f}%"), ("tech", "searches that offered nothing"), ("tech", "fixed vs dynamic horizon"), ("muted", "BOOKING")], False)]
    row2 = [(40, 472, "Daily search choice", [("big", f"{money(h['tk_saving'])} a day"), ("tech", "Tabu + K opt over Tabu, per metro day"), ("tech", f"mean of {h['tk_cases']:,} matched synthetic days, every fleet size"), ("muted", "DAILY PLAN, MODELED COST")], True),
            (524, 416, "Engine speed, shipped", [("big", f"{h['speed_lo']:.2f}x to {h['speed_hi']:.2f}x"), ("tech", "schedule changes tried per second"), ("tech", "this week's engine work, 10 to 50 techs"), ("muted", "MEASURED THROUGH THE PUBLIC API")], False)]
    for x, w, eyebrow, lines, focal in row1:
        f.card(x, 24, w, 156, eyebrow, lines, focal)
    for x, w, eyebrow, lines, focal in row2:
        f.card(x, 196, w, 156, eyebrow, lines, focal)
    f.legend(392, [("swatch", rgba(ACCENT, 0.06), "THE RESULT WORTH MONEY EVERY DAY"), ("swatch", PAPER, "OPERATIONAL RESULT")], note="each figure is read against its own study's control")
    return f


# ---- Part I -----------------------------------------------------------------------------------------------------------
def two_jobs(_=None) -> Figure:
    f = fig("two_jobs", "The scheduler's two jobs", "Schematic with no data: the booking job, where a customer on the phone gets arrival windows in seconds and the chosen one is confirmed, and the daily plan, where a booked metro day is rearranged overnight to cost less without breaking any promise.", 560)
    f.eyebrow_text(40, 28, "JOB 1  BOOKING  SECONDS, MANY CUSTOMERS AT ONCE")
    y, h = 48, 84
    nodes = [(40, 150, "Customer calls", ["WANTS A VISIT"], HOST), (226, 170, "Desk searches", ["WATERFLEX SOFTWARE"], HOST),
             (432, 180, "Scheduler", ["FINDS WINDOWS", "IN UNDER A SECOND"], SERVICE), (648, 136, "4 windows", ["HELD 10 MINUTES"], SERVICE),
             (820, 120, "Confirmed", ["ON THE BOOK"], rgba(ACCENT, 0.10))]
    for i, (x, w, name, sub, fill) in enumerate(nodes):
        focal = i == 4
        f.node(x, y, w, h, name, sub, fill, ACCENT if focal else SOFT, 1.4 if focal else 1)
        if i:
            px, pw = nodes[i - 1][0], nodes[i - 1][1]
            f.arrow(px + pw, y + h / 2, x, y + h / 2, "muted")
    f.callout(40, 168, ["Every existing promise holds and no overtime is added. If another desk changed the same day in",
                        "the meantime, the confirm is refused and the desk searches again: nothing is double booked."])
    f.line(40, 222, 940, 222, ink(0.10), 0.8)
    f.eyebrow_text(40, 254, "JOB 2  DAILY PLAN  MINUTES, ONE METRO DAY AT A TIME")
    y = 274
    nodes2 = [(40, 170, "Booked day", ["EVERY PROMISE FIXED"], HOST), (246, 190, "Cost search", ["CHEAPER ROUTES"], SERVICE),
              (472, 190, "Fairness search", ["EVENS OUT THE WORK"], SERVICE), (698, 242, "Proposal", ["CHEAPER, SAME PROMISES,", "PLANNER COMMITS IT"], rgba(ACCENT, 0.10))]
    for i, (x, w, name, sub, fill) in enumerate(nodes2):
        focal = i == 3
        f.node(x, y, w, h, name, sub, fill, ACCENT if focal else SOFT, 1.4 if focal else 1)
        if i:
            px, pw = nodes2[i - 1][0], nodes2[i - 1][1]
            f.arrow(px + pw, y + h / 2, x, y + h / 2, "muted")
    f.callout(40, 394, ["Visits may move between technicians and change order, but every customer's window holds.",
                        "The fairness search may give back a small, client-set allowance of the saving to spread work evenly,",
                        "and the result is accepted only if it beats the day as it was sent."])
    f.legend(504, [("swatch", HOST, "WATERFLEX SOFTWARE OR ITS USERS"), ("swatch", SERVICE, "THE SCHEDULER"), ("swatch", rgba(ACCENT, 0.10), "THE RESULT")], note="schematic, no data")
    return f


def example_day(rec: dict) -> Figure:
    f = fig("example_day", "One synthetic day, before and after", "Grouped bars of paid minutes for each of ten technicians on one synthetic clustered day: as sent, after the cost search and after the fairness search, with each plan's modeled cost and fairness variance.", 640)
    stages = [("before", "AS SENT", GREY, rgba(GREY, 0.35)), ("reference", "AFTER COST SEARCH", BLUE, rgba(BLUE, 0.35)), ("after", "AFTER FAIRNESS SEARCH", ACCENT, rgba(ACCENT, 0.18))]
    p = Plot(f, 110, 48, 700, 400); p.frame("Paid minutes by technician")
    top = max(max(rec[s]["paid"]) for s, *_ in stages)
    cap = rec["before"]["capacity"][0]
    ceiling = nice_ceiling(max(top, cap))
    p.ygrid(ceiling, None, "PAID MINUTES")
    n = len(rec["before"]["paid"])
    cols = p.columns(n)
    pitch = (p.x1 - p.x0) / n
    bw = pitch * 0.26
    for i, cx in enumerate(cols):
        for j, (stage, _, stroke, fill) in enumerate(stages):
            x = cx + (j - 1) * bw
            p.bar(x, bw, p.ymap(rec[stage]["paid"][i], ceiling), fill=fill, stroke=stroke)
        f.tech(cx, p.y1 + 20, f"T{i + 1}", MUTED)
    f.tech((p.x0 + p.x1) / 2, p.y1 + 44, "TECHNICIAN", MUTED)
    yc = p.ymap(cap, ceiling)
    f.line(p.x0, yc, p.x1, yc, INK, 1, "5,4")
    f.label(p.x1 - 90, yc - 6, f"REGULAR DAY {cap} MIN", INK, KEY)
    # The three plans side by side.
    x0 = 740
    f.eyebrow_text(x0, 64, "THE THREE PLANS")
    for k, (stage, label, stroke, fill) in enumerate(stages):
        y = 92 + k * 100
        focal = stage == "after"
        f.rect(x0, y, 200, 84, PAPER, rx=6); f.rect(x0, y, 200, 84, fill if focal else PAPER, stroke, 1.4 if focal else 1, rx=6)
        f.eyebrow_text(x0 + 12, y + 18, label, ACCENT if focal else SOFT)
        f.text(x0 + 12, y + 46, money(rec[stage]["costCents"] / 100), 22, ACCENT if focal else INK, SANS, weight="700")
        f.text(x0 + 12, y + 70, f"variance {rec[stage]['variance']:.4f}", KEY, MUTED, MONO)
    f.callout(110, 480, [f"The cost search took {money((rec['before']['costCents'] - rec['reference']['costCents']) / 100)} out of the day but piled the work on a few technicians;",
                         f"the fairness search gave back {money((rec['after']['costCents'] - rec['reference']['costCents']) / 100)} of it to spread the work, and the accepted plan",
                         f"still costs {money((rec['before']['costCents'] - rec['after']['costCents']) / 100)} less than the day as sent, with no overtime."])
    f.legend(584, [("swatch", rgba(GREY, 0.35), "AS SENT"), ("swatch", rgba(BLUE, 0.35), "AFTER COST SEARCH"), ("swatch", rgba(ACCENT, 0.18), "ACCEPTED PLAN")], note="10 technicians, clustered, 120 s")
    return f


def architecture(_=None) -> Figure:
    f = fig("architecture", "Where the scheduler sits", "Schematic with no data: WaterFlex Software keeps every record and calls the scheduler's one address; the scheduler VM runs a booking pool, a daily pool, the road map service and a small database that holds only what keeps promises safe.", 560)
    f.node(40, 200, 220, 120, "WaterFlex Software", ["DESKS, PLANNERS,", "EVERY RECORD"], HOST)
    f.zone(320, 40, 620, 420, "SCHEDULER VM  8 VCPU  32 GB  ITS OWN MACHINE")
    f.node(352, 220, 140, 80, "One address", ["/API/V1"], SERVICE)
    f.node(560, 92, 180, 84, "Booking pool", ["ANSWERS IN SECONDS"], rgba(ACCENT, 0.10), ACCENT, 1.4)
    f.node(560, 344, 180, 84, "Daily pool", ["PLANS OVERNIGHT"], SERVICE)
    f.node(784, 92, 132, 84, "Road map", ["DRIVE TIMES"], SERVICE)
    f.node(784, 344, 132, 84, "Database", ["HOLDS ONLY"], STORE)
    f.arrow(260, 260, 352, 260, "link")
    f.label(306, 252, "SNAPSHOT", LINK, KEY)
    f.route([(492, 248), (524, 248), (524, 134), (560, 134)])
    f.route([(492, 272), (524, 272), (524, 386), (560, 386)])
    f.arrow(740, 134, 784, 134, "muted")
    f.arrow(740, 386, 784, 386, "muted")
    f.label(738, 268, "BOTH POOLS USE THE ROAD MAP AND THE DATABASE", MUTED, KEY)
    f.callout(40, 360, ["WaterFlex Software sends the facts a", "call needs and keeps every business", "record; the scheduler keeps no copy."])
    f.legend(504, [("swatch", HOST, "WATERFLEX SOFTWARE"), ("swatch", SERVICE, "SCHEDULER SERVICE"), ("swatch", STORE, "STORE"), ("arrow-link", LINK, "API CALL")], note="schematic, no data")
    return f


def booking_life(_=None) -> Figure:
    f = fig("booking_life", "A booking's life, and why nothing is double booked", "Schematic with no data: a search returns windows held for ten minutes; when the customer chooses, the confirm checks that every technician-day it relies on is unchanged; if another desk changed it, the answer is search again, never a double booking.", 420)
    y, h = 80, 84
    f.node(40, y, 150, h, "Search", ["DAY'S FACTS SENT"], HOST)
    f.node(226, y, 170, h, "Windows held", ["10 MINUTES"], SERVICE)
    f.node(432, y, 150, h, "Customer picks", ["ONE WINDOW"], HOST)
    f.diamond(694, y + h / 2, 176, 112, ["DAY UNCHANGED", "SINCE SEARCH?"])
    f.node(820, y, 120, h, "Booked", ["HOST WRITES IT"], rgba(ACCENT, 0.10), ACCENT, 1.4)
    f.arrow(190, y + h / 2, 226, y + h / 2); f.arrow(396, y + h / 2, 432, y + h / 2); f.arrow(582, y + h / 2, 606, y + h / 2)
    f.arrow(782, y + h / 2, 820, y + h / 2, "accent")
    f.label(800, y + h / 2 - 8, "YES", ACCENT, KEY)
    f.route([(694, y + h / 2 + 56), (694, 220), (115, 220), (115, y + h)], "muted", "5,4")
    f.label(404, 212, "NO: SEARCH AGAIN FROM CURRENT RECORDS", MUTED, KEY)
    f.callout(40, 276, ["Each technician-day carries the timestamp WaterFlex Software already keeps. A confirm succeeds only if every",
                        "timestamp it relied on is still the same, so two desks can never sell the same slot. The cost of a clash",
                        "is one more search, measured in fractions of a second, not a lost or double booked customer."])
    f.legend(384, [("swatch", HOST, "CUSTOMER OR DESK"), ("swatch", SERVICE, "SCHEDULER"), ("swatch", rgba(ACCENT, 0.10), "BOOKED"), ("arrow-dashed", MUTED, "RETRY PATH")], note="schematic, no data")
    return f


# ---- Part II ----------------------------------------------------------------------------------------------------------
def paired_study(_=None) -> Figure:
    f = fig("paired_study", "How one study decides", "Schematic with no data: the same synthetic days, seeds and seconds run on today's code and on the change, each case compared with its own twin; the median of the paired results is held against a bar written before the run; a change that passes is then rerun through the public API on the rehearsal stack before it ships.", 600)
    f.node(40, 40, 900, 64, "The same synthetic days, seeds and seconds for both sides", ["FIXED BEFORE THE RUN, ORDER ALTERNATES, ONE FRESH PROCESS PER CASE"], HOST)
    f.node(120, 148, 300, 76, "Control", ["TODAY'S CODE OR METHOD"], SERVICE)
    f.node(560, 148, 300, 76, "Treatment", ["THE CHANGE UNDER TEST"], SERVICE)
    f.arrow(270, 104, 270, 148); f.arrow(710, 104, 710, 148)
    f.node(300, 268, 380, 76, "Case by case: treatment vs its twin", ["SAVING IN CENTS, OR SPEED RATIO"], SERVICE)
    f.route([(270, 224), (270, 246), (440, 246), (440, 268)]); f.route([(710, 224), (710, 246), (540, 246), (540, 268)])
    f.diamond(490, 410, 300, 104, ["MEDIAN CLEARS THE BAR", "WRITTEN BEFORE THE RUN?"])
    f.arrow(490, 344, 490, 358)
    f.node(40, 372, 200, 76, "Rejected", ["RECORDED, KEPT ON A BRANCH"], PAPER2)
    f.node(740, 372, 200, 76, "Rehearsal stack", ["SAME CHANGE THROUGH THE API"], rgba(ACCENT, 0.10), ACCENT, 1.4)
    f.arrow(340, 410, 240, 410); f.label(290, 402, "NO", MUTED, KEY)
    f.arrow(640, 410, 740, 410, "accent"); f.label(690, 402, "YES", ACCENT, KEY)
    f.callout(40, 500, ["Every case has a twin, so a hard day is compared with the same hard day. The bar is a decision rule",
                        "set in advance (for speed, 1.05 times on the phase the change targets), so the result cannot move the goalposts."])
    f.legend(572, [("swatch", HOST, "SHARED INPUTS"), ("swatch", SERVICE, "STEP"), ("swatch", rgba(ACCENT, 0.10), "SHIPS AFTER THIS CHECK")], note="schematic, no data")
    return f


def timeline(rec: list) -> Figure:
    f = fig("timeline", "Five weeks of studies", "Timeline from September 19 to October 22 with one row per study, placed on its run dates and labeled with its size in cases, solves, requests or runs; colors mark booking, daily-plan quality, load and engine speed studies.", 760)
    import datetime as dt
    start, end = dt.date(2026, 9, 19), dt.date(2026, 10, 23)
    x0, x1 = 300, 790
    xmap = lambda d: x0 + (d - start).days / (end - start).days * (x1 - x0)
    colors = {"booking": BLUE, "daily": AMBER, "load": SERIES[0], "engine": SERIES[4]}
    top, pitch = 64, 32
    for d in (dt.date(2026, 9, 19), dt.date(2026, 9, 29), dt.date(2026, 10, 9), dt.date(2026, 10, 19)):
        x = xmap(d)
        f.line(x, top - 12, x, top + pitch * len(rec), ink(0.08), 0.8)
        f.tech(x, top - 20, d.strftime("%b %d").upper(), MUTED, size=KEY)
    for i, s in enumerate(rec):
        y = top + i * pitch + 12
        a, b = dt.date.fromisoformat(s["start"]), dt.date.fromisoformat(s["end"])
        xa, xb = xmap(a), max(xmap(b + dt.timedelta(days=1)), xmap(a) + 8)
        f.text(x0 - 16, y + 4, s["name"], KEY + 1, INK, SANS, "end", weight="700")
        f.rect(xa, y - 7, xb - xa, 14, rgba(colors[s["kind"]], 0.45), colors[s["kind"]], 1, rx=2)
        f.text(xb + 8, y + 4, s["size"], KEY, MUTED, MONO)
    yl = top + pitch * len(rec) + 40
    f.legend(yl, [("swatch", rgba(BLUE, 0.45), "BOOKING"), ("swatch", rgba(AMBER, 0.45), "DAILY PLAN QUALITY"), ("swatch", rgba(SERIES[0], 0.45), "LOAD AND CAPACITY"),
                  ("swatch", rgba(SERIES[4], 0.45), "ENGINE SPEED")], note="none of it is customer traffic")
    f.height = int(yl + 40)
    return f


# ---- Part III: booking ------------------------------------------------------------------------------------------------
def booking_choice(rec: dict) -> Figure:
    f = fig("booking_choice", "Choosing the booking method", "Left: customers served of 480 against the typical worst-case wait for three booking methods in the September held-out test, on a log time axis. Right: what bounded rearrangement saved per case over insertion, by kind of day, in paid minutes and modeled dollars.", 600)
    p = Plot(f, 110, 56, 470, 420); p.frame("Served vs wait, September")
    lo, hi = 1, 100
    ticks = [1, 3, 10, 30, 100]
    yfloor, yceil = 300, 380
    for v in range(yfloor, yceil + 1, 20):
        y = p.ymap(v, yceil, floor=yfloor)
        f.line(p.x0, y, p.x1, y, ink(0.25) if v == yfloor else ink(0.08), 1 if v == yfloor else 0.8)
        f.tech(p.x0 - 8, y + 4, str(v), MUTED, "end")
    f.text(p.x0 - 56, (p.y0 + p.y1) / 2, "SERVED OF 480", TECH, MUTED, MONO, "middle", spacing="0.10em", extra=f'transform="rotate(-90 {p.x0 - 56:g} {(p.y0 + p.y1) / 2:g})"')
    xmap = lambda s: p.x0 + (math.log10(s) - math.log10(lo)) / (math.log10(hi) - math.log10(lo)) * (p.x1 - p.x0)
    for t in ticks:
        f.line(xmap(t), p.y0, xmap(t), p.y1, ink(0.06), 0.8); f.tech(xmap(t), p.y1 + 20, f"{t} S", MUTED)
    f.tech((p.x0 + p.x1) / 2, p.y1 + 44, "TYPICAL WORST WAIT (MEAN CASE P95), LOG", MUTED)
    names = {"INSERTION": "Insertion", "BOUNDED": "Bounded rearrangement", "SHARED": "Shared, larger search"}
    for e in rec["held"]:
        focal = e["variant"] == "BOUNDED"
        x, y = xmap(e["p95_ms"] / 1000), p.ymap(e["served"], yceil, floor=yfloor)
        f.circle(x, y, 8, rgba(ACCENT, 0.25) if focal else PAPER, ACCENT if focal else MUTED, 1.6)
        anchor = "end" if e["variant"] == "SHARED" else "start"
        dx = -14 if anchor == "end" else 14
        f.text(x + dx, y - 8, names[e["variant"]], KEY + 1, ACCENT if focal else INK, SANS, anchor, weight="700")
        f.text(x + dx, y + 10, f"{e['served']} served, {e['p95_ms'] / 1000:.1f} s", KEY, MUTED, MONO, anchor)
    # Right: savings by workload.
    q = Plot(f, 600, 56, 940, 420); q.frame("Bounded vs insertion, per case")
    ceiling = nice_ceiling(max(max(w["cost"], w["paid_min"]) for w in rec["workload"]))
    q.ygrid(ceiling, None, "MINUTES OR $ SAVED")
    cols = q.columns(len(rec["workload"]))
    bw = (q.x1 - q.x0) / len(cols) * 0.3
    for cx, w in zip(cols, rec["workload"]):
        q.bar(cx - bw / 2 - 2, bw, q.ymap(w["paid_min"], ceiling), fill=rgba(BLUE, 0.35), stroke=BLUE)
        q.bar(cx + bw / 2 + 2, bw, q.ymap(w["cost"], ceiling), focal=True)
        f.tech(cx - bw / 2 - 2, q.ymap(w["paid_min"], ceiling) - 8, f"{w['paid_min']:.0f}", MUTED, size=KEY)
        f.tech(cx + bw / 2 + 2, q.ymap(w["cost"], ceiling) - 8, money(w["cost"]), ACCENT, size=KEY)
        f.tech(cx, q.y1 + 20, w["workload"], MUTED, size=KEY)
    f.tech((q.x0 + q.x1) / 2, q.y1 + 44, "KIND OF DAY", MUTED)
    f.legend(544, [("solid-dot", rgba(ACCENT, 0.25), "CHOSEN METHOD"), ("swatch", rgba(BLUE, 0.35), "PAID MINUTES SAVED"), ("swatch", rgba(ACCENT, 0.12), "MODELED $ SAVED")], note="September studies, fixture days")
    return f


def refinement_frontier(rec: dict) -> Figure:
    f = fig("refinement_frontier", "How long to let the booking search polish", "Line chart, one line per fleet size, of the mean extra modeled cost per booking against the best search a five-second deadline allows, by the median search time, as the optional polishing pass is given 0 to 3 seconds on fully booked schedules; the shaded band is the measured noise.", 640)
    p = Plot(f, 120, 56, 760, 470); p.frame("Extra cost per booking vs search time, booked-out weeks")
    allv = [e["gap"] for fl in rec.values() for e in fl]
    floor, ceiling, ymap = signed_axis(f, p, min(allv + [-0.8]), max(allv), "EXTRA $ PER BOOKING", lambda v: money(v).replace(".00", ""))
    xceil = 4
    xmap = lambda ms: p.x0 + ms / 1000 / xceil * (p.x1 - p.x0)
    f.rect(p.x0, ymap(0.8), p.x1 - p.x0, ymap(-0.8) - ymap(0.8), rgba(MUTED, 0.10))
    f.label(p.x0 + 120, ymap(-0.8) + 16, "NOISE BAND, ABOUT $0.80", MUTED, KEY)
    for s in range(xceil + 1):
        f.line(xmap(s * 1000), p.y0, xmap(s * 1000), p.y1, ink(0.06), 0.8); f.tech(xmap(s * 1000), p.y1 + 20, f"{s} S", MUTED)
    f.tech((p.x0 + p.x1) / 2, p.y1 + 44, "MEDIAN SEARCH TIME AT ONE DESK", MUTED)
    palette = [SERIES[0], BLUE, AMBER, SERIES[3], SERIES[4]]
    labels = []
    for i, (fleet, entries) in enumerate(rec.items()):
        pts = [(xmap(e["p50_ms"]), ymap(e["gap"])) for e in entries if e["cap_ms"] is not None]
        p.polyline(pts, palette[i])
        for e in entries:
            if e["cap_ms"] is None:
                continue
            chosen = e["cap_ms"] == 2000
            f.circle(xmap(e["p50_ms"]), ymap(e["gap"]), 6 if chosen else 3.5, rgba(ACCENT, 0.3) if chosen else palette[i], ACCENT if chosen else None, 1.6)
        labels.append((pts[-1][1], f"{fleet} techs", palette[i]))
    end_labels(f, labels, p.x1 + 14)
    f.callout(780, 96, ["Two seconds closes", "most of the gap.", "It is the new", "default."])
    f.legend(580, [("solid-dot", rgba(ACCENT, 0.3), "2 S, THE DEFAULT NOW"), ("line", BLUE, "ONE FLEET SIZE"), ("swatch", rgba(MUTED, 0.10), "NOISE")], note="rehearsal stack, 40 cases per point")
    return f


def horizon(rec: dict) -> Figure:
    f = fig("horizon", "Booked-out weeks: a horizon that moves", "Bars of the share of searches that offered no window and the windows offered per search, fixed ten-weekday horizon against the dynamic horizon, at one and five desks on a heavily booked three-technician schedule.", 560)
    cells = [("1 desk", 1), ("5 desks", 5)]
    p = Plot(f, 110, 56, 470, 420); p.frame("Searches with no window offered")
    p.ygrid(60, 6, "% OF SEARCHES", lambda v: f"{v:.0f}%")
    cols = p.columns(2)
    bw = 52
    for cx, (label, desks) in zip(cols, cells):
        for j, mode in enumerate(("fixed", "dynamic")):
            e = rec[(mode, desks)]
            v = e["noOffers"] / e["searches"] * 100
            x = cx + (j - 0.5) * (bw + 8)
            focal = mode == "dynamic"
            p.bar(x, bw, p.ymap(v, 60), focal=focal, fill=None if focal else rgba(GREY, 0.35), stroke=None if focal else GREY)
            f.tech(x, p.ymap(v, 60) - 8, f"{v:.0f}%", ACCENT if focal else INK, size=KEY)
        f.tech(cx, p.y1 + 20, label, MUTED)
    q = Plot(f, 600, 56, 940, 420); q.frame("Windows offered per search")
    q.ygrid(4, 4, "WINDOWS")
    for cx, (label, desks) in zip(q.columns(2), cells):
        for j, mode in enumerate(("fixed", "dynamic")):
            e = rec[(mode, desks)]
            x = cx + (j - 0.5) * (bw + 8)
            focal = mode == "dynamic"
            q.bar(x, bw, q.ymap(e["offersPerSearch"], 4), focal=focal, fill=None if focal else rgba(GREY, 0.35), stroke=None if focal else GREY)
            f.tech(x, q.ymap(e["offersPerSearch"], 4) - 8, f"{e['offersPerSearch']:.2f}", ACCENT if focal else INK, size=KEY)
        f.tech(cx, q.y1 + 20, label, MUTED)
    f.legend(504, [("swatch", rgba(GREY, 0.35), "FIXED 10 WEEKDAYS"), ("swatch", rgba(ACCENT, 0.12), "DYNAMIC, UP TO 15")], note="3 techs, 12 weekdays booked out, 3 runs per bar")
    return f


# ---- Part IV: daily ---------------------------------------------------------------------------------------------------
LABEL = {"TABU": "Tabu", "TABU_SIZE_3": "Tabu, short memory", "TABU_SIZE_15": "Tabu, long memory", "TABU_KOPT": "Tabu + K opt", "TABU_KOPT_SIZE_3": "Tabu + K opt size 3",
         "KOPT": "K opt alone", "SUBLIST": "Sublist moves", "LATE_ACCEPTANCE": "Late acceptance"}


def heat(v: float, span: float) -> str:
    a = min(abs(v) / span, 1) * 0.75 + 0.05
    return rgba(BLUE, a) if v >= 0 else rgba(LOSS, a)


def daily_heatmap(rec: dict) -> Figure:
    f = fig("daily_heatmap", "Which daily search saves money, by fleet size", "Heatmap of the mean modeled saving per day against Tabu, the old default, for six alternatives at 5, 10, 20 and 50 technicians and overall, from 4,480 matched synthetic days; blue saves, brown loses, and Tabu + K opt is outlined.", 520)
    fleets, solvers, values = rec["fleets"], rec["solvers"], rec["values"]
    x0, y0, cw, ch = 300, 72, 120, 48
    span = max(abs(v) for row in values for v in row[:-1])
    for j, fl in enumerate(fleets):
        f.tech(x0 + j * cw + cw / 2, y0 - 14, f"{fl} TECHS", MUTED)
    f.tech(x0 + len(fleets) * cw + cw / 2 + 12, y0 - 14, "ALL", MUTED)
    for i, (solver, row) in enumerate(zip(solvers, values)):
        y = y0 + i * ch
        focal = solver == "TABU_KOPT"
        f.text(x0 - 16, y + ch / 2 + 5, LABEL[solver], NAME - 2, ACCENT if focal else INK, SANS, "end", weight="700")
        for j, v in enumerate(row):
            x = x0 + j * cw + (12 if j == len(fleets) else 0)
            f.rect(x + 2, y + 2, cw - 4, ch - 4, heat(v, span), PAPER, 1, rx=2)
            f.text(x + cw / 2, y + ch / 2 + 5, money(v), TECH, PAPER if abs(v) / span > 0.55 else INK, MONO, "middle", weight="700")
        if focal:
            f.rect(x0, y, len(fleets) * cw + cw + 12, ch, "none", ACCENT, 2.4, rx=4)
    f.callout(40, y0 + len(solvers) * ch + 34, ["Only Tabu + K opt is blue in every column: it saves money at every fleet size. The late acceptance family",
                                                "wins on mid-size fleets and loses heavily at 50 technicians, so it could not be one default for everyone."])
    f.legend(y0 + len(solvers) * ch + 104, [("swatch", rgba(BLUE, 0.6), "SAVES VS TABU"), ("swatch", rgba(LOSS, 0.6), "COSTS MORE THAN TABU"), ("swatch", PAPER, "OUTLINED: CHOSEN")], note="$ per synthetic day, 160 days per cell")
    f.height = y0 + len(solvers) * ch + 140
    return f


def diminishing(rec: dict) -> Figure:
    f = fig("diminishing", "What more search time buys", "Line chart of the extra modeled saving per day over the 60-second result as Tabu + K opt is given 90, 120 and 240 seconds, one line per fleet size, with each fleet's 60-second saving over the day as sent in the labels.", 600)
    p = Plot(f, 120, 56, 740, 450); p.frame("Extra saving over the 60 s result, Tabu + K opt")
    budgets = rec["budgets"]
    allv = [v for row in rec["extra"].values() for v in row]
    ceiling = nice_ceiling(max(allv))
    p.ygrid(ceiling, None, "EXTRA $ PER DAY", lambda v: money(v).replace(".00", ""))
    xceil = 240
    xmap = lambda s: p.x0 + 40 + (s - 60) / (xceil - 60) * (p.x1 - p.x0 - 80)
    for b in budgets:
        f.line(xmap(b), p.y0, xmap(b), p.y1, ink(0.06), 0.8); f.tech(xmap(b), p.y1 + 20, f"{b} S", MUTED)
    f.tech((p.x0 + p.x1) / 2, p.y1 + 44, "SECONDS OF SEARCH PER METRO DAY", MUTED)
    palette = [SERIES[0], BLUE, AMBER, ACCENT]
    labels = []
    for i, (fleet, row) in enumerate(rec["extra"].items()):
        focal = fleet == "50"
        pts = [(xmap(b), p.ymap(v, ceiling)) for b, v in zip(budgets, row)]
        p.polyline(pts, palette[i], focal=True)
        labels.append((pts[-1][1], f"{fleet} techs +{money(row[-1])}", palette[i]))
    end_labels(f, labels, p.x1 + 14)
    f.eyebrow_text(760, 72, "SAVING OVER THE DAY AS SENT AT 60 S")
    for k, (fleet, base) in enumerate(rec["base"].items()):
        f.text(760, 96 + k * 20, f"{fleet} techs  {money(base)}", KEY, INK, MONO)
    f.legend(544, [("line", ACCENT, "50 TECHNICIANS"), ("line", BLUE, "OTHER FLEET SIZES")], note="first daily study, 40 days per point")
    return f


def fairness_trade(rec: dict) -> Figure:
    f = fig("fairness_trade", "A fairer measure at the same cost", "Scatter of 36 synthetic days: the shipped fairness measure's change in modeled cost against production on the x axis, and its spread of work relative to production on the y axis, colored by fleet size; most days are fairer at the same cost.", 620)
    p = Plot(f, 120, 56, 700, 470); p.frame("Formula b against the previous measure, per day")
    xs = [e["cost_difference"] for e in rec["pairs"]]
    span = max(5, max(abs(v) for v in xs))
    xlim = math.ceil(span / 5) * 5
    xmap = lambda v: p.x0 + (v + xlim) / (2 * xlim) * (p.x1 - p.x0)
    yceil = 1.25
    for v in (0, 0.25, 0.5, 0.75, 1.0, 1.25):
        y = p.ymap(v, yceil)
        f.line(p.x0, y, p.x1, y, ink(0.45) if v == 1.0 else (ink(0.25) if v == 0 else ink(0.08)), 1.2 if v == 1.0 else 0.8)
        f.tech(p.x0 - 8, y + 4, f"{v:.2f}", MUTED, "end")
    f.text(p.x0 - 60, (p.y0 + p.y1) / 2, "SPREAD OF WORK VS BEFORE", TECH, MUTED, MONO, "middle", spacing="0.10em", extra=f'transform="rotate(-90 {p.x0 - 60:g} {(p.y0 + p.y1) / 2:g})"')
    step = xlim / 2
    v = -xlim
    while v <= xlim + 1e-9:
        f.line(xmap(v), p.y0, xmap(v), p.y1, ink(0.45) if v == 0 else ink(0.06), 1.2 if v == 0 else 0.8)
        f.tech(xmap(v), p.y1 + 20, money(v).replace(".00", ""), MUTED)
        v += step
    f.tech((p.x0 + p.x1) / 2, p.y1 + 44, "CHANGE IN MODELED COST PER DAY", MUTED)
    f.rect(p.x0 + 1, p.ymap(1.0, yceil), p.x1 - p.x0 - 2, p.y1 - p.ymap(1.0, yceil) - 1, rgba(ACCENT, 0.04))
    f.label(p.x0 + 110, p.y1 - 12, "FAIRER THAN BEFORE", ACCENT, KEY)
    colors = {10: SERIES[0], 30: BLUE, 50: AMBER}
    for e in rec["pairs"]:
        f.circle(xmap(e["cost_difference"]), p.ymap(min(e["variance_ratio"], yceil), yceil), 5, rgba(colors[e["technicians"]], 0.75), INK, 0.6)
    s = rec["summary"]
    f.eyebrow_text(730, 80, "THE FAIRNESS PHASE, FORMULA B")
    lines = [("big", f"{s['fairnessPhaseSpeedup']['median']:.1f}x"), ("tech", "work evaluated per second"), ("tech", f"{s['fairness']['fairer']} days fairer, {s['fairness']['same']} same,"), ("tech", f"{s['fairness']['lessFair']} less fair"),
             ("tech", f"median cost change {money(s['cost']['medianDifferenceCents'] / 100)}")]
    f.card(724, 92, 220, 168, "SHIPPED", lines, True)
    f.legend(564, [("solid-dot", rgba(SERIES[0], 0.75), "10 TECHS"), ("solid-dot", rgba(BLUE, 0.75), "30 TECHS"), ("solid-dot", rgba(AMBER, 0.75), "50 TECHS")], note="road matrix study, 15 s, 2 runs per day")
    return f


# ---- Part V: performance ----------------------------------------------------------------------------------------------
def booking_staircase(rec: list) -> Figure:
    f = fig("booking_staircase", "Booking capacity, one change at a time", "Bars of booking searches answered per minute at five desks after each change, with the typical and worst-case wait below; the dotted line separates the first half, measured on one machine, from the second half on the rehearsal stack beside five overnight planners, and the two halves are not one series.", 760)
    p = Plot(f, 110, 56, 940, 400); p.frame("Searches answered per minute, five desks")
    ceiling = nice_ceiling(max(e["searches_per_minute"] for e in rec))
    p.ygrid(ceiling, None, "SEARCHES / MIN")
    cols = p.columns(len(rec))
    pitch = (p.x1 - p.x0) / len(rec)
    for i, (cx, e) in enumerate(zip(cols, rec)):
        focal = i == len(rec) - 1
        p.bar(cx, pitch * 0.56, p.ymap(e["searches_per_minute"], ceiling), focal=focal, fill=None if focal else rgba(BLUE, 0.30), stroke=None if focal else BLUE)
        f.tech(cx, p.ymap(e["searches_per_minute"], ceiling) - 8, whole(e["searches_per_minute"]), ACCENT if focal else INK, size=KEY)
        for k, line in enumerate(e["name"]):
            f.text(cx, p.y1 + 20 + k * 15, line, KEY, INK, SANS, "middle", weight="700")
    split = p.x0 + pitch * rec_split(rec)
    f.line(split, p.y0 - 16, split, 640, INK, 1, "2,3")
    f.label(split - 120, p.y0 - 4, "ONE MACHINE, DESKS ALONE", MUTED, KEY)
    f.label(split + 150, p.y0 - 4, "REHEARSAL STACK, PLANNERS TOO", MUTED, KEY)
    # Latency strip.
    q = Plot(f, 110, 520, 940, 640)
    lat_ceiling = 7
    for v in range(0, lat_ceiling + 1, 1):
        y = q.ymap(v, lat_ceiling)
        f.line(q.x0, y, q.x1, y, ink(0.25) if v == 0 else ink(0.06), 0.8)
        if v % 2 == 1 or v == 0:
            f.tech(q.x0 - 8, y + 4, f"{v} S", MUTED, "end")
    f.text(q.x0 - 56, (q.y0 + q.y1) / 2, "WAIT", TECH, MUTED, MONO, "middle", spacing="0.10em", extra=f'transform="rotate(-90 {q.x0 - 56:g} {(q.y0 + q.y1) / 2:g})"')
    yb = q.ymap(5.5, lat_ceiling)
    f.line(q.x0, yb, q.x1, yb, LOSS, 1, "5,4"); f.label(q.x1 - 100, yb - 6, "5.5 S TARGET", LOSS, KEY)
    for cx, e in zip(cols, rec):
        y50, y95 = q.ymap(e["p50"] / 1000, lat_ceiling), q.ymap(min(e["p95"] / 1000, lat_ceiling), lat_ceiling)
        f.line(cx, y50, cx, y95, ink(0.45), 1)
        f.circle(cx, y50, 5, PAPER, MUTED, 1.5); f.circle(cx, y95, 5, INK)
    f.eyebrow_text(q.x0, q.y0 - 12, "TYPICAL (HOLLOW) AND WORST-CASE P95 (FILLED) WAIT AT THE DESK")
    f.legend(704, [("swatch", rgba(BLUE, 0.30), "AFTER EACH CHANGE"), ("swatch", rgba(ACCENT, 0.12), "TODAY"), ("hollow-dot", MUTED, "TYPICAL WAIT"), ("solid-dot", INK, "WORST-CASE WAIT")], note="the halves are not one series")
    return f


def rec_split(rec):
    return next(i for i, e in enumerate(rec) if e["half"] == 2)


def time_split(rec: list) -> Figure:
    f = fig("time_split", "Where a booking search's time goes", "Stacked horizontal bars of the median booking search's server time, split into waiting for a slot, road routing, the calculation engine and the database, at four stages of the routing and admission work on the rehearsal stack.", 520)
    parts = [("queueMs", "WAITING FOR A SLOT", rgba(GREY, 0.5), GREY), ("routingMs", "ROAD ROUTING", rgba(BLUE, 0.45), BLUE),
             ("calculationMs", "CALCULATION", rgba(AMBER, 0.5), AMBER), ("dbMs", "DATABASE", rgba(SERIES[4], 0.45), SERIES[4])]
    x0, x1 = 300, 900
    ceiling = nice_ceiling(max(sum(e[k] for k, *_ in parts) for e in rec))
    xmap = lambda v: x0 + v / ceiling * (x1 - x0)
    top, pitch = 64, 76
    for k in range(5):
        v = ceiling * k / 4
        f.line(xmap(v), top - 12, xmap(v), top + pitch * len(rec) - 20, ink(0.08) if k else ink(0.25), 0.8)
        f.tech(xmap(v), top + pitch * len(rec), f"{v:,.0f} MS", MUTED)
    for i, e in enumerate(rec):
        y = top + i * pitch
        focal = i == len(rec) - 1
        f.text(x0 - 16, y + 22, e["stage"], NAME - 2, ACCENT if focal else INK, SANS, "end", weight="700")
        x = x0
        for key, _, fill, stroke in parts:
            w = e[key] / ceiling * (x1 - x0)
            f.rect(x, y + 4, w, 28, fill, stroke, 1)
            x += w
        total = sum(e[k] for k, *_ in parts)
        f.text(x + 10, y + 23, f"{total:,} ms", KEY, ACCENT if focal else MUTED, MONO, weight="700" if focal else None)
        if focal:
            f.rect(x0 - 4, y, x - x0 + 8, 36, "none", ACCENT, 1.6, rx=3)
    f.legend(top + pitch * len(rec) + 48, [("swatch", rgba(GREY, 0.5), "WAITING FOR A SLOT"), ("swatch", rgba(BLUE, 0.45), "ROAD ROUTING"),
                                          ("swatch", rgba(AMBER, 0.5), "CALCULATION"), ("swatch", rgba(SERIES[4], 0.45), "DATABASE")], note="median search, five desks")
    f.height = top + pitch * len(rec) + 100
    return f


def bottleneck(rec: dict) -> Figure:
    f = fig("bottleneck", "The bottleneck lesson", "Three panels: in September a road graph ten times faster answered routing requests far quicker but served the same customers; in October, after the other bottlenecks were gone, the same kind of graph multiplied the booking searches answered in a minute.", 560)
    a = rec["alone"]; b = rec["booking"]; c = rec["october"]
    panels = [(40, 300, "September: routing alone", "MS TO ANSWER 16 ROAD LEGS", [("Old graph", a["flexible"]), ("Fast graph", a["ch"])], False, lambda v: f"{v:.1f} ms"),
              (360, 300, "September: booking customers", "SERVED OF " + whole(b["requests"]), [("Old graph", b["flexible"]), ("Fast graph", b["ch"])], False, whole),
              (680, 260, "October: booking capacity", "SEARCHES IN 60 S, 5 DESKS", [("Old graph", c["before"]), ("Fast graph", c["after"])], True, whole)]
    for x, w, title, unit, bars, focal_last, fmtv in panels:
        p = Plot(f, x + 20, 92, x + w - 20, 400)
        f.text(x, 40, title, NAME - 1, INK, SANS, weight="700"); f.eyebrow_text(x, 60, unit)
        ceiling = nice_ceiling(max(v for _, v in bars))
        f.line(p.x0, p.y1, p.x1, p.y1, ink(0.25), 1)
        for k, (cx, (label, v)) in enumerate(zip(p.columns(2), bars)):
            focal = focal_last and k == 1
            p.bar(cx, 72, p.ymap(v, ceiling), focal=focal, fill=None if focal else rgba(BLUE if k else GREY, 0.35), stroke=None if focal else (BLUE if k else GREY))
            f.tech(cx, p.ymap(v, ceiling) - 8, fmtv(v), ACCENT if focal else INK, size=KEY)
            f.tech(cx, p.y1 + 20, label.upper(), MUTED, size=KEY)
    f.callout(40, 456, ["A faster part only pays when it is the part holding the line. In September the booking search was waiting",
                        "on other things; once those were fixed, routing became the constraint and the same idea paid off."])
    f.legend(524, [("swatch", rgba(GREY, 0.35), "OLD GRAPH"), ("swatch", rgba(BLUE, 0.35), "FAST GRAPH"), ("swatch", rgba(ACCENT, 0.12), "WHERE IT PAID")], note="each panel is its own run")
    return f


def holds(rec: list) -> Figure:
    f = fig("holds", "Lost holds, before and after the fix", "Bars of the share of customer-chosen windows lost at confirm at one, five and ten booking desks, in the first load test and after the fix, with every after bar at zero.", 520)
    p = Plot(f, 110, 56, 940, 400); p.frame("Chosen windows lost at confirm")
    p.ygrid(60, 6, "% LOST", lambda v: f"{v:.0f}%")
    bw = 64
    for cx, e in zip(p.columns(len(rec)), rec):
        before = e["before_lost"] / e["before_selected"] * 100
        after = e["after_lost"] / e["after_selected"] * 100
        p.bar(cx - bw / 2 - 4, bw, p.ymap(before, 60), fill=rgba(LOSS, 0.30), stroke=LOSS)
        f.tech(cx - bw / 2 - 4, p.ymap(before, 60) - 8, f"{before:.0f}%", LOSS, size=KEY)
        p.bar(cx + bw / 2 + 4, bw, p.ymap(max(after, 0.4), 60), focal=True)
        f.tech(cx + bw / 2 + 4, p.ymap(after, 60) - 10, f"{after:.0f}%", ACCENT, size=KEY)
        f.tech(cx, p.y1 + 20, f"{e['desks']} DESK{'S' if e['desks'] > 1 else ''}", MUTED)
    f.legend(464, [("swatch", rgba(LOSS, 0.30), "FIRST LOAD TEST"), ("swatch", rgba(ACCENT, 0.12), "AFTER THE FIX, FRESH ADDRESSES")], note="every desk confirms its chosen windows")
    return f


def engine_steps(rec: list) -> Figure:
    f = fig("engine_steps", "Engine speed: every change, shipped or not", "Horizontal bars on a log scale of each engine change's median paired speed ratio on the phase it targets, against the code before it in its own study; the line at 1.05 is the promotion bar; changes that did not ship are grey.", 760)
    x0, x1 = 360, 780
    lo, hi = 0.5, 8
    xmap = lambda v: x0 + (math.log(v) - math.log(lo)) / (math.log(hi) - math.log(lo)) * (x1 - x0)
    top, pitch = 72, 52
    bottom = top + pitch * len(rec)
    for t in (0.5, 1, 2, 4, 8):
        f.line(xmap(t), top - 16, xmap(t), bottom, ink(0.45) if t == 1 else ink(0.08), 1.2 if t == 1 else 0.8)
        f.tech(xmap(t), bottom + 20, f"{t:g}x", MUTED)
    f.line(xmap(1.05), top - 16, xmap(1.05), bottom, ACCENT, 1, "4,3")
    f.label(xmap(1.05) + 54, top - 22, "BAR 1.05X", ACCENT, KEY)
    f.tech((x0 + x1) / 2, bottom + 44, "SPEED RATIO, CHANGE OVER CODE BEFORE IT (LOG)", MUTED)
    for i, e in enumerate(rec):
        y = top + i * pitch
        shipped = e["shipped"]
        f.text(x0 - 16, y + 18, e["name"], NAME - 2, INK if shipped else MUTED, SANS, "end", weight="700")
        f.text(x0 - 16, y + 34, e["phase"], KEY, MUTED, MONO, "end")
        a, b = sorted((xmap(1), xmap(e["ratio"])))
        fill, stroke = (rgba(ACCENT, 0.18), ACCENT) if shipped else (rgba(GREY, 0.25), GREY)
        f.rect(a, y + 8, max(b - a, 2), 22, fill, stroke, 1)
        tx = b + 10 if e["ratio"] >= 1 else xmap(1.05) + 10
        f.text(tx, y + 24, f"{e['ratio']:.2f}x  {e['note']}", KEY, INK if shipped else MUTED, MONO, "start")
    f.legend(bottom + 84, [("swatch", rgba(ACCENT, 0.18), "SHIPPED"), ("swatch", rgba(GREY, 0.25), "MEASURED, NOT SHIPPED"), ("line-dashed", ACCENT, "PROMOTION BAR")], note="each bar is read against its own control")
    f.height = bottom + 120
    return f


def harness_vs_stack(rec: list) -> Figure:
    f = fig("harness_vs_stack", "The workstation result held through the public API", "Dot pairs per fleet size: the paired speed ratio measured on the workstation harness (amber) and the same change measured through the public API on the rehearsal stack (green), for the fairness formula and for this week's shipped engine changes.", 560)
    x0, x1 = 360, 900
    ceiling = 8
    xmap = lambda v: x0 + (math.log(v) - math.log(0.8)) / (math.log(ceiling) - math.log(0.8)) * (x1 - x0)
    top, pitch = 72, 52
    bottom = top + pitch * len(rec)
    for t in (1, 2, 4, 8):
        f.line(xmap(t), top - 16, xmap(t), bottom, ink(0.45) if t == 1 else ink(0.08), 1.2 if t == 1 else 0.8)
        f.tech(xmap(t), bottom + 20, f"{t:g}x", MUTED)
    f.tech((x0 + x1) / 2, bottom + 44, "SPEED RATIO ON THE TARGETED PHASE (LOG)", MUTED)
    for i, e in enumerate(rec):
        y = top + i * pitch + 18
        f.text(x0 - 16, y + 5, e["name"], NAME - 2, INK, SANS, "end", weight="700")
        xs = xmap(e["stack"])
        if e["harness"] is not None:
            xh = xmap(e["harness"])
            f.line(min(xh, xs), y, max(xh, xs), y, ink(0.45), 1)
            f.circle(xh, y, 6, AMBER, INK, 1)
            f.text(xh + (10 if xh > xs else -10), y + 4, f"{e['harness']:.2f}x", KEY, MUTED, MONO, "start" if xh > xs else "end")
        f.circle(xs, y, 6, ACCENT, INK, 1)
        f.text(xs + (-10 if e["harness"] is not None and xmap(e["harness"]) > xs else 10), y + 4, f"{e['stack']:.2f}x", KEY, ACCENT, MONO,
               "end" if e["harness"] is not None and xmap(e["harness"]) > xs else "start")
    f.legend(bottom + 84, [("solid-dot", AMBER, "WORKSTATION HARNESS"), ("solid-dot", ACCENT, "REHEARSAL STACK, PUBLIC API")], note="no harness figure by fleet for the shipped changes")
    f.height = bottom + 120
    return f


# ---- Part VI ----------------------------------------------------------------------------------------------------------
def capacity(rec: dict) -> Figure:
    f = fig("capacity", "What another core buys at the booking desk", "Bars of booking searches answered per minute by ten clients for seven ways of laying out the booking pool on 2, 3 and 4 virtual CPUs; one replica with as many slots as CPUs, the chosen layout, is green.", 580)
    p = Plot(f, 110, 56, 940, 420); p.frame("Searches per minute, 10 clients, by booking pool layout")
    layouts = list(rec.items())
    ceiling = nice_ceiling(max(v["perMinute"] for _, v in layouts))
    p.ygrid(ceiling, None, "SEARCHES / MIN")
    groups = {}
    for name, v in layouts:
        groups.setdefault(v["vcpu"], []).append((name, v))
    n = len(layouts) + len(groups) - 1
    pitch = (p.x1 - p.x0) / n
    k = 0
    for vcpu, items in sorted(groups.items()):
        start = k
        for name, v in items:
            cx = p.x0 + pitch * (k + 0.5)
            replicas, slots = name.split("x")
            focal = replicas == "1" and int(slots) == vcpu and vcpu >= 3
            p.bar(cx, pitch * 0.6, p.ymap(v["perMinute"], ceiling), focal=focal, fill=None if focal else rgba(BLUE, 0.30), stroke=None if focal else BLUE)
            f.tech(cx, p.ymap(v["perMinute"], ceiling) - 8, whole(v["perMinute"]), ACCENT if focal else INK, size=KEY)
            f.text(cx, p.y1 + 20, f"{replicas} x {slots}", KEY, INK, MONO, "middle")
            k += 1
        f.tech(p.x0 + pitch * (start + len(items) / 2), p.y1 + 44, f"{vcpu} VCPU", MUTED)
        k += 1
    f.tech((p.x0 + p.x1) / 2, p.y1 + 68, "REPLICAS X SLOTS", MUTED)
    f.legend(536, [("swatch", rgba(ACCENT, 0.12), "CHOSEN: ONE REPLICA, ONE SLOT PER VCPU"), ("swatch", rgba(BLUE, 0.30), "OTHER LAYOUTS")], note="rehearsal stack; mean of 4 cells per bar")
    return f


# ---- Technical figures: the score, the booking deadline, admission, the statistics and convergence -----------------------
def score_ladder(_=None) -> Figure:
    f = fig("score_ladder", "How the daily search ranks two plans", "Schematic with no data: the daily score's seven levels, five hard and two soft, each with what it counts and its unit, compared from the top; a plan wins at the first level where the two differ, so no saving lower down can buy back a broken promise higher up.", 600)
    levels = [("HARD 0", "Categorical", "missing skills, roads with no route", "COUNT", True),
              ("HARD 1", "Promises and limits", "late arrival, absence, return, capacity", "SECONDS", True),
              ("HARD 2", "Unassigned", "visits left without a technician", "COUNT", True),
              ("HARD 3", "Overtime target", "off the overtime the cost search proved", "MINUTES", True),
              ("HARD 4", "Cost ceiling", "above the 2 percent fairness allowance", "CENTS", True),
              ("SOFT 0", "Phase objective", "overtime, then the spread of work", "MIN / 1E-9", False),
              ("SOFT 1", "Cost", "modeled operating cost of the day", "CENTS", False)]
    x0, w, top, h, pitch = 96, 560, 40, 52, 60
    f.arrow(64, top + 8, 64, top + pitch * len(levels) - 16, "muted")
    f.eyebrow_text(56, top + pitch * len(levels) + 10, "COMPARED IN THIS ORDER")
    for i, (tag, name, what, unit, hard) in enumerate(levels):
        y = top + i * pitch
        focal = tag == "SOFT 1"
        fill = rgba(ACCENT, 0.10) if focal else (SERVICE if hard else HOST)
        f.rect(x0, y, w, h, PAPER, rx=6); f.rect(x0, y, w, h, fill, ACCENT if focal else SOFT, 1.4 if focal else 1, rx=6)
        f.tag(x0 + 12, y + 18, tag)
        f.text(x0 + 100, y + 23, name, NAME, INK, SANS, weight="700")
        f.text(x0 + 100, y + 41, what, KEY, INK, MONO)
        f.text(x0 + w - 14, y + 31, unit, KEY, INK, MONO, "end")
    xr = 692
    f.eyebrow_text(xr, top + 14, "HARD LEVELS")
    f.para(xr, top + 38, ["Promises and limits. A plan with", "any hard level above zero is", "infeasible, whatever it costs.", "Levels 3 and 4 apply only in the", "fairness search."], KEY, INK, lead=17)
    f.eyebrow_text(xr, top + 5 * pitch + 14, "SOFT LEVELS")
    f.para(xr, top + 5 * pitch + 38, ["What the search minimizes once", "every hard level is zero: first", "the phase objective, then cost."], KEY, INK, lead=17)
    f.legend(top + pitch * len(levels) + 64, [("swatch", SERVICE, "HARD LEVEL"), ("swatch", HOST, "SOFT LEVEL"), ("swatch", rgba(ACCENT, 0.10), "WHERE THE MONEY IS")], note="schematic, no data; every level a whole number")
    f.height = top + pitch * len(levels) + 100
    return f


def deadline_budget(_=None) -> Figure:
    f = fig("deadline_budget", "How a booking search spends its five seconds", "Schematic timeline of the five-second booking deadline: the insertion pass gives the first answer, an optional polish of up to two seconds runs only when capacity is abundant and no desk is waiting, a required refinement may run to four seconds when capacity is scarce, and the last second is kept to validate and publish the offers.", 520)
    x0, x1 = 300, 920
    xmap = lambda s: x0 + s / 5 * (x1 - x0)
    top, pitch = 72, 64
    rows = [("Insertion pass", "EVERY GAP, NOTHING MOVED", 0, 0.5, "solid", False),
            ("Optional polish", "PLENTY OF WINDOWS, NOBODY WAITING", 0.5, 2.5, "dashed", True),
            ("Required refinement", "TOO FEW WINDOWS FOUND", 0.5, 4, "solid", False),
            ("Validate and publish", "CHECKED ALONE, THEN HELD", 4, 5, "solid", False)]
    bottom = top + pitch * len(rows)
    for s in range(6):
        f.line(xmap(s), top - 16, xmap(s), bottom, ink(0.25) if s in (0, 5) else ink(0.06), 0.8)
        f.tech(xmap(s), bottom + 20, f"{s} S", MUTED)
    f.line(xmap(4), top - 16, xmap(4), bottom, INK, 1, "5,4"); f.label(xmap(4), top - 22, "SEARCH STOPS", INK, KEY)
    f.line(xmap(5), top - 16, xmap(5), bottom, LOSS, 1.2); f.label(xmap(5) - 30, top - 22, "DEADLINE", LOSS, KEY)
    for i, (name, sub, a, b, style, focal) in enumerate(rows):
        y = top + i * pitch
        f.text(x0 - 16, y + 20, name, NAME - 1, INK, SANS, "end", weight="700")
        f.text(x0 - 16, y + 38, sub, KEY - 1, MUTED, MONO, "end")
        fill, stroke = (rgba(ACCENT, 0.14), ACCENT) if focal else (rgba(BLUE, 0.30), BLUE)
        f.rect(xmap(a), y + 10, xmap(b) - xmap(a), 28, fill, stroke, 1.2, rx=3, dash="5,3" if style == "dashed" else None)
    f.label(xmap(1.5), top + pitch + 30, "UP TO 2 S", ACCENT, KEY)
    f.callout(40, bottom + 66, ["Waiting for a slot, road routing and the database all spend this same budget. The customer always gets the",
                                "insertion pass's windows; the polish only makes the same windows cheaper, and is skipped while other desks wait."])
    f.legend(bottom + 128, [("swatch", rgba(BLUE, 0.30), "ALWAYS OR WHEN NEEDED"), ("swatch-dashed", rgba(ACCENT, 0.14), "OPTIONAL, SHED UNDER LOAD")], note="schematic widths except the 2, 4 and 5 s marks")
    f.height = bottom + 164
    return f


def admission(_=None) -> Figure:
    f = fig("admission", "How the booking pool admits work", "Schematic with no data: requests wait in a line of at most sixteen; confirms are taken before any waiting search and have a slot of their own; each booking replica runs two searches at once; a request that finds the line full is refused at once as busy; the daily pool runs separately and takes no booking work.", 560)
    f.zone(40, 40, 900, 330, "BOOKING REPLICA  2 VCPU")
    f.eyebrow_text(64, 84, "WAITING LINE, AT MOST 16")
    f.node(64, 96, 220, 56, "Confirm", ["CUSTOMER ALREADY CHOSE"], rgba(ACCENT, 0.10), ACCENT, 1.4)
    f.node(64, 168, 220, 56, "Search", ["NEW CALLER"], SERVICE)
    f.node(64, 240, 220, 56, "Search", ["NEW CALLER"], SERVICE)
    f.eyebrow_text(560, 84, "RUNNING")
    f.node(560, 96, 340, 56, "Confirm-only slot", ["A FEW MILLISECONDS"], rgba(ACCENT, 0.10), ACCENT, 1.4)
    f.node(560, 168, 340, 56, "Search slot 1", ["UP TO 5 S"], SERVICE)
    f.node(560, 240, 340, 56, "Search slot 2", ["UP TO 5 S"], SERVICE)
    f.arrow(284, 124, 560, 124, "accent")
    f.label(422, 116, "TAKEN FIRST", ACCENT, KEY)
    f.arrow(284, 196, 560, 196, "muted")
    f.arrow(284, 268, 560, 268, "muted")
    f.callout(64, 338, ["Search slots match the replica's cores: two on two cores, one on a single core."])
    f.node(40, 400, 420, 68, "Line full", ["REFUSED AT ONCE AS BUSY, DESK RETRIES"], PAPER2)
    f.node(520, 400, 420, 68, "Daily pool, its own replicas", ["2 PLANS AT ONCE, NO BOOKING WORK"], HOST)
    f.legend(516, [("swatch", SERVICE, "SEARCH WORK"), ("swatch", rgba(ACCENT, 0.10), "CONFIRMS, FIRST IN LINE"), ("swatch", PAPER2, "REFUSAL")], note="schematic, no data; shipped defaults")
    return f


def paired_distribution(rec: dict) -> Figure:
    f = fig("paired_distribution", "Every matched day, not just the average", "Four histograms, one per fleet size, of Tabu + K opt's saving over Tabu on each of 160 matched cases from the study that chose it, in $2.50 bins, with the mean and its 95 percent interval marked.", 560)
    width, lo, hi = rec["width"], rec["lo"], rec["hi"]
    panels = rec["fleets"]
    gap, left, right = 24, 64, 940
    pw = (right - left - gap * (len(panels) - 1)) / len(panels)
    top, bottom = 84, 400
    ymax = nice_ceiling(max(max(c) for c in rec["counts"].values()))
    for i, fl in enumerate(panels):
        x0 = left + i * (pw + gap); x1 = x0 + pw
        xmap = lambda v, x0=x0, x1=x1: x0 + (v - lo) / (hi - lo) * (x1 - x0)
        s = rec["stats"][str(fl)]
        f.text(x0, 44, f"{fl} technicians", NAME - 1, INK, SANS, weight="700")
        f.eyebrow_text(x0, 62, f"MEAN {money(s['mean'])}, 95% {money(s['lo'])} TO {money(s['hi'])}")
        f.line(x0, bottom, x1, bottom, ink(0.25), 1)
        for k, c in enumerate(rec["counts"][str(fl)]):
            a = lo + k * width
            if c:
                y = bottom - c / ymax * (bottom - top)
                gain = a >= 0
                f.rect(xmap(a) + 0.5, y, xmap(a + width) - xmap(a) - 1, bottom - y, rgba(BLUE if gain else LOSS, 0.45), BLUE if gain else LOSS, 0.6)
        f.line(xmap(0), top - 6, xmap(0), bottom, INK, 1)
        f.rect(xmap(s["lo"]), top - 6, max(xmap(s["hi"]) - xmap(s["lo"]), 2), bottom - top + 6, rgba(ACCENT, 0.14))
        f.line(xmap(s["mean"]), top - 6, xmap(s["mean"]), bottom, ACCENT, 1.6)
        for v in (-20, 0, 20):
            if lo <= v <= hi:
                f.tech(xmap(v), bottom + 18, money(v).replace(".00", ""), MUTED, size=KEY)
        if i == 0:
            for v in range(0, int(ymax) + 1, int(ymax) // 4):
                y = bottom - v / ymax * (bottom - top)
                f.tech(x0 - 8, y + 4, str(v), MUTED, "end", KEY)
    f.text(24, (top + bottom) / 2, "CASES", TECH, MUTED, MONO, "middle", spacing="0.10em", extra=f'transform="rotate(-90 24 {(top + bottom) / 2:g})"')
    f.tech((left + right) / 2, bottom + 44, "SAVING PER DAY OVER TABU, MATCHED CASE", MUTED)
    f.legend(504, [("swatch", rgba(BLUE, 0.45), "SAVED"), ("swatch", rgba(LOSS, 0.45), "COST MORE"), ("line", ACCENT, "MEAN"), ("swatch", rgba(ACCENT, 0.14), "95% INTERVAL OF THE MEAN")], note="interval resamples whole days")
    return f


def convergence(rec: dict) -> Figure:
    f = fig("convergence", "When each search found its best plan", "Line chart of the mean point in the cost search, as a share of its time, at which each method found its final best plan, for Tabu, Tabu + K opt, K opt and late acceptance at 5 to 50 technicians.", 560)
    p = Plot(f, 120, 56, 740, 420); p.frame("Share of the cost search used before the best plan appeared")
    p.ygrid(1, 5, "SHARE OF THE TIME", lambda v: f"{v * 100:.0f}%")
    fleets = rec["fleets"]
    xs = p.columns(len(fleets))
    for x, fl in zip(xs, fleets):
        f.tech(x, p.y1 + 20, f"{fl} TECHS", MUTED)
    colors = {"TABU": AMBER, "TABU_KOPT": ACCENT, "KOPT": SERIES[4], "LATE_ACCEPTANCE": LOSS}
    labels = []
    for s in rec["solvers"]:
        vals = rec["best_fraction"][s]
        pts = [(x, p.ymap(v, 1)) for x, v in zip(xs, vals)]
        p.polyline(pts, colors[s], focal=True)
        labels.append((pts[-1][1], f"{LABEL[s]} {vals[-1] * 100:.0f}%", colors[s]))
    end_labels(f, labels, p.x1 + 14)
    f.line(p.x0, p.ymap(0.9, 1), p.x1, p.ymap(0.9, 1), LOSS, 1, "5,4")
    f.label(p.x0 + 170, p.ymap(0.9, 1) - 6, "BEST FOUND IN THE LAST TENTH", LOSS, KEY)
    f.legend(504, [("line", ACCENT, "TABU + K OPT"), ("line", AMBER, "TABU"), ("line", SERIES[4], "K OPT"), ("line", LOSS, "LATE ACCEPTANCE")], note="first daily study, mean of 160 cases per point")
    return f


def forest(rec: dict) -> Figure:
    f = fig("out_of_sample", "The choice held on days it had never seen", "Forest plot of Tabu + K opt's mean saving per day over Tabu with 95 percent intervals that resample whole days, by fleet size, in the study that chose it and in the follow-up on new days, with days won and lost and the sign test.", 660)
    rows = []
    for fl, a, b in zip(rec["fleets"], rec["first"], rec["follow"]):
        if a is not None:
            rows.append((f"{fl} techs, chose it", a, False))
        rows.append((f"{fl} techs, new days", b, True))
    rows.append(("All, chose it", rec["all"]["first"], False))
    rows.append(("All, new days", rec["all"]["follow"], True))
    x0, x1 = 220, 640
    hi = nice_ceiling(max(r["hi"] for _, r, _ in rows))
    lo = -5
    xmap = lambda v: x0 + (v - lo) / (hi - lo) * (x1 - x0)
    top, pitch = 72, 40
    bottom = top + pitch * len(rows) + 8
    v = 0
    while v <= hi:
        f.line(xmap(v), top - 12, xmap(v), bottom, ink(0.45) if v == 0 else ink(0.08), 1.2 if v == 0 else 0.8)
        f.tech(xmap(v), bottom + 20, money(v).replace(".00", ""), MUTED)
        v += 5
    f.tech((x0 + x1) / 2, bottom + 44, "MEAN $ SAVED PER DAY, 95% INTERVAL", MUTED)
    f.eyebrow_text(672, top - 16, "DAYS WON-LOST"); f.eyebrow_text(800, top - 16, "SIGN TEST")
    for i, (name, s, follow) in enumerate(rows):
        y = top + i * pitch + 16 + (12 if name.startswith("All") else 0)
        f.text(x0 - 16, y + 5, name, KEY + 1, INK, SANS, "end", weight="700")
        color = ACCENT if follow else BLUE
        f.line(xmap(s["lo"]), y, xmap(s["hi"]), y, color, 2)
        for x in (xmap(s["lo"]), xmap(s["hi"])):
            f.line(x, y - 5, x, y + 5, color, 1.4)
        f.rect(xmap(s["mean"]) - 5, y - 5, 10, 10, color, INK, 0.8)
        p = s["sign_p"]
        ptext = "p < 0.0001" if p < 1e-4 else f"p = {p:.4f}" if p < 0.01 else f"p = {p:.2f}"
        f.text(672, y + 4, f"{s['day_wins']}-{s['day_losses']} of {s['days']}", KEY, INK, MONO)
        f.text(800, y + 4, ptext, KEY, INK, MONO)
    f.line(40, top + pitch * (len(rows) - 2) + 12, 940, top + pitch * (len(rows) - 2) + 12, ink(0.15), 0.8)
    f.legend(bottom + 84, [("swatch", BLUE, "STUDY THAT CHOSE IT (IN SAMPLE)"), ("swatch", ACCENT, "NEW DAYS (OUT OF SAMPLE)")], note="bootstrap of whole days, 10,000 draws")
    f.height = bottom + 120
    return f


# ---- Part VII ---------------------------------------------------------------------------------------------------------
def roadmap(_=None) -> Figure:
    f = fig("roadmap", "What comes next, as decisions", "Schematic with no data: three lanes of next steps, measure, decide and integrate, each step with what it would settle; the production-VM measurement and the cutover are the two steps everything else waits on.", 640)
    lanes = [("MEASURE", [("Real VM, real network", "DOES THE TARGET HOLD?", True), ("Real days replayed", "SAVINGS ON REAL ROADS", False), ("Fairness head-to-head", "LOAD BALANCE VS B", False)]),
             ("DECIDE", [("Size 3 as a choice", "OPT-IN, NO NEW DEFAULT", False), ("Multithreaded solving", "GATED: QUALITY AND CORES", False), ("Booking cores", "SIZE THE POOL ON THE VM", False)]),
             ("INTEGRATE", [("Rate limits confirmed", "WITH WATERFLEX SOFTWARE", False), ("Encryption in transit", "BEFORE PRODUCTION TRAFFIC", False), ("Side by side, then cutover", "BOTH PATHS AGREE, SWITCH", True)])]
    y = 40
    for lane, items in lanes:
        f.zone(40, y, 900, 160, lane)
        for j, (name, sub, focal) in enumerate(items):
            x = 64 + j * 296
            f.node(x, y + 40, 260, 96, name, [sub], rgba(ACCENT, 0.10) if focal else SERVICE, ACCENT if focal else SOFT, 1.4 if focal else 1)
            if j:
                f.arrow(x - 36, y + 88, x, y + 88, "muted", "5,4")
        y += 180
    f.legend(y + 40, [("swatch", rgba(ACCENT, 0.10), "THE STEPS OTHERS WAIT ON"), ("swatch", SERVICE, "STEP"), ("arrow-dashed", MUTED, "SUGGESTED ORDER")], note="schematic, no data")
    f.height = y + 76
    return f
