#!/usr/bin/env bash
# Back up the scheduler VM's database: one pg_dump (custom format) of the scheduler database without the route cache's
# rows, checked readable before it is kept, and dumps older than the retention period removed. Run it daily from cron
# on the VM (deploy/vm/README.md, "Backups"):
#
#   15 3 * * * cd /path/to/waterflex-scheduler-java && bash infra/backup-vm.sh >> /var/log/scheduler-backup.log 2>&1
#
# The dump holds tenants, token digests and the thin store (client snapshots with addresses), so it is written for the
# running user only (umask 077). Copy it off the VM: a dump that lives only on the VM is lost with it.
# BACKUP_DIR (default /var/backups/scheduler) is where dumps go; BACKUP_KEEP_DAYS (default SCHEDULER_RETENTION_DAYS)
# how long they are kept. ENV_FILE and COMPOSE_FILE as for infra/release-vm.sh.
set -euo pipefail
umask 077

fail() { echo "backup: $*" >&2; exit 1; }

cd "$(dirname "$0")/.."
env_file=${ENV_FILE:-deploy/vm/.env}
export COMPOSE_FILE=${COMPOSE_FILE:-deploy/vm/docker-compose.yml}
[[ -f $env_file ]] || fail "no $env_file"
compose=(docker compose --env-file "$env_file")
dir=${BACKUP_DIR:-/var/backups/scheduler}
retention=$(sed -n 's/^SCHEDULER_RETENTION_DAYS=\([0-9][0-9]*\).*/\1/p' "$env_file" | tail -n 1)
keep=${BACKUP_KEEP_DAYS:-$retention}
[[ $keep =~ ^[1-9][0-9]*$ ]] || fail "set BACKUP_KEEP_DAYS or SCHEDULER_RETENTION_DAYS in $env_file (got '$keep')"

mkdir -p "$dir"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
dump="$dir/scheduler-$stamp.dump"
partial="$dir/.scheduler-$stamp.dump.partial"
trap 'rm -f "$partial"' EXIT

# The route cache refills from routing, so only its definition is kept.
"${compose[@]}" exec -T scheduler-db pg_dump -U scheduler_owner -d scheduler -Fc --exclude-table-data=road_route_cache > "$partial" \
  || fail "pg_dump failed; the previous dumps are untouched"
# Readable, and holding the tables a restore needs: a dump that cannot be listed is no backup.
contents=$("${compose[@]}" exec -T scheduler-db pg_restore --list < "$partial") || fail "the new dump cannot be read"
for table in tenant tenant_api_token api_request flyway_schema_history; do
  grep -q "TABLE DATA public $table " <<<"$contents" || fail "the new dump has no rows section for $table"
done
mv "$partial" "$dump"

removed=$(find "$dir" -maxdepth 1 -name 'scheduler-*.dump' ! -newer "$dump" -mtime "+$keep" -print -delete | wc -l)
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup $dump ($(du -h "$dump" | cut -f1)); removed $removed older than $keep days"
