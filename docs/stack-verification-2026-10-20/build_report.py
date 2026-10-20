"""Build the stack verification report from the validated evidence and the reference explainer's template.

Install requirements.txt first. No network, services or load runs. The two diagrams in diagrams/ are rendered to
generated/ separately (see EVIDENCE.md); the charts are regenerated here.
"""
from copy import deepcopy
from datetime import datetime
from decimal import Decimal
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

from charts import make_charts
from report_data import (COMMITS, FLEETS, IMAGES, PHASES, RECEIPTS, SEEDS, STEP2, container, decimal_p50, fairer, harness, load, operation, p50,
                         ratio, receipts, speeds)

HERE = Path(__file__).resolve().parent
REFERENCE = HERE.parent / "WaterFlex_Scheduler_Algorithm_Comparison_Explained.docx"
REFERENCE_SHA = "5366f3017d6f18c15de295ba5fb9c1a014b6222911040f1c5e044845e57f832d"
OUTPUT = HERE / "Stack_Verification_2026-10-20_Report.docx"
PRESERVE = ("word/styles.xml", "word/stylesWithEffects.xml", "word/numbering.xml", "word/theme/theme1.xml", "word/fontTable.xml")
DIAGRAMS = ("stack-path", "receipt-pairing")
DAILY = "scheduler-vm-scheduler-daily"


def x(value):
    return f"{value:.2f}x"


def thousands(value):
    return f"{value / 1000:,.1f}k"


def seconds(ms):
    return f"{ms / 1000:.1f}"


def variance(value):
    return f"{Decimal(value):.4f}"


def signed(value):
    return f"{value:+,}" if value else "0"


def build():
    assert sha256(REFERENCE.read_bytes()).hexdigest() == REFERENCE_SHA
    document, runs = load()
    assets = HERE / "generated"
    for name in DIAGRAMS:
        assert (assets / f"{name}.png").exists(), f"render diagrams/{name}.html first (EVIDENCE.md)"
    make_charts(runs, assets)
    doc = Document(REFERENCE)
    table_properties = deepcopy(doc.tables[0]._tbl.tblPr)
    for child in list(doc.element.body):
        if child.tag != qn("w:sectPr"): doc.element.body.remove(child)
    for rid, rel in list(doc.part.rels.items()):
        if rel.reltype.endswith(("/image", "/hyperlink")): del doc.part.rels[rid]
    doc.core_properties.title = "WaterFlex scheduler option b on the rehearsal stack"
    doc.core_properties.subject = "The binary64 fairness variance verified behind the public API against the decimal variance it replaces, 2026-10-20"
    doc.core_properties.author = doc.core_properties.last_modified_by = "WaterFlex"
    doc.core_properties.created = doc.core_properties.modified = datetime(2026, 10, 20)
    figures = []

    def p(text, style=None):
        assert "\u2014" not in text and "\u2013" not in text
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
                text = str(value)
                assert "\u2014" not in text and "\u2013" not in text
                cell.width = Inches(width); cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER; cell.text = text
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

    def figure(name, caption, width=6.85):
        figures.append(name)
        caption = f"Figure {len(figures)}. {caption}"
        para = p(""); shape = para.add_run().add_picture(str(assets / f"{name}.png"), width=Inches(width))
        shape._inline.docPr.set("descr", caption); para.paragraph_format.keep_with_next = True; para.alignment = 1
        cap = p(caption, "Caption"); cap.paragraph_format.space_after = Pt(7)
        for run in cap.runs:
            run.font.color.rgb = RGBColor(0, 0, 0); run.bold = False; run.italic = True

    # The numbers the narrative refers to by name.
    def report(image, fleet, seed, step=1):
        return runs[(step, image, fleet, seed)]["report"]

    queued = {image: report(image, *STEP2, step=2) for image in IMAGES}

    def run_speed(rep, phase):
        return p50(speeds(rep, phase))

    def fleet_ratio(fleet, phase):
        return median([ratio(runs, 1, fleet, seed, phase) for seed in SEEDS])

    def seed_range(fleet, phase):
        values = [ratio(runs, 1, fleet, seed, phase) for seed in SEEDS]
        return min(values), max(values)

    def gap(reps):
        """Each proposal's fairness-phase rate over its own reference-phase rate; the median over the proposals given."""
        return median([r["solves"][1]["movesPerSecond"] / r["solves"][0]["movesPerSecond"] for rep in reps for r in receipts(rep)])

    def fleet_reps(image, fleet):
        return [report(image, fleet, seed) for seed in SEEDS]

    def fairer_count(reps):
        return sum(fairer(r) for rep in reps for r in receipts(rep))

    def call_p50(reps):
        return median([operation(rep)["ms"]["p50"] for rep in reps])

    def call_max(reps):
        return max(operation(rep)["ms"]["max"] for rep in reps)

    def fetched(reps):
        return sum(rep["server"]["routingPairs"] for rep in reps)

    def cpu(reps):
        return median([container(rep, DAILY)["cpuPercent"]["mean"] for rep in reps])

    def cost_change(rep):
        return p50([r["body"]["proposed"]["costCents"] - r["body"]["reference"]["costCents"] for r in receipts(rep)])

    by_fleet, harness_overall = harness()
    all_reps = [entry["report"] for entry in runs.values()]
    assert sum(len(receipts(rep)) for rep in all_reps) == RECEIPTS
    started = min(rep["startedAt"] for rep in all_reps); finished = max(rep["finishedAt"] for rep in all_reps)
    reasons = {r["body"]["reason"] for rep in all_reps for r in receipts(rep)}
    assert reasons == {"COST_REDUCTION"}
    queued_ratio = {phase: ratio(runs, 2, *STEP2, phase) for phase in PHASES}
    drops = [run_speed(queued[image], phase) / median([run_speed(report(image, STEP2[0], seed), phase) for seed in SEEDS]) for image in IMAGES for phase in PHASES]
    assert all(d < 1 for d in drops)
    reference_ratios = [fleet_ratio(fleet, "REFERENCE") for fleet in FLEETS] + [queued_ratio["REFERENCE"]]
    gaps = {image: {fleet: gap(fleet_reps(image, fleet)) for fleet in FLEETS} for image in IMAGES}
    dearer = [r for rep in all_reps for r in receipts(rep) if r["body"]["proposed"]["costCents"] > r["body"]["reference"]["costCents"]]
    assert not dearer
    savings = {image: min(r["body"]["proposed"]["costCents"] - r["body"]["reference"]["costCents"] for fleet in FLEETS for rep in fleet_reps(image, fleet)
                          for r in receipts(rep)) for image in IMAGES}
    lo, hi = FLEETS[0], FLEETS[-1]
    pairs = [(fleet, seed) for fleet in FLEETS for seed in SEEDS]
    found = [pair for pair in pairs if any(fairer(r) or r["body"]["proposed"]["costCents"] != r["body"]["reference"]["costCents"]
                                           for image in IMAGES for r in receipts(report(image, *pair)))]
    assert found == [(20, 3), (50, 1), (50, 2), (50, 3)], found  # the pairs the narrative names
    assert fairer_count([report('before', 20, 3)]) == 0 < fairer_count([report('after', 20, 3)])
    window = f"{started[11:16]} to {finished[11:16]} UTC"
    minutes = round((datetime.fromisoformat(finished[:19]) - datetime.fromisoformat(started[:19])).total_seconds() / 60)

    p("WaterFlex scheduler option b on the rehearsal stack", "Title")
    p("The binary64 fairness variance verified behind the public API, against the decimal variance it replaces", "Subtitle")
    p(f"October 20 2026  |  Stack verification of pull request #189, run 2026-10-20 from {window}")
    p(f"Two studies on the workstation harness promoted option b: the fleet's utilization variance computed in binary64 and carried in units of 1e-9, in place of the DECIMAL128 variance the fairness phase recomputed for every candidate move. The owner's rule is that a change promoted on the harness is verified on the VM rehearsal stack, through the public API with the shipped defaults, before it is called shipped. This report is that verification. Two images, built from the commit before option b and the commit that ships it, answered the same {RECEIPTS} daily proposals on the same stack. The fairness phase evaluated {x(fleet_ratio(lo, 'FAIRNESS'))} as many moves per second at {lo} technicians and {x(fleet_ratio(hi, 'FAIRNESS'))} at {hi}, and {x(queued_ratio['FAIRNESS'])} with two planners queuing; the reference phase, which does not score fairness, was unchanged ({min(reference_ratios):.2f}x to {max(reference_ratios):.2f}x). No proposal cost more than its reference plan, every proposal answered 201, and where the fairness phase found a better plan, option b's was at least as fair and as cheap in every pair. Option b holds on the stack, and pull request #189 ships it.")
    sub("What the measurements tell us")
    table(["Fleet", "Fairness-phase speed, after over before (range by seed)", "Reference-phase speed", "Fairness phase as a share of the reference phase, before / after", "Fairer proposals, before / after", "Proposal call p50, before / after"],
          [*[[f"{fleet} technicians", f"{x(fleet_ratio(fleet, 'FAIRNESS'))} ({seed_range(fleet, 'FAIRNESS')[0]:.2f} to {seed_range(fleet, 'FAIRNESS')[1]:.2f})", x(fleet_ratio(fleet, "REFERENCE")),
              f"{gaps['before'][fleet]:.2f} / {gaps['after'][fleet]:.2f}", f"{fairer_count(fleet_reps('before', fleet))} / {fairer_count(fleet_reps('after', fleet))} of 12",
              f"{seconds(call_p50(fleet_reps('before', fleet)))} s / {seconds(call_p50(fleet_reps('after', fleet)))} s"] for fleet in FLEETS],
            [f"{STEP2[0]}, 2 planners queuing", f"{x(queued_ratio['FAIRNESS'])} (one run)", x(queued_ratio["REFERENCE"]), f"{gap([queued['before']]):.2f} / {gap([queued['after']]):.2f}",
             f"{fairer_count([queued['before']])} / {fairer_count([queued['after']])} of 12", f"{seconds(operation(queued['before'])['ms']['p50'])} s / {seconds(operation(queued['after'])['ms']['p50'])} s"]],
          [1.15, 1.45, .9, 1.4, 1.0, 1.0], size=9)
    p("Each speed is the median over three points seeds of the after image's moves per second divided by the before image's, each side being the median of four proposals. A share is the fairness phase's rate divided by the same proposal's reference-phase rate, the median over twelve proposals; 1.00 would mean the two phases evaluate moves equally fast. A fairer proposal is one whose DECIMAL128 variance is below its own reference plan's. A call is the whole proposal as the client saw it: routing, a 10 s cost search, a 5 s fairness search, validation and the reply.")
    p(f"The report explains each result through what the stack recorded: Timefold's own moves per second at the end of each phase, read from the daily replica's log, paired with a study receipt the replica wrote for every proposal; the reference and proposed plans' cost and variance from that receipt; and the kit's own timing of every call, queue wait and container. All of it comes from one evidence file, docs/evidence/api-load-2026-10-20-binary-variance.json. None of it is customer data; the fleets and appointments are synthetic.")
    sub("How to read this report")
    table(["Sections", "What you will learn"], [
        ("1", "What was measured: the two images, the stack, the two steps, and how a phase's speed reaches the evidence."),
        ("2", "Why the gain grows with the fleet, read from the two phases of the same proposal."),
        ("3", "The speed of each phase, by fleet, seed and queue, beside the harness's own ratios."),
        ("4", "What the extra moves bought: each proposal's variance and cost against its own reference plan."),
        ("5", "The service: call times, queue waits, CPU, and why the slowest calls are not comparable."),
        ("6 to 8", "What went wrong while running the verification, the conclusions, and what should happen next."),
        ("Appendices", "Every proposal, every run, definitions and sources.")], [1.45, 5.45])

    heading("1 What was measured")
    p("A daily proposal runs two searches on one day's plan. The reference phase looks for the cheapest plan that breaks no rule; the fairness phase starts from it and looks for a plan that spreads the work more evenly without exceeding the reference plan's cost plus the client's allowance. Option b changes only the fairness phase's objective: the same capacity-weighted variance of each technician's utilization, computed in binary64 and rounded to an integer count of 1e-9 units, instead of in DECIMAL128. Acceptance and reporting still read the DECIMAL128 variance. [1]")
    table(["Image", "Commit", "What it carries", "Tag on the stack"], [
        ("before", COMMITS["before"][:7], "Main's decimal variance objective, with the study receipt this pull request adds", document["images"]["before"]["tag"].split(":")[1]),
        ("after", COMMITS["after"][:7], "Option b, score model bendable-decimal-repair-v3, the rejected measures removed", document["images"]["after"]["tag"].split(":")[1])], [.75, .85, 3.3, 2.0], size=9)
    p("The commits after the after image on the branch change only this verification's script and documents, so the after image is the code pull request #189 ships. Both images were built once, each in its own worktree, and the stack ran each by its tag with SCHEDULER_IMAGE and SCHEDULER_PULL_POLICY=never. The run checked every replica it measured against the image's ID before reading its log.")
    figure("stack-path", "The path one proposal takes. The kit posts through the proxy to the daily replica, which reads Postgres and the routing service, runs the reference phase and then the fairness phase, and logs each phase's speed and a study receipt. Both images run this path; only the fairness phase's objective differs (accent).")
    table(["Dimension", "Values in this run"], [
        ("Stack", "deploy/vm with rehearsal.override.yml at 127.0.0.1:18080: proxy, booking and daily pools at their VM caps (each replica 2 CPUs, 2 GiB), Postgres, the development routing service capped to 2 CPUs; shipped routing and admission defaults; calculation embedded in the daily replica"),
        ("Step 1", "One planner, synchronous. Fleets of 10, 20 and 50 technicians with 3 visits each, points seeds 1, 2 and 3, 4 proposals per fleet and seed: 72 proposals per image pair, 36 per image"),
        ("Step 2", f"Two planners queuing (Prefer: respond-async) on {STEP2[0]} technicians, points seed {STEP2[1]}, 12 proposals per image: the daily pool's shape, a background limit of 2 on 2 CPUs"),
        ("Search", "10 s cost (reference) phase and 5 s fairness phase; the kit's policy, TABU with a fairness allowance of zero (section 6)"),
        ("Order", "Step 1 ran before then after; step 2 ran after then before"),
        ("Load generator", f"The host-conformance kit (load.ts, daily scenario) on the workstation that runs the stack ({runs[(1, 'before', lo, 1)]['report']['machine']['cpus']} processors); runner revision {document['runnerRevision'][:7]}"),
        ("Window", f"2026-10-20, {window}, {minutes} minutes for {len(runs)} runs")], [1.3, 5.6])
    sub("How a phase's speed reaches the evidence")
    p("Timefold logs one line when each phase's solver ends, with its time spent and move evaluation speed: the same quantity the harness calls moves per second, read from the solver instead of from a benchmark wrapper. With TIMEFOLD_LOG_LEVEL=INFO and SCHEDULER_STUDY_RECEIPTS=true on the daily pool, the replica also logs one study receipt per proposal: the phases that ran, their budgets and terminations, the reference and proposed plans' cost, overtime and DECIMAL128 variance, the cost ceiling, and the acceptance reason. Both lines carry the request's tenant and ID. After each run the kit reads the replica's log and pairs them. [2]")
    figure("receipt-pairing", "How the kit pairs a receipt with its phase speeds. Each Timefold phase-end line queues its speed under its request; a receipt takes its request's queued speeds only when there is exactly one per phase it ran, and is otherwise counted unpaired and given none. A receipt outside the contract fails the run.", width=6.2)
    p(f"All {RECEIPTS} receipts in this run were paired, with two phases each, and the loader refuses the evidence if any is not. It also checks that every receipt names the TABU search, 3 visits per technician, a searched and accepted fairness phase, a ceiling equal to the reference plan's cost, a proposal inside that ceiling and no overtime; and it recomputes each run's medians from the receipts and requires them to equal what the kit recorded.")

    heading("2 Why the gain grows with the fleet")
    p("A local search evaluates a candidate move by changing the plan, rescoring it and undoing the change. Timefold rescores incrementally, walking again only the routes a move touched. A variance cannot be rescored that way, because it depends on the fleet's mean, so production recomputed it over every technician for every move, in DECIMAL128 with long division. That work grows with the fleet; the rest of a move's cost depends mostly on the routes it touches. Option b keeps the same recomputation in binary64, a few cycles per technician. [1]")
    p("The cleanest reading of this on the stack does not compare the two images at all. Every proposal ran both phases on the same replica, the same day's plan and the same roads, so the fairness phase's rate divided by its own reference phase's rate isolates what the fairness objective costs per move.")
    figure("gap", "Each proposal's fairness-phase moves per second divided by its own reference phase's, 12 proposals per fleet and image. The black bar is the median. 1.0 (dashed) would be a fairness phase as fast as the cost search.")
    table(["Fleet", "Before: fairness phase as a share of the reference phase", "Before: the fairness phase is slower by", "After: share", "After: slower by"],
          [[f"{fleet} technicians", f"{gaps['before'][fleet]:.2f}", f"{1 / gaps['before'][fleet]:.1f} times", f"{gaps['after'][fleet]:.2f}", f"{1 / gaps['after'][fleet]:.2f} times"] for fleet in FLEETS],
          [1.3, 1.75, 1.45, 1.1, 1.3])
    p(f"Before option b the fairness phase fell from {1 / gaps['before'][lo]:.1f} times slower than the cost search at {lo} technicians to {1 / gaps['before'][hi]:.1f} times slower at {hi}. After it, the two phases run within {(1 - min(gaps['after'].values())) * 100:.0f} percent of each other at every size. Because the after image's fairness phase now costs about what a cost-phase move costs, the after over before ratio in the next section is roughly the inverse of the before column, and it grows with the fleet for the same reason. A metro larger than {hi} technicians should expect a larger ratio still.")

    heading("3 The speed of each phase")
    figure("speed", "Every proposal's moves per second in each phase, by fleet and image (12 per box). The black bar is the median. The reference phase overlaps between images; the fairness phase does not.")
    table(["Fleet, seed", "Reference, before", "Reference, after", "Ratio", "Fairness, before", "Fairness, after", "Ratio"],
          [*[[f"{fleet}, {seed}", thousands(run_speed(report('before', fleet, seed), 'REFERENCE')), thousands(run_speed(report('after', fleet, seed), 'REFERENCE')),
              x(ratio(runs, 1, fleet, seed, "REFERENCE")), thousands(run_speed(report('before', fleet, seed), 'FAIRNESS')), thousands(run_speed(report('after', fleet, seed), 'FAIRNESS')),
              x(ratio(runs, 1, fleet, seed, "FAIRNESS"))] for fleet, seed in pairs],
            [f"{STEP2[0]}, {STEP2[1]}, queued", thousands(run_speed(queued['before'], 'REFERENCE')), thousands(run_speed(queued['after'], 'REFERENCE')), x(queued_ratio["REFERENCE"]),
             thousands(run_speed(queued['before'], 'FAIRNESS')), thousands(run_speed(queued['after'], 'FAIRNESS')), x(queued_ratio["FAIRNESS"])]],
          [1.3, 1.0, 1.0, .8, 1.0, 1.0, .8], size=9)
    p("Each speed is the median moves per second of the run's proposals (four in step 1, twelve queued), and each ratio is after over before. The rates are this stack's: a daily replica of 2 CPUs on the workstation, beside the routing service, Postgres and the other replicas. They are not the harness's rates and should not be compared with them; the ratios are what transfers between the two instruments.")
    figure("ratio", f"After over before by fleet: each seed's fairness-phase ratio (circles) and its median (line), the queued step (diamond), the reference phase (squares), and option b's fairness-phase ratio on the workstation harness in the road matrix numbering study (amber, fleets of 10, 30 and 50, {harness_overall:.2f}x over all its fleets).")
    p(f"The stack follows the harness's growth with the fleet, a little lower at each size: {x(fleet_ratio(lo, 'FAIRNESS'))} against {x(by_fleet[lo])} at {lo} technicians and {x(fleet_ratio(hi, 'FAIRNESS'))} against {x(by_fleet[hi])} at {hi}. The difference is not explained by the evidence. Two plausible contributors, not measured here, are the search (the harness ran TABU_KOPT, the kit TABU) and the fleets (the harness's four workloads against three visits per technician here), since either changes how much of a move's cost the variance was. Seeds agree closely: the widest spread of a fleet's three ratios is {max(seed_range(f, 'FAIRNESS')[1] - seed_range(f, 'FAIRNESS')[0] for f in FLEETS):.2f}.")
    sub("Two planners queuing")
    p(f"Step 2 put the daily pool in its production shape: two planners submitting asynchronously to a replica that runs two calculations at once on its 2 CPUs. The fairness phase gained {x(queued_ratio['FAIRNESS'])} at {STEP2[0]} technicians, in line with step 1's {x(fleet_ratio(STEP2[0], 'FAIRNESS'))} on the same fleet, and the reference phase {x(queued_ratio['REFERENCE'])}. Each phase's absolute rate was {(1 - max(drops)) * 100:.0f} to {(1 - min(drops)) * 100:.0f} percent lower than with one planner on the same fleet, as two searches shared the replica; the ratio between images did not change. Section 5 has the queue.")

    heading("4 What the extra moves buy")
    p("The proposal does not finish sooner under option b: the fairness phase has a fixed 5 s, and spends it evaluating more moves. Whether those moves buy anything depends on whether a fairer plan exists within the ceiling. Here the kit's policy gives no fairness allowance, so the ceiling is the reference plan's own cost, and the fairness phase can only accept a plan that is at least as cheap. That is a stricter test than the client policy's 2 percent.")
    figure("variance", "Each proposal's DECIMAL128 variance divided by its own reference plan's, by fleet and points seed (four proposals per image). Below 1.0 is fairer than the plan the fairness phase started from.")
    figure("cost", "Each proposal's cost minus its own reference plan's, in cents. Zero is the ceiling under the kit's policy; no proposal of either image is above it.")
    table(["Fleet, seed", "Reference cost p50, before / after (c)", "Proposed variance p50, before / after", "Proposed minus reference cost p50, before / after (c)", "Fairer, before / after"],
          [[f"{fleet}, {seed}", f"{p50([r['body']['reference']['costCents'] for r in receipts(report('before', fleet, seed))]):,} / {p50([r['body']['reference']['costCents'] for r in receipts(report('after', fleet, seed))]):,}",
            f"{variance(decimal_p50([r['body']['proposed']['variance'] for r in receipts(report('before', fleet, seed))]))} / {variance(decimal_p50([r['body']['proposed']['variance'] for r in receipts(report('after', fleet, seed))]))}",
            f"{signed(cost_change(report('before', fleet, seed)))} / {signed(cost_change(report('after', fleet, seed)))}",
            f"{fairer_count([report('before', fleet, seed)])} / {fairer_count([report('after', fleet, seed)])} of 4"] for fleet, seed in pairs],
          [1.0, 1.65, 1.6, 1.65, 1.0], size=9)
    p(f"In {len(pairs) - len(found)} of the {len(pairs)} fleet and seed pairs the fairness phase found nothing to change under either image: every proposal was its reference plan, to the cent and the DECIMAL128 digit, and both images' reference plans were the same. These are the 10-technician fleets and two of the three 20-technician seeds, where neither search found a fairer plan at no extra cost. In the other {len(found)}, the after image's proposals were fairer than their reference plans in {sum(fairer_count([report('after', *pair)]) for pair in found)} of {4 * len(found)} and the before image's in {sum(fairer_count([report('before', *pair)]) for pair in found)}; at 20 technicians, seed 3, only option b found a fairer plan at all. No proposal of either image cost more than its reference plan, and the largest saving was {-savings['after']} cents under option b against {-savings['before']} before.")
    p("The fairness phase's second objective is cost, which is why a proposal can be both fairer and cheaper than the plan it started from: with zero allowance, every move the search keeps must not raise the cost, and the extra moves under option b find more of the plans that lower both. Reference plans differ slightly between images in the pairs where the search found something (and between proposals of one image), because a 10 s search ends where it is when time runs out; the comparison that matters is each proposal against its own reference, which the receipt makes exact.")
    p("Every proposal was accepted, with the API decision IMPROVED, and the policy recorded each as COST_REDUCTION. That reason compares the proposal with the schedule the kit sent, not with the reference plan: the proposal was cheaper than the kit's schedule and not fairer than it. It is the same on both images and does not distinguish them. [1]")

    heading("5 The service")
    p("A daily proposal searches for a fixed 15 s, so option b was never expected to shorten a call; the owner's question for the stack is whether anything else about the service changed. It did not. Call times, queue waits and admission were the same on both images, and no run had a contract breach, a host anomaly, an error or an admission refusal.")
    figure("calls", "Each run's median proposal call (bars) and its slowest call (triangles). A filled triangle is a run that fetched roads from the routing service; a hollow one routed entirely from the cache.")
    table(["Fleet", "Call p50, before / after (s)", "Slowest call, before / after (s)", "Road pairs fetched, before / after", "Daily replica CPU, mean, before / after"],
          [[f"{fleet} technicians", f"{seconds(call_p50(fleet_reps('before', fleet)))} / {seconds(call_p50(fleet_reps('after', fleet)))}",
            f"{seconds(call_max(fleet_reps('before', fleet)))} / {seconds(call_max(fleet_reps('after', fleet)))}",
            f"{fetched(fleet_reps('before', fleet)):,} / {fetched(fleet_reps('after', fleet)):,}",
            f"{cpu(fleet_reps('before', fleet)):.0f}% / {cpu(fleet_reps('after', fleet)):.0f}%"] for fleet in FLEETS],
          [1.15, 1.35, 1.45, 1.45, 1.5])
    p("Call p50 and the slowest call are the median and maximum over the fleet's three runs of each run's own; road pairs are summed over them; CPU is the median of the runs' mean daily replica CPU, where 100 percent is one core.")
    sub("Why the slowest calls are not comparable")
    p(f"The protocol meant every run to start with cold roads. It did not: the scheduler keeps fetched legs in the database's road_route_cache table, which survived the switch of images. So in step 1 the before image fetched every pair from the routing service ({fetched([report('before', f, s) for f, s in pairs]):,} in all) and the after image, running second on the same points, fetched none. Step 2 ran in the other order, and there the after image fetched {queued['after']['server']['routingPairs']:,} and the before image none. Before's {seconds(call_max(fleet_reps('before', hi)))} s slowest call at {hi} technicians is that routing. Moves per second and plans are unaffected, because the search runs its fixed time after the roads are in hand. The daily replica's mean CPU is affected, since a run that waits on the routing service idles: the after image's higher mean in step 1 is most likely the absent wait, not more work. The next verification should clear the cache before every run (section 8).")
    sub("The queue")
    figure("queued", f"Step 2: two planners queuing on {STEP2[0]} technicians. Left, each proposal's moves per second in each phase; right, how long each proposal waited in the replica's queue before it started.")
    qrows = [("Proposal call p50 / slowest (s)", lambda r: f"{seconds(operation(r)['ms']['p50'])} / {seconds(operation(r)['ms']['max'])}"),
             ("Queue wait p50 / longest (ms)", lambda r: f"{p50(r['daily']['queueWaitsMs'])} / {max(r['daily']['queueWaitsMs'])}"),
             ("Reference phase, median moves per second", lambda r: thousands(run_speed(r, "REFERENCE"))),
             ("Fairness phase, median moves per second", lambda r: thousands(run_speed(r, "FAIRNESS"))),
             ("Daily replica CPU, mean / peak", lambda r: f"{container(r, DAILY)['cpuPercent']['mean']:.0f}% / {container(r, DAILY)['cpuPercent']['max']:.0f}%"),
             ("Road pairs fetched", lambda r: f"{r['server']['routingPairs']:,}"),
             ("Decisions; proposals fairer than their reference", lambda r: f"{r['daily']['decisions']['IMPROVED']} IMPROVED; {fairer_count([r])}")]
    table(["Measure", "before", "after"], [[name, read(queued["before"]), read(queued["after"])] for name, read in qrows], [3.3, 1.8, 1.8])
    p(f"A queued call is timed from the POST until the poll that found it complete; the kit polls as the service's Retry-After asks, so part of the {seconds(operation(queued['after'])['ms']['p50'] - call_p50(fleet_reps('after', STEP2[0])))} s above step 1's call is the interval between polls, the same on both images. The queue itself was nearly empty: with a background limit of 2 and two planners, each proposal started within a few milliseconds. Neither image found a fairer plan on this seed; every proposal was its reference plan.")

    heading("6 Running the verification")
    p("infra/run-stack-verification.ps1 builds both images, brings the stack up on each, issues a token and runs the kit. It needed three starts. None of the problems below touched a measurement: the first two stopped the script before any proposal was sent, and the third is a field of the evidence the loader corrects on reading.")
    table(["Issue", "What happened", "What was done"], [
        ("Receipts on the booking pool", "The first start checked that every scheduler replica had study receipts on, but only the daily pool calculates daily proposals; the booking replicas correctly had them off, and the check stopped the run.", "Receipts are checked on the daily pool only (82d3719)."),
        ("A stale token", "The second start reused a token kept from an earlier rehearsal database, which the new database did not know, and the first proposal got a 401.", "A token the stack refuses is kept aside as .stale and a new one issued (2138349)."),
        ("Booking replica names", "The evidence records each booking replica's name as a one-element list, because the script nested an array. Every daily replica's name is a plain string.", "The loader accepts exactly one name in a list; the script is fixed with this report."),
        ("Warm roads", "road_route_cache survived the switch of images, so only the first image of each pair routed cold. The evidence's protocol text still says roads were cold at each run's start.", "Disclosed in section 5 and EVIDENCE.md; the evidence file is not edited."),
        ("The kit's policy", "The kit runs TABU with a fairness allowance of zero, not the example client policy (TABU_KOPT, 2 percent).", "Disclosed in section 4; a short rerun with the client policy is in section 8.")], [1.35, 3.6, 1.95], size=9)
    p("The policy matters differently for the two results. Option b changes only the fairness constraint, which every search variant scores the same way, so the speed result should carry over to TABU_KOPT; the harness measured b under TABU_KOPT and found the same growth with the fleet. The quality result was obtained under zero allowance, which forbids any extra cost, so it is the stricter case; a run with the 2 percent allowance would show how much fairer option b gets when it may spend.")

    heading("7 Conclusions")
    table(["Question", "Answer from the evidence"], [
        ("Does option b's speedup hold behind the public API?", f"Yes. The fairness phase evaluated {x(fleet_ratio(lo, 'FAIRNESS'))} as many moves per second at {lo} technicians, {x(fleet_ratio(20, 'FAIRNESS'))} at 20 and {x(fleet_ratio(hi, 'FAIRNESS'))} at {hi}, and {x(queued_ratio['FAIRNESS'])} with two planners queuing."),
        ("Does it agree with the harness?", f"It follows the same growth with the fleet, a little lower at each size ({x(by_fleet[lo])} and {x(by_fleet[hi])} on the harness at {lo} and {hi})."),
        ("Is anything else slower?", f"No. The reference phase ran at {min(reference_ratios):.2f}x to {max(reference_ratios):.2f}x, calls took as long as before, and queued proposals waited a few milliseconds on both images."),
        ("Are the plans as good?", f"At least as good in every pair. Where the fairness phase found a better plan the after image's proposals were fairer in {sum(fairer_count([report('after', *pair)]) for pair in found)} of {4 * len(found)} against {sum(fairer_count([report('before', *pair)]) for pair in found)}, and no proposal of either image cost more than its reference plan."),
        ("Why does the gain grow with the fleet?", f"The decimal variance was recomputed over the whole fleet on every move. Before b the fairness phase was {1 / gaps['before'][hi]:.1f} times slower than the cost search at {hi} technicians; after it, the two run within {(1 - min(gaps['after'].values())) * 100:.0f} percent."),
        ("What does the run not show?", "Option b under the client policy's allowance and search, call latency with cold roads on both images, and fleets above 50 technicians.")], [2.4, 4.5])
    p("Two cautions carry over. The fleets are synthetic, at most 50 technicians with 3 visits each. And the stack ran on the workstation beside the load generator, with the VM's caps but not the VM's processor; its absolute rates are this stack's, and the paired ratios are what should be expected on the VM.")

    heading("8 What should happen next")
    table(["Next step", "What to do", "What it decides"], [
        ("Merge pull request #189", "Option b, score model v3 and the receipts ship as verified. The image verified as after is the code being merged.", "Option b in production."),
        ("Rerun with the client policy", "Add search and allowance flags to the kit's daily scenario and repeat step 1 at 20 and 50 technicians with TABU_KOPT and 2 percent, both images.", "How much fairer b gets when it may spend; that the speed holds under TABU_KOPT."),
        ("Clear the road cache per run", "Truncate road_route_cache in the verification script before each run, so both images route cold and the slowest calls compare.", "Comparable call latency in every later verification."),
        ("Representation study 2 (PR I)", "Long score, long money, fleet sums without toList() and the timing search, on the harness against main after #189, promoted at 1.05x on the targeted phase, then verified on this stack with this script.", "The next speedup, in both phases."),
        ("Load balance head to head (PR J)", "Option b against Timefold's load balance on the harness, fleets 5 to 50, with bars written before the run.", "Whether the fairness policy changes."),
        ("Clean up the run", "Revoke the study tokens the script issued and remove the two study images when they are no longer needed.", "Nothing left behind on the workstation.")], [1.6, 3.5, 1.8])
    sub("How the calculations were checked")
    p("Every table and chart value is computed from the evidence file at build time; nothing is typed in. report_data.py checks the evidence before use: both images are the pinned commits, and every replica measured ran its image with Timefold at INFO and receipts on the daily pool; every run of both steps is present exactly once with the settings and policy above; every call answered 201 and every decision was IMPROVED; there is no contract breach or host anomaly; every receipt was paired, ran both phases and stayed inside its ceiling with no overtime; and each run's medians, recomputed from its receipts, equal what the kit recorded. verify_report.py recomputes the tables a second time from the raw JSON without the builder's helpers, and checks the chart data, the figure captions, the template parts and the absence of dashes. [3]")

    heading("Appendix A Every proposal")
    p("Each row is one proposal in the order the replica logged it: Timefold's moves per second in each phase, then the reference and proposed plans' cost in cents and DECIMAL128 variance from its receipt. A proposed variance below the reference's is a fairer plan. [3]")
    table(["Step", "Image", "Techs", "Seed", "#", "Ref. moves/s", "Fair. moves/s", "Ref. cost", "Proposed cost", "Ref. variance", "Proposed variance"],
          [[step, image, fleet, seed, i, f"{r['solves'][0]['movesPerSecond']:,}", f"{r['solves'][1]['movesPerSecond']:,}", f"{r['body']['reference']['costCents']:,}",
            f"{r['body']['proposed']['costCents']:,}", variance(r["body"]["reference"]["variance"]), variance(r["body"]["proposed"]["variance"])]
           for (step, image, fleet, seed) in sorted(runs, key=lambda k: (k[0], k[2], k[3], IMAGES.index(k[1])))
           for i, r in enumerate(receipts(runs[(step, image, fleet, seed)]["report"]), 1)],
          [.4, .55, .45, .4, .3, .7, .7, .75, .75, .95, .95], size=8)

    heading("Appendix B Every run")
    p("Each row is one run of the kit: when it started, its proposal calls, the road pairs it fetched from the routing service, and the daily replica's mean CPU while it ran. [3]")
    table(["Step", "Image", "Techs", "Seed", "Started (UTC)", "Proposals", "Call p50 (s)", "Slowest (s)", "Roads fetched", "Daily CPU mean", "Decisions"],
          [[step, image, fleet, seed, rep["startedAt"][11:19], rep["daily"]["proposals"], seconds(operation(rep)["ms"]["p50"]), seconds(operation(rep)["ms"]["max"]),
            f"{rep['server']['routingPairs']:,}", f"{container(rep, DAILY)['cpuPercent']['mean']:.0f}%", f"{rep['daily']['decisions']['IMPROVED']} IMPROVED"]
           for (step, image, fleet, seed) in sorted(runs, key=lambda k: runs[k]["report"]["startedAt"]) for rep in [runs[(step, image, fleet, seed)]["report"]]],
          [.4, .55, .45, .4, .75, .65, .6, .65, .7, .7, 1.05], size=9)

    heading("Appendix C Definitions and sources")
    table(["Term", "Meaning in this report"], [
        ("Image", "A scheduler container image built from one commit and run on the stack by its tag; before and after differ only in the fairness objective and what option b removed."),
        ("Reference phase and plan", "The 10 s cost search, and the plan it ends with; the fairness phase starts from that plan."),
        ("Fairness phase", "The 5 s search for a more even plan inside the cost ceiling; the only phase whose objective option b changes."),
        ("Ceiling", "The most a proposal may cost: the reference plan's cost plus the allowance. The kit's allowance is zero, so the ceiling is the reference plan's cost."),
        ("Moves per second", "Timefold's move evaluation speed at the end of a phase: candidate moves evaluated per second of that phase. More moves in the same time is a more thorough search, not a faster answer."),
        ("Study receipt", "One JSON log line per daily proposal on the daily replica, off by default (SCHEDULER_STUDY_RECEIPTS)."),
        ("Ratio", "After over before: the after run's median moves per second divided by the before run's on the same fleet and seed; a fleet's figure is the median over its three seeds."),
        ("Fairer", "A proposal whose DECIMAL128 variance is below its own reference plan's."),
        ("p50", "The kit's nearest-rank median: of four values, the second smallest.")], [1.6, 5.3])
    p("[1] docs/scheduler-policy.md: the two phases, the allowance, the acceptance rule and the numeric contract; SchedulingPolicy.compare for the acceptance reasons.")
    p("[2] The study receipt: StudyReceipts and the scheduler.study.receipts setting in the scheduler service; the kit's pairing in infra/host-conformance/src/load/serverlog.ts. Timefold's move evaluation speed: docs/timefold-documentation/Timefold-Solver-Docs.md, section 16.2.")
    p("[3] Evidence: docs/evidence/api-load-2026-10-20-binary-variance.json, written by infra/run-stack-verification.ps1 (both images, every replica checked, every run's kit report with its receipts). docs/api-load.md, 'Option b on the stack (V)', is the narrative kept with the code.")
    p("[4] The harness: docs/evidence/solver-matrix-2026-10-20.json and docs/road-matrix-numbering-2026-10-20, option b's paired fairness-phase ratios by fleet; docs/fairness-measures-2026-10-20 for the study that first promoted it.")
    p("Reproduce with docs/stack-verification-2026-10-20/requirements.txt: render the two diagrams as EVIDENCE.md says, then run report_data.py, build_report.py and verify_report.py there. The builder regenerates the charts and chart_data.json and writes this editable Word file.")

    settings = doc.settings.element
    for el in settings.findall(qn("w:updateFields")): settings.remove(el)
    update = OxmlElement("w:updateFields"); update.set(qn("w:val"), "true"); settings.append(update)
    stream = BytesIO(); doc.save(stream)
    with ZipFile(REFERENCE) as ref, ZipFile(stream) as built, ZipFile(OUTPUT, "w", ZIP_DEFLATED) as output:
        preserve = set(PRESERVE) | {n for n in ref.namelist() if "footer" in n}
        for name in sorted(built.namelist()):
            content = ref.read(name) if name in preserve else built.read(name)
            info = ZipInfo(name, (2026, 10, 20, 0, 0, 0)); info.compress_type = ZIP_DEFLATED
            output.writestr(info, content)
    print(OUTPUT)


if __name__ == "__main__": build()
