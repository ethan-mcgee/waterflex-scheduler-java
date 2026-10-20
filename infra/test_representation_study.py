import json
import tempfile
import unittest
from pathlib import Path

import representation_study as rs


def frame(owner, method):
    return {'method': {'type': {'name': owner}, 'name': method}}


def sample(*frames):
    return {'type': 'jdk.ExecutionSample', 'values': {'stackTrace': {'frames': [frame(*item) for item in frames]}}}


def allocation(owner, weight, *frames):
    return {'type': 'jdk.ObjectAllocationSample', 'values': {'objectClass': {'name': owner}, 'weight': weight,
                                                              'stackTrace': {'frames': [frame(*item) for item in frames]}}}


VARIANCE = ('dev.waterflex.scheduler.optimizer.SchedulingPolicy', 'fairness')
MONEY = ('dev.waterflex.scheduler.Monetary', 'cents')
IMPACT = ('ai.timefold.solver.core.impl.score.stream.common.inliner.BendableBigDecimalScoreContext$SingleHardImpact', 'undo')


class AttributionTest(unittest.TestCase):
    def test_decimal_work_is_split_by_where_it_is_called(self):
        document = {'recording': {'events': [
            sample(('java.math.BigDecimal', 'add'), VARIANCE),
            sample(('java/math/MutableBigInteger', 'divideMagnitude'), ('java.math.BigDecimal', 'divide'), MONEY),
            sample(IMPACT),
            sample(('java.math.BigDecimal', 'valueOf'), ('dev.waterflex.scheduler.optimizer.DayConstraintProvider', 'lambda$defineConstraints$3')),
            sample(('java.time.Duration', 'between'), ('dev.waterflex.scheduler.optimizer.RouteTimeline', 'evaluate')),
            sample(('dev.waterflex.scheduler.optimizer.RouteScoringFacts', 'varianceUnits')),
            sample(('ai.timefold.solver.core.impl.score.director.stream.BavetConstraintSession', 'calculateScore')),
            sample(('java.util.HashMap', 'putVal')),
            {'type': 'jdk.ExecutionSample', 'values': {'stackTrace': None}},
            {'type': 'jdk.GarbageCollection', 'values': {}},
            allocation('java.math.BigDecimal', 4096, ('java.math.BigDecimal', 'add'), VARIANCE),
            allocation('java/time/Instant', 1024, ('java.time.Instant', 'plusSeconds')),
        ]}}
        result = rs.attribute(document)
        self.assertEqual(result['samples'], {'decimal: variance': 1, 'decimal: money': 1, 'decimal: score impacts': 2, 'time': 1,
                                             'variance (non-decimal)': 1, 'score engine': 1, 'collections': 1})
        self.assertEqual(result['allocationBytes'], {'decimal: variance': 4096, 'time': 1024})
        self.assertEqual(result['withoutStack'], 1)
        merged = rs.merge([result, result])
        self.assertEqual(merged['recordings'], 2)
        self.assertEqual(merged['samplesPercent']['decimal: score impacts'], 25.0)
        self.assertEqual(merged['leafFramesPercent']['java.math.BigDecimal.add'], 12.5)

    def test_a_malformed_recording_is_refused(self):
        with self.assertRaises(ValueError):
            rs.attribute({'recording': {}})
        with self.assertRaises(ValueError):
            rs.attribute({'recording': {'events': [allocation('java.math.BigDecimal', -1, ('java.math.BigDecimal', 'add'))]}})


def result(variant, technicians, workload, seed, reference, fairness, cost):
    return {'type': 'result', 'variant': variant, 'technicians': technicians, 'workload': workload, 'seed': seed, 'violations': 0,
            'referencePhase': {'moveEvaluations': reference, 'solveMs': 1000}, 'fairnessPhase': {'moveEvaluations': fairness, 'solveMs': 1000},
            'after': {'costCents': cost}}


def write_rows(directory, rep, case, tree, rows):
    technicians, workload, seed = case
    lines = [{'type': 'provenance', 'revision': 'representation-study tree=' + tree}] + rows
    (directory / f'rep{rep}-{technicians}-{workload}-{seed}-{tree}.jsonl').write_text('\n'.join(json.dumps(line) for line in lines) + '\n', encoding='utf-8')


class PairedTest(unittest.TestCase):
    def run_directory(self, root, fast_s=True, s_dearer=0):
        rows = root / 'rows'
        rows.mkdir()
        (root / 'manifest.json').write_text('﻿' + json.dumps({'study': 'test'}), encoding='utf-8')
        cases = [(20, 'CLUSTERED', 17), (50, 'DISPERSED', 23)]
        for rep in (1, 2):
            for case in cases:
                base_cost = 1000 + rep  # a spread of one cent between repetitions
                write_rows(rows, rep, case, 's', [result('TABU_KOPT', *case, 100, 40, base_cost),
                                                  result('TABU_KOPT_LONG_SCORE', *case, 100, 60 if fast_s else 44, base_cost + s_dearer)])
                write_rows(rows, rep, case, 't', [result('TABU_KOPT', *case, 150, 44, base_cost - 3),
                                                  result('TABU_KOPT_LONG_SCORE', *case, 150, 70, base_cost - 3)])
        return root

    def test_speedups_costs_and_the_bars(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = self.run_directory(Path(temporary))
            document = rs.summarize(run, run / 'out.json', 'jfr')
            paired = document['paired']
            self.assertEqual((paired['cases'], paired['repetitions'], paired['violations']), (2, 2, 0))
            self.assertEqual(paired['summary']['T']['referencePhaseSpeedup']['median'], 1.5)
            self.assertEqual(paired['summary']['T']['fairnessPhaseSpeedup']['median'], 1.1)
            self.assertEqual(paired['summary']['S']['fairnessPhaseSpeedup']['median'], 1.5)
            self.assertEqual(paired['summary']['T']['cases'], {'cheaper': 2, 'ties': 0, 'dearer': 0, 'dearerBeyondNoise': 0})
            self.assertEqual(paired['summary']['S']['byFleet']['50']['fairnessPhase']['pairs'], 2)
            self.assertEqual(paired['repetitionAgreement']['maximum'], 0.0)
            self.assertTrue(paired['decision']['T']['promoted'])
            self.assertTrue(paired['decision']['S']['promoted'])
            with self.assertRaises(ValueError):
                rs.summarize(run, run / 'out.json', 'jfr')

    def test_s_is_held_to_its_bar(self):
        with tempfile.TemporaryDirectory() as temporary:
            slow = rs.summarize(self.run_directory(Path(temporary), fast_s=False), Path(temporary) / 'out.json', 'jfr')
            self.assertEqual(slow['paired']['summary']['S']['fairnessPhaseSpeedup']['median'], 1.1)
            self.assertFalse(slow['paired']['decision']['S']['promoted'])
        with tempfile.TemporaryDirectory() as temporary:
            # Two cents dearer is beyond the one cent the baseline moved between repetitions.
            dearer = rs.summarize(self.run_directory(Path(temporary), s_dearer=2), Path(temporary) / 'out.json', 'jfr')
            self.assertEqual(dearer['paired']['summary']['S']['cases']['dearerBeyondNoise'], 2)
            self.assertFalse(dearer['paired']['decision']['S']['promoted'])

    def test_an_incomplete_or_mismatched_run_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = self.run_directory(Path(temporary))
            (run / 'rows' / 'rep2-50-DISPERSED-23-t.jsonl').unlink()
            with self.assertRaisesRegex(ValueError, 'missing'):
                rs.summarize(run, run / 'out.json', 'jfr')
        with tempfile.TemporaryDirectory() as temporary:
            run = self.run_directory(Path(temporary))
            write_rows(run / 'rows', 1, (20, 'CLUSTERED', 17), 's', [result('TABU_KOPT', 20, 'CLUSTERED', 23, 100, 40, 1000),
                                                                      result('TABU_KOPT_LONG_SCORE', 20, 'CLUSTERED', 17, 100, 40, 1000)])
            with self.assertRaisesRegex(ValueError, 'does not match'):
                rs.summarize(run, run / 'out.json', 'jfr')


if __name__ == '__main__':
    unittest.main()
