# Scheduling policy, reservations and operations

This describes the implemented consolidated policy. Release acceptance and measured limitations are tracked separately in [the original-plan checklist](scheduler-plan-checklist.md), [booking benchmarks](booking-benchmarks.md) and [solver experiments](solver-benchmarks.md). The dispatcher still previews and manually applies optimization proposals. No same-day replanning, paid service migration or automatic dispatch apply is introduced.

## Customer promises and overtime

Bookings retain two-hour arrival windows, ten-minute offer expiry, the existing booking horizon and the service-date cutoff of **06:00 America/Chicago**. Qualifications, approved absences, technician paid/overtime limits, date-effective depot assignments and departure/return endpoint policies are hard constraints. Invalid or missing required scheduling facts fail explicitly.

A regular offer adds no overtime relative to the common reservation baseline. A day that already contains overtime can therefore accept another regular offer. Distinct choices are counted by service date and window start/end across the complete horizon before limiting the response to four offers. Several technicians serving one window do not create several choices.

New overtime authorization requires both at most two distinct regular windows and at least 90 percent confirmed utilization, after the prescribed insertion and bounded neighborhood search completes. Eligibility is service qualification plus the effective metro assignment on each date. Regular capacity is the smaller of the daily paid limit and regular working minutes after the union of approved absences. Zero-capacity dates are excluded. Consumed capacity includes confirmed work for every service and is capped per technician/date before aggregation. Active holds constrain feasibility but are not confirmed demand; a refresh excludes the requesting job's old alternatives.

Deadline exhaustion, routing failures and incomplete snapshots cannot establish scarcity. Completed bounded search describes the configured neighborhood, not proof of global infeasibility. Regular choices are retained first; authorized overtime only fills remaining positions up to four. Authorization is persisted with an offer until expiry. A later demand drop alone does not revoke it, but confirmation still checks feasibility, reservation compatibility and technician limits.

## Cost, workload and acceptance

The objective order is hard feasibility and reservation protection, minimum found overtime, minimum found operating cost at that overtime, then lower unfairness within `floor(referenceCost * 1.02)`. Booking uses incremental cost against a consistent reservation baseline; daily optimization uses total daily cost. Zero or negative incremental reference cost gets no positive allowance.

Unfairness is decimal capacity-weighted variance of paid workload divided by available regular capacity. Paid workload includes service, directed travel and paid waiting. Idle technicians qualified for at least one service in the day's demand remain in the comparison. Sibling offers do not count as additional customer jobs. Reports include each technician's workload/capacity/utilization and maximum utilization. Fairness cannot relax skills or availability.

Every arrangement uses canonical timing that minimizes overtime, modeled operating cost and avoidable waiting, with the earliest equivalent departure. Forward and backward timing bounds respect exclusive arrival-window ends, returns and absence-separated intervals. Fairness never adds artificial waiting. Each working segment's departure and return are persisted and used by dispatch geometry and timelines.

Cost remains integer cents rounded once after aggregating regular paid minutes, overtime paid minutes and road mileage. It is modeled operating cost, not customer price or a claim about actual payroll. Road seconds, configured buffer seconds and whole-minute rounding are reported separately; buffers are unchanged. Remaining candidate ties use lower cost, fewer changed existing assignments, earlier customer window, technician ID and route order.

Ordinary preview and locked apply use the same policy comparison. They reject overtime increases, require independently feasible validated arrangements, and retain the baseline unless an admissible overtime, cost or fairness improvement exists. A cheaper proposal does not displace a fairer baseline that is inside the cost allowance. Repair may request additional overtime to preserve existing appointments, but labels it explicitly and requires dispatcher approval of the reviewed proposal. Limits remain hard.

## Search and reservation lifecycle

Search loads immutable full-horizon facts, route baselines, effective intervals, configuration, schedule/reservation versions and routing identity. Candidate and solver move scoring perform no database or network operations. Sparse routing is fetched between search phases. The regular search starts with insertion, then uses same-date relocation, pair swaps and reversal when scarcity needs further investigation. Defaults are six promising routes including least-utilized eligible capacity, depth two, beam eight and 500 arrangements per window. Work proceeds in horizon rounds. Optional fairness refinement after abundant insertion choices has a separate 250 ms cooperative limit within the original deadline.

A persisted metro/date arrangement supports confirmed appointments and every active conservative hold placeholder together. Offers can depend on pending reorder/reassignment without immediately moving confirmed appointments. Dependencies protect technicians, services and dated endpoints needed by that common arrangement. Ordinary batch optimization remains blocked by relevant holds.

Confirmation locks current arrangements and versions in consistent order, replaces the chosen placeholder, releases siblings, independently validates confirmed work and remaining holds, and atomically writes required assignments, route order, segment times and version increments. Repeated confirmation returns the existing appointment. A stale snapshot retries at most once within the original search budget.

Refresh, release, expiry, cancellation and test purges also validate the remaining arrangement. Removing a stop can be unsafe with directed nonmetric travel, so removal is not treated as automatic proof of feasibility. An unsafe mutation is rejected without partially changing the protected arrangement. Background expiry rotates through failures for retry and yields to customer demand. Persisted strict JSON, relational dependencies and version checks retain guarantees across process restarts and rollout-flag changes.

The five-second search budget starts with a validated job/address. Exploratory work ends by four seconds, leaving the final second for independent validation and publication. Queueing, routing waits, pool admission, SQL locks/statements and commit acknowledgement share the deadline. Expensive work is admitted at two searches per instance, or one on a single available CPU, with a queue of at most 16 and one background optimizer. Bookings have priority over queued background work. Snapshots and solvers are never shared mutably.

Durable search IDs propagate cancellation across scheduler instances. Publication checks cancellation under locks; acknowledgement distinguishes delivered offers from abandoned responses. An abandoned search cannot later publish a usable reservation. An uncertain commit acknowledgement requires the durable cancellation/cleanup path, rather than assuming a transport timeout proves rollback.

## Interfaces and diagnostics

Existing booking and optimization endpoints are retained. Booking responses include `search.outcome`, prescribed-search completion, elapsed time, retryability and offers. Outcomes distinguish `AVAILABLE`, `SEARCH_INCOMPLETE`, `NO_CANDIDATE_FOUND`, `ROUTING_UNAVAILABLE`, `SCHEDULE_CONFLICT` and `SERVICE_BUSY`. A defined-search miss is not global proof of no capacity. Initial booking, refresh, selection conflicts and the testing page handle these outcomes. Customer inputs remain in place on failure, with retry and an accessible indeterminate progress bar labeled "Finding available appointments".

Internal diagnostics include distinct windows, confirmed utilization, overtime authorization, limits, coverage, pruning, candidates/moves, insertion/rearrangement origin, stop reason, versions, snapshot age, routing/cache measurements, queue time and database/CPU measurements. Locking-statement duration bounds lock wait but does not isolate it. Foreground CPU excludes shared routing workers; provider HTTP counters and process/heap statistics are available only in the guarded benchmark profile. Historical unavailable metrics remain unavailable rather than becoming fabricated zeroes.

Optimization responses retain monetary meanings and add workload/utilization, variance, maximum utilization, overtime, signed cost change, reference cost, cost ceiling, acceptance reason and segment timings. Solver provenance records the configuration XML/fingerprint, fixed seed, phase budgets, steps/moves, time to best and termination. Current configuration uses a ten-second overtime/cost reference phase and the remaining portion of fifteen seconds for fairness. Unused reference time transfers to fairness. No previous solution is reused across changed provenance.

Routing retains the full-matrix endpoint and adds explicit directed sparse pairs with expected identity and per-pair routability. Only missing coordinate/identity pairs are requested; persistent cache operations are batched and concurrent missing-leg work is coalesced. One caller's deadline does not cancel another caller's result. Unreachable roads, provider unavailability and malformed responses remain distinct. There is no straight-line fallback.

## Configuration and rollout

Policy defaults are persisted settings and included in configuration fingerprints:

| Setting | Default |
| --- | --- |
| `regular_window_threshold` | 2 |
| `confirmed_utilization_threshold` | 0.90 |
| `fairness_cost_allowance` | 0.02 |
| `booking_deadline_ms` | 5000 |

Spring properties can be supplied as command-line arguments or their standard environment-variable equivalents:

| Property | Default / purpose |
| --- | --- |
| `booking.reservations.enabled` | false; issue new common-arrangement offers |
| `booking.search.bounded` | false; enable bounded booking rearrangement |
| `booking.search.refinement-ms` | 250; optional abundant-capacity refinement, range 0..1000 |
| `scheduler.search.capacity` | 0 selects CPU-aware one/two default |
| `scheduler.search.queue-limit` | 16; bounded queue |
| `routing.prewarm.enabled` | false; low-priority versioned cache work |
| `routing.ch.enabled` | false; separate fixed-car prepared graph artifact |
| `spring.datasource.hikari.connection-timeout` | 250 ms |
| `spring.datasource.hikari.validation-timeout` | 250 ms |

Keep rollout disabled until the relevant correctness, latency and quality gates pass. With new issuance disabled, the fallback cannot authorize new overtime from an incomplete scarcity search. Already-issued managed offers still use the durable confirmation/release path. Start with the default admission capacity and benchmark before raising it. Budget database connections across scheduler instances, portals and benchmark clients; application search admission alone does not bound every instance's idle pool.

The eight additive September 24 migrations cover policy/default validation, offer overtime authorization, repair approval, common reservation arrangements/dependencies, reservation obligations, diagnostics, current segment timing and durable cancellation. Apply all migrations before starting the new binaries. Historical timing/policy/solver fields are nullable and displayed as unavailable. Legacy optimization proposals require a fresh preview before apply.

Rollback new booking search by disabling bounded search and new common-arrangement issuance while retaining this version's reservation lifecycle support until all issued offers are confirmed, released or safely expired. Do not delete reservation tables/dependencies or roll back to a binary that cannot honor outstanding arrangements. A configuration or graph change invalidates stale search/preview provenance and requires a fresh validated search or preview. CH uses a separate graph directory and identity; disabling it returns to the original graph artifact. Do not switch routing identity during active reservation experiments and treat outstanding reservations as a rollout guard.

See [benchmark provenance and limitations](booking-benchmarks.md) before interpreting improvements. Experimental ablation artifacts require local isolated `waterflex_test` schemas and must never be deployed. No production data, travel buffers or paid-service dependencies are changed by the verification workflow.
