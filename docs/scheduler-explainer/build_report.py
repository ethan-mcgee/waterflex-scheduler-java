"""Expand a retained Word reference using its existing styles and table component.

Run with Python 3.10+ and python-docx 1.2.0. No network access is required.
The retained reference and original artifact are never modified.
"""
from copy import deepcopy
from hashlib import sha256
from pathlib import Path
import re
from datetime import datetime
from io import BytesIO
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from docx import Document
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches

ROOT = Path(__file__).resolve().parent
REVISION = "bdbba246979ad98f2666960bf36613f0d41ea07d"
REFERENCE_SHA256 = "2d296b4ae319f04ab583a25a23df6e11e845e6d1ba3324f7dcad23d96d53df70"
OUTPUT = ROOT / "WaterFlex_Scheduler_and_Route_Optimization_Explained_Expanded.docx"


def build():
    reference = ROOT / "reference.docx"
    if sha256(reference.read_bytes()).hexdigest() != REFERENCE_SHA256:
        raise ValueError("Retained reference changed; verify it before building")
    doc = Document(reference)
    table_component = deepcopy(doc.tables[0]._tbl)
    original_headings = [p.text for p in doc.paragraphs if p.style.name == "Heading 1"]
    for paragraph in doc.paragraphs:
        if paragraph.text == "September 24 2026":
            paragraph.text = "September 29 2026"
        elif paragraph.text.startswith("Configuration scope:"):
            paragraph.text = (
                f"Configuration scope: this report describes committed repository revision {REVISION}. "
                "Managed reservations and bounded booking rearrangement are implemented but disabled by default. "
                "Contraction Hierarchies and route prewarming are also disabled by default. "
                "These repository defaults do not establish production deployment settings. [1, 2]"
            )
        elif paragraph.text.startswith("Project links below"):
            paragraph.text = (
                f"Project links below are pinned to revision {REVISION}. Bracketed numbers refer to these sources. "
                "Benchmark reports retain their own measured revisions and archived evidence; they are not fresh runs."
            )
        elif paragraph.text.startswith("External references accessed"):
            paragraph.text = (
                "External references checked September 29 2026. Timefold latest currently documents 2.7.0, "
                "while the repository uses 2.6.0. Use it for general concepts; project configuration and tests "
                "establish implementation behavior. GraphHopper sources below are pinned to 11.0."
            )
    for rel in doc.part.rels.values():
        if rel.reltype.endswith("/hyperlink") and rel.is_external:
            rel._target = rel.target_ref.replace("b8124d79c6774320d2debea33ebe8ab06db55a0d", REVISION)
            rel._target = rel.target_ref.replace("graphhopper/blob/master/docs/core/routing.md", "graphhopper/blob/11.0/docs/core/routing.md")
    for node in doc.element.iter(qn("w:t")):
        if node.text == "12  Timefold 2.6 local search and acceptor concepts":
            node.text = "12  Timefold local search and acceptor concepts"

    navigation = doc.tables[0]
    nav_rows = [
        ("2 to 4", "The scheduling problem, priorities and calculated utilization"),
        ("5 to 9", "Booking trace, Tabu steps and alternative algorithms"),
        ("10 to 14", "Route timing, road routing, validation and Apply"),
        ("15 to 17", "The running example and interpretation of measured evidence"),
        ("18 to 20", "Technical details, glossary and linked sources"),
    ]
    for row, values in zip(navigation.rows[1:], nav_rows):
        for cell, value in zip(row.cells, values):
            cell.paragraphs[0].runs[0].text = value
    # Static navigation ranges are verified against the final render.
    # Bookmarks and hyperlinks make the navigation useful beyond pagination.
    for number, paragraph in enumerate([p for p in doc.paragraphs if p.style.name == "Heading 1"], 1):
        start = OxmlElement("w:bookmarkStart")
        start.set(qn("w:id"), str(number))
        start.set(qn("w:name"), f"section_{number}")
        end = OxmlElement("w:bookmarkEnd")
        end.set(qn("w:id"), str(number))
        paragraph._p.insert(1, start)
        paragraph._p.append(end)
    for row, anchor in zip(navigation.rows[1:], (1, 3, 6, 9, 11)):
        p = row.cells[1].paragraphs[0]
        hyperlink = OxmlElement("w:hyperlink")
        hyperlink.set(qn("w:anchor"), f"section_{anchor}")
        for run in list(p._p.findall(qn("w:r"))):
            hyperlink.append(run)
        p._p.append(hyperlink)

    def insert_table(anchor, lines):
        rows = [[cell.strip() for cell in line.strip().strip("|").split("|")] for line in lines]
        headers, data = rows[0], rows[2:]
        t = doc.add_table(rows=1, cols=len(headers))
        t.autofit = False
        widths = {
            2: [2.15, 4.75],
            3: [2.0, 2.5, 2.4],
            4: [1.35, 3.2, 1.2, 1.15],
            5: [1.8, 1.0, 1.7, 1.0, 1.4],
        }[len(headers)]
        # Schedule comparison has short metrics and a longer workload column.
        if headers[0] == "Arrangement":
            widths = [2.0, 1.0, 1.15, 2.75]
        source_pr = table_component.find(qn("w:tblPr"))
        t._tbl.replace(t._tbl.tblPr, deepcopy(source_pr))
        for column, width in zip(t.columns, widths):
            column.width = Inches(width)
        for values in data:
            t.add_row()
        for index, (row, values) in enumerate(zip(t.rows, [headers] + data)):
            source_row = table_component.findall(qn("w:tr"))[0 if index == 0 else (2 if index % 2 == 0 else 1)]
            source_cell = source_row.findall(qn("w:tc"))[0]
            pr = row._tr.get_or_add_trPr()
            pr.append(OxmlElement("w:cantSplit"))
            if index == 0:
                pr.append(OxmlElement("w:tblHeader"))
            for cell, value, width in zip(row.cells, values, widths):
                cell._tc.replace(cell._tc.get_or_add_tcPr(), deepcopy(source_cell.find(qn("w:tcPr"))))
                cell.width = Inches(width)
                paragraph = cell.paragraphs[0]
                sample = source_cell.find(qn("w:p"))
                sample_pr = sample.find(qn("w:pPr"))
                if sample_pr is not None:
                    paragraph._p.insert(0, deepcopy(sample_pr))
                run = paragraph.add_run(value)
                run._r.insert(0, deepcopy(sample.find(qn("w:r")).find(qn("w:rPr"))))
        anchor.addprevious(t._tbl)
        spacer = doc.add_paragraph()
        spacer.paragraph_format.space_after = 0
        anchor.addprevious(spacer._p)

    text = (ROOT / "expansions.md").read_text(encoding="utf-8")
    chunks = re.split(r"<!-- before: (.*?) -->\s*", text)
    for title, body in zip(chunks[1::2], chunks[2::2]):
        anchor = next(p._p for p in doc.paragraphs if p.text == title)
        blocks = body.strip().split("\n\n")
        for block in blocks:
            if block.startswith("|"):
                insert_table(anchor, block.splitlines())
            else:
                level = 2 if block.startswith("# ") else 2 if block.startswith("## ") else None
                paragraph = doc.add_heading(block.lstrip("# "), level) if level else doc.add_paragraph(block.replace("\n", " "))
                anchor.addprevious(paragraph._p)

    glossary = doc.tables[-1]
    # Keep the original glossary page readable by expanding existing slots.
    replacements = {
        "Neighborhood": ("Neighborhood and beam", "A neighborhood is the set of reachable moves. Beam width limits arrangements expanded next; depth limits consecutive rearrangement rounds."),
        "Local optimum": ("Local optimum and dominance", "A local optimum cannot improve through nearby moves. Dominance removes a comparable partial timing only if all implemented comparisons are no worse."),
        "Snapshot and provenance": ("Snapshot provenance and stale preview", "Fixed search facts and their identities and versions. A stale preview no longer matches relevant current facts."),
    }
    for row in glossary.rows[1:]:
        if row.cells[0].text in replacements:
            label, meaning = replacements[row.cells[0].text]
            row.cells[0].paragraphs[0].runs[0].text = label
            row.cells[1].paragraphs[0].runs[0].text = meaning

    # Source and test links remain in Appendix C, rather than cluttering the narrative.
    last_source = next(p for p in doc.paragraphs if p.text.startswith("11  Scheduler acceptance"))
    sources = [
        ("8a  Exhaustive route timing oracle", "scheduler-service/src/test/java/dev/waterflex/scheduler/optimizer/RouteTimingOracleTest.java"),
        ("4a  Policy boundary and missing capacity regressions", "scheduler-service/src/test/java/dev/waterflex/scheduler/optimizer/SchedulingPolicyTest.java"),
        ("5a  Bounded search relocation swap and timeout regressions", "scheduler-service/src/test/java/dev/waterflex/scheduler/BoundedBookingSearchTest.java"),
    ]
    for label, path in sources:
        paragraph = doc.add_paragraph()
        hyperlink = OxmlElement("w:hyperlink")
        hyperlink.set(qn("r:id"), paragraph.part.relate_to(
            f"https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/{REVISION}/{path}",
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink", is_external=True))
        sample = last_source._p.find(qn("w:hyperlink")).find(qn("w:r"))
        run = deepcopy(sample)
        run.find(qn("w:t")).text = label
        hyperlink.append(run)
        paragraph._p.append(hyperlink)
        last_source._p.addnext(paragraph._p)
        last_source = paragraph

    road_anchor = next(p for p in doc.paragraphs if p.text == "Travel buffers")
    road_detail = doc.add_paragraph(
        "Suppose the road engine reports A to B as ten minutes but B to A as sixteen. "
        "One-way streets and access rules can explain the difference. Reversing stops changes the required "
        "directed legs, so a saved forward leg cannot stand in for its reverse. Cache keys preserve that direction "
        "and the routing identity. If the map or routing mode changes, identity separation prevents an old estimate "
        "from silently masquerading as the new graph's result. Sparse requests reduce road work; they still need "
        "every leg used by the candidate and its return. Faster queries do not remove reservation conflicts or "
        "the need for a complete timing evaluation. [1, 10]"
    )
    road_anchor._p.addprevious(road_detail._p)

    if any("\u2014" in t.text for t in doc.element.iter(qn("w:t")) if t.text):
        raise ValueError("Em dash found")
    assert all(title in [p.text for p in doc.paragraphs] for title in original_headings)
    doc.core_properties.modified = doc.core_properties.created = datetime(2026, 9, 29, 0, 0, 0)
    package = BytesIO()
    doc.save(package)
    # Stable archive timestamps make an unchanged source rebuild byte reproducible.
    with ZipFile(package) as source, ZipFile(OUTPUT, "w", ZIP_DEFLATED) as target:
        for name in sorted(source.namelist()):
            member = ZipInfo(name, date_time=(2026, 9, 29, 0, 0, 0))
            member.compress_type = ZIP_DEFLATED
            target.writestr(member, source.read(name))
    print(f"Built {OUTPUT}")
    print(f"Paragraphs {len(doc.paragraphs)}, tables {len(doc.tables)}")


if __name__ == "__main__":
    build()
