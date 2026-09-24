# Consolidated scheduler plan implementation checklist

This checklist tracks the original consolidated specification, not a reduced replacement. A passing build does not imply plan completion. `Partial` means useful implementation exists but the complete requirement and evidence are still missing. No unfinished item is implicitly deferred.

## Policy and preserved contracts

| Requirement | State / remaining acceptance |
| --- | --- |
| Two-hour promises, ten-weekday horizon including supported weekends, ten-minute offers, service-date 06:00 Chicago cutoff | Existing behavior retained; add lifecycle and concurrency regression coverage |
| Qualifications, approved absences, daily/overtime limits, dated depots and endpoints | Existing checks retained; extend to pending rearrangement dependencies |
| Regular candidate means no added overtime relative to a consistent reservation baseline | Not enforced in booking |
| Distinct `(date,start,end)` regular choices counted before four-offer truncation | Not implemented |
| Overtime requires at most two regular choices and at least 90% confirmed utilization | Pure policy predicate tested; booking integration missing |
| Qualified, metro/date eligible capacity, absence union, maxDaily cap, zero-capacity exclusion | Optimization capacity implemented; horizon booking aggregation missing |
| Confirmed demand includes all services, caps consumed regular minutes per tech/date, excludes holds and old alternatives | Not implemented in booking |
| Routing failures, incomplete snapshots, deadlines never establish scarcity | Typed outcomes started; gate enforcement missing |
| Regular offers first, authorized overtime fills at most four slots | Not implemented |
| Persist overtime authorization; later demand drop does not revoke it | Not implemented |
| Decimal capacity-weighted variance including idle eligible techs; paid work includes service/drive/wait | Implemented for optimization; booking incremental fairness missing |
| Fairness excludes sibling offers and uses canonical timing | Optimization has no active holds; booking missing; timing interval placement incomplete |
| Hard feasibility, lowest found overtime, lowest found cost, fairness within floor(reference*1.02) | Optimization implemented; booking missing |
| Zero/negative incremental cost gets no positive fairness allowance | Pure policy tested; booking integration missing |
| Remaining ties: cost, changed assignments, earlier window, technician ID, insertion/order | Partial; disruption and consistent route tie-breaking missing |
| Preview and locked apply share policy, no ordinary overtime increase, retain baseline without improvement | Implemented; broaden quality oracles |
| Disruption repair explicitly labels and requires approval of extra overtime | Implemented with locked recomputation, reviewed preview identities and persisted approval; unit/browser/integration covered |

## A. Snapshots and deadline

- [ ] Immutable full-horizon snapshot: appointments, reservations, qualifications, availability, absences, dated endpoints, configuration fingerprint, schedule/reservation versions, routing identity, directed legs, baseline metrics, metadata.
- [ ] Shared request settings/baselines once; precomputed eligibility, intervals and buffered travel. Partial per-route insertion snapshots exist.
- [ ] No database/network calls during scoring or moves. Insertion and daily scoring meet this locally; new neighborhoods must preserve it.
- [ ] One five-second budget from validated job/address search, including queue, portal, routing, locks and persistence. Initial implementation stops exploration by four seconds, propagates network/SQL timeouts and checks pre-commit. Remaining cancellation, snapshot restructuring, transport classification and load acceptance prevent marking this complete.
- [ ] Cancel abandoned work and prohibit late reservations; stale snapshot retry at most once within original budget.
- [ ] At timeout commit independently validated regular offers or return explicit retryable incomplete result.
- [ ] Distinguish incomplete, defined-search miss, routing unavailable, conflict, busy. Typed contract/UI implemented locally; completion/deadline provenance incomplete.
- [ ] Measure customer-visible API time separately from scheduler elapsed time. Scheduler and portal-to-scheduler timing added; browser total instrumentation missing.

## B. Evaluation and C. timing

- [ ] Prefix/suffix feasibility/resource summaries; proven pruning and changed-arc ranking without assuming feasibility.
- [ ] Recompute affected timeline only; stop propagation only on full resource-state equality. Departure/absence/uncertainty invalidates segment.
- [x] Named Constraint Streams replace whole-plan EasyScoreCalculator move scoring; route-level metrics and decimal fairness.
- [x] Keep independent RouteEvaluator for acceptance; move/undo FULL_ASSERT differential tests.
- [ ] Expand differential tests: repeated reassignment, absences, waiting, departure changes, exact enumeration.
- [ ] Canonical forward/backward timing across feasible interval placements, minimizing overtime then cost then earliest equivalent departure. Fixed-segment waiting absorption implemented.
- [ ] Persist current departure/return per working segment through booking, confirmation, cancellation and repair. Preview summaries persist them today.
- [ ] Dispatch geometry and timeline consume the persisted current timings.
- [ ] Report modeled operating cost, separate road time from configured buffers, never claim unsupported payroll savings.

## D. Bounded search

- [ ] Insertion across all eligible dates/techs/windows, followed by regular rearrangement when distinct choices <=2.
- [ ] Same-day relocation, pair swap, within-route reversal; depth two, beam eight, 500 arrangements per window.
- [ ] Six promising routes including least-utilized eligible route; preserve every existing date/window.
- [ ] Round-based horizon allocation and ranking by directed road cost, qualification scarcity, slack and workload.
- [ ] Use remaining budget for regular choices/fairness after completed scarcity pass.
- [ ] Coverage, pruning, limits, moves, stop reason and insertion/rearrangement provenance recorded.
- [ ] Application shortlists; no Enterprise nearby selection; feature flag retains policy and issued-offer confirmation when disabled.

## E. Routing

- [x] Full matrix retained; sparse directed pairs with explicit IDs, expected identity, individual routability and strict malformed-result handling.
- [x] Insertion requests relevant missing pairs; batch persistent reads/upserts; bounded expiring memory cache.
- [ ] Batch selected cross-route neighborhood legs; resolve identity once per entire request snapshot.
- [ ] Coalesce identical directed-leg requests; isolate caller cancellation/deadlines.
- [ ] Versioned low-priority prewarming after schedule mutations; bookings take priority.
- [ ] Benchmark CH on separate fixed-car graph artifact; correctness, startup, memory, latency, provenance gates before enablement.
- [x] No straight-line fallback; actual Omaha sparse/full directed-pair check recorded.

## F. Durable reservations

- [ ] Versioned common metro/day arrangement independently supports all active conservative sibling holds.
- [ ] Persist arrangement, participants, dependent technicians, timings, versions, config, routing identity, authorization; search outside long transactions.
- [ ] Consistent lock order and atomic confirmation: replace selected placeholder, release siblings, revalidate remaining reservations, apply assignment/order/timing changes, increment versions.
- [ ] Refresh, release, expiry, cancellation, restart, repeated confirmation, configuration changes and stale snapshots revalidate remaining arrangements. Do not assume stop deletion is safe.
- [ ] Extend availability, qualification, depot, endpoint, time-off and purge guards to pending reassignment dependencies.
- [ ] Block ordinary optimization with relevant active holds, including pending route dependencies.

## G. Daily optimization and H. concurrency

- [x] Initial 10-second reference plus remaining 15-second budget for fairness; isolated copies and independent validation.
- [ ] Reuse previous solutions only on matching complete provenance.
- [ ] Benchmark current, Late Acceptance, Tabu, single moves/swaps, sublist/K-opt and low-frequency ruin-and-recreate with matching evaluation settings, fixed datasets and equal budgets.
- [ ] Measure termination and cap effects before final production choice; retain deterministic diagnostic seeds/steps.
- [ ] Persist actual solver configuration/termination and complete policy/schedule/routing provenance. Policy metrics/reference arrangement already persisted.
- [x] Shared admission default two searches, one on single CPU; at most one background optimizer; booking priority; queue <=16; queue time in deadline; isolated solvers. Load benchmarks remain open.

## I. APIs, migrations and UI

- [x] Policy defaults migration and nullable historical optimization metrics; strict persisted JSON validation; legacy apply requires new preview.
- [ ] Reservation schema and policy/configuration fingerprints across the complete lifecycle.
- [ ] Booking diagnostics: windows/utilization/gate, coverage, moves, snapshot age, pairs/cache, queue/lock times, conflict/failure reason.
- [x] Optimization workload/utilization, fairness/max utilization, overtime/cost, signed change, reference/ceiling/reason and preview segment times.
- [ ] Repair overtime approval UI implemented and browser-tested; current-segment geometry/timeline still missing.
- [x] Accessible indeterminate "Finding available appointments"; entered values retained on errors.
- [x] Typed outcomes/retry through initial booking, refresh, conflict and testing UI; unit, browser and sequential integration checks pass.

## Verification, delivery and boundaries

- [ ] Overtime threshold boundaries/duplicates/holds/existing OT/authorization persistence/deadline failures.
- [ ] Search relocation/swap oracles, unequal capacity/idle/scarce skills/absences, cost-ceiling boundaries, no artificial waiting.
- [ ] Timing exclusive ends/absence segments/endpoints/return, move undo/reassignment differential coverage, directed sparse/full/missing/identity/malformed routing.
- [ ] Concurrent reservation compatibility, atomic confirmation, all lifecycle/guard/restart/configuration cases and explicit repair approval.
- [ ] Queue/routing/scoring/lock/persistence deadlines and actual malformed/null external/database/JSON paths.
- [ ] Isolated reproducible 20/30/50-tech sparse/clustered/dispersed/mixed-skill/tight-window/absence/near-capacity datasets.
- [ ] Ablations: baseline, insertion policy/fairness, snapshot/routing/scoring, bounded search, timing, solver/optional acceleration.
- [ ] Cold/warm with concurrency 1/5/10: p50/p95/p99, incomplete rate, coverage/served demand/delay, overtime/wait/fairness/max utilization/cost, churn/retiming, moves/time-to-best, routing/cache/DB/locks/CPU/memory/queue.
- [ ] Documented hardware, exact revision/configuration/dataset/graph; actual Omaha appointment workloads; p95 <=5 seconds plus served-demand and zero hard-constraint/reservation violations.
- [ ] Final Java, nullability, lint, typecheck/build, schema and contracts, unit/browser, booking/optimizer/time-off/depot/sequential integration gates; distinguish fixture/local/road/CI evidence.
- [ ] Complete policy/lifecycle/API/operations/benchmark/rollout/rollback documentation; enable only passing configurations.
- [ ] Detailed PR with final behavior, migrations, evidence, risks and actual CI; wait for review without merging.

Retain embedded Timefold 2.6.0 and self-hosted GraphHopper 11. No paid service migration, replacement solver, same-day replanning, automatic dispatch apply, buffer reduction, live traffic, forecasting, multi-tech visits, manual assignment/pinning features, or preview Neighborhoods API is included.
