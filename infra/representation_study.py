"""Score and time representation study (PR F): JFR attribution and the paired four-arm summary.

Arms, by code tree and variant:
  baseline  tree s (the long score commit, decimal timing on Instant), TABU_KOPT
  S         tree s, TABU_KOPT_LONG_SCORE
  T         tree t (nanosecond timing), TABU_KOPT
  T+S       tree t, TABU_KOPT_LONG_SCORE
Tree s runs the production score path unchanged, so its TABU_KOPT rows are the baseline.

  python infra/representation_study.py summarize --run <run directory> --out <evidence json>

The run directory is what infra/run-representation-study.ps1 writes: rows/rep<r>-<technicians>-<workload>-<seed>-<tree>.jsonl
(SolverBenchmark output) and profiles/<tree>/*.jfr (one recording per measured solve).
"""
import argparse
import json
import re
import shutil
import statistics
import subprocess
import sys
from pathlib import Path

ARMS = {('s', 'TABU_KOPT'): 'baseline', ('s', 'TABU_KOPT_LONG_SCORE'): 'S',
        ('t', 'TABU_KOPT'): 'T', ('t', 'TABU_KOPT_LONG_SCORE'): 'T+S'}
TREATMENTS = ('S', 'T', 'T+S')
PHASES = ('referencePhase', 'fairnessPhase')
ROW_FILE = re.compile(r'rep(\d+)-(\d+)-([A-Z_]+)-(\d+)-([st])\.jsonl')
JFR_FILE = re.compile(r'(\d+)-([A-Z_]+)-(\d+)-([A-Z_0-9]+)-(\d+)-(cost|fairness)\.jfr')
# The bars the plan set: T is promoted if it is not slower; S only at 1.25x on the fairness phase with no case dearer
# than the baseline beyond that case's own run-to-run spread.
S_FAIRNESS_BAR = 1.25
T_BAR = 1.0

VARIANCE_SITES = ('SchedulingPolicy.fairness', 'RouteScoringFacts.fairness', 'RouteScoringFacts.varianceUnits')
MONEY_SITE = 'dev.waterflex.scheduler.Monetary.'


def check(condition, message):
    if not condition:
        raise ValueError(message)


def frame_name(frame):
    check(isinstance(frame, dict) and isinstance(frame.get('method'), dict), 'JFR frame method required')
    method = frame['method']
    owner = method.get('type', {}).get('name') if isinstance(method.get('type'), dict) else None
    check(isinstance(owner, str) and isinstance(method.get('name'), str), 'JFR frame needs a type and a method name')
    return owner.replace('/', '.') + '.' + method['name']


def decimal_class(name):
    return name.startswith('java.math.') or 'BigDecimal' in name


def category(frames, leaf=None):
    """One sample's category. {@code frames} run leaf first; {@code leaf} names an allocated class instead of the leaf frame."""
    check(frames, 'empty stack')
    top = leaf if leaf is not None else frames[0]
    if decimal_class(top):
        if any(site in frame for frame in frames for site in VARIANCE_SITES):
            return 'decimal: variance'
        if any(frame.startswith(MONEY_SITE) for frame in frames):
            return 'decimal: money'
        return 'decimal: score impacts'
    if top.startswith('java.time.'):
        return 'time'
    if any(site in frame for frame in frames for site in VARIANCE_SITES):
        return 'variance (non-decimal)'
    if top.startswith('ai.timefold.solver.core.impl.score') or top.startswith('ai.timefold.solver.core.api.score'):
        return 'score engine'
    if top.startswith('dev.waterflex.'):
        return 'scheduler code'
    if top.startswith('ai.timefold.'):
        return 'engine'
    if top.startswith('java.util.'):
        return 'collections'
    return 'other'


def attribute(document):
    """Execution samples and sampled allocation bytes per category, from a jfr print --json document."""
    check(isinstance(document, dict) and isinstance(document.get('recording'), dict), 'JFR recording object required')
    events = document['recording'].get('events')
    check(isinstance(events, list), 'JFR events required')
    samples, allocation, leaves = {}, {}, {}
    unavailable = 0
    for event in events:
        check(isinstance(event, dict) and isinstance(event.get('values'), dict), 'Malformed JFR event')
        kind, values = event.get('type'), event['values']
        if kind not in ('jdk.ExecutionSample', 'jdk.ObjectAllocationSample'):
            continue
        stack = values.get('stackTrace')
        if not isinstance(stack, dict) or not stack.get('frames'):
            unavailable += 1
            continue
        frames = [frame_name(frame) for frame in stack['frames']]
        if kind == 'jdk.ExecutionSample':
            name = category(frames)
            samples[name] = samples.get(name, 0) + 1
            leaves[frames[0]] = leaves.get(frames[0], 0) + 1
        else:
            weight = values.get('weight')
            check(isinstance(weight, int) and weight >= 0, 'allocation weight must be a whole number of bytes')
            owner = values.get('objectClass', {}).get('name') if isinstance(values.get('objectClass'), dict) else None
            check(isinstance(owner, str), 'allocation object class required')
            name = category(frames, owner.replace('/', '.'))
            allocation[name] = allocation.get(name, 0) + weight
    return {'samples': samples, 'allocationBytes': allocation, 'leaves': leaves, 'withoutStack': unavailable}


def merge(parts):
    total = {'samples': {}, 'allocationBytes': {}, 'leaves': {}, 'withoutStack': 0, 'recordings': 0}
    for part in parts:
        for key in ('samples', 'allocationBytes', 'leaves'):
            for name, value in part[key].items():
                total[key][name] = total[key].get(name, 0) + value
        total['withoutStack'] += part['withoutStack']
        total['recordings'] += 1
    for key in ('samples', 'allocationBytes'):
        whole = sum(total[key].values())
        total[key + 'Percent'] = {name: round(100 * value / whole, 1) for name, value in sorted(total[key].items(), key=lambda item: -item[1])} if whole else {}
    # The twenty leaf frames holding most samples, as a percentage of all samples; the full count stays out of the evidence.
    whole = sum(total['leaves'].values())
    top = sorted(total.pop('leaves').items(), key=lambda item: (-item[1], item[0]))[:20]
    total['leafFramesPercent'] = {name: round(100 * value / whole, 1) for name, value in top} if whole else {}
    return total


def read_rows(path):
    """SolverBenchmark JSONL: one provenance line, configurations, results; a diagnostics failure is refused."""
    match = ROW_FILE.fullmatch(path.name)
    check(match is not None, 'unexpected row file name: ' + path.name)
    rep, technicians, workload, seed, tree = int(match[1]), int(match[2]), match[3], int(match[4]), match[5]
    lines = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]
    check(lines and lines[0].get('type') == 'provenance', path.name + ': provenance first')
    check(not any(line.get('type') == 'diagnostics-failure' for line in lines), path.name + ': diagnostics failure')
    rows = []
    for line in lines:
        if line.get('type') != 'result':
            continue
        check(line['technicians'] == technicians and line['workload'] == workload and line['seed'] == seed, path.name + ': row does not match its file')
        arm = ARMS.get((tree, line['variant']))
        check(arm is not None, path.name + ': unexpected variant ' + str(line['variant']))
        rows.append({'arm': arm, 'rep': rep, 'case': (technicians, workload, seed), 'row': line, 'revision': lines[0]['revision']})
    check(len(rows) == 2, path.name + ': both variants of the tree expected')
    return rows


def rate(row, phase):
    statistics_ = row.get(phase)
    check(isinstance(statistics_, dict), phase + ' missing')
    moves, solve = statistics_.get('moveEvaluations'), statistics_.get('solveMs')
    check(isinstance(moves, int) and isinstance(solve, int) and solve > 0, phase + ' needs move evaluations and a positive solve time')
    return moves * 1000 / solve


def spread(values):
    return {'median': round(statistics.median(values), 3), 'min': round(min(values), 3), 'max': round(max(values), 3), 'pairs': len(values)}


def paired(rows):
    """Per treatment against the baseline of the same case and repetition, and the bars applied."""
    by = {}
    for item in rows:
        key = (item['arm'], item['case'], item['rep'])
        check(key not in by, 'duplicate row: ' + repr(key))
        by[key] = item['row']
    cases = sorted({item['case'] for item in rows})
    reps = sorted({item['rep'] for item in rows})
    for case in cases:
        for rep in reps:
            for arm in ('baseline',) + TREATMENTS:
                check((arm, case, rep) in by, 'missing ' + arm + ' for ' + repr(case) + ' rep ' + str(rep))
    violations = sum(by[key]['violations'] for key in by)
    summary, pairs = {}, []
    for arm in TREATMENTS:
        speedups = {phase: [] for phase in PHASES}
        cheaper = ties = dearer = dearer_beyond_noise = 0
        for case in cases:
            base_costs = [by[('baseline', case, rep)]['after']['costCents'] for rep in reps]
            arm_costs = [by[(arm, case, rep)]['after']['costCents'] for rep in reps]
            noise = max(base_costs) - min(base_costs)
            difference = statistics.mean(arm_costs) - statistics.mean(base_costs)
            if difference < 0:
                cheaper += 1
            elif difference == 0:
                ties += 1
            else:
                dearer += 1
                if difference > noise:
                    dearer_beyond_noise += 1
            for rep in reps:
                for phase in PHASES:
                    speedups[phase].append(rate(by[(arm, case, rep)], phase) / rate(by[('baseline', case, rep)], phase))
            pairs.append({'arm': arm, 'technicians': case[0], 'workload': case[1], 'seed': case[2],
                          'baselineCostCents': base_costs, 'armCostCents': arm_costs, 'baselineSpreadCents': noise,
                          'meanDifferenceCents': difference,
                          'referenceSpeedup': [round(rate(by[(arm, case, rep)], 'referencePhase') / rate(by[('baseline', case, rep)], 'referencePhase'), 3) for rep in reps],
                          'fairnessSpeedup': [round(rate(by[(arm, case, rep)], 'fairnessPhase') / rate(by[('baseline', case, rep)], 'fairnessPhase'), 3) for rep in reps]})
        # The speedups were appended case by case, repetition by repetition.
        order = [case for case in cases for _ in reps]
        by_fleet = {}
        for fleet in sorted({case[0] for case in cases}):
            chosen = [index for index, case in enumerate(order) if case[0] == fleet]
            by_fleet[str(fleet)] = {phase: spread([speedups[phase][index] for index in chosen]) for phase in PHASES}
        summary[arm] = {'referencePhaseSpeedup': spread(speedups['referencePhase']), 'fairnessPhaseSpeedup': spread(speedups['fairnessPhase']),
                        'byFleet': by_fleet, 'cases': {'cheaper': cheaper, 'ties': ties, 'dearer': dearer, 'dearerBeyondNoise': dearer_beyond_noise}}
    # Repetition agreement: the same arm's moves per second, first repetition against the others.
    agreement = []
    for arm in ('baseline',) + TREATMENTS:
        for case in cases:
            for rep in reps[1:]:
                for phase in PHASES:
                    agreement.append(abs(rate(by[(arm, case, rep)], phase) / rate(by[(arm, case, reps[0])], phase) - 1))
    return {'cases': len(cases), 'repetitions': len(reps), 'violations': violations, 'summary': summary,
            'repetitionAgreement': {'medianRelativeDifference': round(statistics.median(agreement), 3), 'maximum': round(max(agreement), 3)} if agreement else None,
            'decision': decide(summary), 'pairs': pairs}


def decide(summary):
    t, s = summary['T'], summary['S']
    t_promoted = t['referencePhaseSpeedup']['median'] >= T_BAR and t['fairnessPhaseSpeedup']['median'] >= T_BAR
    s_promoted = s['fairnessPhaseSpeedup']['median'] >= S_FAIRNESS_BAR and s['cases']['dearerBeyondNoise'] == 0
    return {'T': {'promoted': t_promoted, 'bar': 'median moves per second at least the baseline in both phases (exactness is already shown by ScoringExactnessTest)'},
            'S': {'promoted': s_promoted, 'bar': 'median fairness-phase moves per second at least 1.25x the baseline and no case dearer than the baseline beyond its own run-to-run spread'}}


def jfr_document(path, jfr):
    result = subprocess.run([jfr, 'print', '--json', '--stack-depth', '64', '--events', 'jdk.ExecutionSample,jdk.ObjectAllocationSample', str(path)],
                            capture_output=True, check=True, text=True, encoding='utf-8')
    return json.loads(result.stdout)


def profiles(directory, jfr):
    """Per tree and arm and phase: the merged attribution of every recording."""
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
    # PowerShell 5.1 writes UTF-8 with a byte order mark.
    manifest = json.loads((run / 'manifest.json').read_text(encoding='utf-8-sig'))
    document = {'study': 'Score and time representation (PR F)', 'manifest': manifest,
                'revisions': sorted({item['revision'] for item in rows}), 'paired': paired(rows)}
    if (run / 'profiles').is_dir():
        document['profiles'] = profiles(run / 'profiles', jfr)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(document, indent=1) + '\n', encoding='utf-8')
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
    print(json.dumps({'decision': document['paired']['decision'], 'summary': document['paired']['summary']}, indent=1))


if __name__ == '__main__':
    main(sys.argv[1:])
