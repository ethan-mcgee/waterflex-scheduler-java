"""Build the performance work report from the validated load studies and the selected explainer.

Install requirements.txt first. No network, services or benchmark execution.
"""
from copy import deepcopy
from datetime import datetime
from hashlib import sha256
from io import BytesIO
from pathlib import Path
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
    p("October 14 2026  |  Load studies of pull requests #151 to #160, 2026-10-13 to 2026-10-14")
    p(f"Over two days the scheduler's public API was driven by a load tool that plays WaterFlex Software: booking desks searching, holding and confirming appointments at new addresses, and planners asking for optimized days. Each pull request in the series measured the service before and after its change, on fresh addresses, with the same tool. Across {total_runs} recorded load runs, five booking desks went from {per_minute(b[5]):.0f} answered searches per minute at a p95 of {seconds(b[5]['ops']['booking offers']['ms']['p95'])} to {per_minute(jc_b):.0f} per minute at {ms(jc_b['ops']['booking offers']['ms']['p95'])}, with no hold lost and every search complete. [1]")
    sub("What the measurements tell us")
    table(["Stage", "Change judged", "Five desks: searches per minute", "Offers p95 at the caller"], [
        ["#151", "Load tool; the first measurement", f"{per_minute(b[5]):.0f}", ms(b[5]["ops"]["booking offers"]["ms"]["p95"])],
        ["#152", "Late snapshots keep holds; bookings publish without waiting", f"{per_minute(fresh[5]):.0f}", ms(fresh[5]["ops"]["booking offers"]["ms"]["p95"])],
        ["#154", "searchComplete means what the spec says", f"{per_minute(outcome):.0f}", ms(outcome["ops"]["booking offers"]["ms"]["p95"])],
        ["#155", "Optional refinement shed while bookings wait", f"{per_minute(shed[5]):.0f}", ms(shed[5]["ops"]["booking offers"]["ms"]["p95"])],
        ["J0 (#159)", "VM shape, daily planners beside the desks, time attributed", f"{per_minute(vm_b):.0f}", ms(vm_b["ops"]["booking offers"]["ms"]["p95"])],
        ["J (#160)", "CH graph, parallel pairs, identity cache", f"{per_minute(jc_b):.0f}", ms(jc_b["ops"]["booking offers"]["ms"]["p95"])]], [.9, 3.3, 1.5, 1.2])
    p("The first four rows ran on one machine with the desks alone; the last two ran the VM rehearsal stack with its CPU limits and five daily planners working beside the desks, so the two halves are not one series. Within each half the comparisons hold, and the daily side improved as well: a queued day's proposal went from a 503 after nine seconds of waiting to a fair turn in a queue, and two replica pools nearly doubled the days optimized per minute.")
    p("The report explains each result through what the service recorded: which calls waited for a slot, how many road legs were fetched cold, how many requests were refused and why, and later the time each call spent in admission, routing, the calculation engine and the database. Latencies are measured at the caller, including every wait a host would see. None of it is customer traffic; the addresses are random points inside Omaha and the days are synthetic.")
    sub("How to read this report")
    table(["Sections", "What you will learn"], [("1 to 3", "What was measured, how a booking search spends its time, and the first measurement."),
                                                ("4 to 6", "The booking fixes: late snapshots, same-date contention, and the optional refinement."),
                                                ("7 to 9", "The daily side: early refusal, fair queuing, and separate replica pools."),
                                                ("10 and 11", "The VM-shaped baseline with time attributed, and the routing work."),
                                                ("12 and appendices", "What should happen next, how the numbers were checked, every run, and sources.")], [1.45, 5.45])

    heading("1 What was measured")
    p("The scheduler is reached only through its public API, so the load tool (infra/host-conformance/load.ts) plays the host. A booking desk searches for a new 60-minute job at a fresh address, selects one of the offers, and either confirms it and writes the receipt as WaterFlex Software would (30 percent of searches) or releases it. A daily planner sends one metro day of 20 technicians with two visits each and asks for a cost search of 5 seconds. Every run lasts 60 or 90 seconds and uses its own random seed, so no run reuses another's addresses or cached roads. [1, 2]")
    table(["Dimension", "Values in these studies"], [
        ("Booking desks", "1, 5 or 10 concurrent desks searching the same five dates (10 technicians with 2 visits each per date); from J0, each desk its own dates"),
        ("Daily planners", "1, 2, 4 or 5 concurrent planners, synchronous or queued (Prefer: respond-async)"),
        ("Policy", "offerLimit 4, windowHours 2, bookingDeadlineMs 5000, regularWindowThreshold 2, daily search TABU for 5 s"),
        ("Addresses", "Random points inside 41.22 to 41.28 N, 96.05 to 95.95 W (Omaha); cold means every job at a new point, warm cycles 8 points"),
        ("Stack", "One scheduler jar with 2 search slots, the Omaha routing service (map 2026-10-13), Postgres 16; from J0 the deploy/vm rehearsal stack with 2 cores per scheduler pool"),
        ("Machine", "One 24-processor workstation (AMD Ryzen 9 7900X, 63 GiB) running scheduler, routing, database and the load generator")], [1.4, 5.5])
    table(["Study", "Evidence file", "PR", "Runs", "Change judged"],
          [[key, name.replace("api-load-", ""), pr, len(runs[key]) + (len(extras["coldRemeasurement"]) if key == "concurrency" else 0) + (len(extras["abComparison"]) if key == "shedding" else 0), note]
           for key, name, pr, note in STUDIES], [.85, 2.0, .5, .45, 3.1], size=9)
    p("A run's numbers come from three places: the load tool's own clock at the caller (p50, p95 and p99 of each operation, with every 429 wait a host would retry included), the scheduler's metrics endpoint scraped at the end (routing pairs, identity requests, admission rejections, CPU and heap), and docker stats of the routing container. From J0 a fourth source is the request log line, which attributes each call's time (section 10).")
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
    p("Daily throughput nearly doubled and a queued day waited a third as long, because the daily replica runs two calculations at once while the single replica runs one. Bookings kept their tail: offers p95 fell and the calls refused with 429 fell from 15 and 24 to 4, because no daily calculation competes for the booking replica's cores or slots. The cost is confirm latency: with two booking slots instead of up to four, a confirm waits behind searches for a slot, and its p50 rose from about 130 ms to about 400 ms. That wait is the subject of section 10.")

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
    figure("attribution", "Server time of the median booking call, split into slot wait, routing, calculation engine and database, for the VM baseline (J0) and the two J runs. The confirm has almost no work of its own; its bar is the wait behind searches.")

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

    heading("12 What should happen next")
    p(f"After J the median booking search spends {ms(jc_b['breakdown'][OFFERS]['queueMs']['p50'])} waiting for a slot, {ms(jc_b['breakdown'][OFFERS]['routingMs']['p50'])} routing, {ms(jc_b['breakdown'][OFFERS]['dbMs']['p50'])} in the database and {ms(jc_b['breakdown'][OFFERS]['calculationMs']['p50'])} in the engine, and a confirm still waits {ms(jc_b['breakdown'][CONFIRM]['queueMs']['p50'])} of its {ms(jc_b['breakdown'][CONFIRM]['elapsedMs']['p50'])} behind searches. The evidence supports the following order.")
    table(["Question", "Next evidence to collect", "Decision informed"], [
        ("Confirm before search?", "Let a confirm (a few ms of work) take a slot ahead of waiting searches; measure confirm p50 and offers p95 on the same stack.", "Whether admission order changes."),
        ("JVM and container settings", "A short A/B of the garbage collector and heap flags per pool, on the rehearsal stack; change a default only on a 5 percent or better win.", "The Dockerfile and compose defaults."),
        ("Daily moves per second", "Profile each daily solve on its own (JFR scoped to the solve), then test the one treatment the profile names, and a two-thread variant on the daily pool.", "Whether a 5 s search can do more."),
        ("Embedded or remote engine", "The same load against SCHEDULER_CALCULATION_MODE=REMOTE; remote p95 at most 1.05 times embedded.", "Whether the solver pool is a scaling lever."),
        ("The VM itself", "Repeat J0 and J across the network on the real VM with the load generator on the host's side.", "The latency target measured where it is defined.")], [1.5, 3.3, 2.1])
    p("Two cautions carry over from the measurements. Compare only fresh-seed runs, because a seed run before leaves its roads cached. And read the VM-shaped numbers as a shape, not a capacity: the rehearsal stack's CPU limits are the compose file's defaults on a workstation, and the real VM's share will set its own numbers.")
    sub("How the calculations were checked")
    p("Every table and chart value is computed from the evidence JSON files the pull requests recorded; nothing is typed in. report_data.py checks each load report before use: the statuses of every operation add up to its calls, no response broke the contract, confirms counted as confirmed agree with the 200s less the simulated host's write conflicts, and each routing benchmark's parity checks passed on all 132 pairs. verify_report.py recomputes every numerical table and chart dataset from the raw JSON without the builder's helpers and checks the template parts. [1, 9]")

    heading("Appendix A Every booking run")
    p("Every load tool process with booking desks, in the order measured. Searches are the calls answered 201; latencies are at the caller in milliseconds; lost holds are confirms answered 409 HOLD_UNAVAILABLE out of the holds selected. The VM runs (J0 and J) ran beside five queued daily planners. [1]")
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
    for name, r in (("J0 base-spread", by_run(runs["vm"], "base-spread", "booking")), ("J0 base-spread2", vm_b), ("J flexible", jf_b), ("J CH", jc_b)):
        for op, entry in sorted(r["breakdown"].items()):
            if "daily" in op or "release" in op or "select" in op:
                continue
            rows.append([name, op.replace("POST /api/v1/booking/", "").replace("holds/{holdId}/", ""), entry["calls"], entry["elapsedMs"]["p50"], entry.get("queueMs", {}).get("p50", ""), entry["routingMs"]["p50"],
                         entry["calculationMs"]["p50"], entry["dbMs"]["p50"], entry["routingPairs"]["p50"], entry["cacheHits"]["p50"], entry["fetched"]["p50"]])
    table(["Run", "Operation", "Calls", "Elapsed", "Slot wait", "Routing", "Engine", "DB", "Legs", "Hits", "Fetched"], rows, [1.05, 1.0, .5, .6, .65, .6, .55, .45, .45, .45, .6], size=9)
    p("The first J0 run's date spread still overlapped desks by one day, so 77 of its searches were answered STALE; the second run fixed the spread and is the baseline the report uses. The slot wait column is empty for the first run because that measurement was added between the two.")

    heading("Appendix D Definitions and evidence")
    table(["Term", "Meaning in this report"], [
        ("Desk", "One concurrent booking loop in the load tool: search, select, then confirm or release, again and again for the run."),
        ("Planner", "One concurrent daily loop: build a 20-technician day, ask for a proposal, wait for it (or poll it), again."),
        ("Fresh seed", "A run whose random addresses no earlier run used, so its roads are not cached anywhere."),
        ("Slot", "One of a replica's concurrent search permits (SCHEDULER_SEARCH_CAPACITY, 2 in every run here)."),
        ("STALE", "409 answer to a snapshot older than records the scheduler already used; the host rebuilds and resends."),
        ("Cold leg", "A directed road leg no cache held, fetched from the routing service during the call."),
        ("CH", "Contraction hierarchy: a road graph preprocessed with shortcut edges so that a query touches few nodes."),
        ("Routing identity", "A hash of the road graph and map version; every cached leg is bound to one, and a change clears the caches."),
        ("p50, p95, p99", "The latency that 50, 95 or 99 percent of calls in the run stayed within.")], [1.5, 5.4])
    p("[1] Load studies: docs/evidence/api-load-2026-10-13.json (#151), -booking-concurrency (#152), -daily-admission (#153), -search-outcome (#154), -refinement-shedding (#155), -daily-queue (#157), api-load-2026-10-14-replica-roles (#158), -vm-baseline (#159) and -routing (#160). Each records the stack, the protocol, the seeds and every load tool report. docs/api-load.md is the narrative kept with the code.")
    p("[2] The load tool: infra/host-conformance/load.ts and src/load (host, scenarios, metrics, report). Its README documents every option; the conformance kit in the same folder proves a host's integration.")
    p("[3] The scheduler's booking path: scheduler-service/src/main/java/dev/waterflex/scheduler/api (BookingOffers, BookingReconciliation, BookingConfirm, DailyProposals), SearchAdmission, SearchDeadline, SearchTelemetry, RoadClient and DirectedLegFlights. docs/stateless-api-design.md describes reconciliation, admission and the route cache tiers.")
    p("[4] The deterministic reproduction of the lost hold is recorded as staleSnapshotReproduction in the #151 and #152 evidence, with its control.")
    p("[5] The searchComplete finding and its fix: docs/api-load.md finding 5, with the result block of the #154 evidence.")
    p("[6] Queued proposals and fair turns: docs/stateless-api-design.md, 'Several scheduler replicas on one database', and the integration guide's section 5.")
    p("[7] Replica roles: docs/stateless-api-design.md, Scaling lever 2, and docs/operations-runbook.md, 'Replica roles'.")
    p("[8] The request log line: docs/operations-runbook.md, 'Logs'. Its fields are queueMs, routingMs, calculationMs, dbMs, dbCalls, routingPairs, cacheHits, fetched and cpuMs.")
    p("[9] Routing benchmarks: docs/evidence/routing-ch-omaha-2026-09-24.json (map 2026-09-16, revision 29f1e04) and docs/evidence/routing-ch-2026-10-14.json (map 2026-10-13, revision 96be60a), produced by infra/benchmark-routing-ch.mjs. The second notes that both instances must run with routing.cache.max-entries=1.")
    p("Reproduce with docs/performance-work-2026-10-14/requirements.txt, then run report_data.py, build_report.py and verify_report.py there. The builder reads the evidence files, regenerates all eight charts and chart_data.json, and writes this editable Word file. EVIDENCE.md records methods.")

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
