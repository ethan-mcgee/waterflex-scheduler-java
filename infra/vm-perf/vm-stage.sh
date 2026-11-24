#!/usr/bin/env bash
# Starts one stage of the VM performance test: the live stack with perf.override.yml and a stage env file, which is the
# live deploy/vm/.env with the test's recording settings and the stage's KEY=VALUE settings in place of the live ones.
#
#   bash ~/perf-test/vm-stage.sh <stage> [KEY=VALUE ...]
#
# Only the keys below may be set, so a typo or a secret never lands in a stage file. The booking and daily replicas
# start again for every stage (their flight recording is the stage's own file), routing only when its settings change
# (it reloads the road graph for minutes). Writes out/<stage>/stage.json: when the stage began and what each container
# runs with. Refuses to start under MIN_FREE_GB of free disk. On failure the caller runs vm-restore.sh.
source "$(dirname "$0")/common.sh"

ALLOWED='^(SCHEDULER_(BOOKING|DAILY)_(CPUS|MEMORY|SEARCH_CAPACITY|DB_POOL_SIZE)|SCHEDULER_DAILY_BACKGROUND_LIMIT|SCHEDULER_BOOKING_BACKGROUND_LIMIT|ROUTING_(CPUS|MEMORY)|SCHEDULER_DB_(CPUS|MEMORY)|SCHEDULER_PROXY_CPUS|SCHEDULER_STUDY_PROFILES)$'

stage=${1:-}
valid_stage "$stage" || die "usage: vm-stage.sh <stage: lowercase letters, digits, dashes> [KEY=VALUE ...]"
shift
overrides=()
for pair in "$@"; do
  [[ $pair =~ ^([A-Z_]+)=([A-Za-z0-9./_-]*)$ ]] || die "not KEY=VALUE with a plain value: $pair"
  [[ ${BASH_REMATCH[1]} =~ $ALLOWED ]] || die "a stage may not set ${BASH_REMATCH[1]}"
  overrides+=("$pair")
done

free=$(free_gb)
(( free >= MIN_FREE_GB )) || die "only ${free} GB free; a stage needs ${MIN_FREE_GB} GB"

# What an earlier start of this stage left (one that failed before the workstation copied it) is moved aside to
# attic/, never deleted and never mixed into this start's logs and recordings.
attic=$PERF_HOME/attic/$stage-$(date -u +%Y%m%dT%H%M%SZ)
for leftover in "$OUT/$stage" "$PROFILES/$stage"; do
  if [[ -d $leftover ]] && [[ -n $(find "$leftover" -type f -print -quit) ]]; then
    mkdir -p "$attic"
    mv "$leftover" "$attic/$(basename "$(dirname "$leftover")")"
    log "stage $stage: moved what an earlier start left in $leftover to $attic"
  fi
done
mkdir -p "$OUT/$stage" "$STAGES" "$PROFILES/routing" "$PROFILES/$stage/solves"
# The replicas run as uid 10001, so their folders are writable by anyone; ~/perf-test itself stays the operator's. On
# Linux that must work; Windows (the local rehearsal) has no such modes, and Docker Desktop's mounts are writable anyway.
if ! chmod 0777 "$PROFILES/routing" "$PROFILES/$stage" "$PROFILES/$stage/solves" 2> /dev/null && [[ $(uname -s) == Linux ]]; then
  die "cannot open $PROFILES to the containers' user"
fi

# JVM flags of the test: a flight recording on the JDK's profile settings for the JVM's whole life, dumped when it
# exits, and the collector's and safepoints' log. Routing's file names carry the JVM's start time, so one recording
# per routing start, and its flags never change between stages.
jvm() {
  local name=$1 dir=$2
  echo "-XX:MaxRAMPercentage=75 -XX:StartFlightRecording=name=perf,settings=profile,dumponexit=true,maxsize=200m,filename=/profiles/$dir/$name.jfr -Xlog:gc*,safepoint:file=/profiles/$dir/$name-gc.log:time,uptime,level,tags:filecount=3,filesize=20m"
}
routing_jvm="-XX:MaxRAMPercentage=75 -XX:StartFlightRecording=name=perf,settings=profile,dumponexit=true,maxsize=200m,filename=/profiles/routing/routing-%t.jfr -Xlog:gc*,safepoint:file=/profiles/routing/routing-gc-%t.log:time,uptime,level,tags:filecount=3,filesize=20m"

# The stage file: every live line except the keys this stage sets, then the test's and the stage's own. It holds the
# live secrets, so only the operator can read it, and it never leaves the VM.
env_file=$STAGES/$stage.env
keys=(PERF_PROFILES SCHEDULER_BOOKING_JAVA_OPTIONS SCHEDULER_DAILY_JAVA_OPTIONS ROUTING_JAVA_OPTIONS SCHEDULER_STUDY_RECEIPTS TIMEFOLD_LOG_LEVEL SCHEDULER_STUDY_PROFILES)
for pair in "${overrides[@]}"; do keys+=("${pair%%=*}"); done
pattern="^($(IFS='|'; echo "${keys[*]}"))="
(
  umask 077
  grep -Ev "$pattern" "$LIVE_ENV" > "$env_file.partial"
  {
    echo "# vm-perf stage $stage, written $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "PERF_PROFILES=$PROFILES"
    echo "SCHEDULER_BOOKING_JAVA_OPTIONS=\"$(jvm booking "$stage")\""
    echo "SCHEDULER_DAILY_JAVA_OPTIONS=\"$(jvm daily "$stage")\""
    echo "ROUTING_JAVA_OPTIONS=\"$routing_jvm\""
    echo "SCHEDULER_STUDY_RECEIPTS=true"
    echo "TIMEFOLD_LOG_LEVEL=INFO"
    # Per-solve recordings only where a stage asks for them: each phase of each solve is its own file.
    solves=""
    for pair in "${overrides[@]}"; do
      if [[ $pair == SCHEDULER_STUDY_PROFILES=* ]]; then solves=1; fi
      echo "$pair"
    done
    if [[ -z $solves ]]; then echo "SCHEDULER_STUDY_PROFILES="; fi
  } >> "$env_file.partial"
  mv "$env_file.partial" "$env_file"
)

cd "$CHECKOUT"
compose=(docker compose "${LIVE_FILE_ARGS[@]}" "${OVERRIDE_ARGS[@]}" --env-file "$env_file")
log "stage $stage: starting with ${overrides[*]:-the live caps}"

# A replica can hang while it starts: on 2026-11-24 (stage drive-live) the booking JVM, with 1 CPU available, stopped
# 16 s in with its request threads and a /health check's virtual thread all waiting on Spring Boot's jar file lock, and
# /health/live never answered again. Such a replica is kept as evidence under out/<stage>/hangs/<n>-<service>/ (its
# state, its log with a thread dump, its recording and collector log), counted in stage.json and started again, at most
# START_ATTEMPTS times in all. A hang is a finding of the run, never a reason to lose the night.
START_ATTEMPTS=${START_ATTEMPTS:-3}
hangs=0

# The booking and daily replicas Docker marks unhealthy, one per line.
unhealthy_replicas() {
  local service id
  for service in scheduler-booking scheduler-daily; do
    id=$(container_of "$service")
    [[ -n $id ]] || continue
    if [[ $(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$id") == unhealthy ]]; then echo "$service"; fi
  done
}

# Saves a hung replica's evidence and stops it, which also writes its flight recording.
keep_hang() {
  local service=$1 name=${1#scheduler-} id dir file
  hangs=$((hangs + 1))
  dir=$OUT/$stage/hangs/$hangs-$service
  mkdir -p "$dir"
  id=$(container_of "$service")
  if [[ -n $id ]]; then
    docker inspect -f '{{json .State}}' "$id" > "$dir/state.json" 2>&1 || true
    # SIGQUIT makes the JVM print every platform thread to its log; it needs nothing inside the JRE image.
    docker kill --signal=QUIT "$id" > /dev/null 2>&1 || true
    sleep 3
    docker logs "$id" > "$dir/container.log" 2>&1 || true
  fi
  "${compose[@]}" stop -t 30 "$service" || true
  for file in "$PROFILES/$stage/$name.jfr" "$PROFILES/$stage/$name"-gc.log*; do
    if [[ -e $file ]]; then mv "$file" "$dir/"; fi
  done
}

# Runs up with the given arguments until its replicas are healthy, starting a hung one again.
up_healthy() {
  local attempt hung
  for (( attempt = 1; ; attempt++ )); do
    "${compose[@]}" up -d --wait "$@" && return 0
    mapfile -t hung < <(unhealthy_replicas)
    if (( ${#hung[@]} == 0 )); then return 1; fi
    for service in "${hung[@]}"; do keep_hang "$service"; done
    if (( attempt >= START_ATTEMPTS )); then return 1; fi
    log "stage $stage: ${hung[*]} unhealthy at start (attempt $attempt of $START_ATTEMPTS), kept in hangs/; starting again"
    set -- --wait-timeout 600 --no-deps "${hung[@]}"
  done
}

up_healthy --wait-timeout 900 || die "stage $stage did not come up healthy"
# Every stage starts the booking and daily JVMs again, even when nothing of theirs changed: a fresh recording and a
# cold JIT, which the stage's discarded warm-up cell then warms.
up_healthy --wait-timeout 600 --force-recreate --no-deps scheduler-booking scheduler-daily \
  || die "stage $stage: the booking and daily replicas did not come back healthy"

# What each container runs with: limits, image and the admission and recording settings (no secret is listed).
started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
{
  printf '{"stage":"%s","startedAt":"%s","startHangs":%d,"overrides":[' "$stage" "$started" "$hangs"
  first=1
  for pair in "${overrides[@]}"; do (( first )) || printf ','; first=0; printf '"%s"' "$pair"; done
  printf '],"containers":['
  first=1
  for service in "${ALL_SERVICES[@]}"; do
    id=$(container_of "$service")
    (( first )) || printf ','; first=0
    if [[ -z $id ]]; then printf '{"service":"%s","error":"not running"}' "$service"; continue; fi
    docker inspect -f '{"service":"'"$service"'","id":"{{.Id}}","image":"{{.Config.Image}}","startedAt":"{{.State.StartedAt}}","nanoCpus":{{.HostConfig.NanoCpus}},"memoryBytes":{{.HostConfig.Memory}},"env":[{{range $i, $e := .Config.Env}}{{if or (eq (index (split $e "=") 0) "SCHEDULER_SEARCH_CAPACITY") (eq (index (split $e "=") 0) "SCHEDULER_SEARCH_BACKGROUND_LIMIT") (eq (index (split $e "=") 0) "SCHEDULER_DAILY_QUEUE_WORKERS") (eq (index (split $e "=") 0) "SCHEDULER_DB_POOL_SIZE") (eq (index (split $e "=") 0) "SCHEDULER_STUDY_PROFILES") (eq (index (split $e "=") 0) "JAVA_TOOL_OPTIONS")}}{{json $e}},{{end}}{{end}}""]}' "$id"
  done
  printf ']}\n'
} > "$OUT/$stage/stage.json"
bash "$(dirname "$0")/vm-sampler.sh" start
log "stage $stage: up at $started"
