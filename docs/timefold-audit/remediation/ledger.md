# Remediation ledger

Owner means the numbered implementation step accountable for completion, not a person. Status is **pending** unless the delivery status below records completed work. Each implementation PR records exact tests, verification receipts, experiment attempt IDs where applicable and its PR URL, with one disposition: implemented, adopt, reject, inconclusive, or prerequisite-not-met. Conditional recommendations require evidence for their disposition; mandatory correctness fixes cannot be closed as experimental rejections.

## Delivery status

| Delivery | Disposition and evidence |
| --- | --- |
| Step 1 | Implemented and reviewed in [PR #51](https://github.com/ethan-mcgee/waterflex-scheduler-java/pull/51), merged as `e197204ee72ab6b6aae0c2943b0e42633fd344b0`. Starting receipts remain immutable. |
| Step 2 / TF03 / TF10 | Implementation delivered: strict daily dataset parsing, validation before maps/copy, immutable authoritative facts, clone isolation, missing/unreachable distinction. See [phase 2 contracts and test inventory](phase2/README.md) and [verification receipt](phase2/verification.json). Review PR and CI remain the delivery gate. |
| TF02 | Interim assigned-input rejection implemented by step 2. Construction, unassigned demand and repair scoring remain pending step 4. |
| CR09 | Step 2 daily indexed contract and dense/sparse correctness delivered. Booking/service integration and encoding performance selection remain pending steps 8/15. |
| TF01 / TF08 / CR10 | Step 2 provides validated offline daily input and compatible immutable copying; service/workflow extraction and new isolated constraint/native compatibility probes remain pending their owner steps. |

All benchmark studies and steps 3-16 remain pending. No experimental recommendation has an adopt/reject performance disposition yet.

## Delivery sequence

| Step | Actionable delivery and objective completion condition | Prerequisites |
| --- | --- | --- |
| 1 | Record immutable source/dependency/configuration baseline, map every finding and benchmark requirement, inventory Community compatibility, verify and open review PR. | Audit and accepted plan |
| 2 | Parse and validate indexed datasets before maps/copies; canonicalize and hash; share one immutable fact revision; reject nonempty unassigned input until step 4. | 1 reviewed |
| 3 | Migrate monetary settings with legacy-decimal receipts; update Java, persisted JSON and TypeScript; verify exact fleet-level arithmetic and version invalidation. | 2 |
| 4 | Add assigned/cold/partial/repair modes, construction, explicit unassigned demand, pinned prefixes, and lexicographic BendableBigDecimalScore; reject partial apply. | 2, 3 |
| 5 | Separate public engine execution from exact-version diagnostic adapter; reusable factories with invocation-local state; isolated constraints and scheduled assertion campaigns. | 4 |
| 6 | Install one pre-admission operation deadline; deterministic scoring; cooperative termination/cancellation with worker-owned capacity and measured cleanup. | 5 |
| 7 | Short claim/snapshot/persist transactions, caller-owned attempts, consistent snapshots and revision checks; retain locked independent apply validation. | 2, 6 |
| 8 | Package database-free Spring Boot service and shared module; daily/booking/cancel endpoints, authentication, bounded transient state; equivalent embedded/HTTP adapters. | 3-7 |
| 9 | Skip policy-ineligible fairness; typed unresolved-demand/policy/failure details; durable overnight outcomes and alertable metrics. | 4-8 |
| 10 | Implement CLI v2 dispatch, validation, expansion, run/resume/analyze with immutable artifacts and comparison-block cutoff; preserve v1 semantics. | 2, 5, 6 |
| 11 | Implement native Layer A, policy Layer B, paced workflow Layer C, independently seeded corpus, historical import/anonymization and frozen Layer A fairness targets. | 8-10 |
| 12 | Validate paired equal-dataset analysis; calibrate warmup/concurrency and separate profiling; publish reproducible baseline before optimization. | 11 |
| 13 | Separate PRs in order: indexed lookup, invariant contexts, score-only timing, primitive time, reversible collectors, scaled score, bounded booking generation. Each needs equivalence and measured benefit or evidence-backed retention. | 3, 12 |
| 14 | Matched TABU/LA and neighborhood studies, phase-specific termination, conditional booking prototype and shadow-variable evaluation; record every disposition. | 12, 13 |
| 15 | Freeze one finalist; held-out production-budget pipeline confirmation; matched embedded/remote and independent metro/day resource studies. | 14 |
| 16 | Separate promotion PRs, migration/rollback order, compatible report readers and fresh previews, dashboards/alerts; close ledger with evidence and reviewed decisions. | 15 and all required fixes |

## Audit findings

Current source evidence is pinned by [baseline.json](baseline.json). Existing defect characterizations are historical demonstrations, not desired-behavior acceptance. The compatibility inventory identifies existing tests and gaps; test descriptions below are required future regressions, not claims that those tests exist.

| ID | Owner step(s) | Required tests and objective completion evidence |
| --- | --- | --- |
| TF01 | 2, 7, 8, 15 | Offline daily/booking replay without DB/routing credentials; adapter parity; stale schedule/hold/policy/routing/endpoint rejection; simultaneous claim/apply integration; matched resource/transport study. Caller alone commits. |
| TF02 | 2, 4 | Until construction exists reject nonempty partial/unassigned input; then empty demand, zero technicians, one visit, cold/partial/pinned/infeasible cases preserve exact coverage. Replace omission characterization. |
| TF03 | 2 | Reject duplicate JSON keys/IDs, unknown fields, dangling/mismatched references, invalid indices/numbers/windows/durations, missing required legs before maps/copy. Duplicate visits cannot disappear. Replace three malformed-fact characterizations. |
| TF04 | 3 | Independent exact oracle: 255 minutes at 20.02/hour = 8,509 cents, overtime/mileage/half-cent/overflow/summation-order/repartition tests; migration receipt, decimal-string API/portal/schema tests, stale-cost-model rejection. |
| TF05 | 6, 7, 15 | Fault injection for queue/preparation/routing/validation/persistence/transport delays and cancellation; no long solve transaction; 20-second operation/15-second search allocation; capacity retained through worker cleanup and late results recorded. |
| TF06 | 4, 13 | Quantitative lateness/capacity improves monotonically toward strict feasibility; categorical violations remain higher priority; exact exclusive boundary tests and identical repair seeds. No softened promises. |
| TF07 | 10-12, 14, 15 | B01-B24 below: runnable v2, native/policy/workflow layers, calibration and analysis fixtures, preserved failures, independent datasets and held-out promotion evidence. |
| TF08 | 1, 5 | Compatibility inventory; focused ConstraintVerifier positives/negatives; independent oracle; clone/move/undo; scheduled bounded FULL_ASSERT and NON_INTRUSIVE_FULL_ASSERT plus separate PHASE_ASSERT evaluation. |
| TF09 | 1, 5, 11 | Inventory, loaded artifact version/hash; public engine unaffected by optional telemetry failure; required diagnostic failure invalidates experiment with evidence; unavailable metrics null with reason; observed versus inferred termination. |
| TF10 | 2 | Immutable revision covers routing, rates, demand/services, shifts, absences, capacities, qualifications; copy/round-trip equivalence and assignment isolation. Remove independent mutation paths; replace stale-matrix characterization. |
| TF11 | 9 | Canonical policy and typed UI details agree on strict booking cost/fairness ties, daily ceiling, return travel, bounded completion and actual refinement gates. Preserve zero-overtime acceptance. |
| TF12 | 13 | Reversible fleet collectors: exhaustive/random differential exact cost/fairness/targets and move/undo tests; fleet rounding unchanged; fixed-work equivalence and measured benefit or evidence-backed retain decision. |
| TF13 | 12, 13 | Allocation/CPU/GC baseline then invariant technician context independently measured; no temporary planning solutions/repeated invariant setup; timings/arrivals/absence/asymmetric-road differential tests. |
| TF14 | 13 | Bounded/lazy expanded booking generation preserves stable order, move families and served demand; cancellation/interruption and differential outcomes; allocation/latency evidence. |
| CR01 | 6 | Score path never throws SearchDeadline.Expired; 100 ms, one-second and normal budgets, worker/caller contexts, delayed evaluations, cancellation and cleanup overshoot tests. |
| CR02 | 4 | Explicit coverage penalty, intentional nullable original assignment for new work, pinned-prefix API, demand partition invariant, feasible partial diagnostics and blocked partial apply. Reference targets require complete valid compatible demand/facts. |
| CR03 | 4, 9 | Feasibility/coverage/target ordering and capacity-weighted fairness tests; nonzero-overtime reference starts no fairness solver and records reason, zero-overtime eligible reference still does. |
| CR04 | 9 | 90-to-20-minute overtime example remains diagnostic improvement with policy rejection; persisted/API/UI explanation tests and unchanged schedule. |
| CR05 | 9, 15 | Inject overnight failures and cancellation; per-attempt/metro/date attempted/succeeded/skipped/failed/cancelled receipts, structured context and metrics; unaffected days continue, failed schedules unchanged. |
| CR06 | 14 | Compile and exercise solver/phase fixed-budget, diminished-returns, unimproved-time combinations per algorithm; enclosing spent/step caps survive overrides; production and long budgets separate; held-out quality/cleanup evidence. |
| CR07 | 12, 13 | Separate indexed lookup, score-only timing, primitive-time treatments; profile first, preserve independent explanation/validation; explicit units, overflow, DST and exclusive-boundary equivalence. |
| CR08 | 3, 13 | Bound and prove scaled score ranking/fairness equivalence only after exact money; retain decimal scoring if proof fails. Benchmark separately from collectors; no policy change through rounding. |
| CR09 | 2, 8, 15 | Tagged dense/sparse indexed directed roads; missing versus unreachable, dimensions/duplicates/indices/completeness by operation/neighborhood; canonical round trips/hashes; payload/decode/validation/preparation p95 gates decide encoding. |
| CR10 | 1, 5, 10, 11 | Version-specific Community inventory/probes for construction/unassigned/pinning/scores/constraints/moves/termination/stats; no Enterprise, preview, move threads, nested native parallelism, custom scoring threads or solver fork. |
| CR11 | 14 | Evaluate declarative timing shadows only if profiling after earlier improvements still shows dominant timing and route-length evidence supports it; otherwise record measured prerequisite-not-met disposition. |

## Benchmark requirements

These rows cover the benchmark redesign sections and the accepted plan's measurement/promotion decisions. All are pending. The existing v1 runner and illustrative campaign JSON do not satisfy v2 acceptance.

| ID | Requirement and owner | Required tests and completion evidence |
| --- | --- | --- |
| B01 | Single JSON, version dispatch (10) | Actual infra/experiments.py supports v2 validate/dry-run/run/resume/analyze. Explicit v1 path retains seeds/warmup/runtime meaning. No CLI experiment-parameter overrides. |
| B02 | Strict schema (10) | Unknown/duplicate keys/cases, nonfinite/missing/invalid fields, units, seeds, budgets, phases, unsupported versions/settings rejected. Explicit nullable/inapplicable fields; no fabricated measurements. |
| B03 | Complete parameter inventory (10) | Dataset/config/seed/fork/budget/warmup/JVM/CPU/heap/GC/environment/instrumentation/analysis/load/cache/cutoff fields explicit; no mutable profile aliases; credentials excluded. |
| B04 | Pre-dispatch provenance (10) | Original bytes, resolved configuration, deterministic expanded order, requested resources and observed hardware/JDK/artifacts/flags/affinity/competing activity saved and hashed before dispatch. |
| B05 | Estimation (10) | Uneven matrix expansion, cases/JVMs/blocks/reference setup/warmup coverage; pipeline counted once, overhead separate; serial and optimistic parallel estimates clearly distinguished from observed runtime. |
| B06 | Immutable failure/cutoff/resume (10) | Preserve TCP 47983 lock, frozen outputs, failures and interruptions; stop new comparison blocks, finish dispatched allowances; no retry; new attempt resumes hash-compatible missing blocks, no duplicate observations; fresh JVM warms again. |
| B07 | Native Layer A adapter (11) | BOM-matched benchmark/test dependency, shared config generator, thread-safe validated snapshot IO with fresh entities; no database/live routing during measured calculation. |
| B08 | Native compatibility/failure (11) | Effective inherited phase counts, caps, seeds/sub-runs, enabled/unavailable stats, XML/CSV imports; failed native batch completes diagnostics then outer dispatch stops; retain failed sub-runs and interruption. |
| B09 | Native artifact retention (11) | HTML/XML/CSV histories, solved input/output snapshots, effective configs and hashes stored outside build-clean directories in attempt archives; raw bulk output stays out of Git. |
| B10 | Complete policy Layer B (11) | Reference, target creation, fairness and independent acceptance measured together; each treatment's own reference; allocation/unused transfer and total allowances production-equivalent. |
| B11 | Paced application Layer C (11, 15) | Intended and actual arrival times, all request outcomes/queue/serialization/routing/persistence/reservations/transport/cancellation/cleanup; generator does not hide stalls; timeouts are not invented exact durations. |
| B12 | Representative corpus (11) | Versioned independently seeded geography/skills/windows/durations/absences/utilization/assignments; historical export/anonymization/import; legacy fixtures separate. Explicit synthetic labels until historical inputs exist. |
| B13 | Cohort separation (11) | Assigned, cold/partial, repair and invalid-input classes separate; invalid input is contract testing, repair reports time-to-feasibility/unresolved demand, no mixed feasible cost rankings. |
| B14 | Frozen fairness targets (11) | One validated complete reference/target per Layer A dataset with hash and setup work; common across treatments, compatible demand/model/routing; Layer B deliberately keeps treatment-specific references. |
| B15 | Pairing and reliability (12) | Pair on dataset/target/budget/effective seed/fork/model/routing/runtime cohort; reject duplicates, retain unmatched reasons; requested/completed/valid/paired counts and every failure in denominators. Booking cost requires identical served customer identities. |
| B16 | Equal-dataset inference (12) | Average repetitions within datasets then equal-dataset effects, whole-dataset bootstrap 10,000 draws/95%/explicit seed; preserve families. Fixtures expose pseudoreplication, subgroup reversals, missing-value fabrication and pairing errors. |
| B17 | Warmup calibration (12) | Path-specific 0.2-, 30-, 60-second probes per fresh JVM, stable criterion registered; ambiguity requires explicit new attempt. Warm JVM, scheduler/provider caches and cold process remain distinct. |
| B18 | Concurrency/resources (12, 15) | Calibrate outer concurrency with single-threaded solvers and native parallelism one; matched CPU/memory, serial versus concurrent cohorts separate; report actual resource receipts. |
| B19 | Profiling/instrumentation (12) | Separate allocation/CPU/GC diagnostic runs; effective enabled metrics recorded, missing null with reason, matched timed instrumentation. Publish reproducible baseline before hot-path changes. |
| B20 | Acceptance/neighborhood studies (14) | TABU/LA matched change/swap and selected-count limits; distinct declared foragers; one added move family before interactions; preserve historical controls. SA only through separate temperature study. |
| B21 | Termination studies (14) | Fixed-budget controls per algorithm/eligible reference/fairness/repair phase; early stop inside enclosing caps; measure late improvements, useful outcomes, CPU and cooperative overshoot; long budgets separate. |
| B22 | Conditional booking/modeling (14) | Booking prototype only after parity/profiling hypothesis; preserve enumeration/promises/no-new-overtime/validation/witnesses. Incomplete search never proves infeasibility. Shadow remodeling requires CR11 evidence. |
| B23 | Held-out confirmation (15) | Seal whole datasets, select/freeze one finalist before confirmation, complete production-budget policy, no extra failures/incomplete outcomes/overtime; strict cost interval and family gates; no tuning against confirmation. |
| B24 | Service promotion and rollback (15, 16) | Matched embedded/remote p50/p95/memory/throughput/useful outcomes/load/cache limits, p95 <= 1.05 * control; metro/day and routing concurrency studied separately after provider safety/single-flight/cancel checks. Adapter/algorithm rollback independent of corrected money/validation; dashboards, migration order and version compatibility documented. |

## Existing characterization replacement map

Retain the original test/log identities in baseline.json when these tests change. A passing characterization currently reproduces a defect.

| Existing TimefoldAuditEvidenceTest method | Desired replacement owner |
| --- | --- |
| duplicateTechnicianIdentityPassesIndependentValidation | Step 2: reject duplicate technician identity before maps/validation. |
| copySilentlyCollapsesDuplicateVisitFacts | Step 2: reject duplicate visits before copying, preserve requested count. |
| nonFiniteRateBecomesFeasibleZeroCostAtCoreBoundary | Steps 2/3: reject invalid money before scoring/validation. |
| unassignedDatasetReturnsZeroScoreWithoutServingVisit | Step 2 explicit rejection, then step 4 construction and coverage contract. |
| routeFactsCanDisagreeAfterReplacingMatrix | Step 2 immutable authoritative facts and isolated assignment copies. |
| hardPenaltyPlateauHidesSizeOfWindowViolation | Step 4 quantitative feasibility gradient and exclusive boundary. |
| binaryRateRoundsHalfCentDownInBothEvaluators | Step 3 independent exact oracle returning 8,509 cents. |

## Verification and closure

Every implementation PR runs Java 25 Maven verify and `-Pnullability clean verify`. Interface/persisted-data changes additionally run portal lint/typecheck/nullability/unit/build. Migrations/workflows require schema-contract and DB integration; affected booking/reservation/optimizer/time-off/cancellation/browser regressions remain gates. Benchmark changes run experiment/archive/import/resume/evidence checks; packaging/CI changes validate workflows and Compose. Never weaken nullability or fabricate scheduling defaults to pass.

Historical baseline: see baseline.json. Phase 1 verification: see verification.json. Step 2 implementation evidence: see phase2/verification.json and its associated PR/CI. Overall audit: **open**. Step 3 awaits step 2 review; performance studies have not started.
