# Timefold audit package

Read [the canonical Markdown audit](Timefold_Audit.md) or [the Word edition](Timefold_Audit.docx). The expanded review covers booking/daily algorithms, computation, stateless service readiness, TF01-TF11 reassessment and three additional optimization opportunities (TF12-TF14). It changes no production behavior or business policy.

## Baselines and sources

Original baseline: `164a1e111370d21ea93f634e674c5fdee4edc90f`. Expanded production baseline: `afa035ddf2101d26c12508067d2d9016601db6c3`, refreshed main at authoring. Branch: `codex/timefold-audit-expanded`. The original checkout's unrelated AGENTS.md change was preserved.

The primary source is the user-supplied `Timefold-Solver-Docs.md` under `C:/Users/mcgee/Documents/Codex/2026-10-02/c-users-mcgee-downloads-timefold-solver/outputs/`. SHA-256: `832e78aecbdce7a57497953029fdc5efcb7879e7e256b35377829db4d274da7d`. Neither it nor its assets was changed. The coverage register records 66 chapters, section lines, 122 image references, 113 unique asset hashes, eight inspected diagrams, applicability and 60 retrieved official guide URLs. The local/official guidance identifies 2.7.0; installed solver is 2.6.0. Complete coverage means this inventoried Solver guide, not all APIs, old versions, external blogs, Platform or managed-model manuals. Exact examples were spot-checked, not all compiled.

## Evidence boundaries

`evidence/summary.json` and original logs are unchanged historical records. The verifier checks original authoring hashes against their retained Git revision and original raw logs against current files. `evidence/second-review/manifest.json` hashes the delivered expanded artifacts. It excludes itself and its final verification log to avoid circular hashing. Text hashes normalize CRLF; raw logs and binary artifacts do not.

`second-review/retained-evidence.json` identifies measured revisions, configuration, selected raw hashes and metadata receipts. The current review validated 360 booking cases, 1,280 daily-budget cases, and two 24-case contention runs, including frozen artifact hashes. Only 60 booking pairs match served customer identities; 120 concurrent pairs do not. Contention revisions differ, precluding a clean causal concurrency claim. No new measurement was run. Original failed/interrupted campaigns remain unchanged. Two initial helper schema assumptions failed and were corrected; their logs are retained and distinct from experiment failures.

Fresh Java verification: seven existing `TimefoldAuditEvidenceTest` cases passed with nullability at the new baseline. They characterize unsafe behavior, not desired contracts. Four helper boundary tests check malformed evidence, hash changes and Word omission/reordering. Full original Java/portal gates are historical; no Java/TypeScript changed in this update. PR CI provides its own current result.

## Reproduce

Use JDK 25 and Python with python-docx and lxml, without Python `-O`. From the repository root:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-25'
.\mvnw.cmd -Pnullability -pl scheduler-service '-Dtest=TimefoldAuditEvidenceTest' test --batch-mode --no-transfer-progress
python -m unittest discover -s docs/timefold-audit -p test_verification.py -v
python docs/timefold-audit/verify_evidence.py --external
```

`--external` requires the original local guide/assets path. Omit it for portable repository-only verification. To recheck retained experiments without launching measurements or editing archives:

```powershell
python docs/timefold-audit/collect_retained_evidence.py --archive-root C:/Projects/waterflex-scheduler-java/experiments/runs
```

This regenerates the compact second-review evidence JSON, not the archives. Inspect differences and refresh the delivered manifest only after verification and review.

## Word regeneration and QA

`python docs/timefold-audit/build_report.py` regenerates the same Word companion with Calibri, pale blue tables and no em dashes. `verify_evidence.py` checks exact ordered paragraph/table-cell text and links, not just substring presence. DOCX ZIP timestamps can change its hash; the manifest identifies the reviewed delivered bytes.

The packaged renderer was attempted but LibreOffice was unavailable. Microsoft Word exported the report to PDF and bundled Poppler rendered pages. See `evidence/second-review/visual-qa.json` for the final page-by-page receipt. PDF and page images remain ignored under `qa/`. Re-render and inspect every page after content/style changes before updating artifact hashes.

Historical log verification uses `second-review/historical-line-endings.json`: all 14 original Git blobs have LF endings, while the original manifest describes CRLF output. Both byte identities are retained; reconstructing CRLF reproduces every original hash and size exactly. No historical file or manifest was rewritten.
