"""A map region: the Geofabrik extracts a version merges, an optional clip box and the sample points validation routes.

Used inside the map-import image (prepare.sh) and on the host (infra/validate-map.py, infra/prepare-map.ps1). The
region is passed to prepare.sh as MAP_REGION_BASE64, the same way the depot coverage is, and recorded in the manifest.
A manifest without a region block was prepared before regions existed and is the Omaha region.
"""
import base64
import json
import re
import sys
from pathlib import Path

GEOFABRIK = 'https://download.geofabrik.de/north-america/us/{source}-latest.osm.pbf'
DEFAULT_REGION = 'omaha'
NAME = re.compile(r'^[a-z0-9][a-z0-9-]{0,59}$')
SOURCE = re.compile(r'^[a-z][a-z-]{0,39}$')


def validate(spec):
    """The region as a dict, or raise ValueError naming the first problem."""
    if not isinstance(spec, dict):
        raise ValueError('Region must be an object')
    expected = {'name', 'sources', 'clip', 'samples'}
    if set(spec) != expected:
        raise ValueError(f'Region keys must be exactly {sorted(expected)}')
    name = spec['name']
    if not isinstance(name, str) or not NAME.match(name):
        raise ValueError('Region name must be lowercase letters, digits and hyphens')
    sources = spec['sources']
    if not isinstance(sources, list) or not sources or len(set(sources)) != len(sources) \
            or any(not isinstance(s, str) or not SOURCE.match(s) for s in sources):
        raise ValueError('Region sources must be a non-empty list of distinct Geofabrik US extract names')
    clip = spec['clip']
    if clip is not None:
        if not isinstance(clip, dict) or set(clip) != {'west', 'south', 'east', 'north'}:
            raise ValueError('Region clip must be null or a box with west, south, east and north')
        if any(isinstance(clip[k], bool) or not isinstance(clip[k], (int, float)) for k in clip):
            raise ValueError('Region clip bounds must be numbers')
        if not (-180 <= clip['west'] < clip['east'] <= 180 and -90 <= clip['south'] < clip['north'] <= 90):
            raise ValueError('Region clip bounds are out of order')
    samples = spec['samples']
    if not isinstance(samples, list) or not samples:
        raise ValueError('Region samples must be a non-empty list')
    names = set()
    for sample in samples:
        if not isinstance(sample, dict) or set(sample) != {'name', 'lat', 'lng'}:
            raise ValueError('Each sample has exactly name, lat and lng')
        if not isinstance(sample['name'], str) or not sample['name'] or sample['name'] in names:
            raise ValueError('Sample names must be distinct and non-empty')
        names.add(sample['name'])
        lat, lng = sample['lat'], sample['lng']
        if any(isinstance(v, bool) or not isinstance(v, (int, float)) for v in (lat, lng)) or not (-90 <= lat <= 90 and -180 <= lng <= 180):
            raise ValueError(f'Sample {sample["name"]} has invalid coordinates')
        if clip is not None and not (clip['west'] <= lng <= clip['east'] and clip['south'] <= lat <= clip['north']):
            raise ValueError(f'Sample {sample["name"]} lies outside the clip box')
    return spec


def load(path):
    with Path(path).open(encoding='utf-8') as stream:
        return validate(json.load(stream))


def from_manifest(manifest, regions_directory):
    """The region a manifest was prepared with; a manifest from before regions is Omaha."""
    region = manifest.get('region')
    if region is None:
        return load(Path(regions_directory) / f'{DEFAULT_REGION}.json')
    return validate(region)


def clip_argument(spec):
    clip = spec['clip']
    return '' if clip is None else f"{clip['west']},{clip['south']},{clip['east']},{clip['north']}"


def prepared(manifest, spec):
    """Whether a manifest already describes a complete preparation of this region."""
    try:
        if 'coverage' not in manifest or 'filter' not in manifest['tiger']:
            return False
        region = manifest.get('region')
        name = DEFAULT_REGION if region is None else region['name']
        return name == spec['name'] and set(manifest['sources']) == set(spec['sources'])
    except (KeyError, TypeError):
        return False


def main(argv):
    command = argv[1] if len(argv) > 1 else ''
    if command == 'decode':
        # decode <base64> <output path>: validate and write the region file prepare.sh records.
        spec = validate(json.loads(base64.b64decode(argv[2]).decode('utf-8')))
        Path(argv[3]).write_text(json.dumps(spec, indent=2) + '\n', encoding='utf-8')
        return 0
    if command == 'sources':
        print(' '.join(load(argv[2])['sources']))
        return 0
    if command == 'clip':
        print(clip_argument(load(argv[2])))
        return 0
    if command == 'prepared':
        # prepared <manifest path> <region path>: exit 0 when the version is already prepared for this region.
        with Path(argv[2]).open(encoding='utf-8') as stream:
            manifest = json.load(stream)
        return 0 if prepared(manifest, load(argv[3])) else 1
    print('Usage: region.py decode <base64> <out> | sources <region> | clip <region> | prepared <manifest> <region>', file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv))
