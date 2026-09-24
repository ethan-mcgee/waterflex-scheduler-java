# Consolidated scheduler plan implementation checklist

This checklist tracks the original consolidated specification, not a reduced replacement. A passing build does not imply plan completion. `Partial` means useful implementation exists but the complete requirement and evidence are still missing. No unfinished item is implicitly deferred.

Audit update: September 24. Checked boxes describe implemented behavior with relevant correctness evidence. The separate performance, full-matrix, ablation and final-delivery gates remain required. Earlier chronological checkpoints in `scheduler-policy-progress.md` are historical, not current missing-feature lists.

## Policy and preserved contracts

| Requirement | State / remaining acceptance |
| --- | --- |
| Two-hour promises, ten-weekday horizon including supported weekends, ten-minute offers, service-date 06:00 Chicago cutoff | Existing behavior retained; add lifecycle and concurrency regression coverage |
| Qualifications, approved absences, daily/overtime limits, dated depots and endpoints | Existing checks retained; extend to pending rearrangement dependencies |
| Regular candidate means no added overtime relative to a consistent reservation baseline | Connected common-arrangement path independently validates incremental overtime; rollout acceptance remains open |
| Distinct `(date,start,end)` regular choices counted before four-offer truncation | Connected search core counts distinct windows before preparing up to four compatible offers |
| Overtime requires at most two regular choices and at least 90% confirmed utilization | Connected behind rollout flags; threshold unit tests pass, full API overtime/lifecycle matrix remains open |
| Qualified, metro/date eligible capacity, absence union, maxDaily cap, zero-capacity exclusion | Implemented in horizon snapshots and optimization; broaden constructed and load scenarios |
| Confirmed demand includes all services, caps consumed regular minutes per tech/date, excludes holds and old alternatives | Uses persisted confirmed assignments, excluding holds and pending reassignment; refresh releases old alternatives through validation |
| Routing failures, incomplete snapshots, deadlines never establish scarcity | Deployed insertion path cannot authorize new overtime; search core requires full horizon/window coverage and completed search |
| Regular offers first, authorized overtime fills at most four slots | Implemented in common offer bundle; broader overtime API acceptance remains open |
| Persist overtime authorization; later demand drop does not revoke it | Implemented for managed reservations; `ReservedOvertimeTest` confirms zero-current-demand authorization persistence and rejects unauthorized or over-limit confirmation |
| Decimal capacity-weighted variance including idle eligible techs; paid work includes service/drive/wait | Implemented for optimization and booking core, now connected to offers behind rollout flags |
| Fairness excludes sibling offers and uses canonical timing | Holds excluded from confirmed fairness; independent interval-placement and departure algorithms match exhaustive minute-grid cases |
| Hard feasibility, lowest found overtime, lowest found cost, fairness within floor(reference*1.02) | Implemented for optimization and connected booking search; expand quality and compatibility oracles |
| Zero/negative incremental cost gets no positive fairness allowance | Implemented and unit tested in connected booking candidate comparison |
| Remaining ties: cost, changed assignments, earlier window, technician ID, insertion/order | Booking candidate dominance and final selection implement this order; daily validated candidates use stable disruption/window/route comparison |
| Preview and locked apply share policy, no ordinary overtime increase, retain baseline without improvement | Implemented; broaden quality oracles |
| Disruption repair explicitly labels and requires approval of extra overtime | Implemented with locked recomputation, reviewed preview identities and persisted approval; unit/browser/integration covered |

## A. Snapshots and deadline

- [x] Immutable full-horizon snapshot with routed facts, cached baseline metrics, schedule/reservation versions, provenance and atomic commit integration; PostgreSQL restart/version/rollback checks pass.
- [x] Shared settings and day baselines, eligibility, capacity, immutable working intervals, buffered directed durations, prefix/suffix bounds and bounded route-result caches.
- [x] Candidate scoring and move evaluation use immutable in-memory facts without database or network calls.
- [x] One five-second budget from validated job/address search, including queue, portal, routing, locks and persistence. Exploration stops by four seconds; network/SQL/commit bounds, pool admission, cancellation and pre-publication checks share the budget. Real exhausted-pool and late-admission tests pass. Full cold-cache, overload and browser latency acceptance remains a separate gate below.
- [x] Durable cancellation tokens gate publication across instances, cancel queued/scoring/shared-road waits, and release committed but unacknowledged offers through independent validation. Lost response, locked publication and acknowledged-offer lifetime have cross-instance fixtures in both rollout modes. Cold-cache/overload deadline acceptance remains open below. Stale snapshots retry at most once within the original budget.
- [x] At exploration timeout preserve independently validated regular candidates for the remaining commit budget; return an explicit retryable incomplete result when validation/publication cannot finish.
- [x] Typed incomplete, defined-search miss, routing unavailable, conflict and busy outcomes propagate through customer and testing flows with completion/deadline provenance.
- [x] Browser API duration is measured separately from scheduler and gateway duration. Initial/address-pin measurements explicitly include address validation; no PII is recorded. Browser instrumentation is tested; full browser load acceptance remains open.

## B. Evaluation and C. timing

- [x] Prefix/suffix feasibility/resource summaries and changed directed-arc ranking reject only proven impossibility. A 250-dataset full-evaluation differential oracle checks every insertion position.
- [x] Reuse unchanged route results and aggregate only changed route resources. Changed departure/absence placement recomputes the whole affected route; no unsafe downstream early-stop shortcut is used. Timeline maps are materialized for retained candidates.
- [x] Named Constraint Streams replace whole-plan EasyScoreCalculator move scoring; route-level metrics and decimal fairness.
- [x] Keep independent RouteEvaluator for acceptance; move/undo FULL_ASSERT differential tests.
- [x] Differential tests cover repeated reassignment, undo, holds, changed visit promises, absences, waiting, departure changes and small exact-enumeration fixtures. Scalar ranking matches complete results.
- [x] Canonical forward/backward timing across feasible interval placements, minimizing overtime then cost and removing avoidable waiting before earliest equivalent departure. Scoring and independent validation match a 200-case exhaustive minute-grid oracle, including nonmetric directed legs.
- [x] Persist versioned current departure/return per working segment through booking, confirmation, cancellation, repair and independently validated test purges. Migration 28 leaves historical timing explicitly unavailable.
- [x] Dispatch geometry and timeline consume validated current timings; malformed, stale and routing-identity mismatches have explicit handling. Browser and integration coverage verifies the current path; historical compatibility continues to require review.
- [x] Optimization and time-off reports separate road seconds, configured buffers and rounding; missing historical breakdowns remain unavailable. Evidence reports modeled costs without payroll claims.

## D. Bounded search

- [x] Insertion across eligible dates/technicians/windows followed by regular rearrangement when distinct choices <=2; incomplete exploration cannot authorize overtime.
- [x] Same-day relocation, pair swap and within-route reversal; depth two, beam eight, 500 arrangements per window.
- [x] Six promising routes including the least-utilized eligible route; every existing date/window is preserved and independently checked.
- [x] Round-based horizon work with directed road cost, qualification scarcity, slack and workload ranking.
- [x] Refine regular choices/fairness after the insertion scarcity pass; partial optional refinement preserves independently validated insertion choices and completion evidence.
- [x] Coverage, pruning, configured limits, moves, stop reason and insertion/rearrangement provenance are recorded.
- [x] Application shortlists use no Enterprise nearby selection. Disabled bounded search cannot establish scarcity and already-issued managed offers retain their confirmation path across instances and rollout changes.

The immutable `BookingSnapshot` and `BoundedBookingSearch` core implement these moves and limits, horizon rounds, application shortlists including idle capacity, confirmed utilization, distinct windows, incremental fairness and candidate policy comparison. Tests cover relocation versus insertion, a tiny exact assignment/order oracle, duplicate technician windows, idle capacity, holds, thresholds, cost ceilings and incomplete inputs. The public path now connects this core to common reservation bundles and atomic confirmation behind disabled rollout flags. API tests demonstrate actual relocation, concurrent reservation protection and confirmation with the flags disabled. Acceptance remains open for full coverage, budget refinement and representative performance evidence.

## E. Routing

- [x] Full matrix retained; sparse directed pairs with explicit IDs, expected identity, individual routability and strict malformed-result handling.
- [x] Insertion requests relevant missing pairs; batch persistent reads/upserts; bounded expiring memory cache.
- [x] Batch selected cross-route neighborhood legs with the immutable snapshot's expected routing identity; identity changes invalidate work.
- [x] Coalesce identical directed-leg requests by identity across sparse and full-matrix callers; isolate caller cancellation/deadlines. Bounded worker queue and HTTP concurrency tests pass; representative load acceptance remains open.
- [x] Optional versioned prewarming checks schedule version, routing identity and booking demand between bounded batches and after the final batch. Race tests prevent marking stale work complete; default remains disabled.
- [x] Separate fixed-car CH artifact benchmark records directed parity, startup/preparation/reload, memory, latency and provenance. Actual-road booking comparisons show little throughput improvement; default remains disabled pending combined acceptance.
- [x] No straight-line fallback; actual Omaha sparse/full directed-pair check recorded.

## F. Durable reservations

- [x] Versioned common metro/day arrangement independently supports all active conservative sibling holds.
- [x] Persist participants, dependent technicians, timings, versions, configuration, routing identity and authorization; search runs outside long transactions.
- [x] Consistent lock order and atomic confirmation replace the selected placeholder, release siblings, revalidate remaining reservations, apply assignment/order/timing changes and increment versions. PostgreSQL rollback and API concurrency checks pass.
- [x] Refresh, release, expiry, cancellation, restart, repeated confirmation, configuration changes and stale snapshots revalidate remaining arrangements. Cross-instance lifecycle fixtures preserve a different customer's overlapping holds, including expiry and refresh; PostgreSQL tests cover restart, stale/configuration races and rollback. Directed nonmetric deletion tests reject release/cancellation that would make remaining work infeasible.
- [x] Availability, qualification, depot, endpoint, time-off and purge guards include pending reassignment dependencies.
- [x] Ordinary optimization remains blocked by relevant active holds, including pending route dependencies.

Versioned reservation storage, strict restart decoding, relational dependencies, guard integration and public lifecycle wiring are implemented behind rollout flags. Sparse snapshot routing, common offer bundles, confirmation/release and cancellation use independent validation and short atomic commits. PostgreSQL and API gates cover actual reassignment, stale versions, rollback after route updates, concurrent customers, pending cancellation and confirmation with flags disabled. A bounded background expiry transition revalidates all sibling dates, with enabled and disabled rollout fixtures. Overlapping-customer expiry/refresh and database configuration-race coverage passed at `02d42b6`; the additional nonmetric deletion and exhausted-pool gates passed locally afterward. General rollout still depends on the separate performance and delivery gates.

## G. Daily optimization and H. concurrency

- [x] Initial 10-second reference plus remaining 15-second budget for fairness; isolated copies and independent validation.
- [x] Every phase receives a fresh copied solution and isolated solver. No previous solution is reused across changed provenance.
- [x] Eight configurations benchmarked on identical 20/30/50-technician datasets and budgets; retained capped, uncapped and Tabu choices repeated across three seeds. Source archives preserve and explicitly exclude two corrected initial harness defects.
- [x] Actual termination/cap effects measured; uncapped Tabu selected, diagnostic fixed seeds and 1,000-step capped configuration retained.
- [x] Persist configuration XML/fingerprint, seed, phase budgets, steps, moves, time to best and termination alongside policy, schedule and routing provenance.
- [x] Shared admission default two searches, one on single CPU; at most one background optimizer; booking priority; queue <=16; queue time in deadline; isolated solvers. Load benchmarks remain open.

## I. APIs, migrations and UI

- [x] Policy defaults migration and nullable historical optimization metrics; strict persisted JSON validation; legacy apply requires new preview.
- [x] Reservation, current timing and cancellation migrations persist policy/configuration/version provenance across the lifecycle.
- [x] Booking diagnostics include windows/utilization/gate, coverage, moves, snapshot age, pairs/cache, queue, foreground JDBC/CPU and lock-statement duration, conflict/failure reason. Lock duration is an upper bound on wait; shared pairs are not provider HTTP calls. Complete benchmark observability remains a separate gate.
- [x] Optimization workload/utilization, fairness/max utilization, overtime/cost, signed change, reference/ceiling/reason and preview segment times.
- [x] Repair overtime approval UI is implemented and browser-tested; versioned current segment geometry/timeline is implemented and checked separately.
- [x] Accessible indeterminate "Finding available appointments"; entered values retained on errors.
- [x] Typed outcomes/retry through initial booking, refresh, conflict and testing UI; unit, browser and sequential integration checks pass.

## Verification, delivery and boundaries

- [x] Overtime threshold boundaries/duplicates/holds/existing OT/authorization persistence/deadline failures: `SchedulingPolicyTest`, `BoundedBookingSearchTest`, `ReservedOvertimeTest`, deadline/routing suites. An existing-overtime day still accepts a zero-added-overtime booking.
- [x] Search relocation/swap oracles, unequal capacity/idle/scarce skills/absences, cost-ceiling boundaries, no artificial waiting. The explicit pair-swap fixture succeeds where insertion and both single relocations fail; small exact assignment/order and minute-grid timing oracles independently check outcomes.
- [x] Timing exclusive ends/absence segments/endpoints/return, move undo/reassignment differential coverage, directed sparse/full/missing/identity/malformed routing: timing/bounds/Constraint Streams tests, routing contracts and real Omaha parity evidence.
- [x] Concurrent reservation compatibility, atomic confirmation, lifecycle/guard/restart/configuration cases and explicit repair approval: PostgreSQL snapshot gate; reservation, bounded-booking, cross-instance cancellation/expiry, optimizer and time-off integration gates; approval browser tests.
- [x] Queue/routing/scoring/lock/persistence deadlines and actual malformed/null external/database/JSON paths: admission, telemetry, routing, nullability, reservation decoding, PostgreSQL and browser suites. Hosted CI now includes the real exhausted-pool regression. Performance acceptance remains open.
- [ ] Isolated reproducible 20/30/50-tech sparse/clustered/dispersed/mixed-skill/tight-window/absence/near-capacity datasets.
- [ ] Ablations: baseline, insertion policy/fairness, snapshot/routing/scoring, bounded search, timing, solver/optional acceleration.
- [ ] Cold/warm with concurrency 1/5/10: p50/p95/p99, incomplete rate, coverage/served demand/delay, overtime/wait/fairness/max utilization/cost, churn/retiming, moves/time-to-best, routing/cache/DB/locks/CPU/memory/queue.
- [ ] Documented hardware, exact revision/configuration/dataset/graph; actual Omaha appointment workloads; p95 <=5 seconds plus served-demand and zero hard-constraint/reservation violations.
- [ ] Final Java, nullability, lint, typecheck/build, schema and contracts, unit/browser, booking/optimizer/time-off/depot/sequential integration gates; distinguish fixture/local/road/CI evidence.
- [ ] Complete policy/lifecycle/API/operations/benchmark/rollout/rollback documentation; enable only passing configurations.
- [ ] Detailed PR with final behavior, migrations, evidence, risks and actual CI; wait for review without merging.

Retain embedded Timefold 2.6.0 and self-hosted GraphHopper 11. No paid service migration, replacement solver, same-day replanning, automatic dispatch apply, buffer reduction, live traffic, forecasting, multi-tech visits, manual assignment/pinning features, or preview Neighborhoods API is included.
