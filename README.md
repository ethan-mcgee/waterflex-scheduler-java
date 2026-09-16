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

- `/book`: customer address resolution, local pin confirmation, offers, hold, and confirmation.
- `/schedule`: day routes and promises.
- `/dispatch`: route review and overnight optimization preview and apply.
- `/dispatch/availability`: qualifications and date-specific shifts.
- `/dispatch/follow-up`: pending requests without a promised window.

`POST /api/book` takes an idempotent `requestId` with customer, address, and service details. It returns `{jobId, offers}` or a pending follow-up reference. `POST /api/book/select` takes `{jobId, offerId}` and creates one ten-minute hold. `POST /api/book/confirm` takes `{holdId}` and returns an appointment reference and promised window. Offers are not reservations. No email or SMS is sent. These are local views without enforced permissions.

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
npm run build
```

The small Monaco fixture smoke scripts are `web/scripts/smoke-java-booking.ts` and `web/scripts/smoke-optimizer.ts`. They require a fixture routing service on port 18001 and the scheduler on 18000, pointed at the isolated local database. Do not run them against a real customer database. The booking smoke checks idempotent confirmation and competing holds. The optimizer smoke checks preview, apply, and preservation of the stored promised window.

## Local cutover and rollback

The included Compose database is isolated. Before connecting to a copy of an existing scheduler database, back it up, run Prisma migrations against the copy, and verify Java reads all existing appointments and promised windows. For a local cutover, pause booking, wait for existing Python holds to expire, run the additive migration, and switch the portal engine URL and writes to Java together. Check a fresh booking and existing routes before reopening booking. To roll back, pause booking again, drain Java holds, restore the previous portal engine URL, and keep the additive tables for history. Do not point both engines at the same writable booking database at once.

## Current limitations

- The historical fake-data and optimization-test tools copied from the old portal still call Python-only routes. They are not part of this Java MVP.
- Manual reassignment is not exposed in the Java dispatch UI. Dispatch optimization preview and guarded apply are the supported route-change path. A legacy API route remains in the copied source and should not be used.
- The Nominatim refresh is a manual import and volume swap. The map preparation script does not automate that database swap.
- Offer p95 latency and savings are targets to measure on the intended localhost hardware; no threshold is claimed from the fixture tests.
