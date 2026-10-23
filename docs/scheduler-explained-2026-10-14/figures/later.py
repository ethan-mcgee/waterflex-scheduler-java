"""Figures for the work after the first edition: the booking configuration study and the dynamic horizon (#177, #179), and the engine's chain of
representation changes with their verification on the rehearsal stack (#186, #187, #189, #191).

Data figures take the values charts.py computed and recorded in chart_data.json, in the same diagram-design grammar and report skin as thread.py.
"""
from __future__ import annotations

import math

from .svglib import ACCENT, INK, KEY, MONO, MUTED, NAME, PAPER, SANS, SERIES, TECH, Figure, Plot, ink, nice_ceiling, rgba
from .thread import LOSS, end_labels, money, signed_axis

GREY = "#9AA5AE"
BLUE = SERIES[1]
AMBER = SERIES[2]


def whole(v: float) -> str:
    return f"{v:,.0f}"


def horizon(rec: dict) -> Figure:
    f = Figure("horizon", "The dynamic booking horizon on a booked-out schedule (#177)", "Bars of the share of searches that offered no window and the windows offered per search under high demand, the fixed ten-weekday horizon against the dynamic horizon, at one and five desks; three technicians with the first twelve weekdays booked out, three seeds per bar.", 980, 560)
    cells = [("1 desk", 1), ("5 desks", 5)]
    bw = 52
    p = Plot(f, 110, 56, 470, 420); p.frame("Searches with no window offered")
    p.ygrid(60, 6, "% OF SEARCHES", lambda v: f"{v:.0f}%")
    for cx, (label, desks) in zip(p.columns(2), cells):
        for j, mode in enumerate(("fixed", "dynamic")):
            e = rec[f"{mode}/{desks}"]
            v = e["noOffers"] / e["searches"] * 100
            x = cx + (j - 0.5) * (bw + 8)
            focal = mode == "dynamic"
            p.bar(x, bw, p.ymap(v, 60), focal=focal, fill=None if focal else rgba(GREY, 0.35), stroke=None if focal else GREY)
            f.tech(x, p.ymap(v, 60) - 8, f"{v:.0f}%", ACCENT if focal else INK, size=KEY)
        f.tech(cx, p.y1 + 20, label.upper(), MUTED)
    q = Plot(f, 600, 56, 940, 420); q.frame("Windows offered per search")
    q.ygrid(4, 4, "WINDOWS")
    for cx, (label, desks) in zip(q.columns(2), cells):
        for j, mode in enumerate(("fixed", "dynamic")):
            e = rec[f"{mode}/{desks}"]
            x = cx + (j - 0.5) * (bw + 8)
            focal = mode == "dynamic"
            q.bar(x, bw, q.ymap(e["offersPerSearch"], 4), focal=focal, fill=None if focal else rgba(GREY, 0.35), stroke=None if focal else GREY)
            f.tech(x, q.ymap(e["offersPerSearch"], 4) - 8, f"{e['offersPerSearch']:.2f}", ACCENT if focal else INK, size=KEY)
        f.tech(cx, q.y1 + 20, label.upper(), MUTED)
    f.legend(504, [("swatch", rgba(GREY, 0.35), "FIXED, 10 WEEKDAYS"), ("swatch", rgba(ACCENT, 0.12), "DYNAMIC, UP TO 15")], note="high demand, 3 seeds per bar")
    return f


def refinement_frontier(rec: dict) -> Figure:
    f = Figure("refinement_frontier", "How long the optional refinement should run on a full schedule (#179)", "Line chart, one line per fleet size, of the mean extra modeled cost per booking against an uncapped 5 s search, by the median search time at one desk, as the optional refinement's cap goes from 0 to 3000 ms on fully booked schedules; the band is the noise two identical searches show.", 980, 640)
    p = Plot(f, 120, 56, 760, 470); p.frame("Extra cost per booking against the uncapped search, by search time")
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
    f.callout(780, 96, ["Points left to right:", "caps of 0, 250, 1000,", "2000 and 3000 ms.", "2000 ms is the", "default now."])
    f.legend(580, [("solid-dot", rgba(ACCENT, 0.3), "2000 MS, THE DEFAULT"), ("line", BLUE, "ONE FLEET SIZE"), ("swatch", rgba(MUTED, 0.10), "NOISE")], note="rerun, full schedules, 40 cases per point")
    return f


def capacity(rec: dict) -> Figure:
    f = Figure("capacity", "Booking throughput by pool layout and vCPU (#179)", "Bars of booking searches answered per minute by ten clients for seven layouts of the booking pool on 2, 3 and 4 vCPU, replicas times slots, each the mean of four fleet-and-schedule cells; the fastest layout at each vCPU count is green.", 980, 580)
    p = Plot(f, 110, 56, 940, 420); p.frame("Searches per minute, 10 clients, by booking pool layout")
    layouts = list(rec.items())
    ceiling = nice_ceiling(max(v["perMinute"] for _, v in layouts))
    p.ygrid(ceiling, None, "SEARCHES / MIN")
    groups = {}
    for name, v in layouts:
        groups.setdefault(v["vcpu"], []).append((name, v))
    pitch = (p.x1 - p.x0) / (len(layouts) + len(groups) - 1)
    k = 0
    for vcpu, items in sorted(groups.items()):
        start = k
        best = max(v["perMinute"] for _, v in items)
        for name, v in items:
            cx = p.x0 + pitch * (k + 0.5)
            replicas, slots = name.split("x")
            focal = v["perMinute"] == best
            p.bar(cx, pitch * 0.6, p.ymap(v["perMinute"], ceiling), focal=focal, fill=None if focal else rgba(BLUE, 0.30), stroke=None if focal else BLUE)
            f.tech(cx, p.ymap(v["perMinute"], ceiling) - 8, whole(v["perMinute"]), ACCENT if focal else INK, size=KEY)
            f.text(cx, p.y1 + 20, f"{replicas} x {slots}", KEY, INK, MONO, "middle")
            k += 1
        f.tech(p.x0 + pitch * (start + len(items) / 2), p.y1 + 44, f"{vcpu} VCPU", MUTED)
        k += 1
    f.tech((p.x0 + p.x1) / 2, p.y1 + 68, "REPLICAS X SLOTS", MUTED)
    f.legend(536, [("swatch", rgba(ACCENT, 0.12), "FASTEST LAYOUT AT EACH VCPU"), ("swatch", rgba(BLUE, 0.30), "OTHER LAYOUTS")], note="2:250 variant, mean of 4 cells per bar")
    return f


def engine_chain(rec: list) -> Figure:
    f = Figure("engine_chain", "The engine's chain of representation changes, shipped or not", "Horizontal bars on a log scale of each engine change's median paired moves-per-second ratio on the phase it targets, each against the code just before it in its own study; the dashed line is the 1.05x promotion bar; changes that did not ship are grey with the reason.", 980, 760)
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
    f.tech((x0 + x1) / 2, bottom + 44, "MOVES PER SECOND, CHANGE OVER THE CODE BEFORE IT (LOG)", MUTED)
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
    f.legend(bottom + 84, [("swatch", rgba(ACCENT, 0.18), "SHIPPED"), ("swatch", rgba(GREY, 0.25), "MEASURED, NOT SHIPPED"), ("line-dashed", ACCENT, "PROMOTION BAR")], note="each bar against its own control")
    f.height = bottom + 120
    return f


def fairness_b(rec: dict) -> Figure:
    f = Figure("fairness_b", "Formula b against the decimal variance, case by case (#187)", "Scatter of the 36 cases of the road matrix numbering study: formula b's change in modeled cost against the fixed production engine on the x axis and its fairness variance as a ratio of production's on the y axis, colored by fleet size, with the study's summary.", 980, 620)
    p = Plot(f, 120, 56, 700, 470); p.frame("Formula b against production, per case, 15 s")
    xs = [e["cost_difference"] for e in rec["pairs"]]
    xlim = math.ceil(max(5, max(abs(v) for v in xs)) / 5) * 5
    xmap = lambda v: p.x0 + (v + xlim) / (2 * xlim) * (p.x1 - p.x0)
    yceil = 1.25
    for v in (0, 0.25, 0.5, 0.75, 1.0, 1.25):
        y = p.ymap(v, yceil)
        f.line(p.x0, y, p.x1, y, ink(0.45) if v == 1.0 else (ink(0.25) if v == 0 else ink(0.08)), 1.2 if v == 1.0 else 0.8)
        f.tech(p.x0 - 8, y + 4, f"{v:.2f}", MUTED, "end")
    f.text(p.x0 - 60, (p.y0 + p.y1) / 2, "VARIANCE RATIO TO PRODUCTION", TECH, MUTED, MONO, "middle", spacing="0.10em", extra=f'transform="rotate(-90 {p.x0 - 60:g} {(p.y0 + p.y1) / 2:g})"')
    v = -xlim
    while v <= xlim + 1e-9:
        f.line(xmap(v), p.y0, xmap(v), p.y1, ink(0.45) if v == 0 else ink(0.06), 1.2 if v == 0 else 0.8)
        f.tech(xmap(v), p.y1 + 20, money(v).replace(".00", ""), MUTED)
        v += xlim / 2
    f.tech((p.x0 + p.x1) / 2, p.y1 + 44, "CHANGE IN MODELED COST PER DAY", MUTED)
    f.rect(p.x0 + 1, p.ymap(1.0, yceil), p.x1 - p.x0 - 2, p.y1 - p.ymap(1.0, yceil) - 1, rgba(ACCENT, 0.04))
    f.label(p.x0 + 110, p.y1 - 12, "FAIRER THAN PRODUCTION", ACCENT, KEY)
    colors = {10: SERIES[0], 30: BLUE, 50: AMBER}
    for e in rec["pairs"]:
        f.circle(xmap(e["cost_difference"]), p.ymap(min(e["variance_ratio"], yceil), yceil), 5, rgba(colors[e["technicians"]], 0.75), INK, 0.6)
    s = rec["summary"]
    f.eyebrow_text(730, 80, "FAIRNESS PHASE, FORMULA B")
    f.card(724, 92, 220, 168, "SHIPPED (#189)", [("big", f"{s['fairnessPhaseSpeedup']['median']:.1f}x"), ("tech", "fairness moves/s, this rerun"), ("tech", f"{s['fairness']['fairer']} fairer, {s['fairness']['same']} same,"),
                                                 ("tech", f"{s['fairness']['lessFair']} less fair"), ("tech", f"median cost {money(s['cost']['medianDifferenceCents'] / 100)}")], True)
    f.legend(564, [("solid-dot", rgba(SERIES[0], 0.75), "10 TECHS"), ("solid-dot", rgba(BLUE, 0.75), "30 TECHS"), ("solid-dot", rgba(AMBER, 0.75), "50 TECHS")], note="numbering study, 2 repetitions per case")
    return f


def stack(rec: list) -> Figure:
    f = Figure("stack", "The harness ratios held through the public API on the rehearsal stack", "Dot pairs per fleet size: the paired moves-per-second ratio on the workstation harness (amber) and the same change measured through the public API on the rehearsal stack (green), for formula b's fairness phase (#189) and the shipped long score, long money, W and A on the cost phase (#191).", 980, 560)
    x0, x1 = 360, 900
    xmap = lambda v: x0 + (math.log(v) - math.log(0.8)) / (math.log(8) - math.log(0.8)) * (x1 - x0)
    top, pitch = 72, 52
    bottom = top + pitch * len(rec)
    for t in (1, 2, 4, 8):
        f.line(xmap(t), top - 16, xmap(t), bottom, ink(0.45) if t == 1 else ink(0.08), 1.2 if t == 1 else 0.8)
        f.tech(xmap(t), bottom + 20, f"{t:g}x", MUTED)
    f.tech((x0 + x1) / 2, bottom + 44, "MOVES PER SECOND RATIO ON THE TARGETED PHASE (LOG)", MUTED)
    for i, e in enumerate(rec):
        y = top + i * pitch + 18
        f.text(x0 - 16, y + 5, e["name"], NAME - 2, INK, SANS, "end", weight="700")
        xs = xmap(e["stack"])
        right_of = e["harness"] is not None and xmap(e["harness"]) > xs
        if e["harness"] is not None:
            xh = xmap(e["harness"])
            f.line(min(xh, xs), y, max(xh, xs), y, ink(0.45), 1)
            f.circle(xh, y, 6, AMBER, INK, 1)
            f.text(xh + (10 if xh > xs else -10), y + 4, f"{e['harness']:.2f}x", KEY, MUTED, MONO, "start" if xh > xs else "end")
        f.circle(xs, y, 6, ACCENT, INK, 1)
        f.text(xs + (-10 if right_of else 10), y + 4, f"{e['stack']:.2f}x", KEY, ACCENT, MONO, "end" if right_of else "start")
    f.legend(bottom + 84, [("solid-dot", AMBER, "WORKSTATION HARNESS"), ("solid-dot", ACCENT, "REHEARSAL STACK, PUBLIC API")], note="harness ran 10, 30, 50 techs; L+M+W+A only overall")
    f.height = bottom + 120
    return f
