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

`TABU_KOPT_LONG_SCORE` is experiment-only. A solve on it returns a `DayPlan` rescored with the decimal levels, so acceptance and every reader still see the decimal score.

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
- **CR08 closes.** The score representation is not the bottleneck. `TABU_KOPT_LONG_SCORE` stays experiment-only, so the comparison can be rerun.

Next candidates, from the profile after T:
- **The variance formula itself.** Computing it in binary64 or as exact integers, while keeping the decimal score, would keep most of S's fairness-phase gain without changing the score type. It changes which plan the fairness search finds (the numeric contract's tie tolerance), so it needs the promotion review that a policy change gets.
- **Identity lookups.** `MapN.probe` (one identity-to-number probe per visit per scored route) is the largest leaf after T, at 21.5 percent of cost-phase samples. Holding each entity's road number on the entity would remove it, with `ScoringExactnessTest` as the gate.
- **Not named by this profile.** The whole-route re-walk (CR11) and nearby selection did not show as leading costs, so neither is next.

## Measured configuration choice

[Archived comparison](evidence/solver-comparison-2026-09-24.json) records 294 accepted case results: eight variants across 21 datasets at seed 17, followed by the retained capped, uncapped and Tabu configurations at seeds 23 and 41. The five compressed source files retain excluded rows and provenance; the analyzer explicitly excludes the initial mislabeled capped rows and underloaded near-capacity rows. All accepted candidates passed independent validation with zero reported hard-constraint violations. Hardware and concurrent local activity limit timing comparisons; this is fixture quality evidence, not booking latency evidence.

| Seed | Capped reference cost, cents | Uncapped reference cost, cents | Tabu reference cost, cents | Capped mean variance | Uncapped mean variance | Tabu mean variance |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 17 | 7,505,924 | 7,468,333 | 7,442,256 | 0.010111 | 0.013918 | 0.007772 |
| 23 | 7,502,748 | 7,468,861 | 7,448,680 | 0.010018 | 0.015485 | 0.009190 |
| 41 | 7,501,289 | 7,470,769 | 7,452,334 | 0.011711 | 0.014288 | 0.008942 |

These are aggregate modeled reference costs and unweighted means of per-dataset capacity-weighted utilization variances after acceptance. Each individual case still uses the required overtime target and its own 2 percent ceiling. All three retained variants ended with zero overtime in these cases, so this matrix cannot establish a comparative overtime advantage. Tabu also had lower aggregate accepted cost than both alternatives in every seed. Advanced moves did not demonstrate a reason to adopt their added complexity in the initial comparison.

The daily default is now `TABU`, seed 17: single-visit relocation and swaps, entity tabu size 7, accepted-count 1000, selected-count 10000, and the existing combined 15-second budget. It has no production step cap. Every capped comparison stopped both phases at 1000 steps; aggregate elapsed time across 21 cases was 62.7, 78.3 and 80.9 seconds, versus about 315 seconds for the uncapped alternatives. Retain `CURRENT_CAPPED` as an explicit reproducible diagnostic configuration and `CURRENT_UNCAPPED` as a rollback comparison. Set `scheduler.optimizer.seed` deliberately; every preview persists the actual XML, fingerprint and phase termination statistics. Since the Tabu variant study (docs/daily-tabu-variants-5d15129b) the daily method is no longer a service setting: each client chooses `TABU_KOPT` or `TABU`, with its own cost and fairness search seconds, in `Snapshot.policy` (see docs/stateless-api-design.md). Ordinary dispatcher preview/manual apply remains unchanged.
