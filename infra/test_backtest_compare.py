"""Regression coverage for infra/backtest_compare.py on a hand-built scored export with known numbers."""
import json
import re
import tempfile
import unittest
from pathlib import Path

import backtest_compare as bc
import backtest_dispatcher as bd
import backtest_import as bi
from test_backtest_dispatcher import route, row

LAST = '2024-03-29'  # the history's last date; Part 2's 2024-04-01 is after the data


def request(identifier, requested, served, code='R15', sales=False):
    return {'id': identifier, 'requestDate': requested, 'serviceId': code, 'tags': [code], 'salesCall': sales, 'durationMinutes': 60,
            'location': {'lat': 35.6, 'lng': -120.7}, 'flags': [], 'dispatcher': {'serviceDate': served, 'technicianId': 't01', 'bookedStart': '09:00', 'depot': 'north'}}


REQUESTS = [request('wo-1', '2024-03-01', '2024-03-04'), request('wo-2', '2024-03-04', '2024-03-04', 'P02'), request('wo-3', '2024-03-04', '2024-03-05'),
            request('wo-4', '2024-03-27', '2024-03-28', sales=True), request('wo-5', '2024-02-01', '2024-03-05')]
# Part 1 (the dispatcher on Part 2's jobs; wo-5 was turned away by Part 2, so it is gone): driving 60 + 40 and 30.
PART1 = {'2024-03-04': ([route('t01', 2, 480, 0, 100, 50000)], (20000, 18000, 0, 2000), ['wo-1', 'wo-2']),
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
    bi.write_json(out / part / 'rows.json', {'version': 1, 'kind': 'backtest-score-days', 'dataset': 'facts.json', 'plan': plan, 'payRule': bd.PAY_RULE,
                                             'rows': rows})
    bi.write_json(out / f'{part}-job.json', {'version': 1, 'kind': 'backtest-score-days', 'dataset': 'facts.json', 'plan': plan, 'days': job, 'output': ''})


def booking(request, outcome='BOOKED', **changes):
    record = {'date': '2024-03-04', 'request': request, 'attempt': 1, 'outcome': outcome, 'searchComplete': True, 'refinement': 'COMPLETE',
              'refinementStop': None, 'regularWindowsFound': 4, 'elapsedMs': 900, 'leadDays': 1,
              'horizon': {'dates': 4, 'first': '2024-03-05', 'last': '2024-03-08', 'lastFullDate': None, 'capped': False}}
    return dict(record, **changes)


def moved(days, old, new):
    """The days with the one on old served on new instead, priced as it was."""
    return {new if day == old else day: value for day, value in days.items()}


def on_weekend(days, old, new):
    """The days with the one on old served on the weekend date new, priced as the scorer prices a weekend: every paid
    minute overtime at $45 an hour (75 cents a minute), no regular time, the same mileage."""
    routes, (_, _, _, mileage), ids = days[old]
    routes = [dict(r, overtimeMinutes=r['paidMinutes']) for r in routes]
    labor = 75 * sum(r['paidMinutes'] for r in routes)
    return {new if day == old else day: ((routes, (labor + mileage, 0, labor, mileage), ids) if day == old else value) for day, value in days.items()}


def build(directory, shed=False, slow=False, options=None, part1=None, part2=None, served=None, pick=None):
    """A scored export of one segment. options are a version 2 plan's (weekend work, proposals in flight); without them
    the plan is a version 1 one. pick makes it a version 3 plan of those options whose client picks one of four offers
    at random, its bookings recording the offers shown and the one taken. served moves requests' Part 2 service dates."""
    sim = Path(directory) / 'sim'
    run = Path(directory) / 'run'
    bi.write_json(run / 'part2' / 'requests.json', {'requests': REQUESTS})
    plan = {'version': 1} if options is None else {'version': 2, 'weekendWork': options[0], 'overnightInFlight': options[1]}
    if pick is not None:
        plan.update(version=3, clientPick='random')
    bi.write_json(sim / 'simulation.json', dict(plan, simulationId='sim-test', routingIdentity='abc123def456', historyLastDate=LAST, run=str(run),
                                                segments=[{'name': 's1'}], settings={'offerLimit': 1 if pick is None else 4}))
    out = sim / 'export'
    write_part(out, 'part1', part1 or PART1, 'as-run', 'as-planned.plan.json')
    write_part(out, 'part2', part2 or PART2, 'part2', 'plan.json')
    outcomes = {i: {'outcome': o, 'segment': 's1', 'serviceDate': (served or {}).get(i, d), 'technician': 't01' if d else None, 'attempts': a}
                for i, (o, d, a) in OUTCOMES.items()}
    bi.write_json(out / 'outcomes.json', {'partial': False, 'outcomes': outcomes, 'undecided': [], 'counts': {'BOOKED': 4, 'TURNED_AWAY': 1},
                                          'boundaries': {'scoredTwice': [], 'bookedNotScored': [], 'bookedOutsideTheStudy': []}})
    segment = sim / 'segments' / 's1'
    segment.mkdir(parents=True)
    bookings = [booking('wo-1'), booking('wo-2', refinement='SHED' if shed else 'COMPLETE'), booking('wo-3', refinementStop='CAP', refinement='SHORTENED'),
                booking('wo-4', leadDays=5), booking('wo-5', outcome='TURNED_AWAY', regularWindowsFound=0)]
    if pick is not None:
        # (offers shown, index taken) of each booked request, in order; the turned-away one was shown none.
        for b, (shown, taken) in zip(bookings, pick + [(0, None)], strict=True):
            b['offersShown'] = shown
            if taken is not None:
                b['picked'] = taken
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

    def test_each_request_is_paired_with_itself(self):
        # Part 2 minus the dispatcher: wo-1, wo-2 and wo-3 a day later, wo-4 four days later; wo-5 was turned away.
        self.assertEqual({'requests': 4, 'median': 1.0, 'sooner': 0, 'same': 0, 'later': 4}, self.result['promptness']['paired']['study'])

    def test_part1_promptness_reads_the_dispatcher_date_even_off_the_scored_days(self):
        # A short run scores a few days only; a request the dispatcher served after them must still count, at its own lead.
        requests = {'x': request('x', '2024-03-01', '2024-03-20'), 'y': request('y', '2024-03-01', '2024-03-04')}
        outcomes = {'x': {'outcome': 'BOOKED', 'serviceDate': '2024-03-05', 'attempts': 1},
                    'y': {'outcome': 'TURNED_AWAY', 'serviceDate': None, 'attempts': 11}}
        part1 = bc.part1_promptness(outcomes, requests)['study']['all']
        self.assertEqual((1, 19), (part1['jobs'], part1['medianDays']), 'only the booked request, at the dispatcher date')
        self.assertEqual(4, bc.part2_promptness(outcomes, requests)['study']['all']['medianDays'])

    def test_a_booked_request_without_a_dispatcher_date_is_refused(self):
        broken = dict(request('x', '2024-03-01', '2024-03-04'), dispatcher={'serviceDate': None})
        with self.assertRaises(bc.CompareError):
            bc.part1_promptness({'x': {'outcome': 'BOOKED', 'serviceDate': '2024-03-05', 'attempts': 1}}, {'x': broken})

    def test_counts_print_as_whole_numbers(self):
        text = bc.report(self.result)
        rows = [line for line in text.splitlines() if line.startswith('| Study ')]
        self.assertIn(['Study', '1', '0', '1', '0'], [[c.strip() for c in line.strip('|').split('|')] for line in rows],
                      'late visits and routes not holding are counts')
        self.assertIn('Median P2 minus P1 (days)', text)

    def test_booking_and_overnight_readings(self):
        booking = self.result['booking']
        self.assertEqual((5, 1), (booking['searches'], booking['capStops']))
        self.assertEqual({'median': 1, 'p90': 5, 'max': 5}, booking['offerLeadDays'])
        night = self.result['overnight']
        self.assertEqual((1, 1, 2, {'IMPROVED': 1}, {'EMPTY': 1}), (night['proposals'], night['committed'], night['moved'], night['decisions'], night['skipped']))
        self.assertIsNone(booking['windowPick'], 'a version 1 run did not record the offers shown')
        self.assertEqual((1, 'first'), (self.result['offerLimit'], self.result['clientPick']))
        self.assertNotIn('of those shown', bc.report(self.result))

    def test_a_random_client_is_described_and_its_picks_counted(self):
        sim = build(Path(self.directory.name) / 'pick', options=(False, 4), pick=[(4, 0), (4, 3), (2, 1), (4, 3)])
        result = bc.compare(sim)
        self.assertEqual((4, 'random', 4), (result['offerLimit'], result['clientPick'], result['settings']['offerLimit']))
        self.assertEqual({'offersShown': {'2': 1, '4': 3}, 'picked': {'0': 1, '1': 1, '3': 2}}, result['booking']['windowPick'])
        text = bc.report(result)
        self.assertIn('asked for up to 4 two-hour windows', text)
        self.assertIn('took one of the windows shown at random, each equally likely', text)
        self.assertIn('| Booked with 4 offers shown', text)
        self.assertIn('| Took window 4 of those shown', text)
        self.assertNotIn('took the earliest', text)

    def test_a_version_3_plan_without_its_pick_is_refused(self):
        sim = build(Path(self.directory.name) / 'nopick', options=(False, 4), pick=[(4, 0), (4, 1), (4, 2), (4, 3)])
        path = sim / 'simulation.json'
        plan = json.loads(path.read_text(encoding='utf-8'))
        del plan['clientPick']
        bi.write_json(path, plan)
        with self.assertRaisesRegex(bc.CompareError, 'clientPick is None'):
            bc.compare(sim)

    def test_a_plan_from_before_the_options_keeps_its_bars_and_shows_both_parts_weekends(self):
        self.assertEqual((True, 1), (self.result['weekendWork'], self.result['overnightInFlight']))
        self.assertNotIn('weekendJobs', self.result['validity']['bars'])
        empty = {'days': 0, 'jobs': 0, 'technicianDays': 0, 'paidMinutes': 0, 'overtimeMinutes': 0, 'costCents': 0}
        self.assertEqual({'part1': empty, 'part2': empty}, self.result['weekends'])
        text = bc.report(self.result)
        self.assertIn('Every booking search asked for 1 offer, the window that adds the least cost for the dealership without new overtime', text)
        self.assertIn('Saturdays and Sundays included where a technician worked', text)
        self.assertIn('one continuous segment, one overnight proposal at a time', text)

    def test_without_weekend_work_a_part2_job_on_a_weekend_fails_the_run(self):
        # The dispatcher served wo-3 on Saturday 2024-03-09; a clean Part 2 serves nothing that weekend.
        saturday = on_weekend(PART1, '2024-03-05', '2024-03-09')
        with tempfile.TemporaryDirectory() as clean:
            result = bc.compare(build(clean, options=(False, 4), part1=saturday))
        self.assertTrue(result['validity']['valid'], result['validity'])
        self.assertEqual((0, 0), (result['validity']['bars']['weekendJobs'], result['validity']['bars']['weekendBookings']))
        self.assertEqual({'days': 1, 'jobs': 1, 'technicianDays': 1, 'paidMinutes': 300, 'overtimeMinutes': 300, 'costCents': 23500}, result['weekends']['part1'])
        self.assertEqual(bd.PAY_RULE, result['payRule'])
        self.assertEqual(0, result['weekends']['part2']['jobs'])
        text = bc.report(result)
        self.assertIn('books and moves nothing onto a weekend', text)
        self.assertIn("with up to 4 of a night's proposals in flight at once", text)
        self.assertIn('| weekendJobs ', text)
        rows = {cells[0]: cells[1:] for line in text.splitlines() if line.startswith('|') and len(cells := [c.strip() for c in line.strip('|').split('|')]) == 3}
        self.assertEqual((['1', '0'], ['5.0', '0.0'], ['5.0', '0.0'], ['235.00', '0.00']), (rows['Jobs'], rows['Paid (h)'], rows['Overtime (h)'], rows['Cost ($)']))
        self.assertIn('every paid minute of a Saturday or a Sunday at the overtime rate in both parts', text)
        self.assertIn("Overtime is as California law sets it: a technician's paid time past 8 hours in a day, with double time past 12", text)
        self.assertIn('Paid hours leave out the unpaid meal periods California law requires, with no waiver: a 1 hour lunch once a technician-day passes 5 hours of work, and 30 minutes more once it passes 10.', text)
        self.assertNotIn('from 17:00 until the technician is back', text)
        # Part 2 with wo-3 on Sunday 2024-03-10: one scored weekend job and one weekend booking, each its own bar.
        with tempfile.TemporaryDirectory() as broken:
            bars = bc.compare(build(broken, options=(False, 4), part2=on_weekend(PART2, '2024-03-06', '2024-03-10'), served={'wo-3': '2024-03-10'}))['validity']
        self.assertFalse(bars['valid'])
        self.assertEqual((1, 1), (bars['bars']['weekendJobs'], bars['bars']['weekendBookings']))
        # The same Part 2 under a plan with weekend work has no weekend bar, and still fails: its Sunday is 280 minutes of overtime.
        with tempfile.TemporaryDirectory() as allowed:
            result = bc.compare(build(allowed, options=(True, 1), part2=on_weekend(PART2, '2024-03-06', '2024-03-10'), served={'wo-3': '2024-03-10'}))
        self.assertNotIn('weekendJobs', result['validity']['bars'])
        self.assertFalse(result['validity']['valid'])
        self.assertEqual(280, result['validity']['bars']['overtimeMinutes'])
        self.assertEqual({'jobs': 1, 'paidMinutes': 280, 'overtimeMinutes': 280, 'costCents': 22000},
                         {name: result['weekends']['part2'][name] for name in ('jobs', 'paidMinutes', 'overtimeMinutes', 'costCents')})

    def test_rows_that_do_not_hold_the_pay_rule_are_refused(self):
        # A Saturday still priced as a weekday (regular time, no overtime) in either part, and rows without the scorer's mark.
        for case in (dict(part1=moved(PART1, '2024-03-05', '2024-03-09')), dict(part2=moved(PART2, '2024-03-06', '2024-03-09'), served={'wo-3': '2024-03-09'})):
            with tempfile.TemporaryDirectory() as old:
                with self.assertRaisesRegex(bd.SummaryError, '2024-03-09: a weekend day whose paid minutes are not all overtime'):
                    bc.compare(build(old, options=(True, 1), **case))
        sim = build(Path(self.directory.name) / 'unmarked')
        path = sim / 'export' / 'part2' / 'rows.json'
        rows = json.loads(path.read_text(encoding='utf-8'))
        del rows['payRule']
        bi.write_json(path, rows)
        with self.assertRaisesRegex(bd.SummaryError, 'not paid under the weekly pay rule'):
            bc.compare(sim)

    def test_daily_overtime_in_part2_fails_the_overtime_bar(self):
        # Part 2's t01 paid 10 hours on 2024-03-05 (an 11 hour day less its lunch): 2 hours past 8 are overtime, though
        # none of it falls after 17:00.
        long_day = dict(PART2, **{'2024-03-05': ([route('t01', 2, 600, 120, 70, 40000)], (35000, 24000, 9000, 2000), ['wo-1', 'wo-2'])})
        with tempfile.TemporaryDirectory() as other:
            result = bc.compare(build(other, part2=long_day))
        self.assertFalse(result['validity']['valid'])
        self.assertEqual(120, result['validity']['bars']['overtimeMinutes'])
        self.assertEqual(0, result['validity']['bars']['outsideShiftMinutes'], 'the 17:00 guard is its own bar')
        self.assertEqual((0, 120), (result['part1']['study']['overtimeMinutes'], result['part2']['study']['overtimeMinutes']))
        self.assertEqual((60, 60), (result['part1']['study']['unpaidMinutes'], result['part2']['study']['unpaidMinutes']), 'one lunch in each part')
        # The same rows with the day's overtime left off are not the rule's.
        flat = dict(long_day, **{'2024-03-05': ([route('t01', 2, 600, 0, 70, 40000)], (32000, 30000, 0, 2000), ['wo-1', 'wo-2'])})
        with tempfile.TemporaryDirectory() as other:
            with self.assertRaisesRegex(bd.SummaryError, re.escape('2024-03-05: t01 has 0 overtime minutes (0 double time) where the rule gives 120 (0)')):
                bc.compare(build(other, part2=flat))

    def test_weekend_days_count_in_days_to_service_for_both_parts(self):
        # wo-3, requested Monday 2024-03-04: the dispatcher's Saturday is 5 days out, Part 2's next Monday is 7.
        requests = {'x': request('x', '2024-03-04', '2024-03-09')}
        outcomes = {'x': {'outcome': 'BOOKED', 'serviceDate': '2024-03-11', 'attempts': 1}}
        self.assertEqual(5, bc.part1_promptness(outcomes, requests)['study']['all']['medianDays'])
        self.assertEqual(7, bc.part2_promptness(outcomes, requests)['study']['all']['medianDays'])
        self.assertEqual(2, bc.paired_leads(outcomes, requests)['study']['median'])

    def test_a_plan_that_does_not_state_its_options_is_refused(self):
        sim = build(Path(self.directory.name) / 'bad', options=(False, 4))
        path = sim / 'simulation.json'
        plan = json.loads(path.read_text(encoding='utf-8'))
        del plan['weekendWork']
        bi.write_json(path, plan)
        with self.assertRaisesRegex(bc.CompareError, 'weekendWork is None'):
            bc.compare(sim)
        with self.assertRaisesRegex(bc.CompareError, 'none were given'):
            bc.validity([], [], [], weekend_work=False)
        with self.assertRaisesRegex(bc.CompareError, 'booked without a service date'):
            bc.weekend_bookings({'x': {'outcome': 'BOOKED', 'serviceDate': None}})

    def test_the_report_is_aligned_marks_the_tail_and_has_no_em_dash(self):
        text = bc.report(self.result)
        self.assertTrue(text.startswith('# Paso Robles backtest, Part 2: the product simulated\n'))
        titled = bc.report(self.result, 'Paso Robles backtest 2, Part 2: the product simulated')
        self.assertEqual(text.split('\n', 1)[1], titled.split('\n', 1)[1], 'the title is the only change')
        with self.assertRaisesRegex(bd.SummaryError, 'A report title is one trimmed line'):
            bc.report(self.result, '# Part 2')
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
