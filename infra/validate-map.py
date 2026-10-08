"""Read-only preview acceptance. Selection is geographic, independent of geocoding success."""
import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import sys
import urllib.request

manifest_path = Path(sys.argv[1])
manifest = json.loads(manifest_path.read_text(encoding='utf-8-sig'))
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
assert set(manifest['sources']) == {'nebraska', 'iowa', 'missouri'}
assert 'omaha.pmtiles' in manifest['artifacts'] and 'graph/properties' in manifest['artifacts']
samples = [('Omaha', 41.25855, -95.92929), ('Lincoln', 40.81362, -96.70260),
           ('Fremont', 41.4333, -96.498), ('Tekamah', 41.7783, -96.2211),
           ('West Point', 41.8417, -96.7086), ('Oakland Iowa', 41.309, -95.3967),
           ('Atlantic Iowa', 41.4036, -95.0139), ('Rock Port Missouri', 40.4111, -95.5169),
           ('Tarkio Missouri', 40.4403, -95.3775), ('Auburn', 40.3931, -95.838)]
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
print(json.dumps({'version': manifest['version'], 'validatedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'manifestSha256': hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
                  'routingIdentity': health['routingIdentity'], 'geocoder': geocoder, 'samples': results}, indent=2))
