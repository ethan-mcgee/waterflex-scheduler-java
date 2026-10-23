"""Load balance head to head (PR J): Timefold's load balance against production's variance, at two budgets.

Arms, both from one frozen tree (main with L, M, W and A, plus the MIXED_SHIFT fixture):
  b             TABU_KOPT                 production: the capacity-weighted variance of utilization (option b, PR H)
  load-balance  TABU_KOPT_LOAD_BALANCE    Timefold's load balance over each technician's utilization, unweighted

Each arm searches and is accepted on its own measure (SolverBenchmark records load balance's in acceptedOn), and every
final plan records all five measures, so each arm is read on its own measure and on the other's.

  python infra/load_balance_study.py summarize --run <run directory> --out <evidence json>

The run directory is what infra/run-load-balance-study.ps1 writes:
rows/rep<r>-<budget>ms-<technicians>-<workload>-<fixtureSeed>-<seed>.jsonl and profiles/*.jfr. A case is one fleet, workload,
fixture seed and solver seed: the fixture seeds draw different days of the same shape, so the cases are different problems.

The rule for switching the daily policy to load balance, written before the run (owner to confirm before starting it).
It is read across cases, so it holds at any number of repetitions (a case's value is its mean over them). Load balance
replaces b only if, at every budget:
  variance   the 95 percent bootstrap interval of its median per-case variance ratio to b lies below 1, and it is less fair
             than b by more than TOLERANCE of b's variance in at most LESS_FAIR_ALLOWED of the cases
  maximum    its median per-case maximum utilization ratio to b is at most 1
  cost       its median per-case cost difference to b is at most COST_ALLOWANCE of b's cost
  ceiling    every accepted result of both arms inside its cost ceiling, and zero violations
Speed and convergence (time to best over the phase budget) are reported, not gated: the load balance collector is
maintained on every move, the cost phase included. With repetitions, each case's spread between b's runs is reported too.
"""
import argparse
import json
import random
import re
import shutil
import statistics
import sys
from decimal import Decimal
from pathlib import Path

from representation_study import attribute, check, jfr_document, merge, rate, spread

ARMS = {'TABU_KOPT': 'b', 'TABU_KOPT_LOAD_BALANCE': 'load-balance'}
ORDER = ('b', 'load-balance')
ACCEPTED_ON = {'b': None, 'load-balance': 'loadBalance'}
MEASURES = ('variance', 'maximumUtilization', 'utilizationSpread', 'absoluteDeviation', 'loadBalance')
PHASES = ('referencePhase', 'fairnessPhase')
ACCEPTED = {'OVERTIME_REDUCTION', 'FAIRNESS_IMPROVEMENT', 'COST_REDUCTION'}
ROW_FILE = re.compile(r'rep(\d+)-(\d+)ms-(\d+)-([A-Z_]+)-(\d+)-(\d+)\.jsonl')
JFR_FILE = re.compile(r'(\d+)-([A-Z_]+)-(\d+)-([A-Z_0-9]+)-(\d+)-(cost|fairness)\.jfr')
# The switching rule's two numbers (see the module text).
LESS_FAIR_ALLOWED = Decimal('0.10')
TOLERANCE = Decimal('0.05')
COST_ALLOWANCE = Decimal('0.001')
RESAMPLES = 10_000


def read_rows(path):
    match = ROW_FILE.fullmatch(path.name)
    check(match is not None, 'unexpected row file name: ' + path.name)
    rep, budget, technicians, workload, fixture, seed = int(match[1]), int(match[2]), int(match[3]), match[4], int(match[5]), int(match[6])
    # Measures are exact decimals in the JSON; keep them so.
    lines = [json.loads(line, parse_float=Decimal) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]
    check(lines and lines[0].get('type') == 'provenance', path.name + ': provenance first')
    check(lines[0].get('budgetMs') == budget, path.name + ': budget does not match its file')
    check(not any(line.get('type') == 'diagnostics-failure' for line in lines), path.name + ': diagnostics failure')
    rows = []
    for line in lines:
        if line.get('type') != 'result':
            continue
        check(line.get('technicians') == technicians and line.get('workload') == workload and line.get('fixtureSeed') == fixture
              and line.get('seed') == seed, path.name + ': row does not match its file')
        arm = ARMS.get(line.get('variant'))
        check(arm is not None, path.name + ': unexpected variant ' + str(line.get('variant')))
        check(line.get('acceptedOn') == ACCEPTED_ON[arm], path.name + ': ' + arm + ' accepted on the wrong measure')
        for plan in ('reference', 'after'):
            check(sorted(line.get('measures', {}).get(plan, {})) == sorted(MEASURES), path.name + ': every ' + plan + ' measure expected for ' + arm)
        check(isinstance(line.get('violations'), int), path.name + ': violations required')
        rows.append({'arm': arm, 'budget': budget, 'rep': rep, 'case': (technicians, workload, fixture, seed), 'row': line, 'revision': lines[0]['revision']})
    check(sorted(row['arm'] for row in rows) == sorted(ORDER), path.name + ': each arm expected once')
    return rows


def measure(row, name, plan='after'):
    value = row['measures'][plan][name]
    check(isinstance(value, (Decimal, int)) and not isinstance(value, bool), plan + ' ' + name + ' must be a number')
    return Decimal(value)


def cost(row):
    value = row['after'].get('costCents')
    check(isinstance(value, int) and not isinstance(value, bool), 'after costCents must be an integer')
    return value


def within_ceiling(row):
    """An accepted result must be inside its ceiling; a rejected one keeps the starting schedule, which the ceiling does not bind."""
    if row['acceptanceReason'] not in ACCEPTED:
        return True
    ceiling = row.get('costCeilingCents')
    check(isinstance(ceiling, int), 'an accepted result needs its cost ceiling')
    return cost(row) <= ceiling


def percent(share):
    return f'{(share * 100).normalize():f}'


def mean(values):
    return sum(values, Decimal(0)) / len(values)


def interval(values):
    """The 95 percent bootstrap interval of the median, resampling cases with a fixed seed so a rerun gives the same reading."""
    if len(values) < 2:
        return None
    draw = random.Random(0)
    medians = sorted(statistics.median(draw.choices(values, k=len(values))) for _ in range(RESAMPLES))
    return [round(medians[int(RESAMPLES * 0.025)], 3), round(medians[int(RESAMPLES * 0.975) - 1], 3)]


def ratio_reading(by, cases, reps, name, keep=lambda case: True, bootstrap=False):
    """Load balance against b on one measure: per case the mean over repetitions, then the median ratio, and the cases
    lower or higher, higher by more than TOLERANCE, and (with repetitions) higher beyond b's own spread between them."""
    ratios, lower, higher, beyond_tolerance, beyond_noise, zero = [], 0, 0, 0, 0, 0
    for case in cases:
        if not keep(case):
            continue
        base = [measure(by[('b', case, rep)], name) for rep in reps]
        arm = mean([measure(by[('load-balance', case, rep)], name) for rep in reps])
        reference = mean(base)
        # A case where b's value is zero has no ratio; it is counted, never read as a ratio of zero or one.
        if reference > 0:
            ratios.append(float(arm / reference))
        else:
            zero += 1
        lower += arm < reference
        higher += arm > reference
        beyond_tolerance += arm > reference * (1 + TOLERANCE)
        beyond_noise += arm - reference > max(base) - min(base)
    reading = {'medianRatioToB': round(statistics.median(ratios), 3) if ratios else None, 'lower': lower, 'higher': higher,
               'higherBeyondTolerance': beyond_tolerance, 'higherBeyondNoise': beyond_noise if len(reps) > 1 else None, 'casesWithZeroB': zero}
    if bootstrap:
        reading['medianRatioInterval'] = interval(ratios)
    return reading


def cost_reading(by, cases, reps, keep=lambda case: True):
    differences, relative = [], []
    for case in cases:
        if not keep(case):
            continue
        base = mean([Decimal(cost(by[('b', case, rep)])) for rep in reps])
        difference = mean([Decimal(cost(by[('load-balance', case, rep)])) for rep in reps]) - base
        differences.append(difference)
        check(base > 0, 'b must have a positive cost in ' + repr(case))
        relative.append(difference / base)
    return {'medianDifferenceCents': float(statistics.median(differences)), 'meanDifferenceCents': round(float(mean(differences)), 1),
            'medianRelativeDifference': float(round(statistics.median(relative), 6)),
            'cheaper': sum(1 for value in differences if value < 0), 'ties': sum(1 for value in differences if value == 0),
            'dearer': sum(1 for value in differences if value > 0)}


def paired(rows):
    by = {}
    for item in rows:
        key = (item['arm'], item['case'], item['rep'])
        check(key not in by, 'duplicate row: ' + repr(key))
        by[key] = item['row']
    cases = sorted({item['case'] for item in rows})
    reps = sorted({item['rep'] for item in rows})
    for case in cases:
        for rep in reps:
            for arm in ORDER:
                check((arm, case, rep) in by, 'missing ' + arm + ' for ' + repr(case) + ' rep ' + str(rep))
    violations = {arm: sum(by[(arm, case, rep)]['violations'] for case in cases for rep in reps) for arm in ORDER}
    outside = sorted(key[0] + ' ' + repr(key[1]) + ' rep ' + str(key[2]) for key, row in by.items() if not within_ceiling(row))
    measures = {name: ratio_reading(by, cases, reps, name, bootstrap=True) for name in MEASURES}
    costs = cost_reading(by, cases, reps)
    fleets = sorted({case[0] for case in cases})
    workloads = sorted({case[1] for case in cases})
    order = [case for case in cases for _ in reps]
    speedups = {phase: [rate(by[('load-balance', case, rep)], phase) / rate(by[('b', case, rep)], phase) for case in cases for rep in reps] for phase in PHASES}
    speed = {phase: {'all': spread(values),
                     'byFleet': {str(fleet): spread([value for value, case in zip(values, order) if case[0] == fleet]) for fleet in fleets}}
             for phase, values in speedups.items()}
    by_fleet = {str(fleet): {'variance': ratio_reading(by, cases, reps, 'variance', lambda case: case[0] == fleet),
                             'maximumUtilization': ratio_reading(by, cases, reps, 'maximumUtilization', lambda case: case[0] == fleet),
                             'cost': cost_reading(by, cases, reps, lambda case: case[0] == fleet)} for fleet in fleets}
    by_workload = {workload: {'variance': ratio_reading(by, cases, reps, 'variance', lambda case: case[1] == workload),
                              'maximumUtilization': ratio_reading(by, cases, reps, 'maximumUtilization', lambda case: case[1] == workload),
                              'cost': cost_reading(by, cases, reps, lambda case: case[1] == workload)} for workload in workloads}
    acceptance = {arm: {} for arm in ORDER}
    for (arm, _, _), row in by.items():
        acceptance[arm][row['acceptanceReason']] = acceptance[arm].get(row['acceptanceReason'], 0) + 1
    variance = measures['variance']
    rule = {'variance': variance['medianRatioInterval'] is not None and variance['medianRatioInterval'][1] < 1
                        and variance['higherBeyondTolerance'] <= LESS_FAIR_ALLOWED * len(cases),
            'maximum': measures['maximumUtilization']['medianRatioToB'] is not None and measures['maximumUtilization']['medianRatioToB'] <= 1,
            'cost': Decimal(str(costs['medianRelativeDifference'])) <= COST_ALLOWANCE,
            'ceiling': not outside and sum(violations.values()) == 0}
    return {'cases': len(cases), 'repetitions': len(reps), 'violations': violations, 'outsideCeiling': outside,
            'measures': measures, 'cost': costs, 'speed': speed, 'convergence': convergence(by), 'byFleet': by_fleet, 'byWorkload': by_workload,
            'acceptance': {arm: dict(sorted(value.items())) for arm, value in acceptance.items()},
            'rule': rule, 'passes': all(rule.values()), 'runs': runs(by)}


def convergence(by):
    """Per arm and phase: the median share of the phase budget spent before its best solution, and how often Timefold
    gave no time to best (counted, never read as zero). A share near 1 says the search was still improving."""
    result = {}
    for arm in ORDER:
        result[arm] = {}
        for phase in PHASES:
            shares, unavailable = [], 0
            for (name, _, _), row in by.items():
                if name != arm:
                    continue
                statistics_ = row.get(phase)
                check(isinstance(statistics_, dict), phase + ' missing')
                best, budget = statistics_.get('timeToBestMs'), statistics_.get('budgetMs')
                check(isinstance(budget, int) and budget > 0, phase + ' needs a positive budget')
                if best is None:
                    unavailable += 1
                else:
                    check(isinstance(best, int) and best >= 0, phase + ' time to best must be a whole number of milliseconds')
                    shares.append(best / budget)
            result[arm][phase] = {'medianShareOfBudget': round(statistics.median(shares), 3) if shares else None,
                                  'overNinetyPercent': sum(1 for share in shares if share > 0.9), 'unavailable': unavailable}
    return result


def runs(by):
    """Every run of both arms, for figures and per-case reading."""
    return [{'arm': arm, 'technicians': case[0], 'workload': case[1], 'fixtureSeed': case[2], 'seed': case[3], 'rep': rep,
             'referenceCostCents': row['reference']['costCents'], 'costCents': cost(row), 'costCeilingCents': row.get('costCeilingCents'),
             'acceptanceReason': row['acceptanceReason'],
             'referenceMovesPerSecond': round(rate(row, 'referencePhase'), 1), 'fairnessMovesPerSecond': round(rate(row, 'fairnessPhase'), 1),
             'referenceMeasures': {name: str(measure(row, name, 'reference')) for name in MEASURES},
             'measures': {name: str(measure(row, name)) for name in MEASURES}}
            for (arm, case, rep), row in sorted(by.items(), key=lambda item: (ORDER.index(item[0][0]), item[0][1], item[0][2]))]


def profiles(directory, jfr):
    """Per arm and phase: the merged attribution of every recording, overall and by fleet."""
    grouped = {}
    for path in sorted(directory.glob('*.jfr')):
        match = JFR_FILE.fullmatch(path.name)
        check(match is not None, 'unexpected recording name: ' + path.name)
        arm = ARMS.get(match[4])
        check(arm is not None, 'unexpected recording: ' + path.name)
        grouped.setdefault((arm, match[6], int(match[1])), []).append(attribute(jfr_document(path, jfr)))
    result = {}
    for arm, phase in sorted({(arm, phase) for arm, phase, _ in grouped}):
        fleets = sorted(fleet for a, p, fleet in grouped if (a, p) == (arm, phase))
        result[arm + ' ' + phase] = {'all': merge([part for fleet in fleets for part in grouped[(arm, phase, fleet)]]),
                                     'byFleet': {str(fleet): merge(grouped[(arm, phase, fleet)]) for fleet in fleets}}
    return result


def summarize(run, output, jfr):
    check(not output.exists(), 'evidence output must be new: ' + str(output))
    row_files = sorted((run / 'rows').glob('*.jsonl'))
    check(row_files, 'no row files under ' + str(run / 'rows'))
    rows = [row for path in row_files for row in read_rows(path)]
    # PowerShell 5.1 writes UTF-8 with a byte order mark.
    manifest = json.loads((run / 'manifest.json').read_text(encoding='utf-8-sig'))
    budgets = sorted({row['budget'] for row in rows})
    by_budget = {str(budget): paired([row for row in rows if row['budget'] == budget]) for budget in budgets}
    decision = {'switch': all(value['passes'] for value in by_budget.values()),
                'rule': ('at every budget: the 95 percent bootstrap interval of the median variance ratio to b below 1, with at most '
                         + percent(LESS_FAIR_ALLOWED) + ' percent of cases less fair by more than ' + percent(TOLERANCE) + ' percent;'
                         ' median maximum utilization ratio at most 1; median cost difference at most ' + percent(COST_ALLOWANCE)
                         + ' percent of b\'s cost; every accepted result inside its ceiling with zero violations'),
                'note': 'the rule informs the owner\'s decision; a change of fairness policy is the owner\'s'}
    document = {'study': 'Load balance head to head (PR J)', 'manifest': manifest,
                'revisions': sorted({item['revision'] for item in rows}), 'budgets': by_budget, 'decision': decision}
    if (run / 'profiles').is_dir() and any((run / 'profiles').glob('*.jfr')):
        document['profiles'] = profiles(run / 'profiles', jfr)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(document, indent=1, default=str) + '\n', encoding='utf-8')
    return document


def main(arguments):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest='command', required=True)
    command = commands.add_parser('summarize')
    command.add_argument('--run', required=True, type=Path)
    command.add_argument('--out', required=True, type=Path)
    command.add_argument('--jfr', default=shutil.which('jfr') or 'jfr')
    options = parser.parse_args(arguments)
    document = summarize(options.run, options.out, options.jfr)
    print(json.dumps({'decision': document['decision'],
                      'budgets': {budget: {key: value[key] for key in ('measures', 'cost', 'speed', 'convergence', 'acceptance', 'rule', 'passes')}
                                  for budget, value in document['budgets'].items()}}, indent=1, default=str))


if __name__ == '__main__':
    main(sys.argv[1:])
