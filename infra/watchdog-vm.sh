#!/usr/bin/env bash
# Restart a hung scheduler replica on the VM. Docker marks a replica unhealthy when its /health fails, but never
# restarts it, so a stuck JVM would otherwise stay an outage of its pool until someone restarts it. Run it every minute
# from cron on the VM (deploy/vm/README.md, "Watchdog"):
#
#   * * * * * cd /path/to/waterflex-scheduler-java && bash infra/watchdog-vm.sh >> /var/log/scheduler-watchdog.log 2>&1
#
# Unhealthy is not enough: /health is readiness, and fails while routing or the database is down, which a restart does
# not fix. A replica is restarted only when it is unhealthy and its /health/live, which reads nothing, also fails three
# times in a row. A paused replica was paused by an operator and is left alone. Only the two scheduler pools are
# watched: a routing restart reloads the road graph for minutes. It prints nothing while every replica is well.
# WATCHDOG_STOP_SECONDS (default 10) is how long a restart waits before killing the replica; a hung JVM drains nothing.
# ENV_FILE and COMPOSE_FILE as for infra/release-vm.sh.
set -euo pipefail

cd "$(dirname "$0")/.."
env_file=${ENV_FILE:-deploy/vm/.env}
export COMPOSE_FILE=${COMPOSE_FILE:-deploy/vm/docker-compose.yml}
compose=(docker compose --env-file "$env_file")
grace=${WATCHDOG_STOP_SECONDS:-10}

# One run at a time: a restart and its probes can outlast the minute between runs.
if command -v flock > /dev/null; then exec 9< "$0"; flock -n 9 || exit 0; fi

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) watchdog: $*"; }

# /health/live from inside the replica, each try cut off after 3 seconds.
live() {
  local attempt
  for attempt in 1 2 3; do
    if timeout 10 docker exec "$1" timeout 3 bash -c \
        'exec 3<>/dev/tcp/127.0.0.1/8000 && printf "GET /health/live HTTP/1.0\r\n\r\n" >&3 && head -n 1 <&3 | grep -q " 200 "' \
        > /dev/null 2>&1; then
      return 0
    fi
    (( attempt < 3 )) && sleep 5
  done
  return 1
}

for service in scheduler-booking scheduler-daily; do
  for id in $("${compose[@]}" ps -q "$service"); do
    state=$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$id")
    name=$(docker inspect -f '{{.Name}}' "$id"); name=${name#/}
    case $state in
      "running unhealthy") ;;
      "paused "*) log "$name is paused; an operator paused it, so it is left alone"; continue ;;
      *) continue ;;
    esac
    if live "$id"; then
      log "$name is unhealthy but alive, so a dependency is down (its /health names which); left running"
      continue
    fi
    log "$name is unhealthy and /health/live failed 3 times; restarting it"
    if docker restart -t "$grace" "$id" > /dev/null; then log "$name restarted"; else log "$name could not be restarted"; fi
  done
done
