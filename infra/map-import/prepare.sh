#!/bin/sh
set -eu
if [ "${1:-}" = verify ]; then
  version="${2:?Pass the prepared map version}"
  case "$version" in *[!A-Za-z0-9._-]*|''|.|..) echo 'Invalid version' >&2; exit 2;; esac
  exec python3 /usr/local/bin/verify-map.py "$version"
fi
if [ "${1:-}" = finalize ]; then
  version="${2:?Pass the prepared map version}"
  case "$version" in *[!A-Za-z0-9._-]*|''|.|..) echo 'Invalid version' >&2; exit 2;; esac
  exec python3 /usr/local/bin/finalize-map.py "$version"
fi
# Everything a version needs before it may serve: its artifacts, TIGER data, and a validation report for this manifest.
check_activation() {
  target="/maps/versions/$1"
  for file in manifest.json omaha.pmtiles graph/properties graph-ch-car-v1/properties tiger-nominatim-preprocessed.csv.tar.gz; do
    test -f "$target/$file" || { echo "Missing $file in $1" >&2; exit 1; }
  done
  test -f "$target/validated.json" || { echo 'Validate all three preview services before activation' >&2; exit 1; }
  python3 /usr/local/bin/verify-map.py "$1"
  python3 - "$target" <<'PY'
import hashlib,json,pathlib,sys
p=pathlib.Path(sys.argv[1]); v=json.loads((p/'validated.json').read_text())
assert v['manifestSha256']==hashlib.sha256((p/'manifest.json').read_bytes()).hexdigest(), 'Validation no longer matches prepared manifest'
PY
}
if [ "${1:-}" = check-activation ]; then
  version="${2:?Pass the prepared map version}"
  case "$version" in *[!A-Za-z0-9._-]*|''|.|..) echo 'Invalid version' >&2; exit 2;; esac
  check_activation "$version"
  echo "Ready to activate $version"
  exit 0
fi
if [ "${1:-}" = activate ]; then
  version="${2:?Pass the prepared map version}"
  case "$version" in *[!A-Za-z0-9._-]*|''|.|..) echo 'Invalid version' >&2; exit 2;; esac
  check_activation "$version"
  ln -s "versions/$version/omaha.osm.pbf" /maps/omaha.osm.pbf.next
  ln -s "versions/$version/omaha.pmtiles" /maps/omaha.pmtiles.next
  ln -s "versions/$version/graph" /maps/graph.next
  ln -s "versions/$version/graph-ch-car-v1" /maps/graph-ch-car-v1.next
  ln -s "versions/$version/tiger-nominatim-preprocessed.csv.tar.gz" /maps/tiger-nominatim-preprocessed.csv.tar.gz.next
  mv -Tf /maps/omaha.osm.pbf.next /maps/omaha.osm.pbf
  mv -Tf /maps/omaha.pmtiles.next /maps/omaha.pmtiles
  mv -Tf /maps/graph.next /maps/graph
  mv -Tf /maps/graph-ch-car-v1.next /maps/graph-ch-car-v1
  mv -Tf /maps/tiger-nominatim-preprocessed.csv.tar.gz.next /maps/tiger-nominatim-preprocessed.csv.tar.gz
  printf '%s' "$version" > /maps/map-version.next
  mv -Tf /maps/map-version.next /maps/map-version
  exit 0
fi
version="${1:?Pass a map version such as 2026-09-16}"
case "$version" in *[!A-Za-z0-9._-]*|''|.|..) echo 'Invalid version' >&2; exit 2;; esac
target="/maps/versions/$version"
mkdir -p "$target"
# The region names the Geofabrik extracts to merge, an optional clip box and the points validation routes
# (infra/map-import/regions/<name>.json). It is recorded in the manifest; a manifest without one is the Omaha region.
python3 /usr/local/bin/region-map.py decode "${MAP_REGION_BASE64:?Pass a map region}" "$target/region.json"
if [ -e "$target/manifest.json" ]; then
  if python3 /usr/local/bin/region-map.py prepared "$target/manifest.json" "$target/region.json" 2>/dev/null; then
    echo "Version already prepared: $version"; exit 0
  fi
fi
printf '%s' "${MAP_COVERAGE_BASE64:?Pass depot-derived coverage}" | base64 -d > "$target/coverage.json"
python3 -c 'import json,sys; c=json.load(open(sys.argv[1])); b=c["bounds"]; assert c["circles"] and c["marginMi"]==10 and -180<=b["west"]<b["east"]<=180 and -90<=b["south"]<b["north"]<=90' "$target/coverage.json"
sources="$(python3 /usr/local/bin/region-map.py sources "$target/region.json")"
clip="$(python3 /usr/local/bin/region-map.py clip "$target/region.json")"
for state in $sources; do
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
# US Census TIGER address ranges, preprocessed by the Nominatim project, place house numbers OpenStreetMap lacks
# along their street. The national file is downloaded from nominatim.org directly (the Nominatim image's own TIGER
# download is never used), filtered to the depot circles plus margin, and then deleted; Nominatim imports each row
# with its own query, so ranges outside the service area only cost import time. The source and filtered SHA-256 are
# recorded in the manifest and the filtered file is verified before activation.
tiger_year="${TIGER_YEAR:-2025}"
case "$tiger_year" in [0-9][0-9][0-9][0-9]) ;; *) echo 'Invalid TIGER_YEAR' >&2; exit 2;; esac
tiger_url="https://nominatim.org/data/tiger${tiger_year}-nominatim-preprocessed.csv.tar.gz"
tiger="$target/tiger-nominatim-preprocessed.csv.tar.gz"
national="$target/tiger-national.csv.tar.gz"
coverage_sha="$(sha256sum "$target/coverage.json" | cut -d ' ' -f 1)"
if [ -f "$tiger" ]; then
  python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["coverageSha256"]==sys.argv[2]' "$target/tiger-filter.json" "$coverage_sha" \
    || { echo 'Depot coverage changed since TIGER was filtered; prepare a new version' >&2; exit 1; }
else
  if [ ! -f "$national" ]; then
    curl --fail --location --retry 3 --silent --show-error --user-agent "waterflex-map-import" "$tiger_url" --output "$national.part"
    tar -tzf "$national.part" >/dev/null
    mv "$national.part" "$national"
  fi
  source_sha="$(sha256sum "$national" | cut -d ' ' -f 1)"
  stats="$(python3 /usr/local/bin/filter-tiger.py "$target/coverage.json" "$national" "$tiger.part")"
  python3 -c 'import json,sys; print(json.dumps({"sourceSha256": sys.argv[1], "coverageSha256": sys.argv[2], "filter": json.loads(sys.argv[3])}, indent=2))' \
    "$source_sha" "$coverage_sha" "$stats" > "$target/tiger-filter.json"
  mv "$tiger.part" "$tiger"
  rm -f "$national"
  echo "Filtered TIGER: $stats"
fi
# A region with a clip box keeps only that box of each extract (a whole state is far more than a metro needs), so
# the merge, the tiles and both road graphs stay small. The merged file keeps its historical name whatever the region.
inputs=""
for state in $sources; do
  if [ -n "$clip" ]; then
    if [ ! -f "$target/${state}.clipped.osm.pbf" ]; then
      osmium extract --bbox "$clip" --overwrite --output-format pbf -o "$target/${state}.clipped.osm.pbf.part" "$target/${state}.osm.pbf"
      mv "$target/${state}.clipped.osm.pbf.part" "$target/${state}.clipped.osm.pbf"
    fi
    inputs="$inputs $target/${state}.clipped.osm.pbf"
  else
    inputs="$inputs $target/${state}.osm.pbf"
  fi
done
# shellcheck disable=SC2086
osmium merge $inputs --overwrite -o "$target/omaha.osm.pbf"
osmium fileinfo "$target/omaha.osm.pbf" >/dev/null
python3 - "$target" "$version" "$tiger_year" "$tiger_url" <<'PY'
import hashlib, json, pathlib, subprocess, sys, datetime
with pathlib.Path(sys.argv[1], 'coverage.json').open() as f:
    coverage = json.load(f)
with pathlib.Path(sys.argv[1], 'region.json').open() as f:
    region = json.load(f)
target = pathlib.Path(sys.argv[1])
def checksum(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()
manifest = {'version': sys.argv[2], 'preparedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'region': region, 'coverage': coverage, 'mergedSha256': checksum(target / 'omaha.osm.pbf'), 'sources': {}}
for state in region['sources']:
    manifest['sources'][state] = {
        'url': f'https://download.geofabrik.de/north-america/us/{state}-latest.osm.pbf',
        'sourceTimestamp': subprocess.check_output(['osmium', 'fileinfo', '-e', '-g', 'data.timestamp.last', str(target / f'{state}.osm.pbf')], text=True).strip(),
        'sha256': checksum(target / f'{state}.osm.pbf'),
    }
    if region['clip'] is not None:
        manifest['sources'][state]['clippedSha256'] = checksum(target / f'{state}.clipped.osm.pbf')
tiger_filter = json.loads((target / 'tiger-filter.json').read_text())
manifest['tiger'] = {'year': sys.argv[3], 'url': sys.argv[4], 'sourceSha256': tiger_filter['sourceSha256'],
                     'sha256': checksum(target / 'tiger-nominatim-preprocessed.csv.tar.gz'), 'filter': tiger_filter['filter']}
(target / 'manifest.json.next').write_text(json.dumps(manifest, indent=2) + '\n')
(target / 'manifest.json.next').replace(target / 'manifest.json')
PY
echo "Prepared $target"
