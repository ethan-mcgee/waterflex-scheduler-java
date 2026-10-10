# Configurable scheduler experiments

The Python toolkit runs manual local comparisons of the daily solvers, preserves raw evidence, and generates PNG/SVG figures plus a local HTML index. Production solver defaults and the existing reports remain unchanged. The full studies are not unit tests and are never launched by CI.

Booking experiments are retired (2026-10-09): they drove the scheduler's internal booking endpoints, which the portal no longer uses and which are being deleted. A configuration with a `booking` section is rejected. Analysis still reads archived booking evidence, including the September 29 history import. A future booking benchmark would measure the public `/api/v1` booking flow.

## Setup and commands

Use Python 3.10 or later, Java 25, the checked-in Maven wrapper, and enough disk space for a separate frozen source/build/dependency snapshot per run. Run from the repository root in PowerShell. The launcher has a terminal menu when run without arguments:

```powershell
py -3 run_experiment.py
```

Or choose the experiment and mode directly:

```powershell
py -3 run_experiment.py daily smoke
py -3 run_experiment.py daily dry-run
py -3 run_experiment.py daily full
py -3 run_experiment.py daily full --config experiments/configs/my-daily-study.json
```

`smoke` selects the small corresponding configuration; `dry-run` expands the full configuration without installing packages or measuring; `full` selects the supplied full configuration. `--config` replaces the selected configuration in any mode. Copy a supplied JSON file, give it a new lowercase `name`, edit its lists, and try `dry-run --config <path>` before measuring. Each measured invocation creates a new archive.

The launcher checks Java 25, detects the JDK home when Java is on `PATH`, and installs the Python graph requirements if missing. It then invokes the existing experiment engine. During preparation and measurement, the terminal shows a progress bar, completed/total cases, total elapsed time, current case elapsed time, and case details. Counts advance only after a valid completion receipt is written. If output is redirected, the same status is printed as lines at transitions.

The underlying command remains available for advanced use:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
py -3 -m pip install -r infra/requirements-experiments.txt

py -3 infra/experiments.py run experiments/configs/daily-budget.json --dry-run
py -3 infra/experiments.py run experiments/configs/daily-smoke.json
py -3 infra/experiments.py run experiments/configs/daily-budget.json
```

Commit harness inputs before measurements. Documentation and `AGENTS.md` edits do not prevent runs. The toolkit copies committed source bytes from the clean working tree, builds that frozen copy with strict Java nullability, and stores hashes of source and executable artifacts. It never builds a long run from changing working-tree classes.

Resume, analyze, or import the archived September 29 evidence:

```powershell
py -3 infra/experiments.py resume experiments/runs/<run-id>
py -3 infra/experiments.py analyze experiments/runs/<run-id>
py -3 infra/experiments.py import-history docs/evidence/scheduler-field-2026-09-29
```

`run` prints its unique directory before building. A successful measurement automatically invokes analysis. If interrupted or failed, invoke `analyze` explicitly for a completeness report, then `resume` to retry unfinished cases. A preparation failure before `manifest.json` requires a new run; its build log is retained. A dry run prints every expanded case, count, prerequisites, and total daily search allowance without building or measuring.

## Configuration contract

JSON has `version: 1`, a lowercase slug `name`, and a `daily` object, which requires all its fields; unknown fields, duplicate JSON keys, duplicate values, nulls, empty selections and invalid values are rejected before execution. Counts derive exclusively from the lists.

| Field | Daily |
| --- | --- |
| `solvers` | Any of CURRENT_CAPPED, CURRENT_UNCAPPED, LATE_ACCEPTANCE_CHANGE, LATE_ACCEPTANCE, TABU, SUBLIST, KOPT, RUIN_RECREATE, TABU_SIZE_3, TABU_SIZE_15, TABU_KOPT |
| `control` | One selected solver |
| `seeds` | Distinct integers 0..2147483647 |
| `fleets` | Any of 5, 10, 20, 30, 50 |
| `workloads` | SPARSE, CLUSTERED, DISPERSED, MIXED_SKILL, TIGHT_WINDOW, ABSENCE, NEAR_CAPACITY |
| `budgets_seconds` | 0.1..240, whole millisecond resolution |
| `parallel_cases` | Required integer, at least 1. Booleans, decimals, strings and null are rejected. The usable core count is checked at run time |

`parallel_cases` is the number of independent daily cases, each a fresh solver JVM, that run at the same time. Each running case is confined to one dedicated physical core (both SMT threads), so the setting also decides how many cores the study occupies: `parallel_cases` cores, plus core 0, which stays free for Windows and the orchestrator. There is no cores-per-case setting because the solver search is single threaded. `1` runs one case at a time. The value is part of the saved configuration, so the config and case hashes bind it to the run and a resume cannot change it. Running more than one case at a time needs Windows, and a value above the usable physical cores (11 on the 12-core workstation) fails before any measurement.

The supplied daily study selects LATE_ACCEPTANCE, TABU, SUBLIST and KOPT; TABU is the control. Ten seeds (17, 23, 41, 59, 83, 101, 127, 149, 173, 199), four fleets (5, 10, 20, 50), CLUSTERED and DISPERSED fixtures, and four budgets (60, 90, 120, 240 seconds) produce **1,280 cases** and **163,200 seconds (45 hours 20 minutes)** of search allowance. With `parallel_cases` 10, the dry run estimates about **4 hours 38 minutes** of wall time, including about 2.5 seconds of per-case overhead. Run sequentially the same study takes more than 45 hours 20 minutes. The estimate is a greedy slot simulation in saved dispatch order. Startup, warmup, validation, copying, building and plotting add overhead. Capped/early terminating variants can use less than their allowance. `daily-smoke.json` uses `parallel_cases` 1.

### TABU variant study

`daily-tabu-variants.json` tests TABU tuning against the unchanged TABU control. The three experiment-only variants are TABU_SIZE_3 and TABU_SIZE_15 (entity tabu size 3 or 15 instead of 7, everything else identical) and TABU_KOPT (TABU's acceptor and forager with KOPT's move set: change, swap, sublist and 2-3-opt). A test pins the TABU configuration byte for byte, so the control and the production comparison stay unchanged. The study runs TABU, KOPT, LATE_ACCEPTANCE, SUBLIST and the three variants over the ten seeds, fleets 5, 10, 20 and 50, CLUSTERED, DISPERSED, MIXED_SKILL and TIGHT_WINDOW, and budgets of 60, 90, 120 and 240 seconds: **4,480 cases** and **571,200 seconds** of search allowance, about 16 hours at `parallel_cases` 10. `daily-tabu-smoke.json` runs every solver of the study once at 0.5 seconds as a harness check. `daily-contention-10.json` is the ten-case contention check the next section requires before trusting 10 parallel cases.

The frozen build includes `calculation-engine` (where the solver lives) and every other reactor module, and builds with `-am`, so a run measures the solver from its own frozen source rather than a jar left in the local Maven repository.

## Measurement protocol

Daily cases start fresh JVMs and unchanged fixtures. Each selected solver receives the same 200 ms warmup on the 20-technician SPARSE fixture. The combined budget assigns two-thirds to reference cost search and the remaining actual time to fairness. Raw JSONL contains configured budget, actual per-phase statistics, termination outcomes, independent validation, reference and accepted policy metrics. Earlier budget results never seed later cases. Seeds change stochastic search on fixed geography, not the number of geographic datasets.

Solver order and budget order rotate independently across matched fleet/workload/seed blocks. Solvers are interleaved within budgets. The exact expanded order is saved and reused on resume, and dispatch follows it, so the counterbalancing is kept. Up to `parallel_cases` daily cases run at the same time, each on its own dedicated physical core. Slots alternate between the two L3 cache groups (CCDs) and exclude core 0. Every daily JVM, including with `parallel_cases` 1, runs with `-XX:ActiveProcessorCount=2 -XX:+UseSerialGC` so the runtime sees the same two logical processors whatever the setting. Each attempt keeps its own directory, logs and receipts. `started.json` records the slot and mask, and `completed.json` records the fewest other cases that ran beside it (`in_flight_min`).

After the first failure no new case starts. Cases already running finish and keep their own receipts, then the first error is raised. On interruption the in-flight children and their process trees are stopped, `interrupted.json` is written for each, and a resume retries only unfinished cases.

Concurrent cases share memory bandwidth, L3 cache and boost clocks even though they do not share cores. The manifest records this under `limitations` and records `parallel_cases`, the slot masks, the L3 groups and the JVM flags under `parallelism`. The core topology is part of the runtime comparison, so a resume on changed hardware is rejected. Results from before these JVM flags are a separate cohort and must not be pooled with new ones.

## Evidence and resumption

```text
experiments/runs/<UTC timestamp>-<name>-<unique id>/
  original-config.json    # exact input bytes
  config.json             # validated resolved configuration
  cases.json              # authoritative expanded execution order
  manifest.json           # source/artifact hashes, runtime, hardware
  build.log
  frozen/                 # copied source, classes, dependency jars, server jar
  attempts/<case-id>/<timestamp>-<attempt-id>/
    started.json          # case, slot, core mask and neighbors at start
    raw.jsonl
    completed.json        # raw hash and matching case, only after successful validation
    failed.json or interrupted.json
    solver.log + gc.log
  analysis/<timestamp>-<unique id>/
    summary.json, summary.csv, cases.csv, analysis-provenance.json, index.html
    figure-*.png, figure-*.svg
```

Everything under `experiments/runs` is gitignored. Environment credentials are not copied into configurations or runtime manifests. Do not place secrets in tracked sources or provider URLs. Diagnostic logs remain local evidence and may contain fixture/database diagnostics.

Completion receipts bind raw hashes to exact expected cases. Resume verifies saved configuration, matrix, executable files, toolkit implementation, runtime and hardware identity. It skips completed cases, preserves prior failures/partial JSONL, labels abandoned attempts, and retries in new directories. Completed evidence is never overwritten. Each analysis invocation gets a separate versioned directory, even if only analysis code changed. Changing JSON and running again creates an independent archive.

`parallel_cases` is a required daily field, so archives created before it existed fail current validation and the toolkit hash differs. Resume them through their archived toolkit as described next.

If toolkit implementation changed, invoke the archived `frozen/source/infra/experiments.py resume <absolute-run-path>` with the original runtime instead. Do not edit the saved manifest to bypass a mismatch. Archives of retired booking runs cannot be resumed with the current toolkit. Delete old archives deliberately after inspection when disk/database storage is no longer needed.

A kernel-held loopback lock on port 47983 prevents overlapping toolkit measurements across checkouts on this PC. It releases automatically when the orchestrator exits. On Windows, owned children are attached to kill-on-close jobs, so an orchestrator exit stops its process trees; normal interruption also waits for owned children to stop. Unrelated servers are never stopped. This does not prevent other tools or applications from consuming workstation resources.

## Analysis definitions

- Daily costs are integer modeled cents. Reference savings are `control reference cost - candidate reference cost` at the same budget. Own-budget improvement is `same solver 15-second reference cost - current reference cost`. Negative improvement is preserved. Accepted cost is after fairness and policy acceptance. Fairness is utilization variance, and time is milliseconds.
- Pair keys include fixture fingerprint, fleet, workload, seed, routing identity, cohort, and budget for daily or horizon/request count/concurrency/cache for archived booking evidence. Mismatched fixtures and incomplete controls remain unpaired. All duplicate copies of a case are excluded, not arbitrarily selected.
- Each summary averages within seed, then weights observed seeds equally. Every metric includes observed case/seed counts. Thin daily traces show individual seeds; bands show observed range, not confidence intervals. Seed-limited comparisons are exploratory. Graphs never enforce decreasing cost curves.
- Daily throughput is `moveEvaluations / solveMs * 1000` per phase, summarized per solver and fleet in `summary.json` and the HTML so contention is visible. It is null when either statistic is missing or the time is zero, and nulls are never zero-filled or averaged in. The parallelism block from the manifest appears in `analysis-provenance.json` and the HTML completeness section.
- In archived booking evidence, served, incomplete and failed counts are separate and may overlap. Unknown completion remains unknown. Search latency percentiles use nearest-rank quantiles of individual available request observations pooled across cases, never averages of case percentiles. Historical selection-conflict rows without separate selection timing are excluded from latency and completion calculations.
- Booking cost savings are computed only when fixture and exact served request indices match the control. Equal served counts alone are insufficient. Missing measurements stay JSON null / CSV blank / graph gaps. Raw failures, unfinished attempts, excluded duplicates and unpaired cases are explicit in the HTML/JSON completeness information.
- Historical import copies and hashes September 29 raw archives and manifest read-only. Stage cohorts (screen, original, held, stress, browser and recovery) remain separate. It does not revise the original report or turn historical labels into verified active strategies. Historical archive coverage does not establish completeness of a new configured matrix.

## Contention check and sequential confirmation

Dedicated cores remove core sharing but not memory, cache or clock contention, so measure it before a large parallel study. `daily-contention-1.json` and `daily-contention-6.json` are identical except for `parallel_cases` (1 and 6): four solvers, seeds 17, 23 and 41, fleets 20 and 50, CLUSTERED, 120 seconds, 24 cases each. Run both, then compare:

```powershell
py -3 infra/experiments.py run experiments/configs/daily-contention-1.json
py -3 infra/experiments.py run experiments/configs/daily-contention-6.json
py -3 infra/experiments.py compare-contention experiments/runs/<contention-1-run> experiments/runs/<contention-6-run>
```

The comparison pairs identical cases and reports the median ratio of parallel to sequential move evaluations per second for each solver and fleet. It leaves out pairs that did not run with the full set of neighbors (the ramp-up and the tail) and counts them. Accept `parallel_cases` 6 when every median reference-phase ratio is at least 0.95. Otherwise lower the value (for example to 4) in `daily-budget.json` and repeat. The supplied `daily-budget.json` now requests 10 parallel cases. A six-case contention result does not validate ten-case contention: repeat the same paired comparison with a copy of `daily-contention-6.json` set to `parallel_cases` 10 before relying on the larger study. Keep the saved report with the run evidence before starting the full study.

After the parallel study, run a sequential confirmation (`parallel_cases` 1) of the control and the best candidate on two of the study seeds, at the budgets that matter. Compare each case with its parallel twin (same solver, seed, fleet, workload and budget). The parallel results stand when the candidate-versus-control difference has the same sign and falls inside the paired noise. Otherwise report the gap as a finding. Quote absolute numbers from the sequential run.

## Verification

```powershell
py -3 -m unittest discover -s infra -p test_experiments.py -v
.\mvnw.cmd -Pnullability clean verify --batch-mode --no-transfer-progress
npm.cmd --prefix web run lint
npm.cmd --prefix web run typecheck
npm.cmd --prefix web run test:unit
```

Regression tests cover invalid configurations including `parallel_cases`, core slot ordering and affinity, concurrent dispatch limits, failure and interruption with in-flight receipts, matrix counts, 90/120/240-second propagation, immutable archives, lock exclusion, interruption/resume, provenance mismatch, historical preservation, missing/null metrics, duplicate/fixture/control exclusions, signed paired differences, equal seed weights, rejection of retired booking configurations, and overlapping booking outcomes and pooled percentiles in archived evidence. Real smoke runs must additionally verify process startup, actual solver/settings, independent audits and generated figures. Smoke results are harness verification, not evidence for the archived 1,280-case study.


### Earlier matrices

The planning snapshot described five seeds, three workloads and six budgets for 1,440 daily cases with 133,200 seconds of allowance, plus 720 booking cases. Subsequent user configuration changes produced the checked-in daily matrix described above. Regression tests construct the older daily expansion explicitly without changing the current JSON files. Existing archives, including the 413 completed booking cases, remain historical partial evidence under their original source and calendar semantics. New validation archives must not be pooled with them as corrected comparisons.
