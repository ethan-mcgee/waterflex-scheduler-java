"""The host side of the Part 2 simulation (research/backtest-paso-robles-2026-11/simulation-plan.md, "Host rules").

The simulator plays WaterFlex Software for one dealership, as the portal does: it owns the book (technician-days and
appointments), sends the public API snapshots of it, and writes the API's receipts back with a compare-and-set on every
listed technician-day's lastModified. This module is that book plus the portal's pure rules ported from TypeScript:

- the dynamic booking horizon (web/lib/bookingHorizonCore.ts, with bookingHorizon from web/lib/apiBookingCore.ts),
- the overnight dates (web/lib/overnightCore.ts) and the 6 a.m. freeze (web/lib/apiTimeOffCore.ts),
- the booking receipt check (checkReceipt in web/lib/apiBookingCore.ts) and the receipt write (web/lib/apiReceipt.ts),
- the request desk: each date's requests in a seeded order, and the no-offer rule (owner, 2026-11-05, decision 2).

The book holds local wall-clock minutes on the study's own dates. Only a snapshot moves them to instants on the API's
shifted dates (whole weeks, so weekdays and local times hold across daylight saving), and only a receipt moves them back.
Stdlib only; no network here (the API client is backtest_api.py).
"""
import datetime as dt
import json
import random
import uuid
from dataclasses import dataclass, field, asdict
from pathlib import Path
from zoneinfo import ZoneInfo

# The production horizon defaults (DYNAMIC, cap 15 weekdays, lead 2, full 85 percent).
HORIZON_RULE = {'capWeekdays': 15, 'leadWeekdays': 2, 'fullPercent': 85}
MIN_LEAD_WEEKDAYS, MAX_LEAD_WEEKDAYS = 1, 5
MIN_FULL_PERCENT, MAX_FULL_PERCENT = 50, 100
FREEZE_MINUTE = 360
# Every technician-day starts at this version; each write moves the days it lists to a later one. The scheduler reads
# lastModified as a version (nanoseconds since 1970, RequestBooking.version), so a counter is as good as a clock, and a
# resumed run sends byte-identical snapshots.
VERSION_BASE = dt.datetime(2026, 1, 1, tzinfo=dt.timezone.utc)
# The no-offer rule: a request without an offer is searched again on each following weekday, at most this many times.
RETRY_WEEKDAYS = 10
NAMESPACE = uuid.NAMESPACE_URL


class HostError(ValueError):
    """A broken invariant of the book or of an answer: the run stops rather than write a guess."""


class StaleReceipt(HostError):
    """A receipt computed from a lastModified the book no longer has: nothing is written."""


# ----------------------------------------------------------------------------------------------------------------------
# Calendar and instants


def date_of(value):
    return dt.date.fromisoformat(value) if isinstance(value, str) else value


def add_days(value, days):
    return (date_of(value) + dt.timedelta(days=days)).isoformat()


def is_weekday(value):
    return date_of(value).weekday() < 5


def parse_instant(value):
    """An ISO-8601 instant with an offset, as an aware datetime; anything else is refused."""
    if not isinstance(value, str):
        raise HostError(f'Not an instant: {value!r}')
    try:
        parsed = dt.datetime.fromisoformat(value)
    except ValueError:
        raise HostError(f'Not an instant: {value!r}') from None
    if parsed.tzinfo is None:
        raise HostError(f'An instant needs an offset: {value!r}')
    return parsed


def format_instant(moment):
    moment = moment.astimezone(dt.timezone.utc)
    text = moment.strftime('%Y-%m-%dT%H:%M:%S')
    return text + (f'.{moment.microsecond:06d}' if moment.microsecond else '') + 'Z'


def same_instant(a, b):
    return parse_instant(a) == parse_instant(b)


def local_instant(day, minute, zone):
    """The instant of a local wall-clock minute on a date (1440 is the next midnight). A minute inside a skipped hour
    moves forward, as the portal's localMinute does."""
    date, rest = date_of(day) + dt.timedelta(days=minute // 1440), minute % 1440
    return format_instant(dt.datetime.combine(date, dt.time(hour=rest // 60, minute=rest % 60), tzinfo=zone))


def local_minute(value, day, zone):
    """Minutes after local midnight of the date for an instant that must fall on that date (or exactly its next midnight)."""
    local = parse_instant(value).astimezone(zone)
    if local.second or local.microsecond:
        raise HostError(f'{value} is not on a whole minute')
    if local.date() == date_of(day):
        return local.hour * 60 + local.minute
    if local.date() == date_of(day) + dt.timedelta(days=1) and local.hour == 0 and local.minute == 0:
        return 1440
    raise HostError(f'{value} is not on {day} in {zone.key}')


def version_instant(version):
    return format_instant(VERSION_BASE + dt.timedelta(microseconds=version))


def frozen(service_date, now, zone):
    """A service day freezes at 6 a.m. local time, as the scheduler's ScheduleCutoff does."""
    return now >= parse_instant(local_instant(service_date, FREEZE_MINUTE, zone))


def today_in(zone, now):
    return now.astimezone(zone).date().isoformat()


# ----------------------------------------------------------------------------------------------------------------------
# The booking horizon (bookingHorizonCore.ts)


def booking_horizon(today, weekdays):
    """Every date from tomorrow through the weekdays-th weekday, weekends between included."""
    if not isinstance(weekdays, int) or isinstance(weekdays, bool) or not 1 <= weekdays <= 15:
        raise HostError(f'Invalid booking horizon of {weekdays} weekdays')
    dates, counted, day = [], 0, add_days(today, 1)
    while counted < weekdays:
        dates.append(day)
        if is_weekday(day):
            counted += 1
        day = add_days(day, 1)
    return dates


def check_rule(rule):
    for name, low, high in (('capWeekdays', 1, 15), ('leadWeekdays', MIN_LEAD_WEEKDAYS, MAX_LEAD_WEEKDAYS), ('fullPercent', MIN_FULL_PERCENT, MAX_FULL_PERCENT)):
        value = rule.get(name)
        if not isinstance(value, int) or isinstance(value, bool) or not low <= value <= high:
            raise HostError(f'Invalid {name} {value!r}')


def scan_window(today, rule):
    """The cap window a dynamic horizon is chosen from: the snapshot to scan covers exactly these dates."""
    check_rule(rule)
    return booking_horizon(today, rule['capWeekdays'])


def covered_minutes(start, end, windows):
    """Minutes of [start, end) covered by the windows, which may overlap each other."""
    clipped = sorted((max(start, parse_instant(w['start'])), min(end, parse_instant(w['end']))) for w in windows)
    total, reached = dt.timedelta(), start
    for begin, finish in clipped:
        if begin >= finish:
            continue
        begin = max(begin, reached)
        if finish > begin:
            total += finish - begin
            reached = finish
    return total / dt.timedelta(minutes=1)


def day_fullness(snapshot, date):
    """None when nobody works the date (no capacity), else (busy minutes, available minutes) over its technicians."""
    days = [day for day in snapshot['technicianDays'] if day['serviceDate'] == date]
    if not days:
        return None
    busy = available = 0.0
    for day in days:
        shift_start, shift_end = parse_instant(day['shift']['start']), parse_instant(day['shift']['end'])
        if shift_end <= shift_start:
            raise HostError(f'Technician {day["technicianId"]} has an empty shift on {date}')
        open_minutes = min(day['maxPaidMinutes'], (shift_end - shift_start) / dt.timedelta(minutes=1) - covered_minutes(shift_start, shift_end, day['absences']))
        if open_minutes <= 0:
            continue
        span_end = shift_start
        for appointment in snapshot['appointments']:
            if appointment['technicianId'] == day['technicianId'] and appointment['serviceDate'] == date:
                span_end = max(span_end, parse_instant(appointment['plannedStart']) + dt.timedelta(minutes=appointment['durationMinutes']))
        span = (span_end - shift_start) / dt.timedelta(minutes=1) - covered_minutes(shift_start, span_end, day['absences'])
        busy += min(open_minutes, max(0.0, span))
        available += open_minutes
    return None if available == 0 else (busy, available)


def dynamic_horizon(today, scan, rule):
    """{dates, lastFullDate, capped}: through leadWeekdays weekdays with capacity past the furthest full weekday."""
    window = scan_window(today, rule)
    inside = set(window)
    for day in scan['technicianDays']:
        if day['serviceDate'] not in inside:
            raise HostError(f'The scan has {day["serviceDate"]}, outside the window {window[0]} to {window[-1]}')
    capacity, last_full = [], 0
    for date in window:
        if not is_weekday(date):
            continue
        fill = day_fullness(scan, date)
        if fill is None:
            continue
        capacity.append(date)
        busy, available = fill
        if busy * 100 >= rule['fullPercent'] * available:
            last_full = len(capacity)
    last_full_date = capacity[last_full - 1] if last_full else None
    end_position = last_full + rule['leadWeekdays'] - 1
    if end_position >= len(capacity):
        return {'dates': window, 'lastFullDate': last_full_date, 'capped': True}
    end = capacity[end_position]
    return {'dates': [date for date in window if date <= end], 'lastFullDate': last_full_date, 'capped': False}


def last_booked_date(snapshot):
    dates = [appointment['serviceDate'] for appointment in snapshot['appointments']]
    return max(dates) if dates else None


# ----------------------------------------------------------------------------------------------------------------------
# Overnight (overnightCore.ts)

OVERNIGHT_WEEKDAYS = 10
BUSY_RETRIES = 5
MAX_BUSY_WAIT_MS = 5 * 60_000


def overnight_dates(now, zone, through=None):
    """From today, every date not yet frozen, until ten weekdays are covered, or through `through` when given."""
    dates, date, weekdays = [], today_in(zone, now), 0
    while (weekdays < OVERNIGHT_WEEKDAYS) if through is None else (date <= through):
        if not frozen(date, now, zone):
            dates.append(date)
            if is_weekday(date):
                weekdays += 1
        date = add_days(date, 1)
    return dates


def overnight_end(horizon_end, booked):
    return booked if booked is not None and booked > horizon_end else horizon_end


def busy_retry_delay_ms(attempt, retry_after_ms):
    """The wait before asking again after the attempt-th 429, or None to give up (overnightCore.busyRetryDelayMs)."""
    if not isinstance(attempt, int) or isinstance(attempt, bool) or attempt < 1:
        raise HostError(f'Invalid busy attempt {attempt!r}')
    if attempt > BUSY_RETRIES:
        return None
    wait = retry_after_ms if retry_after_ms is not None else 5000 * 2 ** (attempt - 1)
    return min(max(wait, 1000), MAX_BUSY_WAIT_MS)


# ----------------------------------------------------------------------------------------------------------------------
# The book


@dataclass
class TechnicianDay:
    technician: str
    date: str
    depot: str
    lat: float
    lng: float
    start: int  # local minutes
    end: int
    max_paid: int
    version: int = 0


@dataclass
class Appointment:
    id: str
    service: str
    duration: int
    lat: float
    lng: float
    date: str
    technician: str
    sequence: int
    window_start: int  # local minutes on date
    window_end: int
    planned_start: int
    planned_end: int


@dataclass
class Book:
    """One dealership's schedule as the host keeps it. A segment of the simulation owns one."""
    time_zone: str
    days: dict = field(default_factory=dict)  # (technician, date) -> TechnicianDay
    appointments: dict = field(default_factory=dict)  # id -> Appointment
    version: int = 0  # the last version handed out

    @property
    def zone(self):
        return ZoneInfo(self.time_zone)

    @classmethod
    def from_availability(cls, availability, first=None, last=None, weekends=True):
        """The technician-days of availability.json (backtest_import.py, Part 2 inputs) from first through last. Without
        weekends, the Saturday and Sunday technician-days are left out: the product has nobody to book or move there."""
        book = cls(time_zone=availability['timeZone'])
        for item in availability['technicianDays']:
            if (first is not None and item['date'] < first) or (last is not None and item['date'] > last):
                continue
            if not weekends and not is_weekday(item['date']):
                continue
            key = (item['technicianId'], item['date'])
            if key in book.days:
                raise HostError(f'availability lists {key} twice')
            start, end = (int(item[k][:2]) * 60 + int(item[k][3:]) for k in ('shiftStart', 'shiftEnd'))
            if not 0 <= start < end <= 1440 or not isinstance(item['maxPaidMinutes'], int) or item['maxPaidMinutes'] < 1:
                raise HostError(f'availability has a bad shift for {key}')
            book.days[key] = TechnicianDay(item['technicianId'], item['date'], item['depot'], item['location']['lat'], item['location']['lng'],
                                           start, end, item['maxPaidMinutes'])
        return book

    # -- reading ------------------------------------------------------------------------------------------------------

    def dates(self):
        return sorted({day.date for day in self.days.values()})

    def on(self, date):
        return sorted((day for day in self.days.values() if day.date == date), key=lambda d: d.technician)

    def booked_on(self, date):
        return sorted((a for a in self.appointments.values() if a.date == date), key=lambda a: (a.technician, a.sequence))

    def snapshot(self, dates, settings, shift_days=0):
        """The public API Snapshot of the dates (openapi-v1.yaml), on dates moved by shift_days; services every technician holds."""
        zone, dates = self.zone, sorted(set(dates))
        api = lambda date: add_days(date, shift_days)
        days = [day for date in dates for day in self.on(date)]
        technicians = sorted({day.technician for day in days})
        appointments = [a for date in dates for a in self.booked_on(date)]
        for appointment in appointments:
            if (appointment.technician, appointment.date) not in self.days:
                raise HostError(f'Appointment {appointment.id} is on {appointment.technician} {appointment.date}, which the book does not have')
        point = lambda d: {'lat': d.lat, 'lng': d.lng}
        return {'metroId': settings['metroId'], 'timeZone': self.time_zone, 'rates': dict(settings['rates']), 'policy': dict(settings['policy']),
                'technicians': [{'id': t, 'qualifications': list(settings['services'])} for t in technicians],
                'technicianDays': [{'technicianId': d.technician, 'serviceDate': api(d.date), 'lastModified': version_instant(d.version),
                                    'shift': {'start': local_instant(api(d.date), d.start, zone), 'end': local_instant(api(d.date), d.end, zone)},
                                    'absences': [], 'start': point(d), 'end': point(d), 'maxPaidMinutes': d.max_paid} for d in days],
                'appointments': [{'id': a.id, 'technicianId': a.technician, 'serviceDate': api(a.date), 'serviceId': a.service,
                                  'durationMinutes': a.duration,
                                  'window': {'start': local_instant(api(a.date), a.window_start, zone), 'end': local_instant(api(a.date), a.window_end, zone)},
                                  'location': {'lat': a.lat, 'lng': a.lng}, 'sequence': a.sequence,
                                  'plannedStart': local_instant(api(a.date), a.planned_start, zone)} for a in appointments]}

    def versions(self, dates, shift_days=0):
        return [{'technicianId': d.technician, 'serviceDate': add_days(d.date, shift_days), 'lastModified': version_instant(d.version)}
                for date in sorted(set(dates)) for d in self.on(date)]

    # -- writing ------------------------------------------------------------------------------------------------------

    def _listed(self, receipt, shift_days):
        """The receipt's technician-days as book keys, each once, compared with the book's versions (StaleReceipt on any change)."""
        listed = {}
        for item in receipt['technicianDays']:
            key = (item['technicianId'], add_days(item['serviceDate'], -shift_days))
            if key in listed:
                raise HostError(f'The receipt lists technician-day {key} twice')
            day = self.days.get(key)
            if day is None:
                raise StaleReceipt(f'The receipt lists {key}, which the book does not have')
            if not same_instant(version_instant(day.version), item['lastModified']):
                raise StaleReceipt(f'Technician-day {key} changed since the snapshot')
            listed[key] = day
        if not listed:
            raise HostError('Nothing to write: no technician-day is listed')
        return listed

    def _placements(self, receipt, listed, shift_days):
        zone, placed = self.zone, {}
        for item in receipt['assignments']:
            date = add_days(item['serviceDate'], -shift_days)
            key = (item['technicianId'], date)
            if key not in listed:
                raise HostError(f'Appointment {item["appointmentId"]} is on a technician-day the receipt does not list')
            if item['appointmentId'] in placed:
                raise HostError(f'The receipt assigns appointment {item["appointmentId"]} twice')
            start, end = local_minute(item['plannedStart'], item['serviceDate'], zone), local_minute(item['plannedEnd'], item['serviceDate'], zone)
            if end <= start:
                raise HostError(f'Appointment {item["appointmentId"]} ends before it starts')
            if not isinstance(item['sequence'], int) or item['sequence'] < 0:
                raise HostError(f'Appointment {item["appointmentId"]} has a bad sequence')
            placed[item['appointmentId']] = (item['technicianId'], date, item['sequence'], start, end)
        return placed

    def _write(self, listed, placed, created=None):
        """Every check passed: place the appointments, then move every listed technician-day to one new version."""
        for appointment_id, (technician, date, sequence, start, end) in placed.items():
            if created is not None and appointment_id == created.id:
                created.technician, created.date, created.sequence, created.planned_start, created.planned_end = technician, date, sequence, start, end
                self.appointments[created.id] = created
                continue
            appointment = self.appointments[appointment_id]
            appointment.technician, appointment.sequence, appointment.planned_start, appointment.planned_end = technician, sequence, start, end
        self.version += 1
        for day in listed.values():
            day.version = self.version

    def _check_complete(self, listed, placed, created=None):
        """Every live appointment on a listed technician-day is placed, and each placed one exists on its own date."""
        for appointment in self.appointments.values():
            if (appointment.technician, appointment.date) in listed and appointment.id not in placed:
                raise HostError(f'The receipt does not place appointment {appointment.id}, which is on one of its technician-days')
        for appointment_id, (_, date, _, _, _) in placed.items():
            if created is not None and appointment_id == created.id:
                continue
            existing = self.appointments.get(appointment_id)
            if existing is None or existing.date != date:
                raise StaleReceipt(f'Appointment {appointment_id} is not a current appointment on {date}')

    def apply_booking(self, receipt, job, offer, shift_days=0):
        """Writes a confirmed booking: the receipt is for the offer's one date, places the new job once, and the book has
        every listed technician-day at the receipt's version. Returns the new Appointment."""
        if job['id'] in self.appointments:
            raise StaleReceipt(f'Job {job["id"]} already has an appointment')
        zone, api_date = self.zone, offer['serviceDate']
        date = add_days(api_date, -shift_days)
        for item in receipt['technicianDays']:
            if item['serviceDate'] != api_date:
                raise HostError(f'The receipt lists {item["serviceDate"]}, but the booking is on {api_date}')
        listed = self._listed(receipt, shift_days)
        placed = self._placements(receipt, listed, shift_days)
        if job['id'] not in placed:
            raise HostError(f'The receipt does not assign the booked job {job["id"]}')
        created = Appointment(job['id'], job['serviceId'], job['durationMinutes'], job['location']['lat'], job['location']['lng'], date, '', 0,
                              local_minute(offer['window']['start'], api_date, zone), local_minute(offer['window']['end'], api_date, zone), 0, 0)
        self._check_complete(listed, placed, created)
        self._write(listed, placed, created)
        return created

    def apply_commit(self, receipt, shift_days=0):
        """Writes a committed daily proposal: the same compare-and-set, with every listed technician-day complete."""
        listed = self._listed(receipt, shift_days)
        placed = self._placements(receipt, listed, shift_days)
        self._check_complete(listed, placed)
        self._write(listed, placed)

    # -- checkpoints --------------------------------------------------------------------------------------------------

    def to_json(self):
        return {'timeZone': self.time_zone, 'version': self.version,
                'days': [asdict(d) for d in sorted(self.days.values(), key=lambda d: (d.date, d.technician))],
                'appointments': [asdict(a) for a in sorted(self.appointments.values(), key=lambda a: a.id)]}

    @classmethod
    def from_json(cls, document):
        book = cls(time_zone=document['timeZone'], version=document['version'])
        for item in document['days']:
            day = TechnicianDay(**item)
            book.days[(day.technician, day.date)] = day
        for item in document['appointments']:
            appointment = Appointment(**item)
            book.appointments[appointment.id] = appointment
        return book


# ----------------------------------------------------------------------------------------------------------------------
# The request desk


def request_id(run, segment, date, step, attempt=1):
    """The UUID v5 of run, segment, date, step and attempt: after a crash the same call replays the same answer."""
    return str(uuid.uuid5(NAMESPACE, f'{run}/{segment}/{date}/{step}/{attempt}'))


@dataclass
class Desk:
    """The requests not yet booked: each date's new requests in a seeded order after the callbacks due that date."""
    seed: int
    waiting: dict = field(default_factory=dict)  # request id -> {'next': date, 'retries': n, 'first': date}
    outcomes: dict = field(default_factory=dict)  # request id -> BOOKED | TURNED_AWAY | END_OF_DATA

    def due(self, date, new_ids, carried=()):
        """The requests to search on the date: callbacks due (in the order they first called), then requests carried in
        from before the simulation began (a backlog, oldest first, as (id, request date) pairs), then the date's new
        requests shuffled with a seed of the date, since the history does not record the order they arrived in."""
        callbacks = sorted((i for i, w in self.waiting.items() if w['next'] == date), key=lambda i: (self.waiting[i]['first'], i))
        backlog = [i for i, _ in sorted(carried, key=lambda pair: (pair[1], pair[0])) if i not in self.waiting and i not in self.outcomes]
        for i, first in carried:
            if i in backlog:
                self.waiting[i] = {'next': date, 'retries': 0, 'first': first}
        fresh = sorted(i for i in new_ids if i not in self.waiting and i not in self.outcomes)
        random.Random(f'{self.seed}/{date}').shuffle(fresh)
        for i in fresh:
            self.waiting[i] = {'next': date, 'retries': 0, 'first': date}
        return callbacks + backlog + fresh

    def booked(self, identifier):
        self.waiting.pop(identifier)
        self.outcomes[identifier] = 'BOOKED'

    def no_offer(self, identifier, date):
        """No offer on the date: called back the next weekday, or turned away after RETRY_WEEKDAYS callbacks."""
        state = self.waiting[identifier]
        if state['retries'] == RETRY_WEEKDAYS:
            self.waiting.pop(identifier)
            self.outcomes[identifier] = 'TURNED_AWAY'
            return 'TURNED_AWAY'
        state['retries'] += 1
        following = add_days(date, 1)
        while not is_weekday(following):
            following = add_days(following, 1)
        state['next'] = following
        return 'NO_OFFER'

    def end_of_data(self):
        """Requests still waiting when the roster ends: turned away at the end of the data, counted apart."""
        for identifier in sorted(self.waiting):
            self.outcomes[identifier] = 'END_OF_DATA'
        ended = sorted(self.waiting)
        self.waiting.clear()
        return ended

    def to_json(self):
        return {'seed': self.seed, 'waiting': self.waiting, 'outcomes': self.outcomes}

    @classmethod
    def from_json(cls, document):
        return cls(seed=document['seed'], waiting=dict(document['waiting']), outcomes=dict(document['outcomes']))


def booking_dates(book, today, rule=HORIZON_RULE, settings=None):
    """The dynamic booking horizon from today on the book, as clientHorizon computes it: dates on the study calendar."""
    scan = book.snapshot(scan_window(today, rule), settings or {'metroId': 'scan', 'rates': {}, 'policy': {}, 'services': []})
    return dynamic_horizon(today, scan, rule)


def overnight_plan(book, now, rule=HORIZON_RULE):
    """The dates an overnight run at `now` proposes, as runDates does for a dynamic horizon: today through the later of the
    horizon end and the last booked date of the scan window."""
    zone = book.zone
    today = today_in(zone, now)
    scan = book.snapshot(scan_window(today, rule), {'metroId': 'scan', 'rates': {}, 'policy': {}, 'services': []})
    decision = dynamic_horizon(today, scan, rule)
    end = overnight_end(decision['dates'][-1], last_booked_date(scan))
    return overnight_dates(now, zone, end)


def load_json(path):
    with Path(path).open(encoding='utf-8') as stream:
        return json.load(stream)
