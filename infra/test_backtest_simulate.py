"""Regression coverage for infra/backtest_simulate.py with a fake scheduler: a three-day run, and a run killed in the
middle of a date that resumes from its checkpoint to the same end state. No real row appears here."""
import datetime as dt
import json
import tempfile
import threading
import unittest
from pathlib import Path
from zoneinfo import ZoneInfo

import backtest_api as ba
import backtest_host as bh
import backtest_import as bi
import backtest_simulate as bs

LA = ZoneInfo('America/Los_Angeles')
MONDAY = '2024-03-04'
POLICY = {'bookingDeadlineMs': 5000, 'costSearchSeconds': 10, 'dailySearch': 'TABU_KOPT_SIZE_3', 'fairnessBudget': '0.02', 'fairnessSearchSeconds': 5,
          'regularWindowThreshold': 2}


class Killed(Exception):
    pass


class FakeScheduler:
    """Answers like the API for the simulator's calls, storing every answer by requestId: a replay with the same body
    gets the same answer, one with another body is refused, as the API's claim does. Up to three jobs a technician-day."""

    def __init__(self, store=None, kill_after=None):
        self.store = {} if store is None else store
        self.kill_after, self.calls = kill_after, 0
        self.lock = threading.Lock()

    def answer(self, request_id, body, make):
        with self.lock:
            self.calls += 1
            if self.kill_after is not None and self.calls > self.kill_after:
                raise Killed(f'killed at call {self.calls}')
            known = self.store.get(request_id)
            if known is not None:
                if known[0] != json.dumps(body, sort_keys=True):
                    raise ba.ApiError(400, 'INVALID_REQUEST', f'{request_id} sent again with another body')
                return known[1]
            result = make()
            self.store[request_id] = (json.dumps(body, sort_keys=True), result)
            return result

    @staticmethod
    def minute(instant, date):
        return bh.local_minute(instant, date, LA)

    def offers(self, body):
        def make():
            snapshot = body['snapshot']
            for day in sorted(snapshot['technicianDays'], key=lambda d: (d['serviceDate'], d['technicianId'])):
                if not body['horizon']['firstDate'] <= day['serviceDate'] <= body['horizon']['lastDate']:
                    continue
                count = sum(1 for a in snapshot['appointments'] if a['technicianId'] == day['technicianId'] and a['serviceDate'] == day['serviceDate'])
                if count < 3:
                    start = 8 * 60 + 120 * count
                    window = {'start': bh.local_instant(day['serviceDate'], start, LA), 'end': bh.local_instant(day['serviceDate'], start + 120, LA)}
                    offer = {'offerId': f'of-{body["requestId"]}', 'serviceDate': day['serviceDate'], 'window': window, 'technicianId': day['technicianId']}
                    self.store['offer:' + offer['offerId']] = (None, offer)
                    self.store['job:h-' + offer['offerId']] = (None, body['job'])
                    return {'offerSetId': 'os-' + body['requestId'], 'expiresAt': '2030-01-01T00:00:00Z', 'searchComplete': True, 'refinement': 'COMPLETE',
                            'offers': [{k: offer[k] for k in ('offerId', 'serviceDate', 'window')}], 'skippedTechnicianDays': []}
            return {'offerSetId': 'os-' + body['requestId'], 'expiresAt': '2030-01-01T00:00:00Z', 'searchComplete': True, 'offers': [], 'skippedTechnicianDays': []}
        return self.answer(body['requestId'], body, make), 1

    def select(self, offer_id, request_id):
        return self.answer(request_id, {'offerId': offer_id}, lambda: {'holdId': 'h-' + offer_id, 'offerId': offer_id, 'expiresAt': '2030-01-01T00:00:00Z'}), 1

    def confirm(self, hold_id, request_id, snapshot):
        def make():
            offer, job = self.store['offer:' + hold_id[2:]][1], self.store['job:' + hold_id][1]
            date, technician = offer['serviceDate'], offer['technicianId']
            assignments = [{'appointmentId': a['id'], 'technicianId': a['technicianId'], 'serviceDate': date, 'sequence': a['sequence'],
                            'plannedStart': a['plannedStart'], 'plannedEnd': bh.local_instant(date, self.minute(a['plannedStart'], date) + a['durationMinutes'], LA)}
                           for a in snapshot['appointments'] if a['serviceDate'] == date]
            start = self.minute(offer['window']['start'], date)
            assignments.append({'appointmentId': job['id'], 'technicianId': technician, 'serviceDate': date,
                                'sequence': sum(1 for a in assignments if a['technicianId'] == technician),
                                'plannedStart': bh.local_instant(date, start, LA), 'plannedEnd': bh.local_instant(date, start + job['durationMinutes'], LA)})
            return {'receiptId': 'r-' + request_id, 'assignments': assignments,
                    'technicianDays': [{k: d[k] for k in ('technicianId', 'serviceDate', 'lastModified')} for d in snapshot['technicianDays'] if d['serviceDate'] == date]}
        return self.answer(request_id, {'holdId': hold_id, 'snapshot': snapshot}, make), 1

    def propose(self, body, limit_ms):
        def make():
            snapshot, date = body['snapshot'], body['serviceDate']
            routes, improved = [], False
            for day in snapshot['technicianDays']:
                stops = sorted((a for a in snapshot['appointments'] if a['technicianId'] == day['technicianId']), key=lambda a: a['sequence'])
                order = list(range(len(stops)))
                if len(stops) >= 2:
                    order[0], order[1] = 1, 0  # swap the first two stops, keeping their times
                    improved = True
                times = [(a['plannedStart'], bh.local_instant(date, self.minute(a['plannedStart'], date) + a['durationMinutes'], LA)) for a in stops]
                routes.append({'technicianId': day['technicianId'], 'serviceDate': date, 'stops': [
                    {'appointmentId': stops[order[k]]['id'], 'sequence': k, 'plannedStart': times[k][0], 'plannedEnd': times[k][1]} for k in range(len(stops))]})
            return {'proposalId': 'p-' + body['requestId'], 'inputRevision': '0' * 64, 'decision': 'IMPROVED' if improved else 'NO_IMPROVEMENT', 'reason': 'COST',
                    'routes': routes, 'unresolvedAppointmentIds': [], 'skippedTechnicianDays': [], 'costCents': 100, 'overtimeMinutes': 0,
                    'versions': [{k: d[k] for k in ('technicianId', 'serviceDate', 'lastModified')} for d in snapshot['technicianDays']]}
        return self.answer(body['requestId'], body, make), {'attempts': 1, 'polls': 0, 'elapsedMs': 0}

    def commit(self, proposal_id, request_id, technician_days):
        def make():
            proposal = next(answer for _, answer in self.store.values() if isinstance(answer, dict) and answer.get('proposalId') == proposal_id)
            if technician_days != proposal['versions']:
                raise ba.ApiError(409, 'STALE', 'moved', changed=technician_days)
            return {'receiptId': 'c-' + request_id, 'technicianDays': technician_days, 'assignments': [
                dict(stop, technicianId=route['technicianId'], serviceDate=route['serviceDate']) for route in proposal['routes'] for stop in route['stops']]}
        return self.answer(request_id, {'proposalId': proposal_id, 'days': technician_days}, make), 1


def write_run(directory, per_day=5, days=3):
    """A run directory with Part 2 inputs only: two technicians on two weeks of weekdays, per_day requests a day."""
    run = Path(directory) / 'run'
    dates = [bh.add_days(MONDAY, n) for n in range(14)]
    availability = {'version': 1, 'timeZone': 'America/Los_Angeles', 'lastHistoryDate': dates[-1], 'technicianDays': [
        {'date': d, 'technicianId': t, 'depot': 'north', 'location': {'lat': 35.63, 'lng': -120.69}, 'shiftStart': '08:00', 'shiftEnd': '17:00',
         'maxPaidMinutes': 540, 'source': 'history', 'repeatOf': None, 'dispatcherJobs': 1} for d in dates if bh.is_weekday(d) for t in ('t01', 't02')]}
    requests, n = [], 2
    for day in range(days):
        for _ in range(per_day):
            requests.append({'id': f'wo-{n}', 'requestDate': bh.add_days(MONDAY, day), 'serviceId': 'R15', 'tags': ['R15'], 'salesCall': False,
                             'durationMinutes': 60, 'location': {'lat': 35.6, 'lng': -120.7}, 'flags': [],
                             'dispatcher': {'serviceDate': bh.add_days(MONDAY, day + 1), 'technicianId': 't01', 'bookedStart': '09:00', 'depot': 'north'}})
            n += 1
    # One backlog request made the Friday before, served in the study: it calls on the first simulated date.
    requests.insert(0, {'id': 'wo-1', 'requestDate': '2024-03-01', 'serviceId': 'R11', 'tags': ['R11'], 'salesCall': False, 'durationMinutes': 60,
                        'location': {'lat': 35.6, 'lng': -120.7}, 'flags': [], 'dispatcher': {'serviceDate': MONDAY, 'technicianId': 't02', 'bookedStart': '10:00',
                                                                                              'depot': 'north'}})
    bi.write_json(run / 'part2' / 'requests.json', {'version': 1, 'timeZone': 'America/Los_Angeles', 'requests': requests})
    bi.write_json(run / 'part2' / 'availability.json', availability)
    bi.write_json(run / 'part2' / 'inputs.json', {'routingIdentity': 'stub', 'files': {name: bi.sha256(run / 'part2' / name) for name in ('requests.json', 'availability.json')}})
    bi.write_json(run / 'study-config.json', {'metroId': 'metro-test', 'timeZone': 'America/Los_Angeles', 'policy': POLICY,
                                              'rates': {'regularHourly': '30', 'overtimeHourly': '45', 'mileagePerMile': '0.67', 'travelBufferPct': '0.1',
                                                        'travelBufferMinutes': 2}})
    return run


def stable(path):
    """A log's lines without the wall-clock fields, which differ between runs."""
    return [{k: v for k, v in json.loads(line).items() if k not in ('elapsedMs', 'calls')} for line in Path(path).read_text(encoding='utf-8').splitlines()]


class SimulateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.run = write_run(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def planned(self, name):
        return bs.plan(self.run, Path(self.directory.name) / name, 1, 3, today='2026-11-05', until=bh.add_days(MONDAY, 2))

    def test_the_plan_splits_whole_weeks_and_shifts_each_segment_past_today(self):
        self.assertEqual([('2024-03-04', '2024-03-11'), ('2024-03-11', '2024-03-18')], bs.segment_bounds('2024-03-05', '2024-03-15', 2))
        with self.assertRaises(bs.SimulationError):
            bs.segment_bounds('2024-03-05', '2024-03-15', 3)
        shift = bs.shift_for('2024-03-04', '2026-11-05')
        self.assertEqual(0, shift % 7)
        self.assertGreaterEqual(bh.add_days('2024-03-04', shift), '2026-11-12')
        self.assertLess(bh.add_days('2024-03-04', shift), '2026-11-19')
        document = self.planned('plan')
        segment = document['segments'][0]
        self.assertEqual((MONDAY, bh.add_days(MONDAY, 2), 'SIMULATION_TOKEN_S1'), (segment['start'], segment['stop'], segment['tokenEnv']))
        self.assertEqual(['R11', 'R15'], document['settings']['services'])
        self.assertEqual(POLICY, document['settings']['policy'])

    def test_a_window_plans_named_copies_with_their_own_warm_up_and_tenant(self):
        document = bs.window(self.run, Path(self.directory.name) / 'cal-b', '2024-03-11', '2024-03-18', [('b1', 1), ('c1', 2)], '2026-11-05')
        b1, c1 = document['segments']
        self.assertEqual(('2024-03-04', '2024-02-26'), (b1['start'], c1['start']))
        self.assertEqual(('bt-cal-b-b1', 'bt-cal-b-c1'), (b1['tenantId'], c1['tenantId']))
        self.assertEqual(('window', '2024-03-17'), (document['kind'], b1['stop']))
        with self.assertRaises(bs.SimulationError):
            bs.tenant_of('x' * 70, 's1')

    def test_a_three_day_run_books_every_request_proposes_each_night_and_checkpoints(self):
        config = self.planned('three')
        results = bs.run(Path(self.directory.name) / 'three' / 'simulation.json', 'http://stub', client_factory=lambda s: FakeScheduler(), log=lambda m: None)
        summary = results['s1']
        self.assertEqual(16, sum(1 for o in summary['outcomes'].values() if o == 'BOOKED'), 'the backlog request and fifteen new ones')
        self.assertEqual([], summary['waitingAtEnd'])
        segment = Path(self.directory.name) / 'three' / 'segments' / 's1'
        checkpoint = bh.load_json(segment / 'checkpoint.json')
        self.assertEqual(bh.add_days(MONDAY, 3), checkpoint['cursor'])
        book = bh.Book.from_json(checkpoint['book'])
        self.assertEqual(16, len(book.appointments))
        bookings = stable(segment / 'bookings.jsonl')
        self.assertEqual('wo-1', bookings[0]['request'], 'the backlog request calls on the first simulated date')
        self.assertTrue(all(b['offer']['date'] > b['date'] for b in bookings), 'every offer is from R+1')
        self.assertEqual(MONDAY, bookings[0]['date'])
        proposals = stable(segment / 'proposals.jsonl')
        self.assertEqual({bh.add_days(MONDAY, n) for n in (1, 2, 3)}, {p['night'] for p in proposals})
        committed = [p for p in proposals if p.get('committed')]
        self.assertTrue(committed, 'a technician-day with two stops gets an IMPROVED proposal, which is committed')
        self.assertGreater(book.version, 16, 'every booking and commit moved the versions')
        self.assertGreaterEqual(summary['elapsedSeconds'], 0)
        self.assertEqual(3, summary['simulatedDays'])
        self.assertEqual(config['segments'][0]['shiftDays'] % 7, 0)

    def test_a_run_killed_mid_date_resumes_to_the_same_end_state(self):
        self.planned('whole')
        bs.run(Path(self.directory.name) / 'whole' / 'simulation.json', 'http://stub', client_factory=lambda s: FakeScheduler(), log=lambda m: None)
        self.planned('killed')
        store = {}
        with self.assertRaises(Killed):
            bs.run(Path(self.directory.name) / 'killed' / 'simulation.json', 'http://stub',
                   client_factory=lambda s: FakeScheduler(store, kill_after=23), log=lambda m: None)
        segment = Path(self.directory.name) / 'killed' / 'segments' / 's1'
        cursor = bh.load_json(segment / 'checkpoint.json')['cursor']
        self.assertGreater(cursor, MONDAY, 'the kill came after the first checkpoint')
        self.assertLess(cursor, bh.add_days(MONDAY, 3), 'and before the end')
        # The resumed segment sends the same calls again; the fake refuses any whose body differs from the first.
        bs.run(Path(self.directory.name) / 'killed' / 'simulation.json', 'http://stub', client_factory=lambda s: FakeScheduler(store), log=lambda m: None)
        whole = Path(self.directory.name) / 'whole' / 'segments' / 's1'
        self.assertEqual(bh.load_json(whole / 'checkpoint.json')['book'], bh.load_json(segment / 'checkpoint.json')['book'])
        for name in ('bookings.jsonl', 'proposals.jsonl'):
            self.assertEqual(stable(whole / name), stable(segment / name), name)

    def test_a_checkpoint_from_another_plan_is_refused(self):
        self.planned('other')
        path = Path(self.directory.name) / 'other' / 'simulation.json'
        bs.run(path, 'http://stub', client_factory=lambda s: FakeScheduler(), log=lambda m: None)
        config = bh.load_json(path)
        config['segments'][0]['shiftDays'] += 7
        bi.write_json(path, config)
        with self.assertRaisesRegex(bs.SimulationError, 'another plan'):
            bs.run(path, 'http://stub', client_factory=lambda s: FakeScheduler(), log=lambda m: None)

    def test_changed_inputs_are_refused(self):
        self.planned('changed')
        requests = bh.load_json(self.run / 'part2' / 'requests.json')
        requests['requests'].pop()
        bi.write_json(self.run / 'part2' / 'requests.json', requests)
        with self.assertRaisesRegex(bs.SimulationError, 'changed since'):
            bs.run(Path(self.directory.name) / 'changed' / 'simulation.json', 'http://stub', client_factory=lambda s: FakeScheduler(), log=lambda m: None)

    def test_no_offer_calls_back_on_the_next_weekday(self):
        run = write_run(Path(self.directory.name) / 'full', per_day=13, days=1)
        bs.plan(run, Path(self.directory.name) / 'full-sim', 1, 3, today='2026-11-05', until=bh.add_days(MONDAY, 1))
        bs.run(Path(self.directory.name) / 'full-sim' / 'simulation.json', 'http://stub', client_factory=lambda s: FakeScheduler(), log=lambda m: None)
        bookings = stable(Path(self.directory.name) / 'full-sim' / 'segments' / 's1' / 'bookings.jsonl')
        # Two technicians, three jobs each, on the two lead weekdays: 12 places, 14 requests.
        no_offer = [b for b in bookings if b['outcome'] == 'NO_OFFER']
        self.assertEqual(2, len([b for b in no_offer if b['date'] == MONDAY]))
        self.assertEqual({bh.add_days(MONDAY, 1)}, {b['date'] for b in bookings if b['attempt'] == 2}, 'they call back the next weekday')


if __name__ == '__main__':
    unittest.main()
