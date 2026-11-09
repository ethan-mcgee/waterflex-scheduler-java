"""Overnight budget convergence check for the backtest simulation.

The overnight run starts each day from routes that already keep the rules (the booking built them). The dispatcher's
booked plan holds on few busy days (2024: 40 of 275 days hold, median 3.5 jobs), so each selected day first gets a
short repair solve, and every arm then solves from that same repaired start with the production daily calculation:

  screen-job  writes the backtest-screen job over one split's days (campaign-benchmark reads every day in one JVM)
  candidates  draws the candidate days once from the screen, weighted toward the busiest
  next-seed   prints the next candidate needing its start solve (or DONE), and turns finished start solves into starts
  plan        writes the selection: the days with a usable start, each with its arm order
  arms        prints the arms (and start-arm the start solve) as JSON for infra/run-backtest-convergence.ps1
  summarize   reads every arm's rows and writes evidence.json and report.md with the bars below

The arms and the bars are fixed here before the run; the summary reads them and never tunes them.
Stdlib only, as the other study scripts.
"""
import argparse
import datetime as dt
import json
import random
import shutil
import statistics
import sys
from pathlib import Path

import markdown_tables
from load_balance_study import interval

# The arms. The first is the production setting under study (the owner chose TABU_KOPT_SIZE_3, 10 s cost then 5 s
# fairness); the seed-18 repeat measures the seed's own noise; 30 s and 60 s keep the 2:1 cost to fairness split.
ARMS = (
    {'name': 'size3-15s', 'variant': 'TABU_KOPT_SIZE_3', 'costMs': 10000, 'fairnessMs': 5000, 'seed': 17},
    {'name': 'size3-15s-seed18', 'variant': 'TABU_KOPT_SIZE_3', 'costMs': 10000, 'fairnessMs': 5000, 'seed': 18},
    {'name': 'size3-30s', 'variant': 'TABU_KOPT_SIZE_3', 'costMs': 20000, 'fairnessMs': 10000, 'seed': 17},
    {'name': 'size3-60s', 'variant': 'TABU_KOPT_SIZE_3', 'costMs': 40000, 'fairnessMs': 20000, 'seed': 17},
    {'name': 'kopt-15s', 'variant': 'TABU_KOPT', 'costMs': 10000, 'fairnessMs': 5000, 'seed': 17},
)
BASE, NOISE, MIDDLE, LONG, REFERENCE = 'size3-15s', 'size3-15s-seed18', 'size3-30s', 'size3-60s', 'kopt-15s'
# Bars. Keep 15 s when the 60 s arm's median saving over it is below KEEP_BELOW of the day's delivered cost. Otherwise
# raise to 30 s when 30 s already saves KEEP_BELOW and at least half of what 60 s saves, else to 60 s.
KEEP_BELOW = 0.01
LARGE_SAVING = 0.02
# The selection: SELECTION_COUNT calibration days, drawn 60, 25 and 15 percent from the busiest, middle and lightest
# tercile of the calibration days by job count, each tercile in a fixed shuffled order with SELECTION_SEED. A candidate
# whose start cannot be made is passed over for the next one of its tercile.
SELECTION_COUNT = 40
SELECTION_SHARES = (('busiest', 0.60), ('middle', 0.25), ('lightest', 0.15))
SELECTION_SEED = 3
# The start: a short solve that turns the dispatcher's booked plan into routes that hold, as a booking would leave a
# day before its first overnight run. Its accepted proposal is the start; a booked plan that holds and is not improved
# is its own start; a day neither gives is not used.
START = {'name': 'start', 'variant': 'TABU_KOPT_SIZE_3', 'costMs': 1500, 'fairnessMs': 500, 'seed': 99}
START_FILES = ('facts.json', 'facts-actual-durations.json', 'as-executed.plan.json')
FILES = ('receipt.json', 'solve.json', 'as-planned.row.json', 'solver.row.json')


class ConvergenceError(ValueError):
    """A screen, selection or run the check refuses to read."""


def read_json(path):
    with Path(path).open(encoding='utf-8') as stream:
        return json.load(stream)


def write_json(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True) + '\n', encoding='utf-8')


def arm(name):
    for entry in ARMS:
        if entry['name'] == name:
            return entry
    raise ConvergenceError(f'Unknown arm {name}')


# ----------------------------------------------------------------------------------------------------------------------
# Screen and selection


def screen_job(run, split, output):
    """The backtest-screen job over every day of one split, in date order."""
    index = read_json(Path(run) / 'index.json')
    days = [entry['day'] for entry in index['days'] if entry['split'] == split]
    if not days:
        raise ConvergenceError(f'No {split} days in {run}')
    return {'version': 1, 'kind': 'backtest-screen', 'output': str(output),
            'days': [{'day': day, 'directory': str(Path(run) / 'days' / day)} for day in days]}


def screen_rows(screen):
    document = read_json(screen)
    if document.get('kind') != 'backtest-screen' or document.get('version') != 1:
        raise ConvergenceError('Not a version 1 backtest-screen document')
    rows = document.get('rows')
    if not isinstance(rows, list) or not rows:
        raise ConvergenceError('The screen has no rows')
    for row in rows:
        for name in ('day', 'feasible', 'visits', 'technicians', 'costCents'):
            if row.get(name) is None:
                raise ConvergenceError(f'Screen row lacks {name}: {row}')
        if not isinstance(row['feasible'], bool):
            raise ConvergenceError(f'Screen row feasible is not a boolean: {row}')
    return rows


def order(day_number):
    """Each day's arm order, rotated by one per day so no arm always runs first or last."""
    names = [entry['name'] for entry in ARMS]
    shift = day_number % len(names)
    return names[shift:] + names[:shift]


def candidates(screen, count=SELECTION_COUNT, seed=SELECTION_SEED):
    """Every screened day with work, in terciles by job count, each tercile in a fixed shuffled order, with its quota."""
    rows = screen_rows(screen)
    working = sorted((row for row in rows if row['visits'] > 0), key=lambda row: (row['visits'], row['day']))
    if len(working) < 3:
        raise ConvergenceError('Too few screened days with work')
    third = len(working) // 3
    groups = {'lightest': working[:third], 'middle': working[third:2 * third], 'busiest': working[2 * third:]}
    draw = random.Random(seed)
    terciles = {}
    for name, share in SELECTION_SHARES:
        group = groups[name]
        days = sorted(row['day'] for row in group)
        draw.shuffle(days)
        terciles[name] = {'quota': round(count * share), 'visits': [group[0]['visits'], group[-1]['visits']], 'order': days}
    return {'version': 1, 'screenRows': len(rows), 'holding': sum(1 for row in rows if row['feasible']), 'seed': seed, 'count': count,
            'days': {row['day']: {'visits': row['visits'], 'technicians': row['technicians'], 'bookedPlanHolds': row['feasible']} for row in working},
            'terciles': terciles}


def verdict(run, conv, day):
    """Turns a finished start solve into the day's start: the accepted proposal, else the booked plan when it holds."""
    seed = Path(conv) / 'seed' / day
    path = seed / 'start-verdict.json'
    if path.exists():
        return read_json(path)
    for name in FILES + ('solver.plan.json',):
        if not (seed / name).exists():
            raise ConvergenceError(f'{seed} lacks {name}')
    solve, planned, solver = read_json(seed / 'solve.json'), read_json(seed / 'as-planned.row.json'), read_json(seed / 'solver.row.json')
    if not isinstance(solve.get('accepted'), bool):
        raise ConvergenceError(f'{seed}: accepted is not a boolean')
    for row in (planned, solver):
        for name in ('feasible', 'unassigned', 'overtimeMinutes'):
            if row.get(name) is None:
                raise ConvergenceError(f'{seed}: a row lacks {name}')
    source, plan_file = None, None
    if solve['accepted'] and solver['feasible'] is True and solver['unassigned'] == 0 and solver['overtimeMinutes'] == 0:
        source, plan_file = 'proposal', seed / 'solver.plan.json'
    elif planned['feasible'] is True:
        source, plan_file = 'as-planned', Path(run) / 'days' / day / 'as-planned.plan.json'
    result = {'day': day, 'usable': source is not None, 'source': source, 'reason': solve.get('reason')}
    if source is not None:
        start = Path(conv) / 'starts' / day
        start.mkdir(parents=True, exist_ok=True)
        for name in START_FILES:
            shutil.copyfile(Path(run) / 'days' / day / name, start / name)
        shutil.copyfile(plan_file, start / 'as-planned.plan.json')
    write_json(path, result)
    return result


def next_seed(run, conv):
    """The next candidate whose start solve has not run, or None when every tercile has its quota or no candidate left."""
    chosen = read_json(Path(conv) / 'candidates.json')
    for name, _ in SELECTION_SHARES:
        tercile = chosen['terciles'][name]
        usable = 0
        for day in tercile['order']:
            if usable >= tercile['quota']:
                break
            if not (Path(conv) / 'seed' / day / 'receipt.json').exists():
                return day
            usable += verdict(run, conv, day)['usable']
    return None


def plan(run, conv):
    """The selection: per tercile the first usable starts up to its quota, in date order, each with its arm order."""
    chosen = read_json(Path(conv) / 'candidates.json')
    if next_seed(run, conv) is not None:
        raise ConvergenceError('Start solves are still owed; run next-seed until DONE first')
    picked, shortfall, unusable = [], {}, 0
    for name, _ in SELECTION_SHARES:
        tercile = chosen['terciles'][name]
        taken = []
        for day in tercile['order']:
            if len(taken) >= tercile['quota']:
                break
            result = verdict(run, conv, day)
            if result['usable']:
                taken.append((day, name, result['source']))
            else:
                unusable += 1
        shortfall[name] = tercile['quota'] - len(taken)
        picked.extend(taken)
    picked.sort()
    sources = {}
    for _, _, source in picked:
        sources[source] = sources.get(source, 0) + 1
    return {'version': 1, 'screenRows': chosen['screenRows'], 'holding': chosen['holding'], 'count': len(picked), 'seed': chosen['seed'],
            'start': START, 'sources': sources, 'unusable': unusable, 'shortfall': shortfall,
            'terciles': {name: {'quota': value['quota'], 'visits': value['visits']} for name, value in chosen['terciles'].items()},
            'arms': list(ARMS),
            'plan': [{'day': day, 'tercile': tercile, 'source': source, 'visits': chosen['days'][day]['visits'],
                      'technicians': chosen['days'][day]['technicians'], 'directory': str(Path(conv) / 'starts' / day), 'arms': order(number)}
                     for number, (day, tercile, source) in enumerate(picked)]}


# ----------------------------------------------------------------------------------------------------------------------
# Reading the run


def case(directory):
    """One arm's day: accepted, the delivered cost (the proposal when accepted, else the dispatcher's plan) and its checks."""
    for name in FILES:
        if not (directory / name).exists():
            raise ConvergenceError(f'{directory} lacks {name}')
    solve = read_json(directory / 'solve.json')
    planned = read_json(directory / 'as-planned.row.json')
    solver = read_json(directory / 'solver.row.json')
    accepted = solve.get('accepted')
    if not isinstance(accepted, bool):
        raise ConvergenceError(f'{directory}: accepted is not a boolean')
    if planned.get('feasible') is not True:
        raise ConvergenceError(f'{directory}: the as-planned plan does not hold, so the day should not have been selected')
    for row in (planned, solver):
        for name in ('costCents', 'driveMinutes', 'paidMinutes', 'feasible', 'unassigned', 'overtimeMinutes', 'latenessSeconds', 'pastShiftMinutes'):
            if row.get(name) is None:
                raise ConvergenceError(f'{directory}: a row lacks {name}')
    delivered = solver if accepted else planned
    violations = accepted and (not solver['feasible'] or solver['unassigned'] or solver['overtimeMinutes'] or solver['latenessSeconds'] or solver['pastShiftMinutes'])
    phases = solve.get('diagnostics', {}).get('phases')
    if not isinstance(phases, list) or not phases:
        raise ConvergenceError(f'{directory}: the solve has no phases')
    return {'accepted': accepted, 'reason': solve.get('reason'), 'costCents': delivered['costCents'], 'driveMinutes': delivered['driveMinutes'],
            'paidMinutes': delivered['paidMinutes'], 'baselineCents': planned['costCents'], 'violations': bool(violations),
            'solveMs': solve.get('solveMs'), 'budgetMs': sum(phase['statistics']['budgetMs'] for phase in phases)}


def load(conv, selection):
    rows = Path(conv) / 'rows'
    days = {}
    for entry in selection['plan']:
        days[entry['day']] = {name: case(rows / entry['day'] / name) for name in entry['arms']}
    return days


def saving(days, better, than):
    """Per day, what {@code better} saves over {@code than} as a share of {@code than}'s delivered cost (positive saves)."""
    values = []
    for day, arms in sorted(days.items()):
        base = arms[than]['costCents']
        if base <= 0:
            raise ConvergenceError(f'{day}: {than} delivered a non-positive cost')
        values.append((base - arms[better]['costCents']) / base)
    return values


def reading(values):
    return {'days': len(values), 'median': round(statistics.median(values), 5), 'interval': interval(values),
            'saves': sum(1 for v in values if v > 0), 'tie': sum(1 for v in values if v == 0), 'costs': sum(1 for v in values if v < 0),
            'largeSaving': sum(1 for v in values if v > LARGE_SAVING)}


def decide(longer, middle):
    if longer['median'] < KEEP_BELOW:
        return 'KEEP_15S'
    if middle['median'] >= KEEP_BELOW and middle['median'] >= longer['median'] / 2:
        return 'RAISE_TO_30S'
    return 'RAISE_TO_60S'


def summarize(conv, selection_path, out):
    selection = read_json(selection_path)
    days = load(conv, selection)
    names = [entry['name'] for entry in ARMS]
    per_arm = {name: {'accepted': sum(1 for arms in days.values() if arms[name]['accepted']),
                      'violations': sum(1 for arms in days.values() if arms[name]['violations']),
                      'medianSolveMs': statistics.median(arms[name]['solveMs'] for arms in days.values()),
                      'budgetMs': arm(name)['costMs'] + arm(name)['fairnessMs']} for name in names}
    savings = {name: reading(saving(days, name, BASE)) for name in names if name != BASE}
    noise = [abs(v) for v in saving(days, NOISE, BASE)]
    size3 = reading(saving(days, BASE, REFERENCE))
    valid = all(per_arm[name]['violations'] == 0 for name in names)
    result = {'generatedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'days': len(days), 'selection': {k: selection[k] for k in ('screenRows', 'holding', 'count', 'seed', 'terciles', 'sources', 'unusable', 'shortfall', 'start')},
              'bars': {'keepBelow': KEEP_BELOW, 'largeSaving': LARGE_SAVING}, 'arms': per_arm, 'savingsOverBase': savings,
              'noise': {'medianAbsolute': round(statistics.median(noise), 5), 'max': round(max(noise), 5)},
              'size3OverKopt': size3, 'size3CostlierFlag': size3['interval'] is not None and size3['interval'][1] < 0,
              'valid': valid, 'decision': decide(savings[LONG], savings[MIDDLE]) if valid else 'INVALID_RUN'}
    out = Path(out)
    write_json(out / 'evidence.json', result)
    (out / 'report.md').write_text(report(result), encoding='utf-8')
    return result


def percent(value):
    return 'n/a' if value is None else f'{value * 100:.2f}%'


def report(result):
    names = [entry['name'] for entry in ARMS]
    lines = ['# Overnight budget convergence check', '',
             f'Generated {result["generatedAt"][:19]}Z on {result["days"]} calibration days of {result["selection"]["screenRows"]} screened '
             f'({result["selection"]["holding"]} of them hold as booked), weighted toward the busiest. Every arm solved each day from the same start: '
             f'the accepted proposal of a {(START["costMs"] + START["fairnessMs"]) / 1000:g} s repair solve, or the booked plan when it holds and was not improved '
             f'(sources: {", ".join(f"{k} {v}" for k, v in sorted(result["selection"]["sources"].items()))}; {result["selection"]["unusable"]} candidates passed over). '
             f'Decision: `{result["decision"]}`.', '',
             '## Bars', '',
             f'- **Keep 15 s:** the 60 s arm saves less than {percent(KEEP_BELOW)} of the delivered cost on the median day.',
             f'- **Raise to 30 s:** otherwise, when the 30 s arm saves at least {percent(KEEP_BELOW)} and at least half of what 60 s saves.',
             '- **Raise to 60 s:** otherwise.',
             '- **Validity:** every accepted plan carries zero violations, overtime and unassigned jobs.',
             '- **Reported, not gated:** the seed noise (the 15 s arm against its seed-18 repeat) and `TABU_KOPT_SIZE_3` against `TABU_KOPT` at 15 s.', '',
             '## Arms', '',
             'Each arm\'s accepted days, accepted plans with violations, and median solve time against its budget.', '',
             markdown_tables.table(['Arm', 'Accepted', 'With violations', 'Median solve (ms)', 'Budget (ms)'],
                                   [[name, result['arms'][name]['accepted'], result['arms'][name]['violations'],
                                     result['arms'][name]['medianSolveMs'], result['arms'][name]['budgetMs']] for name in names]), '',
             '## Savings over the 15 s arm', '',
             'What each arm saves over `size3-15s` as a share of its delivered cost per day: median, 95 percent bootstrap interval, '
             f'and the days it saves, ties, costs more, and saves more than {percent(LARGE_SAVING)}.', '']
    rows = []
    for name, value in result['savingsOverBase'].items():
        bounds = value['interval']
        rows.append([name, percent(value['median']), 'n/a' if bounds is None else f'{percent(bounds[0])} to {percent(bounds[1])}',
                     value['saves'], value['tie'], value['costs'], value['largeSaving']])
    lines += [markdown_tables.table(['Arm', 'Median saving', '95% interval', 'Saves', 'Ties', 'Costs more', 'Large'], rows), '',
              '## Noise and tabu size', '',
              f'- **Seed noise:** the two 15 s seeds differ by a median of {percent(result["noise"]["medianAbsolute"])} of the delivered cost (largest {percent(result["noise"]["max"])}).',
              f'- **TABU_KOPT_SIZE_3 over TABU_KOPT at 15 s:** median saving {percent(result["size3OverKopt"]["median"])}'
              + ('' if result['size3OverKopt']['interval'] is None else f', interval {percent(result["size3OverKopt"]["interval"][0])} to {percent(result["size3OverKopt"]["interval"][1])}')
              + ('; flagged for the owner: size 3 costs more on this client.' if result['size3CostlierFlag'] else '.'), '']
    return '\n'.join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    commands = parser.add_subparsers(dest='command', required=True)
    job = commands.add_parser('screen-job')
    job.add_argument('--run', required=True)
    job.add_argument('--split', default='calibration', choices=('calibration', 'test'))
    job.add_argument('--screen', required=True, help='where the screen job writes its rows')
    job.add_argument('--out', required=True)
    chooser = commands.add_parser('candidates')
    chooser.add_argument('--screen', required=True)
    chooser.add_argument('--out', required=True)
    following = commands.add_parser('next-seed')
    following.add_argument('--run', required=True)
    following.add_argument('--conv', required=True)
    planner = commands.add_parser('plan')
    planner.add_argument('--run', required=True)
    planner.add_argument('--conv', required=True)
    planner.add_argument('--out', required=True)
    commands.add_parser('arms')
    commands.add_parser('start-arm')
    summary = commands.add_parser('summarize')
    summary.add_argument('--conv', required=True)
    summary.add_argument('--selection', required=True)
    summary.add_argument('--out', required=True)
    arguments = parser.parse_args(argv)
    try:
        if arguments.command == 'screen-job':
            write_json(arguments.out, screen_job(arguments.run, arguments.split, Path(arguments.screen).resolve()))
        elif arguments.command == 'candidates':
            if Path(arguments.out).exists():
                raise ConvergenceError(f'{arguments.out} exists; candidates are drawn once and never redrawn')
            write_json(arguments.out, candidates(arguments.screen))
        elif arguments.command == 'next-seed':
            day = next_seed(arguments.run, arguments.conv)
            print('DONE' if day is None else day)
        elif arguments.command == 'plan':
            if Path(arguments.out).exists():
                raise ConvergenceError(f'{arguments.out} exists; the selection is written once')
            chosen = plan(arguments.run, arguments.conv)
            write_json(arguments.out, chosen)
            print(f'{chosen["count"]} days selected, starts {chosen["sources"]}, {chosen["unusable"]} passed over, shortfall {chosen["shortfall"]}')
        elif arguments.command == 'arms':
            print(json.dumps(list(ARMS)))
        elif arguments.command == 'start-arm':
            print(json.dumps(START))
        else:
            result = summarize(arguments.conv, arguments.selection, arguments.out)
            print(f'{result["days"]} days, decision {result["decision"]}')
    except ConvergenceError as failure:
        print(f'error: {failure}', file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
