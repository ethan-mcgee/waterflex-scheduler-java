"""Regression coverage for infra/backtest_dispatcher.py on a synthetic as-run run: no real row appears here."""
import json
import tempfile
import unittest
from pathlib import Path

import backtest_dispatcher as bd


def route(tech, visits, paid, overtime, drive, meters, late=0, lateness=0, holds=True, availability=0):
    return {'technicianId': tech, 'holds': holds, 'visits': visits, 'paidMinutes': paid, 'overtimeMinutes': overtime, 'driveMinutes': drive,
            'meters': meters, 'latenessSeconds': lateness, 'lateVisits': late, 'availabilitySeconds': availability, 'unavailableRoads': 0,
            'qualifications': 0, 'capacityMinutes': 0, 'waitingMinutes': 0, 'bufferSeconds': 0, 'arrivals': {}}


def row(routes, cost, regular, overtime_cents, mileage):
    total = lambda name: sum(r[name] for r in routes)
    return {'feasible': all(r['holds'] for r in routes), 'routesHold': all(r['holds'] for r in routes), 'costCents': cost, 'regularCents': regular,
            'overtimeCents': overtime_cents, 'mileageCents': mileage, 'paidMinutes': total('paidMinutes'), 'overtimeMinutes': total('overtimeMinutes'),
            'driveMinutes': total('driveMinutes'), 'meters': total('meters'), 'latenessSeconds': total('latenessSeconds'), 'lateVisits': total('lateVisits'),
            'availabilitySeconds': total('availabilitySeconds'), 'pastShiftMinutes': -(-total('availabilitySeconds') // 60), 'unreachableLegs': 0,
            'routes': routes, 'datasetHash': 'h', 'planHash': 'p'}


def visit(code, lead, sales=False):
    return {'tags': [code], 'salesCall': sales, 'requestLeadDays': lead, 'technician': 't01', 'flags': []}


# Three service dates: a Monday in March 2024, a Friday in April 2024 and a Monday in January 2025.
DAYS = {
    '2024-03-04': ([route('t01', 2, 480, 0, 60, 40000), route('t02', 1, 300, 0, 30, 20000)], (16000, 15000, 0, 1000),
                   {'wo-2': visit('R15', 3), 'wo-3': visit('P02', 0), 'wo-4': visit('X01', 10, sales=True)}),
    '2024-04-05': ([route('t01', 1, 600, 60, 90, 80000, late=1, lateness=900, holds=False, availability=3600), route('t02', 0, 0, 0, 0, 0)],
                   (31000, 25000, 4500, 1500), {'wo-5': visit('R11', 1)}),
    '2025-01-06': ([route('t01', 1, 200, 0, 20, 10000)], (6000, 5500, 0, 500), {'wo-6': visit('R15', 3)}),
}
DEPOTS = {'2024-03-04': {'t01': 'paso-robles', 't02': 'santa-maria'}, '2024-04-05': {'t01': 'santa-maria', 't02': 'santa-maria'},
          '2025-01-06': {'t01': 'santa-maria'}}


def write_run(directory, rule='as-run', drop=None, extra=None, change=None):
    run = Path(directory) / 'run'
    days = []
    for day, (routes, _, observed) in DAYS.items():
        (run / 'days' / day).mkdir(parents=True, exist_ok=True)
        (run / 'days' / day / 'observed.json').write_text(json.dumps({'day': day, 'visits': observed}), encoding='utf-8')
        days.append({'day': day, 'jobs': len(observed), 'depots': DEPOTS[day]})
    ledger = {'rows': 9, 'kept': 6, 'excludedRows': 3, 'reasons': {'exclude': {'BOOKED_OVER_LIMIT': 2, 'OUT_OF_AREA': 1}, 'flag': {}}}
    (run / 'index.json').write_text(json.dumps({'runId': 'test-as-run', 'ruleSet': rule, 'routingIdentity': 'abc123def456ghi', 'days': days,
                                                'ledger': ledger}), encoding='utf-8')
    (run / 'study-config.json').write_text(json.dumps({'salesCallCodes': ['X01']}), encoding='utf-8')
    rows = []
    for day, (routes, cents, _) in DAYS.items():
        if day == drop:
            continue
        reading = row([dict(r) for r in routes], *cents)
        if change and day in change:
            change[day](reading)
        rows.append({'day': day, 'plan': 'as-planned.plan.json', 'row': reading})
    if extra:
        rows.append({'day': extra, 'plan': 'as-planned.plan.json', 'row': row([route('t01', 0, 0, 0, 0, 0)], 0, 0, 0, 0)})
    path = Path(directory) / 'rows.json'
    path.write_text(json.dumps({'version': 1, 'kind': 'backtest-score-days', 'dataset': 'facts.json', 'plan': 'as-planned.plan.json', 'rows': rows}), encoding='utf-8')
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
        self.assertEqual(1, route['study']['overtimeTechnicianDays'])
        self.assertEqual(60, route['2024-Q2']['overtimeMinutes'])
        self.assertEqual(4500, route['2024']['overtimeCents'])
        self.assertEqual(53000, route['study']['costCents'])
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

    def test_missing_and_malformed_input_is_refused(self):
        cases = [dict(drop='2024-04-05'), dict(extra='2025-02-03'), dict(rule='strict-17'),
                 dict(change={'2024-03-04': lambda r: r.__setitem__('overtimeCents', None)}),
                 dict(change={'2024-03-04': lambda r: r.pop('mileageCents')}),
                 dict(change={'2024-03-04': lambda r: r['routes'][0].__setitem__('visits', 5)})]
        for case in cases:
            with tempfile.TemporaryDirectory() as other:
                run, rows = write_run(other, **case)
                with self.assertRaises((bd.SummaryError, KeyError), msg=str(case)):
                    bd.summarize(run, rows)

    def test_the_report_is_aligned_and_marks_the_partial_period(self):
        text = bd.report(bd.summarize(self.run, self.rows))
        self.assertTrue(text.startswith('# Paso Robles backtest, Part 1: the dispatcher as run\n'))
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
