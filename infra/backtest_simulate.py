"""Part 2 of the backtest: the product simulated on the dealership's request stream (simulation-plan.md, "Part 2").

The simulator is the host (backtest_host.py) calling the public API (backtest_api.py). The study's dates are split into
segments that run at once, each a thread with its own tenant, book, desk and date shift. A segment walks its request
dates in order; on each date R it books every request due (offers from R+1 through the dynamic horizon, the one offer
selected and confirmed, the receipt written by compare-and-set), then at 01:00 on R+1 it proposes every date of the
overnight run and commits each IMPROVED proposal. A checkpoint after every date lets a stopped run resume: request ids,
shuffles and versions are deterministic, so a resumed segment sends the same calls and the API replays its answers.

    python infra/backtest_simulate.py plan --run <run dir> --out <simulation dir> --segments 4 --warmup-weeks 3 [--until <date>]
    python infra/backtest_simulate.py run --config <simulation dir>/simulation.json --url http://127.0.0.1:18080 [--only s1,s2]

`plan` writes simulation.json (segments, date shifts chosen from today, the settings sent); `run` reads each segment's
token from the variable the config names (SIMULATION_TOKEN_<SEGMENT>), never from a file. Stdlib only.
"""
import argparse
import datetime as dt
import json
import os
import sys
import threading
import time
from pathlib import Path

import backtest_api as ba
import backtest_host as bh

GATE = 2  # booking calls in flight across every segment: the booking pool's capacity, so nothing waits in its queue
OVERNIGHT_MINUTE = 60  # the overnight run starts at 01:00 local on the day after the request date
STEPS = ('offers', 'select', 'confirm', 'daily', 'commit')


class SimulationError(RuntimeError):
    """A failure the run must not paper over: the segment stops with its last checkpoint intact."""


def write_json(path, document):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.part')
    with temporary.open('w', encoding='utf-8', newline='\n') as stream:
        json.dump(document, stream, indent=1, sort_keys=True)
        stream.write('\n')
    temporary.replace(path)


# ----------------------------------------------------------------------------------------------------------------------
# Planning segments


def monday(date):
    value = bh.date_of(date)
    return (value - dt.timedelta(days=value.weekday())).isoformat()


def segment_bounds(first, last, count):
    """count consecutive [first, end) service-date ranges of whole weeks from the Monday of first, the last ending after last."""
    start = bh.date_of(monday(first))
    weeks = -(-((bh.date_of(last) - start).days + 1) // 7)
    if count < 1 or count > weeks:
        raise SimulationError(f'Cannot split {weeks} weeks into {count} segments')
    edges = [start + dt.timedelta(weeks=round(k * weeks / count)) for k in range(count + 1)]
    return [(edges[k].isoformat(), edges[k + 1].isoformat()) for k in range(count)]


def shift_for(start, today, margin_days=7):
    """Whole weeks that move a segment's first simulated date at least margin_days past today."""
    weeks = max(0, -(-((bh.date_of(today) + dt.timedelta(days=margin_days) - bh.date_of(start)).days) // 7))
    return 7 * weeks


def plan(run, out, count, warmup_weeks, today, until=None, seed=17, only_first=None):
    """simulation.json for the run directory's Part 2 inputs. until cuts the simulation short (smoke, calibration)."""
    run, out = Path(run), Path(out)
    inputs = bh.load_json(run / 'part2' / 'inputs.json')
    requests = bh.load_json(run / 'part2' / 'requests.json')
    availability = bh.load_json(run / 'part2' / 'availability.json')
    config = bh.load_json(run / 'study-config.json')
    for name in ('requests.json', 'availability.json'):
        from backtest_import import sha256  # the importer's hash, so the check is the one inputs.json was written with
        if sha256(run / 'part2' / name) != inputs['files'][name]:
            raise SimulationError(f'part2/{name} differs from part2/inputs.json')
    history_last = availability['lastHistoryDate']
    first_service = min(r['dispatcher']['serviceDate'] for r in requests['requests'])
    bounds = segment_bounds(first_service, history_last, count)
    roster_end = max(d['date'] for d in availability['technicianDays'])
    segments = []
    for number, (first, end) in enumerate(bounds, start=1):
        start = first if number == 1 else bh.add_days(first, -7 * warmup_weeks)
        last_segment = number == len(bounds)
        stop = roster_end if last_segment else bh.add_days(end, -1)
        if until is not None:
            stop = min(stop, until)
        if stop < start:
            continue
        segments.append({'name': f's{number}', 'first': first, 'end': bh.add_days(roster_end, 1) if last_segment else end, 'start': start, 'stop': stop,
                         'last': last_segment, 'shiftDays': shift_for(start, today), 'tokenEnv': f'SIMULATION_TOKEN_S{number}'})
    if only_first is not None:
        segments = segments[:only_first]
    services = sorted({r['serviceId'] for r in requests['requests']})
    document = {'version': 1, 'simulationId': out.name, 'run': str(run.resolve()), 'inputs': inputs['files'], 'routingIdentity': inputs['routingIdentity'],
                'metroId': config['metroId'], 'timeZone': config['timeZone'], 'seed': seed, 'warmupWeeks': warmup_weeks, 'plannedOn': today,
                'horizonRule': dict(bh.HORIZON_RULE), 'bookingGate': GATE, 'retryWeekdays': bh.RETRY_WEEKDAYS,
                'settings': {'rates': dict(config['rates']), 'policy': dict(config['policy']), 'offerLimit': 1, 'windowHours': 2, 'services': services},
                'historyLastDate': history_last, 'rosterEnd': roster_end, 'until': until, 'segments': segments}
    write_json(out / 'simulation.json', document)
    return document


# ----------------------------------------------------------------------------------------------------------------------
# One segment


class Segment:
    def __init__(self, config, segment, api, gate, directory, requests, availability, log=print):
        self.config, self.segment, self.api, self.gate, self.log = config, segment, api, gate, log
        self.directory = Path(directory)
        self.name, self.shift = segment['name'], segment['shiftDays']
        self.settings = dict(config['settings'], metroId=config['metroId'])
        self.rule = config['horizonRule']
        self.requests = {r['id']: r for r in requests['requests']}
        start = segment['start']
        # Requests dated before the first simulated date that the dispatcher served on or after it are carried in: they
        # are searched on that date before its new requests, oldest first.
        self.calls, self.carried = {}, []
        for request in requests['requests']:
            called = request['requestDate']
            if called < start:
                if request['dispatcher']['serviceDate'] >= start:
                    self.carried.append((request['id'], called))
                continue
            if called <= segment['stop'] and (segment['last'] or called < segment['end']):
                self.calls.setdefault(called, []).append(request['id'])
        checkpoint = self.directory / 'checkpoint.json'
        if checkpoint.exists():
            saved = bh.load_json(checkpoint)
            if saved['segment'] != segment:
                raise SimulationError(f'{checkpoint} belongs to another plan of segment {self.name}')
            self.cursor, self.book, self.desk = saved['cursor'], bh.Book.from_json(saved['book']), bh.Desk.from_json(saved['desk'])
            self.trim_logs(self.cursor)
        else:
            self.cursor = start
            self.book = bh.Book.from_availability(availability, first=start)
            self.desk = bh.Desk(seed=config['seed'])

    # -- records ------------------------------------------------------------------------------------------------------

    def append(self, name, record):
        with (self.directory / name).open('a', encoding='utf-8', newline='\n') as stream:
            stream.write(json.dumps(record, sort_keys=True) + '\n')

    def trim_logs(self, cursor):
        """A resumed segment redoes its cursor date, so log lines from that date on are dropped first."""
        for name in ('bookings.jsonl', 'proposals.jsonl'):
            path = self.directory / name
            if path.exists():
                kept = [line for line in path.read_text(encoding='utf-8').splitlines() if line and json.loads(line)['date'] < cursor]
                path.write_text(''.join(line + '\n' for line in kept), encoding='utf-8', newline='\n')

    def save(self, cursor):
        write_json(self.directory / 'checkpoint.json', {'segment': self.segment, 'cursor': cursor, 'book': self.book.to_json(), 'desk': self.desk.to_json()})
        self.cursor = cursor

    def rid(self, date, step, key, attempt=1):
        if step not in STEPS:
            raise SimulationError(f'Unknown step {step}')
        return bh.request_id(self.config['simulationId'], self.name, date, f'{step}:{key}', attempt)

    def api_date(self, date):
        return bh.add_days(date, self.shift)

    # -- booking ------------------------------------------------------------------------------------------------------

    def gated(self, call):
        with self.gate:
            return call()

    def book_request(self, date, identifier):
        request, state = self.requests[identifier], self.desk.waiting[identifier]
        attempt = state['retries'] + 1
        horizon = bh.booking_dates(self.book, date, self.rule)
        dates = horizon['dates']
        job = {'id': identifier, 'serviceId': request['serviceId'], 'durationMinutes': request['durationMinutes'], 'location': dict(request['location'])}
        record = {'date': date, 'request': identifier, 'attempt': attempt, 'horizon': {'dates': len(dates), 'first': dates[0], 'last': dates[-1],
                  'lastFullDate': horizon['lastFullDate'], 'capped': horizon['capped']}}
        body = {'requestId': self.rid(date, 'offers', identifier, attempt), 'offerLimit': self.settings['offerLimit'], 'windowHours': self.settings['windowHours'],
                'snapshot': self.book.snapshot(dates, self.settings, self.shift), 'job': job,
                'horizon': {'firstDate': self.api_date(dates[0]), 'lastDate': self.api_date(dates[-1])}}
        started = time.monotonic()
        offer_set, calls = self.gated(lambda: self.api.offers(body))
        record.update({'elapsedMs': round((time.monotonic() - started) * 1000), 'calls': calls, 'searchComplete': offer_set['searchComplete'],
                       'refinement': offer_set.get('refinement'), 'refinementStop': offer_set.get('refinementStop'),
                       'regularWindowsFound': offer_set.get('regularWindowsFound'), 'incompleteDates': offer_set.get('incompleteDates'),
                       'skippedTechnicianDays': len(offer_set['skippedTechnicianDays'])})
        if not offer_set['offers']:
            record['outcome'] = self.desk.no_offer(identifier, date)
            return record
        offer = offer_set['offers'][0]
        study_date = bh.add_days(offer['serviceDate'], -self.shift)
        if study_date not in dates:
            raise SimulationError(f'{identifier}: the offer on {offer["serviceDate"]} is outside the horizon sent')
        hold, _ = self.gated(lambda: self.api.select(offer['offerId'], self.rid(date, 'select', identifier, attempt)))
        receipt, _ = self.gated(lambda: self.api.confirm(hold['holdId'], self.rid(date, 'confirm', identifier, attempt),
                                                         self.book.snapshot([study_date], self.settings, self.shift)))
        before = {a.id: (a.technician, a.sequence, a.planned_start) for a in self.book.booked_on(study_date)}
        created = self.book.apply_booking(receipt, job, offer, self.shift)
        self.desk.booked(identifier)
        moved = sorted(a.id for a in self.book.booked_on(study_date) if a.id in before and before[a.id] != (a.technician, a.sequence, a.planned_start))
        record.update({'outcome': 'BOOKED', 'offer': {'date': study_date, 'windowStart': created.window_start, 'windowEnd': created.window_end},
                       'technician': created.technician, 'leadDays': (bh.date_of(study_date) - bh.date_of(request['requestDate'])).days, 'moved': moved})
        return record

    # -- overnight ----------------------------------------------------------------------------------------------------

    def overnight(self, night):
        """The overnight run at 01:00 on night: every date of its range proposed, each IMPROVED proposal committed."""
        now = bh.parse_instant(bh.local_instant(night, OVERNIGHT_MINUTE, self.book.zone))
        for date in bh.overnight_plan(self.book, now, self.rule):
            record = {'date': bh.add_days(night, -1), 'night': night, 'serviceDate': date}
            if not self.book.on(date):
                self.append('proposals.jsonl', dict(record, outcome='NO_TECHNICIANS'))
                continue
            if not self.book.booked_on(date):
                self.append('proposals.jsonl', dict(record, outcome='EMPTY'))
                continue
            versions = self.book.versions([date], self.shift)
            body = {'requestId': self.rid(night, 'daily', date), 'serviceDate': self.api_date(date), 'snapshot': self.book.snapshot([date], self.settings, self.shift)}
            proposal, timing = self.api.propose(body, ba.proposal_limit_ms(self.settings['policy']))
            record.update({'outcome': 'PROPOSED', 'decision': proposal['decision'], 'reason': proposal['reason'], 'costCents': proposal['costCents'],
                           'unresolved': len(proposal['unresolvedAppointmentIds']), **timing})
            if proposal['decision'] == 'IMPROVED':
                receipt, _ = self.api.commit(proposal['proposalId'], self.rid(night, 'commit', date), versions)
                before = {a.id: (a.technician, a.sequence) for a in self.book.booked_on(date)}
                self.book.apply_commit(receipt, self.shift)
                record.update({'committed': True, 'moved': sum(1 for a in self.book.booked_on(date) if before[a.id] != (a.technician, a.sequence))})
            self.append('proposals.jsonl', record)

    # -- the walk -----------------------------------------------------------------------------------------------------

    def run(self, stopping=lambda: False):
        """Every request date from the cursor through stop; returns the segment's summary when it finishes."""
        self.directory.mkdir(parents=True, exist_ok=True)
        stop = self.segment['stop']
        while self.cursor <= stop:
            if stopping():
                return None
            date = self.cursor
            for identifier in self.desk.due(date, self.calls.get(date, []), self.carried if date == self.segment['start'] else ()):
                self.append('bookings.jsonl', self.book_request(date, identifier))
            self.overnight(bh.add_days(date, 1))
            self.save(bh.add_days(date, 1))
            if bh.date_of(date).weekday() == 6:
                self.log(f'{self.name} through {date}: {sum(1 for o in self.desk.outcomes.values() if o == "BOOKED")} booked, '
                         f'{len(self.desk.waiting)} waiting, {sum(1 for o in self.desk.outcomes.values() if o == "TURNED_AWAY")} turned away')
        ended = self.desk.end_of_data() if self.segment['last'] and self.segment['stop'] == self.config['rosterEnd'] else []
        summary = {'segment': self.name, 'finished': True, 'outcomes': dict(sorted(self.desk.outcomes.items())),
                   'waitingAtEnd': sorted(self.desk.waiting), 'endOfData': ended, 'appointments': len(self.book.appointments)}
        write_json(self.directory / 'segment.json', summary)
        return summary


# ----------------------------------------------------------------------------------------------------------------------
# The run


def run(config_path, url, only=None, client_factory=None, environ=os.environ, log=print):
    """Every segment of simulation.json at once, one thread each; a failure stops the others at their next date."""
    config_path = Path(config_path)
    config = bh.load_json(config_path)
    run_dir = Path(config['run'])
    from backtest_import import sha256
    for name, digest in config['inputs'].items():
        if sha256(run_dir / 'part2' / name) != digest:
            raise SimulationError(f'part2/{name} changed since the simulation was planned')
    requests, availability = bh.load_json(run_dir / 'part2' / 'requests.json'), bh.load_json(run_dir / 'part2' / 'availability.json')
    segments = [s for s in config['segments'] if only is None or s['name'] in only]
    gate = threading.BoundedSemaphore(config['bookingGate'])
    failed, results, threads = threading.Event(), {}, []

    def work(segment):
        try:
            token = environ.get(segment['tokenEnv'], '')
            api = client_factory(segment) if client_factory else ba.Client(url, token)
            if client_factory is None and api.whoami() == '':
                raise SimulationError(f'{segment["name"]}: the token names no tenant')
            directory = config_path.parent / 'segments' / segment['name']
            results[segment['name']] = Segment(config, segment, api, gate, directory, requests, availability, log).run(failed.is_set)
        except BaseException as error:  # noqa: BLE001 - recorded and re-raised after every thread stops
            failed.set()
            results[segment['name']] = error

    for segment in segments:
        thread = threading.Thread(target=work, args=(segment,), name=segment['name'])
        thread.start()
        threads.append(thread)
    for thread in threads:
        thread.join()
    errors = {name: result for name, result in results.items() if isinstance(result, BaseException)}
    if errors:
        for name, error in errors.items():
            log(f'{name} stopped: {error!r}')
        raise next(iter(errors.values()))
    return results


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    planner = commands.add_parser('plan')
    planner.add_argument('--run', required=True)
    planner.add_argument('--out', required=True)
    planner.add_argument('--segments', type=int, default=4)
    planner.add_argument('--warmup-weeks', type=int, default=3)
    planner.add_argument('--until')
    planner.add_argument('--only-first', type=int)
    planner.add_argument('--today', default=dt.date.today().isoformat())
    runner = commands.add_parser('run')
    runner.add_argument('--config', required=True)
    runner.add_argument('--url', required=True)
    runner.add_argument('--only')
    args = parser.parse_args(argv)
    if args.command == 'plan':
        document = plan(args.run, args.out, args.segments, args.warmup_weeks, args.today, args.until, only_first=args.only_first)
        print(json.dumps([{k: s[k] for k in ('name', 'start', 'first', 'end', 'stop', 'shiftDays')} for s in document['segments']]))
        return 0
    results = run(args.config, args.url, set(args.only.split(',')) if args.only else None)
    print(json.dumps({name: {k: v for k, v in result.items() if k != 'outcomes'} if result else None for name, result in results.items()}))
    return 0


if __name__ == '__main__':
    sys.exit(main())
