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

4. **Daily proposals run one at a time per replica, and a queued one ends as a slow 503.** Admission lets one daily
   calculation (`BACKGROUND`) run at a time. Others queue for their whole allowance (search time plus 5 s), then answer
   503 `CALCULATION_UNAVAILABLE` after about 9 s (receipts show `queueMs` 9005). They are not counted in
   `scheduler.admission.rejections`. Separately, the first proposal for a new 20-technician day spent about 4 s on cold
   routing and ran past its allowance (503 after 9 s); later ones took 5.0 s. Hosts should run one daily proposal at a
   time per replica, as the portal's overnight worker does.

   Proposed:
   - refuse a daily request with 429 when the queue ahead of it cannot finish within its allowance;
   - count queue expiries as rejections;
   - leave routing time out of the 5 s reserve, or make it faster (PR J).

5. **`searchComplete` is false on nearly every search (owner decision needed).** When the first pass finds more
   distinct windows than `regularWindowThreshold`, the optional refinement gets a 250 ms cap. Reaching that cap marks
   the search incomplete (`REFINEMENT_TIME_LIMIT`), so all 494 searches in these runs reported `false` while returning 4 offers.
   The spec says `false` means "the deadline ended the search early; an empty offer list then does not prove there is
   no capacity", and a host reading it that way learns nothing. Either report `false` only when the deadline (or a
   failure) cut the search short, or change the spec's wording to cover the optional refinement.

## Repeating the measurement

Run each line of the protocol above with `npx tsx load.ts` (README, "Load tool") against a stack whose routing
service and database match the one being judged. Pass `--metrics-url` for the scheduler's management port and
`--docker` for the routing container. Use a new `--seed` per run, and keep the JSON reports beside this page's
evidence. On the VM, run the load generator from the host's network so the numbers include it.
