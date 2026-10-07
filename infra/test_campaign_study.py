import copy
from pathlib import Path
import tempfile
import unittest

import campaign_profile as cp
import campaign_study as cs
from experiment_config import read_json
from experiment_runtime import sha, write_new


class CampaignStudyTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.template = read_json(Path(__file__).resolve().parents[1] / 'experiments/configs/campaign-v2-template.json')
        self.template['forks'] = 3

    def specification(self, kind='warmup'):
        entries = []
        probes = [200, 30000, 60000] if kind == 'warmup' else [1, 2]
        for index, probe in enumerate(probes):
            config = copy.deepcopy(self.template)
            if kind == 'warmup':
                config['warmup']['millisecondsPerFreshJvm'] = probe
            elif kind == 'concurrency':
                config['resources']['parallelCases'] = probe
                config['resources']['totalCpuAllocation'] = 2
                config['resources']['memoryLimitMiB'] = 64
            else:
                config['instrumentation']['jfr'] = 'disabled' if index == 0 else 'profile'
                config['instrumentation']['cohort'] = 'quality-basic' if index == 0 else 'diagnostic'
            path = self.base / f'probe-{index}.json'
            write_new(path, config)
            entries.append({'label': f'probe-{index}', 'configuration': {'path': str(path), 'sha256': sha(path)}})
        return {'version': 1, 'kind': kind, 'name': 'registered-study', 'purpose': 'Regression only',
            'campaigns': entries, 'rules': {'referenceLabel': 'probe-2' if kind == 'warmup' else 'probe-0',
            'metric': 'moveEvaluationsPerSecond', 'tolerancePercent': 5, 'draws': 10000, 'seed': 123,
            'confidenceLevel': .95, 'minimumRepetitions': 3}, 'outputLocation': str(self.base / 'runs')}

    def test_each_registered_factor_has_strict_matched_settings(self):
        spec = self.specification()
        self.assertEqual(len(cs.validate(spec, self.base)), 3)
        spec['rules']['referenceLabel'] = 'probe-0'
        with self.assertRaisesRegex(ValueError, '60 seconds'):
            cs.validate(spec, self.base)

    def test_hash_change_and_hidden_override_rejected(self):
        spec = self.specification()
        path = Path(spec['campaigns'][0]['configuration']['path'])
        value = read_json(path)
        value['runtime']['gc'] = 'G1GC'
        path.write_text(__import__('json').dumps(value))
        with self.assertRaisesRegex(ValueError, 'changed'):
            cs.validate(spec, self.base)
        spec['campaigns'][0]['configuration']['sha256'] = sha(path)
        with self.assertRaisesRegex(ValueError, 'declared factor'):
            cs.validate(spec, self.base)

    def test_concurrency_keeps_total_resources_and_native_one(self):
        self.assertEqual(len(cs.validate(self.specification('concurrency'), self.base)), 2)

    def test_profile_is_registered_separately(self):
        self.assertEqual(len(cs.validate(self.specification('profile'), self.base)), 2)

    def test_calibration_ambiguity_stays_visible(self):
        rules = {'draws': 10000, 'seed': 123, 'confidenceLevel': .95}
        self.assertEqual(cs.ratio_interval([1, 1, 1], rules), [1, 1])
        bounds = cs.ratio_interval([.5, 1, 2], rules)
        self.assertLess(bounds[0], .95)
        self.assertGreater(bounds[1], 1.05)

    def test_missing_jfr_events_are_null_with_reason(self):
        report = cp.summarize({'recording': {'events': []}})
        self.assertIsNone(report['gcDurationSeconds'])
        self.assertIsNone(report['allocationSampleWeightBytes'])
        self.assertIsNotNone(report['gcUnavailableReason'])

    def test_actual_jfr_weights_duration_and_stack(self):
        report = cp.summarize({'recording': {'events': [
            {'type': 'jdk.ObjectAllocationSample', 'values': {'weight': 2048}},
            {'type': 'jdk.GarbageCollection', 'values': {'duration': 'PT0.002S'}},
            {'type': 'jdk.ExecutionSample', 'values': {'stackTrace': {'frames': [{'method': {'name': 'evaluate', 'type': {'name': 'RouteEvaluator'}}}]}}}
        ]}})
        self.assertEqual(report['allocationSampleWeightBytes'], 2048)
        self.assertEqual(report['gcDurationSeconds'], .002)
        self.assertEqual(report['executionSampleLeafFrames'], {'RouteEvaluator.evaluate': 1})

    def test_malformed_profile_values_fail_instead_of_defaulting(self):
        for event in ({'type': 'jdk.ObjectAllocationSample', 'values': {}}, {'type': 'jdk.GarbageCollection', 'values': {'duration': None}}):
            with self.assertRaises(ValueError):
                cp.summarize({'recording': {'events': [event]}})


if __name__ == '__main__':
    unittest.main()
