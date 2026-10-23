# Daily solver comparison protocol

For validated JSON matrices, frozen artifacts, interruption/resume, and automatic graphs, use the [configurable experiment toolkit](configurable-experiments.md). The standalone runner below remains available for historical reproduction.

`SolverBenchmark` is an explicit standalone test-class entry point. It does not run the performance matrix during unit tests or change production configuration. `SolverExperimentTest` checks all eight community configurations under `FULL_ASSERT` and independently validates each starting dataset at 20, 30 and 50 technicians.

The configurations compare the existing 1,000-step cap, the same configuration without that cap, Late Acceptance with relocation alone, Late Acceptance with relocation and swaps, Tabu Search, sublist moves, K-opt, and low-frequency ruin-and-recreate. Late Acceptance uses history 400 and accepted-count 1; Tabu uses entity tabu size 7 and accepted-count 1,000. Configuration XML, seed and SHA-256 are recorded for each run. No preview or Enterprise selection features are enabled.

Each measured case receives a 15-second combined budget, split into a 10-second overtime/cost reference search and the remaining time for fairness. An early reference stop transfers time to fairness. Candidate routes are independently evaluated before policy acceptance. Baseline, reference and accepted workload metrics are recorded separately. A short smoke budget verifies the harness but is not quality evidence.

The deterministic datasets cover sparse, clustered, dispersed, mixed-skill, tight-window, absence and uneven near-capacity routes. They contain synthetic directed legs, explicitly identified as fixture evidence. They do not establish Omaha road performance, booking throughput, customer latency, or production capacity. Travel buffers remain 20 percent plus five minutes per leg.

Run the compiled test entry point with a classpath containing test classes, production classes and the Maven dependency classpath. Required JVM properties are `benchmark.revision` (the exact committed harness revision) and `benchmark.output` (a new JSONL path). Optional properties are `benchmark.durationMs` (default 15000), `benchmark.sizes` (20,30,50), `benchmark.workloads`, `benchmark.variants`, and `benchmark.seeds` (default 17). Comma-separated values select subsets. Output uses create-new semantics to prevent accidental replacement of prior evidence.

Freeze the compiled classes and solver resource into a separate artifact before a long run so concurrent development cannot change its code or configuration. Record its SHA-256, hardware, JVM heap and other local activity alongside the output. Every configuration receives an identical short warmup. Variant order rotates across datasets. Repeat promising comparisons with multiple seeds before choosing production defaults.

Results include actual completed steps, move evaluations, score calculations, time to best, solver time and observed termination classification. They also include process CPU time, heap-pool peak usage, changed assignments, retimed appointments, paid waiting and independently verified hard-constraint violations. Heap-pool peaks are diagnostic measurements, not resident memory. A termination classification of `PHASE_COMPLETED` does not assert global optimality. The adapter intentionally targets the installed Timefold 2.6.0 implementation for diagnostics and must be revisited when upgrading.

Initial harness verification: strict Java clean verify passed; all eight configurations completed 500 ms smoke cases for sparse and absence workloads with independent validation.

The first frozen run at `18a9f0b` exposed two harness limitations. Its `CURRENT_CAPPED` rows did not actually retain the step limit because Timefold's per-call termination override replaced it. Those rows and their cap-related termination labels must be excluded from cap comparisons. The corrected adapter explicitly supplies both limits and has a regression test stopping at exactly 1,000 steps. The other uncapped configurations in that artifact are unaffected by this override issue. Existing ordinary preview calls also used the time-only override, so a cap in the resource was not evidence that it applied to those calls.

The first `NEAR_CAPACITY` fixture was also too lightly loaded (about 62 percent fleet utilization in the 20-technician case). Keep those rows only as uneven-load evidence. The replacement fixture assigns eight 55-minute appointments per technician and tests confirmed paid workload at or above 90 percent of available regular capacity for every fleet size. Rerun the affected comparisons using the corrected committed fixture and retain both artifact revisions in provenance. Do not silently combine datasets with different fingerprints.

## Solve-scoped profiling and the CR07 treatment (2026-10-14)

The harness records one JDK Flight Recorder file per measured solve when `benchmark.jfr` names a directory
(`-Dbenchmark.jfr=<dir>`, profile settings): the recording starts just before `SolverExperiment.solve` and is dumped
just after, so its samples cover the solve alone, with no warmup, validation or report in them. Each file is named
`<technicians>-<workload>-<fixtureSeed>-<variant>-<seed>-<phase>.jfr`. The configurable experiment toolkit's whole-JVM
recording (`instrumentation.jfr`) is unchanged; this is the instrument for asking where a solve spends its time.

The first such profile, `TABU_KOPT` on 20 and 50 technicians, `CLUSTERED` and `DISPERSED`, fixture seed 7001 (not one
of the tuning fixtures), 10 s per case, is in [solver-profiling-2026-10-14.json](evidence/solver-profiling-2026-10-14.json).
Of 3,719 samples, the engine's own methods held about a tenth; the rest was what scoring did around them, and it named
one treatment of the three the audit had listed (CR07, indexed lookup and score-only timing), not the other two:

| Leaf frame | Before | What it was |
| --- | ---: | --- |
| `ImmutableCollections$MapN.probe` | 13.1 percent | road lookups keyed by a `from>to` string built for every leg of every scored route |
| `HashMap.putVal` and friends (`newNode`, `resize`, `Map.ofEntries`, `listCopy`) | 15.2 percent | arrival maps and a `DayPlan` built for every scored route, then copied into the result |
| `BigDecimal` and `MutableBigInteger` | about 17 percent | buffered travel minutes computed per leg, money per route, and the bendable BigDecimal score itself |
| `RouteTimingSearch.segments`, `RouteTimeline.evaluate` | 9.5 percent | the timing itself |

The treatment keeps every number the same and removes the work around it: `RoadMatrix` gives each endpoint identity
a number and holds the legs in two tables with their buffered minutes computed once per fact revision (built lazily
on the first scoring lookup, so a plan evaluated once outside the solver, such as a booking candidate, pays nothing
new); `RouteTimeline` and `RouteTimingSearch` evaluate a route against `PlanFacts` directly, and on the score-only path
build no arrival map (the score reads minutes, meters and violations; arrivals come from `DayScoreCalculator` and
`RouteEvaluator`, which still ask for them). `ScoringExactnessTest` captured a step-limited, fixed-seed solve on three
fixtures before the change and asserts the same score, move count, arrangement and independent evaluation after it;
the full assertion campaigns in CI exercise the same path under `FULL_ASSERT`.

Paired on 24 cases (`TABU_KOPT`; 20 and 50 technicians; `CLUSTERED`, `DISPERSED`, `ABSENCE`, `TIGHT_WINDOW`; seeds 17,
23, 41; fixture seed 7001; 10 s), the engine jar built from the commit before the change against the treated one, same
harness, one after the other on an idle workstation:

| Fleet | Cost-phase moves per second, before to after (median) | Fairness-phase speedup (median) | Cases cheaper after at the same budget |
| --- | --- | ---: | --- |
| 20 technicians | 131k to 193k (1.54x) | 1.32x | 9 of 12 (2 ties, 1 dearer by 50 cents) |
| 50 technicians | 107k to 222k (2.00x) | 1.28x | 12 of 12 |

Two independent runs of the same four cases on each side agree within 10 percent, so the speedup is not a
run-to-run effect. The fairness phase gains less because its time goes to `SchedulingPolicy.fairness` (a BigDecimal
variance over the fleet per move) and the BigDecimal score impacts, which the treatment leaves alone. After it the
profile is `MapN.probe` 12.6 percent (now the identity-to-number probe, one per visit), `Duration.between` and
`Instant.until` 16 percent, and BigDecimal about 20 percent: the next step, if one is wanted, is time as primitive
seconds in the timing search, with the same exactness test as its gate, and it is not in this change.

What this is not: a search-policy change. The acceptor, moves, tabu size and budgets are what the Tabu variant study
chose; a solve now evaluates more moves in the same seconds, which is why 21 of 24 cases ended cheaper. The promotion
review belongs to policy changes and was not run for this. Two of the audit's items stay out: TF13 (invariant
technician context) and TF12 (incremental collectors) were not named by the profile; and a `moveThreadCount` variant
cannot be built, because multithreaded solving is a Timefold Enterprise feature (the commercial editions page,
https://docs.timefold.ai/timefold-solver/latest/commercial-editions/commercial-editions, retrieved 2026-10-14) and
`CampaignSolverConfiguration` refuses any value but `NONE` for the Community artifact this project builds with.

## Score and time representation (CR08, 2026-10)

The CR07 profile left two representation costs in the scoring path. `Duration.between` and `Instant.until` held 16 percent, and BigDecimal about 20 percent. CR08 deferred the score representation until the numeric contract was written down and the BigDecimal share was split between the score impacts and the variance formula. This study measures both as separate treatments, and then together. The contract is in [scheduler-policy.md](scheduler-policy.md), "Numeric contract".

| Arm | Code | What changes |
| --- | --- | --- |
| baseline | commit `ec0efc4`, `TABU_KOPT` | nothing: production scoring, timing on `Instant` |
| S | commit `ec0efc4`, `TABU_KOPT_LONG_SCORE` | long score levels, and the variance in rounded units of 1e-9 computed in binary64 |
| T | commit `fa62792`, `TABU_KOPT` | route timing on long nanoseconds after the shift start |
| T+S | commit `fa62792`, `TABU_KOPT_LONG_SCORE` | both |

Equivalence came before any timing.
- T passes `ScoringExactnessTest` unchanged: same score, moves, arrangement and independent evaluation on its three fixtures. New `RouteTimelineTest` cases pin the `Duration` readings on fractional and negative spans.
- S passes `ScoreRepresentationTest`. Its integer levels are exact and the variance is within half a unit. It ranks every pair of plans of one target the same way, and the cost ceiling boundary is the same level. Step-limited searches take the same moves in both phases, and the levels stay inside the long range at 50 technicians.
- Both pass the `FULL_ASSERT` assertion campaign, which covers every variant in four cohorts.

`TABU_KOPT_LONG_SCORE` is experiment-only. A solve on it returns a `DayPlan` rescored with the decimal levels, so acceptance and every reader still see the decimal score. Its code lives in commit `ec0efc4`, not on main: Timefold's Spring Boot starter refuses to start the scheduler with a second `@PlanningSolution` class on the classpath, and `PlanningSolutionScanTest` now holds that rule.

The run is `infra/run-representation-study.ps1`, which the owner starts on an idle workstation.
- Each commit is frozen into its own tree and built once.
- It uses the engine study's 24 cases: `TABU_KOPT`; 20 and 50 technicians; `CLUSTERED`, `DISPERSED`, `ABSENCE`, `TIGHT_WINDOW`; seeds 17, 23, 41; fixture seed 7001; 10 s.
- Every case is its own JVM per tree (`-Xmx1536m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC`). The tree order and the variant order alternate from case to case.
- There are two repetitions, followed by one solve-scoped JFR pass per tree.
- `infra/representation_study.py` pairs every treatment with the baseline of the same case and repetition. It attributes the JFR samples by stack: decimal work in the variance, in money, and in the score impacts; `java.time`; the score engine; the rest. It then applies the bars set before the run:
  - T is promoted if its median moves per second is at least the baseline's in both phases. It changes no semantics, so no promotion review applies.
  - S is promoted only at a median 1.25x on the fairness phase with no case dearer than the baseline beyond that case's own spread between repetitions. Otherwise BigDecimal is not the bottleneck and CR08 closes.

The run took place on 2026-10-20, with the containers stopped. The evidence is [solver-representation-2026-10-20.json](evidence/solver-representation-2026-10-20.json): 24 cases, two repetitions, zero violations in every arm. The same arm's moves per second agreed between repetitions within 3.1 percent (median) and 28.7 percent at the widest.

| Arm | Cost phase, moves per second against the baseline (median, 20 / 50 technicians) | Fairness phase (median, 20 / 50) | Final variance against the baseline | Final cost against the baseline |
| --- | --- | --- | --- | --- |
| T | 1.15x (1.13x / 1.16x) | 1.05x (1.05x / 1.05x) | median ratio 1.00 (8 fairer, 5 same, 11 less fair) | 16 cheaper, 5 ties, 3 dearer |
| S | 1.04x (1.04x / 1.05x) | 4.01x (3.01x / 5.62x) | median ratio 0.67 (19 fairer, 3 same, 2 less fair) | 11 cheaper, 4 ties, 9 dearer |
| T+S | 1.19x (1.16x / 1.24x) | 4.36x (3.31x / 6.59x) | median ratio 0.64 (18 fairer, 2 same, 4 less fair) | 17 cheaper, 2 ties, 5 dearer |

Where the time went in the baseline profile, by stack:

| Phase | `java.time` | BigDecimal in the variance | BigDecimal score impacts | BigDecimal money | Identity lookups (`MapN.probe`, leaf) |
| --- | ---: | ---: | ---: | ---: | ---: |
| cost (reference) | 18.9 percent | none | 6.2 percent | 4.0 percent | 20.8 percent |
| fairness | 5.6 percent | 54.5 percent (and 11.8 percent more in its own frames) | 2.6 percent | 1.6 percent | 6.6 percent |

This answers CR08's question. The decimal cost of the fairness phase is the variance formula (`SchedulingPolicy.fairness` dividing and rounding at DECIMAL128 for every technician on every move), not the score representation. The BigDecimal score impacts are 2.6 to 6.2 percent, and the long score's own gain in the cost phase, where no variance is computed, is 1.04x.

Decisions, held to the bars set before the run:
- **T is promoted.** It is not slower in either phase (1.15x and 1.05x), and `java.time` falls from 18.9 to 0.1 percent of cost-phase samples. It stays in this branch as the production timing path. Its one dearer case beyond noise, 50 `TIGHT_WINDOW` seed 23, is 175 cents dearer at the same variance and inside the ceiling. T's bar does not include cost, since exactness already shows it scores the same.
- **S is not promoted.** Its fairness phase clears 1.25x by a wide margin, but three cases are dearer than the baseline beyond its spread, so the bar as written is not met: 20 `DISPERSED` 23 (+1 cent, baseline spread 0), 50 `ABSENCE` 17 (+290 cents) and 50 `TIGHT_WINDOW` 23 (+253 cents). All three stay under the cost ceiling. The two large ones are fairer: variance falls from 0.0294 to 0.0132 and from 0.0016 to 0.0010. That is the fairness phase spending its allowance as the policy permits. The cost condition is the right test for a cost-phase treatment and a strict one for a fairness-phase treatment. Changing it after the run is for the owner to decide, not this write-up.
- **CR08 closes.** The score representation is not the bottleneck. The study script builds S from commit `ec0efc4`, so the comparison can be rerun.

Next candidates, from the profile after T:
- **The variance formula itself.** Computing it in binary64 or as exact integers, while keeping the decimal score, would keep most of S's fairness-phase gain without changing the score type. It changes which plan the fairness search finds (the numeric contract's tie tolerance), so it needs the promotion review that a policy change gets.
- **Identity lookups.** `MapN.probe` (one identity-to-number probe per visit per scored route) is the largest leaf after T, at 21.5 percent of cost-phase samples. Holding each entity's road number on the entity would remove it, with `ScoringExactnessTest` as the gate.
- **Not named by this profile.** The whole-route re-walk (CR11) and nearby selection did not show as leading costs, so neither is next.

## Fairness measures (PR 187)

The representation study traced the fairness phase's decimal cost to the variance formula, not to the score type. This study tests two cheaper ways to compute the production variance and four other fairness policies. Each one is a `DayConstraintProvider` that replaces only the fairness constraint (`FairnessMeasures`). The score stays `BendableBigDecimalScore`, and every other level is the production one. All are experiment-only variants of `TABU_KOPT` and are never a client choice.

Two formulas for the production variance, held to the bars below:
- **b** (`TABU_KOPT_BINARY_VARIANCE`): the variance over the whole fleet in binary64, carried as rounded units of 1e-9. That is the numeric contract's representation, so the tie tolerance is one unit.
- **c** (`TABU_KOPT_INCREMENTAL_VARIANCE`): the same variance from three running sums the solver keeps, updating only the routes a move changes. The sums are capacity, paid minutes, and paid minutes squared over capacity at a scale of 1e8, all exact longs. The variance is `(sum p^2/c)/C - (P/C)^2`, and only that last difference is binary64.

Four fairness policies, each searched and accepted on its own measure:
- **min-max** (`TABU_KOPT_MIN_MAX_UTILIZATION`): the highest technician utilization.
- **spread** (`TABU_KOPT_UTILIZATION_SPREAD`): the highest minus the lowest utilization.
- **abs-dev** (`TABU_KOPT_ABSOLUTE_DEVIATION`): the capacity-weighted mean absolute deviation of utilization.
- **load-balance** (`TABU_KOPT_LOAD_BALANCE`): Timefold's `loadBalance` collector over each technician's utilization in millionths. It is unweighted by capacity and is carried as the collector's own unfairness.

Every measure counts the technicians the production variance counts: those qualified for the day's demand, with regular capacity.

Equivalence, before any timing (`FairnessMeasureTest`, `SolverBenchmarkTest`):
- Every measure keeps every other score level equal to production's.
- b and c carry the decimal variance to within one unit, and rank every pair of plans as production does. Ties are allowed under two units.
- The policies carry their definitions to within two units, against an independent DECIMAL128 reading.
- A hand-checked two-technician fleet gives the exact unit readings.
- With step-limited searches, b and c take production's fairness-phase moves, and every measure takes production's cost-phase moves.
- `FULL_ASSERT` passes for all six, on every cohort of the assertion campaign.
- In the harness, a policy arm is accepted on its own measure (`acceptedOn` in the row), and every final plan records all five measures.

The run is `infra/run-variance-study.ps1`, which the owner starts. It freezes one tree from this branch, carrying the nanosecond timing of #186, and runs all seven arms in every JVM, their order rotating from case to case. It uses the representation study's 24 cases and two repetitions at two budgets:
- 15 s is the production default: 10 s of cost search and 5 s of fairness.
- 60 s (40 s and 20 s) shows whether a gain holds when the fairness search has room.

A JFR pass at 15 s follows, about 7 hours 30 minutes in all. `infra/variance_study.py` pairs and judges each budget.

The bars for b and c were set by the owner before the run (2026-10-20). Each is promoted only if every bar holds at both budgets:
- **Speed:** median fairness-phase moves per second at least 1.25x the baseline.
- **Fairness:** no case less fair than the baseline beyond that case's own spread between repetitions.
- **Cost:** the median per-case cost difference is not above zero. A case may be dearer if the median is not, because the fairness phase may spend its allowance on fairness.
- **Ceiling:** every accepted result inside its cost ceiling, with zero violations.

The policies change what fairness means, so they have no bars. The evidence compares every arm on all five measures, with three figures for each:
- the median over cases;
- the median ratio to the baseline;
- the count of cases lower and higher than the baseline.

It also records each arm's cost difference, fairness-phase speed and acceptance reasons. Changing the policy is the owner's decision, made on that table.

Options not tested, by owner decision: wider numeric types (the numeric contract already fits a long), and dropping the variance's final divide (a small gain).

### Results (2026-10-20)

Evidence: `docs/evidence/solver-variance-2026-10-20.json`. The run took 7 h 31 min on revision `773f9f6` and covered 672 solves with no violation and nothing outside a ceiling. The full report, with figures, is `docs/fairness-measures-2026-10-20/Fairness_Measures_2026-10-20_Report.docx`.

| Option, budget | Fairness-phase speed (min to max) | Cost-phase speed | Fairer / same / less fair / beyond noise | Median cost difference (cheaper / ties / dearer) | Passes |
| --- | --- | --- | --- | --- | --- |
| b, 15 s | 4.59x (2.83 to 8.01) | 0.998x | 23 / 1 / 0 / 0 | +0 cents (10 / 5 / 9) | Yes |
| c, 15 s | 4.38x (2.73 to 7.92) | 0.960x | 22 / 1 / 1 / 0 | +3 cents (6 / 5 / 13) | No: cost |
| b, 60 s | 4.16x (2.63 to 6.71) | 0.994x | 21 / 3 / 0 / 0 | -18.5 cents (16 / 3 / 5) | Yes |
| c, 60 s | 4.10x (2.59 to 6.66) | 0.957x | 20 / 3 / 1 / 1 | -4 cents (13 / 3 / 8) | No: fairness |

- **b is promoted.** It passes every bar at both budgets and was never less fair than the baseline. Its median final variance is 0.68 of the baseline's at 15 s and 0.62 at 60 s.
- **The decimal variance was the whole difference between the phases.** Production's fairness-phase profile was 56 percent decimal variance and 12 percent the surrounding collection. With b, the fairness phase evaluates a median 251k moves per second, against 54k for production and 240k for production's cost phase.
- **The gain grows with the fleet:** 3.17x at 20 technicians and 6.31x at 50 (15 s). Production recomputes the whole fleet on every move.
- **b at 15 s is fairer than the baseline at 60 s** in 16 of 24 cases.
- **c is not promoted.** It is no faster than b in the fairness phase. Its running sums are kept on every cost-phase move, so its cost phase runs at 0.96x. At 15 s it misses the cost bar by 3 cents. At 60 s it misses the fairness bar on one case: 20 `DISPERSED` 41, where the baseline's spread is zero and c's second repetition ended at 33.2 thousandths against 24.3.

The policies, as the median ratio to the baseline on each measure at 60 s (15 s in the report). Lower is fairer:

| Arm | Variance | Highest utilization | Spread | Absolute deviation | Load balance | Median cost difference |
| --- | --- | --- | --- | --- | --- | --- |
| b | 0.62 | 0.91 | 0.90 | 0.68 | 0.79 | -18.5 cents |
| min-max | 3.05 | 1.20 | 1.29 | 2.16 | 1.73 | -5,181.5 cents |
| spread | 3.19 | 1.20 | 1.29 | 2.18 | 1.77 | -4,445 cents |
| abs-dev | 0.69 | 1.00 | 1.00 | 0.67 | 0.85 | -18.5 cents |
| load-balance | 0.63 | 1.00 | 0.98 | 0.72 | 0.79 | +0 cents |

- **Min-max and spread are less fair on every measure, their own included.** A maximum is flat for almost every move, so the search falls through to cost. These two arms spend about none of the 2 percent allowance (median 0 to 3 percent, against 99 to 100 percent for the others). Spread is accepted as a cost reduction in all 48 runs. Their savings are the allowance not spent, not a better plan.
- **Abs-dev** wins only on its own measure. At 15 s it leaves the busiest technician busier than the baseline in 16 cases.
- **Load-balance** is within 0.01 of b or behind it on every measure, at both budgets.
- **The variance stays the policy.**

Next, in order:
1. Ship b's formula in `DayConstraintProvider.fairness`, keeping `SchedulingPolicy.measure` (DECIMAL128) for acceptance and reporting.
2. Remove c and the four policy providers from main, keeping them on this branch.
3. Re-measure the 10 s and 5 s phase split, since b's fairness phase now needs less time.
4. Carry each visit's `RoadMatrix` number on the visit. `MapN.probe`, the identity-to-number lookup, is now 17.5 percent of the fairness phase's leaf frames. This is under way; see "Road matrix numbering" below.
5. Repeat the VM daily load with b.

## Road matrix numbering (PR 187)

The fairness measure study's profiles kept only leaf frames, so naming the `MapN.probe` caller rested on the CR07 write-up. The study's recordings, read again with full stacks (seed 17, 15 s, eight recordings per arm and phase), confirm it and name the rest of the collection work:

| Leaf frames | b, fairness phase | Production, cost phase | Caller |
| --- | ---: | ---: | --- |
| `ImmutableCollections$MapN.probe` | 17.5 percent | 20.4 percent | `RoadMatrix.number`, all of it: from `RouteTimingSearch.segments` (11.8 and 12.7 percent) and `RouteTimeline.evaluate` (5.7 and 7.6 percent) |
| `HashMap.putVal`, `LinkedHashMap.afterNodeInsertion`, `HashMap.resize` under `RouteTimingSearch.segments` | 10.0 percent | 11.5 percent | `segments`, building its options map on every call |
| `HashMap.getNode`, `IdentityHashMap.get`, `HashMap$TreeNode.getTreeNode` | 5.1 percent | 4.9 percent | Timefold's own frames, not the scheduler's |
| `Set12.contains`, `Collections.disjoint`, `Arrays.copyOf` | 2.3 percent | 2.8 percent | the route's qualifications, eligibility and capacity, the same for every move |

Every leg evaluated looked its visit up by identity. The map's keys were cut from the `from>to` road keys, so they are never the visit's own string, and each lookup compared the whole identity.

The fix, commit `ed97d8b`, changes no rule:
- **Numbers kept on the entities.** Each `PlanVisit` and `TechRoute` keeps its matrix number together with the fact revision that gave it. A lookup is one reference comparison. A revision that reuses the entities (`PlanCopies.withAbsence`, booking's per-route plans) resolves them again. `RouteTimelineTest.roadNumbersFollowTheRevisionTheyAreScoredAgainst` covers this, and it fails when the revision check is removed.
- **Arrays for the timing search.** The segment options and the interval frontier are arrays indexed by the number of visits covered, walked in the ascending order the maps iterated in.
- **Route constants kept.** A frozen route keeps its regular capacity and eligibility per revision.
- **Gate:** `ScoringExactnessTest` passes unchanged: the same score, move count, arrangement and independent evaluation on its three fixtures, the absence fixture among them.

What it reaches: the daily proposal solve in both phases, embedded or in the solver pool, and the final validation through `DayScoreCalculator`. Booking searches rank candidates with `RouteEvaluator`, which reads legs by their `from>to` keys and stays independent of the scoring path, so booking does not change.

The study, `infra/run-matrix-study.ps1`, which the owner starts. It runs two frozen trees, `o` (`d571d83`, before the fix) and `n` (`ed97d8b`). The tree order alternates and the variant order rotates from case to case:

| Arm | Tree, variant | Judged by |
| --- | --- | --- |
| old | o, `TABU_KOPT` | the reference for the fix |
| old-b | o, `TABU_KOPT_BINARY_VARIANCE` | the reference for b's own gain from the fix |
| baseline | n, `TABU_KOPT` | the fix: at least as fast as old in both phases, as T was |
| b, c | n, the two variance options | the fairness measure study's four bars, against this baseline |
| load-balance | n, `TABU_KOPT_LOAD_BALANCE` | every measure against this baseline, no bars |

It uses fleets of 10, 30 and 50 technicians, the four workloads, seeds 17, 23 and 41, fixture seed 7001, two repetitions and the 15 s production budget. Every speed is also reported by fleet. A JFR pass per tree follows, about 2 hours 30 minutes in all. `infra/matrix_study.py` pairs and judges the run. The evidence will be `docs/evidence/solver-matrix-<date>.json`.

### Results

Evidence: `docs/evidence/solver-matrix-2026-10-20.json`. The run started at 15:55 UTC on 2026-10-20 and covered 432 solves with no violation and nothing outside a ceiling. The full report, with figures, is `docs/road-matrix-numbering-2026-10-20/Road_Matrix_Numbering_2026-10-20_Report.docx`; `stacks.json` there attributes the run's recordings to their callers.

The fix, each variant on tree n against the same variant on tree o, median of 72 paired solves:

| Comparison, phase | All fleets (min to max) | 10 technicians | 30 technicians | 50 technicians |
| --- | --- | --- | --- | --- |
| Production, cost phase | 1.38x (1.12 to 2.23) | 1.61x | 1.37x | 1.31x |
| Production, fairness phase | 1.10x (0.85 to 1.47) | 1.17x | 1.06x | 1.05x |
| b, cost phase | 1.41x (1.03 to 2.28) | 1.58x | 1.34x | 1.32x |
| b, fairness phase | 1.30x (1.05 to 1.61) | 1.45x | 1.27x | 1.23x |

- **The fix is promoted.** It is not slower in either phase. Both former sites are gone from tree n's recordings: the identity probe (20.1 percent of production's cost phase before) and the options map (12.3 percent).
- **The gain is largest on small fleets.** The work removed is per leg, while the fleet-wide part of a move grows with the fleet. That reading comes from the profile; it was not measured separately.
- **Production's fairness phase gains only 1.10x,** because 57 percent of its samples are still the DECIMAL128 variance.
- **The extra moves made days cheaper.** Production's median final cost fell 136.25 cents a day (reference plans 63.25 cents), cheaper beyond the old arm's spread in 17 of 36 cases and dearer in none. b's fell 238 cents (19 cheaper, 2 dearer beyond noise).
- **Final fairness moved a little the other way:** a median variance ratio of 1.028 for production (16 less fair and 4 fairer beyond noise) and 1.011 for b (12 and 6). The reference plans were as fair as before (1.004), so this is the fairness phase working under a ceiling measured from a cheaper plan. Whether to spend some of the saving on fairness is the allowance's size, a client setting.

b and c on the faster engine, against the fixed production:

| Option | Fairness-phase speed (min to max) | Cost-phase speed | Fairer / same / less fair / beyond noise | Median cost difference (cheaper / ties / dearer) | Passes |
| --- | --- | --- | --- | --- | --- |
| b | 5.20x (2.07 to 9.24) | 0.988x | 29 / 7 / 0 / 0 | +0 cents (13 / 10 / 13) | Yes |
| c | 5.23x (1.92 to 9.70) | 0.944x | 28 / 7 / 1 / 1 | +0 cents (11 / 10 / 15) | No: fairness |

- **b is promoted again.** Its fairness-phase gain grows with the fleet: 2.79x at 10 technicians, 5.20x at 30, 7.10x at 50. Its median variance is 0.74 of production's (0.99, 0.73 and 0.59 by fleet). At 10 technicians it mostly ties production.
- **c is not promoted.** It fails the fairness bar on 30 `DISPERSED` 17: production's repetitions ended at 15.10 and 14.39 thousandths, c's second at 20.35.

Load balance, as the median ratio to the fixed production on each measure (lower is fairer):

| Arm | Variance | Highest utilization | Spread | Absolute deviation | Load balance | Median cost difference | Fairness-phase speed | Cost-phase speed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| b | 0.74 | 0.94 | 0.93 | 0.78 | 0.86 | +0 cents | 5.20x | 0.99x |
| load-balance | 0.58 | 0.93 | 0.90 | 0.68 | 0.76 | +0 cents | 4.72x | 0.90x |

- **On these fleets load balance is fairer than b on all five measures.** Its variance was lower than b's in 19 cases and higher in 6 (13 lower and 3 higher beyond b's spread). By fleet its variance is 0.87, 0.59 and 0.57 of production's, against b's 0.99, 0.73 and 0.59.
- **The lead is on 10 and 30 technicians,** sizes the fairness measure study did not run. At 50, the size both studies share, the two are close, as they were then.
- **It costs a little more.** Its median cost is 10 cents above b's (10 dearer and 4 cheaper beyond b's spread), and its cost phase runs at 0.90x because Timefold keeps its collector on every move.
- **No policy change on this evidence.** A head-to-head of load balance against b on its own fleets and budgets comes first.

After the fix, b's fairness phase is scheduler code 30 percent (the timing search's own loop 14.6), Timefold's engine 28, BigDecimal score impacts 16, collections 16 and BigDecimal money 6.

Next, in order:
1. Ship b's formula in `DayConstraintProvider.fairness`, keeping `SchedulingPolicy.measure` (DECIMAL128) for acceptance and reporting, and remove c and the three rejected policies. Verify on the VM rehearsal stack.
2. The scoring path without BigDecimal (a long score, long money, fleet sums without `toList()`) and the timing search, measured as treatments at a 1.05x gate, then verified on the stack.
3. Load balance against b, head to head, at 5, 10, 20 and 50 technicians and 15 s and 60 s.

**Shipped (PR H, score model `bendable-decimal-repair-v3`).** `DayConstraintProvider.fairness` now penalizes b's binary64 variance in units of 1e-9 (`RouteScoringFacts.fairnessUnits`), and acceptance still reads the DECIMAL128 variance (`docs/scheduler-policy.md`, Numeric contract). The study variants `TABU_KOPT_BINARY_VARIANCE` (now production), `TABU_KOPT_INCREMENTAL_VARIANCE`, `TABU_KOPT_MIN_MAX_UTILIZATION`, `TABU_KOPT_UTILIZATION_SPREAD` and `TABU_KOPT_ABSOLUTE_DEVIATION` were removed; their code stays on `bench/variance-formula`, and the pinned-commit scripts (`run-variance-study.ps1`, `run-matrix-study.ps1`) still build them from their commits. `TABU_KOPT_LOAD_BALANCE` stays for the head-to-head. The study's readers keep every measure (`FairnessReadings`), so later evidence reads all five.

## Measured configuration choice

[Archived comparison](evidence/solver-comparison-2026-09-24.json) records 294 accepted case results: eight variants across 21 datasets at seed 17, followed by the retained capped, uncapped and Tabu configurations at seeds 23 and 41. The five compressed source files retain excluded rows and provenance; the analyzer explicitly excludes the initial mislabeled capped rows and underloaded near-capacity rows. All accepted candidates passed independent validation with zero reported hard-constraint violations. Hardware and concurrent local activity limit timing comparisons; this is fixture quality evidence, not booking latency evidence.

| Seed | Capped reference cost, cents | Uncapped reference cost, cents | Tabu reference cost, cents | Capped mean variance | Uncapped mean variance | Tabu mean variance |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 17 | 7,505,924 | 7,468,333 | 7,442,256 | 0.010111 | 0.013918 | 0.007772 |
| 23 | 7,502,748 | 7,468,861 | 7,448,680 | 0.010018 | 0.015485 | 0.009190 |
| 41 | 7,501,289 | 7,470,769 | 7,452,334 | 0.011711 | 0.014288 | 0.008942 |

These are aggregate modeled reference costs and unweighted means of per-dataset capacity-weighted utilization variances after acceptance. Each individual case still uses the required overtime target and its own 2 percent ceiling. All three retained variants ended with zero overtime in these cases, so this matrix cannot establish a comparative overtime advantage. Tabu also had lower aggregate accepted cost than both alternatives in every seed. Advanced moves did not demonstrate a reason to adopt their added complexity in the initial comparison.

The daily default is now `TABU`, seed 17: single-visit relocation and swaps, entity tabu size 7, accepted-count 1000, selected-count 10000, and the existing combined 15-second budget. It has no production step cap. Every capped comparison stopped both phases at 1000 steps; aggregate elapsed time across 21 cases was 62.7, 78.3 and 80.9 seconds, versus about 315 seconds for the uncapped alternatives. Retain `CURRENT_CAPPED` as an explicit reproducible diagnostic configuration and `CURRENT_UNCAPPED` as a rollback comparison. Set `scheduler.optimizer.seed` deliberately; every preview persists the actual XML, fingerprint and phase termination statistics. Since the Tabu variant study (docs/daily-tabu-variants-5d15129b) the daily method is no longer a service setting: each client chooses `TABU_KOPT` or `TABU`, with its own cost and fairness search seconds, in `Snapshot.policy` (see docs/stateless-api-design.md). Ordinary dispatcher preview/manual apply remains unchanged.

## Shipped representation: L, M, W and A (PR #191, 2026-10-22)

PR #191 makes four representation changes the default. Each scores every plan exactly as before: the five pinned solves of `ScoringExactnessTest` are unchanged.

| Change | What it does | Timefold docs |
| --- | --- | --- |
| L | the long `BendableScore`, read as the decimal levels it stands for | 12.2.4, 12.1.10, 15.2.1 |
| M | long money, with the narrowed rate contract (spec 1.7.0) | 12.1.10, 16.13 |
| W | each visit keeps its time window in epoch nanoseconds and its duration in nanoseconds, made by its constructor; a route builds its clock and working intervals once, when frozen; a single-interval route builds no array of return points | 16.9, 10.9.6 |
| A | each visit keeps its road matrix number in one field, with no hop through a separate record | 16.9 |

**Measured in study 3** (2026-10-21; 13 frozen trees, 60 cases, 3 repetitions, 2,340 solves with no violation; the evidence and report are on the local branch `bench/study-3`). Each value is the median of 180 paired moves-per-second ratios, cost phase / fairness phase:

| Row | Median | Status |
| --- | --- | --- |
| L+M over main | 1.288x / 1.264x | gated at 1.05x, passes |
| L+M+W over L+M | 1.047x / 1.040x | read only, under 1.05x |
| W+A over L+M+W | 1.005x / 1.001x | read only, no measurable change |
| W+A over L+M | 1.051x / 1.043x | derived after the run |
| W+A over main | 1.342x / 1.312x | derived after the run |

In cost, L+M+W ended cheaper than L+M in 10 of 60 cases and dearer in none. W's cost-phase gain is largest at 30 and 50 technicians (1.051x and 1.061x). The flight recordings place W's saving in the timing search's arithmetic: route evaluation falls from 68.2 to 57.0 samples per million moves, with no other kind of work changing by more than a few samples. CR11's cascade arms (C and AC) were about 0.63x over L+M and are not shipped.

**Owner decision, 2026-10-22.** W and A ship with L and M as the default. This overrides the bar written before the run: W measured under 1.05x, and A measured no change on either base. W is taken for its consistent gain, larger on bigger fleets; A is taken as neutral. The paired ratios are the harness's; the change is verified on the VM rehearsal stack through the public API before it is called shipped (docs/api-load.md).

## Load balance head to head (PR J, planned 2026-10-23)

Should the daily fairness policy move from b, the capacity-weighted variance that ships, to Timefold's load balance (`ConstraintCollectors.loadBalance`, docs 13.4.5.9 and chapter 15)? The road matrix study found load balance fairer on all five measures at 10 to 50 technicians and 15 s. This run covers the fleets and the longer budget that study did not.

**Design.**
- One tree: main `52339c8` (L, M, W and A).
- Two arms:
  - b: `TABU_KOPT`, accepted on the variance;
  - load-balance: `TABU_KOPT_LOAD_BALANCE`, accepted on its own measure.
- Fleets 5, 10, 20 and 50.
- Workloads Clustered, Dispersed, Absence, Tight Window and Near Capacity.
- Seeds 17, 23 and 41 on fixture seed 7001.
- Budgets 15 s (10 s cost, 5 s fairness) and 30 s (20 s and 10 s).
- 3 repetitions, which comes to 720 measured solves.
- How it runs:
  - every case is its own JVM, with the arm order alternating;
  - both budgets run in every repetition;
  - a JFR pass at 15 s on seed 17 follows.
- Every plan is read on all five measures.
- About 5 hours 10 minutes, on the workstation harness.
- Runner and summarizer: `infra/run-load-balance-study.ps1` (the owner starts it) and `infra/load_balance_study.py`.

**The rule for switching, written before the run.** Load balance replaces b only if all of these hold at both budgets:
- **Variance:** its median per-case variance ratio to b is below 1. It is less fair than b beyond b's own spread between repetitions in at most 10 percent of cases.
- **Highest utilization:** its median per-case ratio to b is at most 1.
- **Cost:** its median per-case cost difference is at most 0.1 percent of b's cost.
- **Ceiling:** every accepted result of both arms is inside its cost ceiling, with zero violations.

The speed of both phases is reported, not gated, because the collector runs on every move.

The rule informs the owner's decision; the policy change itself is the owner's call. A switch would also be verified on the VM rehearsal stack. Load balance here is unweighted by capacity, unlike the variance. A capacity-weighted form is not part of this run.
