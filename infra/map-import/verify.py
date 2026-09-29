import hashlib
import json
from pathlib import Path
import sys

target = Path('/maps/versions') / sys.argv[1]
manifest = json.loads((target / 'manifest.json').read_text())
artifacts = {**manifest['artifacts'], 'omaha.osm.pbf': manifest['mergedSha256']}
for name, expected in artifacts.items():
    path = (target / name).resolve()
    assert path.is_relative_to(target.resolve()), 'Invalid manifest artifact path'
    with path.open('rb') as stream:
        assert hashlib.file_digest(stream, 'sha256').hexdigest() == expected, f'Changed artifact: {name}'
print('Prepared artifact checksums verified')
