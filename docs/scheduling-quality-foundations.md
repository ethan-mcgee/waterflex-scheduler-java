# Scheduling quality foundation

Historical checkpoint: this document describes the initial quality foundation before the consolidated scheduling policy. Its cost-only acceptance and outstanding-work statements do not describe the current implementation. See [current policy and operations](scheduler-policy.md) and [acceptance checklist](scheduler-plan-checklist.md).

This change strengthens the existing local scheduler without changing the booking or Timefold search configuration. The ten weekday horizon, two hour customer windows, ten minute offer expiry, local cutoff, and dispatcher approval policy remain in force.

## Cost and acceptance

The route scorer and `RouteEvaluator` independently calculate paid minutes between home departure and return, regular minutes as paid minus overtime, directed road meters, and the arrival of each visit. They sum those measures across the evaluated plan, then price the plan in cents as:

`round(regular_minutes * regular_hourly_dollars * 100 / 60 + overtime_minutes * overtime_hourly_dollars * 100 / 60 + meters / 1609.344 * mileage_dollars_per_mile * 100)`

Java `Math.round` supplies the rounding rule. Travel seconds are buffered and rounded up to minutes per leg before route timing. A booking's displayed incremental cost is the difference between independently evaluated route costs, converted to dollars. The initial pre-offer candidate supplies that cost even when additional sibling reservations change feasibility. Ties sort by earlier window, technician ID, then insertion position.

`RouteEvaluator` now rejects duplicate, missing, and unknown appointment IDs, including a substitution that leaves the total count unchanged. Booking decisions use that validator. An ordinary optimization preview requires an independently feasible baseline, identical scorer and validator cents, an independently feasible proposal, identical proposal cents, and a strict cost improvement. Apply still checks the current schedule, cutoff, holds, routing identity, configuration fingerprint, and independent feasibility.

## Routing contract

`GET /health` on the routing service returns `ready`, `mapVersion`, `routingIdentity`, `profile`, and `engineVersion`. The identity hashes the prepared merged OSM checksum and the pinned car profile, encoded values, GraphHopper version, and snap limit. When the prepared manifest is unavailable, the map version supplies the map component of the hash. Replacing a graph requires restarting the routing service so GraphHopper loads the new graph and identity.

`POST /internal/matrix` accepts `origins`, `destinations`, and optional `expectedRoutingIdentity`. It returns directed `legs`, `mapVersion`, and `routingIdentity`. Missing or invalid coordinates return HTTP 400; unavailable graphs return HTTP 503; mismatched identities return HTTP 409. A leg that cannot be routed is `{ "routable": false, "seconds": null, "meters": null }`. A scheduler rejects malformed dimensions, noninteger or negative leg values, and identity changes.

`POST /internal/route` accepts ordered `points` and `expectedRoutingIdentity`. It returns each road leg's GeoJSON `LineString`, seconds, meters, and routing identity. A disconnected or unsnappable route returns HTTP 422. `GET /v1/dispatch/geometry?metro_id=...&date=YYYY-MM-DD` returns a GeoJSON feature collection for current routes. Add `run_id=...&phase=before` or `phase=after` for an optimization run. Features identify technician, working interval, and directed leg. The scheduler divides routes at approved absences, with a separate home departure and return for each interval. The portal uses this endpoint through `/api/dispatch/geometry`; failed geometry leaves markers visible and no route line.

The scheduler normalizes coordinates to five decimal places before both cache lookup and routing requests. It deduplicates points and requests uncached directed legs in blocks of at most 64 origins and 64 destinations. The in-memory limits default to 100,000 entries and 60 minutes in each service. Scheduler settings are `routing.cache.max-entries` and `routing.cache.ttl-minutes`; routing service settings have the same names. The persistent cache stores the identity in its existing `mapVersion` column, which naturally separates legacy map-version rows. A weekly cleanup removes entries older than 30 days and up to 50,000 entries over the 500,000 row cap. Any excess beyond one batch is removed on subsequent runs.

## Migration and verification

The additive migrations index persistent cache age and add `optimization_run.baselineAssignments`. New runs save both assignment orders, coordinates, and planned arrivals. Old runs have an empty baseline assignment array, so their before geometry cannot be shown; create a new preview to compare road routes.

`routing-service/src/test/resources/fixture-roads.osm` is a synthetic graph authored for this repository. Its SHA-256 is `3ffcd8c6989bf9371df3959caaf2a721367dd8bc2f3a37d80835409ad614a4a2`; the test verifies those bytes before importing them with GraphHopper 11. It checks one-way asymmetry, matrix and geometry distance agreement, invalid coordinates, snapping rejection, and identity rejection after a manifest change. This synthetic graph does not replace a test against the prepared Omaha graph.

Local checks run for this change: Maven `verify`, TypeScript `typecheck`, production portal build, Prisma schema validation, and fixture routing requests for health, directed matrix, geometry, and identity mismatch. Hosted CI passed both jobs, including the isolated PostgreSQL schema contract and booking, optimizer, geometry, and time-off smoke tests. Docker Desktop was not running locally, so the active Omaha graph and workstation latency remain unverified here.

This is the first stage of the broader scheduling quality plan. Reservation route witnesses, snapshot conflict retries and deadlines, booking route moves, exact oracle datasets, a representative Omaha road graph check, benchmark provenance, performance acceptance, and shadow reliability evaluation remain release gates. Do not enable a new algorithm configuration from this stage alone.
