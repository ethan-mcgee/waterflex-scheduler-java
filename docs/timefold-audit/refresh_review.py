"""Record this review's sources and artifacts, without modifying historical evidence.

Run --sources after source review, and --manifest only after verification and visual QA.
The campaign design is a specification, not executable benchmark configuration.
"""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
REVIEW = ROOT / 'evidence/third-review'
BASELINE = '499fde2cc3fe79a8b7413a942a66cfc97b5d0e94'


def write(name, value):
    (REVIEW / name).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def receipt(path, normalize=False):
    content = path.read_bytes()
    if normalize:
        content = content.replace(b'\r\n', b'\n')
    return {'path': path.relative_to(REPO).as_posix(), 'sha256': hashlib.sha256(content).hexdigest(),
            'bytes': len(content), 'normalize_crlf': normalize}


def sources():
    guide = REPO / 'docs/timefold-documentation/Timefold-Solver-Docs.md'
    lines = guide.read_text(encoding='utf-8').splitlines()
    # Exact sections actually used; ranges identify their contents, not a new full-guide audit.
    selected = ['10.', '12.', '16.2.', '16.3.', '16.5.', '16.11.', '16.14.', '17.',
                '25.', '31.', '32.1.', '38.1.', '39.4.', '39.5.', '39.6.', '42.1.']
    headings = [(i + 1, line) for i, line in enumerate(lines) if re.match(r'^#{2,4} ', line)]
    sections = []
    for prefix in selected:
        match = next((h for h in headings if re.match(r'^#{2,4} (?:Chapter )?' + re.escape(prefix) + r' ', h[1])), None)
        if match is None:
            raise ValueError(f'Missing source section {prefix}')
        start, heading = match
        level = len(heading.split(' ')[0])
        end = next((n - 1 for n, title in headings if n > start and len(title.split(' ')[0]) <= level), len(lines))
        sections.append({'section': prefix.rstrip('.'), 'heading': heading, 'start_line': start, 'end_line': end,
                         'use': 'Source context for recommendations; see chapter and section citations in report'})
    report = (ROOT / 'Timefold_Audit.md').read_text(encoding='utf-8')
    urls = sorted(set(re.findall(r'https://docs.timefold.ai/[^)\s]+', report)))
    write('source-register.json', {
        'review_date': '2026-10-06', 'production_baseline': BASELINE,
        'runtime_version': '2.6.0', 'primary_source': receipt(guide),
        'pdf': receipt(guide.with_suffix('.pdf')),
        'source_scope': 'Selected relevant Markdown sections; no new complete PDF or 66-chapter review claimed',
        'sections': sections, 'official_urls': urls,
        'live_checks': [
            {'url': 'https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/benchmarking-and-tweaking', 'observed_version': '2.7.0'},
            {'url': 'https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/performance', 'observed_version': '2.7.1'},
            {'url': 'https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/local-search', 'observed_version': '2.7.1'}],
        'notes': ['Live URLs are mutable. Local source bytes identify the supplied edition.',
                  'Examples remain proposed and have not been compiled against the benchmark artifact.',
                  'Chapter ranges identify cited context; selection does not imply every nested example was executed.']})


def manifest():
    if not (REVIEW / 'visual-qa.json').exists() or not (REVIEW / 'verification.json').exists():
        raise ValueError('Complete verification and visual QA before recording manifest')
    paths = [p for p in ROOT.iterdir() if p.is_file() and p.suffix in ('.py', '.md', '.docx')]
    paths += [p for p in REVIEW.iterdir() if p.is_file() and p.name not in ('manifest.json', 'final-verification.log')]
    historical = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', BASELINE, '--',
                                         'docs/timefold-audit/evidence'], cwd=REPO, text=True).splitlines()
    history = []
    for name in historical:
        data = subprocess.check_output(['git', 'show', f'{BASELINE}:{name}'], cwd=REPO)
        history.append({'path': name, 'sha256': hashlib.sha256(data.replace(b'\r\n', b'\n')).hexdigest(),
                        'normalize_crlf': True})
    write('manifest.json', {'review_date': '2026-10-06', 'production_baseline': BASELINE,
                           'historical_artifacts': history,
                           'artifacts': [receipt(p, p.suffix != '.docx') for p in sorted(paths)]})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sources', action='store_true')
    parser.add_argument('--manifest', action='store_true')
    args = parser.parse_args()
    if not (args.sources or args.manifest):
        parser.error('Select --sources and/or --manifest')
    REVIEW.mkdir(parents=True, exist_ok=True)
    if args.sources:
        sources()
    if args.manifest:
        manifest()
