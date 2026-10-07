# Phase 12: paired analysis and execution calibration

Phase 11 was reviewed and merged in PR #62 as `d3b2a042068cdb1be44f0b7f09b115e5578d4212`. Its historical receipts retain their recording times. Phase 12 adds analysis and registered offline execution studies; it does not change production algorithms, budgets, JVM settings, customer promises or caller-owned apply.

## Paired analysis

The existing v2 `analyze` operation now implements the registered `paired-dataset-bootstrap` method. `inventory-only` still emits no inference. The frozen toolkit includes the analyzer and study/profile helpers. Read-only analysis can use newer code after verifying every original frozen toolkit byte; each new analysis records current analyzer hashes and leaves measured artifacts unchanged. Execution and resume still require exact toolkit identity, and historical frozen entry points remain available. Existing v1 dispatch and analysis remain unchanged.

Pair identity includes dataset bytes, frozen target, complete budget, effective solver seed, fork, score/cost versions, routing identity and observed runtime cohort. Configuration comparisons take place within one sealed campaign. Assertion modes, profiling, resource allocations, warmup and caller/cache declarations participate in runtime identity. Different budgets, input cohorts, model versions and routing identities are separate summaries. Duplicate dispatches, changed artifacts and duplicate pair identities fail rather than silently collapsing observations.

Reports retain requested, dispatched, completed, successful, failed, interrupted, abandoned, missing, independently valid and paired counts per treatment. Requested counts are the reliability denominator, including never-dispatched cases. Completed means a terminal successful or failed process; interruption and orphaned dispatch remain explicit. Missing/failed/invalid counterparts retain pair identity and a reason. Contract and repair cohorts never enter feasible cost rankings.

Matched repetitions are averaged within each dataset, then datasets receive equal weight. The bootstrap resamples whole datasets, with the registered 10,000 draws, 95% coverage and explicit seed. Bounds use empirical order statistics at floor/ceiling indices; no extrapolated tails. A single independent dataset has a descriptive estimate and a null interval with a reason. Fairness effects and intervals remain decimal strings through family gates so small worsening cannot collapse into a binary tie. Families remain visible so an aggregate improvement cannot conceal a worsening scenario family.

Cost requires independently eligible complete outcomes and identical served demand. Workflow audit totals alone cannot establish booking customer identity or accepted offer cost, so unsupported caller cost and fairness effects stay null with reasons. Metrics missing from either treatment make the corresponding summary unavailable; there is no zero imputation or complete-case substitution. New solver and retained-policy receipts expose independently measured capacity-weighted fairness, overtime and exact fleet cents. Older receipts lacking that measurement retain explicit unavailability.

Baseline reports include within-dataset repetition means and equal-dataset descriptive means. The native throughput metric is actual move evaluation count divided by native solve time. Layer A wall time includes benchmark preparation/reporting and independent proposal import; it must not be presented as solver-only latency. Layer B wall time covers the complete policy operation. Caller latency includes intended arrival through response/cancellation/cleanup completion. These meanings remain separate.

A cost evidence gate requires the interval's upper bound below the registered cents threshold, no worse family cost or fairness, and complete independently valid reliability counts. This is evidence for later confirmation, not automatic promotion. Held-out selection, application request p95 and the 5% service gate remain phase 15 requirements.

## Registered studies

`study-run STUDY.json` accepts an explicit version 1 JSON with `kind` (`warmup`, `concurrency`, `profile`), name, purpose, pinned campaign configuration artifacts, rules and output location. Rules explicitly declare reference label, metric, tolerance, 10,000 bootstrap draws, seed, confidence and minimum fresh-JVM repetitions. All adjustable case parameters remain in the pinned v2 campaign JSON files, with no CLI overrides.

Registration precedes dispatch and retains original study bytes, configuration hashes, toolkit identity and `automaticRetries: false`. Each probe gets a new immutable campaign archive at its normal configured path depth, referenced by manifest hash from the study. This avoids Windows rejecting nested JVM working directories. The machine measurement lock encloses sequential probes. A failed/interrupted probe preserves its archive and stops the study; the tool never automatically retries it or produces a recommendation. `study-analyze STUDY_DIRECTORY` verifies completed probe manifests, raw case hashes, actual hardware/JDK/flags/affinity and matched fixed-work identities before comparing.

Warmup studies require exactly the 200, 30,000 and 60,000 millisecond probes, one selected path per study and at least three forks. The 60-second probe is the declared reference. Reference, frozen-target fairness and complete-policy paths have separate registrations and invoke their real adapters on disposable planning state. Ratios are paired by complete case identity including fork, budget and configuration; each dataset/configuration group is retained separately. Bootstrap intervals for fixed-work calibration use independent fresh-JVM fork repetitions. An interval must lie wholly inside the registered tolerance to be classified as stable. Ambiguity remains `ambiguous-register-new-attempt`; no default or warmup value is silently selected.

Concurrency studies compare a serial reference against explicitly declared outer instance counts while holding total CPU/memory, heap, JVM flags, input, allowance and solver settings constant. Native parallelism stays one and move threads stay NONE. The existing runner admits independent treatments within a comparison block into disjoint physical-core slots; enough configurations must exist to exercise the requested occupancy. Effects are compared within each configuration, not across different algorithms or settings. Serial and concurrent runtime hashes remain different and cannot enter ordinary treatment pairing.

Profile studies require an unprofiled reference and separate `diagnostic` JFR profile cohorts. They never become timed algorithm comparison evidence. `profile-study STUDY_DIRECTORY CREATE_NEW_OUTPUT` uses the pinned JDK's `jfr` tool and saves derived JSON outside sealed case directories. Reports retain raw recording/export/tool hashes, actual allocation sample weights, execution sample leaf frames and recorded GC durations. Missing events produce null values with reasons. Allocation weights are sampled estimates. Whole-process recordings include warmup, solving, validation, JVM startup and native report generation, so these profiles cannot alone prove that score evaluation dominates production CPU.

## Local baseline and evidence boundaries

All studies are synthetic, offline and use explicitly declared shorter reference/policy allowances. They do not certify production throughput, historical cost savings, booking latency or service capacity. Suitable historical snapshots and caller cache/deployment/resource confirmation remain prerequisites for later promotion.

The first G1 baseline exceeded its declared process allowance and is retained as a failure. A separately registered JFR diagnostic with longer outer cleanup grace completed, including native cooperative overshoot; captured thread snapshots showed substantial G1 refinement CPU. This is diagnostic evidence, not a matched proof that one collector is superior. A new SerialGC registration preserves the earlier benchmark collector as a distinct cohort. Neither changes production flags. Registration failures also remain inspectable and were corrected before measurement dispatch.

The final verification receipt records exact source/check identities, every attempted campaign/study, raw archive manifests and calibration dispositions. Bulk datasets, JFR files and native reports remain under `experiments/runs`, outside build-clean directories and Git. Owner review is required before phase 13. Conditional optimization and algorithm studies remain pending.

## Recorded results

See [machine-readable baseline/calibration results](baseline-and-calibration.json) and [verification and archive hashes](verification.json). Raw file inventories and recordings remain locally pinned by the receipt.

| Evidence | Recorded outcome | Limit |
| --- | --- | --- |
| SerialGC reference baseline | 16 completed, independently valid cases; eight treatment pairs across two independently seeded clustered datasets. Cost/fairness gate not met. | One synthetic family, short offline allowances and uncalibrated 200 ms warmup; no winner or production claim. |
| Reference warmup | Three forks per 200/30,000/60,000 ms probe; shorter probes outside registered stability tolerance. | Ambiguous; register another study before selecting a comparative warmup default. |
| Frozen-target fairness warmup | All nine cases complete and independently valid; neither shorter probe establishes stability. | Ambiguous; preserves the shared target and existing defaults. |
| Complete-policy warmup | All nine cases complete and independently valid; neither shorter probe establishes stability. | Measures its own reference and acceptance; short budgets do not certify production latency. |
| Outer concurrency | Six cases each at serial/two-instance occupancy, native parallelism one and disjoint physical cores; within-configuration effects remain ambiguous. | No increased production concurrency or capacity certification. |
| Separate JFR profiling | Three instrumented recordings exported with actual CPU samples, allocation sample weights and GC durations, plus an unprofiled control cohort. | Whole-JVM diagnostic scope; no score-only CPU dominance or exact allocation claim. |
| Failure preservation | Initial G1 watchdog failure and nested-path launch failure remain separate from corrected registrations. | Neither failed attempt was automatically retried or relabeled successful. |

Ambiguous calibration is a recorded result, not successful calibration of production settings. Further calibration must use an explicitly registered new attempt before performance selection. Review of this phase does not authorize optimization or promotion based on the cold baseline alone.
