# Booking benchmark protocol

`npm run benchmark:scheduling` extends the sequential booking workflow's seeded selection and scheduling client with isolated fleet datasets and concurrent request rounds. It measures search HTTP time including cancellation/acknowledgement, separately from subsequent customer selection. It does not include address entry, geocoding or browser transport. Do not label these samples browser-visible end-to-end latency.

Use a freshly migrated, unseeded `waterflex_test` schema whose name begins with `benchmark_`. The runner refuses an existing metro because overlapping service areas would make comparisons ambiguous. Each successful case is independently audited and recorded before deleting only that case's explicitly prefixed fixture rows. Failed cases remain for inspection. Output is JSONL with create-new semantics. It includes exact server revision and configuration-stage label, dataset hashes, seed, dates, hardware, every request outcome, served demand, incompletes, p50/p95/p99, assignment changes, retiming, and before/after independent workload, overtime, modeled cost, waiting and road/buffer metrics.

Launch the scheduler with `--spring.profiles.active=benchmark`, the dedicated JDBC schema and chosen routing provider. This installs only the read-only audit and cache experiment endpoints. Both reject databases other than `waterflex_test`; cache reset additionally requires a `benchmark_` schema. The audit independently validates confirmed routes and the common active-reservation arrangement, and rejects changes during measurement. Run cache experiments only on a dedicated idle benchmark instance. The runner resets scheduler memory/persistent road cache per case and optionally prewarms the pinned coordinate matrix. Routing-provider cache configuration and graph identity must also be recorded.

Required environment variables are `DATABASE_URL`, `ENGINE_URL`, `BENCHMARK_REVISION`, `BENCHMARK_VARIANT`, and a new `BENCHMARK_OUTPUT` path. Optional controls:

| Variable | Default |
| --- | --- |
| BENCHMARK_SIZES | 20,30,50 |
| BENCHMARK_WORKLOADS | SPARSE,CLUSTERED,DISPERSED,MIXED_SKILL,TIGHT_WINDOW,ABSENCE,NEAR_CAPACITY |
| BENCHMARK_CONCURRENCY | 1,5,10 |
| BENCHMARK_CACHES | cold,warm |
| BENCHMARK_REQUESTS | 30 per case; accepted range 10..200 |
| BENCHMARK_SEED | 17 |

Datasets preserve two-hour promises, existing buffers, dated home endpoints, skills and approved absences. The near-capacity fixture must independently measure at least 90 percent regular utilization before any requests. Sparse cases start empty. Confirmed appointments are deliberately co-located with each assigned technician to establish a feasible reproducible baseline; dispersed request pins span the known Omaha locations. These fixtures do not cover the full diversity of dispersed multi-stop routes and require complementary solver and real-road cases. Fleet size or low latency alone is not acceptance evidence.

The harness requires the audit-capable revision. Earlier implementation stages need equivalent independent auditing and their original endpoint adapters before they can be included as ablations. A variant label is provenance, not a feature toggle. Do not claim six-stage ablation coverage from differently labeled runs of the same binary. Also retain original routing identity, enabled flags, exact jar hashes, CPU/memory and database/queue/lock instrumentation when collecting acceptance evidence. Those broader gates remain open until actual results are recorded.
