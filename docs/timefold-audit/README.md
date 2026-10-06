# Timefold audit package

Read the [rewritten Markdown audit](Timefold_Audit.md) or [matching Word edition](Timefold_Audit.docx). This 6 October 2026 edition replaces the accumulated reviews with one technical explanation of current behavior, consequences, relevant Timefold guidance, proposed changes, expected benefits, tradeoffs, and acceptance criteria.

The report retains TF01 through TF14, explains the architecture and algorithm choices, and specifies a three-layer benchmark redesign. Proposed datasets, schema version 2, calibration, campaign matrices, statistics, and promotion gates are design work only. No runtime behavior, production policy, dependency, or benchmark runner changes are implemented here. No new performance campaign was run.

## Sources and historical evidence

Reviewed baseline: `499fde2cc3fe79a8b7413a942a66cfc97b5d0e94`. Runtime and benchmark sources are unchanged from the prior expanded review baseline `afa035ddf2101d26c12508067d2d9016601db6c3`.

The supplied `docs/timefold-documentation/Timefold-Solver-Docs.md` and PDF remain external local inputs, not additions to this PR. Their hashes and selected section ranges are in [source-register.json](evidence/third-review/source-register.json). This conversion differs from the earlier source. We do not claim another complete 66-chapter or PDF review. Runtime is 2.6.0; live latest pages returned 2.7.0 and 2.7.1 labels, so examples require compatibility checks before implementation.

Original and second-review manifests, raw logs, coverage, retained archive receipts, and visual QA are preserved. Historical report/helper hashes are checked against their retained Git revisions. The third-review manifest checks the rewritten report and new helpers against current bytes, and checks all preserved historical evidence against baseline identities. Line-ending normalization is explicit. New receipts never overwrite old findings or relabel old measurements.

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

`--external` additionally checks the current supplied Markdown/PDF hashes and selected section ranges. Repository-only verification does not require those untracked inputs. The verifier checks exact ordered Markdown/Word text and hyperlink pairs, historical hashes, pinned source links, explained finding coverage, campaign arithmetic, and visual QA receipts.

The first experiment-suite attempt used the document runtime, which lacked Matplotlib. Its failure log is retained. The project Python rerun passed. Exact commands, runtimes, outcomes, and the one skipped experiment test are recorded in [verification.json](evidence/third-review/verification.json).

## Regenerate the report

```powershell
python docs/timefold-audit/build_report.py
python docs/timefold-audit/refresh_review.py --sources
```

The builder preserves Calibri, pale-blue tables, repeated headers, accessible body text, running headers, and page counts. Render and inspect every page after changing text or styles. The packaged renderer was attempted; LibreOffice is absent in this Windows runtime. Microsoft Word PDF export plus bundled Poppler is the fallback. PDF/page images are internal QA under ignored `qa/`, not additional deliverables.

After completing tests and recording page inspection, refresh the third-review manifest and rerun verification:

```powershell
python docs/timefold-audit/refresh_review.py --manifest
python docs/timefold-audit/verify_evidence.py --external
```

Do not refresh historical manifests to make them match rewritten content. `refresh_review.py` changes only third-review receipts. Its `campaign-design.json` companion is a checked specification, not configuration that can be passed to `run_experiment.py`.
