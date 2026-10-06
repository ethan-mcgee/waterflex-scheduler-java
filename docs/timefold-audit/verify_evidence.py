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
from campaign_spec import verify_configuration

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
REVIEWED = 'afa035ddf2101d26c12508067d2d9016601db6c3'
CURRENT = '499fde2cc3fe79a8b7413a942a66cfc97b5d0e94'
PREVIOUS = '09fb326d24d845ef0812debf301ac4fe7e1f92f7'
REVIEW = ROOT / 'evidence/fourth-review'


def verify_hash(content, item):
    if item.get('normalize_crlf'):
        content = content.replace(b'\r\n', b'\n')
    assert hashlib.sha256(content).hexdigest() == item['sha256'], item['path']


def positive(value):
    if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
        raise ValueError('Expected finite positive measurement')
    return value


def nonnegative(value):
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise ValueError('Expected finite nonnegative measurement')
    return value


def positive_integer(value):
    if type(value) is not int or value <= 0:
        raise ValueError('Positive integer required')
    return value


def verify_campaign(design, report, config=None):
    if not isinstance(design, dict) or not isinstance(design.get('stages'), list) or not design['stages']:
        raise ValueError('Campaign stages required')
    seen = set()
    for stage in design['stages']:
        if not isinstance(stage, dict) or not isinstance(stage.get('id'), str) or not stage['id'] or stage['id'] in seen:
            raise ValueError('Unique stage identity required')
        seen.add(stage['id'])
        factors = stage.get('factors')
        if not isinstance(factors, list) or not factors or any(type(v) is not int or v <= 0 for v in factors):
            raise ValueError('Positive integer factors required')
        base = math.prod(factors)
        budgets = stage.get('budgets_seconds')
        if not isinstance(budgets, list) or not budgets:
            raise ValueError('Budgets required')
        budgets = [positive(v) for v in budgets]
        cases = base * len(budgets)
        solve = base * sum(budgets)
        jvms = positive_integer(stage.get('fresh_jvms'))
        workers = positive_integer(stage.get('parallel_cases'))
        warmup = jvms * positive(stage.get('warmup_seconds'))
        overhead = cases * nonnegative(stage.get('overhead_seconds_per_case'))
        serial_analysis = nonnegative(stage.get('serial_analysis_seconds'))
        for key, expected in {'expected_cases': cases, 'expected_solve_seconds': solve,
                              'expected_warmup_seconds': warmup, 'expected_overhead_seconds': overhead,
                              'expected_serial_seconds': solve + warmup + overhead + serial_analysis,
                              'expected_parallel_seconds': (solve + warmup + overhead) / workers + serial_analysis}.items():
            actual = nonnegative(stage.get(key))
            assert math.isclose(actual, expected), (stage['id'], key)
        counts = stage.get('report_counts')
        if not isinstance(counts, list) or not counts or any(not isinstance(v, str) or not v for v in counts):
            raise ValueError('Report arithmetic labels required')
        parallel = (solve + warmup + overhead) / workers + serial_analysis
        assert counts == [f'{cases:,} observations', f'{solve:,.0f} seconds solving',
                          f'{warmup:,.0f} seconds warmup', f'{overhead:,.0f} seconds overhead',
                          f'{solve + warmup + overhead + serial_analysis:,.0f} seconds serial',
                          f'{parallel:,.0f} seconds or {parallel / 60:g} minutes']
        assert all(label in report for label in counts)
    if config is not None:
        factors = verify_configuration(config)
        stage = design['stages'][0]
        assert stage['factors'] == factors
        assert stage['budgets_seconds'] == [b['seconds'] for b in config['budgetCohorts']]
        assert stage['fresh_jvms'] == stage['expected_cases']  # Illustrated process lifetime.
        assert stage['warmup_seconds'] == config['warmup']['secondsPerFreshJvm']
        assert stage['parallel_cases'] == config['resources']['parallelCases']
        assert stage['overhead_seconds_per_case'] == sum(v for k, v in config['estimation'].items() if k != 'serialAnalysisSeconds')
        assert stage['serial_analysis_seconds'] == config['estimation']['serialAnalysisSeconds']
    calibration = design.get('calibration_example')
    if calibration is not None:
        factors = calibration.get('factors')
        if not isinstance(factors, list) or not factors:
            raise ValueError('Calibration factors required')
        assert math.prod(positive_integer(v) for v in factors) == positive_integer(calibration.get('observations'))
        assert f'{calibration["observations"]} observations' in report


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
    previous = read(ROOT / 'evidence/third-review/manifest.json')
    for item in previous['artifacts']:
        verify_hash(git_bytes(PREVIOUS, item['path']), item)
    revision = read(REVIEW / 'manifest.json')
    assert revision['production_baseline'] == CURRENT
    assert revision['previous_report_revision'] == PREVIOUS
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
    for n in range(1, 12):
        assert re.search(rf'^### CR{n:02d} ', report, re.M), n
    coverage = read(ROOT / 'evidence/second-review/coverage.json')
    assert [c['chapter'] for c in coverage['chapters']] == list(range(1, 67))
    assert all(c['status'] == 'reviewed' and c['applicability'] and c['start_line'] <= c['end_line'] for c in coverage['chapters'])
    assert len(coverage['images']) == 113
    assert sum(len(c['images']) for c in coverage['chapters']) + len(coverage['frontmatter']['images']) == coverage['source']['image_references'] == 122
    urls = {r['url'] for r in coverage['official_pages']}
    assert len(urls) == 60 and all(r['status'] == 'retrieved' for r in coverage['official_pages'])
    assert {c['official_url'] for c in coverage['chapters']} <= urls
    assert all(set(r['links']) <= urls for r in coverage['official_pages'])
    sources = read(REVIEW / 'source-register.json')
    assert set(re.findall(r'https://docs.timefold.ai/[^)\s]+', report)) == set(sources['official_urls'])
    assert sources['production_baseline'] == CURRENT and sources['runtime_version'] == '2.6.0'
    for section in sources['sections']:
        assert type(section['start_line']) is int and type(section['end_line']) is int
        assert 0 < section['start_line'] <= section['end_line']
    if external:
        for item in (sources['primary_source'], sources['pdf']):
            verify_hash((REPO / item['path']).read_bytes(), item)
        inclusion = read(ROOT / 'evidence/third-review/source-inclusion.json')
        for item in inclusion['files']:
            verify_hash((REPO / item['path']).read_bytes(), item)
        lines = (REPO / sources['primary_source']['path']).read_text(encoding='utf-8').splitlines()
        for section in sources['sections']:
            assert section['end_line'] <= len(lines) and lines[section['start_line'] - 1] == section['heading']
    verify_campaign(read(REVIEW / 'campaign-design.json'), report, read(REVIEW / 'campaign-example.json'))
    supplied = read(REVIEW / 'supplied-review.json')
    for item in supplied['artifacts']:
        verify_hash((REPO / item['path']).read_bytes(), item)
    qa = read(REVIEW / 'visual-qa.json')
    assert qa['all_pages_inspected'] is True
    assert len(qa['page_reviews']) == qa['pages'] and qa['pages'] > 0
    assert [p['page'] for p in qa['page_reviews']] == list(range(1, qa['pages'] + 1))
    assert qa['docx_sha256'] == hashlib.sha256((ROOT / 'Timefold_Audit.docx').read_bytes()).hexdigest()
    for _, section in re.findall(r'^### ((?:TF|CR)\d\d) ([\s\S]*?)(?=^### |^## |\Z)', report, re.M):
        for explanation in ('Current behavior:', 'Consequence:', 'Guidance and change:', 'Why this helps:', 'Acceptance:'):
            assert explanation in section, explanation
    verify_parity(report, ROOT / 'Timefold_Audit.docx')
    print(f'Verified original, second-review and third-review artifacts, supplied review hashes, {len(revision["artifacts"])} new artifacts, '
          f'{len(links)} pinned links, 25 explained findings, Community parameter inventory, worked campaign arithmetic, {qa["pages"]} page receipts '
          'and exact Word text/link parity')
    if external:
        print('Supplied Markdown/PDF/figure hashes and selected section ranges verified')


if __name__ == '__main__':
    if not __debug__:
        raise RuntimeError('Verification requires assertions enabled')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--external', action='store_true')
    verify(parser.parse_args().external)
