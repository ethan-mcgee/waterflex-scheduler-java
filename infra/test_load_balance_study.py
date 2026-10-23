import json
import tempfile
import unittest
from pathlib import Path

import load_balance_study as lb

CASES = [(5, 'CLUSTERED', 7001, 17), (20, 'MIXED_SHIFT', 7002, 17)]
MEASURES = {'variance': 0.02, 'maximumUtilization': 0.9, 'utilizationSpread': 0.5, 'absoluteDeviation': 0.2, 'loadBalance': 600000}


def phase(moves, best=500):
    return {'moveEvaluations': moves, 'solveMs': 1000, 'budgetMs': 1000, 'timeToBestMs': best}


def result(variant, case, cost=100_000, ceiling=102_000, reason='FAIRNESS_IMPROVEMENT', fairness_moves=40, best=500, **after):
    technicians, workload, fixture, seed = case
    line = {'type': 'result', 'variant': variant, 'technicians': technicians, 'workload': workload, 'fixtureSeed': fixture, 'seed': seed,
            'violations': 0, 'referencePhase': phase(100), 'fairnessPhase': phase(fairness_moves, best),
            'reference': {'costCents': 99_000}, 'after': {'costCents': cost}, 'costCeilingCents': ceiling, 'acceptanceReason': reason,
            'measures': {'reference': dict(MEASURES), 'after': {**MEASURES, **after}}}
    if variant == 'TABU_KOPT_LOAD_BALANCE':
        line['acceptedOn'] = 'loadBalance'
    return line


def write_run(root, budgets=(15000, 30000), reps=(1,), cases=CASES, arm=None, base=None):
    """{@code arm} and {@code base} give a function of (case, rep, budget) returning overrides for load balance's and b's rows."""
    (root / 'rows').mkdir(parents=True)
    (root / 'manifest.json').write_text('﻿' + json.dumps({'study': 'test'}), encoding='utf-8')
    for budget in budgets:
        for rep in reps:
            for case in cases:
                b = {'variance': 0.02 + rep * 0.0001}
                b.update(base(case, rep, budget) if base else {})
                options = {'variance': 0.012, 'maximumUtilization': 0.85, 'fairness_moves': 36}
                options.update(arm(case, rep, budget) if arm else {})
                lines = [{'type': 'provenance', 'revision': 'load-balance-study', 'budgetMs': budget},
                         result('TABU_KOPT', case, **b), result('TABU_KOPT_LOAD_BALANCE', case, **options)]
                name = f'rep{rep}-{budget}ms-{case[0]}-{case[1]}-{case[2]}-{case[3]}.jsonl'
                (root / 'rows' / name).write_text('\n'.join(json.dumps(line) for line in lines) + '\n', encoding='utf-8')
    return root


def edit_first_row(run, change):
    path = sorted((run / 'rows').glob('*.jsonl'))[0]
    lines = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
    change(lines)
    path.write_text('\n'.join(json.dumps(line) for line in lines) + '\n', encoding='utf-8')


class LoadBalanceStudyTest(unittest.TestCase):
    def summarize(self, **options):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary), **options)
            return lb.summarize(run, run / 'out.json', 'jfr')

    def refused(self, change, message):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary))
            edit_first_row(run, change)
            with self.assertRaisesRegex(ValueError, message):
                lb.summarize(run, run / 'out.json', 'jfr')

    def test_a_fairer_load_balance_at_the_same_cost_meets_the_rule_at_every_budget_with_one_repetition(self):
        document = self.summarize()
        self.assertTrue(document['decision']['switch'])
        for budget in ('15000', '30000'):
            reading = document['budgets'][budget]
            self.assertEqual((reading['cases'], reading['repetitions']), (2, 1))
            variance = reading['measures']['variance']
            self.assertEqual(variance['lower'], 2)
            self.assertAlmostEqual(variance['medianRatioToB'], 0.597, places=3)
            self.assertEqual(variance['medianRatioInterval'], [0.597, 0.597])
            # One repetition gives no spread between runs to read.
            self.assertIsNone(variance['higherBeyondNoise'])
            self.assertEqual(reading['speed']['fairnessPhase']['all']['median'], 0.9)
            self.assertEqual(sorted(reading['speed']['fairnessPhase']['byFleet']), ['20', '5'])
            self.assertEqual(sorted(reading['byWorkload']), ['CLUSTERED', 'MIXED_SHIFT'])
            self.assertEqual(reading['convergence']['b']['fairnessPhase'], {'medianShareOfBudget': 0.5, 'overNinetyPercent': 0, 'unavailable': 0})
            self.assertEqual(reading['acceptance']['load-balance'], {'FAIRNESS_IMPROVEMENT': 2})
            self.assertEqual({run['fixtureSeed'] for run in reading['runs']}, {7001, 7002})

    def test_repetitions_add_the_spread_between_b_runs(self):
        # b's variance spans 0.0201 to 0.0203; load balance is above it by more than that span in the 20-technician case.
        document = self.summarize(reps=(1, 2, 3), arm=lambda case, rep, budget: {'variance': 0.03} if case[0] == 20 else {})
        reading = document['budgets']['15000']['measures']['variance']
        self.assertEqual(document['budgets']['15000']['repetitions'], 3)
        self.assertEqual((reading['higher'], reading['higherBeyondNoise'], reading['higherBeyondTolerance']), (1, 1, 1))

    def test_a_case_less_fair_within_the_tolerance_is_not_counted_against_it(self):
        # Four percent above b's 0.0201: higher, but inside the five percent tolerance.
        document = self.summarize(arm=lambda case, rep, budget: {'variance': 0.0209} if case[0] == 20 else {})
        reading = document['budgets']['15000']['measures']['variance']
        self.assertEqual((reading['higher'], reading['higherBeyondTolerance']), (1, 0))

    def test_more_than_ten_percent_of_cases_less_fair_fails_the_variance_rule(self):
        document = self.summarize(arm=lambda case, rep, budget: {'variance': 0.03} if case[0] == 20 else {})
        self.assertFalse(document['budgets']['15000']['rule']['variance'])
        self.assertFalse(document['decision']['switch'])

    def test_a_median_below_one_with_an_interval_reaching_one_fails_the_variance_rule(self):
        cases = [(5, 'CLUSTERED', seed, 17) for seed in (7001, 7002, 7003)]
        ratios = {7001: 0.6, 7002: 0.95, 7003: 1.04}
        document = self.summarize(cases=cases, arm=lambda case, rep, budget: {'variance': 0.0201 * ratios[case[2]]})
        variance = document['budgets']['15000']['measures']['variance']
        self.assertAlmostEqual(variance['medianRatioToB'], 0.95, places=3)
        self.assertGreaterEqual(variance['medianRatioInterval'][1], 1)
        self.assertFalse(document['budgets']['15000']['rule']['variance'])

    def test_a_dearer_load_balance_fails_the_cost_rule(self):
        # 0.2 percent dearer than b's 100,000 cents: over the 0.1 percent allowance.
        document = self.summarize(arm=lambda case, rep, budget: {'cost': 100_200})
        reading = document['budgets']['15000']
        self.assertFalse(reading['rule']['cost'])
        self.assertEqual((reading['cost']['medianDifferenceCents'], reading['cost']['dearer']), (200.0, 2))
        self.assertFalse(document['decision']['switch'])

    def test_one_budget_failing_blocks_the_switch(self):
        document = self.summarize(arm=lambda case, rep, budget: {'maximumUtilization': 0.95} if budget == 30000 else {})
        self.assertTrue(document['budgets']['15000']['passes'])
        self.assertFalse(document['budgets']['30000']['rule']['maximum'])
        self.assertFalse(document['decision']['switch'])

    def test_an_accepted_result_over_its_ceiling_fails_the_rule(self):
        document = self.summarize(arm=lambda case, rep, budget: {'cost': 103_000} if case[0] == 20 else {})
        self.assertEqual(len(document['budgets']['15000']['outsideCeiling']), 1)
        self.assertFalse(document['budgets']['15000']['rule']['ceiling'])

    def test_a_rejected_result_is_not_bound_by_the_ceiling(self):
        document = self.summarize(arm=lambda case, rep, budget: {'cost': 103_000, 'reason': 'NO_POLICY_IMPROVEMENT'} if case[0] == 20 else {})
        self.assertEqual(document['budgets']['15000']['outsideCeiling'], [])

    def test_a_missing_time_to_best_is_counted_not_read_as_zero(self):
        document = self.summarize(arm=lambda case, rep, budget: {'best': None} if case[0] == 20 else {})
        reading = document['budgets']['15000']['convergence']['load-balance']['fairnessPhase']
        self.assertEqual((reading['medianShareOfBudget'], reading['unavailable']), (0.5, 1))

    def test_a_case_where_b_reads_zero_is_counted_and_left_out_of_the_ratios(self):
        document = self.summarize(base=lambda case, rep, budget: {'variance': 0} if case[0] == 20 else {})
        variance = document['budgets']['15000']['measures']['variance']
        self.assertEqual(variance['casesWithZeroB'], 1)
        self.assertIsNone(variance['medianRatioInterval'])
        self.assertFalse(document['budgets']['15000']['rule']['variance'])

    def test_load_balance_must_be_accepted_on_its_own_measure(self):
        self.refused(lambda lines: lines[2].pop('acceptedOn'), 'accepted on the wrong measure')

    def test_a_missing_measure_is_refused_not_read_as_zero(self):
        self.refused(lambda lines: lines[1]['measures']['after'].pop('loadBalance'), 'every after measure')

    def test_a_null_cost_is_refused(self):
        self.refused(lambda lines: lines[1]['after'].update(costCents=None), 'costCents must be an integer')

    def test_a_row_for_another_fixture_seed_is_refused(self):
        self.refused(lambda lines: lines[1].update(fixtureSeed=1), 'row does not match its file')

    def test_a_missing_fairness_phase_is_refused(self):
        self.refused(lambda lines: lines[2].pop('fairnessPhase'), 'fairnessPhase missing')

    def test_a_missing_repetition_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary), reps=(1, 2))
            (run / 'rows' / 'rep2-15000ms-5-CLUSTERED-7001-17.jsonl').unlink()
            with self.assertRaisesRegex(ValueError, 'missing b'):
                lb.summarize(run, run / 'out.json', 'jfr')

    def test_a_row_file_with_one_arm_is_refused(self):
        self.refused(lambda lines: lines.pop(), 'each arm expected once')


if __name__ == '__main__':
    unittest.main()
