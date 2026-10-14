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
- **Compact object headers (`-XX:+UseCompactObjectHeaders`, a product flag since JDK 24) are within noise on
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

## Repeating the measurement

Run each line of the protocol above with `npx tsx load.ts` (README, "Load tool") against a stack whose routing
service and database match the one being judged. Pass `--metrics-url` for the scheduler's management port and
`--docker` for the routing container. Use a new `--seed` per run, and keep the JSON reports beside this page's
evidence. On the VM, run the load generator from the host's network so the numbers include it.
