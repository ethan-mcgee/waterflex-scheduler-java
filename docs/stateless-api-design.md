# Stateless scheduling API for WaterFlex Software

Status: proposal for owner review. Nothing in this document is implemented yet unless it says "exists today".

## Goal and decision

The scheduler becomes an add-on to WaterFlex Software. WaterFlex Software owns master data (customers, addresses, jobs, appointments, technicians, qualifications, shifts, time off, depots) and the business record of every appointment. The scheduler owns only what it must to keep customer promises safe under concurrency:

- booking holds and reservation arrangements,
- offer sets and proposals it issued, keyed by host IDs and an input revision hash,
- attempt and outcome receipts.

Calculation (solver) and routing are stateless: complete data in, results out. This is the "thin scheduler store" option chosen by the owner on 2026-10-07.

## What exists today

| Piece | State |
| --- | --- |
| Strict daily dataset | `DailyDataset` parses a complete day (technicians, visits, windows, rates, policy versions, indexed locations and directed roads) with duplicate-key, unknown-field, index and road-completeness checks. |
| Strict booking dataset | `BookingDataset` encodes a complete booking snapshot the same way. |
| Calculation service | `solver-service` accepts a hashed `CalculationProtocol.Request` (`DAILY` or `BOOKING`), runs the shared `calculation-engine`, and returns a proposal. No database. Bearer token auth, admission limits, `DELETE /v1/solves/{id}` cancellation. |
| Routing service | `routing-service` turns points into directed legs (`/internal/matrix`, `/internal/legs`) with a routing identity hash. No database; in-memory cache only. |
| Caller | `scheduler-service` reads its own copy of master data from Postgres (about 40 tables), calls routing, calls the engine (embedded by default, remote optional), revalidates the result, persists previews, reservations and appointments, and applies under locks. |

So a request-fed calculation API already works internally. What is missing is a host-facing contract keyed by WaterFlex Software IDs, removal of the scheduler's master data copy, and a commit protocol in which the host owns the business record.

## Target architecture

```
WaterFlex Software  --(snapshot + request)-->  scheduler API  --(dataset)-->  calculation engine
        ^                                           |  ^
        |                                           |  +--(points)--> routing service
        +------(proposal / offers / receipts)-------+
                                                    |
                                              thin store (holds, offers, proposals, receipts)
```

The scheduler API is the only public surface. Calculation and routing stay private.

## Public contract v1

All requests carry `tenantId`, a host `requestId` (idempotency key) and host IDs only. Money and rates are decimal strings. Times are ISO-8601 instants. Unknown fields, duplicate keys, nonfinite numbers and missing required values are rejected, as `DailyDataset` does today.

### Snapshot

Every calculating call carries a snapshot of the facts it needs:

- technicians: host ID, departure and return location, shift, paid and overtime limits, qualifications, absences, and a host `version` per technician-day,
- appointments already committed for those technician-days: host ID, service, location, window, duration, assigned technician, sequence,
- rates and policy settings, or a reference to a versioned settings document,
- locations as coordinates (the scheduler calls routing) or, optionally, precomputed directed legs with a routing identity.

The scheduler computes a canonical content hash of the snapshot (the input revision). Every proposal and offer records it.

### Endpoints

| Endpoint | Purpose |
| --- | --- |
| `POST /v1/daily/proposals` | Snapshot of one metro day. Returns a proposal ID, proposed routes, unresolved demand, policy decision and diagnostics. Stores the proposal and revision in the thin store. |
| `POST /v1/daily/proposals/{id}/commit` | Host sends its current snapshot (or revision hash plus technician-day versions). Scheduler revalidates (cutoff, promises, zero overtime, independent `RouteEvaluator`, holds) and returns a commit receipt with the final assignments and times. 409 if the revision changed. |
| `POST /v1/booking/offers` | Snapshot of the booking horizon plus the job to book. Returns up to four offers and creates holds in the thin store. |
| `POST /v1/booking/offers/{id}/select` and `/release` | Convert or release holds, as today. |
| `POST /v1/booking/holds/{id}/confirm` | Host sends its current snapshot; scheduler revalidates against holds and returns the arrangement to write. |
| `POST /v1/repairs/proposals` | Absence repair for a technician-day, same pattern as daily. |
| `DELETE /v1/requests/{requestId}` | Best-effort cancellation of an in-flight calculation. |

### Commit ownership and concurrency

The scheduler cannot lock rows it does not own, so commits use optimistic concurrency on both sides:

1. The host includes a `version` per technician-day in every snapshot and increments it whenever anything on that day changes.
2. A proposal or offer records the versions it was computed from.
3. On commit or confirm, the scheduler requires the submitted versions to equal the recorded ones, revalidates, and atomically updates its own holds and receipts.
4. The host then writes the business record with a compare-and-set on the same versions. If that fails, the host calls release, and the scheduler retains the failed receipt.

This keeps the audit's rule (caller-side locked revalidation before apply) without the scheduler owning appointments. Step 4 makes the host responsible for the final atomic write, which is unavoidable once it owns the data.

## Thin store

Keep: `slot_hold`, `reservation_arrangement`, `reservation_dependency`, `booking_offer_set`, `booking_offer`, `booking_search_request`, `optimization_run` (as proposals), `daily_calculation_attempt`, `overnight_optimization_attempt`, `road_route_cache` (or move it into routing-service). Add `tenant` and host-ID columns. Remove: customer, address, job, appointment, technician and related master data tables, after migration.

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

## Open questions for the owner

1. Does WaterFlex Software keep a version (or updated-at) per technician-day that can serve as the optimistic concurrency token? If not, it needs one.
2. Who geocodes addresses: WaterFlex Software, or the scheduler using the existing validated geocoding path?
3. Is one deployment per customer acceptable, or does the API need multi-tenant isolation from day one?
4. Should repair be allowed to add dispatcher-approved overtime? The approval flag exists but cannot take effect today (see `docs/scheduler-policy.md`).
5. What latency target applies to `POST /v1/booking/offers` once the snapshot crosses the network (today's budget is five seconds end to end)?
