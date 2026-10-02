"""Build the Word companion from the canonical Markdown audit. Requires python-docx."""
from pathlib import Path
import re
from docx import Document
from docx.shared import Inches, Pt, RGBColor
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_CELL_VERTICAL_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.opc.constants import RELATIONSHIP_TYPE as RT

ROOT = Path(__file__).resolve().parent


def inline(paragraph, text, size=None):
    cursor = 0
    for match in re.finditer(r'\[([^\]]+)\]\(([^)]+)\)', text):
        paragraph.add_run(text[cursor:match.start()])
        link = OxmlElement('w:hyperlink')
        link.set(qn('r:id'), paragraph.part.relate_to(match[2], RT.HYPERLINK, is_external=True))
        run = OxmlElement('w:r')
        props = OxmlElement('w:rPr')
        color = OxmlElement('w:color'); color.set(qn('w:val'), '205781'); props.append(color)
        if size is not None:
            font_size = OxmlElement('w:sz'); font_size.set(qn('w:val'), str(size * 2)); props.append(font_size)
        run.append(props)
        value = OxmlElement('w:t'); value.text = match[1]; run.append(value)
        link.append(run); paragraph._p.append(link)
        cursor = match.end()
    paragraph.add_run(text[cursor:])


def table(doc, lines):
    rows = [[cell.strip() for cell in line.strip().strip('|').split('|')] for line in lines]
    rows = [row for row in rows if not all(re.fullmatch(r':?-+:?', cell) for cell in row)]
    grid = doc.add_table(rows=0, cols=len(rows[0]))
    grid.alignment = WD_TABLE_ALIGNMENT.CENTER
    grid.autofit = False
    width_sets = {2: [3.0, 4.0], 3: [1.65, 2.05, 3.3], 4: [0.55, 0.8, 3.65, 2.0]}
    widths = width_sets[len(rows[0])]
    for column, width in zip(grid.columns, widths): column.width = Inches(width)
    borders = OxmlElement('w:tblBorders')
    for edge in ['top', 'left', 'bottom', 'right', 'insideH', 'insideV']:
        node = OxmlElement('w:' + edge)
        for name, value in [('val', 'single'), ('sz', '4'), ('color', 'D9D9D9')]: node.set(qn('w:' + name), value)
        borders.append(node)
    grid._tbl.tblPr.append(borders)
    for index, row in enumerate(rows):
        cells = grid.add_row().cells
        for cell, value, width in zip(cells, row, widths):
            cell.width = Inches(width)
            cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
            props = cell._tc.get_or_add_tcPr()
            margins = OxmlElement('w:tcMar')
            for edge in ['top', 'left', 'bottom', 'right']:
                item = OxmlElement('w:' + edge); item.set(qn('w:w'), '85'); item.set(qn('w:type'), 'dxa'); margins.append(item)
            props.append(margins)
            if index == 0:
                fill = OxmlElement('w:shd'); fill.set(qn('w:fill'), 'DDEBF7'); props.append(fill)
            p = cell.paragraphs[0]; p.paragraph_format.space_after = Pt(2); p.paragraph_format.space_before = Pt(2)
            inline(p, value, size=9)
            for run in p.runs: run.font.size = Pt(9); run.bold = index == 0
        trpr = grid.rows[index]._tr.get_or_add_trPr()
        avoid_split = OxmlElement('w:cantSplit'); trpr.append(avoid_split)
        if index == 0:
            repeat = OxmlElement('w:tblHeader'); trpr.append(repeat)
    doc.add_paragraph().paragraph_format.space_after = Pt(1)


def main():
    source = (ROOT / 'Timefold_Audit.md').read_text(encoding='utf-8')
    assert '\u2014' not in source
    doc = Document()
    for border in doc.styles.element.xpath('.//w:pBdr'):
        border.getparent().remove(border)
    section = doc.sections[0]
    section.page_width = Inches(8.5); section.page_height = Inches(11)
    section.top_margin = Inches(.65); section.bottom_margin = Inches(.65)
    section.left_margin = Inches(.75); section.right_margin = Inches(.75)
    normal = doc.styles['Normal']
    normal.font.name = 'Calibri'; normal.font.size = Pt(10.5)
    normal.paragraph_format.space_after = Pt(5)
    normal.paragraph_format.line_spacing = 1.04
    for name, size in [('Title', 25), ('Heading 1', 17), ('Heading 2', 13)]:
        style = doc.styles[name]; style.font.name = 'Calibri'; style.font.size = Pt(size)
        style.font.color.rgb = RGBColor(0, 0, 0)
        style.paragraph_format.space_before = Pt(12)
        style.paragraph_format.space_after = Pt(7)
        style.paragraph_format.keep_with_next = True
    footer = section.footer.paragraphs[0]
    footer.alignment = 2
    footer.add_run('WaterFlex audit  |  ')
    field = OxmlElement('w:fldSimple'); field.set(qn('w:instr'), 'PAGE'); footer._p.append(field)
    for run in footer.runs: run.font.size = Pt(8)
    doc.core_properties.title = 'WaterFlex Timefold Audit and Stateless Service Readiness'
    doc.core_properties.subject = 'Timefold documentation comparison and stateless solver readiness'
    doc.core_properties.author = 'WaterFlex'
    lines = source.splitlines(); i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith('|'):
            group = []
            while i < len(lines) and lines[i].startswith('|'): group.append(lines[i]); i += 1
            table(doc, group); continue
        if line.startswith('# '):
            title = doc.add_paragraph(line[2:], 'Title')
            title.paragraph_format.line_spacing = 1.1
            title.paragraph_format.space_after = Pt(18)
        elif line.startswith('## '): doc.add_paragraph(line[3:], 'Heading 1')
        elif line.startswith('### '): doc.add_paragraph(line[4:], 'Heading 2')
        elif line.strip():
            paragraph = doc.add_paragraph()
            inline(paragraph, line)
        i += 1
    output = ROOT / 'Timefold_Audit.docx'
    doc.save(output)
    # Verify every Markdown line, including table cells and hyperlink labels, survived authoring.
    from lxml import etree
    from zipfile import ZipFile
    with ZipFile(output) as archive:
        xml = etree.fromstring(archive.read('word/document.xml'))
    text = ''.join(xml.itertext())
    for line in lines:
        if not line or re.fullmatch(r'[| :\-]+', line): continue
        cells = line.strip('|').split('|') if line.startswith('|') else [re.sub(r'^#+ ', '', line)]
        for cell in cells:
            plain = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', cell.strip())
            assert plain in text, plain
    print(f'Created {output}; Markdown content verified')


if __name__ == '__main__': main()
