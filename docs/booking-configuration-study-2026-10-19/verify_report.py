"""Check delivered tables, plot data and template parts against the raw evidence files.

Recomputes from the gzipped JSON under docs/evidence with its own loops, without report_data.py or the summarizer's
pooling; only the number formatting is shared with the builder. Run normally, without Python's -O flag. Visual
pagination still requires rendering.
"""
import gzip
import json
from collections import defaultdict
from datetime import datetime
from hashlib import sha256
from math import isclose
from statistics import fmean, median
from zipfile import ZipFile

from docx import Document
from docx.oxml.ns import qn
from build_report import HERE, OUTPUT, PRESERVE, REFERENCE, REFERENCE_SHA, money, num, secs

EVIDENCE = HERE.parent / "evidence"
OFFERS = "POST /api/v1/booking/offers 201"
FLEETS = (5, 10, 15, 20, 30)
LAYOUTS = ("1x2", "2x1", "1x3", "3x1", "1x4", "2x2", "4x1")


def read(name):
    with gzip.open(EVIDENCE / name, "rt", encoding="utf-8-sig") as handle:
        return json.load(handle)


def op(report, name):
    found = [o for o in report["operations"] if o["operation"] == name]
    assert len(found) == 1, (report["label"], name)
    return found[0]


def seconds(report):
    stamp = lambda s: datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()
    return stamp(report["finishedAt"]) - stamp(report["startedAt"])


def pooled(reports, field, stat):
    """A breakdown statistic over reports, each report's value weighted by its offers calls."""
    pairs = [(r["serverBreakdown"][OFFERS][field][stat], r["serverBreakdown"][OFFERS]["calls"]) for r in reports
             if r.get("serverBreakdown") and OFFERS in r["serverBreakdown"] and r["serverBreakdown"][OFFERS].get(field) is not None]
    return None if not pairs else sum(v * w for v, w in pairs) / sum(w for _, w in pairs)


def desk(reports):
    searches = sum(r["booking"]["searches"] for r in reports)
    offers = [op(r, "booking offers") for r in reports]
    refinements = defaultdict(int)
    for r in reports:
        for k, v in r["booking"]["refinements"].items(): refinements[k] += v
    answered = sum(r["booking"]["searches"] - r["booking"]["failedSearches"] for r in reports)
    return {"searches": searches, "perMinute": searches / (sum(seconds(r) for r in reports) / 60),
            **{k: fmean(o["ms"][k] for o in offers if o["ms"]) for k in ("p50", "p95", "p99")},
            "busy": sum(o["retriedBusy"] for o in offers), "unavailable": sum(o["statuses"].get("503", 0) for o in offers),
            "incomplete": sum(r["booking"]["searchIncomplete"] for r in reports),
            "shed": None if answered == 0 else 100 * refinements.get("SHED", 0) / answered,
            "engine": pooled(reports, "calculationMs", "p50"), "routing": pooled(reports, "routingMs", "p50"),
            "commit50": pooled(reports, "commitMs", "p50"), "commit95": pooled(reports, "commitMs", "p95")}


def stop_text(counts):
    return ", ".join(f"{k} {v}" for k, v in sorted(counts.items())) or "none"


def verify():
    first, rerun = read("api-load-2026-10-19-booking-configuration.json.gz"), read("api-load-2026-10-19-booking-configuration-full.json.gz")
    doc = Document(OUTPUT)
    assert len(doc.tables) == 27 and len(doc.inline_shapes) == 11 and len(doc.sections) == 1
    assert len([p for p in doc.paragraphs if p.style.name == "Heading 1"]) == 18
    captions = [p for p in doc.paragraphs if p.style.name == "Caption"]
    assert len(captions) == 11
    for i, p in enumerate(captions, 1):
        assert p.text.startswith(f"Figure {i}.") and all(str(r.font.color.rgb) == "000000" and r.italic for r in p.runs)

    def check(index, expected):
        actual = [[c.text for c in r.cells] for r in doc.tables[index].rows[1:]]
        assert actual == [[str(c) for c in r] for r in expected], (index, actual[:3], expected[:3])

    # Section 4: every variant at threshold 2 and the reference on full schedules, recomputed case by case from the rerun.
    quality = {}
    for cell in (c for c in rerun["cells"] if c["part"] == "quality"):
        for report in cell["reports"]:
            for case in report["cases"]:
                by = {e["variant"]: e for e in case["searched"]}
                ref = by["1000"]
                for name, e in by.items():
                    t = quality.setdefault((cell["fleet"], name), {"changes": [], "cheaper": 0, "dearer": 0, "same": 0, "ms": [], "stops": defaultdict(int), "incomplete": 0})
                    if e["status"] != 201:
                        continue
                    if e.get("ms") is not None: t["ms"].append(e["ms"])
                    if e.get("refinementStop"): t["stops"][e["refinementStop"]] += 1
                    if e.get("searchComplete") is False: t["incomplete"] += 1
                    if name == "1000" or ref["status"] != 201:
                        continue
                    key = lambda offers: sorted(f"{o['serviceDate']} {o['start']} {o['end']}" for o in offers)
                    t["same"] += key(e["offers"]) == key(ref["offers"])
                    if e.get("priced") and ref.get("priced"):
                        change = e["priced"]["addedCostCents"] - ref["priced"]["addedCostCents"]
                        t["changes"].append(change); t["cheaper"] += change < 0; t["dearer"] += change > 0
    rows = []
    for f in FLEETS:
        for v in ("2:0", "2:250", "2:1000", "2:2000", "2:3000"):
            t = quality[(f, v)]
            rows.append([f, v, money(fmean(t["changes"])), money(median(t["changes"])), f"{t['cheaper']} / {t['dearer']}", t["same"], num(median(t["ms"])), stop_text(t["stops"])])
        t = quality[(f, "1000")]
        rows.append([f, "1000 (ref)", "", "", "", "", num(median(t["ms"])), f"{stop_text(t['stops'])}; incomplete {t['incomplete']}"])
    check(7, rows)

    # Section 8: one desk on full schedules, pooled over the two repeats.
    load = defaultdict(list)
    for cell in (c for c in first["cells"] if c["part"] == "load"):
        load[(cell["fleet"], cell["profile"], cell["variant"])].extend(cell["reports"])
    rows = []
    for f in FLEETS:
        for v in ("2:0", "2:250", "2:1000", "2:3000", "1000"):
            d = desk(load[(f, "full", v)])
            rows.append([f, v, num(d["perMinute"], 1), num(d["p50"]), num(d["p95"]), num(d["p99"]), num(d["engine"]), num(d["routing"]),
                         f"{num(d['commit50'])} / {num(d['commit95'])}", f"{d['incomplete']} of {d['searches']}"])
    check(12, rows)

    # Section 9: the layout grids at 10 and 5 clients, each client's run counted on its own and the clients summed.
    capacity = {}
    for cell in (c for c in first["cells"] if c["part"] == "capacity"):
        lay = cell["layout"]
        reports = cell["reports"]
        answered = sum(r["booking"]["searches"] - r["booking"]["failedSearches"] for r in reports)
        capacity[(cell["fleet"], cell["profile"], cell["variant"], f"{lay['replicas']}x{lay['slots']}", cell["clients"])] = {
            "threads": lay["replicas"] * lay["cpus"], "perMinute": sum(r["booking"]["searches"] / (seconds(r) / 60) for r in reports),
            "p95": fmean(op(r, "booking offers")["ms"]["p95"] for r in reports if op(r, "booking offers")["ms"]),
            "shed": None if answered == 0 else 100 * sum(r["booking"]["refinements"].get("SHED", 0) for r in reports) / answered,
            "unavailable": sum(op(r, "booking offers")["statuses"].get("503", 0) for r in reports)}
    for index, clients in ((14, 10), (15, 5)):
        rows = []
        for l in LAYOUTS:
            a, b, c = (capacity[(10, "full", v, l, clients)] for v in ("2:250", "2:3000", "1000"))
            rows.append([l, a["threads"], num(a["perMinute"]), secs(a["p95"]), f"{num(a['shed'])}%", num(b["perMinute"]), secs(b["p95"]), f"{num(b['shed'])}%",
                         num(c["perMinute"]), c["unavailable"]])
        check(index, rows)

    # Section 10: the 800 ms reserve arm of the rerun beside the first study's rate at the 1000 ms default.
    reserve = defaultdict(list)
    for cell in (c for c in rerun["cells"] if c["part"] == "reserve" and c["variant"] is not None):
        reserve[(cell["fleet"], cell["variant"], cell["reserveMs"])].extend(cell["reports"])
    rows = []
    for f in FLEETS:
        for v in ("1000", "2:3000"):
            d = desk(reserve[(f, v, 800)])
            rows.append([f, v, f"{num(d['commit50'])} / {num(d['commit95'])}", num(d["perMinute"], 1), num(desk(load[(f, "full", v)])["perMinute"], 1),
                         d["busy"], d["unavailable"], d["incomplete"]])
    check(16, rows)

    # Chart datasets against the same recomputation.
    data = json.loads((HERE / "generated" / "chart_data.json").read_text(encoding="utf-8"))

    def close(actual, expected):
        assert len(actual) == len(expected), (actual, expected)
        assert all(isclose(a, e, rel_tol=1e-9, abs_tol=1e-9) for a, e in zip(actual, expected)), (actual, expected)

    for d in data["gap"]:
        close(d["meanCents"], [fmean(quality[(d["fleet"], f"2:{c}")]["changes"]) for c in d["caps"]])
        close(d["dearer"], [quality[(d["fleet"], f"2:{c}")]["dearer"] for c in d["caps"]])
    for d in data["left"]:
        close([d[v] for v in ("2:250", "2:2000", "2:3000")], [fmean(quality[(d["fleet"], v)]["changes"]) for v in ("2:250", "2:2000", "2:3000")])
    assert data["focal"]["meanCents"] == max(fmean(quality[(f, "2:250")]["changes"]) for f in FLEETS)
    for d in data["desk"]:
        close(d["perMinute"], [desk(load[(f, "full", d["variant"])])["perMinute"] for f in FLEETS])
        close(d["p95"], [desk(load[(f, "full", d["variant"])])["p95"] for f in FLEETS])
    for d in data["routing"]:
        close(d["routingP50"], [desk(load[(f, "full", d["variant"])])["routing"] for f in FLEETS])
    for d in data["layouts"]:
        close(d["perMinuteAll"], [capacity[(10, "full", d["variant"], l, 10)]["perMinute"] for l in LAYOUTS])
        close(d["unavailable"], [capacity[(10, "full", d["variant"], l, 10)]["unavailable"] for l in LAYOUTS])
    for d in data["reserve"]:
        close([d["commitP95"]], [max(desk(reserve[(d["fleet"], v, 800)])["commit95"] for v in ("1000", "2:3000"))])
        close([d["perMinute800"][v] for v in ("2:3000", "1000")], [desk(reserve[(d["fleet"], v, 800)])["perMinute"] for v in ("2:3000", "1000")])
    commit30 = max(desk(reserve[(30, v, 800)])["commit95"] for v in ("1000", "2:3000"))
    assert [d["reserveMs"] for d in data["budget"]] == [250, 500, 800, 1000]
    for d in data["budget"]:
        close([d["commitP95at30"], d["guard"]], [commit30, 500])
        assert d["fits"] == (500 + commit30 <= d["reserveMs"])
    assert data["pipeline"] == {"deadlineMs": 5000, "reserveMs": 1000, "guardMs": 500}

    assert sha256(REFERENCE.read_bytes()).hexdigest() == REFERENCE_SHA
    with ZipFile(REFERENCE) as reference, ZipFile(OUTPUT) as built:
        for name in (*PRESERVE, *[n for n in reference.namelist() if "footer" in n]):
            assert reference.read(name) == built.read(name), name
        for name in built.namelist():
            if name.endswith(".xml"):
                text = built.read(name).decode("utf-8")
                assert "—" not in text and "–" not in text, name
        embedded = {sha256(built.read(n)).hexdigest() for n in built.namelist() if n.startswith("word/media/")}
        assert embedded == {sha256(p.read_bytes()).hexdigest() for p in (HERE / "generated").glob("*.png")}
    assert doc.sections[0]._sectPr.xml == Document(REFERENCE).sections[0]._sectPr.xml
    assert all(shape._inline.docPr.get("descr") for shape in doc.inline_shapes)
    assert all(row._tr.get_or_add_trPr().find(qn("w:cantSplit")) is not None for t in doc.tables for row in t.rows)
    print(f"PASS: {len(quality)} quality cells, {len(load)} one-desk cells, {len(capacity)} capacity runs, {len(reserve)} reserve cells recomputed; "
          f"5 numerical tables; seven chart datasets; template parts.")


if __name__ == "__main__":
    verify()
