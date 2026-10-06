"""Verify preserved reviews, rewritten report, campaign arithmetic and optional local sources."""
import argparse
import hashlib
import math
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile
from lxml import etree
from collect_retained_evidence import read

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
REVIEWED = 'afa035ddf2101d26c12508067d2d9016601db6c3'
CURRENT = '499fde2cc3fe79a8b7413a942a66cfc97b5d0e94'


def verify_hash(content, item):
    if item.get('normalize_crlf'):
        content = content.replace(b'\r\n', b'\n')
    assert hashlib.sha256(content).hexdigest() == item['sha256'], item['path']


def positive(value):
    if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
        raise ValueError('Expected finite positive measurement')
    return value


def verify_campaign(design, report):
    if not isinstance(design, dict) or not isinstance(design.get('stages'), list) or not design['stages']:
        raise ValueError('Campaign stages required')
    seen = set()
    for stage in design['stages']:
        if not isinstance(stage, dict) or not isinstance(stage.get('id'), str) or stage['id'] in seen:
            raise ValueError('Unique stage identity required')
        seen.add(stage['id'])
        factors = stage.get('factors')
        if not isinstance(factors, list) or not factors or any(type(v) is not int or v <= 0 for v in factors):
            raise ValueError('Positive integer factors required')
        base = math.prod(factors)
        if 'requests_per_case' in stage:
            requests = stage['requests_per_case']
            if type(requests) is not int or requests <= 0:
                raise ValueError('Positive integer requests required')
            assert stage['expected_cases'] == base
            assert stage['expected_requests'] == base * requests
        else:
            budgets = stage.get('budgets_seconds')
            if not isinstance(budgets, list) or not budgets:
                raise ValueError('Budgets required')
            budgets = [positive(v) for v in budgets]
            assert stage['expected_cases'] == base * len(budgets)
            assert math.isclose(stage['expected_solve_seconds'], base * sum(budgets))
            if 'warmup_per_observation_seconds' in stage:
                warmups = stage['warmup_per_observation_seconds']
                if not isinstance(warmups, list) or len(warmups) != len(budgets):
                    raise ValueError('Warmup probe alignment required')
                warmup = base * sum(positive(v) for v in warmups)
            else:
                warmup = positive(stage['batches']) * positive(stage['warmup_seconds'])
            assert math.isclose(stage['expected_warmup_seconds'], warmup)
        if 'report_count' in stage:
            assert stage['report_count'] in report
    assert seen == {'warmup', 'reference-screen', 'fairness-screen', 'move-screen', 'confirmation', 'booking'}
    setup = design['fairness_reference_setup']
    assert setup['expected_solve_seconds'] == positive(setup['datasets']) * positive(setup['budget_seconds'])
    screen = [s for s in design['stages'] if s['id'] in ('reference-screen', 'fairness-screen')]
    assert sum(s['expected_cases'] for s in screen) == 960
    assert sum(s['expected_solve_seconds'] for s in screen) == 7 * 3600


def plain_line(line):
    return re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', re.sub(r'^#+ ', '', line.strip()))


def verify_historical(content, item, receipts):
    if item['path'] not in receipts:
        verify_hash(content, item)
        return
    receipt = receipts[item['path']]
    assert hashlib.sha256(content).hexdigest() == receipt['git_sha256'], item['path']
    assert b'\r' not in content, 'Expected retained Git LF bytes'
    original = content.replace(b'\n', b'\r\n')
    assert len(original) == item['bytes']
    assert hashlib.sha256(original).hexdigest() == item['sha256'] == receipt['original_crlf_sha256']


def verify_parity(report, docx):
    expected = []
    for line in report.splitlines():
        if not line.strip() or re.fullmatch(r'[| :\-]+', line):
            continue
        cells = line.strip('|').split('|') if line.startswith('|') else [line]
        expected.extend(plain_line(cell) for cell in cells)
    with ZipFile(docx) as archive:
        xml = etree.fromstring(archive.read('word/document.xml'))
        ns = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
        actual = [''.join(p.xpath('.//w:t/text()', namespaces=ns)) for p in xml.xpath('.//w:body//w:p', namespaces=ns)]
        assert [t for t in actual if t.strip()] == expected, 'Markdown/Word paragraph and cell order differs'
        rels = etree.fromstring(archive.read('word/_rels/document.xml.rels'))
        targets = {r.get('Id'): r.get('Target') for r in rels if r.get('TargetMode') == 'External'}
        actual_links = []
        for link in xml.xpath('.//w:body//w:hyperlink', namespaces=ns):
            label = ''.join(link.xpath('.//w:t/text()', namespaces=ns))
            relation = link.get('{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id')
            assert relation in targets, 'Missing external hyperlink relationship'
            actual_links.append((label, targets[relation]))
        assert re.findall(r'\[([^\]]+)\]\(([^)]+)\)', report) == actual_links, 'Word hyperlink label/target order differs'


def git_bytes(revision, path):
    return subprocess.check_output(['git', 'show', f'{revision}:{path}'], cwd=REPO)


def verify(external=False):
    historical = read(ROOT / 'evidence/summary.json')
    receipts = {r['path']: r for r in read(ROOT / 'evidence/second-review/historical-line-endings.json')}
    for item in historical['artifacts']:
        verify_historical(git_bytes(REVIEWED, item['path']), item, receipts)
        if '/evidence/' in item['path'] or item['path'].endswith('TimefoldAuditEvidenceTest.java'):
            verify_historical((REPO / item['path']).read_bytes(), item, receipts)
    expanded = read(ROOT / 'evidence/second-review/manifest.json')
    for item in expanded['artifacts']:
        verify_hash(git_bytes(CURRENT, item['path']), item)
    revision = read(ROOT / 'evidence/third-review/manifest.json')
    assert revision['production_baseline'] == CURRENT
    for item in revision['historical_artifacts']:
        verify_hash((REPO / item['path']).read_bytes(), item)
    for item in revision['artifacts']:
        verify_hash((REPO / item['path']).read_bytes(), item)
    report = (ROOT / 'Timefold_Audit.md').read_text(encoding='utf-8')
    assert '\u2014' not in report and not re.search(r'\[code:|\[D\d+\]', report)
    links = set(re.findall(r'https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/([0-9a-f]+)/([^ )#]+)#L(\d+)', report))
    assert links
    for commit, path, line in links:
        assert commit == CURRENT
        assert 0 < int(line) <= len(git_bytes(commit, path).decode('utf-8').splitlines()), (path, line)
    for n in range(1, 15):
        assert re.search(rf'^### TF{n:02d} ', report, re.M), n
    coverage = read(ROOT / 'evidence/second-review/coverage.json')
    assert [c['chapter'] for c in coverage['chapters']] == list(range(1, 67))
    assert all(c['status'] == 'reviewed' and c['applicability'] and c['start_line'] <= c['end_line'] for c in coverage['chapters'])
    assert len(coverage['images']) == 113
    assert sum(len(c['images']) for c in coverage['chapters']) + len(coverage['frontmatter']['images']) == coverage['source']['image_references'] == 122
    urls = {r['url'] for r in coverage['official_pages']}
    assert len(urls) == 60 and all(r['status'] == 'retrieved' for r in coverage['official_pages'])
    assert {c['official_url'] for c in coverage['chapters']} <= urls
    assert all(set(r['links']) <= urls for r in coverage['official_pages'])
    sources = read(ROOT / 'evidence/third-review/source-register.json')
    assert set(re.findall(r'https://docs.timefold.ai/[^)\s]+', report)) == set(sources['official_urls'])
    assert sources['production_baseline'] == CURRENT and sources['runtime_version'] == '2.6.0'
    for section in sources['sections']:
        assert type(section['start_line']) is int and type(section['end_line']) is int
        assert 0 < section['start_line'] <= section['end_line']
    if external:
        for item in (sources['primary_source'], sources['pdf']):
            verify_hash((REPO / item['path']).read_bytes(), item)
        lines = (REPO / sources['primary_source']['path']).read_text(encoding='utf-8').splitlines()
        for section in sources['sections']:
            assert section['end_line'] <= len(lines) and lines[section['start_line'] - 1] == section['heading']
    verify_campaign(read(ROOT / 'evidence/third-review/campaign-design.json'), report)
    qa = read(ROOT / 'evidence/third-review/visual-qa.json')
    assert qa['all_pages_inspected'] is True
    assert len(qa['page_reviews']) == qa['pages'] and qa['pages'] > 0
    assert [p['page'] for p in qa['page_reviews']] == list(range(1, qa['pages'] + 1))
    assert qa['docx_sha256'] == hashlib.sha256((ROOT / 'Timefold_Audit.docx').read_bytes()).hexdigest()
    for _, section in re.findall(r'^### (TF\d\d) ([\s\S]*?)(?=^### |^## |\Z)', report, re.M):
        for explanation in ('Current behavior:', 'Consequence:', 'Guidance and change:', 'Why this helps:', 'Acceptance:'):
            assert explanation in section, explanation
    verify_parity(report, ROOT / 'Timefold_Audit.docx')
    print(f'Verified original and second-review Git artifacts, {len(revision["artifacts"])} new artifacts, '
          f'{len(links)} pinned links, 14 explained findings, campaign arithmetic, {qa["pages"]} page receipts '
          'and exact Word text/link parity')
    if external:
        print('Supplied Markdown/PDF hashes and selected section ranges verified')


if __name__ == '__main__':
    if not __debug__:
        raise RuntimeError('Verification requires assertions enabled')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--external', action='store_true')
    verify(parser.parse_args().external)
