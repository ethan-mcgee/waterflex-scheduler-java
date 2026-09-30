# Configurable scheduler experiments

The Python toolkit runs manual local comparisons, preserves raw evidence, and generates PNG/SVG figures plus a local HTML index. Production solver defaults and the existing reports remain unchanged. The full studies are not unit tests and are never launched by CI.

## Setup and commands

Use Python 3.10 or later, Java 25, Node, the checked-in Maven wrapper, and enough disk space for a separate frozen source/build/dependency snapshot per run. Booking snapshots also copy `web/node_modules`, which can take several GB. Run from the repository root in PowerShell. The launcher has a terminal menu when run without arguments:

```powershell
py -3 run_experiment.py
```

Or choose the experiment and mode directly:

```powershell
py -3 run_experiment.py daily smoke
py -3 run_experiment.py daily dry-run
py -3 run_experiment.py daily full
py -3 run_experiment.py booking smoke
py -3 run_experiment.py booking dry-run
py -3 run_experiment.py booking full
py -3 run_experiment.py daily full --config experiments/configs/my-daily-study.json
```

`smoke` selects the small corresponding configuration; `dry-run` expands the full configuration without installing packages or measuring; `full` selects the supplied full configuration. `--config` replaces the selected configuration in any mode. The file must contain only the selected experiment kind. Copy a supplied JSON file, give it a new lowercase `name`, edit its lists, and try `dry-run --config <path>` before measuring. Each measured invocation creates a new archive.

The launcher checks Java 25 and Node, detects the JDK home when Java is on `PATH`, installs the Python graph requirements if missing, and for booking installs Node packages and generates Prisma when missing. It then invokes the existing experiment engine. During preparation and measurement, the terminal shows a progress bar, completed/total cases, total elapsed time, current case elapsed time, and case details. Counts advance only after a valid completion receipt is written. If output is redirected, the same status is printed as lines at transitions. Booking still requires a ready road provider and a local `waterflex_test` database; the launcher checks these before installing dependencies. Provide their credentials and URL through the environment:

```powershell
$env:DATABASE_URL = 'postgresql://USER:PASSWORD@127.0.0.1:5433/waterflex_test'
$env:ROUTING_URL = 'http://127.0.0.1:8001'
```

The underlying command remains available for advanced use:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
py -3 -m pip install -r infra/requirements-experiments.txt
npm.cmd --prefix web ci
npm.cmd --prefix web run prisma:generate

py -3 infra/experiments.py run experiments/configs/daily-budget.json --dry-run
py -3 infra/experiments.py run experiments/configs/daily-smoke.json
py -3 infra/experiments.py run experiments/configs/daily-budget.json
```

Commit harness inputs before measurements. Documentation and `AGENTS.md` edits do not prevent runs. The toolkit copies committed source bytes from the clean working tree, builds that frozen copy with strict Java nullability, and stores hashes of source and executable artifacts. It never builds a long run from changing working-tree classes.

For direct booking runs, an existing local PostgreSQL `waterflex_test` database and a ready road provider are required:

```powershell
# Supply your local credentials through the environment, not experiment JSON.
$env:DATABASE_URL = 'postgresql://USER:PASSWORD@127.0.0.1:5433/waterflex_test'
$env:ROUTING_URL = 'http://127.0.0.1:8001'
py -3 infra/experiments.py run experiments/configs/booking-smoke.json
py -3 infra/experiments.py run experiments/configs/booking-comparison.json

py -3 infra/experiments.py resume experiments/runs/<run-id>
py -3 infra/experiments.py analyze experiments/runs/<run-id>
py -3 infra/experiments.py import-history docs/evidence/scheduler-field-2026-09-29
```

`run` prints its unique directory before building. A successful measurement automatically invokes analysis. If interrupted or failed, invoke `analyze` explicitly for a completeness report, then `resume` to retry unfinished cases. A preparation failure before `manifest.json` requires a new run; its build log is retained. A dry run prints every expanded case, count, prerequisites, and total daily search allowance without building or measuring.

## Configuration contract

JSON has `version: 1`, a lowercase slug `name`, and either or both `daily` and `booking` objects. Omit an unused section. Every included section requires all its fields; unknown fields, duplicate JSON keys, duplicate values, nulls, empty selections and invalid values are rejected before execution. Counts derive exclusively from the lists.

| Field | Daily | Booking |
| --- | --- | --- |
| `solvers` | Any of CURRENT_CAPPED, CURRENT_UNCAPPED, LATE_ACCEPTANCE_CHANGE, LATE_ACCEPTANCE, TABU, SUBLIST, KOPT, RUIN_RECREATE | Any of INSERTION, BOUNDED, EXPANDED, RUIN_RECREATE, SHARED |
| `control` | One selected solver | One selected solver |
| `seeds` | Distinct integers 0..2147483647 | Same |
| `fleets` | Any of 5, 10, 20, 30, 50 | Same |
| `workloads` | SPARSE, CLUSTERED, DISPERSED, MIXED_SKILL, TIGHT_WINDOW, ABSENCE, NEAR_CAPACITY | Same |
| `budgets_seconds` | 0.1..240, whole millisecond resolution | Not accepted |
| `concurrency` | Not accepted | Any of 1, 5, 10 |
| `caches` | Not accepted | cold, warm |
| `requests` | Not accepted | Integer 1..200; defaults supplied in JSON are 10, smoke uses 2 |

The supplied daily study selects LATE_ACCEPTANCE, TABU, SUBLIST and KOPT; TABU is the control. Five seeds (17, 23, 41, 59, 83), four fleets (5, 10, 20, 50), CLUSTERED fixtures, and six budgets (15, 30, 60, 90, 120, 240 seconds) produce **480 cases** and **44,400 seconds (12 hours 20 minutes)** of search allowance. Startup, warmup, validation, copying, building and plotting add overhead. Capped/early terminating variants can use less than their allowance.

The supplied booking matrix has INSERTION and BOUNDED, INSERTION control, the same seeds/fleets, DISPERSED fixtures, three concurrency levels, two caches, and ten requests per case: **240 cases and 2,400 requests**. It retains existing durable booking search limits; daily budgets never configure booking. Runtime depends on search, audits, fixture creation and process startup. These are direct durable-client HTTP measurements, not browser or address-entry latency.

## Measurement protocol

Daily cases start fresh JVMs and unchanged fixtures. Each selected solver receives the same 200 ms warmup on the 20-technician SPARSE fixture. The combined budget assigns two-thirds to reference cost search and the remaining actual time to fairness. Raw JSONL contains configured budget, actual per-phase statistics, termination outcomes, independent validation, reference and accepted policy metrics. Earlier budget results never seed later cases. Seeds change stochastic search on fixed geography, not the number of geographic datasets.

Solver order and budget/settings order rotate independently across matched fleet/workload/seed blocks. Solvers are interleaved within settings. The exact expanded order is saved and reused on resume. Measured cases are sequential. Booking concurrency is deliberate within a case only.

Every booking attempt migrates a new `benchmark_exp_<uuid>` schema in local `waterflex_test`, starts its own scheduler on an unused loopback port with a four-connection pool and minimum idle one, and gives Prisma at most four connections. The process enables reservations and the bounded gate, then explicitly selects the strategy. INSERTION explicitly bypasses refinement. Statistics before/after must report the requested strategy, bounded gate and reservation setting. Independent route and active-reservation audits remain required.

Booking dates are calculated by the existing ten-weekday Chicago horizon helper and frozen. The toolkit and harness require those exact dates to remain valid on resume and before every case. A midnight horizon change requires a new comparison. Routing health must be ready with the saved identity, and both independent audits must use that identity. Cold/warm clears or prewarms scheduler memory/persistent routing caches; the shared road provider cache is not reset. Provider/workstation background activity remains a timing limitation.

## Evidence and resumption

```text
experiments/runs/<UTC timestamp>-<name>-<unique id>/
  original-config.json    # exact input bytes
  config.json             # validated resolved configuration
  cases.json              # authoritative expanded execution order
  manifest.json           # source/artifact hashes, runtime, hardware, dates/identity
  build.log
  frozen/                 # copied source, classes, dependency jars, server jar, Node dependencies
  attempts/<case-id>/<timestamp>-<attempt-id>/
    started.json
    raw.jsonl
    completed.json        # raw hash and matching case, only after successful validation
    failed.json or interrupted.json
    solver.log or booking.json + migrate.log + server.log + client.log
  analysis/<timestamp>-<unique id>/
    summary.json, summary.csv, cases.csv, analysis-provenance.json, index.html
    figure-*.png, figure-*.svg
```

Everything under `experiments/runs` is gitignored. Environment credentials are not copied into configurations or runtime manifests. Do not place secrets in tracked sources or provider URLs. Diagnostic logs remain local evidence and may contain fixture/database diagnostics.

Completion receipts bind raw hashes to exact expected cases. Resume verifies saved configuration, matrix, executable files, toolkit implementation, runtime and hardware identity, plus booking horizon/routing. It skips completed cases, preserves prior failures/partial JSONL, labels abandoned attempts, and retries in new directories. Completed evidence is never overwritten. Each analysis invocation gets a separate versioned directory, even if only analysis code changed. Changing JSON and running again creates an independent archive.

If toolkit implementation changed, invoke the archived `frozen/source/infra/experiments.py resume <absolute-run-path>` with the original runtime instead. Do not edit the saved manifest to bypass a mismatch. Finished booking schemas are retained for inspection; the harness removes its successful fixture rows. Failed schemas retain their fixture data. Delete old archives/schemas deliberately after inspection when disk/database storage is no longer needed.

A kernel-held loopback lock on port 47983 prevents overlapping toolkit measurements across checkouts on this PC. It releases automatically when the orchestrator exits. On Windows, owned children are attached to kill-on-close jobs, so an orchestrator exit stops its process trees; normal interruption also waits for owned children to stop. Unrelated servers are never stopped. This does not prevent other tools or applications from consuming workstation resources.

## Analysis definitions

- Daily costs are integer modeled cents. Reference savings are `control reference cost - candidate reference cost` at the same budget. Own-budget improvement is `same solver 15-second reference cost - current reference cost`. Negative improvement is preserved. Accepted cost is after fairness and policy acceptance. Fairness is utilization variance, and time is milliseconds.
- Pair keys include fixture fingerprint, fleet, workload, seed, routing identity, cohort, and budget for daily or horizon/request count/concurrency/cache for booking. Mismatched fixtures and incomplete controls remain unpaired. All duplicate copies of a case are excluded, not arbitrarily selected.
- Each summary averages within seed, then weights observed seeds equally. Every metric includes observed case/seed counts. Thin daily traces show individual seeds; bands show observed range, not confidence intervals. Five seeds are exploratory. Graphs never enforce decreasing cost curves.
- Booking served, incomplete and failed counts are separate and may overlap. Unknown completion remains unknown. Search latency percentiles use nearest-rank quantiles of individual available request observations pooled across cases, never averages of case percentiles. Historical selection-conflict rows without separate selection timing are excluded from latency and completion calculations.
- Booking cost savings are computed only when fixture and exact served request indices match the control. Equal served counts alone are insufficient. Missing measurements stay JSON null / CSV blank / graph gaps. Raw failures, unfinished attempts, excluded duplicates and unpaired cases are explicit in the HTML/JSON completeness information.
- Historical import copies and hashes September 29 raw archives and manifest read-only. Stage cohorts (screen, original, held, stress, browser and recovery) remain separate. It does not revise the original report or turn historical labels into verified active strategies. Historical archive coverage does not establish completeness of a new configured matrix.

## Verification

```powershell
py -3 -m unittest discover -s infra -p test_experiments.py -v
.\mvnw.cmd -Pnullability clean verify --batch-mode --no-transfer-progress
npm.cmd --prefix web run lint
npm.cmd --prefix web run typecheck
npm.cmd --prefix web run test:unit
```

Regression tests cover invalid configurations, matrix counts, 90/120/240-second propagation, immutable archives, lock exclusion, interruption/resume, provenance mismatch, historical preservation, missing/null metrics, duplicate/fixture/control exclusions, signed paired differences, equal seed weights, overlapping booking outcomes and pooled percentiles. Real smoke runs must additionally verify process startup, actual solver/settings, independent audits and generated figures. Smoke results are harness verification, not evidence for the unrun 480-case study.
