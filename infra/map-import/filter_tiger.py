"""Keep only the TIGER address ranges near the depots.

The Nominatim project publishes TIGER ranges for the whole US as one tar of per-county CSV files (`;`-delimited, with
`from`, `to`, `interpolation`, `street`, `postcode` and a WKT `geometry` column). Nominatim imports every row with its
own spatial query, so this keeps a row only when a vertex of its street line lies within a depot circle plus the
preparation margin, the same envelope the tiles and graph are prepared for. The output tar is byte-for-byte
reproducible, so its SHA-256 identifies the filtered data.

Usage: filter_tiger.py COVERAGE_JSON SOURCE_TAR_GZ OUTPUT_TAR_GZ   (prints the filter statistics as JSON)
"""
import csv
import gzip
import io
import json
import math
import re
import sys
import tarfile
from pathlib import Path

REQUIRED_COLUMNS = ('from', 'to', 'interpolation', 'street', 'postcode', 'geometry')
EARTH_RADIUS_MI = 3958.8
LINESTRING = re.compile(r'^LINESTRING\s*\((.+)\)$')


class TigerFilterError(ValueError):
    pass


def finite(value, name):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise TigerFilterError(f'{name} must be a finite number')
    return float(value)


def load_coverage(path):
    coverage = json.loads(Path(path).read_text(encoding='utf-8'))
    circles = coverage.get('circles') if isinstance(coverage, dict) else None
    if not isinstance(circles, list) or not circles:
        raise TigerFilterError('Coverage has no depot circles')
    margin = finite(coverage.get('marginMi'), 'marginMi')
    if margin < 0:
        raise TigerFilterError('marginMi must not be negative')
    parsed = []
    for index, circle in enumerate(circles):
        if not isinstance(circle, dict):
            raise TigerFilterError(f'Circle {index} is not an object')
        lat = finite(circle.get('lat'), f'circles[{index}].lat')
        lng = finite(circle.get('lng'), f'circles[{index}].lng')
        radius = finite(circle.get('radiusMi'), f'circles[{index}].radiusMi')
        if not -90 <= lat <= 90 or not -180 <= lng <= 180 or radius <= 0:
            raise TigerFilterError(f'Circle {index} is out of range')
        parsed.append((math.radians(lat), math.radians(lng), radius + margin))
    return parsed, margin


def vertices(geometry, where):
    match = LINESTRING.match(geometry.strip())
    if match is None:
        raise TigerFilterError(f'{where}: geometry is not a LINESTRING')
    points = []
    for pair in match.group(1).split(','):
        parts = pair.split()
        if len(parts) != 2:
            raise TigerFilterError(f'{where}: malformed coordinate pair')
        try:
            lng, lat = float(parts[0]), float(parts[1])
        except ValueError as error:
            raise TigerFilterError(f'{where}: malformed coordinate pair') from error
        if not (math.isfinite(lng) and math.isfinite(lat) and -180 <= lng <= 180 and -90 <= lat <= 90):
            raise TigerFilterError(f'{where}: coordinate out of range')
        points.append((math.radians(lat), math.radians(lng)))
    return points


def within(point, circles):
    lat, lng = point
    for circle_lat, circle_lng, reach in circles:
        a = (math.sin((lat - circle_lat) / 2) ** 2
             + math.cos(lat) * math.cos(circle_lat) * math.sin((lng - circle_lng) / 2) ** 2)
        if 2 * EARTH_RADIUS_MI * math.asin(min(1.0, math.sqrt(a))) <= reach:
            return True
    return False


def filter_member(name, stream, circles):
    # A streamed tar member cannot be wrapped as text (it is not seekable); county files are small enough to read whole.
    reader = csv.reader(io.StringIO(stream.read().decode('utf-8'), newline=''), delimiter=';')
    header = next(reader, None)
    if header is None or any(column not in header for column in REQUIRED_COLUMNS):
        raise TigerFilterError(f'{name}: missing TIGER columns {list(REQUIRED_COLUMNS)}')
    geometry = header.index('geometry')
    output = io.StringIO()
    writer = csv.writer(output, delimiter=';', lineterminator='\n')
    writer.writerow(header)
    total = kept = 0
    for line, row in enumerate(reader, start=2):
        if len(row) != len(header):
            raise TigerFilterError(f'{name}:{line}: expected {len(header)} fields, found {len(row)}')
        total += 1
        if any(within(point, circles) for point in vertices(row[geometry], f'{name}:{line}')):
            writer.writerow(row)
            kept += 1
    return total, kept, output.getvalue().encode('utf-8')


def filter_tiger(coverage_path, source_path, output_path):
    circles, margin = load_coverage(coverage_path)
    kept_members = {}
    stats = {'filesTotal': 0, 'filesKept': 0, 'rowsTotal': 0, 'rowsKept': 0}
    with tarfile.open(source_path, mode='r|gz') as source:
        for member in source:
            if not member.isfile() or not member.name.endswith('.csv'):
                continue
            stream = source.extractfile(member)
            if stream is None:
                raise TigerFilterError(f'{member.name}: unreadable member')
            total, kept, data = filter_member(member.name, stream, circles)
            stats['filesTotal'] += 1
            stats['rowsTotal'] += total
            stats['rowsKept'] += kept
            if kept:
                if member.name in kept_members:
                    raise TigerFilterError(f'{member.name}: duplicate member')
                kept_members[member.name] = data
    if stats['filesTotal'] == 0:
        raise TigerFilterError('Source contains no TIGER CSV files')
    if stats['rowsKept'] == 0:
        raise TigerFilterError('No TIGER ranges fall inside the depot coverage')
    stats['filesKept'] = len(kept_members)
    with open(output_path, 'wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as zipped, \
            tarfile.open(fileobj=zipped, mode='w', format=tarfile.USTAR_FORMAT) as output:
        for name in sorted(kept_members):
            data = kept_members[name]
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mtime = 0
            info.mode = 0o644
            info.uid = info.gid = 0
            info.uname = info.gname = ''
            output.addfile(info, io.BytesIO(data))
    stats['marginMi'] = margin
    return stats


if __name__ == '__main__':
    if len(sys.argv) != 4:
        sys.exit(__doc__.strip().splitlines()[-1])
    try:
        print(json.dumps(filter_tiger(*sys.argv[1:]), sort_keys=True))
    except TigerFilterError as error:
        sys.exit(f'TIGER filter failed: {error}')
