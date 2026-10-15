"""The report's figures 2 to 12 as inline-SVG diagrams, generated from the recorded chart data that verify_report.py recomputes.

Each function returns a Figure; charts.py writes the HTML next to this file and rasterizes it. Types follow the diagram-design skill: sequence (booking, daily), layer stack (tiers), heatmap (daily_fleet), bar, line, dumbbell, stacked bar and scatter for the studies.
"""
from __future__ import annotations

from statistics import mean

from .svglib import (ACCENT, HOST, INK, KEY, LINK, MONO, MUTED, PAPER, PAPER2, RULE_SOLID, SANS, SERIES, SERVICE, SOFT, STORE, TECH,
                     Figure, Plot, fmt, ink, mono_width, nice_ceiling, rgba, tick_steps)

OFFERS, CONFIRM = "POST /api/v1/booking/offers 201", "POST /api/v1/booking/holds/{holdId}/confirm 200"


def _row_label(s: str) -> str:
    return " ".join(s.split())


# ---- 2. Booking sequence ---------------------------------------------------------------------------
def booking(recorded: dict) -> Figure:
    f = Figure("booking", "The booking sequence and its refusals", "Sequence of the booking calls between WaterFlex Software and the scheduler: offers, select, confirm and the receipt write, with the refusals that send the host back to a new search.", 980, 700)
    hx, sx = 130, 670
    for x, name, sub, fill in ((hx, "WaterFlex Software", "HOST · DESK OR CUSTOMER", HOST), (sx, "Scheduler", "ANY BOOKING REPLICA", SERVICE)):
        f.node(x - 110, 40, 220, 56, name, [sub], fill)
    for x in (hx, sx):
        f.line(x, 96, x, 616, ink(0.20), 1, "3,3")

    def call(y, text, kind="link"):
        f.arrow(hx + 4, y, sx - 4, y, kind); f.label((hx + sx) / 2, y - 10, text, LINK if kind == "link" else INK, KEY)

    def ret(y, text, kind="muted"):
        if kind == "accent":
            f.arrow(sx - 4, y, hx + 4, y, "accent"); f.label((hx + sx) / 2, y - 10, text, ACCENT, KEY)
        else:
            f.arrow(sx - 4, y, hx + 4, y, "muted", dash="5,4"); f.label((hx + sx) / 2, y - 10, text, SOFT, KEY)

    for y0, y1 in ((136, 172), (216, 244), (344, 380)):
        f.rect(sx - 4, y0, 8, y1 - y0, ink(0.06), MUTED, 0.8)
    call(136, "POST OFFERS · JOB, HORIZON, SNAPSHOT OF EVERY TECHNICIAN-DAY")
    ret(172, "201 OFFER SET · WINDOWS WITH PRICES, SEARCHCOMPLETE")
    call(216, "POST SELECT · THE WINDOW THE CUSTOMER PICKED")
    ret(244, "200 HOLD · EXPIRES IN 10 MINUTES")
    fx, fy, fw, fh = 80, 272, 630, 328
    f.rect(fx, fy, fw, fh, ink(0.02), ink(0.22), 1, rx=4)
    f.rect(fx, fy, 40, 16, PAPER, ink(0.22), 1, rx=2); f.text(fx + 20, fy + 12, "ALT", 8, MUTED, MONO, "middle", spacing="0.12em")
    f.text(fx + 12, fy + 36, "[RECORDS UNCHANGED SINCE THE OFFER]", TECH, MUTED, MONO, spacing="0.04em")
    call(344, "POST CONFIRM · FRESH SNAPSHOT OF THE HOLD'S DATE")
    ret(380, "200 CONFIRMED · ARRANGEMENT FOR THAT DAY", "accent")
    f.path(f"M{hx + 4},408 H{hx + 36} Q{hx + 44},408 {hx + 44},416 V424 Q{hx + 44},432 {hx + 36},432 H{hx + 12}", MUTED, 1.2, marker="arrow")
    f.label(hx + 56, 425, "HOST WRITES THE RECEIPT: COMPARE-AND-SET ON LASTMODIFIED", INK, KEY, anchor="start")
    f.line(fx + 8, 456, fx + fw - 8, 456, ink(0.20), 1, "4,3")
    f.text(fx + 12, 480, "[409 STALE · 409 HOLD_UNAVAILABLE · OR THE RECEIPT WRITE FAILS]", KEY, MUTED, MONO, spacing="0.04em")
    ret(520, "409 WITH ITS REASON (OR THE HOST'S OWN WRITE CONFLICT)")
    call(568, "RELEASE THE SET OR LET THE HOLD EXPIRE · SEARCH AGAIN, NEW REQUESTID", "muted")
    f.callout(730, 300, ["STALE: the snapshot is", "older than records", "already used.", "", "HOLD_UNAVAILABLE: the", "hold expired or was", "lost, another offer was", "selected, or the day no", "longer fits.", "", "Write conflict: a", "lastModified moved", "since the receipt."], INK)
    f.legend(664, [("arrow-link", LINK, "CALL"), ("arrow-dashed", MUTED, "REPLY"), ("line", ACCENT, "HEADLINE SUCCESS"), ("swatch", ink(0.06), "SCHEDULER HOLDS CONTROL")], note="time runs top to bottom")
    return f


# ---- 3. Daily and repair proposals ---------------------------------------------------------------
def daily(recorded: dict) -> Figure:
    f = Figure("daily", "Daily and repair proposals, then the commit", "Sequence of a daily or repair proposal: synchronous or queued behind Prefer respond-async, the decision it returns, and the commit with the host's current timestamps.", 980, 680)
    hx, sx = 130, 670
    for x, name, sub, fill in ((hx, "WaterFlex Software", "OVERNIGHT JOB OR PLANNER", HOST), (sx, "Scheduler", "ANY DAILY REPLICA", SERVICE)):
        f.node(x - 110, 40, 220, 56, name, [sub], fill)
    for x in (hx, sx):
        f.line(x, 96, x, 572, ink(0.20), 1, "3,3")

    def call(y, text):
        f.arrow(hx + 4, y, sx - 4, y, "link"); f.label((hx + sx) / 2, y - 10, text, LINK, KEY)

    def ret(y, text, accent=False):
        if accent:
            f.arrow(sx - 4, y, hx + 4, y, "accent"); f.label((hx + sx) / 2, y - 10, text, ACCENT, KEY)
        else:
            f.arrow(sx - 4, y, hx + 4, y, "muted", dash="5,4"); f.label((hx + sx) / 2, y - 10, text, SOFT, KEY)

    call(136, "POST DAILY OR REPAIR PROPOSAL · SNAPSHOT OF ONE METRO DAY")
    fx, fy, fw, fh = 80, 160, 630, 272
    f.rect(fx, fy, fw, fh, ink(0.02), ink(0.22), 1, rx=4)
    f.rect(fx, fy, 40, 16, PAPER, ink(0.22), 1, rx=2); f.text(fx + 20, fy + 12, "ALT", 8, MUTED, MONO, "middle", spacing="0.12em")
    f.text(fx + 12, fy + 36, "[SYNCHRONOUS]", TECH, MUTED, MONO, spacing="0.04em")
    f.rect(sx - 4, 136, 8, 100, ink(0.06), MUTED, 0.8)
    f.label(sx - 16, 200, "SEARCH: COST + FAIRNESS SECONDS, PLUS UP TO 5 S", INK, KEY, anchor="end")
    ret(236, "201 DAILYPROPOSAL · IMPROVED, NO_IMPROVEMENT OR REJECTED_BY_POLICY")
    f.line(fx + 8, 260, fx + fw - 8, 260, ink(0.20), 1, "4,3")
    f.text(fx + 12, 284, "[PREFER: RESPOND-ASYNC]", TECH, MUTED, MONO, spacing="0.04em")
    ret(312, "202 ACCEPTED AT ONCE · TAKEN IN TURN ACROSS CLIENTS")
    f.rect(sx - 4, 312, 8, 92, ink(0.06), MUTED, 0.8)
    call(356, "GET /REQUESTS/{REQUESTID} · POLL")
    ret(404, "200 DAILYPROPOSAL WHEN DONE (202 WHILE QUEUED OR RUNNING)")
    call(464, "POST .../COMMIT · CURRENT LASTMODIFIED OF EVERY TECHNICIAN-DAY")
    f.rect(sx - 4, 464, 8, 40, ink(0.06), MUTED, 0.8)
    ret(504, "200 RECEIPT · WRITTEN WITH A COMPARE-AND-SET", True)
    f.callout(730, 236, ["Only IMPROVED can be", "committed; the other", "two return the current", "routes and the reason.", "", "Refusals:", "409 STALE, a day changed", "409 NOT_COMMITTABLE", "422, the day is frozen", "(after 6 a.m. local)", "429 BUSY with Retry-After", "", "DELETE /requests cancels;", "the call gets 409", "CANCELLED."], INK)
    f.legend(644, [("arrow-link", LINK, "CALL"), ("arrow-dashed", MUTED, "REPLY"), ("line", ACCENT, "HEADLINE SUCCESS"), ("swatch", ink(0.06), "SCHEDULER HOLDS CONTROL")], note="the two ways a proposal is answered")
    return f


# ---- 4. Route cache tiers -----------------------------------------------------------------------
def tiers(rows: list[list[str]]) -> Figure:
    f = Figure("tiers", "The four route cache tiers, in lookup order", "Layer stack of the route cache: in-flight dedup, scheduler memory, the scheduler database and routing memory, each with its key, lifetime and who shares it.", 980, 660)
    x, w, y, h = 140, 800, 64, 112
    f.arrow(96, y + 8, 96, y + 4 * h - 8, "muted"); f.label(108, y - 16, "LOOKUP ORDER", SOFT, KEY)
    for i, row in enumerate(rows):
        focal = i == 2
        top = y + i * h
        f.rect(x, top, w, h, PAPER)
        f.rect(x, top, w, h, rgba(ACCENT, 0.08) if focal else (PAPER if i % 2 == 0 else PAPER2), ACCENT if focal else ink(0.12), 1.4 if focal else 1)
        number, name = row[0].split(". ", 1)
        f.tag(x + 16, top + 18, f"TIER {number}", ACCENT if focal else ink(0.40), ACCENT if focal else INK)
        f.name(x + 104, top + 32, name, INK, "start")
        lifetime = row[3].split(";")[0].replace("`", "")
        f.tech(x + 104, top + 54, "KEY: " + row[2].replace("`", "").upper(), INK, "start", KEY)
        f.tech(x + 104, top + 74, lifetime.upper(), MUTED, "start", KEY)
        f.tech(x + 104, top + 94, "SHARED BY: " + row[4].upper(), MUTED, "start", KEY)
    f.line(x, y + 4 * h, x + w, y + 4 * h, INK, 1)
    f.callout(x, y + 4 * h + 36, ["Every entry is bound to a routing identity (a hash of the road graph and map version), so a changed",
                                   "map is never mixed with old legs. Keys are coordinates rounded to 5 decimals (about 1 m); a leg the",
                                   "routing service computes is written back to tiers 2 and 3 in a separate short transaction."])
    f.legend(624, [("swatch", rgba(ACCENT, 0.08), "THE TIER ALL REPLICAS SHARE"), ("swatch", PAPER2, "PER REPLICA OR PER CALLER")], note="source: the design document")
    return f


# ---- 5. Daily search heatmaps -------------------------------------------------------------------
def daily_fleet(recorded: dict, labels: dict) -> Figure:
    f = Figure("daily_fleet", "Mean saving against Tabu by fleet size", "Two heatmaps of mean saving against Tabu in dollars per case by fleet size: the Tabu variant study and the follow-up on unseen fixtures.", 980, 860)
    cell_w, cell_h, gap = 96, 44, 4
    x0, y = 240, 40
    for key, title, focal in (("first", "Tabu variant study (160 cases per cell)", "TABU_KOPT"), ("follow", "Follow-up on 40 new fixtures (48 cases per cell)", "TABU_KOPT_SIZE_3")):
        d = recorded[key]
        f.panel_title(40, y + 4, title)
        cols = [str(c) for c in d["fleets"]] + ["all"]
        for j, c in enumerate(cols):
            f.tech(x0 + j * (cell_w + gap) + cell_w / 2, y + 40, c.upper(), MUTED)
        for i, solver in enumerate(d["solvers"]):
            cy = y + 52 + i * (cell_h + gap) + cell_h / 2
            f.text(x0 - 12, cy + 4, labels[solver], KEY, INK, SANS, "end", weight="700")
            for j, v in enumerate(d["values"][i]):
                cx = x0 + j * (cell_w + gap)
                is_focal = solver == focal and j == len(cols) - 1
                f.rect(cx, cy - cell_h / 2, cell_w, cell_h, PAPER)
                if is_focal:
                    f.rect(cx, cy - cell_h / 2, cell_w, cell_h, rgba(ACCENT, 0.85), ACCENT, 1.2, extra=f'data-row="{solver}" data-col="{cols[j]}" data-value="{v:.2f}" data-focal="true"')
                    color = PAPER
                else:
                    alpha = max(0.07, min(0.65, abs(v) / 30 * 0.65))
                    f.rect(cx, cy - cell_h / 2, cell_w, cell_h, rgba(LINK if v >= 0 else SERIES[3], alpha), extra=f'data-row="{solver}" data-col="{cols[j]}" data-value="{v:.2f}"')
                    color = PAPER if alpha >= 0.40 and v >= 0 else INK
                f.tech(cx + cell_w / 2, cy + 4, f"{v:+.2f}", color)
        bottom = y + 52 + len(d["solvers"]) * (cell_h + gap)
        f.tech(x0 + len(cols) * (cell_w + gap) / 2, bottom + 20, "TECHNICIANS", MUTED)
        y = bottom + 56
    f.height = int(y + 60)
    f.legend(y + 24, [("swatch", rgba(LINK, 0.45), "SAVES AGAINST TABU"), ("swatch", rgba(SERIES[3], 0.45), "LOSES AGAINST TABU"), ("swatch", rgba(ACCENT, 0.85), "THE CHOICE MADE")],
             note="dollars per case; ramp spans 0 to 30")
    return f


# ---- 6. Booking search study --------------------------------------------------------------------
def booking_study(recorded: dict) -> Figure:
    f = Figure("booking_study", "Booking search: service and latency", "Bar chart of requests served of 480 by search method in the field validation's held-out matrix, and a line chart of search p95 by concurrency in the first booking experiment.", 980, 600)
    held = recorded["held"]; names = {"INSERTION": "Insertion", "BOUNDED": "Bounded", "SHARED": "Shared"}
    left = Plot(f, 110, 96, 420, 420); left.frame("Held-out service, field validation")
    left.ygrid(480, 4, "REQUESTS SERVED OF 480")
    for cx, h in zip(left.columns(3), held):
        top = left.ymap(h["served"], 480)
        left.bar(cx, 64, top, focal=h["variant"] == "BOUNDED")
        f.tech(cx, top - 24, f"{h['served']} OF {h['requests']}", ACCENT if h["variant"] == "BOUNDED" else MUTED, size=KEY)
        f.tech(cx, top - 8, f"P95 {h['mean_case_p95_ms'] / 1000:.1f} S", MUTED, size=KEY)
        f.text(cx, left.y1 + 24, names[h["variant"]], KEY, INK, SANS, "middle", weight="700")
    f.tech((left.x0 + left.x1) / 2, left.y1 + 46, "48 CASES PER METHOD", MUTED, size=KEY)
    right = Plot(f, 560, 96, 940, 420); right.frame("Latency, first booking experiment")
    ceiling = nice_ceiling(max(e["p95"] for e in recorded["latency"]) / 1000)
    right.ygrid(ceiling, None, "SEARCH P95, SECONDS")
    xs = {1: right.x0 + 40, 5: (right.x0 + right.x1) / 2, 10: right.x1 - 40}
    for c, x in xs.items():
        f.tech(x, right.y1 + 20, str(c), MUTED)
    f.tech((right.x0 + right.x1) / 2, right.y1 + 44, "SIMULTANEOUS REQUESTS (1,800 PER METHOD)", MUTED, size=KEY)
    for solver, color, focal in (("INSERTION", SERIES[1], False), ("BOUNDED", ACCENT, True)):
        pts = [(xs[e["concurrency"]], right.ymap(e["p95"] / 1000, ceiling)) for e in recorded["latency"] if e["solver"] == solver]
        right.polyline(pts, color, focal)
        for (x, y), e in zip(pts, [e for e in recorded["latency"] if e["solver"] == solver]):
            dx = -30 if e["concurrency"] == 10 else (56 if e["concurrency"] == 1 and not focal else 0)
            f.label(x + dx, y - 12 if focal else y - 14, f"{e['p95'] / 1000:.2f} S", color, KEY)
    f.legend(564, [("swatch", rgba(ACCENT, 0.12), "BOUNDED (CHOSEN)"), ("swatch", rgba(MUTED, 0.15), "INSERTION, SHARED"), ("line", SERIES[1], "INSERTION P95"), ("line", ACCENT, "BOUNDED P95")])
    return f


# ---- 7. Routing: flexible against CH --------------------------------------------------------------
def ch(recorded: list, maps: dict) -> Figure:
    f = Figure("ch", "Routing service alone, caches off", "Line chart of the p50 of a 16-pair routing request against the routing service alone, flexible graph against contraction hierarchy, on two maps and dates, log scale.", 980, 580)
    plot = Plot(f, 140, 80, 940, 420)
    ticks = [1, 10, 100, 1000]
    for v in ticks:
        y = plot.ymap(v, 1000, True, 1)
        f.line(plot.x0, y, plot.x1, y, ink(0.25) if v == 1 else ink(0.08), 1 if v == 1 else 0.8)
        f.tech(plot.x0 - 8, y + 4, f"{v} MS", MUTED, "end")
    f.line(plot.x0, plot.y0, plot.x0, plot.y1, ink(0.25), 1)
    f.text(plot.x0 - 84, (plot.y0 + plot.y1) / 2, "P50 PER 16-PAIR REQUEST, LOG", TECH, MUTED, MONO, "middle", spacing="0.10em", extra=f'transform="rotate(-90 {plot.x0 - 84} {(plot.y0 + plot.y1) / 2})"')
    xs = {1: plot.x0 + 80, 5: (plot.x0 + plot.x1) / 2, 10: plot.x1 - 80}
    for c, x in xs.items():
        f.tech(x, plot.y1 + 20, str(c), MUTED)
    f.tech((plot.x0 + plot.x1) / 2, plot.y1 + 44, "CONCURRENT CALLERS", MUTED)
    styles = {("2026-10-14", "CH-car-v1"): (ACCENT, True, None), ("2026-09-24", "CH-car-v1"): (ACCENT, False, "6,4"), ("2026-10-14", "flexible"): (SERIES[1], False, None), ("2026-09-24", "flexible"): (SERIES[1], False, "6,4")}
    keys = []
    for entry in recorded:
        color, focal, dash = styles[(entry["date"], entry["engine"])]
        pts = [(xs[c], plot.ymap(v, 1000, True, 1)) for c, v in zip(entry["concurrency"], entry["p50"])]
        plot.polyline(pts, color, focal, dash)
        for (x, y), v in zip(pts, entry["p50"]):
            f.label(x + 44, y + 4 + (0 if entry["engine"] == "CH-car-v1" else -4), f"{v:.1f}", color, KEY)
        keys.append(("line-dashed" if dash else "line", color, f"{'CH' if entry['engine'] == 'CH-car-v1' else 'FLEXIBLE'} · MAP {'CURRENT' if entry['date'] == max(maps) else 'FIRST'}"))
    f.legend(544, keys, note="dashed: first benchmark · solid: current")
    return f


# ---- 8. Attribution of a booking call's server time --------------------------------------------
def attribution(recorded: list) -> Figure:
    f = Figure("attribution", "Where a booking call's server time goes", "Stacked horizontal bars of the median booking call's server time split into slot wait, routing, calculation engine and database, for offers and confirm across five builds.", 980, 520)
    parts = [("queueMs", "SLOT WAIT", rgba(MUTED, 0.15), MUTED), ("routingMs", "ROUTING", rgba(ACCENT, 0.12), ACCENT), ("calculationMs", "ENGINE", rgba(SERIES[0], 0.30), SERIES[0]), ("dbMs", "DATABASE", rgba(SERIES[2], 0.30), SERIES[2])]
    stages = []
    for e in recorded:
        if e["stage"] not in stages:
            stages.append(e["stage"])
    for op, x0, x1, title in ((OFFERS, 140, 480, "Offers (the search)"), (CONFIRM, 660, 880, "Confirm")):
        plot = Plot(f, x0, 80, x1, 360); plot.frame(title)
        rows = [e for e in recorded if e["operation"] == op]
        ceiling = nice_ceiling(max(e["elapsed_p50"] for e in rows))
        plot.xgrid(ceiling, None, "SERVER TIME OF THE MEDIAN CALL, MS" if op == OFFERS else "MEDIAN CALL, MS")
        for i, e in enumerate(rows):
            y = plot.y0 + 24 + i * 64
            f.text(x0 - 12, y + 20, stages[i], KEY, INK, SANS, "end", weight="700")
            left = 0
            for key, _, fill, stroke in parts:
                w = plot.xmap(left + e[key], ceiling) - plot.xmap(left, ceiling)
                if w > 0:
                    f.rect(plot.xmap(left, ceiling), y, w, 32, PAPER); f.rect(plot.xmap(left, ceiling), y, w, 32, fill, stroke, 1)
                left += e[key]
            f.tech(plot.xmap(left, ceiling) + 8, y + 20, f"{e['elapsed_p50']} MS", MUTED, "start", KEY)
    f.legend(484, [("swatch", fill, name) for _, name, fill, _ in parts], note="a confirm's bar is the wait behind searches")
    return f


# ---- 9. Five desks at each stage ----------------------------------------------------------------
def progression(recorded: list) -> Figure:
    f = Figure("progression", "Five booking desks at each stage", "Bar chart of booking searches answered per minute and a dumbbell chart of caller latency p50 and p95 of offers across the admission, routing and priority work, with the two machines marked.", 980, 620)
    left = Plot(f, 110, 104, 470, 460); left.frame("Throughput at five desks")
    ceiling = nice_ceiling(max(d["searches_per_minute"] for d in recorded))
    left.ygrid(ceiling, None, "SEARCHES ANSWERED PER MINUTE", lambda v: fmt(v))
    cols = left.columns(len(recorded))
    for cx, d in zip(cols, recorded):
        top = left.ymap(d["searches_per_minute"], ceiling)
        left.bar(cx, 30, top, focal=d["stage"] == "J2")
        f.tech(cx, top - 8, fmt(d["searches_per_minute"]), ACCENT if d["stage"] == "J2" else MUTED, size=KEY)
        f.text(cx, left.y1 + 22, d["stage"], KEY, INK, SANS, "middle", weight="700")
    split = (cols[3] + cols[4]) / 2
    f.line(split, left.y0 - 8, split, left.y1, ink(0.35), 1, "2,3")
    f.label((cols[0] + cols[3]) / 2, left.y0 - 16, "ONE MACHINE, DESKS ALONE", SOFT, KEY)
    f.label((cols[4] + cols[7]) / 2, left.y0 - 16, "VM SHAPE, WITH PLANNERS", SOFT, KEY)
    right = Plot(f, 600, 104, 940, 460); right.frame("Latency of offers at the caller")
    ceiling = nice_ceiling(max(d["p95"] for d in recorded))
    right.xgrid(ceiling, None, "CALLER LATENCY, MS")
    for i, d in enumerate(recorded):
        y = right.y0 + 24 + i * 44
        right.dumbbell(y, right.xmap(d["p50"], ceiling), right.xmap(d["p95"], ceiling), fmt(d["p50"]), fmt(d["p95"]), d["stage"], right.x0 - 12)
    if 5500 <= ceiling:
        xb = right.xmap(5500, ceiling)
        f.line(xb, right.y0, xb, right.y1, INK, 1, "6,4"); f.label(xb, right.y0 - 12, "5.5 S BOUND", INK, KEY)
    f.legend(584, [("swatch", rgba(ACCENT, 0.12), "THE BUILD THAT SHIPPED (J2)"), ("swatch", rgba(MUTED, 0.15), "OTHER STAGES"), ("hollow-dot", PAPER, "P50"), ("solid-dot", ACCENT, "P95"), ("line-dashed", INK, "5.5 S BOUND")],
             note="the two halves are not one series")
    return f


# ---- 10. Daily proposals under load --------------------------------------------------------------
def daily_load(recorded: dict) -> Figure:
    f = Figure("daily_load", "Daily proposals: admission, fairness and pools", "Three bar charts of daily proposals: answered or refused with 503 before and after #153, two clients' shares synchronously and queued, and one replica against booking and daily pools.", 980, 580)
    adm = recorded["admission"]
    a = Plot(f, 100, 80, 330, 400); a.frame("Answered or refused")
    ceiling = nice_ceiling(max(e["answered_201"] + e["answered_503"] for e in adm))
    a.ygrid(ceiling, None, "PROPOSALS IN 60 S")
    cols = a.columns(4)
    for cx, e in zip(cols, adm):
        focal = e["stage"] == "#153" and e["planners"] == "4 planners"
        top = a.ymap(e["answered_201"], ceiling)
        a.bar(cx, 40, top, focal=focal)
        if e["answered_503"]:
            top503 = a.ymap(e["answered_201"] + e["answered_503"], ceiling)
            f.rect(cx - 20, top503, 40, top - top503, PAPER); f.rect(cx - 20, top503, 40, top - top503, "url(#hatch)", MUTED, 1)
            f.tech(cx, top503 - 8, f"{e['answered_201']} + {e['answered_503']}", MUTED, size=KEY)
        else:
            f.tech(cx, top - 8, str(e["answered_201"]), ACCENT if focal else MUTED, size=KEY)
        f.text(cx, a.y1 + 22, e["stage"], KEY, INK, SANS, "middle", weight="700")
    f.tech((cols[0] + cols[1]) / 2, a.y1 + 44, "1 PLANNER", MUTED, size=KEY); f.tech((cols[2] + cols[3]) / 2, a.y1 + 44, "4 PLANNERS", MUTED, size=KEY)
    fair = recorded["fairness"]
    b = Plot(f, 420, 80, 640, 400); b.frame("Two clients (#157)")
    ceiling = nice_ceiling(max(e["proposals"] for e in fair))
    b.ygrid(ceiling, None, "PROPOSALS IN 90 S")
    for cx, e in zip(b.columns(4), fair):
        focal = e["mode"] == "async" and e["client"] == "B"
        top = b.ymap(e["proposals"], ceiling)
        b.bar(cx, 36, top, focal=focal)
        f.tech(cx, top - 8, str(e["proposals"]), ACCENT if focal else MUTED, size=KEY)
        f.text(cx, b.y1 + 22, f"{e['client']} x{e['planners']}", KEY, INK, SANS, "middle", weight="700")
        f.tech(cx, b.y1 + 44, "QUEUED" if e["mode"] == "async" else "SYNC", MUTED, size=KEY)
    roles = recorded["roles"]
    c = Plot(f, 730, 80, 940, 400); c.frame("Pools (#158)")
    ceiling = nice_ceiling(max(e["proposals"] for e in roles))
    c.ygrid(ceiling, None, "PROPOSALS IN 60 S")
    for cx, e in zip(c.columns(4), roles):
        focal = e["run"] == "split-2"
        top = c.ymap(e["proposals"], ceiling)
        c.bar(cx, 36, top, focal=focal)
        f.tech(cx, top - 8, str(e["proposals"]), ACCENT if focal else MUTED, size=KEY)
        f.text(cx, c.y1 + 22, e["run"].replace("-", " "), KEY, INK, SANS, "middle", weight="700")
        f.tech(cx, c.y1 + 44, e["shape"].upper(), MUTED, size=KEY)
    f.legend(544, [("swatch", rgba(MUTED, 0.15), "ANSWERED 201"), ("hatch", PAPER, "REFUSED 503 (ANSWERED + REFUSED)"), ("swatch", rgba(ACCENT, 0.12), "THE RESULT EACH CHANGE WAS AFTER")])
    return f


# ---- 11. Confirm priority and the confirm slot ---------------------------------------------------
def confirm(recorded: list) -> Figure:
    f = Figure("confirm", "Confirm and offers at the caller through J2", "Two dumbbell charts of caller latency p50 and p95 for confirm and for offers from J's CH run through the four J2 runs, with the median slot wait of each confirm.", 980, 580)
    for key, x0, x1, title, labelled in (("confirm", 220, 520, "Confirm at the caller", True), ("offers", 620, 920, "Offers at the caller", False)):
        plot = Plot(f, x0, 80, x1, 420); plot.frame(title)
        ceiling = nice_ceiling(max(d[f"{key}_p95"] for d in recorded))
        plot.xgrid(ceiling, None, "CALLER LATENCY, MS")
        for i, d in enumerate(recorded):
            y = plot.y0 + 32 + i * 64
            plot.dumbbell(y, plot.xmap(d[f"{key}_p50"], ceiling), plot.xmap(d[f"{key}_p95"], ceiling), fmt(d[f"{key}_p50"]), fmt(d[f"{key}_p95"]), _row_label(d["stage"]) if labelled else "", x0 - 12)
            if key == "confirm":
                f.tech(x0 - 12, y + 22, f"WAIT {d['confirm_wait_p50']} MS", MUTED, "end", KEY)
    f.legend(544, [("hollow-dot", PAPER, "P50"), ("solid-dot", ACCENT, "P95")], note="rows in study order; WAIT is the server's median slot wait")
    return f


# ---- 12. Inside the engine -----------------------------------------------------------------------
def engine(paired: list) -> Figure:
    f = Figure("engine", "Inside the engine: study L", "Scatter plot of each paired case's cost-phase moves per second before and after the RoadMatrix treatment.", 980, 600)
    left = Plot(f, 300, 60, 720, 480); left.frame("Study L: 24 paired solves")
    lim = nice_ceiling(max(max(p["before"], p["after"]) for p in paired) / 1000)
    left.ygrid(lim, None, "AFTER, THOUSAND MOVES PER SECOND")
    steps = tick_steps(lim)
    for i in range(steps + 1):
        v = lim * i / steps; x = left.xmap(v, lim)
        if i:
            f.line(x, left.y0, x, left.y1, ink(0.08), 0.8)
        f.tech(x, left.y1 + 20, fmt(v), MUTED)
    f.tech((left.x0 + left.x1) / 2, left.y1 + 44, "BEFORE, THOUSAND MOVES PER SECOND", MUTED, size=KEY)
    f.line(left.x0, left.y1, left.xmap(lim, lim), left.ymap(lim, lim), ink(0.25), 1, "2,3")
    f.label(left.xmap(lim, lim) - 40, left.ymap(lim, lim) + 28, "EQUAL", SOFT, KEY)
    f.line(left.x0, left.y1, left.xmap(lim / 2, lim), left.ymap(lim, lim), SERIES[3], 1, "6,4")
    f.label(left.xmap(lim / 2, lim) + 36, left.y0 + 12, "TWICE THE RATE", SERIES[3], KEY)
    for p in paired:
        x, y = left.xmap(p["before"] / 1000, lim), left.ymap(p["after"] / 1000, lim)
        if p["technicians"] == 20:
            f.circle(x, y, 5, PAPER); f.circle(x, y, 5, rgba(MUTED, 0.20), MUTED, 1)
        else:
            f.circle(x, y, 5, PAPER); f.circle(x, y, 5, rgba(INK, 0.55), INK, 1)
    f.legend(564, [("hollow-dot", PAPER, "20 TECHNICIANS"), ("solid-dot", rgba(INK, 0.55), "50 TECHNICIANS")], note="one dot per paired case, same seed and budget")
    return f
