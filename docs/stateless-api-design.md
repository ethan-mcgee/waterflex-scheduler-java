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
| Routing service | `routing-service` turns points into directed legs (`/internal/matrix`, `/internal/legs`) with a routing identity hash. No database; in-memory cache only. Computation endpoints require the `ROUTING_AUTH_TOKEN` bearer token; `/health` is open. |
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

The machine-readable contract is [docs/api/openapi-v1.yaml](api/openapi-v1.yaml) (OpenAPI 3.1, draft). Each operation is marked `x-implementation: implemented` or `planned`; `web/scripts/openapi-v1.test.ts` validates every example against its schema and fails if the implemented operations differ from the `/api/v1` routes the scheduler serves. The Java types in `dev.waterflex.scheduler.api` mirror every object schema (same fields, same required fields), read request bodies strictly (unknown fields, duplicate keys, numeric or noncanonical money, missing values and dangling snapshot references all fail), and `PublicApiContractTest` keeps them in step with the spec.

`RequestDay` builds the solver input for one service date from a daily request instead of the database: the same road point keys, the same technician and appointment order, absent legs treated as unroutable, coordinates used as sent and only address-only locations geocoded. Every route allows zero overtime minutes. Each appointment carries its current `plannedStart`, which proposals report moves against. `DailyAttemptDatabaseIT` checks that a request describing a seeded day produces exactly the solver input the database path produces.

`DailyPreparation` routes a daily request through its own metro's routing service (`MetroRouting`, configured by `ROUTING_METRO_URLS`) and records the routing identity the legs came from. An unconfigured metro fails before any geocoding and never falls back to the portal's single `ROUTING_URL`. A routing identity change while the legs are fetched fails the preparation, as in the database path.

`POST /api/v1/daily/proposals` exists today (`DailyProposals`). It parses strictly, claims the `requestId` in the thin store, applies the 6 a.m. cutoff in the snapshot's time zone, routes through the metro's service, and runs the same daily calculation and preview decision as the portal path (`OptimizationService.createPreview`), inside the same 20 second operation and admission limits. The decision is `IMPROVED` when the calculation's proposal is accepted; `REJECTED_BY_POLICY` when it found a valid plan that policy rejected; otherwise `NO_IMPROVEMENT`, with the current routes and a `reason`. A technician-day with a location that cannot be resolved (see "Locations and geocoding") is left out and listed in `skippedTechnicianDays`. The proposal is stored with the host timestamp of every technician-day in the snapshot and answered with 201. Failures that repeat on retry (invalid facts, unknown metro, frozen date) are stored and replayed; routing outages (503), admission limits (429) and deadline overruns (503) release the claim so a retry runs again. The fairness budget is each client's own choice and arrives with every request (`Snapshot.policy.fairnessBudget`); the remaining policy settings (booking window threshold, utilization threshold, booking deadline) are scheduler configuration and still come from `omaha_setting`, as in the portal path.

`POST /api/v1/repairs/proposals` exists today (`DailyProposals.repair`). It is the daily path with the absence added to the absent technician's day (`PlanCopies.withAbsence`), calculated in the portal's repair mode: the decision is `IMPROVED` only when every appointment is placed, every constraint holds and no overtime is added, and otherwise the current routes are returned unchanged with reason "Repair infeasible". The snapshot holds the absence's date only and must include the absent technician-day; if that day cannot be located the request is a 422, since a skipped day cannot be repaired. The input revision covers the absence as well as the snapshot. The proposal is stored and committed exactly like a daily proposal (request operation `REPAIR_PROPOSAL`). As with daily proposals, booking holds on the date are not consulted: a committed repair that changes a route holding a hold loses that date's holds at their next use (reconciliation rule 3). The portal path instead skips a repair while holds are active.

Public routes live under `/api/v1/`, separate from the scheduler's internal `/v1/` routes used by the admin portal. Every `/api/v1/` call must send `Authorization: Bearer <tenant token>` (exists today, with `GET /api/v1/whoami` returning the token's tenant). All requests carry a host `requestId` (idempotency key) and host IDs only. The tenant is derived from the caller's API token, never from the request body. Money and rates are decimal strings. Times are ISO-8601 instants. Unknown fields, duplicate keys, nonfinite numbers and missing required values are rejected, as `DailyDataset` does today.

### Snapshot

Every calculating call carries a snapshot of the facts it needs:

- technicians: host ID, departure and return location, shift, paid and overtime limits, qualifications, absences, and the host's `lastModified` timestamp per technician-day,
- appointments already committed for those technician-days: host ID, service, location, window, duration, assigned technician, sequence, current planned start,
- rates, and the client's policy choices (today its fairness budget),
- locations as coordinates (see "Locations and geocoding").

The scheduler computes a canonical content hash of the snapshot (the input revision). Every proposal and offer records it.

### Endpoints

| Endpoint | Purpose |
| --- | --- |
| `POST /api/v1/daily/proposals` | Snapshot of one metro day. Returns a proposal ID, proposed routes, unresolved demand, policy decision and diagnostics. Stores the proposal and revision in the thin store. |
| `POST /api/v1/daily/proposals/{id}/commit` | Host sends its current timestamp for every technician-day the proposal covers. Equal timestamps mean the facts the proposal was computed and validated from are unchanged, so the scheduler checks the 6 a.m. cutoff again and returns a commit receipt with every assignment on the proposal's routes. 409 if anything changed; nothing is changed then. |
| `POST /api/v1/booking/offers` | Snapshot of the booking horizon plus the job to book. Returns at most the request's `offerLimit` offers (1 to 4, the client's own cap) and holds each for 10 minutes in the thin store. The portal's `booking.offer.limit` does not apply. |
| `POST /api/v1/booking/offers/{id}/select` and `/release` | Convert or release holds, as today. |
| `POST /api/v1/booking/holds/{id}/confirm` | Host sends its current snapshot of the hold's date; scheduler reconciles it with its holds, turns the selected hold into the job's appointment, and returns the appointments to write. |
| `POST /api/v1/repairs/proposals` | Absence repair for a technician-day, same pattern as daily. Repair never adds overtime. Served (`DailyProposals.repair`). |
| `DELETE /api/v1/requests/{requestId}` | Best-effort cancellation of an in-flight calculation. |

### Contract changes

The contract is a draft until the first endpoint that calculates ships, but every change WaterFlex Software must act on is listed here and in the spec's description.

| Change | Since | What WaterFlex Software does |
| --- | --- | --- |
| `TechnicianDay.absences` is required | #88 | An empty list when the technician has no absence that day. A missing list is rejected, never read as "no absences". |
| `Appointment.plannedStart` is required | #89 | The appointment's currently planned arrival, as an instant. The solver records it as the appointment's original start, and proposals report moves against it. A missing value is rejected, never guessed. |
| `DailyProposal.reason` and `DailyProposal.skippedTechnicianDays` added | #96 | Reads `reason` to see why the scheduler decided as it did. Leaves every technician-day in `skippedTechnicianDays` exactly as it is (it is not in `routes`), and sends coordinates for the location its `message` names to include it next time. |
| `Snapshot.policy.fairnessBudget` is required | #97 | Sends each client's own fairness budget: "0" for none, or a share such as "0.02" (2 percent) by which the daily plan's cost may rise to spread work more evenly. A missing value is rejected, never assumed. |
| Repeated and reused `requestId` | #96 | Retries with the same `requestId` and the same body after a timeout or a 429/503; the stored answer is replayed. Uses a new `requestId` for different facts: reusing one gets 400. |
| Daily commit served; 409 split into `STALE` and `NOT_COMMITTABLE`; 422 after the cutoff | #98 | Sends the current `lastModified` of every technician-day the proposal covers, skipped ones included (a missing or extra one is a 400). On 200, writes every assignment in the receipt with a compare-and-set on the receipt's timestamps. On `STALE`, requests a new proposal. On `NOT_COMMITTABLE`, does nothing: the proposal was already committed (the message names the receipt) or its decision is not `IMPROVED`. On 422, the day's routes are frozen. |
| Booking horizon at most 21 dates; `job.id` must differ from every appointment and technician ID | #99 | Sends a horizon of 1 to 21 dates. Uses a job ID that is not already an appointment or technician ID in the snapshot; a clash is a 400. |
| Repair proposals served | #104 | Sends the absence and the snapshot of its date. Commits an `IMPROVED` repair with the daily commit endpoint, then records the absence itself. Any other decision means the absence cannot be covered without overtime or leaving an appointment unplaced, so a dispatcher resolves it. |
| Address-only locations can be located (deployment option `geocoding.mode=NOMINATIM`) | #103 | Nothing required. Sending coordinates is still best. With the option on, an address-only location is located only as an exact house; a lookup outage is a 503 `ROUTING_UNAVAILABLE` to retry. |
| Confirm sends `{requestId, snapshot}`; a confirmed job's appointment ID is its job ID | #102 | Selects an offer, then confirms its hold with the current snapshot of the hold's metro and date (no other dates). Writes every appointment in the receipt with a compare-and-set on the listed technician-day timestamps, creating the job's appointment under the job's ID. A 409 `HOLD_UNAVAILABLE` (expired, lost, not selected, or the day would need overtime or repair) or a failed write means search again. Confirming again returns the same receipt. |
| `BookingOffersRequest.offerLimit` is required | #101 | Sends each client's own cap on offers per search, 1 to 4. A missing or out-of-range value is a 400, never assumed. |
| Select and release served; new error `HOLD_UNAVAILABLE` | #101 | Treats a 409 `HOLD_UNAVAILABLE` from select as "search again": the hold expired or ended, or another offer in the set was selected. Releases a set it no longer needs; a release is a 200 even when nothing was still held. |
| Booking offers served; `OfferSet.skippedTechnicianDays` added | #100 | Reads `skippedTechnicianDays`: nothing is offered on those technician-days until their locations have coordinates. Starts the horizon after any date already past 6 a.m. local (a frozen date is a 422). Treats a new search for a job as replacing that job's earlier offers, which are no longer held. Retries a 429 or 503 with the same `requestId`. |

### Latency target (Decided)

`POST /v1/booking/offers` must return offers within 5 seconds end to end, measured at the WaterFlex caller. This matches today's `booking_deadline_ms=5000`. Measuring it across the network is part of the deferred performance work.

### Commit ownership and concurrency (Decided: host last-modified timestamps)

The scheduler cannot lock rows it does not own, so commits use optimistic concurrency on both sides. WaterFlex Software already keeps a last-modified timestamp per technician and day; that timestamp is the concurrency token.

1. Every snapshot includes `lastModified` for each technician-day it covers.
2. A proposal or offer records the timestamps it was computed from.
3. On commit or confirm, the scheduler requires the submitted timestamps to equal the recorded ones, revalidates, and atomically updates its own holds and receipts.
4. The host then writes the business record with a compare-and-set on the same timestamps. If that fails, the host calls release, and the scheduler retains the failed receipt. A daily proposal has nothing to release: it stays committed with its receipt, and the host asks for a new proposal from its current facts.
5. A proposal is committed at most once. Its row is locked for the commit, and the receipt table is unique per proposal as a second guard.

Rules WaterFlex Software must guarantee for the timestamp:

- It changes on every change that affects the day: appointment created, edited, cancelled or deleted; shift, availability or time-off change; start or end location change; qualification change.
- A move touches both sides. Moving an appointment from technician A on Tuesday to technician B on Wednesday updates both technician-days.
- A recurring shift template change updates every future day it affects. If that is costly, the host may send a separate shift-template version, and the scheduler checks both.
- It is compared for exact equality at full stored precision, never ordered. Clock skew between servers therefore cannot cause a wrong decision; at worst an unrelated change forces a retry.

This keeps the audit's rule (caller-side locked revalidation before apply) without the scheduler owning appointments. Step 4 makes the host responsible for the final atomic write, which is unavoidable once it owns the data.

### Booking (Decided 2026-10-08)

The owner chose three things for the booking endpoints: confirm sends the day's snapshot, a search covers exactly the dates WaterFlex Software asks for, and an offer may move other customers' appointments to make room.

Built so far: booking search facts from a request instead of the database (`api/RequestBooking`, proven equal to the database loader's facts for the same day in `DailyAttemptDatabaseIT`), and `POST /api/v1/booking/offers` (`api/BookingOffers`, reconciliation in `api/BookingReconciliation`). `POST /api/v1/booking/offers/{offerId}/select` and `/release` (`api/BookingHolds`, `BookingStore.select` and `release`). `POST /api/v1/booking/holds/{holdId}/confirm` (`api/BookingConfirm`, `BookingStore.settle`).

Select and release run no search. Each locks the set's days in the one lock order, then the set, edits the stored day states (an ended hold leaves the arrangement; its moves stay) and answers. Selecting keeps the chosen hold with its original expiry, releases its siblings, and marks the offer and set `SELECTED`; selecting it again returns the same hold. It is a 409 `HOLD_UNAVAILABLE` when the hold expired or ended, or the set was released, superseded or already selected another offer. Releasing ends every hold in the set still held or selected and is a 200 even when nothing was still held. Both are 404 for an offer the tenant does not have, and every answer is stored and replayed for a repeated `requestId`. A new search for the job also ends a selected hold.

`POST /api/v1/booking/offers` parses strictly and claims the `requestId` like the daily endpoint. It refuses (422) a horizon date already past its 6 a.m. cutoff, an unknown metro, an unlocatable job and contradictory orders. It reconciles each horizon date's stored state with the snapshot, ends the job's holds on any date, routes the days, loses the holds of any date whose arrangement no longer fits, and runs the portal pipeline's insertion and bounded refinement (`booking.search.variant`, default `BOUNDED`) within the policy's booking deadline and the booking admission limit. Offers are validated independently and prepared in the common arrangement as the portal does (`ReservationOffers`), then published atomically: each touched day is locked in one order and must still have the version the search read. If another booking changed a day meanwhile, the search runs once more within the same deadline, then answers 429. Routing outages and a deadline with nothing found answer 503 and release the claim; offers found before the deadline are published with `searchComplete: false`.

**Horizon.** A search covers every date from `horizon.firstDate` to `horizon.lastDate`, at most 21 dates, including dates with no technician-day. There is no automatic overflow: when nothing is offered, WaterFlex Software asks again with later dates. The scheduler's own calendar (ten weekdays, then five overflow weekdays) stays for the portal only. The booking engine takes the horizon as declared (`BookingSnapshot.horizon`), and the remote solver receives it in the dataset.

**Facts.** Mapped like the daily path: the shift and absences as instants, `maxPaidMinutes` as the daily limit, zero overtime minutes, qualifications as services. An appointment is its own job. A technician-day's schedule version is its `lastModified` in nanoseconds since 1970, so a stored arrangement can tell exactly which technician-days changed. A technician-day's `sequence` and `plannedStart` must put its appointments in the same order (the engine keeps that order and WaterFlex Software writes both); otherwise the request is refused, never reordered. A technician-day with a location that cannot be located is left out of the search and reported, as in the daily path. A job location that cannot be located refuses the request (422), since no offer can be made for it.

**Moves and the common arrangement.** Offers come from the bounded search the portal uses (`booking.search.bounded`), which may reorder or reassign existing appointments. As in the portal, each metro-day has one stored common arrangement: every technician's route with the host's appointments and every active hold, including moves that pending offers depend on. It is the scheduler's own state, kept in the thin store with the host timestamps it was built from and the routes WaterFlex Software is expected to have.

**Reconciling with a new snapshot.** Each search and confirm compares the snapshot with the stored arrangement, technician-day by technician-day:

1. A technician-day whose `lastModified` is unchanged is as stored.
2. A changed technician-day whose appointments, in order, are the ones the scheduler expects (for example, after WaterFlex Software wrote a confirm receipt) is accepted, and its new timestamp recorded.
3. Any other changed technician-day (a dispatcher added, removed or moved an appointment) replaces its stored route only when that route has no hold and no pending move. Otherwise every hold on that date is lost: a later select or confirm of it is refused, and the date's arrangement restarts from the host's routes.
4. The whole arrangement is then evaluated with the snapshot's current facts (windows, durations, shifts, absences). If it is no longer feasible, every hold on that date is lost the same way.

Nothing is guessed: a hold either still fits the current facts exactly as stored, or it is lost and reported.

**Confirm.** The confirm sends the snapshot of the hold's date. After reconciliation the hold must still be active. The scheduler replaces it with the job, releases the job's other holds, checks the result is feasible and adds no overtime, and returns a receipt with every appointment on each technician-day whose route changes (technician, order or planned times), the new job included under its job ID, plus those technician-days' timestamps for the host's compare-and-set. Applying the receipt brings WaterFlex Software's routes to the common arrangement without its remaining holds, which is also what the scheduler then expects (rule 2 above). Moves that another pending offer depended on are applied at that point too, as the portal does.

As built: only a selected hold can be confirmed, and the snapshot may hold technician-days of the hold's date only (another metro or date is a 400). The host's day is built exactly as a search for the held job on that one date would build it, then reconciled with the stored day. A hold found lost, or a reconciled day that no longer fits the current facts, is a 409 `HOLD_UNAVAILABLE`, and the reconciliation is recorded (the hold becomes `LOST`). The hold becomes the job's appointment with the portal's checks (`ReservationTransition`): the day must stay feasible, the job's route must have no overtime, and the day may not add overtime; a failure is a 409 too. The receipt lists each technician-day whose appointments, order or planned times differ from the host's, and the stored day then expects exactly those routes, so the host writing the receipt is accepted by rule 2. The day is locked and its version checked before anything is written; a concurrent change retries once within the booking deadline, then answers 429. The offer and its set become `CONFIRMED` and the receipt is stored (`api_booking_receipt`), so confirming again with a new `requestId` returns the same receipt.

**Holds.** Every offer holds its slot for 10 minutes (the portal's expiry). Selecting an offer keeps its hold and releases the others in its set. Releasing an offer releases its whole set. A new search for the same job supersedes the job's earlier offer set. Expired holds are dropped from the arrangement on its next use.

## Locations and geocoding (Decided)

The scheduler keeps its local Nominatim geocoding and the current address flow. WaterFlex Software may geocode with Google Maps; the integration handles that as follows:

- Coordinates in the snapshot are authoritative. When a location arrives with latitude and longitude, the scheduler routes from those coordinates as given and does not geocode again.
- Nominatim is the fallback only for locations that arrive with an address and no coordinates.
- Each stored scheduler record notes the coordinate source (`HOST` or `NOMINATIM`), so a bad route can be traced to its input. Not built yet: stored proposals and holds keep the coordinates but not their source.
- As built (`api/AddressLocation`, `api/NominatimGeocoder`, `api/StreetAddress`): `geocoding.mode` is `COORDINATES_REQUIRED` (the default, which never locates an address) or `NOMINATIM` (`NOMINATIM_URL`). The Java matcher is a port of the portal's (`web/lib/geocode.ts`, `web/lib/streetNormalization.ts`) and passes the same cases: ZIP, house number and street decide, USPS street spellings, units, ordinals and route names compare by meaning, and a mailing city is accepted when the ZIP agrees. Unlike the portal, where a person can confirm an approximate pin, the API accepts only an exact house: a line without a house number, a street-level result, or two different houses for one address is not located. A Nominatim outage, timeout (8 seconds per lookup) or malformed answer is a 503 `ROUTING_UNAVAILABLE` ("Address lookup ...") that releases the claim, since the address is not at fault.
- Small travel-time differences between a host coordinate and a Nominatim coordinate for the same address are expected. GraphHopper snaps every point to the nearest routable road either way; a point whose nearest road is not its access road (highway, back alley) can add travel time whichever geocoder produced it.
- Because the product has no maps of its own, Google map display terms do not apply to the scheduler. WaterFlex Software should still confirm that its Google Maps Platform agreement allows Google-geocoded coordinates to be passed to a non-Google routing engine and held in the scheduler's caches. If it does not, the host sends addresses only and the scheduler geocodes with Nominatim.

### Address quality (Decided)

An address reaches the solver only after it has been located with certainty. The scheduler never guesses a location.

- **Formatting is standardized before matching.** Street types and their abbreviations (Street and St), directions (North and N), periods, capitals and spacing, state names and codes, numbered streets (First and 1st), unit designators on the street line (Apt 4, #4, Suite 200), highway and county road names (US-6, US Hwy 6, Co Rd 12), Saint and St at the start of a name, and half or ranged house numbers (123 1/2, 123-125) must all compare as equal when they mean the same place. Any of these the scheduler's address handling does not cover is a defect to fix.
- **ZIP, house number and street decide a match.** All three must agree. The city may differ when the ZIP agrees, because many addresses carry a mailing city that is not the municipality the map uses.
- **An uncertain match is rejected, never used.** The rejection names the location and the reason (house number not in the map, street differs, ZIP differs). WaterFlex Software can then send coordinates for that one location.
- **One bad address affects only itself.** A booking request fails only for its own job. For a daily proposal, the technician-day that holds an appointment that cannot be located is left exactly as it is and reported as skipped, and every other technician-day is still optimized.
- Coordinates sent by WaterFlex Software skip all of this and are used as given.

## Scaling (Decided: embedded first, configuration to scale out)

Apply these levers in order, each only when measurements call for it. Signals: booking response time approaching 5 seconds, `SearchAdmission` rejections, sustained CPU saturation, overnight runs not finishing in their window.

1. **More scheduler replicas behind a load balancer.** Works once no correctness state lives in replica memory (see "Multiple clients").
2. **Separate roles from one artifact.** Run booking replicas (latency sensitive, short solves) apart from batch replicas (overnight and daily proposals, up to 20 s of full CPU per solve). Same JAR, a role setting chooses which endpoints and schedules a replica serves. This removes the most likely CPU contention without a remote hop.
3. **Remote solver pool.** Switch `SCHEDULER_CALCULATION_MODE=REMOTE` and scale `solver-service` independently. Already in place: https with a service token for non-loopback solver URLs, and cancellation routed to the solving replica (below). The scheduler and solver must load the same Timefold core artifact; upgrade Timefold by bringing up the new solver pool beside the old one and switching the scheduler over (see "Remaining gaps"). The remote round trips (up to three per booking search today) must fit the 5 second budget.

   Cancellation across solver replicas (exists today): every solve `POST` and its cancellation `DELETE` carry the solve's request ID in the `Solve-Request-Id` header, and `solver-service` rejects either call when the header is missing or does not match the request. The load balancer in front of the solver pool hashes on that header so both calls reach the same replica. For nginx:

   ```nginx
   upstream solver_pool {
       hash $http_solve_request_id consistent;
       server solver-1.internal:8443;
       server solver-2.internal:8443;
   }
   ```

   A replica that leaves the pool loses only the solves it was running; their callers see a transport failure, and a cancel that misses still ends at the solve's own deadline.

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
- Each client has its own API tokens. The server maps the token to `tenant_id`; requests cannot choose a tenant. Exists today: `tenant` and `tenant_api_token` tables, tokens of the form `wfs_` plus 32 random bytes (base64url), stored only as SHA-256 digests, issued and revoked with `web/scripts/issue-tenant-token.ts`. A revoked token or a disabled tenant gets 401.
- Postgres row-level security is the second guard: each transaction sets `app.tenant_id`, and policies restrict every client-owned table to that tenant. A missed filter in application code then fails closed instead of reading another client's rows.
- Schema-per-client or database-per-client gives stronger isolation but multiplies migrations and operations. Keep it as an option for a client that contractually requires it. Because every row already carries `tenant_id`, that client's rows can be moved into a dedicated database without an API change.

### Several scheduler replicas on one database

- Correctness lives in the database. Holds are protected by row locks and unique constraints on `(tenant_id, technician_id, service_date)` and by the timestamp comparison inside one transaction. Any replica can serve any request, so the load balancer needs no sticky sessions.
- Scheduled work is claimed through the database so each unit runs once across replicas. Status of each job (exists today unless noted):
  - Overnight optimization: every replica fires the 2 AM cron, and the receipt insert in `overnight_optimization_attempt` is the claim, unique on `(metroId, serviceDate, nightOf)`. One replica runs each metro-day per night; the others skip it and count `claimedByOtherReplicaSinceProcessStart`. Replicas also split the metro-days between them. If the claiming replica dies mid-run, the receipt stays `ATTEMPTED` and appears in `unfinishedOlderThanFiveMinutes`; that metro-day is not retried until the next night.
  - Time-off analysis: claimed per request by a conditional `QUEUED` to `ANALYZING` update.
  - Reservation expiry: releases go through the locked reservation release path, so concurrent sweeps are safe.
  - Route prewarming: runs per replica; duplicates only repeat cache warming.
  - Route cache cleanup: idempotent on every replica (see "Route cache").
- Booking search cancellation already works across replicas: `BookingSearchControl` records cancellation durably in `booking_search_request`, every replica polls it every 100 ms, and offer publication takes a `FOR UPDATE` guard on the row and fails closed.
- In-memory state that stays per replica by design:
  - admission counters (`SearchAdmission`): limits are per replica; a global limit would need a shared counter,
  - the route cache memory tier (see "Route cache").
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

- **Per metro.** With one routing service per metro, the scheduler needs a metro-to-routing-URL map instead of the single `routing.url` today, and `RoadClient` becomes one client per metro. About 30 routing call sites lack a metro today, so this is planned with the request-fed preparation step, where every request carries its metro. Each metro's routing identity differs, so tier 2 and tier 3 entries for different metros never collide. Clearing tier 2 on identity change must become per metro, so a graph update in one metro does not flush the others.
- **Across clients.** Route legs are not tenant data and are shared across clients in a metro, which is where most of the cache benefit comes from. `road_route_cache` therefore has no `tenant_id`. It does contain customer coordinates at about 1 m precision, so it is treated as location data: reachable only by the scheduler, never exposed through the API, and purged within 30 days so deleted customers age out.
- **Across replicas.** Tier 2 is per replica by design; tier 3 makes a new or restarted replica warm immediately. No change is needed for correctness.
- **Health call per lookup.** Each lookup currently makes one `/health` round trip to confirm the routing identity. Caching the identity for a few seconds per metro would save that round trip, but the identity is how a changed road graph is detected, so this waits for the deferred performance work and a measured need.
- **Cleanup on every replica.** The weekly cleanup runs on every replica. It is idempotent, so running it more than once is harmless. The size trim deletes the oldest rows in batches of 50,000 until the table is within the 500,000 row cap (exists today).
- **Moving tier 3 into routing.** An alternative is to give each metro's routing service its own persistent cache and drop `road_route_cache` from the scheduler, making the scheduler database hold no coordinates at all. It costs routing its statelessness. Keep tier 3 in the scheduler database for now and revisit if location-data retention requirements tighten.

## Thin store

The public API gets its own tables instead of adding a tenant column to the portal's booking and optimization tables. Those tables reference master data (appointments, jobs, technicians) the thin store must not hold, and the portal path has to keep running unchanged while the two paths are compared before cutover (see "Migration").

Exists today (migrations `20261009120000_public_api_store`, `20261010120000_public_api_commit`, `20261011120000_public_api_booking`, `20261012120000_public_api_booking_select`, `20261013120000_public_api_booking_confirm` and `20261014120000_public_api_repair`):

| Table | Holds |
| --- | --- |
| `api_request` | One row per host `requestId` per tenant: the operation, a SHA-256 of the canonical request, a 60 second claim lease, and the stored response once finished. A repeat with the same body replays the response; a different body is a conflict; an abandoned claim is taken over after its lease. Requests that failed for a passing reason (for example routing unavailable) release their claim so a retry runs again. |
| `api_daily_proposal` | A served daily proposal: host metro, time zone and date, input revision, routing identity, status (`PROPOSED`, `COMMITTED`, `STALE`) and the proposal body. |
| `api_proposal_technician_day` | The host `lastModified` of every technician-day a proposal covers, stored as exact ISO-8601 text so nanoseconds survive. A commit compares the host's current values with these. |
| `api_commit_receipt` | The one commit of a proposal: receipt ID, the commit's `requestId`, and the receipt body (assignments and timestamps). Unique per proposal. |
| `api_booking_day` | Each metro-day's booking state (`BookingDayState`): the common arrangement with every active hold, and per technician the host timestamp and the appointments WaterFlex Software is expected to have. Versioned; every write locks the row and checks the version the search read. |
| `api_booking_offer_set`, `api_booking_offer` | What each search offered, and what became of each hold (`HELD`, `SELECTED`, `RELEASED`, `SUPERSEDED`, `LOST`, `CONFIRMED`). Expiry is a time, not a status. |
| `api_booking_receipt` | The one confirm of a hold: receipt ID, the confirm's `requestId`, and the receipt body. Unique per offer. |

Every key starts with `tenantId`. Row-level security is on for all of them: `PublicApiStore` runs each transaction as the `scheduler_tenant` database role with `app.tenant_id` set, so even a query without a tenant filter sees only the caller's rows, an unset tenant sees none, and that role cannot read any other table. Persisted proposal and booking state JSON is validated again on every read, and a stored answer is validated again before it is replayed.

The portal's tables (`slot_hold`, `reservation_arrangement`, `optimization_run` and the rest) and the master data tables are removed after migration.

## Operations that move to the host

- Overnight optimization: the host calls `POST /v1/daily/proposals` per metro-day on its schedule and commits accepted proposals. The scheduler no longer enumerates metros.
- Time off and shift edits: the host owns them and sends the result in later snapshots. Repair is requested through `/v1/repairs/proposals`.
- The current `web/` portal becomes an admin and demo client of the public API, or is retired.

## Portal as a client (Decided 2026-10-08)

The portal stays, keeps its pages, and becomes a client of the public API, standing in for WaterFlex Software. It schedules for several clients and switches between them, including two clients in the same metro. The work is phased:

1. Client model and switcher (done). A `client` table; `clientId` on `dealership`, `technician` and `customer`; every portal page and route is scoped to the active client chosen in the sidebar (cookie `wf_client`). A row of another client is a 404. Database triggers keep `clientId` fixed, keep each technician at its own client's depots and keep a depot at its own client's dealerships. Several clients may serve one metro (step 5c).
2. Per-client booking and routing solver settings page (done). Table `client_solver_settings`, one row per client: rates (`regularHourly`, `overtimeHourly`, `mileagePerMile`, `travelBufferPct`, `travelBufferMinutes`), `fairnessBudget`, `offerLimit` (1 to 4) and `bookingHorizonWeekdays` (1 to 15, which stays within the API's 21-date horizon). No row means not configured: nothing is calculated for that client, and no value is assumed. Saves carry the version they edited, and a save over a newer version is refused. `lib/clientSettings.ts` turns a row into the API's `rates` and `policy`.
3. Snapshot builder from the portal's tables (done). `lib/clientSnapshot.ts` builds one client's `Snapshot` for a metro and dates in one repeatable-read transaction. It holds only that client's technicians and appointments, even with other clients in the same metro, and reads the rows the way the scheduler's database path does: the effective depot assignment and endpoint policy, weekly availability with its date exception, approved time off as absences, and the daily paid limit as `maxPaidMinutes`. Missing settings, a metro the client does not serve, or inconsistent rows (for example an appointment on a day off) stop the build; nothing is filled in. Shift minutes are stored as America/Chicago times, so a metro in another time zone is refused until that is stored explicitly. `lastModified` comes from database triggers: `technician."factsChangedAt"` moves when a fact of all the technician's days changes (home, limits, qualifications, weekly availability, depot assignment, depot location or endpoint policy), and `technician_day_change."changedAt"` (its own table, so the scheduler's `schedule_day` lock rows are never touched) moves when one day changes (its appointments, their job or address, a date exception, or time off). A technician-day's `lastModified` is the later of the two, read as exact microsecond text, and every change moves it strictly forward. The triggers also see the scheduler's own writes.
4. Booking through `/api/v1` (done, behind `SCHEDULER_PUBLIC_API=true`). Search builds the client's snapshot over its booking horizon (tomorrow through the configured number of weekdays) and stores the returned offers in `portal_api_offer_set` and `portal_api_offer`. Choosing a time selects the offer, confirms its hold with a fresh snapshot of that date, and writes the receipt: in one transaction the portal locks the listed `schedule_day` rows and then the technician rows (the scheduler's order), compares every listed `lastModified` exactly, writes every listed appointment (the new one under the job's ID), bumps `schedule_day.version` so the scheduler's own paths see the change, and marks the job scheduled. Any difference writes nothing and offers current times instead. Day stamps take a share lock on their technician, so no change can land between the comparison and the write. Each client's token is `SCHEDULER_API_TOKEN_<CLIENT ID>` in the environment, checked against `/api/v1/whoami` before first use. Building this found that the scheduler's strict JSON converter, registered ahead of the plain-text one, refused every JSON request body to `/api/v1` and would have encoded replies twice; the controller now reads and writes raw bodies itself.
5. Dispatch, daily proposals and repair through `/api/v1`, then drop the one-client-per-metro trigger, in three changes:
   - 5a. Daily optimization (done, behind `SCHEDULER_PUBLIC_API=true`). "Preview optimization" on the dispatch board builds the client's snapshot of the metro day, asks `/api/v1/daily/proposals`, checks the answer against that snapshot (only the snapshot's technician-days and appointments, each once; an improvement places every appointment), and keeps it in `portal_api_daily_proposal` with the snapshot's technician-days and appointment placements. Applying an `IMPROVED` proposal fixes a commit request ID before the first call (so a retry after a lost answer replays the same commit), sends the current `lastModified` of every technician-day the proposal covers to `/commit`, checks the receipt is exactly the proposal, and writes it with the same compare-and-set as a booking receipt, recording the receipt in the same transaction. After writing, every live appointment on a routed technician-day must be one the receipt placed. A stale day refuses the proposal for good and writes nothing; applying an applied proposal returns it unchanged. The engine's preview, apply and history routes answer 404 in this mode.
   - 5b. Time off, availability and qualifications (done, behind `SCHEDULER_PUBLIC_API=true`). The portal records requests itself and never calls the scheduler's time-off service, whose queue ignores the portal's `AWAITING_ANALYSIS` reports. Analysis runs one day per call to `/api/time-off/analyze`, which the time-off page repeats until done: a frozen day needs coordination; a day with no shift, or no appointments of the absent technician, is left as it is and keeps its `lastModified`; any other day is sent to `/api/v1/repairs/proposals` with a snapshot of that metro day and kept as a `REPAIR` proposal. Approval commits every day's repair, then in one transaction compares every technician-day the repairs and the untouched days cover, writes every repair, records the receipts and approves the request; any change since the analysis writes nothing and sends the request back for analysis. Requests starting two weeks out or later are approved as soon as their analysis allows, as before. Date shifts and qualifications are written by the portal directly with the scheduler's own guards (frozen day, existing appointments). Building this found that any time-off status change moved its days' `lastModified`, so every analysis went stale against its own snapshot; only approval and its reversal move them now, and deleting an approved request moves its days before its intervals go by cascade.
   - 5c. Two clients in one metro (done). The `depot_metro_one_client` trigger is dropped; a new trigger stops a depot moving to another client's dealership. Every metro-wide query of the scheduler's database path was audited, since each selects by `depot."metroId"` alone and would mix two clients' technicians and appointments. `MetroTenancy.requireSingleClient` now refuses a metro served by more than one client with 409 in the daily build (`OptimizationService.capture`, so preview, repair, apply and the scheduler's own time-off analysis), the booking snapshot (`BookingSnapshotLoader`, so booking and live hold maintenance in `ReservationLifecycleService`) and the per-metro-day reservation lock (`ReservationStore`). The overnight batch selects only metros with one client. Dispatch geometry takes an optional `client_id`, which limits current routes to that client's technicians; without it, and for any engine run, a shared metro is refused. In the portal, `servesMetroAlone` gates the engine's optimize, preview and history routes and `ownsOptimizationRun`, and `metroClientId` refuses a shared metro instead of picking an owner. A client in a shared metro books, optimizes and repairs only through `/api/v1`. `PortalClientsDatabaseIT`, `DailyAttemptDatabaseIT` and `scripts/smoke-shared-metro.ts` cover two clients in one metro: isolated snapshots, booking, daily proposals and time-off repair that never read or change the other client's technicians or appointments, and the 409s above.

6. Per-client overnight runner owned by the portal (done, behind `SCHEDULER_PUBLIC_API=true`). Each client keeps its run times on the Solver Settings page (`client_overnight_time`, minutes after midnight America/Chicago; none means manual only) and can "Run now". `portal_overnight_run` is the queue: the worker (`npm run overnight:worker`, the `overnight-worker` compose service under the `public-api` profile) queues each client's due run time, at most an hour late, and a partial unique index keeps it to one run per run time however many workers fire it; another keeps each client to one queued or running run, which "Run now" returns instead of queuing another. Workers claim runs with `FOR UPDATE SKIP LOCKED`. A run covers the scheduler's overnight days (today until ten weekdays, frozen days left out) in every metro the client serves, one day at a time through `/api/v1/daily/proposals`, and records each day in `portal_overnight_day` in the transaction that keeps its proposal: proposed, skipped (frozen, or no technician that day) or failed with the reason. Nothing is applied: the proposals are ordinary daily proposals marked with their run, and the dispatch board opens the newest overnight improvement of a day for a dispatcher to apply. A run whose worker stops reporting for ten minutes is abandoned, and the days it finished stay recorded. With the portal's runner in use, the scheduler's own batch can be turned off with `SCHEDULER_OPTIMIZER_CRON=-`.
7. Retire the portal-only engine endpoints, in stages (decided 2026-10-08): first API mode stops calling them, then API mode becomes the portal's only mode and the legacy portal branches go, then the Java endpoints only the portal used are deleted with their CI jobs. The caller-level benchmark (the campaign workflow layer, the paced caller job, `web/scripts/benchmark-scheduling.ts`) drives those endpoints; the solver and policy layers and the solver assertion campaigns call the calculation engine directly and are not affected. Whether the workflow layer is retired or ported to `/api/v1` is decided before the last stage.
   - 7a-1. Depot and technician depot changes (done, behind `SCHEDULER_PUBLIC_API=true`). `POST /api/v1/routes/evaluate` times one metro day's routes in their given order from a snapshot and reports whether the day holds; nothing is optimized or stored. In API mode a depot's route endpoints, a depot's location and a technician's depot are changed by the portal itself: it loads the snapshot facts of the booked days the change affects (`loadSnapshotFacts`), applies the change to them, has each day timed, and writes the change with the new planned times through the receipt writer's compare-and-set. As on the scheduler's own path, a change that would make a booked day infeasible is refused and writes nothing, a move to another metro must start after the client's booking horizon and after the technician's booked days, and today's routes are left alone after 6 a.m. A booking made while the change is checked refuses it as stale. Active holds are not checked: a hold confirms against a fresh snapshot, so a changed day makes it stale.
   - 7a-2. Appointment cancellation and dispatch geometry (done, behind `SCHEDULER_PUBLIC_API=true`). In API mode the portal cancels an appointment itself, as the scheduler's own path does: the reason is required, a repeated cancellation answers `alreadyCancelled`, and the appointment and its job are cancelled. Before the day's 6 a.m. cutoff the technician's remaining visits that day are timed without it (`POST /api/v1/routes/evaluate`) and renumbered, written in the same compare-and-set as the cancellation; a remaining day that would not hold refuses the cancellation as needing a repair. A frozen day keeps its planned times. `POST /api/v1/routes/geometry` draws one metro day's routes on roads in their given order from a snapshot, split by absences, and locates appointments sent by address; nothing is stored. The dispatch board's current roads come from it, so each client sees only its own technicians in a shared metro. Engine run previews (`before`, `after`) have no API-mode drawing; API proposals are reviewed as timed routes.
   - 7a-3a. Fake data (done, behind `SCHEDULER_PUBLIC_API=true`). The generator books each call for one client (the metro's only client, or the one named with `--client`) by searching the chosen date alone, selecting an offer and confirming its hold, as a booking does; it takes whatever arrival windows the scheduler offers instead of only two-hour ones. Clearing deletes the client's generated jobs and re-times each open day where other visits remain (`POST /api/v1/routes/evaluate`) in the same compare-and-set write; a frozen day keeps its planned times (`lib/apiPurge.ts`, shared with the test runner).
   - 7a-3b. The booking test runner (done, behind `SCHEDULER_PUBLIC_API=true`). A run books for the client active when it is created (`booking_test_run.clientId`; older runs have none and book for the Omaha metro's only client), so switching clients cannot redirect a run in progress. Each request is searched, held and confirmed as a customer booking, each booked day is previewed as the client's daily proposal and applied from the page as on the dispatch board, and purging deletes the run's jobs with the open days they leave re-timed (`purgeApiJobs`). By owner decision the routability pre-check is dropped: an address the scheduler cannot reach gets no offer, so `/internal/test-address-routability` can be deleted in P7c. With 7a-3b, API mode calls none of the scheduler's portal-only endpoints.
   - 7b-1. Booking through the public API only (done). The setting no longer applies to booking: search, refresh, select and release always run through `/api/v1`, and the scheduler's background search (`/api/book/search`) and hold confirmation (`/api/book/confirm`) routes are gone from the portal. A pin outside the client's service area is refused; one the scheduler cannot reach by road gets no offer, since the portal no longer asks the scheduler's internal road validation. Compose routes the seeded `metro-omaha` for the public API by default. 7b-2 moves dispatch, optimization, geometry, fake data and the test runner; 7b-3 moves time off, availability, depots, cancellation, settings and overnight and removes `SCHEDULER_PUBLIC_API`.
   - 7b-2. Dispatch, road geometry, fake data and the booking test runner through the public API only (done). The dispatch board always works with daily proposals; the portal's engine optimization routes (`/api/dispatch/optimize`, preview, apply and history) and the before and after road maps of engine runs are gone, and the board's map shows the client's current routes. Fake data books and clears only through the public API. The test runner books for the client active when a run starts (an older run books for the Omaha metro's only client), previews each day as a daily proposal, and purges through the public API; runs saved with engine previews show them read-only. The runner no longer asks the scheduler's internal road check for each generated address, so `/internal/test-address-routability` has no portal caller.

Each client's public API token lives in the portal's environment or secret file, never in the portal database. Clients are created with `npx tsx scripts/create-client.ts <clientId> "<client name>"`.

## Migration

1. Add the public endpoints alongside the existing ones, backed by a request-fed path that builds `DayPlan` and `BookingSnapshot` from the snapshot instead of the database.
2. Run both paths on the same days and compare proposals, offers and validation outcomes.
3. Move holds and offers to host IDs; cut over the host; retire the master data tables.

## Remaining stateless gaps in calculation and routing

- Remote solve cancellation (`DELETE /v1/solves/{id}`) is tracked in memory per `solver-service` instance. The `Solve-Request-Id` header lets the load balancer send the solve and its cancellation to the same replica (see "Scaling"); without that hashing, a cancel may miss and the solve stops at its own deadline (at most 20 s daily, 120 s booking). The embedded default has no such gap.
- Remote results must come from the same Timefold core artifact (version and SHA-256) and the same policy, cost and score model versions as the caller (`CalculationProtocol.Response.match`). This is the audit's TF01 provenance guarantee and is kept deliberately (decided by the owner on 2026-10-08: roadmap item S1e, a compatible-version set, was declined). A Timefold upgrade is deployed by running the new solver pool beside the old one and switching the scheduler with it, not by loosening the check.
- Routing requires the `ROUTING_AUTH_TOKEN` bearer token on computation endpoints, but traffic is plain http inside the private network. Add TLS if routing is ever reachable beyond that network.
- Remote mode has not been performance-tested; that study is deferred with the rest of the performance work.

## Resolved questions

1. Concurrency token: WaterFlex Software's last-modified timestamp per technician-day (see "Commit ownership and concurrency").
2. Geocoding: host coordinates are authoritative; local Nominatim is the fallback (see "Locations and geocoding").
3. Tenancy: one shared database with `tenant_id` on every row and row-level security, from day one.
4. Repair never adds overtime; the dispatcher overtime approval flag was removed (see `docs/scheduler-policy.md`).
5. Booking latency: offers within 5 seconds end to end.
6. TIGER: the Nominatim import adds the US Census TIGER address ranges, so a house number OpenStreetMap lacks is placed along its street (see the README map steps).

## Open questions

1. Does WaterFlex Software's Google Maps Platform agreement allow passing Google-geocoded coordinates to the scheduler and caching them for up to 30 days?
2. What audit retention period applies to receipts?
3. Does any client require a dedicated database?
