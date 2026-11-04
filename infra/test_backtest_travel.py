"""Regression coverage for infra/backtest_travel.py on synthetic gaps and a fabricated run directory."""
import json
import tempfile
import unittest
from pathlib import Path

import backtest_travel as bt


def rows(gaps_and_legs, year=2024):
    return [{'year': year, 'gapMinutes': gap, 'legSeconds': seconds, 'legMeters': seconds * 15} for gap, seconds in gaps_and_legs]


class CalibrationTest(unittest.TestCase):
    def test_buffered_minutes_matches_the_engine_formula(self):
        # ceil(600 * 1.1 / 60) + 2 = ceil(11) + 2 = 13; ceil(601 * 1.0 / 60) + 0 = 11
        self.assertEqual(13, bt.buffered_minutes(600, 10, 2))
        self.assertEqual(11, bt.buffered_minutes(601, 0, 0))
        self.assertEqual(0 + 2, bt.buffered_minutes(0, 50, 2))
        # 1234 s at 7 percent: 1234 * 1.07 / 60 = 22.006..., ceiling 23, plus 2.
        self.assertEqual(25, bt.buffered_minutes(1234, 7, 2))

    def test_the_calibrated_percent_is_the_smallest_that_covers_the_median(self):
        # Legs of 600 s (10 min) plus 2 minutes; gaps 11, 12, 14, 20 and 30 minutes. Covering the median gap of 14 needs
        # ceil(10 * (1 + pct)) >= 12, so 11 percent (10 percent gives exactly 11, which is 13 with the extra minutes).
        data = rows([(11, 600), (12, 600), (14, 600), (20, 600), (30, 600)])
        chosen = bt.calibrate(data, 0.5, 2)
        self.assertEqual(11, chosen['percent'])
        self.assertEqual('0.11', chosen['travelBufferPct'])
        self.assertEqual(3, chosen['covered'])
        # Four of five means reaching the 20 minute gap: ceil(10 * (1 + pct)) >= 18, so 71 percent.
        self.assertEqual(71, bt.calibrate(data, 0.8, 2)['percent'])
        self.assertIsNone(bt.calibrate(rows([(1000, 60)]), 0.5, 0), 'a gap no percent reaches is reported as none, never forced')
        with self.assertRaises(ValueError):
            bt.calibrate([], 0.5, 2)

    def test_describe_counts_gaps_shorter_than_the_leg(self):
        data = rows([(5, 600), (12, 600), (20, 600), (40, 600)])
        summary = bt.describe(data)
        self.assertEqual(4, summary['gaps'])
        self.assertEqual(1, summary['shorterThanLeg'])
        self.assertEqual(0.25, summary['shorterThanLegShare'])
        self.assertEqual(4, summary['ratio']['count'])
        self.assertAlmostEqual(1.6, summary['ratio']['median'])


def write_day(root, day, split, stops, legs):
    """A day with one technician whose logged stops are (visit, start, end) and whose road legs are (from, to, seconds)."""
    directory = root / 'days' / day
    directory.mkdir(parents=True)
    visits = sorted({stop[0] for stop in stops})
    location = {visit: index + 1 for index, visit in enumerate(visits)}
    facts = {'visits': [{'id': visit, 'location': location[visit]} for visit in visits],
             'roads': {'entries': [{'from': location[a], 'to': location[b], 'state': 'REACHABLE', 'seconds': seconds, 'meters': seconds * 15} for a, b, seconds in legs]}}
    observed = {'visits': {visit: {'technician': 't01', 'actualStart': f'{day}T{start}:00Z', 'actualEnd': f'{day}T{end}:00Z', 'flags': flags}
                           for visit, start, end, *rest in stops for flags in [rest[0] if rest else []]}}
    (directory / 'facts.json').write_text(json.dumps(facts), encoding='utf-8')
    (directory / 'observed.json').write_text(json.dumps(observed), encoding='utf-8')
    return {'day': day, 'year': int(day[:4]), 'split': split}


class RunTest(unittest.TestCase):
    def test_gaps_follow_the_logged_order_and_skip_flagged_or_unroutable_stops(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            entries = [write_day(root, '2024-03-04', 'calibration', [('a', '09:00', '09:30'), ('b', '09:45', '10:15'), ('c', '10:20', '10:50', ['ACTUAL_END_NOT_AFTER_START']), ('d', '14:00', '14:30')],
                                 [('a', 'b', 600), ('b', 'c', 300), ('c', 'd', 300), ('b', 'd', 900)]),
                       write_day(root, '2025-03-04', 'test', [('e', '09:00', '09:30'), ('f', '09:40', '10:00')], [('e', 'f', 900)])]
            (root / 'index.json').write_text(json.dumps({'days': entries}), encoding='utf-8')
            calibration = bt.gaps(root, 'calibration')
            # a to b: 15 minute gap over a 10 minute leg; c is flagged so b to d is the next pair, but its 225 minute gap is over the limit.
            self.assertEqual([{'year': 2024, 'gapMinutes': 15.0, 'legSeconds': 600, 'legMeters': 9000}], calibration)
            test = bt.gaps(root, 'test')
            self.assertEqual(1, len(test))
            self.assertEqual(10.0, test[0]['gapMinutes'])
            self.assertEqual(2, len(bt.gaps(root, 'all')))
            document = bt.report(root, 'all', 0.5, 2)
            self.assertEqual(2, document['overall']['gaps'])
            self.assertEqual(1, document['overall']['shorterThanLeg'], 'the 10 minute gap over a 15 minute leg cannot have been driven')
            self.assertIn('5 to 20 km', document['byDistance'])
            self.assertEqual({'2024', '2025'}, set(document['byYear']))
            self.assertIsNotNone(document['calibrated'])
            self.assertEqual([], bt.gaps(root, 'nothing'))
            with self.assertRaises(ValueError):
                bt.report(root, 'nothing', 0.5, 2)


if __name__ == '__main__':
    unittest.main()
