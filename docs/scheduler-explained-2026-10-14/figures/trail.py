"""Figures for the trail edition of the report: the front matter and the architecture of the stateless API (Parts I and II).

Every figure follows the diagram-design grammar under the report skin (svglib): orthogonal connectors, masked labels, one green accent, a legend strip.
Facts drawn here come from the design document, the integration guide and the OpenAPI contract as report_data reads them; tables that change shape stop the build.
"""
from __future__ import annotations

import textwrap
from datetime import date

from .svglib import (ACCENT, HOST, INK, KEY, LINK, MONO, MUTED, NAME, PAPER, PAPER2, SANS, SERIES, SERVICE, SOFT, STORE, TECH,
                     Figure, ink, mono_width, rgba)

LANES = ("Architecture", "Daily search", "Booking", "Routing", "Load and engine")


def _wrap(s: str, width: int) -> list[str]:
    return textwrap.wrap(s, width, break_long_words=False) or [""]


# ---- Front matter ---------------------------------------------------------------------------------
def timeline(events) -> Figure:
    f = Figure("timeline", "The trail from the demonstration to today", "Timeline of the work from the September 19 demonstration to October 14 in five threads: architecture, daily search, booking, routing, and load and engine work, with the pull request of each change.", 1280, 800)
    start, end = date(2026, 9, 18), date(2026, 10, 15)
    x0, x1, top, lane_h = 200, 1240, 64, 120
    span = (end - start).days

    def xof(d: str) -> float:
        return round(x0 + (date.fromisoformat(d) - start).days / span * (x1 - x0))

    bottom = top + lane_h * len(LANES) + 40
    # Week ticks and the axis.
    for d in ("2026-09-19", "2026-09-26", "2026-10-03", "2026-10-10", "2026-10-14"):
        x = xof(d)
        f.line(x, top - 8, x, bottom, ink(0.08), 0.8)
        f.tech(x, bottom + 22, date.fromisoformat(d).strftime("%b %d").upper(), MUTED)
    f.line(x0, bottom, x1, bottom, ink(0.25), 1)
    # The demonstration and today.
    xt = xof("2026-10-14")
    f.line(xt, top - 16, xt, bottom, ACCENT, 1.4, "6,4")
    f.label(xt - 4, top - 24, "THE CURRENT STATE", ACCENT, KEY, "end")
    for i, lane in enumerate(LANES):
        y = top + i * lane_h + lane_h / 2
        f.text(x0 - 20, y + 5, lane, KEY, INK, SANS, "end", weight="700")
        f.line(x0, y, x1, y, ink(0.20), 1, "2,4")
        groups: dict[str, list] = {}
        for d, pr, l, label in events:
            if l == lane:
                groups.setdefault(d, []).append((pr, label))
        for k, (d, items) in enumerate(sorted(groups.items())):
            x = xof(d)
            first = (d, lane) == ("2026-09-19", "Architecture")
            f.circle(x, y, 6, PAPER, MUTED, 1.5) if first else f.circle(x, y, 6, SERVICE, INK, 1)
            lines = []
            for pr, label in items:
                lines += _wrap(label, 12)
                if pr:
                    lines.append(f"#{pr}")
            above = k % 2 == 0
            lead = 14
            for j, line in enumerate(lines):
                yy = (y - 14 - (len(lines) - 1 - j) * lead) if above else (y + 24 + j * lead)
                f.label(x, yy, line, MUTED if line.startswith("#") else INK, KEY)
    f.legend(764, [("hollow-dot", PAPER, "THE DEMONSTRATION"), ("solid-dot", SERVICE, "A MERGED CHANGE OR A STUDY"), ("line-dashed", ACCENT, "TODAY, OCT 14")],
             note="dates of merged pull requests are checked against git")
    return f


def scoreboard(cards: list[dict]) -> Figure:
    f = Figure("scoreboard", "Where each thread started and where it stands", "Five cards, one per thread of the work, each with the first measured value and the value today: daily search, booking, routing, admission and the engine.", 1280, 292)
    w, gap, x = 232, 10, 40
    for i, c in enumerate(cards):
        f.card(x + i * (w + gap), 36, w, 220, c["thread"], [("name", c["metric"]), ("muted", "FIRST MEASURED"), ("tech", c["before"]), ("muted", "TODAY"), ("big", c["after"]),
                                                             *[("muted", line) for line in c["notes"]]], focal=i == 0)
    return f


# ---- Part I: where it started ----------------------------------------------------------------------
def delta(rows) -> Figure:
    f = Figure("delta", "From a portal module to a stateless service", "Architecture delta: in September the scheduler shared the portal's database and ran its own jobs; today WaterFlex Software calls a stateless API that keeps only a thin store of its own. A ledger lists the seven changes.", 1280, 820)
    # Before.
    f.zone(40, 40, 580, 380, "SEPTEMBER: A MODULE OF THE PORTAL")
    f.node(72, 88, 220, 72, "Portal", ["NEXT.JS · ITS OWN ENDPOINTS"], HOST, tag="1")
    f.node(368, 88, 220, 72, "Scheduler", ["JAVA · 2 A.M. BATCH, WORKERS"], SERVICE, tag="4")
    f.node(72, 280, 516, 96, "Shared Postgres", ["ABOUT 40 PORTAL TABLES · OMAHA_SETTING", "APPOINTMENTS WRITTEN UNDER ROW LOCKS"], STORE, tag="2")
    f.arrow(292, 112, 368, 112, "muted"); f.label(330, 104 - 8, "CALLS", SOFT, KEY)
    f.arrow(160, 160, 160, 280, "muted"); f.label(216, 224, "READS, WRITES", SOFT, KEY)
    f.arrow(478, 160, 478, 280, "muted"); f.label(534, 224, "READS, WRITES", SOFT, KEY)
    # After.
    f.zone(660, 40, 580, 380, "TODAY: A SERVICE OF ITS OWN")
    f.node(692, 88, 220, 72, "WaterFlex Software", ["HOST · OWNS EVERY RECORD"], HOST, tag="7")
    f.node(988, 88, 220, 72, "Scheduler API", ["/API/V1 · 12 OPERATIONS"], SERVICE, ACCENT, 1.6, tag="3")
    f.node(988, 280, 220, 96, "Thin store", ["ITS OWN POSTGRES", "TENANTS · ROW SECURITY"], STORE, tag="6")
    f.node(692, 280, 220, 96, "Host database", ["THE BUSINESS RECORD", "LASTMODIFIED PER DAY"], HOST, tag="5")
    f.arrow(912, 112, 988, 112, "link"); f.label(950, 96, "SNAPSHOT", LINK, KEY)
    f.arrow(988, 140, 912, 140, "muted", "5,4"); f.label(950, 162, "RESULTS", SOFT, KEY)
    f.arrow(1098, 160, 1098, 280, "muted"); f.label(1150, 224, "HOLDS, RECEIPTS", SOFT, KEY)
    f.arrow(802, 160, 802, 280, "muted"); f.label(862, 224, "COMPARE-AND-SET", SOFT, KEY)
    # Ledger.
    f.eyebrow_text(40, 456, "CHANGES LEDGER", SOFT)
    f.line(40, 464, 1240, 464, ink(0.12), 0.8)
    for i, (piece, before, after) in enumerate(rows):
        y = 492 + i * 40
        f.tag(40, y - 12, str(i + 1))
        f.text(84, y + 1, piece, KEY, INK, SANS, weight="700")
        f.para(220, y, _wrap(before, 50)[:2], KEY, MUTED, MONO, lead=14)
        f.para(640, y, _wrap(after, 80)[:2], KEY, INK, MONO, lead=14)
    f.eyebrow_text(220, 482 - 4, "SEPTEMBER", SOFT); f.eyebrow_text(640, 482 - 4, "TODAY", SOFT)
    f.legend(790, [("swatch", HOST, "WATERFLEX SOFTWARE"), ("swatch", SERVICE, "SCHEDULER"), ("swatch", STORE, "DATABASE"), ("arrow-link", LINK, "HTTPS CALL")], note="numbered tags point to the ledger rows")
    return f


# ---- Part II: the stateless API ---------------------------------------------------------------------
def ownership(rows) -> Figure:
    f = Figure("ownership", "Who owns what", "Two zones: WaterFlex Software owns master data, the business record, lastModified and each client's policy; the scheduler owns holds, offers, proposals, receipts and the road graph with its cache. A snapshot goes in, results come out, and the host writes its own record.", 1280, 600)
    left = [r[0] for r in rows if r[0]]
    right = [r[1] for r in rows if r[1]]
    f.zone(40, 40, 520, 500, "WATERFLEX SOFTWARE OWNS")
    f.zone(720, 40, 520, 500, "THE SCHEDULER OWNS")
    for i, text in enumerate(left):
        y = 76 + i * 92
        lines = _wrap(text.replace("`", ""), 58)
        f.rect(64, y, 472, 76, PAPER, rx=6); f.rect(64, y, 472, 76, HOST, SOFT, 1, rx=6)
        f.para(80, y + 26 + (2 - len(lines)) * 8, lines[:3], TECH, INK, MONO)
    for i, text in enumerate(right):
        y = 76 + i * 92
        lines = _wrap(text.replace("`", ""), 58)
        focal = i == 0
        f.rect(744, y, 472, 76, PAPER, rx=6); f.rect(744, y, 472, 76, rgba(ACCENT, 0.08) if focal else (PAPER2 if text.startswith("Nothing") else SERVICE), ACCENT if focal else SOFT, 1.4 if focal else 1, rx=6)
        f.para(760, y + 26 + (2 - len(lines)) * 8, lines[:3], TECH, INK, MONO)
    f.arrow(560, 140, 720, 140, "link"); f.label(640, 128, "SNAPSHOT", LINK, KEY)
    f.text(640, 160, "facts + lastModified", KEY, MUTED, MONO, "middle")
    f.arrow(720, 260, 560, 260, "muted", "5,4"); f.label(640, 248, "RESULTS", SOFT, KEY)
    f.text(640, 280, "offers, routes, receipts", KEY, MUTED, MONO, "middle")
    f.callout(568, 380, ["The host writes its own", "record with a compare-", "and-set; the scheduler", "never touches it."])
    f.legend(566, [("swatch", HOST, "HOST DATA"), ("swatch", rgba(ACCENT, 0.08), "WHAT KEEPS PROMISES SAFE"), ("swatch", SERVICE, "SCHEDULER DATA")], note="source: the integration guide's ownership table")
    return f


SURFACE = (
    ("BOOKING", (("createBookingOffers", "POST /booking/offers", "search, hold windows"), ("selectBookingOffer", "POST /offers/{id}/select", "customer picks one"),
                 ("confirmBookingHold", "POST /holds/{id}/confirm", "fresh snapshot, receipt")), ("releaseBookingOffer", "POST /offers/{id}/release", "give the set back")),
    ("DAILY", (("createDailyProposal", "POST /daily/proposals", "optimize one metro day"), ("commitDailyProposal", "POST /proposals/{id}/commit", "current lastModified, receipt")), None),
    ("REPAIR", (("createRepairProposal", "POST /repairs/proposals", "one disrupted day"),), None),
    ("ROUTES", (("evaluateRoutes", "POST /routes/evaluate", "time a day as it is"), ("drawRoutes", "POST /routes/geometry", "road lines to draw")), None),
    ("REQUESTS", (("getRequest", "GET /requests/{id}", "status of a queued call"), ("cancelRequest", "DELETE /requests/{id}", "best-effort cancel")), None),
    ("IDENTITY", (("whoami", "GET /whoami", "the token's tenant"),), None),
)


def surface(operations) -> Figure:
    ids = {o["id"] for o in operations}
    drawn = {op[0] for _, chain, extra in SURFACE for op in chain} | {extra[0] for _, _, extra in SURFACE if extra}
    assert ids == drawn, (ids ^ drawn)
    f = Figure("surface", "The API surface: twelve operations in six families", "Map of the twelve /api/v1 operations grouped into booking, daily, repair, routes, requests and identity, with the order in which a host calls the booking and daily ones.", 1280, 780)
    w, h, gap, x0 = 300, 72, 76, 200
    row_y = [56, 248, 348, 448, 548, 648]
    for (family, chain, extra), y in zip(SURFACE, row_y):
        f.text(x0 - 24, y + h / 2 + 5, family, KEY, INK, SANS, "end", weight="700")
        for j, (op_id, path, what) in enumerate(chain):
            x = x0 + j * (w + gap)
            focal = op_id == "confirmBookingHold"
            f.rect(x, y, w, h, PAPER, rx=6); f.rect(x, y, w, h, rgba(ACCENT, 0.08) if focal else SERVICE, ACCENT if focal else SOFT, 1.4 if focal else 1, rx=6)
            f.name(x + 16, y + 26, op_id, INK, "start")
            f.tech(x + 16, y + 46, path.upper() if False else path, INK, "start")
            f.tech(x + 16, y + 62, what, MUTED, "start", KEY)
            if j:
                f.arrow(x - gap, y + h / 2, x, y + h / 2, "muted")
        if extra:
            op_id, path, what = extra
            x, yy = x0 + w + gap, y + h + 24
            f.rect(x, yy, w, h, PAPER, rx=6); f.rect(x, yy, w, h, PAPER2, SOFT, 1, rx=6, dash="4,3")
            f.name(x + 16, yy + 26, op_id, INK, "start"); f.tech(x + 16, yy + 46, path, INK, "start"); f.tech(x + 16, yy + 62, what, MUTED, "start", KEY)
            f.route([(x0 + w / 2, y + h), (x0 + w / 2, yy + h / 2), (x, yy + h / 2)], "muted", "5,4")
    f.legend(746, [("swatch", SERVICE, "OPERATION"), ("swatch", rgba(ACCENT, 0.08), "THE CALL THAT COMMITS A BOOKING"), ("arrow", MUTED, "CALLED NEXT"), ("swatch-dashed", PAPER2, "ALTERNATIVE PATH")],
             note="every operation is under /api/v1; the contract is OpenAPI 3.1")
    return f


def token(_=None) -> Figure:
    f = Figure("token", "The lastModified round trip", "Sequence showing the host's lastModified timestamp travelling with the snapshot, recorded with a proposal, compared for equality at commit, and used again in the host's compare-and-set write; a changed value is answered 409 STALE.", 1280, 700)
    hx, sx = 300, 980
    f.node(hx - 130, 40, 260, 56, "WaterFlex Software", ["READS ITS OWN RECORDS"], HOST)
    f.node(sx - 130, 40, 260, 56, "Scheduler", ["THIN STORE"], SERVICE)
    for x in (hx, sx):
        f.line(x, 96, x, 620, ink(0.20), 1, "3,3")
    f.path(f"M{hx + 4},128 H{hx + 36} Q{hx + 44},128 {hx + 44},136 V144 Q{hx + 44},152 {hx + 36},152 H{hx + 12}", MUTED, 1.2, marker="arrow")
    f.label(hx + 56, 145, "READ EACH TECHNICIAN-DAY WITH ITS LASTMODIFIED, E.G. 09:14:02.118", INK, KEY, "start")
    f.arrow(hx + 4, 200, sx - 4, 200, "link"); f.label((hx + sx) / 2, 192, "SNAPSHOT · EVERY DAY CARRIES ITS LASTMODIFIED", LINK, KEY)
    f.rect(sx - 4, 200, 8, 52, ink(0.06), MUTED, 0.8)
    f.label(sx - 16, 232, "RECORD THE VALUES WITH THE PROPOSAL", INK, KEY, "end")
    f.arrow(sx - 4, 252, hx + 4, 252, "muted", "5,4"); f.label((hx + sx) / 2, 272 + 8, "201 PROPOSAL OR OFFERS", SOFT, KEY)
    f.arrow(hx + 4, 328, sx - 4, 328, "link"); f.label((hx + sx) / 2, 320, "COMMIT OR CONFIRM · THE CURRENT VALUES", LINK, KEY)
    fx, fy, fw, fh = 220, 352, 840, 236
    f.rect(fx, fy, fw, fh, ink(0.02), ink(0.22), 1, rx=4)
    f.rect(fx, fy, 40, 16, PAPER, ink(0.22), 1, rx=2); f.text(fx + 20, fy + 12, "ALT", 8, MUTED, MONO, "middle", spacing="0.12em")
    f.text(fx + 12, fy + 36, "[EVERY VALUE EQUAL TO THE ONE RECORDED]", TECH, MUTED, MONO)
    f.arrow(sx - 4, 412, hx + 4, 412, "accent"); f.label((hx + sx) / 2, 404, "200 RECEIPT · ASSIGNMENTS AND TIMESTAMPS", ACCENT, KEY)
    f.path(f"M{hx + 4},436 H{hx + 36} Q{hx + 44},436 {hx + 44},444 V452 Q{hx + 44},460 {hx + 36},460 H{hx + 12}", MUTED, 1.2, marker="arrow")
    f.label(hx + 56, 453, "WRITE THE RECORD WITH A COMPARE-AND-SET ON THE SAME VALUES; MOVE LASTMODIFIED", INK, KEY, "start")
    f.line(fx + 8, 488, fx + fw - 8, 488, ink(0.20), 1, "4,3")
    f.text(fx + 12, 512, "[ANY VALUE DIFFERENT]", TECH, MUTED, MONO)
    f.arrow(sx - 4, 556, hx + 4, 556, "muted", "5,4"); f.label((hx + sx) / 2, 548, "409 STALE · CHANGED LISTS THE TECHNICIAN-DAYS", SOFT, KEY)
    f.callout(1084, 200, ["Compared for exact", "equality, never ordered:", "clock skew between", "servers cannot cause a", "wrong decision. At worst", "an unrelated change", "forces a retry."])
    f.legend(666, [("arrow-link", LINK, "CALL"), ("arrow-dashed", MUTED, "REPLY"), ("line", ACCENT, "HEADLINE SUCCESS")], note="the same token protects proposals, offers, commits and confirms")
    return f


def reconcile(_=None) -> Figure:
    f = Figure("reconcile", "Reconciling a technician-day before a booking search", "Flowchart of the five reconciliation rules a booking search or confirm applies to each technician-day of the host's snapshot against the stored arrangement: refuse STALE, keep as stored, accept the expected change, replace the route, or lose the holds of that date.", 1280, 600)
    y, dw, dh = 150, 200, 136
    xs = [248, 498, 748, 998]
    questions = [["ALREADY SEEN", "TIMESTAMP OR", "PRE-RECEIPT ROUTE?"], ["LASTMODIFIED", "UNCHANGED?"], ["APPOINTMENTS", "EXACTLY AS", "EXPECTED?"], ["STORED ROUTE HAS", "A HOLD OR A", "PENDING MOVE?"]]
    outcomes = [("RULE 0", "409 STALE", "refused before anything is reconciled; nothing lost", True), ("RULE 1", "As stored", "nothing to do", False),
                ("RULE 2", "Accepted", "the new timestamp is recorded", False), ("RULE 3", "Replace route", "the host's route becomes the stored one", False)]
    f.node(16, y - 32, 100, 64, "Snapshot", ["ONE DAY"], HOST)
    f.arrow(116, y, xs[0] - dw / 2, y, "muted")
    for i, (x, q) in enumerate(zip(xs, questions)):
        f.diamond(x, y, dw, dh, q, focal=i == 0)
        if i < 3:
            f.arrow(x + dw / 2, y, xs[i + 1] - dw / 2, y, "muted"); f.label((x + xs[i + 1]) / 2, y - 10, "NO", SOFT, KEY)
        rule, name, sub, focal = outcomes[i]
        ox, oy = x - 110, 300
        yes_label = "YES" if i < 3 else "NO"
        f.arrow(x, y + dh / 2, x, oy, "accent" if focal else "muted"); f.label(x + 24, (y + dh / 2 + oy) / 2 + 4, yes_label, ACCENT if focal else SOFT, KEY)
        f.rect(ox, oy, 220, 92, PAPER, rx=6); f.rect(ox, oy, 220, 92, rgba(ACCENT, 0.08) if focal else SERVICE, ACCENT if focal else SOFT, 1.4 if focal else 1, rx=6)
        f.tag(ox + 8, oy + 6, rule)
        f.name(ox + 110, oy + 46, name)
        f.para(ox + 110, oy + 66, _wrap(sub, 28), KEY, INK, MONO, "middle", 14)
    # Rule 3, yes branch: the date's holds are lost.
    lx, lw = 1198, 132
    f.arrow(xs[3] + dw / 2, y, lx - lw / 2, y, "muted"); f.label((xs[3] + dw / 2 + lx - lw / 2) / 2, y - 12, "YES", SOFT, KEY)
    f.rect(lx - lw / 2, y - 60, lw, 120, PAPER, rx=6); f.rect(lx - lw / 2, y - 60, lw, 120, PAPER2, SOFT, 1, rx=6)
    f.para(lx, y - 26, ["EVERY HOLD", "ON THAT DATE", "IS LOST; THE", "DATE RESTARTS", "FROM THE HOST"], KEY, INK, MONO, "middle", 14)
    # Rule 4 strip.
    f.rect(40, 440, 1200, 76, PAPER, rx=6); f.rect(40, 440, 1200, 76, PAPER2, SOFT, 1, rx=6)
    f.tag(56, 446, "RULE 4")
    f.text(140, 470, "Then the whole arrangement is re-evaluated with the snapshot's current windows, durations, shifts and absences.", TECH, INK, MONO)
    f.text(140, 494, "If it no longer fits, every hold on that date is lost the same way. Nothing is guessed at any step.", TECH, MUTED, MONO)
    f.legend(566, [("swatch", rgba(ACCENT, 0.08), "THE RULE THE LOAD TOOL ADDED (#152)"), ("swatch", SERVICE, "KEPT"), ("swatch", PAPER2, "HOLDS LOST")], note="the scheduler remembers the last 32 timestamps it moved past")
    return f


REFUSAL_GROUPS = (
    ("Fix the request", "send it again with a new requestId; never unchanged", ("400 INVALID_REQUEST", "422 INCOMPLETE_FACTS")),
    ("Check the token", "do not retry", ("401",)),
    ("Rebuild from current records", "commit: a new proposal; booking: same call, new requestId", ("409 STALE",)),
    ("Search again", "a new requestId", ("409 HOLD_UNAVAILABLE",)),
    ("Wait, then retry the same requestId", "Retry-After or backoff", ("429 BUSY", "503 ROUTING_UNAVAILABLE", "503 CALCULATION_UNAVAILABLE")),
    ("Nothing to do", "or repeat a cancelled call if still wanted", ("404 NOT_FOUND", "409 NOT_COMMITTABLE", "409 CANCELLED")),
)


def refusals(problems) -> Figure:
    codes = {f"{s} {c.strip('`')}" if c.startswith("`") else s for s, c, _, _ in problems}
    drawn = {code for _, _, cs in REFUSAL_GROUPS for code in cs}
    assert codes == drawn, codes ^ drawn
    f = Figure("refusals", "Every refusal, by what the host does next", "Every status code the API can refuse with, grouped by the host's next action: fix the request, check the token, rebuild from current records, search again, wait and retry, or nothing.", 1280, 640)
    f.node(40, 252, 180, 72, "A refusal", ["PROBLEM+JSON BODY"], HOST)
    for i, (action, how, cs) in enumerate(REFUSAL_GROUPS):
        y = 40 + i * 92
        focal = action.startswith("Rebuild")
        f.route([(220, 288), (260, 288), (260, y + 36), (300, y + 36)], "accent" if focal else "muted")
        f.rect(300, y, 380, 72, PAPER, rx=6); f.rect(300, y, 380, 72, rgba(ACCENT, 0.08) if focal else SERVICE, ACCENT if focal else SOFT, 1.4 if focal else 1, rx=6)
        f.name(316, y + 30, action, INK, "start"); f.tech(316, y + 52, how, MUTED, "start", KEY)
        x = 712
        for c in cs:
            w = mono_width(c, KEY) + 20
            f.rect(x, y + 24, w, 24, PAPER, rx=2); f.rect(x, y + 24, w, 24, PAPER2, ink(0.40), 0.8, rx=2)
            f.text(x + w / 2, y + 40, c, KEY, INK, MONO, "middle")
            x += w + 12
        f.line(680, y + 36, 712, y + 36, ink(0.25), 1)
    f.legend(606, [("swatch", SERVICE, "THE HOST'S ACTION"), ("swatch", PAPER2, "STATUS AND CODE"), ("swatch", rgba(ACCENT, 0.08), "THE ONE THE TOKEN DESIGN RELIES ON")], note="source: the integration guide's problem table")
    return f


def _pipeline(f: Figure, stages, y0: float, per_row: int, w: float, gap: float, x0: float, refusal_y: float, row_gap: float):
    """Stages laid out in snaking rows with a refusal chip under each; returns the stage boxes."""
    boxes = []
    for i, (name, sub, refusal, focal) in enumerate(stages):
        row, col = divmod(i, per_row)
        col = col if row % 2 == 0 else per_row - 1 - col
        x, y = x0 + col * (w + gap), y0 + row * row_gap
        boxes.append((x, y))
        f.rect(x, y, w, 72, PAPER, rx=6); f.rect(x, y, w, 72, rgba(ACCENT, 0.08) if focal else SERVICE, ACCENT if focal else SOFT, 1.4 if focal else 1, rx=6)
        f.tag(x + 8, y + 6, f"{i + 1}")
        f.name(x + w / 2, y + 40, name)
        f.tech(x + w / 2, y + 60, sub, INK, size=KEY)
        if refusal:
            cw = mono_width(refusal, KEY) + 16
            f.line(x + w / 2, y + 72, x + w / 2, y + refusal_y, ink(0.35), 1, "3,3")
            f.rect(x + w / 2 - cw / 2, y + refusal_y, cw, 22, PAPER, rx=2); f.rect(x + w / 2 - cw / 2, y + refusal_y, cw, 22, PAPER2, ink(0.40), 0.8, rx=2)
            f.text(x + w / 2, y + refusal_y + 15, refusal, KEY, INK, MONO, "middle")
    for i in range(len(boxes) - 1):
        (ax, ay), (bx, by) = boxes[i], boxes[i + 1]
        if ay == by:
            if bx > ax:
                f.arrow(ax + w, ay + 36, bx, by + 36, "muted")
            else:
                f.arrow(ax, ay + 36, bx + w, by + 36, "muted")
        else:
            side = ax + w if (i // per_row) % 2 == 0 else ax
            out = side + (24 if side == ax + w else -24)
            f.route([(side, ay + 36), (out, ay + 36), (out, by + 36), (side, by + 36)], "muted")
    return boxes


def daily_pipeline(_=None) -> Figure:
    f = Figure("daily_pipeline", "A daily proposal, stage by stage", "Pipeline of a daily proposal through nine stages, from authentication to storing the answer, with the refusal each stage can return.", 1280, 470)
    stages = [("Authenticate", "TOKEN TO TENANT", "401", False), ("Parse", "STRICT READ", "400 · 422", False), ("Claim", "REQUESTID + HASH", "400 · 429", False),
              ("Cutoff", "6 A.M. LOCAL", "422", False), ("Admission", "WOULD IT START IN TIME?", "429 BUSY", True),
              ("Route", "EVERY LEG, 30 S", "503", False), ("Calculate", "COST + FAIRNESS + 5 S", "503 · 409", False), ("Decide", "AGAINST THE DAY AS SENT", None, False), ("Store", "PROPOSAL, TIMESTAMPS", None, False)]
    _pipeline(f, stages, 40, 5, 208, 28, 40, 92, 196)
    f.callout(40, 420, ["Decide answers IMPROVED, REJECTED_BY_POLICY or NO_IMPROVEMENT with a reason code; a plan with any overtime is never returned."])
    f.legend(446 - 0, [("swatch", SERVICE, "STAGE"), ("swatch", rgba(ACCENT, 0.08), "THE EARLY REFUSAL (#153)"), ("swatch", PAPER2, "WHAT IT CAN REFUSE WITH")])
    f.height = 480
    return f


def booking_pipeline(_=None) -> Figure:
    f = Figure("booking_pipeline", "A booking search, stage by stage", "Pipeline of a booking search inside the client's deadline, from claiming the requestId to publishing holds, with the loop that reruns the search when another booking changed the same day.", 1280, 480)
    stages = [("Claim", "REQUESTID + HASH", "400 · 429", False), ("Deadline", "BOOKINGDEADLINEMS ≤ 5 S", None, False), ("Cutoff, metro", "FACTS BUILT", "422", False),
              ("Admission", "A SLOT, FAIR BY CLIENT", "429 BUSY", False), ("Reconcile", "RULES 0 TO 4", "409 STALE", True), ("Route", "THE NEW JOB'S LEGS", "503", False),
              ("Insertion", "PASS 1: THE WINDOWS", None, False), ("Refinement", "PASS 2: OPTIONAL", None, False), ("Validate", "INDEPENDENTLY", None, False), ("Publish", "HOLDS FOR 10 MINUTES", "201", False)]
    boxes = _pipeline(f, stages, 40, 5, 208, 28, 40, 92, 196)
    # The rerun loop: when validation finds another booking changed the day, back to Reconcile, outside the row-change connector.
    (vx, vy), (rx, ry) = boxes[8], boxes[4]
    w, low, out = 208, boxes[8][1] + 152, boxes[4][0] + 208 + 44
    f.route([(vx + w / 2, vy + 72), (vx + w / 2, low), (out, low), (out, ry + 56), (rx + w, ry + 56)], "muted", "5,4")
    f.label((vx + w / 2 + out) / 2, low - 10, "THE DAY CHANGED MEANWHILE: RUN AGAIN, AT MOST 3 TIMES, WHILE THE DEADLINE LEAVES TIME", SOFT, KEY)
    f.legend(446, [("swatch", SERVICE, "STAGE"), ("swatch", rgba(ACCENT, 0.08), "WHERE THE CONCURRENCY TOKEN BITES"), ("swatch", PAPER2, "ANSWER"), ("arrow-dashed", MUTED, "RERUN")])
    return f


def isolation(_=None) -> Figure:
    f = Figure("isolation", "Many clients in one database: the guards, layer by layer", "Layer stack of the guards that keep tenants apart: the token decides the tenant, every key starts with the tenant, row-level security fails closed, the runtime login has least privilege, and admission takes turns by client.", 1280, 580)
    layers = [("TOKEN", "The token decides the tenant", "A REQUEST NEVER NAMES ONE · REVOKED OR DISABLED IS 401 · TOKENS STORED AS SHA-256"),
              ("KEYS", "Tenant on every row", "TENANT_ID STARTS EVERY KEY AND INDEX · TWO CLIENTS CAN BOTH HAVE TECHNICIAN 42"),
              ("RLS", "Row-level security", "EACH TRANSACTION SETS APP.TENANT_ID · AN UNSET TENANT SEES NOTHING"),
              ("ROLES", "Least privilege", "RUNTIME LOGIN IS SCHEDULER_APP, NEVER THE OWNER · MIGRATIONS RUN AS THE OWNER"),
              ("TURNS", "Fair turns", "ADMISSION AND THE QUEUE START THE CLIENT WAITING LONGEST · NO CLIENT STARVES ANOTHER")]
    x, w, y0, h = 200, 1040, 48, 76
    f.arrow(150, y0 + 8, 150, y0 + len(layers) * h - 8, "muted"); f.label(150, y0 - 12, "A REQUEST", SOFT, KEY)
    for i, (tag, name, sub) in enumerate(layers):
        y = y0 + i * h
        focal = tag == "RLS"
        f.rect(x, y, w, h, PAPER); f.rect(x, y, w, h, rgba(ACCENT, 0.08) if focal else (PAPER if i % 2 == 0 else PAPER2), ACCENT if focal else ink(0.12), 1.4 if focal else 1)
        f.tag(x + 16, y + 30, tag, ACCENT if focal else ink(0.40), ACCENT if focal else INK)
        f.name(x + 120, y + 34, name, INK, "start")
        f.tech(x + 120, y + 56, sub, MUTED, "start", KEY)
    f.line(x, y0 + len(layers) * h, x + w, y0 + len(layers) * h, INK, 1)
    f.callout(x, y0 + len(layers) * h + 40, ["A missed filter in application code fails closed: the database itself refuses another tenant's rows.",
                                               "Road legs are not tenant data: they are shared by every client in a metro, kept at about 1 m and purged within 30 days."])
    f.legend(546, [("swatch", rgba(ACCENT, 0.08), "THE LAYER THAT FAILS CLOSED"), ("swatch", PAPER2, "GUARD")])
    return f


def replicas(_=None) -> Figure:
    f = Figure("replicas", "What lives in a replica and what lives in the database", "Architecture of three replicas behind the proxy: each keeps only its admission slots, the memory tier of the route cache and its list of running calls, while correctness lives in the shared database.", 1280, 600)
    f.node(440, 32, 400, 56, "scheduler-proxy", ["NO STICKY SESSIONS: ANY REPLICA, SAME ANSWER"], SERVICE)
    reps = [("Booking replica", "SCHEDULER_ROLE=BOOKING"), ("Booking replica", "SCHEDULER_ROLE=BOOKING"), ("Daily replica", "SEVERAL CALCULATIONS AT ONCE")]
    for i, (name, role) in enumerate(reps):
        x = 64 + i * 400
        f.zone(x, 136, 352, 236, name.upper())
        f.tech(x + 176, 176, role, MUTED, size=KEY)
        for j, item in enumerate(("Search slots (admission)", "Route cache, memory tier", "Running calls")):
            f.rect(x + 24, 192 + j * 56, 304, 44, PAPER, rx=6); f.rect(x + 24, 192 + j * 56, 304, 44, PAPER2, SOFT, 1, rx=6)
            f.text(x + 176, 219 + j * 56, item, KEY, INK, SANS, "middle", weight="700")
        f.route([(640, 88), (640, 112), (x + 176, 112), (x + 176, 136)] if x + 176 != 640 else [(640, 88), (640, 136)], "muted")
        f.arrow(x + 176, 372, x + 176, 420, "muted")
    f.rect(64, 420, 1152, 112, PAPER, rx=6); f.rect(64, 420, 1152, 112, rgba(ACCENT, 0.06), ACCENT, 1.4, rx=6)
    f.eyebrow_text(80, 442, "SCHEDULER-DB: WHERE CORRECTNESS LIVES", ACCENT)
    for k, (title, sub) in enumerate((("Row locks + unique keys", "(TENANT, TECHNICIAN, DATE)"), ("Timestamp comparison", "IN ONE TRANSACTION"), ("Request claims", "HASH + 60 S LEASE"),
                                      ("Durable queue", "SKIP LOCKED, TURNS BY CLIENT"), ("Cancel flag", "SEEN BY ANY REPLICA IN ~1 S"))):
        x = 88 + k * 226
        f.text(x, 484, title, KEY, INK, SANS, weight="700"); f.tech(x, 506, sub, MUTED, "start", KEY)
    f.legend(566, [("swatch", PAPER2, "PER REPLICA, BY DESIGN"), ("swatch", rgba(ACCENT, 0.06), "SHARED, AND WHERE CORRECTNESS LIVES")], note="role decides only which background work runs on a replica")
    return f


def queue_states(_=None) -> Figure:
    f = Figure("queue_states", "A queued proposal's life", "State machine of a daily or repair proposal sent with Prefer: respond-async: accepted 202 and queued, taken in turn by any replica, completed with the answer the call would have given, or completed with 429 after waiting 15 minutes.", 1280, 520)
    f.node(40, 196, 200, 80, "POST proposal", ["PREFER: RESPOND-ASYNC"], HOST)
    f.state(320, 196, 200, 80, "QUEUED", "DURABLE TABLE")
    f.state(620, 196, 200, 80, "RUNNING", "ANY REPLICA")
    f.state(940, 196, 260, 80, "COMPLETED", "THE CALL'S OWN ANSWER", focal=True)
    f.arrow(240, 236, 320, 236, "muted"); f.label(280, 226, "202", SOFT, KEY)
    f.arrow(520, 236, 620, 236, "muted"); f.label(570, 226, "ITS TURN", SOFT, KEY)
    f.arrow(820, 236, 940, 236, "accent"); f.label(880, 226, "DONE", ACCENT, KEY)
    f.route([(420, 276), (420, 360), (1070, 360), (1070, 276)], "muted", "5,4"); f.label(745, 352, "WAITED 15 MINUTES: COMPLETED WITH 429, NEVER RUN", SOFT, KEY)
    f.route([(140, 196), (140, 120), (400, 120)], "muted", "5,4"); f.label(270, 112, "QUEUE FULL (50)", SOFT, KEY)
    f.rect(400, 104, 120, 32, PAPER, rx=2); f.rect(400, 104, 120, 32, PAPER2, ink(0.40), 0.8, rx=2); f.text(460, 125, "429 AT ONCE", KEY, INK, MONO, "middle")
    f.callout(620, 96, ["A worker takes the waiting request of the client", "whose last one started longest ago, with SKIP", "LOCKED: thirty overnight days of one client never", "hold back another client's single day."])
    f.para(40, 420, ["The host polls GET /requests/{requestId} and reads QUEUED, RUNNING, then COMPLETED with the status and body the call would have answered.",
                     "DELETE /requests/{requestId} is a best-effort cancel from any replica; the call then answers 409 CANCELLED."], TECH, INK, MONO)
    f.legend(486, [("swatch", SERVICE, "STATE"), ("swatch", rgba(ACCENT, 0.10), "THE ANSWER"), ("arrow-dashed", MUTED, "REFUSAL PATH")])
    return f


def thin_store(tables, stored) -> Figure:
    names = {n.strip("`") for row in tables for n in row[0].replace("`", "").split(", ")}
    expected = {"api_request", "api_daily_proposal", "api_proposal_technician_day", "api_commit_receipt", "api_booking_day", "api_booking_offer_set", "api_booking_offer", "api_booking_receipt"}
    assert names == expected, names ^ expected
    assert len(stored) == 5
    f = Figure("thin_store", "The thin store: what the scheduler keeps, and for how long", "Data model of the scheduler's own tables grouped into request claims, daily proposals and booking, with their relations and the lifetime of each kind of data; everything older than the retention period is purged.", 1280, 760)

    def table_box(x, y, name, cols, focal=False, w=248):
        h = 40 + 18 * len(cols)
        f.rect(x, y, w, h, PAPER, rx=6); f.rect(x, y, w, h, rgba(ACCENT, 0.06) if focal else PAPER, ACCENT if focal else SOFT, 1.4 if focal else 1, rx=6)
        f.rect(x, y, w, 26, rgba(ACCENT, 0.12) if focal else SERVICE, rx=6)
        f.text(x + 12, y + 18, name, KEY, INK, MONO, weight="700")
        for i, c in enumerate(cols):
            f.text(x + 12, y + 44 + i * 18, c, KEY, MUTED, MONO)
        return h

    f.zone(40, 40, 300, 260, "REQUESTS")
    table_box(60, 72, "api_request", ["tenant, requestId", "operation, body SHA-256", "claim lease 60 s", "stored response"], w=260)
    f.zone(370, 40, 300, 420, "DAILY AND REPAIR")
    table_box(390, 72, "api_daily_proposal", ["metro, zone, date", "input revision", "routing identity", "PROPOSED, COMMITTED, STALE"])
    table_box(390, 216, "api_proposal_technician_day", ["proposal, technician, date", "lastModified, exact text"])
    table_box(390, 330, "api_commit_receipt", ["one per proposal", "assignments, timestamps"])
    f.arrow(420, 184, 420, 216, "muted"); f.route([(638, 128), (654, 128), (654, 368), (638, 368)], "muted")
    f.zone(700, 40, 540, 420, "BOOKING")
    table_box(716, 72, "api_booking_day", ["metro-day arrangement", "every active hold", "per technician: timestamp", "versioned; locked on write"], focal=True, w=240)
    table_box(980, 72, "api_booking_offer_set", ["what a search offered", "requestId, snapshot"], w=240)
    table_box(980, 180, "api_booking_offer", ["HELD, SELECTED, RELEASED,", "SUPERSEDED, LOST, CONFIRMED", "expiry is a time"], w=240)
    table_box(980, 330, "api_booking_receipt", ["one per offer", "the confirm's receipt"], w=240)
    f.arrow(1100, 148, 1100, 180, "muted"); f.arrow(1100, 274, 1100, 330, "muted")
    f.zone(40, 320, 300, 140, "SHARED")
    f.para(60, 364, ["tenants, API token digests", "road route cache (30 days)"], KEY, INK, MONO, lead=22)
    # Lifetimes.
    f.eyebrow_text(40, 500, "HOW LONG EACH KIND OF DATA LIVES", SOFT)
    f.line(40, 508, 1240, 508, ink(0.12), 0.8)
    for i, (what, life) in enumerate(stored):
        y = 532 + i * 22
        f.text(40, y, what, KEY, INK, SANS, weight="700")
        f.text(360, y, life.replace('"', ""), KEY, MUTED, MONO)
    f.callout(40, 660, ["SCHEDULER_RETENTION_DAYS is required with no default: the scheduler will not start without it.", "A daily purge deletes children before parents."])
    f.legend(726, [("swatch", rgba(ACCENT, 0.06), "THE ROW EVERY BOOKING LOCKS"), ("swatch", PAPER, "TABLE"), ("arrow", MUTED, "ONE TO MANY OR ONE TO ONE")], note="tenant_id leads every key")
    return f


def geocode(_=None) -> Figure:
    f = Figure("geocode", "Where a stop's coordinates come from", "Flowchart of locating a stop: coordinates in the snapshot are used as given; an address without coordinates is located by Nominatim only in NOMINATIM mode and only as an exact house; anything else is not located.", 1280, 480)
    y = 150
    f.node(40, y - 36, 180, 72, "A location", ["IN THE SNAPSHOT"], HOST)
    f.diamond(380, y, 220, 120, ["LATITUDE AND", "LONGITUDE", "GIVEN?"], focal=True)
    f.diamond(700, y, 200, 120, ["NOMINATIM", "MODE?"])
    f.diamond(1010, y, 220, 120, ["ZIP, NUMBER AND", "STREET AGREE?"])
    f.arrow(220, y, 270, y, "muted")
    f.arrow(490, y, 600, y, "muted"); f.label(545, y - 10, "NO", SOFT, KEY)
    f.arrow(800, y, 900, y, "muted"); f.label(850, y - 10, "YES", SOFT, KEY)
    outcomes = [(380, "Route as given", "NEVER GEOCODED AGAIN", "YES", True), (700, "Not located", "MODE OFF, OR NOT EXACT", "NO", False), (1010, "Located", "AN EXACT HOUSE", "YES", False)]
    for x, name, sub, lab, focal in outcomes:
        f.arrow(x, y + 60, x, 300, "accent" if focal else "muted"); f.label(x + 22, 284, lab, ACCENT if focal else SOFT, KEY)
        f.node(x - 110, 300, 220, 64, name, [sub], rgba(ACCENT, 0.08) if focal else (PAPER2 if name.startswith("Not") else SERVICE), ACCENT if focal else SOFT, 1.4 if focal else 1)
    # A street-level or ambiguous match is not located either: the NO branch joins the same outcome from below.
    f.route([(1120, y), (1200, y), (1200, 404), (720, 404), (720, 364)], "muted")
    f.label(1160, y - 10, "NO", SOFT, KEY); f.label(960, 396, "STREET-LEVEL OR AMBIGUOUS", SOFT, KEY)
    f.legend(456, [("swatch", rgba(ACCENT, 0.08), "THE NORMAL PATH"), ("swatch", SERVICE, "LOCATED"), ("swatch", PAPER2, "NOT LOCATED")], note="every drawn stop records where its coordinates came from")
    return f


def map_lifecycle(_=None) -> Figure:
    f = Figure("map_lifecycle", "How a map version is prepared and switched on", "Process of preparing a map version as a whole: the merged OpenStreetMap extract, both road graphs, tiles and TIGER address ranges, validation with preview services, activation by switching symlinks, and the new routing identity that starts every cache cold.", 1280, 420)
    steps = [("Extract", "MERGED OSM"), ("Road graphs", "FLEXIBLE + CH"), ("Tiles", "FOR DRAWING"), ("TIGER ranges", "FOR NOMINATIM"), ("Validate", "PREVIEW SERVICES"), ("Activate", "SWITCH SYMLINKS")]
    w, gap, x0, y = 172, 34, 40, 80
    for i, (name, sub) in enumerate(steps):
        x = x0 + i * (w + gap)
        focal = name == "Activate"
        f.node(x, y, w, 72, name, [sub], rgba(ACCENT, 0.08) if focal else SERVICE, ACCENT if focal else SOFT, 1.4 if focal else 1, tag=str(i + 1))
        if i:
            f.arrow(x - gap, y + 36, x, y + 36, "muted")
    f.callout(x0 + (w + gap), 188, ["CH import: about 27 s and about 200 MiB more on disk per map version."])
    ax = x0 + 5 * (w + gap) + w / 2
    f.arrow(ax, y + 72, ax, 260, "accent")
    f.node(ax - 260, 260, 360, 72, "A new routing identity", ["HASH OF GRAPH + MAP VERSION"], rgba(ACCENT, 0.08), ACCENT, 1.4)
    f.para(40, 284, ["Memory tiers are cleared and the database tier is never read", "for the old identity: a changed road network can never be", "mixed with old legs, and every cache starts cold."], TECH, INK, MONO)
    f.legend(396, [("swatch", SERVICE, "PREPARATION STEP"), ("swatch", rgba(ACCENT, 0.08), "THE SWITCH AND ITS CONSEQUENCE")], note="a switch between flexible and CH changes the identity too")
    return f
