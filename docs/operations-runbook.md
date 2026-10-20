# Scheduler operations runbook

The scheduler runs on its own VM and is reached only through `/api/v1`. On that VM it runs beside one routing service per served metro, its Postgres database (thin store, tenants, route cache), Nominatim when address lookup is on, and optionally a remote solver pool. This runbook covers running that set. The API contract is `docs/api/openapi-v1.yaml`, the host's side is `docs/integration-guide.md`, and the design is `docs/stateless-api-design.md`.

## Before production traffic: TLS

TLS is not set up yet (deferred by owner decision on 2026-10-13). It is a precondition of production traffic. These connections must be encrypted first:

- WaterFlex Software to the scheduler API (port 8000). Terminate TLS at a reverse proxy in front of 8000, or in the JVM with Spring Boot's `server.ssl.*` settings.
- The scheduler to each routing service. Today this is plain http with a bearer token (`ROUTING_AUTH_TOKEN`).
- The scheduler to Nominatim, when `GEOCODING_MODE=NOMINATIM`.
- The scheduler to a remote solver. This is already enforced: a `SCHEDULER_CALCULATION_URL` must be https unless it names loopback or the compose service `solver-service` on the VM's private network.

Until then, keep every port on the private network, as Compose does by binding to `127.0.0.1`.

## Settings

| Setting | Required | Meaning |
| --- | --- | --- |
| `SCHEDULER_RETENTION_DAYS` | Yes, no default | Days the thin store keeps requests, proposals, offers and receipts (1 to 3650). The scheduler refuses to start without it. |
| `JDBC_DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | Yes | The scheduler's database login. It must be a member of `scheduler_app`, never the schema owner. The operator commands use the same variables with the owner's login. |
| `ROUTING_METRO_URLS` | Yes | One `metro=url` entry per served metro. A busy metro may list several replicas of its routing service separated by `\|` (`omaha=http://routing-omaha-1:8001\|http://routing-omaha-2:8001`); see "Scaling, in order". |
| `ROUTING_AUTH_TOKEN` | Yes | Shared bearer token of the routing services, at least 32 characters. |
| `GEOCODING_MODE`, `NOMINATIM_URL` | No | `COORDINATES_REQUIRED` (default) or `NOMINATIM`. |
| `SCHEDULER_CALCULATION_MODE`, `SCHEDULER_CALCULATION_URL`, `SCHEDULER_CALCULATION_AUTH_TOKEN` | No | `EMBEDDED` (default) or `REMOTE` with a solver pool. Measured for a daily pool, not for booking (docs/api-load.md, "Embedded against remote calculation"). The pool's `SOLVER_CAPACITY` is the replicas' search capacities summed; its `SOLVER_BACKGROUND_LIMIT` (0: every slot) must cover the daily replicas' background limits, or their second concurrent calculation answers 503. |
| `SCHEDULER_ROLE` | No | `booking`, `daily` or `all` (default). Every replica answers every route; a `booking` replica takes no queued daily work. Any other value stops the replica at startup. See "Replica roles". |
| `SCHEDULER_SEARCH_CAPACITY` | No | Concurrent searches per replica. 0 means 2, or 1 on a one-processor machine. |
| `SCHEDULER_DB_POOL_SIZE` | No | Database connections per replica, default 10. It must exceed the search capacity plus the confirm slots, with headroom for refinements and route fetches (a search holds a connection while its routes and refinement take more); the replica refuses to start otherwise. Capacity plus 6 is a safe size. |
| `SCHEDULER_BOOKING_PUBLISH_RESERVE_MS` | No | The part of a client's `bookingDeadlineMs` a booking search keeps back from exploration to validate, prepare and publish its offers, 800 to 1000, default 1000. The request log's `commitMs` says how much of it each search used. |
| `SCHEDULER_BOOKING_REFINEMENT_MS` | No | The operator's cap on an optional booking refinement, 0 to 3000, default 2000 (250 before 2026-10-20); a client's `policy.bookingRefinementMs` replaces it. |
| `SCHEDULER_SEARCH_CONFIRM_SLOTS` | No | Slots beyond the search capacity that only confirms may take, 0 to the capacity, default 1: a confirm (a few milliseconds of work) then never waits for a running search to finish. Confirms also start before any waiting search. |
| `SCHEDULER_SEARCH_BACKGROUND_LIMIT` | No | Daily and repair calculations this replica runs at once, 1 (default) to the search capacity. Raise it only on daily replicas. |
| `SCHEDULER_DAILY_QUEUE_WORKERS` | No | Workers taking queued daily and repair proposals on this replica, default 1; set it to the background limit. 0 leaves the queue to other replicas, as does `SCHEDULER_ROLE=booking`. |
| `SCHEDULER_SHUTDOWN_GRACE` | No | How long each shutdown phase may take, default `30s`: the daily queue's drain, then the web server's drain of calls in progress. A container's stop grace must exceed twice it (the compose files set 75 s). The solver pool reads `SOLVER_SHUTDOWN_GRACE` the same way. |
| `SCHEDULER_DAILY_QUEUE_DRAIN_MS` | No | How long a stopping replica lets its running queued jobs finish, default 25000 (0 to 600000), within the grace above. A job still running then is stopped and queued again at once for another replica, with its attempt not counted. |
| `SCHEDULER_CANCEL_POLL_MS` | No | How often a replica reads the cancellations other replicas recorded for the calls it runs, default 500. |
| `ROUTING_IDENTITY_TTL_MS` | No | How long a routing identity fetched from a routing service's `/health` is trusted, default 2000; the service still refuses a call with a changed identity (409), which drops the remembered one. |
| `ROUTING_FLIGHTS_THREADS`, `ROUTING_FLIGHTS_QUEUE` | No | Leg batches a replica fetches from a routing service at once (8) and lets wait (64). |
| `ROUTING_CH_ENABLED`, `ROUTING_PARALLELISM` (routing service) | No | Serve the map version's contraction hierarchy graph (default true; false serves the flexible graph, with another routing identity) and route a request's pairs on this many threads (0, the default, means the processors available). |
| `SCHEDULER_DAILY_QUEUE_POLL_MS`, `SCHEDULER_DAILY_QUEUE_MAX_WAIT_MINUTES`, `SCHEDULER_DAILY_QUEUE_TENANT_LIMIT` | No | How often an idle worker looks for work queued on another replica (500 ms); how long a queued request may wait before it completes with 429 without running (15 minutes); how many requests one client may have queued or running (50). |
| `MANAGEMENT_PORT` | No | Port of `/actuator/health` and `/actuator/prometheus`, default 9000. Keep it private. |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | No | `ecs` for JSON log lines. |
| `TIMEFOLD_LOG_LEVEL` | No | Log level of the Timefold solver's own lines, default `WARN`, on the scheduler and the solver pool. Its per-solve INFO lines repeat the daily receipt line, which carries the tenant and `requestId`; set `INFO` or `DEBUG` to diagnose one solve. |

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

Stop the scheduler first. A stopping replica stops taking queued jobs, lets running ones finish for `SCHEDULER_DAILY_QUEUE_DRAIN_MS`, and queues any still running again at once for another replica. The web server then drains the calls in progress (booking searches end within their deadline) for up to `SCHEDULER_SHUTDOWN_GRACE`. A call cut off by a forced kill is lost and the host retries it with the same `requestId`; an unfinished claim is taken over when its lease expires, and a running job that was not handed back is taken again by another replica's worker once its lease passes, at most three times. The solver pool tells any solve still running after its own drain to stop.

Upgrade:

1. Run `--command=migrate` with the new jar. On the VM, `docker compose up -d` does this through `scheduler-admin` before the scheduler starts.
2. With a remote solver, start the new solver pool beside the old one. The scheduler and solver must run the same `CalculationProtocol.VERSION` and the same Timefold artifact, or remote calls answer 503.
3. Replace the scheduler and point it at the new pool.
4. Wait for `/health` to answer 200, then stop the old pool.

Rollback is the reverse: start the previous scheduler with the previous pool. Migrations are forward only. A migration that removes something the previous version reads is called out in the design doc's "Contract changes" table and its pull request.

Rolling back past #152 (late snapshots keep holds) needs one more step. From #152 on, the stored booking day state carries `superseded` and `unwritten`, which an earlier build refuses to read, so its booking calls would answer 500 for every day written since. Stop the scheduler, delete the booking day rows (`DELETE FROM api_booking_day;` as the owner role; holds live 10 minutes, so this releases at most the holds of the last 10 minutes, and confirmed bookings live in the host's records), then start the previous build.

## Health

`GET /health` on the API port answers 200 `ok` when every component answers within one second, and 503 `degraded` otherwise. The body names the replica's `role` and each component with `up` or `down` and a short detail; it never contains URLs or credentials.

| Component | Checked | Down means |
| --- | --- | --- |
| `database` | `SELECT 1` | No calculation can claim its request; calls answer 503. |
| `schema` | The database's migration history matches this build | The database was not migrated to this version; run `--command=migrate`. |
| `routing:<metro>` | The routing service's `/health`; the detail is its routing identity, and with several replicas how many are in rotation (`<identity> (2 of 3 replicas)`) | Calls for that metro fail. Other metros are unaffected. With several replicas it is down only when none answers. |
| `calculation` | The solver pool's `/health`, only in `REMOTE` mode. Its body reports `capacity`, `active`, `queued`, `transient` (solves registered) and `stuck`; it answers 503 when a solve is still registered longer than the longest allowance plus a minute | Calculations answer 503. A `stuck` solve means a pool worker never finished: restart that pool replica. |
| `geocoding` | Nominatim's `/status`, only in `NOMINATIM` mode | Address-only locations are reported as skipped. |

`GET /health/live` reads nothing and always answers 200. Use it for restarts, and `/health` for taking a replica out of a load balancer. Restarting on `/health` would restart the scheduler when routing is down, which fixes nothing.

## Logs

Each `/api/v1` call writes one line to the logger `dev.waterflex.scheduler.api.requests`:

```
INFO [tenant=acme request=2b7892ab-02be-43d3-a8e9-5086354eb54a] ... api operation="POST /api/v1/booking/offers" status=201 elapsedMs=374 queueMs=12 routingMs=212 calculationMs=97 dbMs=18 dbCalls=9 routingPairs=310 cacheHits=280 fetched=30 cpuMs=141
```

A call that ran under a deadline (booking offers and confirms, route evaluation and geometry, and the routing of a synchronous daily proposal) also says how its time was spent: `queueMs` waiting for a search slot, `routingMs` waiting on routing (identity checks, leg fetches and the route cache reads around them), `calculationMs` in the calculation engine, `dbMs` and `dbCalls` in the thin store, `routingPairs` the legs the call needed with `cacheHits` served from memory or the route cache and `fetched` from the routing service, and `cpuMs` the request thread's own CPU time. A slow search with high `routingMs` and `fetched` is cold routing (new addresses, or a cache gone cold after a map change); one with high `calculationMs` is the engine; one with high `queueMs` waited for a slot behind other calls (add capacity or replicas).

Every line written while serving the call, including the solver's worker thread, carries the same tenant and the host's `requestId`. Search by `requestId` to follow one call. With `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` the same fields are JSON properties.

Lines to act on:

- `Retention purge failed`: the purge did not run; it tries again the next day. Check the database.
- `Readiness check ... failed`: a dependency is down; the exception names it.
- `Refinement result for job ... failed validation`: the search published its insertion offers instead. Report it with the `requestId`.

## Metrics

`http://<replica>:9000/actuator/prometheus` on each replica. On the VM the replicas publish no ports: Prometheus (`--profile monitoring`) finds every replica of both pools by name, and `scheduler_replica_info{role}` (always 1) labels each one's role, so every meter below can be split by pool.

| Metric | Watch for |
| --- | --- |
| `http_server_requests_seconds` by `uri` and `status` | Latency per operation; booking offers should stay within the client's `bookingDeadlineMs` plus network time. |
| `scheduler_admission_rejections_total` | Rising: replicas are saturated and hosts see 429 (including daily proposals refused because the calculations ahead would not finish in time, and requests whose deadline passed in the queue). Add capacity. |
| `scheduler_admission_active`, `scheduler_admission_queued` | Sustained queue: searches wait for a slot. |
| `scheduler_routing_leg_requests_total`, `scheduler_routing_leg_pairs_total` by `metro` | Routing load per metro; a jump after a map change is the cold cache filling. |
| `scheduler_booking_refinement_invalid_total` | Should stay 0. |
| `scheduler_booking_refinement_shed_total` | Optional refinements skipped because booking requests were queued. A steady rise means booking desks often wait for a slot: add booking capacity. |
| `scheduler_requests_cancelled_total` | Host cancellations. |
| `scheduler_admission_evictions_total` | Queued searches that gave their place in a full queue to a search of a client with fewer waiting, or to an arriving confirm (confirms start before waiting searches and are never evicted). Rising: one client's desks fill the booking queue; add booking capacity. |
| `scheduler_daily_queue_started_total`, `scheduler_daily_queue_expired_total`, `scheduler_daily_queue_failed_total` | Queued proposals this replica started; ones that waited longer than `MAX_WAIT_MINUTES` (hosts got 429: add daily capacity); ones that failed unexpectedly and were queued again (read the logs). The queue's depth is `SELECT state, count(*) FROM api_daily_job WHERE state <> 'DONE' GROUP BY state` as the owner role. |
| `scheduler_retention_deleted_total` | Grows once a day after the first period has passed. |
| `scheduler_request_phase_seconds` by `operation` and `phase` (`queue`, `routing`, `calculation`, `db`, `commit`) | The request log's phase times as histograms: graph p95 per phase to see whether a slow booking search waited for a slot, for routing, for the engine or for the database. |
| `scheduler_replica_info{role}`, `scheduler_admission_background_limit` | The replica's role and how many daily calculations it runs at once. |

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

Each prepared map version carries two road graphs, `graph` (flexible) and `graph-ch-car-v1` (contraction hierarchy, which answers a query in a fraction of the time and CPU); `infra/prepare-map.ps1` prepares both, the manifest lists both, and activation requires both. The routing service serves the CH graph by default (`ROUTING_CH_ENABLED=true`). Switching between the two changes the routing identity exactly as a map change does: every route cache goes cold and a call in flight on the old identity is refused and retried by the host.

A map refresh prepares a new version beside the active one and switches to it (README, "Normal local updates and restarts"). Pause booking during the switch. The routing identity in `/health` changes with the map. The scheduler's route cache is keyed by identity, so it starts cold and routing load is higher until it fills.

The identity also names the GraphHopper release the routing service runs (`engineVersion` in `/health`), so upgrading GraphHopper has the same effect as a map change: deploy it like a map switch, with booking paused, and expect a cold cache. A graph prepared by an earlier release is reused when its storage versions match, which GraphHopper checks at load and logs (`version 11.1|... (9,24,7,5,2,9)`); 11.0 and 11.1 match, and on the 2026-10-13 Omaha map both returned identical legs for 400 directed pairs. A release whose storage versions differ refuses the old graph, and the map version must be prepared again with the new image.

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
2. More scheduler replicas on the same database behind a load balancer. Any replica answers any call: queued proposals (`Prefer: respond-async`) are taken by any replica's worker, and `DELETE /api/v1/requests/{requestId}` cancels a call wherever it runs (the replica that receives it records the cancel; the replica running the call stops it within about a second).
3. Replica roles: a booking pool and a daily pool of the same jar behind one address (see "Replica roles"), each scaled on its own.
4. `REMOTE` calculation with a solver pool, so solving scales apart from the API.
5. More routing capacity for a busy metro: run more replicas of its routing service on the same map version and list them in `ROUTING_METRO_URLS` separated by `|`. The scheduler spreads routing calls over them and shares one route cache for the metro. A replica that cannot be reached, answers 5xx or reports another routing identity is left out for 5 seconds at a time while the others answer, so a map switch can be rolled one replica at a time. `routing:<metro>` stays up while any replica answers, and its detail says how many are in rotation.

## Availability

The VM layout (`deploy/vm/docker-compose.yml`) fails over only where it runs more than one of a thing. The rest is
single, and the scheduler answers the host in a way that lets it retry safely while that part is restored.

| Part | How many | When it stops | What the host sees | Recovery |
| --- | --- | --- | --- | --- |
| `scheduler-proxy` (nginx) | One | Every call fails at the connection. | Connection refused or reset. | Restart it; nothing is lost, since calls never reached a replica. |
| A scheduler replica | One or more per pool | The proxy stops sending it calls within 10 seconds. A synchronous call it was serving is lost; a queued day it was running is taken by another replica's worker once its lease passes (at most three attempts). | The call in progress fails or times out; later calls go to the other replicas. | None needed with two or more replicas per pool. With one, start it again. |
| Postgres (`scheduler-db`) | One | Nothing can be claimed, published or committed. Work in a transaction is rolled back, never half written. | 503 with `database` down in `/health`. Until the follow-up in HANDOFF is fixed, the token check before a call may answer a bare 500 instead; treat it as retryable. | Restore the database. Claims resume when their leases expire. |
| Routing service | One per metro, or several listed with `\|` | Calculating calls for that metro cannot route. Other metros are unaffected. With several replicas, the others carry the metro. | 503 with `routing:<metro>` down. | Start it; loading a graph takes minutes. The in-memory route cache refills from the database cache, which is keyed by routing identity and survives. |
| Nominatim (`GEOCODING_MODE=NOMINATIM` only) | One | Address-only locations cannot be located. Calls that send coordinates are unaffected. | 503 for a call with an address-only location, and the address lookup component down in `/health`. | Start it. |
| Solver pool (`SCHEDULER_CALCULATION_MODE=REMOTE` only) | One service, any number of replicas | Calculations on the replicas that use it cannot run. | 503 with `calculation` down. | Start it, or switch that pool back to `EMBEDDED`. |

**The host's retry contract.** After a connection failure, a timeout, a 429 or a 503, send the same call again with the
same `requestId` after `Retry-After` (or a short backoff without one). A finished request replays its stored answer, a
claim still running elsewhere answers 429 `BUSY` (wait and repeat it), and a claim whose replica died is taken over when its lease
expires, so a retry never books or commits twice. Only a 409 `STALE` needs a new snapshot and a new `requestId`.

**CPU sizing.** The compose CPU limits are ceilings, not reservations, so the pools can be given more than the VM has.
The defaults already add up past an 8 vCPU VM: booking 2, daily 2, routing 2, Nominatim 2, Postgres 1 and the proxy 0.5
(9.5), plus 4 for a remote solver pool. The booking layout docs/api-load.md recommends, one replica with as many slots
as vCPU, makes it larger again at 4 vCPU. Nothing fails when the caps exceed the cores, but under simultaneous booking,
daily and routing load the pools share the cores, and each pool's measured latency no longer holds. Before onboarding
a large fleet, either keep the caps of the pools that run together within the vCPU (leaving one for Postgres and the
proxy), or measure the combined load on the VM rehearsal stack.

## Replica roles

`deploy/vm/docker-compose.yml` runs two pools of the same image behind `scheduler-proxy` (nginx, [deploy/vm/nginx.conf](../deploy/vm/nginx.conf)):

| Pool | `SCHEDULER_ROLE` | Receives | Tuned for |
| --- | --- | --- | --- |
| `scheduler-booking` | `booking` | Everything except the routes below: booking searches, holds and confirms, commits, route evaluation and geometry, whoami, `/health` | Latency. No queued daily work runs here, so bookings never wait behind a 20 second solve. |
| `scheduler-daily` | `daily` | `POST /api/v1/daily/proposals`, `POST /api/v1/repairs/proposals`, `GET` and `DELETE /api/v1/requests/{id}` | Throughput. Up to `SCHEDULER_DAILY_BACKGROUND_LIMIT` daily solves at once per replica, with as many queue workers. |

Every replica answers every route and shares the one database, so the split is about load, not correctness: if the proxy sends a call to the other pool, it still gets the right answer. Bookings keep their priority inside each replica as before. One replica with `SCHEDULER_ROLE=all` (the default) does both and is the right shape for a single small machine.

Scale the booking pool when booking p95 nears the client's deadline or booking replicas reject; scale the daily pool, or raise its background limit with cores to match, when queued days wait (from `queuedAt` to `startedAt`) or `scheduler_daily_queue_expired_total` rises. The proxy re-reads each pool's replicas every 10 seconds. To check which replica answered, read `role` in `/health`, or `scheduler_replica_info` in Prometheus.

## Failure modes

| Symptom | Cause | Action |
| --- | --- | --- |
| 503 from calculating calls, `routing:<metro>` down | Routing service stopped or loading its graph | Start it; loading a graph takes minutes. |
| 503, `calculation` down | Solver pool unreachable or a protocol mismatch | Check the pool; match versions (see Upgrade). |
| 503, `database` down | Database unreachable | Restore the database; claims resume when their leases expire. |
| 429 with `Retry-After` | Admission full | The host retries with the same `requestId`; add capacity if it persists. |
| 401 | Token revoked, tenant disabled or wrong token | Issue a token. |
| Startup fails naming `SCHEDULER_RETENTION_DAYS` | The period is unset or malformed | Set it. |
