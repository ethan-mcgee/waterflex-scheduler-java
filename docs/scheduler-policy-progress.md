# Scheduler policy and routing implementation progress

This is a partial implementation of the consolidated scheduler plan. It is not ready for the plan's production rollout. In particular, the booking overtime gate and five-second appointment-search guarantee are **not implemented**. The PR must remain a draft until the outstanding work and acceptance gates below are completed.

Implementation revision: `7109c2d248d07d2a4e6a5d75b1fda19ab312cafc`.
Inspected baseline: `2d17885141d4a901b06b3f9732530db85c951568`.

## Implemented behavior

- Daily optimization uses independently validated overtime, operating cost, and capacity-weighted decimal workload variance. Available regular capacity unions approved absences, clips them to the regular shift, and applies the paid daily limit. Idle technicians qualified for at least one service in the day's demand participate.
- The reference phase searches overtime first and cost second for up to ten seconds. The fairness phase receives the remaining portion of the fifteen-second search allocation and enforces the reference overtime and `floor(referenceCost * 1.02)` cost ceiling. The solver override replaces the XML termination configuration for ordinary previews. Repair continues using the existing XML termination, including its 1,000-step cap. Comparative solver tuning is still required.
- Named Constraint Streams constraints update affected route metrics. Fleet cost remains rounded after combining route resources. Full recomputation remains available for reporting and differential checks; `RouteEvaluator` remains the independent acceptance check.
- Preview persists policy metrics, rules, the reference route arrangement, acceptance reason, and signed cost change in `optimization_run.policyAnalysis`. Apply restores and validates the reference against locked current data. Ordinary apply rejects overtime increases and legacy previews lacking the policy version. Historical metrics remain explicitly unavailable when absent.
- Fixed working segments can depart later to absorb avoidable waiting. Timing respects exclusive window ends and leaves segment return times unchanged. Baseline and proposed routes use the same rules. Preview summaries persist departure/return times and display them in dispatch. This is not a complete interval-placement optimizer or a durable current-route timing implementation for every booking lifecycle mutation.
- Booking prepares settings, absences, road data, and a baseline once per technician/date during insertion scanning. Candidate scoring performs no database or network operations. The existing long transaction and reservation model have not been replaced.
- `/internal/legs` accepts explicit directed pair IDs and an expected routing identity. Booking requests endpoint, adjacent-stop, and candidate incoming/outgoing legs. The scheduler batches persistent reads and upserts and shares the existing bounded memory cache. Missing, malformed, unavailable, and explicitly unroutable results do not become invented travel times. Full matrix and geometry endpoints remain available.
- Appointment search displays an accessible indeterminate progress bar labeled "Finding available appointments". Existing error handling retains entered form values. Typed incomplete-search results and end-to-end retry propagation remain outstanding.

## Migration and compatibility

`20260924010000_scheduling_policy` adds nullable `policyAnalysis` and validated settings for two regular windows, 90% utilization, 2% fairness allowance, and 5,000 ms. Existing settings fingerprints include these values. The booking thresholds and deadline are stored policy definitions, not active enforcement in this revision.

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

1. Full immutable horizon snapshots, schedule/reservation versions, bounded admission, deadline propagation, cancellation, lock/commit budgets, conflict retry, and typed search outcomes.
2. Booking confirmed-utilization aggregation, distinct-window scarcity, regular-first ranking, incremental fairness, persisted overtime authorization, and enforcement only after a completed bounded scarcity pass. Booking currently retains its legacy cost-based offer policy.
3. Versioned common reservation arrangements, reassignment dependencies, atomic confirmation of rearranged routes, and revalidation across refresh, release, expiry, cancellation, restart, and guarded technician changes.
4. The prescribed two-move relocation/swap/reversal search, six-route shortlist, beam width eight, 500-arrangement cap, horizon round allocation, diagnostics, and feature flags.
5. Prefix/suffix incremental timing, full interval-placement handling, current segment timing persistence across every mutation, and separate road-versus-buffer reporting.
6. Sparse request coalescing with isolated caller deadlines, low-priority versioned prewarming, and contraction-hierarchy artifact benchmarking.
7. Explicit additional-overtime approval for disruption repairs, shared concurrency limits and booking priority, solver acceptance-strategy comparisons, tie-breaking by disruption, and accurate termination/provenance telemetry.
8. Reproducible 20/30/50-technician benchmark matrix, cold/warm and 1/5/10 concurrent booking streams, exact-enumeration quality oracles, five-second p95 proof with served-demand reporting, and complete reservation/failure regression coverage.

No claim is made that the consolidated plan, reservation guarantees for rearranged booking, or performance acceptance has been completed.
