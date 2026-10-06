# Step 5: public engine, optional diagnostics and assertion campaigns

Starting revision: `71df47a9d3d0ba9560fab6b1ddbfe9b92b62c813`, merged [PR #54](https://github.com/ethan-mcgee/waterflex-scheduler-java/pull/54). Its review gate is complete; its original receipts remain unchanged. This phase implements TF08 and TF09 and adds CR10 compatibility evidence. Java 25, Community 2.6.0, score model `bendable-decimal-repair-v2`, cost model `exact-fleet-half-up-v2`, production TABU/seed 17 and `NO_ASSERT` remain unchanged.

## Execution and diagnostics

`SolverEngine` owns configuration generation and a reusable factory for each effective variant, seed, assertion mode and construction selection. Each invocation validates inputs, copies planning state and builds a fresh public `Solver`. Copies share immutable facts and isolate mutable entities, assignments and inverse shadows. Assigned and construction configurations retain their existing neighborhoods, seeds and step caps. There is one newly rejected compatibility combination described below; no fallback silently changes its neighborhood.

Production `DailySolver` uses only public execution APIs. `SolverExperiment` now lives in test scope, which excludes `DefaultSolver`, phase listeners, scopes and internal counters from the production artifact. The experiment adapter requires the loaded core version 2.6.0 and available artifact identity. It checks the implementation before attaching its internal listener. Benchmark configuration, warmup and seed semantics remain the existing v1 behavior; the v2 experiment runner remains step 10.

`EngineProvenance` reads Maven properties from the JAR containing the loaded public factory class and hashes that artifact. Provenance failure produces explicit null version/hash values and a reason, without failing production calculation. It does not substitute the configured Maven version for an observed version. The production engine label is derived from this provenance.

New phase statistics use `format: 2`. Unavailable counters and time to best are explicit nulls with reasons. Production does not enable internal counters, so it reports `NOT_ENABLED` and wall time around public `solve()`. Experiments preserve the internal solver clock and record `TIMEFOLD_INTERNAL_2_6_0` instrumentation and the effective assertion mode. Time to best may be unavailable independently of the required counters. Empty demand or no technicians reports observed `NOT_RUN`, `NO_SEARCH`, and null counters. It does not fabricate zero measurements or a successful search.

`SOLVE_RETURNED`, `TERMINATED_EARLY` and `NOT_RUN` are observed statuses. The internal adapter's step/time/phase labels remain counter/clock inferences and explicitly say `INFERRED`. A solver return does not establish the active termination condition, coverage, feasibility, optimality or application authority. `DailyOutcome` and independent validation retain their separate roles.

Optional telemetry attachment/read failures preserve the result and its independent outcome. Required experiment diagnostic failures throw an exception carrying that result, unavailable reasons and outcome. The standalone benchmark writes a failed-case receipt and stops; it does not emit a valid measurement or rerun the case. Ordinary solver and scoring failures still propagate. No optional telemetry wrapper converts a solver failure into success.

## Persisted and portal compatibility

Java `SavedJson`, TypeScript decoders, dispatch rendering and the optimizer integration check understand v2 diagnostics together. They reject omitted nullable fields, null counters without reasons, fabricated counters alongside an unavailable reason, incomplete artifact provenance, unknown formats and inferred causes labelled as observations. Historical records without `format` retain the original mandatory-counter contract and remain readable. The dispatch view identifies historical inference and renders unavailable counts explicitly.

Deploy matching Java and portal versions together: the older portal decoder cannot read v2 null counters. No SQL migration, score change, cost change or new application authority is introduced. Existing complete-coverage, zero-overtime, promise, cutoff, reservation and locked apply validation remain required. This PR does not deploy the application or promote an algorithm.

## Community compatibility finding

The expanded `FULL_ASSERT` campaign exposed list corruption during undo for `RUIN_RECREATE`, seed 29, on the partial fixture with a pinned prefix. The failure is retained in `evidence/full-assert-3.jsonl` and `evidence/full-assert-3.log`, including effective XML and the Timefold stack trace. The previous seed-17 short probe was insufficient to establish compatibility. This receipt demonstrates the failing combination, not a diagnosis of all Timefold ruin/recreate implementations or versions.

The calculation boundary now rejects `RUIN_RECREATE` whenever an input has a pinned prefix, in all assertion modes and assigned/partial input paths. There is no substitution, dependency upgrade, removed pin or weakened assertion. Ruin/recreate remains available for unpinned fixtures; the other seven variants retain pinned support. Regression tests exercise the rejection before solving and confirm the caller's state is unchanged. This restriction must remain until a separately verified dependency/configuration change resolves the recorded failure. Production TABU is unaffected.

An earlier campaign also exposed a faulty test assumption: bounded absence repair need not finish with feasible assigned work. The corrected campaign compares categorical/quantitative scored feasibility with independent served-work validation and rejects application eligibility for infeasible outcomes. It still checks fresh score agreement, coverage, pins and scoring/validation agreement for feasible results. The failed initial test attempt is retained, not reclassified as a pass or proof of infeasibility.

## Constraint and assertion coverage

`ConstraintRulesTest` uses `ConstraintVerifier` from the pinned core artifact, without a removed 1.x test dependency. Known-value tests cover qualification penalties, reachable return roads, arrival at the exclusive window end, absences, return travel, capacity units, assigned/unassigned coverage, lexicographic target priorities, exact cost, overtime/ceiling targets and capacity-weighted fairness. Existing independent timing and monetary oracle tests remain intact.

`SolverEngineTest` covers concurrent factory/clone isolation, fresh solvers, distinct effective configurations, fixed-work public/diagnostic result equivalence, loaded provenance, no-search outcomes, optional attachment/read failures, required-diagnostic invalidation with a retained receipt, malformed saved diagnostics and pinned ruin/recreate rejection. Existing move/undo and construction tests continue under supported configurations.

The scheduled workflow `solver-assertions.yml` runs daily at 09:00 UTC, with separate jobs for `FULL_ASSERT`, `NON_INTRUSIVE_FULL_ASSERT` and `PHASE_ASSERT`. Each uses seeds 17/29/41, eight variants and assigned, partial-pinned, absence-repair and fairness fixtures: 93 supported solves plus three explicit compatibility rejections. Each solve receives 2 seconds on scheduled/manual runs, bounded by a 15-minute job timeout. PR checks use 250 ms per solve. The workflow retains JSONL and Surefire artifacts on success, failure or interruption, with distinct attempt names and no automatic retry.

Maven profile `solver-assertions` selects a full assertion mode; `phase-assert-evaluation` separately evaluates `PHASE_ASSERT`. Ordinary tests skip the campaign. A campaign requires an explicit revision and new output path, uses `CREATE_NEW`, records dispatch before execution, flushes results/failures and acquires the existing loopback port 47983 exclusion lock. Files remain outside build-clean directories. An interrupted dispatched record remains incomplete evidence.

Local verification exercises all three profiles at the PR allowance. It does not claim a completed future scheduled run or performance result. The named production/timing profile is still `NO_ASSERT`; timed comparisons must match mode and instrumentation as well as seed, budget and dataset. Do not pool assertion campaign timings with existing benchmark measurements. Performance calibration, whole-operation deadlines, service extraction and all later studies remain pending.

## Verification

The immutable [local receipt](verification.json) identifies source hashes, commands, raw logs, successful campaigns and prior failed attempts. The [annotation verification](annotation-verification.json) records the final explicit ConstraintVerifier generic annotation and successful strict build without a new nullability diagnostic. Java verification passes 233 tests with the explicit campaign test skipped; the three campaigns run separately. Portal unit tests pass 108 and portal nullability passes 18. Lint, typecheck, production build, 63 experiment tests (one existing Windows skip), 10 audit helpers, workflow syntax, Compose and historical baseline verification pass.

Exact-head hosted CI additionally gates workflow/ShellCheck/Compose checks, PostgreSQL/schema integration, booking/reservation/cancellation, optimizer/time-off and browser regressions. Local checks do not substitute for those hosted results. The PR records their actual status and remains open for owner review before step 6.
