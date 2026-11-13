"""Replay the imported days through the public API on the rehearsal stack, and read each answer beside the harness.

Each day's api-request.json (infra/backtest_import.py) is posted as a daily proposal with Prefer: respond-async and
polled until it completes. The answer, whatever its status, is kept under <run>/api/<day>/rep<n>.json; a 201 proposal
also becomes a BacktestTool plan file, which the score command reads over the day's sealed facts with the benchmark
jar so the API's plan and the harness's are read by the same scorer. A day already answered 201, 400 or 422 is skipped
on a rerun (the claim replays the same answer for the same requestId), so a stopped replay resumes.

    python infra/backtest_replay.py replay --run <run dir> --url http://127.0.0.1:18080 [--token-env SCHEDULER_BACKTEST_TOKEN]
        [--select test|calibration|all|sample:120|noise:50|<dates>] [--rep 1] [--concurrency 2]
    python infra/backtest_replay.py score --run <run dir> --jar <campaign-benchmark jar> [--rep 1] [--java java]

Stdlib only. The HTTP layer is one function so the tests replace it.
"""
import argparse
import datetime as dt
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import backtest_summary

FINAL_STATUSES = {201, 400, 422}
RETRY_STATUSES = {429, 503}
MAX_RETRIES = 20


def urllib_http(method, url, headers, body):
    """(status, lower-cased headers, body text) for one call; HTTP errors are answers, not exceptions."""
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.status, {k.lower(): v for k, v in response.headers.items()}, response.read().decode('utf-8')
    except urllib.error.HTTPError as error:
        return error.code, {k.lower(): v for k, v in error.headers.items()}, error.read().decode('utf-8')


def parse_body(text):
    try:
        return json.loads(text) if text else None
    except ValueError:
        return {'raw': text}


class Replay:
    def __init__(self, url, token, http=urllib_http, sleep=time.sleep, timeout_s=900):
        self.url = url.rstrip('/')
        self.token = token
        self.http = http
        self.sleep = sleep
        self.timeout_s = timeout_s

    def headers(self, prefer=False):
        headers = {'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json', 'Accept': 'application/json'}
        if prefer:
            headers['Prefer'] = 'respond-async'
        return headers

    def propose(self, request):
        """The final answer for one request: {'status', 'body', 'attempts', 'elapsedMs', 'polls'}."""
        started = time.monotonic()
        attempts, polls = 0, 0
        while True:
            attempts += 1
            status, headers, text = self.http('POST', f'{self.url}/api/v1/daily/proposals', self.headers(prefer=True), request)
            body = parse_body(text)
            if status == 202:
                status, body, polled = self.poll(request['requestId'], headers, started)
                polls += polled
            if status in RETRY_STATUSES and attempts < MAX_RETRIES and time.monotonic() - started < self.timeout_s:
                self.sleep(float(headers.get('retry-after', 5)))
                continue
            return {'status': status, 'body': body, 'attempts': attempts, 'polls': polls, 'elapsedMs': round((time.monotonic() - started) * 1000)}

    def poll(self, request_id, headers, started):
        """GET /api/v1/requests/{id} on Retry-After until the request completes; (status, body, polls) of its result."""
        polls = 0
        wait = float(headers.get('retry-after', 2))
        while time.monotonic() - started < self.timeout_s:
            self.sleep(wait)
            polls += 1
            status, headers, text = self.http('GET', f'{self.url}/api/v1/requests/{request_id}', self.headers(), None)
            body = parse_body(text)
            if status != 200:
                return status, body, polls
            if body.get('state') == 'COMPLETED':
                result = body['result']
                return result['status'], result['body'], polls
            wait = float(headers.get('retry-after', 2))
        return 504, {'error': 'TIMEOUT', 'message': f'No completion within {self.timeout_s} seconds'}, polls


def plan_from_proposal(request, proposal):
    """A BacktestTool plan from a 201 proposal: every technician-day's stops in order, unresolved appointments unassigned."""
    routes = {day['technicianId']: [] for day in request['snapshot']['technicianDays']}
    for route in proposal['routes']:
        routes[route['technicianId']] = [stop['appointmentId'] for stop in sorted(route['stops'], key=lambda s: s['sequence'])]
    return {'version': 1, 'routes': routes, 'unassigned': list(proposal.get('unresolvedAppointmentIds', []))}


def write_json(path, document):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.part')
    with tmp.open('w', encoding='utf-8', newline='\n') as stream:
        json.dump(document, stream, indent=1, sort_keys=True)
        stream.write('\n')
    tmp.replace(path)


def replay_day(run, day, rep, replay, run_id):
    directory = Path(run) / 'api' / day
    answer = directory / f'rep{rep}.json'
    if answer.exists():
        with answer.open(encoding='utf-8') as stream:
            previous = json.load(stream)
        if previous['status'] in FINAL_STATUSES:
            return day, previous['status'], True
    with (Path(run) / 'days' / day / 'api-request.json').open(encoding='utf-8') as stream:
        request = json.load(stream)
    request['requestId'] = str(uuid.uuid5(uuid.NAMESPACE_URL, f'{run_id}/{day}/{rep}'))
    result = replay.propose(request)
    document = {'day': day, 'rep': rep, 'requestId': request['requestId'], 'serviceDate': request['serviceDate'],
                'submittedAt': dt.datetime.now(dt.timezone.utc).isoformat(), **result}
    if result['status'] == 201:
        write_json(directory / f'rep{rep}.plan.json', plan_from_proposal(request, result['body']))
    write_json(answer, document)
    return day, result['status'], False


def replay_days(run, days, rep, replay, concurrency):
    with (Path(run) / 'index.json').open(encoding='utf-8') as stream:
        run_id = json.load(stream)['runId']
    outcomes = []
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        for day, status, skipped in pool.map(lambda d: replay_day(run, d, rep, replay, run_id), days):
            outcomes.append({'day': day, 'status': status, 'skipped': skipped})
            print(json.dumps(outcomes[-1]), flush=True)
    return outcomes


def sealed_facts(run, day, jar, java):
    """The day's sealed booked facts: the harness's copy when it ran, else sealed now beside the answers."""
    rows = Path(run) / 'rows' / day
    if rows.exists():
        for candidate in sorted(rows.glob('*/facts.sealed.json')):
            return candidate
    target = Path(run) / 'api' / day / 'facts.sealed.json'
    if not target.exists():
        request = target.with_suffix('.seal.json')
        write_json(request, {'version': 1, 'kind': 'seal', 'input': str(Path(run) / 'days' / day / 'facts.json'), 'output': str(target)})
        receipt = target.with_suffix('.seal.receipt.json')
        if receipt.exists():
            receipt.unlink()
        run_jar(jar, java, request, receipt)
    return target


def run_jar(jar, java, request, receipt):
    result = subprocess.run([java, '-jar', str(jar), str(request), str(receipt)], capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f'{request.name} failed: {result.stderr[-2000:]}')


def sha256_of(path):
    import hashlib
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def score_days(run, days, rep, jar, java):
    scored = []
    for day in days:
        directory = Path(run) / 'api' / day
        plan = directory / f'rep{rep}.plan.json'
        row = directory / f'rep{rep}.row.json'
        if not plan.exists() or row.exists():
            continue
        facts = sealed_facts(run, day, jar, java)
        request = directory / f'rep{rep}.score.json'
        receipt = directory / f'rep{rep}.score.receipt.json'
        if receipt.exists():
            receipt.unlink()
        write_json(request, {'version': 1, 'kind': 'backtest-score', 'dataset': {'path': str(facts), 'sha256': sha256_of(facts)},
                             'plan': {'path': str(plan), 'sha256': sha256_of(plan)}, 'output': str(row)})
        run_jar(jar, java, request, receipt)
        scored.append(day)
    return scored


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    rp = commands.add_parser('replay')
    rp.add_argument('--run', required=True)
    rp.add_argument('--url', required=True)
    rp.add_argument('--token-file')
    rp.add_argument('--token-env', default='SCHEDULER_BACKTEST_TOKEN')
    rp.add_argument('--select', default='test')
    rp.add_argument('--rep', type=int, default=1)
    rp.add_argument('--concurrency', type=int, default=2)
    rp.add_argument('--timeout-s', type=int, default=900)
    sc = commands.add_parser('score')
    sc.add_argument('--run', required=True)
    sc.add_argument('--jar', required=True)
    sc.add_argument('--java', default='java')
    sc.add_argument('--select', default='all')
    sc.add_argument('--rep', type=int, default=1)
    args = parser.parse_args(argv)
    days = backtest_summary.select_days(args.run, args.select)
    if args.command == 'replay':
        token = Path(args.token_file).read_text(encoding='utf-8').strip() if args.token_file else os.environ.get(args.token_env, '')
        if not token:
            parser.error('a token is needed: --token-file or the token environment variable')
        outcomes = replay_days(args.run, days, args.rep, Replay(args.url, token, timeout_s=args.timeout_s), args.concurrency)
        statuses = {}
        for outcome in outcomes:
            statuses[outcome['status']] = statuses.get(outcome['status'], 0) + 1
        print(json.dumps({'days': len(outcomes), 'statuses': statuses, 'skipped': sum(o['skipped'] for o in outcomes)}))
        return 0
    scored = score_days(args.run, days, args.rep, Path(args.jar), args.java)
    print(json.dumps({'scored': len(scored)}))
    return 0


if __name__ == '__main__':
    sys.exit(main())
