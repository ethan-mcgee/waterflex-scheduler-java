#!/usr/bin/env bash
# The VM performance test's host sampler: every SAMPLE_SECONDS (5) one JSON line in out/samples.jsonl with what the
# JVMs cannot see about themselves. Read from the host's own files, never through docker exec or docker stats, so the
# sampler costs the two cores almost nothing:
# - each container's cgroup cpu.stat (usage_usec, nr_periods, nr_throttled, throttled_usec), memory.current,
#   memory.peak and memory.events (oom, oom_kill);
# - the host's CPU counters from /proc/stat (steal is the hypervisor's), pressure stall totals for CPU, memory and IO,
#   the load average, available memory, swap and free disk.
# A value it cannot read is named in an "error" field, never written as zero. Under MIN_FREE_GB of free disk it writes
# out/DISK_LOW, which the workstation runner checks between cells.
#
#   bash ~/perf-test/vm-sampler.sh start|stop|status
source "$(dirname "$0")/common.sh"
# The running sampler's ID; a sampler loop stops as soon as the file no longer holds its own ID.
idfile=$PERF_HOME/sampler.id
every=${SAMPLE_SECONDS:-5}

# Running when the ID file is there and the sampler wrote a line within three intervals.
running() { [[ -f $idfile && -f $OUT/samples.jsonl ]] && (( $(date +%s) - $(date -r "$OUT/samples.jsonl" +%s) < 3 * every )); }

cgroup_dir() {
  local id=$1
  for dir in "/sys/fs/cgroup/system.slice/docker-$id.scope" "/sys/fs/cgroup/docker/$id"; do [[ -d $dir ]] && { echo "$dir"; return; }; done
}

# "key value" lines as a JSON object of numbers; an unreadable file gives null.
pairs() { [[ -r $1 ]] && awk 'BEGIN { printf "{" } NF == 2 && $2 ~ /^[0-9]+$/ { printf "%s\"%s\":%s", (n++ ? "," : ""), $1, $2 } END { printf "}" }' "$1" || printf 'null'; }
number() { [[ -r $1 ]] && { local value; value=$(tr -dc 0-9 < "$1"); [[ -n $value ]] && printf '%s' "$value" || printf 'null'; } || printf 'null'; }
# A pressure file's "some" and "full" totals (microseconds stalled since boot).
pressure() { [[ -r $1 ]] && awk 'BEGIN { printf "{" } { for (i = 2; i <= NF; i++) if ($i ~ /^total=/) { sub("total=", "", $i); printf "%s\"%s\":%s", (n++ ? "," : ""), $1, $i } } END { printf "}" }' "$1" || printf 'null'; }

sample() {
  local at cpu mem
  at=$(date +%s.%3N)
  # A field the kernel does not report is null, never zero.
  cpu=$(awk 'function f(i) { return (i <= NF && $i ~ /^[0-9]+$/) ? $i : "null" }
    $1 == "cpu" { printf "{\"user\":%s,\"nice\":%s,\"system\":%s,\"idle\":%s,\"iowait\":%s,\"irq\":%s,\"softirq\":%s,\"steal\":%s}", f(2), f(3), f(4), f(5), f(6), f(7), f(8), f(9) }' /proc/stat)
  mem=$(awk '/^(MemAvailable|SwapTotal|SwapFree):/ { gsub(":", "", $1); printf "%s\"%sKb\":%s", (n++ ? "," : ""), $1, $2 }' /proc/meminfo)
  printf '{"at":%s,"host":{"cpu":%s,"userHz":%s,"cpus":%s,"loadavg":"%s",%s,"freeGb":%s,"pressure":{"cpu":%s,"memory":%s,"io":%s}},"containers":{' \
    "$at" "$cpu" "$(getconf CLK_TCK)" "$(nproc)" "$(cut -d' ' -f1-3 /proc/loadavg)" "$mem" "$(free_gb)" \
    "$(pressure /proc/pressure/cpu)" "$(pressure /proc/pressure/memory)" "$(pressure /proc/pressure/io)"
  # One docker call per sample (the daemon's CPU comes from the same two cores): each running service and its ID.
  local first=1 service id dir running
  running=$(docker ps --no-trunc --filter "label=com.docker.compose.project=scheduler-vm" --format '{{.Label "com.docker.compose.service"}} {{.ID}}')
  for service in "${ALL_SERVICES[@]}"; do
    (( first )) || printf ','; first=0
    id=$(awk -v s="$service" '$1 == s { print $2; exit }' <<< "$running")
    if [[ -z $id ]]; then printf '"%s":{"error":"not running"}' "$service"; continue; fi
    dir=$(cgroup_dir "$id")
    if [[ -z $dir ]]; then printf '"%s":{"id":"%s","error":"no cgroup folder"}' "$service" "${id:0:12}"; continue; fi
    printf '"%s":{"id":"%s","cpuMax":"%s","cpuStat":%s,"memoryCurrent":%s,"memoryPeak":%s,"memoryEvents":%s}' "$service" "${id:0:12}" \
      "$(cat "$dir/cpu.max" 2> /dev/null || echo unreadable)" "$(pairs "$dir/cpu.stat")" "$(number "$dir/memory.current")" "$(number "$dir/memory.peak")" "$(pairs "$dir/memory.events")"
  done
  printf '}}\n'
}

case ${1:-} in
  start)
    running && { log "sampler already running"; exit 0; }
    mkdir -p "$OUT"
    # Detached and lowered in priority; it stops itself once the ID file stops naming it, so a stray loop cannot
    # outlive the test or run beside a newer one.
    id="$(date +%s)-$$-$RANDOM"
    echo "$id" > "$idfile"
    detached nohup nice -n 10 bash "$0" run "$id" > /dev/null 2>> "$OUT/sampler.err" < /dev/null &
    log "sampler $id started, every ${every} s into $OUT/samples.jsonl"
    ;;
  run)
    own=${2:?the run subcommand takes its ID}
    while [[ $(cat "$idfile" 2> /dev/null) == "$own" ]]; do
      sample >> "$OUT/samples.jsonl" || echo "$(date -u +%FT%TZ) a sample failed" >> "$OUT/sampler.err"
      (( $(free_gb) >= MIN_FREE_GB )) || touch "$OUT/DISK_LOW"
      sleep "$every"
    done
    ;;
  stop)
    [[ -f $idfile ]] && log "sampler stopped"
    rm -f "$idfile"
    ;;
  status) running && echo running || echo stopped ;;
  once) sample ;;
  *) die "usage: vm-sampler.sh start|stop|status|once" ;;
esac
