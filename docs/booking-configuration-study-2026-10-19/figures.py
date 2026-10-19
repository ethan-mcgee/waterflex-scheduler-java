"""The report's figures as self-contained HTML with inline SVG, in the report's own skin, built from the chart data.

Types: a heatmap, dot plots (rows on one shared horizontal scale, two or three marks a row), a dumbbell, line charts
and stacked bars. Every drawn value is bound to a data attribute, no element carries a transform, and the type is
sized for a 6.85 inch print width (1px is about 0.5pt there), so the sizes are about twice the skill's screen ramp.
Rendered to PNG by render.py.
"""
from html import escape

PAPER, INK, MUTED, ACCENT = "#FFFFFF", "#1F2A33", "#444444", "#B22222"
SERIES = ("#1F2A33", "#2E75B6", "#2E8B57", "#7A5C00")  # Non-focal line series; each clears 3:1 on white.
SANS = "'Calibri', 'Carlito', system-ui, sans-serif"
MONO = "'Consolas', 'Liberation Mono', ui-monospace, monospace"
CAP_LABEL = {0: "0 ms", 250: "250 ms (today)", 1000: "1000 ms", 2000: "2000 ms", 3000: "3000 ms"}
ROWS = {4: (88, 96), 5: (64, 96), 6: (64, 76), 7: (52, 72), 8: (48, 68)}  # rows: (pitch, first y), from the dumbbell spec.
HEIGHT = 560  # 500 of plot plus the legend strip and two source lines.


def ink(opacity):
    return f"rgba(31,42,51,{opacity:.2f})"


def dollars(cents):
    value = cents / 100
    return f"{'-' if value < 0 else ''}${abs(value):.2f}"


def page(slug, title, desc, height, body):
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{escape(title)}</title>
<style>
  :root {{ --paper: {PAPER}; --ink: {INK}; --muted: {MUTED}; --accent: {ACCENT}; }}
  html, body {{ margin: 0; background: var(--paper); color: var(--ink); font-family: {SANS}; }}
  .wrap {{ overflow-x: auto; }}
  svg {{ display: block; width: 1000px; min-width: 1000px; }}
</style>
</head>
<body>
<div class="wrap">
<svg role="img" aria-labelledby="{slug}-title {slug}-desc" viewBox="0 0 1000 {height}" xmlns="http://www.w3.org/2000/svg">
<title id="{slug}-title">{escape(title)}</title>
<desc id="{slug}-desc">{escape(desc)}</desc>
<rect x="0" y="0" width="1000" height="{height}" fill="{PAPER}"/>
{body}
</svg>
</div>
</body>
</html>
"""


def text(x, y, s, size, fill=INK, anchor="start", weight=None, family=SANS, extra=""):
    w = f' font-weight="{weight}"' if weight else ""
    return f'<text x="{x}" y="{y}" fill="{fill}" font-size="{size}" font-family="{family}" text-anchor="{anchor}"{w}{extra}>{escape(s)}</text>'


def eyebrow(x, y, s, anchor="start"):
    return text(x, y, s, 13, MUTED, anchor, family=MONO, extra=' letter-spacing="0.14em"')


def strip(out, x0, keys, sources, rule_y=478):
    """The legend strip: a rule, LEGEND, the keys (already drawn by the caller into `keys`), then the source lines."""
    out.append(f'<line x1="{x0}" y1="{rule_y}" x2="960" y2="{rule_y}" stroke="{ink(0.12)}" stroke-width="1"/>')
    out.append(eyebrow(x0, rule_y + 22, "LEGEND"))
    out.extend(keys)
    for i, line in enumerate(sources):
        out.append(text(x0, rule_y + 48 + 20 * i, line, 14, MUTED))


def domain(values, step):
    """Axis bounds that contain the data without being its extremes: zero anchors whichever side the data does not cross."""
    lo, hi = min(values), max(values)
    floor = 0 if lo >= 0 else -step * ((-lo) // step + 1)
    ceil = 0 if hi <= 0 else step * (hi // step + 1)
    return floor, ceil


def scale_x(floor, ceil):
    return lambda v: round(200 + (v - floor) / (ceil - floor) * 760)


def axis_h(out, floor, ceil, step, fmt):
    """A horizontal value axis across x 200 to 960 with gridlines at each step, the axis line at the floor and a zero line."""
    px = scale_x(floor, ceil)
    tick = floor
    while tick <= ceil + 1e-9:
        x = px(tick)
        at_zero = abs(tick) < 1e-9
        if tick == floor or at_zero:
            out.append(f'<line x1="{x}" y1="40" x2="{x}" y2="420" stroke="{ink(0.25)}" stroke-width="1"/>')
        else:
            out.append(f'<line x1="{x}" y1="56" x2="{x}" y2="408" stroke="{ink(0.08)}" stroke-width="0.8"/>')
        out.append(text(x, 440, fmt(tick), 15, MUTED, "middle", family=MONO))
        tick += step
    return px


def heatmap(rows, focal, noise_cents):
    """rows: [{fleet, caps, meanCents}]; focal: (fleet, cap). One ink ramp over the non-focal cells, one accent cell."""
    caps = rows[0]["caps"]
    x0, y0, w, h, gap = 160, 92, 156, 56, 4
    non_focal = [c for r in rows for cap, c in zip(r["caps"], r["meanCents"]) if (r["fleet"], cap) != focal]
    top = max(non_focal)
    assert top > 0
    ramp = lambda cents: max(0.07, cents / top * 0.65)
    out = [eyebrow(x0, 32, "MEAN ADDED COST PER BOOKING AGAINST THE UNCAPPED SEARCH, FULL SCHEDULES"),
           eyebrow(x0 + (len(caps) * (w + gap) - gap) / 2, 54, "OPTIONAL REFINEMENT CAP", "middle")]
    for j, cap in enumerate(caps):
        out.append(text(x0 + j * (w + gap) + w / 2, y0 - 10, CAP_LABEL[cap], 17, INK, "middle", 600))
    focal_value = None
    for i, r in enumerate(rows):
        y = y0 + i * (h + gap)
        out.append(text(x0 - 12, y + h / 2 + 6, f"{r['fleet']} technicians", 18, INK, "end", 600))
        for j, (cap, cents) in enumerate(zip(r["caps"], r["meanCents"])):
            x = x0 + j * (w + gap)
            out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{PAPER}"/>')
            if (r["fleet"], cap) == focal:
                focal_value = cents
                out.append(f'<rect data-row="{r["fleet"]}" data-col="{cap}" data-value="{cents:.2f}" data-focal="true" x="{x}" y="{y}" width="{w}" height="{h}" '
                           f'fill="rgba(178,34,34,0.85)" stroke="{ACCENT}" stroke-width="1.2"/>')
                color = PAPER
            else:
                op = ramp(cents)
                out.append(f'<rect data-row="{r["fleet"]}" data-col="{cap}" data-value="{cents:.2f}" x="{x}" y="{y}" width="{w}" height="{h}" fill="{ink(op)}"/>')
                color = PAPER if op >= 0.40 else INK
            out.append(text(x + w / 2, y + h / 2 + 6, dollars(cents), 17, color, "middle", 600))
    assert focal_value is not None
    bottom = y0 + len(rows) * (h + gap) - gap
    ly = bottom + 28
    out.append(f'<line x1="{x0}" y1="{ly}" x2="{x0 + len(caps) * (w + gap) - gap}" y2="{ly}" stroke="{ink(0.12)}" stroke-width="1"/>')
    out.append(eyebrow(x0, ly + 22, "LEGEND"))
    kx = x0 + 170
    out.append(text(kx, ly + 22, "LOW $0.00", 15, MUTED, "end"))
    for k, op in enumerate((0.07, 0.22, 0.36, 0.50, 0.65)):
        out.append(f'<rect x="{kx + 10 + k * 28}" y="{ly + 10}" width="24" height="14" fill="{ink(op)}"/>')
    out.append(text(kx + 10 + 5 * 28 + 6, ly + 22, f"HIGH {dollars(top)}", 15, MUTED))
    fx = kx + 10 + 5 * 28 + 120
    out.append(f'<rect x="{fx}" y="{ly + 10}" width="24" height="14" fill="rgba(178,34,34,0.85)" stroke="{ACCENT}" stroke-width="1.2"/>')
    out.append(text(fx + 32, ly + 22, f"Today's default at {focal[0]} technicians, {dollars(focal_value)}", 15, MUTED))
    out.append(text(x0, ly + 50, f"Rerun, 40 cases per cell. The ramp covers the non-focal cells from $0.00 to {dollars(top)}; a gap below $0.00 sits at the floor.", 14, MUTED))
    out.append(text(x0, ly + 70, f"Differences under about {dollars(noise_cents)} are noise.", 14, MUTED))
    return page("gap", "Gap left by each refinement cap on full schedules",
                "A heatmap of the mean added cost per booking against the uncapped search for five fleet sizes and five refinement caps; today's 250 ms cap at 10 technicians is the focal cell.",
                540, "\n".join(out))


def dotplot(slug, title, desc, head, rows, names, step, fmt, axis_title, sources, dumbbell=False):
    """One row per category on one shared horizontal scale: a hollow reference mark, a solid accent mark and, unless
    this is a dumbbell, a tick for a third value. rows: [{label, hollow, solid, tick?}] in axis units; names: the
    legend names for hollow, solid (and tick)."""
    values = [v for r in rows for v in (r["hollow"], r["solid"], *([r["tick"]] if "tick" in r else []))]
    floor, ceil = domain(values, step)
    pitch, first = ROWS[len(rows)]
    out = [eyebrow(200, 32, head)]
    px = axis_h(out, floor, ceil, step, fmt)
    out.append(eyebrow(580, 462, axis_title, "middle"))
    for i, r in enumerate(rows):
        y = first + pitch * i
        a, b = r["hollow"], r["solid"]
        xa, xb = px(a), px(b)
        out.append(text(188, y + 6, r["label"], 18, INK, "end", 600))
        out.append(f'<line data-series="{escape(r["label"])}" data-from="{a:.2f}" data-to="{b:.2f}" x1="{xa}" y1="{y}" x2="{xb}" y2="{y}" stroke="{ink(0.55)}" stroke-width="1"/>')
        if "tick" in r:
            xm = px(r["tick"])
            out.append(f'<line data-series="{escape(r["label"])}" data-mid="{r["tick"]:.2f}" x1="{xm}" y1="{y - 9}" x2="{xm}" y2="{y + 9}" stroke="{INK}" stroke-width="2"/>')
            out.append(text(xm, y - 16, fmt(r["tick"]), 15, MUTED, "middle"))
        out.append(f'<circle cx="{xa}" cy="{y}" r="6" fill="{PAPER}" stroke="{MUTED}" stroke-width="1.5"/>')
        out.append(f'<circle cx="{xb}" cy="{y}" r="6" fill="{ACCENT}" stroke="{INK}" stroke-width="1"/>')
        left, right = ((a, xa), (b, xb)) if xa <= xb else ((b, xb), (a, xa))
        if left[1] - 12 - 8 * len(fmt(left[0])) < 200:  # The label would reach the row label: put it above its mark instead.
            out.append(text(left[1], y - 12, fmt(left[0]), 15, MUTED, "middle"))
        else:
            out.append(text(left[1] - 12, y + 6, fmt(left[0]), 15, MUTED, "end"))
        out.append(text(right[1] + 12, y + 6, fmt(right[0]), 15, MUTED))
    k2 = 300 + 40 + round(7.2 * len(names[0]))
    k3 = k2 + 40 + round(7.2 * len(names[1]))
    keys = [f'<circle cx="300" cy="495" r="6" fill="{PAPER}" stroke="{MUTED}" stroke-width="1.5"/>', text(314, 500, names[0], 15, MUTED),
            f'<circle cx="{k2}" cy="495" r="6" fill="{ACCENT}" stroke="{INK}" stroke-width="1"/>', text(k2 + 14, 500, names[1], 15, MUTED)]
    if not dumbbell:
        keys += [f'<line x1="{k3}" y1="486" x2="{k3}" y2="504" stroke="{INK}" stroke-width="2"/>', text(k3 + 14, 500, names[2], 15, MUTED)]
    strip(out, 200, keys, sources)
    return page(slug, title, desc, HEIGHT, "\n".join(out))


def swarm(slug, title, desc, head, rows, step, fmt, axis_title, sources):
    """One row per category; several hollow and several solid dots on one shared scale, dodged above and below the row
    line where they would overprint. The vertical offset is packing, not data. rows: [{label, hollow: [...], solid: [...]}]."""
    values = [v for r in rows for v in r["hollow"] + r["solid"]]
    floor, ceil = domain(values, step)
    pitch, first = ROWS[len(rows)]
    out = [eyebrow(200, 32, head)]
    px = axis_h(out, floor, ceil, step, fmt)
    out.append(eyebrow(580, 462, axis_title, "middle"))
    r_dot, lane = 5, 12
    for i, r in enumerate(rows):
        y = first + pitch * i
        out.append(text(188, y + 6, r["label"], 18, INK, "end", 600))
        out.append(f'<line x1="200" y1="{y}" x2="960" y2="{y}" stroke="{ink(0.08)}" stroke-width="0.8"/>')
        dots = sorted([(px(v), v, False) for v in r["hollow"]] + [(px(v), v, True) for v in r["solid"]])
        placed = []
        for x, v, solid in dots:
            for k in (0, 1, -1, 2, -2, 3, -3):
                cy = y + k * lane
                if all(abs(x - qx) >= 2 * r_dot + 1 or qy != cy for qx, qy in placed):
                    break
            placed.append((x, cy))
            style = f'fill="{INK}" stroke="{INK}" stroke-width="1"' if solid else f'fill="{PAPER}" stroke="{MUTED}" stroke-width="1.5"'
            out.append(f'<circle data-row="{escape(r["label"])}" data-value="{v:.2f}" cx="{x}" cy="{cy}" r="{r_dot}" {style}/>')
    keys = [f'<circle cx="300" cy="495" r="5" fill="{PAPER}" stroke="{MUTED}" stroke-width="1.5"/>', text(314, 500, "cap 250 ms, thresholds 0, 2, 5 and 10", 15, MUTED),
            f'<circle cx="{300 + 330}" cy="495" r="5" fill="{INK}" stroke="{INK}" stroke-width="1"/>', text(314 + 330, 500, "cap 3000 ms, the same four thresholds", 15, MUTED)]
    strip(out, 200, keys, sources)
    return page(slug, title, desc, HEIGHT, "\n".join(out))


def line_chart(slug, title, desc, head, x_labels, series, step, fmt, y_title, sources, reference=None, x_title="TECHNICIANS, FULL SCHEDULE"):
    """Evenly spaced x positions, one polyline per series, the focal series in the accent with dots, end labels on every
    series. series: [{name, values, focal}]; reference: (value, label) for a dashed horizontal line."""
    top = max(v for s in series for v in s["values"])
    if reference:
        top = max(top, reference[0])
    ceil = step * (top // step + 1)
    xs = [120 + i * 760 / (len(x_labels) - 1) for i in range(len(x_labels))]
    py = lambda v: round(420 - v / ceil * 380, 1)
    out = [eyebrow(80, 32, head)]
    tick = 0
    while tick <= ceil + 1e-9:
        y = py(tick)
        out.append(f'<line x1="80" y1="{y}" x2="960" y2="{y}" stroke="{ink(0.25) if tick == 0 else ink(0.08)}" stroke-width="{1 if tick == 0 else 0.8}"/>')
        out.append(text(72, y + 5, fmt(tick), 15, MUTED, "end", family=MONO))
        tick += step
    out.append(f'<line x1="80" y1="40" x2="80" y2="420" stroke="{ink(0.25)}" stroke-width="1"/>')
    for x, label in zip(xs, x_labels):
        out.append(text(round(x), 444, str(label), 16, INK, "middle", 600))
    out.append(eyebrow(540, 466, x_title, "middle"))
    if reference:
        y = py(reference[0])
        out.append(f'<line x1="80" y1="{y}" x2="960" y2="{y}" stroke="{ACCENT}" stroke-width="1" stroke-dasharray="6,4"/>')
        out.append(text(956, y - 6, reference[1], 14, ACCENT, "end"))
    colors = iter(SERIES)
    keys, kx, ends = [], 180, []
    for s in series:
        color = ACCENT if s["focal"] else next(colors)
        points = " ".join(f"{round(x)},{py(v)}" for x, v in zip(xs, s["values"]))
        out.append(f'<polyline data-series="{escape(s["name"])}" data-values="{" ".join(f"{v:.1f}" for v in s["values"])}" points="{points}" fill="none" '
                   f'stroke="{color}" stroke-width="{2.7 if s["focal"] else 1.8}" stroke-linejoin="round"/>')
        if s["focal"]:
            for x, v in zip(xs, s["values"]):
                out.append(f'<circle cx="{round(x)}" cy="{py(v)}" r="5" fill="{color}"/>')
        ends.append([py(s["values"][-1]), fmt(s["values"][-1]), color])
        keys += [f'<line x1="{kx}" y1="495" x2="{kx + 24}" y2="495" stroke="{color}" stroke-width="{2.7 if s["focal"] else 1.8}"/>', text(kx + 32, 500, s["name"], 15, MUTED)]
        kx += 56 + round(7.2 * len(s["name"]))
    # End labels: the value sits at its own line's end, but two labels nearer than a line of type are pushed apart.
    ends.sort()
    for i in range(1, len(ends)):
        if ends[i][0] - ends[i - 1][0] < 16:
            ends[i][0] = ends[i - 1][0] + 16
    for y, label, color in ends:
        out.append(text(round(xs[-1]) + 10, y + 5, label, 14, color))
    strip(out, 80, keys, sources)
    return page(slug, title, desc, HEIGHT, "\n".join(out))


def pipeline(slug, title, desc, head, stages, deadline_ms, reserve_ms, guard_ms, sources):
    """A left-to-right flowchart of one booking search over a bar of its deadline. stages: five dicts {name, does, limit}
    in order; the third is the shed decision (a diamond) and the fourth the stage the cap governs (the focal node).
    The bar below splits the deadline into exploration, the publish reserve and the database admission guard."""
    assert len(stages) == 5
    x0, w, pitch, y_top, h = 30, 160, 190, 70, 80
    mid = y_top + h / 2
    cx = lambda i: x0 + i * pitch + w / 2
    out = [f'<defs><marker id="{slug}-arrow" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto"><polygon points="0 0, 8 3, 0 6" fill="{MUTED}"/></marker>'
           f'<marker id="{slug}-arrow-accent" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto"><polygon points="0 0, 8 3, 0 6" fill="{ACCENT}"/></marker></defs>',
           eyebrow(x0, 32, head)]
    # Connectors first, so they sit behind the nodes. Same-row arrows are straight; the skipped path is a right-angle loop.
    for i in range(4):
        accent = i == 2  # The decision's forward exit enters the focal node.
        x1, x2 = x0 + i * pitch + w, x0 + (i + 1) * pitch
        if i == 1: x2 = cx(2) - w / 2  # The diamond's left point.
        if i == 2: x1 = cx(2) + w / 2  # The diamond's right point.
        out.append(f'<line x1="{x1}" y1="{mid}" x2="{x2 - 1}" y2="{mid}" stroke="{ACCENT if accent else MUTED}" stroke-width="{1.4 if accent else 1}" '
                   f'marker-end="url(#{slug}-arrow{"-accent" if accent else ""})"/>')
    out.append(text(cx(3) - w / 2 - 4, mid - 10, "EMPTY", 12, ACCENT, "end", family=MONO, extra=' letter-spacing="0.1em"'))
    loop_y = y_top + h + 50
    out.append(f'<path d="M {cx(2)},{y_top + h} V {loop_y - 8} Q {cx(2)},{loop_y} {cx(2) + 8},{loop_y} H {cx(4) - 8} Q {cx(4)},{loop_y} {cx(4)},{loop_y - 8} '
               f'V {y_top + h + 1}" fill="none" stroke="{MUTED}" stroke-width="1" stroke-dasharray="5,4" marker-end="url(#{slug}-arrow)"/>')
    out.append(text((cx(2) + cx(4)) / 2, loop_y + 18, "QUEUED: SKIP THE REFINEMENT, FIRST-PASS OFFERS ONLY", 12, MUTED, "middle", family=MONO, extra=' letter-spacing="0.1em"'))
    out.append(text(cx(3), y_top + h + 22, "the cap's clock starts here, before the neighborhood route fetch", 14, MUTED, "middle", extra=' font-style="italic"'))
    for i, s in enumerate(stages):
        x = x0 + i * pitch
        focal = i == 3
        if i == 2:
            pts = f"{cx(2)},{y_top} {cx(2) + w / 2},{mid} {cx(2)},{y_top + h} {cx(2) - w / 2},{mid}"
            out.append(f'<polygon data-stage="{escape(s["name"])}" points="{pts}" fill="{PAPER}" stroke="{ink(0.6)}" stroke-width="1"/>')
            out.append(text(cx(2), mid - 4, s["name"], 17, INK, "middle", 600))
            out.append(text(cx(2), mid + 16, s["does"], 12, MUTED, "middle", family=MONO))
            continue
        out.append(f'<rect data-stage="{escape(s["name"])}" x="{x}" y="{y_top}" width="{w}" height="{h}" rx="6" fill="{PAPER}" '
                   f'stroke="{ACCENT if focal else ink(0.6)}" stroke-width="{1.6 if focal else 1}"/>')
        out.append(text(cx(i), y_top + 26, s["name"], 17, INK, "middle", 600))
        out.append(text(cx(i), y_top + 48, s["does"], 12, MUTED, "middle", family=MONO))
        out.append(text(cx(i), y_top + 66, s["limit"], 12, ACCENT if focal else MUTED, "middle", family=MONO))
    # The deadline bar: exploration, the reserve, and the guard inside the reserve.
    by, bh, bx0, bw = 262, 36, x0, 920
    px = lambda v: round(bx0 + v / deadline_ms * bw, 1)
    out.append(eyebrow(x0, by - 14, f"THE {deadline_ms / 1000:.0f} S DEADLINE"))
    segments = (("exploration", 0, deadline_ms - reserve_ms, ink(0.10), "FIRST PASS AND REFINEMENT"), ("reserve", deadline_ms - reserve_ms, deadline_ms, "rgba(178,34,34,0.18)", "RESERVE"),
                ("guard", deadline_ms - guard_ms, deadline_ms, "rgba(178,34,34,0.45)", "GUARD"))
    for key, a, b, fill, label in segments:
        out.append(f'<rect data-segment="{key}" data-from-ms="{a}" data-to-ms="{b}" x="{px(a)}" y="{by}" width="{px(b) - px(a)}" height="{bh}" fill="{fill}" stroke="{ink(0.5)}" stroke-width="0.8"/>')
        lx = (px(a) + px(deadline_ms - guard_ms)) / 2 if key == "reserve" else (px(a) + px(b)) / 2  # The reserve label sits left of the guard.
        out.append(text(lx, by + bh / 2 + 5, f"{label} {b - a:,} MS" if key == "exploration" else label, 12, PAPER if key == "guard" else INK, "middle", family=MONO, extra=' letter-spacing="0.08em"'))
    for v in range(0, deadline_ms + 1, 1000):
        out.append(f'<line x1="{px(v)}" y1="{by + bh}" x2="{px(v)}" y2="{by + bh + 6}" stroke="{ink(0.5)}" stroke-width="0.8"/>')
        out.append(text(px(v), by + bh + 24, f"{v:,} ms", 13, MUTED, "middle", family=MONO))
    for v in (deadline_ms - reserve_ms, deadline_ms - guard_ms):
        out.append(text(px(v), by - 6, f"{v:,}", 12, MUTED, "middle", family=MONO))
    rule_y = 352
    out.append(f'<line x1="{x0}" y1="{rule_y}" x2="960" y2="{rule_y}" stroke="{ink(0.12)}" stroke-width="1"/>')
    out.append(eyebrow(x0, rule_y + 22, "LEGEND"))
    box = lambda fill, stroke, width=1, rx=0: (lambda x, y: f'<rect x="{x}" y="{y}" width="22" height="14" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="{width}"/>')
    dashed = lambda x, y: f'<line x1="{x}" y1="{y + 7}" x2="{x + 28}" y2="{y + 7}" stroke="{MUTED}" stroke-dasharray="5,4"/>'
    keys = ((box(PAPER, ink(0.6), rx=3), "Stage", 22), (box(PAPER, ACCENT, 1.6, 3), "Stage the cap governs (focal)", 22), (dashed, "Path under load", 28),
            (box(ink(0.10), ink(0.5), 0.8), "Exploration", 22), (box("rgba(178,34,34,0.18)", ink(0.5), 0.8), f"Publish reserve, {reserve_ms:,} ms", 22),
            (box("rgba(178,34,34,0.45)", ink(0.5), 0.8), f"Database admission guard, last {guard_ms} ms", 22))
    for row, group in enumerate((keys[:3], keys[3:])):  # Two legend rows: the flow keys, then the deadline bar keys.
        kx, ky = x0 + 100, rule_y + 10 + 26 * row
        for mark, name, mw in group:
            out.append(mark(kx, ky))
            out.append(text(kx + mw + 8, ky + 12, name, 15, MUTED))
            kx += mw + 8 + round(7.2 * len(name)) + 28
    for i, line in enumerate(sources):
        out.append(text(x0, rule_y + 74 + 20 * i, line, 14, MUTED))
    return page(slug, title, desc, 450, "\n".join(out))


def stacked_bar(slug, title, desc, head, bars, parts, step, fmt, y_title, sources, references=(), focal=None, frames=None):
    """bars: [{label, values: {part: v}}]; parts: [(key, name, fill)] bottom to top; focal: (bar label, part key) drawn in
    the accent; references: [(value, label, dasharray)] horizontal lines; frames: (name, [value per bar]) draws a dashed
    budget frame from the baseline to that value behind each bar, so a stack taller than its frame is visibly over budget.
    The total printed above each bar is the sum of its parts."""
    totals = [sum(b["values"].values()) for b in bars]
    top = max(totals + [r[0] for r in references] + (list(frames[1]) if frames else []))
    ceil = step * (top // step + 1)
    py = lambda v: round(420 - v / ceil * 380, 1)
    pitch = 760 / len(bars)
    width = round(pitch * 0.6)
    out = [eyebrow(80, 32, head)]
    tick = 0
    while tick <= ceil + 1e-9:
        y = py(tick)
        out.append(f'<line x1="80" y1="{y}" x2="960" y2="{y}" stroke="{ink(0.25) if tick == 0 else ink(0.08)}" stroke-width="{1 if tick == 0 else 0.8}"/>')
        out.append(text(72, y + 5, fmt(tick), 15, MUTED, "end", family=MONO))
        tick += step
    out.append(f'<line x1="80" y1="40" x2="80" y2="420" stroke="{ink(0.25)}" stroke-width="1"/>')
    for value, label, dash in references:
        y = py(value)
        out.append(f'<line x1="80" y1="{y}" x2="960" y2="{y}" stroke="{INK}" stroke-width="1" stroke-dasharray="{dash}"/>')
        out.append(text(956, y - 6, label, 14, INK, "end"))
    for i, b in enumerate(bars):
        x = round(120 + i * pitch + (pitch - width) / 2 - 40)
        base = 0
        crossings = [r[0] for r in references]
        if frames:
            crossings.append(frames[1][i])
            out.append(f'<rect data-frame="{frames[1][i]:.1f}" x="{x - 10}" y="{py(frames[1][i])}" width="{width + 20}" height="{round(420 - py(frames[1][i]), 1)}" '
                       f'fill="none" stroke="{INK}" stroke-width="1" stroke-dasharray="5,4"/>')
        for key, name, fill in parts:
            v = b["values"][key]
            y_top, y_bottom = py(base + v), py(base)
            h = round(y_bottom - y_top, 1)
            out.append(f'<rect x="{x}" y="{y_top}" width="{width}" height="{h}" fill="{PAPER}"/>')
            is_focal = focal is not None and focal[:2] == (b["label"], key)
            out.append(f'<rect data-bar="{escape(b["label"])}" data-part="{key}" data-value="{v:.1f}" x="{x}" y="{y_top}" width="{width}" height="{h}" '
                       f'fill="{ACCENT if is_focal else fill}" stroke="{ACCENT if is_focal else ink(0.6)}" stroke-width="{1.2 if is_focal else 0.8}"/>')
            if h >= 24:
                dark = is_focal or fill in (INK,) or fill.startswith("rgba(31,42,51,0.5") or fill.startswith("rgba(31,42,51,0.6") or fill.startswith("rgba(31,42,51,0.7")
                out.append(text(x + width / 2, (y_top + y_bottom) / 2 + 5, fmt(v), 14, PAPER if dark else INK, "middle"))
            base += v
        label_y = py(base) - 8
        if any(label_y - 14 <= py(v) <= label_y + 4 for v in crossings):
            # A reference or frame line crosses the total; an opaque mask keeps the text legible, the line resumes on either side.
            half = round(4 * len(fmt(base))) + 6
            out.append(f'<rect x="{x + width / 2 - half}" y="{label_y - 14}" width="{2 * half}" height="18" fill="{PAPER}"/>')
        out.append(text(x + width / 2, label_y, fmt(base), 15, MUTED, "middle"))
        out.append(text(x + width / 2, 444, b["label"], 16, INK, "middle", 600))
    keys, kx = [], 180
    for key, name, fill in parts:
        keys += [f'<rect x="{kx}" y="488" width="16" height="12" fill="{fill}" stroke="{ink(0.6)}" stroke-width="0.8"/>', text(kx + 24, 500, name, 15, MUTED)]
        kx += 48 + round(7.2 * len(name))
    if focal:
        keys += [f'<rect x="{kx}" y="488" width="16" height="12" fill="{ACCENT}" stroke="{ACCENT}" stroke-width="1.2"/>', text(kx + 24, 500, focal[2], 15, MUTED)]
        kx += 48 + round(7.2 * len(focal[2]))
    if frames:
        keys += [f'<rect x="{kx}" y="488" width="16" height="12" fill="none" stroke="{INK}" stroke-width="1" stroke-dasharray="5,4"/>', text(kx + 24, 500, frames[0], 15, MUTED)]
    strip(out, 80, keys, sources)
    return page(slug, title, desc, HEIGHT, "\n".join(out))
