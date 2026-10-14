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

## Measured configuration choice

[Archived comparison](evidence/solver-comparison-2026-09-24.json) records 294 accepted case results: eight variants across 21 datasets at seed 17, followed by the retained capped, uncapped and Tabu configurations at seeds 23 and 41. The five compressed source files retain excluded rows and provenance; the analyzer explicitly excludes the initial mislabeled capped rows and underloaded near-capacity rows. All accepted candidates passed independent validation with zero reported hard-constraint violations. Hardware and concurrent local activity limit timing comparisons; this is fixture quality evidence, not booking latency evidence.

| Seed | Capped reference cost, cents | Uncapped reference cost, cents | Tabu reference cost, cents | Capped mean variance | Uncapped mean variance | Tabu mean variance |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 17 | 7,505,924 | 7,468,333 | 7,442,256 | 0.010111 | 0.013918 | 0.007772 |
| 23 | 7,502,748 | 7,468,861 | 7,448,680 | 0.010018 | 0.015485 | 0.009190 |
| 41 | 7,501,289 | 7,470,769 | 7,452,334 | 0.011711 | 0.014288 | 0.008942 |

These are aggregate modeled reference costs and unweighted means of per-dataset capacity-weighted utilization variances after acceptance. Each individual case still uses the required overtime target and its own 2 percent ceiling. All three retained variants ended with zero overtime in these cases, so this matrix cannot establish a comparative overtime advantage. Tabu also had lower aggregate accepted cost than both alternatives in every seed. Advanced moves did not demonstrate a reason to adopt their added complexity in the initial comparison.

The daily default is now `TABU`, seed 17: single-visit relocation and swaps, entity tabu size 7, accepted-count 1000, selected-count 10000, and the existing combined 15-second budget. It has no production step cap. Every capped comparison stopped both phases at 1000 steps; aggregate elapsed time across 21 cases was 62.7, 78.3 and 80.9 seconds, versus about 315 seconds for the uncapped alternatives. Retain `CURRENT_CAPPED` as an explicit reproducible diagnostic configuration and `CURRENT_UNCAPPED` as a rollback comparison. Set `scheduler.optimizer.seed` deliberately; every preview persists the actual XML, fingerprint and phase termination statistics. Since the Tabu variant study (docs/daily-tabu-variants-5d15129b) the daily method is no longer a service setting: each client chooses `TABU_KOPT` or `TABU`, with its own cost and fairness search seconds, in `Snapshot.policy` (see docs/stateless-api-design.md). Ordinary dispatcher preview/manual apply remains unchanged.
