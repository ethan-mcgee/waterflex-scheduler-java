import json
import tempfile
import unittest
from pathlib import Path

import matrix_study as ms

CASES = [(10, 'CLUSTERED', 17), (30, 'DISPERSED', 23), (50, 'ABSENCE', 41)]


def result(variant, tree, case, reference_moves, fairness_moves, cost, variance, ceiling=10_000):
    technicians, workload, seed = case
    line = {'type': 'result', 'variant': variant, 'technicians': technicians, 'workload': workload, 'seed': seed, 'violations': 0,
            'referencePhase': {'moveEvaluations': reference_moves, 'solveMs': 1000}, 'fairnessPhase': {'moveEvaluations': fairness_moves, 'solveMs': 1000},
            'reference': {'costCents': 990}, 'after': {'costCents': cost, 'fairness': {'variance': variance}}, 'costCeilingCents': ceiling,
            'acceptanceReason': 'FAIRNESS_IMPROVEMENT',
            'measures': {'reference': {name: 0.5 for name in ms.MEASURES}, 'after': {'variance': variance, 'maximumUtilization': 0.9,
                                                                                      'utilizationSpread': 0.4, 'absoluteDeviation': 0.1, 'loadBalance': 0.5}}}
    accepted = ms.ACCEPTED_ON.get(ms.ARMS[(tree, variant)])
    if accepted is not None:
        line['acceptedOn'] = accepted
    return line


def write_run(root, fixed_speed=130, b=None):
    """Old production runs 100 moves per second in each phase; the fix runs {@code fixed_speed}. {@code b} overrides option (b)."""
    (root / 'rows').mkdir()
    (root / 'manifest.json').write_text('﻿' + json.dumps({'study': 'test'}), encoding='utf-8')
    for rep in (1, 2):
        for case in CASES:
            base_variance = 0.0200 + rep * 0.0001
            options = {'fairness_moves': fixed_speed * 5, 'cost': 1000, 'variance': 0.0150}
            if b is not None:
                options.update(b(case, rep))
            trees = {'o': [result('TABU_KOPT', 'o', case, 100, 100, 1000, base_variance),
                           result('TABU_KOPT_BINARY_VARIANCE', 'o', case, 100, 400, 1000, 0.0150)],
                     'n': [result('TABU_KOPT', 'n', case, fixed_speed, fixed_speed, 1000, base_variance),
                           result('TABU_KOPT_BINARY_VARIANCE', 'n', case, fixed_speed, options['fairness_moves'], options['cost'], options['variance']),
                           result('TABU_KOPT_INCREMENTAL_VARIANCE', 'n', case, fixed_speed, fixed_speed * 2, 1000, 0.0150),
                           result('TABU_KOPT_LOAD_BALANCE', 'n', case, fixed_speed, fixed_speed, 1001, 0.0250)]}
            for tree, lines in trees.items():
                provenance = {'type': 'provenance', 'revision': 'matrix-study tree=' + tree, 'budgetMs': 15000}
                name = f'rep{rep}-{case[0]}-{case[1]}-{case[2]}-{tree}.jsonl'
                (root / 'rows' / name).write_text('\n'.join(json.dumps(line) for line in [provenance] + lines) + '\n', encoding='utf-8')
    return root


class MatrixStudyTest(unittest.TestCase):
    def summarize(self, **options):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary), **options)
            return ms.summarize(run, run / 'out.json', 'jfr')

    def test_the_fix_is_measured_against_the_same_variant_before_it(self):
        paired = self.summarize()['paired']
        fixed = paired['fix']['baseline']
        self.assertEqual((fixed['against'], fixed['referencePhaseSpeedup']['median'], fixed['fairnessPhaseSpeedup']['median']), ('old', 1.3, 1.3))
        self.assertEqual(sorted(fixed['byFleet']['fairnessPhase']), ['10', '30', '50'])
        self.assertEqual(fixed['byFleet']['referencePhase']['30']['pairs'], 2)
        self.assertEqual(paired['fix']['b']['fairnessPhaseSpeedup']['median'], 1.625)
        self.assertTrue(paired['decision']['fix']['promoted'])

    def test_a_slower_fix_is_not_promoted(self):
        self.assertFalse(self.summarize(fixed_speed=99)['paired']['decision']['fix']['promoted'])

    def test_b_and_c_are_held_to_the_bars_against_the_fixed_production(self):
        paired = self.summarize()['paired']
        b = paired['summary']['b']
        self.assertEqual(b['fairnessPhaseSpeedup']['median'], 5.0)
        self.assertEqual(sorted(b['fairnessPhaseSpeedupByFleet']), ['10', '30', '50'])
        self.assertTrue(paired['decision']['b']['promoted'] and paired['decision']['c']['promoted'])
        self.assertNotIn('load-balance', paired['summary'])
        self.assertEqual(paired['comparison']['load-balance']['acceptedOn'], 'loadBalance')
        unfair = self.summarize(b=lambda case, rep: {'variance': 0.0300} if case[0] == 30 else {})['paired']
        self.assertFalse(unfair['decision']['b']['promoted'])
        self.assertEqual(unfair['summary']['b']['fairness']['lessFairBeyondNoise'], 1)

    def test_every_run_is_kept_in_arm_order(self):
        runs = self.summarize()['paired']['runs']
        self.assertEqual(len(runs), len(ms.ARMS) * len(CASES) * 2)
        self.assertEqual([run['arm'] for run in runs[::len(CASES) * 2]], ms.ORDER)

    def test_an_incomplete_or_mixed_run_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary))
            next((run / 'rows').glob('rep2-50-*-o.jsonl')).unlink()
            with self.assertRaisesRegex(ValueError, 'missing'):
                ms.summarize(run, run / 'out.json', 'jfr')
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary))
            path = next((run / 'rows').glob('rep1-10-*-n.jsonl'))
            lines = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
            lines[0]['budgetMs'] = 60000
            path.write_text('\n'.join(json.dumps(line) for line in lines) + '\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'one budget'):
                ms.summarize(run, run / 'out.json', 'jfr')
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary))
            path = next((run / 'rows').glob('rep1-10-*-o.jsonl'))
            lines = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
            lines[2]['variant'] = 'TABU_KOPT_LOAD_BALANCE'
            path.write_text('\n'.join(json.dumps(line) for line in lines) + '\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'unexpected variant'):
                ms.summarize(run, run / 'out.json', 'jfr')


if __name__ == '__main__':
    unittest.main()
