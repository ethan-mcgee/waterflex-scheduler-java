"""Read-only preview acceptance. Selection is geographic, independent of geocoding success."""
import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import sys
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parent / 'map-import'))
import region as map_region  # noqa: E402

manifest_path = Path(sys.argv[1])
manifest = json.loads(manifest_path.read_text(encoding='utf-8-sig'))
# The region the version was prepared with names its extracts and the points to route; an older manifest is Omaha.
region = map_region.from_manifest(manifest, Path(__file__).resolve().parent / 'map-import' / 'regions')
def get(url, body=None):
    headers = {'Content-Type': 'application/json'}
    if os.environ.get('ROUTING_AUTH_TOKEN'):
        headers['Authorization'] = 'Bearer ' + os.environ['ROUTING_AUTH_TOKEN']
    request = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)

health = get('http://localhost:18001/health')
assert health['ready'] and health['mapVersion'] == manifest['version']
geocoder = get('http://localhost:18082/status?format=json')
assert geocoder['status'] == 0
source_time = max(datetime.datetime.fromisoformat(s['sourceTimestamp'].replace('Z', '+00:00')) for s in manifest['sources'].values())
assert datetime.datetime.fromisoformat(geocoder['data_updated']) == source_time
metadata = get('http://localhost:18083/omaha.json')
west, south, east, north = metadata['bounds']
b = manifest['coverage']['bounds']
# PMTiles stores bounds at seven decimal places, so allow only that encoding precision.
epsilon = 0.0000001
assert west <= b['west'] + epsilon and east >= b['east'] - epsilon and south <= b['south'] + epsilon and north >= b['north'] - epsilon
assert set(manifest['sources']) == set(region['sources'])
assert 'omaha.pmtiles' in manifest['artifacts'] and 'graph/properties' in manifest['artifacts']
samples = [(sample['name'], sample['lat'], sample['lng']) for sample in region['samples']]
results = []
for name, lat, lng in samples:
    point = {'lat': lat, 'lng': lng}
    response = get('http://localhost:18001/internal/legs', {'pairs': [{'id': name, 'origin': point, 'destination': point}],
                                                        'expectedRoutingIdentity': health['routingIdentity']})
    # Reverse lookup checks data coverage, not automatic address acceptance.
    reverse = get(f'http://localhost:18082/reverse?lat={lat}&lon={lng}&format=jsonv2&addressdetails=1')
    assert 'address' in reverse, (name, reverse)
    z = 12; x = int((lng + 180) / 360 * 2**z)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * 2**z)
    with urllib.request.urlopen(f'http://localhost:18083/omaha/{z}/{x}/{y}.mvt', timeout=30) as tile:
        assert len(tile.read()) > 0, name
    results.append({'name': name, 'lat': lat, 'lng': lng, 'routing': response, 'state': reverse['address'].get('state')})
    assert response['pairs'][0]['leg']['routable'], (name, response)
print(json.dumps({'version': manifest['version'], 'region': region['name'], 'validatedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'manifestSha256': hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
                  'routingIdentity': health['routingIdentity'], 'geocoder': geocoder, 'samples': results}, indent=2))
