# WaterFlex Technician Scheduler

A booking and dispatch scheduler for field technicians, with self-hosted maps for Omaha and dealerships across metros. The scheduler is a stateless Spring Boot service with a Timefold solver behind a public API (`/api/v1`): a host such as WaterFlex Software sends a snapshot of its technicians and appointments and gets back booking offers, daily route proposals or repair proposals. The customer and dispatch portal in `web/` is one such host. In production the scheduler runs on its own VM and is reached only over HTTP.

## Architecture

The portal and other hosts call the scheduler over `/api/v1`; the scheduler owns its database and calls its own routing and address services.

```text
 portal (web/, Next.js)                    other hosts (WaterFlex Software)
   | Prisma --> db (portal Postgres)           |
   +------------------ /api/v1 ---------------+
                         |
                 scheduler-service (Spring Boot, Flyway --> scheduler-db)
                   |  calculation-engine (Timefold), embedded,
                   |  or solver-service when SCHEDULER_CALCULATION_MODE=REMOTE
                   |
                   +-- routing-service (GraphHopper), one per metro
                   +-- nominatim (address lookup, optional on the VM)
 portal map UI ------- tiles (PMTiles)
```

The scheduler owns its database and nothing else connects to it. On the VM it runs as two pools, `scheduler-booking` and `scheduler-daily`, behind the nginx `scheduler-proxy` ([deploy/vm/README.md](deploy/vm/README.md)). The design and its decisions are in [docs/stateless-api-design.md](docs/stateless-api-design.md).

## Repository layout

The top-level paths and what each holds:

| Path                                | What it is                                                                                                                                                                                                        |
| ----------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `calculation-engine/`               | Maven module: the Timefold model, constraints, scoring and the independent route evaluator, shared by the scheduler and the solver service.                                                                       |
| `scheduler-service/`                | Maven module: the Spring Boot service behind `/api/v1`, its Flyway migrations (`src/main/resources/db/migration`) and its admin commands.                                                                         |
| `solver-service/`                   | Maven module: the remote solver pool (`remote-solver` profile).                                                                                                                                                   |
| `routing-service/`                  | Maven module: GraphHopper road matrix, legs and geometry for one metro's prepared map.                                                                                                                            |
| `campaign-benchmark/`               | Maven module, built only with `-Pcampaign-benchmark`: the Timefold audit benchmark adapters.                                                                                                                      |
| `web/`                              | The Next.js portal: booking, dispatch, technicians, dealerships, time off and the Solver Settings page. Prisma schema and migrations are in `web/prisma`.                                                         |
| `infra/`                            | Map preparation and activation scripts, `map-import` image, routing fixture, experiment and study harnesses, CI helpers and their Python tests, and the [host conformance kit](infra/host-conformance/README.md). |
| `deploy/vm/`                        | The production VM's Compose stack, `.env.example` and the local rehearsal override.                                                                                                                               |
| `experiments/`, `run_experiment.py` | Tracked experiment configs and datasets; runs are written to `experiments/runs/` (ignored). See [docs/configurable-experiments.md](docs/configurable-experiments.md).                                             |
| `docs/`                             | Living docs, the OpenAPI contract, evidence JSON and dated study reports (see [Documentation](#documentation)).                                                                                                   |

## Requirements and versions

- Docker Desktop with space for the Nebraska, Iowa and Missouri OSM extracts, the US Census TIGER address file (about 1.9 GB downloaded temporarily per map version; only the ranges near the depots are kept), a GraphHopper graph, Nominatim and PMTiles.
- Java 25 to run Maven outside Docker. The checked-in Maven Wrapper downloads Maven 3.10.0.
- Node to run portal checks outside Docker. CI uses Node 22, while `web/Dockerfile` builds on `node:26-slim`; `web/package.json` declares no engine yet.

The main components run at these versions:

| Component               | Version                   |
| ----------------------- | ------------------------- |
| Spring Boot             | 4.1.2                     |
| Timefold Solver         | 2.7.1                     |
| GraphHopper             | 11.1                      |
| PostgreSQL              | 16 (`postgres:16-alpine`) |
| Nominatim               | `mediagis/nominatim:5.3`  |
| Planetiler / go-pmtiles | 0.9.0 / v1.22.1           |

[UPSTREAMS.md](UPSTREAMS.md) records the upstream starting points and their source revisions.

## Local ports

Every published Compose port binds to `127.0.0.1`.

|                  Port | Service                                                                                                           |
| --------------------: | ----------------------------------------------------------------------------------------------------------------- |
|                  3001 | Portal (`web`)                                                                                                    |
|           8000 / 9000 | Scheduler API / management (`/actuator/health`, `/actuator/prometheus`)                                           |
|                  8001 | Routing service                                                                                                   |
|                  8082 | Nominatim                                                                                                         |
|                  8083 | PMTiles                                                                                                           |
|                  5433 | Portal database (`db`)                                                                                            |
|                  5434 | Scheduler database (`scheduler-db`)                                                                               |
| 18082 / 18001 / 18083 | Map preview Nominatim, routing and tiles (`docker-compose.map-preview.yml`, while a new map version is validated) |
|                 18080 | The VM rehearsal stack's API, when it runs beside this one ([deploy/vm/README.md](deploy/vm/README.md))           |

## First local setup

Map preparation reads the depot circles from the portal database, so the portal database comes first.

1. Create `.env` at the repository root with `SCHEDULER_RETENTION_DAYS` (see [Scheduler operations](#scheduler-operations)); the scheduler does not start without it.
2. Install the portal's dependencies (`cd web; npm ci`), which the map scripts use.
3. Start and migrate the portal database, then seed it (a new demo database only; normal startup never seeds):

   ```powershell
   docker compose up -d db schema-migrate
   $env:DATABASE_URL = 'postgresql://waterflex:waterflex@127.0.0.1:5433/waterflex'
   cd web; npm run prisma:seed; cd ..
   ```

4. Before upgrading an existing database to multi-depot dealerships, run the [depot owner audit](docs/dealerships.md) instead and resolve every ownerless or multiply owned depot. The migration stops while the audit has rows.
5. Prepare, validate and activate a map version, with `DATABASE_URL` still set, as in [Map versions](#map-versions).
6. Start everything with `docker compose up -d`.

## Normal local updates and restarts

Use `docker compose up -d` for normal updates and startup. The local images are rebuilt with Docker's layer cache, outdated containers are recreated, and idempotent one-shot jobs run before anything uses their output:

- `schema-migrate` runs `prisma migrate deploy` on the portal's database (`db`).
- `scheduler-admin` runs the scheduler's own migrations on `scheduler-db` and creates its login.
- `portal-clients` and `token-bootstrap` give every portal client a scheduler token (see [Public API and tokens](#public-api-and-tokens)).

If a migration fails, the services that need it stay stopped; inspect it with `docker compose ps -a` and `docker compose logs schema-migrate scheduler-admin`.

Use `docker compose up --no-build` only to restart intentionally on the existing local images. For a restart that neither rebuilds nor migrates, use `docker compose restart db scheduler-db routing-service scheduler-service nominatim tiles web`. Do not run an unqualified `docker compose restart`: it would rerun the one-shot jobs without their dependency checks. `docker compose down` keeps the named volumes. `docker compose down -v` deletes every named volume (`app-db`, `scheduler-db`, `map-data`, `nominatim-db` and `portal-api-tokens`) and is not part of the normal workflow.

### Map versions

Each map is an immutable dated version with its own Nominatim volume, so the serving map is never rebuilt in place. The coverage rules and what validation checks are in [docs/address-and-location.md](docs/address-and-location.md#map-coverage).

1. `./infra/prepare-map.ps1 -Version <version>` (with `DATABASE_URL` set) downloads and checksums the state extracts, keeps the US Census TIGER address ranges (`TIGER_YEAR`, default 2025) within the depot circles plus the margin (`infra/map-import/filter_tiger.py`), merges the extracts, and builds the tiles and both road graphs (`graph` and `graph-ch-car-v1`) in the `map-data` volume, with a manifest at `/maps/versions/<version>/manifest.json`. It then starts the preview services, whose Nominatim imports into `waterflex-nominatim-preview-<version>` with that version's TIGER file. The import takes substantial time and disk space.
2. After the preview Nominatim finishes importing, `./infra/validate-map.ps1 -Version <version>` writes the validation report that activation requires.
3. Pause booking, then run `./infra/activate-map.ps1 -Version <version>`. It checks the artifacts and report (`map-import check-activation`), removes the preview containers, records `ACTIVE_MAP_VERSION` and `COMPOSE_FILE=docker-compose.yml;docker-compose.map-active.yml` in `.env` so later plain `docker compose` commands keep serving the version, and restarts `nominatim`, `routing-service` and `tiles` on it.

To roll back, restore the `.env.before-<version>` backup the script made (or delete `.env` when there was none) and run `docker compose up -d --no-deps nominatim routing-service tiles`. Keep the previous version and its Nominatim volume until bookings have gone through on the new one, then delete them with `docker volume rm <name>`. The [operations runbook](docs/operations-runbook.md) records which version is active.

The TIGER ranges let Nominatim place a house number OpenStreetMap lacks; a match still needs the house number, street and ZIP to agree. Never set `IMPORT_TIGER_ADDRESSES=true`, which makes the image download TIGER data from a third-party server.

## Configuration

### Scheduler operations

The [operations runbook](docs/operations-runbook.md) covers start order, upgrades, health, logs, metrics, retention, backups, scaling levers and failure modes.

- `SCHEDULER_RETENTION_DAYS` (required, 1 to 3650, no default): days the thin store keeps requests, proposals, offers and receipts before the daily purge.
- `SCHEDULER_DB_PASSWORD`: password of the scheduler's database login `scheduler`, a member of the least-privilege role `scheduler_app`, which `scheduler-admin` creates or updates after the migrations. `SCHEDULER_DB_OWNER_PASSWORD` is the owner's password of `scheduler-db`, used only by `scheduler-admin` and `token-bootstrap`. Compose has local-only defaults for both.
- `MANAGEMENT_PORT` (default 9000): `/actuator/health` and `/actuator/prometheus`, never on the API port. Publish it only on a private interface.
- `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`: JSON log lines. Either way each `/api/v1` call logs one summary line with its tenant and host `requestId`.
- `SCHEDULER_CALCULATION_MODE` (`EMBEDDED` by default, or `REMOTE` with `SCHEDULER_CALCULATION_URL` and `SCHEDULER_CALCULATION_AUTH_TOKEN`): where solving runs. `docker compose --profile remote-solver up -d` starts `solver-service`.
- `SCHEDULER_SEARCH_CAPACITY`, `SCHEDULER_DB_POOL_SIZE`, `SCHEDULER_BOOKING_REFINEMENT_MS` and `SCHEDULER_BOOKING_PUBLISH_RESERVE_MS`: search admission and booking budgets; their defaults and ranges are in [docs/scheduler-policy.md](docs/scheduler-policy.md#configuration).
- `GEOCODING_MODE` and `NOMINATIM_URL`: address lookup for locations sent without coordinates.

`GET /health` on the API port is readiness: 200 when the database, every metro's routing service, a remote solver and the address lookup (when configured) answer within a second, 503 with the failing component otherwise. `GET /health/live` reads nothing.

### Routing

`routing-service` requires a shared bearer token on its `/internal/*` endpoints and refuses to start without one (`ROUTING_AUTH_TOKEN`, at least 32 non-whitespace characters); `/health` stays open. Compose sets a local-only default in both `routing-service` and `scheduler-service`; any other deployment must set its own secret in both. Scripts that call a real routing service directly (`infra/validate-map.py`, `infra/verify-sparse-routing.mjs`, `infra/benchmark-routing-ch.mjs`) read the same variable. The CI fixture router does not check it. `ROUTING_CH_ENABLED` (default true) serves the contraction hierarchy graph and `ROUTING_PARALLELISM` sets the threads per request.

`/api/v1` calculations route through one routing service per metro, listed in `ROUTING_METRO_URLS` as comma-separated `metro=url` entries using WaterFlex Software metro IDs (Compose lists the seeded `metro-omaha=http://routing-service:8001`; the VM adds one service per metro). A request for an unlisted metro fails; it never falls back to `ROUTING_URL`.

## Public API and tokens

The public scheduling API lives under `/api/v1/` and is the scheduler's only interface. Each call needs a per-client bearer token. The contract is [docs/api/openapi-v1.yaml](docs/api/openapi-v1.yaml) (version 1.7.0), [docs/integration-guide.md](docs/integration-guide.md) shows a host how to use it, and the [host conformance kit](infra/host-conformance/README.md) checks a scheduler against it.

Tokens are issued by the scheduler's own command line, as the owner of its database: `docker compose run --rm scheduler-admin --command=issue-token <tenantId> "<tenant name>" "<token label>"` (the token is printed once and only its SHA-256 digest is stored), plus `--command=revoke-token <tokenId>`, `--command=list-tenants`, `--command=disable-tenant <tenantId>` and `--command=enable-tenant <tenantId>`. Outside Compose, run `java -jar scheduler-service.jar --command=...` with `JDBC_DATABASE_URL`, `DATABASE_USER` and `DATABASE_PASSWORD` set to the owner's connection. `GET /api/v1/whoami` returns the tenant a token belongs to.

The portal uses one token per client, for the tenant with the same ID. Set it as `SCHEDULER_API_TOKEN_<CLIENT ID>` (uppercase, hyphens as underscores) in `portal-api-tokens.env` at the repository root, for example `SCHEDULER_API_TOKEN_DEFAULT=wfs_...`. Local Compose does this for you, because the scheduler never reads the portal's database: `portal-clients` writes the portal's client list to the `portal-api-tokens` volume, and `token-bootstrap` (`--command=bootstrap-local-tokens`) gives every client a token file there, which the portal reads from `SCHEDULER_API_TOKEN_DIR` when the variable is unset. A token file the scheduler accepts is kept; a missing or unknown one is reissued. For a client created after `up`, run `docker compose run --rm portal-clients && docker compose run --rm token-bootstrap`. `LOCAL_TOKEN_BOOTSTRAP=false` turns this off; production never enables it. The portal checks each token's tenant before using it, never stores tokens, and refuses to book for a client without one. The sidebar shows whether the active client is connected, and why not.

## Portal

### Clients and Solver Settings

The portal schedules for one or more clients, chosen in the sidebar. Each client's dealerships, depots, technicians, customers and bookings are kept apart, and existing data belongs to the client `default`. With `DATABASE_URL` set, add a client from `web/` with `npx tsx scripts/create-client.ts <clientId> "<client name>"`. Two clients may serve the same metro.

The **Solver Settings** page holds each client's booking settings (offers per search, 1 to 4; booking horizon in weekdays) and routing costs (regular and overtime pay, mileage, travel buffers, fairness budget). The portal sends them with the client's `/api/v1` calculations, together with a snapshot of only that client's technicians and appointments (`web/lib/clientSnapshot.ts`). A new client has no settings, and nothing is calculated for it, until they are saved. Only the returned offers hold capacity, and limited availability can yield fewer offers, including none.

The portal runs each client's overnight optimization itself: Solver Settings sets the run times (or none, for manual runs only) and has **Run now**, and the `overnight-worker` service (`docker compose --profile overnight up`, or `npm run overnight:worker` in `web/`) does the work. Overnight improvements wait on the dispatch board for a dispatcher to apply them. See [docs/stateless-api-design.md](docs/stateless-api-design.md#portal-as-a-client-decided-2026-10-08).

### Views

- `/book`: customer address resolution, local pin confirmation, ten-minute reserved offers, refresh and immediate confirmation. See [address matching and booking location](docs/address-and-location.md).
- `/schedule`: weekly routes, approved time-off blocks, promises and reasoned cancellation.
- `/dispatch`: route review and optimization proposals. A preview optimizes only the active client's routes through `/api/v1/daily/proposals`, and applying it writes the proposal only if none of its technician-days changed since.
- `/dealerships`: create an organization, then add its metro depots and their route policies. See [dealerships and depots](docs/dealerships.md).
- `/technicians`: create technicians at a depot and schedule same-dealership depot moves by service date. Home lookup searches the full address, then retries without the state filter. Only candidates whose country, ZIP, city or town, street, and house number and state when present agree are used; the best house result is selected, with a street-only result as fallback. An unchanged house pin can be used directly; a moved house pin needs map confirmation within 250 meters of its geocoder result, and a street-only candidate within 100 meters of its street bounds. Every home pin must be inside the depot metro's service area, and the server repeats these checks on create.
- `/dispatch/testing`: local Compose sequential booking runs that book through the current booking search and show each booked day's road routes. Compose enables it; set `LOCAL_BOOKING_TESTS=false` to opt out. Other environments stay disabled unless they set `LOCAL_BOOKING_TESTS=true`. See [sequential booking tests](docs/sequential-booking-tests.md).
- `/dispatch/availability`: qualifications and date-specific shifts, which the portal saves itself.
- `/dispatch/follow-up`: pending and contacted requests without a promised window.
- `/time-off`: local demo technician selector, request history and staff approval queue. The selector does not authenticate a technician.
- `/settings`: Solver Settings for the active client.

### Booking and cancellation API

`POST /api/book` takes an idempotent `requestId` with customer, address and service details. It returns `{jobId, offers}` or a pending follow-up reference; empty capacity is recorded as `NO_CAPACITY`. Up to the client's offers per search share one ten-minute expiry. The scheduler validates overlapping sibling holds as a set, reuses an unexpired set on retry, and returns fewer choices when the configured maximum cannot be reserved. `POST /api/book/refresh` supersedes the set and releases its holds in one transaction. `POST /api/book/release` takes `{jobId, offerId}` and releases every sibling hold; it is safe to retry, an offer from another job conflicts, a stale set cannot release a newer one, and a scheduled job cannot be released. `POST /api/book/select` rechecks the chosen option, creates the appointment, releases its siblings and returns the appointment reference and promised window. A retry of the same selection returns that appointment; a different selection conflicts.

`POST /api/schedule/appointments` requires an appointment ID and a reason. Cancellation is idempotent, keeps the appointment and reason as history, and frees its capacity. Before the scheduling cutoff the remaining technician route is recalculated while preserving promised windows; at or after it, the other visits stay as planned. The cutoff is **6 a.m. America/Chicago on the service day**, and it also applies to optimization preview and apply, time-off analysis and approval, and date-specific availability edits. Service dates stored as midnight UTC are calendar keys; the portal displays their UTC date component and interprets shift and absence minutes in America/Chicago, including daylight-saving transitions.

### Time off and repair

The `/time-off` form applies the same local start and end times to every date in its inclusive range. Requests need a reason, a future start, a valid interval and at most 31 dates, and may not overlap a pending, ready or approved interval for the same technician. Pending requests do not reduce booking capacity. Staff can retry a failed analysis or deny any unapproved request; denial frees its intervals. Approved requests cannot be denied. The technician selector and staff controls are local demo controls without authentication.

The portal analyzes a request one day at a time through `/api/v1/repairs/proposals` and stores a report across all requested dates. A repair may reassign and reorder visits, subject to existing promises, qualifications, working limits, approved absences and routing validation. Partial-day absences split a shift into separate working intervals, with travel home before each absence and a new departure afterward. A feasible request whose **first affected date is at least 14 local calendar days away** is applied automatically, even when modeled cost rises; a closer one stays `READY` for staff approval. Any frozen date keeps the whole request pending for coordination. The report records daily and total before and after paid, overtime, drive and waiting minutes, distance, modeled cost in cents and reassigned jobs. Approval commits each day's repair at the scheduler, then writes the request with a compare-and-set on every changed technician-day. No email or SMS is sent.

### Scheduling policy and route quality

The independent `RouteEvaluator` checks exact appointment coverage, qualifications, promises, absences, route timing and technician limits. Booking offers only arrangements that add no overtime and ranks them by incremental operating cost, using capacity-weighted workload fairness only to break exact cost ties. Daily optimization minimizes overtime and cost in a reference phase, then allows fairness improvements within the client's fairness budget. Preview and apply reject any proposal with overtime, including one that only reduces it. Cost is rounded once to cents after aggregating regular paid minutes, overtime and mileage; it is modeled operating cost, not a customer price or payroll. The full policy, numeric contract and configuration are in [docs/scheduler-policy.md](docs/scheduler-policy.md).

The routing service's `/health`, `/internal/matrix`, `/internal/legs` and `/internal/route` responses carry `routingIdentity`, a SHA-256 fingerprint of the prepared graph and configuration; a mismatched expected identity returns HTTP 409. The scheduler normalizes coordinates to five decimal places, batches missing directed pairs, coalesces shared requests and rejects malformed responses. Proven unreachable legs are cached; routing outages are not, and there is never a straight-line fallback. The dispatch map renders the road `LineString` features from `/api/dispatch/geometry` and keeps stop markers with a clear message when geometry is unavailable. Jackson tree objects must not cross that HTTP boundary: the routing parser uses Jackson 2 while Spring MVC uses Jackson 3.

## Quality gates

These match what CI runs ([docs/ci-runtime.md](docs/ci-runtime.md)). On Windows, set `$env:JAVA_HOME='C:\Program Files\Java\jdk-25'` and use `mvnw.cmd`.

```powershell
# Java: javac, then the strict ECJ nullability gate (docs/nullability-remediation.md)
./mvnw -Pcampaign-benchmark clean verify
./mvnw -Pcampaign-benchmark,nullability clean verify

# Timefold FULL_ASSERT campaign (solver-assertions.yml; NON_INTRUSIVE_FULL_ASSERT and PHASE_ASSERT are the other modes)
./mvnw -pl calculation-engine -DskipTests install
./mvnw -pl scheduler-service -Psolver-assertions test -Dassertions.mode=FULL_ASSERT -Dassertions.budgetMs=250 `
  -Dassertions.revision=$(git rev-parse HEAD) -Dassertions.output=artifacts/assertions/full-assert.jsonl

# Portal
cd web
npm ci
npm run prisma:generate
npm run lint
npm run typecheck
npm run test:unit
npm run test:nullability
npm run build
npm run test:ui          # Playwright; needs the isolated nullability_ui schema
cd ..

# Infra and audit helpers (JAVA_HOME must point at a JDK with javac)
python -m unittest discover -s infra -p 'test_*.py'
python -m unittest discover -s docs/timefold-audit -p 'test_*.py'
python -m unittest discover -s docs/timefold-audit/remediation -p 'test_*.py'

# Host conformance kit
cd infra/host-conformance; npm ci; npm run typecheck; npm test
```

The integration smokes (`npm run test:*:integration` in `web/`, such as `test:api-booking`, `test:api-dispatch` and `test:api-repair`) need the routing fixture (`node infra/fixture-routing.mjs`, port 18001) and a scheduler on port 18000 pointed at an isolated `waterflex_test` database. Never run them against a real customer database. `npm run test:fake-data:routed` is the exception that needs real road routing for `metro-omaha`; CI does not run it.

### CI

`.github/workflows/ci.yml` runs on pull requests to `main` and pushes to `main`, skipping Markdown-only changes. It reports two aggregate checks, **Build and unit** and **Booking and optimizer integration**, that pass only when every job they cover passes, and runs **Browser regressions** beside them. `solver-assertions.yml` runs the Timefold assertion modes daily and on pull requests that touch the scheduler, and `maintenance.yml` builds the images and runs `npm audit` weekly.

## Deployment

The scheduler runs on its own VM in production. [deploy/vm/README.md](deploy/vm/README.md) has the VM's Compose file (the database, the booking and daily pools behind `scheduler-proxy`, one routing service per metro, and optional Nominatim, remote solver and Prometheus), the install steps using `deploy/vm/.env.example`, and a local rehearsal that runs the VM stack beside this development stack. The scheduler creates and migrates its own schema (`java -jar scheduler-service.jar --command=migrate`).

## Documentation

Living references:

- [Operations runbook](docs/operations-runbook.md): running the scheduler, scaling levers, failure modes.
- [Scheduling policy](docs/scheduler-policy.md): promises, overtime, cost and fairness, lifecycle, configuration.
- [Stateless API design](docs/stateless-api-design.md) and [integration guide](docs/integration-guide.md), with the [OpenAPI contract](docs/api/openapi-v1.yaml).
- [API load results](docs/api-load.md) and [solver experiments](docs/solver-benchmarks.md): measured behavior and the studies behind each default.
- [Configurable experiments](docs/configurable-experiments.md): the one-command experiment launcher.
- [Dealerships and depots](docs/dealerships.md), [address matching and booking location](docs/address-and-location.md) and [sequential booking tests](docs/sequential-booking-tests.md).
- [CI runtime and coverage](docs/ci-runtime.md) and [nullability remediation](docs/nullability-remediation.md).
- [Field validation](docs/scheduler-field-validation.md) and the [algorithm comparison](docs/scheduler-algorithm-comparison-2026-09-29.md) (generated reports).
- [Timefold audit](docs/timefold-audit/README.md) and its remediation phases, including the opt-in [benchmark adapters](docs/timefold-audit/remediation/phase11/README.md). `docs/timefold-documentation/` is a local copy of the Timefold Solver documentation that code comments cite by section number.

Dated study reports, each a Word report with its evidence and builder, are kept as records: `docs/daily-budget-331df1f1`, `daily-tabu-variants-5d15129b`, `daily-search-followup-6454fb05`, `performance-work-2026-10-14`, `scheduler-explained-2026-10-14`, `booking-configuration-study-2026-10-19`, `fairness-measures-2026-10-20`, `road-matrix-numbering-2026-10-20`, `stack-verification-2026-10-20`, `booking-experiment-explainer` and `scheduler-explainer`. Raw evidence is under `docs/evidence/`.

## Known harmless log lines

These come from upstream images or tools and need no action: Nominatim's `ASGI app factory detected` warnings (one per worker) and its `Website setup is no longer required` notice, and a PostgreSQL `could not receive data from client: Connection reset by peer` logged when the `prisma migrate deploy` CLI in `schema-migrate` exits.

## Current limitations

- Manual reassignment is not exposed in the dispatch UI; optimization proposals and guarded apply are the supported route-change path.
- The `/time-off` technician selector and staff controls do not authenticate anyone.
- TLS is not set up on the VM yet; see [deploy/vm/README.md](deploy/vm/README.md#before-production-traffic).

## Contributing

[AGENTS.md](AGENTS.md) holds the repository's working rules: plan tracking, nullability as an acceptance criterion, how benchmarks and studies are run and promoted, and workspace hygiene.
