#!/bin/sh
set -eu
if [ "${1:-}" = activate ]; then
  version="${2:?Pass the prepared map version}"
  target="/maps/versions/$version"
  test -f "$target/manifest.json"
  test -f "$target/omaha.pmtiles"
  test -f "$target/graph/properties"
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
case "$version" in *[!A-Za-z0-9._-]*|'') echo 'Invalid version' >&2; exit 2;; esac
target="/maps/versions/$version"
if [ -e "$target/manifest.json" ]; then echo "Version already prepared: $version" >&2; exit 2; fi
mkdir -p "$target"
for state in nebraska iowa; do
  source="https://download.geofabrik.de/north-america/us/${state}-latest.osm.pbf"
  if [ ! -f "$target/${state}.osm.pbf" ]; then
    curl --fail --location --retry 3 --silent --show-error "$source" --output "$target/${state}.osm.pbf"
  fi
  if [ ! -f "$target/${state}.md5" ]; then
    curl --fail --location --retry 3 --silent --show-error "$source.md5" --output "$target/${state}.md5"
  fi
  expected="$(cut -d ' ' -f 1 "$target/${state}.md5")"
  actual="$(md5sum "$target/${state}.osm.pbf" | cut -d ' ' -f 1)"
  test "$expected" = "$actual" || { echo "Checksum failed: $state" >&2; exit 1; }
done
osmium merge "$target/nebraska.osm.pbf" "$target/iowa.osm.pbf" --overwrite -o "$target/omaha.osm.pbf"
osmium fileinfo "$target/omaha.osm.pbf" >/dev/null
nebraska_hash="$(sha256sum "$target/nebraska.osm.pbf" | cut -d ' ' -f 1)"
iowa_hash="$(sha256sum "$target/iowa.osm.pbf" | cut -d ' ' -f 1)"
merged_hash="$(sha256sum "$target/omaha.osm.pbf" | cut -d ' ' -f 1)"
nebraska_date="$(osmium fileinfo -e -g data.timestamp.last "$target/nebraska.osm.pbf")"
iowa_date="$(osmium fileinfo -e -g data.timestamp.last "$target/iowa.osm.pbf")"
cat > "$target/manifest.json" <<EOF
{"version":"$version","preparedAt":"$(date -u +%Y-%m-%dT%H:%M:%SZ)","sources":{"nebraska":{"url":"https://download.geofabrik.de/north-america/us/nebraska-latest.osm.pbf","sourceTimestamp":"$nebraska_date","sha256":"$nebraska_hash"},"iowa":{"url":"https://download.geofabrik.de/north-america/us/iowa-latest.osm.pbf","sourceTimestamp":"$iowa_date","sha256":"$iowa_hash"}},"mergedSha256":"$merged_hash"}
EOF
echo "Prepared $target"
