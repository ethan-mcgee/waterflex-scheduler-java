"""The public scheduling API as the Part 2 simulator calls it, ported from the portal's client (web/lib/schedulerApi.ts).

Booking: offers, select, confirm and release. Overnight: a daily proposal queued with Prefer: respond-async and polled
on Retry-After, then its commit. Every answer is checked before it is used: a missing or mistyped field is a MALFORMED
error, never a default. A 429, a 503 or a transport failure is sent again with the same requestId, which the API
replays, after Retry-After or a doubling wait from 5 s (at most 300 s), a bounded number of times; anything else is
final and raised as ApiError for the simulator to record.

The token is passed in as a value (the runner reads it from the variable named by --token-env); it is never written.
Stdlib only. The HTTP layer is one function, so the tests run against a stub server or a scripted function.
"""
import json
import time
import urllib.error
import urllib.parse
import urllib.request

PROBLEM_CODES = {'INVALID_REQUEST', 'NOT_FOUND', 'STALE', 'NOT_COMMITTABLE', 'HOLD_UNAVAILABLE', 'INCOMPLETE_FACTS', 'BUSY',
                 'ROUTING_UNAVAILABLE', 'CALCULATION_UNAVAILABLE', 'CANCELLED', 'SERVICE_UNAVAILABLE', 'CURRENT_ROUTES_INFEASIBLE',
                 'INTERNAL_ERROR'}
REFINEMENTS = {'COMPLETE', 'SHORTENED', 'SKIPPED', 'UNAVAILABLE', 'SHED'}
DECISIONS = {'IMPROVED', 'NO_IMPROVEMENT', 'REJECTED_BY_POLICY'}
RETRIED = {429, 503}
CALLER_MARGIN_MS = 3000
DEFAULT_POLL_MS = 3000
MAX_WAIT_MS = 300_000


class ApiError(Exception):
    """A refused or failed call: code is the API's problem code, or TRANSPORT, MALFORMED or NOT_CONNECTED."""

    def __init__(self, status, code, message, changed=(), retry_after_ms=None, attempts=1):
        super().__init__(f'{status} {code}: {message}')
        self.status, self.code, self.message = status, code, message
        self.changed, self.retry_after_ms, self.attempts = list(changed), retry_after_ms, attempts


def urllib_http(method, url, headers, body, timeout_s):
    """(status, lower-cased headers, body text) of one call; an HTTP error status is an answer, a lost call raises OSError."""
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout_s) as response:
            return response.status, {k.lower(): v for k, v in response.headers.items()}, response.read().decode('utf-8')
    except urllib.error.HTTPError as error:
        try:
            return error.code, {k.lower(): v for k, v in error.headers.items()}, error.read().decode('utf-8')
        finally:
            error.close()


# ----------------------------------------------------------------------------------------------------------------------
# Answers, checked


def malformed(what):
    return ApiError(502, 'MALFORMED', what)


def need(document, key, kind, where):
    """document[key] when present and of the kind (a bool is never an int); MALFORMED otherwise."""
    if not isinstance(document, dict) or key not in document:
        raise malformed(f'{where} has no {key}')
    value = document[key]
    if isinstance(value, bool) and kind is not bool:
        raise malformed(f'{where}.{key} is not {kind.__name__}')
    if not isinstance(value, kind):
        raise malformed(f'{where}.{key} is not {kind.__name__}')
    return value


def optional(document, key, kind, where):
    return None if key not in document else need(document, key, kind, where)


def check_window(window, where):
    need(window, 'start', str, where)
    need(window, 'end', str, where)
    return window


def check_offer_set(body):
    where = 'offer set'
    for key, kind in (('offerSetId', str), ('expiresAt', str), ('searchComplete', bool), ('offers', list), ('skippedTechnicianDays', list)):
        need(body, key, kind, where)
    refinement = optional(body, 'refinement', str, where)
    if refinement is not None and refinement not in REFINEMENTS:
        raise malformed(f'{where}.refinement is {refinement}')
    stop = optional(body, 'refinementStop', str, where)
    if stop is not None and stop not in ('CAP', 'DEADLINE'):
        raise malformed(f'{where}.refinementStop is {stop}')
    optional(body, 'regularWindowsFound', int, where)
    optional(body, 'incompleteDates', list, where)
    if len(body['offers']) > 4:
        raise malformed(f'{where} has more than 4 offers')
    for offer in body['offers']:
        need(offer, 'offerId', str, 'offer')
        need(offer, 'serviceDate', str, 'offer')
        check_window(need(offer, 'window', dict, 'offer'), 'offer.window')
    return body


def check_hold(body):
    for key in ('holdId', 'offerId', 'expiresAt'):
        need(body, key, str, 'hold')
    return body


def check_versions(items, where):
    for item in items:
        for key in ('technicianId', 'serviceDate', 'lastModified'):
            need(item, key, str, where)
    return items


def check_receipt(body):
    need(body, 'receiptId', str, 'receipt')
    for item in need(body, 'assignments', list, 'receipt'):
        for key, kind in (('appointmentId', str), ('technicianId', str), ('serviceDate', str), ('sequence', int), ('plannedStart', str), ('plannedEnd', str)):
            need(item, key, kind, 'receipt assignment')
    check_versions(need(body, 'technicianDays', list, 'receipt'), 'receipt technicianDay')
    return body


def check_proposal(body):
    where = 'proposal'
    for key, kind in (('proposalId', str), ('inputRevision', str), ('decision', str), ('reason', str), ('routes', list),
                      ('unresolvedAppointmentIds', list), ('skippedTechnicianDays', list), ('costCents', int), ('overtimeMinutes', int)):
        need(body, key, kind, where)
    if body['decision'] not in DECISIONS:
        raise malformed(f'{where}.decision is {body["decision"]}')
    if body['overtimeMinutes'] != 0:
        raise malformed(f'{where} plans overtime')
    for route in body['routes']:
        need(route, 'technicianId', str, 'route')
        need(route, 'serviceDate', str, 'route')
        for stop in need(route, 'stops', list, 'route'):
            for key, kind in (('appointmentId', str), ('sequence', int), ('plannedStart', str), ('plannedEnd', str)):
                need(stop, key, kind, 'stop')
    return body


def check_status(body, request_id):
    need(body, 'requestId', str, 'request status')
    if body['requestId'] != request_id:
        raise malformed("The scheduling service reported another request's status")
    state = need(body, 'state', str, 'request status')
    if state not in ('QUEUED', 'RUNNING', 'COMPLETED'):
        raise malformed(f'request status state is {state}')
    if (state == 'COMPLETED') != ('result' in body):
        raise malformed('A request has a result exactly when it is COMPLETED')
    if state == 'COMPLETED':
        need(body['result'], 'status', int, 'request result')
        if 'body' not in body['result']:
            raise malformed('request result has no body')
    return body


def problem_error(status, body, retry_after_ms, attempts=1):
    """The ApiError a refusal carries, or MALFORMED when its body is not a Problem."""
    if not isinstance(body, dict) or body.get('error') not in PROBLEM_CODES or not isinstance(body.get('message'), str) or not body['message']:
        return ApiError(502, 'MALFORMED', f'The scheduling service sent an unexpected {status} response: {json.dumps(body)[:300]}', attempts=attempts)
    changed = body.get('changed', [])
    if (body['error'] == 'STALE') != ('changed' in body):
        return ApiError(502, 'MALFORMED', 'Only a STALE problem lists changed technician-days', attempts=attempts)
    return ApiError(status, body['error'], body['message'], check_versions(changed, 'changed'), retry_after_ms, attempts)


def seconds_header(headers, name='retry-after'):
    value = headers.get(name)
    return int(value) * 1000 if isinstance(value, str) and value.isdigit() and 0 < int(value) < 1_000_000 else None


# ----------------------------------------------------------------------------------------------------------------------
# The client


class Client:
    def __init__(self, url, token, http=urllib_http, sleep=time.sleep, monotonic=time.monotonic, max_attempts=8):
        if not token:
            raise ApiError(503, 'NOT_CONNECTED', 'No scheduling API token')
        self.url, self.token = url.rstrip('/'), token
        self.http, self.sleep, self.monotonic, self.max_attempts = http, sleep, monotonic, max_attempts

    def headers(self, prefer=False):
        headers = {'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json', 'Accept': 'application/json'}
        if prefer:
            headers['Prefer'] = 'respond-async'
        return headers

    def send(self, method, path, body, timeout_ms, prefer=False):
        """(status, headers, parsed body) of one call; a lost call is TRANSPORT, a 401 NOT_CONNECTED, unreadable JSON MALFORMED."""
        try:
            status, headers, text = self.http(method, self.url + path, self.headers(prefer), body, timeout_ms / 1000)
        except (OSError, TimeoutError) as error:
            raise ApiError(504 if isinstance(error, TimeoutError) or 'timed out' in str(error) else 503, 'TRANSPORT', str(error)) from None
        if status == 401:
            raise ApiError(503, 'NOT_CONNECTED', 'The scheduling service does not accept the token')
        try:
            parsed = json.loads(text) if text else None
        except ValueError:
            raise malformed(f'The scheduling service sent an unreadable {status} response') from None
        return status, headers, parsed

    def retried(self, call):
        """call() until it answers something other than 429, 503 or a transport failure, at most max_attempts times."""
        for attempt in range(1, self.max_attempts + 1):
            try:
                result = call()
            except ApiError as error:
                error.attempts = attempt
                # Only the API's own 429 and 503 problems and lost calls are worth sending again; NOT_CONNECTED (a refused
                # token, also 503) and MALFORMED are not.
                if attempt == self.max_attempts or not (error.code == 'TRANSPORT' or (error.status in RETRIED and error.code in PROBLEM_CODES)):
                    raise
                wait = error.retry_after_ms if error.retry_after_ms is not None else 5000 * 2 ** (attempt - 1)
                self.sleep(min(max(wait, 1000), MAX_WAIT_MS) / 1000)
                continue
            return result, attempt
        raise AssertionError('unreachable')

    def post(self, path, body, check, timeout_ms):
        def once():
            status, headers, parsed = self.send('POST', path, body, timeout_ms)
            if 200 <= status < 300:
                return check(parsed)
            raise problem_error(status, parsed, seconds_header(headers))
        return self.retried(once)

    # -- calls --------------------------------------------------------------------------------------------------------

    def whoami(self):
        status, headers, body = self.send('GET', '/api/v1/whoami', None, 5000)
        if status != 200:
            raise problem_error(status, body, seconds_header(headers))
        return need(body, 'tenantId', str, 'whoami')

    def offers(self, request):
        """(offer set, attempts). The API answers within the booking deadline; the margin covers the upload and the network."""
        deadline = request['snapshot']['policy']['bookingDeadlineMs']
        return self.post('/api/v1/booking/offers', request, check_offer_set, deadline + CALLER_MARGIN_MS + 10_000)

    def select(self, offer_id, request_id):
        return self.post(f'/api/v1/booking/offers/{urllib.parse.quote(offer_id, safe="")}/select', {'requestId': request_id}, check_hold, 10_000)

    def release(self, offer_id, request_id):
        def released(body):
            if not isinstance(body, dict) or body.get('released') is not True:
                raise malformed('release did not answer released: true')
            return body
        return self.post(f'/api/v1/booking/offers/{urllib.parse.quote(offer_id, safe="")}/release', {'requestId': request_id}, released, 10_000)

    def confirm(self, hold_id, request_id, snapshot):
        return self.post(f'/api/v1/booking/holds/{urllib.parse.quote(hold_id, safe="")}/confirm', {'requestId': request_id, 'snapshot': snapshot},
                         check_receipt, 30_000)

    def commit(self, proposal_id, request_id, technician_days):
        return self.post(f'/api/v1/daily/proposals/{urllib.parse.quote(proposal_id, safe="")}/commit',
                         {'requestId': request_id, 'technicianDays': technician_days}, check_receipt, 30_000)

    def propose(self, request, limit_ms):
        """(proposal, {'attempts', 'polls', 'queuedMs'}) for a daily proposal queued with Prefer: respond-async and polled on
        Retry-After until it completes within limit_ms; a refusal it ended with is raised (a 429 keeps its Retry-After)."""
        started = self.monotonic()
        polls = 0

        def once():
            nonlocal polls
            status, headers, body = self.send('POST', '/api/v1/daily/proposals', request, 30_000, prefer=True)
            if status == 201:
                return check_proposal(body)
            if status != 202:
                if 200 <= status < 300:
                    raise malformed(f'The scheduling service answered {status} to a queued proposal')
                raise problem_error(status, body, seconds_header(headers))
            current, wait = check_status(body, request['requestId']), seconds_header(headers) or DEFAULT_POLL_MS
            while current['state'] != 'COMPLETED':
                if (self.monotonic() - started) * 1000 + wait > limit_ms:
                    raise ApiError(504, 'TRANSPORT', f'The queued proposal was still {current["state"].lower()} after {round(limit_ms / 1000)} seconds')
                self.sleep(wait / 1000)
                polls += 1
                status, headers, body = self.send('GET', f'/api/v1/requests/{urllib.parse.quote(request["requestId"], safe="")}', None, 10_000)
                if status != 200:
                    raise problem_error(status, body, seconds_header(headers))
                current, wait = check_status(body, request['requestId']), seconds_header(headers) or DEFAULT_POLL_MS
            result = current['result']
            if result['status'] == 201:
                return check_proposal(result['body'])
            if result['status'] < 400:
                raise malformed(f'The queued proposal ended with an unexpected {result["status"]} result')
            retry = result.get('retryAfterSeconds')
            raise problem_error(result['status'], result['body'], retry * 1000 if result['status'] == 429 and isinstance(retry, int) else None)

        proposal, attempts = self.retried(once)
        return proposal, {'attempts': attempts, 'polls': polls, 'elapsedMs': round((self.monotonic() - started) * 1000)}


def proposal_limit_ms(policy, queue_ms=300_000):
    """How long a queued proposal may take: the validity bar's 300 s in the queue plus the search time and its reserve."""
    return queue_ms + (policy['costSearchSeconds'] + policy['fairnessSearchSeconds'] + 5) * 1000 + 10_000
