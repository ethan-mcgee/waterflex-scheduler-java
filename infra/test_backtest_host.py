"""Regression coverage for infra/backtest_host.py. The horizon and overnight cases are the TypeScript tests' own
(web/lib/bookingHorizonCore.test.ts and web/lib/overnightCore.test.ts), in the same zone, so the port cannot drift."""
import copy
import datetime as dt
import unittest
from zoneinfo import ZoneInfo

import backtest_host as bh

CHICAGO = ZoneInfo('America/Chicago')
LA = ZoneInfo('America/Los_Angeles')
FRIDAY = '2026-10-09'
RULE = {'capWeekdays': 15, 'leadWeekdays': 2, 'fullPercent': 85}
POINT = {'lat': 41.25, 'lng': -96}


def utc(text):
    return dt.datetime.fromisoformat(text)


def at(date, hour, minute=0):
    """Chicago in October is UTC-5."""
    return f'{date}T{hour + 5:02d}:{minute:02d}:00.000Z'


def snapshot(days):
    """Each day's visits start at 09:00 local, two hours apart, 45 minutes each: four span 86 percent, three 64 percent."""
    technician_days, appointments = [], []
    for day in days:
        technician = day.get('technicianId', 'tech-a')
        technician_days.append({'technicianId': technician, 'serviceDate': day['date'], 'lastModified': '2026-10-01T00:00:00.000Z',
                                'shift': {'start': at(day['date'], 8), 'end': at(day['date'], 17)},
                                'absences': [{'start': at(day['date'], a), 'end': at(day['date'], b)} for a, b in day.get('absences', [])],
                                'start': POINT, 'end': POINT, 'maxPaidMinutes': 540})
        for n in range(day['visits']):
            appointments.append({'id': f'{technician}-{day["date"]}-{n}', 'technicianId': technician, 'serviceDate': day['date'], 'serviceId': 'svc',
                                 'durationMinutes': 45, 'window': {'start': at(day['date'], 8), 'end': at(day['date'], 17)}, 'location': POINT,
                                 'sequence': n, 'plannedStart': at(day['date'], 9 + 2 * n)})
    return {'metroId': 'metro', 'timeZone': 'America/Chicago', 'rates': {}, 'policy': {}, 'technicians': [], 'technicianDays': technician_days,
            'appointments': appointments}


def weekdays_of(dates):
    return [d for d in dates if bh.is_weekday(d)]


def working(visits, overrides=None):
    overrides = overrides or {}
    return [overrides.get(date, {'date': date, 'visits': visits(date)}) for date in weekdays_of(bh.scan_window(FRIDAY, RULE))]


class HorizonTest(unittest.TestCase):
    def test_a_route_span_is_shift_start_to_the_end_of_the_last_planned_visit(self):
        self.assertEqual((465, 540), bh.day_fullness(snapshot([{'date': '2026-10-12', 'visits': 4}]), '2026-10-12'))
        self.assertEqual((0, 540), bh.day_fullness(snapshot([{'date': '2026-10-12', 'visits': 0}]), '2026-10-12'), 'an empty working day is measured')
        self.assertIsNone(bh.day_fullness(snapshot([]), '2026-10-12'), 'nobody working is no capacity, never a zero share')

    def test_absences_leave_the_open_minutes_and_the_part_of_the_span_they_cover(self):
        self.assertEqual((225, 300), bh.day_fullness(snapshot([{'date': '2026-10-12', 'visits': 2, 'absences': [(13, 17)]}]), '2026-10-12'))
        self.assertEqual((345, 420), bh.day_fullness(snapshot([{'date': '2026-10-12', 'visits': 4, 'absences': [(10, 12), (11, 12)]}]), '2026-10-12'),
                         'overlapping absences count once')
        self.assertIsNone(bh.day_fullness(snapshot([{'date': '2026-10-12', 'visits': 0, 'absences': [(8, 17)]}]), '2026-10-12'))

    def test_a_low_volume_schedule_books_only_the_lead_weekdays(self):
        decision = bh.dynamic_horizon(FRIDAY, snapshot(working(lambda d: 1)), RULE)
        self.assertEqual({'dates': bh.booking_horizon(FRIDAY, 2), 'lastFullDate': None, 'capped': False}, decision)
        self.assertEqual(['2026-10-10', '2026-10-11', '2026-10-12', '2026-10-13'], decision['dates'])

    def test_the_horizon_ends_two_weekdays_past_the_furthest_full_weekday(self):
        full = {'2026-10-12', '2026-10-14'}
        decision = bh.dynamic_horizon(FRIDAY, snapshot(working(lambda d: 4 if d in full else 3)), RULE)
        self.assertEqual('2026-10-14', decision['lastFullDate'])
        self.assertEqual('2026-10-16', decision['dates'][-1])
        self.assertFalse(decision['capped'])

    def test_a_weekday_nobody_works_does_not_use_up_the_lead(self):
        holiday = {'date': '2026-10-15', 'visits': 0, 'absences': [(8, 17)]}
        decision = bh.dynamic_horizon(FRIDAY, snapshot(working(lambda d: 4 if d <= '2026-10-14' else 0, {'2026-10-15': holiday})), RULE)
        self.assertEqual('2026-10-14', decision['lastFullDate'])
        self.assertEqual('2026-10-19', decision['dates'][-1])

    def test_a_dealership_booked_out_past_ten_weekdays_books_further_never_past_the_cap(self):
        twelve = set(weekdays_of(bh.scan_window(FRIDAY, RULE))[:12])
        busy = bh.dynamic_horizon(FRIDAY, snapshot(working(lambda d: 4 if d in twelve else 1)), RULE)
        self.assertEqual(14, len(weekdays_of(busy['dates'])))
        self.assertGreater(len(busy['dates']), len(bh.booking_horizon(FRIDAY, 10)))
        solid = bh.dynamic_horizon(FRIDAY, snapshot(working(lambda d: 4)), RULE)
        self.assertEqual({'dates': bh.scan_window(FRIDAY, RULE), 'lastFullDate': '2026-10-30', 'capped': True}, solid)
        self.assertLessEqual(len(solid['dates']), 21)

    def test_full_is_measured_across_every_technician_working_that_weekday(self):
        days = [d for day in working(lambda d: 0) for d in ({**day, 'visits': 4}, {**day, 'visits': 3, 'technicianId': 'tech-b'})]
        self.assertIsNone(bh.dynamic_horizon(FRIDAY, snapshot(days), RULE)['lastFullDate'])
        self.assertTrue(bh.dynamic_horizon(FRIDAY, snapshot(days), dict(RULE, fullPercent=75))['capped'])

    def test_a_scan_outside_the_window_and_an_invalid_rule_are_refused(self):
        with self.assertRaisesRegex(bh.HostError, 'outside the window'):
            bh.dynamic_horizon(FRIDAY, snapshot([{'date': '2026-11-30', 'visits': 0}]), RULE)
        for rule in (dict(RULE, capWeekdays=16), dict(RULE, leadWeekdays=0), dict(RULE, fullPercent=49), dict(RULE, fullPercent=85.5)):
            with self.assertRaises(bh.HostError, msg=str(rule)):
                bh.scan_window(FRIDAY, rule)

    def test_the_last_booked_date_is_the_latest_appointments(self):
        self.assertEqual('2026-10-20', bh.last_booked_date(snapshot([{'date': '2026-10-12', 'visits': 1}, {'date': '2026-10-20', 'visits': 2},
                                                                      {'date': '2026-10-21', 'visits': 0}])))
        self.assertIsNone(bh.last_booked_date(snapshot([{'date': '2026-10-12', 'visits': 0}])))


class OvernightTest(unittest.TestCase):
    def test_a_run_proposes_from_today_until_ten_weekdays_frozen_days_left_out(self):
        night = bh.overnight_dates(utc('2026-10-14T07:00:00+00:00'), CHICAGO)
        self.assertEqual('2026-10-14', night[0])
        self.assertEqual('2026-10-27', night[-1])
        self.assertEqual(14, len(night))
        self.assertTrue('2026-10-17' in night and '2026-10-18' in night)
        morning = bh.overnight_dates(utc('2026-10-14T11:00:00+00:00'), CHICAGO)
        self.assertEqual(('2026-10-15', '2026-10-28'), (morning[0], morning[-1]))
        saturday = bh.overnight_dates(utc('2026-10-17T07:00:00+00:00'), CHICAGO)
        self.assertEqual(['2026-10-17', '2026-10-18', '2026-10-19'], saturday[:3])
        self.assertEqual('2026-10-30', saturday[-1])

    def test_a_dynamic_horizon_run_proposes_through_the_horizon_or_the_last_booked_day(self):
        self.assertEqual(['2026-10-14', '2026-10-15', '2026-10-16', '2026-10-17', '2026-10-18', '2026-10-19'],
                         bh.overnight_dates(utc('2026-10-14T07:00:00+00:00'), CHICAGO, '2026-10-19'))
        self.assertEqual(['2026-10-15'], bh.overnight_dates(utc('2026-10-14T11:00:00+00:00'), CHICAGO, '2026-10-15'))
        self.assertEqual([], bh.overnight_dates(utc('2026-10-14T11:00:00+00:00'), CHICAGO, '2026-10-14'))
        self.assertEqual('2026-10-19', bh.overnight_end('2026-10-19', None))
        self.assertEqual('2026-10-19', bh.overnight_end('2026-10-19', '2026-10-16'))
        self.assertEqual('2026-10-23', bh.overnight_end('2026-10-19', '2026-10-23'))

    def test_a_busy_day_waits_as_asked_then_backs_off_and_gives_up(self):
        self.assertEqual(42_000, bh.busy_retry_delay_ms(1, 42_000))
        self.assertEqual(bh.MAX_BUSY_WAIT_MS, bh.busy_retry_delay_ms(1, 900_000))
        self.assertEqual(1000, bh.busy_retry_delay_ms(1, 10))
        self.assertEqual([5000, 10_000, 20_000], [bh.busy_retry_delay_ms(a, None) for a in (1, 2, 3)])
        self.assertIsNotNone(bh.busy_retry_delay_ms(bh.BUSY_RETRIES, 1000))
        self.assertIsNone(bh.busy_retry_delay_ms(bh.BUSY_RETRIES + 1, 1000))
        for attempt in (0, 1.5, True):
            with self.assertRaises(bh.HostError):
                bh.busy_retry_delay_ms(attempt, None)


# A book in Paso Robles time: two technicians on a PDT Tuesday, whose API date 15 weeks later is in PST.
STUDY_DATE = '2024-10-29'
SHIFT = 105  # 15 weeks: 2025-02-11, also a Tuesday, in PST
SETTINGS = {'metroId': 'metro-paso-robles', 'rates': {'regularHourly': '30'}, 'policy': {'dailySearch': 'TABU_KOPT_SIZE_3'}, 'services': ['R11', 'R15']}


def availability(dates=(STUDY_DATE,)):
    days = [{'date': d, 'technicianId': t, 'depot': 'paso-robles', 'location': {'lat': 35.63, 'lng': -120.69}, 'shiftStart': '08:00', 'shiftEnd': '17:00',
             'maxPaidMinutes': 540, 'source': 'history', 'repeatOf': None, 'dispatcherJobs': 1} for d in dates for t in ('t01', 't02')]
    return {'timeZone': 'America/Los_Angeles', 'technicianDays': days}


def job(identifier='wo-7', service='R15'):
    return {'id': identifier, 'serviceId': service, 'durationMinutes': 60, 'location': {'lat': 35.6, 'lng': -120.7}}


def api_at(minute, date=None):
    return bh.local_instant(date or bh.add_days(STUDY_DATE, SHIFT), minute, LA)


def booking(book, identifier='wo-7', technician='t01', start=9 * 60, others=()):
    """A confirm receipt as the API sends it for the API date, listing every technician-day of the date at its version."""
    api_date = bh.add_days(STUDY_DATE, SHIFT)
    offer = {'serviceDate': api_date, 'window': {'start': api_at(start), 'end': api_at(start + 120)}}
    assignments = [{'appointmentId': identifier, 'technicianId': technician, 'serviceDate': api_date, 'sequence': 0,
                    'plannedStart': api_at(start), 'plannedEnd': api_at(start + 60)}]
    assignments += [dict(item, serviceDate=api_date) for item in others]
    return {'receiptId': 'r1', 'assignments': assignments, 'technicianDays': book.versions([STUDY_DATE], SHIFT)}, offer


class BookTest(unittest.TestCase):
    def setUp(self):
        self.book = bh.Book.from_availability(availability())

    def test_a_snapshot_moves_dates_by_whole_weeks_and_keeps_local_times_across_daylight_saving(self):
        snap = self.book.snapshot([STUDY_DATE], SETTINGS, SHIFT)
        day = snap['technicianDays'][0]
        self.assertEqual('2025-02-11', day['serviceDate'])
        self.assertEqual('2025-02-11T16:00:00Z', day['shift']['start'], '08:00 PST')
        self.assertEqual('2024-10-29T15:00:00Z', self.book.snapshot([STUDY_DATE], SETTINGS)['technicianDays'][0]['shift']['start'], '08:00 PDT')
        self.assertEqual(['R11', 'R15'], snap['technicians'][0]['qualifications'], 'everyone does everything')
        self.assertEqual('2026-01-01T00:00:00Z', day['lastModified'])
        self.assertEqual({'lat': 35.63, 'lng': -120.69}, day['start'])

    def test_a_booking_receipt_is_written_with_a_compare_and_set_and_moves_the_versions(self):
        receipt, offer = booking(self.book)
        created = self.book.apply_booking(receipt, job(), offer, SHIFT)
        self.assertEqual(('t01', STUDY_DATE, 0, 540, 600, 540, 660), (created.technician, created.date, created.sequence, created.planned_start,
                                                                     created.planned_end, created.window_start, created.window_end))
        self.assertEqual({1}, {d.version for d in self.book.days.values()})
        snap = self.book.snapshot([STUDY_DATE], SETTINGS, SHIFT)
        self.assertEqual('2026-01-01T00:00:00.000001Z', snap['technicianDays'][0]['lastModified'])
        self.assertEqual([api_at(540)], [a['plannedStart'] for a in snap['appointments']])
        # The same receipt again is stale: its versions are the ones the book has already moved past.
        with self.assertRaises(bh.StaleReceipt):
            self.book.apply_booking(receipt, job('wo-8'), offer, SHIFT)
        self.assertNotIn('wo-8', self.book.appointments)

    def test_a_receipt_that_breaks_the_contract_writes_nothing(self):
        before = copy.deepcopy(self.book.to_json())
        receipt, offer = booking(self.book)
        cases = {'another date': dict(receipt, technicianDays=[dict(receipt['technicianDays'][0], serviceDate='2025-02-12')] + receipt['technicianDays'][1:]),
                 'listed twice': dict(receipt, technicianDays=receipt['technicianDays'] + receipt['technicianDays'][:1]),
                 'not the job': dict(receipt, assignments=[dict(receipt['assignments'][0], appointmentId='wo-9')]),
                 'ends first': dict(receipt, assignments=[dict(receipt['assignments'][0], plannedEnd=api_at(500))]),
                 'unlisted day': dict(receipt, technicianDays=receipt['technicianDays'][1:])}
        for name, bad in cases.items():
            with self.assertRaises(bh.HostError, msg=name):
                self.book.apply_booking(bad, job(), offer, SHIFT)
            self.assertEqual(before, self.book.to_json(), name)

    def test_a_commit_moves_appointments_between_technicians_and_must_place_every_one(self):
        receipt, offer = booking(self.book)
        self.book.apply_booking(receipt, job(), offer, SHIFT)
        receipt, offer = booking(self.book, 'wo-8', 't01', 11 * 60, others=[
            {'appointmentId': 'wo-7', 'technicianId': 't01', 'sequence': 1, 'plannedStart': api_at(540), 'plannedEnd': api_at(600)}])
        # wo-7 must stay placed when its technician-day is listed, so a confirm that drops it is refused.
        receipt['assignments'][0]['sequence'] = 1
        receipt['assignments'][1]['sequence'] = 0
        self.book.apply_booking(receipt, job('wo-8'), offer, SHIFT)
        api_date = bh.add_days(STUDY_DATE, SHIFT)
        moved = {'receiptId': 'c1', 'technicianDays': self.book.versions([STUDY_DATE], SHIFT), 'assignments': [
            {'appointmentId': 'wo-7', 'technicianId': 't02', 'serviceDate': api_date, 'sequence': 0, 'plannedStart': api_at(560), 'plannedEnd': api_at(620)},
            {'appointmentId': 'wo-8', 'technicianId': 't01', 'serviceDate': api_date, 'sequence': 0, 'plannedStart': api_at(660), 'plannedEnd': api_at(720)}]}
        dropped = dict(moved, assignments=moved['assignments'][:1])
        with self.assertRaisesRegex(bh.HostError, 'does not place appointment wo-8'):
            self.book.apply_commit(dropped, SHIFT)
        self.book.apply_commit(moved, SHIFT)
        self.assertEqual(('t02', 0, 560), (self.book.appointments['wo-7'].technician, self.book.appointments['wo-7'].sequence,
                                           self.book.appointments['wo-7'].planned_start))
        self.assertEqual(3, self.book.version)
        with self.assertRaises(bh.StaleReceipt):
            self.book.apply_commit(moved, SHIFT)

    def test_a_checkpoint_restores_the_same_book_and_the_same_snapshot(self):
        receipt, offer = booking(self.book)
        self.book.apply_booking(receipt, job(), offer, SHIFT)
        restored = bh.Book.from_json(self.book.to_json())
        self.assertEqual(self.book.snapshot([STUDY_DATE], SETTINGS, SHIFT), restored.snapshot([STUDY_DATE], SETTINGS, SHIFT))
        self.assertEqual(self.book.version, restored.version)

    def test_an_instant_off_its_date_or_off_the_minute_is_refused(self):
        with self.assertRaises(bh.HostError):
            bh.local_minute('2025-02-12T16:00:00Z', '2025-02-11', LA)
        with self.assertRaises(bh.HostError):
            bh.local_minute('2025-02-11T16:00:30Z', '2025-02-11', LA)
        with self.assertRaises(bh.HostError):
            bh.parse_instant('2025-02-11T16:00:00')
        self.assertEqual(1440, bh.local_minute('2025-02-12T08:00:00Z', '2025-02-11', LA), 'the next midnight closes the date')

    def test_a_roster_without_weekends_leaves_out_saturday_and_sunday_technician_days(self):
        dates = [bh.add_days('2024-10-28', n) for n in range(0, 14)]
        whole = bh.Book.from_availability(availability(dates))
        self.assertEqual(dates, whole.dates(), 'by default the roster is the history, weekends included')
        weekdays = bh.Book.from_availability(availability(dates), weekends=False)
        self.assertEqual([d for d in dates if bh.is_weekday(d)], weekdays.dates())
        self.assertEqual(20, len(weekdays.days))
        self.assertEqual([], weekdays.on('2024-11-02'), 'nobody is on the roster that Saturday')
        horizon = bh.booking_dates(weekdays, '2024-11-01')
        self.assertEqual(['2024-11-04', '2024-11-05'], [d for d in horizon['dates'] if weekdays.on(d)], 'a Friday request is offered Monday and Tuesday')

    def test_the_book_horizon_and_overnight_plan_follow_the_portal_rules(self):
        dates = [bh.add_days('2024-10-28', n) for n in range(0, 28)]
        book = bh.Book.from_availability(availability([d for d in dates if bh.is_weekday(d)]))
        horizon = bh.booking_dates(book, '2024-10-25')
        self.assertEqual(['2024-10-26', '2024-10-27', '2024-10-28', '2024-10-29'], horizon['dates'], 'an empty book books the two lead weekdays')
        # A booking far out keeps its date in the overnight run even past the horizon's end.
        far = '2024-11-06'
        book.appointments['wo-1'] = bh.Appointment('wo-1', 'R15', 60, 35.6, -120.7, far, 't01', 0, 540, 660, 540, 600)
        night = bh.overnight_plan(book, utc('2024-10-29T08:00:00+00:00'))  # 01:00 PDT on Tuesday 2024-10-29
        self.assertEqual('2024-10-29', night[0], 'today is not frozen at 01:00')
        self.assertEqual(far, night[-1])


class DeskTest(unittest.TestCase):
    def test_callbacks_come_first_and_new_requests_follow_in_a_seeded_order(self):
        desk = bh.Desk(seed=17)
        first = desk.due('2024-03-04', ['wo-3', 'wo-1', 'wo-2'])
        self.assertEqual(sorted(first), ['wo-1', 'wo-2', 'wo-3'])
        self.assertEqual(first, bh.Desk(seed=17).due('2024-03-04', ['wo-2', 'wo-3', 'wo-1']), 'the order depends on the seed and date only')
        self.assertEqual('NO_OFFER', desk.no_offer('wo-2', '2024-03-04'))
        desk.booked('wo-1')
        desk.booked('wo-3')
        self.assertEqual(['wo-2', 'wo-9'], desk.due('2024-03-05', ['wo-9']))

    def test_a_carried_backlog_comes_before_new_requests_oldest_first(self):
        desk = bh.Desk(seed=3)
        due = desk.due('2024-01-01', ['wo-5', 'wo-6'], carried=[('wo-2', '2023-12-20'), ('wo-1', '2023-12-28')])
        self.assertEqual(['wo-2', 'wo-1'], due[:2])
        self.assertEqual('2023-12-20', desk.waiting['wo-2']['first'], 'a carried request keeps the date it first called')

    def test_no_offer_calls_back_each_following_weekday_then_turns_away(self):
        desk = bh.Desk(seed=1)
        desk.due('2024-03-08', ['wo-1'])  # a Friday
        self.assertEqual('NO_OFFER', desk.no_offer('wo-1', '2024-03-08'))
        self.assertEqual('2024-03-11', desk.waiting['wo-1']['next'], 'Friday calls back on Monday')
        date = '2024-03-11'
        for _ in range(bh.RETRY_WEEKDAYS - 1):
            self.assertEqual('NO_OFFER', desk.no_offer('wo-1', date))
            date = desk.waiting['wo-1']['next']
        self.assertEqual('TURNED_AWAY', desk.no_offer('wo-1', date), 'the tenth callback without an offer is the last')
        self.assertEqual('TURNED_AWAY', desk.outcomes['wo-1'])
        self.assertEqual([], desk.due(bh.add_days(date, 1), ['wo-1']), 'a turned-away request does not come back')

    def test_the_end_of_the_data_closes_every_waiting_request(self):
        desk = bh.Desk(seed=1)
        desk.due('2024-03-04', ['wo-1', 'wo-2'])
        desk.booked('wo-1')
        self.assertEqual(['wo-2'], desk.end_of_data())
        self.assertEqual({'wo-1': 'BOOKED', 'wo-2': 'END_OF_DATA'}, bh.Desk.from_json(desk.to_json()).outcomes)

    def test_request_ids_are_stable_and_differ_by_step_and_attempt(self):
        a = bh.request_id('run', 's1', '2024-03-04', 'offers:wo-1')
        self.assertEqual(a, bh.request_id('run', 's1', '2024-03-04', 'offers:wo-1'))
        self.assertNotEqual(a, bh.request_id('run', 's1', '2024-03-04', 'offers:wo-1', 2))
        self.assertNotEqual(a, bh.request_id('run', 's2', '2024-03-04', 'offers:wo-1'))


if __name__ == '__main__':
    unittest.main()
