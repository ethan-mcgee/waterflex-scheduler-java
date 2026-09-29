# Scheduler implementation progress

Baseline: `c28a24c4c0c1b4614b442c7a362d205cfa7e0db3`.

- [x] 1. Inspect checkout, project instructions, and baseline. Checkout was clean; preserve local Compose configuration.
- [x] 2. Implement single-offer, four-hour, zero-overtime policies, legacy commitment protection, and overflow.
- [x] 3. Implement durable search start/status/cancel, worker ownership, publication safeguards, and browser reconnect.
- [x] 4. Implement experimental search variants and computational-work instrumentation.
- [x] 5. Build independent exact oracle and field-scenario suite, including booking-order permutations.
- [ ] 6. Run strict correctness, nullability, frontend, schema, integration/UI gates and staged benchmarks.
- [ ] 7. Generate evidence-backed Markdown/HTML reports, push branch, and open detailed PR for review.

Current steps: 6 and 7. The independent oracle validates 180 flagship steps, 24 companion records, and three rejection fixtures. Strict Java verification (175 tests), 85 frontend units, all 35 browser regressions, schema and local integration gates pass. All 240 daily-solver experiments are complete. Final real-road recovery and browser latency runs are finishing. The original failed high-concurrency audit and its database snapshot are retained; a fresh audit found zero overtime and intact customer promises. The report generator verifies raw totals and hashes. No experimental algorithm has been promoted. Draft PR: https://github.com/ethan-mcgee/waterflex-scheduler-java/pull/38.
