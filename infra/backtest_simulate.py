"""Part 2 of the backtest: the product simulated on the dealership's request stream (simulation-plan.md, "Part 2").

The simulator is the host (backtest_host.py) calling the public API (backtest_api.py). The study's dates are split into
segments that run at once, each a thread with its own tenant, book, desk and date shift. A segment walks its request
dates in order; on each date R it books every request due (offers from R+1 through the dynamic horizon, the one offer
selected and confirmed, the receipt written by compare-and-set), then at 01:00 on R+1 it proposes every date of the
overnight run and commits each IMPROVED proposal. A checkpoint after every date lets a stopped run resume: request ids,
shuffles and versions are deterministic, so a resumed segment sends the same calls and the API replays its answers.

A plan also fixes two things about the product's side (run_options): whether its roster keeps the history's weekend
technician-days (--no-weekend-work leaves them out, so nothing is booked or moved onto a Saturday or Sunday), and how
many of a night's proposals are in flight at once (--overnight-in-flight). A night's dates are independent of each
other: each proposal reads one date's snapshot and its commit writes that date only, so the answers are applied in date
order and the book ends the same whatever the number in flight.

It fixes two about the client's side too (offer_options): how many windows a search offers (--offer-limit, 1 to the
API's 4) and which one the client takes (--client-pick). 'first' takes the one offer of a search with offerLimit 1, the
least added cost; 'random' takes one of the offers shown, chosen with a seed of the request, its date and its attempt,
so a resumed run takes the same one. The API sorts several offers by window start, not by cost, so 'first' is refused
with more than one.

    python infra/backtest_simulate.py plan --run <run dir> --out <simulation dir> --segments 4 --warmup-weeks 3 [--until <date>]
                                           [--no-weekend-work] [--overnight-in-flight 4] [--offer-limit 4 --client-pick random]
    python infra/backtest_simulate.py window --run <run dir> --out <simulation dir> --first <date> --end <date> --copies a:3,b:3,c:6,d:6
    python infra/backtest_simulate.py busiest --run <run dir> --year 2024
    python infra/backtest_simulate.py run --config <simulation dir>/simulation.json --url http://127.0.0.1:18080 [--only s1,s2]

`plan` writes simulation.json (segments, date shifts chosen from today, the settings sent); `window` plans the
calibration month instead: the same dates simulated by named copies, each with its own warm-up and tenant; `busiest`
names the month the calibration uses. `run` reads each segment's token from the variable the config names
(SIMULATION_TOKEN_<SEGMENT>), never from a file; the tenant of a segment is fixed by the plan, so a resumed run issues a
new token for the same tenant and the API replays that tenant's answers. Stdlib only.
"""
import argparse
import datetime as dt
import json
import os
import random
import sys
import threading
import re
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import backtest_api as ba
import backtest_host as bh

GATE = 2  # booking calls in flight across every segment: the booking pool's capacity, so nothing waits in its queue
OVERNIGHT_MINUTE = 60  # the overnight run starts at 01:00 local on the day after the request date
STEPS = ('offers', 'select', 'confirm', 'daily', 'commit')
MAX_IN_FLIGHT = 8  # a night's proposals in flight at once; more than the daily pool can run only wait in its queue
MAX_OFFERS = 4  # the most offers a search returns (the API's BookingOffersRequest.offerLimit)
PICKS = ('first', 'random')  # how the simulated client chooses among the offers shown
VERSIONS = (1, 2, 3)
TENANT = re.compile('[a-z0-9][a-z0-9-]{0,62}')


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


def tenant_of(simulation_id, name):
    tenant = f'bt-{simulation_id}-{name}'.lower()
    if not TENANT.fullmatch(tenant):
        raise SimulationError(f'{tenant} is not a tenant id ([a-z0-9][a-z0-9-]{{0,62}}); choose a shorter simulation directory name')
    return tenant


def inputs_of(run):
    run = Path(run)
    from backtest_import import sha256  # the importer's hash, so the check is the one inputs.json was written with
    inputs = bh.load_json(run / 'part2' / 'inputs.json')
    for name in ('requests.json', 'availability.json'):
        if sha256(run / 'part2' / name) != inputs['files'][name]:
            raise SimulationError(f'part2/{name} differs from part2/inputs.json')
    return (inputs, bh.load_json(run / 'part2' / 'requests.json'), bh.load_json(run / 'part2' / 'availability.json'),
            bh.load_json(run / 'study-config.json'))


def check_options(weekend_work, in_flight):
    if not isinstance(weekend_work, bool):
        raise SimulationError(f'weekendWork is {weekend_work!r}, not true or false')
    if not isinstance(in_flight, int) or isinstance(in_flight, bool) or not 1 <= in_flight <= MAX_IN_FLIGHT:
        raise SimulationError(f'overnightInFlight is {in_flight!r}, not 1 to {MAX_IN_FLIGHT}')
    return weekend_work, in_flight


def check_offers(offer_limit, client_pick):
    if not isinstance(offer_limit, int) or isinstance(offer_limit, bool) or not 1 <= offer_limit <= MAX_OFFERS:
        raise SimulationError(f'offerLimit is {offer_limit!r}, not 1 to {MAX_OFFERS}')
    if client_pick not in PICKS:
        raise SimulationError(f'clientPick is {client_pick!r}, not one of {", ".join(PICKS)}')
    if client_pick == 'first' and offer_limit != 1:
        raise SimulationError(f'clientPick first takes offerLimit 1, not {offer_limit}: the API sorts several offers by window start, '
                              'so the first is not the least cost')
    return offer_limit, client_pick


def version_of(config):
    version = config.get('version')
    if version not in VERSIONS:
        raise SimulationError(f'simulation.json has version {version!r}; this simulator reads versions {", ".join(map(str, VERSIONS))}')
    return version


def run_options(config):
    """(weekend work, overnight proposals in flight) of a plan. A version 1 plan was written before either could be
    chosen and ran with the history's weekend technician-days, one proposal at a time; versions 2 and 3 state both."""
    if version_of(config) == 1:
        return True, 1
    # A plan without one of the two is refused by name rather than read as a default.
    return check_options(config.get('weekendWork'), config.get('overnightInFlight'))


def offer_options(config):
    """(offers a search returns, the client's pick) of a plan. Versions 1 and 2 were written before the pick could be
    chosen: their settings ask for one offer, and the client takes it. A version 3 plan states both."""
    version = version_of(config)
    settings = config.get('settings')
    if not isinstance(settings, dict):
        raise SimulationError(f'simulation.json has settings {settings!r}, not an object')
    if version < 3:
        return check_offers(settings.get('offerLimit'), 'first')
    return check_offers(settings.get('offerLimit'), config.get('clientPick'))


def pick_of(seed, date, identifier, attempt, count):
    """The index of the offer a 'random' client takes from count offers, seeded by the request so a rerun takes the same."""
    if not isinstance(count, int) or isinstance(count, bool) or count < 1:
        raise SimulationError(f'{identifier}: cannot pick from {count!r} offers')
    return random.Random(f'{seed}/{date}/{identifier}/{attempt}').randrange(count)


def roster_end_of(availability, weekend_work):
    """The last date of the roster the product books on."""
    dates = [d['date'] for d in availability['technicianDays'] if weekend_work or bh.is_weekday(d['date'])]
    if not dates:
        raise SimulationError('The roster has no technician-day the product may book')
    return max(dates)


def document_of(run, out, config, inputs, requests, availability, segments, seed, warmup, today, until, kind, weekend_work=True, in_flight=1,
                offer_limit=1, client_pick='first'):
    weekend_work, in_flight = check_options(weekend_work, in_flight)
    offer_limit, client_pick = check_offers(offer_limit, client_pick)
    return {'version': 3, 'kind': kind, 'simulationId': Path(out).name, 'run': str(Path(run).resolve()), 'inputs': inputs['files'],
            'routingIdentity': inputs['routingIdentity'], 'metroId': config['metroId'], 'timeZone': config['timeZone'], 'seed': seed, 'warmupWeeks': warmup,
            'plannedOn': today, 'horizonRule': dict(bh.HORIZON_RULE), 'bookingGate': GATE, 'retryWeekdays': bh.RETRY_WEEKDAYS,
            'weekendWork': weekend_work, 'overnightInFlight': in_flight, 'clientPick': client_pick,
            'settings': {'rates': dict(config['rates']), 'policy': dict(config['policy']), 'offerLimit': offer_limit, 'windowHours': 2,
                         'services': sorted({r['serviceId'] for r in requests['requests']})},
            'historyLastDate': availability['lastHistoryDate'], 'rosterEnd': roster_end_of(availability, weekend_work), 'until': until,
            'segments': segments}


def plan(run, out, count, warmup_weeks, today, until=None, seed=17, only_first=None, weekend_work=True, in_flight=1, offer_limit=1,
         client_pick='first'):
    """simulation.json for the run directory's Part 2 inputs. until cuts the simulation short (smoke, calibration). One
    segment is the whole study from the dispatcher's backlog on its first date: no warm-up applies, and none is recorded."""
    run, out = Path(run), Path(out)
    inputs, requests, availability, config = inputs_of(run)
    weekend_work, in_flight = check_options(weekend_work, in_flight)
    offer_limit, client_pick = check_offers(offer_limit, client_pick)
    history_last = availability['lastHistoryDate']
    first_service = min(r['dispatcher']['serviceDate'] for r in requests['requests'])
    bounds = segment_bounds(first_service, history_last, count)
    roster_end = roster_end_of(availability, weekend_work)
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
                         'last': last_segment, 'shiftDays': shift_for(start, today), 'tokenEnv': f'SIMULATION_TOKEN_S{number}',
                         'tenantId': tenant_of(out.name, f's{number}')})
    if only_first is not None:
        segments = segments[:only_first]
    document = document_of(run, out, config, inputs, requests, availability, segments, seed, None if count == 1 else warmup_weeks, today, until, 'study',
                           weekend_work, in_flight, offer_limit, client_pick)
    write_json(out / 'simulation.json', document)
    return document


def window(run, out, first, end, copies, today, seed=17):
    """The calibration month: the service dates [first, end) simulated by each named copy with its own warm-up in weeks."""
    run, out = Path(run), Path(out)
    inputs, requests, availability, config = inputs_of(run)
    segments = []
    for name, weeks in copies:
        start = bh.add_days(first, -7 * weeks)
        segments.append({'name': name, 'first': first, 'end': end, 'start': start, 'stop': bh.add_days(end, -1), 'last': False,
                         'shiftDays': shift_for(start, today), 'tokenEnv': f'SIMULATION_TOKEN_{name.upper()}', 'tenantId': tenant_of(out.name, name),
                         'warmupWeeks': weeks})
    document = document_of(run, out, config, inputs, requests, availability, segments, seed, None, today, None, 'window')
    write_json(out / 'simulation.json', document)
    return document


def busiest(run, year):
    """[first, end) of the year's month with the most dispatcher jobs per working weekday (simulation-plan.md, Calibration month)."""
    index = bh.load_json(Path(run) / 'variants' / 'as-run' / 'index.json')
    jobs, weekdays = Counter(), Counter()
    for entry in index['days']:
        if entry['year'] == year and bh.is_weekday(entry['day']):
            jobs[entry['day'][:7]] += entry['jobs']
            weekdays[entry['day'][:7]] += 1
    if not jobs:
        raise SimulationError(f'No weekday of {year} in the run')
    month = max(jobs, key=lambda m: (jobs[m] / weekdays[m], m))
    first = f'{month}-01'
    end = (bh.date_of(first).replace(day=28) + dt.timedelta(days=4)).replace(day=1).isoformat()
    return {'month': month, 'first': first, 'end': end, 'jobsPerWeekday': round(jobs[month] / weekdays[month], 2), 'weekdays': weekdays[month]}


# ----------------------------------------------------------------------------------------------------------------------
# One segment


class Segment:
    def __init__(self, config, segment, api, gate, directory, requests, availability, log=print):
        self.config, self.segment, self.api, self.gate, self.log = config, segment, api, gate, log
        self.directory = Path(directory)
        self.name, self.shift = segment['name'], segment['shiftDays']
        self.settings = dict(config['settings'], metroId=config['metroId'])
        self.rule = config['horizonRule']
        self.weekend_work, self.in_flight = run_options(config)
        self.offer_limit, self.client_pick = offer_options(config)
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
            self.elapsed = saved['elapsedSeconds']
        else:
            self.cursor, self.elapsed = start, 0.0
            self.book = bh.Book.from_availability(availability, first=start, weekends=self.weekend_work)
            self.desk = bh.Desk(seed=config['seed'])
        # A segment stopped before its first checkpoint starts again here with that date's lines already logged.
        self.trim_logs(self.cursor)
        if not self.weekend_work:
            # A checkpoint names its segment, not its roster, so a book saved under a plan with weekend work is caught here.
            weekend = sorted({day.date for day in self.book.days.values() if not bh.is_weekday(day.date)})
            if weekend:
                raise SimulationError(f'Segment {self.name}: the plan has no weekend work, but its book has technician-days on {weekend[0]}')

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

    def save(self, cursor, seconds):
        """The checkpoint after a date, with the wall time spent so far (every invocation's, summed)."""
        self.elapsed += seconds
        write_json(self.directory / 'checkpoint.json', {'segment': self.segment, 'cursor': cursor, 'book': self.book.to_json(), 'desk': self.desk.to_json(),
                                                        'elapsedSeconds': round(self.elapsed, 3)})
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
        offers = offer_set['offers']
        if not isinstance(offers, list) or len(offers) > self.offer_limit:
            raise SimulationError(f'{identifier}: the API answered {offers!r:.200} for offerLimit {self.offer_limit}')
        record['offersShown'] = len(offers)
        if not offers:
            record['outcome'] = self.desk.no_offer(identifier, date)
            return record
        offered = []
        for shown in offers:
            shown_date = bh.add_days(shown['serviceDate'], -self.shift)
            if shown_date not in dates:
                raise SimulationError(f'{identifier}: the offer on {shown["serviceDate"]} is outside the horizon sent')
            offered.append({'date': shown_date, 'windowStart': bh.local_minute(shown['window']['start'], shown['serviceDate'], self.book.zone),
                            'windowEnd': bh.local_minute(shown['window']['end'], shown['serviceDate'], self.book.zone)})
        picked = 0 if self.client_pick == 'first' else pick_of(self.config['seed'], date, identifier, attempt, len(offers))
        offer, study_date = offers[picked], offered[picked]['date']
        record.update({'picked': picked, 'offered': offered})
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

    def proposed(self, bodies):
        """Each body's (proposal, timing), in the order of bodies, with at most in_flight proposals waiting on the API at once.
        A failed proposal is raised once the ones already sent have answered; the date is then redone from its checkpoint."""
        limit = ba.proposal_limit_ms(self.settings['policy'])
        if self.in_flight == 1 or len(bodies) < 2:
            return [self.api.propose(body, limit) for body in bodies]
        with ThreadPoolExecutor(max_workers=self.in_flight, thread_name_prefix=f'{self.name}-night') as pool:
            return list(pool.map(lambda body: self.api.propose(body, limit), bodies))

    def overnight(self, night):
        """The overnight run at 01:00 on night: every date of its range proposed, each IMPROVED proposal committed. Every
        proposal is asked from the book as the day's bookings left it, then the answers are written in date order."""
        now = bh.parse_instant(bh.local_instant(night, OVERNIGHT_MINUTE, self.book.zone))
        records, asked = [], []
        for date in bh.overnight_plan(self.book, now, self.rule):
            record = {'date': bh.add_days(night, -1), 'night': night, 'serviceDate': date}
            records.append(record)
            if not self.book.on(date):
                record['outcome'] = 'NO_TECHNICIANS'
            elif not self.book.booked_on(date):
                record['outcome'] = 'EMPTY'
            else:
                body = {'requestId': self.rid(night, 'daily', date), 'serviceDate': self.api_date(date),
                        'snapshot': self.book.snapshot([date], self.settings, self.shift)}
                asked.append((record, self.book.versions([date], self.shift), body))
        answers = self.proposed([body for _, _, body in asked])
        for (record, versions, _), (proposal, timing) in zip(asked, answers, strict=True):
            date = record['serviceDate']
            record.update({'outcome': 'PROPOSED', 'decision': proposal['decision'], 'reason': proposal['reason'], 'costCents': proposal['costCents'],
                           'unresolved': len(proposal['unresolvedAppointmentIds']), **timing})
            if proposal['decision'] == 'IMPROVED':
                receipt, _ = self.api.commit(proposal['proposalId'], self.rid(night, 'commit', date), versions)
                before = {a.id: (a.technician, a.sequence) for a in self.book.booked_on(date)}
                self.book.apply_commit(receipt, self.shift)
                record.update({'committed': True, 'moved': sum(1 for a in self.book.booked_on(date) if before[a.id] != (a.technician, a.sequence))})
        for record in records:
            self.append('proposals.jsonl', record)

    # -- the walk -----------------------------------------------------------------------------------------------------

    def run(self, stopping=lambda: False):
        """Every request date from the cursor through stop; returns the segment's summary when it finishes."""
        self.directory.mkdir(parents=True, exist_ok=True)
        stop = self.segment['stop']
        while self.cursor <= stop:
            if stopping():
                return None
            date, started = self.cursor, time.monotonic()
            for identifier in self.desk.due(date, self.calls.get(date, []), self.carried if date == self.segment['start'] else ()):
                self.append('bookings.jsonl', self.book_request(date, identifier))
            self.overnight(bh.add_days(date, 1))
            self.save(bh.add_days(date, 1), time.monotonic() - started)
            if bh.date_of(date).weekday() == 6:
                self.log(f'{self.name} through {date}: {sum(1 for o in self.desk.outcomes.values() if o == "BOOKED")} booked, '
                         f'{len(self.desk.waiting)} waiting, {sum(1 for o in self.desk.outcomes.values() if o == "TURNED_AWAY")} turned away')
        ended = self.desk.end_of_data() if self.segment['last'] and self.segment['stop'] == self.config['rosterEnd'] else []
        summary = {'segment': self.name, 'finished': True, 'outcomes': dict(sorted(self.desk.outcomes.items())),
                   'waitingAtEnd': sorted(self.desk.waiting), 'endOfData': ended, 'appointments': len(self.book.appointments),
                   'elapsedSeconds': round(self.elapsed, 3), 'simulatedDays': (bh.date_of(stop) - bh.date_of(self.segment['start'])).days + 1}
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
    planner.add_argument('--no-weekend-work', action='store_true', help="leave the history's weekend technician-days out of the product's roster")
    planner.add_argument('--overnight-in-flight', type=int, default=1, help="a night's proposals in flight at once")
    planner.add_argument('--offer-limit', type=int, default=1, help=f'the windows a search offers, 1 to {MAX_OFFERS}')
    planner.add_argument('--client-pick', choices=PICKS, default='first', help="the offer the client takes: 'first' (offerLimit 1) or 'random'")
    planner.add_argument('--today', default=dt.date.today().isoformat())
    windowed = commands.add_parser('window')
    windowed.add_argument('--run', required=True)
    windowed.add_argument('--out', required=True)
    windowed.add_argument('--first', required=True)
    windowed.add_argument('--end', required=True)
    windowed.add_argument('--copies', default='a:3,b:3,c:6,d:6')
    windowed.add_argument('--today', default=dt.date.today().isoformat())
    busy = commands.add_parser('busiest')
    busy.add_argument('--run', required=True)
    busy.add_argument('--year', type=int, default=2024)
    runner = commands.add_parser('run')
    runner.add_argument('--config', required=True)
    runner.add_argument('--url', required=True)
    runner.add_argument('--only')
    args = parser.parse_args(argv)
    if args.command == 'plan':
        document = plan(args.run, args.out, args.segments, args.warmup_weeks, args.today, args.until, only_first=args.only_first,
                        weekend_work=not args.no_weekend_work, in_flight=args.overnight_in_flight, offer_limit=args.offer_limit, client_pick=args.client_pick)
        print(json.dumps({'weekendWork': document['weekendWork'], 'overnightInFlight': document['overnightInFlight'],
                          'offerLimit': document['settings']['offerLimit'], 'clientPick': document['clientPick'], 'rosterEnd': document['rosterEnd'],
                          'segments': [{k: s[k] for k in ('name', 'start', 'first', 'end', 'stop', 'shiftDays')} for s in document['segments']]}))
        return 0
    if args.command == 'window':
        copies = [(item.split(':')[0], int(item.split(':')[1])) for item in args.copies.split(',')]
        document = window(args.run, args.out, args.first, args.end, copies, args.today)
        print(json.dumps([{k: s[k] for k in ('name', 'start', 'first', 'end', 'shiftDays', 'tenantId')} for s in document['segments']]))
        return 0
    if args.command == 'busiest':
        print(json.dumps(busiest(args.run, args.year)))
        return 0
    results = run(args.config, args.url, set(args.only.split(',')) if args.only else None)
    print(json.dumps({name: {k: v for k, v in result.items() if k != 'outcomes'} if result else None for name, result in results.items()}))
    return 0


if __name__ == '__main__':
    sys.exit(main())
