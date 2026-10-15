"""Figures for the trail edition of the report: how the work was measured, the five optimization threads, the system today and what comes next (Parts III to VI).

Data figures take the values charts.py computed and recorded in chart_data.json; schematic figures draw mechanisms the design document, the contract and the
code describe. Every figure follows the diagram-design grammar under the report skin (svglib): orthogonal connectors, masked labels, one green accent, a legend strip.
"""
from __future__ import annotations

import math

from .svglib import (ACCENT, HOST, INK, KEY, LINK, MONO, MUTED, NAME, PAPER, PAPER2, SANS, SERIES, SERVICE, SOFT, STORE, TECH,
                     Figure, Plot, fmt, ink, mono_width, nice_ceiling, rgba)

LOSS = "#9c6b50"
LABEL = {"TABU": "Tabu", "TABU_SIZE_3": "Tabu size 3", "TABU_SIZE_15": "Tabu size 15", "TABU_KOPT": "Tabu + K opt", "TABU_KOPT_SIZE_3": "Tabu + K opt size 3",
         "KOPT": "K opt", "SUBLIST": "Sublist", "LATE_ACCEPTANCE": "Late acceptance", "CURRENT_UNCAPPED": "Uncapped original", "RUIN_RECREATE": "Ruin and recreate"}


def money(v: float) -> str:
    return f"-${-v:,.2f}" if v < 0 else f"${v:,.2f}"


def _step(span: float) -> float:
    """A round step that splits span into four to six intervals."""
    raw = span / 5
    exp = math.floor(math.log10(raw))
    for m in (1, 2, 2.5, 5, 10):
        if m * 10 ** exp >= raw:
            return m * 10 ** exp
    return 10 ** (exp + 1)


def signed_axis(f: Figure, p: Plot, lo: float, hi: float, unit: str, tick=lambda v: fmt(v)):
    """A value axis that may cross zero: returns (floor, ceiling, ymap). Zero is drawn darker."""
    step = _step(hi - lo)
    floor, ceiling = math.floor(lo / step) * step, math.ceil(hi / step) * step
    if floor == lo:
        floor -= step if lo < 0 else 0
    if ceiling == hi and hi > 0:
        ceiling += step
    ymap = lambda v: p.y1 - (v - floor) / (ceiling - floor) * (p.y1 - p.y0)
    v = floor
    while v <= ceiling + 1e-9:
        y = ymap(v)
        f.line(p.x0, y, p.x1, y, ink(0.45) if abs(v) < 1e-9 else ink(0.08), 1.2 if abs(v) < 1e-9 else 0.8)
        f.tech(p.x0 - 8, y + 4, tick(v), MUTED, "end")
        v += step
    f.line(p.x0, p.y0, p.x0, p.y1, ink(0.25), 1)
    f.text(p.x0 - 64, (p.y0 + p.y1) / 2, unit, TECH, MUTED, MONO, "middle", spacing="0.10em", extra=f'transform="rotate(-90 {p.x0 - 64:g} {(p.y0 + p.y1) / 2:g})"')
    return floor, ceiling, ymap


def end_labels(f: Figure, items: list[tuple[float, str, str]], x: float, gap: float = 16) -> None:
    """Labels at the right end of lines, nudged apart so none overlap: items are (y, text, color)."""
    placed: list[float] = []
    for y, text, color in sorted(items):
        yy = max(y, placed[-1] + gap) if placed else y
        placed.append(yy)
        f.text(x, yy + 4, text, KEY, color, MONO)


# ---- Part III: how the work was measured ---------------------------------------------------------
def evidence(counts: dict) -> Figure:
    f = Figure("evidence", "Three kinds of evidence, one discipline", "Three cards for the daily solver studies, the September booking studies and the October load studies, with what one case is, how a comparison is made and what each cannot say; below, the pipeline every number passes through: raw files with hashes, a loader that checks shapes, the builder, and a verifier that reads the raw files again.", 1280, 540)
    cards = [("FIXTURE QUALITY", "Daily solver studies", f"{counts['daily_cases']:,} CASES", ["one synthetic day, one seed,", "one budget, one configuration"], ["matched: same start, exact cents"], ["payroll on a real day"]),
             ("BOOKING QUALITY", "Booking studies, September", f"{counts['booking_requests']:,} REQUESTS", ["ten requests on one fixture", "day, one method"], ["paired where both booked alike"], ["a production desk's experience"]),
             ("BEHAVIOUR UNDER LOAD", "Load studies, October", f"{counts['load_runs']} RUNS", ["60 or 90 s of desks and", "planners on random addresses"], ["fresh seeds, caller latency,", "one change per pull request"], ["the real VM's capacity"])]
    widths, x = (428, 376, 376), 30
    for (eyebrow, name, size, one, compare, cannot), w in zip(cards, widths):
        lines = [("name", name), ("tech", size), ("muted", "ONE CASE"), *[("tech", s) for s in one], ("muted", "COMPARED"), *[("tech", s) for s in compare], ("muted", "CANNOT SAY"), *[("tech", s) for s in cannot]]
        f.card(x, 32, w, 248, eyebrow, lines)
        x += w + 20
    # The pipeline every number passes through.
    y, h, w = 320, 72, 236
    xs = [30, 342, 654, 966]
    steps = [("Raw files", "SHA-256 IN THE PROVENANCE"), ("Loader", "ASSERTS EVERY SHAPE"), ("Builder", "CHARTS, NUMBERS, DOCUMENT"), ("Verifier", "RECOMPUTES FROM RAW")]
    for i, (x, (name, sub)) in enumerate(zip(xs, steps)):
        focal = i == 3
        f.node(x, y, w + (48 if i == 3 else 0), h, name, [sub], rgba(ACCENT, 0.08) if focal else (STORE if i == 0 else SERVICE), ACCENT if focal else SOFT, 1.4 if focal else 1)
        if i:
            f.arrow(xs[i - 1] + w, y + h / 2, x, y + h / 2, "muted")
    f.route([(xs[0] + w / 2, y + h), (xs[0] + w / 2, y + h + 40), (xs[3] + (w + 48) / 2, y + h + 40), (xs[3] + (w + 48) / 2, y + h)], "accent", "5,4")
    f.label((xs[1] + xs[2] + w) / 2, y + h + 32, "READ AGAIN, WITHOUT THE LOADER OR THE BUILDER", ACCENT, KEY)
    f.legend(506, [("swatch", STORE, "EVIDENCE AS RECORDED"), ("swatch", SERVICE, "BUILD STEP"), ("swatch", rgba(ACCENT, 0.08), "INDEPENDENT CHECK")], note="none of it is customer traffic")
    return f


# ---- Part IV: the daily search -------------------------------------------------------------------
def two_phase(_=None) -> Figure:
    f = Figure("two_phase", "A daily proposal's search: two phases, then policy", "The day as sent goes through a cost search and then a fairness search inside the client's seconds, and policy accepts the result only if it beats the day as sent. Below, the moves the searches make: relocate, swap, and the K opt move that reconnects several links of a route at once.", 1280, 640)
    y = 48
    f.node(30, y, 190, 72, "Day as sent", ["BOOKED, PROMISES FIXED"], HOST)
    f.node(256, y, 236, 72, "Cost search", ["CLIENT'S COST SECONDS"], SERVICE)
    f.node(528, y, 236, 72, "Fairness search", ["MAY SPEND AN ALLOWANCE"], SERVICE)
    f.arrow(220, y + 36, 256, y + 36, "muted"); f.arrow(492, y + 36, 528, y + 36, "muted")
    f.diamond(900, y + 36, 200, 112, ["BEATS THE", "DAY AS SENT?"])
    f.arrow(764, y + 36, 800, y + 36, "muted")
    f.node(1040, y - 8, 210, 40, "IMPROVED", [], rgba(ACCENT, 0.08), ACCENT, 1.4)
    f.node(1040, y + 48, 210, 56, "NO_IMPROVEMENT", ["OR REJECTED_BY_POLICY"], PAPER2)
    f.route([(1000, y + 36), (1016, y + 36), (1016, y + 12), (1040, y + 12)], "accent", r=6)
    f.route([(900, y + 92), (900, y + 112), (1024, y + 112), (1024, y + 76), (1040, y + 76)], "muted", r=6)
    f.callout(256, 168, ["Every customer's window holds and no overtime is added in either phase;",
                         "the fairness search may give back a small allowance of the cost it found to spread work more evenly."])
    # The moves.
    f.line(30, 228, 1250, 228, ink(0.10), 0.8)
    f.eyebrow_text(30, 252, "THE MOVES A LOCAL SEARCH TRIES, ONE STEP AT A TIME", SOFT)
    panels = [(30, "Relocate", "ONE VISIT TO ANOTHER ROUTE"), (440, "Swap", "TWO VISITS TRADE PLACES"), (850, "K opt", "SEVERAL LINKS RECONNECTED AT ONCE")]
    for px, name, sub in panels:
        f.name(px, 290, name, INK, "start"); f.tech(px, 310, sub, MUTED, "start", KEY)

    def route_row(px, ry, stops, hi=()):
        f.line(px + 20, ry, px + 20 + 50 * (len(stops) + 1), ry, ink(0.35), 1.2)
        f.rect(px + 8, ry - 10, 20, 20, STORE, SOFT, 1, rx=2); f.text(px + 18, ry + 4, "D", KEY, INK, MONO, "middle")
        xs = []
        for i, s in enumerate(stops):
            cx = px + 70 + i * 50
            xs.append(cx)
            focal = s in hi
            f.circle(cx, ry, 12, rgba(ACCENT, 0.12) if focal else SERVICE, ACCENT if focal else SOFT, 1.4 if focal else 1)
            f.text(cx, ry + 4, s, KEY, INK, MONO, "middle")
        return xs

    a = route_row(30, 370, ["1", "2", "X", "3"], ("X",)); b = route_row(30, 470, ["4", "5", "6"])
    f.route([(a[2], 382), (a[2], 420), (b[1] + 25, 420), (b[1] + 25, 458)], "accent")
    f.text(30, 530, "X leaves route A and enters route B", KEY, MUTED, MONO)
    c = route_row(440, 370, ["1", "X", "3", "4"], ("X",)); d = route_row(440, 470, ["5", "Y", "6"], ("Y",))
    f.arrow(c[1] - 6, 382, d[1] - 6, 458, "accent"); f.arrow(d[1] + 6, 458, c[1] + 6, 382, "accent")
    f.text(440, 530, "X and Y exchange routes in one move", KEY, MUTED, MONO)
    e = route_row(850, 370, ["1", "2", "3", "4", "5"], ("2", "3", "4")); g = route_row(850, 470, ["1", "4", "3", "2", "5"], ("4", "3", "2"))
    f.arrow(850 + 120 + 50, 386, 850 + 120 + 50, 454, "muted")
    f.text(850, 530, "2 to 4 reversed: three links change", KEY, MUTED, MONO)
    f.text(850, 548, "together, which one step at a time cannot", KEY, MUTED, MONO)
    f.legend(606, [("swatch", SERVICE, "A SEARCH STEP"), ("swatch", rgba(ACCENT, 0.12), "WHAT THE MOVE CHANGES"), ("swatch", STORE, "DEPOT")], note="Tabu also keeps a short list of just-moved visits it will not move back at once")
    return f


def budgets29(rec: dict) -> Figure:
    f = Figure("budgets29", "September 29: which search beats Tabu depends on the budget", "Line chart of the mean saving against Tabu in modeled dollars per case at 15, 30 and 60 second budgets for five alternatives, from the September 29 field validation; K opt trails Tabu at 15 s and leads at 60 s.", 1280, 560)
    p = Plot(f, 150, 48, 940, 440)
    budgets, values = rec["budgets"], rec["values"]
    allv = [v for row in values.values() for v in row]
    floor, ceiling, ymap = signed_axis(f, p, min(allv), max(allv), "SAVING VS TABU, $ PER CASE", lambda v: money(v).replace(".00", ""))
    xs = [p.x0 + 60 + i * (p.x1 - p.x0 - 120) / (len(budgets) - 1) for i in range(len(budgets))]
    for x, b in zip(xs, budgets):
        f.line(x, p.y0, x, p.y1, ink(0.06), 0.8); f.tech(x, p.y1 + 22, f"{b} S", MUTED)
    f.tech((p.x0 + p.x1) / 2, p.y1 + 46, "SEARCH BUDGET PER CASE", MUTED)
    labels = []
    for i, (variant, row) in enumerate(values.items()):
        focal = variant == "KOPT"
        color = ACCENT if focal else SERIES[i % len(SERIES)]
        p.polyline([(x, ymap(v)) for x, v in zip(xs, row)], color, focal=focal)
        if not focal:
            for x, v in zip(xs, row):
                f.circle(x, ymap(v), 3, color)
        labels.append((ymap(row[-1]), f"{LABEL[variant]} {money(row[-1])}", color))
    end_labels(f, labels, xs[-1] + 16)
    f.label(p.x0 + 70, ymap(0) - 6, "TABU, THE CONTROL", INK, KEY, "start")
    f.callout(1010, 210, ["At 15 s nothing but the", "uncapped original beat", "Tabu; at 60 s the K opt", "family led. The fleet", "split came next."])
    f.legend(526, [("line", ACCENT, "K OPT"), ("line", SERIES[0], "THE OTHER ALTERNATIVES"), ("line", ink(0.45), "TABU (ZERO)")], note="paired means from the field validation; synthetic clustered days")
    return f


def wtl(rec: dict) -> Figure:
    f = Figure("wtl", "Wins, ties and losses against Tabu, case by case", "Diverging bars of matched cases won (right) and lost (left) against Tabu by each configuration in the Tabu variant study and in the follow-up on new fixtures, with ties and each configuration's mean saving.", 1280, 620)
    panels = [("first", "The Tabu variant study, 16 fixtures", 40, 300), ("follow", "The follow-up, 40 new fixtures", 360, 520)]
    cx, half = 600, 260
    for key, title, y0, y1 in panels:
        rows = rec[key]
        n = max(r["wins"] + r["ties"] + r["losses"] for r in rows)
        f.panel_title(40, y0 + 4, title)
        f.tech(cx, y0 + 4, f"{n} MATCHED CASES PER CONFIGURATION", MUTED, size=KEY)
        top = y0 + 28
        pitch = (y1 - top) / len(rows)
        f.line(cx, top, cx, y1 - 4, ink(0.45), 1.2)
        for i, r in enumerate(rows):
            y = top + i * pitch + pitch / 2
            focal = r["solver"] == rec["chosen"][key]
            wl, ll = r["wins"] / n * half, r["losses"] / n * half
            f.rect(cx, y - 9, wl, 18, rgba(ACCENT, 0.18) if focal else rgba(SERIES[1], 0.35), ACCENT if focal else SERIES[1], 1)
            f.rect(cx - ll, y - 9, ll, 18, rgba(LOSS, 0.25), LOSS, 1)
            f.text(cx - half - 16, y + 5, LABEL[r["solver"]], KEY + 1, INK, SANS, "end", weight="700")
            f.text(cx + half + 24, y + 5, f"{r['wins']} / {r['ties']} / {r['losses']}", KEY, MUTED, MONO)
            f.text(cx + half + 190, y + 5, money(r["mean"]), KEY, ACCENT if focal else (INK if r["mean"] >= 0 else LOSS), MONO, weight="700" if focal else None)
        f.eyebrow_text(cx + half + 24, top - 6, "WIN / TIE / LOSS", SOFT); f.eyebrow_text(cx + half + 190, top - 6, "MEAN SAVING", SOFT)
    f.tech(cx - half / 2, 548, "LOSES TO TABU", LOSS, size=KEY); f.tech(cx + half / 2, 548, "BEATS TABU", SERIES[1], size=KEY)
    f.legend(590, [("swatch", rgba(SERIES[1], 0.35), "CASES WON"), ("swatch", rgba(LOSS, 0.25), "CASES LOST"), ("swatch", rgba(ACCENT, 0.18), "THE CONFIGURATION EACH STUDY CHOSE")], note="a win is a lower accepted cost in exact cents")
    return f


def late_mechanics(rec: dict) -> Figure:
    f = Figure("late_mechanics", "Why late acceptance loses on large fleets, and what Tabu + K opt costs", "Left: the point in the cost phase where each search found its best schedule, by fleet size, in the Tabu variant study. Right: mean fairness variance of the accepted schedules, with the fairness search on (first study) and with the cost search only (follow-up).", 1280, 580)
    p = Plot(f, 120, 64, 560, 420); p.frame("When the best schedule was found")
    p.ygrid(100, 5, "% OF THE COST PHASE", lambda v: f"{v:.0f}%")
    fleets = rec["fleets"]
    xs = [p.x0 + 40 + i * (p.x1 - p.x0 - 80) / (len(fleets) - 1) for i in range(len(fleets))]
    for x, fl in zip(xs, fleets):
        f.tech(x, p.y1 + 22, f"{fl}", MUTED)
    f.tech((p.x0 + p.x1) / 2, p.y1 + 46, "TECHNICIANS", MUTED)
    labels = []
    for i, (solver, row) in enumerate(rec["best_fraction"].items()):
        focal = solver == "TABU_KOPT"
        color = ACCENT if focal else [SERIES[2], SERIES[1], LOSS, SERIES[4]][i % 4]
        p.polyline([(x, p.ymap(v * 100, 100)) for x, v in zip(xs, row)], color, focal=True)
        labels.append((p.ymap(row[-1] * 100, 100), f"{LABEL[solver]} {row[-1] * 100:.0f}%", color))
    end_labels(f, labels, xs[-1] + 14)
    v = Plot(f, 860, 64, 1240, 420); v.frame("Fairness variance, lower is more even")
    groups = rec["variance"]
    ceiling = nice_ceiling(max(e["value"] for g in groups for e in g["rows"]))
    v.ygrid(ceiling, None, "MEAN VARIANCE", lambda t: f"{t:.3f}".rstrip("0").rstrip(".") if t else "0")
    count = sum(len(g["rows"]) for g in groups) + len(groups) - 1
    pitch = (v.x1 - v.x0) / count
    k = 0
    short = {"TABU": "Tabu", "TABU_KOPT": "T+K", "TABU_KOPT_SIZE_3": "T+K s3", "LATE_ACCEPTANCE": "LA", "KOPT": "K opt"}
    for g in groups:
        start = k
        for e in g["rows"]:
            cx = v.x0 + pitch * (k + 0.5)
            focal = e["solver"] == "TABU_KOPT"
            top = v.ymap(e["value"], ceiling)
            control = e["solver"] == "TABU"
            v.bar(cx, pitch * 0.6, top, focal=focal, fill=rgba(SERIES[1], 0.30) if control else None, stroke=SERIES[1] if control else None)
            f.tech(cx, top - 6, f"{e['value']:.4f}", MUTED, size=9)
            f.text(cx, v.y1 + 18, short[e["solver"]], 10, INK, SANS, "middle", weight="700")
            k += 1
        f.tech(v.x0 + pitch * (start + (k - start) / 2), v.y1 + 40, g["label"], MUTED, size=10)
        k += 1
    f.legend(546, [("line", ACCENT, "TABU + K OPT (T+K)"), ("swatch", rgba(SERIES[1], 0.30), "TABU, THE CONTROL"), ("swatch", rgba(MUTED, 0.15), "OTHERS (LA: LATE ACCEPTANCE)")])
    return f


def long_arm(rec: dict) -> Figure:
    f = Figure("long_arm", "The long-budget arm: late acceptance is slow, not wrong", "Mean saving against Tabu + K opt at 240, 480 and 600 seconds for K opt, late acceptance and Tabu at 30 and 50 technicians in the follow-up's long-budget arm; budgets beyond 240 s are beyond what a client can set.", 1280, 540)
    budgets = rec["budgets"]
    allv = [v for fl in rec["fleets"].values() for row in fl.values() for v in row]
    for j, (fleet, rows) in enumerate(rec["fleets"].items()):
        p = Plot(f, 150 + j * 610, 64, 560 + j * 610, 410); p.frame(f"{fleet} technicians")
        floor, ceiling, ymap = signed_axis(f, p, min(allv), max(allv), "SAVING VS TABU + K OPT, $", lambda v: money(v).replace(".00", ""))
        xs = [p.x0 + 30 + i * (p.x1 - p.x0 - 160) / (len(budgets) - 1) for i in range(len(budgets))]
        f.rect(xs[0] + 24, p.y0, p.x1 - xs[0] - 24, p.y1 - p.y0, rgba(MUTED, 0.06))
        f.eyebrow_text(xs[0] + 32, p.y0 + 14, "BEYOND A CLIENT'S 240 S", SOFT)
        for x, b in zip(xs, budgets):
            f.tech(x, p.y1 + 22, f"{b} S", MUTED)
        labels = []
        for solver, row in rows.items():
            color = {"KOPT": SERIES[1], "LATE_ACCEPTANCE": ACCENT, "TABU": LOSS}[solver]
            p.polyline([(x, ymap(v)) for x, v in zip(xs, row)], color, focal=True)
            labels.append((ymap(row[-1]), money(row[-1]), color))
        end_labels(f, labels, xs[-1] + 12)
        f.label(p.x0 + 8, ymap(0) + 18, "TABU + K OPT", INK, KEY, "start")
    f.legend(506, [("line", ACCENT, "LATE ACCEPTANCE"), ("line", SERIES[1], "K OPT"), ("line", LOSS, "TABU")], note=f"{rec['cases_per_point']} cases per point; zero is Tabu + K opt")
    return f


# ---- Part IV: the booking search -----------------------------------------------------------------
def _stops(f: Figure, x: float, y: float, labels: list[str], hi: tuple = (), new: str | None = None, gap: float = 64) -> list[float]:
    f.line(x + 10, y, x + 10 + gap * (len(labels) + 1), y, ink(0.35), 1.2)
    f.rect(x, y - 10, 20, 20, STORE, SOFT, 1, rx=2); f.text(x + 10, y + 4, "D", KEY, INK, MONO, "middle")
    xs = []
    for i, s in enumerate(labels):
        cx = x + 10 + gap * (i + 1)
        xs.append(cx)
        is_new = s == new
        f.circle(cx, y, 13, rgba(ACCENT, 0.14) if is_new else (rgba(SERIES[2], 0.35) if s in hi else SERVICE), ACCENT if is_new else SOFT, 1.4 if is_new else 1)
        f.text(cx, y + 4, s, KEY, INK, MONO, "middle")
    return xs


def booking_methods(_=None) -> Figure:
    f = Figure("booking_methods", "Two ways to fit a new job into a booked day", "Insertion tries the new visit in every gap of every eligible route and moves nothing else; bounded search also tries small rearrangements around the affected work, within limits on routes, depth and candidates.", 1280, 540)
    f.panel_title(40, 48, "Insertion"); f.tech(40, 70, "THE NEW VISIT IN EVERY GAP; NOTHING ELSE MOVES", MUTED, "start", KEY)
    xs = _stops(f, 40, 140, ["1", "2", "3", "4"])
    for a, b in zip([50] + xs, xs + [xs[-1] + 64]):
        mx = (a + b) / 2
        f.line(mx, 116, mx, 128, ACCENT, 1.2, "3,2")
    f.circle(xs[1] + 32, 92, 13, rgba(ACCENT, 0.14), ACCENT, 1.4); f.text(xs[1] + 32, 96, "N", KEY, INK, MONO, "middle")
    f.tech(xs[1] + 52, 96, "TRIED IN EACH OF 5 GAPS", ACCENT, "start", KEY)
    xs = _stops(f, 40, 230, ["1", "2", "N", "3", "4"], new="N")
    f.tech(40, 278, "the first gap that keeps every promise and adds no overtime, per window", MUTED, "start", KEY)
    f.panel_title(680, 48, "Bounded search"); f.tech(680, 70, "ALSO SMALL REARRANGEMENTS AROUND THE NEW WORK", MUTED, "start", KEY)
    xa = _stops(f, 680, 140, ["1", "2", "3", "4"], hi=("3",))
    xb = _stops(f, 680, 230, ["5", "6"], gap=64)
    f.route([(xa[2], 153), (xa[2], 186), (xb[1] + 32, 186), (xb[1] + 32, 217)], "muted")
    f.tech(xa[2] + 14, 182, "3 RELOCATED", MUTED, "start", KEY)
    f.circle(xa[1] + 32, 92, 13, rgba(ACCENT, 0.14), ACCENT, 1.4); f.text(xa[1] + 32, 96, "N", KEY, INK, MONO, "middle")
    f.arrow(xa[1] + 32, 105, xa[1] + 32, 124, "accent")
    f.tech(xa[1] + 52, 96, "N TAKES THE ROOM 3 LEFT", ACCENT, "start", KEY)
    f.tech(680, 278, "relocation, swap or reversal; limits on routes, depth and candidates", MUTED, "start", KEY)
    f.line(40, 316, 1240, 316, ink(0.10), 0.8)
    f.eyebrow_text(40, 340, "THE TRADE THE SEPTEMBER STUDIES MEASURED", SOFT)
    f.card(40, 356, 560, 108, "SERVICE AND COST", [("tech", "Bounded never used more paid minutes or"), ("tech", "cost than insertion where both booked alike")], focal=True)
    f.card(680, 356, 560, 108, "TIME", [("tech", "More candidates per search: a higher p95,"), ("tech", "and more of it as requests run at once")])
    f.legend(512, [("swatch", rgba(ACCENT, 0.14), "THE NEW VISIT"), ("swatch", rgba(SERIES[2], 0.35), "AN EXISTING VISIT THAT MOVES"), ("swatch", STORE, "DEPOT")])
    return f


def workload24(rec: list) -> Figure:
    f = Figure("workload24", "September 24: what bounded search saved, by workload", "Bars of paid minutes and modeled dollars bounded search saved per case against insertion, by workload, over the sequential cases where both methods booked the same requests.", 1280, 500)
    for j, (key, title, unit, fmt_v) in enumerate((("paid_min", "Paid minutes saved per case", "MINUTES PER CASE", lambda v: f"{v:.1f}"), ("cost", "Modeled cost saved per case", "$ PER CASE", money))):
        p = Plot(f, 140 + j * 620, 64, 560 + j * 620, 380); p.frame(title)
        ceiling = nice_ceiling(max(e[key] for e in rec))
        p.ygrid(ceiling, None, unit, (lambda v: f"{v:.0f}") if key == "paid_min" else (lambda v: f"${v:.0f}"))
        for cx, e in zip(p.columns(len(rec)), rec):
            focal = e["workload"] == "CLUSTERED"
            top = p.ymap(e[key], ceiling)
            p.bar(cx, 80, top, focal=focal)
            f.tech(cx, top - 8, fmt_v(e[key]), ACCENT if focal else MUTED, size=KEY)
            f.text(cx, p.y1 + 22, e["workload"].title(), KEY + 1, INK, SANS, "middle", weight="700")
            f.tech(cx, p.y1 + 40, f"{e['cases']} CASES", MUTED, size=KEY)
    f.legend(466, [("swatch", rgba(ACCENT, 0.12), "WHERE THE GAIN CONCENTRATES"), ("swatch", rgba(MUTED, 0.15), "WORKLOAD")], note="sequential cases where both methods booked the same ten requests")
    return f


def deadline(_=None) -> Figure:
    f = Figure("deadline", "Inside the deadline: when the second pass runs, and how it ends", "Flowchart of a booking search's refinement pass: required when the first pass found few windows, optional and capped otherwise, shed when other bookings wait, and dropped in favour of the first pass's offers when it fails; each path reports refinement and searchComplete.", 1280, 700)
    f.node(30, 56, 220, 72, "Insertion pass", ["THE REGULAR WINDOWS"], SERVICE)
    f.diamond(400, 92, 220, 120, ["MORE WINDOWS", "THAN THE CLIENT'S", "THRESHOLD?"])
    f.arrow(250, 92, 290, 92, "muted")
    f.diamond(720, 92, 220, 120, ["BOOKINGS", "WAITING FOR", "A SLOT?"], focal=True)
    f.arrow(510, 92, 610, 92, "muted"); f.label(560, 82, "YES", SOFT, KEY)
    f.node(960, 56, 290, 72, "SHED (#155)", ["INSERTION OFFERS, AT ONCE"], rgba(ACCENT, 0.08), ACCENT, 1.4)
    f.arrow(830, 92, 960, 92, "accent"); f.label(895, 82, "YES", ACCENT, KEY)
    f.node(560, 236, 320, 72, "Optional refinement", ["CAPPED: 250 MS UNLESS THE CLIENT SETS"], SERVICE)
    f.arrow(720, 152, 720, 236, "muted"); f.label(744, 198, "NO", SOFT, KEY)
    f.node(80, 236, 380, 72, "Required refinement", ["MAY USE THE REST OF THE DEADLINE"], SERVICE)
    f.arrow(400, 152, 400, 236, "muted"); f.label(424, 198, "NO", SOFT, KEY)
    f.diamond(720, 420, 220, 104, ["RESULT VALID?"])
    f.diamond(270, 420, 220, 104, ["RESULT VALID?"])
    f.arrow(720, 308, 720, 368, "muted"); f.arrow(270, 308, 270, 368, "muted")
    f.node(960, 384, 290, 72, "UNAVAILABLE (#138)", ["INSERTION OFFERS PUBLISHED"], PAPER2)
    f.arrow(830, 420, 960, 420, "muted"); f.label(895, 410, "NO", SOFT, KEY)
    f.route([(380, 420), (530, 420), (530, 624), (1105, 624), (1105, 456)], "muted"); f.label(455, 410, "NO", SOFT, KEY)
    f.node(560, 536, 320, 64, "COMPLETE or SHORTENED", ["SEARCHCOMPLETE STAYS TRUE (#154)"], rgba(ACCENT, 0.08), ACCENT, 1.4)
    f.arrow(720, 472, 720, 536, "accent"); f.label(744, 512, "YES", ACCENT, KEY)
    f.node(30, 536, 480, 64, "COMPLETE, or cut short by the deadline", ["SEARCHCOMPLETE FALSE AND THE DATES LISTED"], SERVICE)
    f.arrow(270, 472, 270, 536, "muted"); f.label(294, 512, "YES", SOFT, KEY)
    f.callout(960, 160, ["Every path publishes offers that", "passed validation on their own:", "nothing unchecked is offered."])
    f.legend(676, [("swatch", SERVICE, "A PASS OR AN OUTCOME"), ("swatch", rgba(ACCENT, 0.08), "THE API-ERA CHANGES THAT KEPT OFFERS FAST"), ("swatch", PAPER2, "A FAILURE KEPT SMALL")], note="the whole call is bounded by bookingDeadlineMs, at most 5 s")
    return f


def api_fixes(rec: dict) -> Figure:
    f = Figure("api_fixes", "The three fixes to the second pass, measured", "Left: searches at five desks that reported searchComplete false before and after #154. Middle: searches per minute and offers p95 at five desks before and after shedding (#155), on fresh addresses. Right: what #138 changed, covered by tests rather than a load run.", 1280, 520)
    inc = rec["incomplete"]
    p = Plot(f, 110, 72, 360, 380); p.frame("#154 searchComplete")
    p.ygrid(100, 5, "% OF SEARCHES", lambda v: f"{v:.0f}%")
    for cx, (name, part, whole) in zip(p.columns(2), (("BEFORE", inc["before"][0], inc["before"][1]), ("AFTER", inc["after"][0], inc["after"][1]))):
        top = p.ymap(part / whole * 100, 100)
        p.bar(cx, 60, top, focal=name == "AFTER")
        f.tech(cx, top - 8, f"{part} of {whole}", ACCENT if name == "AFTER" else MUTED, size=KEY)
        f.text(cx, p.y1 + 22, name.title(), KEY + 1, INK, SANS, "middle", weight="700")
    f.tech((p.x0 + p.x1) / 2, p.y1 + 44, "REPORTED INCOMPLETE", MUTED, size=KEY)
    sh = rec["shedding"]
    q = Plot(f, 520, 72, 900, 380); q.frame("#155 shedding, A/B at five desks")
    rows = [("SEARCHES / MIN", sh["before"]["per_minute"], sh["after"]["per_minute"], lambda v: f"{v:.0f}"), ("OFFERS P95, S", sh["before"]["p95"] / 1000, sh["after"]["p95"] / 1000, lambda v: f"{v:.1f} s")]
    for i, (name, b, a, fm) in enumerate(rows):
        y = 150 + i * 140
        ceiling = nice_ceiling(max(b, a))
        f.text(q.x0, y - 30, name, KEY, MUTED, MONO)
        f.line(q.x0, y, q.x1, y, ink(0.10), 0.8)
        xb, xa = q.xmap(b, ceiling), q.xmap(a, ceiling)
        f.line(min(xb, xa), y, max(xb, xa), y, ink(0.55), 1)
        f.circle(xb, y, 6, PAPER, MUTED, 1.5); f.circle(xa, y, 6, ACCENT, INK, 1)
        f.tech(xb, y + 24, fm(b), MUTED, size=KEY); f.tech(xa, y + 24, fm(a), ACCENT, size=KEY)
        f.tech(q.x0, y + 46, f"0 TO {fm(ceiling)}", SOFT, "start", 9)
    f.card(980, 72, 270, 300, "#138, DECIDED 2026-10-13", [("name", "Invalid refinement"), ("tech", "used to fail the whole"), ("tech", "search; now the insertion"), ("tech", "offers are published with"), ("tech", "refinement: UNAVAILABLE"), ("muted", "logged and counted;"), ("muted", "an invalid insertion"), ("muted", "result still fails"), ("muted", "covered by tests")])
    f.legend(486, [("hollow-dot", PAPER, "BEFORE"), ("solid-dot", ACCENT, "AFTER"), ("swatch", rgba(ACCENT, 0.12), "AFTER THE FIX")], note="every search still returned four offers")
    return f


# ---- Part IV: routing ----------------------------------------------------------------------------
def paradox(rec: dict) -> Figure:
    f = Figure("paradox", "September's paradox: a far faster road query, no faster booking", "Left: p50 of a 16-pair routing request against the routing service alone, flexible against CH, with one and ten callers on September 24. Right: the same two graphs under a 720-request booking run on real Omaha roads: requests served and pooled p95.", 1280, 520)
    alone = rec["alone"]
    p = Plot(f, 120, 72, 520, 380); p.frame("Routing service alone, p50")
    ceiling = nice_ceiling(max(e["p50"] for e in alone))
    p.ygrid(ceiling, None, "MS PER 16-PAIR REQUEST")
    cols = p.columns(len(alone))
    for cx, e in zip(cols, alone):
        focal = e["engine"] == "CH-car-v1"
        top = p.ymap(e["p50"], ceiling)
        p.bar(cx, 52, top, focal=focal)
        f.tech(cx, top - 8, f"{e['p50']:.1f}", ACCENT if focal else MUTED, size=KEY)
        f.text(cx, p.y1 + 22, "CH" if focal else "Flexible", KEY + 1, INK, SANS, "middle", weight="700")
    f.tech((cols[0] + cols[1]) / 2, p.y1 + 44, "1 CALLER", MUTED, size=KEY); f.tech((cols[2] + cols[3]) / 2, p.y1 + 44, "10 CALLERS", MUTED, size=KEY)
    bk = rec["booking"]
    for j, (key, title, unit, fm) in enumerate((("served", f"Served of {bk['requests']}", "REQUESTS SERVED", lambda v: f"{v:.0f}"), ("p95", "Pooled p95", "SECONDS", lambda v: f"{v:.2f} s"))):
        q = Plot(f, 700 + j * 300, 72, 900 + j * 300, 380); q.frame(title)
        vals = [bk["flexible"][key] / (1000 if key == "p95" else 1), bk["ch"][key] / (1000 if key == "p95" else 1)]
        ceiling = nice_ceiling(max(vals))
        q.ygrid(ceiling, None, unit, (lambda v: f"{v:.0f}") if key == "served" else (lambda v: f"{v:.0f}"))
        for cx, name, v in zip(q.columns(2), ("Flexible", "CH"), vals):
            top = q.ymap(v, ceiling)
            q.bar(cx, 52, top, focal=name == "CH")
            f.tech(cx, top - 8, fm(v), ACCENT if name == "CH" else MUTED, size=KEY)
            f.text(cx, q.y1 + 22, name, KEY + 1, INK, SANS, "middle", weight="700")
    f.legend(486, [("swatch", rgba(MUTED, 0.15), "FLEXIBLE GRAPH"), ("swatch", rgba(ACCENT, 0.12), "CONTRACTION HIERARCHY")], note="same map, same pairs, every leg identical to the metre")
    return f


def ch_mechanism(_=None) -> Figure:
    f = Figure("ch_mechanism", "How a contraction hierarchy answers with less work", "Schematic of a contraction hierarchy on eight junctions of one road: at import every junction is ranked by importance and shortcut edges stand for chains through less important junctions; a query searches only upward from both ends, follows the shortcuts and meets at the most important junction between them, with the same answer as the full graph.", 1280, 580)
    base, step = 440, 72
    ranks = [0, 2, 1, 4, 0, 3, 1, 0]
    xs = [100 + i * 100 for i in range(len(ranks))]
    ys = [base - r * step for r in ranks]
    f.line(60, base + 36, 860, base + 36, ink(0.35), 2)
    f.tech(60, base + 60, "THE ROAD AS DRIVEN: EIGHT JUNCTIONS, LEFT TO RIGHT", MUTED, "start", KEY)
    for x, y in zip(xs, ys):
        f.line(x, y + 12, x, base + 36, ink(0.12), 1, "2,3")
    # Shortcuts added at import: 2 to 4 skips junction 3, 6 to 4 skips junction 5. They enter junction 4 at separate points.
    x4, y4 = xs[3], ys[3]
    f.route([(xs[1], ys[1] - 12), (xs[1], 104), (x4 - 6, 104), (x4 - 6, y4 - 11)], "accent", "5,4")
    f.route([(xs[5], ys[5] - 12), (xs[5], 116), (x4 + 6, 116), (x4 + 6, y4 - 11)], "accent", "5,4")
    f.label((xs[1] + x4) / 2, 96, "SHORTCUT", ACCENT, KEY); f.label((xs[5] + x4) / 2, 108, "SHORTCUT", ACCENT, KEY)
    # The upward searches over real roads: S up to rank 2, T up to rank 1 and then rank 3.
    f.route([(xs[0], ys[0] - 12), (xs[0], ys[1]), (xs[1] - 12, ys[1])], "accent")
    f.route([(xs[7], ys[7] - 12), (xs[7], ys[6]), (xs[6] + 12, ys[6])], "accent")
    f.route([(xs[6], ys[6] - 12), (xs[6], ys[5]), (xs[5] + 12, ys[5])], "accent")
    for i, (x, y, r) in enumerate(zip(xs, ys, ranks)):
        end, top, skipped = i in (0, 7), r == 4, i in (2, 4)
        fill = rgba(ACCENT, 0.14) if top else (HOST if end else (PAPER2 if skipped else SERVICE))
        f.circle(x, y, 12, fill, ACCENT if top else SOFT, 1.4 if top else 1)
        f.text(x, y + 4, "S" if i == 0 else ("T" if i == 7 else str(r)), KEY, INK, MONO, "middle")
    f.label(x4 + 22, y4 + 30, "THE SEARCHES MEET", ACCENT, KEY, "start")
    f.text(40, 300, "RANK", KEY, MUTED, MONO, "middle", extra='transform="rotate(-90 40 300)"')
    f.card(900, 40, 340, 196, "AT IMPORT", [("tech", "rank every junction by"), ("tech", "importance; add a shortcut"), ("tech", "wherever a junction's removal"), ("tech", "would break a shortest path")])
    f.card(900, 256, 340, 196, "THE PRICE", [("tech", "about 27 s more import"), ("tech", "about 200 MiB per map version"), ("tech", "its own routing identity, so a"), ("tech", "switch starts every cache cold")])
    f.legend(546, [("swatch", HOST, "START, TARGET"), ("swatch", PAPER2, "NEVER VISITED"), ("line", ACCENT, "UPWARD SEARCH"), ("line-dashed", ACCENT, "SHORTCUT FROM IMPORT")], note="same answer to the metre")
    return f


def j_routing(rec: list) -> Figure:
    f = Figure("j_routing", "Study J: three runs, three measures", "For the VM baseline on the flexible graph (J0), the flexible graph with parallel pair routing, and the CH graph: booking searches answered in 60 s at five desks, routing time per fetched leg, and the routing container's mean CPU in cores.", 1280, 500)
    panels = (("searches", "Searches answered in 60 s", "SEARCHES", lambda v: f"{v:,.0f}"), ("per_leg", "Routing time per fetched leg", "MS PER LEG", lambda v: f"{v:.2f}"), ("routing_cores", "Routing CPU", "CORES", lambda v: f"{v:.1f}"))
    for j, (key, title, unit, fm) in enumerate(panels):
        p = Plot(f, 120 + j * 400, 64, 400 + j * 400, 380); p.frame(title)
        ceiling = nice_ceiling(max(e[key] for e in rec))
        p.ygrid(ceiling, None, unit)
        for cx, e in zip(p.columns(len(rec)), rec):
            focal = e["run"] == "J CH"
            top = p.ymap(e[key], ceiling)
            p.bar(cx, 52, top, focal=focal)
            f.tech(cx, top - 8, fm(e[key]), ACCENT if focal else MUTED, size=KEY)
            f.text(cx, p.y1 + 22, e["run"], KEY + 1, INK, SANS, "middle", weight="700")
    f.legend(466, [("swatch", rgba(MUTED, 0.15), "FLEXIBLE GRAPH"), ("swatch", rgba(ACCENT, 0.12), "CH, THE ONE KEPT")], note="five desks, fresh seeds, the VM-shaped rehearsal stack")
    return f


# ---- Part IV: admission and concurrency ---------------------------------------------------------
def holds(rec: list) -> Figure:
    f = Figure("holds", "Holds lost and the latency tail, before and after rule 0", "Grouped bars by booking desks: the share of selected holds lost at confirm in the first load measurement (#151) and after rule 0 (#152, fresh seeds), and offers p95 at the caller against the 5.5 s bound.", 1280, 520)
    p = Plot(f, 120, 64, 560, 380); p.frame("Selected holds lost at confirm")
    lost_ceiling = nice_ceiling(max(e["before_lost"] / e["before_selected"] * 100 for e in rec))
    p.ygrid(lost_ceiling, None, "% OF SELECTED HOLDS", lambda v: f"{v:.0f}%")
    q = Plot(f, 760, 64, 1200, 380); q.frame("Offers p95 at the caller")
    ceiling = nice_ceiling(max(max(e["before_p95"], e["after_p95"]) for e in rec) / 1000)
    q.ygrid(ceiling, None, "SECONDS")
    yb = q.ymap(5.5, ceiling); f.line(q.x0, yb, q.x1, yb, ACCENT, 1.2, "6,4"); f.label(q.x0 + 6, yb - 6, "5.5 S BOUND", ACCENT, KEY, "start")
    for plot, getb, geta, fm in ((p, lambda e: e["before_lost"] / e["before_selected"] * 100, lambda e: e["after_lost"] / e["after_selected"] * 100, lambda v: f"{v:.0f}%"),
                                 (q, lambda e: e["before_p95"] / 1000, lambda e: e["after_p95"] / 1000, lambda v: f"{v:.1f}")):
        top_v = lost_ceiling if plot is p else ceiling
        for cx, e in zip(plot.columns(len(rec)), rec):
            for dx, v, after in ((-26, getb(e), False), (26, geta(e), True)):
                top = plot.ymap(v, top_v)
                plot.bar(cx + dx, 44, top, focal=after)
                f.label(cx + dx, top - 6, fm(v), ACCENT if after else MUTED, KEY)
            f.text(cx, plot.y1 + 22, f"{e['desks']} desk" + ("s" if e["desks"] > 1 else ""), KEY + 1, INK, SANS, "middle", weight="700")
    f.legend(486, [("swatch", rgba(MUTED, 0.15), "#151, THE FIRST MEASUREMENT"), ("swatch", rgba(ACCENT, 0.12), "#152, RULE 0, FRESH SEEDS")], note="no hold was lost in any run after the fix")
    return f


def race(_=None) -> Figure:
    f = Figure("race", "How a late snapshot lost holds, and how rule 0 stops it", "Sequence of two booking desks and the scheduler: desk B confirms and the day moves on; desk A's request, built from older records, arrives late. Before #152 rule 3 read it as a host edit and lost every hold on the date; with rule 0 it is answered 409 STALE and nothing is lost.", 1280, 700)
    lanes = {"A": 200, "B": 560, "S": 1000}
    f.node(80, 32, 240, 56, "Desk A", ["BUILT ITS SNAPSHOT EARLY"], HOST)
    f.node(440, 32, 240, 56, "Desk B", ["CONFIRMS FIRST"], HOST)
    f.node(880, 32, 240, 56, "Scheduler", ["THE DATE'S ARRANGEMENT"], SERVICE)
    for x in lanes.values():
        f.line(x, 88, x, 620, ink(0.20), 1, "3,3")
    f.label(lanes["A"] + 12, 126, "READS THE DAY AT T1", INK, KEY, "start")
    f.arrow(lanes["B"] + 4, 164, lanes["S"] - 4, 164, "link"); f.label((lanes["B"] + lanes["S"]) / 2, 156, "CONFIRM WITH T1", LINK, KEY)
    f.arrow(lanes["S"] - 4, 204, lanes["B"] + 4, 204, "muted", "5,4"); f.label((lanes["B"] + lanes["S"]) / 2, 196, "200 RECEIPT · THE DAY MOVES TO T2", SOFT, KEY)
    f.arrow(lanes["A"] + 4, 252, lanes["S"] - 4, 252, "link"); f.label((lanes["A"] + lanes["S"]) / 2 + 120, 244, "SEARCH WITH T1, ARRIVING LATE", LINK, KEY)
    fx, fw = 120, 1020
    f.rect(fx, 280, fw, 300, ink(0.02), ink(0.22), 1, rx=4)
    f.rect(fx, 280, 40, 16, PAPER, ink(0.22), 1, rx=2); f.text(fx + 20, 292, "ALT", 8, MUTED, MONO, "middle", spacing="0.12em")
    f.text(fx + 12, 316, "[BEFORE #152]", TECH, MUTED, MONO)
    f.label(lanes["S"] - 12, 352, "T1 IS NOT T2: LOOKS LIKE A HOST EDIT (RULE 3)", INK, KEY, "end")
    f.arrow(lanes["S"] - 4, 388, lanes["A"] + 4, 388, "muted", "5,4"); f.label((lanes["A"] + lanes["S"]) / 2, 380, "OFFERS · EVERY HOLD ON THE DATE LOST", LOSS, KEY)
    f.text(lanes["B"] + 12, 412, "B's next customer finds its hold gone: 409 HOLD_UNAVAILABLE", KEY, LOSS, MONO)
    f.line(fx + 8, 436, fx + fw - 8, 436, ink(0.20), 1, "4,3")
    f.text(fx + 12, 460, "[WITH RULE 0]", TECH, ACCENT, MONO)
    f.label(lanes["S"] - 12, 494, "T1 IS AMONG THE LAST 32 TIMESTAMPS MOVED PAST", INK, KEY, "end")
    f.arrow(lanes["S"] - 4, 530, lanes["A"] + 4, 530, "accent"); f.label((lanes["A"] + lanes["S"]) / 2, 522, "409 STALE · NOTHING RECONCILED, NOTHING LOST", ACCENT, KEY)
    f.text(lanes["A"] + 12, 556, "A rebuilds from current records and resends", KEY, INK, MONO)
    f.callout(160, 616, ["Any host with several desks produces such late requests; the cost of rule 0 is a resend, never a lost customer."])
    f.legend(670, [("arrow-link", LINK, "CALL"), ("arrow-dashed", MUTED, "REPLY"), ("line", ACCENT, "THE RULE THAT KEEPS HOLDS")], note="the comparison is still exact equality, never ordering")
    return f


def slots(_=None) -> Figure:
    f = Figure("slots", "Admission on a booking replica, before and after J2", "Two panels: before J2 a confirm queued behind waiting searches for one of two search slots; after J2 admission chooses a confirm before any waiting search and a confirm-only slot beyond the search capacity means it never waits for a running search.", 1280, 520)
    for j, title in enumerate(("Before J2", "After J2 (#162)")):
        x0 = 40 + j * 620
        f.panel_title(x0, 48, title)
        f.zone(x0, 68, 580, 340, "BOOKING REPLICA")
        # The queue.
        f.eyebrow_text(x0 + 24, 104, "WAITING", SOFT)
        queue = ["SEARCH", "SEARCH", "CONFIRM"] if j == 0 else ["CONFIRM", "SEARCH", "SEARCH"]
        for i, item in enumerate(queue):
            focal = item == "CONFIRM"
            y = 116 + i * 52
            f.rect(x0 + 24, y, 150, 40, PAPER, rx=6); f.rect(x0 + 24, y, 150, 40, rgba(ACCENT, 0.10) if focal and j == 1 else (PAPER2 if focal else SERVICE), ACCENT if focal and j == 1 else SOFT, 1.4 if focal and j == 1 else 1, rx=6, dash="4,3" if focal and j == 0 else None)
            f.text(x0 + 99, y + 25, item, KEY, INK, MONO, "middle")
        f.eyebrow_text(x0 + 300, 104, "RUNNING", SOFT)
        for i in range(2):
            y = 116 + i * 70
            f.rect(x0 + 300, y, 250, 56, PAPER, rx=6); f.rect(x0 + 300, y, 250, 56, SERVICE, SOFT, 1, rx=6)
            f.text(x0 + 425, y + 24, f"SEARCH SLOT {i + 1}", KEY, INK, MONO, "middle"); f.text(x0 + 425, y + 42, "a search, up to 5 s", KEY, MUTED, MONO, "middle")
        f.arrow(x0 + 174, 136, x0 + 300, 136, "muted")
        if j == 1:
            y = 276
            f.rect(x0 + 300, y, 250, 56, PAPER, rx=6); f.rect(x0 + 300, y, 250, 56, rgba(ACCENT, 0.10), ACCENT, 1.4, rx=6)
            f.text(x0 + 425, y + 24, "CONFIRM-ONLY SLOT", KEY, INK, MONO, "middle"); f.text(x0 + 425, y + 42, "a few ms of work", KEY, MUTED, MONO, "middle")
            f.route([(x0 + 174, 136 + 12), (x0 + 237, 148), (x0 + 237, y + 28), (x0 + 300, y + 28)], "accent")
            f.label(x0 + 237, 240, "FIRST", ACCENT, KEY)
            f.para(x0 + 24, 372, ["A confirm is chosen before any waiting search", "and has a slot no search may take."], KEY, INK, MONO)
        else:
            f.para(x0 + 24, 372, ["A confirm waits for a slot like a search,", "behind every search already waiting."], KEY, MUTED, MONO)
    f.callout(40, 446, ["The confirm completes a booking the customer already chose: its wait was pure waste."])
    f.legend(490, [("swatch", SERVICE, "SEARCH WORK"), ("swatch", rgba(ACCENT, 0.10), "THE CONFIRM, AFTER J2"), ("swatch-dashed", PAPER2, "THE CONFIRM, BEFORE")], note="search capacity is 2 slots in every run here")
    return f


# ---- Part IV: inside the engine -----------------------------------------------------------------
def profile(rec: dict) -> Figure:
    f = Figure("profile", "Where a daily solve's samples went, before and after RoadMatrix", "Two stacked bars of the share of solve-scoped profiler samples by group before and after the RoadMatrix treatment; the remainder is everything the groups do not name.", 1280, 400)
    groups = rec["groups"]
    colors = {"Immutable map probe": SERIES[1], "HashMap and copies": SERIES[2], "BigDecimal arithmetic": LOSS, "Engine timing methods": SERIES[0], "Instant and Duration": SERIES[4], "Timefold score director": STORE}
    x0, x1 = 160, 1220
    for i, stage in enumerate(("before", "after")):
        y = 48 + i * 100
        f.text(x0 - 16, y + 30, stage.title(), NAME, INK, SANS, "end", weight="700")
        f.tech(x0 - 16, y + 50, f"{rec['samples'][stage]:,} SAMPLES", MUTED, "end", KEY)
        x = x0
        for g in groups:
            v = rec[stage][g]
            w = (x1 - x0) * v / 100
            f.rect(x, y, w, 56, PAPER); f.rect(x, y, w, 56, rgba(colors[g], 0.55), INK, 0.6)
            if w > 56:
                f.text(x + w / 2, y + 34, f"{v:.1f}%", KEY, INK, MONO, "middle", weight="700")
            x += w
        rest = 100 - sum(rec[stage][g] for g in groups)
        f.rect(x, y, x1 - x, 56, rgba(MUTED, 0.06), ink(0.20), 0.6)
        f.text((x + x1) / 2, y + 34, f"other {rest:.1f}%", KEY, MUTED, MONO, "middle")
    f.callout(x0, 272, ["After the treatment the largest named shares are BigDecimal and the clock arithmetic of Instant and Duration:", "the next step for performance names them."])
    items = [("swatch", rgba(colors[g], 0.55), g.upper()) for g in groups]
    f.legend(352, items[:3], note="share of solve-scoped samples, %")
    f.legend(380, items[3:], continued=True)
    return f


def roadmatrix(_=None) -> Figure:
    f = Figure("roadmatrix", "One road leg lookup, before and after RoadMatrix", "Before: every scored route built a from-to string per leg, probed an immutable map, computed buffered minutes in BigDecimal and built a map of arrivals that was then copied. After: endpoints are numbered once per fact revision and legs are read from two tables with the minutes precomputed; the score-only path builds no arrival map.", 1280, 520)
    rows = [("Before", ["\"A|B\" KEY", "MAP PROBE", "BIGDECIMAL MINUTES", "ARRIVALS MAP, COPIED"], False),
            ("After (#163)", ["ENDPOINT NUMBERS", "TABLE[i][j]", "MINUTES PRECOMPUTED", "NO ARRIVAL MAP"], True)]
    for r, (name, steps, after) in enumerate(rows):
        y = 64 + r * 150
        f.text(40, y + 40, name, NAME, INK, SANS, weight="700")
        f.tech(40, y + 60, "PER LEG, EVERY SCORE" if not after else "ONCE PER FACT REVISION", MUTED, "start", KEY)
        for i, s in enumerate(steps):
            x = 240 + i * 250
            f.rect(x, y + 12, 210, 56, PAPER, rx=6)
            f.rect(x, y + 12, 210, 56, rgba(ACCENT, 0.08) if after else PAPER2, ACCENT if after else SOFT, 1.4 if after else 1, rx=6)
            f.text(x + 105, y + 45, s, KEY, INK, MONO, "middle")
            if i:
                f.arrow(x - 40, y + 40, x, y + 40, "accent" if after else "muted")
    f.card(40, 360, 1200, 92, "THE GATE", [("tech", "A scoring exactness test captured a step-limited, fixed-seed solve on three fixtures before the change and asserts the"), ("tech", "same score, move count, arrangement and independent evaluation after it. Every number the engine computes stays the same.")], focal=True)
    f.legend(486, [("swatch", PAPER2, "WORK PER LEG BEFORE"), ("swatch", rgba(ACCENT, 0.08), "AFTER: LAZY, BUILT ON THE FIRST LOOKUP")], note="a plan evaluated once, a booking candidate, pays nothing new")
    return f


def paired_fleets(rec: list) -> Figure:
    f = Figure("paired_fleets", "Moves per second by fleet, and what more moves bought", "Per fleet size: median cost-phase moves per second before and after the RoadMatrix treatment with the speedup, the fairness-phase median speedup, and the paired cases that ended cheaper, tied or dearer at the same budget.", 1280, 440)
    q = Plot(f, 260, 64, 820, 330)
    ceiling = nice_ceiling(max(e["after_median"] for e in rec) / 1000)
    n_ticks = 5 if ceiling % 5 == 0 else 4
    for i in range(n_ticks + 1):
        v = ceiling * i / n_ticks; x = q.xmap(v, ceiling)
        f.line(x, q.y0, x, q.y1, ink(0.08) if i else ink(0.25), 0.8); f.tech(x, q.y1 + 20, f"{v:.0f}k", MUTED)
    f.tech((q.x0 + q.x1) / 2, q.y1 + 44, "COST-PHASE MOVES PER SECOND, MEDIAN", MUTED, size=KEY)
    for i, e in enumerate(rec):
        y = 120 + i * 120
        q.dumbbell(y, q.xmap(e["before_median"] / 1000, ceiling), q.xmap(e["after_median"] / 1000, ceiling), f"{e['before_median'] / 1000:.0f}k", f"{e['after_median'] / 1000:.0f}k  ({e['speedup']:.2f}x)", f"{e['fleet']} technicians", 240)
        x = 940
        f.text(x, y - 12, f"fairness phase {e['fairness_speedup']:.2f}x", KEY, MUTED, MONO)
        n = e["wins"] + e["ties"] + e["losses"]
        for k, (count, color) in enumerate(((e["wins"], ACCENT), (e["ties"], STORE), (e["losses"], LOSS))):
            for c in range(count):
                cx = x + 6 + (sum((e["wins"], e["ties"], e["losses"])[:k]) + c) * 22
                f.rect(cx - 8, y, 16, 16, rgba(color, 0.35) if color != STORE else STORE, color if color != STORE else SOFT, 1, rx=2)
        f.text(x, y + 36, f"{e['wins']} cheaper · {e['ties']} tied · {e['losses']} dearer of {n}", KEY, INK, MONO)
    f.legend(406, [("hollow-dot", PAPER, "BEFORE"), ("solid-dot", ACCENT, "AFTER"), ("swatch", rgba(ACCENT, 0.35), "CHEAPER AT THE SAME BUDGET"), ("swatch", rgba(LOSS, 0.35), "DEARER")], note="no constraint violation on either side")
    return f


def round_trips(rec: dict) -> Figure:
    f = Figure("round_trips", "Why a remote engine costs booking more than it saves", "Left: an embedded booking search calls the engine in process. Right: with a remote pool each of up to three engine calls (insertion, refinement, the retry on a moved day) encodes a snapshot, sends it, waits and decodes the answer; the engine share of a search grows accordingly.", 1280, 620)
    for j, (title, remote) in enumerate((("Embedded (the default)", False), ("Remote solver pool", True))):
        x0 = 40 + j * 620
        f.panel_title(x0, 48, title)
        rx, sx = x0 + 120, x0 + 460
        f.node(rx - 100, 68, 200, 52, "Booking replica", [], SERVICE)
        if remote:
            f.node(sx - 100, 68, 200, 52, "Solver pool", [], STORE)
            for x in (rx, sx):
                f.line(x, 120, x, 470, ink(0.20), 1, "3,3")
            for i, call in enumerate(("INSERTION", "REFINEMENT", "RETRY, IF THE DAY MOVED")):
                y = 160 + i * 104
                f.arrow(rx + 4, y, sx - 4, y, "link"); f.label((rx + sx) / 2, y - 8, f"{call}: ENCODE, SEND", LINK, KEY)
                f.arrow(sx - 4, y + 40, rx + 4, y + 40, "muted", "5,4"); f.label((rx + sx) / 2, y + 32, "DECODE THE ANSWER", SOFT, KEY)
        else:
            f.line(rx, 120, rx, 470, ink(0.20), 1, "3,3")
            for i, call in enumerate(("INSERTION", "REFINEMENT", "RETRY, IF THE DAY MOVED")):
                y = 160 + i * 104
                f.path(f"M{rx + 4},{y} H{rx + 36} Q{rx + 44},{y} {rx + 44},{y + 8} V{y + 16} Q{rx + 44},{y + 24} {rx + 36},{y + 24} H{rx + 12}", MUTED, 1.2, marker="arrow")
                f.label(rx + 56, y + 16, f"{call}: IN PROCESS", INK, KEY, "start")
        e = rec["remote" if remote else "embedded"]
        f.card(x0, 480, 560, 76, "ENGINE TIME OF A SEARCH, P50", [("tech", f"{e['calculation_p50']:,} ms of {e['elapsed_p50']:,} ms on the server · {e['searches']} searches in 60 s")], focal=not remote)
    f.legend(596, [("arrow-link", LINK, "SOLVER CALL"), ("arrow-dashed", MUTED, "ANSWER")], note="for a daily pool the same trip is cheap beside a 5 s search")
    return f


# ---- Part V: the system today --------------------------------------------------------------------
def today(rec: dict) -> Figure:
    f = Figure("today", "The scheduler today, with the choice each thread settled", "Architecture of the scheduler as it stands, each part annotated with the decision the evidence settled and the number behind it: the proxy and two pools, the daily search per client, bounded two-pass booking with the confirm slot, the embedded engine with RoadMatrix, the CH routing graph and the thin store with rule 0.", 1280, 700)
    f.node(460, 32, 360, 56, "WaterFlex Software", ["SNAPSHOT IN, RESULTS OUT, RECEIPTS"], HOST)
    f.node(500, 128, 280, 52, "scheduler-proxy", ["ONE ADDRESS, /API/V1"], SERVICE)
    f.arrow(640, 88, 640, 128, "link")
    f.zone(40, 220, 560, 236, "BOOKING POOL")
    f.zone(680, 220, 560, 236, "DAILY POOL")
    f.route([(600, 180), (600, 200), (320, 200), (320, 220)], "muted"); f.route([(680, 180), (680, 200), (960, 200), (960, 220)], "muted")
    left = [("Bounded two-pass search", f"SHED UNDER LOAD: {rec['shed_before']:.0f} TO {rec['shed_after']:.0f} SEARCHES/MIN", True),
            ("Confirm slot and priority", f"CONFIRM P50 {rec['confirm_before']:,} TO {rec['confirm_after']:,} MS", False),
            ("Embedded engine, RoadMatrix", f"{rec['speedup']:.2f}X MOVES PER SECOND", False)]
    right = [("Tabu + K opt, per client", f"{money(rec['daily_saving'])} PER CASE OVER TABU", True),
             ("Fair durable queue", "EARLY 429, TURNS BY CLIENT", False),
             ("Remote-ready engine", "A DAILY-POOL LEVER, NOT A DEFAULT", False)]
    for x0, items in ((60, left), (700, right)):
        for i, (name, sub, focal) in enumerate(items):
            y = 248 + i * 66
            f.rect(x0, y, 520, 56, PAPER, rx=6); f.rect(x0, y, 520, 56, rgba(ACCENT, 0.08) if focal else PAPER2, ACCENT if focal else SOFT, 1.4 if focal else 1, rx=6)
            f.name(x0 + 16, y + 24, name, INK, "start"); f.tech(x0 + 16, y + 44, sub, MUTED, "start", KEY)
    f.zone(40, 500, 1200, 116, "SHARED BY BOTH POOLS")
    f.node(80, 528, 500, 72, "routing-service per metro", [f"CH GRAPH, PARALLEL PAIRS: {rec['searches_before']} TO {rec['searches_after']:,} SEARCHES IN 60 S"], SERVICE)
    f.node(700, 528, 500, 72, "scheduler-db, the thin store", [f"RULE 0 STALE: {rec['lost_before']} TO 0 HOLDS LOST AT FIVE DESKS"], STORE)
    f.arrow(320, 456, 320, 500, "muted"); f.arrow(960, 456, 960, 500, "muted")
    f.legend(666, [("swatch", rgba(ACCENT, 0.08), "THE HEADLINE CHOICE OF EACH POOL"), ("swatch", PAPER2, "A SETTLED CHOICE")], note="every number is from the studies of Part IV")
    return f


# ---- Part VI: what comes next --------------------------------------------------------------------
def roadmap_perf(_=None) -> Figure:
    f = Figure("roadmap_perf", "Performance: what to measure next, in order", "Dependency graph of the performance next steps: measure on the real VM first; then size the booking cores, run the daily pool remote, and replace primitive time in the timing search; multithreaded solving follows the primitive-time work through the same gate; fewer solver round trips only if booking ever uses a pool.", 1280, 560)
    f.node(40, 220, 240, 88, "Measure on the VM", ["J0, J2 AND K ACROSS", "THE REAL NETWORK"], rgba(ACCENT, 0.08), ACCENT, 1.4)
    f.node(380, 56, 280, 72, "Booking cores", ["2, 3, 4 CORES; A 2ND REPLICA"], SERVICE)
    f.node(380, 228, 280, 72, "Daily pool remote", ["POOL SIZED BY THE RULE"], SERVICE)
    f.node(380, 400, 280, 72, "Primitive time", ["INTEGER SECONDS IN TIMING"], SERVICE)
    f.node(760, 400, 300, 72, "Multithreaded solving", ["PLANNED; GATED BELOW"], SERVICE)
    f.node(760, 56, 300, 72, "Fewer round trips", ["ONLY IF BOOKING USES A POOL"], PAPER2, SOFT, 1, dash="4,3")
    f.route([(280, 250), (330, 250), (330, 92), (380, 92)], "accent")
    f.arrow(280, 264, 380, 264, "accent")
    f.route([(280, 278), (330, 278), (330, 436), (380, 436)], "accent")
    f.arrow(660, 436, 760, 436, "muted")
    f.arrow(660, 92, 760, 92, "muted", "5,4"); f.label(710, 84, "IF", SOFT, KEY)
    f.para(1080, 412, ["paired study", "+ exactness test", "+ the replica's", "core budget"], KEY, MUTED, MONO, lead=16)
    f.callout(760, 200, ["Every number in Part IV is from a rehearsal", "stack whose CPU limits are a shape; the", "latency target is defined at the WaterFlex", "caller, which has not been measured yet."])
    f.legend(526, [("swatch", rgba(ACCENT, 0.08), "FIRST"), ("swatch", SERVICE, "NEXT, ONCE THE VM NUMBERS EXIST"), ("swatch-dashed", PAPER2, "CONDITIONAL")], note="one change per measurement, as before")
    return f


def cores(_=None) -> Figure:
    f = Figure("cores", "Multithreaded solving: the same cores, spent differently", "Illustration with a four-core daily replica: today several daily solves run at once on one core each; with moveThreadCount two, each solve spreads its move evaluation over two cores, so half as many solves run at once. Which is better for a client's day is what the gate measures.", 1280, 560)
    for j, (title, sub, solves, per) in enumerate((("Today", "SEVERAL SOLVES, ONE CORE EACH", 4, 1), ("Planned", "ONE SOLVE ON SEVERAL CORES", 2, 2))):
        x0 = 40 + j * 620
        f.panel_title(x0, 48, title); f.tech(x0, 70, sub, MUTED, "start", KEY)
        f.zone(x0, 88, 580, 240, "DAILY REPLICA, 4 CORES (ILLUSTRATION)")
        for c in range(4):
            x = x0 + 24 + c * 136
            solve = c // per
            focal = j == 1
            f.rect(x, 128, 120, 64, PAPER, rx=6); f.rect(x, 128, 120, 64, SERVICE, SOFT, 1, rx=6)
            f.text(x + 60, 156, f"CORE {c + 1}", KEY, INK, MONO, "middle"); f.text(x + 60, 176, f"solve {solve + 1}", KEY, MUTED, MONO, "middle")
        for s in range(solves):
            x = x0 + 24 + s * per * 136
            w = per * 136 - 16
            f.rect(x, 212, w, 40, PAPER, rx=6); f.rect(x, 212, w, 40, rgba(ACCENT, 0.10) if j == 1 else PAPER2, ACCENT if j == 1 else SOFT, 1.4 if j == 1 else 1, rx=6)
            f.text(x + w / 2, 237, f"DAY {s + 1}" + (": MORE MOVES IN ITS SECONDS" if j == 1 else ""), KEY, INK, MONO, "middle")
        f.text(x0 + 24, 296, f"{solves} days solve at once", KEY + 1, INK, SANS, weight="700")
    f.card(40, 360, 1200, 120, "THE GATE BEFORE ANY CLIENT GETS IT", [("tech", "1. The paired study on fresh fixtures: the same days, budgets and seeds, one thread against two."), ("tech", "2. The scoring exactness test: every score the engine computes stays the same."),
                                                                      ("tech", "3. The replica's core budget: fewer solves at once must not lengthen the fair queue past what clients accept.")], focal=True)
    f.legend(526, [("swatch", PAPER2, "ONE SOLVE PER CORE"), ("swatch", rgba(ACCENT, 0.10), "ONE SOLVE ACROSS TWO CORES")], note="today no solver configuration sets move threads")
    return f


def roadmap_quality(_=None) -> Figure:
    f = Figure("roadmap_quality", "Solver quality, booking quality and the host's side: what comes next", "Three lanes of next steps: the daily search (offer Tabu + K opt size 3 as a third choice, replay real days, measure with the fairness search on, then decide a default), booking (a quality study against /api/v1 on recorded days), and integration (the host's side-by-side migration and cutover with the conformance kit and the integration guide, then TLS before production traffic); with the design document's two open questions.", 1280, 670)
    lanes = [("DAILY SEARCH", ["Third choice", "TABU_KOPT_SIZE_3 BESIDE", "TABU_KOPT AND TABU"], ["Replay real days", "RECORDED SNAPSHOTS,", "MEASURED ROADS"], ["Fairness on", "MEASURE BALANCE WITH", "THE SECOND PHASE"], ["Decide a default", "PER CLIENT, ON", "THAT EVIDENCE"]),
             ("BOOKING", ["Quality study", "AGAINST /API/V1 ON", "RECORDED DAYS"], ["Served-customer", "DENOMINATOR, AS IN", "SEPTEMBER"], None, None),
             ("INTEGRATION", ["Host snapshots", "AND RECEIPTS, WITH THE", "KIT (#149), GUIDE (#150)"], ["Side by side", "THEN CUTOVER (S7),", "ON THE HOST'S SIDE"], ["TLS", "HOST TO SCHEDULER,", "ROUTING, NOMINATIM"], ["Production", "TRAFFIC", ""])]
    y0, lh, x0, w, gap = 40, 140, 200, 230, 30
    for i, (lane, *steps) in enumerate(lanes):
        y = y0 + i * lh
        f.rect(30, y, 1220, lh - 16, ink(0.02) if i % 2 == 0 else PAPER, ink(0.10), 0.8, rx=4)
        f.text(46, y + lh / 2 - 4, lane, KEY, INK, MONO, weight="700", spacing="0.08em")
        prev = None
        for k, step in enumerate(steps):
            if step is None:
                continue
            x = x0 + k * (w + gap)
            name, *sub = step
            focal = (i, k) == (0, 0)
            last = (i == 2 and k == 3)
            f.node(x, y + 16, w, lh - 48, name, [s for s in sub if s], rgba(ACCENT, 0.08) if focal else (PAPER2 if last else SERVICE), ACCENT if focal else SOFT, 1.4 if focal else 1, dash="4,3" if last else None)
            if prev is not None:
                f.arrow(prev, y + 16 + (lh - 48) / 2, x, y + 16 + (lh - 48) / 2, "muted")
            prev = x + w
    f.card(30, 452, 1220, 132, "OPEN QUESTIONS IN THE DESIGN DOCUMENT", [("tech", "1. Does WaterFlex Software's Google Maps Platform agreement allow passing Google-geocoded coordinates to the scheduler"), ("tech", "   and caching them for up to 30 days?"),
                                                                           ("tech", "2. Does any client require a dedicated database? Every row already carries tenant_id, so that client's rows"), ("tech", "   could move to a database of its own without an API change.")])
    f.legend(636, [("swatch", rgba(ACCENT, 0.08), "THE NEXT STEP THE EVIDENCE POINTS TO"), ("swatch", SERVICE, "A STEP"), ("swatch-dashed", PAPER2, "THE DESTINATION")], note="every item is from the repository's own plans")
    return f
