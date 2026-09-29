#!/bin/sh
set -eu
if [ "${1:-}" = finalize ]; then
  version="${2:?Pass the prepared map version}"
  case "$version" in *[!A-Za-z0-9._-]*|''|.|..) echo 'Invalid version' >&2; exit 2;; esac
  exec python3 /usr/local/bin/finalize-map.py "$version"
fi
if [ "${1:-}" = activate ]; then
  version="${2:?Pass the prepared map version}"
  case "$version" in *[!A-Za-z0-9._-]*|''|.|..) echo 'Invalid version' >&2; exit 2;; esac
  target="/maps/versions/$version"
  test -f "$target/manifest.json"
  test -f "$target/omaha.pmtiles"
  test -f "$target/graph/properties"
  test -f "$target/validated.json" || { echo 'Validate all three preview services before activation' >&2; exit 1; }
  python3 - "$target" <<'PY'
import hashlib,json,pathlib,sys
p=pathlib.Path(sys.argv[1]); v=json.loads((p/'validated.json').read_text())
assert v['manifestSha256']==hashlib.sha256((p/'manifest.json').read_bytes()).hexdigest(), 'Validation no longer matches prepared manifest'
PY
  ln -s "versions/$version/omaha.osm.pbf" /maps/omaha.osm.pbf.next
  ln -s "versions/$version/omaha.pmtiles" /maps/omaha.pmtiles.next
  ln -s "versions/$version/graph" /maps/graph.next
  mv -Tf /maps/omaha.osm.pbf.next /maps/omaha.osm.pbf
  mv -Tf /maps/omaha.pmtiles.next /maps/omaha.pmtiles
  mv -Tf /maps/graph.next /maps/graph
  printf '%s' "$version" > /maps/map-version.next
  mv -Tf /maps/map-version.next /maps/map-version
  exit 0
fi
version="${1:?Pass a map version such as 2026-09-16}"
case "$version" in *[!A-Za-z0-9._-]*|''|.|..) echo 'Invalid version' >&2; exit 2;; esac
target="/maps/versions/$version"
if [ -e "$target/manifest.json" ]; then
  if python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); assert "missouri" in m["sources"] and "coverage" in m' "$target/manifest.json" 2>/dev/null; then
    echo "Version already prepared: $version"; exit 0
  fi
fi
mkdir -p "$target"
printf '%s' "${MAP_COVERAGE_BASE64:?Pass depot-derived coverage}" | base64 -d > "$target/coverage.json"
python3 -c 'import json,sys; c=json.load(open(sys.argv[1])); b=c["bounds"]; assert c["circles"] and c["marginMi"]==10 and -180<=b["west"]<b["east"]<=180 and -90<=b["south"]<b["north"]<=90' "$target/coverage.json"
for state in nebraska iowa missouri; do
  source="https://download.geofabrik.de/north-america/us/${state}-latest.osm.pbf"
  if [ ! -f "$target/${state}.osm.pbf" ]; then
    curl --fail --location --retry 3 --silent --show-error "$source" --output "$target/${state}.osm.pbf.part"
    mv "$target/${state}.osm.pbf.part" "$target/${state}.osm.pbf"
  fi
  if [ ! -f "$target/${state}.md5" ]; then
    curl --fail --location --retry 3 --silent --show-error "$source.md5" --output "$target/${state}.md5"
  fi
  expected="$(cut -d ' ' -f 1 "$target/${state}.md5")"
  actual="$(md5sum "$target/${state}.osm.pbf" | cut -d ' ' -f 1)"
  test "$expected" = "$actual" || { echo "Checksum failed: $state" >&2; exit 1; }
done
osmium merge "$target/nebraska.osm.pbf" "$target/iowa.osm.pbf" "$target/missouri.osm.pbf" --overwrite -o "$target/omaha.osm.pbf"
osmium fileinfo "$target/omaha.osm.pbf" >/dev/null
python3 - "$target" "$version" <<'PY'
import hashlib, json, pathlib, subprocess, sys, datetime
with pathlib.Path(sys.argv[1], 'coverage.json').open() as f:
    coverage = json.load(f)
target = pathlib.Path(sys.argv[1])
def checksum(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()
manifest = {'version': sys.argv[2], 'preparedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'coverage': coverage, 'mergedSha256': checksum(target / 'omaha.osm.pbf'), 'sources': {}}
for state in ['nebraska', 'iowa', 'missouri']:
    manifest['sources'][state] = {
        'url': f'https://download.geofabrik.de/north-america/us/{state}-latest.osm.pbf',
        'sourceTimestamp': subprocess.check_output(['osmium', 'fileinfo', '-e', '-g', 'data.timestamp.last', str(target / f'{state}.osm.pbf')], text=True).strip(),
        'sha256': checksum(target / f'{state}.osm.pbf'),
    }
(target / 'manifest.json.next').write_text(json.dumps(manifest, indent=2) + '\n')
(target / 'manifest.json.next').replace(target / 'manifest.json')
PY
echo "Prepared $target"
