# WaterFlex Technician Scheduler

Local booking and dispatch prototype with Omaha map data and support for dealerships across metros. The customer portal is in `web/`, the Spring Boot booking and Timefold service is in `scheduler-service/`, the GraphHopper matrix service is in `routing-service/`, and map preparation is in `infra/`. [UPSTREAMS.md](UPSTREAMS.md) records source versions and revisions.

## Requirements

- Docker Desktop with space for Nebraska and Iowa OSM extracts, a GraphHopper graph, Nominatim, and PMTiles.
- Java 25 for running Maven outside Docker. The checked-in Maven Wrapper downloads Maven 3.9.16.
- Node 22 for portal checks outside Docker.

All published Compose ports bind to `127.0.0.1`. The portal is at `http://localhost:3001`, the scheduler at `:8000`, routing at `:8001`, Nominatim at `:8082`, PMTiles at `:8083`, and PostgreSQL at `:5433`.

## First local setup

1. Prepare a dated map version: `./infra/prepare-map.ps1 -Version YYYY-MM-DD`. It downloads and checksum checks the state extracts, merges them, and builds tiles and a road graph in the `map-data` volume. The manifest is at `/maps/versions/<version>/manifest.json` in that volume.
2. Import Nominatim into its separate `nominatim-db` volume with `docker compose run -d -e PBF_PATH=/maps/versions/<version>/omaha.osm.pbf nominatim`. Wait for its import to complete and serve search responses, then stop that one-off container. This import takes substantial time and disk space.
3. Activate the prepared map with `docker compose --profile map-import run --rm map-import activate <version>`.
4. Before upgrading an existing database to multi-depot dealerships, run the [depot owner audit](docs/dealerships.md). Resolve every ownerless or multiply owned depot explicitly. The migration stops if the audit has unresolved rows.
5. Start the application with `docker compose up -d`. Compose builds the local application images, waits for PostgreSQL, applies all tracked Prisma migrations, and starts the portal and scheduler only after migration succeeds.
6. For a new demo database only, seed sample data explicitly with `docker compose exec web npx prisma db seed`. Normal startup never runs seed commands.

## Normal local updates and restarts

Use `docker compose up -d` for normal updates and startup. The local images are checked and rebuilt with Docker's layer cache, outdated containers are recreated, and the idempotent `schema-migrate` job runs `prisma migrate deploy` before `web` or `scheduler-service` starts. If migration fails, those application services remain stopped. Inspect the failure with `docker compose ps -a` and `docker compose logs schema-migrate`.

Use `docker compose up --no-build` only when intentionally restarting with the existing local images. For a restart that neither rebuilds nor runs migrations, use `docker compose restart db routing-service scheduler-service nominatim tiles web`. Do not use an unqualified `docker compose restart`: Compose restarts stopped one-shot services too, so it would rerun `schema-migrate` without startup dependency checks. `docker compose down` removes containers and networks but preserves the named data volumes. `docker compose down -v` deletes the PostgreSQL, map, and Nominatim named volumes and is destructive, so it is not part of the normal workflow.

Normal startup preserves the existing `app-db`, `map-data`, and `nominatim-db` volumes and does not repeat map or Nominatim imports. A map refresh must prepare a new version first, rebuild the Nominatim database separately, pause booking, activate the matching graph and tiles, and restart the map services. The Nominatim volume is currently a single active database, so back it up before a refresh. Do not activate a new map version while an old Nominatim import is serving bookings.

## Routing service token

`routing-service` requires a shared bearer token on its `/internal/*` computation endpoints and refuses to start without one (`ROUTING_AUTH_TOKEN`, at least 32 non-whitespace characters); `/health` stays open. Compose sets a local-only default for both `routing-service` and `scheduler-service`. Any deployment outside local Compose must set its own secret in both services. Scripts that call a real routing service directly (`infra/validate-map.py`, `infra/verify-sparse-routing.mjs`, `infra/benchmark-routing-ch.mjs`) read the same variable. The CI fixture router does not check it.

The public `/api/v1` calculations route through one routing service per metro, set with `ROUTING_METRO_URLS` (`metro=url` entries separated by commas, using WaterFlex Software metro IDs, for example `omaha=http://routing-omaha:8001,lincoln=http://routing-lincoln:8001`). A request for a metro that is not listed fails; it never falls back to `ROUTING_URL`, which stays the routing service for the internal portal. Every listed service uses the same `ROUTING_AUTH_TOKEN`.

## Public API tokens

The public scheduling API for WaterFlex Software lives under `/api/v1/` and requires a per-client bearer token; the internal `/v1/` routes used by this portal are unchanged. With `DATABASE_URL` set, issue a token from `web/` with `npx tsx scripts/issue-tenant-token.ts issue <tenantId> "<tenant name>" "<token label>"` (the token is printed once and only its SHA-256 digest is stored) and revoke one with `npx tsx scripts/issue-tenant-token.ts revoke <tokenId>`. `GET /api/v1/whoami` returns the tenant a token belongs to. See [docs/stateless-api-design.md](docs/stateless-api-design.md).

## Appointment offer limit

`BOOKING_OFFER_LIMIT` sets the deployment-wide maximum choices for each new booking offer set. Valid values are exactly `1`, `2`, or `4`; the default is `4` only when unset. Empty, malformed, and unsupported values fail scheduler startup with a configuration error. The scheduler logs the effective limit at startup.

For a persistent setting, add this to the root `.env` file:

```dotenv
BOOKING_OFFER_LIMIT=2
```

Alternatively, set it in the PowerShell session before starting Compose:

```powershell
$env:BOOKING_OFFER_LIMIT = "2"
docker compose up -d
```

Shell values override `.env`. Clear a shell override with `Remove-Item Env:BOOKING_OFFER_LIMIT -ErrorAction SilentlyContinue`, then run `docker compose up -d` to use `.env` or the default again. Changing this setting requires container recreation through `compose up`; `compose restart` does not update its environment. Direct Spring deployments can set `booking.offer.limit` (for example, `--booking.offer.limit=2`). Use the same setting on every scheduler instance.

The limit applies while building and reserving both legacy and common-reservation offers, so only returned choices hold capacity. It does not reduce search coverage, change ranking or feasibility checks, or establish scarcity for overtime eligibility. Limited availability can yield fewer choices, including none. Existing sets remain selectable with their original choices until expiration, release, or refresh. Refresh releases the previous holds and creates a set using the current limit. The response shape, database schema, and booking screen are unchanged.

## Views and API

- `/book`: customer address resolution, local pin confirmation, ten-minute reserved offers, refresh, and immediate confirmation.
- `/schedule`: weekly routes, approved time-off blocks, promises, and reasoned cancellation.
- `/dispatch`: route review and overnight optimization preview and apply.
- `/dealerships`: create an organization, then add its metro depots and their route policies. See [setup and dated moves](docs/dealerships.md).
- `/technicians`: create technicians at a depot and schedule same-dealership depot moves by service date.
- `/dispatch/testing`: local Compose sequential booking runs and automatic day previews. Compose enables the route by default; set `LOCAL_BOOKING_TESTS=false` to opt out. Non-Compose and production environments remain disabled unless they explicitly set `LOCAL_BOOKING_TESTS=true`. See [operation, recovery, and verification](docs/sequential-booking-tests.md).
- `/dispatch/availability`: qualifications and date-specific shifts.
- `/dispatch/follow-up`: pending and contacted requests without a promised window, with contacted and resolved actions.
- `/time-off`: local demo technician selector, request history, and staff approval queue. The selector does not authenticate a technician.

`POST /api/book` takes an idempotent `requestId` with customer, address, and service details. It returns `{jobId, offers}` or a pending follow-up reference. Empty capacity results are recorded as `NO_CAPACITY`. Up to `BOOKING_OFFER_LIMIT` offered windows (four by default) share one ten-minute expiry. The scheduler validates overlapping sibling holds as a set, reuses an unexpired set on retry, and returns fewer choices when the configured maximum cannot be reserved. `POST /api/book/refresh` supersedes that set and releases its holds in one transaction. `POST /api/book/release` takes `{jobId, offerId}` from the displayed set and releases every sibling hold before Start over returns to the populated form. It is safe to retry; an offer from another job conflicts, a stale set cannot release a newer set, and a scheduled job cannot be released. The scheduler endpoint is `POST /v1/offers/release` with the same body. `POST /api/book/select` rechecks the chosen option, creates the appointment, releases its siblings, and returns the appointment reference and promised window. A retry of the same selection or the legacy `POST /api/book/confirm` returns that appointment; a different selection conflicts. A successful booking clears pending follow-up.

`POST /api/schedule/appointments` requires an appointment ID and a reason. Cancellation is idempotent, keeps the appointment and reason as history, and frees its capacity. Before the scheduling cutoff, the scheduler recalculates the remaining technician route while preserving promised windows. At or after the cutoff, it leaves the other visits' technician, order, and planned times intact. The cutoff is **6 a.m. America/Chicago on the service day**. It also applies to optimization preview and apply, time-off analysis and final approval, and date-specific technician availability edits. Service dates stored as midnight UTC are calendar keys; the portal displays their UTC date component and interprets shift and absence minutes in America/Chicago, including daylight-saving transitions.

### Technician time off and repair

The `/time-off` form applies the same local start and end times to every date in its inclusive range. Requests must have a reason, a future start, a valid interval, and at most 31 selected dates. Overlapping pending, ready, or approved intervals for the same technician are rejected. Pending requests do not reduce booking capacity. Analysis keeps nonworking dates in the requested range and marks them as having no scheduled shift. Approval checks those dates again in case availability changed. An infeasible repair becomes a coordination result with a date-specific reason. Analysis failures show a safe date-specific explanation; the exception and request ID are logged. Staff can retry failed analysis using current cutoff and scheduling data, or deny any unapproved request. Denial records its decision time, frees its intervals for new requests, and moves the request to denied history. Approved requests cannot be denied. The page includes request history, analysis progress, and a staff queue. Its technician selector and staff controls are local demo controls without authentication or role enforcement.

The background analyzer stores a report across all requested dates. It can repair an initially infeasible schedule by reassigning and reordering visits, subject to existing promises, qualifications, working limits, approved absences, and routing validation. Approved partial-day absences split a shift into separate working intervals, with travel home before each absence and a new departure from home afterward. Paid route time and overtime are summed across those intervals. A feasible request whose **first affected date is at least 14 local calendar days away** is applied automatically, even when modeled service-delivery cost rises. A feasible request closer than 14 days remains `READY` for staff approval. Any frozen date keeps the whole request pending for CSR coordination, without partial approval. Active booking reservations defer analysis or approval until a stable proposal can be made.

The report records daily and total before/after paid route minutes, overtime minutes, drive minutes, waiting minutes, distance meters, modeled service-delivery cost in cents, and reassigned job counts. The cost is a scheduling model, not a customer price or payroll calculation. Routing failures, constraint conflicts, active reservations, and search-budget exhaustion are reported separately. Approval locks affected days, checks the report against current schedule and configuration versions, reservations, routing assumptions, independent feasibility validation, and the cutoff, then commits the whole request atomically. A changed schedule requires another analysis. Ordinary dispatch optimization still requires dispatch approval and a modeled cost improvement. The 2 a.m. America/Chicago run creates review-only previews for the current service day and the next nine eligible weekdays; a dispatcher must still apply a proposal.

Dispatch shift and qualification edits pass through the Java scheduling guard. Date-specific availability edits also stop at the service-day cutoff. Qualification edits have no service date, so they retain their existing appointment and active-hold protections. If an edit affects an existing appointment or active hold, it returns a conflict for staff coordination. No email or SMS is sent.

### Scheduling policy and route quality

The independent `RouteEvaluator` checks exact appointment coverage, qualifications, promises, absences, route timing and technician limits. Booking offers only arrangements that add no overtime and ranks them strictly by incremental operating cost, using capacity-weighted workload fairness only to break exact cost ties. Daily optimization minimizes overtime and cost in a reference phase, then allows fairness improvements costing at most two percent more than that reference. Preview and locked apply share the daily policy, which rejects any proposal with overtime, including one that only reduces it (for example from 90 to 20 minutes). Paid time includes return travel. Cost is rounded once to cents after aggregating regular paid minutes, overtime and road mileage. It is modeled operating cost, not a customer price or a claim about payroll savings.

The consolidated booking path uses immutable snapshots, sparse directed routing, a five-second deadline and durable common reservation arrangements. Bounded same-day moves preserve existing customer promises and are applied atomically when the customer confirms. New overtime is never offered under the current policy version; the utilization and window thresholds are retained for diagnostics and do not authorize it. Flexible segment departures remove avoidable paid waiting. New search issuance, bounded moves, prewarming and routing acceleration have guarded rollout settings. See [policy, lifecycle, interfaces and rollout](docs/scheduler-policy.md) and [the original-plan acceptance checklist](docs/scheduler-plan-checklist.md).

The routing service `/health`, `/internal/matrix` and sparse `/internal/legs` responses include `routingIdentity`, a SHA-256 fingerprint of the prepared graph and configuration. A mismatched expected identity returns HTTP 409. The scheduler normalizes coordinates to five decimal places, batches missing directed pairs, coalesces shared requests and rejects malformed responses. Memory caches have bounded entries and expiry. Weekly persistent cleanup removes entries older than 30 days and trims excess rows. Proven unreachable legs are cached; routing outages are not.

The internal `/internal/route` endpoint returns road geometry for ordered points under the same identity. `/v1/dispatch/geometry` returns GeoJSON features for a current day or the before and after routes of an optimization run. Each absence-separated working interval uses the service date's configured departure and return endpoints. The dispatch map renders those road features and keeps stop markers with a clear error when geometry is unavailable. Run details show the routing and scheduling configuration fingerprints. New optimization runs save baseline visit order, location, and planned arrival alongside the proposed assignments so the comparison reflects that run. Older runs created before this migration do not have a baseline route snapshot.

Dispatch geometry is serialized as plain response records containing `LineString` coordinates in longitude/latitude order. Jackson tree objects must not cross this HTTP boundary: the routing parser uses Jackson 2 while Spring MVC uses Jackson 3, which otherwise serializes tree metadata instead of GeoJSON. The controller rejects malformed positions with HTTP 503. The map validates the full response before rendering, fits the road geometry and markers, and cancels obsolete requests when the date or preview changes. Invalid or unavailable geometry leaves stop markers visible with an explicit message; it never substitutes straight lines. These are planned routes from the existing self-hosted GraphHopper graph, without GPS tracking or live traffic.

See the [acceptance report and measured tradeoffs](docs/scheduler-acceptance.md), [booking measurements](docs/booking-benchmarks.md), [solver experiments](docs/solver-benchmarks.md), [one-command experiment launcher](docs/configurable-experiments.md) and [implementation evidence](docs/scheduler-policy-progress.md) for verification boundaries and release gates. The earlier [quality foundation](docs/scheduling-quality-foundations.md) is retained as historical context.

The opt-in [Timefold audit benchmark adapters](docs/timefold-audit/remediation/phase11/README.md) provide native solver, complete-policy and paced caller measurements with immutable inputs, explicit JSON settings and retained raw reports. Acceptance fixtures and synthetic corpus results do not establish production savings.

[Paired analysis and registered calibration](docs/timefold-audit/remediation/phase12/README.md) preserve reliability denominators, equal-dataset effects, exact fairness ordering and unmatched reasons. Explicit warmup, concurrency and separate JFR studies retain every attempt and never automatically select production defaults.

[Combined final audit phases](docs/timefold-audit/remediation/phases13-16/README.md) record computational dispositions, isolated algorithm/neighborhood/termination studies, a read-only confirmation review and rollout/rollback gates. Synthetic or uncalibrated results do not authorize production promotion.

## Verification

Technician home lookup first searches the full address, then retries without the state filter when Nominatim has no matching result. Only candidates whose returned country, ZIP, city or town, street, house number when present, and state when present agree with the entered address are used. The best house result is selected automatically, with a street-only result as fallback. The draggable home pin uses the same self-hosted OpenStreetMap map as depot setup. An unchanged house pin can be used directly; a moved house pin requires map confirmation and must remain within 250 meters of its geocoder result. A street-only candidate requires map confirmation and must remain within 100 meters of its returned street bounds. All home pins must be inside the selected depot metro's service area. The server repeats the lookup and pin checks when creating the technician. New technician records save `GEOCODER_HOUSE` or `MANUALLY_CONFIRMED` home pin provenance; older records keep a null provenance because their origin is unknown. Lookup misses and geocoder failures return separate API statuses.

With the services and a seeded database available:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25'
./mvnw.cmd verify
cd web
npm ci
npm run lint
npm run typecheck
npm run test:geocode
npm run test:schema-contract
npm run test:optimization
npm run test:dispatch-geometry
npm run test:booking-tests
npm run build
```

Pull requests to `main` run the `Build and unit` and `Booking and optimizer integration` checks in `.github/workflows/ci.yml`. The integration job uses its own PostgreSQL 16 service, applies Prisma migrations, seeds it, checks the Java schema contract, and starts `infra/fixture-routing.mjs` with the Java scheduler. The routing fixture accepts only the two Monaco coordinates and fixed Omaha sample/home coordinates used by the smoke scripts. CI also runs `npm run test:booking-tests:integration` to verify sequential booking and preview recovery against this isolated database.

The Monaco fixture smoke scripts are `npm run test:booking:integration`, `npm run test:optimizer:integration`, and `npm run test:time-off:integration`. CI runs all three. They require the routing fixture on port 18001 and the scheduler on 18000, pointed at an isolated database. Do not run them against a real customer database. The booking smoke checks reservations, refresh, selection, legacy confirmation, and cancellation. The time-off smoke checks automatic repair, short-notice staff approval, active-reservation deferral, infeasible repair, nonworking dates, failure details, retry, denial, overlap eligibility, and availability changes before approval. `npm run test:fake-data:integration` invokes the retained fixture generator directly without a public portal route and also mutates the isolated database.

`node infra/test-booking-offer-limit-compose.mjs` verifies offer-limit interpolation, including `.env`, shell precedence, and explicit blanks. With the scheduler JAR built, portal dependencies installed, the fixture router running, and `DATABASE_URL` / `JDBC_DATABASE_URL` pointing at migrated and seeded `waterflex_test`, set `ROUTING_URL` to the fixture router and run `node infra/test-booking-offer-limits.mjs` from the root. Set `JAVA_HOME` to the JDK installation on Windows. This gate starts and stops its own scheduler instances on ports 18010 and 18011 and tests all six limit/path combinations, active hold counts, scarcity, refresh, and reuse/selection across limit changes. CI runs both scripts and the one/two/four-choice browser regression.

The dispatch HTTP regression test exercises Spring MVC serialization and checks actual road coordinate arrays, home departure/return, empty schedules, malformed coordinates, and routing failures. The optimizer smoke checks current, before, and proposed geometry, including route endpoints and technician identity. Portal geometry tests reject malformed payloads and stale date/phase responses. For a local visual check, open a scheduled dispatch day, confirm road-following outbound and return legs, switch to an empty day and back, and inspect an existing run's before/proposed views.

The required nullability gates, warning inventory, intentional-null contracts, and regression commands are documented in [Nullability remediation](docs/nullability-remediation.md).

## Local cutover and rollback

The included Compose database is isolated. Before connecting to a copy of an existing scheduler database, back it up, run Prisma migrations against the copy, and verify Java reads all existing appointments and promised windows. For a local cutover, pause booking, wait for existing Python holds to expire, run the additive migration, and switch the portal engine URL and writes to Java together. Check a fresh booking and existing routes before reopening booking. To roll back, pause booking again, drain Java holds, restore the previous portal engine URL, and keep the additive tables for history. Do not point both engines at the same writable booking database at once.

## Current limitations

- The old fake-data and optimization-test portal pages and APIs have been removed. Their offline libraries remain available; the opt-in sequential test page reuses only pure generation utilities and known locations.
- Manual reassignment is not exposed in the Java dispatch UI. Dispatch optimization preview and guarded apply are the supported route-change path.
- The Nominatim refresh is a manual import and volume swap. The map preparation script does not automate that database swap.
- Offer p95 latency and savings are targets to measure on the intended localhost hardware; no threshold is claimed from the fixture tests.
- Booking reordering, reservation route witnesses, immutable database snapshots, exact oracle fixtures, verification against the active Omaha road graph, and the 5 to 20 technician performance gate are still pending. Keep the baseline search configuration in place until those gates pass.
