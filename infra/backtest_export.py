"""Part 2's final book as day files the Part 1 scorer reads, and Part 1 again on the same jobs (simulation-plan.md,
"Scoring" and "Comparison").

export reads a finished simulation (backtest_simulate.py) and writes, under <simulation dir>/export/:

- part2/: one day per service date a segment owns ([first, end) of its plan), built from that segment's book: a
  DailyDataset draft (facts.json) on the study's own date, the book's routes (plan.json) and the jobs' request dates
  (observed.json), with an index the Part 1 summary reads. Roads come from the same routing service and road cache as
  the import, its identity checked.
- part1/: the dispatcher's as-run days over the same dates, without the jobs Part 2 turned away (TURNED_AWAY or
  END_OF_DATA), so both parts serve the same jobs. An unchanged day points at the import's own day directory.
- outcomes.json: every request's outcome, decided in the segment that owns its request date (the next segment's when
  the owner ended with it still waiting), and the boundary readings: jobs two segments both scored, and booked jobs no
  scored day holds.
- part1-job.json and part2-job.json: backtest-score-days jobs; score runs them with the benchmark jar.

    python infra/backtest_export.py export --simulation <dir> --roads http://127.0.0.1:18001 [--token-env ROUTING_AUTH_TOKEN] [--partial]
    python infra/backtest_export.py score --simulation <dir> --jar <campaign-benchmark jar> [--java java]

--partial exports a simulation cut short (smoke, calibration month): only service dates through each segment's stop.
"""
import argparse
import datetime as dt
import json
import os
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path
from zoneinfo import ZoneInfo

import backtest_host as bh
import backtest_import as bi

REMOVED = ('TURNED_AWAY', 'END_OF_DATA')


class ExportError(ValueError):
    """A simulation that is unfinished or inconsistent: nothing is exported from it."""


# ----------------------------------------------------------------------------------------------------------------------
# Reading the simulation


def load_segments(sim_dir, config, partial):
    """{name: (segment, book, desk, bookings by request)}; every segment must have finished unless partial."""
    result = {}
    for segment in config['segments']:
        directory = Path(sim_dir) / 'segments' / segment['name']
        checkpoint = directory / 'checkpoint.json'
        if not checkpoint.exists():
            raise ExportError(f'Segment {segment["name"]} has not started')
        saved = bh.load_json(checkpoint)
        if saved['segment'] != segment:
            raise ExportError(f'Segment {segment["name"]}: its checkpoint belongs to another plan')
        if saved['cursor'] <= segment['stop']:
            raise ExportError(f'Segment {segment["name"]} stopped at {saved["cursor"]}, before {segment["stop"]}; resume it first')
        if not partial and not (directory / 'segment.json').exists():
            raise ExportError(f'Segment {segment["name"]} has no segment.json')
        attempts = Counter()
        log = directory / 'bookings.jsonl'
        if log.exists():
            for line in log.read_text(encoding='utf-8').splitlines():
                if line:
                    attempts[json.loads(line)['request']] += 1
        result[segment['name']] = (segment, bh.Book.from_json(saved['book']), bh.Desk.from_json(saved['desk']), attempts)
    return result


def scored_range(segment, partial):
    """The service dates a segment owns: [first, end), or through its stop when the simulation was cut short."""
    end = bh.add_days(segment['stop'], 1) if partial else segment['end']
    return segment['first'], min(end, segment['end'])


def owner_of(request, segments):
    """The segment whose dates hold the request's date; a request from before the first segment belongs to it."""
    ordered = sorted(segments.values(), key=lambda item: item[0]['first'])
    position = 0
    for number, (segment, _, _, _) in enumerate(ordered):
        if request['requestDate'] >= segment['first']:
            position = number
    return position, ordered


def outcomes(requests, segments, partial):
    """{request id: {outcome, segment, serviceDate, technician, attempts}} and the requests no segment decided."""
    result, undecided = {}, []
    for request in requests['requests']:
        position, ordered = owner_of(request, segments)
        if partial and request['requestDate'] > ordered[position][0]['stop']:
            continue
        decided = None
        for segment, book, desk, attempts in ordered[position:position + 2]:
            outcome = desk.outcomes.get(request['id'])
            if outcome is not None:
                appointment = book.appointments.get(request['id'])
                if (outcome == 'BOOKED') != (appointment is not None):
                    raise ExportError(f'{request["id"]}: {outcome} in segment {segment["name"]} but {"an" if appointment else "no"} appointment')
                decided = {'outcome': outcome, 'segment': segment['name'], 'serviceDate': appointment.date if appointment else None,
                           'technician': appointment.technician if appointment else None, 'attempts': attempts[request['id']]}
                break
        if decided is None:
            undecided.append(request['id'])
            continue
        result[request['id']] = decided
    return result, undecided


# ----------------------------------------------------------------------------------------------------------------------
# Day files


def location_id(depot):
    return 'depot' if depot == 'depot' else f'depot-{depot}'


def part2_day(date, book, requests, run_config, road_client):
    """(facts, plan, observed, index entry) for one service date of a segment's book, on the study's own date."""
    zone = ZoneInfo(run_config['timeZone'])
    day = dt.date.fromisoformat(date)
    technicians = book.on(date)
    appointments = sorted(book.booked_on(date), key=lambda a: (a.technician, a.sequence))
    depots = sorted({(d.depot, d.lat, d.lng) for d in technicians})
    if len({name for name, _, _ in depots}) != len(depots):
        raise ExportError(f'{date}: one depot name has two locations')
    depot_index = {name: i for i, (name, _, _) in enumerate(depots)}
    points = [(lat, lng) for _, lat, lng in depots]
    locations = [{'id': location_id(name), 'latitude': lat, 'longitude': lng} for name, lat, lng in depots]
    for appointment in appointments:
        locations.append({'id': appointment.id, 'latitude': appointment.lat, 'longitude': appointment.lng})
        points.append((appointment.lat, appointment.lng))
    pairs = sorted({(points[v], points[w]) for v in range(len(points)) for w in range(len(points)) if v != w})
    legs = road_client.legs(pairs) if pairs else {}
    entries = []
    for v in range(len(points)):
        for w in range(len(points)):
            if v != w:
                leg = legs[road_client.key(points[v], points[w])]
                entries.append({'from': v, 'to': w, 'state': 'REACHABLE', 'seconds': leg['seconds'], 'meters': leg['meters']} if leg['routable']
                               else {'from': v, 'to': w, 'state': 'UNREACHABLE', 'seconds': None, 'meters': None})
    services = sorted({a.service for a in appointments})
    service_index = {s: i for i, s in enumerate(services)}
    tech_index = {d.technician: i for i, d in enumerate(technicians)}
    visit_index = {a.id: i for i, a in enumerate(appointments)}
    first_visit = len(depots)
    by_technician = defaultdict(list)
    for appointment in appointments:
        if appointment.technician not in tech_index:
            raise ExportError(f'{date}: {appointment.id} is on {appointment.technician}, who has no technician-day')
        by_technician[appointment.technician].append(appointment.id)
    rates = run_config['rates']
    facts = {'schemaVersion': 1, 'operation': 'DAILY', 'mode': 'ASSIGNED', 'snapshotId': f'{run_config["runId"]}-part2/{date}', 'contentHash': None,
             'versions': dict(bi.VERSIONS), 'routing': {'provider': 'caller', 'profile': 'car', 'identity': road_client.identity},
             'search': dict(run_config['search']),
             'revisions': {'configuration': f'{run_config["runId"]}-part2', 'reservation': date, 'schedule': [f'{date}/{d.technician}' for d in technicians]},
             'rates': {k: rates[k] for k in ('regularHourly', 'overtimeHourly', 'mileagePerMile', 'travelBufferPct', 'travelBufferMinutes')} | {'arrivalBuffer': dict(rates['arrivalBuffer'])},
             'locations': locations, 'services': [{'id': s} for s in services],
             'technicians': [{'id': d.technician, 'departureLocation': depot_index[d.depot], 'returnLocation': depot_index[d.depot],
                              'shiftStart': bi.local_instant(day, d.start, zone), 'shiftEnd': bi.local_instant(day, d.end, zone), 'maxDailyMinutes': d.max_paid,
                              'maxOvertimeMinutes': 0, 'qualifications': list(range(len(services))), 'absences': [],
                              'assigned': [visit_index[i] for i in by_technician.get(d.technician, [])], 'pinnedPrefix': 0} for d in technicians],
             'visits': [{'id': a.id, 'service': service_index[a.service], 'location': visit_index[a.id] + first_visit,
                         'windowStart': bi.local_instant(day, a.window_start, zone), 'windowEnd': bi.local_instant(day, a.window_end, zone),
                         'durationMinutes': a.duration, 'originalTechnician': tech_index[a.technician],
                         'originalPlannedStart': bi.local_instant(day, a.planned_start, zone)} for a in appointments],
             'unassigned': [], 'roads': {'encoding': 'SPARSE', 'size': len(locations), 'entries': entries}}
    plan = {'version': 1, 'routes': {d.technician: list(by_technician.get(d.technician, [])) for d in technicians}, 'unassigned': []}
    observed = {'day': date, 'visits': {}}
    for appointment in appointments:
        request = requests[appointment.id]
        observed['visits'][appointment.id] = {'technician': appointment.technician, 'plannedStart': bi.local_instant(day, appointment.planned_start, zone),
                                              'tags': list(request['tags']), 'salesCall': request['salesCall'], 'flags': list(request['flags']),
                                              'requestLeadDays': (day - dt.date.fromisoformat(request['requestDate'])).days}
    entry = {'day': date, 'jobs': len(appointments), 'technicians': len(technicians), 'depots': {d.technician: d.depot for d in technicians},
             'unreachableLegs': sum(1 for e in entries if e['state'] == 'UNREACHABLE')}
    return facts, plan, observed, entry


def without_visits(facts, plan, observed, drop):
    """A dataset draft, its plan and its observed visits without the visits named in drop, every index renumbered."""
    drop = set(drop)
    keep = [i for i, visit in enumerate(facts['visits']) if visit['id'] not in drop]
    if len(keep) == len(facts['visits']):
        return facts, plan, observed
    removed_locations = {visit['location'] for visit in facts['visits'] if visit['id'] in drop}
    location_map, locations = {}, []
    for position, location in enumerate(facts['locations']):
        if position not in removed_locations:
            location_map[position] = len(locations)
            locations.append(location)
    visit_map = {old: new for new, old in enumerate(keep)}
    result = dict(facts, locations=locations, visits=[dict(facts['visits'][i], location=location_map[facts['visits'][i]['location']]) for i in keep])
    result['technicians'] = [dict(t, departureLocation=location_map[t['departureLocation']], returnLocation=location_map[t['returnLocation']],
                                  assigned=[visit_map[i] for i in t['assigned'] if i in visit_map]) for t in facts['technicians']]
    result['roads'] = dict(facts['roads'], size=len(locations), entries=[dict(e, **{'from': location_map[e['from']], 'to': location_map[e['to']]})
                                                                         for e in facts['roads']['entries'] if e['from'] in location_map and e['to'] in location_map])
    new_plan = dict(plan, routes={t: [i for i in ids if i not in drop] for t, ids in plan['routes'].items()})
    new_observed = dict(observed, visits={i: v for i, v in observed['visits'].items() if i not in drop})
    return result, new_plan, new_observed


# ----------------------------------------------------------------------------------------------------------------------
# Export


def export(sim_dir, road_client, partial=False, only=None):
    """The export of every segment into export/, or of the one segment named only into export-<name>/ (a calibration
    window's copies share their dates, so each is exported, scored and compared on its own)."""
    sim_dir = Path(sim_dir)
    config = bh.load_json(sim_dir / 'simulation.json')
    if only is not None:
        chosen = [s for s in config['segments'] if s['name'] == only]
        if not chosen:
            raise ExportError(f'The simulation has no segment {only}')
        config = dict(config, segments=chosen)
    elif config.get('kind') == 'window':
        raise ExportError('A calibration window shares its dates between copies: export each with --segment')
    run = Path(config['run'])
    if road_client.identity != config['routingIdentity']:
        raise ExportError(f'The routing service has identity {road_client.identity}, the simulation was planned on {config["routingIdentity"]}')
    for name, digest in config['inputs'].items():
        if bi.sha256(run / 'part2' / name) != digest:
            raise ExportError(f'part2/{name} changed since the simulation was planned')
    requests = bh.load_json(run / 'part2' / 'requests.json')
    by_id = {r['id']: r for r in requests['requests']}
    run_config = bh.load_json(run / 'study-config.json')
    if config['until'] is not None and not partial:
        raise ExportError(f'The simulation was planned to stop at {config["until"]}; export it with --partial')
    segments = load_segments(sim_dir, config, partial)
    decided, undecided = outcomes(requests, segments, partial)
    out = sim_dir / ('export' if only is None else f'export-{only}')
    # Part 2: each segment's own service dates from its own book.
    part2_days, scored = [], defaultdict(list)
    for name, (segment, book, _, _) in sorted(segments.items(), key=lambda item: item[1][0]['first']):
        first, end = scored_range(segment, partial)
        for date in book.dates():
            if not first <= date < end or not book.booked_on(date):
                continue
            facts, plan, observed, entry = part2_day(date, book, by_id, run_config, road_client)
            directory = out / 'part2' / 'days' / date
            for file, document in (('facts.json', facts), ('plan.json', plan), ('observed.json', observed)):
                bi.write_json(directory / file, document)
            part2_days.append(dict(entry, segment=name))
            for visit in facts['visits']:
                scored[visit['id']].append(date)
    covered = [scored_range(s, partial) for s, _, _, _ in segments.values()]
    inside = lambda date: any(first <= date < end for first, end in covered)
    twice = sorted(i for i, dates in scored.items() if len(dates) > 1)
    missing = sorted(i for i, o in decided.items() if o['outcome'] == 'BOOKED' and inside(o['serviceDate']) and i not in scored)
    booked_outside = sorted(i for i, o in decided.items() if o['outcome'] == 'BOOKED' and not inside(o['serviceDate']))
    # Part 1 on the same jobs: the as-run days over the same dates, without the jobs Part 2 turned away.
    as_run = run / 'variants' / 'as-run'
    index = bh.load_json(as_run / 'index.json')
    removed = {i for i, o in decided.items() if o['outcome'] in REMOVED}
    part1_days, part1_jobs = [], []
    for entry in sorted(index['days'], key=lambda e: e['day']):
        if not inside(entry['day']):
            continue
        source = as_run / 'days' / entry['day']
        facts, plan, observed = (bh.load_json(source / f) for f in ('facts.json', 'as-planned.plan.json', 'observed.json'))
        drop = removed & {v['id'] for v in facts['visits']}
        if not drop:
            part1_jobs.append({'day': entry['day'], 'directory': str(source.resolve())})
            part1_days.append(entry)
            continue
        facts, plan, observed = without_visits(facts, plan, observed, drop)
        if not facts['visits']:
            continue
        directory = out / 'part1' / 'days' / entry['day']
        for file, document in (('facts.json', facts), ('as-planned.plan.json', plan), ('observed.json', observed)):
            bi.write_json(directory / file, document)
        part1_jobs.append({'day': entry['day'], 'directory': str(directory.resolve())})
        part1_days.append(dict(entry, jobs=len(facts['visits']), removed=len(drop)))
    ledger = index['ledger']
    bi.write_json(out / 'part2' / 'index.json', {'runId': f'{config["simulationId"]}-part2', 'ruleSet': 'part2', 'routingIdentity': road_client.identity,
                                                 'days': part2_days, 'ledger': ledger})
    bi.write_json(out / 'part1' / 'index.json', {'runId': f'{index["runId"]}-same-jobs', 'ruleSet': 'as-run', 'routingIdentity': index['routingIdentity'],
                                                 'days': part1_days, 'ledger': ledger, 'removedJobs': len(removed)})
    for part, days, plan_name in (('part1', part1_jobs, 'as-planned.plan.json'),
                                  ('part2', [{'day': e['day'], 'directory': str((out / 'part2' / 'days' / e['day']).resolve())} for e in part2_days], 'plan.json')):
        bi.write_json(out / f'{part}-job.json', {'version': 1, 'kind': 'backtest-score-days', 'dataset': 'facts.json', 'plan': plan_name, 'days': days,
                                                 'output': str((out / part / 'rows.json').resolve())})
    summary = {'version': 1, 'simulationId': config['simulationId'], 'partial': partial, 'outcomes': decided, 'undecided': undecided,
               'counts': dict(sorted(Counter(o['outcome'] for o in decided.values()).items())),
               'boundaries': {'scoredTwice': twice, 'bookedNotScored': missing, 'bookedOutsideTheStudy': booked_outside},
               'days': {'part1': len(part1_days), 'part2': len(part2_days)}}
    bi.write_json(out / 'outcomes.json', summary)
    return summary


def score(sim_dir, jar, java='java', only=None):
    """Runs both scoring jobs with the benchmark jar; each writes its rows beside its index."""
    out = Path(sim_dir) / ('export' if only is None else f'export-{only}')
    receipts = {}
    for part in ('part1', 'part2'):
        job = out / f'{part}-job.json'
        receipt = out / f'{part}-receipt.json'
        result = subprocess.run([java, '-Xmx3g', '-XX:+UseSerialGC', '-jar', str(jar), str(job), str(receipt)], capture_output=True, text=True)
        if result.returncode != 0:
            raise ExportError(f'Scoring {part} failed: {result.stderr[-2000:]}')
        receipts[part] = bh.load_json(receipt)
    return receipts


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    exporter = commands.add_parser('export')
    exporter.add_argument('--simulation', required=True)
    exporter.add_argument('--roads', required=True)
    exporter.add_argument('--token-env', default='ROUTING_AUTH_TOKEN')
    exporter.add_argument('--partial', action='store_true')
    exporter.add_argument('--segment', help='one copy of a calibration window')
    scorer = commands.add_parser('score')
    scorer.add_argument('--simulation', required=True)
    scorer.add_argument('--jar', required=True)
    scorer.add_argument('--java', default='java')
    scorer.add_argument('--segment')
    args = parser.parse_args(argv)
    if args.command == 'export':
        config = bh.load_json(Path(args.simulation) / 'simulation.json')
        client = bi.RoadClient(args.roads, os.environ.get(args.token_env, ''), Path(config['run']) / 'roads-cache.json')
        summary = export(args.simulation, client, args.partial, args.segment)
        print(json.dumps({k: summary[k] for k in ('counts', 'days')} | {'undecided': len(summary['undecided']),
                                                                         'boundaries': {k: len(v) for k, v in summary['boundaries'].items()}}))
        return 0
    print(json.dumps(score(args.simulation, args.jar, args.java, args.segment)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
