"""Verify evidence, prose claims, original figures and the generated DOCX."""
from hashlib import sha256
import json
from pathlib import Path
from statistics import mean
from zipfile import ZipFile
from lxml import etree
from docx import Document
from report_data import choose, outcomes, verify

ROOT=Path(__file__).resolve().parent

def main():
    e=json.loads((ROOT/'evidence.json').read_text(encoding='utf-8')); pp=verify(e);rr=e['cases']
    cp=[p for p in pp if p['comparable']]
    assert sum(p['savings']['paid_min'] for p in cp)==2870
    assert sum(p['savings']['cost_cents'] for p in cp)==146512
    assert sum(p['savings']['paid_min']>0 for p in cp)==36
    assert sum(p['savings']['cost_cents']>0 for p in cp)==37
    assert all(p['savings']['paid_min']>=0 and p['savings']['cost_cents']>=0 for p in cp)
    for c,served,incomplete,failed in [(1,(600,600),(0,0),(0,0)),(5,(360,369),(233,230),(7,1)),(10,(386,392),(208,207),(6,1))]:
        for n,s in enumerate(('INSERTION','BOUNDED')):
            o=outcomes(choose(rr,concurrency=c,solver=s))
            assert (o['requests'],o['latency_n'],o['served'],o['incomplete'],o['failed'],o['unknown'])==(600,600,served[n],incomplete[n],failed[n],0)
    for r in rr:
        assert r['after']['waiting_min']==r['after']['overtime_min']==0
    for name in ('figure-001.png','figure-003.png'):
        key=f"analysis/{e['analysis']}/{name}"
        assert sha256((ROOT/'original-figures'/name).read_bytes()).hexdigest()==e['file_hashes'][key]
    reference=ROOT.parent/'scheduler-explainer/reference.docx'
    output=ROOT/'WaterFlex_Booking_Experiment_Results_Explained.docx'
    doc=Document(output); ref=Document(reference)
    for attr in ('page_width','page_height','top_margin','bottom_margin','left_margin','right_margin'):
        assert getattr(doc.sections[0],attr)==getattr(ref.sections[0],attr)
    with ZipFile(output) as z:
        texts=[]
        for n in z.namelist():
            if n.startswith('word/') and n.endswith('.xml'):
                tree=etree.fromstring(z.read(n));texts.extend(tree.xpath('//*[local-name()="t"]/text()'))
        alltext=' '.join(texts)
        assert '\u2014' not in alltext
        assert '[[' not in alltext
        assert all(f'Appendix {f} technician scenarios' in alltext for f in (5,10,20,50))
        assert len(doc.inline_shapes)==7
        # Source styles and footer are reused; body and metadata intentionally change.
        with ZipFile(reference) as original:
            for n in ('word/numbering.xml','word/theme/theme1.xml','word/footer1.xml'):
                assert z.read(n)==original.read(n),n
    for p in ROOT.glob('*.py'):
        assert '\u2014' not in p.read_text(encoding='utf-8')
    print('PASS: 360 cases; 60 paired and 120 excluded comparisons; 3600 request timings; 36 scenario groups; arithmetic, missingness, figures, DOCX structure and punctuation')

if __name__=='__main__':main()
