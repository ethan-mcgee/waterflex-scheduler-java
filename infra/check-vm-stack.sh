#!/usr/bin/env bash
# The scheduler VM's stack as WaterFlex Software meets it, built from this tree: the sandbox (deploy/vm with
# sandbox.override.yml, the fixture router in place of the road graph) with the monitoring profile,
# checked over HTTPS through scheduler-proxy. CI runs it on every change ("VM stack over HTTPS"), so a new image for
# nginx, Prometheus, Postgres or the JRE, a proxy configuration change or a Dockerfile change is exercised before it
# merges; the Compose files themselves are only rendered elsewhere. It also runs on a Linux or Git Bash workstation:
#
#   bash infra/check-vm-stack.sh [<output directory>]
#
# It checks that every service is healthy and runs with the users and capabilities deploy/vm/docker-compose.yml sets;
# that Prometheus accepts its configuration and scrapes both pools; the proxy's TLS versions, HTTP/2 and JSON refusals;
# the conformance kit over HTTPS; that a hung booking replica is taken out of rotation; and the proxy's 503 when no
# replica answers. The kit's token lives only in this process's environment and is revoked at the end. The stack is
# left stopped but in place for its logs; remove it with
#   docker compose -f deploy/vm/docker-compose.yml -f deploy/vm/sandbox.override.yml --env-file deploy/vm/sandbox.env \
#     --profile monitoring down -v
set -euo pipefail
# Git Bash would rewrite container paths such as /proc/1/status.
export MSYS_NO_PATHCONV=1

# pwd -W gives Git Bash's Windows form (C:/...), which docker cp needs there; elsewhere it fails and pwd answers.
repository=$(cd "$(dirname "$0")/.." && { pwd -W 2> /dev/null || pwd; })
cd "$repository"
out=${1:-"${RUNNER_TEMP:-$repository/.scratch}/vm-stack-check"}
mkdir -p "$out"
export PROMETHEUS_PORT=${PROMETHEUS_PORT:-19092}
compose=(docker compose -f deploy/vm/docker-compose.yml -f deploy/vm/sandbox.override.yml --env-file deploy/vm/sandbox.env
  --profile monitoring)
public=https://127.0.0.1:18091
private=http://127.0.0.1:18090
failures=0

pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; failures=$((failures + 1)); }
expect() { # <label> <expected> <actual>
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1: expected $2, got $3"; fi
}
container() { "${compose[@]}" ps -q "$1"; }

echo "== Building and starting the stack"
"${compose[@]}" up -d --build --wait --wait-timeout 300
"${compose[@]}" ps --format '{{.Service}} {{.Image}} {{.Status}}'

echo "== Users and capabilities (deploy/vm/docker-compose.yml, x-hardened)"
for service in scheduler-booking scheduler-daily prometheus scheduler-proxy; do
  id=$(container "$service")
  # The service's own process (PID 1), not the user an exec would run as.
  uid=$(docker exec "$id" awk '/^Uid/ {print $2}' /proc/1/status)
  capabilities=$(docker exec "$id" awk '/^CapEff/ {print $2}' /proc/1/status)
  no_new_privileges=$(docker exec "$id" awk '/^NoNewPrivs/ {print $2}' /proc/1/status)
  case $service in
    prometheus) expect "$service runs as nobody" 65534 "$uid"; expect "$service has no capabilities" 0000000000000000 "$capabilities" ;;
    # nginx's master starts as root and keeps CHOWN, DAC_OVERRIDE, SETGID and SETUID to run its workers as nginx.
    scheduler-proxy) expect "$service master runs as root" 0 "$uid"; expect "$service keeps four capabilities" 00000000000000c3 "$capabilities" ;;
    *) expect "$service runs as uid 10001" 10001 "$uid"; expect "$service has no capabilities" 0000000000000000 "$capabilities" ;;
  esac
  expect "$service cannot gain privileges" 1 "$no_new_privileges"
done

echo "== Prometheus"
if docker exec "$(container prometheus)" promtool check config /etc/prometheus/prometheus.yml > /dev/null; then pass "promtool accepts prometheus.yml"
else fail "promtool rejects prometheus.yml"; fi
targets=0
for _attempt in $(seq 1 30); do
  targets=$(curl --silent "http://127.0.0.1:$PROMETHEUS_PORT/api/v1/targets" |
    python3 -c 'import json, sys; print(sum(t["health"] == "up" for t in json.load(sys.stdin)["data"]["activeTargets"]))' 2> /dev/null || echo 0)
  [ "$targets" -ge 2 ] && break
  sleep 2
done
expect "Prometheus scrapes both pools" 2 "$targets"

echo "== TLS on the public listener"
handshake() { openssl s_client -connect 127.0.0.1:18091 "$@" < /dev/null 2>&1 || true; }
handshake -tls1_2 | grep -q "New, TLSv1.2" && pass "TLS 1.2 accepted" || fail "TLS 1.2 refused"
handshake -tls1_3 | grep -q "New, TLSv1.3" && pass "TLS 1.3 accepted" || fail "TLS 1.3 refused"
# The client's own security level would refuse TLS 1.1 before asking; at level 0 the server's refusal is what answers.
handshake -tls1_1 -cipher 'DEFAULT@SECLEVEL=0' | grep -q "alert protocol version" && pass "TLS 1.1 refused by the server" || fail "TLS 1.1 not refused by the server"
handshake -alpn h2,http/1.1 | grep -q "ALPN protocol: h2" && pass "HTTP/2 negotiated" || fail "HTTP/2 not negotiated"

echo "== HTTP answers"
ca="$out/sandbox-ca.pem"
"${compose[@]}" cp scheduler-proxy:/etc/nginx/tls/fullchain.pem "$ca" > /dev/null
status() { curl --silent --output "$out/body" --write-out '%{http_code}' --cacert "$ca" "$@"; }
expect "public /health/live" 200 "$(status "$public/health/live")"
expect "public /health is hidden" 404 "$(status "$public/health")"
grep -q '"error":"NOT_FOUND"' "$out/body" && pass "the 404 is a Problem" || fail "the 404 is not a Problem"
expect "no token" 401 "$(status "$public/api/v1/whoami")"
[ ! -s "$out/body" ] && pass "the 401 has no body" || fail "the 401 has a body"
expect "plain HTTP on the TLS port" 400 "$(curl --silent --output "$out/body" --write-out '%{http_code}' http://127.0.0.1:18091/api/v1/whoami)"
grep -q 'HTTPS only' "$out/body" && pass "the plain-HTTP 400 is a Problem" || fail "the plain-HTTP 400 is not a Problem"
# A body past the proxy's limit, with its length declared as host clients send it, over both protocols: nginx reaches
# the limit by a different path on each, and over HTTP/2 its error page once fell back to HTML.
head -c 17000000 /dev/zero > "$out/oversized"
protocols=(--http1.1)
if curl --version | grep -qw HTTP2; then protocols+=(--http2); else echo "SKIP the 413 over HTTP/2 (this curl has no HTTP/2)"; fi
for protocol in "${protocols[@]}"; do
  expect "a body over 16 MiB ($protocol)" 413 "$(status "$protocol" -H 'Authorization: Bearer x' -H 'Content-Type: application/json' --data-binary @"$out/oversized" "$public/api/v1/routes/evaluate")"
  grep -q '"error":"INVALID_REQUEST"' "$out/body" && pass "the 413 is a Problem ($protocol)" || fail "the 413 is not a Problem ($protocol)"
done
rm -f "$out/oversized"
expect "private /health (booking pool)" 200 "$(curl --silent --output "$out/body" --write-out '%{http_code}' "$private/health")"
grep -q '"role":"booking"' "$out/body" && pass "private /health reaches a booking replica" || fail "private /health did not reach a booking replica"
expect "private /health/daily" 200 "$(curl --silent --output "$out/body" --write-out '%{http_code}' "$private/health/daily")"
grep -q '"role":"daily"' "$out/body" && pass "private /health/daily reaches a daily replica" || fail "private /health/daily did not reach a daily replica"

echo "== Conformance kit over HTTPS"
issued=$("${compose[@]}" run --rm -T scheduler-admin --command=issue-token conformance "Conformance kit" "vm stack check")
token_id=$(sed -n 's/^id: //p' <<< "$issued")
export SCHEDULER_STUDY_TOKEN
SCHEDULER_STUDY_TOKEN=$(sed -n 's/^token: //p' <<< "$issued")
unset issued
if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::add-mask::$SCHEDULER_STUDY_TOKEN"; fi
(cd infra/host-conformance && npm ci --no-audit --no-fund --silent)
if (cd infra/host-conformance && NODE_EXTRA_CA_CERTS="$ca" npx tsx conformance.ts --url "$public" --token-env SCHEDULER_STUDY_TOKEN \
    --metro sandbox --time-zone America/Chicago --points fixture --out "$out/kit"); then pass "conformance kit"
else fail "conformance kit (report in $out/kit)"; fi
unset SCHEDULER_STUDY_TOKEN
"${compose[@]}" run --rm -T scheduler-admin --command=revoke-token "$token_id" > /dev/null && pass "kit token revoked" || fail "kit token $token_id not revoked"

echo "== A hung booking replica"
# Docker marks a hung replica unhealthy but its DNS keeps listing it; nginx's failure count is what takes it out of
# rotation (deploy/vm/nginx.conf). One call waits out the booking read timeout and answers the retryable 503; the
# next calls reach the healthy replica at once.
"${compose[@]}" up -d --wait --wait-timeout 300 --scale scheduler-booking=2 --no-recreate scheduler-booking > /dev/null 2>&1
# nginx looks the pool up again every 10 s (resolver valid=10s); wait until the new replica is in the group.
sleep 12
hung_replica=$("${compose[@]}" ps -q scheduler-booking | tail -n 1)
docker pause "$hung_replica" > /dev/null
held=""
for _attempt in 1 2 3 4; do
  answer=$(curl --silent --output "$out/body" --write-out '%{http_code}' --max-time 75 "$private/api/v1/whoami")
  if [ "$answer" = 503 ]; then held=503; break; fi
done
expect "a call held by the hung replica answers 503" 503 "$held"
grep -q '"retryable":true' "$out/body" && pass "that 503 is a retryable Problem" || fail "that 503 is not a retryable Problem"
fast=0
for _attempt in $(seq 1 10); do
  [ "$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 5 "$private/api/v1/whoami")" = 401 ] && fast=$((fast + 1))
done
expect "the next calls skip the hung replica" 10 "$fast"
docker unpause "$hung_replica" > /dev/null

echo "== No replica answering"
"${compose[@]}" stop scheduler-booking scheduler-daily > /dev/null 2>&1
expect "public API with both pools down" 503 "$(status -H 'Authorization: Bearer x' "$public/api/v1/whoami")"
grep -q '"retryable":true' "$out/body" && pass "the 503 is a retryable Problem" || fail "the 503 is not a retryable Problem"
"${compose[@]}" stop > /dev/null 2>&1

rm -f "$out/body"
if [ "$failures" -gt 0 ]; then echo "$failures check(s) failed"; exit 1; fi
echo "Every check passed"
