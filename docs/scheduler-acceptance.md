# Scheduler acceptance evidence

Implementation, local engineering gates and the planned benchmark comparisons are complete. This report records the scoped acceptance results and material tradeoffs against the [original requirement checklist](scheduler-plan-checklist.md). Final-head CI and review readiness are recorded on [PR #28](https://github.com/ethan-mcgee/waterflex-scheduler-java/pull/28). No production deployment or rollout enablement was performed.

## Final artifact and latency

The [final fixture report](evidence/booking-confirmation-final-2026-09-24.json) measures scheduler revision `43e9147a1cc2ae63cf2cb64fca772f9a530df39f`, jar SHA-256 `585705ad3851c11264b4a636b9d89923dce6f88d5ca8213a5e9dc5124e15dd52`. No scheduler or routing production source changed after that revision. It includes 126 cases and 3,780 requests across 20/30/50 technicians, all seven workloads, cold/warm caches and concurrency 1/5/10. Fixture routing identity is `ci-monaco-omaha-car-v2`.

| Measure | Result |
| --- | ---: |
| HTTP p50 / p95 / p99 | 1,624.4 / 3,975.5 / 4,150.3 ms |
| Largest case p95 | 4,352.0 ms |
| Cases exceeding five-second p95 | 0 / 126 |
| Maximum measured request / requests over five seconds | 4,525.5 ms / 0 |
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

The [actual Omaha browser matrix](evidence/booking-browser-final-2026-09-24.json) separately measured 3,780 searches at pooled p95 3,943.7 ms, largest case p95 4,387.8 ms, 2,396 served and 1,384 retryable outcomes, with zero independently audited violations. Its scheduler is `971b0b9` and portal is `0629445`; it is not relabeled as the final artifact. The later production fix concerns reserved-offer confirmation. Portal application/library code is unchanged from that measured portal revision, and [final-artifact real-road integration reruns](evidence/confirmation-verification-2026-09-24.json) passed. Its maximum measured request was 4,514 ms, with no request above five seconds. Browser timings cover validated-job refresh transport, excluding address entry, geocoding and rendering.

## Matched component comparisons

The [six-variant summary](evidence/booking-matched-acceptance-2026-09-24.json) verifies identical 126-case matrices, thirty-request streams, fixture fingerprints, horizon dates, seed and routing identity. Each source report preserves its own artifact and raw observations.

| Configuration | Served / 3,780 | Pooled p95 | Pooled p99 | Cases above five-second p95 |
| --- | ---: | ---: | ---: | ---: |
| Original with explicit connection rechecks | 3,752 | 52,619.6 ms | 72,970.4 ms | 125 |
| Policy/insertion, full matrices and full evaluation | 1,995 | 3,952.4 ms | 4,317.9 ms | 0 |
| Sparse routing and affected-route evaluation | 2,136 | 3,778.8 ms | 4,041.4 ms | 0 |
| Bounded search, early departures | 2,285 | 4,006.6 ms | 4,239.5 ms | 0 |
| Flexible departures, matching component source | 2,327 | 4,004.9 ms | 4,233.5 ms | 0 |
| Final artifact with later search and confirmation fixes | 2,331 | 3,975.5 ms | 4,150.3 ms | 0 |

These reconstructed component experiments retain shared immutable snapshot, reservation, deadline and independent-validation infrastructure. They isolate full versus sparse route materialization, full versus affected-route scoring, bounded moves and departure timing; they do not separately attribute every snapshot or safety-infrastructure refactor. The four component variants use four-connection pools; original and final instances use ten. All have 768 MiB heaps and shared workstation activity. No significance or production-throughput claim is inferred from small differences between pooled results.

The policy/insertion and sparse variants each serve 1,080/1,260 sequential requests, bounded early timing serves 1,228, flexible timing 1,245, and the final artifact 1,260. The older flexible artifact retains one selection conflict. For the 50-technician near-capacity cold sequential case, bounded early timing serves 10/30, flexible timing 24/30 and final 30/30. Insertion-only variants cannot establish overtime scarcity without the prescribed bounded pass. Their quick incomplete responses are not successful bookings.

Early-departure variants have different baseline paid waiting from canonical-timing variants. Their negative incremental modeled costs can reflect removing existing waiting through rearrangement; they are not revenue or directly comparable payroll savings. Source reports retain before/after cost, waiting, road/buffer seconds, workload, service delay and fairness for every case.

## Original baseline and matched demand

The [uncorrected original matrix](evidence/booking-original-assembled-2026-09-24.json) preserves all five declared source partitions, both transport timeouts and the earlier connection incident. It served 3,669/3,780 requests. The [separate connection-corrected comparison](evidence/booking-original-corrected-2026-09-24.json) replaces only four explicitly identified cases with matching clean rechecks and serves 3,752/3,780. Both have pooled p95 52,619.6 ms and p99 72,970.4 ms. The corrected result retains nine search errors and nineteen selection conflicts; legacy search completion remains unknown. One retained search error in the final continuation is a [documented original road-cache deadlock](evidence/booking-original-middle-diagnostics-2026-09-24.json), not a replacement candidate.

For the sequential subset, both the corrected original and final implementation serve all 1,260 requests:

| Sequential measure | Original | Final |
| --- | ---: | ---: |
| Largest case p95 | 72,963.9 ms | 1,995.0 ms |
| Incremental modeled operating cost | $23,200.94 | $19,033.00 |
| Added overtime | 1,800 minutes | 1,800 minutes |
| Mean daily capacity-weighted variance after booking | 0.00725836 | 0.01205339 |

This matched-demand comparison shows lower modeled cost and latency, but **does not show an overall booking fairness or overtime improvement**. Fairness is worse on this aggregate measure, consistent with the agreed priority placing operating cost before fairness outside the 2 percent allowance. The independently validated constructed cases and repeated daily solver experiments below establish the required feasible fairness/overtime improvements. No broad booking fairness gain is claimed. The lower total overtime in all final concurrent experiments also reflects fewer served requests and must not be called a matched-demand overtime saving.

Cost, overtime and variance remain available for every case in the source reports. Aggregate buffer seconds can decrease when rearrangement removes route legs; the per-leg configured buffer policy did not change. The timing model used by independent original audits is canonical, so this comparison does not recover historical payroll or original persisted waiting.

## Quality and observability

Constructed correctness oracles demonstrate useful regular capacity revealed by relocation and a pair swap that neither insertion nor single relocation finds. The independently validated FULL_ASSERT optimizer case reduces 60 overtime minutes to zero. Equal-capacity fairness improves at unchanged modeled cost in a separate fixture. Exact threshold, 2 percent ceiling, unequal capacity, scarce skill, absence, idle technician, nonmetric travel and no-artificial-waiting regressions pass. These are reproducible quality cases, not claimed fleet-wide percentage savings. In the final full fixture matrix, all six non-scarce workload groups add zero overtime; only near-capacity cases add overtime (2,520 minutes across the independent experiments). All 466 preparation observations authorizing overtime have completed the prescribed search, at most two distinct regular windows and at least 90 percent confirmed utilization. None of the 18 incomplete preparations or 23 deadline-stopped preparations authorizes overtime. Preparation observations can include retries and are not counts of confirmed jobs.

The [solver comparison](solver-benchmarks.md) retains 294 independently validated results and three seeds for the selected alternatives. Uncapped Tabu with relocation/swaps improves aggregate reference cost and accepted fairness against the retained alternatives in all three seeds. Those comparative datasets finish with zero overtime, so they cannot establish a comparative overtime reduction. Optional CH routing improves isolated road query latency but produces little booking throughput gain; CH and prewarming remain disabled.

The final fixture captures foreground database, cache, queue, lock-statement and CPU fields for all 3,780 requests. Provider counters record 539 directed-leg HTTP requests and 5,383 requested pairs. Preparation logs contain 4,826 observations: 1,291 completed, 3,512 stopped optional refinement, and 23 reached a deadline. Retries can produce multiple preparation records; 475 jobs have no preparation observation. Missing records are not fabricated. Optional refinement termination does not itself mean the prescribed scarcity pass was incomplete. Raw coverage, limits, candidates, moves and provenance remain in the compressed source.

Lock-statement duration includes execution and bounds lock wait; it is not an isolated contention measurement. Foreground CPU excludes shared workers, and heap-pool peaks are not resident memory. Assignment changes compare final technician IDs with the seeded appointments. Retiming counts any changed persisted planned start, including canonical timing normalization, while every customer date/window remains unchanged. These counts are not a thresholded measure of customer disruption. Cost uses modeled paid departure-to-return time; no payroll savings claim is made. Road seconds and configured buffers remain separate.

## Engineering and rollout

The [final verification receipt](evidence/confirmation-verification-2026-09-24.json) records 135 standard and strict-nullability unit tests, locked PostgreSQL confirmation metadata regression, reservation/cancellation lifecycle, optimizer, time-off, depot and sequential actual-road gates. Portal lint, typecheck, production build, schema contracts and 22 browser tests have passed, with older unchanged-component evidence retained at its actual revision. Final-head hosted CI is a delivery gate checked on the PR; older successful runs are not substituted for that check.

Apply the eight additive migrations before starting the new binaries. Keep the common-reservation and bounded-search issuance flags disabled until rollout is deliberately approved. Preserve this version's confirmation, release and expiry support for every outstanding managed offer when disabling new issuance. Never downgrade to a binary that cannot honor those arrangements. The [operations guide](scheduler-policy.md) documents configuration, historical metrics, manual optimizer apply and rollback boundaries. No production services, data, travel buffers or default booking rollout flags were changed during verification.
