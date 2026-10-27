# Integrating WaterFlex Software with the scheduler

For the WaterFlex Software engineers who connect the product to the scheduler. It covers what each side owns, how to build a snapshot, every call sequence with its payloads, what each error means and whether to retry, and how to prove the integration before go-live. The contract itself is [docs/api/openapi-v1.yaml](api/openapi-v1.yaml) (version 1.8.0); where this guide and the spec differ, the spec is right and this guide is a defect. The design and its decisions are in [docs/stateless-api-design.md](stateless-api-design.md); the scheduler's operators use [docs/operations-runbook.md](operations-runbook.md).

The portal in `web/` is a working client of this API and the reference for every step below: it builds snapshots (`web/lib/clientSnapshot.ts`), books (`web/lib/apiBooking.ts`), writes receipts with a compare-and-set (`web/lib/apiReceipt.ts`), and runs an overnight schedule (`web/lib/overnightCore.ts`, `web/scripts/overnight-worker.ts`).

> **Before production traffic:** TLS is not set up yet (deferred by owner decision on 2026-10-13). See [step 10](#10-security-preconditions-before-full-integration). Until it is, run the integration only on a private network.

## 1. Topology and ownership

Your backend keeps the master data and every appointment and calls the scheduler VM over HTTPS; everything else on the VM is private.

```text
 WaterFlex Software backend                       Scheduler VM (partitioned CPU and memory)
 +------------------------------+  HTTPS /api/v1  +-----------------------------------------------+
 | master data and the record   | --------------> | scheduler-proxy :8000 --> booking/daily pool  |
 | of every appointment         | snapshot + call |   |  scheduler replicas, embedded engine      |
 |                              | <-------------- |   +--> routing-service per metro (private)    |
 | writes receipts with a       | offers, routes, |   +--> Nominatim (optional, private)          |
 | compare-and-set              | receipts        |   +--> scheduler-db (thin store, its own)     |
 +------------------------------+                 |   management :9000 (metrics, private only)    |
                                                  +-----------------------------------------------+
```

The scheduler runs on its own VM and is reached only through `https://<scheduler-vm>/api/v1`. Nothing else connects to it: no shared database, no client library, no callbacks. WaterFlex Software can call it from any language.

On the VM, `scheduler-service` may run as several replicas of the same jar behind one load balancer (the address you call). Booking replicas answer booking searches, confirms and route calls; daily replicas take daily and repair proposals, synchronous or queued. Every replica answers every route, holds no state of its own, and shares the one thin store, so which replica answers a call never changes the answer; you call one address and see one API.

WaterFlex Software owns:

- Customers, addresses, jobs, appointments, technicians, qualifications, shifts, absences, depots
- The business record: every appointment's technician, sequence and planned times
- The `lastModified` timestamp of every technician-day
- Each client's policy choices and rates, sent with every call
- Overnight schedules, time off, cancellations, depot changes

The scheduler owns:

- Booking holds and each metro-day's common arrangement
- Offer sets, proposals and receipts it issued, keyed by your IDs
- The road graph per metro, its route cache, and address location (Nominatim)
- Nothing else: it reads no master data and holds no policy settings

Every calculating call carries a **snapshot** of the facts it needs. The scheduler answers with time windows (booking offers) or whole timed routes (daily and repair proposals, route evaluations), and you write the result into your own records.

## 2. Onboarding

The scheduler's operator does these once per client, on the scheduler VM (see `deploy/vm/README.md`):

1. **A tenant and its token:** Each WaterFlex Software client is one tenant. The operator issues a token:

   ```sh
   docker compose -f deploy/vm/docker-compose.yml --env-file deploy/vm/.env run --rm scheduler-admin \
     --command=issue-token acme-water "Acme Water" primary
   ```

   It prints `id: <uuid>` and `token: wfs_...` once. Store the token in your secret store, one per client, and send it as `Authorization: Bearer <token>` on every call. The tenant comes only from the token; a request never names one. `GET /api/v1/whoami` answers `{"tenantId": "acme-water"}` for a working token.
2. **Rotation:** Issue a second token for the tenant (another label), deploy it, then revoke the old one with `--command=revoke-token <id>`. Both work in between. `--command=disable-tenant` stops every token of a tenant at once; `--command=list-tenants` shows tenants and their active token counts.
3. **Metros:** Every metro you send (`snapshot.metroId`) must have its routing service listed in the scheduler's `ROUTING_METRO_URLS`. An unlisted metro is a 422, never routed elsewhere. Clients in the same metro share its routing service; routing sees only coordinates.
4. **Geocoding mode:** `GEOCODING_MODE=COORDINATES_REQUIRED` (the default) never locates an address: send coordinates. `NOMINATIM` locates an address-only location when the map has exactly one house matching it (section 3).
5. **Retention:** The scheduler purges requests, proposals, offers and receipts after `SCHEDULER_RETENTION_DAYS`. Keep your own copy of any receipt you need longer. A `requestId` older than the period is forgotten and runs again.

## 3. Building a snapshot

Every calculating call carries a `Snapshot` for one metro: its rates and policy, the technicians it covers, one `TechnicianDay` per technician and date, and every committed appointment on those technician-days.

```json
{
  "metroId": "omaha",
  "timeZone": "America/Chicago",
  "rates": { "regularHourly": "20", "overtimeHourly": "30", "mileagePerMile": "0.67", "travelBufferPct": "0.2", "travelBufferMinutes": 2 },
  "policy": { "fairnessBudget": "0.02", "dailySearch": "TABU_KOPT", "costSearchSeconds": 10, "fairnessSearchSeconds": 5,
              "bookingDeadlineMs": 5000, "regularWindowThreshold": 2 },
  "technicians": [ { "id": "tech-1", "qualifications": ["softener-install", "softener-service"] } ],
  "technicianDays": [ {
    "technicianId": "tech-1", "serviceDate": "2026-10-12", "lastModified": "2026-10-11T21:04:17.123456Z",
    "shift": { "start": "2026-10-12T08:00:00-05:00", "end": "2026-10-12T17:00:00-05:00" },
    "absences": [],
    "start": { "lat": 41.2565, "lng": -95.9345 }, "end": { "lat": 41.2565, "lng": -95.9345 },
    "maxPaidMinutes": 540
  } ],
  "appointments": [ {
    "id": "appt-7", "technicianId": "tech-1", "serviceDate": "2026-10-12", "serviceId": "softener-service",
    "durationMinutes": 60, "window": { "start": "2026-10-12T08:00:00-05:00", "end": "2026-10-12T12:00:00-05:00" },
    "location": { "lat": 41.2603, "lng": -96.0731 }, "sequence": 0, "plannedStart": "2026-10-12T08:20:00-05:00"
  } ]
}
```

**Parsing is strict.** An unknown field, a duplicate key, a missing required value, money as a JSON number, or a reference to a technician or technician-day the snapshot does not hold is a 400 `INVALID_REQUEST`. Nothing is assumed or filled in. In particular:

- `absences` is required on every technician-day; send `[]` when there are none.
- `plannedStart` is required on every appointment: its currently planned arrival.
- Money and rates are canonical decimal strings (`"20"`, `"0.2"`, `"0.67"`), never numbers.
- The cost rates have at most four decimal places; `regularHourly` and `overtimeHourly` are at most 500 and `mileagePerMile` at most 25 (spec 1.7.0). A rate outside that is refused with 400.
- Instants are RFC 3339 with an offset (`Z` or `-05:00`). Service dates are calendar dates in the metro's zone.
- IDs are your own opaque strings, 1 to 128 characters with no leading or trailing space.

**Policy and rates are the client's own**, sent with every call; the scheduler holds none. `fairnessBudget` is a share (`"0"` for none, `"0.02"` for 2 percent). `dailySearch` is `TABU_KOPT`, `TABU` or `TABU_KOPT_SIZE_3`. `costSearchSeconds` (5 to 240) and `fairnessSearchSeconds` (0 to 240, at most 240 together) set how long a proposal searches. The optional `unimprovedSearchSeconds` (1 to `costSearchSeconds`) ends each of those searches once its best plan has not improved for that long, so a day that settles early answers early; without it, each search spends its whole time. `bookingDeadlineMs` (1000 to 5000) bounds a booking search or confirm; `regularWindowThreshold` (0 to 1000) tunes its refinement pass.

**Order.** On each technician-day, `sequence` and `plannedStart` must put the appointments in the same order (ties by appointment ID). A contradiction is refused with a 422 `INCOMPLETE_FACTS`, never reordered: the scheduler keeps that order and you write both.

**Locations.** Coordinates are authoritative: when a location has `lat` and `lng`, they are routed as given. An address without coordinates is located only in `NOMINATIM` mode and only as an exact house (ZIP, house number and street must agree; a mailing city is fine when the ZIP agrees). A location that cannot be located is never guessed:

- In a daily proposal, route evaluation or geometry, its technician-day is left exactly as it is and listed in `skippedTechnicianDays` with reason `LOCATION_UNRESOLVED`; every other day is still calculated.
- In a booking search, a technician-day it belongs to is skipped the same way; an unlocatable **job** is a 422.
- A Nominatim outage is a 503 `ROUTING_UNAVAILABLE` and can be retried.

Route geometry marks every stop with `source`: `HOST` for your coordinates, `NOMINATIM` for a located address. Check a `NOMINATIM` pin before relying on it.

### The `lastModified` rules

`lastModified` on each technician-day is the concurrency token for every commit and confirm. The scheduler compares it for **exact equality** at full precision and never orders it, so clock skew cannot cause a wrong decision. Your system must guarantee:

- It changes on **every** change that affects the day: an appointment created, edited, cancelled or deleted; a shift, availability or absence change; a start or end location change; a qualification change.
- A move touches both sides. Moving an appointment from technician A on Tuesday to technician B on Wednesday updates both technician-days.
- A shift template change updates every future day it affects.
- It is sent exactly as stored. Do not round, reformat or regenerate it between the snapshot and the commit.

A missed update is the one way an integration can overwrite a dispatcher's change, so test it explicitly.

## 4. Booking

Booking finds arrival windows for a new job, holds them for 10 minutes, and turns the customer's choice into an appointment you write. The flow below shows each call's answer; write the receipt with compare-and-set.

```text
offers --201--> customer picks one --> select --200--> confirm --200--> write receipt
   |                                      |409 HOLD_UNAVAILABLE   |409          | write fails
   |                                      v                       v             v
   +---------------------------- search again (new requestId) <---+-- release --+
```

**1. Search.** `POST /api/v1/booking/offers`, with a new `requestId`, the job, a horizon of 1 to 21 dates, the client's `offerLimit` (1 to 4) and `windowHours` (1 to 8), and a snapshot of every technician-day in the horizon:

```json
{
  "requestId": "9e8d7c6b-5a49-4382-9170-6f5e4d3c2b1a",
  "job": { "id": "job-311", "serviceId": "softener-install", "durationMinutes": 90, "location": { "lat": 41.235, "lng": -96.042 } },
  "horizon": { "firstDate": "2026-10-12", "lastDate": "2026-10-12" },
  "offerLimit": 4,
  "windowHours": 4,
  "snapshot": { "...": "as in section 3" }
}
```

It answers 201 within the client's `bookingDeadlineMs`:

```json
{
  "offerSetId": "set-77a1", "expiresAt": "2026-10-12T15:10:00-05:00", "searchComplete": true, "refinement": "SHORTENED", "incompleteDates": [], "skippedTechnicianDays": [],
  "offers": [
    { "offerId": "offer-1", "serviceDate": "2026-10-12", "window": { "start": "2026-10-12T10:00:00-05:00", "end": "2026-10-12T12:00:00-05:00" } }
  ]
}
```

- Each window is `windowHours` long and starts on the hour. Offers add no overtime and may move other appointments to make room; those moves come back in the confirm receipt.
- `searchComplete: false` means the deadline or a failure stopped a pass the offers need: an empty list then does not prove there is no capacity. Search later dates or try again. When the first pass already found plenty of windows, the second (refinement) pass is optional and kept short; cutting it short leaves `searchComplete` true.
- `incompleteDates` lists the dates that pass did not finish. Every other date was searched to the end, so a date outside the list with no offer has no regular capacity for the job: when the customer asks for a later week, search dates after the last complete one rather than repeating the whole horizon.
- `refinement` says how that second pass ended (`COMPLETE`, `SHORTENED`, `SKIPPED`, `UNAVAILABLE`, or `SHED` when other customers were waiting for a booking slot and the optional pass gave its time to them). It is for monitoring and needs no action. A replay of an offer set answered before spec 1.2.0 leaves out `refinement`, and before 1.3.0 `incompleteDates`.
- `policy.bookingRefinementMs` (optional) sets how long a client's optional refinement may run, 0 to 3000 ms (1000 before spec 1.5.0); 0 turns it off and leaves the first pass's offers. Without it the scheduler operator's default applies. The deadline still ends a refinement that would run past it.
- `refinementStop` (spec 1.5.0) says what ended a `SHORTENED` refinement: `CAP`, its own budget, or `DEADLINE`. It is sent only with `SHORTENED`. `regularWindowsFound` (spec 1.5.0) is how many distinct regular windows the first pass found, the number compared with `regularWindowThreshold`; it is left out when the first pass did not finish. Both are for tuning the policy and need no action.
- `job.id` must differ from every appointment and technician ID in the snapshot. A date already past its 6 a.m. local cutoff is a 422; start the horizon after it.
- A new search for the same job ends its earlier offers. Search again whenever the customer changes their mind.

**2. Select.** `POST /api/v1/booking/offers/{offerId}/select` with `{"requestId": "<new uuid>"}` keeps that hold, with its original expiry, and releases the others in the set. Selecting the selected offer again returns the same hold. A 409 `HOLD_UNAVAILABLE` means the hold expired or ended, or another offer of the set was selected: search again.

**3. Confirm.** `POST /api/v1/booking/holds/{holdId}/confirm` with a new `requestId` and a **fresh** snapshot of the hold's metro and date only (another date is a 400). The scheduler reconciles its holds with your current facts, turns the hold into the job's appointment under the job's own ID, and checks the day stays feasible without overtime:

```json
{
  "receiptId": "rcpt-41d9",
  "assignments": [
    { "appointmentId": "job-311", "technicianId": "tech-2", "serviceDate": "2026-10-12", "sequence": 0,
      "plannedStart": "2026-10-12T13:10:00-05:00", "plannedEnd": "2026-10-12T14:40:00-05:00" }
  ],
  "technicianDays": [ { "technicianId": "tech-2", "serviceDate": "2026-10-12", "lastModified": "2026-10-10T16:30:00Z" } ]
}
```

**4. Write the receipt.** In one transaction, compare each listed technician-day's current `lastModified` with the receipt's; if all are equal, write every listed assignment (the new job's appointment included, created under its job ID) and move those `lastModified` values. The receipt lists every appointment on each technician-day whose appointments, order or planned times change; days not listed are untouched. If any timestamp differs, write nothing, release the offer (`POST /api/v1/booking/offers/{offerId}/release`) and search again.

Confirming a confirmed hold again, with any `requestId`, returns the same receipt, so a confirm that timed out can be repeated safely. Release a set you no longer need; release is a 200 even when nothing was still held. Holds you do not release expire after 10 minutes.

**Reconciliation, in short.** Each search and confirm compares your snapshot with the scheduler's stored arrangement for the date. An unchanged technician-day, or one changed exactly as a receipt said, is accepted. Any other change to a day that carries a hold or a move a pending offer depends on loses **every** hold on that date: their select or confirm is then a 409 and the date starts again from your routes. Nothing is guessed: a hold either still fits your current facts exactly, or it is lost.

**Late requests.** With several booking desks, a search or confirm built from your records can arrive after another desk's receipt was written and already used, or after a confirm whose receipt you have not written yet. The scheduler recognises the older `lastModified` (it remembers the last 32 timestamps of each technician-day it moved past) or the route from before the unwritten receipt, and answers 409 `STALE` instead of reading your older routes as a change, so no other desk's hold is lost. Rebuild the snapshot from current records and send it with a new `requestId`; the holds still stand.

**Timeouts.** Set the caller's timeout a little above the client's `bookingDeadlineMs` (the portal adds 3 seconds). Reuse HTTP connections (keep-alive): the budget is short enough that connection setup matters.

## 5. Daily and repair proposals

**Daily.** `POST /api/v1/daily/proposals` with a new `requestId`, the `serviceDate` and a snapshot of every technician-day of that metro and date:

```json
{ "requestId": "3b1f6c1e-2a7d-4f0e-8c52-9a1d7e6b4c21", "serviceDate": "2026-10-12", "snapshot": { "...": "section 3" } }
```

It searches for about `costSearchSeconds + fairnessSearchSeconds` plus up to 5 seconds, then answers 201:

```json
{
  "proposalId": "prop-0c4e", "inputRevision": "9f2b...4d5e", "decision": "IMPROVED",
  "reason": "COST_REDUCTION: lower fleet cost within policy",
  "routes": [ { "technicianId": "tech-1", "serviceDate": "2026-10-12",
    "stops": [ { "appointmentId": "appt-7", "sequence": 0, "plannedStart": "2026-10-12T08:24:00-05:00", "plannedEnd": "2026-10-12T09:24:00-05:00" } ] } ],
  "unresolvedAppointmentIds": [], "skippedTechnicianDays": [], "costCents": 18942, "overtimeMinutes": 0
}
```

- Only `IMPROVED` can be committed. `NO_IMPROVEMENT` and `REJECTED_BY_POLICY` return the current routes.
- `reason` begins with a policy code when a plan was judged (`COST_REDUCTION`, `FAIRNESS_IMPROVEMENT`, `OVERTIME_REDUCTION`, `COMPLETE_ZERO_OVERTIME_DEMAND`, `NO_POLICY_IMPROVEMENT`, `COST_CEILING_EXCEEDED`, `FAIRNESS_REGRESSION_WITHIN_ALLOWANCE`, `OVERTIME_PROHIBITED`, `OVERTIME_TARGET_MISMATCH`, `NO_VALID_CANDIDATE`, `INCOMPLETE_OR_INFEASIBLE_DEMAND`), then a colon and a sentence for people. Take the code from before the first colon; other decisions are a sentence only.
- Skipped technician-days are not in `routes` and stay exactly as they are.
- Routes are frozen from 6 a.m. local time on the service date: a proposal or commit after that is a 422.

**Commit.** `POST /api/v1/daily/proposals/{proposalId}/commit` with a new `requestId` and your **current** `lastModified` for every technician-day the proposal covers, skipped ones included (a missing or extra one is a 400):

```json
{ "requestId": "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
  "technicianDays": [ { "technicianId": "tech-1", "serviceDate": "2026-10-12", "lastModified": "2026-10-11T21:04:17.123456Z" } ] }
```

On 200 the receipt lists every appointment on the proposal's routes; write them all with a compare-and-set on the receipt's timestamps, as for a booking receipt. A 409 `STALE` lists the changed technician-days in `changed`: nothing was committed, so ask for a new proposal from your current facts. A 409 `NOT_COMMITTABLE` means the proposal was already committed (the message names the receipt) or is not `IMPROVED`: do nothing. A proposal is committed at most once; repeating the commit's own `requestId` returns the same receipt.

If your compare-and-set write fails after a 200, the proposal stays committed; ask for a new proposal from your current facts.

**Repair.** `POST /api/v1/repairs/proposals` covers a technician's absence without overtime. Send the absence and a snapshot of its date that includes the absent technician-day (an unlocatable absent day is a 422):

```json
{ "requestId": "5c2d8e4f-1a3b-4c6d-8e0f-2a4b6c8d0e1f",
  "absence": { "technicianId": "tech-1", "serviceDate": "2026-10-12",
               "window": { "start": "2026-10-12T08:00:00-05:00", "end": "2026-10-12T17:00:00-05:00" } },
  "snapshot": { "...": "section 3" } }
```

The answer is a `DailyProposal`. `IMPROVED` means every appointment is placed without overtime: commit it with the daily commit endpoint, write the receipt, then record the absence in your own system. Any other decision (reason "Repair infeasible") means it cannot be covered automatically; a dispatcher decides.

**Queued proposals (spec 1.4.0).** Add the header `Prefer: respond-async` to a daily or repair proposal and the scheduler queues it and answers 202 at once, instead of holding the call open for the search:

```http
POST /api/v1/daily/proposals
Prefer: respond-async

HTTP/1.1 202 Accepted
Location: /api/v1/requests/3b1f6c1e-2a7d-4f0e-8c52-9a1d7e6b4c21
Retry-After: 3
Preference-Applied: respond-async

{ "requestId": "3b1f6c1e-2a7d-4f0e-8c52-9a1d7e6b4c21", "operation": "DAILY_PROPOSAL", "state": "QUEUED", "queuedAt": "2026-10-11T22:00:00.120Z" }
```

Poll `GET /api/v1/requests/{requestId}` (the `Location`) no sooner than `Retry-After`. The status is `QUEUED`, then `RUNNING`, then `COMPLETED` with `result`: the HTTP `status` and `body` the call itself would have answered (201 with the proposal, or a `Problem`). A 201 result is stored like any proposal: commit it as above, and the same `requestId` sent again, with or without the header, replays it. A `BUSY`, `ROUTING_UNAVAILABLE`, `CALCULATION_UNAVAILABLE` or `CANCELLED` result released the `requestId`, as the same answer to a synchronous call does: send the request again with it to run it again, after `result.retryAfterSeconds` when given.

- Any scheduler replica takes queued requests, the waiting request of the client whose last one started longest ago first, so one client's 30 overnight days never hold back another client's single day. Each replica runs one daily calculation at a time; bookings keep priority over it.
- A client may have up to 50 requests queued or running (the operator's `scheduler.daily.queue.tenant-limit`); one more is a 429 `BUSY` with `Retry-After: 30`. A request still waiting after 15 minutes (`max-wait-minutes`) completes with 429 `BUSY` without running; send it again.
- Without the header both endpoints behave exactly as before. Use the queue for anything that is not waiting on a person: overnight runs, batches of days, repairs analysed in the background.

**Overnight optimization** is yours to schedule: queue daily proposals per metro-day at a time each client chooses and commit the `IMPROVED` ones, or hold them for a dispatcher's approval. The scheduler has no batch of its own; the portal's overnight runner (`web/lib/overnight.ts`) queues each day and polls it.

## 6. Cancellation and in-flight requests

`DELETE /api/v1/requests/{requestId}` cancels a daily or repair proposal, booking search or confirm still running under that `requestId`, best effort. It answers 202 without a body; the cancelled call then answers 409 `CANCELLED`, stores nothing and releases its `requestId`, so retrying the same request later runs it again. A 404 means no call is queued or in progress under it (it finished or never started).

The DELETE may reach any replica. A call running on another replica is stopped by that replica within about a second (spec 1.4.1). A request sent with `Prefer: respond-async` that is still queued ends at once, and a running one stops within a few seconds; polling it then reports `COMPLETED` with a 409 `CANCELLED` result. A call that finishes before the cancel reaches it keeps its answer.

A second call with a `requestId` that is still running answers 429 `BUSY`; wait and repeat it.

## 7. Errors, idempotency and retries

Every write carries a `requestId` (a UUID) that is its idempotency key **across all endpoints of the tenant**:

- The same `requestId` with the same body returns the stored answer (status and body), so a retry after a timeout is always safe.
- The same `requestId` with a different body, or on a different endpoint, is a 400. Use a new UUID for every new request, including a select, a release and each commit.
- A request that ended in 429, 503 or 409 `CANCELLED` released its `requestId`: repeating it runs it again.

Every error is a JSON `Problem` (`{"error": "...", "message": "..."}`) except 401. Branch on `error`, never on `message`, which is for people and may change.

- **Status 400**
  - **`error`:** `INVALID_REQUEST`.
  - **Meaning:** Malformed body, unknown field, duplicate key, missing value, a reference the snapshot does not hold, horizon over 21 dates, a reused `requestId`.
  - **What to do:** Fix the request; send it with a new `requestId`. Never retry unchanged.
- **Status 413**
  - **`error`:** `INVALID_REQUEST`.
  - **Meaning:** The body is larger than 8 MiB (8388608 bytes); nothing was claimed or calculated.
  - **What to do:** Send less in one call (fewer dates or technician-days); do not repeat it unchanged.
- **Status 401**
  - **`error`:** (no body, `WWW-Authenticate: Bearer`).
  - **Meaning:** Missing, malformed, unknown or revoked token, or a disabled tenant.
  - **What to do:** Check the token; do not retry.
- **Status 404**
  - **`error`:** `NOT_FOUND`.
  - **Meaning:** No such proposal, offer, hold or running request for this tenant.
  - **What to do:** Do nothing (or search again for a booking).
- **Status 409**
  - **`error`:** `STALE`.
  - **Meaning:** Commit: a technician-day changed since the proposal was computed. Booking search or confirm: the snapshot is older than records already used. `changed` lists the technician-days.
  - **What to do:** Commit: new proposal from current facts. Booking: the same call from current records with a new `requestId`.
- **Status 409**
  - **`error`:** `NOT_COMMITTABLE`.
  - **Meaning:** Already committed, or not `IMPROVED`.
  - **What to do:** Do nothing.
- **Status 409**
  - **`error`:** `HOLD_UNAVAILABLE`.
  - **Meaning:** Hold expired, lost, released, superseded, not selected, or the day would need overtime.
  - **What to do:** Search again (new `requestId`).
- **Status 409**
  - **`error`:** `CANCELLED`.
  - **Meaning:** You cancelled the call.
  - **What to do:** Nothing stored; repeat the same `requestId` if you still want it.
- **Status 422**
  - **`error`:** `INCOMPLETE_FACTS`.
  - **Meaning:** Well formed but not schedulable: a date past its 6 a.m. cutoff, an unconfigured metro, an unlocatable job, `sequence` and `plannedStart` in different orders, no road route.
  - **What to do:** Fix the facts or the dates; retrying unchanged returns the same answer.
- **Status 429**
  - **`error`:** `BUSY`.
  - **Meaning:** Admission limit reached, a concurrent change to the same day, or the same `requestId` still running. A daily or repair proposal is refused at once when the calculations ahead of it would not finish within its allowance, and `Retry-After` is their estimated time (up to 300 seconds). A queued proposal: the client's queue is full, or the request waited longer than the queue allows.
  - **What to do:** Retry the same `requestId` after `Retry-After`.
- **Status 503**
  - **`error`:** `ROUTING_UNAVAILABLE`.
  - **Meaning:** Road routing or address lookup unavailable.
  - **What to do:** Retry the same `requestId` with backoff.
- **Status 503**
  - **`error`:** `CALCULATION_UNAVAILABLE`.
  - **Meaning:** The deadline passed with nothing found, or the remote solver is unavailable.
  - **What to do:** Retry the same `requestId` with backoff.
- **Status 503**
  - **`error`:** `SERVICE_UNAVAILABLE`.
  - **Meaning:** Any operation: the scheduler could not check the token (its token store did not answer in time). Nothing was decided or stored (since 1.8.0).
  - **What to do:** Send the same call, with the same `requestId`, after `Retry-After`.

**Timeouts to set at the caller:** booking offers and confirm, the client's `bookingDeadlineMs` plus about 3 seconds; daily and repair proposals, `costSearchSeconds + fairnessSearchSeconds` plus about 15 seconds (a queued one answers 202 within a second; give up polling after the queue's 15 minutes plus that); everything else, 10 to 30 seconds. After a client-side timeout, repeat the same `requestId`: you get the stored answer, or a 429 while it is still running.

## 8. Changes you make yourself

The scheduler never changes your records. For changes that move existing work, check the affected days first with `POST /api/v1/routes/evaluate`, which times each technician-day in your order and reports `feasible` without storing anything:

```json
{ "serviceDate": "2026-10-12", "snapshot": { "...": "the day as it would be after your change" } }
```

- **Cancelling an appointment:** before the day's 6 a.m. cutoff, evaluate the day without it and write the cancellation with the remaining visits' new planned times and sequence, in one write that moves `lastModified`. A frozen day keeps its planned times.
- **Depot or technician changes** (a depot's location or route endpoints, a technician's depot): evaluate every booked day the change affects as it would be afterwards; refuse the change if any day is not feasible.
- **Time off and shift edits:** request a repair (section 5) for days with appointments; otherwise just send the new facts in later snapshots.
- **Maps:** `POST /api/v1/routes/geometry` returns each day's road legs as GeoJSON positions (`[lng, lat]`) for your dispatch maps; nothing is stored.

## 9. Proving the integration

1. Run the [host conformance kit](../infra/host-conformance/README.md) from your network against the scheduler, with a token for a dedicated `conformance` tenant. It exercises every endpoint, sequence and refusal in this guide and writes a pass/fail report; `src/snapshot.ts` and `src/cases.ts` are compact examples of each call.
2. Prove your own snapshot builder: send a real day of each client to `routes/evaluate` and compare the timed routes with your schedule; then book, confirm and write a receipt end to end on a test client.

Before go-live, check that:

- [ ] TLS is in place for every connection in step 10.
- [ ] Each client has its own tenant token, stored as a secret, with a rotation procedure.
- [ ] Every served metro is in `ROUTING_METRO_URLS`, and its routing `/health` is up.
- [ ] `lastModified` moves on every change listed in section 3, including both sides of a move (tested).
- [ ] Every receipt write is a compare-and-set on the receipt's timestamps, and a failed write releases and searches again.
- [ ] Every new request gets a new `requestId`; every retry after a timeout, 429 or 503 reuses it.
- [ ] Callers branch on `error` codes and handle every row of the table in section 7.
- [ ] Caller timeouts follow section 7, and connections are reused.
- [ ] The overnight schedule runs per client and metro, and its proposals are committed or held for approval.
- [ ] Receipts you must keep longer than `SCHEDULER_RETENTION_DAYS` are stored on your side.
- [ ] The conformance kit passes against the production scheduler.

## 10. Security preconditions before full integration

TLS is deferred by owner decision (2026-10-13) and is **required before production traffic**. Until then the API must be bound only to a private interface (`SCHEDULER_API_BIND`). Encrypt:

- **WaterFlex Software to the scheduler API (port 8000):** Terminate TLS at a reverse proxy in front of 8000, or in the JVM with Spring Boot's `server.ssl.*` settings. Bearer tokens travel in every request, so this one is mandatory.
- **Scheduler to routing services:** Plain http with a bearer token (`ROUTING_AUTH_TOKEN`) today; fine on the VM's internal network, encrypted if a routing service ever runs elsewhere.
- **Scheduler to Nominatim**, in `NOMINATIM` mode, on the same terms.
- **Scheduler to a remote solver pool:** already https with a service token for any non-loopback address.

Also: store tenant tokens only in a secret store and never log them; the management port (9000, metrics and health) stays on the private network only; and the scheduler VM accepts API traffic only from WaterFlex Software's backend. The runbook's TLS section is the operator's checklist for the same work.

## Contract versioning

The API is version 1 (`/api/v1`, spec version 1.8.0). Within v1, changes are additive only and raise the minor version: a new endpoint, a new optional request field, a new response field, a new `error` code or enum value. Each is listed in the spec's version history and the change table in `docs/stateless-api-design.md` before it is deployed. A reader that rejects unknown response fields (as the portal and the conformance kit do, to catch drift) must take each new minor version before the scheduler is upgraded; a lenient reader needs no change. A breaking change (a removed or renamed field, a new required request field, a changed meaning) is published as `/api/v2` beside v1, never in place, and v1 keeps being served until every host has moved. The one exception is narrowing a value's range to bounds no host sends, confirmed with WaterFlex Software before it is deployed: spec 1.7.0 narrowed the cost rates so.
