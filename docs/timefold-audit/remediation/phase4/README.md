# Step 4: construction, unresolved demand and repair scoring

Starting revision: `7c0be97def3599ec129f4b81079f86f9a75f7651`, merged PR #53. Timefold Community remains 2.6.0 and Java remains 25. The score model is now `bendable-decimal-repair-v2`; the exact cost model remains `exact-fleet-half-up-v2`. This delivers TF02, TF06 and CR02, plus CR03's score/target ordering. The fairness-skip policy change belongs to step 9.

## Calculation contract

Every visit occurs exactly once across assigned and explicit unassigned input demand. `ASSIGNED` requires complete input coverage; `COLD` requires all demand initially unassigned; `PARTIAL` and `REPAIR` permit either partition. New visits have an intentionally null original technician and original start, both present or both null. Copies share one immutable fact revision and own independent list assignments, inverse shadows and scores.

Construction runs before local search when the input contains unassigned demand. Fully assigned inputs retain local-search initialization. Production caches the two effective configurations. A list value may remain unassigned; it receives a coverage penalty and appears in `DailyOutcome.unassignedVisitIds`. Empty demand and zero technicians have explicit no-search paths. Empty demand has complete coverage; nonempty demand with zero technicians stays unresolved. No unsuccessful search proves infeasibility.

`pinnedPrefix` is an index into the assigned route list, using Community 2.6.0's `@PlanningPinToIndex`. The immutable facts retain the ordered pinned identities. Unsupported descriptions, invalid bounds and mutations of the captured prefix are rejected. Construction and local search preserve the prefix. Pinning does not authorize infeasible work.

The indexed dataset schema remains v1, with an explicitly changed score-model identity. Previously sealed datasets with the old score model require an intentional new dataset/versioned calculation, rather than silent rehashing. Dense and sparse encodings preserve the same canonical facts and partition. Required visit-to-visit roads are checked even with zero technicians. Missing required roads are rejected; explicit unreachable roads remain categorical facts.

## Score priorities

`BendableBigDecimalScore` has five hard and two soft levels, compared lexicographically. Later levels cannot compensate for earlier violations.

| Level | Meaning |
| --- | --- |
| Hard 0 | Required qualifications and reachable directed roads, counted categorically |
| Hard 1 | Exclusive-window lateness seconds, unavailable working seconds including absences and return travel, plus capacity excess minutes converted to seconds |
| Hard 2 | One penalty for each unassigned visit |
| Hard 3 | Absolute deviation from the recorded reference overtime target, in minutes |
| Hard 4 | Excess over the reference fairness cost ceiling, in cents |
| Soft 0 | Reference overtime minimization, or capacity-weighted workload variance in the fairness phase |
| Soft 1 | Exact fleet cost rounded once to cents |

Feasible routes still use the existing exact route timing search. For an infeasible reachable route, scoring evaluates a relaxed contiguous trajectory to provide a quantitative direction of improvement. Arrival exactly at an exclusive window end has a positive violation. The trajectory is diagnostic, not a minimum-violation solution or independent placement proof. Unknown road durations are never invented; unreachable routes receive a categorical penalty and cannot become eligible through their diagnostic resource totals. Independent validation remains authoritative.

A fairness target can only be created from a complete, independently feasible reference with the same immutable fact revision, matching independently calculated arrivals and exact cost. Its ceiling must cover the reference. A target cannot be transplanted to changed absences, demand or other facts.

## Outcomes and application

`DailyOutcome` reports operation mode, model version, assigned/unassigned identities, completeness, independently feasible assigned work, scoring agreement and policy eligibility separately from solver termination. Eligibility requires complete coverage, agreement with independent validation, zero overtime and satisfaction of any frozen target. It does not grant commit authority or claim improvement over the caller's baseline.

Absence repair returns feasible partial work as `SKIPPED / UNRESOLVED_DEMAND` diagnostics, with search provenance and unresolved identities retained in the time-off report. It cannot create an ordinary applicable partial preview. Saved current-model previews must contain a consistent outcome before apply; the existing locked complete-coverage, promise, cutoff, routing, hold and policy checks still run. The portal shows coverage and keeps Apply disabled for partial/ineligible outcomes or historical previews without current provenance. The backend independently enforces these gates.

Cold and partial inputs are supported at the offline calculation boundary. This phase does not add the remote service or new public scheduling endpoints. Service extraction, public engine/diagnostic separation, one operation deadline, transactional ownership and full overnight telemetry remain later steps. Search budgets and production algorithm choices are not tuned here. Enabling unassigned list moves intentionally changes the model's supported neighborhood.

## Rollout and rollback

1. Keep the phase 3 exact-money migration and matching Java/portal versions in place. Stop scheduling writers and preserve a database backup before applying this phase's migration.
2. Apply `web/prisma/migrations/20261006212000_daily_score_contract/migration.sql` through the normal Prisma deployment process. It marks incompatible unapplied `PREVIEW` and `REPAIR_PREVIEW` rows `STALE / SCORE_MODEL_CHANGED`. Weights, proposals, saved evidence and applied history remain intact.
3. Deploy matching Java and portal contracts together, then generate fresh previews. Old reports remain readable; they do not regain apply authority through the new model.
4. Algorithm rollback is independent of corrected validation and exact money. Reverting this score contract requires coordinated Java/portal versions and fresh previews for that contract. Do not make old proposals applicable by editing provenance or reversing stale statuses.

No database deployment or production promotion was performed by this PR. This is a correctness/model change, not evidence of improved latency, production savings or capacity. Performance, remote promotion and the 5% p95 gate remain pending.

## Verification and evidence

[verification.json](verification.json) records local commands, source hashes and raw evidence identities. Java verify and strict nullability each pass 222 tests; portal unit tests pass 107, and portal nullability passes 17. Lint, typecheck, build, 63 experiment tests (one Windows skip), 10 audit-helper tests, and retained baseline verification pass. The baseline check verifies 225 source files, 203 historical files, three source trees and 101 dependency artifacts. Earlier evidence is unchanged.

`DailyConstructionTest` covers cold/partial construction, nullable origins, no technicians, empty demand, impossible windows and pins, qualification/unreachable categorical priority, absence repair, quantitative capacity/absence gradients, target provenance/order, stored outcome validation and clone isolation. Every one of the eight supported variants constructs partial inputs with a pinned prefix under `FULL_ASSERT`. Existing move/undo, capacity-weighted fairness, return travel, booking, money and exclusive-window regressions remain in the suite. All seven original audit characterizations now assert desired behavior; their historical receipts are retained.

These probes use the loaded 2.6.0 artifact and its actual annotations, score API and solver configuration. Newer online documentation is context, not proof of availability in 2.6.0. Dedicated ConstraintVerifier campaigns, NON_INTRUSIVE_FULL_ASSERT and PHASE_ASSERT evaluations remain step 5.

Database CI executes the real score migration against preserved/current/applied fixture rows. Optimizer integration rejects old score provenance without changing appointments. Time-off integration retains unresolved demand and rejects approval. Browser regressions keep partial Apply disabled and preserve sequential apply behavior. Those gates require the PR's exact-head CI result; they were not run against a local database. The PR records actual CI state. The local receipt is immutable and does not substitute for CI.

Initial compilation/test setup failures and the unavailable `python-docx` helper environment remain in raw logs. Corrected source and the already existing audit Python environment pass the final runs. No dependency was upgraded and no benchmark campaign was started.
