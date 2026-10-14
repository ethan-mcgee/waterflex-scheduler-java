"""Build the performance work report from the validated load studies and the selected explainer.

Install requirements.txt first. No network, services or benchmark execution.
"""
from copy import deepcopy
from datetime import datetime
from hashlib import sha256
from io import BytesIO
from pathlib import Path
from statistics import median
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_CELL_VERTICAL_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor

from charts import by_label, by_run, make_charts
from report_data import STUDIES, load, per_minute, queue_wait

HERE = Path(__file__).resolve().parent
REFERENCE = HERE.parent / "WaterFlex_Scheduler_Algorithm_Comparison_Explained.docx"
REFERENCE_SHA = "5366f3017d6f18c15de295ba5fb9c1a014b6222911040f1c5e044845e57f832d"
OUTPUT = HERE / "Performance_Work_2026-10-14_Report.docx"
PRESERVE = ("word/styles.xml", "word/stylesWithEffects.xml", "word/numbering.xml", "word/theme/theme1.xml", "word/fontTable.xml")


def ms(value):
    return f"{value:,} ms"


def seconds(value_ms):
    return f"{value_ms / 1000:.1f} s"


def pct(part, whole):
    return f"{part / whole * 100:.0f}%"


def lat(op):
    return f"{op['ms']['p50']:,} / {op['ms']['p95']:,} / {op['ms']['p99']:,}"


def build():
    assert sha256(REFERENCE.read_bytes()).hexdigest() == REFERENCE_SHA
    runs, extras, benchmarks = load()
    assets = HERE / "generated"
    make_charts(runs, extras, benchmarks, assets)
    doc = Document(REFERENCE)
    table_properties = deepcopy(doc.tables[0]._tbl.tblPr)
    for child in list(doc.element.body):
        if child.tag != qn("w:sectPr"): doc.element.body.remove(child)
    for rid, rel in list(doc.part.rels.items()):
        if rel.reltype.endswith(("/image", "/hyperlink")): del doc.part.rels[rid]
    doc.core_properties.title = "WaterFlex scheduler performance work explained"
    doc.core_properties.subject = "How the public API's speed under load was measured and improved, and why, 2026-10-13 to 2026-10-14"
    doc.core_properties.author = doc.core_properties.last_modified_by = "WaterFlex"
    doc.core_properties.created = doc.core_properties.modified = datetime(2026, 10, 14)
    figures = []

    def p(text, style=None):
        assert "—" not in text and "–" not in text
        para = doc.add_paragraph(text, style); para.paragraph_format.widow_control = True
        return para

    def heading(text):
        h = p(text, "Heading 1"); h.paragraph_format.page_break_before = True

    def sub(text): p(text, "Heading 2")

    def table(headers, data, widths, size=10):
        assert abs(sum(widths) - 6.9) < .01
        t = doc.add_table(rows=1, cols=len(headers)); t.autofit = False; t.alignment = WD_TABLE_ALIGNMENT.CENTER
        t._tbl.replace(t._tbl.tblPr, deepcopy(table_properties))
        for col, w in zip(t.columns, widths): col.width = Inches(w)
        for i, values in enumerate([headers, *data]):
            assert len(values) == len(headers)
            row = t.rows[0] if i == 0 else t.add_row()
            trpr = row._tr.get_or_add_trPr(); trpr.append(OxmlElement("w:cantSplit"))
            if i == 0: trpr.append(OxmlElement("w:tblHeader"))
            for j, (cell, value, width) in enumerate(zip(row.cells, values, widths)):
                cell.width = Inches(width); cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER; cell.text = str(value)
                props = cell._tc.get_or_add_tcPr()
                shade = OxmlElement("w:shd"); shade.set(qn("w:fill"), "DCE6EF" if i == 0 else ("FFFFFF" if i % 2 else "F4F6F8")); props.append(shade)
                margins = OxmlElement("w:tcMar")
                for side in ("top", "left", "bottom", "right"):
                    n = OxmlElement("w:" + side); n.set(qn("w:w"), "65"); n.set(qn("w:type"), "dxa"); margins.append(n)
                props.append(margins)
                para = cell.paragraphs[0]; para.paragraph_format.space_after = Pt(2); para.paragraph_format.space_before = Pt(2); para.paragraph_format.line_spacing = 1.0
                if len(headers) > 2 and j > 0: para.alignment = 1
                if len(data) <= 12 and i < len(data): para.paragraph_format.keep_with_next = True  # Short tables stay on one page.
                for run in para.runs: run.font.name = "Calibri"; run.font.size = Pt(size); run.bold = i == 0
        spacer = p(""); spacer.paragraph_format.space_after = Pt(0); spacer.paragraph_format.line_spacing = Pt(3)

    def figure(name, caption):
        figures.append(name)
        caption = f"Figure {len(figures)}. {caption}"
        para = p(""); shape = para.add_run().add_picture(str(assets / f"{name}.png"), width=Inches(6.85))
        shape._inline.docPr.set("descr", caption); para.paragraph_format.keep_with_next = True
        cap = p(caption, "Caption"); cap.paragraph_format.space_after = Pt(7)
        for run in cap.runs:
            run.font.color.rgb = RGBColor(0, 0, 0); run.bold = False; run.italic = True

    # The runs the narrative refers to by name.
    b = {c: by_label(runs["baseline"], f"booking-cold-c{c}") for c in (1, 5, 10)}
    b_warm, b_mixed = by_label(runs["baseline"], "booking-warm-c5"), by_label(runs["baseline"], "mixed-c5-d1")
    b_daily = {c: by_label(runs["baseline"], f"daily-c{c}") for c in (1, 4)}
    c = {n: by_label(runs["concurrency"], f"booking-cold-c{n}") for n in (1, 5, 10)}
    c_warm, c_mixed = by_label(runs["concurrency"], "booking-warm-c5"), by_label(runs["concurrency"], "mixed-c5-d1")
    fresh = {n: by_label(extras["coldRemeasurement"], f"cold-recheck-c{n} (seed {s})") for n, s in ((1, 93), (5, 91), (10, 92))}
    a_daily = {n: by_label(runs["admission"], f"daily-c{n}") for n in (1, 4)}
    a_mixed = by_label(runs["admission"], "mixed-c5-d1")
    outcome = by_label(runs["outcome"], "booking-cold-c5")
    shed = {n: by_label(runs["shedding"], f"after-c{n} (seed {s})") for n, s in ((1, 51), (5, 55), (10, 60))}
    ab_before, ab_after = extras["abComparison"]
    fair = {(r["note"], r["desks"]): r for r in runs["queue"] if r["note"] in ("sync", "async")}
    mixed_async, mixed_sync = by_label(runs["queue"], "mixed async v2"), by_label(runs["queue"], "mixed sync v2")
    roles = [("all-1", "One all replica"), ("split-1", "Booking + daily"), ("split-2", "Booking + daily"), ("all-2", "One all replica")]
    vm_b, vm_d = by_run(runs["vm"], "base-spread2", "booking"), by_run(runs["vm"], "base-spread2", "daily")
    jf_b, jf_d = by_run(runs["routing"], "j-flex", "booking"), by_run(runs["routing"], "j-flex", "daily")
    jc_b, jc_d = by_run(runs["routing"], "j-ch", "booking"), by_run(runs["routing"], "j-ch", "daily")
    j2 = {run: by_run(runs["priority"], run, "booking") for run in ("j2-confirm", "j2-gc", "j2-slot", "j2-coh2")}
    j2_d = {run: by_run(runs["priority"], run, "daily") for run in j2}
    k = {run: by_run(runs["remote"], run, "booking") for run in ("k-embedded", "k-remote", "k-remote4", "k-remote5")}
    k_d = {run: by_run(runs["remote"], run, "daily") for run in k}
    prof = extras["profiling"]; pairs, summary = prof["pairs"], prof["summary"]
    heap = lambda r: r["server"]["heapUsedMaxBytes"] / 2 ** 30
    daily_cpu = lambda r: [x for x in r["containers"] if "daily" in x["name"]][0]["cpuPercent"]["mean"]
    final = j2["j2-slot"]
    OFFERS, CONFIRM = "POST /api/v1/booking/offers 201", "POST /api/v1/booking/holds/{holdId}/confirm 200"
    total_runs = sum(len(v) for v in runs.values()) + len(extras["coldRemeasurement"]) + len(extras["abComparison"])
    bench_new, bench_old = benchmarks["2026-10-14"], benchmarks["2026-09-24"]
    ch_new = {(l["engine"], l["concurrency"]): l for l in bench_new["latency"]}
    ch_old = {(l["engine"], l["concurrency"]): l for l in bench_old["latency"]}
    offers_ratio = jc_b["booking"]["searches"] / vm_b["booking"]["searches"]
    per_leg = lambda r: r["breakdown"][OFFERS]["routingMs"]["p50"] / r["breakdown"][OFFERS]["fetched"]["p50"]
    routing_cpu = lambda r: [x for x in r["containers"] if "routing" in x["name"]][0]["cpuPercent"]["mean"]
    booking_cpu = lambda r: [x for x in r["containers"] if "booking" in x["name"]][0]["cpuPercent"]["mean"]

    p("WaterFlex scheduler performance work explained", "Title")
    p("How the public API's speed under load was measured and improved, and why", "Subtitle")
    p("October 14 2026  |  Load studies of pull requests #151 to #164, 2026-10-13 to 2026-10-14")
    p(f"Over two days the scheduler's public API was driven by a load tool that plays WaterFlex Software: booking desks searching, holding and confirming appointments at new addresses, and planners asking for optimized days. Each pull request in the series measured the service before and after its change, on fresh addresses, with the same tool. Across {total_runs} recorded load runs, five booking desks went from {per_minute(b[5]):.0f} answered searches per minute at a p95 of {seconds(b[5]['ops']['booking offers']['ms']['p95'])} to {per_minute(final):.0f} per minute at {ms(final['ops']['booking offers']['ms']['p95'])}, with no hold lost and every search complete. A confirm, the call that completes the booking a customer chose, went from {ms(jc_b['ops']['confirm']['ms']['p50'])} to {ms(final['ops']['confirm']['ms']['p50'])} p50 at the caller; the daily search evaluates {summary['costPhaseSpeedup']['median']:.1f} times the moves per second it did in the same five seconds; and a remote solver pool was measured and kept as a lever for the daily side only. [1, 10]")
    sub("What the measurements tell us")
    table(["Stage", "Change judged", "Five desks: searches per minute", "Offers p95 at the caller"], [
        ["#151", "Load tool; the first measurement", f"{per_minute(b[5]):.0f}", ms(b[5]["ops"]["booking offers"]["ms"]["p95"])],
        ["#152", "Late snapshots keep holds; bookings publish without waiting", f"{per_minute(fresh[5]):.0f}", ms(fresh[5]["ops"]["booking offers"]["ms"]["p95"])],
        ["#154", "searchComplete means what the spec says", f"{per_minute(outcome):.0f}", ms(outcome["ops"]["booking offers"]["ms"]["p95"])],
        ["#155", "Optional refinement shed while bookings wait", f"{per_minute(shed[5]):.0f}", ms(shed[5]["ops"]["booking offers"]["ms"]["p95"])],
        ["J0 (#159)", "VM shape, daily planners beside the desks, time attributed", f"{per_minute(vm_b):.0f}", ms(vm_b["ops"]["booking offers"]["ms"]["p95"])],
        ["J (#160)", "CH graph, parallel pairs, identity cache", f"{per_minute(jc_b):.0f}", ms(jc_b["ops"]["booking offers"]["ms"]["p95"])],
        ["J2 (#162)", "Confirms ahead of searches, a confirm-only slot; JVM flags measured", f"{per_minute(final):.0f}", ms(final["ops"]["booking offers"]["ms"]["p95"])],
        ["K (#164)", "Embedded engine kept (this run) against a remote solver pool", f"{per_minute(k['k-embedded']):.0f}", ms(k["k-embedded"]["ops"]["booking offers"]["ms"]["p95"])]], [.9, 3.3, 1.5, 1.2])
    p("The first four rows ran on one machine with the desks alone; the last four ran the VM rehearsal stack with its CPU limits and five daily planners working beside the desks, so the two halves are not one series. Within each half the comparisons hold, and the daily side improved as well: a queued day's proposal went from a 503 after nine seconds of waiting to a fair turn in a queue, and two replica pools nearly doubled the days optimized per minute.")
    p("The report explains each result through what the service recorded: which calls waited for a slot, how many road legs were fetched cold, how many requests were refused and why, and later the time each call spent in admission, routing, the calculation engine and the database. Latencies are measured at the caller, including every wait a host would see. None of it is customer traffic; the addresses are random points inside Omaha and the days are synthetic.")
    sub("How to read this report")
    table(["Sections", "What you will learn"], [("1 to 3", "What was measured, how a booking search spends its time, and the first measurement."),
                                                ("4 to 6", "The booking fixes: late snapshots, same-date contention, and the optional refinement."),
                                                ("7 to 9", "The daily side: early refusal, fair queuing, and separate replica pools."),
                                                ("10 and 11", "The VM-shaped baseline with time attributed, and the routing work."),
                                                ("12 to 14", "Confirms ahead of searches and the JVM flags, more moves per second inside the daily search, and the embedded engine against a remote solver pool."),
                                                ("15 and appendices", "What should happen next, how the numbers were checked, every run, every paired solver case, and sources.")], [1.45, 5.45])

    heading("1 What was measured")
    p("The scheduler is reached only through its public API, so the load tool (infra/host-conformance/load.ts) plays the host. A booking desk searches for a new 60-minute job at a fresh address, selects one of the offers, and either confirms it and writes the receipt as WaterFlex Software would (30 percent of searches) or releases it. A daily planner sends one metro day of 20 technicians with two visits each and asks for a cost search of 5 seconds. Every run lasts 60 or 90 seconds and uses its own random seed, so no run reuses another's addresses or cached roads. [1, 2]")
    table(["Dimension", "Values in these studies"], [
        ("Booking desks", "1, 5 or 10 concurrent desks searching the same five dates (10 technicians with 2 visits each per date); from J0, each desk its own dates"),
        ("Daily planners", "1, 2, 4 or 5 concurrent planners, synchronous or queued (Prefer: respond-async)"),
        ("Policy", "offerLimit 4, windowHours 2, bookingDeadlineMs 5000, regularWindowThreshold 2, daily search TABU for 5 s"),
        ("Addresses", "Random points inside 41.22 to 41.28 N, 96.05 to 95.95 W (Omaha); cold means every job at a new point, warm cycles 8 points"),
        ("Stack", "One scheduler jar with 2 search slots, the Omaha routing service (map 2026-10-13), Postgres 16; from J0 the deploy/vm rehearsal stack with 2 cores per scheduler pool; in K a solver pool with 4 cores beside them"),
        ("Machine", "One 24-processor workstation (AMD Ryzen 9 7900X, 63 GiB) running scheduler, routing, database and the load generator")], [1.4, 5.5])
    table(["Study", "Evidence file", "PR", "Runs", "Change judged"],
          [[key, name.replace("api-load-", ""), pr, len(runs[key]) + (len(extras["coldRemeasurement"]) if key == "concurrency" else 0) + (len(extras["abComparison"]) if key == "shedding" else 0), note]
           for key, name, pr, note in STUDIES], [.85, 2.0, .5, .45, 3.1], size=9)
    p("A run's numbers come from three places: the load tool's own clock at the caller (p50, p95 and p99 of each operation, with every 429 wait a host would retry included), the scheduler's metrics endpoint scraped at the end (routing pairs, identity requests, admission rejections, CPU and heap), and docker stats of the routing container. From J0 a fourth source is the request log line, which attributes each call's time (section 10). Study L (section 13) is not a load study: it profiles the daily search inside the benchmark harness, with one flight recording per solve.")
    sub("The measures")
    table(["Measure", "How to interpret it"], [
        ("Searches per minute", "Booking searches answered 201 in the run, scaled to a minute. More desks or faster searches raise it; refusals and resends do not count."),
        ("Offers p50 / p95 / p99", "Caller-side latency of POST /api/v1/booking/offers. The design target is p95 within the client's 5 s deadline plus 500 ms."),
        ("Confirm p50 / p95", "Caller-side latency of confirming a selected hold with a fresh snapshot."),
        ("Holds lost", "Confirms answered 409 HOLD_UNAVAILABLE out of the holds selected: a customer who chose a time and could not get it."),
        ("STALE resends", "Calls answered 409 STALE and sent again from current records; each is a new call with its own latency."),
        ("Waited on 429", "Calls refused as BUSY at least once and retried after Retry-After; their wait counts in their latency."),
        ("Proposals", "Daily proposals answered 201 in the run; a 503 or 429 is a day the planner did not get."),
        ("Queue wait", "For a queued proposal, the time from queuing to the start of its calculation, from its status.")], [1.55, 5.35])

    heading("2 How a booking search spends its time")
    p("A booking search is not one calculation. It is a short pipeline, and every stage can wait on something. Knowing the stages makes the rest of the report readable, because each fix below removes one wait. [3]")
    table(["Stage", "What happens", "What it waits on"], [
        ("Parse and claim", "The request is read strictly and its requestId claimed in the thin store.", "The database"),
        ("Admission", "The search asks for one of the replica's search slots (2 here).", "Other searches and confirms on the replica"),
        ("Reconcile", "Each date's stored holds are compared with the host's snapshot.", "Nothing; a stale snapshot is refused here"),
        ("Route", "Every directed leg between the job and the day's stops is looked up: memory, database, then the routing service.", "The routing service, for legs never seen"),
        ("Insert and refine", "The calculation engine places the job on each day, then an optional pass improves the cost.", "CPU, within the client's deadline"),
        ("Publish", "Offers are written and their days' versions checked.", "Other bookings that wrote the same day")], [1.3, 3.2, 2.4])
    p("Two of these stages dominated in turn. Before J the route stage was the largest part of a cold search, because every new address needs about 500 legs that nothing has seen before. Once routing was fast, the admission stage was what remained: with five desks on two slots, a search spends most of its time waiting to start.")
    sub("Why the numbers are measured at the caller")
    p("A host does not see the scheduler's internal time; it sees how long its call took, including a 429 it had to wait out and a STALE it had to resend. Every latency in this report is that outer time, so a change that moved work from one place to another, or turned a lost hold into a resend, shows its true cost. Server-side timings appear only in section 10, where they explain the outer time.")

    heading("3 The first measurement (#151)")
    p(f"The load tool shipped with its first measurement. One desk met the latency target with room to spare; five or more desks did not, and the reason was not computation. [1]")
    table(["Run", "Searches / min", "Offers p50 / p95 / p99", "Confirm p50 / p95", "Holds lost", "Waited on 429"],
          [[r["label"].replace("booking-", "").replace("-c", ", ").replace("cold", "cold").replace("mixed, 5-d1", "mixed 5 desks + 1 planner"), f"{per_minute(r):.0f}", lat(r["ops"]["booking offers"]), f"{r['ops']['confirm']['ms']['p50']:,} / {r['ops']['confirm']['ms']['p95']:,}",
            f"{r['booking']['holdUnavailable']} of {r['booking']['selected']}", r["ops"]["booking offers"]["retriedBusy"]] for r in (b[1], b[5], b[10], b_warm, b_mixed)], [1.55, .85, 1.5, 1.1, .9, 1.0])
    figure("desks", "Offers p95 at the caller (left) and searches answered per minute (right) by the number of desks booking the same five dates, on fresh addresses: the first measurement, after #152, and after #155. The dashed line is the 5.5 s bound.")
    p(f"Three findings came out of these runs, and the series that follows is their fixes. First, a search whose deadline was nearly spent answered 500 instead of 429, because the admission signal was wrapped by the transaction manager; that was fixed in the same pull request. Second, {pct(b[5]['booking']['holdUnavailable'], b[5]['booking']['selected'])} of the holds customers selected at five desks were lost at confirm, {pct(b[10]['booking']['holdUnavailable'], b[10]['booking']['selected'])} at ten (section 4). Third, {pct(b[5]['ops']['booking offers']['retriedBusy'], b[5]['ops']['booking offers']['calls'])} of searches at five desks waited on at least one 429, because bookings on the same dates serialized on day versions (section 5).")
    p(f"Cold routing was the largest part of a search's own time: a cold five-desk search took {ms(b[5]['ops']['booking offers']['ms']['p50'])} p50 against {ms(b_warm['ops']['booking offers']['ms']['p50'])} with warm addresses, and needed about {b[5]['server']['routingPairs'] // b[5]['booking']['searches']} routing pairs per search against {b_warm['server']['routingPairs'] // b_warm['booking']['searches']}. The routing container did most of the machine's work ({routing_cpu(b[5]):.0f} percent of a core at five desks) while the scheduler process used {b[5]['server']['processCpu']['mean'] * 100:.0f} percent of the machine. That observation set the order of the later work: contention first, routing last, because routing needed a graph change.")
    sub("The daily side at the start")
    table(["Run", "Proposals asked", "Answered 201", "503 (calculation unavailable)", "p50"],
          [[name, r["daily"]["proposals"], r["ops"]["daily proposal"]["statuses"].get("201", 0), r["ops"]["daily proposal"]["statuses"].get("503", 0), seconds(r["ops"]["daily proposal"]["ms"]["p50"])]
           for name, r in (("1 planner", b_daily[1]), ("4 planners", b_daily[4]), ("1 planner beside 5 desks", b_mixed))], [2.1, 1.2, 1.1, 1.7, .8])
    p(f"A replica ran one daily calculation at a time, and the planners behind it queued for their whole allowance and then failed: {b_daily[4]['ops']['daily proposal']['statuses'].get('503', 0)} of {b_daily[4]['daily']['proposals']} proposals at four planners were a 503 after about {seconds(b_daily[4]['ops']['daily proposal']['ms']['p50'])}. The first proposal of a run also spent its cold routing inside the search allowance. Sections 7 to 9 follow this thread.")

    heading("4 Keeping holds when snapshots arrive late (#152)")
    p("The lost holds had one cause, reproduced deterministically before the fix and again after it. [1, 4]")
    table(["Step", "What happens"], [(str(i + 1), s) for i, s in enumerate(extras["staleBefore"]["steps"])] + [("Result before", extras["staleBefore"]["result"]), ("Result after", extras["staleAfter"]["result"]), ("Control", extras["staleAfter"]["control"])], [1.2, 5.7])
    p("The scheduler compares a technician-day's lastModified only for equality, so that clock skew between servers can never matter. The price was that a request built from older records, arriving after a newer receipt was already used, looked like a fresh host edit of a route that holds depended on, and reconciliation rule 3 lost every hold on that date. Any host with several booking desks produces such late requests.")
    p("The fix keeps equality. Each technician-day now remembers the last 32 timestamps the scheduler has moved past, and the route from before a confirm receipt the host has not written yet. A snapshot carrying either is answered 409 STALE before anything is reconciled, and the host rebuilds it from current records. Nothing is guessed and nothing is lost; the cost is a resend.")
    figure("holds", "Left: selected holds lost at confirm in each run of the first measurement and in the same runs after #152. Right: STALE resends per answered search after #152, the cost that replaced the loss.")
    table(["Run", "Holds lost before", "Holds lost after", "STALE resends after", "Resends per search"],
          [[name, f"{x['booking']['holdUnavailable']} of {x['booking']['selected']}", f"{y['booking']['holdUnavailable']} of {y['booking']['selected']}", y["booking"]["stale"], f"{y['booking']['stale'] / y['booking']['searches']:.2f}"]
           for name, x, y in (("1 desk", b[1], c[1]), ("5 desks", b[5], c[5]), ("10 desks", b[10], c[10]), ("5 desks, warm", b_warm, c_warm), ("mixed", b_mixed, c_mixed))], [1.5, 1.4, 1.3, 1.4, 1.3])
    p(f"No hold was lost in any run after the fix. At ten desks on the same five dates, {c[10]['booking']['stale'] / c[10]['booking']['searches']:.1f} resends per answered search is a high price, and it is a price of the test's shape: ten desks booking the same fifty technician-days is more contention than one metro sees. From J0 the load tool gives each desk its own dates, and resends vanish (section 10).")

    heading("5 Letting concurrent bookings publish")
    p("The second fix in #152 addressed the 429 waits. A search published only if every day it had read still had the version it read, so ten desks on five dates kept invalidating each other. Now a search checks only the days its offers hold, or where the job's earlier holds end; a day it merely brought up to date is skipped when another booking wrote it first; and days it read but did not change are not checked at all, because offers on one date never depend on another. A moved day runs the search again while the deadline leaves time, at most three times. [3]")
    p(f"The first runs after the fix looked better than they were: they were the third pass over the same seeds, so the roads were cached. Measured again on fresh seeds, which is the comparison this report keeps: [1]")
    table(["Desks", "Baseline offers p95", "#152 offers p95, fresh seeds", "Searches / min, baseline", "Searches / min, #152", "Waited on 429, baseline / #152"],
          [[n, seconds(b[n]["ops"]["booking offers"]["ms"]["p95"]), seconds(fresh[n]["ops"]["booking offers"]["ms"]["p95"]), f"{per_minute(b[n]):.0f}", f"{per_minute(fresh[n]):.0f}",
            f"{b[n]['ops']['booking offers']['retriedBusy']} / {fresh[n]['ops']['booking offers']['retriedBusy']}"] for n in (1, 5, 10)], [.6, 1.25, 1.45, 1.2, 1.1, 1.3])
    p(f"Every desk count is within the 5.5 s bound at the caller after the fix, ten desks only just ({seconds(fresh[10]['ops']['booking offers']['ms']['p95'])}). A seed run before leaves its roads in the cache and makes a one-desk search about a quarter of a second faster, which is why every comparison in this report is between fresh-seed runs.")

    heading("6 The optional refinement (#154 and #155)")
    p("A booking search runs two passes. The insertion pass places the job on each day and finds the regular windows; the refinement pass improves cost and may move other appointments. When the insertion pass already finds more distinct windows than the client's regularWindowThreshold, the refinement is optional and capped at 250 ms. [3]")
    p(f"Two things were wrong with how that showed up. First, reaching the cap marked the search incomplete, so every search in the first measurement reported searchComplete: false ({b[5]['booking']['searchIncomplete']} of {b[5]['booking']['searches']} at five desks) while returning four offers, and a host reading the flag as the spec defines it (the deadline cut the search short) learned nothing. #154 made the flag mean exactly that and added OfferSet.refinement to say how the second pass ended: in a 60 s run at five desks afterwards, {extras['outcome']['searchCompleteFalse']} of {extras['outcome']['searches']} searches were incomplete and all {extras['outcome']['refinement']['SHORTENED']} refinements were SHORTENED by the cap. [5]")
    p("Second, that optional pass was spent while other customers waited for a slot. #155 sheds it (refinement SHED) while booking requests are queued on the replica, reports the dates a needed pass did not finish (incompleteDates), and lets a client set its own refinement budget. Measured as an A/B at five desks on fresh addresses: [1]")
    table(["Build", "Searches / min", "Offers p50", "Offers p95", "Refinement outcomes", "Incomplete"],
          [[name, f"{per_minute(r):.0f}", ms(r["ops"]["booking offers"]["ms"]["p50"]), ms(r["ops"]["booking offers"]["ms"]["p95"]),
            ", ".join(f"{k} {v}" for k, v in sorted(r["booking"]["refinements"].items())), r["booking"]["searchIncomplete"]] for name, r in (("#154", ab_before), ("#155", ab_after))], [.8, 1.1, 1.0, 1.0, 2.1, .9])
    p(f"Throughput doubled ({per_minute(ab_before):.0f} to {per_minute(ab_after):.0f} searches per minute) and p95 halved, because a search that would have spent 250 ms refining while four desks waited now hands that time to them. Every search still returned four offers, and shed searches skip only the optional cost improvement; the windows offered are the insertion pass's, which the refinement never changes. With ten desks the same build answered {per_minute(shed[10]):.0f} searches per minute with p95 {ms(shed[10]['ops']['booking offers']['ms']['p95'])}.")
    figure("progression", "Five booking desks at each stage: searches answered per minute (left) and caller latency of offers (right). Left of the dotted line, one machine with the desks alone; right of it, the VM rehearsal stack with five daily planners working beside the desks.")

    heading("7 Refusing daily proposals early (#153)")
    p(f"In the first measurement a daily replica ran one calculation at a time, and a proposal that could not start queued for its whole allowance and then failed with 503 after about {seconds(b_daily[4]['ops']['daily proposal']['ms']['p50'])}. The planner learned nothing it could act on, and the refusal was not even counted. The first proposal of a day also spent its cold routing (about 4 s for 20 technicians) inside the search allowance. [1, 3]")
    p("#153 routes the day before the calculation asks for a slot, under its own 30 s deadline. Each calculation tells admission how long it will hold its slot (its search time plus a 2 s settle margin); a request that could not start within its reserve is refused at once with 429 and the estimated wait as Retry-After, before anything is routed; one still waiting when it could no longer finish leaves the queue with 429 too; and every refusal counts as an admission rejection.")
    table(["Run", "Proposals asked", "Answered 201", "503", "429 answers, retried after Retry-After", "Proposal p50"],
          [[name, r["daily"]["proposals"], r["ops"]["daily proposal"]["statuses"].get("201", 0), r["ops"]["daily proposal"]["statuses"].get("503", 0), r["server"]["admissionRejections"], seconds(r["ops"]["daily proposal"]["ms"]["p50"])]
           for name, r in (("1 planner", a_daily[1]), ("4 planners", a_daily[4]), ("1 planner beside 5 desks", a_mixed))], [1.9, 1.0, .9, .5, 1.7, .9])
    figure("daily", "Left: proposals answered (solid) or refused with 503 (hatched) at one and four planners, before and after #153. Middle: proposals each of two clients got in 90 s on one replica, synchronously and queued (L4). Right: proposals in 60 s from one replica doing everything or from booking and daily pools at equal capacity (L5).")
    p(f"Every proposal was now answered, the cold first day included. What remained was fairness: retries were a lottery, and one proposal at four planners waited {seconds(a_daily[4]['ops']['daily proposal']['ms']['max'])} for its turn. On a replica that also books, queued bookings always go first, so the planner beside five desks got {a_mixed['daily']['proposals']} proposals in a minute. Sections 8 and 9 take those two in turn.")

    heading("8 Fair turns for queued proposals (L4, #157)")
    p("A host may now queue a daily or repair proposal with the header Prefer: respond-async and poll its status. Any replica's worker takes queued requests in turn across clients, the waiting request of the client whose last one started longest ago first. Measured with two clients on one replica for 90 s: client A with four planners and client B with one. [1, 6]")
    table(["Mode", "Client", "Proposals", "Proposal p50", "Proposal p95", "Wait before the calculation started"],
          [[mode, f"{t}, {n} planner{'s' if n > 1 else ''}", fair[(m, n)]["daily"]["proposals"], seconds(fair[(m, n)]["ops"]["daily proposal"]["ms"]["p50"]), seconds(fair[(m, n)]["ops"]["daily proposal"]["ms"]["p95"]),
            (lambda w: f"p50 {seconds(w[0])}, max {seconds(w[1])}")(queue_wait(fair[(m, n)])) if m == "async" else "not queued"]
           for m, mode in (("sync", "Synchronous, 429 and Retry-After"), ("async", "Queued")) for t, n in (("A", 4), ("B", 1))], [2.0, 1.1, .8, .9, .9, 1.2])
    p(f"Synchronously, one client starved the other: B's single planner got {fair[('sync', 1)]['daily']['proposals']} proposal in 90 s, because A's four planners took each freed slot first while B waited out its Retry-After. Queued, B got {fair[('async', 1)]['daily']['proposals']}, one every 9 to 15 s; it waits only for the calculation running when it asks, whatever A has queued. The total is the same ({fair[('sync', 4)]['daily']['proposals'] + fair[('sync', 1)]['daily']['proposals']} and {fair[('async', 4)]['daily']['proposals'] + fair[('async', 1)]['daily']['proposals']} proposals): a replica still runs one calculation at a time, so the queue shares the work out fairly rather than adding any.")
    table(["Beside five desks, one client", "Searches / min", "Offers p95", "Proposals", "Proposal p95"],
          [[name, f"{per_minute(r):.0f}", ms(r["ops"]["booking offers"]["ms"]["p95"]), r["daily"]["proposals"], seconds(r["ops"]["daily proposal"]["ms"]["p95"])] for name, r in (("Synchronous", mixed_sync), ("Queued", mixed_async))], [2.3, 1.2, 1.1, 1.1, 1.2])
    p("Queuing costs bookings nothing: with five desks and two planners the queued run answered as many searches as the synchronous one, and admission still puts bookings first. A first build whose worker waited for an empty booking queue got through only six proposals; the worker now waits only while a daily calculation runs on its replica.")

    heading("9 Booking and daily replica pools (L5, #158)")
    p("The same jar now runs as booking replicas, which take no queued daily work, and daily replicas, which may run several calculations at once; the VM compose puts nginx in front of the two pools. The comparison holds total search capacity equal at four slots: one replica doing everything (capacity 4, one calculation at a time) against a booking replica (2 slots) plus a daily replica (2 slots, two at a time). Load: five desks and five queued planners for 60 s, in the order all, split, split, all. [1, 7]")
    table(["Shape", "Searches / min", "Offers p95 / p99", "Waited on 429", "Confirm p50 / p95", "Proposals", "Proposal p50 / p95", "Queue wait p50"],
          [[name, f"{per_minute(by_run(runs['roles'], run, 'booking')):.0f}", f"{by_run(runs['roles'], run, 'booking')['ops']['booking offers']['ms']['p95']:,} / {by_run(runs['roles'], run, 'booking')['ops']['booking offers']['ms']['p99']:,}",
            by_run(runs["roles"], run, "booking")["ops"]["booking offers"]["retriedBusy"], f"{by_run(runs['roles'], run, 'booking')['ops']['confirm']['ms']['p50']} / {by_run(runs['roles'], run, 'booking')['ops']['confirm']['ms']['p95']}",
            by_run(runs["roles"], run, "daily")["daily"]["proposals"], f"{by_run(runs['roles'], run, 'daily')['ops']['daily proposal']['ms']['p50'] / 1000:.1f} / {by_run(runs['roles'], run, 'daily')['ops']['daily proposal']['ms']['p95'] / 1000:.1f} s",
            seconds(queue_wait(by_run(runs["roles"], run, "daily"))[0])] for run, name in roles], [1.2, .75, .95, .65, .9, .7, .95, .8], size=9)
    figure("roles", "The four L5 runs at equal search capacity: offers p95 at the caller (left) and the median time to a queued daily proposal (right). Gold bars are the split pools.")
    p("Daily throughput nearly doubled and a queued day waited a third as long, because the daily replica runs two calculations at once while the single replica runs one. Bookings kept their tail: offers p95 fell and the calls refused with 429 fell from 15 and 24 to 4, because no daily calculation competes for the booking replica's cores or slots. The cost is confirm latency: with two booking slots instead of up to four, a confirm waits behind searches for a slot, and its p50 rose from about 130 ms to about 400 ms. That wait is measured in section 10 and removed in section 12.")

    heading("10 A baseline on the VM's shape, with time attributed (J0, #159)")
    p("Every earlier number came from one 24-processor machine running everything at about 10 percent CPU, which measures how calls share slots, not what a partitioned VM can do. From here the measurements run the deploy/vm rehearsal stack with its limits: 2 cores for the booking pool, 2 for the daily pool, 1 for the database, the routing service unlimited. Two tools were added. The request log line now says where each call's time went (slot wait, routing, calculation engine, database, with the routing pairs, cache hits and legs fetched cold), and the load tool's date spread gives each desk its own dates, so the STALE resends of the earlier same-date runs are gone and the numbers are the scheduler's. [1, 8]")
    table(["Call", "Calls", "Caller p50 / p95", "Server p50", "Slot wait", "Routing", "Engine", "Database", "Legs needed / fetched"],
          [["Offers (201)", vm_b["breakdown"][OFFERS]["calls"], f"{vm_b['ops']['booking offers']['ms']['p50']} / {vm_b['ops']['booking offers']['ms']['p95']} ms", ms(vm_b["breakdown"][OFFERS]["elapsedMs"]["p50"]),
            ms(vm_b["breakdown"][OFFERS]["queueMs"]["p50"]), ms(vm_b["breakdown"][OFFERS]["routingMs"]["p50"]), ms(vm_b["breakdown"][OFFERS]["calculationMs"]["p50"]), ms(vm_b["breakdown"][OFFERS]["dbMs"]["p50"]),
            f"{vm_b['breakdown'][OFFERS]['routingPairs']['p50']} / {vm_b['breakdown'][OFFERS]['fetched']['p50']}"],
           ["Confirm (200)", vm_b["breakdown"][CONFIRM]["calls"], f"{vm_b['ops']['confirm']['ms']['p50']} / {vm_b['ops']['confirm']['ms']['p95']} ms", ms(vm_b["breakdown"][CONFIRM]["elapsedMs"]["p50"]),
            ms(vm_b["breakdown"][CONFIRM]["queueMs"]["p50"]), ms(vm_b["breakdown"][CONFIRM]["routingMs"]["p50"]), ms(vm_b["breakdown"][CONFIRM]["calculationMs"]["p50"]), ms(vm_b["breakdown"][CONFIRM]["dbMs"]["p50"]),
            f"{vm_b['breakdown'][CONFIRM]['routingPairs']['p50']} / {vm_b['breakdown'][CONFIRM]['fetched']['p50']}"],
           ["Daily, queued", vm_d["daily"]["proposals"], f"{vm_d['ops']['daily proposal']['ms']['p50'] / 1000:.1f} / {vm_d['ops']['daily proposal']['ms']['p95'] / 1000:.1f} s", "", f"queue {seconds(queue_wait(vm_d)[0])}", "", "5 s search", "", ""]],
          [.95, .5, 1.1, .7, .8, .65, .65, .7, .85], size=9)
    p(f"Half of a search was the slot wait: with five desks on a two-slot replica, a search waited {ms(vm_b['breakdown'][OFFERS]['queueMs']['p50'])} for a slot before it started, and a confirm that needs about {vm_b['breakdown'][CONFIRM]['dbMs']['p50'] + vm_b['breakdown'][CONFIRM]['routingMs']['p50'] + vm_b['breakdown'][CONFIRM]['cpuMs']['p50']} ms of work waited {ms(vm_b['breakdown'][CONFIRM]['queueMs']['p50'])} behind the searches. The other half was routing, and it was cold legs: each search needed about {vm_b['breakdown'][OFFERS]['routingPairs']['p50']} directed legs, {vm_b['breakdown'][OFFERS]['fetched']['p50']} of them fetched from the routing service at about {per_leg(vm_b):.1f} ms each, because the service routed a batch's pairs one after another on the flexible graph and the scheduler fed it through two worker threads. The routing container ran at {routing_cpu(vm_b):.0f} percent of a core for the booking replica's {booking_cpu(vm_b):.0f} percent.")
    p(f"The engine was not the booking lever: insertion and refinement took {ms(vm_b['breakdown'][OFFERS]['calculationMs']['p50'])} p50 ({ms(vm_b['breakdown'][OFFERS]['calculationMs']['p95'])} p95), and the request thread used {vm_b['breakdown'][OFFERS]['cpuMs']['p50']} ms of CPU per search. The daily replica was CPU bound by design at {[x for x in vm_b['containers'] if 'daily' in x['name']][0]['cpuPercent']['mean']:.0f} percent of its two cores, two 5 s searches at a time; more daily throughput is more daily cores or replicas, or faster moves per second inside the budget.")
    figure("attribution", "Server time of the median booking call, split into slot wait, routing, calculation engine and database, for the VM baseline (J0), the two J runs, the J2 build that shipped (section 12) and the remote solver pool (section 14). The confirm has almost no work of its own; its bar is the wait behind searches.")

    heading("11 Routing: the CH graph, parallel pairs and fewer round trips (J, #160)")
    p("J changed four things the baseline pointed at. Each map version now carries a contraction hierarchy graph beside the flexible one, prepared with the map, listed in its manifest and required at activation, and the routing service serves it by default. The routing service routes a request's pairs on several threads at once. The scheduler trusts a routing identity it fetched within the last two seconds instead of asking /health before every lookup; the service still refuses a call that names another identity, which drops the remembered one. And the scheduler fetches leg batches on eight workers with a queue of 64 instead of two and 16. [3, 9]")
    sub("What a contraction hierarchy is")
    p("A flexible graph answers each route by exploring the road network outward from both ends until the searches meet, so a longer trip or a busier machine means a longer answer. A contraction hierarchy spends time at import ordering every junction by importance and adding shortcut edges that stand for whole chains of minor roads, so a query climbs from both ends through ever more important roads and meets near the top after touching a few hundred nodes. The answer is identical to the metre; only the work changes. The price is an import step of 27 s and 2.7 million shortcuts for the Omaha map, about 200 MiB more on disk per version, and a routing identity that differs from the flexible graph's, so switching starts every cache cold like a map change does.")
    sub("The routing service alone")
    table(["Map, date", "Engine", "First 132 cold pairs", "16 pairs, 1 caller p50 / p95", "5 callers p50 / p95", "10 callers p50 / p95"],
          [[f"{doc['engines'][0]['mapVersion']}, {date}", "Flexible" if e == "flexible" else "CH", f"{doc['firstRequestMs'][i]:,.0f} ms",
            *[f"{ch[(e, cc)]['p50Ms']:.1f} / {ch[(e, cc)]['p95Ms']:.1f} ms" for cc in (1, 5, 10)]]
           for date, doc, ch in (("2026-09-24", bench_old, ch_old), ("2026-10-14", bench_new, ch_new)) for i, e in enumerate(("flexible", "CH-car-v1"))], [1.3, .7, 1.1, 1.3, 1.25, 1.25], size=9)
    figure("ch", "p50 of a 16-pair routing request against the routing service alone with its caches off, flexible against CH, on the 2026-09-16 map in September and the 2026-10-13 map on 2026-10-14 (the new image routes a request's pairs in parallel). Log scale.")
    p(f"On 132 Omaha pairs the CH graph gives exactly the same directed legs as the flexible graph (every check in both benchmarks passed) and answers a 16-pair request in {ch_new[('CH-car-v1', 1)]['p50Ms']:.1f} ms instead of {ch_new[('flexible', 1)]['p50Ms']:.1f} ms with one caller, and {ch_new[('CH-car-v1', 10)]['p50Ms']:.1f} ms instead of {ch_new[('flexible', 10)]['p50Ms']:.1f} ms with ten. The parallel pairs of the new image already helped the flexible graph (the September flexible line is far higher), and CH takes the rest. One lesson for anyone repeating it: with the routing service's own cache on, both engines answer the repeated 132 pairs from memory and CH looks slower by its small overhead, which is what the first attempt showed; the benchmark expects both instances launched with routing.cache.max-entries=1, as its evidence says.")
    sub("The scheduler under load")
    table(["Run", "Searches in 60 s", "Caller p50 / p95", "Server p50", "Slot wait", "Routing", "Per fetched leg", "Confirm p50 / p95", "Routing CPU"],
          [[name, r["booking"]["searches"], f"{r['ops']['booking offers']['ms']['p50']} / {r['ops']['booking offers']['ms']['p95']} ms", ms(r["breakdown"][OFFERS]["elapsedMs"]["p50"]), ms(r["breakdown"][OFFERS]["queueMs"]["p50"]), ms(r["breakdown"][OFFERS]["routingMs"]["p50"]),
            f"{per_leg(r):.2f} ms", f"{r['ops']['confirm']['ms']['p50']} / {r['ops']['confirm']['ms']['p95']} ms", f"{routing_cpu(r):.0f}%"]
           for name, r in (("J0 baseline, flexible", vm_b), ("J, flexible graph", jf_b), ("J, CH graph", jc_b))], [1.25, .7, .95, .65, .65, .6, .7, .85, .55], size=9)
    figure("cpu", "Searches answered per minute against the routing container's mean CPU (percent of one core, log scale) for the baseline and the two J runs, with the booking replica's own CPU in each label. The flexible graph with parallel pairs bought its gain with thirteen cores.")
    p(f"The CH run answered {offers_ratio:.1f} times the baseline's searches at a quarter of the latency, with no errors, no STALE and every search complete. The engine and database shares are unchanged ({ms(jc_b['breakdown'][OFFERS]['calculationMs']['p50'])} and {ms(jc_b['breakdown'][OFFERS]['dbMs']['p50'])} p50), so the whole gain is routing and the slot time that routing was holding: a slot held {ms(vm_b['breakdown'][OFFERS]['elapsedMs']['p50'] - vm_b['breakdown'][OFFERS]['queueMs']['p50'])} per search before and {ms(jc_b['breakdown'][OFFERS]['elapsedMs']['p50'] - jc_b['breakdown'][OFFERS]['queueMs']['p50'])} after, so two slots serve five desks with a {ms(jc_b['breakdown'][OFFERS]['queueMs']['p50'])} wait instead of {ms(vm_b['breakdown'][OFFERS]['queueMs']['p50'])}.")
    p(f"CH is the lever; parallel flexible search is not. Routing a batch's pairs in parallel on the flexible graph halved the routing time but spent {routing_cpu(jf_b) / 100:.0f} cores doing it; the same parallelism on the CH graph answered in a third of that time on {routing_cpu(jc_b) / 100:.1f} cores. On a VM whose routing service is capped at a few cores, flexible mode would be far worse than the middle row shows. The daily replica is unchanged across the three runs ({vm_d['daily']['proposals']}, {jf_d['daily']['proposals']} and {jc_d['daily']['proposals']} proposals, {seconds(jc_d['ops']['daily proposal']['ms']['p50'])} p50): its work is the 5 s search, not routing.")

    heading("12 Confirms ahead of searches, and the JVM flags (J2, #162)")
    p(f"After J a confirm, which does a few milliseconds of work to complete a booking the customer already chose, still spent {ms(jc_b['breakdown'][CONFIRM]['queueMs']['p50'])} of its {ms(jc_b['breakdown'][CONFIRM]['elapsedMs']['p50'])} on the server waiting for a search slot behind searches. J2 changed admission in two steps and then measured the JVM flags the compose file sets, on the same stack and load as J's CH run, with fresh seeds for every run. [1, 3]")
    p("The first step is priority. A confirm is a booking ticket that admission chooses before any waiting search; among waiting confirms the same fairness rule applies as among searches (the client with the fewest running, then the one whose last booking started longest ago); a full queue never evicts a confirm, and an arriving confirm takes the place of the heaviest client's newest waiting search. The second step is a confirm-only slot: SCHEDULER_SEARCH_CONFIRM_SLOTS (default 1, at most the capacity) opens slots beyond the search capacity that only confirms may take, so a confirm no longer waits for a running search to finish either.")
    table(["Run", "JVM flags (booking / daily)", "Confirm caller p50 / p95", "Confirm slot wait", "Offers caller p50 / p95", "Searches in 60 s", "Booking CPU, heap max", "Daily p50"],
          [[name, flags, f"{r['ops']['confirm']['ms']['p50']} / {r['ops']['confirm']['ms']['p95']} ms", ms(r["breakdown"][CONFIRM]["queueMs"]["p50"]), f"{r['ops']['booking offers']['ms']['p50']} / {r['ops']['booking offers']['ms']['p95']} ms",
            r["booking"]["searches"], f"{booking_cpu(r):.0f}%, {heap(r):.2f} GiB", seconds(d["ops"]["daily proposal"]["ms"]["p50"])]
           for name, flags, r, d in (("J, CH graph (before)", "default (G1)", jc_b, jc_d), ("Priority", "default", j2["j2-confirm"], j2_d["j2-confirm"]), ("Priority; ZGC / Parallel GC", "ZGC / Parallel GC", j2["j2-gc"], j2_d["j2-gc"]),
                                     ("Priority and confirm slot (shipped)", "default", final, j2_d["j2-slot"]), ("Shipped build; compact headers", "compact object headers, both", j2["j2-coh2"], j2_d["j2-coh2"]))], [1.45, 1.25, .9, .6, .9, .6, .8, .4], size=9)
    figure("confirm", "Confirm (left) and offers (right) at the caller, p50 and p95, from J's CH run through the four J2 runs. The small label on each confirm bar is the median slot wait the server recorded for it.")
    offers_p50 = [r["ops"]["booking offers"]["ms"]["p50"] for r in (jc_b, *j2.values())]
    p(f"A confirm now answers in {ms(final['ops']['confirm']['ms']['p50'])} p50 and {ms(final['ops']['confirm']['ms']['p95'])} p95 at the caller, from {ms(jc_b['ops']['confirm']['ms']['p50'])} and {ms(jc_b['ops']['confirm']['ms']['p95'])}. Priority alone took the wait from {ms(jc_b['breakdown'][CONFIRM]['queueMs']['p50'])} to {ms(j2['j2-confirm']['breakdown'][CONFIRM]['queueMs']['p50'])}, because the confirm still waited for one of the two running searches to finish; the confirm slot took it to {ms(final['breakdown'][CONFIRM]['queueMs']['p50'])}. Offers are unchanged within run-to-run variation (p50 {min(offers_p50)} to {max(offers_p50)} ms across the five runs), every search still completes with four offers and no errors, and the replica served {pct(final['booking']['searches'] - jc_b['booking']['searches'], jc_b['booking']['searches'])} more searches in the minute because the desks spent less of it in confirms. Both steps ship as defaults.")
    p(f"The garbage collector is not a lever here, so G1 stays. ZGC on the booking replica is within 3 percent on offers p50 and p95 and worse on confirm p95 ({j2['j2-gc']['ops']['confirm']['ms']['p95']} against {j2['j2-confirm']['ops']['confirm']['ms']['p95']} ms), offers p99 ({j2['j2-gc']['ops']['booking offers']['ms']['p99']} against {j2['j2-confirm']['ops']['booking offers']['ms']['p99']} ms), CPU ({booking_cpu(j2['j2-gc']):.0f} against {booking_cpu(j2['j2-confirm']):.0f} percent) and heap ({heap(j2['j2-gc']):.2f} against {heap(j2['j2-confirm']):.2f} GiB used). Parallel GC on the daily replica changes nothing the load tool can see: a queued 20-technician day is about 6 s of queue wait plus a fixed 5 s search, so a collector's few milliseconds per solve are below the measurement, and the right instrument for that replica is the move rate inside the solver (section 13).")
    p(f"Compact object headers, a product flag since JDK 25 (JEP 519) that halves the object header to 64 bits, are within noise on latency (offers {j2['j2-coh2']['ops']['booking offers']['ms']['p50']} / {j2['j2-coh2']['ops']['booking offers']['ms']['p95']} against {final['ops']['booking offers']['ms']['p50']} / {final['ops']['booking offers']['ms']['p95']} ms, confirm {j2['j2-coh2']['ops']['confirm']['ms']['p50']} / {j2['j2-coh2']['ops']['confirm']['ms']['p95']} against {final['ops']['confirm']['ms']['p50']} / {final['ops']['confirm']['ms']['p95']} ms), with the heap {heap(final) - heap(j2['j2-coh2']):.2f} GiB smaller and the booking replica's mean CPU {booking_cpu(final) - booking_cpu(j2['j2-coh2']):.0f} points lower in this run. The CPU figure moved by as much between two runs with identical flags ({booking_cpu(j2['j2-confirm']):.0f} to {booking_cpu(j2['j2-gc']):.0f} percent), so it is not counted as a win. The threshold set before the runs was a 5 percent latency improvement; nothing met it, so the compose file keeps the JDK defaults and records both pools' flags as settings (SCHEDULER_BOOKING_JAVA_OPTIONS, SCHEDULER_DAILY_JAVA_OPTIONS) for whoever measures them on the VM itself. [12]")
    p(f"One measurement hazard is recorded with the evidence: the VM compose file rebuilds the image from the working tree on every restart, so one run (seeds 713 and 714) unknowingly carried a later commit and was repeated on the committed image before anything was concluded from it; the repeat is the compact-headers row above. With confirms out of the way, the booking replica's remaining wait is searches queuing for the two slots ({ms(final['breakdown'][OFFERS]['queueMs']['p50'])} p50 of a {ms(final['breakdown'][OFFERS]['elapsedMs']['p50'])} search, Figure 5), which is CPU: more cores, more replicas, or a cheaper search.")

    heading("13 More moves per second inside the daily search (L, #163)")
    p(f"A daily proposal's latency is its queue wait plus a fixed search budget, so the daily side cannot be made faster by the load tool's measure; it can only be made better in the same seconds, by evaluating more candidate moves. Study L asked where a solve spends its time. The benchmark harness now starts one JDK Flight Recorder recording just before each solve and stops it just after, so the samples cover the solve alone, with no warmup, validation or report in them; the configurable experiment toolkit's whole-JVM recording is unchanged. The first profile covered TABU_KOPT on 20 and 50 technicians, CLUSTERED and DISPERSED, fixture seed 7001 (not one of the tuning fixtures), 10 s per case, with the experiment runtime's daily flags (two processors, 1.5 GiB heap, the serial collector). [10]")
    p(f"Of {prof['before']['samples']:,} samples before the change, the engine's own timing methods held about a tenth; the rest was what scoring did around them. Every scored route looked its road legs up in an immutable map keyed by a from-to string built for each leg, built a HashMap of arrivals and a day plan that were then copied into a result, and computed its buffered travel minutes in BigDecimal per leg. That named one treatment of the three the earlier audit had listed (CR07, indexed lookup and score-only timing) and not the other two.")
    what = {"Immutable map probe": "Road lookups keyed by a from-to string built for every leg of every scored route; after, one identity-to-number probe per visit",
            "HashMap and copies": "Arrival maps and a day plan built for every scored route, then copied into the result",
            "BigDecimal arithmetic": "Buffered travel minutes per leg, money per route, and the bendable BigDecimal score itself",
            "Instant and Duration": "The timing search's clock arithmetic, now the largest share it leaves exposed",
            "Engine timing methods": "RouteTimingSearch.segments and RouteTimeline.evaluate, the timing itself",
            "Timefold score director": "The constraint stream's own bookkeeping around each move"}
    table(["Group of leaf frames", "Before", "After", "What it was"], [[g, f"{prof['before']['groups'].get(g, 0):.1f}%", f"{prof['after']['groups'].get(g, 0):.1f}%", what[g]] for g in what], [1.5, .6, .6, 4.2])
    figure("profile", "Left: share of a solve's samples by group of leaf frame, before and after the treatment (the top 20 frames of each profile; the rest is spread thinly). Right: cost-phase moves per second of the four profiled solves.")
    p("The treatment keeps every number the same and removes the work around it. RoadMatrix gives each endpoint identity a number and holds the legs in two tables with their buffered minutes computed once per fact revision, built lazily on the first scoring lookup, so a plan evaluated once outside the solver (a booking candidate) pays nothing new. RouteTimeline and RouteTimingSearch evaluate a route against the plan facts directly, and on the score-only path they build no arrival map: the score reads minutes, meters and violations, and arrivals are built only where a caller asks for them. A scoring exactness test captured a step-limited, fixed-seed solve on three fixtures before the change and asserts the same score, move count, arrangement and independent evaluation after it; the full assertion campaigns in CI exercise the same path.")
    sub("The paired study")
    p("Paired on 24 cases (TABU_KOPT; 20 and 50 technicians; CLUSTERED, DISPERSED, ABSENCE and TIGHT_WINDOW; seeds 17, 23 and 41; fixture seed 7001; 10 s), the engine jar built from the commit before the change was run against the treated one with the same harness classes, one after the other on an otherwise idle workstation. [10]")
    fleet_rows = []
    for fleet in (20, 50):
        fp = [x for x in pairs if x["technicians"] == fleet]
        before_k, after_k = median(x["costPhaseMovesPerSecond"]["before"] for x in fp) / 1000, median(x["costPhaseMovesPerSecond"]["after"] for x in fp) / 1000
        wins = sum(x["afterCostCents"]["after"] < x["afterCostCents"]["before"] for x in fp); ties = sum(x["afterCostCents"]["after"] == x["afterCostCents"]["before"] for x in fp)
        fleet_rows.append([f"{fleet} technicians", f"{before_k:.0f}k to {after_k:.0f}k ({summary['costPhaseSpeedupByFleet'][str(fleet)]['medianCostPhaseSpeedup']:.2f}x)", f"{median(x['fairnessPhaseSpeedup'] for x in fp):.2f}x",
                           f"{wins} of 12" + (f" ({ties} ties, {12 - wins - ties} dearer)" if wins < 12 else "")])
    table(["Fleet", "Cost-phase moves per second, median before to after", "Fairness-phase speedup, median", "Cases cheaper after at the same budget"], fleet_rows, [1.2, 2.3, 1.5, 1.9])
    figure("paired", "Left: each paired case's cost-phase moves per second before (x) and after (y) the treatment; the dotted line is no change and the dashed line is twice the rate. Right: the speedup of each case, three seeds per fleet and workload.")
    loss = [x for x in pairs if x["afterCostCents"]["after"] > x["afterCostCents"]["before"]]
    p(f"The cost phase evaluates {summary['costPhaseSpeedup']['median']:.2f} times the moves per second (median; {summary['costPhaseSpeedup']['min']:.2f} to {summary['costPhaseSpeedup']['max']:.2f} across the 24 cases), {summary['costPhaseSpeedupByFleet']['50']['medianCostPhaseSpeedup']:.2f} times on 50 technicians, where each scored route had more legs to look up. The fairness phase gains {summary['fairnessPhaseSpeedup']['median']:.2f} times, less, because its time goes to the fairness term (a BigDecimal variance over the fleet per move) and the BigDecimal score impacts, which the treatment leaves alone. Two independent runs of the same four cases on each side agreed within 10 percent, so the speedup is not a run-to-run effect. Because a solve now evaluates more moves in the same seconds, {summary['afterCostCentsWinTieLoss']['afterWins']} of 24 cases ended cheaper at the same budget, {summary['afterCostCentsWinTieLoss']['ties']} tied, and {len(loss)} was dearer by {loss[0]['afterCostCents']['after'] - loss[0]['afterCostCents']['before']} cents, with no constraint violation on either side; median peak heap rose from {summary['medianPeakHeapBytes']['before'] / 2 ** 20:.0f} to {summary['medianPeakHeapBytes']['after'] / 2 ** 20:.0f} MiB for the indexed tables.")
    p(f"What this is not: a search-policy change. The acceptor, moves, tabu size and budgets are what the Tabu variant study chose, so the promotion review that belongs to policy changes was not run for this. Two of the audit's items stay out: invariant technician context (TF13) and incremental collectors (TF12) were not named by the profile; and a multi-threaded solve (moveThreadCount) cannot be built, because multithreaded solving is a Timefold Enterprise feature and the solver configuration refuses it for the Community artifact this project builds with. [11] After the treatment the profile's largest shares are BigDecimal ({prof['after']['groups']['BigDecimal arithmetic']:.1f} percent) and the clock arithmetic of Instant and Duration ({prof['after']['groups']['Instant and Duration']:.1f} percent); the next step, if one is wanted, is time as primitive seconds in the timing search, with the same exactness test as its gate.")

    heading("14 The embedded engine against a remote solver pool (K, #164)")
    p(f"The design's third scaling lever moves the calculation out of the scheduler into a solver pool (SCHEDULER_CALCULATION_MODE=REMOTE); it had never been measured. K compares the two on the rehearsal stack with the same load as J2's runs, the solver pool at its compose shape (4 cores, 4 GiB) beside the booking and daily replicas at 2 cores each. The acceptance set beforehand was remote p95 at most 1.05 times embedded. The embedded run is also the first measurement of study L's engine behind the API: it answered {k['k-embedded']['booking']['searches']} searches at {k['k-embedded']['ops']['booking offers']['ms']['p50']} / {k['k-embedded']['ops']['booking offers']['ms']['p95']} ms against J2's {final['booking']['searches']} at {final['ops']['booking offers']['ms']['p50']} / {final['ops']['booking offers']['ms']['p95']} ms on the same build otherwise, a difference within what identical configurations showed in J2, and its engine share is {ms(k['k-embedded']['breakdown'][OFFERS]['calculationMs']['p50'])} p50 against {ms(final['breakdown'][OFFERS]['calculationMs']['p50'])}. [1, 7]")
    table(["Run", "Offers caller p50 / p95", "Slot wait", "Engine share", "Searches in 60 s", "Daily proposals answered", "Daily p50", "Daily replica CPU"],
          [[name, f"{r['ops']['booking offers']['ms']['p50']} / {r['ops']['booking offers']['ms']['p95']} ms", ms(r["breakdown"][OFFERS]["queueMs"]["p50"]), ms(r["breakdown"][OFFERS]["calculationMs"]["p50"]), r["booking"]["searches"],
            f"{d['ops']['daily proposal']['statuses'].get('201', 0)} of {d['daily']['proposals']}" + (f", {d['ops']['daily proposal']['statuses']['503']} answered 503" if "503" in d["ops"]["daily proposal"]["statuses"] else ""),
            seconds(d["ops"]["daily proposal"]["ms"]["p50"]), f"{daily_cpu(r):.0f}%"]
           for name, r, d in (("Embedded (the default)", k["k-embedded"], k_d["k-embedded"]), ("Remote, previous pool defaults (capacity 2, one daily at a time)", k["k-remote"], k_d["k-remote"]),
                              ("Remote, capacity 4, one daily at a time", k["k-remote4"], k_d["k-remote4"]), ("Remote, capacity 4, every slot open to daily work (new defaults)", k["k-remote5"], k_d["k-remote5"]))], [2.0, .95, .55, .6, .6, 1.0, .55, .65], size=9)
    figure("remote", "Left: offers at the caller for the embedded run and the three remote runs, with the searches each answered in 60 s. Right: queued daily proposals answered (solid) or refused with 503 (hatched) in the same runs, with the daily replica's mean CPU.")
    rm, em = k["k-remote5"], k["k-embedded"]
    p(f"Booking fails the acceptance, so the embedded engine stays the default. A remote search costs {rm['ops']['booking offers']['ms']['p50'] / em['ops']['booking offers']['ms']['p50']:.1f} times the embedded one at the caller (p50 {rm['ops']['booking offers']['ms']['p50']} against {em['ops']['booking offers']['ms']['p50']} ms, p95 {rm['ops']['booking offers']['ms']['p95']} against {em['ops']['booking offers']['ms']['p95']} ms) and the replica serves two thirds of the searches. The engine share of a search goes from {ms(em['breakdown'][OFFERS]['calculationMs']['p50'])} to {ms(rm['breakdown'][OFFERS]['calculationMs']['p50'])}: a search makes up to three solver calls (insertion, refinement, and the retry on a moved day), each a snapshot encoded, sent, decoded and answered, and the request thread spends {rm['breakdown'][OFFERS]['cpuMs']['p50'] - em['breakdown'][OFFERS]['cpuMs']['p50']} ms more CPU per search on that encoding ({em['breakdown'][OFFERS]['cpuMs']['p50']} to {rm['breakdown'][OFFERS]['cpuMs']['p50']} ms). Nothing else changes: routing, the database and the confirm ({ms(rm['breakdown'][CONFIRM]['elapsedMs']['p50'])}, no calculation) are the same, as Figure 5 shows.")
    p(f"For a daily pool, remote is the lever it was meant to be. The proposal takes the same {seconds(k_d['k-remote5']['ops']['daily proposal']['ms']['p50'])} p50 (queue wait plus a fixed 5 s search) and the daily replica's CPU falls from {daily_cpu(em):.0f} to {daily_cpu(rm):.0f} percent of its two cores, because the solve runs in the pool. Each pool has its own calculation mode, so the daily pool can go remote while booking stays embedded.")
    p(f"The study also found and fixed a sizing fault. With the pool's previous defaults {k_d['k-remote']['ops']['daily proposal']['statuses']['503']} of {k_d['k-remote']['daily']['proposals']} daily proposals answered 503 CALCULATION_UNAVAILABLE: the solver service built its admission with the scheduler's default of one daily calculation at a time, whatever its capacity, so the second of the two solves a daily replica admits waited behind the first and was refused once it could no longer finish in its allowance. Raising the capacity alone did nothing ({k_d['k-remote4']['ops']['daily proposal']['statuses']['503']} of {k_d['k-remote4']['daily']['proposals']} still refused). The pool now takes SOLVER_BACKGROUND_LIMIT (0, the default, opens every slot to daily work, since a pool protects nothing else) and its default capacity is 4, the sum of the replicas' search capacities on this shape. The rule for an operator: a pool's capacity is the served replicas' search capacities summed, and its background limit at least the daily replicas' background limits summed.")

    heading("15 What should happen next")
    p(f"After J2 the median booking search spends {ms(final['breakdown'][OFFERS]['queueMs']['p50'])} waiting for a slot, {ms(final['breakdown'][OFFERS]['routingMs']['p50'])} routing, {ms(final['breakdown'][OFFERS]['dbMs']['p50'])} in the database and {ms(final['breakdown'][OFFERS]['calculationMs']['p50'])} in the engine, and a confirm no longer waits at all. The daily search does {summary['costPhaseSpeedup']['median']:.1f} times the work in its budget, and a remote pool can take that work off the daily replica. The evidence supports the following order.")
    table(["Question", "Next evidence to collect", "Decision informed"], [
        ("The VM itself", "Repeat J0, J2 and K across the network on the real VM with the load generator on the host's side.", "The latency target measured where it is defined."),
        ("Booking cores", "The slot wait is half of a search; measure five and ten desks at 2, 3 and 4 booking cores, and with a second booking replica.", "The booking pool's size on the VM."),
        ("Daily pool remote", "Run the daily pool REMOTE on the VM with the pool sized by the rule in section 14; confirm 25 of 25 proposals and the replica's CPU.", "Whether the daily pool goes remote at go-live."),
        ("Primitive time in the timing search", "Replace Instant and Duration with seconds in RouteTimingSearch, gated by the scoring exactness test; repeat the 24-case paired study.", "Whether a 5 s search can do still more."),
        ("Fewer solver round trips", "Carry insertion and refinement in one solver call; only if booking is ever to use a pool.", "Whether remote booking could meet the 1.05 bound.")], [1.5, 3.3, 2.1])
    p("Two cautions carry over from the measurements. Compare only fresh-seed runs, because a seed run before leaves its roads cached. And read the VM-shaped numbers as a shape, not a capacity: the rehearsal stack's CPU limits are the compose file's defaults on a workstation, and the real VM's share will set its own numbers.")
    sub("How the calculations were checked")
    p("Every table and chart value is computed from the evidence JSON files the pull requests recorded; nothing is typed in. report_data.py checks each load report before use: the statuses of every operation add up to its calls, no response broke the contract, confirms counted as confirmed agree with the 200s less the simulated host's write conflicts, and each routing benchmark's parity checks passed on all 132 pairs. For study L it checks that every profile's samples add up across its recordings and recomputes every paired summary (medians, extremes, wins and ties) from the 24 cases. verify_report.py recomputes every numerical table and chart dataset from the raw JSON without the builder's helpers and checks the template parts. [1, 9, 10]")

    heading("Appendix A Every booking run")
    p("Every load tool process with booking desks, in the order measured. Searches are the calls answered 201; latencies are at the caller in milliseconds; lost holds are confirms answered 409 HOLD_UNAVAILABLE out of the holds selected. The VM runs (J0 to K) ran beside five queued daily planners. [1]")
    booking_runs = [(pr, r) for key, _, pr, _ in STUDIES for r in (runs[key] + (extras["coldRemeasurement"] if key == "concurrency" else []) + (extras["abComparison"] if key == "shedding" else [])) if r["booking"] is not None]
    table(["PR", "Run", "Desks", "Seed", "Searches", "Offers p50 / p95 / p99", "Confirm p50 / p95", "Lost", "Stale", "Busy"],
          [[pr, r["label"][:30], r["desks"], r["seed"], r["booking"]["searches"], lat(r["ops"]["booking offers"]), f"{r['ops']['confirm']['ms']['p50']} / {r['ops']['confirm']['ms']['p95']}",
            f"{r['booking']['holdUnavailable']}/{r['booking']['selected']}", r["booking"].get("stale", 0), r["ops"]["booking offers"]["retriedBusy"]] for pr, r in booking_runs],
          [.45, 1.5, .45, .45, .6, 1.25, .85, .5, .4, .45], size=9)

    heading("Appendix B Every daily run")
    p("Every load tool process with daily planners. Proposals are the calls made; answered are the 201s; the p50 and p95 are the time to the proposal at the caller, polling included for queued ones; the queue wait is the median time a queued proposal waited before its calculation started. [1]")
    daily_runs = [(pr, r) for key, _, pr, _ in STUDIES for r in runs[key] if r["daily"] is not None]
    table(["PR", "Run", "Planners", "Mode", "Seed", "Proposals", "Answered", "p50 / p95", "Queue wait p50"],
          [[pr, r["label"][:30], r["planners"] or r["desks"], r["mode"] or "sync", r["seed"], r["daily"]["proposals"], r["ops"]["daily proposal"]["statuses"].get("201", 0),
            f"{r['ops']['daily proposal']['ms']['p50'] / 1000:.1f} / {r['ops']['daily proposal']['ms']['p95'] / 1000:.1f} s",
            seconds(queue_wait(r)[0]) if r["daily"].get("queueWaitsMs") else ""] for pr, r in daily_runs], [.45, 1.95, .6, .55, .45, .7, .65, .9, .65], size=9)

    heading("Appendix C Server-side attribution of the VM runs")
    p("From J0 the request log line attributes each call's time. Each row is one operation and status in one run: p50 of the server's elapsed time, the slot wait, routing, calculation engine and database shares, the directed legs the call needed, those answered from a cache, and those fetched from the routing service. [8]")
    rows = []
    for name, r in (("J0 base-spread", by_run(runs["vm"], "base-spread", "booking")), ("J0 base-spread2", vm_b), ("J flexible", jf_b), ("J CH", jc_b), ("J2 priority", j2["j2-confirm"]), ("J2 ZGC / Parallel", j2["j2-gc"]),
                    ("J2 confirm slot", final), ("J2 compact headers", j2["j2-coh2"]), ("K embedded", k["k-embedded"]), ("K remote, old pool", k["k-remote"]), ("K remote, cap. 4", k["k-remote4"]), ("K remote, fixed", k["k-remote5"])):
        for op, entry in sorted(r["breakdown"].items()):
            if "daily" in op or "release" in op or "select" in op:
                continue
            rows.append([name, op.replace("POST /api/v1/booking/", "").replace("holds/{holdId}/", ""), entry["calls"], entry["elapsedMs"]["p50"], entry.get("queueMs", {}).get("p50", ""), entry["routingMs"]["p50"],
                         entry["calculationMs"]["p50"], entry["dbMs"]["p50"], entry["routingPairs"]["p50"], entry["cacheHits"]["p50"], entry["fetched"]["p50"]])
    table(["Run", "Operation", "Calls", "Elapsed", "Slot wait", "Routing", "Engine", "DB", "Legs", "Hits", "Fetched"], rows, [1.15, .9, .5, .6, .65, .6, .55, .45, .45, .45, .6], size=9)
    p("The first J0 run's date spread still overlapped desks by one day, so 77 of its searches were answered STALE; the second run fixed the spread and is the baseline the report uses. The slot wait column is empty for the first run because that measurement was added between the two. In the remote runs the engine column is the time the request thread waited for the solver pool, encoding included.")

    heading("Appendix D Every paired solver case")
    p("The 24 cases of study L, each a 10 s TABU_KOPT solve on fixture seed 7001 with the engine built before the treatment and the treated engine: cost-phase and fairness-phase moves per second, the speedups, and the cost in cents each side ended with at the same budget. No case had a constraint violation on either side. [10]")
    table(["Techs", "Workload", "Seed", "Cost moves/s before", "after", "Speedup", "Fairness speedup", "Cost cents before", "after"],
          [[x["technicians"], x["workload"], x["seed"], f"{x['costPhaseMovesPerSecond']['before']:,}", f"{x['costPhaseMovesPerSecond']['after']:,}", f"{x['costPhaseSpeedup']:.3f}", f"{x['fairnessPhaseSpeedup']:.3f}",
            f"{x['afterCostCents']['before']:,}", f"{x['afterCostCents']['after']:,}"] for x in sorted(pairs, key=lambda x: (x["technicians"], x["workload"], x["seed"]))], [.55, 1.1, .5, .95, .8, .65, .8, .85, .7], size=9)

    heading("Appendix E Definitions and evidence")
    table(["Term", "Meaning in this report"], [
        ("Desk", "One concurrent booking loop in the load tool: search, select, then confirm or release, again and again for the run."),
        ("Planner", "One concurrent daily loop: build a 20-technician day, ask for a proposal, wait for it (or poll it), again."),
        ("Fresh seed", "A run whose random addresses no earlier run used, so its roads are not cached anywhere."),
        ("Slot", "One of a replica's concurrent search permits (SCHEDULER_SEARCH_CAPACITY, 2 in every run here)."),
        ("STALE", "409 answer to a snapshot older than records the scheduler already used; the host rebuilds and resends."),
        ("Cold leg", "A directed road leg no cache held, fetched from the routing service during the call."),
        ("CH", "Contraction hierarchy: a road graph preprocessed with shortcut edges so that a query touches few nodes."),
        ("Routing identity", "A hash of the road graph and map version; every cached leg is bound to one, and a change clears the caches."),
        ("Confirm slot", "A permit beyond the search capacity that only a confirm may take (SCHEDULER_SEARCH_CONFIRM_SLOTS, 1 by default)."),
        ("Solver pool", "The solver-service replicas a scheduler in REMOTE mode sends its calculations to; sized by SOLVER_CAPACITY and SOLVER_BACKGROUND_LIMIT."),
        ("Moves per second", "Candidate moves the daily search evaluated per second of its phase; more moves in the same budget is a better search, not a faster answer."),
        ("Leaf frame", "The method at the top of a sampled stack in a flight recording: where the CPU was at that instant."),
        ("Paired case", "One fleet, workload and seed solved by both engines under the same budget, so that the two results differ only in the engine."),
        ("p50, p95, p99", "The latency that 50, 95 or 99 percent of calls in the run stayed within.")], [1.5, 5.4])
    p("[1] Load studies: docs/evidence/api-load-2026-10-13.json (#151), -booking-concurrency (#152), -daily-admission (#153), -search-outcome (#154), -refinement-shedding (#155), -daily-queue (#157), api-load-2026-10-14-replica-roles (#158), -vm-baseline (#159), -routing (#160), -confirm-priority-and-jvm (#162) and -embedded-vs-remote (#164). Each records the stack, the protocol, the seeds and every load tool report. docs/api-load.md is the narrative kept with the code.")
    p("[2] The load tool: infra/host-conformance/load.ts and src/load (host, scenarios, metrics, report). Its README documents every option; the conformance kit in the same folder proves a host's integration.")
    p("[3] The scheduler's booking path: scheduler-service/src/main/java/dev/waterflex/scheduler/api (BookingOffers, BookingReconciliation, BookingConfirm, DailyProposals), SearchAdmission, SearchDeadline, SearchTelemetry, RoadClient and DirectedLegFlights. docs/stateless-api-design.md describes reconciliation, admission and the route cache tiers.")
    p("[4] The deterministic reproduction of the lost hold is recorded as staleSnapshotReproduction in the #151 and #152 evidence, with its control.")
    p("[5] The searchComplete finding and its fix: docs/api-load.md finding 5, with the result block of the #154 evidence.")
    p("[6] Queued proposals and fair turns: docs/stateless-api-design.md, 'Several scheduler replicas on one database', and the integration guide's section 5.")
    p("[7] Replica roles: docs/stateless-api-design.md, Scaling lever 2, and docs/operations-runbook.md, 'Replica roles'.")
    p("[8] The request log line: docs/operations-runbook.md, 'Logs'. Its fields are queueMs, routingMs, calculationMs, dbMs, dbCalls, routingPairs, cacheHits, fetched and cpuMs.")
    p("[9] Routing benchmarks: docs/evidence/routing-ch-omaha-2026-09-24.json (map 2026-09-16, revision 29f1e04) and docs/evidence/routing-ch-2026-10-14.json (map 2026-10-13, revision 96be60a), produced by infra/benchmark-routing-ch.mjs. The second notes that both instances must run with routing.cache.max-entries=1.")
    p("[10] Solver profiling: docs/evidence/solver-profiling-2026-10-14.json (the two profiles' recordings, samples and leaf frames, the move rates, and the 24 paired cases with both sides' provenance), produced by the scheduler-service SolverBenchmark harness with -Dbenchmark.jfr; docs/solver-benchmarks.md, 'Solve-scoped profiling and the CR07 treatment'; the exactness gate is scheduler-service's ScoringExactnessTest.")
    p("[11] Timefold Solver, 'Commercial editions', https://docs.timefold.ai/timefold-solver/latest/commercial-editions/commercial-editions (retrieved 2026-10-14): multithreaded solving is listed among the Enterprise features.")
    p("[12] OpenJDK, JEP 519 'Compact Object Headers', https://openjdk.org/jeps/519 (retrieved 2026-10-14): the feature became a product option in JDK 25, after its experimental form in JDK 24, and is off by default.")
    p("Reproduce with docs/performance-work-2026-10-14/requirements.txt, then run report_data.py, build_report.py and verify_report.py there. The builder reads the evidence files, regenerates all twelve charts and chart_data.json, and writes this editable Word file. EVIDENCE.md records methods.")

    settings = doc.settings.element
    for el in settings.findall(qn("w:updateFields")): settings.remove(el)
    update = OxmlElement("w:updateFields"); update.set(qn("w:val"), "true"); settings.append(update)
    stream = BytesIO(); doc.save(stream)
    with ZipFile(REFERENCE) as ref, ZipFile(stream) as built, ZipFile(OUTPUT, "w", ZIP_DEFLATED) as output:
        preserve = set(PRESERVE) | {n for n in ref.namelist() if "footer" in n}
        for name in sorted(built.namelist()):
            content = ref.read(name) if name in preserve else built.read(name)
            info = ZipInfo(name, (2026, 10, 14, 0, 0, 0)); info.compress_type = ZIP_DEFLATED
            output.writestr(info, content)
    print(OUTPUT)


if __name__ == "__main__": build()
