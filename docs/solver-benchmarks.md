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

## Measured configuration choice

[Archived comparison](evidence/solver-comparison-2026-09-24.json) records 294 accepted case results: eight variants across 21 datasets at seed 17, followed by the retained capped, uncapped and Tabu configurations at seeds 23 and 41. The five compressed source files retain excluded rows and provenance; the analyzer explicitly excludes the initial mislabeled capped rows and underloaded near-capacity rows. All accepted candidates passed independent validation with zero reported hard-constraint violations. Hardware and concurrent local activity limit timing comparisons; this is fixture quality evidence, not booking latency evidence.

| Seed | Capped reference cost, cents | Uncapped reference cost, cents | Tabu reference cost, cents | Capped mean variance | Uncapped mean variance | Tabu mean variance |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 17 | 7,505,924 | 7,468,333 | 7,442,256 | 0.010111 | 0.013918 | 0.007772 |
| 23 | 7,502,748 | 7,468,861 | 7,448,680 | 0.010018 | 0.015485 | 0.009190 |
| 41 | 7,501,289 | 7,470,769 | 7,452,334 | 0.011711 | 0.014288 | 0.008942 |

These are aggregate modeled reference costs and unweighted means of per-dataset capacity-weighted utilization variances after acceptance. Each individual case still uses the required overtime target and its own 2 percent ceiling. All three retained variants ended with zero overtime in these cases, so this matrix cannot establish a comparative overtime advantage. Tabu also had lower aggregate accepted cost than both alternatives in every seed. Advanced moves did not demonstrate a reason to adopt their added complexity in the initial comparison.

The daily default is now `TABU`, seed 17: single-visit relocation and swaps, entity tabu size 7, accepted-count 1000, selected-count 10000, and the existing combined 15-second budget. It has no production step cap. Every capped comparison stopped both phases at 1000 steps; aggregate elapsed time across 21 cases was 62.7, 78.3 and 80.9 seconds, versus about 315 seconds for the uncapped alternatives. Retain `CURRENT_CAPPED` as an explicit reproducible diagnostic configuration and `CURRENT_UNCAPPED` as a rollback comparison. Set `scheduler.optimizer.variant` and `scheduler.optimizer.seed` deliberately; every preview persists the actual XML, fingerprint and phase termination statistics. Ordinary dispatcher preview/manual apply remains unchanged.
