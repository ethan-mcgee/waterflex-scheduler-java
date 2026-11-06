"""Regression coverage for infra/backtest_import.py on a fabricated history: no real row, address or account appears here."""
import csv
import datetime as dt
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from zoneinfo import ZoneInfo

import backtest_import as bi

JAR = Path(__file__).resolve().parent.parent / 'campaign-benchmark' / 'target' / 'campaign-benchmark-0.1.0-SNAPSHOT.jar'
ZONE = ZoneInfo('America/Los_Angeles')


def config(**changes):
    base = {'runId': 'backtest-test', 'timeZone': 'America/Los_Angeles', 'metroId': 'metro-test', 'depot': {'lat': 35.63, 'lng': -120.69},
            'bbox': {'west': -121.5, 'south': 34.3, 'east': -119.4, 'north': 36.3}, 'years': [2024, 2025], 'calibrationYears': [2024],
            'shift': {'start': '08:00', 'end': '17:00', 'salesCallEnd': '19:00'}, 'windowMinutes': 120, 'salesCallCodes': ['X01'],
            'zeroDurationMinutes': 60, 'excludeTechnicians': ['ZZ9'],
            'rates': {'regularHourly': '30', 'overtimeHourly': '45', 'mileagePerMile': '0.67', 'travelBufferPct': '0.1', 'travelBufferMinutes': 2,
                      'arrivalBuffer': {'percent': 25, 'minMinutes': 15, 'maxMinutes': 60}},
            'policy': {'fairnessBudget': '0.02', 'dailySearch': 'TABU_KOPT', 'costSearchSeconds': 10, 'fairnessSearchSeconds': 5,
                       'bookingDeadlineMs': 5000, 'regularWindowThreshold': 2},
            'search': {'variant': 'TABU_KOPT', 'seed': 17, 'remainingMillis': 15000}, 'apiDateShiftDays': 1057,
            'ruleSets': ['strict-17', 'evening-sales', 'sales-day'], 'primaryRuleSet': 'strict-17'}
    base.update(changes)
    return bi.validate_config(base)


def record(account, lat, lng, day, tech, code, booked='A,9,0', duration='60', start='A,9,10', end='A,9,40', scheduled=None, requested=None):
    """One fabricated CSV row in the history's column order; every address is invented."""
    sa, sh, sm = booked.split(',') if booked else ('', '0', '0')
    aa, ah, am = start.split(',')
    ea, eh, em = end.split(',')
    fmt = lambda d: d.strftime('%m/%d/%Y') if d else ''
    return [account, '12', 'FABRICATED LN   ', 'PASO ROBLES   ', 'CA', str(lat), str(lng), fmt(requested or day), code, 'DESC', '   ', '', '   ', '',
            fmt(scheduled or day), fmt(day), tech, tech, sa, sh, sm, duration, aa, ah, am, ea, eh, em]


MONDAY = dt.date(2024, 1, 8)


def history():
    """Two weeks of a two-technician shop, with one row for every rule."""
    rows = []
    day = MONDAY
    for offset in range(14):
        d = MONDAY + dt.timedelta(days=offset)
        if d.weekday() >= 5 or d == MONDAY + dt.timedelta(days=9):  # the second Wednesday is a company holiday
            continue
        rows.append(record('100001', 35.62, -120.68, d, 'AB1', 'R15', booked='A,9,0', start='A,9,5', end='A,9,35'))
        rows.append(record('100002', 35.60, -120.66, d, 'AB1', 'P04', booked='A,11,0', start='A,10,50', end='A,11,30'))
        rows.append(record('100003', 35.50, -120.67, d, 'CD2', 'R11', booked='P,1,0', start='P,1,20', end='P,2,0'))
    # Rule rows, all on the first Tuesday.
    tuesday = MONDAY + dt.timedelta(days=1)
    rows.append(record('100004', 35.55, -120.70, tuesday, 'AB1', 'X01', booked='P,5,0', start='P,5,5', end='P,6,0'))        # evening sales call, kept
    rows.append(record('100005', 35.55, -120.71, tuesday, 'CD2', 'R15', booked='P,6,30', start='P,6,30', end='P,7,0'))      # non-sales booked inside the sales day end, kept
    rows.append(record('100006', 35.56, -120.72, tuesday, 'CD2', 'R15', booked='P,2,0', duration='0', start='P,2,0', end='P,2,30'))  # zero duration repaired
    rows.append(record('100007', 35.57, -120.73, tuesday, 'CD2', 'R15', booked='P,3,0', start='P,3,0', end='P,2,30'))        # end before start, flagged
    rows.append(record('100008', 35.58, -120.74, tuesday, 'CD2', 'R10', booked='A,10,0', scheduled=MONDAY))                  # rescheduled, flagged
    rows.append(record('100009', 32.71, -117.16, tuesday, 'AB1', 'R15', booked='A,10,30'))                                   # San Diego pin, excluded
    rows.append(record('100010', 35.59, -120.75, dt.date(1924, 6, 10), 'AB1', 'R15'))                                       # bad year, excluded
    rows.append(record('100011', 35.59, -120.75, tuesday, 'AB1', 'R15', booked=''))                                          # no booked time, excluded
    rows.append(record('100012', 35.59, -120.75, tuesday, 'ZZ9', 'R15'))                                                     # singleton technician, excluded
    rows.append(record('100013', 35.59, -120.76, tuesday, 'AB1', ''))                                                        # no complaint, excluded
    wednesday = MONDAY + dt.timedelta(days=2)
    rows.append(record('100014', 35.59, -120.76, wednesday, 'AB1', 'R15', booked='P,5,30'))                                  # non-sales day, booked after 17:00, held out
    rows.append(record('100017', 35.59, -120.76, wednesday, 'AB1', 'R15', booked='P,7,15'))                                  # past every rule set's end, excluded
    rows.append(record('100015', 35.59, -120.77, wednesday, 'AB1', 'P03', booked='A,7,30'))                                  # 07:30 booking, kept
    saturday = MONDAY + dt.timedelta(days=5)
    rows.append(record('100016', 35.61, -120.69, saturday, 'CD2', 'R12', booked='A,9,0'))                                    # weekend, flagged, roster is who worked
    return rows


def write_history(directory):
    path = Path(directory) / 'history.csv'
    with path.open('w', newline='', encoding='utf-8') as stream:
        writer = csv.writer(stream)
        writer.writerow(bi.COLUMNS)
        writer.writerows(history())
    return path


class DepotConfigTest(unittest.TestCase):
    def test_an_address_only_depot_validates_but_cannot_build(self):
        cfg = config()
        cfg['depot'] = {'address': {'street': '1 Example St', 'city': 'Paso Robles', 'state': 'CA', 'postalCode': '93446'}}
        bi.validate_config(cfg)
        with self.assertRaises(bi.ImportError_):
            bi.build([], cfg, Path(tempfile.mkdtemp()), bi.StubRoadClient(), None)

    def test_a_template_placeholder_depot_is_refused(self):
        cfg = config()
        cfg['depot'] = {'address': {'street': '<house number and street>', 'city': '<city>', 'state': 'CA', 'postalCode': '<zip>'}}
        with self.assertRaises(bi.ImportError_):
            bi.validate_config(cfg)

    def test_rule_sets_are_named_and_the_primary_is_one_of_them(self):
        with self.assertRaises(bi.ImportError_):
            config(ruleSets=['overtime-19'])
        with self.assertRaises(bi.ImportError_):
            config(ruleSets=['strict-17', 'strict-17'])
        with self.assertRaises(bi.ImportError_):
            config(ruleSets=['sales-day'], primaryRuleSet='strict-17')

    def test_travel_minutes_match_the_engine_rounding(self):
        rates = {'travelBufferPct': '0.1', 'travelBufferMinutes': 2}
        self.assertEqual(13, bi.travel_minutes(600, rates), 'ceil(660 / 60) + 2')
        self.assertEqual(14, bi.travel_minutes(601, rates), 'ceil(661.1 / 60) + 2')
        self.assertEqual(2, bi.travel_minutes(0, rates))

    def test_a_boolean_coordinate_is_refused(self):
        cfg = config()
        cfg['depot'] = {'lat': True, 'lng': -120.69}
        with self.assertRaises(bi.ImportError_):
            bi.validate_config(cfg)


class ParsingTest(unittest.TestCase):
    def test_twelve_hour_clocks(self):
        self.assertEqual(0, bi.parse_clock('A', '12', '0'))
        self.assertEqual(12 * 60, bi.parse_clock('P', '12', '0'))
        self.assertEqual(9 * 60 + 15, bi.parse_clock('A', '9', '15'))
        self.assertEqual(17 * 60, bi.parse_clock('P', '5', '0'))
        self.assertIsNone(bi.parse_clock('P', '13', '0'))
        self.assertIsNone(bi.parse_clock('N', '12', '0'))
        self.assertIsNone(bi.parse_clock('', '0', '0'))
        self.assertIsNone(bi.parse_clock('A', '9', '60'))

    def test_a_wrong_header_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'bad.csv'
            path.write_text('a,b,c\n1,2,3\n', encoding='utf-8')
            with self.assertRaises(bi.ImportError_):
                bi.parse_rows(path)

    def test_local_instants_follow_daylight_saving(self):
        self.assertEqual('2024-01-08T16:00:00Z', bi.local_instant(dt.date(2024, 1, 8), 8 * 60, ZONE))
        self.assertEqual('2024-07-08T15:00:00Z', bi.local_instant(dt.date(2024, 7, 8), 8 * 60, ZONE))


class RulesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.rows = bi.parse_rows(write_history(self.directory.name))
        self.config = config()

    def tearDown(self):
        self.directory.cleanup()

    def test_every_rule_lands_in_the_ledger_with_its_reason(self):
        ledger, kept = bi.apply_rules(self.rows, self.config)
        excluded = {reason: {row.index for row, r, s in ledger if r == reason and s == bi.EXCLUDE} for reason in
                    ('OUT_OF_AREA', 'BAD_YEAR', 'NO_BOOKED_TIME', 'SINGLETON_TECH', 'NO_COMPLAINT', 'BOOKED_AFTER_DAY_END')}
        by_account = {int(r[0]): i for i, r in enumerate(history(), start=2)}
        self.assertEqual({by_account[100009]}, excluded['OUT_OF_AREA'])
        self.assertEqual({by_account[100010]}, excluded['BAD_YEAR'])
        self.assertEqual({by_account[100011]}, excluded['NO_BOOKED_TIME'])
        self.assertEqual({by_account[100012]}, excluded['SINGLETON_TECH'])
        self.assertEqual({by_account[100013]}, excluded['NO_COMPLAINT'])
        self.assertEqual({by_account[100017]}, excluded['BOOKED_AFTER_DAY_END'], 'a 19:15 booking is past every rule set\'s end')
        flags = {reason: {row.index for row, r, s in ledger if r == reason and s == bi.FLAG} for reason in
                 ('ZERO_DURATION_REPAIRED', 'ACTUAL_END_NOT_AFTER_START', 'RESCHEDULED', 'WEEKEND')}
        self.assertEqual({by_account[100006]}, flags['ZERO_DURATION_REPAIRED'])
        self.assertEqual({by_account[100007]}, flags['ACTUAL_END_NOT_AFTER_START'])
        self.assertEqual({by_account[100008]}, flags['RESCHEDULED'])
        self.assertEqual({by_account[100016]}, flags['WEEKEND'])
        kept_accounts = {int(history()[row.index - 2][0]) for row in kept}
        self.assertIn(100005, kept_accounts, 'an 18:30 booking on the sales-call day stays')
        self.assertIn(100015, kept_accounts, 'a 07:30 booking stays')
        self.assertIn(100014, kept_accounts, 'a 17:30 booking is a shift question, decided per rule set, not a data error')
        self.assertNotIn(100017, kept_accounts)
        self.assertEqual(60, next(row for row in kept if history()[row.index - 2][0] == '100006').booked_minutes)

    def test_zero_duration_is_excluded_when_no_repair_is_configured(self):
        ledger, _ = bi.apply_rules(self.rows, config(zeroDurationMinutes=None))
        self.assertEqual(1, sum(1 for _, reason, severity in ledger if reason == 'ZERO_DURATION' and severity == bi.EXCLUDE))

    def test_roster_follows_spans_weekdays_and_company_holidays(self):
        ledger, kept = bi.apply_rules(self.rows, self.config)
        spans, holidays, on_shift = bi.roster(self.rows, kept, self.config)
        self.assertEqual({'AB1', 'CD2'}, set(spans))
        self.assertEqual([MONDAY + dt.timedelta(days=9)], holidays, 'the empty second Wednesday is the only holiday')
        self.assertEqual(['AB1', 'CD2'], on_shift[MONDAY + dt.timedelta(days=1)])
        saturday = MONDAY + dt.timedelta(days=5)
        self.assertEqual(['CD2'], on_shift[saturday], 'a weekend day rosters only who worked')
        self.assertNotIn(MONDAY + dt.timedelta(days=9), on_shift)

    def test_a_corrected_coordinate_brings_a_row_back(self):
        index = next(i for i, r in enumerate(history(), start=2) if r[0] == '100009')
        ledger, kept = bi.apply_rules(bi.parse_rows(write_history(self.directory.name)), self.config, {str(index): {'lat': 35.6, 'lng': -120.7}})
        self.assertIn(index, {row.index for row in kept})
        self.assertIn('GEOCODE_CORRECTED', next(row for row in kept if row.index == index).flags)


class BuildTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.out = Path(self.directory.name) / 'run'
        self.rows = bi.parse_rows(write_history(self.directory.name))
        self.run = bi.build(self.rows, config(), self.out, bi.StubRoadClient())
        self.index = self.variant('sales-day')

    def tearDown(self):
        self.directory.cleanup()

    def variant(self, rule):
        return json.loads((self.out / 'variants' / rule / 'index.json').read_text(encoding='utf-8'))

    def day(self, date, rule='sales-day'):
        return self.out / 'variants' / rule / 'days' / date.isoformat()

    def read(self, date, name, rule='sales-day'):
        with (self.day(date, rule) / name).open(encoding='utf-8') as stream:
            return json.load(stream)

    def held(self, rule):
        with (self.out / 'variants' / rule / 'held-out.csv').open(encoding='utf-8', newline='') as stream:
            return {int(history()[int(row['row']) - 2][0]): row['salesCall'] == 'True' for row in csv.DictReader(stream)}

    def test_the_run_directory_holds_every_day_and_the_ledger(self):
        self.assertTrue((self.out / 'ledger.csv').exists())
        summary = json.loads((self.out / 'ledger-summary.json').read_text(encoding='utf-8'))
        self.assertEqual(len(history()), summary['rows'])
        self.assertEqual(summary['rows'] - summary['excludedRows'], summary['kept'])
        self.assertEqual(6, summary['excludedRows'], 'San Diego pin, 1924 row, no booked time, singleton, no complaint, booked after 19:00')
        self.assertEqual({'strict-17', 'evening-sales', 'sales-day'}, set(self.run['ruleSets']))
        self.assertEqual('strict-17', self.run['primaryRuleSet'])
        self.assertFalse((self.out / 'index.json').exists(), 'the root is not a run directory; each rule set is')
        for rule in ('strict-17', 'evening-sales', 'sales-day'):
            index = self.variant(rule)
            self.assertEqual(rule, index['ruleSet'])
            self.assertEqual(rule == 'strict-17', index['primary'])
            self.assertEqual(f'backtest-test-{rule}', index['runId'])
            self.assertEqual(10, len(index['days']), '9 working days plus the Saturday')
            self.assertEqual('stub-road-model-v1', index['routingIdentity'])
            self.assertEqual(1057, index['apiDateShiftDays'])
            self.assertEqual(rule, json.loads((self.out / 'variants' / rule / 'study-config.json').read_text(encoding='utf-8'))['ruleSet'])
            for entry in index['days']:
                self.assertEqual({'facts.json', 'facts-actual-durations.json', 'as-planned.plan.json', 'as-executed.plan.json', 'api-request.json', 'observed.json'},
                                 set(entry['files']))
                self.assertEqual('calibration', entry['split'])
                self.assertEqual(rule, entry['ruleSet'])

    def test_no_account_or_street_leaves_the_csv(self):
        for path in self.out.rglob('*'):
            # technicians.json is the pseudonym map itself, kept in the run directory for the study's own use.
            if path.is_file() and path.name != 'technicians.json':
                text = path.read_text(encoding='utf-8')
                for secret in ('10000', 'FABRICATED', 'AB1', 'CD2'):
                    self.assertNotIn(secret, text, f'{secret} in {path.name}')

    def test_sales_day_runs_to_nineteen_hundred_for_everyone(self):
        tuesday = MONDAY + dt.timedelta(days=1)
        facts = self.read(tuesday, 'facts.json')
        entry = next(e for e in self.index['days'] if e['day'] == tuesday.isoformat())
        self.assertTrue(entry['salesCallDay'])
        self.assertEqual(1, entry['salesCalls'])
        self.assertEqual('19:00', entry['dayEnd'])
        for technician in facts['technicians']:
            self.assertEqual(bi.local_instant(tuesday, 19 * 60, ZONE), technician['shiftEnd'])
            self.assertEqual(660, technician['maxDailyMinutes'])
            self.assertEqual(0, technician['maxOvertimeMinutes'])
            self.assertEqual(list(range(len(facts['services']))), technician['qualifications'], 'everyone does everything')
        monday = self.read(MONDAY, 'facts.json')
        for technician in monday['technicians']:
            self.assertEqual(bi.local_instant(MONDAY, 17 * 60, ZONE), technician['shiftEnd'])
            self.assertEqual(540, technician['maxDailyMinutes'])
        sales = next(v for v in facts['visits'] if facts['services'][v['service']]['id'] == 'X01')
        self.assertEqual(bi.local_instant(tuesday, 17 * 60, ZONE), sales['windowStart'])
        self.assertEqual(bi.local_instant(tuesday, 19 * 60, ZONE), sales['windowEnd'])
        # The 18:30 hour-long job cannot finish by 19:00, so it leaves every plan of the rule set and is counted.
        self.assertEqual({100005: False, 100014: False}, self.held('sales-day'))
        self.assertEqual({'rows': 2, 'salesCalls': 0}, self.index['ledger']['heldOut'])

    def test_strict_17_ends_every_day_at_seventeen_hundred_and_holds_out_the_evening(self):
        tuesday = MONDAY + dt.timedelta(days=1)
        facts = self.read(tuesday, 'facts.json', 'strict-17')
        for technician in facts['technicians']:
            self.assertEqual(bi.local_instant(tuesday, 17 * 60, ZONE), technician['shiftEnd'])
            self.assertEqual(540, technician['maxDailyMinutes'])
        self.assertEqual({100004: True, 100005: False, 100014: False}, self.held('strict-17'))
        entry = next(e for e in self.variant('strict-17')['days'] if e['day'] == tuesday.isoformat())
        self.assertEqual(2, entry['heldOut'])
        self.assertEqual(1, entry['heldOutSalesCalls'])
        self.assertEqual('17:00', entry['dayEnd'])
        planned = self.read(tuesday, 'as-planned.plan.json', 'strict-17')
        held_ids = {f'wo-{i}' for i, r in enumerate(history(), start=2) if r[0] in ('100004', '100005')}
        self.assertFalse(held_ids & set(sum(planned['routes'].values(), [])), 'a held-out job leaves the dispatcher\'s plan too')
        self.assertFalse(held_ids & {v['id'] for v in facts['visits']})

    def test_evening_sales_extends_only_the_technician_with_the_evening_call(self):
        tuesday = MONDAY + dt.timedelta(days=1)
        facts = self.read(tuesday, 'facts.json', 'evening-sales')
        ends = {t['id']: t['shiftEnd'] for t in facts['technicians']}
        self.assertEqual({'t01': bi.local_instant(tuesday, 19 * 60, ZONE), 't02': bi.local_instant(tuesday, 17 * 60, ZONE)}, ends)
        services = [service['id'] for service in facts['services']]
        sales = next(v for v in facts['visits'] if services[v['service']] == 'X01@t01')
        qualified = {t['id']: {services[i] for i in t['qualifications']} for t in facts['technicians']}
        self.assertIn('X01@t01', qualified['t01'])
        self.assertNotIn('X01@t01', qualified['t02'], 'the evening call stays with the technician the dispatcher gave it')
        self.assertEqual('t01', facts['technicians'][sales['originalTechnician']]['id'])
        others = [v for v in facts['visits'] if v is not sales]
        self.assertTrue(all(v['windowEnd'] <= bi.local_instant(tuesday, 17 * 60, ZONE) for v in others), 'ordinary windows end at 17:00')
        entry = next(e for e in self.variant('evening-sales')['days'] if e['day'] == tuesday.isoformat())
        self.assertEqual(['t01'], entry['extendedTechnicians'])
        self.assertEqual(1, entry['pinnedSalesCalls'])
        self.assertEqual({100005: False, 100014: False}, self.held('evening-sales'))
        request = self.read(tuesday, 'api-request.json', 'evening-sales')['snapshot']
        shifted = tuesday + dt.timedelta(days=1057)
        self.assertEqual({'t01': bi.local_instant(shifted, 19 * 60, ZONE), 't02': bi.local_instant(shifted, 17 * 60, ZONE)},
                         {d['technicianId']: d['shift']['end'] for d in request['technicianDays']})
        self.assertEqual({'t01': 660, 't02': 540}, {d['technicianId']: d['maxPaidMinutes'] for d in request['technicianDays']})
        self.assertNotIn('X01@t01', next(t for t in request['technicians'] if t['id'] == 't02')['qualifications'])

    def test_plans_and_datasets_agree_on_order_and_durations(self):
        tuesday = MONDAY + dt.timedelta(days=1)
        facts, actual = self.read(tuesday, 'facts.json'), self.read(tuesday, 'facts-actual-durations.json')
        planned, executed = self.read(tuesday, 'as-planned.plan.json'), self.read(tuesday, 'as-executed.plan.json')
        ids = [v['id'] for v in facts['visits']]
        for technician in facts['technicians']:
            self.assertEqual([ids[i] for i in technician['assigned']], planned['routes'][technician['id']])
            booked = [facts['visits'][i]['windowStart'] for i in technician['assigned']]
            self.assertEqual(sorted(booked), booked, 'as planned follows the booked time')
        self.assertEqual(set(planned['routes']), set(executed['routes']))
        self.assertEqual(sorted(sum(planned['routes'].values(), [])), sorted(sum(executed['routes'].values(), [])))
        observed = self.read(tuesday, 'observed.json')
        for visit, with_actual in zip(facts['visits'], actual['visits']):
            seen = observed['visits'][visit['id']]
            self.assertEqual(visit['durationMinutes'], seen['bookedMinutes'])
            if seen['actualDurationMinutes'] is None:
                self.assertEqual(visit['durationMinutes'], with_actual['durationMinutes'], 'no logged span falls back to the booked minutes')
                self.assertIn('ACTUAL_END_NOT_AFTER_START', seen['flags'])
            else:
                self.assertEqual(seen['actualDurationMinutes'], with_actual['durationMinutes'])
        self.assertEqual(len(facts['locations']) * (len(facts['locations']) - 1), len(facts['roads']['entries']), 'every directed pair')
        self.assertTrue(all(e['state'] == 'REACHABLE' for e in facts['roads']['entries']))
        self.assertEqual(0, facts['technicians'][0]['departureLocation'])
        self.assertEqual('depot', facts['locations'][0]['id'])

    def test_the_api_request_is_the_same_day_on_a_shifted_date(self):
        tuesday = MONDAY + dt.timedelta(days=1)
        request = self.read(tuesday, 'api-request.json')
        facts = self.read(tuesday, 'facts.json')
        shifted = tuesday + dt.timedelta(days=1057)
        self.assertEqual(shifted.weekday(), tuesday.weekday())
        self.assertEqual(shifted.isoformat(), request['serviceDate'])
        snapshot = request['snapshot']
        self.assertEqual('metro-test', snapshot['metroId'])
        self.assertEqual(len(facts['technicians']), len(snapshot['technicianDays']))
        for day in snapshot['technicianDays']:
            self.assertEqual(bi.local_instant(shifted, 8 * 60, ZONE), day['shift']['start'])
            self.assertEqual(bi.local_instant(shifted, 19 * 60, ZONE), day['shift']['end'])
            self.assertEqual(660, day['maxPaidMinutes'])
            self.assertEqual({'lat': 35.63, 'lng': -120.69}, day['start'])
        by_id = {v['id']: v for v in facts['visits']}
        sequences = {}
        for appointment in snapshot['appointments']:
            visit = by_id[appointment['id']]
            original_local = dt.datetime.fromisoformat(visit['windowStart'].replace('Z', '+00:00')).astimezone(ZONE).time()
            shifted_local = dt.datetime.fromisoformat(appointment['window']['start'].replace('Z', '+00:00')).astimezone(ZONE).time()
            self.assertEqual(original_local, shifted_local, 'local wall-clock times survive the shift')
            self.assertEqual(appointment['plannedStart'], appointment['window']['start'])
            self.assertEqual(facts['technicians'][visit['originalTechnician']]['id'], appointment['technicianId'])
            sequences.setdefault(appointment['technicianId'], []).append(appointment['sequence'])
        for values in sequences.values():
            self.assertEqual(list(range(len(values))), values)
        self.assertEqual(snapshot['rates']['arrivalBuffer'], {'percent': 25, 'minMinutes': 15, 'maxMinutes': 60})
        self.assertEqual('TABU_KOPT', snapshot['policy']['dailySearch'])

    def test_an_unroutable_pin_leaves_every_plan_and_lands_in_the_ledger(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / 'run'
            bad = (35.50, -120.67)
            run = bi.build(self.rows, config(), out, bi.StubRoadClient(unroutable=[bad]))
            weekdays = sum(1 for r in history() if r[0] == '100003')
            self.assertEqual(weekdays, run['ledger']['reasons']['exclude']['UNROUTABLE_LOCATION'])
            for rule in ('strict-17', 'evening-sales', 'sales-day'):
                index = json.loads((out / 'variants' / rule / 'index.json').read_text(encoding='utf-8'))
                self.assertTrue(all(e['unreachableLegs'] == 0 for e in index['days']), 'no plan keeps a job no road reaches')
                facts = json.loads((out / 'variants' / rule / 'days' / MONDAY.isoformat() / 'facts.json').read_text(encoding='utf-8'))
                self.assertFalse(any(abs(l.get('latitude', 0) - bad[0]) < 1e-9 and abs(l.get('longitude', 0) - bad[1]) < 1e-9 for l in facts['locations']))

    def test_the_date_shift_is_chosen_in_whole_weeks_past_today(self):
        shift = bi.default_date_shift(self.rows, today=dt.date(2026, 11, 3), years={2024, 2025})
        self.assertEqual(0, shift % 7)
        self.assertGreaterEqual(MONDAY + dt.timedelta(days=shift), dt.date(2026, 11, 17))
        self.assertLess(MONDAY + dt.timedelta(days=shift), dt.date(2026, 11, 24))

    @unittest.skipUnless(JAR.exists(), 'campaign-benchmark jar not built')
    def test_every_dataset_seals_in_the_engine(self):
        cases = [(rule, entry) for rule in ('strict-17', 'evening-sales', 'sales-day') for entry in self.variant(rule)['days']]
        for rule, entry in cases:
            for name in ('facts.json', 'facts-actual-durations.json'):
                source = self.day(dt.date.fromisoformat(entry['day']), rule) / name
                sealed = source.with_suffix('.sealed.json')
                request = source.with_suffix('.seal.json')
                receipt = source.with_suffix('.receipt.json')
                request.write_text(json.dumps({'version': 1, 'kind': 'seal', 'input': str(source), 'output': str(sealed)}), encoding='utf-8')
                result = subprocess.run(['java', '-jar', str(JAR), str(request), str(receipt)], capture_output=True, text=True)
                self.assertEqual(0, result.returncode, f'{entry["day"]} {name}: {result.stderr[-2000:]}')
                self.assertIn('contentHash', json.loads(receipt.read_text(encoding='utf-8')))


NORTH = {'lat': 35.63, 'lng': -120.69}
SOUTH = {'lat': 34.95, 'lng': -120.44}
MOVE = MONDAY + dt.timedelta(days=7)


def two_depots(**changes):
    """The as-run study shape: two depots, t01 always north, t02 north until MOVE and south from it."""
    base = dict(config(), ruleSets=['as-run'], primaryRuleSet='as-run', roster='csv-days', maxBookedMinutes=360,
                depots={'north': dict(NORTH), 'south': dict(SOUTH)},
                technicianDepots={'t01': [{'from': None, 'depot': 'north'}],
                                  't02': [{'from': None, 'depot': 'north'}, {'from': MOVE.isoformat(), 'depot': 'south'}]})
    del base['depot']
    base.update(changes)
    return bi.validate_config(base)


class TwoDepotConfigTest(unittest.TestCase):
    def test_one_depot_or_named_depots_never_both(self):
        both = dict(two_depots(), depot=dict(NORTH))
        with self.assertRaises(bi.ImportError_):
            bi.validate_config(both)
        neither = dict(two_depots())
        del neither['depots']
        with self.assertRaises(bi.ImportError_):
            bi.validate_config(neither)
        with self.assertRaises(bi.ImportError_):
            bi.validate_config(dict(config(), technicianDepots={'t01': [{'from': None, 'depot': 'depot'}]}))

    def test_assignments_start_open_name_known_depots_and_rise(self):
        bad = [
            {'t01': [{'from': '2024-01-01', 'depot': 'north'}]},
            {'t01': [{'from': None, 'depot': 'west'}]},
            {'t01': [{'from': None, 'depot': 'north'}, {'from': '2024-02-01', 'depot': 'south'}, {'from': '2024-01-15', 'depot': 'north'}]},
            {'t01': [{'from': None, 'depot': 'north'}, {'from': 'soon', 'depot': 'south'}]},
            {'t01': [{'from': None}]},
            {'t01': []},
            {},
        ]
        for assignments in bad:
            with self.assertRaises(bi.ImportError_, msg=str(assignments)):
                two_depots(technicianDepots=assignments)

    def test_the_depot_follows_the_date_of_a_move(self):
        cfg = two_depots()
        self.assertEqual('north', bi.depot_of(cfg, 't02', MOVE - dt.timedelta(days=1))[0])
        self.assertEqual(('south', (SOUTH['lat'], SOUTH['lng'])), bi.depot_of(cfg, 't02', MOVE))
        with self.assertRaises(bi.ImportError_):
            bi.depot_of(cfg, 't03', MOVE)

    def test_the_roster_rule_and_the_booking_limit_are_checked(self):
        with self.assertRaises(bi.ImportError_):
            two_depots(roster='every-weekday')
        for limit in (0, -5, True, '360'):
            with self.assertRaises(bi.ImportError_, msg=repr(limit)):
                two_depots(maxBookedMinutes=limit)
        self.assertIsNone(two_depots(maxBookedMinutes=None)['maxBookedMinutes'])


class AsRunTwoDepotBuildTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.out = Path(self.directory.name) / 'run'
        self.rows = bi.parse_rows(write_history(self.directory.name))
        self.run = bi.build(self.rows, two_depots(), self.out, bi.StubRoadClient())
        self.index = json.loads((self.out / 'variants' / 'as-run' / 'index.json').read_text(encoding='utf-8'))

    def tearDown(self):
        self.directory.cleanup()

    def read(self, date, name):
        with (self.out / 'variants' / 'as-run' / 'days' / date.isoformat() / name).open(encoding='utf-8') as stream:
            return json.load(stream)

    def test_each_technician_day_starts_and_ends_at_its_depot_from_the_date_of_a_move(self):
        before = self.read(MONDAY, 'facts.json')
        self.assertEqual(['depot-north'], [location['id'] for location in before['locations'][:1]])
        self.assertEqual({0}, {t['departureLocation'] for t in before['technicians']} | {t['returnLocation'] for t in before['technicians']})
        after = self.read(MOVE, 'facts.json')
        self.assertEqual(['depot-north', 'depot-south'], [location['id'] for location in after['locations'][:2]])
        homes = {t['id']: (t['departureLocation'], t['returnLocation']) for t in after['technicians']}
        self.assertEqual({'t01': (0, 0), 't02': (1, 1)}, homes)
        self.assertEqual((SOUTH['lat'], SOUTH['lng']), (after['locations'][1]['latitude'], after['locations'][1]['longitude']))
        self.assertTrue(all(visit['location'] >= 2 for visit in after['visits']), 'visits follow both depots')
        self.assertEqual(len(after['locations']) * (len(after['locations']) - 1), len(after['roads']['entries']), 'every ordered pair, depot to depot included')
        request = self.read(MOVE, 'api-request.json')
        starts = {day['technicianId']: (day['start'], day['end']) for day in request['snapshot']['technicianDays']}
        self.assertEqual(({'lat': SOUTH['lat'], 'lng': SOUTH['lng']},) * 2, starts['t02'])
        self.assertEqual(({'lat': NORTH['lat'], 'lng': NORTH['lng']},) * 2, starts['t01'])
        entry = next(e for e in self.index['days'] if e['day'] == MOVE.isoformat())
        self.assertEqual({'t01': 'north', 't02': 'south'}, entry['depots'])
        technicians = json.loads((self.out / 'technicians.json').read_text(encoding='utf-8'))
        self.assertEqual('csv-days', technicians['roster'])
        self.assertEqual('south', technicians['depots']['t02'][1]['depot'])

    def test_as_run_holds_nothing_out_ends_shifts_at_seventeen_and_runs_windows_to_nineteen(self):
        self.assertEqual(0, self.index['ledger']['heldOut']['rows'])
        wednesday = MONDAY + dt.timedelta(days=2)
        facts = self.read(wednesday, 'facts.json')
        index = next(i for i, r in enumerate(history(), start=2) if r[0] == '100014')
        evening = next(visit for visit in facts['visits'] if visit['id'] == f'wo-{index}')
        self.assertEqual(bi.local_instant(wednesday, 17 * 60 + 30, ZONE), evening['windowStart'])
        self.assertEqual(bi.local_instant(wednesday, 19 * 60, ZONE), evening['windowEnd'], 'a 17:30 booking keeps its window, capped at 19:00')
        for technician in facts['technicians']:
            self.assertEqual(bi.local_instant(wednesday, 17 * 60, ZONE), technician['shiftEnd'])
            self.assertEqual(540, technician['maxDailyMinutes'])
            self.assertEqual(0, technician['maxOvertimeMinutes'])
        tuesday = MONDAY + dt.timedelta(days=1)
        self.assertEqual([], next(e for e in self.index['days'] if e['day'] == tuesday.isoformat())['extendedTechnicians'], 'no sales-call day extends a shift')

    def test_a_technician_without_a_depot_is_refused(self):
        cfg = two_depots(technicianDepots={'t01': [{'from': None, 'depot': 'north'}]})
        with self.assertRaises(bi.ImportError_):
            bi.build(self.rows, cfg, Path(self.directory.name) / 'refused', bi.StubRoadClient())
        stray = two_depots(technicianDepots=dict(two_depots()['technicianDepots'], t09=[{'from': None, 'depot': 'north'}]))
        with self.assertRaises(bi.ImportError_):
            bi.build(self.rows, stray, Path(self.directory.name) / 'stray', bi.StubRoadClient())

    def test_a_booking_over_the_limit_leaves_the_study(self):
        rows = bi.parse_rows(write_history(self.directory.name))
        rows[0].booked_minutes, rows[1].booked_minutes = 361, 360
        ledger, kept = bi.apply_rules(rows, two_depots())
        self.assertIn((rows[0].index, 'BOOKED_OVER_LIMIT', bi.EXCLUDE), {(row.index, reason, severity) for row, reason, severity in ledger})
        self.assertIn(rows[1].index, {row.index for row in kept}, 'exactly six hours stays')
        _, unlimited = bi.apply_rules(bi.parse_rows(write_history(self.directory.name)), two_depots(maxBookedMinutes=None))
        self.assertEqual(len(kept) + 1, len(unlimited))

    def test_the_csv_roster_holds_exactly_who_worked_each_date(self):
        thursday = MONDAY + dt.timedelta(days=3)
        rows = [row for row in bi.parse_rows(write_history(self.directory.name)) if not (row.day == thursday and row.technician == 'CD2')]
        _, kept = bi.apply_rules(rows, two_depots())
        _, _, by_csv = bi.roster(rows, kept, two_depots())
        _, _, by_span = bi.roster(rows, kept, two_depots(roster='spans'))
        self.assertEqual(['AB1'], by_csv[thursday], 'CD2 has no row that Thursday')
        self.assertEqual(['AB1', 'CD2'], by_span[thursday], 'the span rule would roster CD2 anyway')

    @unittest.skipUnless(JAR.exists(), 'campaign-benchmark jar not built')
    def test_two_depot_days_seal_in_the_engine(self):
        for date in (MONDAY, MOVE):
            source = self.out / 'variants' / 'as-run' / 'days' / date.isoformat() / 'facts.json'
            request, receipt = source.with_suffix('.seal.json'), source.with_suffix('.receipt.json')
            request.write_text(json.dumps({'version': 1, 'kind': 'seal', 'input': str(source), 'output': str(source.with_suffix('.sealed.json'))}), encoding='utf-8')
            result = subprocess.run(['java', '-jar', str(JAR), str(request), str(receipt)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, f'{date}: {result.stderr[-2000:]}')


FRIDAY = MONDAY + dt.timedelta(days=11)  # the last date of the fabricated history
EXTENSION = {'repeatFrom': MOVE.isoformat(), 'repeatTo': FRIDAY.isoformat(), 'through': (FRIDAY + dt.timedelta(days=7)).isoformat(),
             'holidays': [(FRIDAY + dt.timedelta(days=5)).isoformat()]}


class Part2InputsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.out = Path(self.directory.name) / 'run'
        self.rows = bi.parse_rows(write_history(self.directory.name))
        thursday = MONDAY + dt.timedelta(days=3)
        # CD2's only Thursday job loses its booked time: excluded, yet CD2 still worked that date.
        next(row for row in self.rows if row.day == thursday and row.technician == 'CD2').booked = None
        self.config = two_depots(availabilityExtension=EXTENSION)
        self.run = bi.build(self.rows, self.config, self.out, bi.StubRoadClient())
        self.requests = json.loads((self.out / 'part2' / 'requests.json').read_text(encoding='utf-8'))
        self.availability = json.loads((self.out / 'part2' / 'availability.json').read_text(encoding='utf-8'))

    def tearDown(self):
        self.directory.cleanup()

    def as_run_jobs(self):
        index = json.loads((self.out / 'variants' / 'as-run' / 'index.json').read_text(encoding='utf-8'))
        ids = set()
        for entry in index['days']:
            ids |= set(json.loads((self.out / 'variants' / 'as-run' / 'days' / entry['day'] / 'observed.json').read_text(encoding='utf-8'))['visits'])
        return ids

    def test_the_requests_are_the_as_run_jobs_in_request_order(self):
        requests = self.requests['requests']
        self.assertEqual(self.as_run_jobs(), {r['id'] for r in requests}, 'Part 2 is asked for exactly the jobs Part 1 scored')
        self.assertEqual(sorted(requests, key=lambda r: (r['requestDate'], int(r['id'][3:]))), requests)
        sales = [r for r in requests if r['salesCall']]
        self.assertEqual(['X01'], [r['serviceId'] for r in sales])
        evening = next(r for r in requests if r['dispatcher']['bookedStart'] == '17:30')
        self.assertEqual({'serviceDate': (MONDAY + dt.timedelta(days=2)).isoformat(), 'technicianId': 't01', 'bookedStart': '17:30', 'depot': 'north'},
                         evening['dispatcher'])
        moved = next(r for r in requests if r['dispatcher']['technicianId'] == 't02' and r['dispatcher']['serviceDate'] >= MOVE.isoformat())
        self.assertEqual('south', moved['dispatcher']['depot'])
        self.assertEqual(len(requests), self.requests['summary']['requests'])
        receipt = json.loads((self.out / 'part2' / 'inputs.json').read_text(encoding='utf-8'))
        self.assertEqual(bi.sha256(self.out / 'part2' / 'requests.json'), receipt['files']['requests.json'])
        self.assertEqual(receipt, self.run['part2'])

    def test_history_days_are_who_worked_and_the_tail_repeats_working_weekdays(self):
        days = self.availability['technicianDays']
        history = [d for d in days if d['source'] == 'history']
        thursday = (MONDAY + dt.timedelta(days=3)).isoformat()
        cd2 = next(d for d in history if d['date'] == thursday and d['technicianId'] == 't02')
        self.assertEqual(0, cd2['dispatcherJobs'], 'a technician-day with only excluded rows still counts as worked')
        self.assertEqual(1, self.availability['summary']['historyWithoutKeptJobs'])
        saturday = (MONDAY + dt.timedelta(days=5)).isoformat()
        self.assertEqual(['t02'], [d['technicianId'] for d in history if d['date'] == saturday], 'a weekend day is who worked')
        self.assertTrue(all(d['shiftStart'] == '08:00' and d['shiftEnd'] == '17:00' and d['maxPaidMinutes'] == 540 for d in days))
        self.assertTrue(all(d['dispatcherJobs'] is None for d in days if d['source'] == 'repeat'), 'a repeated day has no dispatcher')
        # Working weekdays of MOVE..FRIDAY: Mon, Tue, Thu, Fri (the Wednesday is a holiday with no rows).
        tail = sorted({(d['date'], d['repeatOf']) for d in days if d['source'] == 'repeat'})
        source = [MOVE, MOVE + dt.timedelta(days=1), MOVE + dt.timedelta(days=3), FRIDAY]
        targets = [FRIDAY + dt.timedelta(days=n) for n in (3, 4, 6, 7)]  # Mon, Tue, (Wed holiday), Thu, Fri
        self.assertEqual([(t.isoformat(), s.isoformat()) for t, s in zip(targets, source)], tail)
        repeated = {d['technicianId']: d['depot'] for d in days if d['source'] == 'repeat'}
        self.assertEqual({'t01': 'north', 't02': 'south'}, repeated, 'a repeated day takes the depot of its own date')
        self.assertEqual(FRIDAY.isoformat(), self.availability['lastHistoryDate'])

    def test_no_account_street_or_real_id_reaches_part2(self):
        for path in (self.out / 'part2').iterdir():
            text = path.read_text(encoding='utf-8')
            for secret in ('10000', 'FABRICATED', 'AB1', 'CD2'):
                self.assertNotIn(secret, text, f'{secret} in {path.name}')

    def test_inputs_alone_match_the_build_and_leave_the_days_untouched(self):
        day = self.out / 'variants' / 'as-run' / 'days' / MONDAY.isoformat() / 'facts.json'
        before = (day.stat().st_mtime_ns, bi.sha256(day))
        first = bi.sha256(self.out / 'part2' / 'requests.json'), bi.sha256(self.out / 'part2' / 'availability.json')
        receipt = bi.inputs(self.rows, self.config, self.out, bi.StubRoadClient())
        self.assertEqual(first, (receipt['files']['requests.json'], receipt['files']['availability.json']))
        self.assertEqual(before, (day.stat().st_mtime_ns, bi.sha256(day)))

    def test_inputs_refuse_a_run_whose_jobs_differ(self):
        rows = bi.parse_rows(write_history(self.directory.name))
        rows[0].booked_minutes = 400  # now over the limit, so the requests lose a job the as-run days still hold
        with self.assertRaises(bi.ImportError_):
            bi.inputs(rows, self.config, self.out, bi.StubRoadClient())

    def test_a_request_date_missing_or_after_service_is_excluded(self):
        rows = bi.parse_rows(write_history(self.directory.name))
        rows[0].requested = None
        rows[1].requested = rows[1].day + dt.timedelta(days=1)
        ledger, kept = bi.apply_rules(rows, self.config)
        reasons = {(row.index, reason) for row, reason, severity in ledger if severity == bi.EXCLUDE}
        self.assertIn((rows[0].index, 'NO_REQUEST_DATE'), reasons)
        self.assertIn((rows[1].index, 'REQUESTED_AFTER_SERVICE'), reasons)
        self.assertFalse({rows[0].index, rows[1].index} & {row.index for row in kept})

    def test_the_extension_is_checked(self):
        bad = [dict(EXTENSION, through=EXTENSION['repeatTo']), dict(EXTENSION, repeatFrom='soon'), dict(EXTENSION, holidays='none'),
               {k: v for k, v in EXTENSION.items() if k != 'holidays'}, dict(EXTENSION, extra=1)]
        for extension in bad:
            with self.assertRaises(bi.ImportError_, msg=str(extension)):
                two_depots(availabilityExtension=extension)
        with self.assertRaises(bi.ImportError_, msg='a source past the data'):
            late = dict(EXTENSION, repeatTo=(FRIDAY + dt.timedelta(days=3)).isoformat(), through=(FRIDAY + dt.timedelta(days=9)).isoformat())
            bi.build(self.rows, two_depots(availabilityExtension=late), Path(self.directory.name) / 'late', bi.StubRoadClient())
        plain = bi.part2_inputs(self.rows, bi.prepare(self.rows, two_depots(), bi.StubRoadClient())[2], two_depots(),
                                {'AB1': 't01', 'CD2': 't02'})[1]
        self.assertEqual(0, plain['summary']['repeat'], 'no extension, no repeated days')


if __name__ == '__main__':
    unittest.main()
