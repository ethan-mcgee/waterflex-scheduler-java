# Scheduler acceptance evidence

Acceptance is still being reconciled with the complete original plan. The final production-artifact fixture matrix is complete; the matched component comparisons and unchanged original continuation are still running. This document does not authorize deployment or replace the [requirement checklist](scheduler-plan-checklist.md).

## Final artifact and latency

The [final fixture report](evidence/booking-confirmation-final-2026-09-24.json) measures scheduler revision `43e9147a1cc2ae63cf2cb64fca772f9a530df39f`, jar SHA-256 `585705ad3851c11264b4a636b9d89923dce6f88d5ca8213a5e9dc5124e15dd52`. No scheduler or routing production source changed after that revision. It includes 126 cases and 3,780 requests across 20/30/50 technicians, all seven workloads, cold/warm caches and concurrency 1/5/10. Fixture routing identity is `ci-monaco-omaha-car-v2`.

| Measure | Result |
| --- | ---: |
| HTTP p50 / p95 / p99 | 1,624.4 / 3,975.5 / 4,150.3 ms |
| Largest case p95 | 4,352.0 ms |
| Cases exceeding five-second p95 | 0 / 126 |
| Served | 2,331 / 3,780 |
| Retryable schedule conflict / busy / incomplete | 815 / 587 / 47 |
| Independently audited promise, reservation, qualification or limit violations | 0 |

| Simultaneous requests | Served | Largest case p95 |
| --- | ---: | ---: |
| 1 | 1,260 / 1,260 | 1,995.0 ms |
| 5 | 670 / 1,260 | 4,352.0 ms |
| 10 | 401 / 1,260 | 4,349.8 ms |

The local latency gate passes with served demand reported. Concurrent requests to the same schedules cause substantial reservation conflicts and admission pressure. These results do not establish production throughput, and increasing admission capacity is not justified by this experiment. The 50-technician near-capacity sequential cases each served 30/30 requests, with cold/warm p95 1,995.0/1,891.1 ms. Fast retry responses are never counted as served appointments.

The reference workstation is an AMD Ryzen 9 7900X, 24 logical processors and 67,870,916,608 bytes of RAM. The scheduler had a 768 MiB heap. Other isolated benchmarks shared this machine, PostgreSQL and the fixture provider. This is a documented local reference, not dedicated hardware or hosted production evidence.

The [actual Omaha browser matrix](evidence/booking-browser-final-2026-09-24.json) separately measured 3,780 searches at pooled p95 3,943.7 ms, largest case p95 4,387.8 ms, 2,396 served and 1,384 retryable outcomes, with zero independently audited violations. Its scheduler is `971b0b9` and portal is `0629445`; it is not relabeled as the final artifact. The later production fix concerns reserved-offer confirmation. Portal application/library code is unchanged from that measured portal revision, and [final-artifact real-road integration reruns](evidence/confirmation-verification-2026-09-24.json) passed. Browser timings cover validated-job refresh transport, excluding address entry, geocoding and rendering.

## Quality and observability

Constructed correctness oracles demonstrate useful regular capacity revealed by relocation and a pair swap that neither insertion nor single relocation finds. The independently validated FULL_ASSERT optimizer case reduces 60 overtime minutes to zero. Equal-capacity fairness improves at unchanged modeled cost in a separate fixture. Exact threshold, 2 percent ceiling, unequal capacity, scarce skill, absence, idle technician, nonmetric travel and no-artificial-waiting regressions pass. These are reproducible quality cases, not claimed fleet-wide percentage savings.

The [solver comparison](solver-benchmarks.md) retains 294 independently validated results and three seeds for the selected alternatives. Uncapped Tabu with relocation/swaps improves aggregate reference cost and accepted fairness against the retained alternatives in all three seeds. Those comparative datasets finish with zero overtime, so they cannot establish a comparative overtime reduction. Optional CH routing improves isolated road query latency but produces little booking throughput gain; CH and prewarming remain disabled.

The final fixture captures foreground database, cache, queue, lock-statement and CPU fields for all 3,780 requests. Provider counters record 539 directed-leg HTTP requests and 5,383 requested pairs. Preparation logs contain 4,826 observations: 1,291 completed, 3,512 stopped optional refinement, and 23 reached a deadline. Retries can produce multiple preparation records; 475 jobs have no preparation observation. Missing records are not fabricated. Optional refinement termination does not itself mean the prescribed scarcity pass was incomplete. Raw coverage, limits, candidates, moves and provenance remain in the compressed source.

Lock-statement duration includes execution and bounds lock wait; it is not an isolated contention measurement. Foreground CPU excludes shared workers, and heap-pool peaks are not resident memory. Assignment changes compare final technician IDs with the seeded appointments. Retiming counts any changed persisted planned start, including canonical timing normalization, while every customer date/window remains unchanged. These counts are not a thresholded measure of customer disruption. Cost uses modeled paid departure-to-return time; no payroll savings claim is made. Road seconds and configured buffers remain separate.

## Engineering and rollout

The [final verification receipt](evidence/confirmation-verification-2026-09-24.json) records 135 standard and strict-nullability unit tests, locked PostgreSQL confirmation metadata regression, reservation/cancellation lifecycle, optimizer, time-off, depot and sequential actual-road gates. Portal lint, typecheck, production build, schema contracts and 22 browser tests have passed, with older unchanged-component evidence retained at its actual revision. Final-head hosted CI remains a delivery gate.

Apply the eight additive migrations before starting the new binaries. Keep the common-reservation and bounded-search issuance flags disabled until rollout is deliberately approved. Preserve this version's confirmation, release and expiry support for every outstanding managed offer when disabling new issuance. Never downgrade to a binary that cannot honor those arrangements. The [operations guide](scheduler-policy.md) documents configuration, historical metrics, manual optimizer apply and rollback boundaries. No production services, data, travel buffers or default booking rollout flags were changed during verification.
