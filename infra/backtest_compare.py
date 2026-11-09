"""Part 2 against Part 1: the comparison, the validity bars and the report (simulation-plan.md, "Comparison" and
"Validity bars").

Reads a scored export (backtest_export.py): Part 1 is the dispatcher's as-run days on Part 2's jobs, Part 2 the
simulated book; both scored by the same scorer. Every measure is the Part 1 summary's own (backtest_dispatcher.py), so
the two parts are read the same way. Promptness is read per request, on the same set for both: every request Part 2
booked (its outcome from the segment that owns its request date), Part 1 at the dispatcher's service date and Part 2 at
the booked date, with the paired difference per request. Days to service are calendar days in both parts, weekends
included. A plan without weekend work (backtest_simulate.py, run_options) adds two bars: no Part 2 job on a Saturday or
Sunday, on the scored days and in the booked outcomes.

    python infra/backtest_compare.py compare --simulation <dir> --out research/<study>/part2 [--segment <copy>]
    python infra/backtest_compare.py calibration --alone <run A dir> --together <run B dir> --out research/<study>/calibration

compare writes report.md and evidence.json to --out. calibration reads run A (the month alone, one copy) and run B (four
copies at once: two with a 3-week warm-up, b1 and b2, and two with a 6-week one, c1 and c2), each copy exported and
scored on its own, and applies the plan's contention and warm-up bars. Stdlib only.
"""
import argparse
import datetime as dt
import json
import statistics
import sys
from collections import Counter, defaultdict
from decimal import Decimal
from pathlib import Path

import backtest_dispatcher as bd
import backtest_simulate as bs
from load_balance_study import interval
from markdown_tables import table

WEEK_MEASURES = (('driveMinutes', 'Drive (min)'), ('paidMinutes', 'Paid (min)'), ('costCents', 'Cost (cents)'))
PROPOSAL_LIMIT_MS = 300_000


class CompareError(ValueError):
    pass


def read_lines(path):
    path = Path(path)
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line]


def load(sim_dir, only=None):
    sim_dir = Path(sim_dir)
    out = sim_dir / ('export' if only is None else f'export-{only}')
    config = bd.read_json(sim_dir / 'simulation.json')
    if only is not None:
        config = dict(config, segments=[s for s in config['segments'] if s['name'] == only])
    outcomes = bd.read_json(out / 'outcomes.json')
    job1 = bd.read_json(out / 'part1-job.json')
    directories = {d['day']: d['directory'] for d in job1['days']}
    _, part1 = bd.load(out / 'part1', out / 'part1' / 'rows.json', directories=directories)
    _, part2 = bd.load(out / 'part2', out / 'part2' / 'rows.json', rule='part2', plan='plan.json')
    bookings, proposals = [], []
    for segment in config['segments']:
        directory = sim_dir / 'segments' / segment['name']
        bookings += [dict(r, segment=segment['name']) for r in read_lines(directory / 'bookings.jsonl')]
        proposals += [dict(r, segment=segment['name']) for r in read_lines(directory / 'proposals.jsonl')]
    run = Path(config['run'])
    requests = {r['id']: r for r in bd.read_json(run / 'part2' / 'requests.json')['requests']}
    return config, outcomes, part1, part2, bookings, proposals, requests


# ----------------------------------------------------------------------------------------------------------------------
# Readings


is_weekend = bd.is_weekend
# What a part served on Saturdays and Sundays. bd.load has already refused rows that do not hold the pay rule.
weekend_reading = bd.weekend_reading


def weekend_bookings(outcomes):
    """The booked requests whose Part 2 service date is a Saturday or Sunday."""
    found = []
    for identifier, outcome in outcomes.items():
        if outcome['outcome'] == 'BOOKED':
            if not outcome.get('serviceDate'):
                raise CompareError(f'{identifier} is booked without a service date')
            if is_weekend(outcome['serviceDate']):
                found.append(identifier)
    return sorted(found)


def validity(part2, bookings, proposals, outcomes=None, weekend_work=True):
    """Each bar of the plan with its count of failures; the run is valid when every count is zero. A plan without weekend
    work adds its two bars, which need the outcomes."""
    refinements = Counter(b.get('refinement') for b in bookings)
    bars = {
        'bookingShed': refinements.get('SHED', 0),
        'bookingSkipped': refinements.get('SKIPPED', 0),
        'bookingUnavailable': refinements.get('UNAVAILABLE', 0),
        'bookingDeadlineStops': sum(1 for b in bookings if b.get('refinement') == 'SHORTENED' and b.get('refinementStop') == 'DEADLINE'),
        'searchIncomplete': sum(1 for b in bookings if not b['searchComplete']),
        'proposalsOver300s': sum(1 for p in proposals if p.get('outcome') == 'PROPOSED' and p['elapsedMs'] > PROPOSAL_LIMIT_MS),
        'improvedNotCommitted': sum(1 for p in proposals if p.get('decision') == 'IMPROVED' and not p.get('committed')),
        'daysNotHolding': sum(1 for _, row, _ in part2 if not row['feasible']),
        'lateVisits': sum(row['lateVisits'] for _, row, _ in part2),
        'outsideShiftMinutes': sum(row['pastShiftMinutes'] for _, row, _ in part2),
        'overtimeMinutes': sum(row['overtimeMinutes'] for _, row, _ in part2),
    }
    if not weekend_work:
        if outcomes is None:
            raise CompareError('A plan without weekend work is checked against the outcomes, and none were given')
        bars['weekendJobs'] = weekend_reading(part2)['jobs']
        bars['weekendBookings'] = len(weekend_bookings(outcomes))
    return {'bars': bars, 'valid': all(v == 0 for v in bars.values()),
            'capStops': sum(1 for b in bookings if b.get('refinementStop') == 'CAP')}


def week_differences(weeks1, weeks2):
    """Per period (study, years, quarters): every week both parts have, Part 2 minus Part 1, with its interval."""
    paired = sorted(set(weeks1) & set(weeks2))
    result = {}
    for name, _ in WEEK_MEASURES:
        by_period = defaultdict(list)
        for monday in paired:
            difference = weeks2[monday][name] - weeks1[monday][name]
            for key in bd.periods(dt.date.fromisoformat(monday))[:3]:
                by_period[key].append(difference)
        result[name] = {key: {'weeks': len(values), 'median': statistics.median(values), 'interval': interval(values),
                              'better': sum(1 for v in values if v < 0), 'tied': sum(1 for v in values if v == 0), 'worse': sum(1 for v in values if v > 0)}
                        for key, values in by_period.items()}
    return result, {'paired': len(paired), 'part1Only': len(set(weeks1) - set(weeks2)), 'part2Only': sorted(set(weeks2) - set(weeks1))}


def booked(outcomes, requests, keep=lambda request: True):
    """Every request Part 2 booked, with its request: the one set both parts' promptness is read on."""
    for identifier, outcome in outcomes.items():
        if identifier not in requests:
            raise CompareError(f'The outcome of {identifier} has no request')
        request = requests[identifier]
        if outcome['outcome'] == 'BOOKED' and keep(request):
            if not outcome.get('serviceDate'):
                raise CompareError(f'{identifier} is booked without a service date')
            if not (request.get('dispatcher') or {}).get('serviceDate'):
                raise CompareError(f'{identifier} has no dispatcher service date')
            yield identifier, request, outcome


def promptness_on(served, outcomes, requests, keep):
    """bd.promptness of the booked requests, each served on served(request, outcome): one synthetic day per service date."""
    days = defaultdict(dict)
    for identifier, request, outcome in booked(outcomes, requests, keep):
        day = served(request, outcome)
        lead = (dt.date.fromisoformat(day) - dt.date.fromisoformat(request['requestDate'])).days
        days[day][identifier] = {'tags': request['tags'], 'salesCall': request['salesCall'], 'requestLeadDays': lead}
    return bd.promptness([({'day': day}, None, {'visits': visits}) for day, visits in sorted(days.items())], {'X01'})[0]


def part2_promptness(outcomes, requests, keep=lambda request: True):
    """Part 2's promptness: each booked request at the date Part 2 booked it for."""
    return promptness_on(lambda request, outcome: outcome['serviceDate'], outcomes, requests, keep)


def part1_promptness(outcomes, requests, keep=lambda request: True):
    """The dispatcher's promptness on the same requests: each at the dispatcher's service date, whether or not that date is
    among the scored days (reading only the scored days would drop the requests the dispatcher served late in a short run)."""
    return promptness_on(lambda request, outcome: request['dispatcher']['serviceDate'], outcomes, requests, keep)


def paired_leads(outcomes, requests):
    """Per booked request, Part 2's service date minus the dispatcher's in days, by period of the request date."""
    by_period = defaultdict(list)
    for _, request, outcome in booked(outcomes, requests):
        difference = (dt.date.fromisoformat(outcome['serviceDate']) - dt.date.fromisoformat(request['dispatcher']['serviceDate'])).days
        for key in bd.periods(dt.date.fromisoformat(request['requestDate'])):
            by_period[key].append(difference)
    return {key: {'requests': len(values), 'median': statistics.median(values), 'sooner': sum(1 for v in values if v < 0),
                  'same': sum(1 for v in values if v == 0), 'later': sum(1 for v in values if v > 0)}
            for key, values in by_period.items()}


def long_lead(request):
    """The dispatcher served it more than 30 days after the request."""
    return (dt.date.fromisoformat(request['dispatcher']['serviceDate']) - dt.date.fromisoformat(request['requestDate'])).days > 30


def booking_health(bookings):
    searched = [b for b in bookings]
    elapsed = [b['elapsedMs'] for b in searched]
    regular = [b['regularWindowsFound'] for b in searched if b.get('regularWindowsFound') is not None]
    lead = [b['leadDays'] for b in searched if b['outcome'] == 'BOOKED']
    q = bd.quantile
    return {'searches': len(searched), 'refinement': dict(sorted(Counter(str(b.get('refinement')) for b in searched).items())),
            'capStops': sum(1 for b in searched if b.get('refinementStop') == 'CAP'),
            'elapsedMs': {'median': statistics.median(elapsed), 'p95': q(elapsed, 0.95), 'max': max(elapsed)} if elapsed else None,
            'regularWindowsFound': {'median': statistics.median(regular), 'p10': q(regular, 0.1), 'p90': q(regular, 0.9)} if regular else None,
            'offerLeadDays': {'median': statistics.median(lead), 'p90': q(lead, 0.9), 'max': max(lead)} if lead else None,
            'horizonDates': {'median': statistics.median(b['horizon']['dates'] for b in searched), 'capped': sum(1 for b in searched if b['horizon']['capped'])}
            if searched else None,
            'outcomes': dict(sorted(Counter(b['outcome'] for b in searched).items()))}


def overnight(proposals):
    nights = Counter((p['segment'], p['night']) for p in proposals if p.get('outcome') == 'PROPOSED')
    proposed = [p for p in proposals if p.get('outcome') == 'PROPOSED']
    elapsed = [p['elapsedMs'] for p in proposed]
    return {'nights': len(nights), 'datesPerNight': {'median': statistics.median(nights.values()), 'max': max(nights.values())} if nights else None,
            'proposals': len(proposed), 'decisions': dict(sorted(Counter(p['decision'] for p in proposed).items())),
            'committed': sum(1 for p in proposed if p.get('committed')), 'moved': sum(p.get('moved', 0) for p in proposed),
            'skipped': dict(sorted(Counter(p['outcome'] for p in proposals if p.get('outcome') != 'PROPOSED').items())),
            'elapsedMs': {'median': statistics.median(elapsed), 'p95': bd.quantile(elapsed, 0.95), 'max': max(elapsed)} if elapsed else None}


def compare(sim_dir, only=None):
    config, outcomes, part1, part2, bookings, proposals, requests = load(sim_dir, only)
    try:
        weekend_work, in_flight = bs.run_options(config)
    except bs.SimulationError as error:
        raise CompareError(str(error)) from error
    history_last = config['historyLastDate']
    totals1, techdays1, weeks1 = bd.route_measures(part1)
    totals2, techdays2, weeks2 = bd.route_measures(part2)
    in_data = [d for d in part2 if d[0]['day'] <= history_last]
    tail = [d for d in part2 if d[0]['day'] > history_last]
    within, _, _ = bd.route_measures(in_data)
    tail_totals = bd.route_measures(tail)[0].get('study') if tail else None
    weekly, pairing = week_differences(weeks1, weeks2)
    decided = outcomes['outcomes']
    return {'simulationId': config['simulationId'], 'partial': outcomes['partial'], 'routingIdentity': config['routingIdentity'],
            'historyLastDate': history_last, 'segments': config['segments'], 'settings': config['settings'],
            'weekendWork': weekend_work, 'overnightInFlight': in_flight, 'payRule': dict(bd.PAY_RULE),
            'validity': validity(part2, bookings, proposals, decided, weekend_work),
            'weekends': {'part1': weekend_reading(part1), 'part2': weekend_reading(part2)},
            'periods': bd.period_order(set(totals1) | set(totals2)),
            'part1': {k: dict(v) for k, v in totals1.items()}, 'part2': {k: dict(v) for k, v in totals2.items()},
            'part2WithinData': {k: dict(v) for k, v in within.items()}, 'tail': dict(tail_totals) if tail_totals else None,
            'weekly': weekly, 'pairing': pairing,
            'promptness': {'part1': part1_promptness(decided, requests), 'part2': part2_promptness(decided, requests),
                           'part1WithoutLongLeads': part1_promptness(decided, requests, lambda r: not long_lead(r)),
                           'part2WithoutLongLeads': part2_promptness(decided, requests, lambda r: not long_lead(r)),
                           'paired': paired_leads(decided, requests)},
            'demand': {'counts': outcomes['counts'], 'undecided': len(outcomes['undecided']),
                       'calledBack': sum(1 for o in decided.values() if o['attempts'] > 1),
                       'turnedAwayEmergencies': sum(1 for i, o in decided.items() if o['outcome'] in ('TURNED_AWAY', 'END_OF_DATA')
                                                    and requests[i]['tags'][0] in bd.EMERGENCY_CODES)},
            'boundaries': {k: len(v) for k, v in outcomes['boundaries'].items()},
            'booking': booking_health(bookings), 'overnight': overnight(proposals)}


# ----------------------------------------------------------------------------------------------------------------------
# Report

number, dollars = bd.number, bd.dollars


def per_job(t, name, scale=1):
    return number(t[name] / scale / t['jobs']) if t and t['jobs'] else 'n/a'


def period_start(key):
    if len(key) == 4:
        return dt.date(int(key), 1, 1)
    if '-Q' in key:
        return dt.date(int(key[:4]), 3 * int(key[-1]) - 2, 1)
    return dt.date(int(key[:4]), int(key[5:]), 1)


def period_label(key, last):
    """bd.label, and a period that starts after the history marked as after the data."""
    if key != 'study' and period_start(key) > last:
        return f'{key} (after the data)'
    return bd.label(key, last)


def both(t1, t2, key, name, scale=1, form=None):
    # A whole count stays whole: dividing by a scale of 1 would print 70 as 70.0.
    form = form or (lambda value: number(value if scale == 1 else value / scale))
    return [form(t[key][name]) if key in t else 'n/a' for t in (t1, t2)]


def signed(value, places=1):
    return 'n/a' if value is None else f'{value:+,.{places}f}'


TITLE = 'Paso Robles backtest, Part 2: the product simulated'


def report(result, title=TITLE):
    last = dt.date.fromisoformat(result['historyLastDate'])
    keys = result['periods']
    p1, p2 = result['part1'], result['part2']
    label = lambda k: period_label(k, last)
    v = result['validity']
    w1, w2 = result['weekends']['part1'], result['weekends']['part2']
    one_segment = len(result['segments']) == 1
    offers = result['settings'].get('offerLimit')
    design = []
    if offers is not None:
        # One offer is the search's first choice: the least added cost with no new overtime, ties to the earlier window
        # (BoundedBookingSearch.choose). Several come back in window order, so the first of them is the earliest.
        design.append('Every booking search asked for 1 offer, the window that adds the least cost for the dealership without new overtime, and the customer took it.'
                      if offers == 1 else f'Every booking search asked for {offers} offers and the customer took the earliest of them.')
    design.append('The product\'s roster is the history\'s technician-days, Saturdays and Sundays included where a technician worked.' if result['weekendWork'] else
                  'The product\'s roster leaves out the history\'s Saturday and Sunday technician-days, so it books and moves nothing onto a weekend; '
                  'the dispatcher\'s weekend work stays in Part 1 as it ran.')
    design.append(f'The simulation ran as {"one continuous segment" if one_segment else str(len(result["segments"])) + " segments at once"}, '
                  + ('one overnight proposal at a time.' if result['overnightInFlight'] == 1 else f'with up to {result["overnightInFlight"]} of a night\'s proposals in flight at once.'))
    lines = [f'# {bd.checked_title(title)}', '',
             f'Generated by `infra/backtest_compare.py` from simulation `{result["simulationId"]}` (routing identity `{result["routingIdentity"][:12]}`'
             + (', cut short: a smoke or calibration run, not the study result' if result['partial'] else '') + '). '
             'Part 1 is the dispatcher\'s schedule as it ran, on the jobs Part 2 also served; Part 2 is the product booking every request through the public API '
             'and optimizing every night. Both are scored by the same scorer, on booked durations, with each technician-day starting and ending at its depot. '
             'Paid hours leave out a 1 hour unpaid lunch on each technician-day longer than 6 hours depot to depot. Overtime is a technician\'s paid time past '
             '40 hours in a Monday-to-Friday week, counted on the day it falls, and every paid minute of a Saturday or a Sunday. Work after 17:00 is not overtime '
             'in itself; it is the `outsideShiftMinutes` bar. '
             f'The history ends on {last.isoformat()}; Part 2\'s days after it use the repeated roster and have no dispatcher counterpart.', '',
             ' '.join(design), '',
             '## Validity', '',
             f'The run is {"valid" if v["valid"] else "NOT valid"}: the table shows each bar of the plan and how many times it failed.', '',
             table(['Bar', 'Failures'], [[name, number(count)] for name, count in v['bars'].items()]), '',
             f'Booking refinements stopped by their own 2000 ms cap: {number(v["capStops"])} (reported, not a failure).', '',
             '## Routes', '',
             'The table shows, by service date, the jobs each part served and their driving, paid time and cost per job.', '',
             table(['Period', 'Jobs P1', 'Jobs P2', 'Drive/job P1 (min)', 'Drive/job P2 (min)'],
                   [[label(k)] + both(p1, p2, k, 'jobs') + [per_job(p1.get(k), 'driveMinutes'), per_job(p2.get(k), 'driveMinutes')] for k in keys], text=(0,)), '',
             'The same rows with paid time and cost per job:', '',
             table(['Period', 'Paid/job P1 (min)', 'Paid/job P2 (min)', 'Cost/job P1 ($)', 'Cost/job P2 ($)'],
                   [[label(k), per_job(p1.get(k), 'paidMinutes'), per_job(p2.get(k), 'paidMinutes'), per_job(p1.get(k), 'costCents', 100),
                     per_job(p2.get(k), 'costCents', 100)] for k in keys], text=(0,)), '',
             'The same periods in totals:', '',
             table(['Period', 'Drive P1 (h)', 'Drive P2 (h)', 'Paid P1 (h)', 'Paid P2 (h)', 'Overtime P1 (h)', 'Overtime P2 (h)', 'Cost P1 ($)', 'Cost P2 ($)'],
                   [[label(k)] + both(p1, p2, k, 'driveMinutes', 60) + both(p1, p2, k, 'paidMinutes', 60) + both(p1, p2, k, 'overtimeMinutes', 60)
                    + both(p1, p2, k, 'costCents', form=dollars) for k in keys], text=(0,)), '',
             '## Customer windows', '',
             'The table shows arrivals after the end of the 2-hour window and the technician-days whose route does not hold, in each part.', '',
             table(['Period', 'Late visits P1', 'Late visits P2', 'Routes not holding P1', 'Routes not holding P2'],
                   [[label(k)] + both(p1, p2, k, 'lateVisits') + both(p1, p2, k, 'routesNotHolding') for k in keys], text=(0,)), '',
             '## Weekends', '',
             'The table shows what each part served on Saturdays and Sundays. Weekend work is overtime under the dealership\'s rule, so the scorer prices every '
             'paid minute of a Saturday or a Sunday at the overtime rate in both parts, and a weekend\'s paid hours and overtime hours are equal.'
             + ('' if result['weekendWork'] else ' Part 2 has no weekend roster in this run, so its column is zero unless a bar above failed.'), '',
             table(['Reading', 'Part 1', 'Part 2'],
                   [['Dates with jobs', number(w1['days']), number(w2['days'])], ['Jobs', number(w1['jobs']), number(w2['jobs'])],
                    ['Technician-days', number(w1['technicianDays']), number(w2['technicianDays'])],
                    ['Paid (h)', number(w1['paidMinutes'] / 60), number(w2['paidMinutes'] / 60)],
                    ['Overtime (h)', number(w1['overtimeMinutes'] / 60), number(w2['overtimeMinutes'] / 60)],
                    ['Cost ($)', dollars(w1['costCents']), dollars(w2['costCents'])]]), '']
    tail = result['tail']
    if tail:
        lines += ['## After the data', '',
                  f'Part 2 served {tail["jobs"]:,} jobs on {tail["days"]} dates after {last.isoformat()}, on the repeated roster. The table shows those days\' totals.', '',
                  table(['Measure', 'Value'], [['Technician-days', number(tail['technicianDays'])], ['Jobs', number(tail['jobs'])], ['Drive (h)', number(tail['driveMinutes'] / 60)],
                                               ['Paid (h)', number(tail['paidMinutes'] / 60)], ['Cost ($)', dollars(tail['costCents'])]]), '']
        last_keys = [k for k in bd.periods(last)[1:] if k in result['part2WithinData']]
        within = result['part2WithinData']
        lines += [f"Part 2's {', '.join(last_keys)} rows above include those days. Without them:", '',
                  table(['Period', 'Jobs P2', 'Drive/job P2 (min)', 'Paid/job P2 (min)', 'Cost/job P2 ($)'],
                        [[k, number(within[k]['jobs']), per_job(within[k], 'driveMinutes'), per_job(within[k], 'paidMinutes'), per_job(within[k], 'costCents', 100)]
                         for k in last_keys], text=(0,)), '']
    lines += ['## Week by week', '',
              f'Each table pairs the Monday-to-Sunday service weeks both parts have ({result["pairing"]["paired"]}) and reads Part 2 minus Part 1: the median weekly difference, its 95 percent bootstrap interval (10,000 resamples, fixed seed), and the weeks Part 2 was lower, equal or higher. Negative is better for Part 2.', '']
    for name, title in WEEK_MEASURES:
        readings = result['weekly'][name]
        rows = []
        for k in keys:
            r = readings.get(k)
            if r is None:
                continue
            ci = r['interval']
            rows.append([label(k), number(r['weeks']), signed(r['median']), 'n/a' if ci is None else f'{ci[0]:+,.1f} to {ci[1]:+,.1f}',
                         number(r['better']), number(r['tied']), number(r['worse'])])
        lines += [f'Weekly difference in {title.lower()}:', '', table(['Period', 'Weeks', f'Median {title}', '95% interval', 'Lower', 'Equal', 'Higher'], rows, text=(0,)), '']
    lines += ['## Promptness', '',
              'Each table shows, by the period of the request date, the days from request to service of the same requests, every one Part 2 booked: Part 1 at the dispatcher\'s service date, Part 2 at the date Part 2 booked it for. Part 2 books from the day after the request, so same-day service is not possible for it. '
              'Days are calendar days in both parts, weekends included: a request the dispatcher served on a Saturday is read at that Saturday'
              + ('.' if result['weekendWork'] else ', and a request Part 2 holds over a weekend carries those two days.'), '']
    names = {'all': 'Every job', 'emergency': 'Emergencies (' + ', '.join(bd.EMERGENCY_CODES) + ')', 'sales': 'Sales calls', 'other': 'Other jobs'}
    prompt = result['promptness']
    for group in bd.GROUPS:
        first, second = [], []
        cell = lambda p, f, conv=float: 'n/a' if p is None else number(conv(p[f]))
        for k in keys:
            a, b = prompt['part1'].get(k, {}).get(group), prompt['part2'].get(k, {}).get(group)
            if a is None and b is None:
                continue
            first.append([label(k), cell(a, 'jobs', int), cell(b, 'jobs', int), cell(a, 'medianDays'), cell(b, 'medianDays')])
            second.append([label(k), cell(a, 'p90Days', int), cell(b, 'p90Days', int), cell(a, 'within5Weekdays', int), cell(b, 'within5Weekdays', int)])
        lines += [f'{names[group]} by request period:', '',
                  table(['Period', 'Jobs P1', 'Jobs P2', 'Median P1 (days)', 'Median P2 (days)'], first, text=(0,)), '',
                  'The same rows with the 90th percentile and the jobs served within 5 weekdays:', '',
                  table(['Period', 'p90 P1 (days)', 'p90 P2 (days)', 'In 5 weekdays P1', 'In 5 weekdays P2'], second, text=(0,)), '']
    paired = [[label(k), number(r['requests']), signed(float(r['median'])), number(r['sooner']), number(r['same']), number(r['later'])]
              for k in keys if (r := prompt['paired'].get(k)) is not None]
    lines += ["The table pairs each request with itself and reads Part 2's service date minus the dispatcher's: the median difference and the requests Part 2 served sooner, on the same day or later. Negative is sooner for Part 2.", '',
              table(['Period', 'Requests', 'Median P2 minus P1 (days)', 'Sooner', 'Same day', 'Later'], paired, text=(0,)), '']
    a, b = prompt['part1WithoutLongLeads'].get('study', {}).get('all'), prompt['part2WithoutLongLeads'].get('study', {}).get('all')
    if a and b:
        lines += [f'Without the requests the dispatcher served more than 30 days out, the study medians are {number(float(a["medianDays"]))} days for Part 1 and {number(float(b["medianDays"]))} for Part 2 (90th percentiles {a["p90Days"]} and {b["p90Days"]}).', '']
    d = result['demand']
    lines += ['## Demand not served', '',
              'The table shows how every request ended in Part 2.', '',
              table(['Outcome', 'Requests'], [[k, number(n)] for k, n in d['counts'].items()] + [['Undecided', number(d['undecided'])]]), '',
              f'{number(d["calledBack"])} booked or turned-away requests needed at least one callback; {number(d["turnedAwayEmergencies"])} turned-away requests were emergencies. '
              'Jobs Part 2 turned away are taken out of Part 1 too, so both parts serve the same jobs.', '']
    bh_, on = result['booking'], result['overnight']
    health = [['Searches', number(bh_['searches'])]]
    health += [[f'Refinement {k}', number(n)] for k, n in bh_['refinement'].items()]
    health += [['Cap stops', number(bh_['capStops'])]]
    if bh_['elapsedMs']:
        health += [['Search median (ms)', number(bh_['elapsedMs']['median'], 0)], ['Search p95 (ms)', number(bh_['elapsedMs']['p95'])]]
    if bh_['regularWindowsFound']:
        health += [['Regular windows found, median', number(float(bh_['regularWindowsFound']['median']))]]
    if bh_['offerLeadDays']:
        health += [['Offer lead, median (days)', number(float(bh_['offerLeadDays']['median']))], ['Offer lead, p90 (days)', number(bh_['offerLeadDays']['p90'])]]
    lines += ['## Booking health', '', 'The table shows how the booking searches ran.', '', table(['Reading', 'Value'], health), '']
    night = [['Nights', number(on['nights'])], ['Proposals', number(on['proposals'])], ['Committed', number(on['committed'])], ['Appointments moved', number(on['moved'])]]
    night += [[f'Decision {k}', number(n)] for k, n in on['decisions'].items()]
    night += [[f'Not proposed, {k}', number(n)] for k, n in on['skipped'].items()]
    if on['datesPerNight']:
        night += [['Dates per night, median', number(float(on['datesPerNight']['median']))], ['Dates per night, largest', number(on['datesPerNight']['max'])]]
    if on['elapsedMs']:
        night += [['Proposal answer, median (ms)', number(on['elapsedMs']['median'], 0)], ['Proposal answer, largest (ms)', number(on['elapsedMs']['max'])]]
    night += [['Cents the commits saved', 'not measured']]
    lines += ['## Overnight', '', 'The table shows the overnight runs: proposals, decisions and commits.', '', table(['Reading', 'Value'], night), '']
    lines += ['## Segment boundaries', '',
              ('The run is one segment from the dispatcher\'s backlog on its first date, so no job is simulated twice. The table counts the jobs scored twice and the booked jobs no scored day holds.'
               if one_segment else
               'Segments run at once and each scores only its own service dates, so a job requested near a boundary is simulated twice. The table counts the jobs that two segments both scored and the booked jobs no scored day holds.'), '',
              table(['Reading', 'Jobs'], [[k, number(n)] for k, n in result['boundaries'].items()]), '']
    return '\n'.join(lines).rstrip('\n') + '\n'


def copy_reading(sim_dir, name):
    """A copy's Part 2 drive minutes and cost cents per job over its scored dates, its validity, and its wall time."""
    result = compare(sim_dir, name)
    study = result['part2'].get('study')
    if not study or not study['jobs']:
        raise CompareError(f'{sim_dir} copy {name} scored no job')
    segment = bd.read_json(Path(sim_dir) / 'segments' / name / 'segment.json')
    weeks = segment['simulatedDays'] / 7
    return {'copy': name, 'jobs': study['jobs'], 'drivePerJob': study['driveMinutes'] / study['jobs'], 'costPerJob': study['costCents'] / study['jobs'],
            'valid': result['validity']['valid'], 'bars': result['validity']['bars'], 'wallSeconds': segment['elapsedSeconds'],
            'wallMinutesPerSimulatedWeek': segment['elapsedSeconds'] / 60 / weeks}


def calibration(alone, together, copies=('b1', 'b2', 'c1', 'c2')):
    """The plan's bars: contention keeps 4 segments when A and the mean of B's 3-week copies differ by at most the larger of
    1 percent and twice the noise (the 3-week copies' own difference) and B is valid; the warm-up keeps 3 weeks when B's
    6-week and 3-week means differ by at most the same. Each on drive minutes and on cost per job."""
    single = bd.read_json(Path(alone) / 'simulation.json')['segments']
    if len(single) != 1:
        raise CompareError('Run A simulates the month alone: one copy')
    a = copy_reading(alone, single[0]['name'])
    b = {name: copy_reading(together, name) for name in copies}
    bars = {}
    for measure in ('drivePerJob', 'costPerJob'):
        three = [b['b1'][measure], b['b2'][measure]]
        six = [b['c1'][measure], b['c2'][measure]]
        three_mean, six_mean = statistics.mean(three), statistics.mean(six)
        noise = abs(three[0] - three[1]) / three_mean
        allowed = max(0.01, 2 * noise)
        bars[measure] = {'noise': noise, 'allowed': allowed, 'contention': abs(a[measure] - three_mean) / three_mean,
                         'warmup': abs(six_mean - three_mean) / three_mean}
    together_valid = all(r['valid'] for r in b.values())
    keep_four = together_valid and all(v['contention'] <= v['allowed'] for v in bars.values())
    keep_three = all(v['warmup'] <= v['allowed'] for v in bars.values())
    return {'alone': a, 'together': b, 'bars': bars, 'togetherValid': together_valid, 'segments': 4 if keep_four else 2,
            'warmupWeeks': 3 if keep_three else 6}


def calibration_report(result):
    rows = [[r['copy'], number(r['jobs']), number(r['drivePerJob'], 2), dollars(round(r['costPerJob'])), 'yes' if r['valid'] else 'no',
             number(r['wallMinutesPerSimulatedWeek'])] for r in [result['alone']] + list(result['together'].values())]
    bars = [[measure, f'{v["noise"]:.2%}', f'{v["allowed"]:.2%}', f'{v["contention"]:.2%}', f'{v["warmup"]:.2%}'] for measure, v in result['bars'].items()]
    lines = ['# Paso Robles backtest, Part 2: calibration month', '',
             'Generated by `infra/backtest_compare.py calibration`. Run A simulates the month alone with a 3-week warm-up; run B simulates it four times at once, two copies with a 3-week warm-up (b1, b2) and two with a 6-week one (c1, c2). The noise is the 3-week copies\' own difference.', '',
             'The table shows each copy\'s Part 2 jobs, drive minutes and cost per job over the month, whether it met the validity bars, and its wall time per simulated week.', '',
             table(['Copy', 'Jobs', 'Drive per job (min)', 'Cost per job ($)', 'Valid', 'Wall per simulated week (min)'], rows, text=(0, 4)), '',
             'The table shows the bars: the allowed difference is the larger of 1 percent and twice the noise.', '',
             table(['Measure', 'Noise', 'Allowed', 'A against B (3 weeks)', '6 weeks against 3 weeks'], bars, text=(0,)), '',
             f'Decision: {result["segments"]} segments at once (run B {"met" if result["togetherValid"] else "did not meet"} the validity bars) and a {result["warmupWeeks"]}-week warm-up.', '']
    return '\n'.join(lines).rstrip('\n') + '\n'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    single = commands.add_parser('compare')
    single.add_argument('--simulation', required=True)
    single.add_argument('--out', required=True)
    single.add_argument('--segment')
    single.add_argument('--title', default=TITLE, help='the report\'s H1, for a backtest other than the first')
    calibrate = commands.add_parser('calibration')
    calibrate.add_argument('--alone', required=True)
    calibrate.add_argument('--together', required=True)
    calibrate.add_argument('--out', required=True)
    args = parser.parse_args(argv)
    if args.command == 'calibration':
        result = calibration(args.alone, args.together)
        out = Path(args.out)
        out.mkdir(parents=True, exist_ok=True)
        (out / 'report.md').write_text(calibration_report(result), encoding='utf-8', newline='\n')
        bd.write_json(out / 'evidence.json', result)
        print(json.dumps({'segments': result['segments'], 'warmupWeeks': result['warmupWeeks'], 'report': str(out / 'report.md')}))
        return 0
    title = bd.checked_title(args.title)
    result = compare(args.simulation, args.segment)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / 'report.md').write_text(report(result, title), encoding='utf-8', newline='\n')
    bd.write_json(out / 'evidence.json', result)
    print(json.dumps({'valid': result['validity']['valid'], 'report': str(out / 'report.md')}))
    return 0


if __name__ == '__main__':
    sys.exit(main())
