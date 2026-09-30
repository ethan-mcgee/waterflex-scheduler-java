# Booking experiment explainer

Word report and reproducible authoring materials for the immutable September 30 booking archive. No runtime, API, configuration, experiment or service changes. The original checkout's uncommitted files and both experiment archives are untouched.

## Inputs and methods

`report.md` is the explanatory prose. `evidence.json` contains compact extracted audits and request observations, plus per-input SHA-256 receipts. `extract_evidence.py` reads raw files without writing to the archive and uses its hash-verified frozen analyzer. `report_data.py` provides matched comparisons and pooled nearest-rank percentiles. `build_report.py` reuses `../scheduler-explainer/reference.docx`. `original-figures` contains unchanged copies of saved figures 001 and 003.

Archive: `C:/Projects/waterflex-scheduler-java/experiments/runs/20260930T135624Z-booking-comparison-a752ef8e`.

Analysis: `analysis/20260930T154451Z-5a2c0070` within that archive. Measured revision: `e9c01d132f3b99fa03611ee889b7012e8846e3aa`. The measured branch includes reliability changes not present in the report branch's main base; the report intentionally analyzes the recorded revision rather than presenting current main as the measured software.

360 expected cases reconcile with 360 raw files and selected analysis inputs. All 360 raw hashes were verified. There are 60 comparable sequential pairs and 120 concurrent pairs excluded because their served customer identities differ. The compact extract is a review aid, not a replacement for the preserved raw archive.

Paid minutes sum audited policy workload entries. An explicitly empty workload list contributes zero only when the audit also records zero confirmed and reserved visits, road, waiting, buffers, rounding and cost. This derived empty-schedule value is not a default for missing data. Missing or null workload entries remain unavailable. Paid totals independently reconcile to 30 minutes per confirmed visit plus road, buffer, rounding and waiting components throughout this archive.

## Reproduce

Do not start benchmarks or use the benchmark database. The user authorized building this completed booking report while the daily experiment continues. Its isolated renderer used networking disabled, a 0.5 CPU limit and a 1 GB memory limit. Heavy local repository builds remain deferred until the daily experiment finishes naturally. Check both the daily launcher process and the active archive before those checks; process absence alone after a crash does not prove natural completion.

Using the bundled Codex Python runtime with the versions in `requirements.txt`:

```powershell
$Report = 'docs/booking-experiment-explainer'
& $Python "$Report/extract_evidence.py" 'C:/Projects/waterflex-scheduler-java/experiments/runs/20260930T135624Z-booking-comparison-a752ef8e'
& $Python -m unittest discover -s $Report -p test_report.py -v
& $Python "$Report/build_report.py"
& $Python "$Report/verify_report.py"
```

For the initial creation, run the documents skill's `container_tools/mark_artifact_operation_started.mjs --operation-kind create --expected-output-count 1 --output-format docx` once immediately before authoring. Chart creation requires Calibri on Windows or Carlito on Linux. The build performs no network access and uses stable DOCX ZIP timestamps; byte reproducibility should be checked by building twice in the same environment.

Render using the documents skill's `render_docx.py` and inspect every PNG. The existing `waterflex-explainer-qa:20260929` Docker image contains LibreOffice, Poppler and Carlito for this Windows host; reuse it with networking disabled and report inputs mounted read-only. Keep PDF and page PNGs in ignored `artifacts/booking-experiment-explainer`, never as additional deliverables.

## Validation status

Completed: 360-case evidence reconciliation, 12 report regression tests, report verifier, identical-byte rebuild, and visual inspection of all 18 final pages. The verifier checks pairing exclusions, denominators, missingness, arithmetic, source figures, document structure and absence of em dashes. Internal QA artifacts are in `artifacts/booking-experiment-explainer/qa-3` and are not deliverables.

Final Word SHA-256: `ac9727b5c4452ab67f051038b63bd45488e1920e7e4ccf19f241af188e7ad61b`.

Java nullability and relevant unit gates plus web lint/typecheck/unit gates were not run locally while the daily experiment remained active. Consult the PR for actual remote CI status. No integration or benchmark database checks were run. No scheduler behavior changed. Check `gh auth status` before pushing and opening the detailed PR, and leave it open for review.
