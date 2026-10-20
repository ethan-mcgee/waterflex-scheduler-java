"""Fairness measure study (PR 187): two variance formulas and four fairness policies against production, at each budget.

Arms, all from one frozen tree (bench/variance-formula), all on the nanosecond timing that #186 promoted:
  baseline      TABU_KOPT                       production: DECIMAL128 variance over the whole fleet on every move
  b             TABU_KOPT_BINARY_VARIANCE       the same variance in binary64, carried as rounded units of 1e-9
  c             TABU_KOPT_INCREMENTAL_VARIANCE  the same variance from three exact running sums the solver updates per move
  min-max       TABU_KOPT_MIN_MAX_UTILIZATION   the highest technician utilization
  spread        TABU_KOPT_UTILIZATION_SPREAD    the highest minus the lowest utilization
  abs-dev       TABU_KOPT_ABSOLUTE_DEVIATION    the capacity-weighted mean absolute deviation of utilization
  load-balance  TABU_KOPT_LOAD_BALANCE          Timefold's load balance over utilization, unweighted

b and c change only how the production variance is computed, so they are accepted on it and held to the bars below.
The four policies change what fairness means: each searches and is accepted on its own measure, and every final plan
records all five measures, so they get a comparison table, not bars.

  python infra/variance_study.py summarize --run <run directory> --out <evidence json>

The run directory is what infra/run-variance-study.ps1 writes: rows/rep<r>-<budget>ms-<technicians>-<workload>-<seed>.jsonl
and profiles/*.jfr.

The bars for b and c, set before the run (owner decision 2026-10-20), applied at every budget:
  speed     median fairness-phase moves per second at least 1.25x the baseline
  fairness  no case less fair than the baseline beyond that case's own spread between repetitions
  cost      median final cost (arm minus baseline, per case) not above zero; a case may be dearer if it is fairer
  ceiling   every accepted result inside its cost ceiling, and zero violations
"""
import argparse
import json
import re
import shutil
import statistics
import sys
from decimal import Decimal
from pathlib import Path

from representation_study import attribute, check, jfr_document, merge, rate, spread

ARMS = {'TABU_KOPT': 'baseline', 'TABU_KOPT_BINARY_VARIANCE': 'b', 'TABU_KOPT_INCREMENTAL_VARIANCE': 'c',
        'TABU_KOPT_MIN_MAX_UTILIZATION': 'min-max', 'TABU_KOPT_UTILIZATION_SPREAD': 'spread',
        'TABU_KOPT_ABSOLUTE_DEVIATION': 'abs-dev', 'TABU_KOPT_LOAD_BALANCE': 'load-balance'}
TREATMENTS = ('b', 'c')
POLICIES = ('min-max', 'spread', 'abs-dev', 'load-balance')
# The measure each policy is accepted on, as SolverBenchmark records it in acceptedOn.
ACCEPTED_ON = {'min-max': 'maximumUtilization', 'spread': 'utilizationSpread', 'abs-dev': 'absoluteDeviation', 'load-balance': 'loadBalance'}
MEASURES = ('variance', 'maximumUtilization', 'utilizationSpread', 'absoluteDeviation', 'loadBalance')
PHASES = ('referencePhase', 'fairnessPhase')
ACCEPTED = {'OVERTIME_REDUCTION', 'FAIRNESS_IMPROVEMENT', 'COST_REDUCTION'}
ROW_FILE = re.compile(r'rep(\d+)-(\d+)ms-(\d+)-([A-Z_]+)-(\d+)\.jsonl')
JFR_FILE = re.compile(r'(\d+)-([A-Z_]+)-(\d+)-([A-Z_0-9]+)-(\d+)-(cost|fairness)\.jfr')
SPEED_BAR = 1.25


def read_rows(path):
    match = ROW_FILE.fullmatch(path.name)
    check(match is not None, 'unexpected row file name: ' + path.name)
    rep, budget, technicians, workload, seed = int(match[1]), int(match[2]), int(match[3]), match[4], int(match[5])
    # Variances are exact decimals in the JSON; keep them so.
    lines = [json.loads(line, parse_float=Decimal) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]
    check(lines and lines[0].get('type') == 'provenance', path.name + ': provenance first')
    check(lines[0].get('budgetMs') == budget, path.name + ': budget does not match its file')
    check(not any(line.get('type') == 'diagnostics-failure' for line in lines), path.name + ': diagnostics failure')
    rows = []
    for line in lines:
        if line.get('type') != 'result':
            continue
        check(line['technicians'] == technicians and line['workload'] == workload and line['seed'] == seed, path.name + ': row does not match its file')
        arm = ARMS.get(line['variant'])
        check(arm is not None, path.name + ': unexpected variant ' + str(line['variant']))
        check(line.get('acceptedOn') == ACCEPTED_ON.get(arm), path.name + ': ' + arm + ' accepted on the wrong measure')
        check(sorted(line.get('measures', {}).get('after', {})) == sorted(MEASURES), path.name + ': every measure expected for ' + arm)
        rows.append({'arm': arm, 'budget': budget, 'rep': rep, 'case': (technicians, workload, seed), 'row': line, 'revision': lines[0]['revision']})
    check(sorted(row['arm'] for row in rows) == sorted(ARMS.values()), path.name + ': every arm expected once')
    return rows


def variance(row):
    value = row['after']['fairness']['variance']
    check(isinstance(value, (Decimal, int)), 'variance must be a number')
    return Decimal(value)


def measure(row, name):
    return stage(row, 'after', name)


def stage(row, plan, name):
    value = row['measures'][plan][name]
    check(isinstance(value, (Decimal, int)), plan + ' ' + name + ' must be a number')
    return Decimal(value)


def within_ceiling(row):
    """An accepted result must be inside its ceiling; a rejected one keeps the starting schedule, which the ceiling does not bind."""
    if row['acceptanceReason'] not in ACCEPTED:
        return True
    return row['after']['costCents'] <= row['costCeilingCents']


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
            for arm in ARMS.values():
                check((arm, case, rep) in by, 'missing ' + arm + ' for ' + repr(case) + ' rep ' + str(rep))
    violations = sum(row['violations'] for row in by.values())
    outside = sorted(key[0] + ' ' + repr(key[1]) + ' rep ' + str(key[2]) for key, row in by.items() if not within_ceiling(row))
    summary, pairs = {}, []
    for arm in TREATMENTS:
        speedups = {phase: [] for phase in PHASES}
        cost_differences, fairer, same, less_fair, less_fair_beyond_noise = [], 0, 0, 0, 0
        for case in cases:
            base_variance = [variance(by[('baseline', case, rep)]) for rep in reps]
            arm_variance = [variance(by[(arm, case, rep)]) for rep in reps]
            noise = max(base_variance) - min(base_variance)
            gap = sum(arm_variance) / len(reps) - sum(base_variance) / len(reps)
            if gap < 0:
                fairer += 1
            elif gap == 0:
                same += 1
            else:
                less_fair += 1
                if gap > noise:
                    less_fair_beyond_noise += 1
            base_cost = [by[('baseline', case, rep)]['after']['costCents'] for rep in reps]
            arm_cost = [by[(arm, case, rep)]['after']['costCents'] for rep in reps]
            difference = statistics.mean(arm_cost) - statistics.mean(base_cost)
            cost_differences.append(difference)
            for rep in reps:
                for phase in PHASES:
                    speedups[phase].append(rate(by[(arm, case, rep)], phase) / rate(by[('baseline', case, rep)], phase))
            pairs.append({'arm': arm, 'technicians': case[0], 'workload': case[1], 'seed': case[2],
                          'baselineVariance': [str(value) for value in base_variance], 'armVariance': [str(value) for value in arm_variance],
                          'baselineVarianceSpread': str(noise), 'meanVarianceDifference': str(gap),
                          'baselineCostCents': base_cost, 'armCostCents': arm_cost, 'meanCostDifferenceCents': difference})
        ratios = [float(sum(variance(by[(arm, case, rep)]) for rep in reps) / sum(variance(by[('baseline', case, rep)]) for rep in reps))
                  for case in cases if sum(variance(by[('baseline', case, rep)]) for rep in reps) > 0]
        summary[arm] = {'referencePhaseSpeedup': spread(speedups['referencePhase']), 'fairnessPhaseSpeedup': spread(speedups['fairnessPhase']),
                        'fairness': {'fairer': fairer, 'same': same, 'lessFair': less_fair, 'lessFairBeyondNoise': less_fair_beyond_noise,
                                     'medianVarianceRatio': round(statistics.median(ratios), 3) if ratios else None},
                        'cost': {'medianDifferenceCents': statistics.median(cost_differences), 'meanDifferenceCents': round(statistics.mean(cost_differences), 1),
                                 'cheaper': sum(1 for value in cost_differences if value < 0), 'ties': sum(1 for value in cost_differences if value == 0),
                                 'dearer': sum(1 for value in cost_differences if value > 0)}}
        arm_outside = [entry for entry in outside if entry.startswith(arm + ' ')]
        summary[arm]['bars'] = {'speed': summary[arm]['fairnessPhaseSpeedup']['median'] >= SPEED_BAR,
                                'fairness': less_fair_beyond_noise == 0,
                                'cost': statistics.median(cost_differences) <= 0,
                                'ceiling': not arm_outside and all(by[(arm, case, rep)]['violations'] == 0 for case in cases for rep in reps)}
        summary[arm]['passes'] = all(summary[arm]['bars'].values())
    return {'cases': len(cases), 'repetitions': len(reps), 'violations': violations, 'outsideCeiling': outside, 'summary': summary,
            'comparison': comparison(by, cases, reps), 'pairs': pairs, 'runs': runs(by)}


def runs(by):
    """Every arm's every run, for figures and per-case reading: the reference and final cost, ceiling, reason, phase speeds,
    and all five measures of the reference and final plans."""
    return [{'arm': arm, 'technicians': case[0], 'workload': case[1], 'seed': case[2], 'rep': rep,
             'referenceCostCents': row['reference']['costCents'], 'costCents': row['after']['costCents'],
             'costCeilingCents': row['costCeilingCents'], 'acceptanceReason': row['acceptanceReason'],
             'referenceMovesPerSecond': round(rate(row, 'referencePhase'), 1), 'fairnessMovesPerSecond': round(rate(row, 'fairnessPhase'), 1),
             'referenceMeasures': {name: str(stage(row, 'reference', name)) for name in MEASURES},
             'measures': {name: str(measure(row, name)) for name in MEASURES}}
            for (arm, case, rep), row in sorted(by.items(), key=lambda item: (list(ARMS.values()).index(item[0][0]), item[0][1], item[0][2]))]


def comparison(by, cases, reps):
    """Every arm on every measure: the median over cases of each case's mean and, against the baseline, the median ratio
    and the cases where the arm is lower or higher; with the cost difference, the fairness-phase speed and the reasons."""
    table = {}
    for arm in ARMS.values():
        entry = {'acceptedOn': ACCEPTED_ON.get(arm, 'variance'), 'measures': {}}
        for name in MEASURES:
            means = [sum(measure(by[(arm, case, rep)], name) for rep in reps) / len(reps) for case in cases]
            base = [sum(measure(by[('baseline', case, rep)], name) for rep in reps) / len(reps) for case in cases]
            ratios = [float(value / reference) for value, reference in zip(means, base) if reference > 0]
            entry['measures'][name] = {'median': str(statistics.median(means)),
                                       'medianRatioToBaseline': round(statistics.median(ratios), 3) if ratios else None,
                                       'lowerThanBaseline': sum(1 for value, reference in zip(means, base) if value < reference),
                                       'higherThanBaseline': sum(1 for value, reference in zip(means, base) if value > reference)}
        differences = [statistics.mean(by[(arm, case, rep)]['after']['costCents'] for rep in reps)
                       - statistics.mean(by[('baseline', case, rep)]['after']['costCents'] for rep in reps) for case in cases]
        entry['cost'] = {'medianDifferenceCents': statistics.median(differences), 'cheaper': sum(1 for value in differences if value < 0),
                         'dearer': sum(1 for value in differences if value > 0)}
        entry['fairnessPhaseSpeedup'] = spread([rate(by[(arm, case, rep)], 'fairnessPhase') / rate(by[('baseline', case, rep)], 'fairnessPhase')
                                                for case in cases for rep in reps])
        reasons = {}
        for case in cases:
            for rep in reps:
                reason = by[(arm, case, rep)]['acceptanceReason']
                reasons[reason] = reasons.get(reason, 0) + 1
        entry['acceptance'] = dict(sorted(reasons.items()))
        table[arm] = entry
    return table


def profiles(directory, jfr):
    grouped = {}
    for path in sorted(directory.glob('*.jfr')):
        match = JFR_FILE.fullmatch(path.name)
        check(match is not None, 'unexpected recording name: ' + path.name)
        arm = ARMS.get(match[4])
        check(arm is not None, 'unexpected recording: ' + path.name)
        grouped.setdefault((arm, match[6]), []).append(attribute(jfr_document(path, jfr)))
    return {arm + ' ' + phase: merge(parts) for (arm, phase), parts in sorted(grouped.items())}


def summarize(run, output, jfr):
    check(not output.exists(), 'evidence output must be new: ' + str(output))
    row_files = sorted((run / 'rows').glob('*.jsonl'))
    check(row_files, 'no row files under ' + str(run / 'rows'))
    rows = [row for path in row_files for row in read_rows(path)]
    # PowerShell 5.1 writes UTF-8 with a byte order mark.
    manifest = json.loads((run / 'manifest.json').read_text(encoding='utf-8-sig'))
    budgets = sorted({row['budget'] for row in rows})
    by_budget = {str(budget): paired([row for row in rows if row['budget'] == budget]) for budget in budgets}
    bars = ('at every budget: fairness-phase median at least 1.25x, no case less fair beyond its spread, median cost not above'
            ' the baseline, every accepted result inside its ceiling with zero violations')
    decision = {arm: {'promoted': all(by_budget[str(budget)]['summary'][arm]['passes'] for budget in budgets), 'bars': bars} for arm in TREATMENTS}
    decision['policies'] = 'compared on every measure, no bars: a change of fairness policy is an owner decision'
    document = {'study': 'Fairness measures (PR 187)', 'manifest': manifest,
                'revisions': sorted({item['revision'] for item in rows}), 'budgets': by_budget, 'decision': decision}
    if (run / 'profiles').is_dir():
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
                      'budgets': {budget: {'summary': value['summary'], 'comparison': value['comparison']}
                                  for budget, value in document['budgets'].items()}}, indent=1, default=str))


if __name__ == '__main__':
    main(sys.argv[1:])
