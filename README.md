# WaterFlex Technician Scheduler

Local Omaha booking and dispatch prototype. The customer portal is in `web/`, the Spring Boot booking and Timefold service is in `scheduler-service/`, the GraphHopper matrix service is in `routing-service/`, and map preparation is in `infra/`. [UPSTREAMS.md](UPSTREAMS.md) records source versions and revisions.

## Requirements

- Docker Desktop with space for Nebraska and Iowa OSM extracts, a GraphHopper graph, Nominatim, and PMTiles.
- Java 25 for running Maven outside Docker. The checked-in Maven Wrapper downloads Maven 3.9.16.
- Node 22 for portal checks outside Docker.

All published Compose ports bind to `127.0.0.1`. The portal is at `http://localhost:3001`, the scheduler at `:8000`, routing at `:8001`, Nominatim at `:8082`, PMTiles at `:8083`, and PostgreSQL at `:5433`.

## First local setup

1. Start the database: `docker compose up -d db`.
2. Install portal dependencies with `cd web; npm ci; npx prisma generate`. Set `DATABASE_URL=postgresql://waterflex:waterflex@localhost:5433/waterflex`, then run `npx prisma migrate deploy` and `npx prisma db seed`. The included migrations are additive to the copied Prisma schema.
3. Prepare a dated map version: `./infra/prepare-map.ps1 -Version YYYY-MM-DD`. It downloads and checksum checks the state extracts, merges them, and builds tiles and a road graph in the `map-data` volume. The manifest is at `/maps/versions/<version>/manifest.json` in that volume.
4. Import Nominatim into its separate `nominatim-db` volume with `docker compose run -d -e PBF_PATH=/maps/versions/<version>/omaha.osm.pbf nominatim`. Wait for its import to complete and serve search responses, then stop that one-off container. This import takes substantial time and disk space.
5. Activate the prepared map with `docker compose --profile map-import run --rm map-import activate <version>`. Start the application with `docker compose up -d --build db routing-service scheduler-service nominatim tiles web`.

Normal `docker compose up -d` uses existing named volumes and does not repeat imports. A refresh must prepare a new version first, rebuild the Nominatim database separately, pause booking, activate the matching graph and tiles, and restart the map services. The Nominatim volume is currently a single active database, so back it up before a refresh. Do not activate a new map version while an old Nominatim import is serving bookings.

## Views and API

- `/book`: customer address resolution, local pin confirmation, ten-minute reserved offers, refresh, and immediate confirmation.
- `/schedule`: weekly routes, approved time-off blocks, promises, and reasoned cancellation.
- `/dispatch`: route review and overnight optimization preview and apply.
- `/dispatch/availability`: qualifications and date-specific shifts.
- `/dispatch/follow-up`: pending and contacted requests without a promised window, with contacted and resolved actions.
- `/time-off`: local demo technician selector, request history, and staff approval queue. The selector does not authenticate a technician.

`POST /api/book` takes an idempotent `requestId` with customer, address, and service details. It returns `{jobId, offers}` or a pending follow-up reference. Each offered window has a sibling hold in one ten-minute offer set. `/api/book/refresh` atomically replaces that set. `/api/book/select` commits the selected appointment and returns its reference and promised window; the legacy `/api/book/confirm` returns the same appointment for a selected hold. `/api/schedule/appointments` requires a cancellation reason and retains the cancelled record. No email or SMS is sent. These are local views without enforced permissions.

Time-off requests are analyzed in a durable background report. A feasible request whose first date is at least 14 local calendar days away applies automatically. Short-notice feasible requests wait for staff approval. Frozen dates and active reservations block automated approval. Dispatch shift and qualification edits pass through the Java scheduling guard, which rejects edits that would invalidate existing appointments or active holds.

## Verification

With the services and a seeded database available:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25'
./mvnw.cmd verify
cd web
npm ci
npm run typecheck
npm run test:geocode
npm run test:schema-contract
npm run test:optimization
npm run build
```

Pull requests to `main` run the `Build and unit` and `Booking and optimizer integration` checks in `.github/workflows/ci.yml`. The integration job uses its own PostgreSQL 16 service, applies Prisma migrations, seeds it, checks the Java schema contract, and starts `infra/fixture-routing.mjs` with the Java scheduler. The routing fixture accepts only the two Monaco coordinates used by the smoke scripts.

The Monaco fixture smoke scripts are `npm run test:booking:integration`, `npm run test:optimizer:integration`, and `npm run test:time-off:integration`. They require the routing fixture on port 18001 and the scheduler on 18000, pointed at an isolated database. Do not run them against a real customer database. The booking smoke checks reservations, refresh, selection, legacy confirmation, and cancellation. The time-off smoke checks automatic repair and short-notice staff approval. `npm run test:fake-data:integration` invokes the retained fixture generator directly without a public portal route and also mutates the isolated database.

## Local cutover and rollback

The included Compose database is isolated. Before connecting to a copy of an existing scheduler database, back it up, run Prisma migrations against the copy, and verify Java reads all existing appointments and promised windows. For a local cutover, pause booking, wait for existing Python holds to expire, run the additive migration, and switch the portal engine URL and writes to Java together. Check a fresh booking and existing routes before reopening booking. To roll back, pause booking again, drain Java holds, restore the previous portal engine URL, and keep the additive tables for history. Do not point both engines at the same writable booking database at once.

## Current limitations

- The old fake-data and optimization-test portal pages and APIs have been removed. Their offline fixture and analysis libraries remain available for local tests.
- Manual reassignment is not exposed in the Java dispatch UI. Dispatch optimization preview and guarded apply are the supported route-change path.
- The Nominatim refresh is a manual import and volume swap. The map preparation script does not automate that database swap.
- Offer p95 latency and savings are targets to measure on the intended localhost hardware; no threshold is claimed from the fixture tests.
