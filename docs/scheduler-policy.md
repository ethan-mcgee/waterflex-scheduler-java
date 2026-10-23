# Scheduling policy, reservations and operations

This is the scheduling policy the public `/api/v1` calculations apply. [The integration guide](integration-guide.md) describes the interface, and measured behavior is in [the API load results](api-load.md) and [solver experiments](solver-benchmarks.md). The dispatcher still previews and manually applies optimization proposals. No same-day replanning, paid service migration or automatic dispatch apply is introduced.

Booking always uses common reservation arrangements and the bounded rearrangement search (`booking.search.variant=BOUNDED`, the default); the rollout flags that once gated them were removed. No booking path authorizes new overtime (see below). Daily optimization policy operates independently of booking.

## Customer promises and overtime

Bookings retain two-hour arrival windows, ten-minute offer expiry, the existing booking horizon and the service-date cutoff of **06:00 America/Chicago**. Qualifications, approved absences, technician paid/overtime limits, date-effective depot assignments and departure/return endpoint policies are hard constraints. Invalid or missing required scheduling facts fail explicitly.

A regular offer adds no overtime relative to the common reservation baseline. A day that already contains overtime can therefore accept another regular offer. Distinct choices are counted by service date and window start/end across the complete horizon before limiting the response to four offers. Several technicians serving one window do not create several choices.

New overtime is never offered under policy `zero-overtime-four-hour-v2`. `SchedulingPolicy.authorizeOvertime` validates its utilization inputs and always returns false, whatever the window count, confirmed utilization or search completion; the prohibition survives algorithm rollback and saved settings. The `regular_window_threshold` and `confirmed_utilization_threshold` settings are still validated, fingerprinted and reported, but they do not authorize overtime. Allowing overtime offers again requires a separate business decision and a new policy version. The window threshold still decides when capacity is abundant enough for optional fairness refinement (more distinct regular windows than the threshold). Eligibility is service qualification plus the effective metro assignment on each date. Regular capacity is the smaller of the daily paid limit and regular working minutes after the union of approved absences. Zero-capacity dates are excluded. Consumed capacity includes confirmed work for every service and is capped per technician/date before aggregation. Active holds constrain feasibility but are not confirmed demand; a refresh excludes the requesting job's old alternatives.

Deadline exhaustion, routing failures and incomplete snapshots are reported as incomplete search, not as a lack of capacity. Completed bounded search describes the configured neighborhood, not proof of global infeasibility. Only regular choices are offered, up to four. Offers issued under earlier policy versions may carry a persisted overtime authorization; confirmation still checks feasibility, reservation compatibility and technician limits.

## Cost, workload and acceptance

Booking and daily optimization use different rules after hard feasibility and reservation protection.

Booking considers only candidates that add no overtime, then ranks them strictly by incremental operating cost against a consistent reservation baseline. Fairness only breaks exact cost ties, followed by window, technician and route order. Booking applies no cost allowance: a fairer candidate that costs one cent more is not preferred.

Daily optimization uses total daily cost. Its reference phase minimizes overtime, then cost. Its fairness phase keeps the reference overtime and permits cost up to `floor(referenceCost * (1 + fairness_cost_allowance))`, normally 2 percent above the cost found by the reference phase (not above the current schedule). Zero or negative reference cost gets no positive allowance.

Unfairness is decimal capacity-weighted variance of paid workload divided by available regular capacity. Paid workload includes service, directed travel and paid waiting. Idle technicians qualified for at least one service in the day's demand remain in the comparison. Sibling offers do not count as additional customer jobs. Reports include each technician's workload/capacity/utilization and maximum utilization. Fairness cannot relax skills or availability.

The fairness measure study (PR 187, `docs/solver-benchmarks.md`) measures candidates beside this policy, all experiment-only (`FairnessMeasures`) and none a client choice. Two compute the same variance more cheaply: in binary64 over the fleet, and from exact running sums updated per move. Four change what fairness means: the highest utilization, the spread between the highest and lowest, the capacity-weighted mean absolute deviation, and Timefold's load balance over utilization. Each candidate counts the same technicians and is accepted on its own measure in the study. Production carries the variance as option b (PR 189, described below) and it is the only fairness objective a client runs.

The load balance head to head (PR J, `docs/solver-benchmarks.md`, report `docs/load-balance-2026-10-23/`) tested whether Timefold's load balance should replace b, on 120 distinct days at 15 s and 30 s, against a rule written before the run. It did not meet the rule at either budget, so the owner kept b as the default. On days where every technician has the same hours the two rank plans the same way, and load balance's search ran at 0.86x to 0.90x of b's. On mixed shifts they differ, because the variance counts a 9-hour technician's gap 2.25 times a 4-hour technician's while load balance counts every technician once. Whether fairness should count each technician once is a question for the owner and dispatch; if the answer is yes, the measure would be built as b with equal weights rather than with the collector.

Every arrangement uses canonical timing that minimizes overtime, modeled operating cost and avoidable waiting, with the earliest equivalent departure. Forward and backward timing bounds respect exclusive arrival-window ends, returns and absence-separated intervals. Fairness never adds artificial waiting. Each working segment's departure and return are persisted and used by dispatch geometry and timelines.

Cost remains integer cents rounded once after aggregating regular paid minutes, overtime paid minutes and road mileage. It is modeled operating cost, not customer price or a claim about actual payroll. Road seconds, configured buffer seconds and whole-minute rounding are reported separately; buffers are unchanged. Remaining candidate ties use lower cost, fewer changed existing assignments, earlier customer window, technician ID and route order.

Ordinary preview and locked apply use the same comparison, `SchedulingPolicy.compare`. Any candidate with overtime is rejected (`OVERTIME_PROHIBITED`), even one that reduces overtime: reducing a day from 90 to 20 overtime minutes is recorded as a diagnostic improvement with a policy rejection, and the schedule stays unchanged. A zero-overtime candidate that removes existing overtime is accepted as `OVERTIME_REDUCTION`. When the validated reference still has overtime, the fairness phase is skipped (`SKIPPED_REFERENCE_OVERTIME`) because no candidate preserving that overtime could be accepted. Otherwise a candidate must be independently feasible, within the cost ceiling, and improve overtime, fairness or cost; a cheaper proposal does not displace a fairer baseline that is inside the cost allowance. The rejection codes are `OVERTIME_PROHIBITED`, `OVERTIME_TARGET_MISMATCH` (the candidate keeps more overtime than the reference proved possible), `COST_CEILING_EXCEEDED`, `FAIRNESS_REGRESSION_WITHIN_ALLOWANCE` and `NO_POLICY_IMPROVEMENT`; the acceptance codes are `OVERTIME_REDUCTION`, `FAIRNESS_IMPROVEMENT` and `COST_REDUCTION`. A daily calculation reports the code it decided on as its reason, or `NO_VALID_CANDIDATE` when no plan found passed independent validation; the public proposal adds a sentence after the code. Paid time includes return travel to the end-of-day endpoint.

Repair proposals must also have zero overtime at preview and apply. Overtime is never assigned, so there is no dispatcher approval for repair overtime: the time-off approve request carries no body fields and rejects any overtime flag. A repair that would need overtime stays unapplied and needs manual resolution. Limits remain hard. The `time_off_request.additionalOvertimeApproved` column is kept only for historical rows; new approvals leave it false.

### Numeric contract

The daily search score has five hard and two soft levels (`DayScores`), compared in order, so no lower level can make up for a higher one. Every level is an integer count except one:

| Level | Quantity | Unit |
| --- | --- | --- |
| hard 0, categorical | missing qualifications, unreachable directed roads | count |
| hard 1, quantitative | window lateness, absence and return violation, capacity excess | seconds (capacity excess is whole minutes times 60) |
| hard 2, unassigned | unassigned visits | count |
| hard 3, overtime target | deviation from the reference overtime (fairness phase) | minutes |
| hard 4, cost ceiling | cost above the ceiling (fairness phase) | cents |
| soft 0, phase objective | overtime (reference phase), or the workload variance (fairness phase) | minutes, or the variance in units of 1e-9 |
| soft 1, cost | modeled operating cost | cents, at most `Monetary.MAX_CENTS` (2^53) |

The production score is `BendableScore`, a `long` per level (Timefold docs 12.2.4), under score model `bendable-decimal-repair-v3`. The model name is kept on purpose: every level carries the same integer it carried as `BendableBigDecimalScore`, so every pair of plans compares the same way, and only the version string crosses the wire (datasets, the calculation protocol, daily outcomes), never a score value. A new name would only mark stored previews stale. Acceptance (`SchedulingPolicy.compare`) always reads the DECIMAL128 variance (34 significant digits) that `SchedulingPolicy.fairness` computes, whatever the search carried.

The cost level is exact in long arithmetic because the cost rates are narrowed to fit it (spec 1.7.0, `Monetary`):
- **The contract.** Each rate has at most four decimal places; `regularHourly` and `overtimeHourly` are at most 500 and `mileagePerMile` at most 25. The API, the dataset reader, booking snapshots and every fact revision refuse anything else. `travelBufferPct` is not a cost rate and keeps its own rules.
- **The arithmetic.** Each rate is a whole number of 1e-4 units (`Monetary.Rates`). The fleet's cents are its summed paid minutes, overtime minutes and meters priced once and rounded half up, exactly as the decimal form `Monetary.cents` computes them: the same cents (cost model `exact-fleet-half-up-v3`), the same single rounding boundary. The timing search orders candidate timings by the same integer numerator.
- **The range.** At the caps a day's cost fits a long for up to about 11 million paid minutes and 5.9 million km of driving (about 7,600 technicians on full 24-hour days). A larger day is refused ("Fleet cost exceeds the cost model's range"), never approximated or priced another way.
- **The independent evaluators** (`RouteEvaluator`, `DayScoreCalculator`, `SchedulingPolicy`) keep the decimal form, so `ScoringExactnessTest` and `MonetaryTest` compare the two.

The search carries the variance as option b of the fairness measure study (PR 187) defined it (`RouteScoringFacts.fairnessUnits`). This is the scaled-integer form Timefold's documentation recommends over floating point in a score (12.1.10), keeping more decimal places than its load balancing chapter asks of `unfairness` to avoid score traps (15.2.1):
- The variance is computed in binary64 from the same workloads and the same formula as `SchedulingPolicy.fairness`, with the capacity and paid-minute sums as exact longs. It is then rounded once, half up, to units of 1e-9 and carried as that exact decimal (scale 9). A move never accumulates binary error: each evaluation computes from the sums.
- A variance outside the range a long can carry (2^62 units) throws, so it never wraps.
- The tie tolerance: the objective can tie two plans whose variances differ by less than a unit, and with binary64's error (far below a unit) can order two plans the other way round only when their variances differ by less than two units and they are equal on every other level. The tolerance can change which plan the search returns, never whether a plan is accepted.
- The margin is wide for whole-minute changes. Moving one paid minute between two technicians of equal capacity `c` in a fleet of `n` changes the variance by at least `2 / (n * c^2)`. That is 1.7e-7 (170 units) at 50 technicians of 480 minutes, and 1.9e-8 at a full 24-hour capacity. Unequal capacities can bring two variances closer, and that is what the tolerance covers.
- `FairnessUnitsTest` checks the contract on fixtures of 20 and 50 technicians and the small case, over fairness-phase plans and random moves of each:
  - The objective is within a unit of the decimal variance.
  - Every pair of plans of one scoring target is ranked as the decimal variance ranks it, but for the tolerance.
  - A hand-checked fleet gives the exact units; a difference below half a unit reads as a tie.
  - 50 technicians at extreme utilizations stay inside the long range.
  - Every long level equals the one rebuilt independently of the constraint streams: hard levels from each route's full evaluation and the unassigned count, the cost from `DayScoreCalculator`'s decimal cents, and the objective as overtime minutes or the decimal variance to within a unit.
- Load balance (experiment only) carries `unfairness` in units of 1e-6, the six places Timefold's load balancing chapter keeps (15.2.1). The collector already gives six places, so the scaling is exact, and a seventh place would be refused rather than rounded.

The score representation study (CR08) also measured a long score, the experiment-only variant `TABU_KOPT_LONG_SCORE` in commits `ec0efc4` and `fa62792`; it is not in the production build. With the variance carried as units, every level is now an integer, so a long score is exact on every level.

The route timing (`RouteTimeline`, `RouteTimingSearch`) runs on long nanoseconds after the route's shift start, not on `Instant` and `Duration`:
- It is exact for any instant the API admits, fractional seconds included.
- The readings are `Duration`'s own: lateness is whole seconds floored plus one second, and an availability violation rounds part of a second up to a whole one. Paid, waiting and overtime minutes are the floored seconds divided by 60 toward zero.
- Arrivals are instants again on the way out.
- A route whose times lie more than 292 years from its shift start throws `ArithmeticException` rather than evaluating.
- `ScoringExactnessTest` (same score, moves, arrangement and independent evaluation as before) is the gate for this.

## Search and reservation lifecycle

Search loads immutable full-horizon facts, route baselines, effective intervals, configuration, schedule/reservation versions and routing identity. Candidate and solver move scoring perform no database or network operations. Sparse routing is fetched between search phases. The regular search starts with insertion, then uses same-date relocation, pair swaps and reversal when scarcity needs further investigation. Defaults are six promising routes including least-utilized eligible capacity, depth two, beam eight and 500 arrangements per window. Work proceeds in horizon rounds. Optional fairness refinement after abundant insertion choices has a separate cooperative limit (2000 ms by default) within the original deadline.

A persisted metro/date arrangement supports confirmed appointments and every active conservative hold placeholder together. Offers can depend on pending reorder/reassignment without immediately moving confirmed appointments. Dependencies protect technicians, services and dated endpoints needed by that common arrangement. Ordinary batch optimization remains blocked by relevant holds.

Confirmation locks current arrangements and versions in consistent order, replaces the chosen placeholder, releases siblings, independently validates confirmed work and remaining holds, and atomically writes required assignments, route order, segment times and version increments. Repeated confirmation returns the existing appointment. A stale snapshot retries at most once within the original search budget.

A regular offer can transfer existing overtime between routes while adding no net overtime. Confirmation preserves its persisted nonpositive issuance delta and independently validates against the current complete reserved arrangement. This remains valid when another customer's confirmation has already applied the dependent reassignment. The selected offer and its issuance metric are reread inside the commit lock; a changed metric conflicts. Missing historical metrics do not become zero and retain the conservative marginal overtime check. A positive delta does not establish a regular reservation, and all technician limits remain hard.

Refresh, release, expiry, cancellation and test purges also validate the remaining arrangement. Removing a stop can be unsafe with directed nonmetric travel, so removal is not treated as automatic proof of feasibility. An unsafe mutation is rejected without partially changing the protected arrangement. Background expiry rotates through failures for retry and yields to customer demand. Persisted strict JSON, relational dependencies and version checks retain guarantees across process restarts.

The five-second search budget starts with a validated job/address. Exploratory work ends by four seconds, leaving the final second for independent validation and publication. Queueing, routing waits, pool admission, SQL locks/statements and commit acknowledgement share the deadline. Expensive work is admitted at two searches per instance, or one on a single available CPU, with a queue of at most 16 and one background optimizer. Bookings have priority over queued background work. Snapshots and solvers are never shared mutably.

A host cancels a running search, confirm or proposal with `DELETE /api/v1/requests/{requestId}` on the replica running it; the call then answers 409 `CANCELLED` and stores nothing. Publication checks the deadline and cancellation under locks, so a cancelled or expired search never publishes a usable reservation.

## Configuration

Every policy rule is the client's own and arrives in `Snapshot.policy` with each calculation; the scheduler holds no
policy settings (the shared `omaha_setting` table was dropped). The rules are included in configuration fingerprints:

| `Snapshot.policy` field | Meaning | Portal starting value |
| --- | --- | --- |
| `regularWindowThreshold` | 0 to 1000; distinct regular windows above which the booking refinement is kept short | 2 |
| `fairnessBudget` | 0 to 1; daily fairness phase only, booking uses no allowance | 0.02 |
| `bookingDeadlineMs` | 1000 to 5000; booking search and confirm deadline at the scheduler | 5000 |

There is no utilization threshold. It only fed the overtime authorization, which the zero-overtime policy always
refuses, so it was removed with calculation protocol 3.

Spring properties can be supplied as command-line arguments or their standard environment-variable equivalents:

| Property | Default / purpose |
| --- | --- |
| `booking.search.variant` | `BOUNDED`; the booking search variant (bounded rearrangement after insertion) |
| `booking.search.refinement-ms` | 2000 (`SCHEDULER_BOOKING_REFINEMENT_MS`, 250 before 2026-10-20); optional abundant-capacity refinement, range 0..3000; a client's `policy.bookingRefinementMs` replaces it, and it is skipped while booking requests are queued |
| `booking.search.publish-reserve-ms` | 1000 (`SCHEDULER_BOOKING_PUBLISH_RESERVE_MS`); the part of a client's `bookingDeadlineMs` kept back from exploration to validate, prepare and publish the offers, range 800..1000; the request log's `commitMs` says how much of it a search used |
| `scheduler.search.capacity` | 0 selects CPU-aware one/two default |
| `scheduler.search.queue-limit` | 16; bounded queue |
| `scheduler.search.confirm-slots` | 1 (`SCHEDULER_SEARCH_CONFIRM_SLOTS`); slots beyond the capacity open to confirms alone, 0 to the capacity; confirms start before waiting searches in any case |
| `routing.ch.enabled` | true (routing-service, `ROUTING_CH_ENABLED`); serves the map version's contraction hierarchy graph (`graph-ch-car-v1`, prepared beside the flexible graph by `infra/prepare-map.ps1`); false serves the flexible graph with another routing identity |
| `routing.parallelism` | 0 (routing-service, `ROUTING_PARALLELISM`): the pairs of one request are routed on this many threads at once, 0 meaning the processors available |
| `routing.identity-ttl-ms` | 2000 (scheduler, `ROUTING_IDENTITY_TTL_MS`): how long a fetched routing identity is trusted before `/health` is asked again; the routing service still checks the identity on every call and a 409 drops it |
| `routing.flights.threads`, `routing.flights.queue` | 8 and 64 (scheduler): batches of legs fetched from a metro's routing service at once, and batches that may wait; a batch that finds the queue full is refused as busy |
| `spring.datasource.hikari.connection-timeout` | 250 ms |
| `spring.datasource.hikari.validation-timeout` | 250 ms |

Start with the default admission capacity (`SCHEDULER_SEARCH_CAPACITY`; the VM compose sets 2) and measure before raising it; [the operations runbook](operations-runbook.md) lists the scaling levers. Budget database connections across scheduler replicas; search admission alone does not bound every replica's idle pool. A configuration or road graph change invalidates stale search and proposal provenance and requires a fresh search or proposal. CH uses a separate graph directory and routing identity; disabling it returns to the original graph artifact, and either switch leaves the route cache cold.
