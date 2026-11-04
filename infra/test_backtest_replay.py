"""Regression coverage for infra/backtest_replay.py with a scripted HTTP layer; no stack, no network."""
import json
import tempfile
import unittest
from pathlib import Path

import backtest_replay as br


def request(day):
    return {'requestId': 'to-be-replaced', 'serviceDate': day, 'snapshot': {
        'technicianDays': [{'technicianId': 't01'}, {'technicianId': 't02'}], 'appointments': [{'id': 'wo-1'}, {'id': 'wo-2'}, {'id': 'wo-3'}]}}


def proposal():
    return {'proposalId': 'p', 'decision': 'IMPROVED', 'routes': [
        {'technicianId': 't01', 'serviceDate': 'x', 'stops': [{'appointmentId': 'wo-2', 'sequence': 1}, {'appointmentId': 'wo-1', 'sequence': 0}]}],
        'unresolvedAppointmentIds': ['wo-3'], 'costCents': 1234, 'overtimeMinutes': 0}


class ScriptedHttp:
    """Answers in order per (method, path prefix); records every call."""

    def __init__(self, script):
        self.script = script
        self.calls = []

    def __call__(self, method, url, headers, body):
        self.calls.append((method, url, headers, body))
        path = url.split('/api/v1/', 1)[1]
        key = 'POST' if method == 'POST' else 'GET'
        status, response_headers, response_body = self.script[key].pop(0)
        return status, response_headers, json.dumps(response_body) if response_body is not None else ''


def write_run(root, days):
    (root / 'index.json').write_text(json.dumps({'runId': 'run', 'days': [{'day': d, 'split': 'test', 'year': 2025, 'technicians': 2, 'weekday': 0} for d in days]}), encoding='utf-8')
    for day in days:
        directory = root / 'days' / day
        directory.mkdir(parents=True)
        (directory / 'api-request.json').write_text(json.dumps(request(day)), encoding='utf-8')


class ReplayTest(unittest.TestCase):
    def test_an_async_answer_is_polled_to_its_proposal_and_a_plan_is_written(self):
        http = ScriptedHttp({'POST': [(202, {'retry-after': '1'}, {'state': 'QUEUED'})],
                             'GET': [(200, {'retry-after': '1'}, {'state': 'RUNNING'}), (200, {}, {'state': 'COMPLETED', 'result': {'status': 201, 'body': proposal()}})]})
        waits = []
        replay = br.Replay('http://stack', 'tok', http=http, sleep=waits.append)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_run(root, ['2025-02-03'])
            outcomes = br.replay_days(root, ['2025-02-03'], 1, replay, 1)
            self.assertEqual([{'day': '2025-02-03', 'status': 201, 'skipped': False}], outcomes)
            answer = json.loads((root / 'api' / '2025-02-03' / 'rep1.json').read_text(encoding='utf-8'))
            self.assertEqual(201, answer['status']); self.assertEqual(1, answer['attempts']); self.assertEqual(2, answer['polls'])
            self.assertEqual('IMPROVED', answer['body']['decision'])
            plan = json.loads((root / 'api' / '2025-02-03' / 'rep1.plan.json').read_text(encoding='utf-8'))
            self.assertEqual({'t01': ['wo-1', 'wo-2'], 't02': []}, plan['routes'], 'stops in sequence order, an empty route for the idle technician')
            self.assertEqual(['wo-3'], plan['unassigned'])
            self.assertEqual([1.0, 1.0], waits)
            method, url, headers, body = http.calls[0]
            self.assertEqual('respond-async', headers['Prefer']); self.assertEqual('Bearer tok', headers['Authorization'])
            self.assertEqual(answer['requestId'], body['requestId'])
            self.assertNotEqual('to-be-replaced', body['requestId'])
            self.assertTrue(http.calls[1][1].endswith('/api/v1/requests/' + body['requestId']))
            # A second replay of the same day and repetition skips the final answer and makes no call.
            before = len(http.calls)
            self.assertEqual([{'day': '2025-02-03', 'status': 201, 'skipped': True}], br.replay_days(root, ['2025-02-03'], 1, replay, 1))
            self.assertEqual(before, len(http.calls))

    def test_a_busy_stack_is_retried_after_its_retry_after_and_a_refusal_is_final(self):
        http = ScriptedHttp({'POST': [(429, {'retry-after': '3'}, {'error': 'BUSY', 'message': 'full'}),
                                      (422, {}, {'error': 'INCOMPLETE_FACTS', 'message': 'Routes for 2025-02-04 are frozen from 6 a.m. local time'})], 'GET': []})
        waits = []
        replay = br.Replay('http://stack', 'tok', http=http, sleep=waits.append)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_run(root, ['2025-02-04'])
            outcomes = br.replay_days(root, ['2025-02-04'], 2, replay, 1)
            self.assertEqual(422, outcomes[0]['status'])
            self.assertEqual([3.0], waits)
            answer = json.loads((root / 'api' / '2025-02-04' / 'rep2.json').read_text(encoding='utf-8'))
            self.assertEqual(2, answer['attempts']); self.assertEqual('INCOMPLETE_FACTS', answer['body']['error'])
            self.assertFalse((root / 'api' / '2025-02-04' / 'rep2.plan.json').exists())
            self.assertEqual(2, answer['rep'])

    def test_a_timed_out_poll_is_recorded_and_retried_next_time(self):
        http = ScriptedHttp({'POST': [(202, {'retry-after': '1'}, {'state': 'QUEUED'})], 'GET': [(200, {}, {'state': 'RUNNING'})] * 5})
        replay = br.Replay('http://stack', 'tok', http=http, sleep=lambda s: None, timeout_s=0)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_run(root, ['2025-02-05'])
            outcomes = br.replay_days(root, ['2025-02-05'], 1, replay, 1)
            self.assertEqual(504, outcomes[0]['status'])
            answer = json.loads((root / 'api' / '2025-02-05' / 'rep1.json').read_text(encoding='utf-8'))
            self.assertEqual('TIMEOUT', answer['body']['error'])
            # Not final: the next replay posts again.
            http.script['POST'].append((201, {}, proposal()))
            self.assertEqual(201, br.replay_days(root, ['2025-02-05'], 1, replay, 1)[0]['status'])


if __name__ == '__main__':
    unittest.main()
