import hashlib
import json
from pathlib import Path
import sys

target = Path('/maps/versions') / sys.argv[1]
manifest = json.loads((target / 'manifest.json').read_text())
def checksum(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()
assert checksum(target / 'omaha.osm.pbf') == manifest['mergedSha256']
assert (target / 'graph/properties').is_file()
assert checksum(target / 'tiger-nominatim-preprocessed.csv.tar.gz') == manifest['tiger']['sha256'], 'Changed TIGER address data'
manifest['artifacts'] = {str(path.relative_to(target)): checksum(path)
                         for path in [target / 'omaha.pmtiles', target / 'tiger-nominatim-preprocessed.csv.tar.gz', *sorted((target / 'graph').glob('*'))]
                         if path.is_file() and path.name != 'gh.lock'}
manifest['tools'] = {'planetiler': '0.9.0', 'graphhopper': '11.0', 'nominatim': '5.3', 'importStyle': 'address', 'tigerYear': manifest['tiger']['year']}
(target / 'manifest.json.next').write_text(json.dumps(manifest, indent=2) + '\n')
(target / 'manifest.json.next').replace(target / 'manifest.json')
