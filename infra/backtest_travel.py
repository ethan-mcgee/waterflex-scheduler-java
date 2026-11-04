"""Check the road model against the logged day, and calibrate the travel buffer on the calibration split.

For each technician-day the gap between one job's logged end and the next job's logged start is drive time plus
whatever else happened; it should rarely be shorter than the road leg between the two addresses. The share of gaps
shorter than the raw leg is a reading of how far the logged times and the road model can be trusted together. The
calibrated travelBufferPct is the smallest percent whose buffered leg (Monetary.bufferedMinutes:
ceil(seconds * (1 + pct) / 60) + travelBufferMinutes) covers the chosen share of gaps, the median by default.

    python infra/backtest_travel.py calibrate --run <run dir> --out <run dir>/travel-calibration.json
        [--split calibration] [--coverage 0.5] [--travel-buffer-minutes 2]

Reads the importer's observed.json and facts.json per day; stdlib only.
"""
import argparse
import datetime as dt
import json
import math
import statistics
import sys
from collections import defaultdict
from pathlib import Path

BUCKETS = [('under 5 km', 0, 5000), ('5 to 20 km', 5000, 20000), ('20 to 60 km', 20000, 60000), ('over 60 km', 60000, float('inf'))]
MAX_GAP_MINUTES = 180
PERCENTS = range(0, 201)


def instant(text):
    return dt.datetime.fromisoformat(text.replace('Z', '+00:00'))


def day_gaps(day_directory):
    """(gap minutes, leg seconds, leg meters) for every consecutive pair of logged jobs on one technician-day."""
    with (day_directory / 'observed.json').open(encoding='utf-8') as stream:
        observed = json.load(stream)
    with (day_directory / 'facts.json').open(encoding='utf-8') as stream:
        facts = json.load(stream)
    location = {visit['id']: visit['location'] for visit in facts['visits']}
    legs = {(entry['from'], entry['to']): entry for entry in facts['roads']['entries'] if entry['state'] == 'REACHABLE'}
    by_technician = defaultdict(list)
    for visit_id, seen in observed['visits'].items():
        if seen['actualStart'] is None or seen['actualEnd'] is None or 'ACTUAL_END_NOT_AFTER_START' in seen['flags']:
            continue
        by_technician[seen['technician']].append((instant(seen['actualStart']), instant(seen['actualEnd']), visit_id))
    gaps = []
    for stops in by_technician.values():
        stops.sort()
        for (_, end, a), (start, _, b) in zip(stops, stops[1:]):
            gap = (start - end).total_seconds() / 60
            leg = legs.get((location[a], location[b]))
            if leg is None or leg['seconds'] <= 0 or gap <= 0 or gap > MAX_GAP_MINUTES:
                continue
            gaps.append((gap, leg['seconds'], leg['meters']))
    return gaps


def gaps(run, split):
    """Every gap of the days in the split (calibration, test or all), with its year."""
    with (Path(run) / 'index.json').open(encoding='utf-8') as stream:
        index = json.load(stream)
    result = []
    for entry in index['days']:
        if split != 'all' and entry['split'] != split:
            continue
        for gap, seconds, meters in day_gaps(Path(run) / 'days' / entry['day']):
            result.append({'year': entry['year'], 'gapMinutes': gap, 'legSeconds': seconds, 'legMeters': meters})
    return result


def buffered_minutes(seconds, percent, extra_minutes):
    """Monetary.bufferedMinutes with the percent as an integer: exact integer arithmetic, no binary rounding."""
    return -(-seconds * (100 + percent) // 6000) + extra_minutes


def quantiles(values):
    if not values:
        return None
    ordered = sorted(values)
    at = lambda q: ordered[min(len(ordered) - 1, int(q * len(ordered)))]
    return {'count': len(ordered), 'p10': round(at(0.1), 3), 'p25': round(at(0.25), 3), 'median': round(statistics.median(ordered), 3),
            'p75': round(at(0.75), 3), 'p90': round(at(0.9), 3)}


def describe(rows):
    ratios = [row['gapMinutes'] / (row['legSeconds'] / 60) for row in rows]
    impossible = sum(1 for row in rows if row['gapMinutes'] < row['legSeconds'] / 60)
    return {'gaps': len(rows), 'ratio': quantiles(ratios), 'gapMinutes': quantiles([row['gapMinutes'] for row in rows]),
            'legMinutes': quantiles([row['legSeconds'] / 60 for row in rows]),
            'shorterThanLeg': impossible, 'shorterThanLegShare': round(impossible / len(rows), 4) if rows else None}


def calibrate(rows, coverage, extra_minutes):
    """The smallest whole percent whose buffered legs cover at least {@code coverage} of the gaps, or None when none does."""
    if not rows:
        raise ValueError('No gaps to calibrate on')
    for percent in PERCENTS:
        covered = sum(1 for row in rows if buffered_minutes(row['legSeconds'], percent, extra_minutes) >= row['gapMinutes'])
        if covered / len(rows) >= coverage:
            return {'percent': percent, 'travelBufferPct': f'{percent / 100:.2f}', 'covered': covered, 'of': len(rows), 'coverage': round(covered / len(rows), 4)}
    return None


def report(run, split, coverage, extra_minutes):
    rows = gaps(run, split)
    if not rows:
        raise ValueError(f'No usable gaps in the {split} split of {run}')
    by_bucket = {}
    for name, low, high in BUCKETS:
        subset = [row for row in rows if low <= row['legMeters'] < high]
        if subset:
            by_bucket[name] = describe(subset)
    by_year = {str(year): describe([row for row in rows if row['year'] == year]) for year in sorted({row['year'] for row in rows})}
    chosen = calibrate(rows, coverage, extra_minutes)
    alternative = calibrate(rows, 0.75, extra_minutes)
    return {'run': str(run), 'split': split, 'travelBufferMinutes': extra_minutes, 'coverageTarget': coverage,
            'overall': describe(rows), 'byDistance': by_bucket, 'byYear': by_year,
            'calibrated': chosen, 'seventyFifthPercentile': alternative, 'maxGapMinutes': MAX_GAP_MINUTES}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    cal = commands.add_parser('calibrate')
    cal.add_argument('--run', required=True)
    cal.add_argument('--out', required=True)
    cal.add_argument('--split', default='calibration', choices=['calibration', 'test', 'all'])
    cal.add_argument('--coverage', type=float, default=0.5)
    cal.add_argument('--travel-buffer-minutes', type=int, default=2)
    args = parser.parse_args(argv)
    if not (0 < args.coverage <= 1):
        parser.error('--coverage is a share between 0 and 1')
    document = report(Path(args.run), args.split, args.coverage, args.travel_buffer_minutes)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open('w', encoding='utf-8', newline='\n') as stream:
        json.dump(document, stream, indent=1, sort_keys=True)
        stream.write('\n')
    print(json.dumps({'gaps': document['overall']['gaps'], 'shorterThanLegShare': document['overall']['shorterThanLegShare'],
                      'calibrated': document['calibrated'], 'seventyFifthPercentile': document['seventyFifthPercentile']}))
    return 0


if __name__ == '__main__':
    sys.exit(main())
