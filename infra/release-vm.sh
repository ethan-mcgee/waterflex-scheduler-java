#!/usr/bin/env bash
# Deploy one release to the scheduler VM: check out its git tag, build the scheduler image tagged by the tag's commit
# and the routing image tagged by the last commit that changed what it is built from (so a release that leaves routing
# alone reuses it and does not reload the road graph), record the release in the env file and start the stack on it. Run it on the VM from this repository's
# checkout, which stays at the tag. Rolling back is the same command with the earlier tag; its images are reused while
# they are still on the VM.
#
#   bash infra/release-vm.sh v2026.11.13.1
#
# A release is an annotated tag on a commit already merged into main, made and pushed from a workstation:
#
#   git tag -a v2026.11.13.1 -m "<what changed>" <commit> && git push origin v2026.11.13.1
#
# ENV_FILE (default deploy/vm/.env) holds the stack's settings; the script changes only SCHEDULER_RELEASE,
# SCHEDULER_REVISION, ROUTING_REVISION and the two pull policies (missing) in it. COMPOSE_FILE (default deploy/vm/docker-compose.yml) lets the sandbox rehearse a release, and
# RELEASE_REMOTE (default origin) is where tags and main are read. Every release is appended to deploy/vm/releases.log.
set -euo pipefail

fail() { echo "release: $*" >&2; exit 1; }

tag=${1:-}
[[ $tag =~ ^v[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[0-9]+$ ]] || fail "name a release tag v<year>.<month>.<day>.<n>, for example v2026.11.13.1 (got '$tag')"
cd "$(git rev-parse --show-toplevel)"
env_file=${ENV_FILE:-deploy/vm/.env}
remote=${RELEASE_REMOTE:-origin}
export COMPOSE_FILE=${COMPOSE_FILE:-deploy/vm/docker-compose.yml}
log=deploy/vm/releases.log
[[ -f $env_file ]] || fail "no $env_file; copy deploy/vm/.env.example and fill it in (deploy/vm/README.md)"
compose=(docker compose --env-file "$env_file")

# A changed or untracked file would be built into the images under the tag's name. The env file is the script's own
# to change (the sandbox's is tracked) and is never built into an image.
[[ -z $(git status --porcelain -- . ":(exclude)$env_file") ]] || { git status --short >&2; fail "the checkout has changes; a release builds only its tag"; }

# The tag must be the one everyone sees on the remote, annotated, and on main. A tag that moved on the remote stops the
# fetch ("would clobber existing tag") rather than replacing the one already here.
git fetch --tags "$remote" main || fail "could not fetch $remote; if a tag moved there, compare git ls-remote --tags $remote with git show-ref --tags"
main=$(git rev-parse FETCH_HEAD)
[[ $(git cat-file -t "refs/tags/$tag" 2>/dev/null) == tag ]] || fail "$tag is not an annotated tag on $remote"
published=$(git ls-remote "$remote" "refs/tags/$tag" | cut -f1)
[[ -n $published && $published == "$(git rev-parse "refs/tags/$tag")" ]] || fail "$tag here differs from $remote's"
git merge-base --is-ancestor "$tag^{commit}" "$main" || fail "$tag is not on $remote's main; release merged commits only"

previous=$(sed -n 's/^SCHEDULER_RELEASE=\([^[:space:]]*\).*/\1/p' "$env_file" | tail -n 1)
git -c advice.detachedHead=false checkout --quiet --detach "$tag"
revision=$(git rev-parse --short=12 HEAD)
# What routing-service/Dockerfile copies into its build.
routing=$(git log -1 --format=%h --abbrev=12 -- .mvn mvnw pom.xml scheduler-service/pom.xml calculation-engine/pom.xml routing-service)
# The shell's values win over the env file's while Compose resolves the image names and build arguments. With the
# policies at missing, `up` starts the images checked below instead of rebuilding them (and restarting what runs them).
export SCHEDULER_RELEASE=$tag SCHEDULER_REVISION=$revision ROUTING_REVISION=$routing
export SCHEDULER_PULL_POLICY=missing ROUTING_PULL_POLICY=missing

images=$("${compose[@]}" config --images | grep '^waterflex-scheduler-' | sort -u || true)
expected=$(printf '%s\n' "waterflex-scheduler-engine:$revision" "waterflex-scheduler-routing:$routing")
grep -qx "waterflex-scheduler-engine:$revision" <<<"$images" || fail "the scheduler image is not tagged $revision; is SCHEDULER_IMAGE set in $env_file or the shell?"
if grep -vxF "$expected" <<<"$images"; then fail "the images above are not the release's"; fi

# Each image is built only when it is not here yet: rebuilding one would stamp it anew and restart what runs it.
# The sandbox runs no routing-omaha, so it has no routing image to build.
build() {
  local service=$1 image=$2 commit=$3
  grep -qx "$image" <<<"$images" || return 0
  if docker image inspect "$image" > /dev/null 2>&1; then
    echo "== Reusing $image"
  else
    echo "== Building $image"
    "${compose[@]}" build "$service"
  fi
  local built
  built=$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image")
  [[ $built == "$commit" ]] || fail "$image was built from '$built', not $commit; remove it and run again"
}
build scheduler-admin "waterflex-scheduler-engine:$revision" "$revision"
build routing-omaha "waterflex-scheduler-routing:$routing" "$routing"

# Recorded before the start, so a later `docker compose up` brings back this release even if this start fails.
set_key() {
  if grep -q "^$1=" "$env_file"; then sed -i "s|^$1=.*|$1=$2|" "$env_file"; else printf '%s=%s\n' "$1" "$2" >> "$env_file"; fi
}
set_key SCHEDULER_RELEASE "$tag"
set_key SCHEDULER_REVISION "$revision"
set_key ROUTING_REVISION "$routing"
set_key SCHEDULER_PULL_POLICY missing
set_key ROUTING_PULL_POLICY missing

echo "== Starting $tag (migrations first; a new road graph takes minutes to load)"
if ! "${compose[@]}" up -d --wait --wait-timeout 900; then
  "${compose[@]}" ps >&2
  fail "$tag did not become healthy; roll back with: bash infra/release-vm.sh ${previous:-<previous tag>}"
fi
running=$("${compose[@]}" ps --format '{{.Image}}' | grep '^waterflex-scheduler-' | sort -u || true)
if grep -vxF "$expected" <<<"$running"; then fail "the containers above do not run $tag"; fi

printf '%s %s %s routing=%s previous=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$tag" "$revision" "$routing" "${previous:-none}" >> "$log"
echo "== $tag ($revision, routing $routing) is running; recorded in $log"
