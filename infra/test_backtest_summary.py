"""Regression coverage for infra/backtest_summary.py on a synthetic run directory; no jar, no network."""
import json
import tempfile
import unittest
from pathlib import Path

import backtest_summary as bs

MEASURE_NAMES = [name for name, _ in bs.MEASURES]


def row(drive, paid=480, feasible=True, lateness=0, past_shift=0, late_visits=0, overtime=0):
    document = {'datasetHash': 'd', 'planHash': 'p', 'feasible': feasible, 'routesHold': 2, 'unassigned': 0, 'hardPenalty': 0, 'costCents': drive * 100,
                'paidMinutes': paid, 'overtimeMinutes': overtime, 'driveMinutes': drive, 'waitingMinutes': 0, 'meters': drive * 800, 'bufferSeconds': 0,
                'qualifications': 0, 'unreachableLegs': 0, 'latenessSeconds': lateness, 'lateVisits': late_visits, 'availabilitySeconds': past_shift * 60,
                'pastShiftMinutes': past_shift, 'capacityExcessMinutes': 0, 'fairnessVariance': '0.01', 'maximumUtilization': '0.9', 'routes': []}
    return document


def write_json(path, document):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document), encoding='utf-8')


def write_run(root, days, api_days=(), failing=()):
    """A run whose solver saves exactly `saving` drive minutes per day against the executed plan; on a `failing` day the
    dispatcher's booked plan does not hold (late and past the shift)."""
    entries = []
    for day, split, technicians, sales, saving in days:
        entries.append({'day': day, 'split': split, 'year': int(day[:4]), 'weekday': 0, 'technicians': technicians, 'jobs': 10 + technicians,
                        'salesCallDay': sales, 'unreachableLegs': 1 if sales else 0, 'flags': {}})
        case = root / 'rows' / day / 'seed17-rep1'
        write_json(case / 'receipt.json', {'accepted': True})
        write_json(case / 'as-planned.row.json', row(100, feasible=False, lateness=900, past_shift=20, late_visits=2) if day in failing else row(100))
        write_json(case / 'as-planned-actual.row.json', row(103, late_visits=2))
        write_json(case / 'as-executed.row.json', row(90))
        write_json(case / 'as-executed-actual.row.json', row(92, late_visits=1))
        # A rejected candidate may drop a job and drive less; the summary must not count it.
        write_json(case / 'solver.row.json', row(90 - saving) if saving > 0 else dict(row(40), unassigned=1, feasible=False))
        write_json(case / 'solver-actual.row.json', row(92 - saving, late_visits=1))
        write_json(case / 'solve.json', {'accepted': saving > 0, 'reason': 'IMPROVED' if saving > 0 else 'NO_IMPROVEMENT', 'solveMs': 10})
    write_json(root / 'index.json', {'runId': 'synthetic', 'ruleSet': 'strict-17', 'primary': True, 'routingIdentity': 'stub-road-model-v1', 'mapVersion': 'stub',
                                     'apiDateShiftDays': 7, 'days': entries,
                                     'ledger': {'rows': 20, 'kept': 18, 'excludedRows': 2, 'reasons': {'exclude': {'BAD_YEAR': 2}, 'flag': {'WEEKEND': 1}},
                                                'heldOut': {'rows': 3, 'salesCalls': 1}},
                                     'technicians': {'t01': {'first': '2024-01-02', 'last': '2025-06-30'}}, 'holidays': ['2024-07-04']})
    write_json(root / 'study-config.json', {'runId': 'synthetic', 'bars': {'repairShareMin': 0.8}})
    for day, status, decision in api_days:
        body = {'decision': decision, 'routes': []} if status == 201 else {'error': 'INCOMPLETE_FACTS'}
        write_json(root / 'api' / day / 'rep1.json', {'day': day, 'rep': 1, 'status': status, 'body': body})
        if status == 201:
            write_json(root / 'api' / day / 'rep1.row.json', row(85))


DAYS = [('2024-01-02', 'calibration', 2, True, 5), ('2024-01-03', 'calibration', 3, False, 0),
        ('2025-01-06', 'test', 2, False, 8), ('2025-01-07', 'test', 2, True, 6), ('2025-01-08', 'test', 3, False, 10), ('2025-01-09', 'test', 4, True, 4)]


class SummaryTest(unittest.TestCase):
    def test_paired_medians_and_groupings(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_run(root, DAYS, api_days=[('2025-01-06', 201, 'IMPROVED'), ('2025-01-07', 422, None), ('2025-01-08', 201, 'NO_IMPROVEMENT')],
                      failing=('2025-01-07',))
            result = bs.summarize(root, root / 'out')
            test = result['comparisons']['delivered vs as-executed']
            self.assertEqual(-7.0, test['all test']['driveMinutes']['median'], 'solver minus as-executed on the test split: -8, -6, -10, -4')
            self.assertEqual({'days': 4, 'better': 4, 'tie': 0, 'worse': 0}, {k: test['all test']['driveMinutes'][k] for k in ('days', 'better', 'tie', 'worse')})
            self.assertEqual(0.0, test['all test']['paidMinutes']['median'])
            self.assertIsNotNone(test['all test']['driveMinutes']['interval'])
            self.assertEqual(2, test['sales-call days (test)']['driveMinutes']['days'])
            self.assertEqual(2, test['days without a sales call (test)']['driveMinutes']['days'])
            self.assertEqual(2.5, test['all calibration']['driveMinutes']['median'],
                             'calibration differences -5 and +10: the rejected day delivers the dispatcher\'s 100 minutes, not the candidate\'s 40')
            planned = result['comparisons']['delivered vs as-planned']['all test']['driveMinutes']
            self.assertEqual(-17.0, planned['median'], 'solver minus as-planned adds the 10 minutes the dispatcher planned over what was driven')
            forward = result['comparisons']['played forward: delivered vs as-executed']['all test']['driveMinutes']
            self.assertEqual(-7.0, forward['median'])
            self.assertEqual(1, test['2 technicians (calibration)']['driveMinutes']['days'])
            self.assertEqual(2, test['2 technicians (test)']['driveMinutes']['days'])
            validity = result['validity']['test']
            self.assertEqual({'IMPROVED': 4}, validity['reasons'])
            self.assertEqual(0, validity['acceptedPlansWithViolations'], 'the rejected candidate that dropped a job is not a delivered violation')
            self.assertEqual(1, result['validity']['calibration']['playedForwardLateWorse'], 'the rejected day delivers the dispatcher\'s order, later than executed')
            self.assertEqual({'IMPROVED': 1, 'NO_IMPROVEMENT': 1}, result['validity']['calibration']['reasons'])
            agreement = result['agreement']
            self.assertEqual({'agreed': 1, 'differed': 1, 'refused': 1}, agreement['counts'])
            self.assertEqual(0.5, agreement['agreementShare'])
            self.assertEqual(0, result['noise']['days'], 'one solve per day gives no noise reading')
            self.assertIsNone(result['noise']['medianSd'])
            split = result['split']['test']
            self.assertEqual(3, split['efficiency']['days'], 'the failing day is not an efficiency day')
            self.assertEqual(-18.0, split['efficiency']['measures']['driveMinutes']['median'], 'delivered minus as-planned: -18, -20, -14')
            self.assertEqual({'days': 1, 'repaired': 1, 'share': 1.0}, {k: split['repair'][k] for k in ('days', 'repaired', 'share')})
            self.assertEqual(-16.0, split['repair']['cost']['driveMinutes']['median'])
            self.assertEqual({'total': 2, 'median': 2}, split['repair']['removed']['lateVisits'])
            self.assertEqual(20, split['repair']['removed']['pastShiftMinutes']['total'])
            lower, upper = split['repair']['interval']
            self.assertLess(lower, 1.0); self.assertEqual(1.0, upper)
            bars = result['bars']
            self.assertTrue(bars['evaluated']); self.assertTrue(bars['efficiencyDriveAndPaid']); self.assertTrue(bars['repairShare'])
            self.assertEqual(0.8, bars['repairShareMin']); self.assertTrue(bars['solverPlansClean'])
            self.assertFalse(bars['apiAgreement']); self.assertFalse(bars['noiseBelowAThird'])
            self.assertEqual({'2024': 1, '2025': 2}, result['routingCoverage'])
            report = (root / 'out' / 'report.md').read_text(encoding='utf-8')
            self.assertIn('# Backtest: synthetic', report)
            self.assertIn('Shift rule `strict-17`, the gated primary.', report)
            self.assertIn('held 3 more out of every plan (1 of them sales calls)', report)
            self.assertIn('| all test', report)
            self.assertNotIn('\u2014', report, 'reports never carry an em-dash (AGENTS.md)')
            self.assertTrue((root / 'out' / 'evidence.json').exists())
            self.assertTrue((root / 'out' / 'protocol.md').exists(), 'the protocol template is copied beside the report')

    def test_a_day_missing_one_plan_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_run(root, DAYS[:2])
            (root / 'rows' / '2024-01-02' / 'seed17-rep1' / 'solver-actual.row.json').unlink()
            with self.assertRaises(bs.SummaryError):
                bs.summarize(root, root / 'out')

    def test_a_null_measure_is_refused_not_read_as_zero(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_run(root, DAYS[:2])
            path = root / 'rows' / '2024-01-02' / 'seed17-rep1' / 'solver.row.json'
            document = json.loads(path.read_text(encoding='utf-8'))
            document['driveMinutes'] = None
            path.write_text(json.dumps(document), encoding='utf-8')
            with self.assertRaises(bs.SummaryError):
                bs.summarize(root, root / 'out')

    def test_noise_reads_the_spread_across_seeds(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_run(root, DAYS)
            case = root / 'rows' / '2025-01-06'
            for seed, drive in ((18, 84), (19, 80)):
                for name in ('as-planned', 'as-planned-actual', 'as-executed', 'as-executed-actual', 'solver', 'solver-actual'):
                    write_json(case / f'seed{seed}-rep1' / f'{name}.row.json', row(drive if name == 'solver' else 90))
                write_json(case / f'seed{seed}-rep1' / 'receipt.json', {}); write_json(case / f'seed{seed}-rep1' / 'solve.json', {'accepted': True, 'reason': 'IMPROVED'})
            result = bs.summarize(root, root / 'out')
            self.assertEqual(1, result['noise']['days'])
            self.assertAlmostEqual(1.633, result['noise']['medianSd'], places=3, msg='population SD of 82, 84, 80')
            self.assertEqual(-7.0, result['comparisons']['delivered vs as-executed']['all test']['driveMinutes']['median'], 'the primary reading uses the lowest seed only')

    def test_a_solve_without_its_decision_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_run(root, DAYS[:2])
            write_json(root / 'rows' / '2024-01-02' / 'seed17-rep1' / 'solve.json', {'reason': 'IMPROVED'})
            with self.assertRaises(bs.SummaryError):
                bs.summarize(root, root / 'out')

    def test_every_rule_set_is_summarized_and_tabled(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for rule in ('strict-17', 'sales-day'):
                write_run(root / 'variants' / rule, DAYS)
            (root / 'variants' / 'evening-sales').mkdir(parents=True)
            write_json(root / 'run.json', {'runId': 'synthetic', 'primaryRuleSet': 'strict-17', 'ruleSets': {
                rule: {'directory': f'variants/{rule}'} for rule in ('strict-17', 'evening-sales', 'sales-day')}})
            overview = bs.summarize_all(root, root / 'out')
            self.assertEqual({'strict-17', 'sales-day'}, set(overview['ruleSets']), 'a rule set without harness rows is skipped')
            self.assertTrue((root / 'out' / 'strict-17' / 'report.md').exists())
            report = (root / 'out' / 'report.md').read_text(encoding='utf-8')
            self.assertIn('| strict-17 | primary', report)
            self.assertIn('Repaired', report)
            self.assertIn('| sales-day | sensitivity', report)
            self.assertIn('3 (1 sales)', report)

    def test_a_missing_repair_bar_is_not_read_rather_than_invented(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_run(root, DAYS, failing=('2025-01-07',))
            write_json(root / 'study-config.json', {'runId': 'synthetic'})
            bars = bs.summarize(root, root / 'out')['bars']
            self.assertIsNone(bars['repairShare']); self.assertIsNone(bars['repairShareMin'])
            self.assertIn('no bar set', (root / 'out' / 'report.md').read_text(encoding='utf-8'))

    def test_wilson_interval(self):
        self.assertIsNone(bs.wilson(0, 0))
        lower, upper = bs.wilson(30, 45)
        self.assertAlmostEqual(0.5206, lower, places=3); self.assertAlmostEqual(0.7867, upper, places=3)

    def test_day_selection(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_run(root, DAYS)
            self.assertEqual([d[0] for d in DAYS], bs.select_days(root, 'all'))
            self.assertEqual(['2024-01-02', '2024-01-03'], bs.select_days(root, 'calibration'))
            self.assertEqual(4, len(bs.select_days(root, 'test')))
            self.assertEqual(6, len(bs.select_days(root, 'smoke')))
            sample = bs.select_days(root, 'sample:2')
            self.assertEqual(2, len(sample)); self.assertEqual(sample, bs.select_days(root, 'sample:2'), 'a fixed seed draws the same days')
            self.assertTrue(all(d.startswith('2025') for d in sample), 'samples come from the test split')
            self.assertEqual(4, len(bs.select_days(root, 'noise:50')), 'a count beyond the split returns the whole split')
            self.assertEqual(['2025-01-06', '2025-01-08'], bs.select_days(root, '2025-01-08, 2025-01-06'))
            with self.assertRaises(bs.SummaryError):
                bs.select_days(root, '2025-12-25')


if __name__ == '__main__':
    unittest.main()
