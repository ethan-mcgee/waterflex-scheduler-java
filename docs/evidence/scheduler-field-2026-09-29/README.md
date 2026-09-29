# Retained scheduler experiments

The generated [report](../../scheduler-field-validation.md) explains decisions, exclusions, and limitations. `summary.json` contains numerical summaries and SHA-256 checksums. Rebuild and verify it with:

```powershell
python infra/report-field-validation.py --require-complete
```

- `experiment-manifest.json`: original revision, frozen artifact identities, policies, limits, expected matrix, and source/class fingerprints.
- `booking-*-original.jsonl.gz`: unchanged original-policy comparison.
- `booking-*-screen.jsonl.gz`: five search variants on development seeds.
- `booking-*-held*.jsonl.gz`: dispersed real-road cases on held-out seeds, including the separately identified recovery run.
- `booking-*-stress.jsonl.gz`: capacity pressure, delayed confirmation, and abandonment.
- `booking-*-browser.jsonl.gz`: Chromium through an isolated production-built portal, durable start/poll, and API confirmation.
- `screen-15.jsonl.gz`, `held-{15,30,60}-{59,83}.jsonl.gz`: all eight daily solvers with exact configuration and phase diagnostics. These use deterministic fixture roads.
- `field-*.json`: independent exact-case oracle results, directed roads, service facts, promises, planned times, endpoints, and rejection fixtures.
- `validation.json` and `gates/`: executed gates and compressed logs.
- `failed-shared-83-*`: original failed final audit, the fixture-only PostgreSQL snapshot preserved before restart, a fresh route audit, and investigation. The failure remains a failure; its missing client timings were not invented.

JSONL starts with provenance and then records cases. Gzip preserves original bytes. A `failure` or `audit_failure` record is not a validated case. Terminal incomplete searches remain in served-demand denominators. Paired costs exclude cases with different served customer identities. The report labels the separately repeated matrix cell; the original failed attempt remains retained.

All data here is synthetic or test-fixture data. Experiments use isolated schemas in `waterflex_test`. These artifacts do not demonstrate production savings and do not authorize deploying or merging the PR.
