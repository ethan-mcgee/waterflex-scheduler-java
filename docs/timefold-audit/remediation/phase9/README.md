# Phase 9: policy diagnostics and overnight outcomes

## Policy behavior

Daily reference search still uses its original allowance and independent validation. Fairness preserves the chosen reference's overtime target. If that target is nonzero, the zero-overtime acceptance policy makes fairness acceptance impossible, so no fairness solver starts. Reference statistics remain recorded with `SKIPPED_REFERENCE_OVERTIME` and the measured target. An eligible zero-overtime reference still starts fairness when the original operation has search allowance. No additional solve or retry is introduced.

The 90-to-20-minute overtime example remains a diagnostic improvement with policy rejection. Typed diagnostics retain the searched candidate's coverage and overtime even when WaterFlex persists the unchanged baseline as the displayed schedule. A lower overtime total does not authorize application. The regression injects a controlled reference result using identical captured demand and directed roads, verifies the 90 and 20 minute evaluations, counts solver starts and checks rejection. It is contract evidence, not a performance measurement.

`solver_analysis.policy` contains a version, fairness disposition, decision, explicitly nullable measured reference/candidate overtime and the candidate's independent `DailyOutcome`, including unresolved identities. Historical reports can lack these diagnostics; no targets or failures are invented. Java saved-JSON validation, strict calculation wire decoding and the portal contract validate current fields. Optimization review displays the policy reason and unresolved candidate demand separately from the retained schedule.

Booking still ranks exact incremental cost first and fairness only breaks cost ties. Daily fairness can use the configured headroom above its complete reference cost ceiling. Both include return travel and independently validate promises and resources. Booking's bounded completion indicates only the prescribed search completed, not global infeasibility. Optional refinement is capped only for non-durable, completed insertion searches whose distinct regular windows exceed the configured threshold. Required refinement and durable work remain under their original overall clock. Overflow is attempted only after a complete result with no candidates. These gates do not grant overtime authorization and are unchanged by this phase.

## Durable overnight receipts

The existing 2 a.m. America/Chicago cron now belongs to `OvernightOptimization`. It preserves the existing eligible horizon and creates previews only. Each metro/date receives a new UUID attempt and explicit preview key. A short independent transaction records `ATTEMPTED` before calling the existing caller-owned preview operation. Another short transaction records `SUCCEEDED`, `SKIPPED`, `FAILED` or `CANCELLED`, with start/end timestamps, the run identity when available, actual result reason and structured failure code/class/stage. No schedule application occurs in this job.

Calculation failure, routing failure, solver transport failure, input conflict and deadline/cancellation remain distinct diagnostics. Deadline and cancellation share an explicit code because the existing exception does not distinguish them. Failures do not silently disappear and do not stop unaffected metro/date work. The existing daily attempt owns calculation/persistence fences; the overnight receipt references its explicit preview key. Failed work requires a new attempt, not an automatic retry.

`SUCCEEDED` means a preview was created with a required run identity. It does not mean the schedule was applied. Skipped, attempted and failed work can legitimately have no run identity.

If receipt creation fails, that day does not calculate without a receipt. If terminal receipt persistence fails, the original `ATTEMPTED` row remains visible and an error is logged with attempt/metro/date. The process counter records receipt-write failures. A process crash can leave an attempted row; it is not relabeled as a measured cancellation or completion. Receipt records intentionally retain metro identity without a foreign key so deleting current operational data does not erase historical failure evidence.

`GET /v1/optimize/overnight/attempts?metro_id=...&date=YYYY-MM-DD` returns up to 100 newest typed receipts for that metro/date. `GET /v1/optimize/overnight/metrics` returns durable counts by state, `totalAttempted`, unfinished attempts older than five minutes and receipt-write failures since process start. These JSON metrics are alertable by existing operational polling; this PR does not configure an external notification service. Alert on receipt-write failures, old unfinished attempts and increases in failed/cancelled outcomes. Counts are observed rows; zero counts mean no rows in that state.

Deploy the additive Prisma migration before the caller binary. It adds only the overnight receipt table and indexes. Keep it on rollback to preserve evidence; restoring the earlier binary restores the earlier cron behavior. Calculation mode remains embedded by default. Caller and remote solver should be deployed with matching diagnostic contracts. No dependency or solver configuration promotion is included.

## Verification and remaining gates

Regression coverage checks impossible versus eligible fairness starts, the rejected overtime example, malformed/null policy facts, benchmark cron isolation, actual failed preview persistence and unchanged appointments, typed overnight failure/cancellation receipts, skipped/succeeded outcomes and continuation to other dates. PostgreSQL tests run in the hosted integration job; local Docker Desktop remains unavailable. Local Java, nullability and portal results and intermediate failures are retained in the receipt. Hosted exact-head gates and owner review are required before this phase is delivered.

Steps 10-16 remain pending. No fixture evidence establishes production savings, fairness improvement frequency, runtime benefit, service capacity or remote promotion readiness.
