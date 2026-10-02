# Timefold audit package

Read [the audit](Timefold_Audit.md) or its [Word edition](Timefold_Audit.docx). Both cover the experimental booking and daily solvers and the intended stateless dataset API. They contain eleven prioritized findings, acceptance criteria, a proposed ownership boundary and eighteen documentation references.

The audited production baseline is `164a1e111370d21ea93f634e674c5fdee4edc90f`, the user's local checkout when the audit began. Source links are pinned to that commit. During delivery, `origin/main` advanced to `4562993`; this is not a claim that the newer main branch was fully re-audited. The audit branch adds reports and characterization tests without modifying production behavior.

## Evidence and reproduction

`evidence/summary.json` records observed test counts, limitations and SHA-256 hashes. Raw logs preserve successful checks, initial disproved cold-start hypotheses and the experiment-toolkit Python dependency failure. Seven cases in `TimefoldAuditEvidenceTest` assert observed flaws, not desired contracts. Replace these assertions with safe-behavior regression tests when implementing fixes.

From the repository root, using an installed JDK 25:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-25'
.\mvnw.cmd -Pnullability -pl scheduler-service '-Dtest=TimefoldAuditEvidenceTest' test --batch-mode --no-transfer-progress
.\mvnw.cmd -Pnullability clean verify --batch-mode --no-transfer-progress
python -m unittest discover -s infra -p test_experiments.py -v
```

The Python experiment tests require their existing scientific dependencies, including matplotlib. Portal checks were run in `web` after `npm ci --ignore-scripts` and `npm run prisma:generate`: `npm run lint`, `npm run typecheck` and `npm run test:unit`.

## Report build and verification

Run `python docs/timefold-audit/build_report.py` with python-docx and lxml installed. The builder checks that Markdown paragraphs, table cells and hyperlink labels survive in the Word document. Run `python docs/timefold-audit/verify_evidence.py` to verify archived hashes and pinned code-link locations. Rebuilding the Word file can change ZIP metadata and therefore its hash; the manifest records the visually reviewed delivered artifact.

The packaged document renderer was unavailable because its LibreOffice runtime was absent; the failure log is retained. Microsoft Word exported the document to PDF, bundled Poppler rendered it, and every one of the ten pages was visually inspected. Temporary PDF and page images remain in ignored `qa/`. No database integration suite, browser integration suite, benchmark campaign or API load test was run for this audit.
