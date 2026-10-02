"""Read an immutable booking archive; write compact, traceable report inputs only."""
import argparse
from collections import Counter
import csv
from hashlib import sha256
import json
import math
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
def unique_object(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError(f'Duplicate JSON key: {key}')
        obj[key] = value
    return obj

def measurement(row, *path):
    for key in path:
        if row is None:
            return None
        if not isinstance(row, dict):
            raise ValueError('Invalid measurement object')
        row = row.get(key)
    if row is not None and (type(row) not in (int, float) or not math.isfinite(row) or row < 0):
        raise ValueError('Invalid nonnegative measurement')
    return row

def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'), object_pairs_hook=unique_object)

def complete_sum(values):
    return None if not values or any(v is None for v in values) else sum(values)

def audit(a):
    if not isinstance(a, dict) or not isinstance(a.get('days'), list) or not a['days']:
        raise ValueError('Missing audited days')
    days = a['days']
    result = {key: complete_sum([measurement(d, *path) for d in days]) for key, path in {
        'cost_cents': ('policy', 'costCents'), 'overtime_min': ('policy', 'overtimeMinutes'),
        'waiting_min': ('waitingMinutes',), 'road_sec': ('roadSeconds',),
        'buffer_sec': ('configuredBufferSeconds',), 'rounding_sec': ('roundingSeconds',),
        'confirmed': ('confirmedAppointments',), 'reserved': ('reservedStops',),
    }.items()}
    paid = []
    for d in days:
        policy = d.get('policy')
        fairness = policy.get('fairness') if isinstance(policy, dict) else None
        workloads = fairness.get('workloads') if isinstance(fairness, dict) else None
        if workloads == [] and all(measurement(d, *path) == 0 for path in [
            ('confirmedAppointments',), ('reservedStops',), ('roadSeconds',), ('waitingMinutes',),
            ('configuredBufferSeconds',), ('roundingSeconds',), ('policy', 'costCents')]):
            # Explicitly audited empty day. This is a derivation from recorded zeros,
            # not a replacement for an absent workload or unknown measurement.
            paid.append(0)
        else:
            paid.append(None if not isinstance(workloads, list) or not workloads else
                        complete_sum([measurement(w, 'paidMinutes') for w in workloads]))
    result['paid_min'] = complete_sum(paid)
    return result

def extract(archive, analysis):
    provenance = read(analysis / 'analysis-provenance.json')
    frozen = archive / 'frozen/source/infra'
    if sha256((frozen/'experiment_analysis.py').read_bytes()).hexdigest() != provenance['analyzer_sha256']:
        raise ValueError('Frozen analyzer differs from recorded analysis')
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(frozen))
    from experiment_analysis import normalize, compare
    expected = read(archive / 'cases.json')
    summary = read(analysis / 'summary.json')
    expected_by_id = {c['id']: c for c in expected}
    archived_csv = list(csv.DictReader((analysis / 'cases.csv').open(encoding='utf-8')))
    rows, receipts = [], []
    all_raw = list(archive.glob('attempts/*/*/raw.jsonl'))
    for item in provenance['inputs']:
        relative = item['name'].replace('\\', '/')
        path = archive / relative
        data = path.read_bytes()
        digest = sha256(data).hexdigest()
        if digest != item['sha256']:
            raise ValueError(f'Archive hash mismatch: {relative}')
        records = [json.loads(line, object_pairs_hook=unique_object) for line in data.decode('utf-8-sig').splitlines()]
        if len(records) != 2 or records[0]['type'] != 'provenance' or records[1]['type'] != 'case':
            raise ValueError(f'Unexpected records: {relative}')
        p, raw = records
        row = normalize(raw, p, 'current')
        case_id = path.parent.parent.name
        exp = expected_by_id[case_id]
        for key in ('kind', 'solver', 'fleet', 'workload', 'seed', 'concurrency', 'cache', 'requests'):
            if row[key] != exp[key]:
                raise ValueError(f'Case mismatch {case_id}: {key}')
        row.update(source=relative, line=2, case_id=case_id, revision=p['revision'],
                   before=audit(raw['before']), after=audit(raw['after']), rates=raw['after']['operatingRates'],
                   policy_rules=raw['before']['policyRules'],
                   configuration=raw['processBefore']['configuration'],
                   configuration_fingerprint=raw['before']['configurationFingerprint'])
        for observed, original in zip(row['attempts'], raw['attempts']):
            observed.update(outcome=original['outcome'], selection_ms=original.get('selectionElapsedMs'))
        rows.append(row)
        receipts.append(dict(path=relative, sha256=digest))
    rows, issues = compare(rows, {'booking': 'INSERTION'})
    if issues or len(rows) != 360 or len({r['case_id'] for r in rows}) != 360:
        raise ValueError('Case reconciliation failed')
    for row, saved in zip(rows, archived_csv):
        if row['pair_status'] != saved['pair_status'] or row['served'] != int(saved['served']):
            raise ValueError('Existing analysis disagrees')
        for field in ('accepted_cost','before_cost','p50_ms','p95_ms','failed','incomplete','unknown_completion'):
            archived_value = float(saved[field]) if saved[field] else None
            if row[field] != archived_value:
                raise ValueError(f'Existing analysis disagrees: {field}')
    counts = Counter(r['pair_status'] for r in rows if r['solver'] == 'BOUNDED')
    if counts != {'paired': 60, 'different_served_customers': 120}:
        raise ValueError('Unexpected pairing exclusions')
    output = dict(archive=archive.name, analysis=analysis.name, expected=len(expected),
                  analyzer_sha256=provenance['analyzer_sha256'],
                  raw_files=len(all_raw), selected_inputs=len(receipts), observed=summary['observed_cases'],
                  pair_counts=dict(counts), sources=receipts, cases=rows)
    output['file_hashes'] = {str(p.relative_to(archive)).replace('\\','/'): sha256(p.read_bytes()).hexdigest()
                            for p in [archive/'config.json', archive/'cases.json', archive/'manifest.json',
                                      analysis/'analysis-provenance.json', analysis/'summary.json', analysis/'cases.csv',
                                      analysis/'figure-001.png', analysis/'figure-003.png']}
    return output

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path)
    parser.add_argument('--analysis', default='20260930T154451Z-5a2c0070')
    args = parser.parse_args()
    result = extract(args.archive, args.archive/'analysis'/args.analysis)
    (ROOT/'evidence.json').write_text(json.dumps(result, indent=2, allow_nan=False)+'\n', encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in ('cases','sources','file_hashes')}))
