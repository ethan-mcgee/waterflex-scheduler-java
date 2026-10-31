import json
import tempfile
import unittest
from pathlib import Path

import arrival_buffer_study as abs_

CASES = [(10, 'CLUSTERED', 7001, 17), (30, 'ABSENCE', 7001, 17), (50, 'TIGHT_WINDOW', 7002, 17)]
WINDOWS = (2, 6)
BUFFER = {'percent': 25, 'minMinutes': 15, 'maxMinutes': 60}


def result(case, hours, moves, cost, inside, buffer_seconds=None):
    technicians, workload, fixture, seed = case
    after = {'costCents': cost, 'overtimeMinutes': 0}
    line = {'type': 'result', 'variant': 'TABU_KOPT', 'technicians': technicians, 'workload': workload, 'fixtureSeed': fixture, 'seed': seed,
            'windowMinutes': hours * 60, 'datasetFingerprint': f'{technicians}-{workload}-{fixture}-{hours}', 'violations': 0, 'appointments': 40,
            'acceptanceReason': 'FAIRNESS_IMPROVEMENT', 'referencePhase': {'moveEvaluations': moves, 'solveMs': 1000},
            'fairnessPhase': {'moveEvaluations': moves, 'solveMs': 1000}, 'after': after}
    if buffer_seconds is not None:
        after['bufferSeconds'] = buffer_seconds
        line['stopsInsideBufferBefore'] = 6
        line['stopsInsideBufferAfter'] = inside
    return line


def write_run(root, off_speed=99, on_speed=93, absence_on_speed=None, wide_on_cost=1010, designs=None):
    """Main runs 100 moves per second in each phase; off and on run their own speeds, ABSENCE optionally slower for on.
    On costs 10 cents more than off on 2-hour windows and {@code wide_on_cost} - 1000 on 6-hour ones. {@code designs}
    maps a treatment name to its (off speed, on speed, put back) for a run of several designs; without it the run is
    the one branch's off and on."""
    (root / 'rows').mkdir()
    designs = designs or {'': (off_speed, on_speed, None)}
    names = ['main'] + [prefix + kind for prefix in (name + '-' if name else '' for name in designs) for kind in ('off', 'on')]
    (root / 'manifest.json').write_text('﻿' + json.dumps({'study': 'test', 'arrivalBuffer': '25,15,60', 'arms': {name: '' for name in names}}),
                                        encoding='utf-8')
    for rep in (1, 2):
        for hours in WINDOWS:
            for case in CASES:
                arms = {'main': ({}, result(case, hours, 100, 1000, 0))}
                for name, (off, on, put_back) in designs.items():
                    prefix = name + '-' if name else ''
                    if absence_on_speed is not None and case[1] == 'ABSENCE':
                        on = absence_on_speed
                    arms[prefix + 'off'] = ({'arrivalBuffer': {'percent': 0, 'minMinutes': 0, 'maxMinutes': 0}, 'reportArrivalBuffer': BUFFER},
                                            result(case, hours, off, 1000, 5, 0))
                    arms[prefix + 'on'] = ({'arrivalBuffer': BUFFER}, result(case, hours, on, 1010 if hours == 2 else wide_on_cost, 0, 0))
                    if put_back is not None:
                        for arm in (prefix + 'off', prefix + 'on'):
                            arms[arm][1].update(putBack=put_back if arm.endswith('on') else 0, unassignedAfterPutBack=0)
                for arm, (extra, line) in arms.items():
                    provenance = {'type': 'provenance', 'revision': 'arrival-buffer-study arm=' + arm, 'budgetMs': 15000, 'windowMinutes': hours * 60, **extra}
                    name = f'rep{rep}-{case[0]}-{case[1]}-{case[2]}-{case[3]}-{hours}h-{arm}.jsonl'
                    (root / 'rows' / name).write_text('\n'.join(json.dumps(item) for item in [provenance, line]) + '\n', encoding='utf-8')
    return root


def rewrite(path, change):
    lines = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
    change(lines)
    path.write_text('\n'.join(json.dumps(line) for line in lines) + '\n', encoding='utf-8')


class ArrivalBufferStudyTest(unittest.TestCase):
    def summarize(self, **options):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary), **options)
            return abs_.summarize(run, run / 'out.json', 'jfr')

    def test_both_arms_pass_their_bars_against_main(self):
        paired = self.summarize()['paired']
        self.assertEqual(paired['cases'], len(CASES) * len(WINDOWS))
        self.assertEqual(paired['speed']['off/main']['referencePhase']['overall']['median'], 0.99)
        self.assertEqual(paired['speed']['on/main']['fairnessPhase']['overall']['median'], 0.93)
        self.assertEqual(sorted(paired['speed']['on/main']['referencePhase']['byWorkload']), ['ABSENCE', 'CLUSTERED', 'TIGHT_WINDOW'])
        self.assertEqual(paired['speed']['on/main']['referencePhase']['byFleet']['30']['pairs'], 4)
        self.assertEqual(paired['speed']['on/main']['referencePhase']['byWindowHours']['6']['pairs'], 6)
        self.assertTrue(paired['decision']['off']['promoted'] and paired['decision']['on']['promoted'])

    def test_each_arm_is_held_to_its_own_bar(self):
        self.assertFalse(self.summarize(off_speed=97)['paired']['decision']['off']['promoted'])
        self.assertTrue(self.summarize(off_speed=97)['paired']['decision']['on']['promoted'])
        self.assertFalse(self.summarize(on_speed=89)['paired']['decision']['on']['promoted'])

    def test_a_slow_absence_workload_shows_on_its_own(self):
        speed = self.summarize(absence_on_speed=70)['paired']['speed']['on/main']['referencePhase']
        self.assertEqual(speed['byWorkload']['ABSENCE']['median'], 0.7)
        self.assertEqual(speed['overall']['median'], 0.93)

    def test_quality_reports_what_the_buffer_buys_and_costs(self):
        paired = self.summarize()['paired']
        self.assertEqual((paired['quality']['off']['stopsInsideBufferAfter'], paired['quality']['on']['stopsInsideBufferAfter']), (60, 0))
        self.assertNotIn('stopsInsideBufferAfter', paired['quality']['main'])
        self.assertEqual((paired['costAgainstOff']['median'], paired['costAgainstOff']['higher']), (10, 12))
        self.assertEqual(paired['stopsInsideBufferAgainstOff']['lower'], 12)

    def test_each_window_length_reports_its_own_cost(self):
        windows = self.summarize(wide_on_cost=1040)['paired']['byWindowHours']
        self.assertEqual(sorted(windows), ['2', '6'])
        self.assertEqual((windows['2']['costAgainstOff']['median'], windows['6']['costAgainstOff']['median']), (10, 40))
        self.assertEqual(windows['6']['quality']['off']['stopsInsideBufferAfter'], 30)

    def test_an_arm_that_ran_the_wrong_buffer_tree_or_window_is_refused(self):
        for suffix, change, message in [
                ('-2h-on.jsonl', lambda lines: lines[0].update(arrivalBuffer={'percent': 30, 'minMinutes': 15, 'maxMinutes': 60}), 'wrong buffer'),
                ('-2h-off.jsonl', lambda lines: lines[0].pop('reportArrivalBuffer'), 'count stops'),
                ('-2h-main.jsonl', lambda lines: lines[0].update(arrivalBuffer=BUFFER), 'before the buffer'),
                ('-2h-on.jsonl', lambda lines: lines[1].update(variant='TABU'), 'TABU_KOPT'),
                ('-6h-main.jsonl', lambda lines: lines[0].update(windowMinutes=120), '6-hour windows'),
                ('-6h-on.jsonl', lambda lines: lines[1].update(windowMinutes=240), 'does not match'),
                ('-6h-main.jsonl', lambda lines: lines[1].update(datasetFingerprint='another day'), 'different days')]:
            with tempfile.TemporaryDirectory() as temporary:
                run = write_run(Path(temporary))
                rewrite(next((run / 'rows').glob('rep1-10-*' + suffix)), change)
                with self.assertRaisesRegex(ValueError, message):
                    abs_.summarize(run, run / 'out.json', 'jfr')

    def test_several_designs_are_each_held_to_the_bars_against_the_same_main(self):
        paired = self.summarize(designs={'shrink7': (100, 99, 1), 'soft8': (97, 95, None)})['paired']
        self.assertEqual(sorted(paired['speed']), ['shrink7-off/main', 'shrink7-on/main', 'shrink7-on/shrink7-off',
                                                   'soft8-off/main', 'soft8-on/main', 'soft8-on/soft8-off'])
        self.assertEqual(paired['speed']['shrink7-on/shrink7-off']['referencePhase']['overall']['median'], 0.99)
        self.assertTrue(paired['decision']['shrink7-off']['promoted'] and paired['decision']['soft8-on']['promoted'])
        self.assertFalse(paired['decision']['soft8-off']['promoted'])
        self.assertEqual(sorted(paired['treatments']), ['shrink7', 'soft8'])
        self.assertEqual(paired['treatments']['soft8']['costAgainstOff']['median'], 10)
        self.assertNotIn('costAgainstOff', paired)
        # Put back is summed where the rows report it, and left out where they do not.
        self.assertEqual(paired['quality']['shrink7-on']['putBack'], 12)
        self.assertNotIn('putBack', paired['quality']['soft8-on'])

    def test_an_incomplete_or_mixed_run_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary))
            next((run / 'rows').glob('rep2-50-*-6h-main.jsonl')).unlink()
            with self.assertRaisesRegex(ValueError, 'missing'):
                abs_.summarize(run, run / 'out.json', 'jfr')
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary))
            rewrite(next((run / 'rows').glob('rep1-10-*-2h-on.jsonl')), lambda lines: lines[0].update(budgetMs=30000))
            with self.assertRaisesRegex(ValueError, 'one budget'):
                abs_.summarize(run, run / 'out.json', 'jfr')


if __name__ == '__main__':
    unittest.main()
