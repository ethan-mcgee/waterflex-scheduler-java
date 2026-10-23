import json
import tempfile
import unittest
from pathlib import Path

import load_balance_study as lb

CASES = [(5, 'CLUSTERED', 17), (20, 'NEAR_CAPACITY', 23)]
MEASURES = {'variance': 0.02, 'maximumUtilization': 0.9, 'utilizationSpread': 0.5, 'absoluteDeviation': 0.2, 'loadBalance': 600000}


def result(variant, case, cost=100_000, ceiling=102_000, reason='FAIRNESS_IMPROVEMENT', fairness_moves=40, **after):
    technicians, workload, seed = case
    line = {'type': 'result', 'variant': variant, 'technicians': technicians, 'workload': workload, 'seed': seed, 'violations': 0,
            'referencePhase': {'moveEvaluations': 100, 'solveMs': 1000}, 'fairnessPhase': {'moveEvaluations': fairness_moves, 'solveMs': 1000},
            'reference': {'costCents': 99_000}, 'after': {'costCents': cost}, 'costCeilingCents': ceiling, 'acceptanceReason': reason,
            'measures': {'reference': dict(MEASURES), 'after': {**MEASURES, **after}}}
    if variant == 'TABU_KOPT_LOAD_BALANCE':
        line['acceptedOn'] = 'loadBalance'
    return line


def write_run(root, budgets=(15000, 30000), arm=None, base=None):
    """{@code arm} and {@code base} give a function of (case, rep) returning overrides for load balance's and b's rows."""
    (root / 'rows').mkdir(parents=True)
    (root / 'manifest.json').write_text('﻿' + json.dumps({'study': 'test'}), encoding='utf-8')
    for budget in budgets:
        for rep in (1, 2, 3):
            for case in CASES:
                b = {'variance': 0.02 + rep * 0.0001}
                b.update(base(case, rep) if base else {})
                options = {'variance': 0.012, 'maximumUtilization': 0.85, 'fairness_moves': 36}
                options.update(arm(case, rep) if arm else {})
                lines = [{'type': 'provenance', 'revision': 'load-balance-study', 'budgetMs': budget},
                         result('TABU_KOPT', case, **b), result('TABU_KOPT_LOAD_BALANCE', case, **options)]
                (root / 'rows' / f'rep{rep}-{budget}ms-{case[0]}-{case[1]}-{case[2]}.jsonl').write_text(
                    '\n'.join(json.dumps(line) for line in lines) + '\n', encoding='utf-8')
    return root


class LoadBalanceStudyTest(unittest.TestCase):
    def summarize(self, **options):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary), **options)
            return lb.summarize(run, run / 'out.json', 'jfr')

    def test_a_fairer_load_balance_at_the_same_cost_meets_the_rule_at_every_budget(self):
        document = self.summarize()
        self.assertTrue(document['decision']['switch'])
        for budget in ('15000', '30000'):
            reading = document['budgets'][budget]
            self.assertEqual(reading['cases'], 2)
            self.assertEqual(reading['repetitions'], 3)
            self.assertEqual(reading['measures']['variance']['lower'], 2)
            self.assertAlmostEqual(reading['measures']['variance']['medianRatioToB'], 0.594, places=3)
            self.assertEqual(reading['speed']['fairnessPhase']['all']['median'], 0.9)
            self.assertEqual(sorted(reading['speed']['fairnessPhase']['byFleet']), ['20', '5'])
            self.assertEqual(reading['acceptance']['load-balance'], {'FAIRNESS_IMPROVEMENT': 6})
            self.assertEqual(len(reading['runs']), 12)

    def test_a_dearer_load_balance_fails_the_cost_rule(self):
        # 0.2 percent dearer than b's 100,000 cents: over the 0.1 percent allowance.
        document = self.summarize(arm=lambda case, rep: {'cost': 100_200})
        reading = document['budgets']['15000']
        self.assertFalse(reading['rule']['cost'])
        self.assertEqual(reading['cost']['medianDifferenceCents'], 200.0)
        self.assertEqual(reading['cost']['dearer'], 2)
        self.assertFalse(document['decision']['switch'])

    def test_one_budget_failing_blocks_the_switch(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary), budgets=(15000,))
            write_run_budget = write_run(Path(temporary) / 'other', budgets=(30000,), arm=lambda case, rep: {'maximumUtilization': 0.95})
            for path in (write_run_budget / 'rows').glob('*.jsonl'):
                path.rename(run / 'rows' / path.name)
            document = lb.summarize(run, run / 'out.json', 'jfr')
        self.assertTrue(document['budgets']['15000']['passes'])
        self.assertFalse(document['budgets']['30000']['rule']['maximum'])
        self.assertFalse(document['decision']['switch'])

    def test_less_fair_beyond_b_spread_is_counted(self):
        # b's variance spans 0.0201 to 0.0203; load balance's mean is above it by more than that span in one case.
        document = self.summarize(arm=lambda case, rep: {'variance': 0.03} if case[0] == 20 else {})
        reading = document['budgets']['15000']['measures']['variance']
        self.assertEqual(reading['higher'], 1)
        self.assertEqual(reading['higherBeyondNoise'], 1)
        # One case in two is over the ten percent allowed.
        self.assertFalse(document['budgets']['15000']['rule']['variance'])

    def test_an_accepted_result_over_its_ceiling_fails_the_rule(self):
        document = self.summarize(arm=lambda case, rep: {'cost': 103_000} if rep == 2 else {})
        self.assertEqual(len(document['budgets']['15000']['outsideCeiling']), 2)
        self.assertFalse(document['budgets']['15000']['rule']['ceiling'])

    def test_a_rejected_result_is_not_bound_by_the_ceiling(self):
        document = self.summarize(arm=lambda case, rep: {'cost': 103_000, 'reason': 'NO_POLICY_IMPROVEMENT'} if rep == 2 else {})
        self.assertEqual(document['budgets']['15000']['outsideCeiling'], [])

    def test_load_balance_must_be_accepted_on_its_own_measure(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary))
            path = sorted((run / 'rows').glob('*.jsonl'))[0]
            lines = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
            del lines[2]['acceptedOn']
            path.write_text('\n'.join(json.dumps(line) for line in lines) + '\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'accepted on the wrong measure'):
                lb.summarize(run, run / 'out.json', 'jfr')

    def test_a_missing_measure_is_refused_not_read_as_zero(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary))
            path = sorted((run / 'rows').glob('*.jsonl'))[0]
            lines = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
            del lines[1]['measures']['after']['loadBalance']
            path.write_text('\n'.join(json.dumps(line) for line in lines) + '\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'every after measure'):
                lb.summarize(run, run / 'out.json', 'jfr')

    def test_a_null_cost_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary))
            path = sorted((run / 'rows').glob('*.jsonl'))[0]
            lines = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
            lines[1]['after']['costCents'] = None
            path.write_text('\n'.join(json.dumps(line) for line in lines) + '\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'costCents must be an integer'):
                lb.summarize(run, run / 'out.json', 'jfr')

    def test_a_missing_repetition_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary))
            (run / 'rows' / 'rep2-15000ms-5-CLUSTERED-17.jsonl').unlink()
            with self.assertRaisesRegex(ValueError, 'missing b'):
                lb.summarize(run, run / 'out.json', 'jfr')

    def test_a_row_file_with_one_arm_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary))
            path = sorted((run / 'rows').glob('*.jsonl'))[0]
            lines = path.read_text(encoding='utf-8').splitlines()
            path.write_text('\n'.join(lines[:2]) + '\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'each arm expected once'):
                lb.summarize(run, run / 'out.json', 'jfr')


if __name__ == '__main__':
    unittest.main()
