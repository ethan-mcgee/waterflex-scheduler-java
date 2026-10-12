"""Conservative raw-evidence adapter and reusable static experimental figures."""
from collections import Counter, defaultdict
import csv
from datetime import date, datetime, timezone
import gzip
import html
import json
import math
from pathlib import Path
import re
from statistics import mean, median
import uuid

from experiment_config import DAILY, canonical, read_json, unique_object, digest, expand, validate

# Booking experiments are retired; archived booking evidence still names these variants.
BOOKING = ['INSERTION', 'BOUNDED', 'EXPANDED', 'RUIN_RECREATE', 'SHARED']

# Colors are assigned by name so adding a solver never shifts an existing (archived) series' color.
COLORS = {'CURRENT_CAPPED': '#777777', 'CURRENT_UNCAPPED': '#444444', 'LATE_ACCEPTANCE_CHANGE': '#a58d3d',
          'LATE_ACCEPTANCE': '#c37e12', 'TABU': '#2363a0', 'SUBLIST': '#9171ad', 'KOPT': '#b34f75',
          'RUIN_RECREATE': '#608642', 'INSERTION': '#00959b', 'BOUNDED': '#d56033', 'EXPANDED': '#86743e',
          'SHARED': '#516170', 'TABU_SIZE_3': '#6fa8dc', 'TABU_SIZE_15': '#0b3a66', 'TABU_KOPT': '#3c9a8f'}


def number(value):
    if value is None:
        return None
    if type(value) not in (float, int) or not math.isfinite(value) or value < 0:
        raise ValueError('Invalid nonnegative measurement')
    return value


def measurement(row, *path):
    for key in path:
        if row is None:
            return None
        if not isinstance(row, dict):
            raise ValueError('Invalid measurement object')
        row = row.get(key)
    return number(row)


def required(row, key, kind):
    if not isinstance(row, dict):
        raise ValueError('Expected an observation object')
    value = row.get(key)
    if type(value) is not kind or (kind is str and not value):
        raise ValueError(f'Missing/invalid {key}')
    return value


def rate(count, milliseconds):
    """Events per second; unavailable (never zero) when either side is missing or no time elapsed."""
    if count is None or milliseconds is None or milliseconds <= 0:
        return None
    return count / milliseconds * 1000


def quantile(values, fraction):
    ordered = sorted(v for v in values if v is not None)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)] if ordered else None


def audit_cost(audit):
    if not isinstance(audit, dict) or not isinstance(audit.get('days'), list) or not audit['days']:
        return None
    values = [measurement(d, 'policy', 'costCents') for d in audit['days']]
    return sum(values) if all(v is not None for v in values) else None


def calendar_reference(value):
    if not isinstance(value, str) or not value:
        raise ValueError('Missing/invalid calendar reference')
    instant = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if instant.tzinfo is None:
        raise ValueError('Calendar reference requires an offset')
    return instant.astimezone(timezone.utc).isoformat()


def normalize(raw, provenance, cohort):
    daily = raw['type'] == 'result'
    row = dict(kind='daily' if daily else 'booking', cohort=cohort,
        solver=required(raw, 'variant', str), fleet=required(raw, 'technicians' if daily else 'size', int),
        workload=required(raw, 'workload', str), seed=required(raw if daily else provenance, 'seed', int),
        fixture=required(raw, 'datasetFingerprint', str))
    if row['solver'] not in (DAILY if daily else BOOKING) or row['fleet'] not in [5, 10, 20, 30, 50] or row['seed'] < 0:
        raise ValueError('Unsupported solver, fleet or seed')
    if daily:
        if raw.get('violations') != 0 or type(raw.get('violations')) is not int:
            raise ValueError('Daily independent validation absent/failed')
        row.update(budget_ms=number(raw.get('budgetMs', provenance.get('budgetMs'))),
            routing=required(provenance, 'routingIdentity', str),
            reference_cost=measurement(raw, 'reference', 'costCents'), accepted_cost=measurement(raw, 'after', 'costCents'),
            fairness=measurement(raw, 'after', 'fairness', 'variance'), elapsed_ms=measurement(raw, 'elapsedMs'),
            reference_ms=measurement(raw, 'referencePhase', 'solveMs'), fairness_ms=measurement(raw, 'fairnessPhase', 'solveMs'),
            reference_move_rate=rate(measurement(raw, 'referencePhase', 'moveEvaluations'), measurement(raw, 'referencePhase', 'solveMs')),
            fairness_move_rate=rate(measurement(raw, 'fairnessPhase', 'moveEvaluations'), measurement(raw, 'fairnessPhase', 'solveMs')))
        if row['budget_ms'] is None:
            raise ValueError('Daily budget unavailable')
    else:
        if raw.get('independentlyValidated') is not True or raw.get('promiseViolations') != 0:
            raise ValueError('Booking independent validation absent/failed')
        before, after = raw.get('before'), raw.get('after')
        if not isinstance(before, dict) or not isinstance(after, dict) or any(a.get('independentlyValidated') is not True for a in (before, after)):
            raise ValueError('Missing independent route/reservation audit')
        routing = required(before, 'routingIdentity', str)
        if after.get('routingIdentity') != routing:
            raise ValueError('Routing identity mismatch')
        # Historical archives retain their original real-time calendar semantics.
        reference = provenance.get('calendarReference')
        if reference is not None:
            row['calendar_reference'] = calendar_reference(reference)
            for phase in ('processBefore', 'processAfter'):
                observation = required(raw, phase, dict)
                settings = required(observation, 'configuration', dict)
                expected_isolation = {'scheduler.optimizer.cron': '-', 'routing.cache.cleanup-cron': '-',
                                      'routing.prewarm.enabled': 'false', 'time-off.analysis.enabled': 'false'}
                if any(settings.get(key) != value for key, value in expected_isolation.items()):
                    raise ValueError('Benchmark isolation settings changed or missing')
                if calendar_reference(settings.get('benchmark.calendar-reference')) != row['calendar_reference']:
                    raise ValueError('Server calendar differs from frozen reference')
        attempts = required(raw, 'attempts', list)
        dates = required(provenance, 'dates', list)
        if not dates or any(not isinstance(d, str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}', d) for d in dates) or len(set(dates)) != len(dates):
            raise ValueError('Invalid frozen dates')
        for d in dates:
            date.fromisoformat(d)
        if not attempts or len(attempts) != provenance.get('requests'):
            raise ValueError('Incomplete request observations')
        indices = [required(a, 'index', int) for a in attempts]
        if sorted(indices) != list(range(len(attempts))):
            raise ValueError('Duplicate/missing request indices')
        observed = []
        for a in attempts:
            served = required(a, 'served', bool)
            completed = a.get('completed')
            if completed is not None and type(completed) is not bool:
                raise ValueError('Invalid completion observation')
            outcome = required(a, 'outcome', str)
            latency = number(a.get('elapsedMs'))
            if outcome == 'SELECTION_CONFLICT' and a.get('selectionElapsedMs') is None:
                latency, completed = None, None  # Unrecoverable historical search/selection mixing.
            observed.append(dict(index=a['index'], served=served, completed=completed, elapsed_ms=latency,
                failed=outcome in ('SEARCH_ERROR', 'SELECTION_CONFLICT') or a.get('error') is not None))
        served = sum(a['served'] for a in observed)
        incomplete = sum(a['completed'] is False for a in observed)
        row.update(concurrency=required(raw, 'concurrency', int), cache=required(raw, 'cache', str),
            dates=dates, routing=routing, requests=len(observed), attempts=observed,
            served=served, incomplete=incomplete, failed=sum(a['failed'] for a in observed),
            unknown_completion=sum(a['completed'] is None for a in observed),
            served_indices=sorted(a['index'] for a in observed if a['served']),
            served_rate=served / len(observed), incomplete_rate=incomplete / len(observed),
            failed_rate=sum(a['failed'] for a in observed) / len(observed),
            accepted_cost=audit_cost(after), before_cost=audit_cost(before),
            p50_ms=quantile([a['elapsed_ms'] for a in observed], .5),
            p95_ms=quantile([a['elapsed_ms'] for a in observed], .95))
    return row


def load_raw(path, expected=None, cohort='current'):
    rows, issues, provenance = [], [], None
    opener = gzip.open if path.suffix == '.gz' else open
    try:
        with opener(path, 'rt', encoding='utf-8-sig') as stream:
            for line_index, line in enumerate(stream, 1):
                try:
                    raw = json.loads(line, object_pairs_hook=unique_object)
                    if not isinstance(raw, dict):
                        raise ValueError('JSON row must be an object')
                    if raw.get('type') == 'provenance':
                        if provenance is not None:
                            raise ValueError('Duplicate provenance')
                        provenance = raw
                    elif raw.get('type') in ('result', 'case'):
                        if provenance is None:
                            raise ValueError('Missing provenance')
                        row = normalize(raw, provenance, cohort)
                        if expected:
                            for key in ('kind', 'solver', 'fleet', 'workload', 'seed', 'budget_ms'):
                                if row.get(key) != expected[key]:
                                    raise ValueError(f'Unexpected {key}')
                        row['source'] = str(path)
                        row['line'] = line_index
                        rows.append(row)
                    elif raw.get('type') in ('failure', 'audit_failure'):
                        issues.append(dict(source=str(path), line=line_index, reason=raw['type']))
                except (ValueError, TypeError, KeyError) as error:
                    issues.append(dict(source=str(path), line=line_index, reason=str(error)))
    except (OSError, EOFError) as error:
        issues.append(dict(source=str(path), reason=type(error).__name__))
    if provenance is None:
        issues.append(dict(source=str(path), reason='Missing provenance'))
    return rows, issues


def pair_key(row, budget=True):
    fields = ['kind', 'cohort', 'fixture', 'routing', 'fleet', 'workload', 'seed']
    fields += (['budget_ms'] if budget else []) if row['kind'] == 'daily' else ['concurrency', 'cache', 'requests', 'dates', 'calendar_reference']
    return canonical([row.get(f) for f in fields])


def compare(rows, controls):
    issues = []
    def logical_key(row):
        # A matrix case cannot occur twice even if the duplicate claims a different fixture.
        return pair_key({**row, 'fixture': '<expected-case>', 'routing': '<expected-provider>'}), row['solver']
    counts = Counter(logical_key(r) for r in rows)
    valid = []
    for row in rows:
        if counts[logical_key(row)] > 1:
            issues.append(dict(source=row['source'], line=row['line'], reason='Duplicate case excluded (all copies)'))
        else:
            valid.append(dict(row))
    lookup = {(pair_key(r), r['solver']): r for r in valid}
    fifteen = {(pair_key(r, False), r['solver']): r for r in valid if r['kind'] == 'daily' and r['budget_ms'] == 15000}
    for r in valid:
        control = lookup.get((pair_key(r), controls[r['kind']]))
        r['control_savings_cents'] = None
        r['own_15s_improvement_cents'] = None
        r['pair_status'] = 'paired'
        if control is None:
            r['pair_status'] = 'unpaired_control'
        elif r['kind'] == 'booking' and r['served_indices'] != control['served_indices']:
            r['pair_status'] = 'different_served_customers'
        else:
            field = 'reference_cost' if r['kind'] == 'daily' else 'accepted_cost'
            if r.get(field) is not None and control.get(field) is not None:
                r['control_savings_cents'] = control[field] - r[field]
            else:
                r['pair_status'] = 'unavailable_cost'
        if r['kind'] == 'daily':
            own = fifteen.get((pair_key(r, False), r['solver']))
            if own and own.get('reference_cost') is not None and r.get('reference_cost') is not None:
                r['own_15s_improvement_cents'] = own['reference_cost'] - r['reference_cost']
    return valid, issues


def equal_seed_summary(rows, metric):
    by_seed = defaultdict(list)
    for row in rows:
        value = row.get(metric)
        if value is not None:
            by_seed[row['seed']].append(value)
    values = [mean(v) for v in by_seed.values()]
    return {'mean': mean(values) if values else None, 'min': min(values) if values else None,
        'max': max(values) if values else None, 'seeds': len(values),
        'observed_cases': sum(map(len, by_seed.values())), 'total_cases': len(rows),
        'seed_means': {str(k): mean(v) for k, v in sorted(by_seed.items())}}


def summary_groups(rows):
    groups = defaultdict(list)
    for r in rows:
        keys = ['kind', 'cohort', 'solver', 'fleet', 'workload', 'routing'] + (['budget_ms', 'fixture'] if r['kind'] == 'daily' else ['concurrency', 'cache', 'dates', 'requests'])
        groups[canonical({k: r[k] for k in keys})].append(r)
    summaries = []
    for key, items in sorted(groups.items()):
        summary = json.loads(key)
        metrics = ['reference_cost', 'accepted_cost', 'fairness', 'elapsed_ms', 'reference_ms', 'fairness_ms',
                   'reference_move_rate', 'fairness_move_rate',
                   'control_savings_cents', 'own_15s_improvement_cents'] if summary['kind'] == 'daily' else [
                   'served_rate', 'incomplete_rate', 'failed_rate', 'control_savings_cents']
        summary['metrics'] = {m: equal_seed_summary(items, m) for m in metrics}
        if summary['kind'] == 'booking':
            observations = [a['elapsed_ms'] for r in items for a in r['attempts'] if a['elapsed_ms'] is not None]
            summary['latency'] = {'observed': len(observations), 'total': sum(r['requests'] for r in items),
                'p50_ms': quantile(observations, .5), 'p95_ms': quantile(observations, .95), 'p99_ms': quantile(observations, .99)}
        summaries.append(summary)
    return summaries


def throughput_summary(rows):
    """Move evaluations per second by solver and fleet, so contention shows up. Missing rates stay missing."""
    groups = defaultdict(list)
    for r in rows:
        if r['kind'] == 'daily':
            groups[(r['solver'], r['fleet'])].append(r)
    result = []
    for (solver, fleet), items in sorted(groups.items()):
        entry = dict(solver=solver, fleet=fleet, total_cases=len(items))
        for phase in ('reference', 'fairness'):
            values = [r[f'{phase}_move_rate'] for r in items if r.get(f'{phase}_move_rate') is not None]
            entry[f'{phase}_move_rate'] = dict(observed=len(values), mean=mean(values) if values else None,
                                              min=min(values) if values else None, max=max(values) if values else None)
        result.append(entry)
    return result


def run_rows(run):
    """Completed daily rows of one run with the neighbor count recorded for each attempt; never writes."""
    from experiment_runtime import completed_attempt
    rows, issues = [], []
    for case in read_json(run / 'cases.json'):
        attempt = completed_attempt(run, case)
        if attempt is None:
            issues.append({'case': case['id'], 'reason': 'Missing completed case'})
            continue
        rs, errors = load_raw(attempt / 'raw.jsonl', expected=case)
        issues.extend(errors)
        receipt = read_json(attempt / 'completed.json')
        for row in rs:
            row['in_flight_min'] = receipt.get('in_flight_min')
            row['full_load_fraction'] = receipt.get('full_load_fraction')  # Absent before it was recorded.
        rows.extend(rs)
    return rows, issues


def fully_loaded(row, parallel_cases, share=.95):
    """Steady state: every slot busy for at least `share` of the case's wall time. Archives without the
    recorded share fall back to the fewest-neighbors count."""
    if row.get('full_load_fraction') is not None:
        return row['full_load_fraction'] >= share
    return row.get('in_flight_min') is not None and row['in_flight_min'] >= parallel_cases - 1


def contention_report(sequential_run, parallel_run, threshold=.95):
    """Pair identical daily cases across a sequential and a parallel run and compare solver throughput."""
    seq_rows, seq_issues = run_rows(sequential_run)
    par_rows, par_issues = run_rows(parallel_run)
    parallel_cases = read_json(parallel_run / 'config.json')['daily']['parallel_cases']
    def key(r):
        return canonical([r['solver'], r['seed'], r['fleet'], r['workload'], r['budget_ms'], r['fixture']])
    sequential = {key(r): r for r in seq_rows}
    pairs, excluded = defaultdict(list), Counter()
    for r in par_rows:
        other = sequential.get(key(r))
        if other is None:
            excluded['no_sequential_twin'] += 1
        elif not fully_loaded(r, parallel_cases):
            excluded['not_fully_loaded'] += 1  # Pool ramp-up and tail run with fewer neighbors.
        else:
            ratios = {phase: (r[f'{phase}_move_rate'] / other[f'{phase}_move_rate']
                              if r.get(f'{phase}_move_rate') is not None and other.get(f'{phase}_move_rate') else None)
                      for phase in ('reference', 'fairness')}
            if all(v is None for v in ratios.values()):
                excluded['throughput_unavailable'] += 1
            else:
                pairs[(r['solver'], r['fleet'])].append(ratios)
    groups = []
    for (solver, fleet), items in sorted(pairs.items()):
        entry = dict(solver=solver, fleet=fleet, pairs=len(items))
        for phase in ('reference', 'fairness'):
            values = [i[phase] for i in items if i[phase] is not None]
            entry[f'{phase}_median_ratio'] = median(values) if values else None
            entry[f'{phase}_pairs'] = len(values)
        groups.append(entry)
    decided = [g['reference_median_ratio'] for g in groups]
    accepted = bool(decided) and all(v is not None and v >= threshold for v in decided)
    return {'format': 1, 'sequential_run': str(sequential_run), 'parallel_run': str(parallel_run),
            'parallel_cases': parallel_cases, 'threshold': threshold, 'groups': groups,
            'excluded_pairs': dict(excluded), 'issues': seq_issues + par_issues,
            'accepted': accepted,
            'rule': 'Accepted when every solver and fleet median reference-phase throughput ratio is at least the threshold',
            'loaded_rule': 'A pair counts when every slot was busy for at least 95% of the parallel case wall time '
                           '(older archives: fewest neighbors at least parallel_cases - 1)'}


def plots(output, rows, controls, budgets):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    plt.rcParams.update({'font.size': 10, 'axes.spines.top': False, 'axes.spines.right': False,
                         'svg.fonttype': 'none', 'figure.dpi': 110})
    figures = []
    groups = defaultdict(list)
    for r in rows:
        scope = r['fixture'] if r['kind'] == 'daily' else canonical([r['dates'], r['requests']])
        groups[(r['kind'], r['cohort'], r['workload'], r['fleet'], r['routing'], scope)].append(r)
    def save(fig, title, caption):
        name = f'figure-{len(figures) + 1:03}'
        fig.savefig(output / f'{name}.png', bbox_inches='tight')
        fig.savefig(output / f'{name}.svg', bbox_inches='tight')
        plt.close(fig)
        figures.append({'name': name, 'title': title, 'caption': caption})
    for (kind, cohort, workload, fleet, routing, scope), items in sorted(groups.items()):
        title = f'{kind.title()} | {fleet} technicians | {workload} | {cohort}'
        if kind == 'daily':
            title += f'\nFixture {scope[:12]}'
        if kind == 'daily':
            metrics = [('reference_cost', 'Reference modeled cost (cents)'), ('accepted_cost', 'Accepted modeled cost (cents)'),
                ('control_savings_cents', f'Reference savings vs {controls["daily"]} (cents)'),
                ('own_15s_improvement_cents', 'Reference improvement vs own 15 s (cents)'),
                ('fairness', 'Accepted utilization variance'), ('elapsed_ms', 'Actual case runtime (ms)')]
            fig, axes = plt.subplots(3, 2, figsize=(14, 12), layout='constrained')
            fig.suptitle(title, fontsize=13)
            xs = sorted(set(budgets) | {r['budget_ms'] for r in items})
            for ax, (metric, label) in zip(axes.flat, metrics):
                available = False
                for variant_index, solver in enumerate(dict.fromkeys(r['solver'] for r in items)):
                    selected = [r for r in items if r['solver'] == solver]
                    color = COLORS[solver]
                    for seed_index, seed in enumerate(sorted({r['seed'] for r in selected})):
                        seed_rows = {r['budget_ms']: r for r in selected if r['seed'] == seed}
                        ax.plot([x / 1000 for x in xs], [seed_rows.get(x, {}).get(metric) if seed_rows.get(x, {}).get(metric) is not None else math.nan for x in xs],
                                color=color, alpha=.3, linewidth=.8, marker='.', markersize=4,
                                linestyle=['-', '--', ':', '-.'][seed_index % 4])
                    stats = [equal_seed_summary([r for r in selected if r['budget_ms'] == x], metric) for x in xs]
                    if not any(s['mean'] is not None for s in stats):
                        continue
                    available = True
                    ys = [s['mean'] if s['mean'] is not None else math.nan for s in stats]
                    lo = [s['min'] if s['min'] is not None else math.nan for s in stats]
                    hi = [s['max'] if s['max'] is not None else math.nan for s in stats]
                    counts = '/'.join(str(s['seeds']) for s in stats)
                    ax.plot([x / 1000 for x in xs], ys, color=color, linewidth=1.8,
                            marker=['o', 's', '^', 'D', 'v', 'P', 'X', '*'][variant_index % 8], markersize=4, label=f'{solver} n={counts}')
                    ax.fill_between([x / 1000 for x in xs], lo, hi, color=color, alpha=.08)
                ax.set(xlabel='Combined search budget (seconds)', ylabel=label)
                ax.set_xticks([x / 1000 for x in xs])
                if len(xs) == 1:
                    ax.set_xlim(max(0, xs[0] / 1000 - .25), xs[0] / 1000 + .25)
                ax.grid(alpha=.15)
                if available and metric.endswith('cents'):
                    ax.axhline(0, color='#555555', linewidth=.7)
                if available:
                    ax.legend(fontsize=7, loc='best')
                else:
                    ax.set_yticks([])
                    ax.text(.5, .5, 'Unavailable: no matched observations', ha='center', transform=ax.transAxes)
            save(fig, title, 'Synthetic directed fixtures. Thin traces are individual seeds; shaded range is observed min/max, not a confidence interval. Thick lines weight seeds equally. Legend n gives observed seed counts in ascending budget order. Five seeds are exploratory; seeds vary search, not geography. Missing points remain gaps. Costs are modeled cents, not payroll savings.')
        else:
            for concurrency, cache in sorted({(r['concurrency'], r['cache']) for r in items}):
                selected = [r for r in items if r['concurrency'] == concurrency and r['cache'] == cache]
                fig, axes = plt.subplots(1, 2, figsize=(13, 5), layout='constrained')
                subtitle = f'{title}\nConcurrency {concurrency}, {cache} cache | {items[0]["dates"][0]} to {items[0]["dates"][-1]}'
                fig.suptitle(subtitle, fontsize=12)
                solvers = sorted({r['solver'] for r in selected})
                for index, solver in enumerate(solvers):
                    rs = [r for r in selected if r['solver'] == solver]
                    color = COLORS[solver]
                    for shift, metric, marker in [(-.18, 'served_rate', 'o'), (0, 'incomplete_rate', 's'), (.18, 'failed_rate', '^')]:
                        s = equal_seed_summary(rs, metric)
                        axes[0].scatter([index + shift], [s['mean'] * 100], color=color, marker=marker, s=45)
                        axes[0].vlines(index + shift, s['min'] * 100, s['max'] * 100, color=color)
                    values = sorted(a['elapsed_ms'] for r in rs for a in r['attempts'] if a['elapsed_ms'] is not None)
                    if values:
                        axes[1].step([values[0], *values], [0, *[(i + 1) / len(values) * 100 for i in range(len(values))]],
                            where='post', color=color, linestyle=['-', '--', ':', '-.'][index % 4],
                            label=f'{solver}: n={len(values)}, seeds={len({r["seed"] for r in rs})}')
                rate_labels = [s.replace('_', '\n') + f'\n{sum(r["requests"] for r in selected if r["solver"] == s)} requests, {len({r["seed"] for r in selected if r["solver"] == s})} seeds' for s in solvers]
                axes[0].set(xticks=range(len(solvers)), xticklabels=rate_labels,
                    ylabel='Requests (%)', ylim=(-3, 103), title='Circle served; square incomplete; triangle failed')
                axes[1].set(xlabel='Request search observation (ms)', ylabel='Cumulative observations (%)',
                            title='Pooled request latency distribution', ylim=(0, 103))
                if axes[1].lines:
                    axes[1].legend(fontsize=8)
                else:
                    axes[1].text(.5, .5, 'Unavailable: no latency observations', ha='center', transform=axes[1].transAxes)
                for ax in axes:
                    ax.grid(alpha=.15)
                save(fig, subtitle, f'Booking road-provider evidence, routing identity {routing}. Rates use equal seed weighting; vertical lines show observed seed range. Served and incomplete may overlap and must not be stacked. Latencies pool individual requests, excluding unavailable or historically mixed search/selection timing. Cost comparisons require identical served request indices and fixture fingerprints. Provider cache is not reset; cold/warm refers to scheduler caches.')
    return figures


def analyze(run):
    from experiment_runtime import completed_attempt, sha, stamp, write_new
    output = run / 'analysis' / f'{stamp()}-{uuid.uuid4().hex[:8]}'
    output.mkdir(parents=True, exist_ok=False)
    rows, issues, inputs = [], [], []
    controls = {'daily': 'TABU', 'booking': 'INSERTION'}
    expected_count = None
    parallelism = None
    budgets = []
    if (run / 'import.json').exists():
        imported = read_json(run / 'import.json')
        for receipt in imported['files']:
            path = run / 'history' / receipt['name']
            if sha(path) != receipt['sha256']:
                raise ValueError('Imported historical evidence changed')
            inputs.append(receipt)
            if not path.name.endswith('.jsonl.gz'):
                continue
            # Explicit historical stage boundaries, never merge original, screen, held, stress or browser transports.
            match = re.match(r'booking-[a-z_]+-\d+-(.+)\.jsonl.gz$', path.name)
            cohort = 'historical-' + (match[1] if match else ('daily-held' if path.name.startswith('held-') else 'daily-screen'))
            rs, errors = load_raw(path, cohort=cohort)
            rows.extend(rs)
            issues.extend(errors)
    else:
        config = validate(read_json(run / 'config.json'))
        cases = read_json(run / 'cases.json')
        manifest = read_json(run / 'manifest.json')
        if manifest['config_hash'] != digest(config) or manifest['cases_hash'] != digest(cases) or cases != expand(config):
            raise ValueError('Saved configuration/matrix provenance mismatch')
        expected_count = len(cases)
        parallelism = manifest.get('parallelism')  # Absent in archives that predate parallel daily cases.
        for kind in controls:
            if kind in config:
                controls[kind] = config[kind]['control']
        budgets = [round(s * 1000) for s in config.get('daily', {}).get('budgets_seconds', [])]
        for case in cases:
            attempt = completed_attempt(run, case)
            if attempt is None:
                issues.append({'case': case['id'], 'reason': 'Missing completed case'})
            else:
                path = attempt / 'raw.jsonl'
                rs, errors = load_raw(path, expected=case)
                receipt = read_json(attempt / 'completed.json')
                for row in rs:
                    row['in_flight_min'] = receipt.get('in_flight_min')
                    row['full_load_fraction'] = receipt.get('full_load_fraction')
                rows.extend(rs)
                issues.extend(errors)
                inputs.append({'name': str(path.relative_to(run)), 'sha256': sha(path)})
            for old in (run / 'attempts' / case['id']).glob('*'):
                if old != attempt:
                    issues.append({'source': str(old), 'reason': 'Unfinished/failed attempt excluded; raw evidence retained'})
    rows, duplicate_issues = compare(rows, controls)
    issues.extend(duplicate_issues)
    summaries = summary_groups(rows)
    figures = plots(output, rows, controls, budgets)
    report = {'format': 1, 'expected_cases': expected_count, 'observed_cases': len(rows), 'controls': controls,
        'inputs': inputs, 'issues': issues, 'pair_status_counts': dict(Counter(r['pair_status'] for r in rows)),
        'summaries': summaries, 'throughput': throughput_summary(rows), 'parallelism': parallelism,
        'cases': rows, 'figures': figures,
        'limitations': ['Small-seed-count comparisons are exploratory, not statistical significance claims.',
            'Daily seeds vary search on fixed geography. Daily fixtures and booking provider evidence are separate.',
            'Missing measurements remain null. Unpaired control and missing own-15-second comparisons remain unavailable.',
            'Concurrent daily cases share memory bandwidth, L3 cache and boost clocks; check move evaluations per second before trusting parallel timings.',
            'Historical expected matrix is unavailable here; archive coverage is not a claim of a complete new experiment.']}
    write_new(output / 'summary.json', report)
    # Source and analysis version recorded with every invocation, including a changed analyzer.
    write_new(output / 'analysis-provenance.json', {'analyzer_sha256': sha(Path(__file__)), 'inputs': inputs, 'parallelism': parallelism})
    flat = [{k: v for k, v in r.items() if not isinstance(v, (list, dict))} for r in rows]
    with (output / 'cases.csv').open('x', newline='', encoding='utf-8') as stream:
        writer = csv.DictWriter(stream, sorted({k for r in flat for k in r}))
        writer.writeheader()
        writer.writerows(flat)
    with (output / 'summary.csv').open('x', newline='', encoding='utf-8') as stream:
        columns = ['kind', 'cohort', 'solver', 'fleet', 'workload', 'budget_ms', 'concurrency', 'cache', 'metric', 'mean', 'min', 'max', 'seeds', 'observed_cases', 'total_cases']
        writer = csv.DictWriter(stream, columns, extrasaction='ignore')
        writer.writeheader()
        for group in summaries:
            for metric, stats in group['metrics'].items():
                writer.writerow({**group, 'metric': metric, **stats})
    esc = html.escape
    parts = ['<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">',
        '<title>Scheduler experiment evidence</title><style>body{font:16px system-ui;max-width:1400px;margin:2rem auto;padding:0 1rem;color:#222}img{width:100%;height:auto}figure{margin:2rem 0}pre{white-space:pre-wrap}a{color:#2363a0}</style>',
        '<h1>Scheduler experiment evidence</h1>', f'<p>Observed cases: {len(rows)}. Expected: {expected_count if expected_count is not None else "unknown (historical import)"}. Issues: {len(issues)}.</p>',
        '<p><a href="summary.json">Summary JSON and completeness details</a> | <a href="summary.csv">Summary CSV</a> | <a href="cases.csv">Case CSV</a></p>',
        '<p>' + esc(' '.join(report['limitations'])) + '</p>',
        '<p>Pair status: ' + esc(json.dumps(report['pair_status_counts'])) + '</p>',
        '<p>Parallelism: ' + (esc(json.dumps(parallelism)) if parallelism else 'not recorded (sequential or historical evidence)') + '</p>',
        '<h2>Move evaluations per second</h2><pre>' + esc(json.dumps(report['throughput'], indent=2)) + '</pre>']
    for figure in figures:
        parts.append(f'<figure><h2>{esc(figure["title"])}</h2><a href="{figure["name"]}.svg">SVG</a><img loading="lazy" src="{figure["name"]}.png" alt="{esc(figure["title"])}"><figcaption>{esc(figure["caption"])}</figcaption></figure>')
    parts.extend(['<h2>Missing, failed and excluded evidence</h2><pre>', esc(json.dumps(issues, indent=2)), '</pre></html>'])
    (output / 'index.html').write_text('\n'.join(parts), encoding='utf-8')
    return output / 'index.html'
