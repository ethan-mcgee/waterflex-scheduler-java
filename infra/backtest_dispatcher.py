"""Part 1 of the Paso Robles backtest: the dispatcher's schedule as it ran, scored and summarized.

The as-run rule set of infra/backtest_import.py gives one day file per service date with every kept job at its booked
time. BacktestTool's backtest-score-days job reads each day's as-planned plan (the dispatcher's order by booked time)
in numbers; this script writes that job and then summarizes its rows with each day's observed.json: route measures,
overtime and its cost, customer windows, and promptness from the request date, for the full study, each year, quarter
and month.

Pay is the scorer's, by the dealership's rule (owner, 2026-11-09): unpaid meal periods as California Labor Code section
512 sets them, with no waiver (a 1 hour lunch once a technician-day's work passes 5 hours, 30 minutes more once it passes
10); overtime as section 510 sets it (paid time past 8 hours in a day, double time past 12, a technician's regular time
past 40 hours in a Monday-to-Friday week, and double time past 8 hours on a seventh day in a row); and every paid minute
of a Saturday or a Sunday as overtime. Rows paid under another rule, or under none, are refused, and the rule is
checked again on the rows read.

    python infra/backtest_dispatcher.py job --run <as-run variant dir> --rows <rows.json> --job <job.json>
    python infra/backtest_dispatcher.py summarize --run <as-run variant dir> --rows <rows.json> --out <report dir>

Stdlib only. A day without a row, a row for a day the run does not have, or a missing number is refused, never read as zero.
"""
import argparse
import datetime as dt
import json
import statistics
import sys
from collections import defaultdict
from decimal import Decimal
from pathlib import Path

from markdown_tables import table

METERS_PER_MILE = Decimal('1609.344')
# Owner, 2026-11-04: same-day emergencies, reported apart from the rest.
EMERGENCY_CODES = ('P02', 'P03', 'R02')
GROUPS = ('all', 'emergency', 'sales', 'other')
ROUTE_FIELDS = ('technicianId', 'holds', 'visits', 'paidMinutes', 'overtimeMinutes', 'doubleTimeMinutes', 'unpaidMinutes', 'driveMinutes', 'meters', 'latenessSeconds',
                'lateVisits', 'availabilitySeconds', 'unavailableRoads')
ROW_FIELDS = ('feasible', 'routesHold', 'costCents', 'regularCents', 'overtimeCents', 'mileageCents', 'paidMinutes', 'overtimeMinutes', 'doubleTimeMinutes',
              'unpaidMinutes',
              'driveMinutes', 'meters', 'latenessSeconds', 'lateVisits', 'availabilitySeconds', 'pastShiftMinutes', 'unreachableLegs', 'routes')
# The scorer's pay rule (BacktestTool.PAY_RULE), as its rows file names it: 480 regular minutes a day with double time
# past 720 (past 480 on a seventh day in a row) at twice the regular rate, 2,400 regular minutes in a technician's
# Monday-to-Friday week; 60 unpaid minutes once a technician-day's work passes 300 and 30 more once the work after them
# passes 600 (California's meal periods); weekends overtime.
PAY_RULE = {'dailyRegularMinutes': 480, 'dailyDoubleTimeAfterMinutes': 720, 'seventhDayDoubleTimeAfterMinutes': 480, 'weeklyRegularMinutes': 2400,
            'doubleTimeMultiple': 2, 'firstMealMinutes': 60, 'firstMealAfterMinutes': 300, 'secondMealMinutes': 30,
            'secondMealAfterMinutes': 600, 'weekendOvertime': True}


def meals(span):
    """The unpaid meal minutes of a technician-day span minutes long from depot to depot (PayRule.meals)."""
    first = PAY_RULE['firstMealMinutes'] if span > PAY_RULE['firstMealAfterMinutes'] else 0
    second = PAY_RULE['secondMealMinutes'] if first and span - first > PAY_RULE['secondMealAfterMinutes'] else 0
    return first + second


class SummaryError(ValueError):
    """Input the summary refuses rather than guess around."""


def read_json(path):
    with Path(path).open(encoding='utf-8') as stream:
        return json.load(stream)


def whole(value, name):
    """A required whole number; a missing or non-integer value is refused, never read as zero."""
    if isinstance(value, bool) or not isinstance(value, int):
        raise SummaryError(f'{name} must be a whole number, got {value!r}')
    return value


def flag(value, name):
    if not isinstance(value, bool):
        raise SummaryError(f'{name} must be true or false, got {value!r}')
    return value


# ----------------------------------------------------------------------------------------------------------------------
# The scoring job


def load_index(run, rule='as-run'):
    index = read_json(Path(run) / 'index.json')
    if index.get('ruleSet') != rule:
        raise SummaryError(f'This summary reads the {rule} rule set, not {index.get("ruleSet")!r}')
    days = index['days']
    if not days:
        raise SummaryError('The run has no days')
    return index


def job(run, rows_path):
    """The backtest-score-days job for every day of the run, in date order."""
    index = load_index(run)
    days = [{'day': entry['day'], 'directory': str((Path(run) / 'days' / entry['day']).resolve())} for entry in sorted(index['days'], key=lambda e: e['day'])]
    return {'version': 1, 'kind': 'backtest-score-days', 'dataset': 'facts.json', 'plan': 'as-planned.plan.json', 'days': days,
            'output': str(Path(rows_path).resolve())}


# ----------------------------------------------------------------------------------------------------------------------
# Reading


def is_weekend(day):
    try:
        return dt.date.fromisoformat(day).weekday() >= 5
    except (TypeError, ValueError):
        raise SummaryError(f'{day!r} is not a date') from None


def checked_row(day, row):
    for name in ROW_FIELDS:
        if name not in row:
            raise SummaryError(f'{day}: the score row lacks {name}')
    for name in ROW_FIELDS:
        if name in ('feasible', 'routesHold'):
            flag(row[name], f'{day} {name}')
        elif name != 'routes':
            whole(row[name], f'{day} {name}')
    for route in row['routes']:
        for name in ROUTE_FIELDS:
            if name not in route:
                raise SummaryError(f'{day}: a route reading lacks {name}')
            if name == 'holds':
                flag(route[name], f'{day} route holds')
            elif name != 'technicianId':
                whole(route[name], f'{day} route {name}')
    return row


def check_pay(by_day):
    """The rows hold the pay rule they are marked with. The scorer paid every technician's week over the days of its
    file, so the same walk over the same rows must give the same minutes: the meal periods of each route; on a weekday
    the overtime past 8 hours, the double time past 12 and the regular time past the week's 40 hours so far; on a
    weekend every paid minute as overtime, with double time past 12 hours, or past 8 on a seventh day in a row."""
    weeks = defaultdict(int)
    worked = defaultdict(int)
    for day in sorted(by_day):
        row = by_day[day]
        weekend = is_weekend(day)
        date = dt.date.fromisoformat(day)
        monday = date - dt.timedelta(days=date.weekday())
        technicians = set()
        for route in row['routes']:
            technician, paid, unpaid = route['technicianId'], route['paidMinutes'], route['unpaidMinutes']
            if technician in technicians:
                raise SummaryError(f'{day}: two routes of {technician}')
            technicians.add(technician)
            if unpaid != meals(paid + unpaid):
                raise SummaryError(f'{day}: {technician} has {unpaid} unpaid minutes on a day of {paid + unpaid} minutes depot to depot')
            key = (technician, monday)
            seventh = date.weekday() == 6 and worked[key] == 6
            if (weekend and PAY_RULE['weekendOvertime']) or seventh:
                if route['overtimeMinutes'] != paid:
                    raise SummaryError(f'{day}: a weekend day whose paid minutes are not all overtime')
                overtime = paid
                double = max(0, paid - PAY_RULE['seventhDayDoubleTimeAfterMinutes' if seventh else 'dailyDoubleTimeAfterMinutes'])
            else:
                double = max(0, paid - PAY_RULE['dailyDoubleTimeAfterMinutes'])
                daily = max(0, min(paid, PAY_RULE['dailyDoubleTimeAfterMinutes']) - PAY_RULE['dailyRegularMinutes'])
                regular = paid - daily - double
                weeks[key] += regular
                overtime = daily + double + max(0, min(regular, weeks[key] - PAY_RULE['weeklyRegularMinutes']))
            if paid > 0:
                worked[key] += 1
            if (route['overtimeMinutes'], route['doubleTimeMinutes']) != (overtime, double):
                raise SummaryError(f'{day}: {technician} has {route["overtimeMinutes"]} overtime minutes ({route["doubleTimeMinutes"]} double time) '
                                   f'where the rule gives {overtime} ({double})')
        if weekend and (row['overtimeMinutes'] != row['paidMinutes'] or row['regularCents'] != 0):
            raise SummaryError(f'{day}: a weekend day whose paid minutes are not all overtime')
        for name in ('paidMinutes', 'unpaidMinutes', 'overtimeMinutes', 'doubleTimeMinutes'):
            if row[name] != sum(route[name] for route in row['routes']):
                raise SummaryError(f"{day}: the routes do not sum to the day's {name}")


def load(run, rows_path, rule='as-run', plan='as-planned.plan.json', directories=None):
    """Every day of the run with its score row and observed visits: [(entry, row, observed)] in date order. directories
    maps a day to the directory its observed.json is in when that is not <run>/days/<day> (Part 1 on Part 2's jobs)."""
    index = load_index(run, rule)
    scored = read_json(rows_path)
    if scored.get('kind') != 'backtest-score-days' or scored.get('plan') != plan:
        raise SummaryError(f'The rows are not a backtest-score-days reading of {plan}')
    # Compared as written, so a rule of another type (1 for true) is another rule.
    if json.dumps(scored.get('payRule'), sort_keys=True) != json.dumps(PAY_RULE, sort_keys=True):
        raise SummaryError('The rows were not paid under the weekly pay rule (California overtime and meal periods, weekends as overtime); '
                           'score the days again')
    by_day = {}
    for item in scored['rows']:
        if item['day'] in by_day:
            raise SummaryError(f'{item["day"]} is scored twice')
        by_day[item['day']] = checked_row(item['day'], item['row'])
    check_pay(by_day)
    entries = sorted(index['days'], key=lambda e: e['day'])
    missing = [e['day'] for e in entries if e['day'] not in by_day]
    if missing:
        raise SummaryError(f'{len(missing)} days have no score row, the first {missing[0]}')
    extra = sorted(set(by_day) - {e['day'] for e in entries})
    if extra:
        raise SummaryError(f'Score rows for days the run does not have, the first {extra[0]}')
    days = []
    for entry in entries:
        directory = (directories or {}).get(entry['day'], Path(run) / 'days' / entry['day'])
        observed = read_json(Path(directory) / 'observed.json')
        row = by_day[entry['day']]
        visits = sum(route['visits'] for route in row['routes'])
        if visits != entry['jobs'] or len(observed['visits']) != entry['jobs']:
            raise SummaryError(f'{entry["day"]}: {entry["jobs"]} jobs in the index, {visits} in the routes, {len(observed["visits"])} observed')
        days.append((entry, row, observed))
    return index, days


# ----------------------------------------------------------------------------------------------------------------------
# Periods


def periods(day):
    """The study, year, quarter and month keys of a date."""
    quarter = (day.month - 1) // 3 + 1
    return ['study', f'{day.year}', f'{day.year}-Q{quarter}', f'{day.year}-{day.month:02d}']


def period_order(keys):
    """The study first, then years, quarters and months, each in time order."""
    def rank(key):
        if key == 'study':
            return (0, key)
        if len(key) == 4:
            return (1, key)
        if '-Q' in key:
            return (2, key)
        return (3, key)
    return sorted(keys, key=rank)


def weekdays_between(start, end):
    """Monday-to-Friday dates after start up to and including end: 0 for the same day, 1 for the next weekday."""
    if end < start:
        raise SummaryError(f'Service on {end} is before the request on {start}')
    count, day = 0, start
    while day < end:
        day += dt.timedelta(days=1)
        count += day.weekday() < 5
    return count


def group_of(visit, sales_codes):
    if visit['tags'][0] in EMERGENCY_CODES:
        return 'emergency'
    if visit['salesCall'] or visit['tags'][0] in sales_codes:
        return 'sales'
    return 'other'


# ----------------------------------------------------------------------------------------------------------------------
# Measures


def route_measures(days):
    """Per period: totals of the dispatcher's days, technician-days and weeks."""
    totals = defaultdict(lambda: defaultdict(int))
    weeks = defaultdict(lambda: defaultdict(int))
    technician_days = defaultdict(list)
    for entry, row, _ in days:
        day = dt.date.fromisoformat(entry['day'])
        worked = [route for route in row['routes'] if route['visits'] > 0]
        monday = day - dt.timedelta(days=day.weekday())
        for key in periods(day):
            t = totals[key]
            t['days'] += 1
            t['jobs'] += entry['jobs']
            t['technicianDays'] += len(worked)
            t['overtimeTechnicianDays'] += sum(1 for route in worked if route['overtimeMinutes'] > 0)
            t['routesNotHolding'] += sum(1 for route in worked if not route['holds'])
            for name in ('costCents', 'regularCents', 'overtimeCents', 'mileageCents', 'paidMinutes', 'overtimeMinutes', 'doubleTimeMinutes', 'unpaidMinutes', 'driveMinutes',
                         'meters', 'latenessSeconds', 'lateVisits', 'pastShiftMinutes', 'unreachableLegs'):
                t[name] += row[name]
            technician_days[key] += [(route['paidMinutes'], route['driveMinutes'], route['overtimeMinutes']) for route in worked]
        week = weeks[monday.isoformat()]
        for name in ('costCents', 'paidMinutes', 'overtimeMinutes', 'doubleTimeMinutes', 'unpaidMinutes', 'driveMinutes', 'meters'):
            week[name] += row[name]
        week['jobs'] += entry['jobs']
    return totals, technician_days, weeks


def weekend_reading(days):
    """What was served on Saturdays and Sundays; every count is zero when nothing was served there."""
    weekend = [d for d in days if is_weekend(d[0]['day'])]
    totals = route_measures(weekend)[0]['study'] if weekend else {}
    return {name: totals[name] if weekend else 0 for name in ('days', 'jobs', 'technicianDays', 'paidMinutes', 'overtimeMinutes', 'costCents')}


def quantile(values, share):
    """The nearest-rank quantile of a non-empty list."""
    ordered = sorted(values)
    rank = max(1, -(-len(ordered) * share // 1))
    return ordered[int(rank) - 1]


def promptness(days, sales_codes):
    """Per period of the request date and group: the lead of every job in calendar days and weekdays."""
    leads = defaultdict(list)
    unknown = defaultdict(int)
    for entry, _, observed in days:
        served = dt.date.fromisoformat(entry['day'])
        for visit in observed['visits'].values():
            group = group_of(visit, sales_codes)
            lead = visit['requestLeadDays']
            if lead is None:
                unknown[('study', group)] += 1
                unknown[('study', 'all')] += 1
                continue
            lead = whole(lead, f'{entry["day"]} requestLeadDays')
            if lead < 0:
                raise SummaryError(f'{entry["day"]}: a job served {-lead} days before its request')
            requested = served - dt.timedelta(days=lead)
            reading = (lead, weekdays_between(requested, served))
            for key in periods(requested):
                leads[(key, group)].append(reading)
                leads[(key, 'all')].append(reading)
    result = {}
    for (key, group), readings in leads.items():
        calendar = [r[0] for r in readings]
        working = [r[1] for r in readings]
        result.setdefault(key, {})[group] = {
            'jobs': len(readings), 'medianDays': statistics.median(calendar), 'p90Days': quantile(calendar, 0.9),
            'medianWeekdays': statistics.median(working), 'p90Weekdays': quantile(working, 0.9),
            'sameDay': sum(1 for d in calendar if d == 0), 'nextDay': sum(1 for d in calendar if d == 1),
            'within5Weekdays': sum(1 for w in working if w <= 5)}
    return result, {f'{key}/{group}': count for (key, group), count in unknown.items()}


def by_technician(days):
    """Study totals per technician, with the depots each worked from."""
    result = defaultdict(lambda: defaultdict(int))
    depots = defaultdict(set)
    for entry, row, _ in days:
        for route in row['routes']:
            if route['visits'] == 0:
                continue
            t = result[route['technicianId']]
            t['technicianDays'] += 1
            t['overtimeTechnicianDays'] += route['overtimeMinutes'] > 0
            for name in ('visits', 'paidMinutes', 'overtimeMinutes', 'driveMinutes', 'meters', 'lateVisits'):
                t[name] += route[name]
            if 'depots' not in entry or route['technicianId'] not in entry['depots']:
                raise SummaryError(f'{entry["day"]}: the index names no depot for {route["technicianId"]}')
            depots[route['technicianId']].add(entry['depots'][route['technicianId']])
    return {tech: dict(values, depots=sorted(depots[tech])) for tech, values in sorted(result.items())}


def summarize(run, rows_path, sales_codes=('X01',)):
    index, days = load(run, rows_path)
    totals, technician_days, weeks = route_measures(days)
    prompt, unknown = promptness(days, set(sales_codes))
    last = max(dt.date.fromisoformat(entry['day']) for entry, _, _ in days)
    route = {}
    for key, t in totals.items():
        paid = [p for p, _, _ in technician_days[key]]
        drive = [d for _, d, _ in technician_days[key]]
        route[key] = dict(t, medianPaidPerTechnicianDay=statistics.median(paid) if paid else None,
                          medianDrivePerTechnicianDay=statistics.median(drive) if drive else None)
    week_rows = [dict(values, week=monday) for monday, values in sorted(weeks.items())]
    return {'runId': index['runId'], 'ruleSet': index['ruleSet'], 'routingIdentity': index['routingIdentity'], 'lastServiceDate': last.isoformat(),
            'days': len(days), 'periods': period_order(route), 'route': route, 'weekend': weekend_reading(days), 'weeks': week_rows, 'promptness': prompt,
            'promptnessUnknown': unknown, 'technicians': by_technician(days), 'ledger': index['ledger']}


# ----------------------------------------------------------------------------------------------------------------------
# Report


def dollars(cents):
    return f'{Decimal(cents) / 100:,.2f}'


def number(value, places=1):
    if value is None:
        return 'n/a'
    if isinstance(value, int):
        return f'{value:,}'
    return f'{value:,.{places}f}'


def miles(meters):
    return float(Decimal(meters) / METERS_PER_MILE)


def label(key, last):
    """A period's name, marked when the data ends inside it."""
    partial = {f'{last.year}', f'{last.year}-Q{(last.month - 1) // 3 + 1}', f'{last.year}-{last.month:02d}'}
    if key == 'study':
        return 'Study'
    return f'{key} (to {last.isoformat()})' if key in partial else key


TITLE = 'Paso Robles backtest, Part 1: the dispatcher as run'


def checked_title(title):
    """A report title is one line of text without a leading '#': the report writes it as its H1."""
    if not isinstance(title, str) or not title.strip() or '\n' in title or title.lstrip().startswith('#') or title != title.strip():
        raise SummaryError(f'A report title is one trimmed line without a leading #: {title!r}')
    return title


def report(result, title=TITLE):
    last = dt.date.fromisoformat(result['lastServiceDate'])
    keys = result['periods']
    r = result['route']
    w = result['weekend']
    weekend = (f'Of the study\'s overtime, {number(w["overtimeMinutes"] / 60)} hours are weekend work: {number(w["technicianDays"])} technician-days on '
               f'{number(w["days"])} Saturdays and Sundays with {number(w["jobs"])} jobs, costing ${dollars(w["costCents"])} with their mileage.'
               if w['days'] else 'No job was served on a Saturday or a Sunday.')
    lines = [f'# {checked_title(title)}', '',
             f'Generated by `infra/backtest_dispatcher.py` from run `{result["runId"]}` (rule set `{result["ruleSet"]}`, routing identity `{result["routingIdentity"][:12]}`). Every kept job is scored at its booked time in the dispatcher\'s order, each technician-day starting and ending at its technician\'s depot, shifts 08:00 to 17:00, with lateness and work past 17:00 measured rather than refused. Pay follows the dealership\'s rule: unpaid meal periods as California law sets them, with no waiver (a 1 hour lunch once a technician-day passes 5 hours of work, and 30 minutes more once it passes 10), overtime as California sets it (paid time past 8 hours in a day, double time past 12, regular time past 40 hours in a Monday-to-Friday week, and double time past 8 hours on a seventh day in a row), and overtime for every paid minute of a Saturday or a Sunday. Job lengths are the booked durations, which can run longer than the work took, so overtime here is the booked load, not the hours paid. Route measures fall in the period of the service date; promptness falls in the period of the request date. The data ends on {last.isoformat()}, so that year, quarter and month are partial.', '',
             '## Routes', '',
             f'The table shows the dispatcher\'s days, technician-days with work, jobs, driving, miles and paid hours in each period. Paid hours leave out the unpaid meal periods, {number(r["study"]["unpaidMinutes"] / 60)} hours in the study.', '',
             table(['Period', 'Days', 'Tech-days', 'Jobs', 'Drive (h)', 'Miles', 'Paid (h)', 'Paid per tech-day (min)'],
                   [[label(k, last), number(r[k]['days']), number(r[k]['technicianDays']), number(r[k]['jobs']), number(r[k]['driveMinutes'] / 60),
                     number(miles(r[k]['meters']), 0), number(r[k]['paidMinutes'] / 60), number(r[k]['medianPaidPerTechnicianDay'], 0)] for k in keys], text=(0,)), '',
             'The same rows per job:', '',
             table(['Period', 'Drive per job (min)', 'Miles per job', 'Cost per job ($)', 'Median drive per tech-day (min)'],
                   [[label(k, last), number(r[k]['driveMinutes'] / r[k]['jobs']), number(miles(r[k]['meters']) / r[k]['jobs']),
                     dollars(round(Decimal(r[k]['costCents']) / r[k]['jobs'])), number(r[k]['medianDrivePerTechnicianDay'], 0)] for k in keys], text=(0,)), '',
             '## Overtime and cost', '',
             'The table shows overtime, the double time within it, how many technician-days had any, and the day cost. Overtime on a weekday is the technician\'s paid time past 8 hours in the day, with double time past 12, as California law sets it; the regular time left would be overtime past 40 hours in the Monday-to-Friday week, which five days of at most 8 regular hours never pass. On a Saturday or a Sunday it is every paid minute, since weekend work is overtime under the dealership\'s rule, with double time past 12 hours, or past 8 on a technician\'s seventh day of work in a row; those minutes stay out of the week\'s 40 hours. Work after 17:00 is not overtime in itself; it is counted under customer windows and timing. The day cost is split into regular time, overtime ($45 an hour, double time $60) and mileage. Each part is priced and rounded on its own, so the parts can differ from the total by a cent or two per day.', '',
             table(['Period', 'Overtime tech-days', 'Overtime (h)', 'Double time (h)', 'Overtime ($)', 'Regular ($)', 'Mileage ($)', 'Total ($)'],
                   [[label(k, last), number(r[k]['overtimeTechnicianDays']), number(r[k]['overtimeMinutes'] / 60), number(r[k]['doubleTimeMinutes'] / 60),
                     dollars(r[k]['overtimeCents']),
                     dollars(r[k]['regularCents']), dollars(r[k]['mileageCents']), dollars(r[k]['costCents'])] for k in keys], text=(0,)), '',
             weekend, '',
             '## Customer windows and timing', '',
             'The table shows arrivals after the end of the 2-hour window, the minutes they were late in total, the minutes worked outside the shift (past 17:00 or before 08:00), the technician-days whose route does not hold as booked, and legs with no road.', '',
             table(['Period', 'Late visits', 'Minutes late', 'Outside shift (min)', 'Routes not holding', 'Legs with no road'],
                   [[label(k, last), number(r[k]['lateVisits']), number(r[k]['latenessSeconds'] / 60, 0), number(r[k]['pastShiftMinutes']),
                     number(r[k]['routesNotHolding']), number(r[k]['unreachableLegs'])] for k in keys], text=(0,)), '',
             '## Promptness', '',
             'Each table shows, by the period of the request date, the days from `repair_req_date` to `actual_fix_date`: the median and 90th percentile in calendar days and in weekdays (holidays count as weekdays), and how many jobs were served the same day, the next calendar day and within 5 weekdays.', '']
    names = {'all': 'Every job', 'emergency': 'Emergencies (' + ', '.join(EMERGENCY_CODES) + ')', 'sales': 'Sales calls', 'other': 'Other jobs'}
    for group in GROUPS:
        rows = []
        for k in keys:
            p = result['promptness'].get(k, {}).get(group)
            if p is None:
                continue
            rows.append([label(k, last), number(p['jobs']), number(float(p['medianDays'])), number(p['p90Days']), number(float(p['medianWeekdays'])),
                         number(p['p90Weekdays']), number(p['sameDay']), number(p['nextDay']), number(p['within5Weekdays'])])
        lines += [f'### {names[group]}', '', f'{names[group]} by request period:', '',
                  table(['Period', 'Jobs', 'Median (days)', 'p90 (days)', 'Median (weekdays)', 'p90 (weekdays)', 'Same day', 'Next day', 'Within 5 weekdays'], rows, text=(0,)), '']
    first_year = min(k for k in keys if len(k) == 4)
    earlier = sum(p['all']['jobs'] for k, p in result['promptness'].items() if len(k) == 4 and k < first_year)
    if earlier:
        lines += [f"{earlier:,} jobs were requested before {first_year} (the dispatcher's opening backlog); they count in the Study rows only.", '']
    unknown = sum(v for k, v in result['promptnessUnknown'].items() if k.endswith('/all'))
    if unknown:
        lines += [f'{unknown} jobs have no request date and are left out of promptness.', '']
    t = result['technicians']
    lines += ['## By technician', '',
              'The table shows each technician\'s study totals and the depots they worked from.', '',
              table(['Technician', 'Depots', 'Tech-days', 'Jobs', 'Paid per tech-day (min)', 'Drive per job (min)', 'Overtime (h)', 'Overtime tech-days', 'Late visits'],
                    [[tech, ', '.join(v['depots']), number(v['technicianDays']), number(v['visits']), number(v['paidMinutes'] / v['technicianDays'], 0),
                      number(v['driveMinutes'] / v['visits']) if v['visits'] else 'n/a', number(v['overtimeMinutes'] / 60), number(v['overtimeTechnicianDays']),
                      number(v['lateVisits'])] for tech, v in t.items()]), '']
    ledger = result['ledger']
    lines += ['## Rows left out', '',
              f'Of {ledger["rows"]:,} history rows, {ledger["kept"]:,} are scored and {ledger["excludedRows"]:,} are left out. A row can carry more than one reason, so the counts below can sum to more than the rows left out.', '',
              table(['Reason', 'Rows'], [[reason, number(count)] for reason, count in sorted(ledger['reasons']['exclude'].items())]), '']
    return '\n'.join(lines).rstrip('\n') + '\n'


def write_json(path, document):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('w', encoding='utf-8', newline='\n') as stream:
        json.dump(document, stream, indent=1, sort_keys=True)
        stream.write('\n')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    job_cmd = commands.add_parser('job')
    job_cmd.add_argument('--run', required=True)
    job_cmd.add_argument('--rows', required=True)
    job_cmd.add_argument('--job', required=True)
    summary_cmd = commands.add_parser('summarize')
    summary_cmd.add_argument('--run', required=True)
    summary_cmd.add_argument('--rows', required=True)
    summary_cmd.add_argument('--out', required=True)
    summary_cmd.add_argument('--title', default=TITLE, help='the report\'s H1, for a backtest other than the first')
    args = parser.parse_args(argv)
    if args.command == 'job':
        document = job(args.run, args.rows)
        write_json(args.job, document)
        print(json.dumps({'days': len(document['days']), 'job': args.job}))
        return 0
    config = read_json(Path(args.run) / 'study-config.json')
    title = checked_title(args.title)
    result = summarize(args.run, args.rows, tuple(config['salesCallCodes']))
    out = Path(args.out)
    write_json(out / 'evidence.json', result)
    (out / 'report.md').write_text(report(result, title), encoding='utf-8', newline='\n')
    print(json.dumps({'days': result['days'], 'report': str(out / 'report.md')}))
    return 0


if __name__ == '__main__':
    sys.exit(main())
