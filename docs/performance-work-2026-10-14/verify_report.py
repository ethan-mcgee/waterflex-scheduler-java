"""Check delivered tables, plot data and template parts against the raw evidence files.

Recomputes from the JSON under docs/evidence without the builder's or loader's derived fields. Run normally, without
Python's -O flag. Visual pagination still requires rendering.
"""
import json
from hashlib import sha256
from math import isclose
from statistics import median
from zipfile import ZipFile

from docx import Document
from docx.oxml.ns import qn
from build_report import HERE, OUTPUT, REFERENCE, REFERENCE_SHA, PRESERVE

EVIDENCE = HERE.parent / "evidence"
OFFERS, CONFIRM = "POST /api/v1/booking/offers 201", "POST /api/v1/booking/holds/{holdId}/confirm 200"


def read(name):
    return json.loads((EVIDENCE / name).read_text(encoding="utf-8"))


def op(report, name):
    return [o for o in report["operations"] if o["operation"] == name][0]


def pm(report, name="booking offers", status="201"):
    return op(report, name)["statuses"].get(status, 0) * 60 / report["settings"]["durationS"]


def lat(o):
    return f"{o['ms']['p50']:,} / {o['ms']['p95']:,} / {o['ms']['p99']:,}"


def verify():
    base = read("api-load-2026-10-13.json"); conc = read("api-load-2026-10-13-booking-concurrency.json")
    adm = read("api-load-2026-10-13-daily-admission.json"); out = read("api-load-2026-10-13-search-outcome.json")
    shed = read("api-load-2026-10-13-refinement-shedding.json"); queue = read("api-load-2026-10-13-daily-queue.json")
    roles = read("api-load-2026-10-14-replica-roles.json"); vm = read("api-load-2026-10-14-vm-baseline.json"); routing = read("api-load-2026-10-14-routing.json")
    bench = {"2026-09-24": read("routing-ch-omaha-2026-09-24.json"), "2026-10-14": read("routing-ch-2026-10-14.json")}
    by = lambda doc, label: [r for r in doc["runs"] if r["label"] == label][0]
    b = {n: by(base, f"booking-cold-c{n}") for n in (1, 5, 10)}; b_warm, b_mixed = by(base, "booking-warm-c5"), by(base, "mixed-c5-d1")
    c = {n: by(conc, f"booking-cold-c{n}") for n in (1, 5, 10)}; c_warm, c_mixed = by(conc, "booking-warm-c5"), by(conc, "mixed-c5-d1")
    fresh = {n: [r for r in conc["coldRemeasurement"]["runs"] if r["settings"]["concurrency"] == n][0] for n in (1, 5, 10)}
    shed5 = by(shed, "after-c5 (seed 55)"); outcome5 = by(out, "booking-cold-c5")
    vmr = {r["label"]: r for r in vm["runs"]}; jr = {r["label"]: r for r in routing["runs"]}; rr = {r["label"]: r for r in roles["runs"]}
    vm_b, vm_d = vmr["base-spread2"]["booking"], vmr["base-spread2"]["daily"]
    jf_b, jc_b = jr["j-flex"]["booking"], jr["j-ch"]["booking"]
    bd = {label: vmr[label]["serverBreakdown"] if label.startswith("base") else jr[label]["serverBreakdown"] for label in ("base-spread", "base-spread2", "j-flex", "j-ch")}

    doc = Document(OUTPUT)
    assert len(doc.tables) == 24 and len(doc.inline_shapes) == 8 and len(doc.sections) == 1
    assert len([p for p in doc.paragraphs if p.style.name == "Heading 1"]) == 16
    captions = [p for p in doc.paragraphs if p.style.name == "Caption"]
    assert len(captions) == 8
    for i, p in enumerate(captions, 1):
        assert p.text.startswith(f"Figure {i}.") and all(str(r.font.color.rgb) == "000000" and r.italic for r in p.runs)

    def check(index, expected):
        actual = [[c.text for c in r.cells] for r in doc.tables[index].rows[1:]]
        assert actual == [[str(c) for c in r] for r in expected], (index, actual, expected)

    ms = lambda v: f"{v:,} ms"; sec = lambda v: f"{v / 1000:.1f} s"
    check(0, [["#151", "Load tool; the first measurement", f"{pm(b[5]):.0f}", ms(op(b[5], 'booking offers')['ms']['p95'])],
              ["#152", "Late snapshots keep holds; bookings publish without waiting", f"{pm(fresh[5]):.0f}", ms(op(fresh[5], 'booking offers')['ms']['p95'])],
              ["#154", "searchComplete means what the spec says", f"{pm(outcome5):.0f}", ms(op(outcome5, 'booking offers')['ms']['p95'])],
              ["#155", "Optional refinement shed while bookings wait", f"{pm(shed5):.0f}", ms(op(shed5, 'booking offers')['ms']['p95'])],
              ["J0 (#159)", "VM shape, daily planners beside the desks, time attributed", f"{pm(vm_b):.0f}", ms(op(vm_b, 'booking offers')['ms']['p95'])],
              ["J (#160)", "CH graph, parallel pairs, identity cache", f"{pm(jc_b):.0f}", ms(op(jc_b, 'booking offers')['ms']['p95'])]])
    check(6, [[name, f"{pm(r):.0f}", lat(op(r, "booking offers")), f"{op(r, 'confirm')['ms']['p50']:,} / {op(r, 'confirm')['ms']['p95']:,}", f"{r['booking']['holdUnavailable']} of {r['booking']['selected']}", op(r, "booking offers")["retriedBusy"]]
              for name, r in (("cold, 1", b[1]), ("cold, 5", b[5]), ("cold, 10", b[10]), ("warm, 5", b_warm), ("mixed 5 desks + 1 planner", b_mixed))])
    check(7, [[name, r["daily"]["proposals"], op(r, "daily proposal")["statuses"].get("201", 0), op(r, "daily proposal")["statuses"].get("503", 0), sec(op(r, "daily proposal")["ms"]["p50"])]
              for name, r in (("1 planner", by(base, "daily-c1")), ("4 planners", by(base, "daily-c4")), ("1 planner beside 5 desks", b_mixed))])
    check(9, [[name, f"{x['booking']['holdUnavailable']} of {x['booking']['selected']}", f"{y['booking']['holdUnavailable']} of {y['booking']['selected']}", y["booking"]["stale"], f"{y['booking']['stale'] / y['booking']['searches']:.2f}"]
              for name, x, y in (("1 desk", b[1], c[1]), ("5 desks", b[5], c[5]), ("10 desks", b[10], c[10]), ("5 desks, warm", b_warm, c_warm), ("mixed", b_mixed, c_mixed))])
    check(10, [[n, sec(op(b[n], "booking offers")["ms"]["p95"]), sec(op(fresh[n], "booking offers")["ms"]["p95"]), f"{pm(b[n]):.0f}", f"{pm(fresh[n]):.0f}",
                f"{op(b[n], 'booking offers')['retriedBusy']} / {op(fresh[n], 'booking offers')['retriedBusy']}"] for n in (1, 5, 10)])
    check(11, [[name, f"{pm(r):.0f}", ms(op(r, "booking offers")["ms"]["p50"]), ms(op(r, "booking offers")["ms"]["p95"]), ", ".join(f"{k} {v}" for k, v in sorted(r["booking"]["refinements"].items())), r["booking"]["searchIncomplete"]]
               for name, r in zip(("#154", "#155"), shed["abComparison"])])
    check(12, [[name, r["daily"]["proposals"], op(r, "daily proposal")["statuses"].get("201", 0), op(r, "daily proposal")["statuses"].get("503", 0), r["server"]["admissionRejections"], sec(op(r, "daily proposal")["ms"]["p50"])]
               for name, r in (("1 planner", by(adm, "daily-c1")), ("4 planners", by(adm, "daily-c4")), ("1 planner beside 5 desks", by(adm, "mixed-c5-d1")))])
    fair = {(m, r["settings"]["concurrency"]): r for m in ("sync", "async") for r in queue["fairness"][m]}
    waits = lambda r: (median(r["daily"]["queueWaitsMs"]), max(r["daily"]["queueWaitsMs"]))
    check(13, [[mode, f"{t}, {n} planner{'s' if n > 1 else ''}", fair[(m, n)]["daily"]["proposals"], sec(op(fair[(m, n)], "daily proposal")["ms"]["p50"]), sec(op(fair[(m, n)], "daily proposal")["ms"]["p95"]),
                f"p50 {sec(waits(fair[(m, n)])[0])}, max {sec(waits(fair[(m, n)])[1])}" if m == "async" else "not queued"]
               for m, mode in (("sync", "Synchronous, 429 and Retry-After"), ("async", "Queued")) for t, n in (("A", 4), ("B", 1))])
    mixed = {r["label"]: r for r in queue["mixed"]["final"]}
    check(14, [[name, f"{pm(r):.0f}", ms(op(r, "booking offers")["ms"]["p95"]), r["daily"]["proposals"], sec(op(r, "daily proposal")["ms"]["p95"])] for name, r in (("Synchronous", mixed["mixed sync v2"]), ("Queued", mixed["mixed async v2"]))])
    check(15, [[name, f"{pm(rr[run]['booking']):.0f}", f"{op(rr[run]['booking'], 'booking offers')['ms']['p95']:,} / {op(rr[run]['booking'], 'booking offers')['ms']['p99']:,}", op(rr[run]["booking"], "booking offers")["retriedBusy"],
                f"{op(rr[run]['booking'], 'confirm')['ms']['p50']} / {op(rr[run]['booking'], 'confirm')['ms']['p95']}", rr[run]["daily"]["daily"]["proposals"],
                f"{op(rr[run]['daily'], 'daily proposal')['ms']['p50'] / 1000:.1f} / {op(rr[run]['daily'], 'daily proposal')['ms']['p95'] / 1000:.1f} s", sec(waits(rr[run]["daily"])[0])]
               for run, name in (("all-1", "One all replica"), ("split-1", "Booking + daily"), ("split-2", "Booking + daily"), ("all-2", "One all replica"))])
    o, cf = bd["base-spread2"][OFFERS], bd["base-spread2"][CONFIRM]
    check(16, [["Offers (201)", o["calls"], f"{op(vm_b, 'booking offers')['ms']['p50']} / {op(vm_b, 'booking offers')['ms']['p95']} ms", ms(o["elapsedMs"]["p50"]), ms(o["queueMs"]["p50"]), ms(o["routingMs"]["p50"]), ms(o["calculationMs"]["p50"]), ms(o["dbMs"]["p50"]), f"{o['routingPairs']['p50']} / {o['fetched']['p50']}"],
               ["Confirm (200)", cf["calls"], f"{op(vm_b, 'confirm')['ms']['p50']} / {op(vm_b, 'confirm')['ms']['p95']} ms", ms(cf["elapsedMs"]["p50"]), ms(cf["queueMs"]["p50"]), ms(cf["routingMs"]["p50"]), ms(cf["calculationMs"]["p50"]), ms(cf["dbMs"]["p50"]), f"{cf['routingPairs']['p50']} / {cf['fetched']['p50']}"],
               ["Daily, queued", vm_d["daily"]["proposals"], f"{op(vm_d, 'daily proposal')['ms']['p50'] / 1000:.1f} / {op(vm_d, 'daily proposal')['ms']['p95'] / 1000:.1f} s", "", f"queue {sec(waits(vm_d)[0])}", "", "5 s search", "", ""]])
    rows = []
    for date, doc_ in bench.items():
        for i, e in enumerate(("flexible", "CH-car-v1")):
            l = {x["concurrency"]: x for x in doc_["latency"] if x["engine"] == e}
            rows.append([f"{doc_['engines'][0]['mapVersion']}, {date}", "Flexible" if e == "flexible" else "CH", f"{doc_['firstRequestMs'][i]:,.0f} ms", *[f"{l[cc]['p50Ms']:.1f} / {l[cc]['p95Ms']:.1f} ms" for cc in (1, 5, 10)]])
    check(17, rows)
    cpu = lambda r: f"{[x for x in r['containers'] if 'routing' in x['name']][0]['cpuPercent']['mean']:.0f}%"
    check(18, [[name, r["booking"]["searches"], f"{op(r, 'booking offers')['ms']['p50']} / {op(r, 'booking offers')['ms']['p95']} ms", ms(e[OFFERS]["elapsedMs"]["p50"]), ms(e[OFFERS]["queueMs"]["p50"]), ms(e[OFFERS]["routingMs"]["p50"]),
                f"{e[OFFERS]['routingMs']['p50'] / e[OFFERS]['fetched']['p50']:.2f} ms", f"{op(r, 'confirm')['ms']['p50']} / {op(r, 'confirm')['ms']['p95']} ms", cpu(r)]
               for name, r, e in (("J0 baseline, flexible", vm_b, bd["base-spread2"]), ("J, flexible graph", jf_b, bd["j-flex"]), ("J, CH graph", jc_b, bd["j-ch"]))])
    booking_runs = [("#151", r) for r in base["runs"] if r["booking"]] + [("#152", r) for r in conc["runs"] + conc["coldRemeasurement"]["runs"] if r["booking"]] \
        + [("#153", r) for r in adm["runs"] if r["booking"]] + [("#154", r) for r in out["runs"]] + [("#155", r) for r in shed["runs"] + shed["abComparison"]] \
        + [("#157", r) for r in queue["fairness"]["sync"] + queue["fairness"]["async"] + queue["mixed"]["final"] if r["booking"]] \
        + [(pr, r["booking"]) for pr, doc_ in (("#158", roles), ("#159", vm), ("#160", routing)) for r in doc_["runs"]]
    check(20, [[pr, r["label"][:30], r["settings"]["concurrency"], r["settings"]["seed"], r["booking"]["searches"], lat(op(r, "booking offers")), f"{op(r, 'confirm')['ms']['p50']} / {op(r, 'confirm')['ms']['p95']}",
                f"{r['booking']['holdUnavailable']}/{r['booking']['selected']}", r["booking"].get("stale", 0), op(r, "booking offers")["retriedBusy"]] for pr, r in booking_runs])
    daily_runs = [("#151", r) for r in base["runs"] if r["daily"]] + [("#152", r) for r in conc["runs"] if r["daily"]] + [("#153", r) for r in adm["runs"] if r["daily"]] \
        + [("#157", r) for r in queue["fairness"]["sync"] + queue["fairness"]["async"] + queue["mixed"]["final"] if r["daily"]] \
        + [(pr, r["daily"]) for pr, doc_ in (("#158", roles), ("#159", vm), ("#160", routing)) for r in doc_["runs"]]
    check(21, [[pr, r["label"][:30], r["settings"].get("dailyConcurrency") or r["settings"]["concurrency"], r["settings"].get("dailyMode") or "sync", r["settings"]["seed"], r["daily"]["proposals"], op(r, "daily proposal")["statuses"].get("201", 0),
                f"{op(r, 'daily proposal')['ms']['p50'] / 1000:.1f} / {op(r, 'daily proposal')['ms']['p95'] / 1000:.1f} s", sec(waits(r)[0]) if r["daily"].get("queueWaitsMs") else ""] for pr, r in daily_runs])
    rows = []
    for name, label in (("J0 base-spread", "base-spread"), ("J0 base-spread2", "base-spread2"), ("J flexible", "j-flex"), ("J CH", "j-ch")):
        for key, e in sorted(bd[label].items()):
            if "daily" in key or "release" in key or "select" in key:
                continue
            rows.append([name, key.replace("POST /api/v1/booking/", "").replace("holds/{holdId}/", ""), e["calls"], e["elapsedMs"]["p50"], e.get("queueMs", {}).get("p50", ""), e["routingMs"]["p50"], e["calculationMs"]["p50"], e["dbMs"]["p50"], e["routingPairs"]["p50"], e["cacheHits"]["p50"], e["fetched"]["p50"]])
    check(22, rows)

    data = json.loads((HERE / "generated/chart_data.json").read_text())

    def close(actual, expected):
        assert len(actual) == len(expected), (actual, expected)
        assert all(isclose(a, e, rel_tol=1e-10, abs_tol=1e-10) for a, e in zip(actual, expected)), (actual, expected)

    close([d["searches_per_minute"] for d in data["progression"]], [pm(r) for r in (b[5], fresh[5], outcome5, shed5, vm_b, jf_b, jc_b)])
    close([d["p95"] for d in data["progression"]], [op(r, "booking offers")["ms"]["p95"] for r in (b[5], fresh[5], outcome5, shed5, vm_b, jf_b, jc_b)])
    shed_by = {r["settings"]["concurrency"]: r for r in shed["runs"]}
    for d, rs in zip(data["desks"], ({1: b[1], 5: b[5], 10: b[10]}, fresh, shed_by)):
        close(d["p95"], [op(rs[n], "booking offers")["ms"]["p95"] for n in (1, 5, 10)])
    close([d["lost_before"] for d in data["holds"]], [r["booking"]["holdUnavailable"] for r in (b[1], b[5], b[10], b_warm, b_mixed)])
    close([d["stale_after"] for d in data["holds"]], [r["booking"]["stale"] for r in (c[1], c[5], c[10], c_warm, c_mixed)])
    close([d["answered_201"] for d in data["daily_admission"]], [op(r, "daily proposal")["statuses"].get("201", 0) for r in (by(base, "daily-c1"), by(adm, "daily-c1"), by(base, "daily-c4"), by(adm, "daily-c4"))])
    close([d["proposals"] for d in data["daily_fairness"]], [fair[(m, n)]["daily"]["proposals"] for m in ("sync", "async") for n in (4, 1)])
    close([d["proposals"] for d in data["daily_roles"]], [rr[run]["daily"]["daily"]["proposals"] for run in ("all-1", "split-1", "split-2", "all-2")])
    for d in data["attribution"]:
        e = bd[{"J0": "base-spread2", "J flexible": "j-flex", "J CH": "j-ch"}[d["stage"]]][d["operation"]]
        close([d[k] for k in ("queueMs", "routingMs", "calculationMs", "dbMs", "elapsed_p50")], [e[k]["p50"] for k in ("queueMs", "routingMs", "calculationMs", "dbMs", "elapsedMs")])
    for d in data["ch"]:
        close(d["p50"], [x["p50Ms"] for x in bench[d["date"]]["latency"] if x["engine"] == d["engine"]])
    close([d["routing_cpu_mean"] for d in data["cpu"]], [[x for x in r["containers"] if "routing" in x["name"]][0]["cpuPercent"]["mean"] for r in (vm_b, jf_b, jc_b)])
    close([d["offers_p95"] for d in data["roles"]], [op(rr[run]["booking"], "booking offers")["ms"]["p95"] for run in ("all-1", "split-1", "split-2", "all-2")])

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
    print(f"PASS: {len(booking_runs)} booking runs, {len(daily_runs)} daily runs, 2 routing benchmarks; 16 numerical tables; eight chart datasets and images; template parts.")


if __name__ == "__main__":
    verify()
