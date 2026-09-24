# Scheduler policy and routing implementation progress

This is a partial implementation of the consolidated scheduler plan. It is not ready for the plan's production rollout. In particular, the booking overtime gate and five-second appointment-search guarantee are **not implemented**. The PR must remain a draft until the outstanding work and acceptance gates below are completed.

Initial foundation revision: `7109c2d248d07d2a4e6a5d75b1fda19ab312cafc`.
Inspected baseline: `2d17885141d4a901b06b3f9732530db85c951568`.

## Implemented behavior

- Daily optimization uses independently validated overtime, operating cost, and capacity-weighted decimal workload variance. Available regular capacity unions approved absences, clips them to the regular shift, and applies the paid daily limit. Idle technicians qualified for at least one service in the day's demand participate.
- The reference phase searches overtime first and cost second for up to ten seconds. The fairness phase receives the remaining portion of the fifteen-second search allocation and enforces the reference overtime and `floor(referenceCost * 1.02)` cost ceiling. The solver override replaces the XML termination configuration for ordinary previews. Repair continues using the existing XML termination, including its 1,000-step cap. Comparative solver tuning is still required.
- Named Constraint Streams constraints update affected route metrics. Fleet cost remains rounded after combining route resources. Full recomputation remains available for reporting and differential checks; `RouteEvaluator` remains the independent acceptance check.
- Preview persists policy metrics, rules, the reference route arrangement, acceptance reason, and signed cost change in `optimization_run.policyAnalysis`. Apply restores and validates the reference against locked current data. Ordinary apply rejects overtime increases and legacy previews lacking the policy version. Historical metrics remain explicitly unavailable when absent.
- Fixed working segments can depart later to absorb avoidable waiting. Timing respects exclusive window ends and leaves segment return times unchanged. Baseline and proposed routes use the same rules. Preview summaries persist departure/return times and display them in dispatch. This is not a complete interval-placement optimizer or a durable current-route timing implementation for every booking lifecycle mutation.
- Booking prepares settings, absences, road data, and a baseline once per technician/date during insertion scanning. Candidate scoring performs no database or network operations. The existing long transaction and reservation model have not been replaced.
- `/internal/legs` accepts explicit directed pair IDs and an expected routing identity. Booking requests endpoint, adjacent-stop, and candidate incoming/outgoing legs. The scheduler batches persistent reads and upserts and shares the existing bounded memory cache. Missing, malformed, unavailable, and explicitly unroutable results do not become invented travel times. Full matrix and geometry endpoints remain available.
- Appointment search displays an accessible indeterminate progress bar labeled "Finding available appointments". Existing error handling retains entered form values. Typed incomplete, routing, conflict and busy results now propagate through initial booking, refresh, conflict handling and the sequential testing journal. Empty incomplete searches stay open for retry rather than becoming a no-capacity claim.

## Migration and compatibility

`20260924010000_scheduling_policy` adds nullable `policyAnalysis` and validated settings for two regular windows, 90% utilization, 2% fairness allowance, and 5,000 ms. Existing settings fingerprints include these values. The booking scarcity thresholds are not yet connected to a completed bounded search. The deadline now limits admission, exploration, routing, lock waits and pre-commit checks; end-to-end performance acceptance and cancellation propagation remain outstanding.

For eventual deployment, migrate first and deploy the routing service before the scheduler, because new booking code calls `/internal/legs`. The new routing service remains compatible with the old full-matrix client. The additive schema can remain during rollback. Revert scheduler/portal behavior together and require fresh previews after changing policy behavior. No live services were deployed during this work.

## Local evidence

Windows workstation: AMD Ryzen 9 7900X, 12 physical cores / 24 logical processors, 67,870,916,608 bytes of physical memory. Java 25.0.1, embedded Timefold 2.6.0, GraphHopper 11.0. Integration data used `waterflex_test`; browser data used its separate `nullability_ui` schema. The live application database was not used for test mutations.

Passed:

- `./mvnw.cmd clean verify` and `./mvnw.cmd -Pnullability clean verify`: 57 scheduler tests and two routing tests, no failures or skipped tests.
- Portal lint, typecheck, production build, optimization/nullability/geometry/sequential-booking unit tests.
- Prisma validation, migration deployment, seeding, and Java/Prisma schema contract checks.
- Booking, optimizer, time-off, multi-depot, and sequential-booking integration gates against `ci-monaco-omaha-car-v2`. Final booking and optimizer runs also verified the final compiled segment-summary changes. Hosted CI is the final full-suite check of the PR head.
- Twenty existing browser tests and the additional progress/input-retention regression, run separately.
- FULL_ASSERT solver tests exercise moves and undo and compare scorer output with independent evaluation. A constructed equal-capacity case balances workload with unchanged $60 modeled cost and zero overtime. Constructed timing cases remove 59 and 230 minutes of avoidable waiting within the existing segment model. These are correctness examples, not measured operational savings.

The booking smoke intentionally corrupts a persistent fixture-cache row. Run it before optimizer tests on a fresh scheduler, as CI does. Running it after warming that coordinate in memory can fail its database-repair assertion even though the valid memory leg is used. The final cold-cache booking run passed.

Actual Omaha road evidence is in [the recorded API samples](evidence/sparse-routing-omaha-2026-09-23.json). An isolated copy of the existing 199 MB graph was loaded by the new routing build. Six directed pairs matched the full matrix exactly, with asymmetric return legs, explicit unreachable behavior, and stale-identity rejection. Graph identity: `d4a7c8575bad71445a7f745383e1466de88552a81db580196a4c62916acdcbaf`; merged OSM checksum: `cff0f790d8eda12bca1f4823bd52a429b7ae7c7626cbf9ca76f0992185636f1c`.

The initial sparse request took approximately 330 ms and its repeat approximately 4 ms. The subsequent full matrix used a warmed cache, so these samples do not compare sparse and full performance. They are not appointment latency, percentiles, load tests, or fleet quality acceptance. Reproduce the direct check with `node infra/verify-sparse-routing.mjs http://127.0.0.1:18003 report.json` against an isolated routing instance.

## Outstanding implementation and acceptance

1. Full immutable horizon snapshots, schedule/reservation versions, bounded admission, deadline propagation, cancellation, lock/commit budgets, conflict retry, and cancellation propagation. Typed search outcomes and initial deadline/admission enforcement are implemented.
2. Booking confirmed-utilization aggregation, distinct-window scarcity, regular-first ranking, incremental fairness, persisted overtime authorization, and enforcement only after a completed bounded scarcity pass. Booking uses cost ranking among zero-added-overtime candidates until the complete scarcity pass can authorize overtime. A new persisted overtime authorization field preserves known legacy overtime offers and prevents regular-offer confirmation from adding unauthorized overtime.
3. Versioned common reservation arrangements, reassignment dependencies, atomic confirmation of rearranged routes, and revalidation across refresh, release, expiry, cancellation, restart, and guarded technician changes.
4. The prescribed two-move relocation/swap/reversal search, six-route shortlist, beam width eight, 500-arrangement cap, horizon round allocation, diagnostics, and feature flags.
5. Prefix/suffix incremental timing, full interval-placement handling, current segment timing persistence across every mutation, and separate road-versus-buffer reporting.
6. Sparse request coalescing with isolated caller deadlines, low-priority versioned prewarming, and contraction-hierarchy artifact benchmarking.
7. Solver acceptance-strategy comparisons, tie-breaking by disruption, accurate termination/provenance telemetry and load evidence for admission limits. Explicit repair overtime approval and shared admission are now implemented.
8. Reproducible 20/30/50-technician benchmark matrix, cold/warm and 1/5/10 concurrent booking streams, exact-enumeration quality oracles, five-second p95 proof with served-demand reporting, and complete reservation/failure regression coverage.

No claim is made that the consolidated plan, reservation guarantees for rearranged booking, or performance acceptance has been completed.

## Crash recovery and deadline checkpoint

The crash zero-filled `web/lib/nullability.test.ts` and generated `.next` types. The test source and its new cases were reconstructed from Git and the recorded edits. Generated output was removed and regenerated; a scan of tracked and untracked source text found no other zero-byte corruption, and `git fsck` passed.

Shared admission uses two expensive searches by default (one on a single CPU), a maximum queue of sixteen, booking priority, and one background optimization/repair at a time. Queue waits consume the request deadline. The portal sends an absolute five-second deadline, and the scheduler uses a monotonic remaining budget, stopping exploration with one second reserved for validation and persistence. PostgreSQL statement/lock limits and a pre-commit deadline check prevent timed-out work from later reserving offers in the tested lock case. Database pool acquisition is bounded. This does not yet prove the complete cold-cache/overload p95 requirement, cancel all disconnected callers, or replace long search transactions with immutable horizon snapshots.

Local verification includes strict Java/nullability, portal lint/typecheck, typed-response/nullability tests, migration/schema checks, and booking plus sequential integration in the new isolated `waterflex_test.search_policy_recovery` schema. A deliberately locked job returned a retryable outcome without creating a hold. The sequential fixture now recognizes the manual-survivor coordinate it intentionally retains after purge; previously reusing the disposable test database caused an explicit routing error for that fixture point.

The full original requirement inventory is tracked in [scheduler-plan-checklist.md](scheduler-plan-checklist.md). Remaining work is not implicitly deferred.

## Repair overtime review checkpoint

A disruption repair adding overtime cannot be applied automatically. Locked apply independently compares current baseline and proposed overtime, and missing approval leaves the time-off request `READY` rather than requeueing it. Dispatch shows the additional minutes and requires an unchecked-by-default acknowledgement tied to the displayed report. The API also requires the exact reviewed repair IDs, preventing approval of a replaced preview. Migration `20260924030000_repair_overtime_approval` records the explicit approval on the request. Technician limits remain hard constraints.

The time-off integration includes a constructed repair that must transfer a late appointment to a shorter-shift technician. It verifies that automatic approval stops, an unapproved manual apply changes nothing, and explicit approval applies the repair. The complete time-off integration and the new browser approval regression pass locally.

## Immutable search core checkpoint

`BookingSnapshot` defensively copies route facts, assignments, qualifications, absences, directed legs, versions and provenance. It rejects incomplete horizon coverage, duplicate assignments, missing required rates and malformed scheduling facts. Explicit unreachable legs remain distinct from legs not loaded into the snapshot. Mutable evaluator plans are created independently from these facts.

`BoundedBookingSearch` implements insertion rounds across windows, followed by same-date relocation, swaps and reversals when regular choices are scarce. Defaults remain six shortlisted routes, depth two, beam eight and 500 examined arrangements per window. The shortlist includes the least-utilized eligible technician and ranks road insertion cost, qualification scarcity and slack. Search performs no database or network operations. It measures confirmed utilization across all services, excludes holds from demand and fairness, counts distinct windows, and applies the overtime/cost/fairness policy using integer cents and decimal variance. Missing routing facts or deadline exhaustion cannot authorize overtime. Dominated candidate pruning preserves the policy winner under a tightening cost ceiling.

This core is not connected to public offers yet. Durable common arrangements, snapshot loading, atomic confirmation and lifecycle protections must be implemented before enabling rearrangement. No performance acceptance is claimed. Constructed tests demonstrate a relocation that insertion misses and agreement with exact assignment/order enumeration on a tiny case, along with duplicate-window, idle-capacity, hold-demand and cost-ceiling checks.

The existing booking path now prioritizes overtime before insertion cost, batches weekly availability per date, and rejects missing cost/buffer settings. Cancellation rejects an unvalidated remaining route and rolls back; confirmation and cancellation persist remaining hold timings as well as appointment timings. The booking integration deliberately introduces a missing shift and an infeasible daily limit, verifies both cancellations roll back, restores the fixture and verifies successful cancellation.

Local Java verification, strict nullability verification, portal lint/typecheck, the updated booking integration and schema contracts pass. Schema checking now uses the selected database schema and explicitly checks overtime authorization, policy analysis and repair approval fields. Hosted CI passed at the preceding checkpoint `a2045bc420f74c0731024306c5d06c80c4530c4a`; new changes require their own CI run.

## Reservation storage and snapshot loading checkpoint

Migrations `20260924040000_reservation_arrangements` and `20260924050000_reservation_obligations` add versioned metro/day state, hold/technician/service dependencies and a combined guard view. `ReservationState` validates persisted versions, authorization booleans, route coverage and segment timing. `ReservationStore` requires a commit transaction, independently validates the entire arrangement, checks live hold coverage and schedule versions, and writes dependencies for every participating service, including pending reassignment of an existing appointment.

`BookingSnapshotLoader` loads the horizon in a short repeatable-read transaction with batched availability and shared policy settings. Routing and search are separate from that transaction. The real PostgreSQL gate `BookingSnapshotDatabaseIT` verifies refresh exclusion, missing hold coordinates, persistence without moving confirmed appointments, reload through a new store instance, pending reassignment dependencies and stale schedule rejection. Run it explicitly with `JDBC_DATABASE_URL` pointing to migrated, seeded `waterflex_test` and `./mvnw.cmd -Pnullability -pl scheduler-service -Dtest=BookingSnapshotDatabaseIT test`.

Availability, qualification, depot, endpoint and optimizer guards now include pending dependencies. Standard-week edits reject changes to affected reserved dates. Purge locks jobs and technician/days and refuses changes while active reservation obligations exist. The sequential fixture explicitly releases offers left by its injected selection failure before testing later purge behavior.

Local booking, pending-dependency, optimizer, multi-depot, time-off and sequential integration gates pass in the new `waterflex_test.reservation_policy_gate` schema. All 22 browser tests pass in `nullability_ui`. An empty fixture left by the interrupted UI setup was identified and removed by exact IDs; no application data was reset. Earlier failed sequential runs remain in `search_policy_recovery` for inspection. Java/nullability checks, schema validation and contract checks also pass at their recorded checkpoints; subsequent edits must be verified again.

The loader/store/search components are not yet connected to public offer issuance and confirmation. Common-arrangement lifecycle integration, routing population outside transactions, bounded-search enablement, cancellation propagation and the remaining timing/performance work stay open in the original checklist.

## Reservation routing and atomic commit checkpoint

`SnapshotRouting` prepares directed insertion legs, confirmed-only shortcut legs, selected neighborhood cross-route legs and lifecycle before/after legs. Explicit unreachable pairs remain distinct from missing facts. Candidate evaluation stays in memory. Cache reads and batched writes now use short transactions with request-specific PostgreSQL timeouts; network requests occur after those cache transactions end. The full-matrix interface is retained and its persistent writes are batched.

`ReservationOffers` independently validates a common arrangement containing all returned alternatives, retains regular choices before authorized overtime and rejects incompatible proposed rearrangements. `ReservationTransition` prepares confirmation and sibling release, fetching removal shortcuts before validation. Generic dated snapshot loading supports offers whose service date is no longer in a new search horizon after midnight.

`ReservationCommit` locks the job, participating technicians, technician/days and reservation headers in consistent order, re-reads facts and rejects changed configuration, coverage or versions before mutation. It independently validates the proposal under locks, applies confirmed reassignment/order/timing only when requested, updates holds and versions, and saves the remaining common arrangement atomically. The real PostgreSQL gate now verifies successful pending reassignment, stale rejection before mutation, and rollback both before route updates and after route updates when arrangement coverage fails.

Clean strict Java verification passes locally. Directed routing, common sibling reservations, release and confirmation preparation pass deterministic tests. The real database gate passes in `waterflex_test.reservation_policy_gate`. Hosted CI run `35943949246` passed for the preceding checkpoint `f6ff2d2`; it does not verify these subsequent changes. Public lifecycle wiring and the remaining full-plan requirements are still in progress, and no five-second performance acceptance is claimed.
