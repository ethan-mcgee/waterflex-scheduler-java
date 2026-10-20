import json
import tempfile
import unittest
from pathlib import Path

import stack_profiles as sp


def frame(owner, method):
    return {'method': {'type': {'name': owner}, 'name': method}}


def sample(thread, *frames):
    return {'type': 'jdk.ExecutionSample', 'values': {'sampledThread': {'javaName': thread}, 'stackTrace': {'frames': [frame(*item) for item in frames]}}}


def allocation(thread, owner, weight, *frames):
    return {'type': 'jdk.ObjectAllocationSample', 'values': {'eventThread': {'javaName': thread}, 'objectClass': {'name': owner}, 'weight': weight,
                                                              'stackTrace': {'frames': [frame(*item) for item in frames]}}}


VARIANCE = ('dev.waterflex.scheduler.optimizer.RouteScoringFacts', 'varianceUnits')
ENGINE = ('ai.timefold.solver.core.impl.localsearch.DefaultLocalSearchPhase', 'solve')


def receipt(proposal, thread, phases=('REFERENCE', 'FAIRNESS'), recordings=None):
    body = {'proposalId': proposal, 'phases': [{'name': name, 'budgetMs': 100, 'solveMs': 100, 'termination': 'TIME_LIMIT'} for name in phases]}
    if recordings is not None:
        body['profile'] = {'thread': thread, 'recordings': recordings}
    return {'tenant': 'loadtest', 'requestId': proposal, 'body': body, 'solves': []}


def run(image, step, fleet, receipts):
    return {'step': step, 'image': image, 'fleet': fleet, 'seed': 1, 'report': {'study': {'receipts': receipts}}}


class StackProfilesTest(unittest.TestCase):
    def setUp(self):
        self.documents = {}
        self.original = sp.jfr_document
        sp.jfr_document = lambda path, jfr: self.documents[path.name]

    def tearDown(self):
        sp.jfr_document = self.original

    def record(self, folder, name, events):
        (folder / name).write_bytes(b'FLR\0')
        self.documents[name] = {'recording': {'events': events}}

    def test_only_the_calculation_threads_samples_are_attributed_and_grouped_by_image_step_and_fleet(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            # Two queued calculations overlap: each recording holds both threads' samples, and the compiler's.
            self.record(folder, 'p1-REFERENCE.jfr', [sample('daily-queue-0', ENGINE), sample('daily-queue-1', VARIANCE), sample('C2 CompilerThread0', ENGINE)])
            self.record(folder, 'p1-FAIRNESS.jfr', [sample('daily-queue-0', VARIANCE), sample('daily-queue-0', VARIANCE), allocation('daily-queue-1', 'java.math.BigDecimal', 64, VARIANCE)])
            self.record(folder, 'p2-REFERENCE.jfr', [sample('daily-queue-1', ENGINE)])
            self.record(folder, 'p2-FAIRNESS.jfr', [sample('daily-queue-1', ENGINE), allocation('daily-queue-1', 'java.math.BigDecimal', 64, VARIANCE)])
            evidence = {'runs': [
                run('after', 2, 20, [receipt('p1', 'daily-queue-0', recordings={'REFERENCE': 'p1-REFERENCE.jfr', 'FAIRNESS': 'p1-FAIRNESS.jfr'}),
                                     receipt('p2', 'daily-queue-1', recordings={'REFERENCE': 'p2-REFERENCE.jfr', 'FAIRNESS': 'p2-FAIRNESS.jfr'})])]}
            profiles = sp.summarize(evidence, folder, 'jfr')
            self.assertEqual(sorted(profiles), ['after fairness', 'after reference', 'after step2 20t fairness', 'after step2 20t reference'])
            fairness = profiles['after fairness']
            self.assertEqual(fairness['recordings'], 2)
            self.assertEqual(fairness['samples'], {'variance (non-decimal)': 2, 'engine': 1})
            self.assertEqual(fairness['allocationBytes'], {'decimal: variance': 64}, 'a BigDecimal allocated under a variance frame')
            self.assertEqual(fairness['otherThreadSamples'], 1, "p1's fairness recording held one of p2's allocation samples")
            self.assertEqual(profiles['after reference']['otherThreadSamples'], 2)
            self.assertEqual(profiles['after step2 20t reference']['samplesPercent'], {'engine': 100.0})

    def test_a_receipt_or_file_that_cannot_stand_for_its_phase_refuses_the_block(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            self.record(folder, 'p1-REFERENCE.jfr', [sample('t', ENGINE)])
            with self.assertRaisesRegex(ValueError, 'no recordings'):
                sp.summarize({'runs': [run('before', 1, 10, [receipt('p1', 't')])]}, folder, 'jfr')
            with self.assertRaisesRegex(ValueError, 'no recording for its FAIRNESS phase'):
                sp.summarize({'runs': [run('before', 1, 10, [receipt('p1', 't', recordings={'REFERENCE': 'p1-REFERENCE.jfr'})])]}, folder, 'jfr')
            with self.assertRaisesRegex(ValueError, 'missing recording'):
                sp.summarize({'runs': [run('before', 1, 10, [receipt('p1', 't', recordings={'REFERENCE': 'p1-REFERENCE.jfr', 'FAIRNESS': 'p1-FAIRNESS.jfr'})])]}, folder, 'jfr')
            with self.assertRaisesRegex(ValueError, 'recordings and a thread required'):
                sp.summarize({'runs': [run('before', 1, 10, [receipt('p1', '', recordings={'REFERENCE': 'p1-REFERENCE.jfr'})])]}, folder, 'jfr')

    def test_main_adds_the_block_once_and_keeps_the_rest_of_the_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            self.record(folder, 'p1-REFERENCE.jfr', [sample('t', ENGINE)])
            evidence = folder / 'evidence.json'
            evidence.write_text('﻿' + json.dumps({'study': 'x', 'runs': [run('before', 1, 10, [receipt('p1', 't', ('REFERENCE',), {'REFERENCE': 'p1-REFERENCE.jfr'})])]}), encoding='utf-8')
            sp.main(['--evidence', str(evidence), '--profiles', str(folder), '--jfr', 'jfr'])
            written = json.loads(evidence.read_text(encoding='utf-8'))
            self.assertEqual(written['study'], 'x')
            self.assertEqual(written['profiles']['before reference']['samples'], {'engine': 1})
            with self.assertRaisesRegex(ValueError, 'without a profiles block'):
                sp.main(['--evidence', str(evidence), '--profiles', str(folder), '--jfr', 'jfr'])


if __name__ == '__main__':
    unittest.main()
