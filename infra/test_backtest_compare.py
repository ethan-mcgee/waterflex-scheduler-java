"""Regression coverage for infra/backtest_compare.py on a hand-built scored export with known numbers."""
import json
import tempfile
import unittest
from pathlib import Path

import backtest_compare as bc
import backtest_import as bi
from test_backtest_dispatcher import route, row

LAST = '2024-03-29'  # the history's last date; Part 2's 2024-04-01 is after the data


def request(identifier, requested, served, code='R15', sales=False):
    return {'id': identifier, 'requestDate': requested, 'serviceId': code, 'tags': [code], 'salesCall': sales, 'durationMinutes': 60,
            'location': {'lat': 35.6, 'lng': -120.7}, 'flags': [], 'dispatcher': {'serviceDate': served, 'technicianId': 't01', 'bookedStart': '09:00', 'depot': 'north'}}


REQUESTS = [request('wo-1', '2024-03-01', '2024-03-04'), request('wo-2', '2024-03-04', '2024-03-04', 'P02'), request('wo-3', '2024-03-04', '2024-03-05'),
            request('wo-4', '2024-03-27', '2024-03-28', sales=True), request('wo-5', '2024-02-01', '2024-03-05')]
# Part 1 (the dispatcher on Part 2's jobs; wo-5 was turned away by Part 2, so it is gone): driving 60 + 40 and 30.
PART1 = {'2024-03-04': ([route('t01', 2, 480, 60, 100, 50000)], (20000, 15000, 3000, 2000), ['wo-1', 'wo-2']),
         '2024-03-05': ([route('t01', 1, 300, 0, 30, 20000)], (9000, 8000, 0, 1000), ['wo-3']),
         '2024-03-28': ([route('t01', 1, 200, 0, 20, 10000, late=1, lateness=600, holds=False)], (7000, 6000, 0, 1000), ['wo-4'])}
# Part 2: the same three jobs a day later, with less driving, and wo-4 after the data.
PART2 = {'2024-03-05': ([route('t01', 2, 420, 0, 70, 40000)], (15000, 13000, 0, 2000), ['wo-1', 'wo-2']),
         '2024-03-06': ([route('t02', 1, 280, 0, 25, 15000)], (8000, 7000, 0, 1000), ['wo-3']),
         '2024-04-01': ([route('t01', 1, 210, 0, 30, 12000)], (7500, 6500, 0, 1000), ['wo-4'])}
OUTCOMES = {'wo-1': ('BOOKED', '2024-03-05', 1), 'wo-2': ('BOOKED', '2024-03-05', 1), 'wo-3': ('BOOKED', '2024-03-06', 2),
            'wo-4': ('BOOKED', '2024-04-01', 1), 'wo-5': ('TURNED_AWAY', None, 11)}


def write_part(out, part, days, rule, plan):
    by_id = {r['id']: r for r in REQUESTS}
    entries, rows, job = [], [], []
    for day, (routes, cents, ids) in days.items():
        directory = out / part / 'days' / day
        observed = {'day': day, 'visits': {i: {'tags': by_id[i]['tags'], 'salesCall': by_id[i]['salesCall'], 'technician': 't01', 'flags': [],
                                               'requestLeadDays': (bi.dt.date.fromisoformat(day) - bi.dt.date.fromisoformat(by_id[i]['requestDate'])).days}
                                           for i in ids}}
        bi.write_json(directory / 'observed.json', observed)
        entries.append({'day': day, 'jobs': len(ids), 'depots': {r['technicianId']: 'north' for r in routes}})
        rows.append({'day': day, 'plan': plan, 'row': row(routes, *cents)})
        job.append({'day': day, 'directory': str(directory)})
    bi.write_json(out / part / 'index.json', {'runId': part, 'ruleSet': rule, 'routingIdentity': 'abc123def456', 'days': entries,
                                              'ledger': {'rows': 5, 'kept': 5, 'excludedRows': 0, 'reasons': {'exclude': {}, 'flag': {}}}})
    bi.write_json(out / part / 'rows.json', {'version': 1, 'kind': 'backtest-score-days', 'dataset': 'facts.json', 'plan': plan, 'rows': rows})
    bi.write_json(out / f'{part}-job.json', {'version': 1, 'kind': 'backtest-score-days', 'dataset': 'facts.json', 'plan': plan, 'days': job, 'output': ''})


def booking(request, outcome='BOOKED', **changes):
    record = {'date': '2024-03-04', 'request': request, 'attempt': 1, 'outcome': outcome, 'searchComplete': True, 'refinement': 'COMPLETE',
              'refinementStop': None, 'regularWindowsFound': 4, 'elapsedMs': 900, 'leadDays': 1,
              'horizon': {'dates': 4, 'first': '2024-03-05', 'last': '2024-03-08', 'lastFullDate': None, 'capped': False}}
    return dict(record, **changes)


def build(directory, shed=False, slow=False):
    sim = Path(directory) / 'sim'
    run = Path(directory) / 'run'
    bi.write_json(run / 'part2' / 'requests.json', {'requests': REQUESTS})
    bi.write_json(sim / 'simulation.json', {'simulationId': 'sim-test', 'routingIdentity': 'abc123def456', 'historyLastDate': LAST, 'run': str(run),
                                            'segments': [{'name': 's1'}], 'settings': {}})
    out = sim / 'export'
    write_part(out, 'part1', PART1, 'as-run', 'as-planned.plan.json')
    write_part(out, 'part2', PART2, 'part2', 'plan.json')
    outcomes = {i: {'outcome': o, 'segment': 's1', 'serviceDate': d, 'technician': 't01' if d else None, 'attempts': a} for i, (o, d, a) in OUTCOMES.items()}
    bi.write_json(out / 'outcomes.json', {'partial': False, 'outcomes': outcomes, 'undecided': [], 'counts': {'BOOKED': 4, 'TURNED_AWAY': 1},
                                          'boundaries': {'scoredTwice': [], 'bookedNotScored': [], 'bookedOutsideTheStudy': []}})
    segment = sim / 'segments' / 's1'
    segment.mkdir(parents=True)
    bookings = [booking('wo-1'), booking('wo-2', refinement='SHED' if shed else 'COMPLETE'), booking('wo-3', refinementStop='CAP', refinement='SHORTENED'),
                booking('wo-4', leadDays=5), booking('wo-5', outcome='TURNED_AWAY', regularWindowsFound=0)]
    proposals = [{'date': '2024-03-04', 'night': '2024-03-05', 'serviceDate': '2024-03-05', 'outcome': 'PROPOSED', 'decision': 'IMPROVED', 'reason': 'COST',
                  'costCents': 15000, 'committed': True, 'moved': 2, 'elapsedMs': 301_000 if slow else 16_000},
                 {'date': '2024-03-04', 'night': '2024-03-05', 'serviceDate': '2024-03-06', 'outcome': 'EMPTY'}]
    (segment / 'bookings.jsonl').write_text(''.join(json.dumps(b) + '\n' for b in bookings), encoding='utf-8')
    (segment / 'proposals.jsonl').write_text(''.join(json.dumps(p) + '\n' for p in proposals), encoding='utf-8')
    return sim


class CompareTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.result = bc.compare(build(self.directory.name))

    def tearDown(self):
        self.directory.cleanup()

    def test_a_clean_run_is_valid_and_each_failure_is_counted(self):
        self.assertTrue(self.result['validity']['valid'], self.result['validity'])
        self.assertEqual(1, self.result['validity']['capStops'], 'a cap stop is reported, not a failure')
        with tempfile.TemporaryDirectory() as other:
            bars = bc.compare(build(other, shed=True, slow=True))['validity']
        self.assertFalse(bars['valid'])
        self.assertEqual((1, 1), (bars['bars']['bookingShed'], bars['bars']['proposalsOver300s']))

    def test_routes_are_compared_by_service_period_and_the_tail_stands_apart(self):
        p1, p2 = self.result['part1'], self.result['part2']
        self.assertEqual((4, 4), (p1['study']['jobs'], p2['study']['jobs']))
        self.assertEqual((150, 125), (p1['study']['driveMinutes'], p2['study']['driveMinutes']))
        self.assertNotIn('2024-04', p1)
        self.assertEqual(1, self.result['tail']['jobs'])
        self.assertEqual(3, self.result['part2WithinData']['study']['jobs'])

    def test_weeks_both_parts_have_are_paired_part2_minus_part1(self):
        drive = self.result['weekly']['driveMinutes']['study']
        # Week of 2024-03-04: 130 - 95 = ... P1 100 + 30 = 130, P2 70 + 25 = 95; week of 2024-03-25: P1 20, P2 none.
        self.assertEqual({'weeks': 1, 'median': -35, 'interval': None, 'better': 1, 'tied': 0, 'worse': 0}, drive)
        self.assertEqual({'paired': 1, 'part1Only': 1, 'part2Only': ['2024-04-01']}, self.result['pairing'])

    def test_promptness_reads_each_request_at_its_own_booking(self):
        part1, part2 = self.result['promptness']['part1']['study']['all'], self.result['promptness']['part2']['study']['all']
        self.assertEqual((4, 4), (part1['jobs'], part2['jobs']))
        self.assertEqual(1, part1['sameDay'], 'the dispatcher served the P02 the day it came in')
        self.assertEqual(0, part2['sameDay'], 'Part 2 books from the next day')
        self.assertEqual(1, self.result['promptness']['part2']['study']['emergency']['jobs'])
        without = self.result['promptness']['part2WithoutLongLeads']['study']['all']['jobs']
        self.assertEqual(4, without, 'wo-5, the only long lead, was turned away and is in neither')
        self.assertEqual({'counts': {'BOOKED': 4, 'TURNED_AWAY': 1}, 'undecided': 0, 'calledBack': 2, 'turnedAwayEmergencies': 0}, self.result['demand'])

    def test_booking_and_overnight_readings(self):
        booking = self.result['booking']
        self.assertEqual((5, 1), (booking['searches'], booking['capStops']))
        self.assertEqual({'median': 1, 'p90': 5, 'max': 5}, booking['offerLeadDays'])
        night = self.result['overnight']
        self.assertEqual((1, 1, 2, {'IMPROVED': 1}, {'EMPTY': 1}), (night['proposals'], night['committed'], night['moved'], night['decisions'], night['skipped']))

    def test_the_report_is_aligned_marks_the_tail_and_has_no_em_dash(self):
        text = bc.report(self.result)
        self.assertTrue(text.startswith('# Paso Robles backtest, Part 2: the product simulated\n'))
        self.assertNotIn('—', text)
        self.assertIn('2024-04 (after the data)', text)
        self.assertIn('2024-Q1 (to 2024-03-29)', text)
        self.assertIn('not measured', text)
        self.assertIn('The run is valid', text)
        block = []
        for line in text.splitlines() + ['']:
            if line.startswith('|'):
                block.append(line)
                continue
            if block:
                self.assertEqual(1, len({len(l) for l in block}), block)
                self.assertTrue(all(len(l) <= 160 for l in block), block[0])
                block = []
        self.assertTrue(text.endswith('\n') and not text.endswith('\n\n'))



class CalibrationTest(unittest.TestCase):
    def reading(self, name, drive, cost, valid=True):
        return {'copy': name, 'jobs': 100, 'drivePerJob': drive, 'costPerJob': cost, 'valid': valid, 'bars': {}, 'wallSeconds': 600,
                'wallMinutesPerSimulatedWeek': 1.5}

    def run_bars(self, a, b1, b2, c1, c2, valid=True):
        readings = {'a': a, 'b1': b1, 'b2': b2, 'c1': c1, 'c2': c2}
        original = bc.copy_reading
        bc.copy_reading = lambda sim, name: self.reading(name, *readings[name], valid=valid or name == 'a')
        try:
            with tempfile.TemporaryDirectory() as directory:
                bi.write_json(Path(directory) / 'simulation.json', {'segments': [{'name': 'a'}]})
                return bc.calibration(directory, 'together')
        finally:
            bc.copy_reading = original

    def test_close_copies_keep_four_segments_and_three_weeks(self):
        result = self.run_bars((30.0, 5000), (30.2, 5010), (29.8, 4990), (30.1, 5005), (29.9, 4995))
        self.assertEqual((4, 3), (result['segments'], result['warmupWeeks']))
        self.assertAlmostEqual(0.4 / 30, result['bars']['drivePerJob']['noise'])
        self.assertAlmostEqual(max(0.01, 2 * 0.4 / 30), result['bars']['drivePerJob']['allowed'])

    def test_contention_or_a_longer_warm_up_beyond_the_allowance_changes_the_decision(self):
        crowded = self.run_bars((30.0, 5000), (32.0, 5300), (32.0, 5300), (32.0, 5300), (32.0, 5300))
        self.assertEqual(2, crowded['segments'], 'A differs from B by 6 percent with no noise: contention')
        self.assertEqual(3, crowded['warmupWeeks'])
        cold = self.run_bars((30.0, 5000), (30.0, 5000), (30.0, 5000), (31.0, 5200), (31.0, 5200))
        self.assertEqual((4, 6), (cold['segments'], cold['warmupWeeks']))
        invalid = self.run_bars((30.0, 5000), (30.0, 5000), (30.0, 5000), (30.0, 5000), (30.0, 5000), valid=False)
        self.assertEqual(2, invalid['segments'], 'run B broke a validity bar')

    def test_the_calibration_report_is_aligned(self):
        text = bc.calibration_report(self.run_bars((30.0, 5000), (30.2, 5010), (29.8, 4990), (30.1, 5005), (29.9, 4995)))
        self.assertIn('Decision: 4 segments', text)
        self.assertNotIn('—', text)
        rows = [l for l in text.splitlines() if l.startswith('|')]
        self.assertTrue(rows)


if __name__ == '__main__':
    unittest.main()
