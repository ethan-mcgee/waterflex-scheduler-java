# Phase 10: campaign orchestration v2

PR #60, phase 9, was reviewed and merged as `608d127d9ffc0f2f1574838032a3f055de9c528d`. This phase implements the actual `infra/experiments.py` version dispatch and campaign lifecycle. It supplies a hash-pinned fresh-JVM adapter protocol. The production native solver, complete policy, and paced application adapters are phase 11, and equal-dataset inference is phase 12. A lifecycle fixture is not solver or production performance evidence.

## Operations and compatibility

```powershell
python infra/experiments.py validate experiments/configs/campaign-v2-template.json
python infra/experiments.py dry-run experiments/configs/campaign-v2-template.json
python infra/experiments.py run path/to/fully-pinned-campaign.json
python infra/experiments.py resume path/to/run
python infra/experiments.py analyze path/to/run
```

`run CONFIG --dry-run` remains supported. CLI arguments select the operation and artifact only. No seed, budget, algorithm, resource or load overrides exist. Version 1 dispatch, configuration expansion, historical warmup/seed meaning, importer, analysis and retry semantics are unchanged. The interactive `run_experiment.py` convenience launcher remains explicitly v1. Import-history and compare-contention remain v1-only operations; v2 archives are not converted into historical measurements.

The template is schema-valid and can be estimated without an installed adapter. Its zero Java/JAR hashes deliberately prevent execution until the author records actual byte identities in the JSON. The included `CampaignFixture.java` is a test executable, has no Timefold dependency, and deliberately fails the `la` fixture treatment. Never use its results for algorithm comparisons. The CLI regression compiles a temporary fixture JAR and fills all three input hashes before using the real run/resume/analyze entry point.

## Explicit configuration

`campaign_config.py` validates every object with exact required keys. Duplicate JSON keys, unknown/missing fields, nonfinite numbers, booleans in integer positions, duplicate identities/content/cases and unsupported Community settings fail. There are no fabricated scheduling facts, measured values, implicit aliases or default experiment settings.

The single JSON declares:

| Section | Meaning |
| --- | --- |
| datasets | Immutable input and optional frozen target paths/SHA-256, family, tuning/confirmation role, assigned/cold/partial/repair/invalid-input cohort, origin, independent synthetic seed or explicit null, score/model/routing identities. Invalid and repair campaigns stay separate. |
| configurations | Stable identity, TABU/LA size, accepted/selected counts, weighted move families, environment mode, move threads NONE, native parallelism one, explicit fixed/diminished/unimproved termination inside an enclosing cap. Inapplicable fields are null. |
| solverSeeds/forks/budgets | Effective seeds, fresh-process repetitions, purpose/phase, operation/search/reference/fairness/repair/reserve milliseconds and unused transfer policy. Solver fairness requires frozen targets; policy/workflow creates its own reference. Production daily pipelines retain 20-second operation, 15-second search and 10-second reference allocations. |
| warmup | Milliseconds for each declared path in every fresh JVM, disposable inputs, calibration probes/repetitions/stability rule. Calibration experiments are phase 12. |
| runtime/resources | Pinned Java 25 executable, heap bounds, GC, processor count, allowlisted flags, explicit noncredential environment, one-case JVM lifetime, outer concurrency, physical-core allocation/affinity, heap allocation guard, concurrency calibration inventory. |
| instrumentation | Explicit quality/diagnostic cohort, requested statistics, JFR setting, sample interval, required internal diagnostics and disabled constraint profiling. Diagnostic timing stays separate. |
| analysis | Complete pairing identity, requested analysis method, 10,000 bootstrap draws, seed, 95% level, role/selection and business gates. Phase 10 stores these rules but produces lifecycle inventory only. |
| applicationLoad | Null for solver/policy; otherwise explicit paced arrivals, counts/rate/concurrency, scheduler/provider cache states, request timeout, cancellation observation, embedded/remote identity. Actual pacing and service receipt validation are phase 11. |
| execution/estimation/output/adapter | Counterbalance order seed, no automatic retries, stop-next-block policy, missing-case resume, absolute UTC cutoff or null, cleanup grace, separately declared overhead/reference setup/analysis estimates, output location, protocol and pinned fat JAR. |

All phases' search allowances sum once to the search cap. Estimates count the complete policy operation once, rather than counting reference and fairness as separate pipelines. Warmup is counted per path and JVM. Startup/preparation/validation/reporting/setup/analysis overhead is separate. Serial and optimistic concurrent estimates account for uneven treatment counts and nonoverlapping comparison blocks; neither is observed runtime. Application estimates include the intended arrival span and last timeout allowance.

## Adapter boundary

The executable fat JAR receives exactly two arguments: immutable `request.json` and a create-new `adapter-receipt.json` destination. No shell command or mutable Maven profile is selected by the campaign. The request carries the exact indexed input/target file identities, case/treatment/phase/budget, warmup, instrumentation, load, runtime cohort hash, actual JVM flags and allocated CPUs. The pinned adapter must validate its supported layer/settings before measured calculation, independently validate snapshots/targets and outputs, and faithfully record the effective configuration and enabled/unavailable metrics in its raw result. The future production adapter must implement the requested settings; the phase 10 runner does not claim to verify Timefold effective phases or scoring from an echoed hash.

The outer receipt has exactly `protocol`, `caseId`, `requestHash`, `state`, `evidenceKind`, `warmup`, `runtime`, `instrumentation`, and structured `result`. Protocol is `waterflex-campaign-jvm-v1`. Case/request hashes must match. Success requires each path's measured warmup receipt to meet the declared duration, disposable inputs, JVM input arguments/available processors, observed process affinity, and a real PID. Fixture and benchmark evidence kinds must agree with dataset origin. Nonzero process exit, missing/oversized/malformed receipt, invalid identity/runtime/warmup, or a non-success adapter state fails the case, preserving the original adapter output and process log. The instrumentation receipt partitions every requested statistic into enabled or unavailable; unavailable values must be null with a reason. Required internal diagnostic failure invalidates the experiment. Phase 10 invents no measurements.

JFR is a separate diagnostic cohort. The runner adds its actual output flag before archiving the request and validates the JVM's observed arguments against it. Fat-JAR dependencies and layer-specific reports are the adapter's responsibility. All nested raw outputs are hashed after process-tree cleanup, outside build-clean directories.

## Provenance, resources and failures

Before dispatch the archive contains original config bytes, absolute resolved config, deterministic block/case order, estimates, frozen toolkit/JAR/datasets/targets, and a manifest of byte hashes. Each attempt records requested/observed runtime and process activity before measurements. Observations include Java executable/JDK modules hashes, exact JVM flags and settings probe, hardware/memory, usable physical-core affinity, Python version and explicitly configured noncredential environment. Process inventory excludes command arguments and environment. It documents competing activity and does not establish machine isolation.

TCP port 47983 serializes v1 and v2 measurements across checkouts. Concurrent cases have disjoint physical cores; Windows preserves the existing kill-on-close job ownership, and Linux requires taskset and sysfs topology. CPU allocation and declared heap allocation are checked against observed resources. `memoryLimitMiB` is a declared allocation guard for configured JVM heaps, not an OS RSS hard limit or isolation from external processes. JAVA_TOOL_OPTIONS, application overrides and credentials are not inherited. Heap/native/RSS measurements and concurrency calibration remain later evidence gates.

Preparation failures after archive creation have immutable failure receipts and a printed run directory. A dispatched case gets a durable dispatch receipt before launch and a terminal receipt with recursive hashes of request, log, response and all raw artifacts after cleanup. Evidence publication uses create-new semantics; resume verifies configuration, expansion, runtime, toolkit, frozen files and completed-case hashes. A changed JDK/hardware/affinity/configuration/toolkit is a distinct campaign/runtime cohort, not an implicit continuation. To use historical toolkit bytes, invoke `RUN/frozen/toolkit/experiments.py resume RUN` with the original compatible Python/JDK/resources.

Comparison blocks contain every treatment for the same dataset, target, budget, effective seed and fork. Treatment positions rotate and block order uses the saved order seed. Budget pairing uses the budget content hash, rather than an editable label. Runtime cohort identity includes warmup, instrumentation, load/cache and requested resources; assertion modes cannot be mixed in one campaign. Blocks do not overlap. Admission checks the absolute cutoff immediately before the next block. Every admitted block's cases finish their full unchanged allowances, including pending treatment cases when outer concurrency is smaller than the treatment count. Failure also stops the next block after the admitted block completes. There is no automatic retry.

Explicit resume creates a new attempt and runs only never-dispatched cases. Failed, interrupted and orphaned dispatches are permanently consumed case identities and remain in denominators. An incomplete block can receive its untouched cases but cannot replace a failed observation. An expired immutable cutoff remains expired on resume. Extending a cutoff or repeating a failed comparison requires a separate, explicitly registered campaign. Existing results must never be relabeled or erased. An explicit interruption stops owned processes and preserves interruption/orphan evidence; it does not imply all requested work completed.

`analyze` appends a fresh inventory with requested/dispatched/missing cases, all outcomes, successful and incomplete blocks, and original artifact paths. A successful adapter process is not proof of independent scheduling validity. Valid-case counts and inference remain null with reasons until the phase 11/12 readers supply those checks. A resume attempt with no untouched cases can complete even when historical cases failed; the inventory still exposes those failures.

## Verification and remaining gates

Run `python -m unittest discover -s infra -p test_experiments.py -v` with Java 25. The existing CI entry point includes both the unchanged v1 suite and the v2 parser/expansion/estimate/strict-null/provenance/failure/cutoff/interruption/resume fixtures. The real CLI fixture observes four different JVM PIDs, actual flags and affinity, warmup in every fresh process, two deliberately retained failures, and no duplicate dispatches after repeated resume. A separate diagnostic fixture produces a real retained JFR file with matching observed JVM flags. These are contract tests, not measurements of solver speed or quality.

Local full Java verify/nullability, portal gates and retained baseline/audit checks are recorded in `verification.json`. Hosted business integration, browser and assertion checks must pass on the exact PR head before delivery. Owner review is required before phase 11. No native benchmark adapter, corpus generation, calibration, inference, experimental optimization, production promotion or deployment is claimed here. Rollback is returning to v1 input/entry points; v1 archives and production scheduling defaults are unchanged.
