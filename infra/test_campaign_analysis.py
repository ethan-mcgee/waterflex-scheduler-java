import copy
from pathlib import Path
import unittest

import campaign_analysis as ca
import campaign_config as cc
from experiment_config import read_json


class CampaignAnalysisTests(unittest.TestCase):
    def fixture(self, forks=1):
        config = read_json(Path(__file__).resolve().parents[1] / 'experiments/configs/campaign-v2-template.json')
        config['analysis']['method'] = 'paired-dataset-bootstrap'
        config['forks'] = forks
        config['solverSeeds'] = [17]
        config['datasets'] *= 2
        config['datasets'][0] = copy.deepcopy(config['datasets'][0])
        config['datasets'][1] = copy.deepcopy(config['datasets'][1])
        config['datasets'][1]['id'] = 'dataset-b'
        config['datasets'][1]['input']['sha256'] = 'a' * 64
        config['datasets'][1]['family'] = 'second'
        blocks = cc.expand(config)
        observations, loaded = {}, {}
        for block in blocks:
            for case in block['cases']:
                observations[case['id']] = {'case': case, 'state': 'SUCCEEDED'}
                difference = -10 if case['configurationId'] == 'la' else 0
                loaded[case['id']] = {'valid': True, 'result': self.result(100 + difference)}
        return config, blocks, observations, loaded

    @staticmethod
    def result(cost):
        return {'layer': 'solver', 'wallMs': 100, 'metrics': {'costCents': cost, 'overtimeMinutes': 0},
                'policyMetrics': {'costCents': cost, 'overtimeMinutes': 0, 'fairness': {'variance': '0.01'}},
                'nativeMeasurement': {'solveMs': 100, 'moveEvaluationCount': 100},
                'proposal': {'routes': {'tech': ['customer']}}}

    def report(self, fixture):
        return ca.paired(*fixture, 'runtime-a')

    def test_deterministic_whole_dataset_interval_and_cost_gate(self):
        fixture = self.fixture(2)
        first = self.report(fixture)
        self.assertEqual(first, self.report(fixture))
        comparison = first['comparisons'][0]
        self.assertEqual(comparison['metrics']['costCents']['interval'], [-10, -10])
        self.assertEqual(comparison['datasetCount'], 2)
        self.assertEqual(comparison['pairedCount'], 4)
        self.assertTrue(comparison['costGateSatisfied'])
        self.assertFalse(first['automaticPromotion'])

    def test_unequal_repetitions_are_not_independent_datasets(self):
        config, blocks, observations, loaded = self.fixture(10)
        for block in blocks:
            for case in block['cases']:
                if case['datasetId'] == 'dataset-a' and case['fork'] > 0:
                    observations.pop(case['id'])
                    loaded.pop(case['id'])
                elif case['configurationId'] == 'la':
                    loaded[case['id']]['result'] = self.result(80 if case['datasetId'] == 'dataset-a' else 110)
        comparison = self.report((config, blocks, observations, loaded))['comparisons'][0]
        self.assertEqual(comparison['metrics']['costCents']['estimate'], -5)
        self.assertEqual(comparison['pairedCount'], 11)
        self.assertEqual(comparison['families']['second']['costCents'], 10)
        self.assertFalse(comparison['costGateSatisfied'])

    def test_missing_measurement_is_never_imputed_or_dropped(self):
        fixture = self.fixture()
        next(iter(fixture[3].values()))['result'].pop('policyMetrics')
        metric = self.report(fixture)['comparisons'][0]['metrics']['fairnessVariance']
        self.assertIsNone(metric['estimate'])
        self.assertIsNone(metric['interval'])

    def test_all_failure_states_stay_in_requested_denominator(self):
        config, blocks, observations, loaded = self.fixture(3)
        candidates = [case for block in blocks for case in block['cases'] if case['configurationId'] == 'la']
        for case, state in zip(candidates, ('FAILED', 'INTERRUPTED', 'ABANDONED')):
            observations[case['id']]['state'] = state
            loaded.pop(case['id'])
        observations.pop(candidates[3]['id'])
        loaded.pop(candidates[3]['id'])
        report = self.report((config, blocks, observations, loaded))
        counts = report['counts']['la']
        self.assertEqual(counts['requested'], 6)
        self.assertEqual(counts['reliabilityDenominator'], 6)
        self.assertEqual([counts[key] for key in ('failed', 'interrupted', 'abandoned', 'missing')], [1, 1, 1, 1])
        self.assertEqual(len(report['unmatched']), 4)
        self.assertFalse(report['comparisons'][0]['costGateSatisfied'])

    def test_pair_identity_mismatch_and_duplicate_are_rejected(self):
        fixture = self.fixture()
        fixture[1][0]['cases'][0]['targetHash'] = 'changed-target'
        with self.assertRaisesRegex(ValueError, 'Pair identity'):
            self.report(fixture)
        fixture = self.fixture()
        fixture[1].append(copy.deepcopy(fixture[1][0]))
        with self.assertRaisesRegex(ValueError, 'Duplicate requested'):
            self.report(fixture)

    def test_served_identity_difference_blocks_cost_comparison(self):
        fixture = self.fixture()
        for row in fixture[3].values():
            row['result']['proposal']['routes']['tech'] = ['different']
            break
        self.assertIsNone(self.report(fixture)['comparisons'][0]['metrics']['costCents']['estimate'])

    def test_repair_and_invalid_receipts_never_rank_feasible_cost(self):
        fixture = self.fixture()
        for dataset in fixture[0]['datasets']:
            dataset['cohort'] = 'repair'
        report = self.report(fixture)
        self.assertEqual(report['comparisons'], [])
        self.assertEqual(len(report['unmatched']), 2)

    def test_single_dataset_never_produces_independent_interval(self):
        self.assertIsNone(ca.interval([-10], {'seed': 1, 'draws': 10000, 'confidenceLevel': .95})['interval'])

    def test_null_nonfinite_negative_or_boolean_decimal_rejected(self):
        for value in (None, True, 'NaN', 'Infinity', '-1', 'no'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                ca.decimal_number(value)


if __name__ == '__main__':
    unittest.main()
