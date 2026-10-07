import copy
import json
import tempfile
import unittest
from pathlib import Path

from campaign_promotion import evaluate, review, latency_gate
from experiment_runtime import sha


def receipt():
    workload = {'requested': 100, 'completed': 100, 'validAccepted': 90, 'invalidAccepted': 0,
                'failures': 1, 'timeouts': 1, 'incomplete': 8, 'overtimeIncreases': 0,
                'p50Ms': '10', 'p95Ms': '100', 'cleanupP95Ms': '5'}
    return {'version': 1, 'kind': 'application-confirmation', 'candidateConfigurationHash': 'a' * 64,
            'frozenConfigurationHash': 'a' * 64, 'tuningDatasets': ['tuning'],
            'confirmationDatasets': ['held-out-one', 'held-out-two'], 'productionBudgetMs': 20000,
            'searchBudgetMs': 15000, 'warmupDisposition': 'stable', 'concurrencyDisposition': 'stable',
            'origin': 'historical', 'costUpper95Cents': '-1',
            'families': [{'id': 'clustered', 'costDifferenceCents': '-2', 'fairnessDifference': '-0.01', 'pairedDatasets': 2}],
            'control': workload, 'candidate': copy.deepcopy(workload), 'matchedLoadHash': 'b' * 64,
            'candidateLoadHash': 'b' * 64, 'matchedResourcesHash': 'c' * 64, 'candidateResourcesHash': 'c' * 64,
            'servedControl': ['v1', 'v2'], 'servedCandidate': ['v1', 'v2']}


class CampaignPromotionTests(unittest.TestCase):
    def test_long_decimal_control_does_not_round_into_acceptance(self):
        self.assertFalse(latency_gate('100.00000000000000000000000001', '105.000000000000000000000000011'))
        self.assertTrue(latency_gate('100.00000000000000000000000001', '105.0000000000000000000000000105'))

    def test_impossible_outcome_counts_fail_instead_of_selecting_a_winner(self):
        value = receipt(); value['candidate']['timeouts'] = 101
        with self.assertRaises(ValueError): evaluate(value)

    def test_exact_p95_boundary_and_no_deployment_authority(self):
        value = receipt(); value['candidate']['p95Ms'] = '105'
        self.assertTrue(evaluate(value)['eligibleForOwnerReview'])
        self.assertFalse(evaluate(value)['deploymentAuthorized'])
        value['candidate']['p95Ms'] = '105.000000000000000000000001'
        self.assertIn('p95WithinFivePercent', evaluate(value)['failedGates'])

    def test_tuning_overlap_or_changed_finalist_blocks_review(self):
        for field, value, gate in [('confirmationDatasets', ['tuning', 'held-out-two'], 'untouchedConfirmation'),
                                   ('candidateConfigurationHash', 'd' * 64, 'frozenFinalist')]:
            raw = receipt(); raw[field] = value
            self.assertIn(gate, evaluate(raw)['failedGates'])

    def test_same_served_demand_and_load_resources_are_required(self):
        for field, value, gate in [('servedCandidate', ['v1', 'v3'], 'sameServedDemand'),
                                   ('candidateLoadHash', 'd' * 64, 'matchedLoad'),
                                   ('candidateResourcesHash', 'd' * 64, 'matchedResources')]:
            raw = receipt(); raw[field] = value
            self.assertIn(gate, evaluate(raw)['failedGates'])

    def test_every_adverse_outcome_and_missing_dispatch_blocks(self):
        for field in ('failures', 'timeouts', 'incomplete', 'invalidAccepted', 'overtimeIncreases'):
            raw = receipt(); raw['candidate'][field] += 1
            self.assertFalse(evaluate(raw)['eligibleForOwnerReview'], field)
        raw = receipt(); raw['candidate']['completed'] -= 1
        self.assertIn('completeDenominators', evaluate(raw)['failedGates'])

    def test_family_fairness_preserves_tiny_decimal_regression(self):
        raw = receipt(); raw['families'][0]['fairnessDifference'] = '0.00000000000000000000000000000000001'
        self.assertIn('noFamilyRegression', evaluate(raw)['failedGates'])
        raw['costUpper95Cents'] = '0'
        self.assertIn('costUpperBoundBelowZero', evaluate(raw)['failedGates'])

    def test_calibration_synthetic_short_budget_are_explicit_blocks(self):
        raw = receipt(); raw.update(warmupDisposition='ambiguous', origin='synthetic', productionBudgetMs=3000)
        self.assertEqual(set(evaluate(raw)['failedGates']), {'calibrated', 'historicalEvidence', 'productionAllowances'})

    def test_missing_null_nonfinite_boolean_unknown_values_fail(self):
        for value in (None, True, float('nan'), 'NaN', 'Infinity'):
            raw = receipt(); raw['candidate']['p95Ms'] = value
            with self.assertRaises(ValueError): evaluate(raw)
        raw = receipt(); raw['candidate']['invented'] = 0
        with self.assertRaises(ValueError): evaluate(raw)
        raw = receipt(); del raw['costUpper95Cents']
        with self.assertRaises(ValueError): evaluate(raw)

    def test_receipt_bytes_are_pinned_and_read_only(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); data = root / 'receipt.json'; data.write_text(json.dumps(receipt()))
            spec = root / 'review.json'
            spec.write_text(json.dumps({'version': 1, 'confirmation': {'path': 'receipt.json', 'sha256': sha(data)}}))
            before = {file.name: file.read_bytes() for file in root.iterdir()}
            self.assertTrue(review(spec)['eligibleForOwnerReview'])
            self.assertEqual(before, {file.name: file.read_bytes() for file in root.iterdir()})
            data.write_text(json.dumps(receipt()) + '\n')
            with self.assertRaises(ValueError): review(spec)
