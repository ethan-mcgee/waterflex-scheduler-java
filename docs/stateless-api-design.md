# Stateless scheduling API for WaterFlex Software

Status: proposal for owner review. Nothing in this document is implemented yet unless it says "exists today". Owner decisions recorded 2026-10-07 are marked "Decided".

## Goal and decision

The scheduler becomes an add-on to WaterFlex Software. WaterFlex Software owns master data (customers, addresses, jobs, appointments, technicians, qualifications, shifts, time off, depots) and the business record of every appointment. The scheduler owns only what it must to keep customer promises safe under concurrency:

- booking holds and reservation arrangements,
- offer sets and proposals it issued, keyed by host IDs and an input revision hash,
- attempt and outcome receipts.

Calculation (solver) and routing are stateless: complete data in, results out. This is the "thin scheduler store" option chosen by the owner on 2026-10-07.

The product has no end-user screens of its own. WaterFlex Software renders all maps and schedules; the scheduler returns data only.

## What exists today

| Piece | State |
| --- | --- |
| Strict daily dataset | `DailyDataset` parses a complete day (technicians, visits, windows, rates, policy versions, indexed locations and directed roads) with duplicate-key, unknown-field, index and road-completeness checks. |
| Strict booking dataset | `BookingDataset` encodes a complete booking snapshot the same way. |
| Calculation service | `solver-service` accepts a hashed `CalculationProtocol.Request` (`DAILY` or `BOOKING`), runs the shared `calculation-engine`, and returns a proposal. No database. Bearer token auth, admission limits, `DELETE /v1/solves/{id}` cancellation. |
| Routing service | `routing-service` turns points into directed legs (`/internal/matrix`, `/internal/legs`) with a routing identity hash. No database; in-memory cache only. |
| Caller | `scheduler-service` reads its own copy of master data from Postgres (about 40 tables), calls routing, calls the engine (embedded by default, remote optional), revalidates the result, persists previews, reservations and appointments, and applies under locks. |
| Geocoding | Self-hosted Nominatim (`NOMINATIM_URL`) over OpenStreetMap, with address matching and validation in the portal. No paid geocoding or matrix API. |

So a request-fed calculation API already works internally. What is missing is a host-facing contract keyed by WaterFlex Software IDs, removal of the scheduler's master data copy, and a commit protocol in which the host owns the business record.

## Target architecture

```
WaterFlex Software  --(snapshot + request)-->  scheduler API  --(dataset)-->  calculation engine (embedded)
        ^                                           |  ^
        |                                           |  +--(points)--> routing service for the job's metro
        +------(proposal / offers / receipts)-------+
                                                    |
                                              thin store (holds, offers, proposals, receipts, route cache)
```

The scheduler API is the only public surface. Calculation and routing stay private.

## Deployment (Decided)

- The scheduler and routing services run on their own VMs on the same private network as the rest of WaterFlex Software. Nothing is exposed to the public internet.
- The solver stays embedded in `scheduler-service` (`SCHEDULER_CALCULATION_MODE=EMBEDDED`, the default today). Moving it out is a configuration change, not a redesign; see "Scaling".
- Each served metro has its own private routing service, loaded with that metro's road graph. Routing sees only coordinates, never client data, so every client in a metro shares that metro's routing service.
- One thin Postgres database serves all clients (see "Multiple clients").

## Public contract v1

All requests carry a host `requestId` (idempotency key) and host IDs only. The tenant is derived from the caller's API token, never from the request body. Money and rates are decimal strings. Times are ISO-8601 instants. Unknown fields, duplicate keys, nonfinite numbers and missing required values are rejected, as `DailyDataset` does today.

### Snapshot

Every calculating call carries a snapshot of the facts it needs:

- technicians: host ID, departure and return location, shift, paid and overtime limits, qualifications, absences, and the host's `lastModified` timestamp per technician-day,
- appointments already committed for those technician-days: host ID, service, location, window, duration, assigned technician, sequence,
- rates and policy settings, or a reference to a versioned settings document,
- locations as coordinates (see "Locations and geocoding").

The scheduler computes a canonical content hash of the snapshot (the input revision). Every proposal and offer records it.

### Endpoints

| Endpoint | Purpose |
| --- | --- |
| `POST /v1/daily/proposals` | Snapshot of one metro day. Returns a proposal ID, proposed routes, unresolved demand, policy decision and diagnostics. Stores the proposal and revision in the thin store. |
| `POST /v1/daily/proposals/{id}/commit` | Host sends its current snapshot (or revision hash plus technician-day timestamps). Scheduler revalidates (cutoff, promises, zero overtime, independent `RouteEvaluator`, holds) and returns a commit receipt with the final assignments and times. 409 if anything changed. |
| `POST /v1/booking/offers` | Snapshot of the booking horizon plus the job to book. Returns up to four offers and creates holds in the thin store. |
| `POST /v1/booking/offers/{id}/select` and `/release` | Convert or release holds, as today. |
| `POST /v1/booking/holds/{id}/confirm` | Host sends its current snapshot; scheduler revalidates against holds and returns the arrangement to write. |
| `POST /v1/repairs/proposals` | Absence repair for a technician-day, same pattern as daily. Repair never adds overtime. |
| `DELETE /v1/requests/{requestId}` | Best-effort cancellation of an in-flight calculation. |

### Latency target (Decided)

`POST /v1/booking/offers` must return offers within 5 seconds end to end, measured at the WaterFlex caller. This matches today's `booking_deadline_ms=5000`. Measuring it across the network is part of the deferred performance work.

### Commit ownership and concurrency (Decided: host last-modified timestamps)

The scheduler cannot lock rows it does not own, so commits use optimistic concurrency on both sides. WaterFlex Software already keeps a last-modified timestamp per technician and day; that timestamp is the concurrency token.

1. Every snapshot includes `lastModified` for each technician-day it covers.
2. A proposal or offer records the timestamps it was computed from.
3. On commit or confirm, the scheduler requires the submitted timestamps to equal the recorded ones, revalidates, and atomically updates its own holds and receipts.
4. The host then writes the business record with a compare-and-set on the same timestamps. If that fails, the host calls release, and the scheduler retains the failed receipt.

Rules WaterFlex Software must guarantee for the timestamp:

- It changes on every change that affects the day: appointment created, edited, cancelled or deleted; shift, availability or time-off change; start or end location change; qualification change.
- A move touches both sides. Moving an appointment from technician A on Tuesday to technician B on Wednesday updates both technician-days.
- A recurring shift template change updates every future day it affects. If that is costly, the host may send a separate shift-template version, and the scheduler checks both.
- It is compared for exact equality at full stored precision, never ordered. Clock skew between servers therefore cannot cause a wrong decision; at worst an unrelated change forces a retry.

This keeps the audit's rule (caller-side locked revalidation before apply) without the scheduler owning appointments. Step 4 makes the host responsible for the final atomic write, which is unavoidable once it owns the data.

## Locations and geocoding (Decided)

The scheduler keeps its local Nominatim geocoding and the current address flow. WaterFlex Software may geocode with Google Maps; the integration handles that as follows:

- Coordinates in the snapshot are authoritative. When a location arrives with latitude and longitude, the scheduler routes from those coordinates as given and does not geocode again.
- Nominatim is the fallback only for locations that arrive with an address and no coordinates.
- Each stored scheduler record notes the coordinate source (`HOST` or `NOMINATIM`), so a bad route can be traced to its input.
- Small travel-time differences between a host coordinate and a Nominatim coordinate for the same address are expected. GraphHopper snaps every point to the nearest routable road either way; a point whose nearest road is not its access road (highway, back alley) can add travel time whichever geocoder produced it.
- Because the product has no maps of its own, Google map display terms do not apply to the scheduler. WaterFlex Software should still confirm that its Google Maps Platform agreement allows Google-geocoded coordinates to be passed to a non-Google routing engine and held in the scheduler's caches. If it does not, the host sends addresses only and the scheduler geocodes with Nominatim.

## Scaling (Decided: embedded first, configuration to scale out)

Apply these levers in order, each only when measurements call for it. Signals: booking response time approaching 5 seconds, `SearchAdmission` rejections, sustained CPU saturation, overnight runs not finishing in their window.

1. **More scheduler replicas behind a load balancer.** Works once no correctness state lives in replica memory (see "Multiple clients").
2. **Separate roles from one artifact.** Run booking replicas (latency sensitive, short solves) apart from batch replicas (overnight and daily proposals, up to 20 s of full CPU per solve). Same JAR, a role setting chooses which endpoints and schedules a replica serves. This removes the most likely CPU contention without a remote hop.
3. **Remote solver pool.** Switch `SCHEDULER_CALCULATION_MODE=REMOTE` and scale `solver-service` independently. Prerequisites (roadmap S1): https with service tokens, cancellation that works across replicas, and an explicit compatible-version set instead of the byte-identical engine requirement. The remote round trips (up to three per booking search today) must fit the 5 second budget.

Routing scales per metro by adding replicas of that metro's routing service behind its own internal address.

## Multiple clients (Decided: one shared database, tenant on every row)

### What is stored

Client schedules are not stored in the scheduler. They stay in WaterFlex Software. The thin store holds only:

| Data | Typical lifetime |
| --- | --- |
| Booking holds and reservation arrangements | Minutes, until confirmed, released or expired |
| Offer sets and offers | Hours, until selected or expired |
| Daily and repair proposals | Until committed, rejected or stale |
| Attempt, commit and outcome receipts | Retained for audit on a fixed retention period, then purged |
| Road route cache | Up to 30 days (see "Route cache") |

### Tenant isolation

- One Postgres database and one set of tables for all clients. Every client-owned table has a `tenant_id` column, and every primary key, unique constraint and index starts with it, for example `(tenant_id, technician_id, service_date)`. Host IDs are unique only within a client, so two clients can both have technician `42`.
- Each client has its own API tokens. The server maps the token to `tenant_id`; requests cannot choose a tenant.
- Postgres row-level security is the second guard: each transaction sets `app.tenant_id`, and policies restrict every client-owned table to that tenant. A missed filter in application code then fails closed instead of reading another client's rows.
- Schema-per-client or database-per-client gives stronger isolation but multiplies migrations and operations. Keep it as an option for a client that contractually requires it. Because every row already carries `tenant_id`, that client's rows can be moved into a dedicated database without an API change.

### Several scheduler replicas on one database

- Correctness lives in the database. Holds are protected by row locks and unique constraints on `(tenant_id, technician_id, service_date)` and by the timestamp comparison inside one transaction. Any replica can serve any request, so the load balancer needs no sticky sessions.
- Scheduled work (overnight proposals, cache cleanup, expiry sweeps) is claimed through the database with a lease row taken by `SELECT ... FOR UPDATE SKIP LOCKED`, so each unit runs on exactly one replica and is picked up by another if that replica dies.
- In-memory state that must move or be accepted before running several replicas:
  - booking search cancellation registry (`BookingSearchControl`): move to the database or route cancels by request ID,
  - admission counters (`SearchAdmission`): per replica is acceptable if limits are set per replica; a global limit needs a shared counter,
  - the route cache memory tier: per replica is correct as is (see below).
- Database scale: the rows are small and short-lived, so one primary with connection pooling (PgBouncer) serves many clients. If one database is ever outgrown, shard by tenant with a tenant-to-database map.

## Route cache

Routing results are cached in four tiers today. None of them is a source of truth: every entry is recomputable from the road graph, and every entry is bound to a routing identity so a changed graph can never be mixed with old legs.

| Tier | Where | Key | Lifetime and size | Shared by |
| --- | --- | --- | --- | --- |
| 1. In-flight dedup | `DirectedLegFlights` in `scheduler-service` | routing identity + directed pair | Until the routing call finishes; 2 workers, queue of 16 | Concurrent requests in one replica |
| 2. Scheduler memory | `RoadClient` LRU map | directed coordinate pair + routing identity | `routing.cache.ttl-minutes` (60) and `routing.cache.max-entries` (100,000); cleared when the routing identity changes | One replica |
| 3. Scheduler database | `road_route_cache` table | `(originKey, destinationKey, profile, mapVersion)` | Read only if fetched in the last 30 days; weekly cleanup (`routing.cache.cleanup-cron`) deletes older rows and trims beyond 500,000 rows | All replicas |
| 4. Routing memory | `MatrixController` LRU map in `routing-service` | directed coordinate pair | 60 minutes, 100,000 entries | All callers of that routing replica |

How a lookup works:

1. Coordinates are rounded to 5 decimal places (about 1 m) to form keys, so the same address always produces the same key.
2. `RoadClient` first asks routing `/health` for the current routing identity (a hash of the graph and map version). If it differs from the last one seen, tier 2 is cleared.
3. Tier 2 is checked, then tier 3 in batches of 256 pairs. Database hits are copied into tier 2.
4. Remaining pairs go through tier 1, so two requests needing the same leg trigger one routing call. Routing answers from tier 4 or computes the leg with GraphHopper.
5. New legs, including "unroutable" results, are written to tier 3 in a separate short transaction and to tier 2. Cache writes never join the caller's business transaction.

Implications for the target design:

- **Per metro.** With one routing service per metro, the scheduler needs a metro-to-routing-URL map instead of the single `routing.url` today, and `RoadClient` becomes one client per metro. Each metro's routing identity differs, so tier 2 and tier 3 entries for different metros never collide. Clearing tier 2 on identity change must become per metro, so a graph update in one metro does not flush the others.
- **Across clients.** Route legs are not tenant data and are shared across clients in a metro, which is where most of the cache benefit comes from. `road_route_cache` therefore has no `tenant_id`. It does contain customer coordinates at about 1 m precision, so it is treated as location data: reachable only by the scheduler, never exposed through the API, and purged within 30 days so deleted customers age out.
- **Across replicas.** Tier 2 is per replica by design; tier 3 makes a new or restarted replica warm immediately. No change is needed for correctness.
- **Health call per lookup.** Each lookup currently makes one `/health` round trip to confirm the routing identity. Under heavy booking load, cache the identity for a few seconds per metro; the identity is still verified on every routing response, so a stale cached identity only causes a retry.
- **Cleanup on every replica.** The weekly cleanup runs on every replica. It is idempotent, but it should take the scheduled-work lease. The size trim deletes at most 50,000 rows per run, so if the table grows faster than that per week the 500,000 row cap is not enforced; the trim should loop until under the cap.
- **Moving tier 3 into routing.** An alternative is to give each metro's routing service its own persistent cache and drop `road_route_cache` from the scheduler, making the scheduler database hold no coordinates at all. It costs routing its statelessness. Keep tier 3 in the scheduler database for now and revisit if location-data retention requirements tighten.

## Thin store

Keep: `slot_hold`, `reservation_arrangement`, `reservation_dependency`, `booking_offer_set`, `booking_offer`, `booking_search_request`, `optimization_run` (as proposals), `daily_calculation_attempt`, `overnight_optimization_attempt`, `road_route_cache`. Add `tenant`, `tenant_id` and host-ID columns and the row-level security policies. Remove: customer, address, job, appointment, technician and related master data tables, after migration.

## Operations that move to the host

- Overnight optimization: the host calls `POST /v1/daily/proposals` per metro-day on its schedule and commits accepted proposals. The scheduler no longer enumerates metros.
- Time off and shift edits: the host owns them and sends the result in later snapshots. Repair is requested through `/v1/repairs/proposals`.
- The current `web/` portal becomes an admin and demo client of the public API, or is retired.

## Migration

1. Add the public endpoints alongside the existing ones, backed by a request-fed path that builds `DayPlan` and `BookingSnapshot` from the snapshot instead of the database.
2. Run both paths on the same days and compare proposals, offers and validation outcomes.
3. Move holds and offers to host IDs; cut over the host; retire the master data tables.

## Remaining stateless gaps in calculation and routing

- Cancellation state is in memory per solver instance. With several replicas, route `DELETE` by request ID consistently, or accept that a cancel may miss and the solve stops at its own deadline (at most 20 s daily, 120 s booking).
- Remote results must come from the byte-identical engine artifact (`CalculationProtocol.Response.match`). This forces scheduler and solver to deploy together, which is acceptable while they ship as one product.
- Routing has no authentication and must stay on the private network.
- Remote mode has not been performance-tested; that study is deferred with the rest of the performance work.

## Resolved questions

1. Concurrency token: WaterFlex Software's last-modified timestamp per technician-day (see "Commit ownership and concurrency").
2. Geocoding: host coordinates are authoritative; local Nominatim is the fallback (see "Locations and geocoding").
3. Tenancy: one shared database with `tenant_id` on every row and row-level security, from day one.
4. Repair never adds overtime; the dispatcher overtime approval flag was removed (see `docs/scheduler-policy.md`).
5. Booking latency: offers within 5 seconds end to end.

## Open questions

1. Does WaterFlex Software's Google Maps Platform agreement allow passing Google-geocoded coordinates to the scheduler and caching them for up to 30 days?
2. What audit retention period applies to receipts?
3. Does any client require a dedicated database?
1. Does WaterFlex Software keep a version (or updated-at) per technician-day that can serve as the optimistic concurrency token? If not, it needs one.
2. Who geocodes addresses: WaterFlex Software, or the scheduler using the existing validated geocoding path?
3. Is one deployment per customer acceptable, or does the API need multi-tenant isolation from day one?
4. Resolved: repair never adds overtime. The dispatcher overtime approval flag was removed (see `docs/scheduler-policy.md`).
5. What latency target applies to `POST /v1/booking/offers` once the snapshot crosses the network (today's budget is five seconds end to end)?
