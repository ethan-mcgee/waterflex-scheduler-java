"""Turn a dealership's work-order history (CSV) into one replayable day per service date for the backtest.

Each day gets a sealed-ready DailyDataset draft (the engine's own input, road legs included), the dispatcher's plan as
planned and as executed (BacktestTool plan files), a public API daily proposal request on a shifted date, and the
observed times. Rows the rules exclude or flag go to a ledger with a reason, never silently. Nothing here keeps an
account number or a street: technicians and visits get pseudonymous ids, and only coordinates leave the CSV.

    python infra/backtest_import.py build --csv <history.csv> --config <study-config.json> --out <run dir>
        [--roads http://127.0.0.1:18001] [--token-env ROUTING_AUTH_TOKEN] [--corrections <geocode-corrections.json>]
    python infra/backtest_import.py geocode --csv <history.csv> --config <study-config.json> --out <corrections.json>
        --nominatim http://127.0.0.1:18082
    python infra/backtest_import.py inputs --csv <history.csv> --config <run dir>/study-config.json --out <run dir>
        [--roads ...] [--token-env ...] [--corrections ...]

build also writes Part 2's inputs (part2/requests.json and part2/availability.json); inputs writes only those, from the
same rules, so a run whose days are already scored gains them without rewriting a day file.

Stdlib only. The road client is the development routing service's POST /internal/legs; a build without --roads refuses
to write datasets, since a day without legs cannot be scored.
"""
import argparse
import copy
import csv
import datetime as dt
import hashlib
import json
import math
import os
import sys
import urllib.parse
import urllib.request
import uuid
from fractions import Fraction
from collections import Counter, defaultdict
from pathlib import Path
from zoneinfo import ZoneInfo

COLUMNS = ['account_number', 'house_number', 'street', 'city', 'state', 'lattitude', 'longitude', 'repair_req_date',
           'complaint_1_c', 'cmpl_1_desc', 'complaint_2_c', 'cmpl_2_desc', 'complaint_3_c', 'cmpl_3_desc', 'sched_fix_date',
           'actual_fix_date', 'sched_emp_id', 'actual_emp_id', 'sched_ampm_f', 'sched_hour', 'sched_minute', 'sched_duration',
           'act_strt_ampm_f', 'actual_start_hr', 'actual_start_mn', 'act_end_ampm_f', 'actual_end_hr', 'actual_end_mn']
VERSIONS = {'policy': 'zero-overtime-four-hour-v2', 'cost': 'exact-fleet-half-up-v3', 'score': 'bendable-decimal-repair-v4'}
EXCLUDE = 'exclude'
FLAG = 'flag'
LEG_BATCH = 256
# The shift rules a run is built under (owner, 2026-11-03): strict-17 ends every technician-day at the regular end;
# evening-sales gives the later end only to a technician the dispatcher gave a sales call that cannot finish by the
# regular end, and keeps that call with them; sales-day gives every technician the later end on any day with a sales call.
# as-run (owner, 2026-11-04) is the dispatcher's day as it ran: every kept job at its booked time, nothing held out, the
# shift ending at the regular end so that the work past it reads as overtime, and windows running to the later end.
RULE_SETS = ('strict-17', 'evening-sales', 'sales-day', 'as-run')
ROSTERS = ('spans', 'csv-days')


class ImportError_(ValueError):
    """A problem in the CSV or the configuration that the importer refuses to paper over."""


# ----------------------------------------------------------------------------------------------------------------------
# Configuration


def load_config(path):
    with Path(path).open(encoding='utf-8') as stream:
        return validate_config(json.load(stream))


def depot_located(depot):
    return all(isinstance(depot.get(k), (int, float)) and not isinstance(depot.get(k), bool) for k in ('lat', 'lng'))


def depot_addressed(depot):
    address = depot.get('address')
    return isinstance(address, dict) and all(isinstance(address.get(k), str) and address[k].strip() and not address[k].startswith('<')
                                             for k in ('street', 'city', 'state', 'postalCode'))


def validate_config(config):
    required = {'runId', 'timeZone', 'metroId', 'bbox', 'years', 'calibrationYears', 'shift', 'windowMinutes',
                'salesCallCodes', 'zeroDurationMinutes', 'excludeTechnicians', 'rates', 'policy', 'search', 'apiDateShiftDays',
                'ruleSets', 'primaryRuleSet'}
    missing = required - set(config)
    if missing:
        raise ImportError_(f'Study config lacks {sorted(missing)}')
    ZoneInfo(config['timeZone'])
    # One depot for everyone (depot), or named depots with each technician's dated assignment (depots, technicianDepots).
    if ('depot' in config) == ('depots' in config):
        raise ImportError_('Study config needs exactly one of depot (one for everyone) or depots with technicianDepots')
    if 'depot' in config:
        if 'technicianDepots' in config:
            raise ImportError_('Study config technicianDepots needs depots, not depot')
        named = {'depot': config['depot']}
    else:
        named = config['depots']
        if not isinstance(named, dict) or not named:
            raise ImportError_('Study config depots must name at least one depot')
        validate_assignments(config.get('technicianDepots'), set(named))
    for name, depot in named.items():
        if not isinstance(depot, dict) or not (depot_located(depot) or depot_addressed(depot)):
            raise ImportError_(f'Study config depot {name} needs lat and lng, or an address (street, city, state, postalCode) to geocode')
    roster_rule = config.get('roster', 'spans')
    if roster_rule not in ROSTERS:
        raise ImportError_(f'Study config roster must be one of {list(ROSTERS)}')
    limit = config.get('maxBookedMinutes')
    if limit is not None and (not isinstance(limit, int) or isinstance(limit, bool) or limit < 1):
        raise ImportError_('Study config maxBookedMinutes must be null (no limit) or a positive integer')
    validate_extension(config.get('availabilityExtension'))
    bbox = config['bbox']
    if not (bbox['west'] < bbox['east'] and bbox['south'] < bbox['north']):
        raise ImportError_('Study config bbox is out of order')
    shift = config['shift']
    for key in ('start', 'end', 'salesCallEnd'):
        dt.time.fromisoformat(shift[key])
    if not (dt.time.fromisoformat(shift['start']) < dt.time.fromisoformat(shift['end']) <= dt.time.fromisoformat(shift['salesCallEnd'])):
        raise ImportError_('Study config shift times are out of order')
    if not isinstance(config['windowMinutes'], int) or config['windowMinutes'] < 1:
        raise ImportError_('Study config windowMinutes must be a positive integer')
    zero = config['zeroDurationMinutes']
    if zero is not None and (not isinstance(zero, int) or zero < 1):
        raise ImportError_('Study config zeroDurationMinutes must be null (exclude) or a positive integer')
    shift_days = config['apiDateShiftDays']
    if shift_days is not None and (not isinstance(shift_days, int) or shift_days % 7 != 0):
        raise ImportError_('Study config apiDateShiftDays must be null (chosen at build time) or a whole number of weeks')
    rates = config['rates']
    for key in ('regularHourly', 'overtimeHourly', 'mileagePerMile', 'travelBufferPct'):
        if not isinstance(rates[key], str):
            raise ImportError_(f'Study config rates.{key} must be a decimal string')
    if not isinstance(rates['travelBufferMinutes'], int):
        raise ImportError_('Study config rates.travelBufferMinutes must be an integer')
    buffer = rates['arrivalBuffer']
    if set(buffer) != {'percent', 'minMinutes', 'maxMinutes'}:
        raise ImportError_('Study config rates.arrivalBuffer needs percent, minMinutes and maxMinutes')
    search = config['search']
    if set(search) != {'variant', 'seed', 'remainingMillis'}:
        raise ImportError_('Study config search needs variant, seed and remainingMillis')
    rule_sets = config['ruleSets']
    if not isinstance(rule_sets, list) or not rule_sets or len(set(rule_sets)) != len(rule_sets) or not set(rule_sets) <= set(RULE_SETS):
        raise ImportError_(f'Study config ruleSets must be a non-empty list of distinct names from {list(RULE_SETS)}')
    if config['primaryRuleSet'] not in rule_sets:
        raise ImportError_('Study config primaryRuleSet must be one of its ruleSets')
    return config


def validate_extension(extension):
    """availabilityExtension: null (none), or the roster repeated past the data (owner, 2026-11-05, decision 8):
    {repeatFrom, repeatTo, through: ISO dates, holidays: [ISO dates]}; the working weekdays from repeatFrom to repeatTo
    repeat in order, one per weekday after the last date of the data, through the through date, skipping the holidays."""
    if extension is None:
        return
    if not isinstance(extension, dict) or set(extension) != {'repeatFrom', 'repeatTo', 'through', 'holidays'}:
        raise ImportError_('Study config availabilityExtension needs exactly repeatFrom, repeatTo, through and holidays')
    try:
        start, end, through = (dt.date.fromisoformat(extension[k]) for k in ('repeatFrom', 'repeatTo', 'through'))
        holidays = extension['holidays']
        if not isinstance(holidays, list):
            raise TypeError
        [dt.date.fromisoformat(d) for d in holidays]
    except (TypeError, ValueError):
        raise ImportError_('Study config availabilityExtension dates must be ISO dates and holidays a list of them') from None
    if not start <= end < through:
        raise ImportError_('Study config availabilityExtension needs repeatFrom <= repeatTo < through')


def validate_assignments(assignments, depots):
    """technicianDepots: {pseudonym: [{from: null or an ISO date, depot: name}, ...]}, the first from null, dates rising."""
    if not isinstance(assignments, dict) or not assignments:
        raise ImportError_('Study config technicianDepots must give each technician its depots')
    for technician, periods in assignments.items():
        if not isinstance(periods, list) or not periods:
            raise ImportError_(f'Study config technicianDepots.{technician} must list at least one period')
        previous = None
        for position, period in enumerate(periods):
            if not isinstance(period, dict) or set(period) != {'from', 'depot'}:
                raise ImportError_(f'Study config technicianDepots.{technician} periods need exactly from and depot')
            if period['depot'] not in depots:
                raise ImportError_(f'Study config technicianDepots.{technician} names an unknown depot {period["depot"]!r}')
            if position == 0:
                if period['from'] is not None:
                    raise ImportError_(f'Study config technicianDepots.{technician} must start with from null (every date before the first move)')
                continue
            try:
                start = dt.date.fromisoformat(period['from'])
            except (TypeError, ValueError):
                raise ImportError_(f'Study config technicianDepots.{technician} from must be an ISO date after the first period') from None
            if previous is not None and start <= previous:
                raise ImportError_(f'Study config technicianDepots.{technician} dates must rise')
            previous = start


def depot_names(config):
    """{name: depot} for the configured depots; a single depot is named 'depot'."""
    return {'depot': config['depot']} if 'depot' in config else dict(config['depots'])


def depot_of(config, pseudonym, day):
    """(name, (lat, lng)) of the depot the technician starts and ends at on the day; refused when none is configured."""
    named = depot_names(config)
    if 'depot' in config:
        name = 'depot'
    else:
        periods = config['technicianDepots'].get(pseudonym)
        if periods is None:
            raise ImportError_(f'Study config technicianDepots has no depot for {pseudonym}; every technician needs one')
        name = periods[0]['depot']
        for period in periods[1:]:
            if day >= dt.date.fromisoformat(period['from']):
                name = period['depot']
    depot = named[name]
    if not depot_located(depot):
        raise ImportError_(f'Study config depot {name} has no coordinates yet: geocode it (backtest_import.py geocode) or record them from the owner')
    return name, (depot['lat'], depot['lng'])


def check_assignments(config, pseudonyms):
    """Every technician of the run has a depot, and every assignment names a technician of the run."""
    if 'depot' in config:
        return
    known, assigned = set(pseudonyms.values()), set(config['technicianDepots'])
    if known - assigned:
        raise ImportError_(f'Study config technicianDepots has no depot for {sorted(known - assigned)}; every technician needs one')
    if assigned - known:
        raise ImportError_(f'Study config technicianDepots names technicians the history does not have: {sorted(assigned - known)}')


# ----------------------------------------------------------------------------------------------------------------------
# Parsing


class Row:
    __slots__ = ('index', 'technician', 'lat', 'lng', 'requested', 'scheduled_date', 'day', 'codes', 'booked', 'booked_minutes',
                 'actual_start', 'actual_end', 'flags')

    def __init__(self, index):
        self.index = index
        self.flags = []


def parse_date(value):
    text = value.strip()
    if not text:
        return None
    try:
        return dt.datetime.strptime(text, '%m/%d/%Y').date()
    except ValueError:
        return None


def parse_clock(flag, hour, minute):
    """A 12-hour time as minutes after midnight, or None when the record carries none."""
    flag, hour, minute = flag.strip().upper(), hour.strip(), minute.strip()
    if flag not in ('A', 'P') or not hour or not minute:
        return None
    try:
        h, m = int(hour), int(minute)
    except ValueError:
        return None
    if not (0 <= h <= 12 and 0 <= m <= 59):
        return None
    if flag == 'A' and h == 12:
        h = 0
    elif flag == 'P' and h < 12:
        h += 12
    return h * 60 + m


def parse_rows(path):
    """Every CSV row as a Row with its raw problems recorded, in file order."""
    rows = []
    with Path(path).open(newline='', encoding='utf-8', errors='replace') as stream:
        reader = csv.reader(stream)
        header = next(reader, None)
        if header is None or [h.strip() for h in header] != COLUMNS:
            raise ImportError_('CSV header differs from the work-order history layout')
        for index, values in enumerate(reader, start=2):
            if len(values) != len(COLUMNS):
                raise ImportError_(f'CSV row {index} has {len(values)} fields, expected {len(COLUMNS)}')
            record = dict(zip(COLUMNS, values))
            row = Row(index)
            row.technician = record['actual_emp_id'].strip() or record['sched_emp_id'].strip()
            try:
                row.lat, row.lng = float(record['lattitude']), float(record['longitude'])
            except ValueError:
                row.lat = row.lng = None
            if row.lat is not None and not (math.isfinite(row.lat) and math.isfinite(row.lng)):
                row.lat = row.lng = None
            row.requested = parse_date(record['repair_req_date'])
            row.scheduled_date = parse_date(record['sched_fix_date'])
            row.day = parse_date(record['actual_fix_date'])
            row.codes = [c.strip().replace(' ', '_') for c in (record['complaint_1_c'], record['complaint_2_c'], record['complaint_3_c']) if c.strip()]
            row.booked = parse_clock(record['sched_ampm_f'], record['sched_hour'], record['sched_minute'])
            try:
                row.booked_minutes = int(record['sched_duration'].strip() or '0')
            except ValueError:
                row.booked_minutes = None
            row.actual_start = parse_clock(record['act_strt_ampm_f'], record['actual_start_hr'], record['actual_start_mn'])
            row.actual_end = parse_clock(record['act_end_ampm_f'], record['actual_end_hr'], record['actual_end_mn'])
            rows.append(row)
    return rows


# ----------------------------------------------------------------------------------------------------------------------
# Rules and the ledger


def inside(bbox, lat, lng):
    return bbox['west'] <= lng <= bbox['east'] and bbox['south'] <= lat <= bbox['north']


def apply_rules(rows, config, corrections=None):
    """Decide every row: a list of (row, reason, severity) ledger entries, and the rows kept with their flags. The rows it
    returns are copies, so the parsed rows stay as read and the rules can be applied again with the same result."""
    corrections = corrections or {}
    bbox, zero = config['bbox'], config['zeroDurationMinutes']
    years, excluded_technicians = set(config['years']), set(config['excludeTechnicians'])
    shift_start = minutes(config['shift']['start'])
    # No shift rule runs past the later end, so a booking at or after it is a data error under every rule set; a booking
    # one rule set cannot serve is held out of that rule set's days (shift_rule), not excluded here.
    latest_end = minutes(config['shift']['salesCallEnd'])
    ledger, kept = [], []
    for original in rows:
        row = copy.copy(original)
        row.flags = list(original.flags)
        reasons = []
        if row.day is None or row.day.year not in years:
            reasons.append('BAD_YEAR')
        # Part 2 replays every job on its request date, so a job without one, or requested after it was served, is refused
        # rather than given a date it never had.
        if row.requested is None:
            reasons.append('NO_REQUEST_DATE')
        elif row.day is not None and row.requested > row.day:
            reasons.append('REQUESTED_AFTER_SERVICE')
        if not row.technician:
            reasons.append('NO_TECHNICIAN')
        elif row.technician in excluded_technicians:
            reasons.append('SINGLETON_TECH')
        if not row.codes:
            reasons.append('NO_COMPLAINT')
        correction = corrections.get(str(row.index))
        if correction is not None:
            row.lat, row.lng = correction['lat'], correction['lng']
            row.flags.append('GEOCODE_CORRECTED')
        if row.lat is None or not inside(bbox, row.lat, row.lng):
            reasons.append('OUT_OF_AREA')
        if row.booked is None:
            reasons.append('NO_BOOKED_TIME')
        elif row.booked < shift_start - 60:
            reasons.append('BOOKED_BEFORE_SHIFT')
        elif row.booked >= latest_end:
            reasons.append('BOOKED_AFTER_DAY_END')
        if row.booked_minutes is None:
            reasons.append('BAD_DURATION')
        elif row.booked_minutes <= 0:
            if zero is None:
                reasons.append('ZERO_DURATION')
            else:
                row.booked_minutes = zero
                row.flags.append('ZERO_DURATION_REPAIRED')
        elif row.booked_minutes > 720:
            reasons.append('BAD_DURATION')
        if row.booked_minutes is not None and config.get('maxBookedMinutes') is not None and row.booked_minutes > config['maxBookedMinutes']:
            reasons.append('BOOKED_OVER_LIMIT')
        if reasons:
            for reason in reasons:
                ledger.append((row, reason, EXCLUDE))
            continue
        if row.scheduled_date != row.day:
            row.flags.append('RESCHEDULED')
        if row.actual_start is None or row.actual_end is None or row.actual_end <= row.actual_start:
            row.flags.append('ACTUAL_END_NOT_AFTER_START')
        elif row.actual_end - row.actual_start > 240:
            row.flags.append('ACTUAL_OVER_4H')
        if len(row.codes) > 1:
            row.flags.append('MULTI_COMPLAINT')
        if row.day.weekday() >= 5:
            row.flags.append('WEEKEND')
        for flag in row.flags:
            ledger.append((row, flag, FLAG))
        kept.append(row)
    return ledger, kept


def minutes(clock):
    value = dt.time.fromisoformat(clock)
    return value.hour * 60 + value.minute


def clock(total):
    return f'{total // 60:02d}:{total % 60:02d}'


def travel_minutes(seconds, rates):
    """A leg's planned minutes as the engine buffers it (Monetary.bufferedMinutes): ceil(seconds * (1 + pct) / 60) + extra."""
    exact = Fraction(seconds) * (1 + Fraction(rates['travelBufferPct'])) / 60
    return -(-exact.numerator // exact.denominator) + rates['travelBufferMinutes']


def depot_legs(rows, config, road_client, pseudonyms):
    """{row index: (minutes from its technician's depot that day, minutes back, both routable)} for every row."""
    depots = {row.index: depot_of(config, pseudonyms[row.technician], row.day)[1] for row in rows}
    points = {row.index: (row.lat, row.lng) for row in rows}
    pairs = sorted({(depots[i], point) for i, point in points.items()} | {(point, depots[i]) for i, point in points.items()})
    legs = road_client.legs(pairs) if pairs else {}
    result = {}
    for index, point in points.items():
        depot = depots[index]
        out, back = legs[road_client.key(depot, point)], legs[road_client.key(point, depot)]
        routable = out['routable'] and back['routable']
        result[index] = (travel_minutes(out['seconds'], config['rates']) if routable else None,
                         travel_minutes(back['seconds'], config['rates']) if routable else None, routable)
    return result


def drop_unroutable(kept, config, road_client, pseudonyms):
    """Kept rows whose pin the road graph cannot reach from or back to their technician's depot leave every plan (UNROUTABLE_LOCATION)."""
    legs = depot_legs(kept, config, road_client, pseudonyms)
    ledger, routable = [], []
    for row in kept:
        if legs[row.index][2]:
            routable.append(row)
        else:
            ledger.append((row, 'UNROUTABLE_LOCATION', EXCLUDE))
    return ledger, routable


def servable(row, end, legs, config):
    """Whether one technician alone can serve the row at its booked time inside a shift ending at {@code end}: arrive in the
    window after driving from the depot at the shift start, do the work, and be back at the depot by the end."""
    drive_in, drive_back, routable = legs[row.index]
    if not routable:
        return False
    window_end = min(row.booked + config['windowMinutes'], end)
    arrival = max(row.booked, minutes(config['shift']['start']) + drive_in)
    return arrival <= window_end and arrival + row.booked_minutes + drive_back <= end


def shift_rule(rule, rows, legs, config):
    """One day under one rule set: (rows served, rows held out, {technician: day end in minutes}, rows pinned to their
    dispatcher's technician, the end that caps every unpinned window). Held-out rows leave every plan of the rule set, the
    dispatcher's included, so pairing stays exact. Under as-run nothing is held out and the windows run to the later end
    (window_cap), while every shift ends at the regular end."""
    regular, late = minutes(config['shift']['end']), minutes(config['shift']['salesCallEnd'])
    sales_codes = set(config['salesCallCodes'])
    technicians = {row.technician for row in rows}
    pinned = set()
    if rule == 'as-run':
        return list(rows), [], {t: regular for t in technicians}, pinned, late
    if rule == 'strict-17':
        ends = {t: regular for t in technicians}
    elif rule == 'sales-day':
        end = late if any(row.codes[0] in sales_codes for row in rows) else regular
        ends = {t: end for t in technicians}
    elif rule == 'evening-sales':
        ends = {t: regular for t in technicians}
        for row in rows:
            if row.codes[0] in sales_codes and not servable(row, regular, legs, config) and servable(row, late, legs, config):
                pinned.add(row.index)
                ends[row.technician] = late
    else:
        raise ImportError_(f'Unknown rule set {rule}')
    # An unpinned row is checked against the end its rule gives the whole fleet, the latest any technician may take it.
    fleet_end = late if rule == 'sales-day' and any(e == late for e in ends.values()) else regular
    served, held = [], []
    for row in rows:
        end = ends[row.technician] if row.index in pinned else fleet_end
        (served if servable(row, end, legs, config) else held).append(row)
    return served, held, ends, pinned, fleet_end


def dated_rows(rows, config):
    """Rows with a date inside the study years and a technician the study keeps, whatever else the rules decide."""
    excluded = set(config['excludeTechnicians'])
    return [row for row in rows if row.day is not None and row.day.year in set(config['years']) and row.technician and row.technician not in excluded]


def worked_days(rows, config):
    """{date: sorted real technician ids} for every date of the history (owner, 2026-11-04, decision 1): a technician works a
    date exactly when the history has a row of theirs on it, kept or excluded."""
    worked = defaultdict(set)
    for row in dated_rows(rows, config):
        worked[row.day].add(row.technician)
    return {day: sorted(worked[day]) for day in sorted(worked)}


def roster(rows, kept, config):
    """Technician spans, holidays and the roster per day (the owner's rule), from every dated row and the kept rows."""
    dated = dated_rows(rows, config)
    if not dated:
        raise ImportError_('No dated rows inside the study years')
    spans = {}
    for row in dated:
        first, last = spans.get(row.technician, (row.day, row.day))
        spans[row.technician] = (min(first, row.day), max(last, row.day))
    busy = {row.day for row in dated}
    start, end = min(busy), max(busy)
    holidays = []
    day = start
    while day <= end:
        if day.weekday() < 5 and day not in busy:
            holidays.append(day)
        day += dt.timedelta(days=1)
    holiday_set = set(holidays)
    days = sorted({row.day for row in kept})
    on_shift = {}
    if config.get('roster', 'spans') == 'csv-days':
        worked = worked_days(rows, config)
        return spans, holidays, {day: worked[day] for day in days}
    for day in days:
        if day.weekday() < 5 and day not in holiday_set:
            on_shift[day] = sorted(t for t, (first, last) in spans.items() if first <= day <= last)
        else:
            on_shift[day] = sorted({row.technician for row in kept if row.day == day})
    return spans, holidays, on_shift


# ----------------------------------------------------------------------------------------------------------------------
# Roads


class RoadClient:
    """POST /internal/legs on a routing service, with a cache on disk keyed by coordinate pair."""

    def __init__(self, url, token, cache_path):
        self.url = url.rstrip('/')
        self.token = token
        self.cache_path = Path(cache_path)
        self.cache = {}
        if self.cache_path.exists():
            with self.cache_path.open(encoding='utf-8') as stream:
                stored = json.load(stream)
            self.identity = stored['routingIdentity']
            self.map_version = stored['mapVersion']
            self.cache = stored['legs']
        health = self.get('/health')
        if not health.get('ready'):
            raise ImportError_('Routing service is not ready')
        if self.cache and health['routingIdentity'] != self.identity:
            raise ImportError_(f'Road cache at {self.cache_path} came from another routing identity; delete it or use a new run directory')
        self.identity = health['routingIdentity']
        self.map_version = health['mapVersion']

    def headers(self):
        headers = {'Content-Type': 'application/json'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        return headers

    def get(self, path):
        request = urllib.request.Request(self.url + path, headers=self.headers())
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)

    def post(self, path, body):
        request = urllib.request.Request(self.url + path, data=json.dumps(body).encode(), headers=self.headers(), method='POST')
        with urllib.request.urlopen(request, timeout=300) as response:
            return json.load(response)

    @staticmethod
    def key(origin, destination):
        return f'{origin[0]!r},{origin[1]!r}>{destination[0]!r},{destination[1]!r}'

    def legs(self, pairs):
        """{key: leg} for every (origin, destination) coordinate pair; a leg is {'routable', 'seconds', 'meters'}."""
        wanted = {self.key(o, d): (o, d) for o, d in pairs if self.key(o, d) not in self.cache}
        keys = sorted(wanted)
        for start in range(0, len(keys), LEG_BATCH):
            batch = keys[start:start + LEG_BATCH]
            body = {'expectedRoutingIdentity': self.identity,
                    'pairs': [{'id': key, 'origin': {'lat': wanted[key][0][0], 'lng': wanted[key][0][1]},
                               'destination': {'lat': wanted[key][1][0], 'lng': wanted[key][1][1]}} for key in batch]}
            response = self.post('/internal/legs', body)
            if response['routingIdentity'] != self.identity:
                raise ImportError_('Routing identity changed during the build')
            for result in response['pairs']:
                leg = result['leg']
                self.cache[result['id']] = {'routable': bool(leg['routable']),
                                            'seconds': leg['seconds'] if leg['routable'] else None,
                                            'meters': leg['meters'] if leg['routable'] else None}
            self.save()
        return {self.key(o, d): self.cache[self.key(o, d)] for o, d in pairs}

    def save(self):
        write_json(self.cache_path, {'routingIdentity': self.identity, 'mapVersion': self.map_version, 'legs': self.cache})


class StubRoadClient:
    """A straight-line road model for tests: 1.3 times the great-circle distance at 15 m/s."""
    identity = 'stub-road-model-v1'
    map_version = 'stub'

    def __init__(self, unroutable=()):
        self.unroutable = set(unroutable)

    key = staticmethod(RoadClient.key)

    def legs(self, pairs):
        result = {}
        for origin, destination in pairs:
            key = self.key(origin, destination)
            if origin in self.unroutable or destination in self.unroutable:
                result[key] = {'routable': False, 'seconds': None, 'meters': None}
            else:
                meters = round(haversine_m(origin, destination) * 1.3)
                result[key] = {'routable': True, 'seconds': round(meters / 15), 'meters': meters}
        return result


def haversine_m(a, b):
    lat1, lng1, lat2, lng2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


# ----------------------------------------------------------------------------------------------------------------------
# Days


def local_instant(day, clock_minutes, zone):
    """The instant of a local wall-clock time on a day, as the API and the dataset carry it (ISO, UTC, whole seconds)."""
    local = dt.datetime.combine(day, dt.time(hour=clock_minutes // 60, minute=clock_minutes % 60), tzinfo=zone)
    return local.astimezone(dt.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')


def technician_ids(spans):
    """Pseudonymous ids in a stable order: t01, t02, ... by the real id's sort order."""
    return {real: f't{index:02d}' for index, real in enumerate(sorted(spans), start=1)}


def build_day(day, rows, on_shift, pseudonyms, config, road_client, rule):
    """Every file of one service date under one rule set, as a dict of name to JSON document, and the rows held out."""
    zone = ZoneInfo(config['timeZone'])
    shift, window_minutes = config['shift'], config['windowMinutes']
    sales_codes = set(config['salesCallCodes'])
    sales_day = any(row.codes[0] in sales_codes for row in rows)
    served, held, ends, pinned, fleet_end = shift_rule(rule, rows, depot_legs(rows, config, road_client, pseudonyms), config)
    # The end that caps a window: the fleet's end, except under as-run, whose windows run to the later end.
    window_cap = fleet_end
    if rule == 'as-run':
        fleet_end, window_cap = minutes(shift['end']), fleet_end
    day_start = minutes(shift['start'])
    technicians = sorted(set(on_shift) | {row.technician for row in rows})
    # Each technician's depot that day; the day's depots come first in the locations, in name order.
    tech_depot = {t: depot_of(config, pseudonyms[t], day) for t in technicians}
    depot_points = {name: point for name, point in tech_depot.values()}
    depot_order = sorted(depot_points)
    depot_location = {name: position for position, name in enumerate(depot_order)}
    # A rostered technician without a job of the day takes the fleet's end (the later end on a sales-day day).
    tech_end = {t: ends.get(t, fleet_end) for t in technicians}
    day_end = max(tech_end.values())
    tech_index = {t: i for i, t in enumerate(technicians)}
    rows = served
    visits, points = [], [depot_points[name] for name in depot_order]
    rows = sorted(rows, key=lambda r: (r.booked, r.index))
    for row in rows:
        window_start = row.booked
        window_end = min(row.booked + window_minutes, tech_end[row.technician] if row.index in pinned else window_cap)
        if window_end <= window_start:
            raise ImportError_(f'Row {row.index} is booked at or after the day end; the rules should have excluded it')
        sales = row.codes[0] in sales_codes
        actual = row.actual_end - row.actual_start if row.actual_start is not None and row.actual_end is not None and row.actual_end > row.actual_start else None
        # A pinned evening sales call carries a service only its dispatcher's technician holds, so it stays with them.
        service = f'{row.codes[0]}@{pseudonyms[row.technician]}' if row.index in pinned else row.codes[0]
        visits.append({'id': f'wo-{row.index}', 'row': row, 'service': service, 'tags': row.codes, 'salesCall': sales, 'pinned': row.index in pinned,
                       'windowStart': local_instant(day, window_start, zone), 'windowEnd': local_instant(day, window_end, zone),
                       'plannedStart': local_instant(day, row.booked, zone), 'durationMinutes': row.booked_minutes,
                       'actualDurationMinutes': actual, 'technician': row.technician, 'location': (row.lat, row.lng)})
        points.append((row.lat, row.lng))
    services = sorted({visit['service'] for visit in visits})
    service_index = {s: i for i, s in enumerate(services)}
    qualified = {t: [s for s in services if '@' not in s or s.endswith('@' + pseudonyms[t])] for t in technicians}
    # Locations: each of the day's depots once, then one entry per visit (two visits at one address keep their own entries).
    locations = [{'id': 'depot' if name == 'depot' else f'depot-{name}', 'latitude': depot_points[name][0], 'longitude': depot_points[name][1]}
                 for name in depot_order]
    for visit in visits:
        locations.append({'id': visit['id'], 'latitude': visit['location'][0], 'longitude': visit['location'][1]})
    pairs = {(points[v], points[w]) for v in range(len(points)) for w in range(len(points)) if v != w}
    legs = road_client.legs(sorted(pairs)) if pairs else {}
    entries, unreachable = [], 0
    for v in range(len(points)):
        for w in range(len(points)):
            if v == w:
                continue
            leg = legs[road_client.key(points[v], points[w])]
            if leg['routable']:
                entries.append({'from': v, 'to': w, 'state': 'REACHABLE', 'seconds': leg['seconds'], 'meters': leg['meters']})
            else:
                entries.append({'from': v, 'to': w, 'state': 'UNREACHABLE', 'seconds': None, 'meters': None})
                unreachable += 1
    planned = defaultdict(list)
    for visit in visits:
        planned[visit['technician']].append(visit['id'])
    executed = defaultdict(list)
    for visit in sorted(visits, key=lambda v: (v['row'].actual_start if v['row'].actual_start is not None else v['row'].booked, v['row'].index)):
        executed[visit['technician']].append(visit['id'])
    visit_index = {visit['id']: i for i, visit in enumerate(visits)}
    first_visit = len(depot_order)
    rates = config['rates']

    def dataset(durations):
        technician_nodes = []
        for real in technicians:
            home = depot_location[tech_depot[real][0]]
            technician_nodes.append({'id': pseudonyms[real], 'departureLocation': home, 'returnLocation': home, 'shiftStart': local_instant(day, day_start, zone),
                                     'shiftEnd': local_instant(day, tech_end[real], zone), 'maxDailyMinutes': tech_end[real] - day_start, 'maxOvertimeMinutes': 0,
                                     'qualifications': [service_index[s] for s in qualified[real]],
                                     'absences': [], 'assigned': [visit_index[i] for i in planned.get(real, [])], 'pinnedPrefix': 0})
        visit_nodes = []
        for visit in visits:
            visit_nodes.append({'id': visit['id'], 'service': service_index[visit['service']], 'location': visit_index[visit['id']] + first_visit,
                                'windowStart': visit['windowStart'], 'windowEnd': visit['windowEnd'], 'durationMinutes': durations[visit['id']],
                                'originalTechnician': tech_index[visit['technician']], 'originalPlannedStart': visit['plannedStart']})
        return {'schemaVersion': 1, 'operation': 'DAILY', 'mode': 'ASSIGNED', 'snapshotId': f'{config["runId"]}/{day.isoformat()}',
                'contentHash': None, 'versions': dict(VERSIONS),
                'routing': {'provider': 'caller', 'profile': 'car', 'identity': road_client.identity},
                'search': dict(config['search']),
                'revisions': {'configuration': config['runId'], 'reservation': day.isoformat(), 'schedule': [f'{day.isoformat()}/{pseudonyms[t]}' for t in technicians]},
                'rates': {'regularHourly': rates['regularHourly'], 'overtimeHourly': rates['overtimeHourly'], 'mileagePerMile': rates['mileagePerMile'],
                          'travelBufferPct': rates['travelBufferPct'], 'travelBufferMinutes': rates['travelBufferMinutes'],
                          'arrivalBuffer': dict(rates['arrivalBuffer'])},
                'locations': locations, 'services': [{'id': s} for s in services], 'technicians': technician_nodes, 'visits': visit_nodes,
                'unassigned': [], 'roads': {'encoding': 'SPARSE', 'size': len(locations), 'entries': entries}}

    booked = {visit['id']: visit['durationMinutes'] for visit in visits}
    actual = {visit['id']: visit['actualDurationMinutes'] if visit['actualDurationMinutes'] is not None else visit['durationMinutes'] for visit in visits}
    plan_file = lambda routes: {'version': 1, 'routes': {pseudonyms[t]: list(routes.get(t, [])) for t in technicians}, 'unassigned': []}
    shift_days = config['apiDateShiftDays']
    api_day = day + dt.timedelta(days=shift_days)
    appointments = []
    for real, ids in planned.items():
        for sequence, visit_id in enumerate(ids):
            visit = visits[visit_index[visit_id]]
            booked_local = visit['row'].booked
            appointments.append({'id': visit_id, 'technicianId': pseudonyms[real], 'serviceDate': api_day.isoformat(), 'serviceId': visit['service'],
                                 'durationMinutes': visit['durationMinutes'],
                                 'window': {'start': local_instant(api_day, booked_local, zone),
                                            'end': local_instant(api_day, min(booked_local + window_minutes, tech_end[real] if visit['pinned'] else window_cap), zone)},
                                 'location': {'lat': visit['location'][0], 'lng': visit['location'][1]}, 'sequence': sequence,
                                 'plannedStart': local_instant(api_day, booked_local, zone)})
    appointments.sort(key=lambda a: (a['technicianId'], a['sequence']))
    api_request = {'requestId': str(uuid.uuid5(uuid.NAMESPACE_URL, f'{config["runId"]}/{day.isoformat()}/1')), 'serviceDate': api_day.isoformat(),
                   'snapshot': {'metroId': config['metroId'], 'timeZone': config['timeZone'], 'rates': dict(rates), 'policy': dict(config['policy']),
                                'technicians': [{'id': pseudonyms[t], 'qualifications': qualified[t]} for t in technicians],
                                'technicianDays': [{'technicianId': pseudonyms[t], 'serviceDate': api_day.isoformat(), 'lastModified': local_instant(api_day, 0, zone),
                                                    'shift': {'start': local_instant(api_day, day_start, zone), 'end': local_instant(api_day, tech_end[t], zone)},
                                                    'absences': [], 'start': {'lat': tech_depot[t][1][0], 'lng': tech_depot[t][1][1]},
                                                    'end': {'lat': tech_depot[t][1][0], 'lng': tech_depot[t][1][1]},
                                                    'maxPaidMinutes': tech_end[t] - day_start} for t in technicians],
                                'appointments': appointments}}
    observed = {'day': day.isoformat(), 'visits': {visit['id']: {
        'technician': pseudonyms[visit['technician']], 'bookedStart': visit['plannedStart'],
        'actualStart': local_instant(day, visit['row'].actual_start, zone) if visit['row'].actual_start is not None else None,
        'actualEnd': local_instant(day, visit['row'].actual_end, zone) if visit['row'].actual_end is not None else None,
        'actualDurationMinutes': visit['actualDurationMinutes'], 'bookedMinutes': visit['durationMinutes'], 'salesCall': visit['salesCall'],
        'tags': visit['tags'], 'flags': list(visit['row'].flags), 'requestLeadDays': (day - visit['row'].requested).days if visit['row'].requested else None}
        for visit in visits}}
    index = {'day': day.isoformat(), 'year': day.year, 'weekday': day.weekday(), 'apiServiceDate': api_day.isoformat(), 'jobs': len(visits),
             'technicians': len(technicians), 'techniciansWithJobs': len(planned), 'salesCallDay': sales_day, 'salesCalls': sum(1 for v in visits if v['salesCall']),
             'dayEnd': clock(day_end), 'unreachableLegs': unreachable, 'ruleSet': rule,
             'heldOut': len(held), 'heldOutSalesCalls': sum(1 for row in held if row.codes[0] in sales_codes),
             'pinnedSalesCalls': len(pinned), 'extendedTechnicians': sorted(pseudonyms[t] for t in technicians if tech_end[t] > minutes(shift['end'])),
             'depots': {pseudonyms[t]: tech_depot[t][0] for t in technicians},
             'flags': dict(Counter(flag for visit in visits for flag in visit['row'].flags)),
             'split': 'calibration' if day.year in set(config['calibrationYears']) else 'test'}
    return {'facts.json': dataset(booked), 'facts-actual-durations.json': dataset(actual), 'as-planned.plan.json': plan_file(planned),
            'as-executed.plan.json': plan_file(executed), 'api-request.json': api_request, 'observed.json': observed, 'index': index}, held


# ----------------------------------------------------------------------------------------------------------------------
# Build


def write_json(path, document):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.part')
    with tmp.open('w', encoding='utf-8', newline='\n') as stream:
        json.dump(document, stream, indent=1, sort_keys=True)
        stream.write('\n')
    tmp.replace(path)


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def prepare(rows, config, road_client, corrections=None):
    """The decisions every output shares: (config with the date shift chosen, ledger, kept rows, pseudonyms, spans,
    holidays, roster per kept day)."""
    config = dict(config)
    if config['apiDateShiftDays'] is None:
        config['apiDateShiftDays'] = default_date_shift(rows, years=set(config['years']))
    ledger, kept = apply_rules(rows, config, corrections)
    if not kept:
        raise ImportError_('Every row was excluded; check the configuration against the CSV')
    # Pseudonyms come from every dated row, so they do not depend on which rows the rules keep.
    spans, _, _ = roster(rows, kept, config)
    pseudonyms = technician_ids(spans)
    check_assignments(config, pseudonyms)
    unroutable, kept = drop_unroutable(kept, config, road_client, pseudonyms)
    ledger += unroutable
    # A dropped row's flags were already written; its exclusion is the decision that counts, so its flag entries go.
    dropped = {row.index for row, _, _ in unroutable}
    ledger = [(row, reason, severity) for row, reason, severity in ledger if severity == EXCLUDE or row.index not in dropped]
    spans, holidays, on_shift = roster(rows, kept, config)
    return config, ledger, kept, pseudonyms, spans, holidays, on_shift


def extension_days(worked, config):
    """[(date after the data, the source date whose technicians it repeats)] under availabilityExtension, in date order."""
    extension = config.get('availabilityExtension')
    if extension is None:
        return []
    last = max(worked)
    start, end, through = (dt.date.fromisoformat(extension[k]) for k in ('repeatFrom', 'repeatTo', 'through'))
    if end > last or through <= last:
        raise ImportError_(f'availabilityExtension must repeat dates of the data (to {last}) and run past it')
    # The working weekdays of the source weeks: a weekday with nobody working (a holiday) is not repeated.
    source = [day for day in worked if start <= day <= end and day.weekday() < 5 and worked[day]]
    if not source:
        raise ImportError_('availabilityExtension repeats no working weekday')
    holidays = {dt.date.fromisoformat(d) for d in extension['holidays']}
    result, day = [], last + dt.timedelta(days=1)
    while day <= through:
        if day.weekday() < 5 and day not in holidays:
            result.append((day, source[len(result) % len(source)]))
        day += dt.timedelta(days=1)
    return result


def part2_inputs(rows, kept, config, pseudonyms):
    """Part 2's request stream and technician-days (simulation-plan.md, Host rules), pseudonymous: (requests, availability)."""
    sales_codes = set(config['salesCallCodes'])
    requests = []
    for row in sorted(kept, key=lambda r: (r.requested, r.index)):
        if row.requested is None or row.requested > row.day:
            raise ImportError_(f'Row {row.index} has no request date before its service; the rules should have excluded it')
        pseudonym = pseudonyms[row.technician]
        requests.append({'id': f'wo-{row.index}', 'requestDate': row.requested.isoformat(), 'serviceId': row.codes[0], 'tags': list(row.codes),
                         'salesCall': row.codes[0] in sales_codes, 'durationMinutes': row.booked_minutes,
                         'location': {'lat': row.lat, 'lng': row.lng}, 'flags': list(row.flags),
                         # What the dispatcher did, for the comparison only; the simulator books without it.
                         'dispatcher': {'serviceDate': row.day.isoformat(), 'technicianId': pseudonym, 'bookedStart': clock(row.booked),
                                        'depot': depot_of(config, pseudonym, row.day)[0]}})
    start, end = minutes(config['shift']['start']), minutes(config['shift']['end'])
    jobs = Counter((row.day, row.technician) for row in kept)
    worked = worked_days(rows, config)

    def technician_day(day, real, source, repeat_of):
        pseudonym = pseudonyms[real]
        name, (lat, lng) = depot_of(config, pseudonym, day)
        return {'date': day.isoformat(), 'technicianId': pseudonym, 'depot': name, 'location': {'lat': lat, 'lng': lng},
                'shiftStart': config['shift']['start'], 'shiftEnd': config['shift']['end'], 'maxPaidMinutes': end - start,
                'source': source, 'repeatOf': repeat_of, 'dispatcherJobs': jobs[(day, real)] if source == 'history' else None}

    days = [technician_day(day, real, 'history', None) for day in worked for real in worked[day]]
    tail = extension_days(worked, config)
    days += [technician_day(day, real, 'repeat', source.isoformat()) for day, source in tail for real in worked[source]]
    lead = 'requestDate'
    availability = {'version': 1, 'runId': config['runId'], 'timeZone': config['timeZone'], 'lastHistoryDate': max(worked).isoformat(),
                    'extension': config.get('availabilityExtension'), 'technicianDays': days,
                    'summary': {'history': sum(1 for d in days if d['source'] == 'history'), 'repeat': sum(1 for d in days if d['source'] == 'repeat'),
                                'historyDates': len(worked), 'repeatDates': len(tail),
                                'historyWithoutKeptJobs': sum(1 for d in days if d['source'] == 'history' and d['dispatcherJobs'] == 0)}}
    request_document = {'version': 1, 'runId': config['runId'], 'timeZone': config['timeZone'], 'salesCallCodes': sorted(sales_codes),
                        'requests': requests,
                        'summary': {'requests': len(requests), 'firstRequestDate': requests[0][lead], 'lastRequestDate': requests[-1][lead],
                                    'salesCalls': sum(1 for r in requests if r['salesCall']),
                                    'beforeStudyYears': sum(1 for r in requests if int(r[lead][:4]) not in set(config['years']))}}
    return request_document, availability


def write_part2_inputs(out, rows, kept, config, pseudonyms, road_client, expected_ids=None):
    """Write part2/requests.json, part2/availability.json and part2/inputs.json (their hashes); returns inputs.json's document.
    expected_ids, when given, is the as-run job set the requests must equal, so both parts cover the same jobs."""
    requests, availability = part2_inputs(rows, kept, config, pseudonyms)
    if expected_ids is not None and {r['id'] for r in requests['requests']} != set(expected_ids):
        raise ImportError_('Part 2 requests differ from the as-run jobs; both parts must cover the same jobs')
    directory = Path(out) / 'part2'
    write_json(directory / 'requests.json', requests)
    write_json(directory / 'availability.json', availability)
    receipt = {'version': 1, 'runId': config['runId'], 'routingIdentity': road_client.identity,
               'files': {name: sha256(directory / name) for name in ('requests.json', 'availability.json')},
               'requests': requests['summary'], 'availability': availability['summary']}
    write_json(directory / 'inputs.json', receipt)
    return receipt


def inputs(rows, config, out, road_client, corrections=None):
    """Part 2's inputs alone for a run already built under the same rules: checked against its as-run day files when present."""
    config, _, kept, pseudonyms, _, _, _ = prepare(rows, config, road_client, corrections)
    expected = None
    index_path = Path(out) / 'variants' / 'as-run' / 'index.json'
    if index_path.exists():
        with index_path.open(encoding='utf-8') as stream:
            index = json.load(stream)
        expected = set()
        for entry in index['days']:
            with (index_path.parent / 'days' / entry['day'] / 'observed.json').open(encoding='utf-8') as stream:
                expected |= set(json.load(stream)['visits'])
    return write_part2_inputs(out, rows, kept, config, pseudonyms, road_client, expected)


def build(rows, config, out, road_client, corrections=None):
    """The whole run directory from parsed rows. Returns the index document."""
    out = Path(out)
    config, ledger, kept, pseudonyms, spans, holidays, on_shift = prepare(rows, config, road_client, corrections)
    out.mkdir(parents=True, exist_ok=True)
    with (out / 'ledger.csv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(['row', 'reason', 'severity', 'day'])
        for row, reason, severity in ledger:
            writer.writerow([row.index, reason, severity, row.day.isoformat() if row.day else ''])
    summary = {'rows': len(rows), 'kept': len(kept), 'excludedRows': len({row.index for row, _, severity in ledger if severity == EXCLUDE}),
               'reasons': {severity: dict(sorted(Counter(reason for _, reason, s in ledger if s == severity).items())) for severity in (EXCLUDE, FLAG)}}
    write_json(out / 'ledger-summary.json', summary)
    write_json(out / 'technicians.json', {'pseudonyms': pseudonyms, 'spans': {pseudonyms[t]: [first.isoformat(), last.isoformat()] for t, (first, last) in spans.items()},
                                          'holidays': [d.isoformat() for d in holidays], 'roster': config.get('roster', 'spans'),
                                          'depots': config.get('technicianDepots', {p: [{'from': None, 'depot': 'depot'}] for p in pseudonyms.values()})})
    by_day = defaultdict(list)
    for row in kept:
        by_day[row.day].append(row)
    variants, as_run_ids = {}, None
    for rule in config['ruleSets']:
        # Each rule set is a run directory of its own, so the harness, the replay and the summary read it unchanged.
        variant = dict(config, runId=f'{config["runId"]}-{rule}', ruleSet=rule)
        directory = out / 'variants' / rule
        days, held_rows = [], []
        for day in sorted(by_day):
            documents, held = build_day(day, by_day[day], on_shift[day], pseudonyms, variant, road_client, rule)
            held_rows += held
            if not documents['index']['jobs']:
                continue
            day_directory = directory / 'days' / day.isoformat()
            for name, document in documents.items():
                if name != 'index':
                    write_json(day_directory / name, document)
            entry = documents['index']
            entry['files'] = {name: sha256(day_directory / name) for name in documents if name != 'index'}
            days.append(entry)
            if rule == 'as-run':
                as_run_ids = (as_run_ids or set()) | {visit['id'] for visit in documents['facts.json']['visits']}
        sales_codes = set(config['salesCallCodes'])
        with (directory / 'held-out.csv').open('w', encoding='utf-8', newline='') as stream:
            writer = csv.writer(stream)
            writer.writerow(['row', 'day', 'salesCall', 'bookedStart', 'bookedMinutes'])
            for row in held_rows:
                writer.writerow([row.index, row.day.isoformat(), row.codes[0] in sales_codes, clock(row.booked), row.booked_minutes])
        held_summary = {'rows': len(held_rows), 'salesCalls': sum(1 for row in held_rows if row.codes[0] in sales_codes)}
        index = {'runId': variant['runId'], 'ruleSet': rule, 'primary': rule == config['primaryRuleSet'], 'routingIdentity': road_client.identity,
                 'mapVersion': road_client.map_version, 'apiDateShiftDays': config['apiDateShiftDays'], 'days': days,
                 'ledger': dict(summary, heldOut=held_summary),
                 'technicians': {pseudonyms[t]: {'first': first.isoformat(), 'last': last.isoformat()} for t, (first, last) in spans.items()},
                 'holidays': [d.isoformat() for d in holidays]}
        write_json(directory / 'index.json', index)
        write_json(directory / 'study-config.json', variant)
        variants[rule] = {'directory': f'variants/{rule}', 'days': len(days), 'jobs': sum(e['jobs'] for e in days), 'heldOut': held_summary}
    part2 = write_part2_inputs(out, rows, kept, config, pseudonyms, road_client, as_run_ids)
    run = {'runId': config['runId'], 'routingIdentity': road_client.identity, 'mapVersion': road_client.map_version,
           'primaryRuleSet': config['primaryRuleSet'], 'ruleSets': variants, 'ledger': summary, 'part2': part2}
    write_json(out / 'run.json', run)
    write_json(out / 'study-config.json', config)
    return run


def default_date_shift(rows, today=None, years=None):
    """Whole weeks that move the earliest dated row at least 14 days past today, so the API never sees a frozen date."""
    today = today or dt.date.today()
    dated = [row.day for row in rows if row.day is not None and (years is None or row.day.year in years)]
    if not dated:
        raise ImportError_('No dated rows to shift')
    earliest = min(dated)
    weeks = 0
    while earliest + dt.timedelta(days=7 * weeks) < today + dt.timedelta(days=14):
        weeks += 1
    return 7 * weeks


# ----------------------------------------------------------------------------------------------------------------------
# Geocoding (local Nominatim only)


def nominatim_search(url, params):
    query = urllib.parse.urlencode({**params, 'format': 'jsonv2', 'addressdetails': 1, 'limit': 1})
    request = urllib.request.Request(f'{url.rstrip("/")}/search?{query}', headers={'User-Agent': 'waterflex-backtest-import'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def geocode(rows, config, nominatim):
    """Coordinates for the rows outside the area and the depot, from the local Nominatim; exact house matches inside the box only."""
    bbox = config['bbox']
    result = {'rows': {}, 'unresolved': [], 'depot': None}
    depot = config.get('depot', {})  # named depots carry their coordinates already
    if 'address' in depot:
        address = depot['address']
        found = nominatim_search(nominatim, {'street': address['street'], 'city': address['city'], 'state': address['state'], 'postalcode': address['postalCode']})
        if found and inside(bbox, float(found[0]['lat']), float(found[0]['lon'])) and found[0].get('address', {}).get('house_number'):
            result['depot'] = {'lat': float(found[0]['lat']), 'lng': float(found[0]['lon']), 'source': 'nominatim-preview'}
    with Path(config['csv']).open(newline='', encoding='utf-8', errors='replace') as stream:
        reader = csv.DictReader(stream)
        for index, record in enumerate(reader, start=2):
            row = next((r for r in rows if r.index == index), None)
            if row is None or (row.lat is not None and inside(bbox, row.lat, row.lng)):
                continue
            street = f"{record['house_number'].strip()} {record['street'].strip()}"
            found = nominatim_search(nominatim, {'street': street, 'city': record['city'].strip(), 'state': record['state'].strip() or 'CA'})
            if found and found[0].get('address', {}).get('house_number') == record['house_number'].strip() and inside(bbox, float(found[0]['lat']), float(found[0]['lon'])):
                result['rows'][str(index)] = {'lat': float(found[0]['lat']), 'lng': float(found[0]['lon']), 'source': 'nominatim-preview'}
            else:
                result['unresolved'].append(index)
    return result


# ----------------------------------------------------------------------------------------------------------------------
# Command line


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    build_cmd = commands.add_parser('build')
    build_cmd.add_argument('--csv', required=True)
    build_cmd.add_argument('--config', required=True)
    build_cmd.add_argument('--out', required=True)
    build_cmd.add_argument('--roads', required=True, help='routing service URL, for example http://127.0.0.1:18001, or "stub" for a straight-line dry run that no study may score')
    build_cmd.add_argument('--token-env', default='ROUTING_AUTH_TOKEN')
    build_cmd.add_argument('--corrections', help='geocode-corrections.json from the geocode command')
    inputs_cmd = commands.add_parser('inputs')
    for name in ('--csv', '--config', '--out', '--roads'):
        inputs_cmd.add_argument(name, required=True)
    inputs_cmd.add_argument('--token-env', default='ROUTING_AUTH_TOKEN')
    inputs_cmd.add_argument('--corrections')
    geocode_cmd = commands.add_parser('geocode')
    geocode_cmd.add_argument('--csv', required=True)
    geocode_cmd.add_argument('--config', required=True)
    geocode_cmd.add_argument('--out', required=True)
    geocode_cmd.add_argument('--nominatim', required=True)
    args = parser.parse_args(argv)
    config = load_config(args.config)
    rows = parse_rows(args.csv)
    if args.command == 'geocode':
        config['csv'] = args.csv
        result = geocode(rows, config, args.nominatim)
        write_json(args.out, result)
        print(json.dumps({'resolvedRows': len(result['rows']), 'unresolved': len(result['unresolved']), 'depot': result['depot']}))
        return 0
    corrections = None
    if args.corrections:
        with Path(args.corrections).open(encoding='utf-8') as stream:
            corrections = json.load(stream)['rows']
    token = os.environ.get(args.token_env, '')
    client = StubRoadClient() if args.roads == 'stub' else RoadClient(args.roads, token, Path(args.out) / 'roads-cache.json')
    if args.command == 'inputs':
        print(json.dumps(inputs(rows, config, args.out, client, corrections)))
        return 0
    run = build(rows, config, args.out, client, corrections)
    print(json.dumps({'kept': run['ledger']['kept'], 'excludedRows': run['ledger']['excludedRows'], 'routingIdentity': run['routingIdentity'],
                      'ruleSets': run['ruleSets']}))
    return 0


if __name__ == '__main__':
    sys.exit(main())
