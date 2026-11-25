# Shared by the VM performance test's scripts on the VM (infra/vm-perf/README.md). Sourced, never run.
# Everything the test writes is under PERF_HOME (~/perf-test), outside the checkout; the live deploy/vm/.env is only read.
set -euo pipefail

PERF_HOME=${PERF_HOME:-$HOME/perf-test}
CHECKOUT=${CHECKOUT:-$HOME/waterflex-scheduler-java}
LIVE_ENV=${LIVE_ENV:-$CHECKOUT/deploy/vm/.env}
# The live stack's Compose files, comma-separated; the local rehearsal adds deploy/vm/rehearsal.override.yml.
LIVE_COMPOSE_FILES=${LIVE_COMPOSE_FILES:-$CHECKOUT/deploy/vm/docker-compose.yml}
IFS=, read -r -a LIVE_FILES <<< "$LIVE_COMPOSE_FILES"
LIVE_FILE_ARGS=()
for file in "${LIVE_FILES[@]}"; do [[ -f $file ]] || { echo "vm-perf: no Compose file $file" >&2; exit 1; }; LIVE_FILE_ARGS+=(-f "$file"); done
OVERRIDE_FILE=$PERF_HOME/perf.override.yml
# 1 on the VM: also publish the pools' metrics ports on loopback (perf.ports.yml). The local rehearsal already does.
PERF_PORTS=${PERF_PORTS:-1}
OVERRIDE_ARGS=(-f "$OVERRIDE_FILE")
[[ $PERF_PORTS == 1 ]] && OVERRIDE_ARGS+=(-f "$PERF_HOME/perf.ports.yml")
OUT=$PERF_HOME/out
PROFILES=$PERF_HOME/profiles
STAGES=$PERF_HOME/stages
# Below this much free disk no stage starts and the sampler raises DISK_LOW (the VM had 9.2 GB free on 2026-11-24).
MIN_FREE_GB=${MIN_FREE_GB:-3}
ALL_SERVICES=(scheduler-booking scheduler-daily routing-omaha scheduler-db scheduler-proxy)

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) vm-perf: $*"; }
die() { log "FAIL $*" >&2; exit 1; }

# Detached from this shell where setsid exists (the VM); Git Bash, used by the local rehearsal, has none.
detached() { if command -v setsid > /dev/null; then setsid "$@"; else "$@"; fi; }

free_gb() { df --output=avail -BG / | tail -n 1 | tr -dc 0-9; }

# The container ID of a service of the live project, empty when it has none running.
container_of() { docker ps -q --filter "label=com.docker.compose.project=scheduler-vm" --filter "label=com.docker.compose.service=$1" | head -n 1; }

# A stage name is a file and folder name: letters, digits and dashes only.
valid_stage() { [[ $1 =~ ^[a-z0-9][a-z0-9-]{0,62}$ ]]; }

[[ -f $LIVE_ENV ]] || die "no $LIVE_ENV; is CHECKOUT ($CHECKOUT) the scheduler's checkout?"
