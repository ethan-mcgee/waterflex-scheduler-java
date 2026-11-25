#!/usr/bin/env bash
# Prints the two image tags of a commit, separated by a space: the scheduler image's (the commit itself) and the routing
# image's (the last commit at or before it that changed what routing-service/Dockerfile builds from, so a release that
# leaves routing alone reuses its image and the VM does not reload the road graph).
#
#   bash infra/image-revisions.sh [<commit, tag or branch; default HEAD>]
#
# infra/release-vm.sh, infra/ship-images.sh, infra/deploy-vm.ps1 and .github/workflows/images.yml all name images with
# it, so they can never disagree (infra/test_release.py checks that none computes them another way). It reads only git history, never the working tree, so it answers for any commit, including a tag
# older than this script.
set -euo pipefail

fail() { echo "image-revisions: $*" >&2; exit 1; }

ref=${1:-HEAD}
cd "$(dirname "$0")/.."
[[ $(git rev-parse --is-shallow-repository) == false ]] || fail "a shallow clone cannot find the routing image's commit; fetch the full history"
commit=$(git rev-parse --verify --quiet "$ref^{commit}") || fail "no commit '$ref'"
engine=$(git rev-parse --short=12 "$commit")
# What routing-service/Dockerfile copies into its build (infra/test_release.py checks this list against it).
routing=$(git log -1 --format=%h --abbrev=12 "$commit" -- .mvn mvnw pom.xml scheduler-service/pom.xml calculation-engine/pom.xml routing-service)
[[ $engine =~ ^[0-9a-f]{12}$ ]] || fail "unexpected scheduler revision '$engine' for $ref"
[[ $routing =~ ^[0-9a-f]{12}$ ]] || fail "unexpected routing revision '$routing' for $ref"
echo "$engine $routing"
