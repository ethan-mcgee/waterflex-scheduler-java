import copy
import json
import tempfile
import unittest
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import patch

import campaign_matrix as cm
from experiment_runtime import sha, write_new
from test_campaigns import configuration


class CampaignMatrixTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base = configuration()
        write_new(self.root / 'base.json', self.base)

    def spec(self):
        return {'version': 1, 'name': 'test-matrix', 'purpose': 'Contract fixtures only',
                'baseCampaign': {'path': 'base.json', 'sha256': sha(self.root / 'base.json')},
                'studies': [{'id': 'algorithms', 'factor': 'algorithm', 'control': self.base['configurations'][0],
                             'candidates': [self.base['configurations'][1]]}], 'outputLocation': str(self.root / 'registered')}

    def register(self):
        write_new(self.root / 'spec.json', self.spec())
        return cm.register(self.root / 'spec.json')

    def test_registers_original_bytes_and_separate_pinned_configurations(self):
        result = self.register(); directory = Path(result['directory'])
        self.assertEqual((directory / 'original-specification.json').read_bytes(), (self.root / 'spec.json').read_bytes())
        self.assertEqual(result['campaigns'][0]['configuration']['sha256'], sha(directory / 'algorithms.json'))
        with self.assertRaises(FileExistsError): cm.register(self.root / 'spec.json')

    def test_hidden_changed_factor_fails_before_registration(self):
        spec = self.spec(); spec['studies'][0]['candidates'][0]['selectedCountLimit'] += 1
        write_new(self.root / 'spec.json', spec)
        with self.assertRaises(ValueError): cm.register(self.root / 'spec.json')
        self.assertFalse((self.root / 'registered').exists())

    def test_null_and_missing_treatments_fail_before_registering(self):
        for value in (None, {}, {'id': 'broken'}):
            control = self.base['configurations'][0]
            with self.assertRaises(ValueError): cm.factor(control, value, 'algorithm')

    def test_registered_template_requires_real_pins_and_has_one_matched_factor(self):
        spec = json.loads((Path(__file__).resolve().parents[1] / 'experiments/configs/final-audit-matrix-template.json').read_text())
        self.assertEqual(spec['baseCampaign']['sha256'], '0' * 64)
        for study in spec['studies']: cm.factor(study['control'], study['candidates'][0], study['factor'])

    def test_read_only_analysis_validates_manifest_and_original_configuration(self):
        root = Path(self.register()['directory']); archive = self.root / 'archive'; archive.mkdir()
        (archive / 'original-config.json').write_bytes((root / 'algorithms.json').read_bytes())
        toolkit = cm.cr.toolkit_hashes(); write_new(archive / 'manifest.json', {'toolkit': toolkit})
        write_new(root / 'dispatch.json', {'registrationHash': sha(root / 'registration.json'), 'toolkit': toolkit})
        write_new(root / 'algorithms-archive.json', {'run': str(archive), 'manifestHash': sha(archive / 'manifest.json')})
        with patch.object(cm.cr, 'analyze', return_value={'test': True}) as analyzer:
            self.assertEqual(cm.analyze(root)['campaigns'][0]['analysis'], {'test': True})
            self.assertEqual(analyzer.call_count, 1)
            (archive / 'original-config.json').write_text('{}')
            with self.assertRaises(ValueError): cm.analyze(root)
            self.assertEqual(analyzer.call_count, 1)

    def test_move_family_changes_one_only_with_stable_order(self):
        control = self.base['configurations'][0]; candidate = copy.deepcopy(control)
        candidate['id'] = 'additional'; candidate['moves'].append({'family': 'subListChange', 'weight': 1})
        cm.factor(control, candidate, 'one-move-family')
        candidate['moves'][0]['weight'] = 2
        with self.assertRaises(ValueError): cm.factor(control, candidate, 'one-move-family')

    def test_termination_must_preserve_existing_enclosing_caps(self):
        control = self.base['configurations'][0]; candidate = copy.deepcopy(control)
        candidate['id'] = 'early-stop'; candidate['termination'].update(kind='unimproved-time', unimprovedMs=20)
        cm.factor(control, candidate, 'termination')
        candidate['termination']['stepCap'] = 1
        with self.assertRaises(ValueError): cm.factor(control, candidate, 'termination')

    def test_changed_registered_bytes_prevent_any_dispatch(self):
        root = Path(self.register()['directory']); path = root / 'algorithms.json'
        path.write_text(path.read_text() + '\n')
        with patch.object(cm.cr, 'new_run') as dispatch:
            with self.assertRaises(ValueError): cm.execute(root)
            dispatch.assert_not_called()

    def test_one_failed_launch_is_retained_and_cannot_retry(self):
        root = Path(self.register()['directory']); fake_run = self.root / 'archive'
        fake_run.mkdir(); write_new(fake_run / 'manifest.json', {'test': True})
        with patch.object(cm, 'measurement_lock', return_value=nullcontext()), \
             patch.object(cm.cr, 'new_run', return_value=fake_run) as dispatch, \
             patch.object(cm.cr, 'execute', side_effect=RuntimeError('retained test launch failure')):
            with self.assertRaises(RuntimeError): cm.execute(root)
            self.assertEqual(dispatch.call_count, 1)
            self.assertEqual(json.loads((root / 'terminal.json').read_text())['state'], 'FAILED')
            with self.assertRaises(ValueError): cm.execute(root)
            self.assertEqual(dispatch.call_count, 1)
