# API load measurement

How the scheduler behaves under concurrent host traffic, measured over its public API with the load tool
(`infra/host-conformance/load.ts`, described in its [README](../infra/host-conformance/README.md#load-tool)). The raw
reports are in [docs/evidence/api-load-2026-10-13.json](evidence/api-load-2026-10-13.json).

## Protocol

- **Stack:** one `scheduler-service` jar (revision 6abf872 plus this PR's fix; embedded calculation;
  `SCHEDULER_SEARCH_CAPACITY=2`, as the VM compose sets) on its own Postgres, and the Omaha `routing-service`
  (map 2026-10-13, flexible mode, CH off).
- **Machine:** scheduler, routing, database and load generator all ran on one 24-CPU machine (AMD Ryzen 9 7900X,
  63 GiB). This is not the VM's partitioned share, so treat absolute numbers as an upper bound on what the VM will do.
  The comparisons between runs are what carry over.
- **Workload:**
  - Booking: 10 technicians on each of 5 dates (two days ahead and later), each with 2 visits already booked. Each search
    is for a new 60-minute job; `offerLimit` 4, `windowHours` 2, `bookingDeadlineMs` 5000. 30 percent of searches are
    booked (select, confirm, then a compare-and-set receipt write in the simulated host); the rest release their offers.
  - Daily: one day of 20 technicians with 2 visits each, 5 s of cost search and no fairness phase.
- **Runs:** each lasts 60 s and has its own seed, so no run reuses another's points or cached routes. `cold` places
  every job at a new point; `warm` cycles 8 points.
- **Measures:**
  - caller-side latency, including waits on 429 `BUSY` (which a host must retry);
  - server time per route and status from `http_server_requests_seconds`;
  - routing pairs fetched;
  - scheduler CPU and heap (Prometheus) and routing container CPU and memory (`docker stats`).

**Acceptance (latency target):** booking offers p95 within the client's `bookingDeadlineMs` plus 500 ms at the caller.

## Results

| Run | Searches per min | Offers p50 / p95 / p99 ms | Confirm p50 / p95 ms | Holds lost at confirm | Searches that waited on 429 | Routing pairs per search | Routing CPU mean / max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| booking cold, 1 desk | 76 | 701 / 987 / 3254 | 31 / 50 | 0 of 19 | 0 | 289 | 55 / 78 percent |
| booking cold, 5 desks | 97 | 1824 / 6233 / 11212 | 670 / 1093 | 14 of 38 | 35 | 285 | 54 / 136 percent |
| booking cold, 10 desks | 94 | 3442 / 12006 / 23908 | 2033 / 3133 | 23 of 39 | 43 | 277 | 54 / 130 percent |
| booking warm, 5 desks | 115 | 590 / 10207 / 14392 | 249 / 334 | 7 of 35 | 35 | 48 | 16 / 144 percent |
| mixed, 5 desks and 1 planner | 85 | 1989 / 7018 / 11983 | 715 / 1983 | 10 of 30 | 34 | 322 | 70 / 187 percent |

| Run | Proposals | Answered 201 | 503 `CALCULATION_UNAVAILABLE` | p50 ms |
| --- | ---: | ---: | ---: | ---: |
| daily, 1 planner | 12 | 11 (all `IMPROVED`) | 1 (the first, cold routing) | 5063 |
| daily, 4 planners | 28 | 3 | 25 (queued) | 9028 |
| mixed, 1 planner beside 5 desks | 6 | 2 | 4 | 10015 |

Every search answered 201 with 4 offers, and no response broke the contract. The scheduler process used 4 to 8
percent of the machine and at most 0.9 GiB of heap. The routing service did most of the CPU work.

- **One desk meets the latency target:** p95 987 ms against a 5500 ms bound.
- **Five or more desks miss it:** the cause is contention between bookings on the same dates (finding 3), not
  computation. Each attempt still answered within the deadline at the server (mean 1.4 s at 5 desks, 2.6 s at 10).
- **Cold routing costs about 1.2 s per search at 5 desks:** cold p50 1824 ms against warm 590 ms, with 285 against
  48 routing pairs per search. It is the largest part of a search's own time, which PR J addresses.

## After the booking concurrency fixes

The booking and mixed lines of the protocol, run again on the same stack with findings 2 and 3 fixed (#152) and new
seeds (21 to 25). Raw reports: [docs/evidence/api-load-2026-10-13-booking-concurrency.json](evidence/api-load-2026-10-13-booking-concurrency.json).
The load tool now does what the integration guide asks of a host: a search or confirm answered 409 `STALE` is sent
again from current records with a new `requestId`. Latency is per call, so a resent search counts as two calls.

| Run | Searches per min | Offers p50 / p95 / p99 ms | Confirm p50 / p95 ms | Holds lost at confirm | Calls resent after `STALE` | Calls that waited on 429 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| booking cold, 1 desk | 151 | 344 / 636 / 743 | 21 / 25 | 0 of 45 | 0 | 0 |
| booking cold, 5 desks | 147 | 973 / 2177 / 3905 | 709 / 1695 | 0 of 50 | 115 | 10 |
| booking cold, 10 desks | 154 | 786 / 3399 / 4616 | 1225 / 2841 | 0 of 56 | 289 | 14 |
| booking warm, 5 desks | 156 | 973 / 2660 / 5000 | 665 / 1329 | 0 of 41 | 98 | 17 |
| mixed, 5 desks and 1 planner | 131 | 898 / 2722 / 3412 | 735 / 1760 | 0 of 49 | 113 | 3 |

- **No hold was lost** in any run (before: 37 percent of selected holds at 5 desks, 59 percent at 10), and the
  deterministic reproduction of finding 2 now ends with desk 2's confirm answering 200.
- **Latency improved, by less than the table shows.** Those runs were the third pass over seeds 21 to 25, so their
  routes were already cached. Run again on fresh seeds, as the baseline was (evidence, `coldRemeasurement`):

  | Desks | Baseline offers p95 | #152 offers p95, fresh seeds | Searches per minute, baseline / #152 |
  | ---: | ---: | ---: | ---: |
  | 1 | 0.99 s | 0.72 s | 76 / 87 |
  | 5 | 6.2 s | 2.6 s | 97 / 125 |
  | 10 | 12.0 s | 5.0 s | 94 / 117 |

  Every desk count is now within the 5.5 s bound at the caller, 10 desks only just. Compare load runs on fresh seeds
  only: a seed run before makes a one-desk search about 250 ms faster.
- **The cost is resends.** A search built from records that another desk's receipt overtook before it arrived is
  answered `STALE` and sent again: about 0.8 resends per answered search at 5 desks, 1.7 at 10. Each resend is a new call, and a `STALE` answer still
  took 0.4 to 0.6 s at the server: it is decided before the search, but the scheduler first builds the request's facts
  and checks the routing identity. PR J's routing identity cache removes most of that round trip.
- The first run on the fixed code still lost 9 of 48 holds at 5 desks. It exposed a second kind of late snapshot: one
  read before the host wrote a confirm receipt carries the unchanged timestamp with the route from before the
  receipt. The scheduler now remembers that route until the receipt is written (or the hold lifetime passes) and
  answers such a snapshot with `STALE` too; the runs above include that change.

## Findings

1. **Fixed in this PR: a booking search near its deadline answered 500.** When too little of the deadline was left to
   open a database connection, the scheduler's admission check raised `SearchAdmission.Busy`. Spring's transaction
   manager wrapped it in `CannotCreateTransactionException`, so the booking handler never mapped it to 429 and the
   request claim was kept until its lease ran out. `DeadlineSignals.unwrap` now restores the signal in the tenant and
   route cache transactions (regression test `PublicApiStoreDeadlineTest`).

2. **Fixed in #152: an older snapshot that arrives late loses other desks' holds.** Reproduced
   deterministically:
   1. Desk 1 books and the host writes the receipt.
   2. Desk 2 searches and selects on the new records.
   3. A request built from the records *before* that receipt arrives late.
   4. Desk 2's confirm then answers 409 `HOLD_UNAVAILABLE` "The hold is lost". Without step 3 it answers 200.

   Because `lastModified` is compared only for equality, the scheduler reads the older timestamp as a new host edit of
   a route that holds depend on (reconciliation rule 3) and loses every hold on that date. Any host with concurrent
   booking desks produces such late requests. Here they lost 37 percent of selected holds at 5 desks and 59 percent at
   10. They also raise 429 retries, because the date's state is rebuilt.

   The fix keeps equality-only comparison (so clock skew still cannot matter). The scheduler remembers, per
   technician-day, the last 32 timestamps it has moved past, and the route from before a confirm receipt the host
   has not written yet. A snapshot carrying either is stale and is refused with 409 `STALE` (retry with current
   records) instead of being reconciled as a host change (spec 1.1.0).

3. **Fixed in #152: concurrent bookings in one metro serialize on day versions.** A search or confirm publishes only if every day it
   read still has the version it read. Otherwise it retries once, then answers 429 ("Other bookings kept changing
   these dates"). With 5 or 10 desks booking the same 5 dates, 29 to 42 percent of searches waited on at least one
   429, and caller-side p95 rose to 6 to 12 s although every attempt stayed within the deadline. Finding 2 adds to this.
   Now a search checks only the days its offers hold, or where the job's earlier holds end. A day it only brings up
   to date with the host is skipped when another booking wrote it first. Days it read but did not change are not
   checked at all. Searches for one job publish one at a time, and a moved day runs the search again while the
   deadline leaves time to explore, up to three times. Faster searches (PR J) shorten the window further.

4. **Partly fixed in #153: daily proposals run one at a time per replica, and a queued one ended as a slow 503.**
   Admission let one daily calculation (`BACKGROUND`) run at a time. Others queued for their whole allowance (search
   time plus 5 s), then answered 503 `CALCULATION_UNAVAILABLE` after about 9 s (receipts showed `queueMs` 9005), and
   were not counted in `scheduler.admission.rejections`. The first proposal for a new 20-technician day spent about
   4 s on cold routing and ran past its allowance.

   Now:
   - The day is routed before the calculation asks for a slot, under its own 30 s deadline.
   - Each calculation tells admission how long it expects to hold its slot: its search time plus a 2 s settle
     margin. A request that could not start within its reserve is refused at once with 429 and the estimated wait
     as `Retry-After`, before anything is routed.
   - A request that is still waiting when it could no longer finish leaves the queue with a 429 too.
   - Every such refusal, and any deadline that passes in the queue, counts as an admission rejection.
   - The portal's overnight run waits `Retry-After` and asks for the day again, up to five times.

   Measured again ([evidence](evidence/api-load-2026-10-13-daily-admission.json), seeds 34, 35 and 37):

   | Run | Proposals | Answered 201 | 503 | 429 answers, retried after `Retry-After` |
   | --- | ---: | ---: | ---: | ---: |
   | daily, 1 planner | 11 | 11 (the first included, cold routing) | 0 | 0 |
   | daily, 4 planners | 15 | 15 | 0 | 39 (0.3 s each) |
   | mixed, 1 planner beside 5 desks | 2 | 2 | 0 | 12 (about 3 s each) |

   What remained was the next step of the plan:
   - Retries were a lottery with no fairness between planners or clients: one proposal at 4 planners waited 86 s.
     The durable queue with per-client turns (L4, below) replaces it for hosts that queue.
   - On a replica that also books, queued bookings always go first, so daily work waits while desks are busy.
     Separate booking and daily replicas behind a load balancer (L5) remove that contention.

5. **`searchComplete` is false on nearly every search. Fixed in #154:** `searchComplete` is now false only when the
   deadline or a failure stopped a pass the offers need, and `OfferSet.refinement` (spec 1.2.0) reports a refinement
   cut short at its cap as `SHORTENED`. A 60 s run at 5 desks afterwards answered 123 searches: none reported
   `searchComplete: false`, and all 123 refinements were `SHORTENED` by the cap
   ([evidence](evidence/api-load-2026-10-13-search-outcome.json)). #155 adds `incompleteDates`, sheds the optional
   refinement while booking requests are queued (`SHED`) and lets a client set its own budget. At 5 desks, both builds
   on fresh addresses ([evidence](evidence/api-load-2026-10-13-refinement-shedding.json)):

   | Build | Searches per minute | Offers p50 | Offers p95 | Refinement |
   | --- | ---: | ---: | ---: | --- |
   | #154 | 124 | 1.28 s | 2.56 s | `SHORTENED` 127 |
   | #155 | 256 | 0.66 s | 1.30 s | `SHED` 262, `SHORTENED` 1 |

   Every search in both runs returned 4 offers and none was incomplete; shed searches skip only the optional cost and
   fairness improvement. Compare runs on fresh seeds only: a seed run before leaves its routes in the cache and makes
   a one-desk search about 250 ms faster. The original finding: when the first pass finds more
   distinct windows than `regularWindowThreshold`, the optional refinement gets a 250 ms cap. Reaching that cap marks
   the search incomplete (`REFINEMENT_TIME_LIMIT`), so all 494 searches in these runs reported `false` while returning 4 offers.
   The spec says `false` means "the deadline ended the search early; an empty offer list then does not prove there is
   no capacity", and a host reading it that way learns nothing. Either report `false` only when the deadline (or a
   failure) cut the search short, or change the spec's wording to cover the optional refinement.

## Asynchronous proposals and fair queuing (L4)

A host may now queue a daily or repair proposal with `Prefer: respond-async` and poll its status (spec 1.4.0). Any
replica's worker takes queued requests round-robin across tenants, and booking slots are shared between clients
within a replica. Measured on fresh seeds ([evidence](evidence/api-load-2026-10-13-daily-queue.json)), with two
tenants at once for 90 s: tenant A with 4 daily planners, tenant B with 1, 20-technician days, 5 s searches.

| Mode | Tenant | Proposals | Proposal p50 | Proposal p95 | Wait before the calculation started |
| --- | --- | ---: | ---: | ---: | --- |
| Synchronous, 429 and `Retry-After` | A, 4 planners | 20 | 5.1 s | 105 s | |
| Synchronous, 429 and `Retry-After` | B, 1 planner | **1** | 109 s | 109 s | |
| Queued (`Prefer: respond-async`) | A, 4 planners | 12 | 39.2 s | 45.2 s | p50 32.5 s, max 38.3 s |
| Queued (`Prefer: respond-async`) | B, 1 planner | **9** | 9.1 s | 15.1 s | p50 3.9 s, max 5.8 s |

- **Synchronously, one client starved another.** Tenant B's single planner got 1 proposal in 109 s, because tenant A's
  four planners took each freed slot first while B waited out its `Retry-After`. Queued, B's planner got a proposal
  every 9 to 15 s: B waits only for the calculation running when it asks, whatever A has queued.
- **Throughput is unchanged:** 21 proposals in both modes. A replica still runs one daily calculation at a time, so the
  queue shares the same work out fairly rather than adding any. More daily capacity is the separate replicas of L5.
- **Beside booking desks, queuing costs bookings nothing.** One tenant, 5 desks and 2 daily planners for 60 s: queued,
  9 daily proposals (p95 30 s) against 10 synchronous (p95 73 s), and 187 against 192 searches. A first build whose
  worker waited for an empty booking queue managed only 6 proposals (bookings rose to 221 searches); the worker now
  waits only while a daily calculation runs or waits on its replica, and admission still puts bookings first.
- **Any replica serves the queue.** With a second replica running no workers on the same database, the conformance
  kit's daily and cancellation cases sent through it all passed: its queued proposals ran on the first replica, and a
  cancel sent to it stopped a proposal running there.

## Replica roles (L5)

The same jar now runs as booking replicas (`SCHEDULER_ROLE=booking`, no queued daily work) and daily replicas
(`SCHEDULER_ROLE=daily`, several daily calculations at once with `SCHEDULER_SEARCH_BACKGROUND_LIMIT`) behind one
address; `deploy/vm/` puts nginx in front of the two pools. Measured on fresh seeds at **equal total search capacity
(4 slots)** ([evidence](evidence/api-load-2026-10-14-replica-roles.json)): one `all` replica (capacity 4, one daily
calculation at a time) against a booking replica (capacity 2) plus a daily replica (capacity 2, two at a time). Load:
5 booking desks and 5 queued daily planners (20-technician days, 5 s searches) for 60 s, as two load tool processes.
Pairs ran in the order all, split, split, all.

| Shape | Run | Searches per minute | Offers p95 | Offers p99 | Offers waited on 429 | Confirm p50 / p95 | Daily proposals | Proposal p50 / p95 | Queue wait p50 |
| --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- | ---: |
| One `all` replica | 1 | 241 | 1746 ms | 2495 ms | 15 | 136 / 515 ms | 13 | 27.2 / 36.4 s | 20.7 s |
| One `all` replica | 2 | 245 | 1804 ms | 3442 ms | 24 | 122 / 480 ms | 14 | 27.1 / 36.4 s | 20.1 s |
| Booking + daily replicas | 1 | 226 | 1430 ms | 2084 ms | 4 | 379 / 810 ms | **25** | 15.1 / 18.3 s | 7.5 s |
| Booking + daily replicas | 2 | 222 | 1382 ms | 2392 ms | 4 | 440 / 795 ms | **25** | 15.1 / 18.3 s | 7.4 s |

- **Daily throughput nearly doubles** (13 and 14 to 25 and 25 proposals), and a queued day waits a third as long
  (20 s to 7.5 s), because the daily replica runs two solves at once while the `all` replica runs one at a time.
- **Bookings keep their tail.** Booking offers p95 fell from about 1.8 s to 1.4 s, and calls refused with 429 and sent
  again fell from 15 to 24 down to 4. No daily solve competes for the booking replica's cores or slots.
- **The cost is booking throughput and confirm latency.** With 2 booking slots instead of up to 4, searches per
  minute fell about 8 percent and confirm p50 rose from about 130 ms to about 400 ms (confirms wait behind searches
  for a slot). Give the booking pool more replicas or capacity when that matters; the pools now scale apart.
- **Cancellation reaches every replica.** On the local VM rehearsal with two replicas per pool behind nginx, the
  conformance kit passed all 27 cases, and in its repeated cancellation cases 4 of 8 synchronous cancels reached a
  replica other than the one running the call; that replica's watcher stopped the call, which answered 409 `CANCELLED`.
  nginx found the replicas added while it ran without a restart.

## A baseline on the VM's shape, with the time attributed (J0)

Every number above came from one 24-core machine running the scheduler, routing, the database and the load generator
at about 10 percent CPU, which measures how calls share slots, not what a partitioned VM can do. This baseline runs
the `deploy/vm` rehearsal stack with its CPU limits (2 cores per scheduler pool, 1 for the database; the routing
service unlimited and in flexible mode), and the request log line now says where each call's time went
(`queueMs`, `routingMs`, `calculationMs`, `dbMs`, with the routing pairs, cache hits and fetched legs). The load tool's
new `--date-spread` gives each desk its own dates, so the 409 `STALE` resends of earlier runs (38 percent of answers
in L5) are gone and the numbers are the scheduler's. Load: 5 booking desks and 5 queued daily planners for 60 s
([evidence](evidence/api-load-2026-10-14-vm-baseline.json)).

| Call | Calls | Caller p50 / p95 | Server p50: slot wait | routing | engine | database | pairs needed / fetched |
| --- | ---: | --- | ---: | ---: | ---: | ---: | --- |
| Booking offers (201) | 289 | 837 / 1341 ms | 424 ms | 353 ms | 12 ms | 28 ms | 511 / 239 |
| Confirm (200) | 79 | 454 / 773 ms | 432 ms | 5 ms | 0 ms | 4 ms | 61 / 0 |
| Daily proposal, queued | 25 | 15.1 / 18.4 s | queue wait p50 7.8 s | | 5 s search | | |

- **Half of a search is the slot wait.** With 5 desks on a 2-slot booking replica, a search waits 424 ms p50 for a
  slot before it starts; a confirm, which needs 13 ms of work, waits 432 ms behind the searches. Confirms should go
  ahead of searches in admission; that is a few lines, measured next.
- **The other half is routing, and it is cold legs.** Each search needs about 511 directed legs; 239 are fetched from
  the routing service at about 1.5 ms each (the service routes a batch's pairs one after another in flexible mode,
  and the scheduler feeds it through two worker threads). The routing container ran at 166 percent CPU for the
  booking replica's 63 percent: routing, not the scheduler, is where the CPU goes. CH plus concurrency on both sides
  is the next change (J).
- **The engine is not the booking lever.** The insertion and refinement passes take 12 ms p50 (40 ms p95). The
  booking replica's request threads used 34 ms CPU per search; the rest of its CPU is JSON, routing clients and GC.
- **The daily replica is CPU bound by design** (180 percent of its 2 cores: two 5 s searches at a time). More daily
  throughput is more daily replicas or cores, or faster moves per second inside the budget (L).

## Routing: the CH graph, parallel pairs and fewer round trips (J)

The baseline said a search spends half its time waiting for cold legs from a routing service that routes a batch's
pairs one after another through a flexible A* graph, with a `/health` round trip before every lookup and two leg
workers in the scheduler. J changes all four: each map version now carries a contraction hierarchy graph beside the
flexible one (`graph-ch-car-v1`, prepared by `infra/prepare-map.ps1`, listed in the manifest and required at
activation) and the routing service serves it by default; the routing service routes a request's pairs on
`ROUTING_PARALLELISM` threads at once; the scheduler trusts a fetched routing identity for `ROUTING_IDENTITY_TTL_MS`
(2 s; the service still refuses a call with another identity with 409, which drops the remembered one); and the
scheduler fetches leg batches on 8 workers with a queue of 64. Same stack and load as the baseline, run twice with
the same scheduler image: once against the flexible graph and once against the CH graph
([evidence](evidence/api-load-2026-10-14-routing.json); the routing API alone, 132 Omaha pairs uncached on both
graphs, is in [routing-ch-2026-10-14.json](evidence/routing-ch-2026-10-14.json): exact parity on every directed leg,
16-pair requests 29.6 ms to 3.7 ms p50 at concurrency 1 and 118.9 ms to 8.1 ms at concurrency 10).

| Run | Offers in 60 s | Caller p50 / p95 | Server p50: slot wait | routing | per fetched leg | Confirm caller p50 / p95 | Routing container CPU |
| --- | ---: | --- | ---: | ---: | ---: | --- | ---: |
| Baseline (flexible, before J) | 289 | 837 / 1341 ms | 424 ms | 353 ms | 1.48 ms | 454 / 773 ms | 166 percent |
| J, flexible graph | 454 | 493 / 746 ms | 228 ms | 197 ms | 0.77 ms | 265 / 503 ms | 1324 percent |
| J, CH graph (default) | 1020 | 216 / 353 ms | 94 ms | 65 ms | 0.23 ms | 116 / 214 ms | 261 percent |

- **3.5 times the searches at a quarter of the latency, with no errors and no change in what is offered.** Every
  search still finishes complete; the engine and database shares are unchanged (12 ms and 33 ms p50), so the whole
  gain is routing and the slot wait that routing was holding.
- **CH is the lever; parallel flexible search is not.** Routing a batch's pairs in parallel on the flexible graph
  halves the routing time but spends 13 cores doing it (1324 percent CPU on an unlimited container); the same
  parallelism on the CH graph answers in a third of the time on 2.6 cores. On a VM with a routing service capped at a
  few cores, flexible mode would be far worse than this run shows. The CH graph costs 27 s and 2.7 million shortcuts
  at import on a 12-core machine and about 200 MiB more on disk per version.
- **The routing identity changes with the graph.** CH on and CH off are different identities (the `/health`
  detail), so switching one way or the other starts every route cache cold, exactly like a map change.
- **The daily replica is unchanged** (25 queued proposals, 15.1 s p50, queue wait p50 7.1 to 7.8 s): its work is the
  5 s search, not routing.
- **Next.** The booking replica now spends 94 ms p50 waiting for a slot and 65 ms routing; the confirm still waits
  behind searches (98 ms of its 112 ms). Confirm priority in admission and the JVM settings are the next measured
  change; after them the slot wait is the booking replica's CPU, which is more cores or replicas.

## Confirm priority and JVM settings (J2)

After J a confirm, which does a few milliseconds of work to complete a booking the customer already chose, still
spent 98 of its 112 ms waiting for a search slot behind searches. J2 changes admission in two steps and then measures
the JVM flags the compose file sets, on the same stack and load as J's CH run
([evidence](evidence/api-load-2026-10-14-confirm-priority-and-jvm.json); a confirm's own CPU time is 3 ms p50 in
every run, from the `cpuMs` field of the request log).

1. **Confirm priority.** A confirm is a booking ticket with priority: `SearchAdmission.next` chooses waiting confirms
   before waiting searches (among confirms the same fairness rule as among searches: the client with the fewest
   running, then the one whose last booking started longest ago), a full queue never evicts a confirm, and an arriving
   confirm takes the heaviest client's newest waiting search's place.
2. **A confirm-only slot.** `SCHEDULER_SEARCH_CONFIRM_SLOTS` (default 1, at most the capacity) opens slots beyond the
   search capacity that only confirms may take, so a confirm does not wait for a running search to finish either.

| Run | JVM flags (booking / daily) | Confirm caller p50 / p95 | Confirm server p50: slot wait | Offers caller p50 / p95 | Searches in 60 s | Booking replica CPU, heap max | Daily proposal caller p50 |
| --- | --- | --- | ---: | --- | ---: | --- | ---: |
| J, CH graph (before) | default (G1) | 116 / 214 ms | 98 ms | 216 / 353 ms | 1020 | 135 percent, 0.36 GiB | 15.1 s |
| J2 step 1, priority | default | 89 / 122 ms | 71 ms | 213 / 341 ms | 1086 | 135 percent, 0.38 GiB | 12.4 s |
| J2 step 1, ZGC / Parallel | `-XX:+UseZGC` / `-XX:+UseParallelGC` | 87 / 142 ms | 65 ms | 210 / 331 ms | 1084 | 154 percent, 1.25 GiB | 12.4 s |
| J2 steps 1 and 2, confirm slot (shipped) | default | 20 / 35 ms | 0 ms | 226 / 307 ms | 1161 | 145 percent, 0.39 GiB | 12.3 s |
| J2 steps 1 and 2, compact headers | `-XX:+UseCompactObjectHeaders` both | 20 / 32 ms | 0 ms | 228 / 320 ms | 1174 | 124 percent, 0.34 GiB | 12.3 s |

- **A confirm now answers in 20 ms p50 and 35 ms p95 at the caller, from 116 and 214 ms.** Priority alone took the
  wait from 98 to 71 ms (the confirm still waited for one of the two running searches to finish); the confirm slot
  took it to 0. Offers are unchanged within run-to-run variation (p50 213 to 228 ms across the five runs) and every
  search still completes with four offers and no errors; the replica served 14 percent more searches in the 60 s
  because the desks spent less time in confirms. Both steps ship as defaults.
- **The garbage collector is not a lever here, so G1 stays.** ZGC on the booking replica is within 3 percent on
  offers p50 and p95 and worse on confirm p95 (142 against 122 ms), offers p99 (820 against 551 ms), CPU (154 against
  135 percent) and heap (1.25 against 0.38 GiB used; 1.68 GiB resident). Parallel GC on the daily replica changes
  nothing the load tool can see: a queued 20-technician day is 6 s of queue wait plus a fixed 5 s search, so a GC
  difference of a few milliseconds per solve is below the measurement. A move-rate comparison inside the solver
  (study L's harness) is the right instrument for that replica, not this one.
- **Compact object headers (`-XX:+UseCompactObjectHeaders`, a product flag since JDK 25, JEP 519) are within noise on
  latency** (offers 228 / 320 against 226 / 307 ms, confirm 20 / 32 against 20 / 35 ms) with the heap 0.05 GiB
  smaller and the booking replica's mean CPU 21 points lower in this run; the CPU figure moved by as much between
  runs with identical flags (135 to 154 percent), so it is not counted as a win. The threshold set before the runs
  was 5 percent on a latency figure; nothing met it, so the compose keeps the JDK defaults and records both pools'
  flags as settings (`SCHEDULER_BOOKING_JAVA_OPTIONS`, `SCHEDULER_DAILY_JAVA_OPTIONS`) for the operator who wants
  to measure them on the VM itself.
- **A measurement hazard worth knowing.** `deploy/vm/docker-compose.yml` declares `pull_policy: build`, so every
  `up --force-recreate` rebuilds the image from the working tree; one run (seeds 713/714) unknowingly carried a later
  commit and was repeated on the committed image before anything was concluded from it.
- **Next.** With confirms out of the way, the booking replica's remaining wait is searches queueing for the two
  slots (117 ms p50 of a 221 ms search), which is CPU: more cores, more replicas, or a cheaper search (study L).

## Embedded against remote calculation (K)

The design's third scaling lever moves the calculation out of the scheduler into a solver pool
(`SCHEDULER_CALCULATION_MODE=REMOTE`). It had never been measured. This run compares the two on the rehearsal
stack with the same load as J2's runs, the solver pool at its compose shape (4 CPUs, 4 GiB) beside the booking and
daily replicas at 2 CPUs each ([evidence](evidence/api-load-2026-10-14-embedded-vs-remote.json)). The acceptance set
beforehand was remote p95 at most 1.05 times embedded.

| Run | Offers caller p50 / p95 | Offers server p50: slot wait | calculation | Searches in 60 s | Daily proposal p50 (25 queued) | Daily replica CPU | Solver pool |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| Embedded (default) | 244 / 357 ms | 124 ms | 15 ms | 1058 | 15.1 s, 25 of 25 | 185 percent | none |
| Remote, previous pool defaults | 352 / 485 ms | 187 ms | 64 ms | 748 | 15 of 18 answered 503 | 43 percent | capacity 2, one daily at a time |
| Remote, capacity 4 | 356 / 556 ms | 190 ms | 63 ms | 753 | 13 of 18 answered 503 | 33 percent | capacity 4, one daily at a time |
| Remote, capacity 4, every slot open to daily work | 374 / 504 ms | 201 ms | 69 ms | 703 | 15.1 s, 25 of 25 | 36 percent | the new defaults |

- **Booking fails the acceptance; the embedded solver stays the default.** A remote search costs 1.5 times the
  embedded one at the caller (p50 374 against 244 ms, p95 504 against 357 ms) and the replica serves two thirds of
  the searches. The calculation share goes from 15 to 69 ms: a search makes up to three solver calls (insertion,
  refinement, and the retry on a moved day), each a snapshot encoded, sent, decoded and answered, and the replica
  spends 20 ms more CPU per search on that encoding (`cpuMs` 37 to 57 ms). Nothing else changes: routing, the
  database and the confirm (16 ms, no calculation) are the same.
- **For a daily pool, remote is the lever it was meant to be.** The proposal takes the same 15.1 s p50 (queue wait
  plus a fixed 5 s search) and the daily replica's CPU falls from 185 to 36 percent: the solve runs in the pool. Each
  pool has its own `SCHEDULER_CALCULATION_MODE`, so the daily pool can go remote while booking stays embedded; set
  the mode and URL on the daily replica alone (an override file next to `deploy/vm/docker-compose.yml`).
- **A finding and a fix.** With the pool's previous defaults most daily proposals answered 503
  `CALCULATION_UNAVAILABLE`: `SolverApplication` built its admission with the scheduler's default of one daily
  calculation at a time, whatever the capacity, so the second of the two solves a daily replica admits waited behind
  the first and was refused once it could no longer finish in its allowance. Raising the capacity alone did nothing.
  The pool now takes `SOLVER_BACKGROUND_LIMIT` (0, the default, opens every slot to daily work; a pool protects
  nothing else) and its default capacity is 4, the sum of the replicas' search capacities on this shape. The rule
  for an operator: a pool's capacity is the replicas' search capacities summed, and its background limit at least
  the daily replicas' background limits summed.
- **The L engine on the VM shape.** The embedded run is the first measurement of study L's scoring treatment behind
  the API: a 5 s search is a 5 s search, so the proposal latency is unchanged by design; what changed is how many
  moves it evaluates (docs/solver-benchmarks.md).

## Dynamic booking horizon (H)

A client's booking horizon can follow its schedule: through two weekdays past the furthest weekday whose route spans
fill 85 percent of the technicians' open time, at most 15 weekdays (`web/lib/bookingHorizonCore.ts`). This study
compares it with the fixed 10 weekdays on the local compose stack, Omaha routing, with `infra/run-horizon-study.ps1`
([evidence](evidence/api-load-2026-10-16-dynamic-horizon.json)): 120 s cold booking runs, 1 and 5 desks, 3 seeds a
cell. Low volume is 6 technicians with 1 visit a day; high demand is 3 technicians with 2, the first 12 weekdays
booked out at 4. Every desk confirms 30 percent of its searches, so the schedule fills as a run goes on. The kit's
technicians work weekends too, so a fixed 10 weekdays is 13 dates here.

| Cell | Dates searched | Searches per minute | Refinement | Offers per search | Searches with no offer | Search p50 / p95 |
| --- | --- | ---: | --- | ---: | ---: | --- |
| Low, fixed, 1 desk | 13 | 161 | SHORTENED 100 percent | 4.00 | 0 | 354 / 381 ms |
| Low, dynamic, 1 desk | 3 to 4 | 190 | SHORTENED 53, COMPLETE 47 percent | 3.96 | 0 | 303 / 332 ms |
| High, fixed, 1 desk | 13 | 155 | SHORTENED 99 percent | 4.00 | 0 | 372 / 395 ms |
| High, dynamic, 1 desk | 18 to 19 | 146 | SHORTENED 100 percent | 4.00 | 0 | 394 / 423 ms |
| Low, fixed, 5 desks | 13 | 645 | SHED 99 percent | 4.00 | 0 | 350 / 1047 ms |
| Low, dynamic, 5 desks | 3 growing to 12 | 628 | SHED 98 percent | 4.00 | 0 | 282 / 1676 ms |
| High, fixed, 5 desks | 13 | 610 | COMPLETE 54, SHED 46 percent | 1.86 | 1892 of 3677 (51 percent) | 476 / 788 ms |
| High, dynamic, 5 desks | 19 to 20 (the cap) | 523 | SHED 91, COMPLETE 9 percent | 3.66 | 195 of 3159 (6 percent), all at the cap | 416 / 1556 ms |

No search failed, none answered `searchComplete: false`, no date was reported incomplete, the scheduler rejected
nothing at admission and no run found a contract breach. Scheduler CPU stayed at 4 to 8 percent of the machine.

- **The dynamic horizon does what it is for on a booked-out schedule.** With five desks filling three technicians,
  the fixed horizon ran out of room: half its searches offered nothing. Its 1981 COMPLETE refinements track its 1892
  empty searches, which have little or nothing to refine (the tally does not join the two per search). The dynamic horizon kept moving past the filled days and offered on
  94 percent of searches; its 195 empty searches came once it reached the 15-weekday cap (20 dates), which is
  where a sustained rush would stop it too.
- **A longer horizon costs little.** At one desk, 19 dates take 394 ms at p50 against 372 ms for 13, and the
  insertion pass never came near the 5000 ms deadline: the hypothesis of more deadline SHORTENED and incomplete
  dates on long horizons is rejected at this size (3 technicians). SHORTENED at one desk is the 250 ms refinement
  cap, whatever the horizon.
- **A short horizon gets the refinement finished.** At one desk, 3 to 4 dates are 14 percent faster at p50 and the
  refinement completes within its cap on 47 percent of searches instead of none.
- **Shedding follows the queue, not the horizon.** At five desks a booking is nearly always queued, so the optional
  refinement is shed on 91 to 99 percent of searches whatever the horizon length; the hypothesis of fewer SHED on
  short horizons is rejected. Under load the low-volume horizon also stopped being short: as the desks booked, it grew
  from 3 dates to 12, as the rule intends.
- **The five-desk tails are desk contention, not horizon length.** In the low dynamic cell the p95 is 1.5 to 1.8 s
  at every length from 3 dates to 11, and 1133 of 2400 offer calls in one run answered 409 `STALE`: the desks search
  the same dates (no `--date-spread`, which a weekday horizon cannot use), and a short horizon puts all of them on
  the same few days. Each STALE resend counts in the search's time.

## Window threshold (W)

`regularWindowThreshold` (default 2, `20260924010000_scheduling_policy`) decides when a booking search must refine:
when the insertion pass finds no more distinct regular windows than the threshold, the refinement is required, never
shed and never capped, and only `bookingDeadlineMs` (5000) stops it. Otherwise it is optional: shed when other
bookings are queued, and capped at `bookingRefinementMs` (250). This study asks what raising the threshold buys in
the offers and what it costs in throughput, on the local compose stack, Omaha routing, with
`infra/run-threshold-study.ps1` ([evidence](evidence/api-load-2026-10-16-window-threshold.json)). Both profiles are the
horizon study's, on a fixed 10 weekdays (13 dates).

- **Quality** (`quality.ts`): per profile, 2 schedules, each optimized date by date with daily proposals first (every
  date came back `IMPROVED` and was committed), then 20 job addresses searched once per threshold (2, 10, 25, 50,
  1000) on the same records, in a rotated order. The best offer of each search was confirmed and priced with
  `routes/evaluate` against the day before it, and never written.
- **Efficiency** (`load.ts`): 120 s cold booking runs at thresholds 2, 25 and 1000, 1 and 5 desks, 2 repeats,
  interleaved.

### Offer quality

| Profile, schedule | Threshold | Refinement | Best offer adds, mean | Visits reassigned | Same offers as 2 | Earlier / later first offer | Cheaper / dearer | Mean change | Search p50 |
| --- | ---: | --- | ---: | ---: | ---: | --- | --- | ---: | ---: |
| Low, 1 | 2 | SHORTENED 20 | $31.81 | 0 | | | | | 302 ms |
| Low, 1 | 10, 25, 50 | SHORTENED 20 | $31.81 to $31.88 | 0 | 19 to 20 | 0 to 1 / 0 | 0 / 0 to 1 | $0.00 to +$0.07 | 302 ms |
| Low, 1 | 1000 | COMPLETE 20 | $30.94 | 16 | 10 | 1 / 4 | 6 / 1 | -$0.88 | 1999 ms |
| Low, 2 | 2 | SHORTENED 20 | $31.74 | 9 | | | | | 303 ms |
| Low, 2 | 10, 25, 50 | SHORTENED 20 | $31.70 to $32.00 | 2 to 4 | 13 | 3 / 1 to 2 | 1 to 3 / 2 | -$0.04 to +$0.25 | 302 ms |
| Low, 2 | 1000 | COMPLETE 20 | $31.42 | 24 | 5 | 6 / 3 | 9 / 4 | -$0.32 | 2022 ms |
| High, 1 | 2 | SHORTENED 20 | $2.05 | 2 | | | | | 307 ms |
| High, 1 | 10, 25, 50 | SHORTENED 20 | $2.05 to $2.28 | 2 to 3 | 14 to 16 | 0 / 0 | 0 / 0 to 2 | $0.00 to +$0.23 | 306 ms |
| High, 1 | 1000 | COMPLETE 20 | $1.93 | 4 | 13 | 0 / 1 | 2 / 0 | -$0.12 | 567 ms |
| High, 2 | 2 | SHORTENED 20 | $7.23 | 19 | | | | | 306 ms |
| High, 2 | 10, 25, 50 | SHORTENED 20 | $5.17 to $6.10 | 19 to 20 | 15 | 0 to 1 / 1 to 2 | 1 to 2 / 1 to 2 | -$2.06 to -$1.13 | 307 ms |
| High, 2 | 1000 | COMPLETE 20 | $6.88 | 23 | 14 | 1 / 1 | 5 / 1 | -$0.35 | 593 ms |

Every search answered with 4 offers, none answered `searchComplete: false`, and every best offer was priced.

- **Thresholds 10 to 50 never triggered.** All 240 of their searches were SHORTENED, exactly as at 2: on these
  schedules the insertion pass always found more than 50 distinct windows, so the refinement stayed optional and
  capped. Their rows are therefore the run's noise floor: the same code path as threshold 2, differing only in where
  the 250 ms cap happened to stop the search. That noise changes the offers on up to 7 of 20 cases and the mean cost
  by up to $2.06 (high, schedule 2, where one dear outlier moves the mean; the p50 is $4.42 at 2, 10 and 25).
- **Threshold 1000 changes the offers more than the noise does, and the price barely.** Its refinement ran to
  completion on every search and reassigned more existing visits (16 and 24 against 0 and 9 at low volume), but the
  best offer got cheaper by $0.12 to $0.88 a booking: 1 to 3 percent of the $31 a low-volume booking adds, and inside
  the noise floor on high demand. The first offer moved earlier on about as many cases as it moved later (8 earlier,
  9 later over the four schedules). The hypothesis that low-volume offers rarely change is half right: they change,
  but not in a way a customer or the fleet cost would notice. The hypothesis that high demand gains earlier or cheaper
  offers is rejected: 13 to 14 of 20 cases kept the same offers and none of the differences cleared the noise.
- **A booking on a busy day is cheap.** A best offer adds $2 to $7 on the high-demand schedule against $31 on the low
  one: the technician is already in the area, where at low volume a booking adds most of a trip.

### Efficiency

| Profile, desks | Threshold | Searches per minute | Refinement | Search p50 / p95 | Confirmed | Notes |
| --- | ---: | ---: | --- | --- | ---: | --- |
| Low, 1 | 2 | 160 | SHORTENED 100 percent | 362 / 380 ms | 94 | |
| Low, 1 | 25 | 160 | SHORTENED 100 percent | 356 / 380 ms | 103 | |
| Low, 1 | 1000 | 29 | COMPLETE 100 percent | 1984 / 2649 ms | 14 | |
| High, 1 | 2 | 158 | SHORTENED 98 percent | 367 / 385 ms | 96 | |
| High, 1 | 25 | 158 | SHORTENED 100 percent | 366 / 384 ms | 92 | |
| High, 1 | 1000 | 99 | COMPLETE 100 percent | 574 / 758 ms | 58 | |
| Low, 5 | 2 | 638 | SHED 99 percent | 202 / 478 ms | 396 | |
| Low, 5 | 25 | 678 | SHED 99 percent | 203 / 463 ms | 385 | |
| Low, 5 | 1000 | 42 | COMPLETE 51, SHORTENED 49 percent | 4031 / 9044 ms | 24 | 7 searches 503 `CALCULATION_UNAVAILABLE`; 80 of 163 answered `searchComplete: false` |
| High, 5 | 2 | 636 | COMPLETE 58, SHED 42 percent | 265 / 603 ms | 180 | 1409 of 2543 offered nothing (55 percent) |
| High, 5 | 25 | 542 | COMPLETE 50, SHED 50 percent | 261 / 925 ms | 180 | 839 of 2166 offered nothing (39 percent) |
| High, 5 | 1000 | 77 | COMPLETE 100 percent | 1669 / 4022 ms | 41 | none offered nothing; the schedule never filled |

Figures are the mean of the two repeats. The scheduler rejected nothing at admission, no run found a contract breach
or host anomaly, and the scheduler's CPU stayed at 3 to 10 percent of the machine. At 5 desks the desks search the
same dates, so 409 `STALE` resends count in search time, as in the horizon study.

- **One desk:** threshold 1000 cuts throughput by 82 percent at low volume (p50 0.36 to 1.98 s) and 37 percent on
  high demand (0.37 to 0.57 s), with no BUSY waits. The hypothesis held for high demand; at low volume the cost is
  about five times the probe's, because 13 dates of open days give the complete refinement far more to try.
  Threshold 25 is indistinguishable from 2.
- **Five desks:** a required refinement is never shed, so searches queue behind each other. Threshold 1000 runs 42 to
  77 searches a minute against 636 to 638, with a p95 of 4 to 9 s; at low volume half its answers hit the 5 s deadline
  and 7 searches found nothing before it. CPU stayed low, so the queue, not the machine, is the limit. The hypothesis
  held, more steeply than the probe.
- **Threshold 25 matters only once a schedule fills.** At low volume it was inert again. On high demand at 5 desks,
  where three technicians book out within the run, searches with 3 to 25 windows left had their refinement made
  required. COMPLETE outcomes exceeded the searches that offered nothing by about 27 a run at threshold 2 and 118 at
  25 (the tally does not join the two per search), at 15 percent less throughput and a p95 of 925 ms against 603. It
  offered something on more searches (61 against 45 percent), but with the same 180 confirmed bookings in both, the
  run cannot separate a better search from a schedule that took longer to fill.

**Recommendation: keep the threshold at 2.** On an optimized schedule a higher threshold buys at most a dollar a
booking at low volume and nothing measurable on a busy schedule, while 1000 costs a factor of 1.6 to 5.5 in latency
at one desk and of 8 to 15 in throughput under load, with deadline failures. Values from 10 to 50 cost nothing while a
schedule has room, and only start to change searches, at a throughput cost, once it is nearly booked out, which is
where the dynamic horizon (H) already keeps offers coming.

### By fleet size

The same study was repeated at 5, 10, 20 and 30 technicians per date, thresholds 2, 25 and 1000, one repeat of
each load run ([evidence](evidence/api-load-2026-10-16-window-threshold-fleet.json), `-Fleets '5,10,20,30'`). The
profiles are otherwise unchanged. Before the run, the 30-technician self-check found that a cold search on a large
fleet could answer 429 `BUSY`: its routes used most of the deadline, so the refinement was refused a database
connection, and validating some 200 candidates used the rest of the publish reserve. The scheduler now publishes the
insertion pass's offers with the refinement `UNAVAILABLE` (stop reason `REFINEMENT_BUSY`), and validation computes
what no candidate changes once per date; the study ran on that build. No run found a contract breach or host
anomaly, the scheduler rejected nothing at admission, and its CPU stayed under 11 percent of the machine.

**Offer quality.** Both schedules of each profile together, 40 cases; the threshold 25 column is the noise floor.

| Profile, technicians | Best offer adds at 2 | 1000: same offers as 2 | Earlier / later first offer | Cheaper / dearer | Mean change at 1000 | Mean change at 25 | Search p50 at 2 / 1000 | 1000 incomplete |
| --- | ---: | ---: | --- | --- | ---: | ---: | --- | ---: |
| Low, 5 | $31.71, $31.82 | 18 | 14 / 4 | 20 / 3 | -$0.37, -$0.85 | -$0.38, +$0.02 | 286 / 927 ms | 0 |
| Low, 10 | $31.81, $31.98 | 18 | 6 / 6 | 17 / 2 | -$0.47, -$0.14 | -$0.44, +$0.04 | 309 / 2290 ms | 0 |
| Low, 20 | $31.66, $31.65 | 19 | 5 / 12 | 24 / 0 | -$0.44, -$0.37 | -$0.01, +$0.21 | 361 / 4054 ms | 24 |
| Low, 30 | $31.52, $31.71 | 13 | 11 / 8 | 16 / 7 | -$0.24, -$0.12 | -$0.02, +$0.02 | 425 / 4071 ms | 31 |
| High, 5 | $27.01, $25.11 | 5 | 16 / 14 | 19 / 11 | -$0.60, +$0.58 | +$0.45, +$0.73 | 314 / 1314 ms | 0 |
| High, 10 | $30.81, $30.01 | 4 | 27 / 9 | 29 / 10 | -$1.89, -$1.77 | -$0.30, +$0.71 | 360 / 2097 ms | 0 |
| High, 20 | $30.77, $29.98 | 0 | 31 / 7 | 30 / 10 | -$2.34, -$1.38 | +$0.92, +$0.19 | 461 / 2874 ms | 0 |
| High, 30 | $31.07, $27.08 | 12 | 17 / 7 | 22 / 4 | -$1.21, -$1.27 | +$2.40, +$1.77 | 596 / 3556 ms | 6 |

Every search answered 201 with 4 offers and every best offer was priced. At 2 and 25 every refinement was
SHORTENED; at 1000 it was COMPLETE except where the deadline stopped it (the incomplete column, answered
`searchComplete: false`). The two figures in a cell are the two schedules; p50s are their mean.

**Efficiency.** Searches per minute at thresholds 2 / 25 / 1000, with search p50 (and p95 at 5 desks).

| Profile, technicians | 1 desk | p50 at 2 / 1000 | 1000 incomplete | 5 desks | p50 / p95 at 2 | p50 / p95 at 1000 | 1000 incomplete, 503s |
| --- | --- | --- | ---: | --- | --- | --- | --- |
| Low, 5 | 176 / 175 / 53 | 328 / 1106 ms | 0 of 105 | 704 / 694 / 71 | 171 / 437 ms | 1897 / 4022 ms | 5 of 142, 0 |
| Low, 10 | 160 / 161 / 20 | 358 / 3078 ms | 1 of 39 | 608 / 623 / 37 | 235 / 548 ms | 4036 / 9089 ms | 50 of 74, 3 |
| Low, 20 | 128 / 128 / 16 | 453 / 4047 ms | 22 of 31 | 414 / 424 / 45 | 365 / 765 ms | 4054 / 9110 ms | 76 of 89, 12 |
| Low, 30 | 98 / 98 / 15 | 595 / 4063 ms | 30 of 30 | 303 / 301 / 49 | 566 / 1027 ms | 4070 / 8953 ms | 86 of 98, 12 |
| High, 5 | 146 / 149 / 32 | 394 / 1789 ms | 1 of 63 | 503 / 425 / 37 | 226 / 955 ms | 4014 / 9251 ms | 29 of 73, 6 |
| High, 10 | 116 / 116 / 15 | 492 / 4072 ms | 18 of 30 | 334 / 336 / 34 | 382 / 802 ms | 4093 / 14247 ms | 57 of 67, 7 |
| High, 20 | 63 / 63 / 15 | 865 / 4116 ms | 29 of 29 | 165 / 165 / 33 | 851 / 1644 ms | 4158 / 10300 ms | 58 of 65, 7 |
| High, 30 | 49 / 48 / 15 | 1169 / 4164 ms | 29 of 29 | 100 / 97 / 28 | 1265 / 2764 ms | 4190 / 11459 ms | 51 of 56, 5 |

At 5 desks thresholds 2 and 25 shed 95 to 100 percent of refinements. No search at 2 or 25 failed or answered
`searchComplete: false`. The 503s are `CALCULATION_UNAVAILABLE`, a search that found nothing before its deadline.
At 1000 with 5 desks, 1 to 9 percent of refinements were `UNAVAILABLE`, mostly at 20 and 30 technicians: the
searches the fix keeps from answering 429 `BUSY`, which publish the first pass's offers instead. Only the high,
5-technician run at 5 desks booked anything out (28 searches at threshold 2 offered nothing).

The four hypotheses written before the run:

- **Thresholds between 2 and 1000 stay inert: held.** All 320 quality searches at 25 were SHORTENED, as at 2, and
  their load runs match threshold 2 within a run's variation at every fleet size. A regular window is a date and a
  start, counted once however many technicians could take it, so a bigger fleet does not bring the count near 25.
  Threshold 25's mean change reached +$2.40 at 30 technicians on high demand, from a few dear outliers (its median
  was unchanged); that is the noise floor a threshold 1000 effect has to clear.
- **The threshold 2 search slows as the fleet grows: held, more steeply on high demand.** At 1 desk the p50 went
  from 328 to 595 ms at low volume and from 394 to 1169 ms on high demand between 5 and 30 technicians, and
  throughput fell by 44 and 66 percent (57 and 80 percent at 5 desks). Each search here is for a new address, so its
  roads are fetched: routing pairs per search grew from 251 to 1018 at low volume and from 601 to 4627 on high
  demand. Every threshold 2 search stayed well inside the deadline (worst p95 2.8 s, high, 30 technicians, 5 desks).
- **The gap between 2 and 1000 in best-offer cost widens with the fleet: half held.** On high demand it did, from
  no clear direction at 5 technicians (19 cheaper, 11 dearer, mean -$0.60 and +$0.58) to $1.38 to $2.34 a booking
  cheaper at 10 and 20, 5 to 8 percent of the $30 a booking adds, with 29 or 30 of 40 offers cheaper and the first offer
  earlier on 27 to 31. At 30 the deadline stopped some refinements and the gain fell back to about $1.25, inside the
  noise floor. At low volume it never widened: 1000 saved $0.12 to $0.85 at every fleet size, as at 6 technicians
  in the first study, because one appointment a day leaves little for a refinement to move.
- **At 1000 the deadline stops the refinement from about 20 technicians: held at low volume, earlier under load
  on high demand.** In the quality runs, 24 and 31 of 40 low-volume searches answered `searchComplete: false` at 20
  and 30 technicians, while high demand, on its optimized schedules, completed at 20 within 2.9 s. In the load runs,
  whose schedules are not optimized and fill as bookings land, 1 desk was deadline-bound from 10 technicians on high
  demand (18 of 30 searches) and from 20 at low volume. From there threshold 1000 runs a flat 15 searches a minute
  at 1 desk, each search using its full deadline.

**The recommendation stands: keep the threshold at 2, at every fleet size measured.** The only fleets where 1000
bought a measurable saving were high demand at 10 to 20 technicians, about $2 a booking, and there it cost 4 to 8
times the throughput at 1 desk and 5 to 10 times at 5 desks, with p95s of 10 to 14 s and a few searches finding
nothing. What a larger fleet does change is the cost of every search: at 30 technicians, threshold 2 manages 98
searches a minute per desk at low volume and 49 on high demand, against 176 and 146 at 5, mostly in fetching new
roads. One limit is left: validation still grows with the number of candidates, inside a fixed 1 s publish reserve,
so a fleet much larger than 30 could reach it again.

## Repeating the measurement

Run each line of the protocol above with `npx tsx load.ts` (README, "Load tool") against a stack whose routing
service and database match the one being judged. Pass `--metrics-url` for the scheduler's management port and
`--docker` for the routing container. Use a new `--seed` per run, and keep the JSON reports beside this page's
evidence. On the VM, run the load generator from the host's network so the numbers include it.
