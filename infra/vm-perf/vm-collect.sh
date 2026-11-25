#!/usr/bin/env bash
# Saves what a stage leaves on the VM, for the workstation to copy:
#
#   bash ~/perf-test/vm-collect.sh logs <stage>       before the next stage starts: recreating a container drops its
#                                                     log, so each service's log since the stage began goes to
#                                                     out/<stage>/logs/, with the watchdog's lines of the stage
#   bash ~/perf-test/vm-collect.sh clean <stage>      after the copy: removes the stage's logs and recordings from the VM
#   bash ~/perf-test/vm-collect.sh clean-routing      after the restore: removes routing's finished recordings
#   bash ~/perf-test/vm-collect.sh caps | host        the live capacity settings (no secret) or the host's shape
#
# A stage's flight recordings are written when its JVMs exit, so they are complete only once the next stage (or the
# restore) has started; the workstation copies profiles/<stage> then.
source "$(dirname "$0")/common.sh"
what=${1:-}
case $what in
  logs)
    stage=${2:-}; valid_stage "$stage" || die "usage: vm-collect.sh logs <stage>"
    [[ -f $OUT/$stage/stage.json ]] || die "stage $stage was never started"
    since=$(sed -n 's/.*"startedAt":"\([^"]*\)".*/\1/p' "$OUT/$stage/stage.json" | head -n 1)
    [[ -n $since ]] || die "stage $stage has no start time"
    mkdir -p "$OUT/$stage/logs"
    for service in "${ALL_SERVICES[@]}"; do
      id=$(container_of "$service")
      if [[ -z $id ]]; then echo "not running" > "$OUT/$stage/logs/$service.missing"; continue; fi
      docker logs --since "$since" "$id" > "$OUT/$stage/logs/$service.log" 2>&1
    done
    for file in /var/log/scheduler-watchdog.log "$HOME/scheduler-watchdog.log"; do
      [[ -r $file ]] && awk -v since="$since" '$1 >= since' "$file" > "$OUT/$stage/logs/watchdog.log"
    done
    du -sh "$OUT/$stage/logs" | sed 's/^/vm-perf: logs /'
    ;;
  clean)
    stage=${2:-}; valid_stage "$stage" || die "usage: vm-collect.sh clean <stage>"
    rm -rf "${OUT:?}/$stage" "${PROFILES:?}/$stage"
    log "removed $stage from the VM ($(free_gb) GB free)"
    ;;
  clean-routing)
    rm -f "${PROFILES:?}"/routing/*
    log "removed routing recordings ($(free_gb) GB free)"
    ;;
  caps)
    # The live settings that shape capacity, and the release; no other key of the env file (it holds secrets).
    grep -E '^(SCHEDULER_(BOOKING|DAILY)_(CPUS|MEMORY|SEARCH_CAPACITY|BACKGROUND_LIMIT|REPLICAS|DB_POOL_SIZE)|ROUTING_(CPUS|MEMORY)|SCHEDULER_DB_(CPUS|MEMORY)|SCHEDULER_PROXY_(CPUS|MEMORY)|SCHEDULER_RELEASE|SCHEDULER_REVISION|ROUTING_REVISION|ACTIVE_MAP_VERSION)=' "$LIVE_ENV"       || echo "none of the capacity keys is set: the Compose defaults apply"
    ;;
  host)
    echo "vCPU: $(nproc)"
    { lscpu 2> /dev/null | grep -E '^(Model name|CPU\(s\)|Hypervisor vendor|Thread\(s\) per core):' ; } || echo "lscpu: not available"
    free -m 2> /dev/null || echo "free: not available"
    df -h / 2> /dev/null
    docker version --format 'docker {{.Server.Version}}' 2> /dev/null || echo "docker: not reachable"
    ;;
  *) die "usage: vm-collect.sh logs <stage> | clean <stage> | clean-routing | caps | host" ;;
esac
