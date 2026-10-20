# Scheduler VM

The scheduler runs on its own VM with a fixed share of compute and is reached only through `/api/v1`. WaterFlex Software sends snapshots and gets back time windows (booking offers) or whole solved routes (daily and repair proposals, route evaluations). Nothing else connects: no portal, no shared database, no client library.

`docker-compose.yml` here is the whole VM:

| Service | Role |
| --- | --- |
| `scheduler-db` | The scheduler's own Postgres: tenants and token digests, the thin store, the route cache. |
| `scheduler-admin` | One-shot, as the database owner: applies migrations and creates the serving login on every start. Operators run tenant commands through it. |
| `scheduler-proxy` | nginx: the one API address (`/api/v1`, `/health`) on the private interface. It sends daily and repair proposals and `/api/v1/requests/*` to the daily pool and everything else to the booking pool ([nginx.conf](nginx.conf)). |
| `scheduler-booking` | Booking replicas (`SCHEDULER_ROLE=booking`): booking searches, confirms, commits and route calls. They take no queued daily work. |
| `scheduler-daily` | Daily replicas (`SCHEDULER_ROLE=daily`): daily and repair proposals, synchronous and queued, up to `SCHEDULER_DAILY_BACKGROUND_LIMIT` at once each. |
| `routing-omaha` | Omaha's routing service. Add one per served metro. |
| `nominatim` | Profile `nominatim`: address lookup for locations sent without coordinates (`GEOCODING_MODE=NOMINATIM`). |
| `solver-service` | Profile `remote-solver`: a remote solver pool (`SCHEDULER_CALCULATION_MODE=REMOTE`). The embedded solver is the default. |
| `prometheus` | Profile `monitoring`: scrapes every scheduler replica's metrics. |

Each service has `deploy.resources` limits set from `.env`, so the VM's partition is explicit; a pool's limits apply to each of its replicas. Both pools run the same image and every replica answers every route, so a call that reaches the other pool still gets the right answer. Running it is covered by [the operations runbook](../../docs/operations-runbook.md).

## Before production traffic

TLS is not set up (deferred by owner decision). Terminate TLS in front of the API port before WaterFlex Software sends real traffic; the runbook lists every connection that must be encrypted. Until then, bind the API only to the private interface (`SCHEDULER_API_BIND`).

## Install

1. Install Docker Engine with the Compose plugin, and clone this repository on the VM.
2. Copy `deploy/vm/.env.example` to `deploy/vm/.env` and fill in every empty value. Generate secrets with `openssl rand -base64 48 | tr -d '/+=' | cut -c1-48`. `SCHEDULER_RETENTION_DAYS` is your audit decision; there is no default.
3. Put the maps on the VM. Prepare a version on a build machine as the main README describes (`./infra/prepare-map.ps1 -Version <version>`, which builds both road graphs, `graph` and `graph-ch-car-v1`; then validate it), and copy that version's directory into the map volume:

   ```sh
   docker volume create scheduler-vm-maps
   docker run --rm -v scheduler-vm-maps:/maps -v /path/to/prepared/versions:/src:ro alpine \
     sh -c 'mkdir -p /maps/versions && cp -a /src/<version> /maps/versions/'
   ```

   Set `ACTIVE_MAP_VERSION=<version>` in `.env`.
4. Start the VM:

   ```sh
   docker compose -f deploy/vm/docker-compose.yml --env-file deploy/vm/.env up -d
   ```

   `scheduler-admin` migrates the database and exits; both pools start after it, and `scheduler-proxy` in front of them. Loading the road graph takes minutes, and `/health` answers 503 until routing is up.
5. Check readiness from the VM: `curl http://<SCHEDULER_API_BIND>:8000/health` answers 200 with `"role":"booking"` and `database`, `schema` and `routing:metro-omaha` up (the proxy sends `/health` to a booking replica). `docker compose ... ps` shows each replica healthy.
6. Issue a token for each WaterFlex Software client and hand it over through a secret channel:

   ```sh
   docker compose -f deploy/vm/docker-compose.yml --env-file deploy/vm/.env run --rm scheduler-admin \
     --command=issue-token <tenantId> "<client name>" primary
   ```

   `curl -H "Authorization: Bearer <token>" http://<SCHEDULER_API_BIND>:8000/api/v1/whoami` answers the tenant.
7. Prove the install end to end with the [host conformance kit](../../infra/host-conformance/README.md), from the host's network, with a token for a dedicated `conformance` tenant (never a client's). Run it again after every upgrade.

## Upgrade

Pull the new revision, then `docker compose -f deploy/vm/docker-compose.yml --env-file deploy/vm/.env up -d --build`. `scheduler-admin` applies new migrations before the new scheduler starts. With a remote solver, follow the runbook's upgrade order (new solver pool beside the old one first).

## Scaling the pools

Each pool scales on its own, by replica count and by each replica's share (`.env`, then `up -d`):

- **Booking** (`SCHEDULER_BOOKING_REPLICAS`, `_CPUS`, `_MEMORY`, `_SEARCH_CAPACITY`): add replicas when booking p95 nears the client's deadline or `scheduler_admission_rejections_total` rises on booking replicas. Keep the search capacity at about the replica's cores.
- **Daily** (`SCHEDULER_DAILY_REPLICAS`, `_CPUS`, `_MEMORY`, `_SEARCH_CAPACITY`, `SCHEDULER_DAILY_BACKGROUND_LIMIT`): add replicas, or raise the background limit up to the search capacity with the cores to match, when queued days wait long (from `queuedAt` to `startedAt` in `GET /api/v1/requests/{id}`) or `scheduler_daily_queue_expired_total` rises.

What is single on this VM, what happens when each part stops, and the host's retry contract are in the runbook's "Availability" section ([docs/operations-runbook.md](../../docs/operations-runbook.md)), with the CPU sizing caveat. The proxy finds new replicas within 10 seconds without a restart. A replica stopped while it runs a queued day leaves it to another replica once its lease passes; a synchronous call it was running fails at the caller, who repeats it with the same `requestId`.

## Another metro

Copy `routing-omaha` to `routing-<metro>` with that metro's map (its own volume or version directory), and add `<metro id>=http://routing-<metro>:8001` to `ROUTING_METRO_URLS`. The metro ID is the one WaterFlex Software sends in its snapshots.

## Local rehearsal

The same stack runs on a development machine as its own Compose project (`scheduler-vm`), beside the development stack, with nothing shared but the development stack's routing service:

```sh
# deploy/vm/rehearsal.env (ignored by git): local secrets plus
#   SCHEDULER_API_BIND=127.0.0.1  SCHEDULER_API_PORT=18080  PROMETHEUS_PORT=19091
#   ROUTING_METRO_URLS=metro-omaha=http://host.docker.internal:8001  ROUTING_AUTH_TOKEN=<the development stack's>
#   ACTIVE_MAP_VERSION=<any>  SCHEDULER_RETENTION_DAYS=30
docker compose -f deploy/vm/docker-compose.yml -f deploy/vm/rehearsal.override.yml --env-file deploy/vm/rehearsal.env --profile monitoring up -d --build
docker compose -f deploy/vm/docker-compose.yml -f deploy/vm/rehearsal.override.yml --env-file deploy/vm/rehearsal.env run --rm scheduler-admin --command=issue-token rehearsal "Rehearsal" primary
curl http://127.0.0.1:18080/health
# ... and down -v to remove it.
```

`rehearsal.override.yml` moves `routing-omaha` into an unused profile and points both pools at the development router, so the Omaha graph is not loaded twice. Moving to the real VM is this directory, a `.env`, the map volume and a token.

A before-and-after verification runs one image per commit: build each in its own worktree under its own tag, then bring the stack up with `SCHEDULER_IMAGE=<tag> SCHEDULER_PULL_POLICY=never` and without `--build`, so the tag is what runs. `infra/run-stack-verification.ps1` does this, with `SCHEDULER_STUDY_RECEIPTS=true` and `TIMEFOLD_LOG_LEVEL=INFO` for the study's readings, and `SCHEDULER_STUDY_PROFILES=/profiles` (a host directory mounted into the daily replica) so every phase of every proposal is flight-recorded; `infra/stack_profiles.py` attributes the recordings into the evidence. Its proposals use the client's example policy, `TABU_KOPT` with a 2 percent fairness budget (`-DailySearch`, `-FairnessBudget`).
