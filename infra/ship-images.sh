#!/usr/bin/env bash
# Copies one commit's two images from GHCR to the scheduler VM through this workstation, for a VM that cannot reach the
# registry itself: pulls them here, checks their revision labels, and loads them into the VM's Docker over SSH under the
# local names Compose runs. infra/release-vm.sh (and a test stage) then finds them and reuses them ("== Reusing ...").
# The bytes are the ones GitHub Actions built (.github/workflows/images.yml); nothing is built here.
#
#   bash infra/ship-images.sh <tag or commit> <user@vm>
#
# Run it in Git Bash (its pipes carry binary data; Windows PowerShell 5.1 pipes do not). infra/deploy-vm.ps1 -Ship runs
# it. SCHEDULER_IMAGE_REGISTRY (default ghcr.io/ethan-mcgee) is where the images are pulled from.
set -euo pipefail
# PowerShell's plain `bash` is WSL, which has no Docker here and reads C:/ paths as relative folders.
[[ $(uname -s) == MINGW* || $(uname -s) == Linux ]] || { echo "Run this in Git Bash (from PowerShell: & 'C:\Program Files\Git\bin\bash.exe' $0 ...)" >&2; exit 2; }
fail() { echo "ship-images: $*" >&2; exit 1; }
ref=${1:?Pass the release tag or commit, for example v2026.11.20.2}
target=${2:?Pass the SSH target, for example ethan@<vm address>}
[[ $target =~ ^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+$ ]] || fail "not user@host: $target"
registry=${SCHEDULER_IMAGE_REGISTRY:-ghcr.io/ethan-mcgee}

cd "$(dirname "$0")/.."
git fetch --quiet --tags origin
read -r engine routing < <(bash infra/image-revisions.sh "$ref")
images=("waterflex-scheduler-engine:$engine" "waterflex-scheduler-routing:$routing")
echo "== $ref: ${images[*]}"

for image in "${images[@]}"; do
  docker pull --quiet "$registry/$image" > /dev/null \
    || fail "$registry/$image is not in the registry; build it with: gh workflow run images.yml -f ref=$ref"
  docker tag "$registry/$image" "$image"
  label=$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image")
  [[ $label == "${image##*:}" ]] || fail "$image carries revision '$label'"
done

# Only what the VM does not have yet crosses the network (about 1 GB uncompressed for both).
missing=()
for image in "${images[@]}"; do
  ssh -o BatchMode=yes "$target" "docker image inspect $image > /dev/null 2>&1" || missing+=("$image")
done
if (( ${#missing[@]} == 0 )); then
  echo "== The VM already has both images"
else
  echo "== Loading ${missing[*]} into $target"
  docker save "${missing[@]}" | gzip -1 | ssh -o BatchMode=yes "$target" 'gunzip | docker load'
fi
for image in "${images[@]}"; do
  here=$(docker image inspect --format '{{.Id}}' "$image")
  there=$(ssh -o BatchMode=yes "$target" "docker image inspect --format '{{.Id}}' $image")
  [[ $here == "$there" ]] || fail "$image is $there on the VM, not $here; remove it there (docker image rm $image) and run again"
done
echo "== Both images are on the VM with the IDs pulled from $registry"
