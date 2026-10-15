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
    f = Figure("timeline", "The trail from the demonstration to today", "Timeline of the work from the September 19 demonstration to October 14 in five threads: architecture, daily search, booking, routing, and load and engine work, with the pull request of each change.", 980, 900)
    start, end = date(2026, 9, 18), date(2026, 10, 15)
    x0, x1, top, lane_h = 156, 940, 64, 148
    span = (end - start).days

    def xof(d: str) -> float:
        return round(x0 + (date.fromisoformat(d) - start).days / span * (x1 - x0))

    bottom = top + lane_h * len(LANES) + 24
    f.height = int(bottom + 112)
    for d in ("2026-09-19", "2026-09-26", "2026-10-03", "2026-10-10", "2026-10-14"):
        x = xof(d)
        f.line(x, top - 8, x, bottom, ink(0.08), 0.8)
        f.tech(x, bottom + 22, date.fromisoformat(d).strftime("%b %d").upper(), MUTED)
    f.line(x0, bottom, x1, bottom, ink(0.25), 1)
    xt = xof("2026-10-14")
    f.line(xt, top - 16, xt, bottom, ACCENT, 1.4, "6,4")
    f.label(xt - 4, top - 24, "THE CURRENT STATE", ACCENT, KEY, "end")
    lead = 14
    for i, lane in enumerate(LANES):
        y = top + i * lane_h + lane_h / 2
        f.text(x0 - 16, y + 5, lane, KEY, INK, SANS, "end", weight="700")
        f.line(x0, y, x1, y, ink(0.20), 1, "2,4")
        groups: dict[str, list] = {}
        for d, pr, l, label in events:
            if l == lane:
                groups.setdefault(d, []).append((pr, label))
        placed = {True: [], False: []}  # side -> [(x_left, x_right, depth)]
        for k, (d, items) in enumerate(sorted(groups.items())):
            x = xof(d)
            first = (d, lane) == ("2026-09-19", "Architecture")
            f.circle(x, y, 6, PAPER, MUTED, 1.5) if first else f.circle(x, y, 6, SERVICE, INK, 1)
            lines = []
            for pr, label in items:
                lines += _wrap(label, 10)
                if pr:
                    lines.append(f"#{pr}")
            half = max(mono_width(line, KEY) for line in lines) / 2 + 8
            # Each block goes on the side of the lane where it sits closest to the axis without overlapping a block
            # already placed there; ties alternate, so neighbours spread above and below.
            def depth_on(side: bool) -> int:
                depth = 0
                for left, right, d0, d1 in sorted(placed[side], key=lambda b: b[2]):
                    if left < x + half and x - half < right and depth < d1 and d0 < depth + len(lines):
                        depth = d1
                return depth
            preferred = k % 2 == 0
            above = preferred if depth_on(preferred) <= depth_on(not preferred) else not preferred
            depth = depth_on(above)
            placed[above].append((x - half, x + half, depth, depth + len(lines)))
            for j, line in enumerate(lines):
                if above:
                    yy = y - 14 - (depth + len(lines) - 1 - j) * lead
                else:
                    yy = y + 24 + (depth + j) * lead
                f.label(x, yy, line, MUTED if line.startswith("#") else INK, KEY)
    f.legend(bottom + 70, [("hollow-dot", PAPER, "THE DEMONSTRATION"), ("solid-dot", SERVICE, "A MERGED CHANGE OR A STUDY"), ("line-dashed", ACCENT, "TODAY, OCT 14")],
             note="pull request dates are checked against git")
    return f


def scoreboard(cards: list[dict]) -> Figure:
    f = Figure("scoreboard", "Where each thread started and where it stands", "One row per thread of the work, each with the first measured value and the value today: daily search, booking, routing, admission and the engine.", 980, 416)
    cols = (40, 336, 588, 740)
    for x, head in zip(cols, ("THREAD AND MEASURE", "FIRST MEASURED", "TODAY", "CONTEXT")):
        f.eyebrow_text(x, 44, head, ACCENT if head == "TODAY" else SOFT)
    for i, c in enumerate(cards):
        y = 60 + i * 64
        f.line(40, y, 940, y, ink(0.12), 0.8)
        f.eyebrow_text(cols[0], y + 22, c["thread"].upper(), SOFT)
        f.text(cols[0], y + 46, c["metric"], NAME, INK, SANS, weight="700")
        f.text(cols[1], y + 40, c["before"], TECH, MUTED, MONO)
        f.arrow(cols[2] - 60, y + 36, cols[2] - 16, y + 36, "muted")
        f.text(cols[2], y + 46, c["after"], 28, INK, SANS, weight="700")
        for j, note in enumerate(c["notes"]):
            f.text(cols[3], y + 30 + j * 18, note, KEY, MUTED, MONO)
    f.line(40, 380, 940, 380, ink(0.12), 0.8)
    return f


# ---- Part I: where it started ----------------------------------------------------------------------
def delta(rows) -> Figure:
    f = Figure("delta", "From a portal module to a stateless service", "Architecture delta: in September the scheduler shared the portal's database and ran its own jobs; today WaterFlex Software calls a stateless API that keeps only a thin store of its own. A ledger lists the seven changes.", 980, 1000)
    # Before.
    f.zone(40, 40, 900, 264, "SEPTEMBER: A MODULE OF THE PORTAL")
    f.node(80, 76, 240, 72, "Portal", ["NEXT.JS · ITS OWN ENDPOINTS"], HOST, tag="1")
    f.node(440, 76, 240, 72, "Scheduler", ["JAVA · 2 A.M. BATCH, WORKERS"], SERVICE, tag="4")
    f.node(80, 196, 600, 88, "Shared Postgres", ["ABOUT 40 PORTAL TABLES · OMAHA_SETTING", "APPOINTMENTS WRITTEN UNDER ROW LOCKS"], STORE, tag="2")
    f.arrow(320, 112, 440, 112, "muted"); f.label(380, 102, "CALLS", SOFT, KEY)
    f.arrow(200, 148, 200, 196, "muted"); f.label(212, 177, "READS, WRITES", SOFT, KEY, "start")
    f.arrow(560, 148, 560, 196, "muted"); f.label(572, 177, "READS, WRITES", SOFT, KEY, "start")
    f.callout(720, 108, ["One database, one", "settings table and its", "own overnight batch:", "fine for a demo, wrong", "for a product."])
    # After.
    f.zone(40, 332, 900, 264, "TODAY: A SERVICE OF ITS OWN")
    f.node(80, 368, 240, 72, "WaterFlex Software", ["HOST · OWNS EVERY RECORD"], HOST, tag="7")
    f.node(440, 368, 240, 72, "Scheduler API", ["/API/V1 · 12 OPERATIONS"], SERVICE, ACCENT, 1.6, tag="3")
    f.node(80, 488, 240, 88, "Host database", ["THE BUSINESS RECORD", "LASTMODIFIED PER DAY"], HOST, tag="5")
    f.node(440, 488, 240, 88, "Thin store", ["ITS OWN POSTGRES", "TENANTS · ROW SECURITY"], STORE, tag="6")
    f.arrow(320, 392, 440, 392, "link"); f.label(380, 382, "SNAPSHOT", LINK, KEY)
    f.arrow(440, 420, 320, 420, "muted", "5,4"); f.label(380, 444, "RESULTS", SOFT, KEY)
    f.arrow(200, 440, 200, 488, "muted"); f.label(212, 469, "COMPARE-AND-SET", SOFT, KEY, "start")
    f.arrow(560, 440, 560, 488, "muted"); f.label(572, 469, "HOLDS, RECEIPTS", SOFT, KEY, "start")
    f.callout(720, 400, ["Every call carries the", "facts it needs; nothing", "is copied, synced or", "scheduled by the", "scheduler itself."])
    # Ledger.
    f.eyebrow_text(40, 636, "CHANGES LEDGER", SOFT)
    f.line(40, 644, 940, 644, ink(0.12), 0.8)
    f.eyebrow_text(212, 664, "SEPTEMBER", SOFT); f.eyebrow_text(584, 664, "TODAY", SOFT)
    y = 690
    for i, (piece, before, after) in enumerate(rows):
        b, a = _wrap(before, 50), _wrap(after, 51)
        f.tag(40, y - 12, str(i + 1))
        f.text(76, y + 1, piece, KEY, INK, SANS, weight="700")
        f.para(212, y, b, KEY, MUTED, MONO, lead=14)
        f.para(584, y, a, KEY, INK, MONO, lead=14)
        y += 14 * max(len(b), len(a)) + 14
    f.height = int(y + 60)
    f.legend(y + 34, [("swatch", HOST, "WATERFLEX SOFTWARE"), ("swatch", SERVICE, "SCHEDULER"), ("swatch", STORE, "DATABASE"), ("arrow-link", LINK, "HTTPS CALL")], note="tags point to the ledger rows")
    return f


# ---- Part II: the stateless API ---------------------------------------------------------------------
def ownership(rows) -> Figure:
    f = Figure("ownership", "Who owns what", "Two zones: WaterFlex Software owns master data, the business record, lastModified and each client's policy; the scheduler owns holds, offers, proposals, receipts and the road graph with its cache. A snapshot goes in, results come out, and the host writes its own record.", 980, 640)
    left = [r[0] for r in rows if r[0]]
    right = [r[1] for r in rows if r[1]]
    sides = []
    for side, items, zx in (("left", left, 40), ("right", right, 580)):
        boxes = [_wrap(text.replace("`", ""), 42) for text in items]
        sides.append((side, items, zx, boxes))
    zh = max(sum(22 + 18 * len(lines) + 12 for lines in boxes) for *_, boxes in sides) + 36 + 8
    f.zone(40, 40, 360, zh, "WATERFLEX SOFTWARE OWNS")
    f.zone(580, 40, 360, zh, "THE SCHEDULER OWNS")
    for side, items, zx, boxes in sides:
        y = 76
        for i, (text, lines) in enumerate(zip(items, boxes)):
            h = 22 + 18 * len(lines)
            focal = side == "right" and i == 0
            fill = HOST if side == "left" else (rgba(ACCENT, 0.08) if focal else (PAPER2 if text.startswith("Nothing") else SERVICE))
            f.rect(zx + 16, y, 328, h, PAPER, rx=6); f.rect(zx + 16, y, 328, h, fill, ACCENT if focal else SOFT, 1.4 if focal else 1, rx=6)
            f.para(zx + 32, y + 24, lines, TECH, INK, MONO)
            y += h + 12
    f.arrow(400, 136, 580, 136, "link"); f.label(490, 126, "SNAPSHOT", LINK, KEY)
    f.para(490, 158, ["facts and", "lastModified"], KEY, MUTED, MONO, "middle", 14)
    f.arrow(580, 236, 400, 236, "muted", "5,4"); f.label(490, 226, "RESULTS", SOFT, KEY)
    f.para(490, 258, ["offers, routes,", "receipts"], KEY, MUTED, MONO, "middle", 14)
    f.callout(412, 340, ["The host writes", "its own record", "with a compare-", "and-set; the", "scheduler never", "touches it."])
    f.height = int(40 + zh + 84)
    f.legend(40 + zh + 52, [("swatch", HOST, "HOST DATA"), ("swatch", rgba(ACCENT, 0.08), "WHAT KEEPS PROMISES SAFE"), ("swatch", SERVICE, "SCHEDULER DATA")], note="source: the integration guide")
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
    f = Figure("surface", "The API surface: twelve operations in six families", "Map of the twelve /api/v1 operations grouped into booking, daily, repair, routes, requests and identity, with the order in which a host calls the booking and daily ones.", 980, 820)
    w, h, gap, x0 = 248, 72, 36, 124
    row_y = [48, 240, 340, 440, 540, 640]
    for (family, chain, extra), y in zip(SURFACE, row_y):
        f.text(x0 - 20, y + h / 2 + 5, family, KEY, INK, SANS, "end", weight="700")
        for j, (op_id, path, what) in enumerate(chain):
            x = x0 + j * (w + gap)
            focal = op_id == "confirmBookingHold"
            f.rect(x, y, w, h, PAPER, rx=6); f.rect(x, y, w, h, rgba(ACCENT, 0.08) if focal else SERVICE, ACCENT if focal else SOFT, 1.4 if focal else 1, rx=6)
            f.name(x + 16, y + 26, op_id, INK, "start")
            f.tech(x + 16, y + 46, path, INK, "start")
            f.tech(x + 16, y + 62, what, MUTED, "start", KEY)
            if j:
                f.arrow(x - gap, y + h / 2, x, y + h / 2, "muted")
        if extra:
            op_id, path, what = extra
            x, yy = x0 + w + gap, y + h + 24
            f.rect(x, yy, w, h, PAPER, rx=6); f.rect(x, yy, w, h, PAPER2, SOFT, 1, rx=6, dash="4,3")
            f.name(x + 16, yy + 26, op_id, INK, "start"); f.tech(x + 16, yy + 46, path, INK, "start"); f.tech(x + 16, yy + 62, what, MUTED, "start", KEY)
            f.route([(x0 + w / 2, y + h), (x0 + w / 2, yy + h / 2), (x, yy + h / 2)], "muted", "5,4")
    f.legend(760, [("swatch", SERVICE, "OPERATION"), ("swatch", rgba(ACCENT, 0.08), "THE CALL THAT COMMITS A BOOKING"), ("arrow", MUTED, "CALLED NEXT"), ("swatch-dashed", PAPER2, "ALTERNATIVE PATH")],
             note="all under /api/v1, OpenAPI 3.1")
    return f


def token(_=None) -> Figure:
    f = Figure("token", "The lastModified round trip", "Sequence showing the host's lastModified timestamp travelling with the snapshot, recorded with a proposal, compared for equality at commit, and used again in the host's compare-and-set write; a changed value is answered 409 STALE.", 980, 740)
    hx, sx = 150, 680
    f.node(hx - 120, 40, 240, 56, "WaterFlex Software", ["READS ITS OWN RECORDS"], HOST)
    f.node(sx - 120, 40, 240, 56, "Scheduler", ["THIN STORE"], SERVICE)
    for x in (hx, sx):
        f.line(x, 96, x, 656, ink(0.20), 1, "3,3")
    f.path(f"M{hx + 4},128 H{hx + 36} Q{hx + 44},128 {hx + 44},136 V144 Q{hx + 44},152 {hx + 36},152 H{hx + 12}", MUTED, 1.2, marker="arrow")
    f.label(hx + 56, 138, "READ EACH TECHNICIAN-DAY WITH ITS", INK, KEY, "start")
    f.label(hx + 56, 154, "LASTMODIFIED, E.G. 09:14:02.118", INK, KEY, "start")
    f.arrow(hx + 4, 200, sx - 4, 200, "link"); f.label((hx + sx) / 2, 190, "SNAPSHOT · EVERY DAY CARRIES ITS LASTMODIFIED", LINK, KEY)
    f.rect(sx - 4, 200, 8, 52, ink(0.06), MUTED, 0.8)
    f.label(sx - 16, 232, "RECORD THE VALUES WITH THE PROPOSAL", INK, KEY, "end")
    f.arrow(sx - 4, 252, hx + 4, 252, "muted", "5,4"); f.label((hx + sx) / 2, 276, "201 PROPOSAL OR OFFERS", SOFT, KEY)
    f.arrow(hx + 4, 328, sx - 4, 328, "link"); f.label((hx + sx) / 2, 318, "COMMIT OR CONFIRM · THE CURRENT VALUES", LINK, KEY)
    fx, fy, fw, fh = 110, 352, 610, 272
    f.rect(fx, fy, fw, fh, ink(0.02), ink(0.22), 1, rx=4)
    f.rect(fx, fy, 40, 16, PAPER, ink(0.22), 1, rx=2); f.text(fx + 20, fy + 12, "ALT", 8, MUTED, MONO, "middle", spacing="0.12em")
    f.text(fx + 12, fy + 36, "[EVERY VALUE EQUAL TO THE ONE RECORDED]", TECH, MUTED, MONO)
    f.arrow(sx - 4, 428, hx + 4, 428, "accent"); f.label((hx + sx) / 2, 418, "200 RECEIPT · ASSIGNMENTS AND TIMESTAMPS", ACCENT, KEY)
    f.path(f"M{hx + 4},452 H{hx + 36} Q{hx + 44},452 {hx + 44},460 V468 Q{hx + 44},476 {hx + 36},476 H{hx + 12}", MUTED, 1.2, marker="arrow")
    f.label(hx + 56, 462, "WRITE THE RECORD WITH A COMPARE-AND-SET ON", INK, KEY, "start")
    f.label(hx + 56, 478, "THE SAME VALUES; MOVE LASTMODIFIED", INK, KEY, "start")
    f.line(fx + 8, 508, fx + fw - 8, 508, ink(0.20), 1, "4,3")
    f.text(fx + 12, 532, "[ANY VALUE DIFFERENT]", TECH, MUTED, MONO)
    f.arrow(sx - 4, 588, hx + 4, 588, "muted", "5,4"); f.label((hx + sx) / 2, 578, "409 STALE · CHANGED LISTS THE TECHNICIAN-DAYS", SOFT, KEY)
    f.callout(740, 200, ["Compared for exact", "equality, never ordered:", "clock skew between", "servers cannot cause a", "wrong decision. At", "worst an unrelated", "change forces a retry."])
    f.legend(704, [("arrow-link", LINK, "CALL"), ("arrow-dashed", MUTED, "REPLY"), ("line", ACCENT, "HEADLINE SUCCESS")], note="one token for proposals, offers, commits and confirms")
    return f


def reconcile(_=None) -> Figure:
    f = Figure("reconcile", "Reconciling a technician-day before a booking search", "Flowchart of the five reconciliation rules a booking search or confirm applies to each technician-day of the host's snapshot against the stored arrangement: refuse STALE, keep as stored, accept the expected change, replace the route, or lose the holds of that date.", 980, 920)
    cx, dw, dh = 260, 240, 120
    cys = [164, 314, 464, 614]
    questions = [["ALREADY SEEN", "TIMESTAMP OR", "PRE-RECEIPT ROUTE?"], ["LASTMODIFIED", "UNCHANGED?"], ["APPOINTMENTS", "EXACTLY AS", "EXPECTED?"], ["STORED ROUTE HAS", "A HOLD OR A", "PENDING MOVE?"]]
    right = [("RULE 0", "409 STALE", "refused before anything is reconciled; nothing is lost", "focal"), ("RULE 1", "As stored", "nothing to do", "kept"),
             ("RULE 2", "Accepted", "the new timestamp is recorded", "kept"), ("RULE 3", "Holds lost", "every hold on that date is lost; the date restarts from the host's records", "lost")]
    f.node(cx - 80, 40, 160, 56, "Snapshot", ["ONE TECHNICIAN-DAY"], HOST)
    f.arrow(cx, 96, cx, cys[0] - dh / 2, "muted")
    for i, (cy, q) in enumerate(zip(cys, questions)):
        f.diamond(cx, cy, dw, dh, q, focal=i == 0)
        rule, name, sub, kind = right[i]
        focal = kind == "focal"
        f.arrow(cx + dw / 2, cy, 500, cy, "accent" if focal else "muted"); f.label(440, cy - 10, "YES", ACCENT if focal else SOFT, KEY)
        fill = rgba(ACCENT, 0.08) if focal else (PAPER2 if kind == "lost" else SERVICE)
        f.rect(500, cy - 42, 440, 84, PAPER, rx=6); f.rect(500, cy - 42, 440, 84, fill, ACCENT if focal else SOFT, 1.4 if focal else 1, rx=6)
        f.tag(508, cy - 36, rule)
        f.name(516, cy - 2, name, INK, "start")
        f.para(516, cy + 18, _wrap(sub, 58), KEY, INK, MONO, lead=14)
        if i < 3:
            f.arrow(cx, cy + dh / 2, cx, cys[i + 1] - dh / 2, "muted"); f.label(cx + 22, cy + dh / 2 + 19, "NO", SOFT, KEY)
    f.arrow(cx, cys[3] + dh / 2, cx, 714, "muted"); f.label(cx + 22, cys[3] + dh / 2 + 25, "NO", SOFT, KEY)
    f.rect(cx - 120, 714, 240, 84, PAPER, rx=6); f.rect(cx - 120, 714, 240, 84, SERVICE, SOFT, 1, rx=6)
    f.tag(cx - 112, 720, "RULE 3")
    f.name(cx, 756, "Replace route")
    f.para(cx, 776, ["the host's route becomes", "the stored one"], KEY, INK, MONO, "middle", 14)
    f.rect(500, 714, 440, 100, PAPER, rx=6); f.rect(500, 714, 440, 100, PAPER2, SOFT, 1, rx=6, dash="4,3")
    f.tag(508, 720, "RULE 4")
    f.para(516, 754, ["Then, for every day kept, the whole arrangement is", "re-evaluated with the snapshot's current windows,", "durations, shifts and absences. If it no longer fits,", "that date's holds are lost the same way."], KEY, INK, MONO, lead=14)
    f.legend(874, [("swatch", rgba(ACCENT, 0.08), "THE RULE THE LOAD TOOL ADDED (#152)"), ("swatch", SERVICE, "KEPT"), ("swatch", PAPER2, "HOLDS LOST")], note="32 timestamps remembered per day")
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
    f = Figure("refusals", "Every refusal, by what the host does next", "Every status code the API can refuse with, grouped by the host's next action: fix the request, check the token, rebuild from current records, search again, wait and retry, or nothing.", 980, 680)
    rows_y = [40 + i * 96 for i in range(len(REFUSAL_GROUPS))]
    mid = (rows_y[0] + rows_y[-1] + 80) / 2
    f.node(40, mid - 36, 160, 72, "A refusal", ["PROBLEM+JSON"], HOST)
    spine = 228
    f.line(200, mid, spine, mid, MUTED, 1.2)
    f.line(spine, rows_y[0] + 40, spine, rows_y[-1] + 40, MUTED, 1.2)
    for (action, how, cs), y in zip(REFUSAL_GROUPS, rows_y):
        focal = action.startswith("Rebuild")
        f.arrow(spine, y + 40, 260, y + 40, "accent" if focal else "muted")
        f.rect(260, y, 340, 80, PAPER, rx=6); f.rect(260, y, 340, 80, rgba(ACCENT, 0.08) if focal else SERVICE, ACCENT if focal else SOFT, 1.4 if focal else 1, rx=6)
        f.name(276, y + 28, action, INK, "start")
        f.para(276, y + 48, _wrap(how, 44), KEY, MUTED, MONO, lead=14)
        # Status chips, wrapping onto a second row when they do not fit.
        chips, x, row = [], 632, 0
        for c in cs:
            w = mono_width(c, KEY) + 20
            if x > 632 and x + w > 940:
                row += 1; x = 632
            chips.append((x, row, w, c)); x += w + 12
        rows = row + 1
        top = y + 40 - (rows * 24 + (rows - 1) * 8) / 2
        for cx, r, w, c in chips:
            cy = top + r * 32
            f.rect(cx, cy, w, 24, PAPER, rx=2); f.rect(cx, cy, w, 24, PAPER2, ink(0.40), 0.8, rx=2)
            f.text(cx + w / 2, cy + 16, c, KEY, INK, MONO, "middle")
        f.line(600, top + 12, 632, top + 12, ink(0.25), 1)
    f.legend(644, [("swatch", SERVICE, "THE HOST'S ACTION"), ("swatch", PAPER2, "STATUS AND CODE"), ("swatch", rgba(ACCENT, 0.08), "THE ONE THE TOKEN DESIGN RELIES ON")], note="source: the integration guide")
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
    f = Figure("daily_pipeline", "A daily proposal, stage by stage", "Pipeline of a daily proposal through nine stages, from authentication to storing the answer, with the refusal each stage can return.", 980, 600)
    stages = [("Authenticate", "TOKEN TO TENANT", "401", False), ("Parse", "STRICT READ", "400 · 422", False), ("Claim", "REQUESTID + HASH", "400 · 429", False),
              ("Cutoff", "6 A.M. LOCAL", "422", False), ("Admission", "WOULD IT START IN TIME?", "429 BUSY", True),
              ("Route", "EVERY LEG, 30 S", "503", False), ("Calculate", "COST + FAIRNESS + 5 S", "503 · 409", False), ("Decide", "AGAINST THE DAY AS SENT", None, False), ("Store", "PROPOSAL, TIMESTAMPS", None, False)]
    _pipeline(f, stages, 40, 3, 268, 48, 40, 92, 156)
    f.callout(40, 496, ["Decide answers IMPROVED, REJECTED_BY_POLICY or NO_IMPROVEMENT with a reason code;", "a plan with any overtime is never returned."])
    f.legend(564, [("swatch", SERVICE, "STAGE"), ("swatch", rgba(ACCENT, 0.08), "THE EARLY REFUSAL (#153)"), ("swatch", PAPER2, "WHAT IT CAN REFUSE WITH")])
    return f


def booking_pipeline(_=None) -> Figure:
    f = Figure("booking_pipeline", "A booking search, stage by stage", "Pipeline of a booking search inside the client's deadline, from claiming the requestId to publishing holds, with the loop that reruns the search when another booking changed the same day.", 980, 680)
    stages = [("Claim", "REQUESTID + HASH", "400 · 429", False), ("Deadline", "BOOKINGDEADLINEMS ≤ 5 S", None, False), ("Cutoff, metro", "FACTS BUILT", "422", False),
              ("Admission", "A SLOT, FAIR BY CLIENT", "429 BUSY", False), ("Reconcile", "RULES 0 TO 4", "409 STALE", True), ("Route", "THE NEW JOB'S LEGS", "503", False),
              ("Insertion", "PASS 1: THE WINDOWS", None, False), ("Refinement", "PASS 2: OPTIONAL", None, False), ("Validate", "INDEPENDENTLY", None, False), ("Publish", "HOLDS FOR 10 MINUTES", "201", False)]
    w = 268
    boxes = _pipeline(f, stages, 40, 3, w, 48, 40, 92, 156)
    # The rerun loop: from Validate back up to Reconcile, through the gap between the rows and clear of every refusal chip.
    (vx, vy), (rx, ry) = boxes[8], boxes[4]
    gap_y, up_x = vy - 20, rx + w / 2 + 72
    f.route([(vx + w / 2, vy), (vx + w / 2, gap_y), (up_x, gap_y), (up_x, ry + 72)], "muted", "5,4")
    f.label((vx + w / 2 + up_x) / 2, gap_y - 10, "RERUN, AT MOST 3", SOFT, KEY)
    f.legend(644, [("swatch", SERVICE, "STAGE"), ("swatch", rgba(ACCENT, 0.08), "WHERE THE CONCURRENCY TOKEN BITES"), ("swatch", PAPER2, "ANSWER"), ("arrow-dashed", MUTED, "RERUN")],
             note="reruns while the deadline leaves time")
    return f


def isolation(_=None) -> Figure:
    f = Figure("isolation", "Many clients in one database: the guards, layer by layer", "Layer stack of the guards that keep tenants apart: the token decides the tenant, every key starts with the tenant, row-level security fails closed, the runtime login has least privilege, and admission takes turns by client.", 980, 620)
    layers = [("TOKEN", "The token decides the tenant", "A REQUEST NEVER NAMES ONE · REVOKED OR DISABLED IS 401 · TOKENS STORED AS SHA-256"),
              ("KEYS", "Tenant on every row", "TENANT_ID STARTS EVERY KEY AND INDEX · TWO CLIENTS CAN BOTH HAVE TECHNICIAN 42"),
              ("RLS", "Row-level security", "EACH TRANSACTION SETS APP.TENANT_ID · AN UNSET TENANT SEES NOTHING"),
              ("ROLES", "Least privilege", "RUNTIME LOGIN IS SCHEDULER_APP, NEVER THE OWNER · MIGRATIONS RUN AS THE OWNER"),
              ("TURNS", "Fair turns", "ADMISSION AND THE QUEUE START THE CLIENT WAITING LONGEST · NO CLIENT STARVES ANOTHER")]
    x, w, y0, h = 140, 800, 56, 76
    f.arrow(96, y0 + 8, 96, y0 + len(layers) * h - 8, "muted"); f.label(108, y0 - 16, "A REQUEST", SOFT, KEY)
    for i, (tag, name, sub) in enumerate(layers):
        y = y0 + i * h
        focal = tag == "RLS"
        f.rect(x, y, w, h, PAPER); f.rect(x, y, w, h, rgba(ACCENT, 0.08) if focal else (PAPER if i % 2 == 0 else PAPER2), ACCENT if focal else ink(0.12), 1.4 if focal else 1)
        f.tag(x + 16, y + 30, tag, ACCENT if focal else ink(0.40), ACCENT if focal else INK)
        f.name(x + 100, y + 34, name, INK, "start")
        f.tech(x + 100, y + 56, sub, MUTED, "start", KEY)
    f.line(x, y0 + len(layers) * h, x + w, y0 + len(layers) * h, INK, 1)
    f.callout(x, y0 + len(layers) * h + 40, ["A missed filter in application code fails closed: the database itself refuses another tenant's rows.",
                                               "Road legs are not tenant data: they are shared by every client in a metro, kept at", "about 1 m and purged within 30 days."])
    f.legend(584, [("swatch", rgba(ACCENT, 0.08), "THE LAYER THAT FAILS CLOSED"), ("swatch", PAPER2, "GUARD")])
    return f


def replicas(_=None) -> Figure:
    f = Figure("replicas", "What lives in a replica and what lives in the database", "Architecture of three replicas behind the proxy: each keeps only its admission slots, the memory tier of the route cache and its list of running calls, while correctness lives in the shared database.", 980, 660)
    f.node(290, 32, 400, 56, "scheduler-proxy", ["NO STICKY SESSIONS: ANY REPLICA, SAME ANSWER"], SERVICE)
    reps = [("Booking replica", "SCHEDULER_ROLE=BOOKING"), ("Booking replica", "SCHEDULER_ROLE=BOOKING"), ("Daily replica", "SEVERAL CALCULATIONS AT ONCE")]
    starts = (390, 490, 590)
    for i, (name, role) in enumerate(reps):
        x = 40 + i * 308
        cx = x + 142
        f.zone(x, 136, 284, 236, name.upper())
        f.tech(cx, 176, role, MUTED, size=KEY)
        for j, item in enumerate(("Search slots (admission)", "Route cache, memory tier", "Running calls")):
            f.rect(x + 24, 192 + j * 56, 236, 44, PAPER, rx=6); f.rect(x + 24, 192 + j * 56, 236, 44, PAPER2, SOFT, 1, rx=6)
            f.text(cx, 219 + j * 56, item, KEY, INK, SANS, "middle", weight="700")
        sx = starts[i]
        f.route([(sx, 88), (sx, 112), (cx, 112), (cx, 136)] if sx != cx else [(sx, 88), (sx, 136)], "muted")
        f.arrow(cx, 372, cx, 420, "muted")
    f.rect(40, 420, 900, 164, PAPER, rx=6); f.rect(40, 420, 900, 164, rgba(ACCENT, 0.06), ACCENT, 1.4, rx=6)
    f.eyebrow_text(56, 442, "SCHEDULER-DB: WHERE CORRECTNESS LIVES", ACCENT)
    for k, (title, sub) in enumerate((("Row locks + unique keys", "(TENANT, TECHNICIAN, DATE)"), ("Timestamp comparison", "IN ONE TRANSACTION"), ("Request claims", "HASH + 60 S LEASE"),
                                      ("Durable queue", "SKIP LOCKED, TURNS BY CLIENT"), ("Cancel flag", "SEEN BY ANY REPLICA IN ~1 S"))):
        x, y = 64 + (k % 3) * 300, 480 + (k // 3) * 56
        f.text(x, y, title, KEY, INK, SANS, weight="700"); f.tech(x, y + 22, sub, MUTED, "start", KEY)
    f.legend(624, [("swatch", PAPER2, "PER REPLICA, BY DESIGN"), ("swatch", rgba(ACCENT, 0.06), "SHARED, AND WHERE CORRECTNESS LIVES")], note="the role picks background work only")
    return f


def queue_states(_=None) -> Figure:
    f = Figure("queue_states", "A queued proposal's life", "State machine of a daily or repair proposal sent with Prefer: respond-async: accepted 202 and queued, taken in turn by any replica, completed with the answer the call would have given, or completed with 429 after waiting 15 minutes.", 980, 560)
    f.node(40, 196, 170, 80, "POST proposal", ["RESPOND-ASYNC"], HOST)
    f.state(260, 196, 170, 80, "QUEUED", "DURABLE TABLE")
    f.state(500, 196, 170, 80, "RUNNING", "ANY REPLICA")
    f.state(740, 196, 200, 80, "COMPLETED", "THE CALL'S ANSWER", focal=True)
    f.arrow(210, 236, 260, 236, "muted"); f.label(235, 226, "202", SOFT, KEY)
    f.arrow(430, 236, 500, 236, "muted"); f.label(465, 226, "ITS TURN", SOFT, KEY)
    f.arrow(670, 236, 740, 236, "accent"); f.label(705, 226, "DONE", ACCENT, KEY)
    f.route([(345, 276), (345, 360), (840, 360), (840, 276)], "muted", "5,4"); f.label(592, 352, "WAITED 15 MINUTES: COMPLETED WITH 429, NEVER RUN", SOFT, KEY)
    f.route([(125, 196), (125, 120), (300, 120)], "muted", "5,4"); f.label(212, 112, "QUEUE FULL (50)", SOFT, KEY)
    f.rect(300, 104, 120, 32, PAPER, rx=2); f.rect(300, 104, 120, 32, PAPER2, ink(0.40), 0.8, rx=2); f.text(360, 125, "429 AT ONCE", KEY, INK, MONO, "middle")
    f.callout(460, 84, ["A worker takes the waiting request of the client whose", "last one started longest ago, with SKIP LOCKED: thirty", "overnight days of one client never hold back another", "client's single day."])
    lines = _wrap("The host polls GET /requests/{requestId} and reads QUEUED, RUNNING, then COMPLETED with the status and body the call would have answered.", 118) \
        + _wrap("DELETE /requests/{requestId} is a best-effort cancel from any replica; the call then answers 409 CANCELLED.", 118)
    f.para(40, 412, lines, TECH, INK, MONO)
    f.legend(524, [("swatch", SERVICE, "STATE"), ("swatch", rgba(ACCENT, 0.10), "THE ANSWER"), ("arrow-dashed", MUTED, "REFUSAL PATH")])
    return f


def thin_store(tables, stored) -> Figure:
    names = {n.strip("`") for row in tables for n in row[0].replace("`", "").split(", ")}
    expected = {"api_request", "api_daily_proposal", "api_proposal_technician_day", "api_commit_receipt", "api_booking_day", "api_booking_offer_set", "api_booking_offer", "api_booking_receipt"}
    assert names == expected, names ^ expected
    assert len(stored) == 5
    f = Figure("thin_store", "The thin store: what the scheduler keeps, and for how long", "Data model of the scheduler's own tables grouped into request claims, daily proposals and booking, with their relations and the lifetime of each kind of data; everything older than the retention period is purged.", 980, 900)

    def table_box(x, y, name, cols, focal=False, w=248):
        h = 40 + 18 * len(cols)
        f.rect(x, y, w, h, PAPER, rx=6); f.rect(x, y, w, h, rgba(ACCENT, 0.06) if focal else PAPER, ACCENT if focal else SOFT, 1.4 if focal else 1, rx=6)
        f.rect(x, y, w, 26, rgba(ACCENT, 0.12) if focal else SERVICE, rx=6)
        f.text(x + 12, y + 18, name, KEY, INK, MONO, weight="700")
        for i, c in enumerate(cols):
            f.text(x + 12, y + 44 + i * 18, c, KEY, MUTED, MONO)
        return h

    f.zone(40, 40, 280, 176, "REQUESTS")
    table_box(56, 72, "api_request", ["tenant, requestId", "operation, body SHA-256", "claim lease 60 s", "stored response"])
    f.zone(40, 240, 280, 120, "SHARED")
    f.para(56, 288, ["tenants, API token digests", "road route cache (30 days)"], KEY, INK, MONO, lead=22)
    f.zone(350, 40, 280, 400, "DAILY AND REPAIR")
    table_box(366, 72, "api_daily_proposal", ["metro, zone, date", "input revision", "routing identity", "PROPOSED, COMMITTED, STALE"])
    table_box(366, 220, "api_proposal_technician_day", ["proposal, technician, date", "lastModified, exact text"])
    table_box(366, 340, "api_commit_receipt", ["one per proposal", "assignments, timestamps"])
    f.arrow(400, 184, 400, 220, "muted"); f.route([(614, 128), (626, 128), (626, 378), (614, 378)], "muted")
    f.zone(660, 40, 280, 516, "BOOKING")
    table_box(676, 72, "api_booking_day", ["metro-day arrangement", "every active hold", "per technician: timestamp", "versioned; locked on write"], focal=True)
    table_box(676, 216, "api_booking_offer_set", ["what a search offered", "requestId, snapshot"])
    table_box(676, 316, "api_booking_offer", ["HELD, SELECTED, RELEASED,", "SUPERSEDED, LOST, CONFIRMED", "expiry is a time"])
    table_box(676, 456, "api_booking_receipt", ["one per offer", "the confirm's receipt"])
    f.arrow(800, 292, 800, 316, "muted"); f.arrow(800, 410, 800, 456, "muted")
    # Lifetimes.
    f.eyebrow_text(40, 600, "HOW LONG EACH KIND OF DATA LIVES", SOFT)
    f.line(40, 608, 940, 608, ink(0.12), 0.8)
    for i, (what, life) in enumerate(stored):
        y = 634 + i * 24
        f.text(40, y, what, KEY, INK, SANS, weight="700")
        f.text(340, y, life.replace('"', ""), KEY, MUTED, MONO)
    f.callout(40, 784, ["SCHEDULER_RETENTION_DAYS is required with no default: the scheduler will not start", "without it. A daily purge deletes children before parents."])
    f.legend(864, [("swatch", rgba(ACCENT, 0.06), "THE ROW EVERY BOOKING LOCKS"), ("swatch", PAPER, "TABLE"), ("arrow", MUTED, "ONE TO MANY OR ONE TO ONE")], note="tenant_id leads every key")
    return f


def geocode(_=None) -> Figure:
    f = Figure("geocode", "Where a stop's coordinates come from", "Flowchart of locating a stop: coordinates in the snapshot are used as given; an address without coordinates is located by Nominatim only in NOMINATIM mode and only as an exact house; anything else is not located.", 980, 520)
    y = 150
    f.node(40, y - 36, 150, 72, "A location", ["IN THE SNAPSHOT"], HOST)
    f.diamond(310, y, 200, 120, ["LATITUDE AND", "LONGITUDE", "GIVEN?"], focal=True)
    f.diamond(570, y, 180, 120, ["NOMINATIM", "MODE?"])
    f.diamond(810, y, 180, 120, ["ZIP, NUMBER", "AND STREET", "AGREE?"])
    f.arrow(190, y, 210, y, "muted")
    f.arrow(410, y, 480, y, "muted"); f.label(445, y - 10, "NO", SOFT, KEY)
    f.arrow(660, y, 720, y, "muted"); f.label(690, y - 10, "YES", SOFT, KEY)
    outcomes = [(310, "Route as given", "NEVER GEOCODED AGAIN", "YES", True), (570, "Not located", "MODE OFF, OR NOT EXACT", "NO", False), (810, "Located", "AN EXACT HOUSE", "YES", False)]
    for x, name, sub, lab, focal in outcomes:
        f.arrow(x, y + 60, x, 300, "accent" if focal else "muted"); f.label(x + 22, 284, lab, ACCENT if focal else SOFT, KEY)
        f.node(x - 100, 300, 200, 64, name, [sub], rgba(ACCENT, 0.08) if focal else (PAPER2 if name.startswith("Not") else SERVICE), ACCENT if focal else SOFT, 1.4 if focal else 1)
    # A street-level or ambiguous match is not located either: the NO branch joins the same outcome from below.
    f.route([(900, y), (924, y), (924, 412), (590, 412), (590, 364)], "muted")
    f.label(912, y - 10, "NO", SOFT, KEY); f.label(757, 404, "STREET-LEVEL OR AMBIGUOUS", SOFT, KEY)
    f.legend(484, [("swatch", rgba(ACCENT, 0.08), "THE NORMAL PATH"), ("swatch", SERVICE, "LOCATED"), ("swatch", PAPER2, "NOT LOCATED")], note="every stop records its source")
    return f


def map_lifecycle(_=None) -> Figure:
    f = Figure("map_lifecycle", "How a map version is prepared and switched on", "Process of preparing a map version as a whole: the merged OpenStreetMap extract, both road graphs, tiles and TIGER address ranges, validation with preview services, activation by switching symlinks, and the new routing identity that starts every cache cold.", 980, 520)
    steps = [("Extract", ["MERGED OSM"]), ("Road graphs", ["FLEXIBLE + CH", "CH: ~27 S, ~200 MIB MORE"]), ("Tiles", ["FOR DRAWING"]),
             ("TIGER ranges", ["FOR NOMINATIM"]), ("Validate", ["PREVIEW SERVICES"]), ("Activate", ["SWITCH SYMLINKS"])]
    w, gap, h = 268, 48, 88
    cols, rows_y = (40, 356, 672), (48, 208)
    pos = []
    for i, (name, sub) in enumerate(steps):
        row, col = divmod(i, 3)
        x, y = cols[col if row == 0 else 2 - col], rows_y[row]
        pos.append((x, y))
        focal = name == "Activate"
        f.node(x, y, w, h, name, sub, rgba(ACCENT, 0.08) if focal else SERVICE, ACCENT if focal else SOFT, 1.4 if focal else 1, tag=str(i + 1))
    for (ax, ay), (bx, by) in zip(pos, pos[1:]):
        if ay == by:
            f.arrow(ax + w, ay + h / 2, bx, by + h / 2, "muted") if bx > ax else f.arrow(ax, ay + h / 2, bx + w, by + h / 2, "muted")
        else:
            f.arrow(ax + w / 2, ay + h, bx + w / 2, by, "muted")
    ax, ay = pos[-1]
    f.arrow(ax + w / 2, ay + h, ax + w / 2, 352, "accent")
    f.node(ax, 352, 360, 72, "A new routing identity", ["HASH OF GRAPH + MAP VERSION"], rgba(ACCENT, 0.08), ACCENT, 1.4)
    f.para(440, 372, ["Memory tiers are cleared and the database tier is", "never read for the old identity: a changed road", "network is never mixed with old legs, and every", "cache starts cold."], TECH, INK, MONO)
    f.legend(484, [("swatch", SERVICE, "PREPARATION STEP"), ("swatch", rgba(ACCENT, 0.08), "THE SWITCH AND ITS CONSEQUENCE")], note="switching flexible and CH does it too")
    return f
