#!/usr/bin/env bash
# Restore the scheduler VM's database from a dump made by infra/backup-vm.sh, on this VM or a new one. It replaces the
# scheduler database, so it asks for --replace:
#
#   bash infra/restore-vm.sh /var/backups/scheduler/scheduler-<stamp>.dump --replace
#
# It stops both scheduler pools (the proxy answers 503 SERVICE_UNAVAILABLE, which hosts retry), recreates the database
# empty, recreates the server-level roles a dump does not carry, restores the dump in one transaction, runs
# --command=migrate (the serving login, and any migration newer than the dump), and starts the pools. Holds and offers
# made after the dump are lost; a host confirming one gets 409 HOLD_UNAVAILABLE and searches again.
# ENV_FILE and COMPOSE_FILE as for infra/release-vm.sh.
set -euo pipefail

fail() { echo "restore: $*" >&2; exit 1; }

dump=${1:-}
[[ -f $dump ]] || fail "name a dump file (got '$dump')"
[[ ${2:-} == --replace ]] || fail "this replaces the scheduler database with $dump; add --replace to go ahead"
dump=$(cd "$(dirname "$dump")" && pwd)/$(basename "$dump")
cd "$(dirname "$0")/.."
env_file=${ENV_FILE:-deploy/vm/.env}
export COMPOSE_FILE=${COMPOSE_FILE:-deploy/vm/docker-compose.yml}
[[ -f $env_file ]] || fail "no $env_file"
compose=(docker compose --env-file "$env_file")
sql() { "${compose[@]}" exec -T scheduler-db psql -U scheduler_owner -d "$1" -v ON_ERROR_STOP=1 -q; }

"${compose[@]}" up -d --wait scheduler-db
"${compose[@]}" exec -T scheduler-db pg_restore --list < "$dump" > /dev/null || fail "$dump cannot be read"

echo "== Stopping the scheduler pools"
"${compose[@]}" stop scheduler-booking scheduler-daily

echo "== Recreating the scheduler database"
sql postgres <<'SQL'
DROP DATABASE IF EXISTS scheduler WITH (FORCE);
CREATE DATABASE scheduler OWNER scheduler_owner;
SQL
# Roles belong to the server, so a dump carries their table grants but not the roles; on a new server they would not
# exist, and migrate would not create them (V1 has already run, by the restored history). The same attributes and
# memberships as V1__thin_store.sql.
sql scheduler <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'scheduler_tenant') THEN
    CREATE ROLE scheduler_tenant NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT;
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'scheduler_app') THEN
    CREATE ROLE scheduler_app NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
END $$;
GRANT scheduler_tenant TO scheduler_owner;
GRANT scheduler_tenant TO scheduler_app;
SQL

echo "== Restoring $dump"
"${compose[@]}" exec -T scheduler-db pg_restore -U scheduler_owner -d scheduler --no-owner --exit-on-error --single-transaction < "$dump" \
  || fail "the restore failed and was rolled back; the database is empty and the pools are stopped"

echo "== Migrating and starting the pools"
"${compose[@]}" run --rm -T scheduler-admin > /dev/null
"${compose[@]}" up -d --wait
echo "== Restored $dump; tenants: $("${compose[@]}" exec -T scheduler-db psql -U scheduler_owner -d scheduler -tAc 'SELECT count(*) FROM tenant')"
