"""Report calculations, with explicit missingness and matched-demand exclusions."""
from collections import Counter
import math
from statistics import mean

SOLVERS = ('INSERTION', 'BOUNDED')
METRICS = ('paid_min', 'cost_cents', 'road_sec', 'waiting_min', 'overtime_min', 'buffer_sec', 'rounding_sec')

def quantile(values, fraction):
    observed = sorted(v for v in values if v is not None)
    return observed[max(0, math.ceil(len(observed)*fraction)-1)] if observed else None

def average(values):
    # Tables promise all five seeds, so never silently average a partial set.
    return mean(values) if values and all(v is not None for v in values) else None

def difference(first, second):
    return None if first is None or second is None else first-second

def choose(rows, **filters):
    return [r for r in rows if all(r[k] == v for k,v in filters.items())]

def outcomes(rows):
    attempts = [a for r in rows for a in r['attempts']]
    latencies = [a['elapsed_ms'] for a in attempts]
    return dict(requests=len(attempts), served=sum(a['served'] for a in attempts),
                incomplete=sum(a['completed'] is False for a in attempts),
                unknown=sum(a['completed'] is None for a in attempts), failed=sum(a['failed'] for a in attempts),
                p50=quantile(latencies,.5), p95=quantile(latencies,.95),
                latency_n=sum(v is not None for v in latencies),
                outcomes=dict(Counter(a['outcome'] for a in attempts)))

def pairs(rows):
    key = lambda r: (r['fleet'],r['workload'],r['seed'],r['concurrency'],r['cache'])
    controls = {key(r):r for r in rows if r['solver']=='INSERTION'}
    result=[]
    for b in rows:
        if b['solver'] != 'BOUNDED':
            continue
        i=controls[key(b)]
        for field in ('fixture','routing','dates','calendar_reference','before','rates','policy_rules'):
            if i[field] != b[field]:
                raise ValueError(f'Pair conditions differ: {field}')
        # Audit configuration fingerprints include case-specific metro/technician IDs.
        # Compare actual runtime settings, allowing only the intended search variant.
        settings=lambda r:{k:v for k,v in r['configuration'].items() if k!='booking.search.variant'}
        if settings(i)!=settings(b):
            raise ValueError('Runtime settings differ outside the intended variant')
        matching = i['served_indices'] == b['served_indices']
        if matching != (b['pair_status']=='paired'):
            raise ValueError('Pair exclusions disagree')
        result.append(dict(insertion=i, bounded=b, comparable=matching,
                           savings={m:difference(i['after'][m],b['after'][m]) if matching else None for m in METRICS}))
    return result

def savings(pair_rows, metric):
    comparable=[p for p in pair_rows if p['comparable']]
    return average([p['savings'][metric] for p in comparable])

def verify(evidence):
    rows=evidence['cases']
    if not evidence['expected']==evidence['raw_files']==evidence['selected_inputs']==evidence['observed']==len(rows)==360:
        raise ValueError('Matrix count mismatch')
    if len({r['case_id'] for r in rows})!=360 or len(evidence['sources'])!=360:
        raise ValueError('Duplicate or missing evidence')
    pp=pairs(rows)
    if sum(p['comparable'] for p in pp)!=60 or any(p['comparable'] != (p['bounded']['concurrency']==1) for p in pp):
        raise ValueError('Unexpected comparison coverage')
    for r in rows:
        if len(r['attempts'])!=10 or sorted(a['index'] for a in r['attempts'])!=list(range(10)):
            raise ValueError('Invalid request denominator')
        for phase in ('before','after'):
            a=r[phase]
            if any(a[m] is None for m in METRICS):
                raise ValueError('Report requires unavailable audit metrics to be reviewed')
            reconstructed=30*a['confirmed']+(a['road_sec']+a['buffer_sec']+a['rounding_sec'])/60+a['waiting_min']
            if abs(reconstructed-a['paid_min'])>1e-7 or a['reserved']!=0:
                raise ValueError('Paid component reconciliation failed')
        if r['after']['confirmed']-r['before']['confirmed'] != r['served']:
            raise ValueError('Booking/audit reconciliation failed')
    return pp
