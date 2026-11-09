"""Regression coverage for infra/backtest_dispatcher.py on a synthetic as-run run: no real row appears here."""
import json
import re
import tempfile
import unittest
from pathlib import Path

import backtest_dispatcher as bd


def route(tech, visits, paid, overtime, drive, meters, late=0, lateness=0, holds=True, availability=0, unpaid=None, double=None):
    """A route as the scorer pays it. Without unpaid: a route paid 300 minutes or less had no meal; one paid up to 600
    had its 60 minute lunch and no more than 10 hours of work after it; one paid more had both meal periods (90)."""
    unpaid = (0 if paid <= 300 else 60 if paid <= 600 else 90) if unpaid is None else unpaid
    double = max(0, paid - 720) if double is None else double  # past 12 hours; a seventh day in a row says its own
    return {'technicianId': tech, 'holds': holds, 'visits': visits, 'paidMinutes': paid, 'overtimeMinutes': overtime, 'doubleTimeMinutes': double, 'unpaidMinutes': unpaid, 'driveMinutes': drive,
            'meters': meters, 'latenessSeconds': lateness, 'lateVisits': late, 'availabilitySeconds': availability, 'unavailableRoads': 0,
            'qualifications': 0, 'capacityMinutes': 0, 'waitingMinutes': 0, 'bufferSeconds': 0, 'arrivals': {}}


def row(routes, cost, regular, overtime_cents, mileage):
    total = lambda name: sum(r[name] for r in routes)
    return {'feasible': all(r['holds'] for r in routes), 'routesHold': all(r['holds'] for r in routes), 'costCents': cost, 'regularCents': regular,
            'overtimeCents': overtime_cents, 'mileageCents': mileage, 'paidMinutes': total('paidMinutes'), 'overtimeMinutes': total('overtimeMinutes'),
            'doubleTimeMinutes': total('doubleTimeMinutes'), 'unpaidMinutes': total('unpaidMinutes'), 'driveMinutes': total('driveMinutes'), 'meters': total('meters'), 'latenessSeconds': total('latenessSeconds'), 'lateVisits': total('lateVisits'),
            'availabilitySeconds': total('availabilitySeconds'), 'pastShiftMinutes': -(-total('availabilitySeconds') // 60), 'unreachableLegs': 0,
            'routes': routes, 'datasetHash': 'h', 'planHash': 'p'}


def visit(code, lead, sales=False):
    return {'tags': [code], 'salesCall': sales, 'requestLeadDays': lead, 'technician': 't01', 'flags': []}


# Three service dates: a Monday in March 2024, a Friday in April 2024 and a Monday in January 2025. The Friday is 10
# paid hours, 2 of them past 8 and so overtime; its hour past 17:00 is time outside the shift, not overtime in itself.
DAYS = {
    '2024-03-04': ([route('t01', 2, 480, 0, 60, 40000), route('t02', 1, 300, 0, 30, 20000)], (16000, 15000, 0, 1000),
                   {'wo-2': visit('R15', 3), 'wo-3': visit('P02', 0), 'wo-4': visit('X01', 10, sales=True)}),
    '2024-04-05': ([route('t01', 1, 600, 120, 90, 80000, late=1, lateness=900, holds=False, availability=3600), route('t02', 0, 0, 0, 0, 0)],
                   (34500, 24000, 9000, 1500), {'wo-5': visit('R11', 1)}),
    '2025-01-06': ([route('t01', 1, 200, 0, 20, 10000)], (6000, 5500, 0, 500), {'wo-6': visit('R15', 3)}),
}
DEPOTS = {'2024-03-04': {'t01': 'paso-robles', 't02': 'santa-maria'}, '2024-04-05': {'t01': 'santa-maria', 't02': 'santa-maria'},
          '2025-01-06': {'t01': 'santa-maria'}}


# Saturday 2024-03-09 as the scorer prices it: 300 paid minutes, all of them overtime at $45 an hour, and $10 of mileage.
SATURDAY = {'2024-03-09': ([route('t01', 1, 300, 300, 30, 20000)], (23500, 0, 22500, 1000), {'wo-7': visit('R15', 1)})}
# Monday 2024-03-11 to Friday 2024-03-15 as the scorer pays them: t01 is out 730 minutes a day, 640 of them paid, 160
# past 8 hours. Its 480 regular minutes a day make 2,400 in the week, so the week adds no overtime.
WEEK = {f'2024-03-{11 + index}': ([route('t01', 1, 640, overtime, 60, 16093)], (50 * (640 - overtime) + 75 * overtime + 1000, 50 * (640 - overtime), 75 * overtime, 1000),
                                  {f'wo-1{index}': visit('R15', 1)}) for index, overtime in enumerate((160,) * 5)}
# Monday 2024-03-18 to Sunday 2024-03-24: t01 works all seven days. The Monday is 780 paid minutes (240 past 8 hours, 60 of
# them past 12 at double time, $60 an hour); the Saturday is all overtime; the Sunday, its seventh day in a row, is all
# overtime with double time past 8 hours.
SEVEN = {'2024-03-18': ([route('t01', 1, 780, 300, 60, 16093)], (24000 + 24000 + 1000, 24000, 24000, 1000), {'wo-30': visit('R15', 1)}),
         **{f'2024-03-{day}': ([route('t01', 1, 480, 0, 60, 16093)], (25000, 24000, 0, 1000), {f'wo-{day}': visit('R15', 1)}) for day in range(19, 23)},
         '2024-03-23': ([route('t01', 1, 480, 480, 60, 16093)], (37000, 0, 36000, 1000), {'wo-23': visit('R15', 1)}),
         '2024-03-24': ([route('t01', 1, 600, 600, 60, 16093, double=120)], (49000, 0, 48000, 1000), {'wo-24': visit('R15', 1)})}


def write_run(directory, rule='as-run', drop=None, extra=None, change=None, more=None, marked=True, pay_rule=None):
    """more adds days to DAYS; marked False writes rows as a scorer from before the pay rule did, and pay_rule marks
    them with another rule than the scorer's."""
    run = Path(directory) / 'run'
    days = []
    every = dict(DAYS, **(more or {}))
    depots = dict(DEPOTS, **{day: {'t01': 'santa-maria'} for day in more or {}})
    for day, (routes, _, observed) in every.items():
        (run / 'days' / day).mkdir(parents=True, exist_ok=True)
        (run / 'days' / day / 'observed.json').write_text(json.dumps({'day': day, 'visits': observed}), encoding='utf-8')
        days.append({'day': day, 'jobs': len(observed), 'depots': depots[day]})
    ledger = {'rows': 9, 'kept': 6, 'excludedRows': 3, 'reasons': {'exclude': {'BOOKED_OVER_LIMIT': 2, 'OUT_OF_AREA': 1}, 'flag': {}}}
    (run / 'index.json').write_text(json.dumps({'runId': 'test-as-run', 'ruleSet': rule, 'routingIdentity': 'abc123def456ghi', 'days': days,
                                                'ledger': ledger}), encoding='utf-8')
    (run / 'study-config.json').write_text(json.dumps({'salesCallCodes': ['X01']}), encoding='utf-8')
    rows = []
    for day, (routes, cents, _) in every.items():
        if day == drop:
            continue
        reading = row([dict(r) for r in routes], *cents)
        if change and day in change:
            change[day](reading)
        rows.append({'day': day, 'plan': 'as-planned.plan.json', 'row': reading})
    if extra:
        rows.append({'day': extra, 'plan': 'as-planned.plan.json', 'row': row([route('t01', 0, 0, 0, 0, 0)], 0, 0, 0, 0)})
    path = Path(directory) / 'rows.json'
    document = {'version': 1, 'kind': 'backtest-score-days', 'dataset': 'facts.json', 'plan': 'as-planned.plan.json', 'rows': rows}
    path.write_text(json.dumps(dict(document, payRule=pay_rule or bd.PAY_RULE) if marked else document), encoding='utf-8')
    return run, path


class DispatcherSummaryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.run, self.rows = write_run(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def test_the_job_names_every_day_in_date_order(self):
        document = bd.job(self.run, self.rows)
        self.assertEqual('backtest-score-days', document['kind'])
        self.assertEqual(['2024-03-04', '2024-04-05', '2025-01-06'], [d['day'] for d in document['days']])
        self.assertTrue(all(Path(d['directory']).is_absolute() for d in document['days']))
        self.assertEqual('as-planned.plan.json', document['plan'])

    def test_route_totals_fall_in_the_period_of_their_service_date(self):
        result = bd.summarize(self.run, self.rows)
        route = result['route']
        self.assertEqual(['study', '2024', '2025', '2024-Q1', '2024-Q2', '2025-Q1', '2024-03', '2024-04', '2025-01'], result['periods'])
        self.assertEqual(3, route['study']['days'])
        self.assertEqual(5, route['study']['jobs'])
        self.assertEqual(4, route['study']['technicianDays'], 'an empty route is not a technician-day with work')
        self.assertEqual(1, route['study']['overtimeTechnicianDays'], 'the Friday of 10 paid hours')
        self.assertEqual(120, route['2024-Q2']['overtimeMinutes'], 'the 2 paid hours past 8, not the hour past 17:00')
        self.assertEqual(9000, route['2024']['overtimeCents'])
        self.assertEqual(120, route['study']['unpaidMinutes'], 'the lunch of the two technician-days longer than 6 hours')
        self.assertEqual(60, route['2024-04']['unpaidMinutes'])
        self.assertEqual(56500, route['study']['costCents'])
        self.assertEqual(route['study']['regularCents'] + route['study']['overtimeCents'] + route['study']['mileageCents'], route['study']['costCents'])
        self.assertEqual(200, route['study']['driveMinutes'])
        self.assertEqual(180, route['2024']['driveMinutes'])
        self.assertEqual(1, route['2024-04']['routesNotHolding'])
        self.assertEqual(60, route['2024-04']['pastShiftMinutes'])
        self.assertEqual(1, route['study']['lateVisits'])
        self.assertEqual(390, route['2024-03']['medianPaidPerTechnicianDay'])
        self.assertEqual(['2024-03-04', '2024-04-01', '2025-01-06'], [w['week'] for w in result['weeks']], 'Monday-to-Sunday weeks')
        self.assertEqual('2025-01-06', result['lastServiceDate'])

    def test_promptness_counts_calendar_days_and_weekdays_by_request_period(self):
        result = bd.summarize(self.run, self.rows)
        prompt = result['promptness']
        self.assertEqual(5, prompt['study']['all']['jobs'])
        self.assertEqual(1, prompt['study']['emergency']['jobs'])
        self.assertEqual(1, prompt['study']['sales']['jobs'])
        self.assertEqual(1, prompt['study']['all']['sameDay'])
        self.assertEqual(1, prompt['study']['all']['nextDay'])
        self.assertEqual(3, prompt['study']['all']['medianDays'])
        # Requested Friday 2024-03-01, served Monday 2024-03-04: 3 calendar days, 1 weekday.
        self.assertEqual(1, bd.weekdays_between(*map(__import__('datetime').date.fromisoformat, ('2024-03-01', '2024-03-04'))))
        self.assertEqual(0, bd.weekdays_between(*map(__import__('datetime').date.fromisoformat, ('2024-03-04', '2024-03-04'))))
        # The sales call was requested 2024-02-23 and the 2025 job on 2025-01-03: both fall in the request's period.
        self.assertEqual(1, prompt['2024-02']['sales']['jobs'])
        self.assertEqual(1, prompt['2025-01']['all']['jobs'])
        self.assertEqual(4, prompt['study']['all']['within5Weekdays'], 'all but the sales call requested 10 days ahead')

    def test_technicians_list_the_depots_they_worked_from(self):
        technicians = bd.summarize(self.run, self.rows)['technicians']
        self.assertEqual(['paso-robles', 'santa-maria'], technicians['t01']['depots'])
        self.assertEqual(['santa-maria'], technicians['t02']['depots'], 'an empty route is not a depot worked from')
        self.assertEqual(3, technicians['t01']['technicianDays'])

    def test_weekend_work_is_overtime_and_reported_on_its_own(self):
        self.assertEqual({'days': 0, 'jobs': 0, 'technicianDays': 0, 'paidMinutes': 0, 'overtimeMinutes': 0, 'costCents': 0},
                         bd.summarize(self.run, self.rows)['weekend'])
        self.assertIn('No job was served on a Saturday or a Sunday.', bd.report(bd.summarize(self.run, self.rows)))
        with tempfile.TemporaryDirectory() as other:
            result = bd.summarize(*write_run(other, more=SATURDAY))
        self.assertEqual({'days': 1, 'jobs': 1, 'technicianDays': 1, 'paidMinutes': 300, 'overtimeMinutes': 300, 'costCents': 23500}, result['weekend'])
        self.assertEqual(2, result['route']['study']['overtimeTechnicianDays'], 'the Friday past 8 paid hours and the Saturday')
        self.assertEqual(420, result['route']['study']['overtimeMinutes'])
        text = bd.report(result)
        self.assertIn('On a Saturday or a Sunday it is every paid minute', text)
        self.assertIn("Of the study's overtime, 5.0 hours are weekend work: 1 technician-days on 1 Saturdays and Sundays with 1 jobs, costing $235.00", text)

    def test_weekday_overtime_is_the_paid_time_past_8_hours_in_the_day(self):
        with tempfile.TemporaryDirectory() as other:
            result = bd.summarize(*write_run(other, more=WEEK))
        route = result['route']
        self.assertEqual(920, route['study']['overtimeMinutes'], '160 on each of the five days and the 120 of 2024-04-05')
        self.assertEqual(6, route['study']['overtimeTechnicianDays'])
        self.assertEqual(60000, route['2024-03']['overtimeCents'], '800 minutes at $45 an hour')
        self.assertEqual(0, route['study']['doubleTimeMinutes'])
        self.assertEqual(570, route['study']['unpaidMinutes'], 'five more days with both meal periods')
        week = next(w for w in result['weeks'] if w['week'] == '2024-03-11')
        self.assertEqual((3200, 800, 450), (week['paidMinutes'], week['overtimeMinutes'], week['unpaidMinutes']))
        self.assertEqual((920, 6), (result['technicians']['t01']['overtimeMinutes'], result['technicians']['t01']['overtimeTechnicianDays']))
        text = bd.report(result)
        self.assertIn("Overtime on a weekday is the technician's paid time past 8 hours in the day, with double time past 12, as California law sets it", text)
        self.assertIn('| Double time (h) |', text)
        self.assertIn('Work after 17:00 is not overtime in itself', text)
        self.assertIn('Paid hours leave out the unpaid meal periods, 9.5 hours in the study.', text)
        self.assertIn('unpaid meal periods as California law sets them, with no waiver', text)
        self.assertNotIn('from 17:00 until the technician is back', text)

    def test_double_time_is_paid_past_12_hours_and_past_8_on_a_seventh_day_in_a_row(self):
        with tempfile.TemporaryDirectory() as other:
            result = bd.summarize(*write_run(other, more=SEVEN))
        totals = result['route']
        self.assertEqual(180, totals['study']['doubleTimeMinutes'], '60 on the Monday and 120 on the seventh day')
        self.assertEqual(120 + 300 + 480 + 600, totals['study']['overtimeMinutes'])
        week = next(w for w in result['weeks'] if w['week'] == '2024-03-18')
        self.assertEqual((1380, 180), (week['overtimeMinutes'], week['doubleTimeMinutes']))
        # The same Sunday after a Saturday off is not a seventh day in a row: it has no double time.
        rested = {day: value for day, value in SEVEN.items() if day != '2024-03-23'}
        rested['2024-03-24'] = ([route('t01', 1, 600, 600, 60, 16093)], (46000, 0, 45000, 1000), {'wo-24': visit('R15', 1)})
        with tempfile.TemporaryDirectory() as other:
            self.assertEqual(60, bd.summarize(*write_run(other, more=rested))['route']['study']['doubleTimeMinutes'])
        with tempfile.TemporaryDirectory() as other:
            with self.assertRaisesRegex(bd.SummaryError, re.escape('2024-03-24: t01 has 600 overtime minutes (120 double time) where the rule gives 600 (0)')):
                bd.summarize(*write_run(other, more=dict(rested, **{'2024-03-24': SEVEN['2024-03-24']})))
        with tempfile.TemporaryDirectory() as other:
            with self.assertRaisesRegex(bd.SummaryError, re.escape('2024-03-24: t01 has 600 overtime minutes (0 double time) where the rule gives 600 (120)')):
                bd.summarize(*write_run(other, more=dict(SEVEN, **{'2024-03-24': rested['2024-03-24']})))

    def test_rows_that_do_not_hold_the_pay_rule_are_refused(self):
        # A row file without the scorer's mark or with another rule's, and marked ones whose minutes the rule does not give.
        for case in (dict(marked=False), dict(pay_rule=dict(bd.PAY_RULE, weeklyRegularMinutes=2700)), dict(pay_rule=dict(bd.PAY_RULE, weekendOvertime=1)),
                     dict(pay_rule={'weekendOvertime': True})):
            with tempfile.TemporaryDirectory() as other:
                with self.assertRaisesRegex(bd.SummaryError, 'not paid under the weekly pay rule', msg=str(case)):
                    bd.summarize(*write_run(other, **case))

        def both(name, value):
            # The same minutes on the day's one route and on the day, so the day still sums.
            return lambda r: (r.__setitem__(name, value), r['routes'][0].__setitem__(name, value))
        cases = [
            # The engine's reading, before any pay: no lunch on a day of 480 minutes.
            ('2024-03-04', lambda r: r['routes'][0].__setitem__('unpaidMinutes', 0), '2024-03-04: t01 has 0 unpaid minutes on a day of 480 minutes depot to depot'),
            # A lunch on a day of 5 hours or less.
            ('2025-01-06', both('unpaidMinutes', 60), '2025-01-06: t01 has 60 unpaid minutes on a day of 260 minutes depot to depot'),
            # Overtime after 17:00, as the scorer counted it before the rule, instead of the time past 8 hours.
            ('2024-04-05', both('overtimeMinutes', 60), '2024-04-05: t01 has 60 overtime minutes (0 double time) where the rule gives 120 (0)'),
            # The lunch alone on a day whose work after it still passes 10 hours: the second meal period is missing.
            ('2024-03-15', both('unpaidMinutes', 60), '2024-03-15: t01 has 60 unpaid minutes on a day of 700 minutes depot to depot'),
            # The day's overtime left off, and double time on a day of less than 12 paid hours.
            ('2024-03-14', both('overtimeMinutes', 0), '2024-03-14: t01 has 0 overtime minutes (0 double time) where the rule gives 160 (0)'),
            ('2024-03-12', both('doubleTimeMinutes', 10), '2024-03-12: t01 has 160 overtime minutes (10 double time) where the rule gives 160 (0)'),
            ('2024-03-15', lambda r: r.__setitem__('doubleTimeMinutes', 5), "2024-03-15: the routes do not sum to the day's doubleTimeMinutes"),
            ('2024-03-15', lambda r: r.__setitem__('paidMinutes', 641), "2024-03-15: the routes do not sum to the day's paidMinutes"),
            ('2024-03-15', lambda r: r.__setitem__('overtimeMinutes', 0), "2024-03-15: the routes do not sum to the day's overtimeMinutes"),
            ('2024-03-15', lambda r: r['routes'].append(dict(r['routes'][0], visits=0)), '2024-03-15: two routes of t01')]
        for day, change, message in cases:
            with tempfile.TemporaryDirectory() as other:
                with self.assertRaisesRegex(bd.SummaryError, re.escape(message)):
                    bd.summarize(*write_run(other, more=WEEK, change={day: change}))
        # A Saturday still priced as a weekday.
        weekday = lambda r: (r.__setitem__('overtimeMinutes', 0), r['routes'][0].__setitem__('overtimeMinutes', 0), r.__setitem__('regularCents', 15000))
        route_only = lambda r: r['routes'][0].__setitem__('overtimeMinutes', 0)
        regular = lambda r: r.__setitem__('regularCents', 1)
        for change in (weekday, route_only, regular):
            with tempfile.TemporaryDirectory() as other:
                with self.assertRaisesRegex(bd.SummaryError, '2024-03-09: a weekend day whose paid minutes are not all overtime'):
                    bd.summarize(*write_run(other, more=SATURDAY, change={'2024-03-09': change}))
        with self.assertRaisesRegex(bd.SummaryError, 'is not a date'):
            bd.is_weekend('saturday')
        with self.assertRaisesRegex(bd.SummaryError, 'is not a date'):
            bd.is_weekend(None)

    def test_missing_and_malformed_input_is_refused(self):
        cases = [dict(drop='2024-04-05'), dict(extra='2025-02-03'), dict(rule='strict-17'),
                 dict(change={'2024-03-04': lambda r: r.__setitem__('overtimeCents', None)}),
                 dict(change={'2024-03-04': lambda r: r.pop('mileageCents')}),
                 dict(change={'2024-03-04': lambda r: r.pop('unpaidMinutes')}),
                 dict(change={'2024-03-04': lambda r: r['routes'][0].pop('unpaidMinutes')}),
                 dict(change={'2024-03-04': lambda r: r.pop('doubleTimeMinutes')}),
                 dict(change={'2024-03-04': lambda r: r['routes'][0].pop('doubleTimeMinutes')}),
                 dict(change={'2024-03-04': lambda r: r['routes'][0].__setitem__('unpaidMinutes', None)}),
                 dict(change={'2024-03-04': lambda r: r['routes'][0].__setitem__('visits', 5)})]
        for case in cases:
            with tempfile.TemporaryDirectory() as other:
                run, rows = write_run(other, **case)
                with self.assertRaises((bd.SummaryError, KeyError), msg=str(case)):
                    bd.summarize(run, rows)

    def test_the_report_is_aligned_and_marks_the_partial_period(self):
        text = bd.report(bd.summarize(self.run, self.rows))
        self.assertTrue(text.startswith('# Paso Robles backtest, Part 1: the dispatcher as run\n'))
        titled = bd.report(bd.summarize(self.run, self.rows), 'Paso Robles backtest 2, Part 1: the dispatcher under the weekly pay rule')
        self.assertTrue(titled.startswith('# Paso Robles backtest 2, Part 1: the dispatcher under the weekly pay rule\n\nGenerated by'))
        self.assertEqual(text.split('\n', 1)[1], titled.split('\n', 1)[1], 'the title is the only change')
        for title in ('', '  ', '# Two hashes', 'Two\nlines', ' padded', None):
            with self.assertRaisesRegex(bd.SummaryError, 'A report title is one trimmed line', msg=repr(title)):
                bd.report(bd.summarize(self.run, self.rows), title)
        self.assertNotIn('—', text)
        self.assertIn('2025 (to 2025-01-06)', text)
        self.assertIn('| Study ', text)
        self.assertIn('| 2024-03 ', text, 'period labels are left-aligned text')
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


if __name__ == '__main__':
    unittest.main()
