# Stateless API service report: sources and methods

## Sources

The report is built from the documents that define and describe the service, read at build time:

| Source | What the report takes from it |
| --- | --- |
| `docs/api/openapi-v1.yaml` | The contract version, every operation (method, path, operationId, summary), the `Problem.error` enum, the object schema list, and the version history in `info.description` |
| `docs/stateless-api-design.md` | The endpoint table, the contract change table, the thin store table, the "What is stored" lifetimes, the route cache tiers; the narrative's decisions and mechanisms |
| `docs/integration-guide.md` | The ownership table, the error and remedy table, the booking and proposal sequences, the `lastModified` rules, the go-live checklist |
| `docs/operations-runbook.md` | The settings table and the operator's signals |
| `infra/host-conformance/src/cases.ts` | The names of the conformance cases |
| The repository's merged pull requests, listed on 2026-10-14 | The pull request table and the phase timeline; typed into `report_data.py` with that note, since the list is not a file in the repository |

## Validation

`report_data.py` asserts, before anything is read: every operation is marked `implemented`; the first version in the history equals `info.version`; the counts of operations (12), error codes (10) and conformance cases (27); and the row counts of the parsed markdown tables (10 endpoints, 27 contract changes, 11 error rows, 4 cache tiers). A table that fails to parse stops the build.

`verify_report.py` parses the same files again with its own markdown table reader and compares 12 document-derived tables cell for cell: operations, ownership, errors, thin store tables, lifetimes, cache tiers, settings, conformance cases, contract changes, pull requests, schemas and endpoints. It checks that every error code of the spec's enum appears in the guide's table, that the phase table's pull request lists and dates follow from the pull request list, that the counts quoted in the narrative equal the parsed counts, that the chart data matches the sources, and that the template's styles, numbering, theme, fonts and footer are byte-identical to the reference. It confirms that no em or en dash appears in the document XML.

## Interpretation limits

- The report describes the service as the documents describe it on 2026-10-14, at contract version 1.4.1. A later contract version changes the spec's history and the design document's change table; rebuilding the report picks both up, and the verifier fails if the narrative's counts no longer match.
- Performance claims are left to `docs/performance-work-2026-10-14`; this report only points to it.
- Where the design document marks a decision "Decided", the report states it as such; open questions are listed as open in section 14.

## Rebuild

```text
python -m pip install -r docs/stateless-api-service-2026-10-14/requirements.txt
python docs/stateless-api-service-2026-10-14/report_data.py
python docs/stateless-api-service-2026-10-14/build_report.py
python docs/stateless-api-service-2026-10-14/verify_report.py
```

Calibri on Windows or Carlito on Linux is required for the figures. Render every page after changes; LibreOffice and PyMuPDF were used for review.
