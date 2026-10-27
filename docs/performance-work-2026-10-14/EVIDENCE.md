# Performance work 2026-10-13 to 2026-10-14: evidence and methods

## Source and scope

The immutable inputs are the load study files under `docs/evidence/`, one per pull request, each recorded by the pull request that made the change it judges:

| Study       | File                                                | PR   | What it judged                                                                                                                                                                            |
| ----------- | --------------------------------------------------- | ---- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| baseline    | `api-load-2026-10-13.json`                          | #151 | The load tool and the first measurement (revision 6abf872 plus the deadline 500 fix)                                                                                                      |
| concurrency | `api-load-2026-10-13-booking-concurrency.json`      | #152 | Late snapshots answered `STALE`; bookings publish without waiting on unrelated days; `coldRemeasurement` repeats 1, 5 and 10 desks on fresh seeds                                         |
| admission   | `api-load-2026-10-13-daily-admission.json`          | #153 | Daily proposals refused early with `Retry-After`, routed before the slot                                                                                                                  |
| outcome     | `api-load-2026-10-13-search-outcome.json`           | #154 | `searchComplete` false only when a needed pass was cut short; `refinement` reported                                                                                                       |
| shedding    | `api-load-2026-10-13-refinement-shedding.json`      | #155 | Optional refinement shed while bookings wait; `abComparison` is the five-desk A/B on fresh seeds                                                                                          |
| queue       | `api-load-2026-10-13-daily-queue.json`              | #157 | Queued proposals (`Prefer: respond-async`) with per-client fair turns; two clients for 90 s, then mixed load                                                                              |
| roles       | `api-load-2026-10-14-replica-roles.json`            | #158 | One `all` replica against booking and daily pools at equal search capacity, four runs                                                                                                     |
| vm          | `api-load-2026-10-14-vm-baseline.json`              | #159 | The `deploy/vm` rehearsal stack with CPU limits, `--date-spread`, and the request log's attribution of each call's time                                                                   |
| routing     | `api-load-2026-10-14-routing.json`                  | #160 | The same stack with the J changes, once on the flexible graph and once on the CH graph                                                                                                    |
| priority    | `api-load-2026-10-14-confirm-priority-and-jvm.json` | #162 | Confirm priority alone, then with a confirm-only slot (shipped), and the GC and compact-header A/Bs on the same stack; four runs, one confounded run left out as the file's protocol says |
| remote      | `api-load-2026-10-14-embedded-vs-remote.json`       | #164 | `SCHEDULER_CALCULATION_MODE` EMBEDDED against REMOTE with the solver pool at its previous defaults, at capacity 4, and with `SOLVER_BACKGROUND_LIMIT` 0 (the new defaults)                |

Study L (#163) is not a load study. `solver-profiling-2026-10-14.json` holds two solve-scoped JFR profiles (eight recordings each, the top 20 leaf frames and the move rates of the four profiled solves) and the 24-case paired study of the engine before and after the `RoadMatrix` treatment, with both sides' provenance.

Two routing benchmarks from `infra/benchmark-routing-ch.mjs` complete the picture: `routing-ch-omaha-2026-09-24.json` (map 2026-09-16, before any routing change) and `routing-ch-2026-10-14.json` (map 2026-10-13, the J routing image), each with exact directed parity on 132 Omaha pairs and p50/p95/p99 at 1, 5 and 10 concurrent callers with the service's cache limited to one entry.

Every run used the load tool `infra/host-conformance/load.ts` on one 24-processor workstation (AMD Ryzen 9 7900X, 63 GiB) running the scheduler, the Omaha routing service, Postgres and the load generator. Each run had its own seed; the report compares fresh-seed runs only.

## Validation

`report_data.py` checks, for every load tool report before it is read:

- **Shape:** the machine and processor count, a 60 or 90 s duration, a seed in 0 to 1000.
- **Consistency:** each operation's statuses add up to its calls; p50 ≤ p95 ≤ p99 ≤ max; searches equal the offers calls answered 201; confirms counted as confirmed plus the simulated host's write conflicts equal the confirm calls answered 200; no failed searches and no searches without offers; a queued run's recorded waits match its 201 proposals.
- **Contract:** no contract breach and no host anomaly recorded.
- **Queued runs with refusals:** a proposal refused 503 after its queue wait still records that wait, so when a run has 503s the waits cover every proposal, otherwise only the 201s.

For study L it checks that each profile's samples add up across its recordings, that every leaf frame falls in one of the report's six groups (an unknown frame is an error), that each case's recorded speedups equal after over before from its move rates, and it recomputes the summary (median, extremes and per-fleet medians of the cost-phase speedup, the fairness-phase median, wins, ties and losses on the after-cost) from the 24 cases.

For the routing benchmarks it checks the exact directed parity, sparse-equals-full and 132-routable results, the engine order, and the hardware.

`verify_report.py` recomputes 21 numerical tables and the twelve chart datasets from the raw JSON, without using the loader's or builder's helpers, and checks that the template's styles, numbering, theme, fonts and footer are byte-identical to the reference, that every figure has a description, that no table row may split, and that no em or en dash appears in the document XML.

## Derived measures

- **Searches per minute:** booking offers calls answered 201, times 60, divided by the run's duration in seconds.
- **Holds lost:** confirms answered 409 `HOLD_UNAVAILABLE` out of the holds selected (`booking.holdUnavailable` of `booking.selected`).
- **STALE resends per search:** `booking.stale` divided by `booking.searches`.
- **Queue wait:** median and maximum of `daily.queueWaitsMs`, each the time from queuing to the start of the calculation.
- **Per fetched leg:** the server-side p50 `routingMs` of offers divided by the p50 `fetched`, both from the request log attribution.
- **Routing CPU:** the mean `cpuPercent` of the routing container from `docker stats`, percent of one core.
- **Booking and daily replica CPU, heap max:** the same for the scheduler containers; heap is `server.heapUsedMaxBytes` from the scraped metrics, in GiB.
- **Leaf-frame groups:** the top 20 leaf frames of a profile summed by prefix: the immutable map probe alone; other `java.util` (HashMap and copies); `java.math` (BigDecimal); `java.time`; `dev.waterflex` (engine timing); `ai.timefold`.
- **Per-fleet moves per second:** the median over the fleet's 12 cases of each side's cost-phase moves per second; the speedup is the median of the per-case ratios, not the ratio of the medians.

Means and percentiles are the load tool's own. Nothing missing is replaced with a value; where a measurement did not exist yet (the slot wait before the second J0 run) the cell is empty.

## Interpretation limits

- **Not customer traffic:** random points inside Omaha and synthetic 20-technician days.
- **Two halves:** studies #151 to #157 ran the scheduler jar alone on the workstation; #158 to #164 ran the `deploy/vm` rehearsal stack with its CPU limits beside five queued daily planners. Comparisons hold within each half and the report never joins them into one curve.
- **Absolute numbers:** the rehearsal stack's limits are the compose defaults on a workstation, not the VM's share; the real VM sets its own numbers.
- **Seeds:** a run on seeds used before leaves roads cached and runs faster; every comparison is between fresh-seed runs, and the #152 study's first pass (cached seeds) appears only in the appendix.
- **Interpretation versus measurement:** statements about why time went where it did rest on the recorded attribution; the explanation of the CH graph's mechanism is from the routing engine's documentation, not a measurement.
- **Run-to-run variation:** J2 showed the booking replica's mean CPU moving 20 points between two runs with identical flags, so a CPU or heap difference of that size is not read as a result; the J2 decision rule was a 5 percent latency improvement, set before the runs.
- **The paired study's machine:** the paired solves ran with the experiment runtime's daily flags (two processors, 1.5 GiB heap, serial GC) on the workstation, one side after the other; the speedups are ratios within one machine and say nothing about the VM's absolute move rates.
- **One case, not a median:** the `docs/solver-benchmarks.md` table of #163 quoted one case's moves per second (20-CLUSTERED seed 17, 117k to 180k) where it said median; the report uses the per-fleet medians recomputed from the evidence (131k to 193k, 107k to 222k) and the table was corrected with this report.

## Rebuild

```text
python -m pip install -r docs/performance-work-2026-10-14/requirements.txt
python docs/performance-work-2026-10-14/report_data.py
python docs/performance-work-2026-10-14/build_report.py
python docs/performance-work-2026-10-14/verify_report.py
```

Calibri on Windows or Carlito on Linux is required for the figures. Render every page after changes; LibreOffice and PyMuPDF were used for review.
