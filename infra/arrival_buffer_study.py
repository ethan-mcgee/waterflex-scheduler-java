"""Late-arrival buffer study: what the buffer costs the daily search's speed, and what it buys, against production.

Arms, all TABU_KOPT at one budget, paired per case and repetition:
  main  tree m (main before the buffer), no buffer                 production as it ships today
  off   tree b (the buffer branch), no buffer                      the branch with the setting absent
  on    tree b, the client buffer (25 percent, 15 to 60 minutes)   the branch with the portal default
Tree m is main plus a test-only fixture change (bench/arrival-buffer-main) so it can build the same 4- and 6-hour
windows; a case is a fleet, workload, fixture seed, solver seed and window length, and its three arms must have solved
the same day (one dataset fingerprint), which the summary checks.

Bars, written before the run (2026-10-27). Speed is the median over pairs of moves per second, arm over main, per phase:
  off  at least 0.98x in both phases and zero violations: with no buffer the arithmetic is the same apart from one more
       field per timing, and ScoringExactnessTest already shows the same moves, score and arrangement as main.
  on   at least 0.90x in both phases and zero violations: the buffer adds one subtraction and comparison per visit and
       one more dominance dimension on routes with absences (the ABSENCE workload is the risk and is reported alone).
Quality is reported, never barred: cost, overtime and buffer use after the search, and the stops planned inside the
buffer. The off arm counts its stops against the same buffer for reporting only (benchmark.reportArrivalBuffer), so
off against on shows what the buffer buys. A buffer that costs more than the owner accepts is an owner decision.

  python infra/arrival_buffer_study.py summarize --run <run directory> --out <evidence json>

The run directory is what infra/run-arrival-buffer-study.ps1 writes: rows/rep<r>-<technicians>-<workload>-<fixture>-
<seed>-<window>h-<arm>.jsonl, manifest.json and profiles/<arm>/<window>h/*.jfr. A run with -Treatments compares several
buffer designs at once: each treatment <name> has arms <name>-off and <name>-on, held to the same bars against main, and
its quality comparisons appear under treatments.<name>; a run of the one branch keeps the arms off and on and the
top-level comparisons.
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

PHASES = ('referencePhase', 'fairnessPhase')
BARS = {'off': 0.98, 'on': 0.90}
NONE = {'percent': 0, 'minMinutes': 0, 'maxMinutes': 0}
ROW_FILE = re.compile(r'rep(\d+)-(\d+)-([A-Z_]+)-(\d+)-(\d+)-(\d+)h-(main|(?:[a-z0-9]+-)?(?:off|on))\.jsonl')
# Positions in a case: (technicians, workload, fixture seed, solver seed, window hours).
FLEET, WORKLOAD, WINDOW = 0, 1, 4
JFR_FILE = re.compile(r'(\d+)-([A-Z_]+)-(\d+)-([A-Z_0-9]+)-(\d+)-(cost|fairness)\.jfr')


def kind(arm):
    """main, off or on: what an arm ran, whatever design it belongs to."""
    return arm if arm == 'main' else arm.rsplit('-', 1)[-1]


def buffer_of(text):
    parts = [int(part) for part in text.split(',')]
    check(len(parts) == 3, 'a buffer is percent,minMinutes,maxMinutes: ' + text)
    return {'percent': parts[0], 'minMinutes': parts[1], 'maxMinutes': parts[2]}


def read_rows(path, buffer):
    match = ROW_FILE.fullmatch(path.name)
    check(match is not None, 'unexpected row file name: ' + path.name)
    rep, technicians, workload, fixture, seed, hours, arm = (int(match[1]), int(match[2]), match[3], int(match[4]), int(match[5]), int(match[6]),
                                                             match[7])
    # Variances are exact decimals in the JSON; keep them so.
    lines = [json.loads(line, parse_float=Decimal) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]
    check(lines and lines[0].get('type') == 'provenance', path.name + ': provenance first')
    provenance = lines[0]
    check(isinstance(provenance.get('budgetMs'), int), path.name + ': provenance needs the budget')
    check(provenance.get('windowMinutes') == hours * 60, path.name + ': the run did not use ' + str(hours) + '-hour windows')
    check(not any(line.get('type') == 'diagnostics-failure' for line in lines), path.name + ': diagnostics failure')
    # Each arm must have run what it claims: main predates the setting, off ran without it, on with the study's buffer.
    if arm == 'main':
        check('arrivalBuffer' not in provenance, path.name + ': main must be the tree before the buffer')
    else:
        check(provenance.get('arrivalBuffer') == (buffer if kind(arm) == 'on' else NONE), path.name + ': wrong buffer for ' + arm)
    if kind(arm) == 'off':
        check(provenance.get('reportArrivalBuffer') == buffer, path.name + ': off must count stops against the study buffer')
    results = [line for line in lines if line.get('type') == 'result']
    check(len(results) == 1, path.name + ': one result expected')
    row = results[0]
    check(row['technicians'] == technicians and row['workload'] == workload and row['fixtureSeed'] == fixture and row['seed'] == seed
          and row.get('windowMinutes') == hours * 60, path.name + ': row does not match its file')
    check(row['variant'] == 'TABU_KOPT', path.name + ': every arm runs TABU_KOPT')
    return {'arm': arm, 'budget': provenance['budgetMs'], 'rep': rep, 'case': (technicians, workload, fixture, seed, hours), 'row': row,
            'revision': provenance['revision']}


def split(values, keys, position):
    """The same paired values, split by one part of the case (FLEET, WORKLOAD or WINDOW)."""
    return {str(group): spread([value for value, key in zip(values, keys) if key[position] == group]) for group in sorted({key[position] for key in keys})}


def speed(by, pairs, arm, against):
    ratios = {phase: [rate(by[(arm, case, rep)], phase) / rate(by[(against, case, rep)], phase) for case, rep in pairs] for phase in PHASES}
    keys = [case for case, _ in pairs]
    return {phase: {'overall': spread(ratios[phase]), 'byFleet': split(ratios[phase], keys, FLEET), 'byWorkload': split(ratios[phase], keys, WORKLOAD),
                    'byWindowHours': split(ratios[phase], keys, WINDOW)} for phase in PHASES}


def differences(by, pairs, arm, against, read):
    values = [read(by[(arm, case, rep)]) - read(by[(against, case, rep)]) for case, rep in pairs]
    return {'median': statistics.median(values), 'lower': sum(1 for value in values if value < 0), 'same': sum(1 for value in values if value == 0),
            'higher': sum(1 for value in values if value > 0), 'pairs': len(values)}


def quality(by, pairs, arm):
    rows = [by[(arm, case, rep)] for case, rep in pairs]
    reasons = {}
    for row in rows:
        reasons[row['acceptanceReason']] = reasons.get(row['acceptanceReason'], 0) + 1
    summary = {'medianCostCents': statistics.median(row['after']['costCents'] for row in rows),
               'overtimeMinutes': sum(row['after']['overtimeMinutes'] for row in rows), 'reasons': dict(sorted(reasons.items()))}
    if arm != 'main' and all('putBack' in row for row in rows):
        summary['putBack'] = sum(row['putBack'] for row in rows)
        summary['unassignedAfterPutBack'] = sum(row['unassignedAfterPutBack'] for row in rows)
    if arm != 'main':
        summary['stopsInsideBufferBefore'] = sum(row['stopsInsideBufferBefore'] for row in rows)
        summary['stopsInsideBufferAfter'] = sum(row['stopsInsideBufferAfter'] for row in rows)
        summary['appointments'] = sum(row['appointments'] for row in rows)
        summary['bufferSecondsAfter'] = sum(row['after']['bufferSeconds'] for row in rows)
    return summary


def comparisons(by, pairs, cases, prefix):
    """One buffer design's quality against its own off arm and against main, overall and by window length."""
    on, off = prefix + 'on', prefix + 'off'
    cost = lambda row: row['after']['costCents']
    inside = lambda row: row['stopsInsideBufferAfter']
    windows = {}
    for hours in sorted({case[WINDOW] for case in cases}):
        subset = [(case, rep) for case, rep in pairs if case[WINDOW] == hours]
        windows[str(hours)] = {'quality': {arm: quality(by, subset, arm) for arm in ('main', off, on)}, 'costAgainstOff': differences(by, subset, on, off, cost),
                               'stopsInsideBufferAgainstOff': differences(by, subset, on, off, inside)}
    return {'costAgainstOff': differences(by, pairs, on, off, cost), 'costAgainstMain': differences(by, pairs, on, 'main', cost),
            'stopsInsideBufferAgainstOff': differences(by, pairs, on, off, inside), 'byWindowHours': windows}


def paired(rows, arms=('main', 'off', 'on')):
    by = {}
    for item in rows:
        key = (item['arm'], item['case'], item['rep'])
        check(key not in by, 'duplicate row: ' + repr(key))
        check(item['arm'] in arms, 'a row of an arm the manifest does not name: ' + item['arm'])
        by[key] = item['row']
    cases = sorted({item['case'] for item in rows})
    reps = sorted({item['rep'] for item in rows})
    for case in cases:
        for rep in reps:
            for arm in arms:
                check((arm, case, rep) in by, 'missing ' + arm + ' for ' + repr(case) + ' rep ' + str(rep))
            # Tree m builds its fixtures with its own copy of the window code: every arm must have solved the same day.
            check(len({by[(arm, case, rep)]['datasetFingerprint'] for arm in arms}) == 1, 'the arms solved different days for ' + repr(case))
    pairs = [(case, rep) for case in cases for rep in reps]
    violations = {arm: sum(by[(arm, case, rep)]['violations'] for case, rep in pairs) for arm in arms}
    prefixes = sorted({arm[:-len('off')] for arm in arms if kind(arm) == 'off'})
    check(all(prefix + 'on' in arms for prefix in prefixes) and len(prefixes) * 2 + 1 == len(arms), 'every design needs an off and an on arm')
    speeds = {arm + '/main': speed(by, pairs, arm, 'main') for arm in arms if arm != 'main'}
    for prefix in prefixes:
        speeds[prefix + 'on/' + prefix + 'off'] = speed(by, pairs, prefix + 'on', prefix + 'off')
    decision = {}
    for arm in arms:
        if arm == 'main':
            continue
        medians = {phase: speeds[arm + '/main'][phase]['overall']['median'] for phase in PHASES}
        decision[arm] = {'promoted': all(value >= BARS[kind(arm)] for value in medians.values()) and violations[arm] == 0, 'medians': medians,
                         'bar': 'median paired moves per second against main at least ' + str(BARS[kind(arm)]) + 'x in both phases, zero violations'}
    document = {'cases': len(cases), 'repetitions': len(reps), 'violations': violations, 'speed': speeds, 'decision': decision,
                'quality': {arm: quality(by, pairs, arm) for arm in arms}}
    if prefixes == ['']:
        document.update(comparisons(by, pairs, cases, ''))
    else:
        document['treatments'] = {prefix[:-1]: comparisons(by, pairs, cases, prefix) for prefix in prefixes}
    return document


def profiles(directory, jfr, arms):
    """Per arm, window length and phase: the merged attribution of every recording, in profiles/<arm>/<window>h/."""
    grouped = {}
    for path in sorted(directory.glob('*/*/*.jfr')):
        match = JFR_FILE.fullmatch(path.name)
        window = re.fullmatch(r'(\d+)h', path.parent.name)
        check(match is not None and window is not None and path.parent.parent.name in arms, 'unexpected recording: ' + str(path))
        grouped.setdefault((path.parent.parent.name, int(window[1]), match[6]), []).append(attribute(jfr_document(path, jfr)))
    return {f'{arm} {hours}h {phase}': merge(parts) for (arm, hours, phase), parts in sorted(grouped.items())}


def summarize(run, output, jfr):
    check(not output.exists(), 'evidence output must be new: ' + str(output))
    # PowerShell 5.1 writes UTF-8 with a byte order mark.
    manifest = json.loads((run / 'manifest.json').read_text(encoding='utf-8-sig'))
    buffer = buffer_of(manifest['arrivalBuffer'])
    row_files = sorted((run / 'rows').glob('*.jsonl'))
    check(row_files, 'no row files under ' + str(run / 'rows'))
    rows = [read_rows(path, buffer) for path in row_files]
    # Main first, then the buffer arms in a fixed order (an older manifest's arms are an unordered map).
    check('main' in manifest['arms'], 'the manifest must name the main arm')
    arms = ('main',) + tuple(sorted(arm for arm in manifest['arms'] if arm != 'main'))
    budgets = sorted({row['budget'] for row in rows})
    check(len(budgets) == 1, 'one budget expected, found ' + repr(budgets))
    document = {'study': 'Late-arrival buffer', 'manifest': manifest, 'budgetMs': budgets[0], 'arrivalBuffer': buffer,
                'revisions': sorted({item['revision'] for item in rows}), 'paired': paired(rows, arms)}
    if (run / 'profiles').is_dir():
        document['profiles'] = profiles(run / 'profiles', jfr, arms)
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
    print(json.dumps({'decision': paired_['decision'], 'quality': paired_['quality']}, indent=1, default=str))


if __name__ == '__main__':
    main(sys.argv[1:])
