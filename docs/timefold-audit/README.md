# Timefold audit package

Read the [rewritten Markdown audit](Timefold_Audit.md) or [matching Word edition](Timefold_Audit.docx). This 6 October 2026 edition replaces the accumulated reviews with one technical explanation of current behavior, consequences, relevant Timefold guidance, proposed changes, expected benefits, tradeoffs, and acceptance criteria.

The report retains TF01 through TF14 and integrates CR01 through CR11 from the supplied second review. It specifies cooperative deadline ownership, indexed directed-road datasets, overconstrained diagnostic planning, policy-based fairness skipping, explicit target weighting, overnight observability, and independently measurable timing, score-representation, and aggregation changes. Benchmark foundations and profiling precede performance work; shadow-variable remodeling remains later work.

Community Edition is an explicit constraint. Booking insertion/refinement, independently validated ordering and business explanations, external profiling, and parallel independent solves form the recommended approach. Enterprise purchase evaluation, custom multithreaded move evaluation, and a solver fork are excluded from the recommended sequence. Public, preview, and internal dependencies need separate compatibility checks.

The three benchmark layers, shared fairness references, held-out datasets, uncertainty limits, and failure preservation remain. One proposed versioned JSON file contains every adjustable campaign parameter. Coverage, per-solve allowance, and CPU/concurrency are separate decisions. Dry-run expansion and overhead-aware duration formulas replace mandatory stage durations. A campaign cutoff stops new comparison blocks without shortening configured solves. Proposed datasets, schema version 2, calibration, statistics, and promotion gates are design work only. No runtime behavior, production policy, dependency, or benchmark runner changes are implemented here. No new performance campaign was run.

## Sources and historical evidence

Reviewed baseline: `499fde2cc3fe79a8b7413a942a66cfc97b5d0e94`. Runtime and benchmark sources are unchanged from the prior expanded review baseline `afa035ddf2101d26c12508067d2d9016601db6c3`.

The supplied `docs/timefold-documentation/` directory is included in this PR: the Markdown guide, PDF, and 113 figure assets. Git preserves the Markdown source bytes without line-ending conversion. Their hashes and selected section ranges are in [source-register.json](evidence/fourth-review/source-register.json). We do not claim another complete 66-chapter or PDF review. Runtime is 2.6.0; current overview pages returned 2.7.1 labels, so examples require compatibility checks before implementation. The Timefold 2.x long-based class is `HardMediumSoftScore`; the second review's `HardMediumSoftLongScore` name is obsolete.

Original, second-review, and third-review manifests, raw logs, coverage, retained archive receipts, and visual QA are preserved. Historical report/helper hashes are checked against their retained Git revisions, including the previous report revision `09fb326d24d845ef0812debf301ac4fe7e1f92f7`. Fourth-review receipts identify the current report and helpers and check all preserved historical evidence. The supplied [second-review Markdown](Timefold_Second_Review.md) and Word files are preserved byte for byte with a separate receipt. Line-ending normalization for historical text is explicit. New receipts never overwrite old findings or relabel old measurements.

The prior retained archive review covered 360 booking cases, 1,280 daily cases, and two 24-case contention runs. This edition preserves those review receipts rather than claiming a new full archive traversal. Seven Java characterization tests reproduce unsafe core behavior; passing them does not mean the findings are fixed.

## Reproduce verification

Use JDK 25, Python with python-docx and lxml for document helpers, and the project experiment dependencies for the experiment suite. Do not use Python `-O`, which disables evidence assertions.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-25'
.\mvnw.cmd -Pnullability clean verify --batch-mode --no-transfer-progress
python -m unittest discover -s infra -p test_experiments.py -v
python -m unittest discover -s docs/timefold-audit -p test_verification.py -v
python docs/timefold-audit/verify_evidence.py
python docs/timefold-audit/verify_evidence.py --external
npm.cmd --prefix web run test:nullability
npm.cmd --prefix web run lint
npm.cmd --prefix web run typecheck
npm.cmd --prefix web run test:unit
```

`--external` additionally checks the included source Markdown/PDF hashes and selected section ranges. The option retains its original name; these inputs are now tracked in this repository. The verifier checks exact ordered Markdown/Word text and hyperlink pairs, historical hashes, pinned source links, explained finding coverage, campaign arithmetic, and visual QA receipts.

Current commands, runtimes, and outcomes are recorded in [verification.json](evidence/fourth-review/verification.json). Previous test failures and successful reruns remain historical evidence in their original directories.

[campaign-example.json](evidence/fourth-review/campaign-example.json) is a complete illustrative parameter inventory for a reference-only Community study. It is not accepted by `run_experiment.py`. `campaign_spec.py` validates this illustration's shape, nullability, version/edition, resource bounds, and matching arithmetic. It is not a runtime configuration parser, dataset validator, JVM launcher, or proof that the proposed Timefold settings compile. `acceptorParameter` means entity tabu size for TABU and history size for LA; phase-specific termination parameters apply only to their named policy. Reference setup and application load fields are explicitly inapplicable in this Layer A example. The future schema must dispatch on layer/algorithm/move family and declare any additional bounds required by new treatments.

The original and resolved JSON, deterministic expansion, artifact hashes, and discovered hardware/effective runtime settings must be archived when the future runner is implemented. Credentials remain outside JSON. Runtime settings in this example are requested values, not measured machine metadata. Daytime and overnight comparisons use explicitly selected parameters, with no automatic preset selection or experiment retries.

## Regenerate the report

```powershell
python docs/timefold-audit/build_report.py
python docs/timefold-audit/refresh_review.py --sources
```

The builder preserves Calibri, pale-blue tables, repeated headers, accessible body text, running headers, and page counts. Render and inspect every page after changing text or styles. The packaged renderer was attempted; LibreOffice is absent in this Windows runtime. Microsoft Word PDF export plus bundled Poppler is the fallback. PDF/page images are internal QA under ignored `qa/`, not additional deliverables.

After completing tests and recording page inspection, refresh the fourth-review manifest and rerun verification:

```powershell
python docs/timefold-audit/refresh_review.py --manifest
python docs/timefold-audit/verify_evidence.py --external
```

Do not refresh historical manifests to make them match rewritten content. `refresh_review.py` changes only fourth-review receipts. Its `campaign-design.json` companion checks worked formulas without enforcing mandatory stages or durations. The production example has 96 cases, 960 seconds solving, 2,880 seconds per-JVM warmup, and 384 seconds estimated overhead; the ideal two-worker estimate is 2,112 seconds. The separate 60-second cohort gives 4,512 seconds under the same assumptions. Neither estimate is a measured runtime or completion guarantee.
