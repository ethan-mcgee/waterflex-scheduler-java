import hashlib
import json
from pathlib import Path
import sys

target = Path(sys.argv[2] if len(sys.argv) > 2 else '/maps/versions') / sys.argv[1]
manifest = json.loads((target / 'manifest.json').read_text())
def checksum(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()
assert checksum(target / 'omaha.osm.pbf') == manifest['mergedSha256']
assert (target / 'graph/properties').is_file()
# The contraction hierarchy graph (routing-service with ROUTING_CH_ENABLED=true) is prepared beside the flexible one
# and versioned with it, so an activated map always carries both and the routing identity names the one in use.
assert (target / 'graph-ch-car-v1/properties').is_file(), 'Prepare the CH graph (graph-ch-car-v1) before finalizing'
assert checksum(target / 'tiger-nominatim-preprocessed.csv.tar.gz') == manifest['tiger']['sha256'], 'Changed TIGER address data'
manifest['artifacts'] = {path.relative_to(target).as_posix(): checksum(path)
                         for path in [target / 'omaha.pmtiles', target / 'tiger-nominatim-preprocessed.csv.tar.gz',
                                      *sorted((target / 'graph').glob('*')), *sorted((target / 'graph-ch-car-v1').glob('*'))]
                         if path.is_file() and path.name != 'gh.lock'}
manifest['tools'] = {'planetiler': '0.9.0', 'graphhopper': '11.0', 'nominatim': '5.3', 'importStyle': 'address', 'tigerYear': manifest['tiger']['year']}
(target / 'manifest.json.next').write_text(json.dumps(manifest, indent=2) + '\n')
(target / 'manifest.json.next').replace(target / 'manifest.json')
