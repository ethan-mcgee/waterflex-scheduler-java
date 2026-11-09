"""Pair the backtest's plans day by day and write the evidence and the report.

Reads a run directory (one rule set's variants/<rule>/ from infra/backtest_import.py): the importer's index and
ledger, the harness rows (rows/<day>/seed<s>-rep<n>/*.row.json and solve.json from campaign-benchmark's backtest-day
job), the API answers (api/<day>/rep<n>.json and .row.json from backtest_replay.py) and the travel calibration. Every
reading is paired per day; a day missing one of its plans is refused rather than read partly, and a null measure is
never read as zero.

The pre-registered bars split the days in two (owner, 2026-11-04). Efficiency: on the days the dispatcher's booked plan
holds under the rule set, the delivered plan against the as-planned one, both keeping the rules. Repair: on the days it
does not hold, the share the delivered plan turns into a valid one, with what that costs beside the violations removed.

The solver side of every comparison is the delivered plan: the proposal when the production calculation accepted it,
and the dispatcher's own plan when it did not, because that is what the product would have handed the dispatcher. A
candidate the calculation rejected (one that drops a job, say) never counts as a saving.

    python infra/backtest_summary.py summarize --run <run dir>/variants/<rule> --out research/backtest-<client>-<date>/<rule>/
    python infra/backtest_summary.py summarize-all --run <run dir> --out research/backtest-<client>-<date>/
    python infra/backtest_summary.py days --run <run dir> --select all|calibration|test|smoke|sample:<n>|noise:<n>|<dates>

Stdlib only.
"""
import argparse
import datetime as dt
import json
import random
import shutil
import statistics
import sys
from collections import defaultdict
from pathlib import Path

import markdown_tables
from load_balance_study import interval

MEASURES = [('driveMinutes', 'drive minutes'), ('meters', 'meters'), ('paidMinutes', 'paid minutes'), ('costCents', 'cost cents'),
            ('pastShiftMinutes', 'past-shift minutes'), ('latenessSeconds', 'lateness seconds'), ('lateVisits', 'late visits'),
            ('fairnessVariance', 'fairness variance'), ('bufferSeconds', 'buffer seconds')]
PLANS = {'as-planned': 'as-planned.row.json', 'as-planned-actual': 'as-planned-actual.row.json', 'as-executed': 'as-executed.row.json',
         'solver': 'solver.row.json', 'solver-actual': 'solver-actual.row.json', 'as-executed-actual': 'as-executed-actual.row.json'}
EVIDENCE = Path(__file__).resolve().parent.parent / 'research' / 'evidence'
RUNS = Path(__file__).resolve().parent.parent / 'experiments' / 'runs'
SAMPLE_SEED = 2
NOISE_SEED = 1
SMOKE_DAYS = 10


class SummaryError(ValueError):
    """A run the summary refuses to read."""


# ----------------------------------------------------------------------------------------------------------------------
# Day selection (shared with the runner and the replay)


def load_index(run):
    with (Path(run) / 'index.json').open(encoding='utf-8') as stream:
        return json.load(stream)


def stratified(entries, count, seed):
    """{@code count} days spread over (year, technician count) strata in proportion, drawn with a fixed seed."""
    if count >= len(entries):
        return [entry['day'] for entry in entries]
    strata = defaultdict(list)
    for entry in entries:
        strata[(entry['year'], entry['technicians'])].append(entry['day'])
    draw = random.Random(seed)
    chosen = []
    keys = sorted(strata)
    shares = {key: len(strata[key]) * count / len(entries) for key in keys}
    for key in keys:
        take = int(shares[key])
        chosen.extend(draw.sample(sorted(strata[key]), take))
    remaining = sorted(set(entry['day'] for entry in entries) - set(chosen))
    chosen.extend(draw.sample(remaining, count - len(chosen)))
    return sorted(chosen)


def select_days(run, spec):
    """The days a selection names, in date order."""
    index = load_index(run)
    entries = index['days']
    if spec == 'all':
        return [entry['day'] for entry in entries]
    if spec in ('calibration', 'test'):
        return [entry['day'] for entry in entries if entry['split'] == spec]
    if spec == 'smoke':
        return [entry['day'] for entry in entries][:SMOKE_DAYS]
    if spec.startswith('sample:') or spec.startswith('noise:'):
        kind, _, count = spec.partition(':')
        return stratified([entry for entry in entries if entry['split'] == 'test'], int(count), SAMPLE_SEED if kind == 'sample' else NOISE_SEED)
    known = {entry['day'] for entry in entries}
    days = []
    for value in spec.split(','):
        day = value.strip()
        if day not in known:
            raise SummaryError(f'Unknown day {day}')
        days.append(day)
    return sorted(days)


# ----------------------------------------------------------------------------------------------------------------------
# Reading the run


def read_json(path):
    with Path(path).open(encoding='utf-8') as stream:
        return json.load(stream)


def measure(row, name):
    value = row.get(name)
    if value is None:
        raise SummaryError(f'Row lacks {name}')
    if name == 'fairnessVariance':
        return float(value)
    return value


def harness_rows(run):
    """{day: {(seed, rep): {'plans': {name: row}, 'solve': solve document}}} for every day with harness output."""
    rows = Path(run) / 'rows'
    result = defaultdict(dict)
    if not rows.exists():
        return result
    for day_directory in sorted(rows.iterdir()):
        for case in sorted(day_directory.iterdir()):
            if not (case / 'receipt.json').exists():
                continue
            name = case.name
            seed, _, rep = name.partition('-rep')
            plans = {}
            for plan, file in PLANS.items():
                path = case / file
                if not path.exists():
                    raise SummaryError(f'{day_directory.name} {name} lacks {file}')
                plans[plan] = read_json(path)
            solve = read_json(case / 'solve.json')
            accepted = solve.get('accepted')
            if not isinstance(accepted, bool):
                raise SummaryError(f'{day_directory.name} {name} solve.json lacks its decision')
            plans['delivered'] = plans['solver'] if accepted else plans['as-planned']
            plans['delivered-actual'] = plans['solver-actual'] if accepted else plans['as-planned-actual']
            result[day_directory.name][(int(seed.removeprefix('seed')), int(rep))] = {'plans': plans, 'solve': solve}
    return result


def api_answers(run):
    """{day: {rep: {'answer': ..., 'row': row or None}}}."""
    api = Path(run) / 'api'
    result = defaultdict(dict)
    if not api.exists():
        return result
    for day_directory in sorted(api.iterdir()):
        for answer in sorted(day_directory.glob('rep*.json')):
            if answer.name.endswith('.plan.json') or answer.name.endswith('.row.json') or answer.name.count('.') > 1:
                continue
            rep = int(answer.stem.removeprefix('rep'))
            row = day_directory / f'rep{rep}.row.json'
            result[day_directory.name][rep] = {'answer': read_json(answer), 'row': read_json(row) if row.exists() else None}
    return result


# ----------------------------------------------------------------------------------------------------------------------
# Pairing


def primary_case(cases):
    """The primary solve of a day: the lowest seed's first repetition."""
    return cases[min(cases)]


def differences(days, entries, left, right, name):
    values = []
    for entry in entries:
        case = primary_case(days[entry['day']])
        values.append(measure(case['plans'][left], name) - measure(case['plans'][right], name))
    return values


def reading(values):
    """Median, bootstrap interval and counts for paired differences (negative is the left plan doing less)."""
    if not values:
        return {'days': 0, 'median': None, 'interval': None, 'better': 0, 'tie': 0, 'worse': 0}
    return {'days': len(values), 'median': round(statistics.median(values), 3), 'interval': interval(values),
            'better': sum(1 for v in values if v < 0), 'tie': sum(1 for v in values if v == 0), 'worse': sum(1 for v in values if v > 0)}


def groupings(entries):
    """Named subsets of the days for the split tables."""
    groups = [('all', entries)]
    groups += [(f'year {year}', [e for e in entries if e['year'] == year]) for year in sorted({e['year'] for e in entries})]
    groups += [(f'quarter {q}', [e for e in entries if (int(e['day'][5:7]) - 1) // 3 + 1 == q]) for q in (1, 2, 3, 4)]
    technicians = sorted({e['technicians'] for e in entries})
    for count in technicians:
        groups.append((f'{count} technicians', [e for e in entries if e['technicians'] == count]))
    jobs = sorted(e['jobs'] for e in entries)
    if len(jobs) >= 3:
        low, high = jobs[len(jobs) // 3], jobs[2 * len(jobs) // 3]
        groups += [(f'jobs under {low}', [e for e in entries if e['jobs'] < low]), (f'jobs {low} to {high - 1}', [e for e in entries if low <= e['jobs'] < high]),
                   (f'jobs {high} and over', [e for e in entries if e['jobs'] >= high])]
    groups += [('sales-call days', [e for e in entries if e['salesCallDay']]), ('days without a sales call', [e for e in entries if not e['salesCallDay']])]
    return [(name, group) for name, group in groups if group]


def comparisons(days, entries):
    """Every paired reading, by comparison, group and measure."""
    pairs = [('delivered vs as-executed', 'delivered', 'as-executed'), ('delivered vs as-planned', 'delivered', 'as-planned'),
             ('played forward: delivered vs as-executed', 'delivered-actual', 'as-executed-actual')]
    result = {}
    for label, left, right in pairs:
        result[label] = {}
        for group, members in groupings(entries):
            result[label][group] = {name: reading(differences(days, members, left, right, name)) for name, _ in MEASURES}
    return result


def validity(days, entries, config):
    """The pre-registered validity readings over the primary solves."""
    violations, refused, accepted, reasons = 0, 0, 0, defaultdict(int)
    late_worse = 0
    for entry in entries:
        case = primary_case(days[entry['day']])
        solver = case['plans']['solver']
        if case['solve']['accepted'] and (solver['latenessSeconds'] > 0 or solver['pastShiftMinutes'] > 0 or solver['overtimeMinutes'] > 0
                                          or solver['unassigned'] > 0 or not solver['feasible']):
            violations += 1
        baseline = case['plans']['as-planned']
        if not baseline['feasible']:
            refused += 1
        accepted += case['solve']['accepted']
        reasons[case['solve']['reason']] += 1
        if case['plans']['delivered-actual']['lateVisits'] > case['plans']['as-executed-actual']['lateVisits']:
            late_worse += 1
    return {'acceptedPlansWithViolations': violations, 'baselinesThatDoNotHold': refused, 'accepted': accepted, 'days': len(entries),
            'reasons': dict(sorted(reasons.items())), 'playedForwardLateWorse': late_worse}


def agreement(days, api, entries):
    """The API's answer against the harness decision on the same day, for the days the replay reached."""
    rows = []
    agreed = compared = 0
    for entry in entries:
        answers = api.get(entry['day'])
        if not answers:
            continue
        answer = answers[min(answers)]
        case = primary_case(days[entry['day']]) if entry['day'] in days else None
        status = answer['answer']['status']
        decision = answer['answer']['body'].get('decision') if status == 201 else answer['answer']['body'].get('error')
        harness = case['solve']['accepted'] if case else None
        baseline_holds = case['plans']['as-planned']['feasible'] if case else None
        verdict = 'refused' if status != 201 else ('agreed' if (decision == 'IMPROVED') == harness else 'differed')
        if status == 201 and baseline_holds:
            compared += 1
            agreed += verdict == 'agreed'
        api_drive = measure(answer['row'], 'driveMinutes') if answer['row'] else None
        rows.append({'day': entry['day'], 'status': status, 'decision': decision, 'harnessAccepted': harness, 'baselineHolds': baseline_holds,
                     'verdict': verdict, 'apiDriveMinutes': api_drive, 'harnessDriveMinutes': case['plans']['delivered']['driveMinutes'] if case else None})
    counts = defaultdict(int)
    for row in rows:
        counts[row['verdict']] += 1
    return {'days': len(rows), 'counts': dict(counts), 'comparedWithHoldingBaseline': compared, 'agreedWithHoldingBaseline': agreed,
            'agreementShare': round(agreed / compared, 4) if compared else None, 'rows': rows}


def noise(days, entries):
    """Per-day spread of the delivered plan's drive minutes across seeds and repetitions, for days solved more than once."""
    spreads = []
    for entry in entries:
        cases = days[entry['day']]
        if len(cases) < 2:
            continue
        values = [case['plans']['delivered']['driveMinutes'] for case in cases.values()]
        spreads.append(statistics.pstdev(values))
    return {'days': len(spreads), 'medianSd': round(statistics.median(spreads), 3) if spreads else None,
            'maxSd': round(max(spreads), 3) if spreads else None}


def wilson(successes, total, z=1.96):
    """The 95 percent Wilson score interval of a share, or None without cases."""
    if total == 0:
        return None
    share = successes / total
    centre = share + z * z / (2 * total)
    spread = z * ((share * (1 - share) + z * z / (4 * total)) / total) ** 0.5
    scale = 1 + z * z / total
    return [round((centre - spread) / scale, 4), round((centre + spread) / scale, 4)]


def holds(case):
    return case['plans']['as-planned']['feasible']


def repaired(case):
    solver = case['plans']['solver']
    return case['solve']['accepted'] and solver['feasible'] and solver['unassigned'] == 0


def efficiency(days, entries):
    """Delivered minus as-planned per measure on the days the dispatcher's booked plan holds: both plans keep the rules."""
    holding = [entry for entry in entries if holds(primary_case(days[entry['day']]))]
    return {'days': len(holding), 'measures': {name: reading(differences(days, holding, 'delivered', 'as-planned', name)) for name, _ in MEASURES}}


def repair(days, entries):
    """On the days the dispatcher's booked plan does not hold: how many the delivered plan makes valid, what that costs
    against the as-planned plan, and the violations the as-planned plan carried on those days."""
    failing = [entry for entry in entries if not holds(primary_case(days[entry['day']]))]
    fixed = [entry for entry in failing if repaired(primary_case(days[entry['day']]))]
    carried = lambda name: [measure(primary_case(days[entry['day']])['plans']['as-planned'], name) for entry in fixed]
    return {'days': len(failing), 'repaired': len(fixed), 'share': round(len(fixed) / len(failing), 4) if failing else None,
            'interval': wilson(len(fixed), len(failing)),
            'cost': {name: reading(differences(days, fixed, 'delivered', 'as-planned', name)) for name in ('driveMinutes', 'paidMinutes', 'costCents')},
            'removed': {name: {'total': sum(carried(name)), 'median': statistics.median(carried(name)) if fixed else None}
                        for name in ('lateVisits', 'latenessSeconds', 'pastShiftMinutes')}}


def bars(result, config):
    """The pre-registered bars read against the test split."""
    split = result['split'].get('test')
    if split is None:
        return {'evaluated': False}
    drive, paid = split['efficiency']['measures']['driveMinutes'], split['efficiency']['measures']['paidMinutes']
    primary = drive['interval'] is not None and drive['interval'][1] < 0 and paid['interval'] is not None and paid['interval'][1] <= 0
    # The repair bar is the study's own number, written into its config before the run; without it the bar is not read.
    minimum = config.get('bars', {}).get('repairShareMin')
    share = split['repair']['share']
    repair_ok = None if minimum is None or share is None else share >= minimum
    validity_ok = result['validity']['test']['acceptedPlansWithViolations'] == 0
    forward_ok = result['validity']['test']['playedForwardLateWorse'] == 0
    agreement_share = result['agreement']['agreementShare']
    agreement_ok = agreement_share is not None and agreement_share >= 0.9
    effect = abs(drive['median']) if drive['median'] is not None else None
    sd = result['noise']['medianSd']
    noise_ok = effect is not None and sd is not None and effect > 0 and sd < effect / 3
    return {'evaluated': True, 'efficiencyDriveAndPaid': primary, 'efficiencyDays': split['efficiency']['days'], 'driveMedian': drive['median'],
            'repairShare': repair_ok, 'repairShareValue': share, 'repairShareMin': minimum, 'repairDays': split['repair']['days'],
            'solverPlansClean': validity_ok, 'playedForwardNotWorse': forward_ok, 'apiAgreement': agreement_ok, 'noiseBelowAThird': noise_ok,
            'apiAgreementShare': agreement_share, 'noiseMedianSd': sd}


# ----------------------------------------------------------------------------------------------------------------------
# Summary and report


def summarize(run, out):
    run, out = Path(run), Path(out)
    index = load_index(run)
    config = read_json(run / 'study-config.json')
    days = harness_rows(run)
    if not days:
        raise SummaryError(f'No harness rows under {run}/rows')
    entries = [entry for entry in index['days'] if entry['day'] in days]
    missing = [entry['day'] for entry in index['days'] if entry['day'] not in days]
    api = api_answers(run)
    # The calibration reads the whole history once, at the run root above the rule sets.
    calibration = next((path for path in (run / 'travel-calibration.json', run.parent.parent / 'travel-calibration.json') if path.exists()), None)
    result = {'runId': index['runId'], 'ruleSet': index.get('ruleSet'), 'primary': index.get('primary'),
              'routingIdentity': index['routingIdentity'], 'mapVersion': index.get('mapVersion'),
              'generatedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'config': config,
              'days': {'solved': len(entries), 'imported': len(index['days']), 'missing': missing,
                       'bySplit': {split: sum(1 for e in entries if e['split'] == split) for split in ('calibration', 'test')}},
              'ledger': index['ledger'], 'holidays': index['holidays'], 'technicians': index['technicians'],
              'comparisons': {}, 'validity': {}, 'split': {}, 'agreement': agreement(days, api, entries), 'noise': noise(days, entries),
              'travelCalibration': read_json(calibration) if calibration is not None else None,
              'routingCoverage': {str(year): sum(e['unreachableLegs'] for e in entries if e['year'] == year) for year in sorted({e['year'] for e in entries})}}
    for split in ('calibration', 'test'):
        members = [e for e in entries if e['split'] == split]
        if not members:
            continue
        result['validity'][split] = validity(days, members, config)
        result['split'][split] = {'efficiency': efficiency(days, members), 'repair': repair(days, members)}
        for label, groups in comparisons(days, members).items():
            result['comparisons'].setdefault(label, {})
            for group, readings in groups.items():
                result['comparisons'][label][f'{group} {split}' if group == 'all' else f'{group} ({split})'] = readings
    result['bars'] = bars(result, config)
    out.mkdir(parents=True, exist_ok=True)
    with (out / 'evidence.json').open('w', encoding='utf-8', newline='\n') as stream:
        json.dump(result, stream, indent=1, sort_keys=True)
        stream.write('\n')
    (out / 'report.md').write_text(report(result), encoding='utf-8', newline='\n')
    # Only a study run under this checkout's experiments/runs/ files its evidence; a test's temporary run never does.
    if RUNS in run.resolve().parents and EVIDENCE.parent.exists():
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(out / 'evidence.json', EVIDENCE / f'{index["runId"]}.json')
    protocol = out / 'protocol.md'
    template = Path(__file__).resolve().parent / 'backtest-protocol.template.md'
    if not protocol.exists() and template.exists():
        shutil.copyfile(template, protocol)
    return result


def summarize_all(run, out):
    """Every rule set of a run that has harness rows, each into out/<rule>/, and the cross-variant table."""
    run, out = Path(run), Path(out)
    manifest = read_json(run / 'run.json')
    results = {}
    for rule, entry in manifest['ruleSets'].items():
        directory = run / entry['directory']
        if not (directory / 'rows').exists():
            continue
        results[rule] = summarize(directory, out / rule)
    if not results:
        raise SummaryError(f'No rule set under {run} has harness rows yet')
    rows = []
    for rule, result in results.items():
        split = result['split'].get('test')
        drive, paid = (split['efficiency']['measures']['driveMinutes'], split['efficiency']['measures']['paidMinutes']) if split else (None, None)
        repair_share = split['repair']['share'] if split else None
        validity = result['validity'].get('test', {})
        held = result['ledger']['heldOut']
        ci = lambda r: 'not measured' if r is None or r['interval'] is None else f'{fmt(r["interval"][0])} to {fmt(r["interval"][1])}'
        rows.append([rule, 'primary' if rule == manifest['primaryRuleSet'] else 'sensitivity', result['days']['solved'],
                     f'{held["rows"]} ({held["salesCalls"]} sales)', fmt(drive['median']) if drive else 'not measured', ci(drive),
                     fmt(paid['median']) if paid else 'not measured',
                     'not measured' if repair_share is None else f'{fmt(repair_share * 100)} percent',
                     f'{validity["accepted"]} of {validity["days"]}' if validity else 'not measured'])
    overview = {'runId': manifest['runId'], 'primaryRuleSet': manifest['primaryRuleSet'], 'ruleSets': {rule: {
        'bars': result['bars'], 'days': result['days'], 'heldOut': result['ledger']['heldOut'],
        'split': result['split'].get('test')} for rule, result in results.items()}}
    out.mkdir(parents=True, exist_ok=True)
    with (out / 'evidence.json').open('w', encoding='utf-8', newline='\n') as stream:
        json.dump(overview, stream, indent=1, sort_keys=True)
        stream.write('\n')
    lines = [f'# Backtest across shift rules: {manifest["runId"]}', '',
             f'Each rule set pairs the delivered plan with the dispatcher\'s executed plan under the same rules; plans are never compared across rule sets. `{manifest["primaryRuleSet"]}` carries the pre-registered bars and the others are reported sensitivities. The readings below are the test split: efficiency is delivered minus as-planned per day on the days the dispatcher\'s booked plan holds (negative is less), and repair is the share of the other days the delivered plan makes valid.', '',
             markdown_tables.table(['Rule set', 'Role', 'Days', 'Held out', 'Drive median (min)', 'Drive 95 percent interval', 'Paid median (min)', 'Repaired', 'Accepted'], rows), '',
             'Each rule set\'s full report is in its own folder beside this one.', '']
    (out / 'report.md').write_text('\n'.join(lines), encoding='utf-8', newline='\n')
    return overview


def fmt(value, places=1):
    if value is None:
        return 'not measured'
    return f'{value:,.{places}f}' if isinstance(value, float) else f'{value:,}'


def reading_row(name, label, readings):
    r = readings[name]
    ci = 'not measured' if r['interval'] is None else f'{fmt(r["interval"][0])} to {fmt(r["interval"][1])}'
    return [label, r['days'], fmt(r['median']), ci, r['better'], r['tie'], r['worse']]


def report(result):
    rule = result.get('ruleSet')
    role = 'the gated primary' if result.get('primary') else 'a reported sensitivity'
    lines = [f'# Backtest: {result["runId"]}', '',
             f'Generated {result["generatedAt"][:19]}Z from routing identity `{result["routingIdentity"]}`. {result["days"]["solved"]} of {result["days"]["imported"]} imported days were solved'
             + (f'; missing: {len(result["days"]["missing"])}.' if result['days']['missing'] else '.')
             + (f' Shift rule `{rule}`, {role}.' if rule else ''), '',
             'The solver side of every table is the delivered plan: the proposal when the production calculation accepted it, the dispatcher\'s own plan when it did not.', '']
    b = result['bars']
    lines += ['## Pre-registered bars', '']
    if not b['evaluated']:
        lines += ['The test split has no solved days yet, so no bar was read.', '']
    else:
        lines += ['The bars from the protocol, read against the test split:', '',
                  markdown_tables.table(['Bar', 'Result', 'Reading'], [
                      ['Efficiency: on holding days, drive minutes below zero and paid minutes not worse', 'met' if b['efficiencyDriveAndPaid'] else 'missed',
                       f'{b["efficiencyDays"]} days, median drive {fmt(b["driveMedian"])} min'],
                      ['Repair: share of non-holding days made valid', 'not read' if b['repairShare'] is None else ('met' if b['repairShare'] else 'missed'),
                       f'{b["repairDays"]} days, {fmt(b["repairShareValue"], 3) if b["repairShareValue"] is not None else "not measured"} against {b["repairShareMin"] if b["repairShareMin"] is not None else "no bar set"}'],
                      ['Validity: accepted plans carry no lateness, past-shift, overtime or unassigned job', 'met' if b['solverPlansClean'] else 'missed', ''],
                      ['Validity: played forward, late visits not worse than as executed', 'met' if b['playedForwardNotWorse'] else 'missed', ''],
                      ['Validity: API agrees with the harness on at least 90 percent of holding days', 'met' if b['apiAgreement'] else 'missed', fmt(b['apiAgreementShare'], 3) if b['apiAgreementShare'] is not None else 'not measured'],
                      ['Validity: noise SD below a third of the primary effect', 'met' if b['noiseBelowAThird'] else 'missed', fmt(b['noiseMedianSd']) if b['noiseMedianSd'] is not None else 'not measured']]), '']
    for split, reading_ in result['split'].items():
        e, r = reading_['efficiency'], reading_['repair']
        lines += [f'## Efficiency and repair, {split} split', '',
                  f'Efficiency: delivered minus as-planned on the {e["days"]} days the dispatcher\'s booked plan holds:', '',
                  markdown_tables.table(['Measure', 'Days', 'Median', '95 percent interval', 'Better', 'Tie', 'Worse'],
                                        [reading_row(name, label, e['measures']) for name, label in MEASURES if e['measures'][name]['days'] > 0] or [['none', 0, 'n/a', 'n/a', 0, 0, 0]]), '']
        interval_text = 'not measured' if r['interval'] is None else f'{fmt(r["interval"][0] * 100)} to {fmt(r["interval"][1] * 100)} percent'
        share_text = 'not measured' if r['share'] is None else f'{fmt(r["share"] * 100)} percent'
        lines += [f'Repair: the dispatcher\'s booked plan does not hold on {r["days"]} days; the delivered plan is valid on {r["repaired"]} ({share_text}, Wilson 95 percent interval {interval_text}). On those days, delivered minus as-planned:', '',
                  markdown_tables.table(['Measure', 'Days', 'Median', '95 percent interval', 'Better', 'Tie', 'Worse'],
                                        [reading_row(name, name, r['cost']) for name in ('driveMinutes', 'paidMinutes', 'costCents') if r['cost'][name]['days'] > 0] or [['none', 0, 'n/a', 'n/a', 0, 0, 0]]), '',
                  'The violations the as-planned plans carried on the repaired days, which the delivered plans remove:', '',
                  markdown_tables.table(['Violation', 'Total', 'Median per day'], [[name, r['removed'][name]['total'], fmt(r['removed'][name]['median'])] for name in r['removed']]), '']
    for label, groups in result['comparisons'].items():
        lines += [f'## {label[0].upper()}{label[1:]}', '', 'Per-day paired differences (left plan minus right plan; negative is the left plan doing less), the median with its 95 percent bootstrap interval, and the days the left plan was better, tied or worse:', '']
        for name, measure_label in MEASURES:
            rows = [reading_row(name, group, readings) for group, readings in groups.items() if readings[name]['days'] > 0]
            if not rows:
                continue
            lines += [f'### {measure_label[0].upper()}{measure_label[1:]}', '', markdown_tables.table(['Group', 'Days', 'Median', '95 percent interval', 'Better', 'Tie', 'Worse'], rows), '']
    lines += ['## Validity by split', '']
    for split, v in result['validity'].items():
        lines += [f'The {split} split, {v["days"]} days: {v["accepted"]} proposals accepted, {v["acceptedPlansWithViolations"]} accepted plans with a violation, {v["baselinesThatDoNotHold"]} as-planned baselines that do not hold under the road model (the shipped API would refuse these), {v["playedForwardLateWorse"]} days where the delivered plan played forward has more late visits than the executed plan played forward.', '',
                  'Harness decisions by reason:', '', markdown_tables.table(['Reason', 'Days'], [[reason, count] for reason, count in v['reasons'].items()]), '']
    a = result['agreement']
    lines += ['## API agreement', '', f'{a["days"]} days were replayed through the public API; {a["agreedWithHoldingBaseline"]} of {a["comparedWithHoldingBaseline"]} days with a holding baseline agreed with the harness decision.', '']
    if a['counts']:
        lines += [markdown_tables.table(['Verdict', 'Days'], [[k, v] for k, v in sorted(a['counts'].items())]), '']
    n = result['noise']
    lines += ['## Noise', '', f'{n["days"]} days were solved more than once; the median per-day standard deviation of the solver\'s drive minutes is {fmt(n["medianSd"])} (max {fmt(n["maxSd"])}).', '']
    t = result['travelCalibration']
    lines += ['## Travel calibration', '']
    if t is None:
        lines += ['No travel calibration file in the run.', '']
    else:
        o = t['overall']
        lines += [f'On the {t["split"]} split, {o["gaps"]} logged gaps between consecutive jobs: the median gap over the road leg is {fmt(o["ratio"]["median"], 2)}x, and {fmt(o["shorterThanLegShare"] * 100 if o["shorterThanLegShare"] is not None else None)} percent of gaps are shorter than the leg itself. Calibrated travel buffer: {t["calibrated"]["travelBufferPct"] if t["calibrated"] else "none reached the target"}.', '']
    held = result['ledger'].get('heldOut')
    held_text = f' The shift rule held {held["rows"]} more out of every plan ({held["salesCalls"]} of them sales calls) because no technician could finish them inside the shift.' if held else ''
    lines += ['## Exclusion ledger', '', f'{result["ledger"]["kept"]} of {result["ledger"]["rows"]} rows kept; {result["ledger"]["excludedRows"]} excluded.{held_text}', '',
              markdown_tables.table(['Reason', 'Severity', 'Rows'], [[reason, severity, count] for severity in ('exclude', 'flag') for reason, count in result['ledger']['reasons'][severity].items()]), '']
    lines += ['## Routing coverage', '', markdown_tables.table(['Year', 'Unreachable legs'], [[year, count] for year, count in result['routingCoverage'].items()]), '']
    return '\n'.join(lines).rstrip('\n') + '\n'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    s = commands.add_parser('summarize')
    s.add_argument('--run', required=True)
    s.add_argument('--out', required=True)
    a = commands.add_parser('summarize-all')
    a.add_argument('--run', required=True)
    a.add_argument('--out', required=True)
    d = commands.add_parser('days')
    d.add_argument('--run', required=True)
    d.add_argument('--select', default='all')
    args = parser.parse_args(argv)
    if args.command == 'days':
        for day in select_days(args.run, args.select):
            print(day)
        return 0
    if args.command == 'summarize-all':
        overview = summarize_all(args.run, args.out)
        print(json.dumps({rule: entry['bars'] for rule, entry in overview['ruleSets'].items()}))
        return 0
    result = summarize(args.run, args.out)
    print(json.dumps({'solved': result['days']['solved'], 'bars': result['bars']}))
    return 0


if __name__ == '__main__':
    sys.exit(main())
