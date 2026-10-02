"""Verify retained audit artifacts and source-link locations without network access."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
summary = json.loads((ROOT / 'evidence/summary.json').read_text(encoding='utf-8'))
for item in summary['artifacts']:
    path = REPO / item['path']
    content = path.read_bytes()
    if item.get('normalize_crlf'):
        content = content.replace(b'\r\n', b'\n')
    assert hashlib.sha256(content).hexdigest() == item['sha256'], path
report = (ROOT / 'Timefold_Audit.md').read_text(encoding='utf-8')
assert '\u2014' not in report
links = set(re.findall(r'https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/([0-9a-f]+)/([^ )#]+)#L(\d+)', report))
assert links, 'No pinned source links found'
for commit, path, line in links:
    assert commit == summary['production_baseline']
    source = subprocess.check_output(['git', 'show', f'{commit}:{path}'], cwd=REPO).decode('utf-8')
    assert 0 < int(line) <= len(source.splitlines()), (path, line)
print(f'Verified {len(summary["artifacts"])} artifact hashes and {len(links)} pinned source locations')
