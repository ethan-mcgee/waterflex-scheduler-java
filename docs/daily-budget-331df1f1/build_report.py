"""Build the daily report from validated evidence and the selected explainer.

Install requirements.txt first. No network, services or benchmark execution.
"""
from copy import deepcopy
from datetime import datetime
from hashlib import sha256
from io import BytesIO
from pathlib import Path
from statistics import mean
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_CELL_VERTICAL_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor

from charts import SOLVERS, ALTS, LABEL, FLEETS, BUDGETS, WORKLOADS, select, pair, avg, make_charts
from report_data import load, RUN, ANALYSIS

HERE = Path(__file__).resolve().parent
REFERENCE = HERE.parent / "WaterFlex_Scheduler_Algorithm_Comparison_Explained.docx"
REFERENCE_SHA = "5366f3017d6f18c15de295ba5fb9c1a014b6222911040f1c5e044845e57f832d"
OUTPUT = HERE / "Daily_Budget_331df1f1_Report.docx"
PRESERVE = ("word/styles.xml", "word/stylesWithEffects.xml", "word/numbering.xml", "word/theme/theme1.xml", "word/fontTable.xml")


def dollars(value):
    return f"${value:,.2f}" if value >= 0 else f"-${-value:,.2f}"


def wtl(rows):
    return "/".join(str(sum(test(r["saving"]) for r in rows)) for test in (lambda x:x>0,lambda x:x==0,lambda x:x<0))


def paid(row, stage="after"):
    return sum(w["paidMinutes"] for w in row["raw"][stage]["fairness"]["workloads"])


def build():
    assert sha256(REFERENCE.read_bytes()).hexdigest() == REFERENCE_SHA
    config, raw_rows = load()
    rows = pair(raw_rows)
    assets = HERE / "generated"
    make_charts(rows, assets)
    doc = Document(REFERENCE)
    table_properties = deepcopy(doc.tables[0]._tbl.tblPr)
    for child in list(doc.element.body):
        if child.tag != qn("w:sectPr"): doc.element.body.remove(child)
    for rid, rel in list(doc.part.rels.items()):
        if rel.reltype.endswith(("/image", "/hyperlink")): del doc.part.rels[rid]
    doc.core_properties.title = "WaterFlex daily budget experiment explained"
    doc.core_properties.subject = "Modeled cost fairness and runtime in run 331df1f1"
    doc.core_properties.author = doc.core_properties.last_modified_by = "WaterFlex"
    doc.core_properties.created = doc.core_properties.modified = datetime(2026,10,1)

    def p(text, style=None):
        assert "\u2014" not in text
        para=doc.add_paragraph(text,style);para.paragraph_format.widow_control=True
        return para

    def heading(text):
        h=p(text,"Heading 1");h.paragraph_format.page_break_before=True

    def sub(text): p(text,"Heading 2")

    def table(headers, data, widths):
        assert abs(sum(widths)-6.9)<.01
        t=doc.add_table(rows=1,cols=len(headers));t.autofit=False;t.alignment=WD_TABLE_ALIGNMENT.CENTER
        t._tbl.replace(t._tbl.tblPr,deepcopy(table_properties))
        for col,w in zip(t.columns,widths): col.width=Inches(w)
        for i,values in enumerate([headers,*data]):
            assert len(values)==len(headers)
            row=t.rows[0] if i==0 else t.add_row()
            trpr=row._tr.get_or_add_trPr();trpr.append(OxmlElement("w:cantSplit"))
            if i==0:trpr.append(OxmlElement("w:tblHeader"))
            for j,(cell,value,width) in enumerate(zip(row.cells,values,widths)):
                cell.width=Inches(width);cell.vertical_alignment=WD_CELL_VERTICAL_ALIGNMENT.CENTER;cell.text=str(value)
                props=cell._tc.get_or_add_tcPr()
                shade=OxmlElement("w:shd");shade.set(qn("w:fill"),"DCE6EF" if i==0 else ("FFFFFF" if i%2 else "F4F6F8"));props.append(shade)
                margins=OxmlElement("w:tcMar")
                for side in ("top","left","bottom","right"):
                    n=OxmlElement("w:"+side);n.set(qn("w:w"),"65");n.set(qn("w:type"),"dxa");margins.append(n)
                props.append(margins)
                para=cell.paragraphs[0];para.paragraph_format.space_after=Pt(2);para.paragraph_format.space_before=Pt(2);para.paragraph_format.line_spacing=1.0
                if len(headers)>2 and j>0:para.alignment=1
                for run in para.runs:run.font.name="Calibri";run.font.size=Pt(10);run.bold=i==0
        spacer=p("");spacer.paragraph_format.space_after=Pt(0);spacer.paragraph_format.line_spacing=Pt(3)

    def figure(name, caption):
        para=p("");shape=para.add_run().add_picture(str(assets/f"{name}.png"),width=Inches(6.85))
        shape._inline.docPr.set("descr",caption);para.paragraph_format.keep_with_next=True
        cap=p(caption,"Caption");cap.paragraph_format.space_after=Pt(7)
        for run in cap.runs:
            run.font.color.rgb=RGBColor(0,0,0);run.bold=False;run.italic=True

    p("WaterFlex daily budget experiment explained","Title")
    p("A practical guide to modeled cost fairness and search time","Subtitle")
    p("October 1 2026  |  Run 331df1f1")
    p("The overnight experiment compares four ways to improve an already booked day. Across 320 matched cases per alternative, K opt, late acceptance and sublist each reduce accepted modeled cost on average relative to Tabu. Their advantage depends strongly on fleet size and workload. [1]")
    sub("What the comparison tells us")
    table(["Solver","Mean accepted cost","Mean saving vs Tabu","Cost wins /320"],[
        [LABEL[s],dollars(avg(select(rows,solver=s),"accepted_cost")/100),"Control" if s=="TABU" else dollars(avg(select(rows,solver=s),"saving")),"Control" if s=="TABU" else str(sum(r["saving"]>0 for r in select(rows,solver=s)))] for s in SOLVERS],[1.5,1.8,1.9,1.7])
    p("Sublist has the lowest pooled mean cost, but the differences between the alternatives are small. All three lose to Tabu in every one of the 40 matched 50 technician dispersed cases. Their mean additional cost there is about $47 to $52 per case, alongside better average workload balance. A blanket solver switch would hide this tradeoff.")
    p("Retain Tabu as the comparison control and investigate alternatives by fleet, workload and available search time. These are synthetic schedule costs under configured labor and mileage rates. They do not measure payroll savings or production capacity.")
    sub("How to read this report")
    table(["Sections","What you will learn"],[("1 and 2","What was tested, how the solvers work and what the measurements mean."),("3 to 5","Where cost improves, how budgets change the result and how much cases vary."),("6 and 7","A measured example and the cost of balancing workload."),("8 to 10","Runtime, schedule changes and decisions supported by the evidence."),("Appendices","Every fleet and budget group, definitions, sources and methods.")],[1.35,5.55])

    heading("1 What was tested")
    p("One case is one solver improving one fixed daily schedule for one seed and one time budget. Each method receives the same starting work in its matched comparison. This tests rearranging accepted appointments, rather than admitting new booking requests. [1, 3]")
    table(["Dimension","Values in this archive"],[("Solvers","Tabu, K opt, late acceptance and sublist"),("Fleet sizes","5, 10, 20 and 50 technicians"),("Workloads","Clustered and dispersed"),("Search seeds",", ".join(map(str,config["daily"]["seeds"]))),("Time budgets","60, 90, 120 and 240 seconds per case"),("Execution","Ten independent cases in parallel; two active processors per JVM")],[1.5,5.4])
    p("The design is 4 solvers x 4 fleets x 2 workloads x 10 seeds x 4 budgets = 1,280 cases. A fleet, workload and budget group contains ten cases per solver. Pooling all four budgets gives 40 cases per solver for one fleet and workload. Pooling all groups gives 320 cases per solver.")
    p("There are eight fixed synthetic fixtures, one for each fleet and workload combination. The seeds change the search path on those same schedules. Ten seeds do not mean ten independent territories, and 1,280 cases do not mean 1,280 different customer days.")
    sub("What clustered and dispersed mean here")
    p("Clustered fixtures use cheaper travel within four artificial groups and more expensive travel between groups. Dispersed fixtures use a deterministic directed travel matrix with varying leg costs. Neither uses measured road travel. Both start roughly half the technicians with six visits and the others with two; each visit requires 30 minutes of service. [3]")
    table(["Technicians","Appointments per case","Service minutes per case"],[[f,select(rows,fleet=f)[0]["raw"]["appointments"],select(rows,fleet=f)[0]["raw"]["appointments"]*30] for f in FLEETS],[2.0,2.4,2.5])
    p("All 1,280 expected results are present, pairable and report zero violations. Both search phases reached their time limits in every case. These checks establish the recorded outcomes for this matrix; they do not establish that the search found the best possible schedule.")

    heading("2 How the search and measurements work")
    p("Imagine grouping nearby visits on a route. Moving one visit may help, swapping two may help more, and moving an entire stretch can uncover an arrangement that single changes miss. A move defines the change to try. A search strategy decides which changes to explore and which temporary setbacks to tolerate. [3]")
    table(["Solver","Search configuration in this run"],[("Tabu","Relocation and swap; temporarily discourages revisiting recently changed entities. Tabu size 7; accepted count limit 1,000."),("Late acceptance","Relocation and swap; compares moves with older scores using a history of 400. Accepted count limit 1."),("Sublist","Late acceptance plus moving or reversing stretches of two to four visits."),("K opt","Sublist configuration plus reconnecting two or three route links.")],[1.5,5.4])
    p("All four use a selected count limit of 10,000. Tabu versus the alternatives changes both acceptance strategy and search settings. The result therefore compares complete configurations; it cannot attribute a saving to one move type alone.")
    sub("The two phases and the accepted result")
    p("The harness initially allocates two thirds of the budget to cost search: 40 seconds out of a 60 second case, for example. It chooses the better feasible reference between that search result and the starting schedule. Remaining time goes to a fairness search with a cost ceiling equal to the reference cost plus 2%, rounded down to cents. The final policy checks determine the accepted schedule. [3]")
    table(["Measure","How to interpret it"],[("Accepted cost","The final recorded schedule cost. Use this for solver comparisons."),("Reference cost","The cost search milestone before the fairness phase. It can be lower than accepted cost."),("Saving vs Tabu","Tabu accepted cost minus alternative accepted cost, in dollars per matched case. Positive favors the alternative."),("Fairness variance","Capacity weighted variation in paid time divided by available capacity. Lower means a more even distribution."),("Move evaluation rate","Evaluated candidate changes per second of solver time in the stated phase."),("Elapsed time","Whole benchmark case time, including both phases and local validation.")],[1.6,5.3])
    p("Modeled cost uses $30 per regular labor hour, $45 per overtime hour and $0.67 per mile. New overtime is prohibited. Travel includes a 20% buffer and five minutes per included leg. These assumptions can change modeled cost without an equivalent change in real driving or payroll. [3]")

    heading("3 Where modeled cost improves")
    p("Each cell below averages 40 paired differences: ten seeds at four budgets, holding fixture and starting work fixed. The sign matters before the size. A positive value means the alternative accepts a cheaper schedule than Tabu. A negative value means Tabu is cheaper. [1]")
    figure("cost_groups","Figure 1. Mean accepted modeled cost saving in dollars per case. Each cell has 40 pairs. Blue is positive and tan is negative; printed signs give the same information. Ten search seeds are included.")
    p("Read a row to compare solvers under one workload. Read a column to see how one solver behaves across fleets. The strongest gains are in dispersed fleets of 10 and 20. The 50 technician dispersed row reverses the pattern for every alternative. Clustered groups have positive mean savings throughout, although some individual cases still lose.")
    sub("Why the pooled average is much smaller")
    k=select(rows,solver="KOPT");good=[r for r in k if not (r["fleet"]=="50" and r["workload"]=="DISPERSED")];bad=select(k,fleet=50,workload="DISPERSED")
    p(f"For K opt, the other seven fleet and workload groups average {dollars(avg(good,'saving'))} saved across 280 pairs. The 50 technician dispersed group averages {dollars(-avg(bad,'saving'))} added cost across 40 pairs. Weighting those groups by their case counts yields only {dollars(avg(k,'saving'))} saved over all 320 pairs. This explains how a method can win often while its overall average remains modest.")
    p("The pooled mean gives each test case equal weight. It is not weighted by how often these fleet sizes occur among WaterFlex customers. A fleet of 50 has more appointments and larger total cost than a fleet of five, so its dollar differences can have greater influence. The appendices retain all workload and budget combinations.")

    heading("4 What extra search time changes")
    p("The charts compare each alternative with Tabu at the same budget. Each point averages 40 pairs: four fleet sizes and ten seeds within one workload. Budget is a numeric axis, so the gap from 120 to 240 seconds is four times the gap from 60 to 90. [1]")
    figure("budgets","Figure 2. Paired mean accepted cost savings by workload and budget. The horizontal zero line is Tabu. Lines join separately executed budget cases; they are not a trace of one search continuing.")
    table(["Budget seconds","K opt saving $","Late acceptance $","Sublist saving $"],[[b,*[f"{avg(select(rows,solver=s,budget_ms=b*1000),'saving'):+.2f}" for s in ALTS]] for b in BUDGETS],[1.35,1.8,1.95,1.8])
    p("Each table row pools 80 pairs per alternative. Average relative savings rise with the budget, especially in dispersed work. This does not prove that each schedule gets cheaper with more time. Both phases get more time, and a separate run can finish at a different accepted tradeoff.")
    sub("Compare a solver with its own shorter run")
    own=[]
    for s in SOLVERS:
        short={r["key"][:3]:r for r in select(rows,solver=s,budget_ms=60000)}
        differences=[(int(short[r["key"][:3]]["accepted_cost"])-int(r["accepted_cost"]))/100 for r in select(rows,solver=s,budget_ms=240000)]
        own.append([LABEL[s],dollars(mean(differences)),f"{sum(v>0 for v in differences)}/{sum(v==0 for v in differences)}/{sum(v<0 for v in differences)}"])
    table(["Solver","Mean saving at 240 vs 60 seconds","Cheaper / same / dearer"],own,[1.5,2.95,2.45])
    p("These 80 comparisons per solver hold fixture and seed fixed while changing budget. A larger advantage over Tabu and a reduction against the solver's own shorter run answer different questions. The experiment does not identify a universal best budget.")

    heading("5 What individual cases and seeds show")
    p("A win counts a lower accepted cost in exact integer cents. It does not describe the size of the saving. The table includes all 320 matched cases per alternative, including losses and ties. [1]")
    table(["Alternative","Wins / ties / losses","Worst saving $","Best saving $"],[[LABEL[s],wtl(select(rows,solver=s)),f"{min(r['saving'] for r in select(rows,solver=s)):+.2f}",f"{max(r['saving'] for r in select(rows,solver=s)):+.2f}"] for s in ALTS],[1.55,2.15,1.6,1.6])
    figure("variation","Figure 3. Left: each seed averages 32 matched cases per alternative. Right: all 320 paired savings per alternative. These displays describe observed variation, not confidence intervals.")
    p("On the left, seed labels identify repeatable search choices. Equal spacing is categorical; seed 199 is not a later date than seed 17. The means reveal whether the headline depends on just one search seed. They still mix fleets and workloads, so a favorable average can coexist with consistent 50 technician dispersed losses.")
    p("On the right, choose a dollar value and move up to a curve. Its height is the percentage of cases with savings at or below that value. At zero, the curve includes losses and ties. Farther right means a larger saving; negative values are added costs. The left tail exposes losses close to $100 that a win count alone would hide.")
    p("These spreads describe the fixed matrix. They do not estimate uncertainty across customer territories. Ten searches per scenario improve visibility into local variation, but geographic and operational diversity still require new fixtures.")

    heading("6 Follow one measured schedule")
    p("Consider the five technician clustered fixture at seed 17 and a 60 second budget. All four methods start from the same 18 appointments and the same modeled cost. This is an observed archive case. The table separates the cost search reference from the final accepted result. [1]")
    example=select(rows,fleet=5,workload="CLUSTERED",seed=17,budget_ms=60000)
    table(["Solver","Start $","Reference $","Accepted $","Variance"],[[LABEL[s],*[f"{select(example,solver=s)[0]['raw'][stage]['costCents']/100:.2f}" for stage in ("before","reference","after")],f"{float(select(example,solver=s)[0]['fairness']):.5f}"] for s in SOLVERS],[1.6,1.15,1.35,1.35,1.45])
    t=select(example,solver="TABU")[0];k=select(example,solver="KOPT")[0]
    p(f"Tabu finds a {dollars(int(t['reference_cost'])/100)} reference, then accepts {dollars(int(t['accepted_cost'])/100)}. The difference, {dollars((int(t['accepted_cost'])-int(t['reference_cost']))/100)}, accompanies lower variance than its reference. Its ceiling is {dollars(t['raw']['costCeilingCents']/100)}, so the accepted result remains inside the recorded allowance.")
    p(f"K opt accepts {dollars(int(k['accepted_cost'])/100)}, saving {dollars(k['saving'])} versus Tabu, or {k['saving']/(int(t['accepted_cost'])/100)*100:.2f}% of Tabu's accepted cost. Its higher variance means that cheaper outcome is less evenly balanced. Subtracting K opt's reference from Tabu's accepted cost would compare different phases and overstate the accepted saving.")
    figure("worked","Figure 4. Accepted paid time as a percentage of technician capacity in the measured example. Each point is one technician. K opt and sublist overlap exactly. Lines connect identities and are not time trends.")
    p("Read the vertical spread within each solver's line. More similar utilization values mean more even work relative to capacity. Fairness uses all eligible technicians; selecting only the busiest route would miss effects on others. This example illustrates one tradeoff, rather than forecasting every five technician day.")

    heading("7 How fairness changes the cost decision")
    p("Fairness uses each technician's paid minutes divided by regular capacity, then measures how far those utilization ratios vary around their capacity weighted mean. Lower variance is more even. It is a squared ratio, not a percentage of satisfied technicians or a guarantee of equal hours. [3]")
    p("For teaching arithmetic, two people with equal 480 minute capacities and 240 and 480 paid minutes have utilizations of 0.50 and 1.00. Their mean is 0.75 and variance is 0.0625. At 360 paid minutes each, both utilizations are 0.75 and variance is zero. These illustrative values are not additional experiment observations.")
    figure("fairness","Figure 5. Each point averages 40 pairs for one solver, fleet and workload. Right means lower cost than Tabu; up means lower variance. K opt labels identify fleets, with C for clustered and D for dispersed. Other markers represent the same eight groups.")
    p("The upper right combines lower cost and better balance. The lower right saves cost but has worse balance. The upper left improves balance at added cost. The 50 technician dispersed points occupy that upper left region, so their cost disadvantage does not mean every aspect of their schedules is worse.")
    table(["50 technician dispersed","Accepted cost $","Fairness variance","Cost losses /40"],[[LABEL[s],f"{avg(select(rows,solver=s,fleet=50,workload='DISPERSED'),'accepted_cost')/100:,.2f}",f"{avg(select(rows,solver=s,fleet=50,workload='DISPERSED'),'fairness'):.5f}","Control" if s=="TABU" else str(sum(r["saving"]<0 for r in select(rows,solver=s,fleet=50,workload="DISPERSED")))] for s in SOLVERS],[2.1,1.65,1.65,1.5])
    p("Each method has its own reference and cost ceiling. Staying within that ceiling does not guarantee being cheaper than another solver. The table shows an observed tradeoff, but cannot prove that reference search or fairness selection alone caused the cost gap. Explaining that mechanism requires examining the search trajectories.")

    heading("8 How long the work took")
    p("All four methods consumed essentially their allotted case time. Both phases report TIME_LIMIT for all 1,280 cases. The allocated search time ended while a usable schedule was retained; this does not mean the case failed or that no feasible schedule existed. [1]")
    table(["Budget seconds","Cases","Min elapsed s","Mean elapsed s","Max elapsed s"],[[b,len(select(rows,budget_ms=b*1000)),f"{min(int(r['elapsed_ms']) for r in select(rows,budget_ms=b*1000))/1000:.3f}",f"{avg(select(rows,budget_ms=b*1000),'elapsed_ms')/1000:.3f}",f"{max(int(r['elapsed_ms']) for r in select(rows,budget_ms=b*1000))/1000:.3f}"] for b in BUDGETS],[1.3,.8,1.6,1.6,1.6])
    figure("throughput","Figure 6. Arithmetic mean of per-case move evaluation rates. Each point pools 80 cases per solver and fleet: two workloads, ten seeds and four budgets. Both panels use the same scale starting at zero.")
    p("The horizontal axis is fleet size and the vertical axis is thousands of candidate move evaluations per second. Rates decline with larger fleets. Fairness evaluations also run more slowly than reference evaluations. This measures search work, not how many appointments can be served or how good the final schedule is.")
    p("For each case and phase, the rate is move evaluations divided by solve milliseconds, multiplied by 1,000. The graph averages those rates equally. Faster evaluation permits more candidates, but different moves and acceptance rules make counts an incomplete measure of search quality.")
    p("Ten independent JVM cases ran in parallel with dedicated core assignments and two active processors per JVM. They still shared workstation resources. These timings cannot establish production request latency, concurrent customer capacity or isolated single-case performance. Longer budgets also change both search phases. [1, 2]")

    heading("9 What changed in accepted schedules")
    p("Cost and variance summarize different parts of the result. Paid time, waiting, changed assignments and retimed appointments add operational context. This table averages all 320 cases per solver equally across the matrix. Larger fleets contribute more appointments per case. [1]")
    table(["Solver","Paid min","Waiting min","Assignments changed","Appointments retimed"],[[LABEL[s],f"{mean(paid(r) for r in select(rows,solver=s)):.2f}",f"{mean(r['raw']['paidWaitingAfter'] for r in select(rows,solver=s)):.2f}",f"{mean(r['raw']['changedAssignments'] for r in select(rows,solver=s)):.2f}",f"{mean(r['raw']['retimedAppointments'] for r in select(rows,solver=s)):.2f}"] for s in SOLVERS],[1.5,1.1,1.2,1.6,1.5])
    p("Paid minutes sum the accepted workloads across the fleet. Waiting is the recorded paid waiting component. Assignment changes count appointments moved to another technician; retimed appointments count changed internal arrival plans. A retimed appointment can remain inside its customer promise. The two change counts overlap and should not be added.")
    sub("A reference cost is not the dispatch outcome")
    table(["Solver","Mean reference $","Mean accepted $","Accepted minus reference $"],[[LABEL[s],f"{avg(select(rows,solver=s),'reference_cost')/100:,.2f}",f"{avg(select(rows,solver=s),'accepted_cost')/100:,.2f}",f"{mean((int(r['accepted_cost'])-int(r['reference_cost']))/100 for r in select(rows,solver=s)):.2f}"] for s in SOLVERS],[1.5,1.65,1.65,2.1])
    p(f"Across all 1,280 cases, accepted cost averages {dollars(mean((int(r['accepted_cost'])-int(r['reference_cost']))/100 for r in rows))} above its own reference. Reporting the reference as final cost would omit the recorded fairness tradeoff. Acceptance reasons are 794 fairness improvements and 486 cost reductions. Those labels describe acceptance against the starting policy comparison, not a win against Tabu.")
    sub("What zero recorded violations establishes")
    p("The retained harness independently checked route feasibility, hard score, cost and arrivals before recording each result. All cases report zero violations. All recorded before, reference and accepted metrics have zero overtime. This report also reconciles workloads and variance. These are checks of saved schedules, not an exhaustive proof of scheduler correctness. [1, 3]")
    p("The archive has no full event-by-event search trace or own 15 second improvement metric for these cases. It cannot show exactly when a cheaper candidate was lost or quantify what stopping at 15 seconds would have produced. Those values remain unavailable.")

    heading("10 What should happen next")
    p("Use this run to choose focused follow-up comparisons. Sublist has the best pooled accepted cost, K opt has the largest mean relative advantage at 240 seconds, and all alternatives have favorable mean cost in seven of eight fleet and workload groups. Consistent losses in the eighth group and differing fairness results make one overall ranking insufficient.")
    table(["Question","Next evidence to collect","Decision informed"],[("Why 50 dispersed loses","Retain reference and fairness trajectories, then replicate with realistic territories.","Whether cost search, acceptance or their interaction explains the gap."),("Which budget is useful","Compare each solver with itself across budgets on realistic schedules.","Whether extra time buys enough improvement for the overnight window."),("How broadly gains hold","Add varied territories, skills, absences, tight promises and near-capacity work.","Whether a fleet-specific choice generalizes."),("What concurrency permits","Measure isolated and shared-load runs with CPU, memory and admission observations.","An operational capacity and timing limit.")],[1.45,2.9,2.55])
    p("A future recommendation should keep accepted modeled cost as the primary comparison, state acceptable balance and runtime tradeoffs, and retain failures and incomplete search evidence. This report supports that decision process without estimating production savings.")
    sub("How the calculations were checked")
    p("Checks cover archived configuration and case hashes, all 1,280 raw SHA-256 hashes, unique case coverage and the full design cross product. Fixture identity, starting metrics, costs, elapsed time and phase rates reconcile with the saved analysis. Fairness is independently recomputed from paid minutes and capacity. [1, 2]")
    p("Comparisons hold fixture, fleet, workload, seed and budget fixed unless explicitly comparing budgets. Wins use exact cents. Means weight cases equally; rounding is only for display. No missing value becomes zero. All six figures are recalculated for ten seeds; the stale archive caption saying five seeds is not reused.")

    for letter,f in zip("ABCD",FLEETS):
        heading(f"Appendix {letter} The {f} technician cases")
        p("Each row contains ten matched seeds per solver at the stated budget. C means clustered and D means dispersed. Savings are Tabu accepted cost minus alternative accepted cost in dollars per case. W/T/L means wins, ties and losses in exact cents; each entry totals ten. [1]")
        data=[]
        for w in WORKLOADS:
            for b in BUDGETS:
                groups=[select(rows,solver=s,fleet=f,workload=w,budget_ms=b*1000) for s in SOLVERS]
                data.append([w[0],b,f"{avg(groups[0],'accepted_cost')/100:,.2f}",*[f"{avg(g,'saving'):+.2f}\n{wtl(g)}" for g in groups[1:]]])
        table(["Work","Sec","Tabu cost $","K opt $\nW/T/L","Late acc. $\nW/T/L","Sublist $\nW/T/L"],data,[.55,.55,1.25,1.5,1.55,1.5])
        sub("Accepted workload balance")
        data=[]
        for w in WORKLOADS:
            for b in BUDGETS:
                data.append([w[0],b,*[f"{avg(select(rows,solver=s,fleet=f,workload=w,budget_ms=b*1000),'fairness'):.5f}" for s in SOLVERS]])
        table(["Work","Sec","Tabu variance","K opt variance","Late acc. variance","Sublist variance"],data,[.55,.55,1.45,1.45,1.45,1.45])

    heading("Appendix E Definitions and evidence")
    table(["Term","Meaning in this report"],[("Case","One solver run at one fleet, workload, seed and budget."),("Matched pair","Same fixture and starting schedule under the same seed and budget, comparing an alternative with Tabu."),("Mean saving","Average paired difference in accepted modeled dollars. Positive favors the alternative."),("Reference and ceiling","Cost search milestone and its 2% fairness allowance, rounded down to cents."),("Capacity weighted variance","Sum of capacity times squared utilization deviation, divided by total capacity."),("TIME_LIMIT","The phase ended at its allocated time. It says neither proven optimal nor infeasible."),("Observed range","Smallest through largest saved value, not a population confidence interval.")],[1.85,5.05])
    p(f"[1] Immutable daily archive: experiments/runs/{RUN.name}. Measured revision: bfa0cfec45ce98c770796d8aecdc35c9692b8357. Raw JSONL, manifest, configuration and cases remain the primary evidence.")
    p(f"[2] Saved analysis: {ANALYSIS.name} under the archive's analysis folder. cases.csv and analysis-provenance.json supply the reconciled table and input hashes. The parallel configuration specifies ten cases; JVM settings include two active processors and Serial GC.")
    p("[3] Measured source: SolverBenchmark.java and SolverBenchmarkData.java under scheduler-service/src/test/java/dev/waterflex/scheduler/optimizer; SolverExperiment.java, SchedulingPolicy.java and RouteEvaluator.java under scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer. These define fixtures, settings, phases and metrics.")
    p("Reproduce with docs/daily-budget-331df1f1/requirements.txt, then run report_data.py, build_report.py and verify_report.py there. The builder reads the archive, regenerates all six charts and chart_data.json, and creates this editable Word file. EVIDENCE.md records methods. Render and review every page after changes.")
    p("Formatting and explanation structure follow WaterFlex_Scheduler_Algorithm_Comparison_Explained.docx. Its historical values are not pooled with this run. Ten seeds on eight deterministic fixtures and shared local execution limit generalization to customer work.")

    settings=doc.settings.element
    for el in settings.findall(qn("w:updateFields")):settings.remove(el)
    update=OxmlElement("w:updateFields");update.set(qn("w:val"),"true");settings.append(update)
    stream=BytesIO();doc.save(stream)
    with ZipFile(REFERENCE) as ref, ZipFile(stream) as built, ZipFile(OUTPUT,"w",ZIP_DEFLATED) as output:
        preserve=set(PRESERVE)|{n for n in ref.namelist() if "footer" in n}
        for name in sorted(built.namelist()):
            content=ref.read(name) if name in preserve else built.read(name)
            info=ZipInfo(name,(2026,10,1,0,0,0));info.compress_type=ZIP_DEFLATED
            output.writestr(info,content)
    print(OUTPUT)


if __name__=="__main__": build()
