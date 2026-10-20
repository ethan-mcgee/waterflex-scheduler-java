"""Road matrix numbering study (PR 187): the scoring path's identity lookups and route maps, then b, c and load balance again.

Arms, by code tree and variant, all at one budget:
  old           tree o (before the fix), TABU_KOPT                       production as the fairness measure study ran it
  old-b         tree o, TABU_KOPT_BINARY_VARIANCE                        option (b) before the fix
  baseline      tree n (the fix), TABU_KOPT                              production with numbered visits and routes
  b             tree n, TABU_KOPT_BINARY_VARIANCE                        option (b) on the fix
  c             tree n, TABU_KOPT_INCREMENTAL_VARIANCE                   option (c) on the fix
  load-balance  tree n, TABU_KOPT_LOAD_BALANCE                           Timefold's load balance over utilization, on the fix

The fix changes no rule (ScoringExactnessTest gates it), so it is judged as the timing change T was: promoted if the fixed
production is not slower than the old one in either phase. b and c are held to the fairness measure study's four bars
against the fixed production; load balance gets the comparison on every measure, with no bars.

  python infra/matrix_study.py summarize --run <run directory> --out <evidence json>

The run directory is what infra/run-matrix-study.ps1 writes: rows/rep<r>-<technicians>-<workload>-<seed>-<tree>.jsonl and
profiles/<tree>/*.jfr.
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
from variance_study import ACCEPTED_ON, MEASURES, PHASES, comparison, judge, runs, within_ceiling

ARMS = {('o', 'TABU_KOPT'): 'old', ('o', 'TABU_KOPT_BINARY_VARIANCE'): 'old-b',
        ('n', 'TABU_KOPT'): 'baseline', ('n', 'TABU_KOPT_BINARY_VARIANCE'): 'b',
        ('n', 'TABU_KOPT_INCREMENTAL_VARIANCE'): 'c', ('n', 'TABU_KOPT_LOAD_BALANCE'): 'load-balance'}
ORDER = list(ARMS.values())
# Each fix pair: the arm on the fixed tree and the same variant before the fix.
FIX = {'baseline': 'old', 'b': 'old-b'}
TREATMENTS = ('b', 'c')
TREES = {'o': 2, 'n': 4}
ROW_FILE = re.compile(r'rep(\d+)-(\d+)-([A-Z_]+)-(\d+)-([on])\.jsonl')
JFR_FILE = re.compile(r'(\d+)-([A-Z_]+)-(\d+)-([A-Z_0-9]+)-(\d+)-(cost|fairness)\.jfr')
FIX_BAR = 1.0


def read_rows(path):
    match = ROW_FILE.fullmatch(path.name)
    check(match is not None, 'unexpected row file name: ' + path.name)
    rep, technicians, workload, seed, tree = int(match[1]), int(match[2]), match[3], int(match[4]), match[5]
    # Variances are exact decimals in the JSON; keep them so.
    lines = [json.loads(line, parse_float=Decimal) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]
    check(lines and lines[0].get('type') == 'provenance', path.name + ': provenance first')
    check(isinstance(lines[0].get('budgetMs'), int), path.name + ': provenance needs the budget')
    check(not any(line.get('type') == 'diagnostics-failure' for line in lines), path.name + ': diagnostics failure')
    rows = []
    for line in lines:
        if line.get('type') != 'result':
            continue
        check(line['technicians'] == technicians and line['workload'] == workload and line['seed'] == seed, path.name + ': row does not match its file')
        arm = ARMS.get((tree, line['variant']))
        check(arm is not None, path.name + ': unexpected variant ' + str(line['variant']) + ' in tree ' + tree)
        check(line.get('acceptedOn') == ACCEPTED_ON.get(arm), path.name + ': ' + arm + ' accepted on the wrong measure')
        check(sorted(line.get('measures', {}).get('after', {})) == sorted(MEASURES), path.name + ': every measure expected for ' + arm)
        rows.append({'arm': arm, 'budget': lines[0]['budgetMs'], 'rep': rep, 'case': (technicians, workload, seed), 'row': line,
                     'revision': lines[0]['revision']})
    check(len(rows) == TREES[tree], path.name + ': every variant of tree ' + tree + ' expected once')
    return rows


def by_fleet(values, order, cases):
    """The same paired ratios, split by fleet size; {@code order} names each value's case."""
    return {str(fleet): spread([value for value, case in zip(values, order) if case[0] == fleet]) for fleet in sorted({case[0] for case in cases})}


def fix(by, cases, reps, arm, old):
    """The fixed arm against the same variant before the fix: phase speeds overall and by fleet, and what the extra moves bought."""
    order = [case for case in cases for _ in reps]
    speedups = {phase: [rate(by[(arm, case, rep)], phase) / rate(by[(old, case, rep)], phase) for case in cases for rep in reps] for phase in PHASES}
    costs = [statistics.mean(by[(arm, case, rep)]['after']['costCents'] for rep in reps)
             - statistics.mean(by[(old, case, rep)]['after']['costCents'] for rep in reps) for case in cases]
    variances = [sum(Decimal(by[(arm, case, rep)]['after']['fairness']['variance']) for rep in reps)
                 - sum(Decimal(by[(old, case, rep)]['after']['fairness']['variance']) for rep in reps) for case in cases]
    return {'against': old,
            'referencePhaseSpeedup': spread(speedups['referencePhase']), 'fairnessPhaseSpeedup': spread(speedups['fairnessPhase']),
            'byFleet': {phase: by_fleet(speedups[phase], order, cases) for phase in PHASES},
            'cost': {'medianDifferenceCents': statistics.median(costs), 'cheaper': sum(1 for value in costs if value < 0),
                     'ties': sum(1 for value in costs if value == 0), 'dearer': sum(1 for value in costs if value > 0)},
            'variance': {'fairer': sum(1 for value in variances if value < 0), 'same': sum(1 for value in variances if value == 0),
                         'lessFair': sum(1 for value in variances if value > 0)}}


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
    violations = sum(row['violations'] for row in by.values())
    outside = sorted(key[0] + ' ' + repr(key[1]) + ' rep ' + str(key[2]) for key, row in by.items() if not within_ceiling(row))
    fixes = {arm: fix(by, cases, reps, arm, old) for arm, old in FIX.items()}
    summary, pairs = {}, []
    order = [case for case in cases for _ in reps]
    for arm in TREATMENTS:
        summary[arm], arm_pairs = judge(by, cases, reps, arm, outside)
        speeds = [rate(by[(arm, case, rep)], 'fairnessPhase') / rate(by[('baseline', case, rep)], 'fairnessPhase') for case in cases for rep in reps]
        summary[arm]['fairnessPhaseSpeedupByFleet'] = by_fleet(speeds, order, cases)
        pairs += arm_pairs
    fixed = fixes['baseline']
    promoted = fixed['referencePhaseSpeedup']['median'] >= FIX_BAR and fixed['fairnessPhaseSpeedup']['median'] >= FIX_BAR and violations == 0
    decision = {'fix': {'promoted': promoted, 'bar': 'the fixed production at least as fast as the old one in both phases (median of paired'
                        ' moves per second), zero violations; exactness is shown by ScoringExactnessTest'}}
    for arm in TREATMENTS:
        decision[arm] = {'promoted': summary[arm]['passes'], 'bars': 'against the fixed production: fairness-phase median at least 1.25x,'
                         ' no case less fair beyond its spread, median cost not above the baseline, every accepted result inside its ceiling'}
    decision['load-balance'] = 'compared on every measure, no bars: a change of fairness policy is an owner decision'
    return {'cases': len(cases), 'repetitions': len(reps), 'violations': violations, 'outsideCeiling': outside, 'fix': fixes,
            'summary': summary, 'comparison': comparison(by, cases, reps, ORDER), 'decision': decision, 'pairs': pairs, 'runs': runs(by, ORDER)}


def profiles(directory, jfr):
    """Per arm and phase: the merged attribution of every recording, each tree's recordings in its own folder."""
    grouped = {}
    for path in sorted(directory.glob('*/*.jfr')):
        match = JFR_FILE.fullmatch(path.name)
        check(match is not None, 'unexpected recording name: ' + path.name)
        arm = ARMS.get((path.parent.name, match[4]))
        check(arm is not None, 'unexpected recording: ' + str(path))
        grouped.setdefault((arm, match[6]), []).append(attribute(jfr_document(path, jfr)))
    return {arm + ' ' + phase: merge(parts) for (arm, phase), parts in sorted(grouped.items())}


def summarize(run, output, jfr):
    check(not output.exists(), 'evidence output must be new: ' + str(output))
    row_files = sorted((run / 'rows').glob('*.jsonl'))
    check(row_files, 'no row files under ' + str(run / 'rows'))
    rows = [row for path in row_files for row in read_rows(path)]
    budgets = sorted({row['budget'] for row in rows})
    check(len(budgets) == 1, 'one budget expected, found ' + repr(budgets))
    # PowerShell 5.1 writes UTF-8 with a byte order mark.
    manifest = json.loads((run / 'manifest.json').read_text(encoding='utf-8-sig'))
    document = {'study': 'Road matrix numbering (PR 187)', 'manifest': manifest, 'budgetMs': budgets[0],
                'revisions': sorted({item['revision'] for item in rows}), 'paired': paired(rows)}
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
    paired_ = document['paired']
    print(json.dumps({'decision': paired_['decision'], 'fix': paired_['fix'], 'summary': paired_['summary']}, indent=1, default=str))


if __name__ == '__main__':
    main(sys.argv[1:])
