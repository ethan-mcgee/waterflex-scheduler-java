#!/usr/bin/env bash
# Read-only check of the live scheduler VM. It changes nothing: no restarts, no tokens, no writes outside /tmp. The
# release it expects is the one deploy/vm/.env records (infra/release-vm.sh writes it), so it needs no edit per release.
# infra/deploy-vm.ps1 runs it after every release and restore; by hand, from the workstation in Git Bash:
#
#   ssh ethan@192.168.20.171 'bash -s' < infra/check-vm-live.sh
#   ssh ethan@192.168.20.171 'bash -s -- 602791f4' < infra/check-vm-live.sh   # and expect this road map identity
set -uo pipefail

expected_identity=${1:-}
[[ $expected_identity =~ ^[0-9a-f]*$ ]] || { echo "FAIL a map identity is a hex prefix, not '$expected_identity'"; exit 1; }
cd "${CHECKOUT:-$HOME/waterflex-scheduler-java}" || { echo "FAIL no checkout"; exit 1; }
setting() { sed -n "s/^$1=\([^[:space:]]*\).*/\1/p" deploy/vm/.env | tail -n 1; }
expected_release=$(setting SCHEDULER_RELEASE)
expected_revision=$(setting SCHEDULER_REVISION)
[[ -n $expected_release && -n $expected_revision ]] || { echo "FAIL deploy/vm/.env records no release; deploy one with infra/release-vm.sh"; exit 1; }
# Every metro the scheduler routes for: the ID before each = in ROUTING_METRO_URLS.
mapfile -t metros < <(setting ROUTING_METRO_URLS | tr ',' '\n' | sed -n 's/=.*//p')
compose=(docker compose -f deploy/vm/docker-compose.yml --env-file deploy/vm/.env)
private=http://127.0.0.1:8000
address=$(sed -n 's/^SCHEDULER_API_BIND=\([^[:space:]]*\).*/\1/p' deploy/vm/.env | tail -n 1)
port=$(sed -n 's/^SCHEDULER_API_PORT=\([0-9]*\).*/\1/p' deploy/vm/.env | tail -n 1)
public="https://$address${port:+:$port}"
ca=deploy/vm/tls/fullchain.pem
body=$(mktemp)
trap 'rm -f "$body"' EXIT
failures=0

pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; failures=$((failures + 1)); }
info() { echo "INFO $*"; }
expect() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1: expected $2, got $3"; fi; }
code() { curl --silent --max-time 15 --output "$body" --write-out '%{http_code}' "$@"; }

echo "== Host"
info "$(date -u +%Y-%m-%dT%H:%M:%SZ) $(hostname), $(uptime -p)"
info "load $(cut -d' ' -f1-3 /proc/loadavg) on $(nproc) vCPU"
free -h | sed 's/^/INFO /'
df -h / | sed 's/^/INFO /'
free_gb=$(df --output=avail -BG / | tail -n 1 | tr -dc 0-9)
[ "$free_gb" -ge 3 ] && pass "disk has ${free_gb} GB free" || fail "disk has only ${free_gb} GB free"

echo "== Checkout and release"
tag=$(git describe --tags --exact-match 2> /dev/null || echo "not on a tag")
expect "checkout is at the release tag" "$expected_release" "$tag"
[ -z "$(git status --porcelain -- . ':(exclude)deploy/vm/.env')" ] && pass "checkout has no local changes" || fail "checkout has local changes"
expect "env file records the release" "$expected_release" "$(sed -n 's/^SCHEDULER_RELEASE=\([^[:space:]]*\).*/\1/p' deploy/vm/.env | tail -n 1)"

echo "== Containers"
"${compose[@]}" ps --format '{{.Service}}	{{.State}}	{{.Status}}' | sed 's/^/INFO /'
bad=$("${compose[@]}" ps --format '{{.Service}} {{.State}} {{.Status}}' | grep -v -E 'running .*\(healthy\)|running Up [^(]*$' || true)
[ -z "$bad" ] && pass "every service is running and healthy" || fail "not healthy: $bad"
docker stats --no-stream --format '{{.Name}}	{{.MemUsage}}	{{.MemPerc}}	{{.CPUPerc}}' | sed 's/^/INFO /'
restarts=$(for id in $("${compose[@]}" ps -q); do docker inspect -f '{{.Name}} {{.RestartCount}}' "$id"; done | awk '$2 > 0')
[ -z "$restarts" ] && pass "no container has restarted" || fail "restarted containers: $restarts"

echo "== Health on the management listener"
for path in /health /health/daily; do
  expect "$path answers" 200 "$(code "$private$path")"
  info "$path $(cat "$body")"
  grep -q '"status":"ok"' "$body" && pass "$path status ok" || fail "$path status is not ok"
  grep -q "\"release\":\"$expected_release\"" "$body" && pass "$path runs $expected_release" || fail "$path does not run $expected_release"
  grep -q "\"revision\":\"$expected_revision\"" "$body" && pass "$path runs commit $expected_revision" || fail "$path does not run commit $expected_revision"
  (( ${#metros[@]} > 0 )) || fail "ROUTING_METRO_URLS names no metro"
  for metro in "${metros[@]}"; do
    grep -q "\"routing:$metro\":{\"status\":\"up\",\"detail\":\"$expected_identity" "$body" \
      && pass "$path routing for $metro is up${expected_identity:+ on map $expected_identity}" \
      || fail "$path routing for $metro is not up${expected_identity:+ on map $expected_identity}"
  done
done

echo "== Public HTTPS listener ($public)"
if [ -f "$ca" ]; then
  info "certificate $(openssl x509 -in "$ca" -noout -enddate)"
  openssl x509 -in "$ca" -noout -checkend $((60 * 86400)) > /dev/null && pass "certificate valid for 60 more days" || fail "certificate expires within 60 days"
  expect "public /health/live (trusting the VM certificate)" 200 "$(code --cacert "$ca" "$public/health/live")"
  expect "public /health is hidden" 404 "$(code --cacert "$ca" "$public/health")"
  expect "public API without a token" 401 "$(code --cacert "$ca" "$public/api/v1/whoami")"
  expect "public API with a wrong token" 401 "$(code --cacert "$ca" -H 'Authorization: Bearer not-a-token' "$public/api/v1/whoami")"
else
  fail "no certificate at $ca"
fi
expect "plain HTTP on the TLS port is refused" 400 "$(code "http://$address${port:+:$port}/api/v1/whoami")"

echo "== Cron, watchdog and backups"
crontab -l 2> /dev/null | grep -q 'infra/backup-vm.sh' && pass "backup is in crontab" || fail "backup is not in crontab"
crontab -l 2> /dev/null | grep -q 'infra/watchdog-vm.sh' && pass "watchdog is in crontab" || fail "watchdog is not in crontab"
if [ -s ~/scheduler-watchdog.log ]; then info "watchdog log, last lines:"; tail -n 5 ~/scheduler-watchdog.log | sed 's/^/INFO   /'
else pass "watchdog has logged nothing (every replica well)"; fi
dumps=$(ls -1t /var/backups/scheduler/*.dump 2> /dev/null | head -n 3)
if [ -n "$dumps" ]; then
  pass "backups present"; ls -lh $dumps | sed 's/^/INFO   /'
else
  info "no dump yet (the first runs at 03:15 VM time)"
fi
[ -s ~/scheduler-backup.log ] && { info "backup log, last lines:"; tail -n 5 ~/scheduler-backup.log | sed 's/^/INFO   /'; }

echo
if [ "$failures" -gt 0 ]; then echo "$failures check(s) failed"; exit 1; fi
echo "Every check passed"
