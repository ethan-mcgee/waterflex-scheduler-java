"""Inline-SVG building blocks for the report's figures, following the diagram-design skill's grammar under the WaterFlex report skin.

Tokens come from the saved `waterflex` profile of the skill (paper white, ink #1F2A33, muted #444444, soft #5A6B7A, accent #287A4C, link #0B3A66; Calibri for names and Consolas for technical text). Every figure is a 980-wide canvas (divisible by 4, so the skill's grid holds) on the skill's presentation type ramp (names 16, technical labels 12, eyebrows 8). The figures print at 6.85 inches, so a 980 canvas puts a 12px label at about 6 pt on paper, 1.31 times what the earlier 1280 canvas gave.
"""
from __future__ import annotations

import html
import math
from dataclasses import dataclass, field

PAPER, PAPER2, INK, MUTED, SOFT, RULE_SOLID = "#FFFFFF", "#F4F6F8", "#1F2A33", "#444444", "#5A6B7A", "#C9C9C9"
ACCENT, LINK = "#287A4C", "#0B3A66"
SERVICE, STORE, HOST = "#7DAFCB", "#C9C9C9", "#DCE6EF"
SERIES = ["#7c8f6f", "#5e7a9b", "#b8915a", "#9c6b50", "#6e6479"]
SANS = "'Calibri', 'Carlito', system-ui, sans-serif"
MONO = "'Consolas', 'Liberation Mono', ui-monospace, monospace"
NAME, TECH, EYEBROW, CALLOUT, KEY = 16, 12, 8, 14, 11


def ink(alpha: float) -> str:
    return f"rgba(31,42,51,{alpha})"


def rgba(hex_color: str, alpha: float) -> str:
    r, g, b = (int(hex_color[i:i + 2], 16) for i in (1, 3, 5))
    return f"rgba({r},{g},{b},{alpha})"


def esc(s: str) -> str:
    return html.escape(s, quote=True)


def mono_width(s: str, size: float = TECH) -> float:
    return len(s) * 0.62 * size


def sans_width(s: str, size: float = NAME) -> float:
    return len(s) * 0.60 * size


def nice_ceiling(value: float) -> float:
    """The smallest 1, 2, 2.5 or 5 times a power of ten that is at least value (never the observed maximum itself)."""
    if value <= 0:
        return 1.0
    exp = math.floor(math.log10(value))
    for m in (1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10):
        c = m * 10 ** exp
        if c >= value * 1.0001:
            return c
    return 10 ** (exp + 1)


def tick_steps(ceiling: float) -> int:
    """How many equal intervals split 0..ceiling into round values (a step of 1, 2, 2.5 or 5 times a power of ten)."""
    for n in (5, 4, 6, 3):
        step = ceiling / n
        exp = math.floor(math.log10(step))
        if any(abs(step - m * 10 ** exp) < 1e-9 * step for m in (1, 2, 2.5, 5)):
            return n
    return 4


def fmt(v: float) -> str:
    if abs(v) >= 100 or float(v).is_integer():
        return f"{v:,.0f}"
    if abs(v) >= 10:
        return f"{v:.1f}"
    return f"{v:.2f}".rstrip("0").rstrip(".")


@dataclass
class Figure:
    slug: str
    title: str
    desc: str
    width: int = 980
    height: int = 600
    eyebrow: str = "WaterFlex scheduler explained"
    parts: list[str] = field(default_factory=list)
    defs: list[str] = field(default_factory=list)

    # ---- primitives -------------------------------------------------------------------------
    def add(self, s: str) -> None:
        self.parts.append(s)

    def text(self, x: float, y: float, s: str, size: float = TECH, fill: str = INK, family: str = MONO, anchor: str = "start",
             weight: str | None = None, italic: bool = False, spacing: str | None = None, extra: str = "") -> None:
        attrs = f'x="{x:g}" y="{y:g}" fill="{fill}" font-size="{size:g}" font-family="{family}" text-anchor="{anchor}"'
        if weight:
            attrs += f' font-weight="{weight}"'
        if italic:
            attrs += ' font-style="italic"'
        if spacing:
            attrs += f' letter-spacing="{spacing}"'
        self.add(f"<text {attrs}{(' ' + extra) if extra else ''}>{esc(s)}</text>")

    def name(self, x: float, y: float, s: str, fill: str = INK, anchor: str = "middle", size: float = NAME) -> None:
        self.text(x, y, s, size, fill, SANS, anchor, weight="700")

    def tech(self, x: float, y: float, s: str, fill: str = INK, anchor: str = "middle", size: float = TECH) -> None:
        self.text(x, y, s, size, fill, MONO, anchor)

    def eyebrow_text(self, x: float, y: float, s: str, fill: str = SOFT, anchor: str = "start") -> None:
        self.text(x, y, s, EYEBROW, fill, MONO, anchor, weight="500", spacing="0.14em")

    def callout(self, x: float, y: float, lines: list[str], fill: str = INK, anchor: str = "start") -> None:
        for i, line in enumerate(lines):
            self.text(x, y + i * 18, line, CALLOUT, fill, SANS, anchor, italic=True)

    def rect(self, x: float, y: float, w: float, h: float, fill: str = PAPER, stroke: str | None = None, sw: float = 1, rx: float = 0,
             dash: str | None = None, extra: str = "") -> None:
        s = f'<rect x="{x:g}" y="{y:g}" width="{w:g}" height="{h:g}"'
        if rx:
            s += f' rx="{rx:g}"'
        s += f' fill="{fill}"'
        if stroke:
            s += f' stroke="{stroke}" stroke-width="{sw:g}"'
        if dash:
            s += f' stroke-dasharray="{dash}"'
        self.add(s + (f" {extra}" if extra else "") + "/>")

    def line(self, x1: float, y1: float, x2: float, y2: float, stroke: str = MUTED, sw: float = 1.2, dash: str | None = None, marker: str | None = None) -> None:
        s = f'<line x1="{x1:g}" y1="{y1:g}" x2="{x2:g}" y2="{y2:g}" stroke="{stroke}" stroke-width="{sw:g}"'
        if dash:
            s += f' stroke-dasharray="{dash}"'
        if marker:
            s += f' marker-end="url(#{marker})"'
        self.add(s + "/>")

    def path(self, d: str, stroke: str = MUTED, sw: float = 1.2, dash: str | None = None, marker: str | None = None, fill: str = "none") -> None:
        s = f'<path d="{d}" fill="{fill}" stroke="{stroke}" stroke-width="{sw:g}"'
        if dash:
            s += f' stroke-dasharray="{dash}"'
        if marker:
            s += f' marker-end="url(#{marker})"'
        self.add(s + "/>")

    def circle(self, cx: float, cy: float, r: float, fill: str, stroke: str | None = None, sw: float = 1) -> None:
        s = f'<circle cx="{cx:g}" cy="{cy:g}" r="{r:g}" fill="{fill}"'
        if stroke:
            s += f' stroke="{stroke}" stroke-width="{sw:g}"'
        self.add(s + "/>")

    def arrow(self, x1: float, y1: float, x2: float, y2: float, kind: str = "muted", dash: str | None = None) -> None:
        """A straight connector; the two ends must share x or y (the skill's rule 1)."""
        assert x1 == x2 or y1 == y2, (x1, y1, x2, y2)
        stroke, marker, sw = {"muted": (MUTED, "arrow", 1.2), "accent": (ACCENT, "arrow-accent", 1.4), "link": (LINK, "arrow-link", 1.4), "open": (MUTED, "arrow-open", 1.2)}[kind]
        self.line(x1, y1, x2, y2, stroke, sw if not dash else 1, dash, marker)

    def label(self, cx: float, y_baseline: float, s: str, fill: str = SOFT, size: float = TECH, anchor: str = "middle") -> None:
        """A label on an opaque paper mask (arrow labels, in-chart annotations). y_baseline is the text baseline; the mask is 16 tall."""
        w = mono_width(s, size) + 8
        x = cx - w / 2 if anchor == "middle" else (cx if anchor == "start" else cx - w)
        self.rect(x, y_baseline - 12, w, 16, PAPER, rx=2)
        self.text(cx, y_baseline, s, size, fill, MONO, anchor, spacing="0.06em")

    def tag(self, x: float, y: float, word: str, stroke: str = ink(0.40), fill: str = INK) -> float:
        w = int(round(len(word) * 0.62 * KEY + 12)); w += (4 - w % 4) % 4
        self.rect(x, y, w, 16, "transparent", stroke, 0.8, rx=2)
        self.text(x + w / 2, y + 12, word, KEY, fill, MONO, "middle", spacing="0.08em")
        return w

    def node(self, x: float, y: float, w: float, h: float, name: str, sub: list[str], fill: str = SERVICE, stroke: str = SOFT, sw: float = 1,
             dash: str | None = None, tag: str | None = None, sub_fill: str = INK) -> None:
        self.rect(x, y, w, h, PAPER, rx=6)
        self.rect(x, y, w, h, fill, stroke, sw, rx=6, dash=dash)
        if tag:
            self.tag(x + 8, y + 6, tag)
        cy = y + h / 2
        base = cy - 4 + (0 if sub else 6) - (len(sub) - 1) * 8 if sub else cy + 6
        self.name(x + w / 2, base, name)
        for i, s in enumerate(sub):
            self.tech(x + w / 2, base + 20 + i * 16, s, sub_fill)

    def zone(self, x: float, y: float, w: float, h: float, label: str) -> None:
        self.rect(x, y, w, h, ink(0.02), ink(0.35), 1, rx=8, dash="6,4")
        lw = mono_width(label, EYEBROW) * 1.14 + 16
        self.rect(x + 12, y + 4, lw, 14, PAPER, rx=2)
        self.eyebrow_text(x + 20, y + 15, label)

    def panel_title(self, x: float, y: float, s: str) -> None:
        self.name(x, y, s, INK, "start")

    # ---- connectors, decisions, cards and states ------------------------------------------------
    def route(self, points: list[tuple[float, float]], kind: str = "muted", dash: str | None = None, r: float = 8, end_marker: bool = True) -> None:
        """An orthogonal connector through the given points, every bend a quarter arc of radius r (the skill's rule 1)."""
        for (ax, ay), (bx, by) in zip(points, points[1:]):
            assert ax == bx or ay == by, (ax, ay, bx, by)
        d = f"M{points[0][0]:g},{points[0][1]:g}"
        for i in range(1, len(points) - 1):
            (px, py), (cx, cy), (nx, ny) = points[i - 1], points[i], points[i + 1]
            ux, uy = (cx > px) - (cx < px), (cy > py) - (cy < py)
            vx, vy = (nx > cx) - (nx < cx), (ny > cy) - (ny < cy)
            rr = min(r, abs(cx - px + cy - py) / 2, abs(nx - cx + ny - cy) / 2)
            d += f" L{cx - ux * rr:g},{cy - uy * rr:g} Q{cx:g},{cy:g} {cx + vx * rr:g},{cy + vy * rr:g}"
        d += f" L{points[-1][0]:g},{points[-1][1]:g}"
        stroke, marker, sw = {"muted": (MUTED, "arrow", 1.2), "accent": (ACCENT, "arrow-accent", 1.4), "link": (LINK, "arrow-link", 1.4), "open": (MUTED, "arrow-open", 1.2)}[kind]
        self.path(d, stroke, 1 if dash else sw, dash, marker if end_marker else None)

    def diamond(self, cx: float, cy: float, w: float, h: float, lines: list[str], focal: bool = False) -> None:
        pts = f"{cx:g},{cy - h / 2:g} {cx + w / 2:g},{cy:g} {cx:g},{cy + h / 2:g} {cx - w / 2:g},{cy:g}"
        self.add(f'<polygon points="{pts}" fill="{PAPER}"/>')
        self.add(f'<polygon points="{pts}" fill="{rgba(ACCENT, 0.08) if focal else PAPER2}" stroke="{ACCENT if focal else SOFT}" stroke-width="1"/>')
        for i, line in enumerate(lines):
            self.text(cx, cy + 4 + (i - (len(lines) - 1) / 2) * 16, line, KEY, INK, MONO, "middle")

    def card(self, x: float, y: float, w: float, h: float, eyebrow: str, lines: list[tuple[str, str]], focal: bool = False) -> None:
        """A summary card: an eyebrow tag and lines of (style, text), style one of name, big, tech, muted."""
        self.rect(x, y, w, h, PAPER, rx=6)
        self.rect(x, y, w, h, rgba(ACCENT, 0.06) if focal else PAPER, ACCENT if focal else ink(0.18), 1.2 if focal else 1, rx=6)
        self.eyebrow_text(x + 16, y + 22, eyebrow.upper(), ACCENT if focal else SOFT)
        yy = y + 50
        for style, text in lines:
            if style == "name":
                self.text(x + 16, yy, text, NAME, INK, SANS, weight="700"); yy += 24
            elif style == "big":
                self.text(x + 16, yy + 12, text, 28, ACCENT if focal else INK, SANS, weight="700"); yy += 40
            elif style == "tech":
                self.text(x + 16, yy, text, TECH, INK, MONO); yy += 18
            else:
                self.text(x + 16, yy, text, TECH, MUTED, MONO); yy += 18

    def state(self, x: float, y: float, w: float, h: float, name: str, sub: str | None = None, focal: bool = False, terminal: bool = False) -> None:
        self.rect(x, y, w, h, PAPER, rx=10)
        self.rect(x, y, w, h, rgba(ACCENT, 0.10) if focal else (PAPER2 if terminal else SERVICE), ACCENT if focal else SOFT, 2 if terminal else 1, rx=10)
        if sub:
            self.name(x + w / 2, y + h / 2 - 2, name); self.tech(x + w / 2, y + h / 2 + 16, sub, INK)
        else:
            self.name(x + w / 2, y + h / 2 + 6, name)

    def para(self, x: float, y: float, lines: list[str], size: float = TECH, fill: str = INK, family: str = MONO, anchor: str = "start", lead: float = 18) -> None:
        for i, line in enumerate(lines):
            self.text(x, y + i * lead, line, size, fill, family, anchor)

    # ---- legend strip ---------------------------------------------------------------------
    def legend(self, y: float, items: list[tuple[str, str, str]], x0: float = 40, note: str | None = None, continued: bool = False) -> int:
        """items: (kind, color, text). kind in swatch, swatch-dashed, hollow-dot, solid-dot, line, line-dashed, arrow, arrow-link, arrow-dashed, hatch.
        Items wrap onto further rows 22 apart when the canvas is too narrow, and the note goes right-aligned on the last row or a row of its own.
        continued: a second row of the same legend, with no rule and no LEGEND label. Returns the number of rows drawn."""
        if not continued:
            self.line(x0, y - 20, self.width - x0, y - 20, ink(0.10), 0.8)
            self.eyebrow_text(x0, y + 6, "LEGEND", MUTED)
        right, start, row = self.width - x0, x0 + 70, 0
        x = start
        for kind, color, text in items:
            need = (26 if kind.startswith(("swatch", "hatch")) or kind.endswith("dot") else 46) + mono_width(text, KEY)
            if x > start and x + need > right:
                row += 1; x = start
            yy = y + row * 22
            if kind.startswith("swatch") or kind == "hatch":
                self.rect(x, yy - 4, 18, 12, color if kind != "hatch" else "url(#hatch)", SOFT, 0.8, rx=2, dash="3,2" if kind.endswith("dashed") else None)
                tx = x + 26
            elif kind.endswith("dot"):
                if kind == "hollow-dot":
                    self.circle(x + 9, yy + 2, 6, PAPER, MUTED, 1.5)
                else:
                    self.circle(x + 9, yy + 2, 6, color, INK, 1)
                tx = x + 26
            else:
                dash = "5,4" if kind.endswith("dashed") else None
                marker = {"arrow": "arrow", "arrow-link": "arrow-link", "arrow-dashed": "arrow"}.get(kind)
                self.line(x, yy + 2, x + 36, yy + 2, color, 1.6 if kind.startswith("line") and not dash else 1.2, dash, marker)
                tx = x + 46
            self.text(tx, yy + 6, text, KEY, MUTED, MONO)
            x = tx + int(mono_width(text, KEY)) + 28
            x += (4 - x % 4) % 4
        if note:
            if x + mono_width(note, KEY) > right:
                row += 1
            self.text(right, y + row * 22 + 6, note, KEY, MUTED, MONO, "end")
        return row + 1

    # ---- output -----------------------------------------------------------------------------
    def html(self) -> str:
        defs = "\n".join([
            f'<marker id="arrow" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto"><polygon points="0 0, 8 3, 0 6" fill="{MUTED}"/></marker>',
            f'<marker id="arrow-accent" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto"><polygon points="0 0, 8 3, 0 6" fill="{ACCENT}"/></marker>',
            f'<marker id="arrow-link" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto"><polygon points="0 0, 8 3, 0 6" fill="{LINK}"/></marker>',
            f'<marker id="arrow-open" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto"><polyline points="0 0, 8 3, 0 6" fill="none" stroke="{MUTED}" stroke-width="1.2"/></marker>',
            f'<pattern id="hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="6" height="6" fill="{PAPER}"/><line x1="0" y1="0" x2="0" y2="6" stroke="{MUTED}" stroke-width="1.2"/></pattern>',
        ] + self.defs)
        body = "\n        ".join(self.parts)
        return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>{esc(self.title)}</title>
  <style>
    *, *::before, *::after {{ box-sizing: border-box; margin: 0; padding: 0; }}
    :root {{ --color-paper: {PAPER}; --color-ink: {INK}; --color-muted: {MUTED}; --color-accent: {ACCENT};
      --font-sans: {SANS}; --font-serif: 'Calibri', 'Carlito', serif; --font-mono: {MONO}; }}
    body {{ font-family: var(--font-sans); background: var(--color-paper); color: var(--color-ink); min-height: 100vh;
      display: flex; align-items: center; justify-content: center; padding: 3rem 2rem; }}
    .frame {{ max-width: 1020px; width: 100%; }}
    .diagram-container {{ width: 100%; overflow-x: auto; }}
    .eyebrow {{ font-family: var(--font-mono); font-size: 0.66rem; font-weight: 500; letter-spacing: 0.18em; text-transform: uppercase; color: var(--color-muted); margin-bottom: 0.5rem; }}
    h1 {{ font-family: var(--font-serif); font-size: clamp(1.5rem, 2.4vw + 0.75rem, 2rem); font-weight: 400; letter-spacing: -0.02em; line-height: 1.15; color: var(--color-ink); margin-bottom: 1.5rem; }}
    svg {{ width: 100%; min-width: {self.width}px; display: block; }}
    @media print {{ .diagram-container {{ overflow-x: visible; }} svg {{ min-width: 0; }} }}
  </style>
</head>
<body>
  <div class="frame">
    <p class="eyebrow">{esc(self.eyebrow)}</p>
    <h1>{esc(self.title)}</h1>
    <div class="diagram-container">
      <svg viewBox="0 0 {self.width} {self.height}" xmlns="http://www.w3.org/2000/svg" role="img" aria-labelledby="{self.slug}-title {self.slug}-desc">
        <title id="{self.slug}-title">{esc(self.title)}</title>
        <desc id="{self.slug}-desc">{esc(self.desc)}</desc>
        <defs>
          {defs}
        </defs>
        <rect width="100%" height="100%" fill="{PAPER}"/>
        {body}
      </svg>
    </div>
  </div>
</body>
</html>
"""


# ---- chart scaffolding -------------------------------------------------------------------------
@dataclass
class Plot:
    """A plot area inside a figure: x0..x1 by y0..y1 in canvas units, with a value axis mapping."""
    fig: Figure
    x0: float
    y0: float
    x1: float
    y1: float

    def frame(self, title: str | None = None) -> None:
        if title:
            self.fig.panel_title(self.x0 - 56, self.y0 - 28, title)

    def ygrid(self, ceiling: float, steps: int | None, unit: str, tick_fmt=fmt, log: bool = False, ticks: list[float] | None = None) -> None:
        steps = steps or tick_steps(ceiling)
        values = ticks if ticks is not None else [ceiling * i / steps for i in range(steps + 1)]
        for v in values:
            y = self.ymap(v, ceiling, log)
            if v == values[0]:
                self.fig.line(self.x0, y, self.x1, y, ink(0.25), 1)
            else:
                self.fig.line(self.x0, y, self.x1, y, ink(0.08), 0.8)
            self.fig.tech(self.x0 - 8, y + 4, tick_fmt(v), MUTED, "end")
        self.fig.line(self.x0, self.y0, self.x0, self.y1, ink(0.25), 1)
        self.fig.text(self.x0 - 56, (self.y0 + self.y1) / 2, unit, TECH, MUTED, MONO, "middle", spacing="0.10em",
                      extra=f'transform="rotate(-90 {self.x0 - 56:g} {(self.y0 + self.y1) / 2:g})"')

    def ymap(self, v: float, ceiling: float, log: bool = False, floor: float = 0) -> float:
        if log:
            lo, hi = math.log10(floor), math.log10(ceiling)
            return self.y1 - (math.log10(v) - lo) / (hi - lo) * (self.y1 - self.y0)
        return self.y1 - (v - floor) / (ceiling - floor) * (self.y1 - self.y0)

    def xgrid(self, ceiling: float, steps: int | None, unit: str, tick_fmt=fmt) -> None:
        steps = steps or tick_steps(ceiling)
        for i in range(steps + 1):
            v = ceiling * i / steps
            x = self.xmap(v, ceiling)
            self.fig.line(x, self.y0, x, self.y1, ink(0.25) if i == 0 else ink(0.08), 1 if i == 0 else 0.8)
            self.fig.tech(x, self.y1 + 20, tick_fmt(v), MUTED)
        self.fig.text((self.x0 + self.x1) / 2, self.y1 + 44, unit, TECH, MUTED, MONO, "middle", spacing="0.10em")

    def xmap(self, v: float, ceiling: float, floor: float = 0) -> float:
        return round(self.x0 + (v - floor) / (ceiling - floor) * (self.x1 - self.x0))

    def columns(self, n: int) -> list[float]:
        pitch = (self.x1 - self.x0) / n
        return [self.x0 + pitch * (i + 0.5) for i in range(n)]

    def bar(self, cx: float, width: float, top_y: float, focal: bool = False, fill: str | None = None, stroke: str | None = None, bottom_y: float | None = None) -> None:
        bottom = self.y1 if bottom_y is None else bottom_y
        f = fill or (rgba(ACCENT, 0.12) if focal else rgba(MUTED, 0.15)); s = stroke or (ACCENT if focal else MUTED)
        self.fig.rect(cx - width / 2, top_y, width, bottom - top_y, PAPER)
        self.fig.rect(cx - width / 2, top_y, width, bottom - top_y, f, s, 1)

    def dumbbell(self, y: float, x_ref: float, x_focal: float, ref_text: str, focal_text: str, row_label: str, label_x: float) -> None:
        self.fig.line(x_ref, y, x_focal, y, ink(0.55), 1)
        self.fig.circle(x_ref, y, 6, PAPER, MUTED, 1.5)
        self.fig.circle(x_focal, y, 6, ACCENT, INK, 1)
        left, right = min(x_ref, x_focal), max(x_ref, x_focal)
        left_text, right_text = (ref_text, focal_text) if x_ref <= x_focal else (focal_text, ref_text)
        if left - 24 - mono_width(left_text) < self.x0:
            # Too close to the axis for a label on the left: print both values, in pair order, to the right.
            self.fig.tech(right + 12, y + 4, f"{left_text} · {right_text}", MUTED, "start")
        else:
            self.fig.tech(left - 12, y + 4, left_text, MUTED, "end")
            self.fig.tech(right + 12, y + 4, right_text, MUTED, "start")
        self.fig.text(label_x, y + 5, row_label, KEY, INK, SANS, "end", weight="700")

    def polyline(self, points: list[tuple[float, float]], color: str, focal: bool = False, dash: str | None = None) -> None:
        pts = " ".join(f"{x:g},{y:g}" for x, y in points)
        s = f'<polyline points="{pts}" fill="none" stroke="{color}" stroke-width="{1.8 if focal else 1.2}" stroke-linejoin="round"'
        if dash:
            s += f' stroke-dasharray="{dash}"'
        self.fig.add(s + "/>")
        if focal:
            for x, y in points:
                self.fig.circle(x, y, 4, color)
