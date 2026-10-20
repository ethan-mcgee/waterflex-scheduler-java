import json
import tempfile
import unittest
from pathlib import Path

import variance_study as vs


def result(variant, case, fairness_moves, cost, variance, ceiling=10_000, reason='FAIRNESS_IMPROVEMENT', maximum=0.9):
    technicians, workload, seed = case
    line = {'type': 'result', 'variant': variant, 'technicians': technicians, 'workload': workload, 'seed': seed, 'violations': 0,
            'referencePhase': {'moveEvaluations': 100, 'solveMs': 1000}, 'fairnessPhase': {'moveEvaluations': fairness_moves, 'solveMs': 1000},
            'reference': {'costCents': 990}, 'after': {'costCents': cost, 'fairness': {'variance': variance}}, 'costCeilingCents': ceiling,
            'acceptanceReason': reason,
            'measures': {'reference': {'variance': 0.03, 'maximumUtilization': 0.95, 'utilizationSpread': 0.5, 'absoluteDeviation': 0.2, 'loadBalance': 0.6},
                         'after': {'variance': variance, 'maximumUtilization': maximum, 'utilizationSpread': 0.4,
                                   'absoluteDeviation': 0.1, 'loadBalance': 0.5}}}
    accepted = vs.ACCEPTED_ON.get(vs.ARMS[variant])
    if accepted is not None:
        line['acceptedOn'] = accepted
    return line


POLICY_VARIANTS = ('TABU_KOPT_MIN_MAX_UTILIZATION', 'TABU_KOPT_UTILIZATION_SPREAD', 'TABU_KOPT_ABSOLUTE_DEVIATION', 'TABU_KOPT_LOAD_BALANCE')


CASES = [(20, 'CLUSTERED', 17), (50, 'DISPERSED', 23)]


def write_run(root, budgets=(15000,), b=None):
    """{@code b} changes option (b)'s row: a function of (case, rep) returning keyword overrides."""
    (root / 'rows').mkdir()
    (root / 'manifest.json').write_text('﻿' + json.dumps({'study': 'test'}), encoding='utf-8')
    for budget in budgets:
        for rep in (1, 2):
            for case in CASES:
                base_variance = 0.0200 + rep * 0.0001  # a spread of 0.0001 between repetitions
                options = {'fairness_moves': 50, 'cost': 1000, 'variance': 0.0150}
                if b is not None:
                    options.update(b(case, rep))
                lines = [{'type': 'provenance', 'revision': 'variance-study', 'budgetMs': budget},
                         result('TABU_KOPT', case, 40, 1000, base_variance),
                         result('TABU_KOPT_BINARY_VARIANCE', case, options['fairness_moves'], options['cost'], options['variance'],
                                ceiling=options.get('ceiling', 10_000)),
                         result('TABU_KOPT_INCREMENTAL_VARIANCE', case, 60, 1000, 0.0150)]
                # Each policy is fairer than production on the highest utilization and dearer by one cent.
                lines += [result(variant, case, 40, 1001, 0.0250, maximum=0.8) for variant in POLICY_VARIANTS]
                name = f'rep{rep}-{budget}ms-{case[0]}-{case[1]}-{case[2]}.jsonl'
                (root / 'rows' / name).write_text('\n'.join(json.dumps(line) for line in lines) + '\n', encoding='utf-8')
    return root


class VarianceStudyTest(unittest.TestCase):
    def summarize(self, **options):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary), **options)
            return vs.summarize(run, run / 'out.json', 'jfr')

    def test_a_faster_fairer_arm_passes_every_bar_at_every_budget(self):
        document = self.summarize(budgets=(15000, 60000))
        self.assertEqual(sorted(document['budgets']), ['15000', '60000'])
        b = document['budgets']['15000']['summary']['b']
        self.assertEqual(b['fairnessPhaseSpeedup']['median'], 1.25)
        self.assertEqual(b['fairness']['fairer'], 2)
        self.assertEqual(b['bars'], {'speed': True, 'fairness': True, 'cost': True, 'ceiling': True})
        self.assertTrue(document['decision']['b']['promoted'])
        self.assertTrue(document['decision']['c']['promoted'])
        self.assertEqual(document['budgets']['15000']['summary']['c']['fairnessPhaseSpeedup']['median'], 1.5)
        self.assertNotIn('min-max', document['budgets']['15000']['summary'])

    def test_the_policies_are_compared_on_every_measure(self):
        comparison = self.summarize()['budgets']['15000']['comparison']
        self.assertEqual(sorted(comparison), sorted(vs.ARMS.values()))
        spread = comparison['spread']
        self.assertEqual(spread['acceptedOn'], 'utilizationSpread')
        self.assertEqual(comparison['baseline']['acceptedOn'], 'variance')
        self.assertEqual(sorted(spread['measures']), sorted(vs.MEASURES))
        self.assertEqual(spread['measures']['maximumUtilization']['medianRatioToBaseline'], 0.889)
        self.assertEqual((spread['measures']['maximumUtilization']['lowerThanBaseline'], spread['measures']['variance']['higherThanBaseline']), (2, 2))
        self.assertEqual((spread['cost']['medianDifferenceCents'], spread['cost']['dearer']), (1, 2))
        self.assertEqual(spread['acceptance'], {'FAIRNESS_IMPROVEMENT': 4})

    def test_every_run_of_every_arm_is_kept(self):
        runs = self.summarize()['budgets']['15000']['runs']
        self.assertEqual(len(runs), len(vs.ARMS) * len(CASES) * 2)
        self.assertEqual([run['arm'] for run in runs[::4]], list(vs.ARMS.values()))
        first = runs[0]
        self.assertEqual((first['arm'], first['technicians'], first['rep'], first['costCents']), ('baseline', 20, 1, 1000))
        self.assertEqual((first['fairnessMovesPerSecond'], first['measures']['maximumUtilization']), (40.0, '0.9'))
        self.assertEqual((first['referenceCostCents'], first['referenceMeasures']['maximumUtilization']), (990, '0.95'))

    def test_a_policy_row_accepted_on_another_measure_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary))
            path = next((run / 'rows').glob('rep1-*-20-*.jsonl'))
            lines = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
            lines[4]['acceptedOn'] = 'variance'
            path.write_text('\n'.join(json.dumps(line) for line in lines) + '\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'wrong measure'):
                vs.summarize(run, run / 'out.json', 'jfr')

    def test_a_dearer_case_is_allowed_when_the_median_is_not_dearer(self):
        document = self.summarize(b=lambda case, rep: {'cost': 1050} if case[0] == 50 else {'cost': 900})
        b = document['budgets']['15000']['summary']['b']
        self.assertEqual((b['cost']['cheaper'], b['cost']['dearer']), (1, 1))
        self.assertTrue(b['bars']['cost'])

    def test_each_bar_fails_on_its_own(self):
        slow = self.summarize(b=lambda case, rep: {'fairness_moves': 44})
        self.assertFalse(slow['decision']['b']['promoted'])
        self.assertEqual(slow['budgets']['15000']['summary']['b']['bars']['speed'], False)
        # Less fair by more than the baseline's 0.0001 spread in one case.
        unfair = self.summarize(b=lambda case, rep: {'variance': 0.0300} if case[0] == 20 else {})
        self.assertEqual(unfair['budgets']['15000']['summary']['b']['fairness']['lessFairBeyondNoise'], 1)
        self.assertFalse(unfair['decision']['b']['promoted'])
        dearer = self.summarize(b=lambda case, rep: {'cost': 1100})
        self.assertFalse(dearer['budgets']['15000']['summary']['b']['bars']['cost'])
        outside = self.summarize(b=lambda case, rep: {'cost': 1000, 'ceiling': 999})
        self.assertFalse(outside['budgets']['15000']['summary']['b']['bars']['ceiling'])
        self.assertEqual(len(outside['budgets']['15000']['outsideCeiling']), 4)

    def test_a_bar_must_hold_at_every_budget(self):
        document = self.summarize(budgets=(15000, 60000), b=lambda case, rep: {})
        self.assertTrue(document['decision']['b']['promoted'])
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary), budgets=(15000, 60000))
            for path in (run / 'rows').glob('*-60000ms-*.jsonl'):
                lines = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
                lines[2]['fairnessPhase']['moveEvaluations'] = 41
                path.write_text('\n'.join(json.dumps(line) for line in lines) + '\n', encoding='utf-8')
            document = vs.summarize(run, run / 'out.json', 'jfr')
            self.assertTrue(document['budgets']['15000']['summary']['b']['passes'])
            self.assertFalse(document['decision']['b']['promoted'])

    def test_an_incomplete_or_mismatched_run_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary))
            path = next((run / 'rows').glob('rep2-*-50-*.jsonl'))
            path.unlink()
            with self.assertRaisesRegex(ValueError, 'missing'):
                vs.summarize(run, run / 'out.json', 'jfr')
        with tempfile.TemporaryDirectory() as temporary:
            run = write_run(Path(temporary))
            path = next((run / 'rows').glob('rep1-*-20-*.jsonl'))
            lines = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
            lines[0]['budgetMs'] = 60000
            path.write_text('\n'.join(json.dumps(line) for line in lines) + '\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'budget'):
                vs.summarize(run, run / 'out.json', 'jfr')


if __name__ == '__main__':
    unittest.main()
