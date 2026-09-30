# Reliable experiment validation

This is implementation validation on a shared Windows workstation, not a solver performance comparison. No full daily or booking performance matrix was executed. Original archives remain unchanged, including the 413 completed cases and failed attempt in `20260930T051515Z-booking-comparison-837d75f2`.

## Scope

The implementation fixes transactional fixture cleanup, maintenance isolation, frozen booking calendars, process ownership, resume and evidence validation. Production schedule defaults remain Chicago 02:00 daily optimization and Sunday 03:30 routing-cache cleanup. No migration was added and reliability commits do not modify the experiment JSON files.

The checkout's pre-existing user configuration commit `0239901` defines 640 daily cases with 48,000 seconds of search allowance and 360 booking cases. The planning snapshot's 1,440 daily / 133,200-second and 720 booking expansions are separately covered by regression tests. Configurations have not been restored or silently expanded.

## Automated gates

- Java 25 nullability `clean verify`: 180 scheduler tests and 2 routing tests passed.
- Python toolkit: 39 tests, with the POSIX process-group test skipped on Windows.
- Portal lint/typecheck and 102 unit tests passed.
- PostgreSQL cleanup regression: optimization run and changes removed, unrelated metro preserved, and a late restrictive foreign key rolled the complete cleanup back.
- PostgreSQL snapshot/calendar: 2 tests passed, including historical service dates under a fixed calendar, real capture timestamps, successful confirmation, and real-time hold expiry.
- PostgreSQL durable-worker: cancellation, expired leases, restart recovery and idempotent publication passed.
- PostgreSQL database admission and time-off state: 2 tests passed.
- CI now includes the cleanup and fixed-calendar database regressions in dedicated schemas. Unit gates cover midnight, Chicago 06:00, weekends, both DST transitions, complete normal/overflow snapshot validation, routing copies, real monotonic deadlines, production cron defaults and disabled scheduled maintenance boundaries.

## Targeted executable matrices

| Validation | Cases | Status |
| --- | ---: | --- |
| Daily short: all four solvers, fleets 5/10/20/50, CLUSTERED/DISPERSED/SPARSE, seed 83, 100 ms | 48 | Passed; complete report |
| Daily long: each solver, fleet 50, DISPERSED, seed 83, 240 seconds | 4 | Passed; complete report and interruption/resume hash checks |
| Failed booking reproduction: BOUNDED, fleet 20, DISPERSED, seed 83, concurrency 1, cold, 10 requests | 1 | Passed; 10/10 served, no incomplete searches or promise violations |
| Booking coverage: both solvers, fleet 50, all three workloads, both caches, concurrency 1/10, seed 83, 10 requests | 24 | Passed; complete report and interruption/resume hash checks |

Daily measurements use independent synthetic directed fixtures. Booking measurements use the ready local road provider, per-attempt `benchmark_` schemas, independently audited schedules, and explicit effective-setting checks. Cold cache clears scheduler caches, not the shared road provider's caches. Latencies and solver costs here are validation observations and do not establish production savings.

The booking coverage matrix served 191/240 requests and retained 49 `SEARCH_INCOMPLETE` outcomes, all at concurrency 10. Both solvers served 60/60 at concurrency 1; at concurrency 10, INSERTION served 35/60 and BOUNDED 36/60. All 24 case audits reported zero promise violations. These are recorded outcomes, not harness failures or evidence of infeasibility. No retries were added to conceal incomplete searches.

CI run [36719360452](https://github.com/ethan-mcgee/waterflex-scheduler-java/actions/runs/36719360452), attempt 2, passed build/unit, booking/optimizer integration, and browser regressions on source/documentation commit `78a6066`. Attempt 1 reported a startup failure with zero jobs and no annotations; an explicit rerun on identical source started normally. Local actionlint also passed. Final evidence-only commits may trigger a separate CI run.

## Recovery and provenance

The runner is deliberately interrupted after one completed result while the next owned process is active. Completed receipt/raw hashes are saved before interruption. On resume the unfinished attempt must be retained, marked interrupted, and retried in a new directory; the completed result must remain byte-for-byte unchanged. Complete reports are required at the end of each targeted matrix.

Full local archives remain under `experiments/runs/`, with frozen executable/source dependencies and per-attempt metadata/logs. The [evidence index](evidence/reliable-experiments-validation-2026-09-30/index.json) retains source manifests, resolved configs, raw JSONL, completion/interruption receipts, attempt logs, gate logs, report summaries/CSVs and representative figures. All archived compressed and uncompressed hashes, 77 completed raw/receipt pairs and both interrupted attempts were verified. Full HTML reports and all figures remain in the local archives listed in the index. Historic results retain their original semantics and cannot be treated as the new isolated comparison.


## Reproduce the targeted matrices

Use the normal launcher prerequisites described in [configurable experiments](configurable-experiments.md). These commands create new validation archives from the current committed source and do not alter retained evidence:

```powershell
py -3 run_experiment.py daily full --config docs/evidence/reliable-experiments-validation-2026-09-30/daily-short/config.json
py -3 run_experiment.py daily full --config docs/evidence/reliable-experiments-validation-2026-09-30/daily-long/config.json
py -3 run_experiment.py booking full --config docs/evidence/reliable-experiments-validation-2026-09-30/booking-failed/config.json
py -3 run_experiment.py booking full --config docs/evidence/reliable-experiments-validation-2026-09-30/booking-coverage/config.json
```

The explicit 240-second matrix takes roughly sixteen minutes plus preparation. The recovery evidence documents an additional deliberately interrupted attempt; it was not counted as a completed measurement. Each archive records the exact frozen revision used, and the final CI run validates the combined implementation. Tests simulate calendar boundaries rather than waiting for a real Chicago midnight or DST transition. Workstation load, road-provider caches and single-seed coverage remain limits; these results do not replace a full performance study.
