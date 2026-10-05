"""Reconcile retained experiments without launching measurements or editing archives."""
import argparse
from collections import Counter
import csv
import hashlib
import importlib.util
import json
from pathlib import Path
from statistics import mean
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]

def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ValueError(f'Cannot load evidence helper: {path}')
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result

def read(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f'Duplicate JSON key {key} in {path}')
            result[key] = value
        return result
    return json.loads(path.read_text(encoding='utf-8-sig'), object_pairs_hook=unique,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def receipt(run, analysis):
    manifest = read(run/'manifest.json')
    config = read(run/'config.json')
    cases = read(run/'cases.json')
    config_tools = module('audit_config', REPO/'infra/experiment_config.py')
    assert config_tools.digest(read(run/'original-config.json')) == manifest['config_hash']
    assert config_tools.digest(cases) == manifest['cases_hash']
    provenance = read(analysis/'analysis-provenance.json')
    files = {item['name'].replace('\\','/'): item['sha256'] for item in provenance['inputs']}
    assert len(files) == len(provenance['inputs'])
    for name, expected in files.items():
        assert sha(run/name) == expected, name
    frozen = run/'frozen'
    for name, expected in manifest['files'].items():
        assert sha(frozen/name.replace('\\','/')) == expected, name
    metadata = ['manifest.json','config.json','original-config.json','cases.json']
    metadata += [str(p.relative_to(run)).replace('\\','/') for p in [analysis/'analysis-provenance.json',analysis/'cases.csv',analysis/'summary.json']]
    return dict(archive=run.name,analysis=analysis.name,revision=manifest['revision'],config=config,
                expected_cases=len(cases),raw_hashes_verified=len(files),frozen_hashes_verified=len(manifest['files']),
                input_hashes=files,metadata_hashes={name:sha(run/name) for name in metadata})

def collect(archive_root):
    result = {'review_date':'2026-10-02','archive_root':str(archive_root),'runs':[]}
    booking = archive_root/'20260930T135624Z-booking-comparison-a752ef8e'
    ba = booking/'analysis/20260930T154451Z-5a2c0070'
    be = module('audit_booking_extract', REPO/'docs/booking-experiment-explainer/extract_evidence.py').extract(booking, ba)
    br = receipt(booking, ba)
    br['pair_counts'] = be['pair_counts']
    br['outcomes'] = dict(Counter(a['outcome'] for row in be['cases'] for a in row['attempts']))
    br['observed_cases'] = len(be['cases'])
    result['runs'].append(br)
    daily = module('audit_daily_data', REPO/'docs/daily-budget-331df1f1/report_data.py')
    daily.RUN = archive_root/'20261001T065847Z-daily-budget-331df1f1'
    daily.ANALYSIS = daily.RUN/'analysis/20261001T113647Z-4d5d82e8'
    _, rows = daily.load()
    dr = receipt(daily.RUN,daily.ANALYSIS)
    dr['observed_cases']=len(rows)
    dr['accepted_mean_cents']={solver:mean(r['raw']['after']['costCents'] for r in rows if r['key'][-1]==solver) for solver in ('TABU','KOPT','LATE_ACCEPTANCE','SUBLIST')}
    dr['violations']=sum(r['raw']['violations'] for r in rows)
    result['runs'].append(dr)
    for name,analysis_name in [('20261001T064508Z-daily-contention-6-931421d0','20261001T065328Z-374c8971'),('20261001T145040Z-daily-contention-1-912c78be','20261001T153932Z-c28bb3fc')]:
        run=archive_root/name;analysis=run/'analysis'/analysis_name
        rr=receipt(run,analysis)
        provenance=read(analysis/'analysis-provenance.json')
        assert sha(run/'frozen/source/infra/experiment_analysis.py')==provenance['analyzer_sha256']
        analyzer=module('audit_'+name[-8:],run/'frozen/source/infra/experiment_analysis.py')
        normalized=[]
        violations=[]
        for item in provenance['inputs']:
            lines=[json.loads(line) for line in (run/item['name'].replace('\\','/')).read_text().splitlines() if line.strip()]
            assert [line['type'] for line in lines] == ['provenance','configuration','result']
            normalized.append(analyzer.normalize(lines[2],lines[0],'current'))
            violations.append(lines[2]['violations'])
        normalized,issues=analyzer.compare(normalized,{'daily':'TABU'})
        assert not issues
        saved=list(csv.DictReader((analysis/'cases.csv').open(encoding='utf-8',newline='')))
        assert len(saved)==len(normalized)==24
        for row,old in zip(normalized,saved):
            for field in ('accepted_cost','reference_cost','elapsed_ms','reference_move_rate','fairness_move_rate'):
                assert row[field] is not None and abs(row[field]-float(old[field]))<1e-7,field
            assert row['pair_status']==old['pair_status']=='paired'
        rr['observed_cases']=len(normalized)
        rr['violations']=sum(violations)
        rr['groups']=[dict(fleet=fleet,solver=solver,accepted_mean_cents=mean(r['accepted_cost'] for r in normalized if r['fleet']==fleet and r['solver']==solver),reference_moves_per_second=mean(r['reference_move_rate'] for r in normalized if r['fleet']==fleet and r['solver']==solver),fairness_moves_per_second=mean(r['fairness_move_rate'] for r in normalized if r['fleet']==fleet and r['solver']==solver)) for fleet in (20,50) for solver in ('TABU','KOPT','LATE_ACCEPTANCE','SUBLIST')]
        result['runs'].append(rr)
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive-root',type=Path,required=True)
    args=parser.parse_args()
    result=collect(args.archive_root)
    output=ROOT/'evidence/second-review/retained-evidence.json'
    output.write_text(json.dumps(result,indent=2,allow_nan=False)+'\n',encoding='utf-8')
    for row in result['runs']:
        print(row['archive'],row['observed_cases'],'cases;',row['raw_hashes_verified'],'raw hashes;',row['frozen_hashes_verified'],'frozen hashes verified')
