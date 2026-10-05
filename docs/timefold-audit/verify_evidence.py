"""Verify historical evidence and expanded artifacts; --external also checks local docs/assets."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile
from lxml import etree
ROOT=Path(__file__).resolve().parent
REPO=ROOT.parents[1]
REVIEWED='afa035ddf2101d26c12508067d2d9016601db6c3'
def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))
def verify_hash(content,item):
    if item.get('normalize_crlf'):
        content=content.replace(b'\r\n',b'\n')
    assert hashlib.sha256(content).hexdigest()==item['sha256'],item['path']
def plain_line(line):
    return re.sub(r'\[([^\]]+)\]\([^)]+\)',r'\1',re.sub(r'^#+ ','',line.strip()))
def verify_historical(content,item,receipts):
    if item['path'] not in receipts:
        verify_hash(content,item)
        return
    receipt=receipts[item['path']]
    assert hashlib.sha256(content).hexdigest()==receipt['git_sha256'],item['path']
    assert b'\r' not in content, 'Expected retained Git LF bytes'
    original=content.replace(b'\n',b'\r\n')
    assert len(original)==item['bytes']
    assert hashlib.sha256(original).hexdigest()==item['sha256']==receipt['original_crlf_sha256']
def verify_parity(report,docx):
    expected=[]
    for line in report.splitlines():
        if not line.strip() or re.fullmatch(r'[| :\-]+',line):continue
        cells=line.strip('|').split('|') if line.startswith('|') else [line]
        expected.extend(plain_line(cell) for cell in cells)
    with ZipFile(docx) as archive:
        xml=etree.fromstring(archive.read('word/document.xml'))
        ns={'w':'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
        actual=[''.join(p.xpath('.//w:t/text()',namespaces=ns)) for p in xml.xpath('.//w:body//w:p',namespaces=ns)]
        assert [t for t in actual if t.strip()]==expected,'Markdown/Word paragraph and cell order differs'
        rels=etree.fromstring(archive.read('word/_rels/document.xml.rels'))
        targets={r.get('Target') for r in rels if r.get('TargetMode')=='External'}
        assert set(re.findall(r'\[[^\]]+\]\(([^)]+)\)',report))<=targets

def verify(external=False):
    historical=read(ROOT/'evidence/summary.json')
    receipts={r['path']:r for r in read(ROOT/'evidence/second-review/historical-line-endings.json')}
    for item in historical['artifacts']:
        # Old authoring hashes remain valid at the retained Git revision.
        content=subprocess.check_output(['git','show',f'{REVIEWED}:{item["path"]}'],cwd=REPO)
        verify_historical(content,item,receipts)
        if '/evidence/' in item['path'] or item['path'].endswith('TimefoldAuditEvidenceTest.java'):
            verify_historical((REPO/item['path']).read_bytes(),item,receipts)
    expanded=read(ROOT/'evidence/second-review/manifest.json')
    for item in expanded['artifacts']:verify_hash((REPO/item['path']).read_bytes(),item)
    report=(ROOT/'Timefold_Audit.md').read_text(encoding='utf-8')
    assert '\u2014' not in report and not re.search(r'\[code:|\[D\d+\]',report)
    links=set(re.findall(r'https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/([0-9a-f]+)/([^ )#]+)#L(\d+)',report))
    assert links
    for commit,path,line in links:
        assert commit in (REVIEWED,historical['production_baseline'])
        text=subprocess.check_output(['git','show',f'{commit}:{path}'],cwd=REPO).decode('utf-8')
        assert 0<int(line)<=len(text.splitlines()),(path,line)
    for n in range(1,15):assert re.search(rf'^### TF{n:02d} ',report,re.M),n
    coverage=read(ROOT/'evidence/second-review/coverage.json');chapters=coverage['chapters']
    assert [c['chapter'] for c in chapters]==list(range(1,67))
    assert all(c['status']=='reviewed' and c['applicability'] and c['start_line']<=c['end_line'] for c in chapters)
    assert len(coverage['images'])==113
    assert sum(len(c['images']) for c in chapters)+len(coverage['frontmatter']['images'])==coverage['source']['image_references']==122
    assert coverage['frontmatter']['status']=='reviewed'
    urls={r['url'] for r in coverage['official_pages']}
    assert len(urls)==60 and all(r['status']=='retrieved' for r in coverage['official_pages'])
    assert {c['official_url'] for c in chapters}<=urls
    assert set(re.findall(r'https://docs.timefold.ai/[^)\s]+',report))<=urls
    assert all(set(r['links'])<=urls for r in coverage['official_pages'])
    if external:
        source=Path(coverage['source']['path']);verify_hash(source.read_bytes(),coverage['source'])
        assert len(re.findall(r'^## Chapter ',source.read_text(encoding='utf-8'),re.M))==66
        for item in coverage['images']:verify_hash((source.parent/item['path']).read_bytes(),item)
    verify_parity(report,ROOT/'Timefold_Audit.docx')
    print(f'Verified {len(historical["artifacts"])} historical artifacts, {len(expanded["artifacts"])} current artifacts, {len(links)} pinned links, 66 chapters, 60 guide URLs and exact Word text/link parity')
    if external:print('Primary documentation and all 113 asset hashes verified unchanged')
if __name__=='__main__':
    if not __debug__:raise RuntimeError('Verification requires assertions enabled')
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--external',action='store_true')
    verify(parser.parse_args().external)
