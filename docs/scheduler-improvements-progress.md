# Scheduler implementation progress

Baseline: `c28a24c4c0c1b4614b442c7a362d205cfa7e0db3`.

- [x] 1. Inspect checkout, project instructions, and baseline. Checkout was clean; preserve local Compose configuration.
- [x] 2. Implement single-offer, four-hour, zero-overtime policies, legacy commitment protection, and overflow.
- [x] 3. Implement durable search start/status/cancel, worker ownership, publication safeguards, and browser reconnect.
- [x] 4. Implement experimental search variants and computational-work instrumentation.
- [ ] 5. Build independent exact oracle and field-scenario suite, including booking-order permutations.
- [ ] 6. Run strict correctness, nullability, frontend, schema, integration/UI gates and staged benchmarks.
- [ ] 7. Generate evidence-backed Markdown/HTML reports, push branch, and open detailed PR for review.

Current steps: 5 and 6. The independent small-town oracle, all booking orders, promise-sensitive backtracking, and coordinated reassignment are implemented. Database ownership/cancellation and browser recovery tests pass. Expanded validation and benchmarks are running; no experimental algorithm has been promoted.
