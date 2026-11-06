"""Regression coverage for infra/backtest_api.py against a stub HTTP server on 127.0.0.1 that plays scripted answers."""
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import backtest_api as ba

OFFER_SET = {'offerSetId': 'os-1', 'expiresAt': '2027-01-01T00:00:00Z', 'searchComplete': True, 'refinement': 'COMPLETE', 'regularWindowsFound': 3,
             'incompleteDates': [], 'offers': [{'offerId': 'of-1', 'serviceDate': '2027-01-05', 'window': {'start': '2027-01-05T17:00:00Z', 'end': '2027-01-05T19:00:00Z'}}],
             'skippedTechnicianDays': []}
PROPOSAL = {'proposalId': 'p-1', 'inputRevision': '0' * 64, 'decision': 'IMPROVED', 'reason': 'COST', 'routes': [
    {'technicianId': 't01', 'serviceDate': '2027-01-05', 'stops': [{'appointmentId': 'wo-1', 'sequence': 0, 'plannedStart': '2027-01-05T17:00:00Z',
                                                                    'plannedEnd': '2027-01-05T18:00:00Z'}]}],
    'unresolvedAppointmentIds': [], 'skippedTechnicianDays': [], 'costCents': 1234, 'overtimeMinutes': 0}
REQUEST = {'requestId': '00000000-0000-5000-8000-000000000001', 'serviceDate': '2027-01-05',
           'snapshot': {'policy': {'bookingDeadlineMs': 5000, 'costSearchSeconds': 10, 'fairnessSearchSeconds': 5}}}


class Stub:
    """Answers each call with the next scripted (status, headers, body) and records what it was sent."""

    def __init__(self):
        self.script, self.calls = [], []
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def handle_one(self, method):
                length = int(self.headers.get('Content-Length') or 0)
                body = json.loads(self.rfile.read(length)) if length else None
                stub.calls.append({'method': method, 'path': self.path, 'headers': dict(self.headers), 'body': body})
                status, headers, answer = stub.script.pop(0)
                text = answer if isinstance(answer, str) else json.dumps(answer)
                self.send_response(status)
                for name, value in headers.items():
                    self.send_header(name, value)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(text.encode())))
                self.end_headers()
                self.wfile.write(text.encode())

            def do_GET(self):
                self.handle_one('GET')

            def do_POST(self):
                self.handle_one('POST')

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f'http://127.0.0.1:{self.server.server_address[1]}'

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class Clock:
    def __init__(self):
        self.now, self.slept = 0.0, []

    def sleep(self, seconds):
        self.slept.append(seconds)
        self.now += seconds

    def monotonic(self):
        return self.now


class ClientTest(unittest.TestCase):
    def setUp(self):
        self.stub, self.clock = Stub(), Clock()
        self.client = ba.Client(self.stub.url, 'tok-secret', sleep=self.clock.sleep, monotonic=self.clock.monotonic, max_attempts=3)

    def tearDown(self):
        self.stub.close()

    def answer(self, *items):
        self.stub.script.extend(items)

    def test_offers_send_the_request_with_the_token_and_check_the_answer(self):
        self.answer((201, {}, OFFER_SET))
        offer_set, attempts = self.client.offers(REQUEST)
        self.assertEqual((OFFER_SET, 1), (offer_set, attempts))
        call = self.stub.calls[0]
        self.assertEqual(('POST', '/api/v1/booking/offers', REQUEST), (call['method'], call['path'], call['body']))
        self.assertEqual('Bearer tok-secret', call['headers']['Authorization'])

    def test_busy_and_unavailable_are_sent_again_with_the_same_request_after_retry_after(self):
        busy = {'error': 'BUSY', 'message': 'busy'}
        self.answer((429, {'Retry-After': '2'}, busy), (503, {}, {'error': 'SERVICE_UNAVAILABLE', 'message': 'down'}), (201, {}, OFFER_SET))
        _, attempts = self.client.offers(REQUEST)
        self.assertEqual(3, attempts)
        self.assertEqual([2.0, 10.0], self.clock.slept, 'Retry-After, then the doubling wait for the second attempt')
        self.assertEqual(1, len({json.dumps(c['body']) for c in self.stub.calls}), 'the same requestId every time')
        self.answer(*[(503, {}, {'error': 'SERVICE_UNAVAILABLE', 'message': 'down'})] * 3)
        with self.assertRaises(ba.ApiError) as raised:
            self.client.offers(REQUEST)
        self.assertEqual(('SERVICE_UNAVAILABLE', 3), (raised.exception.code, raised.exception.attempts))

    def test_a_final_refusal_is_raised_at_once_with_what_it_carries(self):
        changed = [{'technicianId': 't01', 'serviceDate': '2027-01-05', 'lastModified': '2026-01-01T00:00:00.000002Z'}]
        self.answer((409, {}, {'error': 'STALE', 'message': 'moved', 'changed': changed}))
        with self.assertRaises(ba.ApiError) as raised:
            self.client.confirm('h-1', 'r-1', {'technicianDays': []})
        self.assertEqual((409, 'STALE', changed, 1), (raised.exception.status, raised.exception.code, raised.exception.changed, raised.exception.attempts))
        self.assertEqual('/api/v1/booking/holds/h-1/confirm', self.stub.calls[0]['path'])

    def test_answers_that_do_not_match_the_api_are_malformed_never_defaulted(self):
        bad = [(201, {}, {k: v for k, v in OFFER_SET.items() if k != 'searchComplete'}), (201, {}, dict(OFFER_SET, searchComplete='yes')),
               (201, {}, dict(OFFER_SET, refinement='MAYBE')), (500, {}, {'oops': True}), (500, {}, 'not json'),
               (409, {}, {'error': 'STALE', 'message': 'no list'}), (201, {}, dict(OFFER_SET, regularWindowsFound=True))]
        for item in bad:
            self.answer(item)
            with self.assertRaises(ba.ApiError, msg=str(item)) as raised:
                self.client.offers(REQUEST)
            self.assertEqual('MALFORMED', raised.exception.code, str(item))

    def test_a_refused_token_is_not_connected(self):
        self.answer((401, {}, ''))
        with self.assertRaises(ba.ApiError) as raised:
            self.client.select('of-1', 'r-1')
        self.assertEqual(('NOT_CONNECTED', 1), (raised.exception.code, raised.exception.attempts), 'a refused token is not sent again')
        self.assertEqual(1, len(self.stub.calls))
        with self.assertRaises(ba.ApiError):
            ba.Client(self.stub.url, '')

    def test_a_queued_proposal_is_polled_on_retry_after_until_it_completes(self):
        queued = {'requestId': REQUEST['requestId'], 'operation': 'DAILY_PROPOSAL', 'state': 'QUEUED'}
        self.answer((202, {'Retry-After': '1'}, queued), (200, {'Retry-After': '4'}, dict(queued, state='RUNNING')),
                    (200, {}, dict(queued, state='COMPLETED', result={'status': 201, 'body': PROPOSAL})))
        proposal, record = self.client.propose(REQUEST, ba.proposal_limit_ms(REQUEST['snapshot']['policy']))
        self.assertEqual(PROPOSAL, proposal)
        self.assertEqual({'attempts': 1, 'polls': 2, 'elapsedMs': 5000}, record)
        self.assertEqual([1.0, 4.0], self.clock.slept)
        self.assertEqual('respond-async', self.stub.calls[0]['headers']['Prefer'])
        self.assertEqual(f'/api/v1/requests/{REQUEST["requestId"]}', self.stub.calls[1]['path'])

    def test_a_queued_proposal_that_ends_refused_is_raised_and_a_replayed_one_returns_at_once(self):
        queued = {'requestId': REQUEST['requestId'], 'operation': 'DAILY_PROPOSAL', 'state': 'QUEUED'}
        refusal = {'error': 'CURRENT_ROUTES_INFEASIBLE', 'message': 't01 runs late'}
        self.answer((202, {}, queued), (200, {}, dict(queued, state='COMPLETED', result={'status': 422, 'body': refusal})))
        with self.assertRaises(ba.ApiError) as raised:
            self.client.propose(REQUEST, 600_000)
        self.assertEqual((422, 'CURRENT_ROUTES_INFEASIBLE'), (raised.exception.status, raised.exception.code))
        self.answer((201, {}, PROPOSAL))
        self.assertEqual(PROPOSAL, self.client.propose(REQUEST, 600_000)[0])

    def test_a_proposal_with_overtime_or_another_requests_status_is_malformed(self):
        self.answer((201, {}, dict(PROPOSAL, overtimeMinutes=5)))
        with self.assertRaises(ba.ApiError) as raised:
            self.client.propose(REQUEST, 600_000)
        self.assertEqual('MALFORMED', raised.exception.code)
        self.answer((202, {}, {'requestId': 'other', 'operation': 'DAILY_PROPOSAL', 'state': 'QUEUED'}))
        with self.assertRaises(ba.ApiError) as raised:
            self.client.propose(REQUEST, 600_000)
        self.assertEqual('MALFORMED', raised.exception.code)

    def test_a_proposal_still_queued_past_its_limit_stops(self):
        client = ba.Client(self.stub.url, 'tok', sleep=self.clock.sleep, monotonic=self.clock.monotonic, max_attempts=1)
        queued = {'requestId': REQUEST['requestId'], 'operation': 'DAILY_PROPOSAL', 'state': 'QUEUED'}
        self.answer((202, {'Retry-After': '30'}, queued), (200, {'Retry-After': '30'}, queued))
        with self.assertRaises(ba.ApiError) as raised:
            client.propose(REQUEST, 45_000)
        self.assertEqual((504, 'TRANSPORT'), (raised.exception.status, raised.exception.code))

    def test_a_lost_call_is_transport_and_is_sent_again(self):
        answers = [OSError('connection refused'), (201, {}, json.dumps(OFFER_SET))]

        def http(method, url, headers, body, timeout_s):
            answer = answers.pop(0)
            if isinstance(answer, Exception):
                raise answer
            return answer

        client = ba.Client('http://127.0.0.1:1', 'tok', http=http, sleep=self.clock.sleep, monotonic=self.clock.monotonic)
        self.assertEqual((OFFER_SET, 2), client.offers(REQUEST))
        self.assertEqual([5.0], self.clock.slept)

    def test_commit_and_release_check_their_answers(self):
        receipt = {'receiptId': 'c-1', 'assignments': PROPOSAL['routes'][0]['stops'][:0], 'technicianDays': []}
        self.answer((200, {}, receipt), (200, {}, {'released': True}), (200, {}, {'released': False}))
        self.assertEqual(receipt, self.client.commit('p-1', 'r-2', [])[0])
        self.assertEqual({'released': True}, self.client.release('of-1', 'r-3')[0])
        with self.assertRaises(ba.ApiError):
            self.client.release('of-1', 'r-4')
        self.assertEqual('/api/v1/daily/proposals/p-1/commit', self.stub.calls[0]['path'])


if __name__ == '__main__':
    unittest.main()
