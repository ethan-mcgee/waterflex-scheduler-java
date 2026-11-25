#!/usr/bin/env bash
# Puts the VM back on its live layout: the documented start (infra/release-vm.sh's own `up`) with the live
# deploy/vm/.env and no override, which recreates every container the test changed and takes away its mounts and
# published metrics ports. Stops the sampler and the dead-man timer. Safe to run any number of times.
#
#   bash ~/perf-test/vm-restore.sh [reason]
#
# The dead-man timer (vm-arm.sh) runs it with the reason "deadman" when the workstation never did.
source "$(dirname "$0")/common.sh"
reason=${1:-requested}
mkdir -p "$OUT"
exec > >(tee -a "$OUT/restore.log") 2>&1
log "restore ($reason): starting the live layout"

bash "$(dirname "$0")/vm-sampler.sh" stop || true
# Disarms the dead-man timer: it finds its ID gone when it wakes and does nothing.
if [[ -f $PERF_HOME/deadman.id ]]; then rm -f "$PERF_HOME/deadman.id"; log "dead-man timer disarmed"; fi

cd "$CHECKOUT"
if docker compose "${LIVE_FILE_ARGS[@]}" --env-file "$LIVE_ENV" up -d --wait --wait-timeout 900; then
  log "restore ($reason): live layout is up and healthy"
else
  log "FAIL restore ($reason): the live layout did not come up healthy; run: cd $CHECKOUT && docker compose -f deploy/vm/docker-compose.yml --env-file deploy/vm/.env up -d"
  exit 1
fi
# What runs now, so the morning check can compare it with the live caps.
for service in "${ALL_SERVICES[@]}"; do
  id=$(container_of "$service")
  [[ -n $id ]] && docker inspect -f "$service cpus={{.HostConfig.NanoCpus}} memory={{.HostConfig.Memory}} mounts={{len .Mounts}} ports={{json .HostConfig.PortBindings}}" "$id"
done
date -u +%Y-%m-%dT%H:%M:%SZ > "$OUT/RESTORED"
