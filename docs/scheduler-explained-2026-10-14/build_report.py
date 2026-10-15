"""Build "WaterFlex scheduler explained" from the validated sources. Install requirements.txt first. No network or benchmark runs.

The document is a trail: Part I is the stateless API, Part II how the work was measured, Part III the five optimization threads in the order they
happened, Part IV the system as it stands, and Part V what comes next.
"""
import re
from copy import deepcopy
from datetime import datetime
from hashlib import sha256
from io import BytesIO
from pathlib import Path
from statistics import mean
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_CELL_VERTICAL_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor
from PIL import Image, ImageChops

from charts import by_label, by_run, make_charts
from report_data import load, wtl

HERE = Path(__file__).resolve().parent
REFERENCE = HERE.parent / "WaterFlex_Scheduler_Algorithm_Comparison_Explained.docx"
REFERENCE_SHA = "5366f3017d6f18c15de295ba5fb9c1a014b6222911040f1c5e044845e57f832d"
OUTPUT = HERE / "WaterFlex_Scheduler_Explained_2026-10-14.docx"
# Figures fill the 6.9 inch text column (less a hair) and stop at 6.4 inches tall; renders are 3 pixels per canvas unit, and
# a 12 unit margin is kept around each drawing once its blank border is trimmed.
FIGURE_WIDTH, FIGURE_MAX_HEIGHT, FIGURE_PAD_PX = 6.85, 6.4, 36
PRESERVE = ("word/styles.xml", "word/stylesWithEffects.xml", "word/numbering.xml", "word/theme/theme1.xml", "word/fontTable.xml")
OFFERS, CONFIRM = "POST /api/v1/booking/offers 201", "POST /api/v1/booking/holds/{holdId}/confirm 200"
CITATION = re.compile(r"\[\d")


def ms(value):
    return f"{value:,} ms"


def seconds(value_ms):
    return f"{value_ms / 1000:.1f} s"


def dollars(value):
    return f"-${-value:,.2f}" if value < 0 else f"${value:,.2f}"


def pct(part, whole):
    return f"{part / whole * 100:.0f}%"


def by(rows, solver, **kw):
    out = [r for r in rows if r["solver"] == solver and all(r[k] == v for k, v in kw.items())]
    assert out, (solver, kw)
    return out


def saving(rows, solver, **kw):
    return mean(r["saving"] for r in by(rows, solver, **kw))


def wtl_text(rows):
    w, t, l = wtl(rows)
    return f"{w} wins, {t} ties and {l} losses"


def build():
    assert sha256(REFERENCE.read_bytes()).hexdigest() == REFERENCE_SHA
    data = load()
    charts = make_charts(data, HERE / "generated")
    docs, perf, daily, b24, f29, r24 = data["docs"], data["perf"], data["daily"], data["booking24"], data["field29"], data["routing24"]
    runs, extras, benchmarks, per_minute, queue_wait = perf["runs"], perf["extras"], perf["benchmarks"], perf["per_minute"], perf["queue_wait"]
    doc = Document(REFERENCE)
    table_properties = deepcopy(doc.tables[0]._tbl.tblPr)
    for child in list(doc.element.body):
        if child.tag != qn("w:sectPr"): doc.element.body.remove(child)
    for rid, rel in list(doc.part.rels.items()):
        if rel.reltype.endswith(("/image", "/hyperlink")): del doc.part.rels[rid]
    doc.core_properties.title = "WaterFlex scheduler explained"
    doc.core_properties.subject = "The stateless scheduling API, the optimization work behind it, and what comes next"
    doc.core_properties.author = doc.core_properties.last_modified_by = "WaterFlex"
    doc.core_properties.created = doc.core_properties.modified = datetime(2026, 10, 14)
    figures = []

    def clean(text):
        assert "—" not in text and "–" not in text, text[:60]
        assert not CITATION.search(text), text[:80]
        return text

    def p(text, style=None):
        para = doc.add_paragraph(clean(text), style); para.paragraph_format.widow_control = True
        return para

    def lead(head, text):
        """A paragraph that opens with a bold phrase: a component, a step or a thread's handoff."""
        para = doc.add_paragraph(); para.paragraph_format.widow_control = True
        para.add_run(clean(head) + " ").bold = True
        para.add_run(clean(text))
        return para

    def part(text):
        h = p(text, "Heading 1"); h.paragraph_format.keep_with_next = True; h.paragraph_format.page_break_before = True

    def heading(text):
        h = p(text, "Heading 2"); h.paragraph_format.keep_with_next = True

    def sub(text):
        h = p(text, "Heading 3"); h.paragraph_format.keep_with_next = True

    def handed_on(text):
        lead("What this thread handed on.", text)

    def table(headers, rows, widths, size=10, keep=True):
        assert abs(sum(widths) - 6.9) < .01, widths
        t = doc.add_table(rows=1, cols=len(headers)); t.autofit = False; t.alignment = WD_TABLE_ALIGNMENT.CENTER
        t._tbl.replace(t._tbl.tblPr, deepcopy(table_properties))
        for col, w in zip(t.columns, widths): col.width = Inches(w)
        for i, values in enumerate([headers, *rows]):
            assert len(values) == len(headers), values
            row = t.rows[0] if i == 0 else t.add_row()
            trpr = row._tr.get_or_add_trPr(); trpr.append(OxmlElement("w:cantSplit"))
            if i == 0: trpr.append(OxmlElement("w:tblHeader"))
            for j, (cell, value, width) in enumerate(zip(row.cells, values, widths)):
                clean(str(value))
                cell.width = Inches(width); cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER; cell.text = str(value)
                props = cell._tc.get_or_add_tcPr()
                shade = OxmlElement("w:shd"); shade.set(qn("w:fill"), "DCE6EF" if i == 0 else ("FFFFFF" if i % 2 else "F4F6F8")); props.append(shade)
                margins = OxmlElement("w:tcMar")
                for side in ("top", "left", "bottom", "right"):
                    n = OxmlElement("w:" + side); n.set(qn("w:w"), "65"); n.set(qn("w:type"), "dxa"); margins.append(n)
                props.append(margins)
                para = cell.paragraphs[0]; para.paragraph_format.space_after = Pt(2); para.paragraph_format.space_before = Pt(2); para.paragraph_format.line_spacing = 1.0
                if len(headers) > 2 and j > 0: para.alignment = 1
                if keep and len(rows) <= 12 and i < len(rows): para.paragraph_format.keep_with_next = True
                for run in para.runs: run.font.name = "Calibri"; run.font.size = Pt(size); run.bold = i == 0
        spacer = p(""); spacer.paragraph_format.space_after = Pt(0); spacer.paragraph_format.line_spacing = Pt(3)

    def fitted(png):
        """Trim a rendered figure to its drawing and size it for the page: as wide as the text column, unless that would make
        it taller than FIGURE_MAX_HEIGHT, so a figure never crowds its caption and the next paragraph off the page."""
        image = Image.open(png).convert("RGB")
        ink = ImageChops.difference(image, Image.new("RGB", image.size, "white")).convert("L").point(lambda v: 255 if v > 8 else 0)
        box = ink.getbbox()
        assert box is not None, f"{png.name} is blank"
        pad = FIGURE_PAD_PX
        image = image.crop((max(box[0] - pad, 0), max(box[1] - pad, 0), min(box[2] + pad, image.width), min(box[3] + pad, image.height)))
        width = min(FIGURE_WIDTH, FIGURE_MAX_HEIGHT * image.width / image.height)
        stream = BytesIO(); image.save(stream, "PNG"); stream.seek(0)
        return stream, width

    def figure(name, caption):
        assert name not in figures, name
        figures.append(name)
        caption = f"Figure {len(figures)}. {caption}"
        image, width = fitted(HERE / "generated" / f"{name}.png")
        para = p(""); para.alignment = WD_ALIGN_PARAGRAPH.CENTER
        shape = para.add_run().add_picture(image, width=Inches(width))
        shape._inline.docPr.set("descr", clean(caption)); para.paragraph_format.keep_with_next = True
        cap = p(caption, "Caption"); cap.paragraph_format.space_after = Pt(7)
        for run in cap.runs:
            run.font.color.rgb = RGBColor(0, 0, 0); run.bold = False; run.italic = True

    # The runs and rows the narrative refers to by name.
    b5, b1, b10 = (by_label(runs["baseline"], f"booking-cold-c{c}") for c in (5, 1, 10))
    b_daily = {c: by_label(runs["baseline"], f"daily-c{c}") for c in (1, 4)}
    fresh = {n: by_label(extras["coldRemeasurement"], f"cold-recheck-c{n} (seed {s})") for n, s in ((1, 93), (5, 91), (10, 92))}
    c10 = by_label(runs["concurrency"], "booking-cold-c10")
    a_daily = {n: by_label(runs["admission"], f"daily-c{n}") for n in (1, 4)}
    ab_before, ab_after = extras["abComparison"]
    outcome = extras["outcome"]
    fair = {(r["note"], r["desks"]): r for r in runs["queue"] if r["note"] in ("sync", "async")}
    roles = {run: (by_run(runs["roles"], run, "booking"), by_run(runs["roles"], run, "daily")) for run in ("all-1", "split-1", "split-2", "all-2")}
    vm_b = by_run(runs["vm"], "base-spread2", "booking")
    jf_b, jc_b = by_run(runs["routing"], "j-flex", "booking"), by_run(runs["routing"], "j-ch", "booking")
    j2 = {run: by_run(runs["priority"], run, "booking") for run in ("j2-confirm", "j2-gc", "j2-slot", "j2-coh2")}
    final = j2["j2-slot"]
    k = {run: by_run(runs["remote"], run, "booking") for run in ("k-embedded", "k-remote", "k-remote4", "k-remote5")}
    k_d = {run: by_run(runs["remote"], run, "daily") for run in k}
    prof = extras["profiling"]; pairs, summary = prof["pairs"], prof["summary"]
    ch_new = {(l["engine"], l["concurrency"]): l for l in benchmarks["2026-10-14"]["latency"]}
    ch_old = {(l["engine"], l["concurrency"]): l for l in benchmarks["2026-09-24"]["latency"]}
    routing_cpu = lambda r: [x for x in r["containers"] if "routing" in x["name"]][0]["cpuPercent"]["mean"]
    booking_cpu = lambda r: [x for x in r["containers"] if "booking" in x["name"]][0]["cpuPercent"]["mean"]
    daily_cpu = lambda r: [x for x in r["containers"] if "daily" in x["name"]][0]["cpuPercent"]["mean"]
    per_leg = lambda r: r["breakdown"][OFFERS]["routingMs"]["p50"] / r["breakdown"][OFFERS]["fetched"]["p50"]
    part_ms = lambda r, key, op=OFFERS: r["breakdown"][op][key]["p50"]
    F, M, L, C = daily["first"]["rows"], daily["follow"]["rows"], daily["follow"]["long"], daily["follow"]["confirm"]
    FC = daily["first"]["confirm"]
    tk_first, tk_follow, s3_follow = by(F, "TABU_KOPT"), by(M, "TABU_KOPT"), by(M, "TABU_KOPT_SIZE_3")
    late_family = [r for r in F if r["solver"] in ("KOPT", "SUBLIST", "LATE_ACCEPTANCE")]
    tk_twins = {r["key"][:-1]: r for r in tk_follow}
    direct = [tk_twins[r["key"][:-1]]["accepted_cost"] - r["accepted_cost"] for r in s3_follow]
    held = {v: f29["booking"][("held", v)] for v in ("INSERTION", "BOUNDED", "SHARED")}
    omaha = r24["omaha"]
    total_runs = sum(len(v) for v in runs.values()) + len(extras["coldRemeasurement"]) + len(extras["abComparison"])
    daily_cases = len(F) + len(FC) + len(M) + len(L) + len(C)
    workloads = ("CLUSTERED", "DISPERSED", "MIXED_SKILL", "TIGHT_WINDOW")
    late = charts["late_mechanics"]; best = late["best_fraction"]
    var_first = {e["solver"]: e["value"] for e in late["variance"][0]["rows"]}; var_follow = {e["solver"]: e["value"] for e in late["variance"][1]["rows"]}
    arm = charts["long_arm"]["fleets"]
    groups_before, groups_after = prof["before"]["groups"], prof["after"]["groups"]
    loss = [x for x in pairs if x["afterCostCents"]["after"] > x["afterCostCents"]["before"]]
    pf = {e["fleet"]: e for e in charts["paired_fleets"]}
    fixtures_first, fixtures_follow = daily["first"]["fixtures"], daily["follow"]["fixtures"]

    # Facts the narrative depends on, checked before any prose uses them.
    assert all(saving(F, "TABU_KOPT", fleet=f) > 0 for f in (5, 10, 20, 50)) and all(saving(F, "TABU_KOPT", workload=w) > 0 for w in workloads)
    assert all(saving(M, "TABU_KOPT", fleet=f) > 0 for f in (5, 10, 20, 30, 50))
    assert all(saving(M, "TABU_KOPT_SIZE_3", fleet=f) > saving(M, "TABU_KOPT", fleet=f) for f in (10, 20, 30, 50)) and saving(M, "TABU_KOPT_SIZE_3", fleet=5) < saving(M, "TABU_KOPT", fleet=5)
    assert wtl(by(C, "TABU_KOPT_SIZE_3")) == (16, 0, 0)
    assert all(saving(F, s, fleet=50) < 0 for s in ("KOPT", "SUBLIST", "LATE_ACCEPTANCE"))
    assert held["BOUNDED"]["served"] > held["INSERTION"]["served"] > held["SHARED"]["served"]
    assert b24["paid_min"] > 0 and b24["cost"] > 0
    assert k["k-remote5"]["ops"]["booking offers"]["ms"]["p95"] > 1.05 * k["k-embedded"]["ops"]["booking offers"]["ms"]["p95"]
    assert summary["afterCostCentsWinTieLoss"]["afterWins"] == 21 and summary["violationsAfter"] == 0 and len(loss) == 1
    assert best["LATE_ACCEPTANCE"][-1] > 0.9 and best["TABU_KOPT"][-1] < 0.75
    assert arm["50"]["LATE_ACCEPTANCE"][0] < 0 < arm["50"]["LATE_ACCEPTANCE"][2] and arm["50"]["KOPT"][0] < 0 < arm["50"]["KOPT"][1]
    assert all(e["after_lost"] == 0 for e in charts["holds"]) and all(e["after_p95"] < 5500 for e in charts["holds"])
    assert abs(omaha["flexible"]["served"] - omaha["ch"]["served"]) < 10 and abs(omaha["flexible"]["p95Ms"] - omaha["ch"]["p95Ms"]) < 50
    assert jc_b["booking"]["searches"] > jf_b["booking"]["searches"] > vm_b["booking"]["searches"]
    assert var_follow["TABU"] < var_follow["TABU_KOPT"] < var_follow["TABU_KOPT_SIZE_3"] and var_first["TABU"] < var_first["TABU_KOPT"]
    assert groups_after["BigDecimal arithmetic"] > groups_before["BigDecimal arithmetic"] and groups_after["HashMap and copies"] < groups_before["HashMap and copies"]
    assert ch_old[("flexible", 1)]["p50Ms"] / ch_old[("CH-car-v1", 1)]["p50Ms"] > 10

    # ---- Front matter -------------------------------------------------------------------------------------------------------
    p("WaterFlex scheduler explained", "Title")
    p("The stateless scheduling API, the optimization work behind it, and what comes next", "Subtitle")
    p("October 14 2026  |  Covers the work from the September 19 demonstration to 2026-10-14")
    p("At the September demonstration the scheduler was a module of the portal: it read its own copy of the portal's data, ran its own jobs and kept its own settings. Since then it has become a service with no screens and no copy of anyone's data. WaterFlex Software sends a snapshot of a metro day or a booking request to /api/v1 and gets back offers, optimized routes or a receipt, and the scheduler keeps only the thin store it needs to keep customer promises safe when several desks book at once.")
    p(f"Underneath, the same month produced five threads of optimization work. The daily search changed from Tabu to Tabu + K opt after {daily_cases:,} matched solver cases on synthetic days. The booking search kept bounded rearrangement and learned to give up its optional second pass when others wait. The road graph became a contraction hierarchy once the time inside a booking search was attributed part by part. Admission gained a rule that stops late snapshots from losing holds, a fair queue, separate pools and a confirm that never waits. And the engine itself evaluates almost twice the moves in the same seconds.")
    p("The solver cases are synthetic days with a synthetic travel matrix, and the load runs use random Omaha addresses against a rehearsal stack on one workstation. Each section says what its numbers can and cannot support. Part I explains the system; Part II explains how it was measured; Part III follows the five threads; Part IV is the system today; and Part V is what comes next. Appendix A lists every study and evidence file, and Appendix B defines the terms and measures.")

    # ---- Part I ------------------------------------------------------------------------------------------------------------
    part("Part I  The stateless API")
    p("Part I explains the system as it stands, from the outside in: who owns what, where it runs, the contract, the timestamp the design rests on, how one request flows, how many clients and replicas share it, and how a location becomes a road leg. Every later thread changed something in one of these pieces, so this is the map the rest of the trail refers to.")
    figure("stateless", "The scheduler as a service of its own. WaterFlex Software keeps every record and calls the scheduler API over /api/v1 with a snapshot of the facts a call needs; the scheduler answers with results and keeps only its thin store. The host writes its own database with a compare-and-set on the timestamps the scheduler returns.")
    p("Three consequences follow from this shape. The service has no screens, so every map and schedule is rendered by WaterFlex Software from data the scheduler returns. The service reads no policy of its own: every rule a client chooses (its fairness budget, its daily search method and times, its booking deadline and window threshold) arrives with every call, and a missing value is refused, never assumed. And no client library ships: the product stays Java on its own VM, and a host integrates from any language against the OpenAPI contract.")
    heading("1 Who owns what")
    p("The scheduler is an add-on to WaterFlex Software. WaterFlex Software owns master data (customers, addresses, jobs, appointments, technicians, qualifications, shifts, time off, depots) and the business record of every appointment. The scheduler owns only what it must to keep customer promises safe when several desks book at once: the holds and the arrangement they depend on, the offer sets and proposals it issued, and the receipts of what was committed. Calculation and routing are stateless: complete data in, results out.")
    figure("ownership", "Who owns what. WaterFlex Software keeps every business record and sends a snapshot of the facts a call needs; the scheduler answers with offers, proposals and receipts, and keeps only the thin store. The host writes the business record itself, with a compare-and-set on the timestamps the receipt names.")
    p("The arrows in the figure are the whole relationship. A snapshot goes in with every calculating call; results come out; and a receipt tells the host exactly which technician-days to write and which timestamps to compare. The scheduler never writes the host's records and never reads them except through the snapshot it was sent.")
    heading("2 Where it runs")
    figure("topology", "The scheduler VM and its host. WaterFlex Software calls one address; behind it the proxy sends booking calls to the booking pool and proposals to the daily pool. Both pools embed the calculation engine and reach the same three shared pieces. The management port is private and not behind the proxy.")
    p("The scheduler runs on its own VM with a partitioned share of compute, on the same private network as the rest of WaterFlex Software, reached only through https://<scheduler-vm>/api/v1. No replica holds state of its own and all share the one thin store, so which replica answers a call never changes the answer. The pieces in the figure each have one job.")
    lead("The proxy.", "scheduler-proxy is nginx on port 8000 and the one address a host calls. Daily and repair proposals and request status go to the daily pool; everything else goes to the booking pool. It needs no sticky sessions.")
    lead("Booking replicas.", "They answer booking offers, select, release and confirm, and route evaluation and geometry, with the calculation engine embedded in the same process. They take no queued daily work, so a long overnight optimization never competes with a customer on the phone.")
    lead("Daily replicas.", "They take daily and repair proposals, synchronously or from the durable queue, and may run several calculations at once.")
    lead("Routing.", "Each served metro has its own routing-service, loaded with that metro's road graph. It turns coordinates into directed road legs and sees only coordinates, never client data, so every client in a metro shares it.")
    lead("Nominatim.", "Optional. In NOMINATIM mode it locates a location that arrives with an address and no coordinates, and only as an exact house.")
    lead("The database.", "scheduler-db is Postgres 16 and holds the thin store, tenants and tokens, and the route cache. The scheduler creates and migrates it itself; nothing else connects to it.")
    lead("The solver service.", "Optional. A remote calculation pool a scheduler pool may be pointed at with SCHEDULER_CALCULATION_MODE=REMOTE. The default is EMBEDDED; section 13 measures what remote costs and when it pays.")
    lead("The management port.", "Port 9000 serves health with components and Prometheus metrics, on the private network only and never behind the proxy.")
    heading("3 The contract")
    p(f"The machine-readable contract is docs/api/openapi-v1.yaml, OpenAPI 3.1, version {docs['version']}. Every operation is marked implemented; a test validates every example against its schema and fails if the implemented operations differ from the routes the scheduler serves, and the Java types mirror every object schema. Within version 1 a change is additive only and raises the minor version: the contract is at {docs['history'][0][0]} after {len(docs['history']) - 1} additive versions since 1.0.0.")
    p("The same conventions hold for every call. The tenant comes from the bearer token and a request never names one. Every write carries a host UUID, its requestId, that is its idempotency key across all of the tenant's endpoints: a replay returns the stored answer and a different body under the same key is refused. Money and rates are decimal strings and instants are RFC 3339. And no operation ever assigns overtime, so a repair that would need it is reported infeasible rather than solved.")
    figure("surface", "The API surface: twelve operations in six families. Arrows show the order in which a host calls them: a booking runs offers, select and confirm (or release), and a daily proposal runs create and commit. Confirm is the one call that turns a customer's choice into a booking.")
    heading("4 The timestamp the whole design rests on")
    p("The scheduler cannot lock rows it does not own, so every commit is optimistic on both sides, and the token is the lastModified timestamp WaterFlex Software already keeps per technician and day. Every snapshot includes it; a proposal or offer records the values it was computed from; a commit or confirm requires the submitted values to equal the recorded ones; and the host then writes the business record with a compare-and-set on the same values.")
    figure("token", "The lastModified round trip. The host reads each technician-day with its timestamp, the scheduler records the values with the proposal or offer set, and a commit or confirm succeeds only if every value is still the one recorded; otherwise it is answered 409 STALE with the technician-days that changed.")
    p("The timestamp is compared for exact equality and never ordered, so clock skew between servers cannot cause a wrong decision; at worst an unrelated change forces a retry. The host must move it on every change that affects the day, on both sides of a move, and send it exactly as stored.")
    sub("Reconciling a booked day")
    p("For booking, each metro-day has one stored common arrangement: every technician's route with the host's appointments and every active hold. Each search and confirm compares the host's snapshot with it, technician-day by technician-day, and nothing is guessed.")
    figure("reconcile", "Reconciling a technician-day before a booking search or confirm. Rule 0 refuses a snapshot older than records the scheduler already used, before anything is reconciled. Rules 1 and 2 keep or accept the day; rule 3 replaces the stored route when no hold depends on it and otherwise loses that date's holds; rule 4 re-evaluates the whole arrangement against the snapshot's current facts.")
    p("Rule 0 did not exist in the first version of the API. Section 12 shows what happened without it: at five booking desks more than a third of the holds customers selected were lost at confirm. The rule is the clearest example in this document of a measurement changing the design.")
    sub("Every refusal, and what the host does next")
    p(f"A call that cannot be answered is refused with a problem+json body: a status, a stable code and a message. The integration guide lists {len(docs['problems'][1])} of them. What matters to a host is not the code but the next action, and there are only six.")
    figure("refusals", "Every refusal the API can answer with, grouped by what the host does next: fix the request and send it with a new requestId, check the token, rebuild from current records, search again, wait and retry the same requestId, or nothing. The highlighted group is the one the concurrency token relies on.")
    heading("5 One request, end to end")
    p("Every calculating call carries a Snapshot of one metro: its rates and policy, the technicians it covers, one TechnicianDay per technician and date with the host's lastModified, and every committed appointment on those days. The scheduler computes a canonical hash of it, the input revision, and every proposal and offer records which revision it was computed from. What happens next is a short pipeline, and every stage of it can refuse.")
    sub("A daily proposal")
    figure("daily_pipeline", "A daily proposal through its nine stages, with the refusal each stage can return. The admission check (#153) refuses at once, with the estimated wait as Retry-After, a request that could not start in time.")
    p("Authentication maps the bearer token to a tenant. The body is read strictly: unknown fields, duplicate keys, missing values and dangling references are 400, and contradictions between sequence and plannedStart are 422. The requestId is claimed in the thin store with a hash of the body, so a replay returns the stored answer, a different body is 400 and a running one is 429. The 6 a.m. cutoff in the metro's zone freezes a day. Admission estimates when the work ahead would finish. The day is routed under its own 30 s allowance, and an unlocatable technician-day is skipped and reported. The engine runs the cost search and the fairness search for the client's seconds plus a 5 s reserve, embedded or remote, and a watchdog ends an overrunning phase. Policy then compares the result with the day as sent, and the proposal and the timestamp of each technician-day are stored and answered 201.")
    figure("daily", "Daily and repair proposals: synchronous or queued, then the commit with the host's current timestamps. The note on the right lists the refusals and the cancel.")
    sub("A booking search")
    figure("booking_pipeline", "A booking search inside the client's deadline. Reconciliation is where the concurrency token bites; if another booking changed the same day while the search ran, the search runs again, at most three times, while the deadline leaves time.")
    p("A booking search claims its requestId, bounds the whole call by the client's bookingDeadlineMs (at most 5 s), checks the cutoff and metro, builds the facts, takes an admission slot fairly across tenants, reconciles the stored arrangement of each date with the snapshot, routes the new job's legs, runs an insertion pass and then an optional refinement through the engine, validates the result independently, and publishes the offers as holds that last 10 minutes. The windows a customer sees are the insertion pass's; the refinement only improves cost. Section 10 is the story of that second pass.")
    figure("booking", "The booking sequence and its refusals. Each 409 and a failed receipt write lead to the same place: release the set (or let the hold expire) and search again from current records with a new requestId.")
    heading("6 Many clients and many replicas")
    p("One Postgres database and one set of tables serve every client. Each WaterFlex client is a tenant with its own API tokens, stored only as SHA-256 digests and issued with the scheduler's own command line. Isolation does not rest on any one mechanism; it is a stack of guards, each of which would stop a leak the one above it missed.")
    figure("isolation", "Many clients in one database: the guards, layer by layer. The token decides the tenant, every key starts with the tenant, row-level security makes the database itself refuse another tenant's rows, the runtime login has least privilege, and admission takes turns by client.")
    p("Row-level security is the layer that fails closed. Each transaction runs as the scheduler_tenant role with app.tenant_id set, and policies restrict every client-owned table to that tenant; a transaction with no tenant set sees nothing. A missed filter in application code therefore returns no rows rather than another client's. Road legs are the deliberate exception: they are not tenant data, they are shared by every client in a metro, and that sharing is where most of the cache benefit comes from.")
    sub("Several replicas on one database")
    figure("replicas", "What lives in a replica and what lives in the database. Each replica keeps only its admission slots, the memory tier of the route cache and its list of running calls; correctness lives in the shared database, so any replica can answer any call.")
    p("Holds are protected by row locks and unique constraints on (tenant, technician, date) and by the timestamp comparison inside one transaction, so the load balancer needs no sticky sessions. What stays per replica is there by design: admission is about the replica's own cores, the memory tier is a cache, and the list of running calls is reached from any replica by a cancel flag in the database within about a second. A replica's role (SCHEDULER_ROLE booking, daily or all) decides only which background work runs there.")
    sub("Queued proposals")
    figure("queue_states", "A queued proposal's life. A proposal sent with Prefer: respond-async is answered 202 and queued in a durable table, taken in turn by any replica's worker, and completed with the status and body the call itself would have answered; a request still waiting after 15 minutes completes with 429 without running.")
    p("Workers take queued requests with SKIP LOCKED, the waiting request of the client whose last one started longest ago first, so one client's thirty overnight days never hold back another's single day. A client may have 50 requests queued or running. Overnight optimization is therefore the host's to schedule; the scheduler has no batch of its own.")
    sub("What is stored, and for how long")
    figure("thin_store", "The thin store: the scheduler's own tables, grouped by what they serve, and how long each kind of data lives. The booking day is the row every booking locks; everything older than the deployment's retention period is purged.")
    p("The retention period is the deployment's SCHEDULER_RETENTION_DAYS, required with no default: the scheduler does not start without it. A daily purge deletes rows older than the period in batches through a database function, children before parents. A host keeps its own copy of any receipt it needs longer.")
    heading("7 Routing and geocoding")
    p("Every scored route needs the directed travel time between consecutive stops, so a day of 20 technicians needs a few thousand legs and a booking search for a new address needs a few hundred that nothing has seen before. Before a leg can be computed, every stop needs coordinates.")
    figure("geocode", "Where a stop's coordinates come from. Coordinates in the snapshot are authoritative and never geocoded again; an address without coordinates is located by Nominatim only in NOMINATIM mode and only as an exact house, and a street-level or ambiguous match is not located.")
    p("Every drawn stop records where its coordinates came from, so a bad route can be traced to its input. The legs themselves are computed by the metro's routing service and cached in four tiers.")
    figure("tiers", "The four route cache tiers in lookup order, from the design document. A pair of coordinates is answered by the first tier that holds it; a leg computed by the routing service is written back to the database and memory tiers in a separate short transaction.")
    p("None of the tiers is a source of truth: every entry is recomputable from the road graph, and every entry is bound to a routing identity, a hash of the graph and map version that the routing service reports and checks on every call. The database tier keeps a leg for up to 30 days and at most 500,000 rows, shared by every replica; the memory tiers are per replica and per request.")
    figure("map_lifecycle", "A map version's life: the merged OpenStreetMap extract, both road graphs, tiles and TIGER address ranges are prepared as a whole, validated with preview services and activated by switching symlinks. Activation changes the routing identity, so no cached leg from the old network is ever used again.")
    p("A map update, or a switch between the flexible and contraction hierarchy graphs, changes the identity, clears the memory tiers and leaves the database tier unused for the old identity, so a changed road network can never be mixed with old legs. Section 11 is the story of how the second graph got onto that list.")
    handed_on("The architecture fixed what is shared (the database, the routing service, the token) and what is per replica (slots and caches). Every optimization in Part III changed one of those pieces, and was measured against this API.")

    # ---- Part II -----------------------------------------------------------------------------------------------------------
    part("Part II  How the work was measured")
    heading("8 Three kinds of evidence, one discipline")
    p("Three kinds of evidence run through Part III, and they answer different questions. Fixture studies ask which daily search finds cheaper schedules in the same seconds. The September booking studies asked which booking method serves more customers and at what latency. The October load studies ask how the service behaves when many callers use it at once. None of them is customer traffic.")
    figure("evidence", "Three kinds of evidence and what each can say. Below, the pipeline every number in this document passes through: raw files whose hashes are recorded, a loader that checks the shape of every file before a number is read, the builder, and a verifier that reads the raw files again without the loader's or the builder's help.")
    p(f"The discipline is the same everywhere. Each study records its configuration, seeds, revision and raw results with hashes. A loader checks every file before a number is read: statuses add up to calls, matched pairs share a starting schedule, and there is no constraint violation anywhere. The report is built from those files, and a verifier recomputes every chart dataset from the raw data. This document is built that way from {len(perf['studies'])} load studies ({total_runs} runs), the solver profiling study, two routing benchmarks, the two daily search studies ({daily_cases:,} cases), the September booking study ({b24['requests']:,} requests) and the field validation summary.")
    p("Two rules matter most for the load studies. Compare only fresh-seed runs, because a seed run before leaves its roads cached and makes a one-desk search about a quarter of a second faster. And read every latency as the caller's, waits and retries included, because that is where the booking target of the client's deadline plus 500 ms is defined. Appendix B defines each measure.")
    handed_on("With the measures fixed, each thread in Part III can be read the same way: first iteration, measurement, change, evidence, decision.")

    # ---- Part III: daily search ----------------------------------------------------------------------------------------------
    part("Part III  The optimization trail")
    p("Part III follows the five threads in the order the evidence arrived. They are not independent. The booking search's second pass mattered only once admission stopped losing holds; the faster road graph mattered only once the booking search's own contention was gone; and the engine work started from a profile that the routing work made worth taking. Each thread ends with what it handed on.")
    heading("9 The daily search: from Tabu to Tabu + K opt")
    p("A daily proposal rearranges a day that is already booked: appointments may move between technicians and change order, but every customer's promised window holds and no overtime is added. The question of September was which local search to run inside the client's seconds.")
    figure("two_phase", "A daily proposal's search: a cost search, then a fairness search that may spend a small allowance of the cost it found to spread work more evenly, and policy accepts the result only if it beats the day as sent. Below, the moves a local search tries one step at a time; the K opt move changes several links of a route at once.")
    p("Every local search here works the same way: start from the day as sent, try a move, keep or reject it, and repeat until the time runs out. What differs is which moves are tried and which worse steps are accepted. Tabu search keeps a short list of recently moved visits it will not move back at once, and takes the best available step even when it is worse, so it can leave a local trap. Late acceptance accepts a move if it beats the solution of a fixed number of steps ago. The K opt move reconnects several links of a route at once, which finds order changes that single relocations and swaps cannot reach one step at a time.")
    sub("First iteration: Tabu, and a comparison that said to keep it")
    p(f"The demonstration's default was Tabu search with a 15 s budget and a tabu list of 7. The first algorithm comparison, part of the field validation, tested seven alternatives on {f29['daily_cases']} clustered cases over two held-out seeds and three budgets.")
    figure("budgets29", "The first algorithm comparison: mean saving against Tabu in modeled dollars per case at 15, 30 and 60 s, from the field validation summary. At 15 s only the uncapped original search beat Tabu; at 60 s the K opt family led and a larger ruin-and-recreate neighborhood still trailed.")
    p(f"At 15 s nothing beat Tabu except the uncapped version of the original search ({dollars(f29['daily'][(15, 'CURRENT_UNCAPPED')])} per case). K opt trailed by {dollars(-f29['daily'][(15, 'KOPT')])} per case and late acceptance by {dollars(-f29['daily'][(15, 'LATE_ACCEPTANCE')])}. At 60 s the picture reversed: K opt saved {dollars(f29['daily'][(60, 'KOPT')])} per case and late acceptance {dollars(f29['daily'][(60, 'LATE_ACCEPTANCE')])}. The comparison's report also noted that K opt's 15 s losses were concentrated in the 50-technician cases. The decision was to retain Tabu and study fleet size and budget together.")
    p("A budget study that followed (1,280 cases at 60 to 240 s) sharpened the fleet finding. By its report, K opt, sublist and late acceptance each saved about $6 per case on average, and each lost every one of the 40 matched 50-technician dispersed cases, by $47 to $52. A blanket switch would have hidden that trade. Its run archive is not in the repository, so this document quotes its report and recomputes nothing from it.")
    sub("The Tabu variant study")
    p(f"The question became whether Tabu's weakness on mid-size fleets and the K opt family's weakness on large ones could be traded away. The Tabu variant study ran {len(F):,} matched cases on {fixtures_first} fixtures (5 to 50 technicians, four workloads, budgets of 60 to 240 s, three seeds) with seven configurations: Tabu, Tabu with a shorter and a longer tabu list, Tabu with the K opt and sublist moves added, and the three late acceptance methods.")
    figure("daily_fleet", "Mean saving against Tabu in modeled dollars per case by fleet size. Left: the Tabu variant study (160 cases per cell). Right: the follow-up on 40 fixtures the first study never saw (48 cases per cell), with the combined Tabu + K opt size 3. Blue saves, brown loses, and the green cell is the configuration each study chose.")
    p(f"Tabu + K opt saved {dollars(saving(F, 'TABU_KOPT'))} per case and was the only configuration ahead in every fleet size, every workload ({dollars(min(saving(F, 'TABU_KOPT', workload=w) for w in workloads))} to {dollars(max(saving(F, 'TABU_KOPT', workload=w) for w in workloads))}) and every budget. The late acceptance family was the better choice on 10 and 20 technicians (about {dollars(mean(r['saving'] for r in late_family if r['fleet'] in (10, 20)))} per case) and lost about {dollars(-mean(r['saving'] for r in late_family if r['fleet'] == 50))} on 50. A mean can hide a few large wins among many losses, so the next figure counts cases.")
    figure("wtl", "Wins, ties and losses against Tabu, case by case, in both studies. A win is a lower accepted cost in exact cents on the same starting day, seed and budget. Tabu + K opt won the most cases in the first study; Tabu + K opt size 3 and Tabu + K opt led the follow-up.")
    p(f"Tabu + K opt had {wtl_text(tk_first)} out of {len(tk_first)}, so its mean is not carried by a few cases. Rerun one case at a time on {len(FC) // 2} cases it saved {dollars(saving(FC, 'TABU_KOPT'))} per case ({wtl_text(by(FC, 'TABU_KOPT'))}), so running six cases at once had not made the result.")
    sub("Why late acceptance loses on large fleets, and what Tabu + K opt costs")
    figure("late_mechanics", "Left: the point in the cost phase at which each search found its best schedule, by fleet size, in the Tabu variant study. Right: the mean fairness variance of the accepted schedules, lower being more even, with the fairness search on (first study) and with the cost search only (follow-up). The two right-hand groups come from different fixtures and compare only within themselves.")
    p(f"The recorded search mechanics say why the late acceptance family loses on 50 technicians. It found its best schedule at {best['LATE_ACCEPTANCE'][-1] * 100:.0f} percent of its phase on average there, against {best['TABU_KOPT'][-1] * 100:.0f} percent for Tabu + K opt: it was still improving when its time ran out. Tabu + K opt keeps most of the mid-size gain because the K opt moves reconnect several route links at once, and avoids the large-fleet loss because Tabu's step style converges early.")
    p(f"Its cost is balance. With the fairness search on, its accepted schedules had a mean variance of {var_first['TABU_KOPT']:.4f} against Tabu's {var_first['TABU']:.4f}. With the cost search alone, as in the follow-up, every cheaper method spread work less evenly than Tabu ({var_follow['TABU_KOPT']:.4f} for Tabu + K opt and {var_follow['TABU_KOPT_SIZE_3']:.4f} for size 3, against {var_follow['TABU']:.4f}). The fairness search closes most of the absolute gap, and it is part of every real daily proposal.")
    sub("The decision, and the follow-up that tested it")
    p(f"The daily search then became each client's own choice in the snapshot's policy (TABU_KOPT or TABU, with its own cost and fairness seconds up to 240 together), and Tabu + K opt became the default for existing clients (#133). A study that chooses its winner on the fixtures it measured can be fooled by those fixtures, so the follow-up (#134) ran {len(M):,} cases on {fixtures_follow} new fixtures with new seeds, added 30 technicians, and tried the one combination the first study suggested: Tabu + K opt with the shorter tabu list of 3.")
    p(f"Tabu + K opt held: {dollars(saving(M, 'TABU_KOPT'))} per case, ahead at every fleet size, {wtl_text(tk_follow)}. Tabu + K opt size 3 did better still, {dollars(saving(M, 'TABU_KOPT_SIZE_3'))} per case, ahead of Tabu + K opt itself in {sum(v > 0 for v in direct)} of {len(direct)} matched cases and at every fleet size from 10 technicians up; it trails by {dollars(saving(M, 'TABU_KOPT', fleet=5) - saving(M, 'TABU_KOPT_SIZE_3', fleet=5))} at 5. Rerun one case at a time it beat Tabu in {wtl(by(C, 'TABU_KOPT_SIZE_3'))[0]} of {len(by(C, 'TABU_KOPT_SIZE_3'))}.")
    figure("long_arm", "The follow-up's long-budget arm: mean saving against Tabu + K opt at 240, 480 and 600 s, at 30 and 50 technicians. The shaded budgets are beyond the 240 s a client can set. On 50 technicians late acceptance draws level only at 600 s and K opt passes Tabu + K opt only from about 480 s.")
    p(f"The long-budget arm answered the last open question. On 50 technicians late acceptance draws level with Tabu + K opt only at 600 s ({dollars(arm['50']['LATE_ACCEPTANCE'][2])} against it; {dollars(arm['50']['LATE_ACCEPTANCE'][0])} at 240 s), and K opt passes it from about 480 s ({dollars(arm['50']['KOPT'][1])}, against {dollars(arm['50']['KOPT'][0])} at 240 s). Those budgets are beyond the 240 s a client can set, so the large-fleet weakness is slowness, not a fault, and a fleet-size rule is not needed: one method per client covers every fleet the studies measured.")
    handed_on(f"Tabu + K opt is each client's default, with {dollars(saving(F, 'TABU_KOPT'))} per case over Tabu on the first study and {dollars(saving(M, 'TABU_KOPT'))} on fixtures it had never seen, and a stronger candidate (size 3) waits for the evidence Part V describes. A daily solve's quality now depends on how many moves it can evaluate in its seconds, which is where section 13 picks the thread up.")

    # ---- Part III: booking ---------------------------------------------------------------------------------------------------
    heading("10 The booking search: insertion, bounded rearrangement, and the deadline")
    p("Booking finds arrival windows for a new job and holds them while the customer chooses. The search must keep every existing promise and add no overtime, and it must answer inside the client's deadline. Two methods were in play from the start.")
    figure("booking_methods", "Two ways to fit a new job into a booked day. Insertion tries the new visit in every gap of every eligible route without moving anything else; bounded search also tries small rearrangements around the affected work, within limits on routes, depth and candidates. The September studies measured the trade between the two.")
    sub("First iteration: the September studies")
    p(f"The first booking experiment compared the two on {b24['cases']} cases ({b24['requests']:,} requests). In all {b24['comparable']} sequential comparisons where both methods booked the same ten requests, bounded search used {b24['paid_min']:.2f} fewer paid minutes and {dollars(b24['cost'])} less modeled cost per case on average, and never more of either.")
    figure("workload24", "The first booking experiment: what bounded search saved per case against insertion, by workload, in paid minutes (left) and modeled cost (right), over the sequential cases where both methods booked the same ten requests. Clustered days gain the most; sparse days have little to rearrange.")
    p(f"The gain depended on the workload: clustered days saved {b24['by_workload']['CLUSTERED']['paid_min']:.2f} minutes and {dollars(b24['by_workload']['CLUSTERED']['cost'])} per case, dispersed days {b24['by_workload']['DISPERSED']['paid_min']:.2f} and {dollars(b24['by_workload']['DISPERSED']['cost'])}, sparse days {b24['by_workload']['SPARSE']['paid_min']:.2f} minutes and {dollars(b24['by_workload']['SPARSE']['cost'])}. The price was time: one request at a time, the search p95 rose from {b24['latency'][('INSERTION', 1)]['p95'] / 1000:.2f} s to {b24['latency'][('BOUNDED', 1)]['p95'] / 1000:.2f} s; at ten simultaneous requests from {b24['latency'][('INSERTION', 10)]['p95'] / 1000:.2f} s to {b24['latency'][('BOUNDED', 10)]['p95'] / 1000:.2f} s.")
    figure("booking_study", "Left: requests served of 480 in the field validation's held-out matrix by search method, with each method's mean case p95. Right: search p95 by concurrency in the first booking experiment, insertion against bounded.")
    p(f"The field validation widened the matrix (5 to 50 technicians, concurrency 1 to 10, cold and warm caches, two held-out seeds) and added three larger searches. Bounded search served {held['BOUNDED']['served']} of {held['BOUNDED']['requests']} requests against insertion's {held['INSERTION']['served']}, with a mean case p95 of {held['BOUNDED']['meanCaseP95Ms'] / 1000:.1f} s against {held['INSERTION']['meanCaseP95Ms'] / 1000:.1f} s. The largest search, shared evaluation across windows, served {held['SHARED']['served']} at {held['SHARED']['meanCaseP95Ms'] / 1000:.1f} s and ended one run with an audit failure, so it and the expanded and ruin-and-recreate variants were set aside. The decision was to keep bounded search as the booking method and insertion as its control, under the policy the studies had been run with: a single best offer per window, zero new overtime, service and return travel inside regular availability.")
    sub("What changed in the API era")
    p("The public API runs the same bounded search as two passes inside the client's bookingDeadlineMs: an insertion pass that places the job on each day and finds the regular windows, then a refinement pass that improves cost and may move other appointments. When the insertion pass already finds more distinct windows than the client's regularWindowThreshold, the refinement is optional and capped, at 250 ms unless the client sets its own budget. When it finds fewer, offers are scarce and the refinement may use the rest of the deadline to find more.")
    figure("deadline", "Inside the deadline: when the second pass runs, and how it ends. Each path reports how the refinement ended (refinement) and whether the offers are complete (searchComplete). The three highlighted outcomes are the API-era changes that the load tool's first measurements led to.")
    p("Three things about that second pass were wrong when the load tool first measured it, and each was fixed and measured in turn.")
    figure("api_fixes", "The three fixes to the second pass. Left: searches at five desks that reported searchComplete false before #154 and after it. Middle: the #155 A/B at five desks on fresh addresses, searches per minute and offers p95. Right: #138, covered by tests rather than a load run.")
    lead("searchComplete (#154).", f"Reaching the optional refinement's cap marked the search incomplete, so {b5['booking']['searchIncomplete']} of {b5['booking']['searches']} searches at five desks reported searchComplete false while returning four offers. Now the flag means what the contract says, that the deadline cut a needed pass short, and OfferSet.refinement says how the second pass ended: {outcome['searchCompleteFalse']} of {outcome['searches']} searches were incomplete in 60 s at five desks, and all {outcome['refinement']['SHORTENED']} refinements were SHORTENED by the cap.")
    lead("Shedding (#155).", f"The optional pass was spent while other customers waited for a slot. Now refinement is shed (SHED) while booking requests are queued on the replica, a client may set its own refinement budget, and the dates a needed pass did not finish are reported. In the A/B at five desks on fresh addresses the replica answered {per_minute(ab_before):.0f} searches per minute before and {per_minute(ab_after):.0f} after, with offers p95 from {seconds(ab_before['ops']['booking offers']['ms']['p95'])} to {seconds(ab_after['ops']['booking offers']['ms']['p95'])}, and every search still returned four offers.")
    lead("Invalid refinement (#138).", "A refinement result that failed validation used to fail the whole search. Now the insertion offers are published with refinement UNAVAILABLE and the failure is logged and counted; an invalid insertion result still fails the search, so nothing unchecked is ever offered.")
    handed_on("The second pass is worth its 250 ms when the replica is idle and worth nothing when four other desks wait for the slot it holds, so the right policy is conditional and the host is told which it got. Throughput at five desks doubled without changing a single offered window. With the search's own contention gone, what remained inside a search was routing, which is where section 11 begins.")

    # ---- Part III: routing ---------------------------------------------------------------------------------------------------
    heading("11 Routing: from a flexible graph to a contraction hierarchy")
    p("The routing service computes legs on a GraphHopper road graph of the metro, and the scheduler caches them in the tiers of section 7. The question of this thread was never whether a faster graph existed; it was whether routing was the bottleneck.")
    sub("First iteration: sparse pairs, and a faster graph that changed nothing")
    p(f"The first routing change fetched only the directed pairs a search needs instead of whole matrices: the first sparse Omaha request took {r24['sparse']['initialSparse']:.1f} ms and a repeat {r24['sparse']['repeatedSparse']:.2f} ms, with every pair matching the full matrix. Next, a contraction hierarchy (CH) graph was prepared for the Omaha map and benchmarked against the flexible graph on 132 directed pairs, and every leg matched to the metre.")
    figure("paradox", "September's paradox. Left: the routing service alone answered a 16-pair request more than ten times faster on the CH graph. Right: under a 720-request booking run on real Omaha roads the two graphs served the same customers at the same pooled p95.")
    p(f"Alone, an uncached 16-pair request answered in {ch_old[('CH-car-v1', 1)]['p50Ms']:.1f} ms p50 instead of {ch_old[('flexible', 1)]['p50Ms']:.1f} with one caller, and {ch_old[('CH-car-v1', 10)]['p50Ms']:.1f} instead of {ch_old[('flexible', 10)]['p50Ms']:.1f} with ten. Under a full booking run of {omaha['flexible']['requests']} requests the flexible graph served {omaha['flexible']['served']} at a pooled p95 of {omaha['flexible']['p95Ms'] / 1000:.2f} s and the CH graph {omaha['ch']['served']} at {omaha['ch']['p95Ms'] / 1000:.2f} s. A road query more than ten times faster had bought nothing a customer would notice, because the booking search of that time spent its deadline elsewhere, on reservation contention and its own candidate work. CH and road prewarming stayed off by default, with a note that the decision should be revisited with combined booking evidence.")
    sub("How a contraction hierarchy works")
    figure("ch_mechanism", "How a contraction hierarchy answers with less work. At import every junction is ranked by importance and shortcuts stand for paths through less important junctions; a query searches only upward from both ends, follows the shortcuts, and meets at the most important junction between them. The answer is the same to the metre; only the work changes.")
    p("A contraction hierarchy spends time once, at import, ordering every junction by importance and adding shortcut edges wherever removing a junction would break a shortest path. A query then climbs from both ends through ever more important junctions and meets near the top after touching a few hundred nodes instead of the thousands a plain search would settle. The price is an import step of about 27 s and about 200 MiB more on disk per map version, and a routing identity of its own, so switching starts every cache cold as a map change does.")
    sub("What the attribution showed")
    p(f"The combined evidence came from study J0. It ran the load tool against the VM-shaped rehearsal stack and, for the first time, the request log attributed each call's time to its parts. A booking search at five desks took {ms(vm_b['ops']['booking offers']['ms']['p50'])} p50 at the caller: {ms(part_ms(vm_b, 'queueMs'))} waiting for a slot, {ms(part_ms(vm_b, 'routingMs'))} routing, {ms(part_ms(vm_b, 'calculationMs'))} in the engine and {ms(part_ms(vm_b, 'dbMs'))} in the database.")
    figure("attribution", "Server time of the median booking call, split into slot wait, routing, calculation engine and database, for the VM baseline (J0), the two J runs, the J2 build that shipped (section 12) and the remote solver pool (section 13). The confirm has almost no work of its own; its bar is the wait behind searches.")
    p(f"Each search needed about {vm_b['breakdown'][OFFERS]['routingPairs']['p50']} directed legs and fetched {vm_b['breakdown'][OFFERS]['fetched']['p50']} of them cold at about {per_leg(vm_b):.2f} ms each, because the service routed a batch's pairs one after another and the scheduler fed it through two worker threads; the routing container ran at {routing_cpu(vm_b):.0f} percent of a core for the booking replica's {booking_cpu(vm_b):.0f}. By October the booking contention of September had been fixed (section 12), and routing was now the largest part of a search's own time. The engine was not the lever: {ms(part_ms(vm_b, 'calculationMs'))} of a {ms(part_ms(vm_b, 'elapsedMs'))} server time.")
    sub("The change, and what it bought")
    p("Study J (#160) changed four things the attribution pointed at. Each map version now carries a CH graph beside the flexible one, prepared with the map, listed in its manifest and required at activation, and the routing service serves it by default. The routing service routes a request's pairs on several threads at once. The scheduler trusts a routing identity it fetched within the last two seconds instead of asking /health before every lookup. And the scheduler fetches leg batches on eight workers with a queue of 64 instead of two and 16.")
    figure("ch", "p50 of a 16-pair routing request against the routing service alone with its caches off, flexible against CH, in the first benchmark and on the current map with the new image, which routes a request's pairs in parallel. Log scale.")
    p(f"The routing service alone now answers a 16-pair request in {ch_new[('CH-car-v1', 1)]['p50Ms']:.1f} ms instead of {ch_new[('flexible', 1)]['p50Ms']:.1f} with one caller and {ch_new[('CH-car-v1', 10)]['p50Ms']:.1f} instead of {ch_new[('flexible', 10)]['p50Ms']:.1f} with ten, with exact parity on all 132 pairs. Under load the difference between the two graphs finally showed.")
    figure("j_routing", "Study J at five desks: booking searches answered in 60 s, routing time per fetched leg, and the routing container's mean CPU in cores, for the VM baseline on the flexible graph (J0), the flexible graph with parallel pair routing, and the CH graph.")
    p(f"The CH run answered {jc_b['booking']['searches'] / vm_b['booking']['searches']:.1f} times the baseline's searches at a quarter of the latency, and the engine and database shares did not move, so the whole gain is routing and the slot time routing was holding: a slot held {ms(part_ms(vm_b, 'elapsedMs') - part_ms(vm_b, 'queueMs'))} per search before and {ms(part_ms(jc_b, 'elapsedMs') - part_ms(jc_b, 'queueMs'))} after. CH is the lever and parallel flexible search is not: routing a batch's pairs in parallel on the flexible graph halved the routing time but spent {routing_cpu(jf_b) / 100:.0f} cores doing it, while CH answered in a third of that time on {routing_cpu(jc_b) / 100:.1f}. One lesson for anyone repeating the September benchmark: with the service's own cache on, both engines answer repeated pairs from memory and CH looks slower by its small overhead, so the benchmark launches both instances with the cache limited to one entry.")
    handed_on("Why did the same graph change nothing in September and everything in October? Because the September booking search was bound by contention and its own candidate work, and the October one, after sections 10 and 12, was bound by routing. The order of the work was the finding. With routing fast, what remained was waiting for a slot, which is the admission thread.")

    # ---- Part III: admission -------------------------------------------------------------------------------------------------
    heading("12 Admission and concurrency under load")
    p("The load tool's first measurement (#151) showed that one booking desk met the latency target with room to spare, and five or more did not. The reason was not computation. Across the next eight pull requests each measurement found one thing, one change fixed it, and the next measurement found the next.")
    sub("Lost holds, and the rule that keeps them (#152)")
    figure("holds", "Holds lost and the latency tail by booking desks, in the first load measurement (#151) and after rule 0 (#152, fresh seeds). Left: selected holds lost at confirm. Right: offers p95 at the caller against the 5.5 s bound.")
    p(f"At five desks {pct(b5['booking']['holdUnavailable'], b5['booking']['selected'])} of the holds customers selected were lost at confirm, and at ten {pct(b10['booking']['holdUnavailable'], b10['booking']['selected'])}. The cause was reproduced deterministically, and it was a race the design had not anticipated.")
    figure("race", "How a late snapshot lost holds. Desk B confirms and the day moves on; desk A's search, built from records read before that, arrives late. Before #152 rule 3 read the old timestamp as a host edit and lost every hold on the date; with rule 0 the scheduler recognizes a timestamp it already moved past and answers 409 STALE, and nothing is lost.")
    p("The scheduler compares a technician-day's lastModified only for equality. A request built from older records, arriving after a newer receipt was already used, looked like a fresh host edit of a route that holds depended on, and rule 3 lost every hold on that date. Any host with several desks produces such late requests. The fix is rule 0: each technician-day remembers the last 32 timestamps the scheduler has moved past and the route from before a confirm receipt the host has not written yet, and a snapshot carrying either is answered 409 STALE before anything is reconciled. Nothing is guessed and nothing is lost; the cost is a resend. The same pull request let concurrent bookings publish: a search had refused to publish unless every day it had read was still at the version it read, so ten desks on five dates kept invalidating each other, and now it checks only the days its offers hold.")
    p(f"No hold was lost in any run after the fix, and every desk count was within the 5.5 s bound: offers p95 went from {seconds(b5['ops']['booking offers']['ms']['p95'])} to {seconds(fresh[5]['ops']['booking offers']['ms']['p95'])} at five desks and from {seconds(b10['ops']['booking offers']['ms']['p95'])} to {seconds(fresh[10]['ops']['booking offers']['ms']['p95'])} at ten. At ten desks on the same five dates {c10['booking']['stale'] / c10['booking']['searches']:.1f} resends per answered search is a high price, and it is a price of the test's shape: ten desks booking the same fifty technician-days is more contention than one metro sees. From J0 the load tool gives each desk its own dates, and the resends vanish.")
    sub("The daily side: refuse early, queue fairly, split the pools (#153, #157, #158)")
    p(f"A replica ran one daily calculation at a time, and planners behind it queued for their whole allowance and then failed: {b_daily[4]['ops']['daily proposal']['statuses'].get('503', 0)} of {b_daily[4]['daily']['proposals']} proposals at four planners were a 503 after about {seconds(b_daily[4]['ops']['daily proposal']['ms']['p50'])}, and the planner learned nothing it could act on. #153 routes the day before the calculation asks for a slot, under its own 30 s deadline, and refuses at once with 429 and the estimated wait as Retry-After any request that could not start within its reserve: at four planners {a_daily[4]['ops']['daily proposal']['statuses'].get('201', 0)} of {a_daily[4]['daily']['proposals']} were answered.")
    figure("daily_load", "Left: proposals answered (solid) or refused with 503 (hatched) at one and four planners, before and after #153. Middle: proposals each of two clients got in 90 s on one replica, synchronously and queued (#157). Right: proposals in 60 s from one replica doing everything or from booking and daily pools at equal capacity (#158).")
    p(f"What remained was fairness: retries were a lottery, and on a replica that also books, queued bookings always go first. #157 added the durable queue of section 6 with per-client turns. Measured with two clients on one replica for 90 s, client A with four planners and client B with one: synchronously B got {fair[('sync', 1)]['daily']['proposals']} proposal because A's planners took each freed slot first; queued, B got {fair[('async', 1)]['daily']['proposals']}, one every 9 to 15 s. The total was the same ({fair[('sync', 4)]['daily']['proposals'] + fair[('sync', 1)]['daily']['proposals']} and {fair[('async', 4)]['daily']['proposals'] + fair[('async', 1)]['daily']['proposals']}), because a replica still runs one calculation at a time; the queue shares the work out rather than adding any.")
    p(f"#158 split the jar into booking replicas, which take no queued daily work, and daily replicas, which may run several calculations at once, with nginx in front. At equal total search capacity, with five desks and five queued planners for 60 s in the order all, split, split, all, daily throughput went from {roles['all-1'][1]['daily']['proposals']} and {roles['all-2'][1]['daily']['proposals']} proposals to {roles['split-1'][1]['daily']['proposals']} and {roles['split-2'][1]['daily']['proposals']}. A queued day waited {seconds(queue_wait(roles['split-1'][1])[0])} instead of {seconds(queue_wait(roles['all-1'][1])[0])} for its turn, and the booking tail fell (offers p95 {ms(roles['all-1'][0]['ops']['booking offers']['ms']['p95'])} to {ms(roles['split-1'][0]['ops']['booking offers']['ms']['p95'])}) because no daily calculation competes for the booking replica's cores. The cost was the confirm: with two booking slots instead of four, a confirm waited behind searches, and its p50 rose from {ms(roles['all-1'][0]['ops']['confirm']['ms']['p50'])} to {ms(roles['split-1'][0]['ops']['confirm']['ms']['p50'])}.")
    sub("A confirm that never waits (#162)")
    p(f"After the routing work a confirm, which does a few milliseconds of work to complete a booking the customer already chose, still spent {ms(part_ms(jc_b, 'queueMs', CONFIRM))} of its {ms(part_ms(jc_b, 'elapsedMs', CONFIRM))} on the server waiting for a search slot. J2 changed admission in two steps, and measured the JVM flags the compose file sets with a decision rule set before the runs: a default changes only on a 5 percent latency improvement.")
    figure("slots", "Admission on a booking replica before and after J2. Before, a confirm queued behind waiting searches for one of two search slots. After, admission chooses a confirm before any waiting search, and a confirm-only slot beyond the search capacity means it never waits for a running search either.")
    figure("confirm", "Confirm (left) and offers (right) at the caller, p50 and p95, from J's CH run through the four J2 runs. Hollow dots are p50 and filled dots p95; the WAIT line under each confirm row is the median slot wait the server recorded.")
    p(f"A confirm now answers in {ms(final['ops']['confirm']['ms']['p50'])} p50 and {ms(final['ops']['confirm']['ms']['p95'])} p95 at the caller, from {ms(jc_b['ops']['confirm']['ms']['p50'])} and {ms(jc_b['ops']['confirm']['ms']['p95'])}. Priority alone took the wait from {ms(part_ms(jc_b, 'queueMs', CONFIRM))} to {ms(part_ms(j2['j2-confirm'], 'queueMs', CONFIRM))}, and the slot took it to {ms(part_ms(final, 'queueMs', CONFIRM))}. Offers were unchanged within run-to-run variation, and the replica served {pct(final['booking']['searches'] - jc_b['booking']['searches'], jc_b['booking']['searches'])} more searches in the minute because the desks spent less of it in confirms. The garbage collector is not a lever: ZGC on the booking replica was within 3 percent on offers and worse on confirm p95 and heap, Parallel GC on the daily replica changed nothing the load tool can see, and compact object headers were within noise; G1 and the JDK defaults stay.")
    figure("progression", "Five booking desks at each stage: searches answered per minute (left) and caller latency of offers as p50 (hollow) and p95 (filled) against the 5.5 s bound (right). Left of the dotted line, one machine with the desks alone; right of it, the VM rehearsal stack with five daily planners working beside the desks. The two halves are not one series.")
    handed_on(f"With confirms out of the way, the booking replica's remaining wait is searches queuing for the two slots ({ms(part_ms(final, 'queueMs'))} p50 of a {ms(part_ms(final, 'elapsedMs'))} search), which is CPU: more cores, more replicas, or a cheaper search. The cheaper search is inside the engine.")

    # ---- Part III: engine ----------------------------------------------------------------------------------------------------
    heading("13 Inside the engine")
    p("Two studies looked at the calculation itself: where a daily solve spends its time, and whether moving the engine out of the scheduler into a pool is worth it.")
    sub("More moves per second in the same budget (study L, #163)")
    p(f"A daily proposal's latency is its queue wait plus a fixed search budget, so the daily side cannot be made faster by the load tool's measure; it can only be made better in the same seconds by evaluating more candidate moves. The benchmark harness now starts a JDK Flight Recorder recording just before each solve and stops it just after, so the samples cover the solve alone. The first profile (Tabu + K opt, 20 and 50 technicians, clustered and dispersed, a fixture seed never used for tuning, 10 s per case) collected {prof['before']['samples']:,} samples.")
    figure("profile", "Where a daily solve's profiler samples went, by group, before and after the RoadMatrix treatment. Before, looking legs up, copying arrival maps and BigDecimal arithmetic around the engine's own timing methods held about half the samples; after, BigDecimal and the clock arithmetic of Instant and Duration are the largest named shares.")
    p(f"Of the samples, the engine's own timing methods held {groups_before['Engine timing methods']:.1f} percent; the rest was what scoring did around them. Every scored route looked its road legs up in an immutable map keyed by a from-to string built for each leg ({groups_before['Immutable map probe']:.1f} percent of samples), built a HashMap of arrivals and a day plan that were then copied into a result ({groups_before['HashMap and copies']:.1f} percent), and computed its buffered travel minutes in BigDecimal per leg ({groups_before['BigDecimal arithmetic']:.1f} percent).")
    figure("roadmatrix", "One road leg lookup before and after RoadMatrix. Before, every scored route built a string key per leg, probed a map, computed minutes in BigDecimal and built an arrival map that was copied. After, endpoints are numbered once per fact revision, legs are read from two tables with the minutes precomputed, and the score-only path builds no arrival map. The scoring exactness test is the gate.")
    p("The treatment keeps every number the same and removes the work around it. RoadMatrix gives each endpoint identity a number and holds the legs in two tables with their buffered minutes computed once per fact revision, built lazily on the first scoring lookup, so a plan evaluated once outside the solver (a booking candidate) pays nothing new. The timing search evaluates a route against the plan facts directly and, on the score-only path, builds no arrival map. A scoring exactness test captured a step-limited, fixed-seed solve on three fixtures before the change and asserts the same score, move count, arrangement and independent evaluation after it. Then the engine built from the commit before the change was paired against the treated one on 24 cases, one after the other on an idle workstation.")
    figure("engine", "Left: each paired case's cost-phase moves per second before (x) and after (y) the RoadMatrix treatment; the dotted line is no change and the dashed line is twice the rate. Right: offers at the caller for the embedded engine and three remote pool configurations in study K, with the daily proposals each pool answered.")
    figure("paired_fleets", "Moves per second by fleet: the median cost-phase rate before and after the treatment with its speedup, the fairness phase's median speedup, and each paired case marked cheaper, tied or dearer at the same budget.")
    p(f"The cost phase evaluates {summary['costPhaseSpeedup']['median']:.2f} times the moves per second (median; {summary['costPhaseSpeedup']['min']:.2f} to {summary['costPhaseSpeedup']['max']:.2f} across the 24 cases), more on 50 technicians ({pf[50]['speedup']:.2f} times) than on 20 ({pf[20]['speedup']:.2f}), because each scored route there had more legs to look up. The fairness phase gains {summary['fairnessPhaseSpeedup']['median']:.2f} times, less, because its time goes to the fairness term and the BigDecimal score, which the treatment leaves alone. Because a solve now evaluates more moves in the same seconds, {summary['afterCostCentsWinTieLoss']['afterWins']} of 24 cases ended cheaper at the same budget, {summary['afterCostCentsWinTieLoss']['ties']} tied and {len(loss)} was dearer by {loss[0]['afterCostCents']['after'] - loss[0]['afterCostCents']['before']} cents, with no constraint violation on either side. This is not a search-policy change: the acceptor, moves and tabu size are what section 9 chose, so the promotion review that belongs to policy changes was not run for it.")
    sub("The embedded engine against a remote pool (study K, #164)")
    p("The design's third scaling lever moves the calculation out of the scheduler into a solver pool (SCHEDULER_CALCULATION_MODE=REMOTE), and it had never been measured. K compared the two on the rehearsal stack under the same load as J2, with the pool at its compose shape (4 cores) beside the booking and daily replicas at 2 cores each, and an acceptance set beforehand: remote p95 at most 1.05 times embedded. Booking fails it.")
    figure("round_trips", "Why a remote engine costs booking more than it saves. An embedded search calls the engine in process; with a remote pool each of up to three engine calls encodes a snapshot, sends it, and decodes the answer. The cards give the engine share of a median search's server time and the searches answered in 60 s.")
    p(f"A remote search costs {k['k-remote5']['ops']['booking offers']['ms']['p50'] / k['k-embedded']['ops']['booking offers']['ms']['p50']:.1f} times the embedded one at the caller (p50 {k['k-remote5']['ops']['booking offers']['ms']['p50']} against {k['k-embedded']['ops']['booking offers']['ms']['p50']} ms, p95 {k['k-remote5']['ops']['booking offers']['ms']['p95']} against {k['k-embedded']['ops']['booking offers']['ms']['p95']} ms) and the replica serves two thirds of the searches, because the engine share of a search goes from {ms(part_ms(k['k-embedded'], 'calculationMs'))} to {ms(part_ms(k['k-remote5'], 'calculationMs'))}. So the embedded engine stays the default for booking.")
    p(f"For a daily pool, remote is the lever it was meant to be: the proposal takes the same {seconds(k_d['k-remote5']['ops']['daily proposal']['ms']['p50'])} p50 (queue wait plus a fixed 5 s search) and the daily replica's CPU falls from {daily_cpu(k['k-embedded']):.0f} to {daily_cpu(k['k-remote5']):.0f} percent of its two cores, because the solve runs in the pool. Each pool has its own calculation mode, so the daily pool can go remote while booking stays embedded. The study also found and fixed a sizing fault: with the pool's previous defaults {k_d['k-remote']['ops']['daily proposal']['statuses']['503']} of {k_d['k-remote']['daily']['proposals']} daily proposals answered 503, because the solver service admitted one daily calculation at a time whatever its capacity. The pool now opens every slot to daily work by default, and the rule for an operator is that a pool's capacity is the served replicas' search capacities summed.")
    handed_on(f"After the treatment the profile's largest named shares are BigDecimal ({groups_after['BigDecimal arithmetic']:.1f} percent) and the clock arithmetic of Instant and Duration ({groups_after['Instant and Duration']:.1f} percent). They are the next performance step, and the first one Part V names after measuring on the VM.")

    # ---- Part IV -------------------------------------------------------------------------------------------------------------
    part("Part IV  Where it stands")
    heading("14 The system today")
    p("The five threads end in one system. The figure below is the topology of Part I with each piece annotated by the choice its thread settled and the number behind it.")
    figure("today", "The scheduler today, with the choice each thread settled. The booking pool runs bounded two-pass search that sheds its optional pass under load, a confirm slot with priority, and the embedded engine with RoadMatrix; the daily pool runs each client's daily search (Tabu + K opt by default) from a fair durable queue, ready to move its engine to a remote pool; both share the CH routing graph and the thin store with rule 0.")
    p(f"Read from the top, the figure is the trail in reverse. A host's call reaches one address. A booking search runs the method September chose, inside the client's deadline, giving up its optional pass when others wait ({per_minute(ab_before):.0f} to {per_minute(ab_after):.0f} searches per minute at five desks). Its legs come from a contraction hierarchy that finally mattered once contention was gone ({vm_b['booking']['searches']} to {jc_b['booking']['searches']:,} searches in 60 s). Its confirm never waits ({ms(jc_b['ops']['confirm']['ms']['p50'])} to {ms(final['ops']['confirm']['ms']['p50'])} p50). Its holds survive late snapshots ({b5['booking']['holdUnavailable']} lost at five desks to none). A daily proposal runs the search the fixture studies chose ({dollars(saving(F, 'TABU_KOPT'))} per case over Tabu), from a queue that takes turns by client, in an engine that evaluates {summary['costPhaseSpeedup']['median']:.2f} times the moves in the same seconds.")
    p("The method behind every one of those numbers was the same: measure where the time or the money actually goes before changing anything, change one thing, and keep it only when a paired measurement on fresh inputs says so. The September CH graph and the October CH graph were the same graph; what changed between them was the evidence.")
    handed_on("Two gaps remain in that evidence: every load number is from a rehearsal stack, not the VM, and every solver number is from synthetic days. Part V starts from both.")

    # ---- Part V ------------------------------------------------------------------------------------------------------------
    part("Part V  What comes next")
    heading("15 Performance")
    p(f"After J2 the median booking search spends {ms(part_ms(final, 'queueMs'))} waiting for a slot, {ms(part_ms(final, 'routingMs'))} routing, {ms(part_ms(final, 'dbMs'))} in the database and {ms(part_ms(final, 'calculationMs'))} in the engine, and a confirm no longer waits at all. The daily search does {summary['costPhaseSpeedup']['median']:.1f} times the work in its budget, and a remote pool can take that work off the daily replica. The evidence supports the following steps, in this order.")
    figure("roadmap_perf", "Performance next steps in dependency order. Measuring on the real VM comes first, because every load number so far is from a rehearsal stack. Booking cores, the remote daily pool and the primitive-time work follow; multithreaded solving follows the primitive-time work through the same gate; fewer solver round trips matter only if booking ever uses a pool.")
    lead("Measure on the VM.", "Repeat J0, J2 and K across the network on the real VM, with the load generator on the host's side. Every number in sections 11 to 13 is from a rehearsal stack whose CPU limits are a shape, not the VM's share, and the latency target is defined at the WaterFlex caller across the network, which has not been measured yet. This decides whether the target holds where it is defined.")
    lead("Booking cores.", f"The slot wait is half of a search ({ms(part_ms(final, 'queueMs'))} of {ms(part_ms(final, 'elapsedMs'))}). Measure five and ten desks at 2, 3 and 4 booking cores, and with a second booking replica. This sizes the booking pool on the VM.")
    lead("Daily pool remote.", "Run the daily pool REMOTE on the VM with the solver pool sized by the rule of section 13, and confirm every proposal answered and the replica's CPU. This decides whether the daily pool goes remote at go-live.")
    lead("Primitive time in the timing search.", "BigDecimal and Instant and Duration are now the largest shares of a solve. Replace them with integer seconds in the route timing search, gated by the scoring exactness test, then repeat the 24-case paired study. This decides whether a 5 s search can do still more, and like RoadMatrix it changes no number the engine computes.")
    lead("Multithreaded solving.", "Planned work with a gate. The daily search runs single threaded today: parallelism comes from several solves at once on each daily replica, independent solves per core in the benchmarks, and parallel pair routing. Timefold can spread one solve's move evaluation over several threads (moveThreadCount); the production configuration sets none today and the campaign configuration refuses any value but NONE, so the first step is to make it a measured option.")
    figure("cores", "Multithreaded solving spends the same cores differently. Illustrated on a four-core daily replica: today four days solve at once on one core each; with two move threads per solve, two days solve at once, each evaluating more moves in its seconds. The gate below decides whether that trade is worth it for any client.")
    p("The gate has three parts, the same discipline as every thread in Part III. The paired study on fresh fixtures compares one thread against two on the same days, budgets and seeds, and shows whether more moves per solve buys a cheaper schedule. The scoring exactness test shows that every score stays the same. And the replica's core budget decides the cost: a solve that uses two cores halves the calculations a replica can run at once, so the fair queue must not lengthen past what clients accept. Multithreaded solving goes to a client only when all three say so, and only as a per-pool setting the measurements justify.")
    lead("Fewer solver round trips.", "Carry insertion and refinement in one solver call. This matters only if booking is ever to use a remote pool, and it is the one change that could bring remote booking within the 1.05 bound; until then it stays unscheduled.")
    heading("16 Solver quality, booking quality and features")
    p("The daily search studies ended with a candidate that beats the current default on every new fixture from 10 technicians up, and with questions the fixtures cannot answer. The booking search has not had a quality study since the API replaced the internal endpoints. And the integration itself has steps left on the WaterFlex Software side.")
    figure("roadmap_quality", "Next steps in three lanes. The daily search: offer Tabu + K opt size 3 as a third client choice, replay real days, measure balance with the fairness search on, then decide a default. Booking: a quality study against /api/v1 on recorded days. Integration: host snapshots and receipts with the conformance kit and the integration guide, side-by-side migration and cutover, then TLS before production traffic. Below, the design document's two open questions.")
    sub("The daily search")
    lead("A third choice.", f"Add Tabu + K opt size 3 as a daily search choice beside TABU_KOPT and TABU. It exists today as an experiment configuration only. On 40 unseen fixtures it saved {dollars(mean(direct))} per case more than Tabu + K opt, led from 10 technicians up, trailed slightly at 5, and won 16 of 16 when run alone. Making it a choice costs nothing and lets a client opt in; making it a default needs the next two steps.")
    lead("Real days.", "Every fixture is synthetic, with a synthetic travel matrix, and the one study on real Omaha roads was a booking study. Replay recorded days with measured road travel through both Tabu + K opt variants; the daily proposal endpoint accepts a recorded snapshot as it is, so no new code is needed to run it.")
    lead("Balance with the fairness search on.", f"Cheaper methods are less even without a fairness search ({var_follow['TABU_KOPT_SIZE_3']:.4f} against Tabu's {var_follow['TABU']:.4f} in the follow-up), and with one most of the gap closes. Measure Tabu + K opt size 3 with the fairness search on before adoption, and decide whether a client's fairness budget should carry an explicit acceptable variance.")
    lead("What not to do.", "Keep the 240 s client limit: K opt passes Tabu + K opt at 50 technicians only from about 480 s, so longer searches matter only for overnight runs, where the queue of section 6 already makes a long search harmless to other clients. And add no fleet-size selector: the first study's hindsight rule held up on new fixtures, and one method per client matches it.")
    sub("The booking search")
    lead("A booking quality study.", "The booking benchmarks of September measured the retired internal endpoints, and the load tool measures speed, not offer quality. Run a booking quality study against /api/v1 on recorded days, with the served-customer denominator the September studies insisted on, so that the two-pass search, shedding and the refinement fallback are known to have kept the quality September measured.")
    sub("The integration")
    lead("The host's side.", "WaterFlex Software builds snapshots from its own records, including a lastModified per technician-day that follows the rules of section 4; writes receipts with a compare-and-set on those timestamps; treats STALE and HOLD_UNAVAILABLE as ask again; holds one tenant token per client; and runs its own overnight schedule against the daily proposal endpoint. The host conformance kit (#149) proves each of those behaviours against a running scheduler, and the integration guide (#150) is the contract in prose.")
    lead("Side by side, then cutover.", "The side-by-side migration and cutover (S7) is the remaining step on the WaterFlex Software side: run the host's integration beside the current path until both agree, then switch.")
    lead("TLS.", "TLS is not yet set up between WaterFlex Software and the scheduler, nor from the scheduler to routing and Nominatim; it has been deferred. It is a precondition of production traffic, and the operations runbook lists what must be encrypted.")
    sub("Two open questions")
    p("The design document ends with two questions only WaterFlex can answer. Does WaterFlex Software's Google Maps Platform agreement allow passing Google-geocoded coordinates to the scheduler and caching them for up to 30 days? The route cache keeps coordinates at about 1 m for up to 30 days, so the answer decides whether host coordinates from Google can be used as they are. And does any client require a dedicated database? Every row already carries tenant_id, so that client's rows could move to a database of its own without an API change; the question is whether any contract requires it.")
    p("The thread that runs through both of these sections is the one that ran through the whole month. Each next step names the evidence that would justify it before anyone builds it, and each will be kept only when a paired measurement on fresh inputs says so.")

    # ---- Appendices ---------------------------------------------------------------------------------------------------------
    part("Appendix A  Studies and evidence")
    rows = [("2026-09-23", "Sparse routing check", "docs/evidence/sparse-routing-omaha-2026-09-23.json", "Six directed Omaha pairs; sparse against full matrix; API samples"),
            ("2026-09-24", "Flexible against CH, routing alone", "docs/evidence/routing-ch-omaha-2026-09-24.json", "132 pairs, map 2026-09-16, 1/5/10 callers"),
            ("2026-09-24", "Flexible against CH, booking", "docs/evidence/booking-omaha-routing-2026-09-24.json", "720 requests per graph on real Omaha roads"),
            ("2026-09-24", "Insertion against bounded", "docs/booking-experiment-explainer/evidence.json", f"{b24['cases']} cases, {b24['requests']:,} requests; 60 paired sequential cases"),
            ("2026-09-29", "Field validation", "docs/evidence/scheduler-field-2026-09-29/summary.json", f"{f29['held_cases']} held-out booking cases per variant; {f29['daily_cases']} daily cases, paired means by budget"),
            ("2026-10-01", "Daily budget study", "docs/daily-budget-331df1f1 (report only; run archive not in the repository)", "1,280 cases; quoted, not recomputed"),
            ("2026-10-11", "Tabu variant study", "experiments/runs/20261010T040557Z-daily-tabu-variants-p6-5d15129b", f"{len(F):,} cases on {fixtures_first} fixtures; {len(FC)} sequential confirmation cases"),
            ("2026-10-13", "Daily search follow-up", "experiments/runs/20261013T052707Z-daily-search-followup-6454fb05 (+ long budget, confirmation)", f"{len(M):,} + {len(L)} + {len(C)} cases on {fixtures_follow} new fixtures")]
    rows += [(re.search(r"\d{4}-\d{2}-\d{2}", name).group(), f"Load study {pr}", f"docs/evidence/{name}", note) for key, name, pr, note in perf["studies"]]
    rows += [("2026-10-14", "Solver profiling (#163)", "docs/evidence/solver-profiling-2026-10-14.json", "Two solve-scoped JFR profiles; 24 paired cases"),
             ("2026-10-14", "Flexible against CH, routing alone", "docs/evidence/routing-ch-2026-10-14.json", "132 pairs, map 2026-10-13, parallel pairs")]
    table(["Date", "Study", "File", "What it holds"], rows, [.8, 1.6, 2.7, 1.8], size=9)
    p("Every load run used the load tool infra/host-conformance/load.ts on one 24-processor workstation (AMD Ryzen 9 7900X, 63 GiB) running the scheduler, the Omaha routing service, Postgres and the load generator; the solver studies ran on the same machine with the experiment runtime's daily flags. Studies #151 to #157 ran the scheduler jar alone; #158 to #164 ran the deploy/vm rehearsal stack with its CPU limits beside five queued daily planners, and the two halves are never read as one series.")
    p("The design, its decisions and its open questions are in docs/stateless-api-design.md; the contract is docs/api/openapi-v1.yaml with docs/integration-guide.md; deployment and settings are in docs/operations-runbook.md; and the load studies' narrative is docs/api-load.md. Reproduce this document with docs/scheduler-explained-2026-10-14/requirements.txt, then run report_data.py, build_report.py and verify_report.py there. The builder reads every source, regenerates all figures and chart_data.json, and writes this editable Word file; EVIDENCE.md records methods and limits.")

    part("Appendix B  Definitions and measures")
    sub("Terms")
    table(["Term", "Meaning in this document"], [
        ("Snapshot", "The facts of one metro's technician-days a calculating call carries: rates, policy, technicians, days with lastModified, committed appointments, locations."),
        ("Thin store", "The scheduler's own tables: holds, offer sets, proposals, receipts, request claims, tenants and tokens, the route cache; nothing the host owns."),
        ("Input revision", "A canonical hash of a snapshot; every proposal and offer records the one it was computed from."),
        ("STALE", "409 answer to a snapshot older than records the scheduler already used; the host rebuilds from current records and resends."),
        ("Slot", "One of a replica's concurrent search permits (SCHEDULER_SEARCH_CAPACITY, 2 in every run here); a confirm slot is a permit only a confirm may take."),
        ("Desk, planner", "One concurrent booking loop, or one concurrent daily loop, in the load tool."),
        ("Fresh seed", "A load run whose random addresses no earlier run used, so its roads are not cached anywhere."),
        ("Matched case", "The same starting day, seed and budget solved by two configurations, so the results differ only in the configuration."),
        ("Tabu search", "A local search that keeps a short memory of recently moved entities and will not move them back at once, and that accepts the best available step even when it is worse."),
        ("K opt move", "A move that reconnects several route links at once to explore coordinated order changes; the sublist move moves a stretch of visits together."),
        ("Late acceptance", "A local search that accepts a move if it beats the solution from a fixed number of steps ago, even when it worsens the current one."),
        ("Insertion, bounded search", "Insertion tries the new visit in every position without moving existing work; bounded search also tries limited rearrangements around the affected routes."),
        ("Contraction hierarchy", "A road graph preprocessed with shortcut edges ordered by importance, so that a query touches few nodes; identical answers, less work."),
        ("Routing identity", "A hash of the road graph and map version; every cached leg is bound to one, and a change clears the caches."),
        ("Move threads", "Timefold's option to spread one solve's move evaluation over several threads (moveThreadCount); planned work in section 15, not used today.")], [1.5, 5.4], size=9)
    sub("Measures")
    table(["Measure", "How to read it"], [
        ("Saving per case", "The control's accepted cost minus the configuration's, in modeled dollars, for the same starting day; positive is cheaper than the control."),
        ("Wins / ties / losses", "Matched cases where the configuration's accepted cost was lower, equal or higher than the control's, in exact cents."),
        ("Fairness variance", "Capacity-weighted variance of each technician's paid time over capacity; lower is more even."),
        ("Best-found point", "When in the cost phase a search found the schedule it ended with, as a share of the phase."),
        ("Searches per minute", "Booking searches answered 201 in a load run, scaled to a minute; refusals and resends do not count."),
        ("p50 / p95", "The latency that half, or 95 percent, of calls in a run stayed within, measured at the caller with every wait included. The booking design target is p95 within the client's 5 s deadline plus 500 ms."),
        ("Holds lost", "Confirms answered 409 HOLD_UNAVAILABLE out of the holds customers selected: a customer who chose a time and could not get it."),
        ("Moves per second", "Candidate moves the daily search evaluated per second of its phase; more moves in the same budget is a better search, not a faster answer.")], [1.55, 5.35], size=9)

    # Every generated figure is placed exactly once, and every one the charts produced is used.
    produced = {pth.stem for pth in (HERE / "generated").glob("*.png")}
    assert set(figures) == produced, set(figures) ^ produced
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
    (HERE / "generated" / "figures.txt").write_text("\n".join(figures) + "\n", encoding="utf-8")
    print(OUTPUT, len(figures), "figures")


if __name__ == "__main__": build()
