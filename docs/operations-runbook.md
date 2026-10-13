# Scheduler operations runbook

The scheduler runs on its own VM and is reached only through `/api/v1`. On that VM it runs beside one routing service per served metro, its Postgres database (thin store, tenants, route cache), Nominatim when address lookup is on, and optionally a remote solver pool. This runbook covers running that set. The API contract is `docs/api/openapi-v1.yaml`; the design is `docs/stateless-api-design.md`.

## Before production traffic: TLS

TLS is not set up yet (deferred by owner decision on 2026-10-13). It is a precondition of production traffic. These connections must be encrypted first:

- WaterFlex Software to the scheduler API (port 8000). Terminate TLS at a reverse proxy in front of 8000, or in the JVM with Spring Boot's `server.ssl.*` settings.
- The scheduler to each routing service. Today this is plain http with a bearer token (`ROUTING_AUTH_TOKEN`).
- The scheduler to Nominatim, when `GEOCODING_MODE=NOMINATIM`.
- The scheduler to a remote solver. This is already enforced: a non-loopback `SCHEDULER_CALCULATION_URL` must be https.

Until then, keep every port on the private network, as Compose does by binding to `127.0.0.1`.

## Settings

| Setting | Required | Meaning |
| --- | --- | --- |
| `SCHEDULER_RETENTION_DAYS` | Yes, no default | Days the thin store keeps requests, proposals, offers and receipts (1 to 3650). The scheduler refuses to start without it. |
| `JDBC_DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | Yes | The scheduler's database login. It must be a member of `scheduler_app`, never the schema owner. The operator commands use the same variables with the owner's login. |
| `ROUTING_METRO_URLS` | Yes | One `metro=url` entry per served metro. |
| `ROUTING_AUTH_TOKEN` | Yes | Shared bearer token of the routing services, at least 32 characters. |
| `GEOCODING_MODE`, `NOMINATIM_URL` | No | `COORDINATES_REQUIRED` (default) or `NOMINATIM`. |
| `SCHEDULER_CALCULATION_MODE`, `SCHEDULER_CALCULATION_URL`, `SCHEDULER_CALCULATION_AUTH_TOKEN` | No | `EMBEDDED` (default) or `REMOTE` with a solver pool. |
| `SCHEDULER_SEARCH_CAPACITY` | No | Concurrent searches per replica. 0 means 2, or 1 on a one-processor machine. |
| `MANAGEMENT_PORT` | No | Port of `/actuator/health` and `/actuator/prometheus`, default 9000. Keep it private. |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | No | `ecs` for JSON log lines. |

## Install

`deploy/vm/README.md` installs the VM from `deploy/vm/docker-compose.yml`. The rest of this runbook applies to that stack and to any other way of running the same services.

## Database and login

The scheduler owns its database. Its schema comes only from the migrations in the jar, applied by the owner:

```sh
JDBC_DATABASE_URL=<url> DATABASE_USER=<owner> DATABASE_PASSWORD=<owner password> \
SCHEDULER_DB_LOGIN=scheduler SCHEDULER_DB_PASSWORD=<16 to 128 of A-Z a-z 0-9 . _ ~ -> \
java -jar scheduler-service.jar --command=migrate
```

On the VM, the `scheduler-admin` service runs this on every start. The serving scheduler never migrates. It checks the schema with its own login, and the readiness component `schema` stays down until the database matches the build.

The running scheduler logs in as a member of `scheduler_app`, which holds exactly what it needs:

- it can read `tenant` and `tenant_api_token`,
- it can read and write `road_route_cache`,
- it can act as `scheduler_tenant`, whose row-level policies confine each transaction to one tenant's thin store rows,
- it can run the purge function `scheduler_purge`.

`scheduler_app` does not inherit `scheduler_tenant`'s table grants, so the login reads thin store rows only inside a transaction that has switched to that role and set the tenant. It cannot change the schema, write tenants or change tokens.

`--command=migrate` creates the login after the migrations, or sets its password when it exists. It refuses the migrating user and any role with superuser, bypass-RLS, create-role or create-database rights. Rotating the password is: run it with the new password, then restart the scheduler with it.

## Start, stop and upgrade

Start order: database, `--command=migrate` (migrations and the login), routing services, Nominatim (if used), the solver pool (if remote), then the scheduler. The scheduler starts without its dependencies, but `/health` answers 503 until they answer.

Stop the scheduler first. A call in progress is lost and the host retries it with the same `requestId`; an unfinished claim is taken over when its lease expires.

Upgrade:

1. Run `--command=migrate` with the new jar. On the VM, `docker compose up -d` does this through `scheduler-admin` before the scheduler starts.
2. With a remote solver, start the new solver pool beside the old one. The scheduler and solver must run the same `CalculationProtocol.VERSION` and the same Timefold artifact, or remote calls answer 503.
3. Replace the scheduler and point it at the new pool.
4. Wait for `/health` to answer 200, then stop the old pool.

Rollback is the reverse: start the previous scheduler with the previous pool. Migrations are forward only. A migration that removes something the previous version reads is called out in the design doc's "Contract changes" table and its pull request.

## Health

`GET /health` on the API port answers 200 `ok` when every component answers within one second, and 503 `degraded` otherwise. The body names each component with `up` or `down` and a short detail; it never contains URLs or credentials.

| Component | Checked | Down means |
| --- | --- | --- |
| `database` | `SELECT 1` | No calculation can claim its request; calls answer 503. |
| `schema` | The database's migration history matches this build | The database was not migrated to this version; run `--command=migrate`. |
| `routing:<metro>` | The routing service's `/health`; the detail is its routing identity | Calls for that metro fail. Other metros are unaffected. |
| `calculation` | The solver pool's `/health`, only in `REMOTE` mode | Calculations answer 503. |
| `geocoding` | Nominatim's `/status`, only in `NOMINATIM` mode | Address-only locations are reported as skipped. |

`GET /health/live` reads nothing and always answers 200. Use it for restarts, and `/health` for taking a replica out of a load balancer. Restarting on `/health` would restart the scheduler when routing is down, which fixes nothing.

## Logs

Each `/api/v1` call writes one line to the logger `dev.waterflex.scheduler.api.requests`:

```
INFO [tenant=acme request=2b7892ab-02be-43d3-a8e9-5086354eb54a] ... api operation="POST /api/v1/booking/offers" status=201 elapsedMs=374
```

Every line written while serving the call, including the solver's worker thread, carries the same tenant and the host's `requestId`. Search by `requestId` to follow one call. With `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` the same fields are JSON properties.

Lines to act on:

- `Retention purge failed`: the purge did not run; it tries again the next day. Check the database.
- `Readiness check ... failed`: a dependency is down; the exception names it.
- `Refinement result for job ... failed validation`: the search published its insertion offers instead. Report it with the `requestId`.

## Metrics

`http://<private address>:9000/actuator/prometheus`:

| Metric | Watch for |
| --- | --- |
| `http_server_requests_seconds` by `uri` and `status` | Latency per operation; booking offers should stay within the client's `bookingDeadlineMs` plus network time. |
| `scheduler_admission_rejections_total` | Rising: replicas are saturated and hosts see 429. Add capacity. |
| `scheduler_admission_active`, `scheduler_admission_queued` | Sustained queue: searches wait for a slot. |
| `scheduler_routing_leg_requests_total`, `scheduler_routing_leg_pairs_total` by `metro` | Routing load per metro; a jump after a map change is the cold cache filling. |
| `scheduler_booking_refinement_invalid_total` | Should stay 0. |
| `scheduler_requests_cancelled_total` | Host cancellations. |
| `scheduler_retention_deleted_total` | Grows once a day after the first period has passed. |

## Retention

A purge runs daily at 03:45 America/Chicago. It deletes rows older than `SCHEDULER_RETENTION_DAYS` in batches of 5,000 per table until none remain. A row still referenced by a newer row stays until both have aged. Every replica may run it; rows another replica has locked are skipped. To run it by hand as the scheduler's login:

```sql
SELECT scheduler_purge(now() - make_interval(days => <days>), 5000);  -- repeat until it returns 0
```

The function refuses a cutoff less than one day old. A request purged this way is forgotten: a host repeating its `requestId` gets a new calculation.

## Tokens

Each client (tenant) has its own bearer tokens, issued by the scheduler's command line as the database owner. On the VM:

```sh
docker compose -f deploy/vm/docker-compose.yml --env-file deploy/vm/.env run --rm scheduler-admin --command=issue-token <tenantId> "<tenant name>" "<token label>"
#   --command=revoke-token <tokenId> | list-tenants | disable-tenant <tenantId> | enable-tenant <tenantId>
```

The token is printed once and stored only as a digest. A disabled tenant's tokens answer 401 until it is enabled again. To rotate: issue a new token, give it to the host, wait until the host uses it, then revoke the old one. A revoked token answers 401 at once.

## Maps and routing identity

A map refresh prepares a new version beside the active one and switches to it (README, "Normal local updates and restarts"). Pause booking during the switch. The routing identity in `/health` changes with the map. The scheduler's route cache is keyed by identity, so it starts cold and routing load is higher until it fills.

## Backup and restore

The thin store holds only what WaterFlex Software can recompute or does not need for its business record: holds, offers, proposals and receipts. The host owns the business record. Back up the scheduler database daily and keep the dumps at least as long as the retention period. On the VM:

```sh
docker compose -f deploy/vm/docker-compose.yml --env-file deploy/vm/.env exec -T scheduler-db \
  pg_dump -U scheduler_owner -d scheduler -Fc --exclude-table-data=road_route_cache > scheduler-$(date +%F).dump
```

The route cache is left out; it refills from routing. Roles belong to the server, not the dump, and `--command=migrate` recreates them.

To restore, stop `scheduler-service`, restore the dump into an empty `scheduler` database as the owner (`pg_restore -U scheduler_owner -d scheduler --no-owner`), run `--command=migrate` (it recreates the roles and the login, and applies nothing else when the dump is current), and start the scheduler. Holds and offers older than the dump are lost. Hosts searching again get new offers, and a confirm of a lost hold answers 409 `HOLD_UNAVAILABLE`, which the host treats as "search again".

## Scaling, in order

1. `SCHEDULER_SEARCH_CAPACITY` to match the cores given to the scheduler.
2. More scheduler replicas on the same database behind a load balancer. Send `DELETE /api/v1/requests/{requestId}` to the replica running the call by hashing on the request ID.
3. `REMOTE` calculation with a solver pool, so solving scales apart from the API.
4. More routing capacity for a busy metro.

## Failure modes

| Symptom | Cause | Action |
| --- | --- | --- |
| 503 from calculating calls, `routing:<metro>` down | Routing service stopped or loading its graph | Start it; loading a graph takes minutes. |
| 503, `calculation` down | Solver pool unreachable or a protocol mismatch | Check the pool; match versions (see Upgrade). |
| 503, `database` down | Database unreachable | Restore the database; claims resume when their leases expire. |
| 429 with `Retry-After` | Admission full | The host retries with the same `requestId`; add capacity if it persists. |
| 401 | Token revoked, tenant disabled or wrong token | Issue a token. |
| Startup fails naming `SCHEDULER_RETENTION_DAYS` | The period is unset or malformed | Set it. |
