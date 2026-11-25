#!/usr/bin/env bash
# Copies one prepared map version from this workstation's map volume into the scheduler VM's map volume over SSH: only
# what routing-omaha reads (the merged extract, the CH graph) plus the manifest and validation report, about 90 MB for
# the central coast. Then compares SHA-256 on both ends.
#
#   bash infra/ship-map.sh <version> <user@vm>
#
# Run it in Git Bash. No archive is written on either machine.
set -euo pipefail
# PowerShell's plain `bash` is WSL, which has no Docker here.
[[ $(uname -s) == MINGW* ]] || { echo "Run this in Git Bash (from PowerShell: & 'C:\Program Files\Git\bin\bash.exe' $0 ...)" >&2; exit 2; }
export MSYS_NO_PATHCONV=1 # Git Bash would otherwise rewrite /maps into a Windows path.
version=${1:?Pass the map version, for example 2026-11-20-ca-central-coast}
target=${2:?Pass the SSH target, for example ethan@<vm address>}
[[ $version =~ ^[A-Za-z0-9._-]+$ ]] || { echo "Invalid version" >&2; exit 2; }
volume=waterflex-scheduler-java_map-data
files="$version/omaha.osm.pbf $version/graph-ch-car-v1 $version/manifest.json $version/validated.json"

docker run --rm -v "$volume:/maps:ro" alpine sh -c "cd /maps/versions && test -f $version/validated.json && test -f $version/graph-ch-car-v1/properties" \
  || { echo "$version is not prepared and validated here" >&2; exit 1; }

echo "== Copying $version to $target"
docker run --rm -v "$volume:/maps:ro" alpine tar -C /maps/versions -cf - $files | gzip -1 | \
  ssh "$target" "docker volume create scheduler-vm-maps > /dev/null && gunzip | docker run --rm -i -v scheduler-vm-maps:/maps alpine sh -c 'mkdir -p /maps/versions && tar -C /maps/versions -xf - && chmod -R a+rX /maps/versions/$version'"

sums="cd /maps/versions && find $version -type f | sort | xargs sha256sum"
here=$(docker run --rm -v "$volume:/maps:ro" alpine sh -c "cd /maps/versions && find $files -type f | sort | xargs sha256sum")
there=$(ssh "$target" "docker run --rm -v scheduler-vm-maps:/maps:ro alpine sh -c '$sums'")
if [[ "$here" == "$there" ]]; then
  echo "== $(wc -l <<<"$here") files match on both ends"
else
  diff <(echo "$here") <(echo "$there") || true
  echo "Checksums differ; do not start the VM on this copy" >&2; exit 1
fi
