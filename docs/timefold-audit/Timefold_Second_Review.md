# WaterFlex Timefold Audit Review and Additional Findings

Prepared 6 October 2026 as an independent second review of Timefold_Audit.md for the WaterFlex scheduler owner and implementing engineer.

## Executive assessment

The existing audit is sound on the points that matter most. I traced its central claims back to the code. No constraint covers unassigned visits, so an empty plan can score zero. Daily preview creates its deadline but never installs it. Money is computed in double and wrapped in BigDecimal afterward. The fleet aggregate rescans every route. Each route evaluation builds a temporary DayPlan. The three-layer benchmark design is the right structure, and the warning against treating search seeds as independent datasets is the most important methodological point in it.

This review adds eleven findings, CR01 through CR11, that the audit does not cover. Three of them change how the audit's own recommendations should be carried out. First, the TF05 deadline fix as written would make daily solves fail near their deadline, because route scoring checks the request deadline itself (CR01). Second, Timefold already documents a standard way to handle work that cannot be placed (overconstrained planning), and it fits both TF02 and TF06 better than rejection alone (CR02). Third, the stateless service needs its payload shape decided before input validation is built. Today the routing matrix is all-pairs and string-keyed, so request size grows with the square of the number of locations (CR09).

For a stateless API, the biggest efficiency gain the audit misses is the termination policy. Every daily solve runs its whole fixed budget whether or not search has stopped improving. The local guide recommends diminished-returns termination as the best overall choice (CR06). The next biggest is the inner loop of route timing. It builds a string key for every road leg lookup, allocates Instant and Duration objects for every time step, and builds arrival maps that the score never reads (CR07). Both are likely to matter more than the fleet aggregation in TF12, but neither has been measured yet.

The benchmark redesign needs proportion more than more rigor. About six of the seven hours in Stage 2, and eight of the nine hours twenty minutes in Stage 3, go to the 60-second and 30-second budgets, which are not production budgets. For a fifteen-second endpoint, measure production budgets first and add termination policy as a treatment. Decide whether the service is long-lived or scales to zero before spending time on warmup calibration.

This review proposes no runtime changes and implements none. It ran no new tests or performance measurements. Every CR finding is source-confirmed or an opportunity, not reproduced.

### Decisions to make first

| Decision | Recommended direction and reason |
| --- | --- |
| Deadline ownership | Timefold termination and terminateEarly alone bound solver time. Route scoring must never check the request deadline. Otherwise TF05 turns deadline expiry into solver exceptions. |
| Unplaceable work | Adopt overconstrained planning with an explicit unassigned-visit penalty level. It gives repair and cold inputs an honest partial outcome instead of a flat hard plateau or a blanket rejection. |
| Service payload | Use indexed locations with a dense or explicitly sparse matrix and a routing identity hash. Do not ship string-keyed all-pairs maps. Settle this before writing the TF03 validator. |
| Termination policy | Keep the fixed budget as a hard cap, and benchmark diminished-returns and unimproved-time termination inside it. CPU seconds per request set the stateless service's capacity. |
| Deployment shape | Decide between long-lived, warmed instances and scale-to-zero before warmup calibration. The answer determines whether JIT warmup or cold start is the main latency question. |
| Enterprise edition | Treat nearby selection, the Assignment Recommendation API, score analysis, constraint profiling, and multithreaded solving as one purchase decision. Do not plan diagnostics that quietly depend on them. |

### How to read this review

Priority and evidence labels follow the existing audit. High means a blocker for the proposed dataset service or a substantial lifecycle gap. Medium means a bounded defect, method gap, or improvement needing engineering work. Low means a localized consistency risk or documentation gap. Source-confirmed means the control flow or configuration establishes the finding, without a measurement of its impact. Opportunity means the mechanism is plausible but its benefit is unmeasured. R references resolve to the same immutable revision the audit reviewed. Runtime and benchmark sources under scheduler-service, routing-service, infra, and web/scripts are unchanged between that revision and this review's checkout.

## Review of the existing audit

The audit's findings hold. The table records whether each one stands as written, needs an amendment from this review, or should be reprioritized.

| Finding | Assessment |
| --- | --- |
| TF01 | Agree. Extend the boundary definition to include the payload and matrix shape in CR09. Otherwise the snapshot contract will be designed around a map that does not scale. |
| TF02 | Agree with amendment. Rejecting unassigned input is the right v1 boundary. The longer-term model should be overconstrained planning (CR02), not only a construction phase. |
| TF03 | Agree. The unsafe key-delimiter issue goes away once locations are integer-indexed (CR09), so the validator can check indices instead of escaping strings. |
| TF04 | Agree. The one-cent error has low business impact, but TF04 must come before the score-type change in CR08 and the aggregate change in TF12, because both depend on the numeric contract. |
| TF05 | Agree with amendment. CR01 must be resolved first. Bound the solve through Timefold termination and keep request-deadline checks outside score calculation. |
| TF06 | Agree with amendment. With overconstrained planning, a visit that cannot be placed becomes unassigned and is not a flat hard penalty. Graded lateness is still useful for repair. |
| TF07 | Agree with the amendments in the benchmark section below. Most of the campaign budget is spent on non-production budgets, and termination policy is missing as a factor. |
| TF08 | Agree. The single route-level hard constraint combines qualifications, windows, absences, and limits into one penalty, so ConstraintVerifier tests cannot isolate those rules until that constraint is split. |
| TF09 | Agree. Urgency is moderate while 2.6.0 stays pinned, but it should be done before any version upgrade. |
| TF10 | Agree, Low. Integer-indexed facts in CR09 make the immutable-revision design simpler. |
| TF11 | Agree. Also document the zero-overtime acceptance consequence in CR04. |
| TF12 | Agree. Do it together with the score-type change in CR08, because rescanning the fleet costs more when every step is a DECIMAL128 BigDecimal operation. |
| TF13 | Agree, but it is narrower than the actual hot-path cost. The inner timing loop in CR07 probably dominates. Measure before ordering the two. |
| TF14 | Agree. Lower priority, because expanded booking variants are not the production default. |

What the audit gets right but does not credit: DailySolver builds its SolverFactory once and creates a fresh Solver for each phase. That matches Timefold's guidance that the factory is shared and each solver belongs to one solve, and the stateless service should keep this pattern with one factory per effective configuration. See R10.

## Architecture and correctness

### CR01 Keep request deadlines out of score calculation

Current behavior: RouteTimingSearch calls SearchDeadline.checkpoint at three points: inside the interval loop, the option loop, and the per-visit segment loop. These run during every route score evaluation. checkpoint calls timeout, which uses explorationNanos when not committing. explorationNanos reserves one second. When less than one second of an installed deadline remains, every checkpoint throws Expired. Today daily solving runs without an installed deadline, so these calls do nothing. Evidence: R01, R02, and R03. Classification: High, a source-confirmed interaction with the TF05 remediation. No failure occurs on the current daily path.

Consequence: TF05 recommends propagating one monotonic deadline through preparation and solving. If that is implemented by running the daily calculation inside SearchDeadline.within, the last second of every solve raises Expired from inside Timefold's score director. The solver then fails with an exception instead of returning its best solution, and that best solution was valid moments earlier. If the solve moves to a SolverManager thread pool, the thread-local deadline is absent on the solver thread and the checks silently do nothing. Neither result is the intended contract.

Guidance and change: The guide's lifecycle model bounds solving with configured termination and asynchronous terminateEarly. It does not expect score calculation to abort. Derive the solver's spent limit from the remaining request budget minus a reserved validation allowance, and connect cancellation to terminateEarly or SolverManager termination. Remove deadline checks from RouteTimingSearch, or make them apply only outside scoring through an explicit flag. Keep deadline checks in routing, database, and booking code, where they currently protect real work. [Guide chapter 25](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/library-integration)

Why this helps: Expiry becomes a normal termination that returns the best solution so far, which the caller then validates on its own. It also removes a hidden dependency between a pure scoring function and request state stored in a thread-local, which matters once scoring runs inside a separate service.

Acceptance: A solve under an installed deadline that expires during search returns a validated best solution or an explicit incomplete outcome, never an Expired stack trace from score calculation. Test the deadline at 100 ms, at one second, and at the full allowance, both on the calling thread and on a solver-manager thread.

### CR02 Model work that cannot be placed as overconstrained planning

Current behavior: TechRoute declares its list variable without allowsUnassignedValues. Visits not on any route contribute nothing to the score, which is the TF02 defect. A visit that cannot be placed on its route adds 1,000,000 hard penalty through the infeasible timing fallback, which is the TF06 plateau. Absence repair reports SKIPPED with a reason and does not propose a partial plan. Evidence: R04, R05, and R06. Classification: Medium, a source-confirmed modeling gap with an established Timefold pattern.

Consequence: The current model has only two outcomes: every visit placed, or a hard-infeasible plan. Repair after an absence is exactly the case where placing every visit may be impossible, and a dispatcher still benefits from a plan that places most of them and names the rest. A flat hard penalty also does not tell search which visit to give up.

Guidance and change: The guide's overconstrained planning section recommends two steps. Let the planning variable leave values unassigned, then penalize unassigned values on their own score level. For list variables this means allowsUnassignedValues set to true and a constraint over unassigned PlanVisit entities. For WaterFlex, that constraint must sit above overtime and cost but below hard feasibility, so the solver never trades a broken promise for one more placed visit. This needs a four-level score or an equivalent ordering, and it changes the reference and fairness score semantics. Ordinary preview should still require every visit to be placed before acceptance. Local guide section 11.3; [Guide chapter 10](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems)

Why this helps: It resolves TF02 by construction. Unassigned work becomes visible and penalized instead of ignored. It also gives repair a meaningful partial result and a natural signal for search. It is also a prerequisite for a construction heuristic phase that handles cold inputs without fabricating assignments. The cost is a score-level change that must be versioned and checked against independent validation.

Acceptance: An input with one unplaceable visit returns that visit as unassigned with a nonzero unassigned penalty, and every other visit is feasibly placed. Preview acceptance still rejects any unassigned visit. Independent validation reports the same unassigned set. No visit disappears from the result.

### CR03 Skip fairness search that policy will reject, and separate target units

Current behavior: SchedulingPolicy.compare returns OVERTIME_PROHIBITED for any candidate with nonzero overtime. createPreview still runs the fairness phase whenever the reference candidate is valid, including when the reference has nonzero overtime, and that fairness result can never be accepted. The fairness target constraint adds the absolute overtime difference in minutes to the cost-ceiling excess in cents on a single hard level. Evidence: R07, R08, and R09. Classification: Medium, a source-confirmed waste of solver capacity and a units inconsistency.

Consequence: For each such day, up to five seconds of solver CPU produces a result that policy discards. For a stateless service this is capacity spent with no possible benefit. Combining minutes and cents means one minute of overtime deviation is penalized the same as one cent over the ceiling. That does not affect feasibility at zero, but it distorts the repair direction inside the hard level.

Guidance and change: Return early with the reference outcome when reference overtime is nonzero, and record the reason. Because accepted daily plans must have zero overtime, the fairness phase's overtime target can be overtime greater than zero, kept as its own constraint and separate from the cost-ceiling excess. [Guide chapter 12](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/overview)

Why this helps: It saves solver time that cannot change the outcome and makes the hard level's direction of improvement explicit. Both changes are small and easy to test.

Acceptance: A day whose best reference keeps nonzero overtime runs no fairness solve and records the reason. The fairness-phase hard score reports overtime and ceiling excess as separate constraints. The acceptance results of existing policy tests are unchanged.

### CR04 Document that partial overtime reductions are discarded

Current behavior: The policy accepts only zero-overtime candidates. A baseline day with overtime that search reduces but cannot remove produces no preview, even though the reference phase ranks overtime first. Evidence: R08 and R07. Classification: Low, a source-confirmed policy consequence that the audit does not mention.

Consequence: Dispatchers never see an optimization that cuts a day's overtime from ninety minutes to twenty. That may be intended under zero-overtime-four-hour-v2, but it is a business decision, not a solver limitation. Without documentation it is easy to read it as search failure.

Guidance and change: State the rule in the canonical policy alongside the TF11 corrections. If partial reductions should be offered, define them as a separate decision with its own acceptance reason, not as a relaxation of the zero-overtime rule.

Why this helps: Experiments and dispatch expectations match the actual acceptance rule.

Acceptance: Policy documentation and tests agree on the outcome for a baseline with overtime and a candidate with less, but nonzero, overtime.

### CR05 Make overnight batch failures observable

Current behavior: The overnight job loops over every metro and future day and discards every exception. Evidence: R11. Classification: Low, source-confirmed.

Consequence: A routing outage, schema drift, or solver exception silently leaves days unoptimized. When this becomes calls to a stateless service, transport and contract failures join that silent class.

Guidance and change: Record each failed metro and day with its failure class, and expose counts in telemetry. Keep the current behavior of leaving the day unchanged.

Why this helps: Nightly failure becomes visible before dispatchers notice missing previews.

Acceptance: An injected failure for one metro and day produces a recorded failure and does not stop the remaining days.

## Computational improvements worth measuring

### CR06 Add a convergence-based termination inside the fixed budget

Current behavior: Production TABU terminates only on spent time: ten seconds for reference and the remaining time up to fifteen seconds for fairness. Repair always uses fifteen seconds. The legacy capped configuration adds a step count limit. No configuration uses diminished-returns or unimproved-time termination. Evidence: R10, R12, and R07. Classification: Medium, an opportunity. Among the computation items it probably has the largest capacity effect for a stateless service, but it is unmeasured.

Consequence: Every solve uses its whole budget even after improvement has stopped, which is common for already feasible warm plans. In a stateless service, CPU seconds per request determine how many concurrent solves an instance can hold and how long the nightly batch takes.

Guidance and change: The local guide recommends diminished-returns termination for the best overall experience. Combined with a spent limit using OR composition, it stops when the rate of improvement falls below a fraction of the initial rate, or at the cap. The default sliding window is thirty seconds, which is longer than the whole daily budget. With defaults it would never fire, so the window must be configured to something like one or two seconds and calibrated. Unimproved spent limit is a simpler alternative. Do not combine either with a step count limit, which disables diminished returns. [Guide section 37.2.4](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/overview#diminishedReturnsTermination)

Why this helps: Converged solves release capacity early with no loss of quality, while hard cases still get the full cap. The risk is stopping during a plateau that would later have improved. That is why this is a measured treatment and not a default change.

Acceptance: On matched datasets and seeds, the treatment's accepted cost and fairness are not worse than the fixed-budget control under the audit's quality gate, and its median and p95 CPU seconds per solve are reported. Record the effective termination reason for every solve.

### CR07 Remove allocation from the route timing inner loop

Current behavior: Every road leg lookup during scoring concatenates strings such as previous plus ">" plus visit ID, or visit ID plus ">" plus technician ID plus ":return", then hashes the result. All time arithmetic goes through Instant and Duration objects. Each evaluation builds arrival maps and copies them with Map.copyOf, even though the constraint score reads only scalar metrics. With absences, segment enumeration copies a LinkedHashMap for each candidate return point, which is quadratic in the number of visits per block. Evidence: R01, R13, and R14. Classification: Medium, a source-confirmed opportunity distinct from TF13.

Consequence: TF13 moves preparation that does not depend on the assignment out of the loop. This finding is about the work that must remain in the loop. Allocation and string hashing on every leg and time step reduce move evaluation speed, which the local guide treats as the main measure of score calculation efficiency.

Guidance and change: Give every location an integer index once, when the problem is prepared. Store legs in primitive arrays, with seconds and meters indexed by origin and destination, plus a separate return array per technician. Represent times as long minute or second offsets from a day epoch. Split evaluation into a score-only path that returns scalars and an explain path that builds arrivals and segments for preview, persistence, and independent validation. Keep RouteEvaluator independent so it can still catch errors in the optimized path. [Guide sections 16.2 and 16.3](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/performance)

Why this helps: It removes per-step allocation without changing the scoring definition, so equivalence can be checked exactly against the current implementation on every fixture. The tradeoff is an indexing layer that must stay consistent with the routing identity, which CR09 already requires.

Acceptance: For every fixture and a randomized move sequence, the score-only path matches the current path exactly for hard penalty, overtime, paid minutes, meters, and cents. The explain path matches arrivals exactly. Report move evaluation speed before and after at 20 and 50 technicians in a warmed Layer A cohort.

### CR08 Choose the score type after the numeric contract

Current behavior: The solution uses HardMediumSoftBigDecimalScore. The fairness constraint computes capacity-weighted variance with DECIMAL128 division across the whole fleet on every fleet tuple update. Evidence: R15 and R16. Classification: Medium, an opportunity that depends on TF04 and TF12.

Consequence: BigDecimal arithmetic and score comparison cost much more than long arithmetic. That cost is paid on every move, on top of the full fleet rescan described in TF12.

Guidance and change: The guide recommends avoiding floating point in scores and using BigDecimal or scaled long instead. Once TF04 fixes exact cents and the policy declares the fairness precision it needs, consider HardMediumSoftLongScore. Cost would be in integer cents, and variance would be scaled to a declared fixed precision and rounded by one documented rule. Evaluate this together with TF12's reversible aggregates, because both change the same constraint. Local guide section 12.1.10; [Guide chapter 12](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/overview)

Why this helps: Long scores remove allocation from both calculation and comparison. The risk is precision. If two arrangements differ in variance below the declared scale, the scaled score cannot tell them apart. That must be an explicit policy decision, not a side effect.

Acceptance: Scaled variance ranks every pair of fixture arrangements the same way as the exact decimal oracle, or the policy records the tie tolerance. Measure move evaluation speed separately from accepted quality.

### CR09 Define the stateless payload and matrix shape before validation

Current behavior: Daily preparation builds one location map with two endpoints per technician plus every visit, and requests every ordered pair. The result is stored as a map keyed by concatenated strings. Many pairs can never be used, for example any leg into a departure endpoint or out of a return endpoint. Evidence: R17 and R18. Classification: High for the service boundary. This is a source-confirmed design input for TF01 and TF03.

Consequence: With T technicians and V visits, the matrix has (2T + V) squared entries. Fifty technicians and three hundred visits give 160,000 legs. Serialized as string-keyed JSON objects, that payload dominates request size, parsing time, and memory for a stateless call. The key format is also where TF03's delimiter ambiguity comes from.

Guidance and change: Define the request as indexed technicians, visits, and locations, with legs as dense primitive arrays limited to usable directions. Alternatively, send an explicit sparse list of required directed pairs and declare that every needed pair must be present. Include the routing identity and a content hash. The validator from TF03 then checks index ranges, completeness of the required pairs, and finite nonnegative values before any domain object is built. [Guide chapter 10](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems)

Why this helps: Request size, parsing, and validation scale with the problem's real structure. The same indices feed CR07's primitive arrays, so the service never rebuilds string keys. Snapshot hashing for the benchmark corpus becomes simpler and canonical.

Acceptance: A 50-technician fixture serializes, parses, and validates within a stated budget recorded in Layer C. A missing required leg, an out-of-range index, or a nonfinite value is rejected with the offending field before solving. Round trips preserve every fact and the content hash.

### CR10 Mark Enterprise-only features explicitly

Current behavior: The audit proposes per-move-type and constraint-match diagnostics, constraint profiling, multithreaded solving, and partitioned search. It mentions Enterprise only for move threads and node sharing. The local guide's feature table lists score analysis, the Assignment Recommendation API, nearby selection, multithreaded solving, partitioned search, constraint profiling, and multistage moves as Enterprise features. The code says it never enables Enterprise features. Evidence: R10. Classification: Low, a documentation gap in the audit's proposals.

Consequence: A benchmark or diagnostic plan that assumes these features will stall at implementation, or will add a commercial dependency without a decision. Two of them are directly relevant to WaterFlex. Nearby selection is the standard speedup for routing neighborhoods. The Assignment Recommendation API is built for the booking question of where a new visit fits and returns ranked options with score differences. It needs a construction heuristic as the first phase.

Guidance and change: Make one explicit Enterprise evaluation that covers nearby selection for the daily solve, the Recommendation API as a comparison for booking insertion, and score analysis for preview explanations. Until then, keep diagnostics to Community features: constraint match totals from the score director, the benchmarker statistics available in the pinned artifact, and ConstraintVerifier. Note that the Recommendation API does essentially what BookingSearchPipeline's insertion stage already does. Its main value would be maintenance and explanation, not new capability. Local guide chapters 46 and 62.

Why this helps: Plans stay buildable on the current license, and the commercial decision is made once, with a defined experiment.

Acceptance: Every proposed diagnostic or treatment names its edition. A Community build runs every Stage 0 through Stage 4 configuration.

### CR11 Consider declarative shadow variables only after CR07

Current behavior: Route timing is recomputed in full by a Constraint Streams map node whenever a route changes. The dynamic program over availability blocks chooses return points for the whole route. Evidence: R19 and R01. Classification: Low, an opportunity.

Consequence: A move near the end of a long route recomputes timing from the start. Routes are short in practice, so the cost is bounded.

Guidance and change: The guide's declarative shadow variables can keep arrival times incrementally from the changed index onward, which is the standard routing pattern. The block dynamic program does not map directly onto per-visit shadow sources, so this would be a remodel, not a refactor. Do it only if Layer A shows timing recomputation still dominates after CR07. Local guide section 10.8.

Why this helps: Incremental timing scales better with route length. The tradeoff is model complexity and a harder equivalence proof.

Acceptance: Before starting, profile timing recomputation's share of score calculation time after CR07.

## Benchmark redesign amendments

### Spend the campaign budget on production budgets first

Current gap: The audit's Stage 2 expands to 240 runs per budget for each phase. At 10 and 5 seconds the production cells take 40 and 20 minutes. At 60 and 30 seconds the sensitivity cells take 4 hours and 2 hours. Stage 3 expands to 480 runs per budget, which is 80 minutes at 10 seconds and 8 hours at 60 seconds. About 14 of the combined 16 hours 20 minutes of Stage 2 and Stage 3 solving go to budgets the fifteen-second endpoint never uses.

Recommendation: Run Stages 2 and 3 at production budgets only. That is about 2 hours 20 minutes of solving plus warmup. Add the long budgets only for a candidate whose production-budget result is ambiguous, or for an explicit nightly-batch question. The audit already notes that sixty seconds cannot justify a fifteen-second claim. The campaign cost should reflect that.

### Add termination policy as a treatment

Proposed analysis: Before the TABU versus LA screen, compare three termination policies under the production control configuration. The policies are the fixed budget, diminished returns with a calibrated short sliding window combined with the cap by OR, and unimproved spent limit with the cap. The outcomes are accepted cost and fairness under the audit's quality gate, plus CPU seconds consumed and the effective termination reason. With twelve tuning datasets, five seeds, and two forks, this is 360 pipeline observations and at most 90 minutes of solving. The result decides how every later stage terminates, so it belongs early.

### Decide the deployment shape before warmup calibration

Current gap: The warmup pilot assumes long-lived, warmed instances. If the stateless service scales to zero or runs on short-lived instances, the first request in a new process is the latency that matters. Sixty seconds of warmup would then measure something production never sees.

Recommendation: Record the intended deployment model in the campaign contract. For long-lived instances, keep the audit's Stage 1. For scale-to-zero, replace Stage 1 with a Layer C cold-start study, and treat startup reduction such as class data sharing, checkpoint and restore, or native images as treatments in that layer.

### Measure promotion under the production concurrency envelope

Current gap: The default protocol runs one batch at a time with no move threads and no nested parallelism. That is correct for isolating algorithm effects in Layer A. A stateless service will run several solves at once on a fixed CPU allocation, and algorithms do not slow down equally under shared caches and memory bandwidth.

Recommendation: Keep Layer A serial. Run Layer B confirmation and Layer C at the intended solves per instance and CPU limit, and record both in the runtime profile. The audit's runtime cohort rules already prevent mixing this cohort with serial results.

### Run a throughput baseline before any campaign

Proposed pilot: Measure warmed move evaluation speed for the current TABU control on four tuning datasets (20 and 50 technicians, clustered and absence) with three forks each. This takes minutes. It shows whether CR07, TF13, TF12, and CR08 are worth their engineering cost, and it gives a reference point for every later equivalence check.

### Revised stage allowances

| Stage | Matrix and purpose | Count and nominal allowance |
| --- | --- | --- |
| 0 Correctness and smoke | Unchanged from the audit. | Test suite and short smoke only. |
| 0.5 Throughput baseline | TABU control x 4 datasets x 3 fresh JVMs, 10-second warmed probe each. | 12 observations; 2 minutes solving plus warmup. |
| 1 Warmup or cold start | Audit Stage 1 if long-lived; otherwise a Layer C cold-start study. | 72 observations, about 48 minutes, or as registered. |
| 1.5 Termination policy | 3 termination policies x 12 tuning datasets x 5 seeds x 2 forks, full pipeline capped at 15 seconds. | 360 observations; at most 90 minutes solving. |
| 2 Matched acceptance screen | Audit Stage 2 at production budgets only, using the Stage 1.5 termination. | 480 observations; at most 1 hour solving. |
| 3 Optional move screen | Audit Stage 3 at the 10-second reference budget only. | 480 observations; at most 1 hour 20 minutes solving. |
| 4 Sealed pipeline confirmation | Audit Stage 4 at 15 seconds, at the production concurrency envelope. Add the 60-second cohort only if needed. | 720 observations; at most 3 hours solving. |
| 5 Booking application | Unchanged from the audit. | 72 cases and 14,400 requests. |

The audit's other benchmark recommendations stand without change: dataset seeds separate from solver seeds, a shared frozen reference for fairness experiments, holding out whole datasets, preserving failures, and pairing by effective seed. The twelve-dataset cluster bootstrap is exploratory, as the audit says, and should not gate a production default on its own.

## Merged priorities and implementation order

| Stage | Work | Findings |
| --- | --- | --- |
| 1 Boundary | Define the indexed payload, routing identity, and validator. Reject unassigned input for v1. | TF01, TF03, CR09, TF02 |
| 2 Lifecycle | Remove score-time deadline checks, then bound solving through termination and cancellation. | CR01, TF05 |
| 3 Numeric contract | Exact money, then the score type decision. | TF04, CR08 |
| 4 Baseline | Stage 0.5 throughput baseline. | CR07 measurement |
| 5 Hot path | Inner-loop primitives and score-only evaluation, then hoisted preparation and reversible aggregates. | CR07, TF13, TF12 |
| 6 Policy and waste | Skip rejected fairness solves, separate target units, document the overtime rule, observe batch failures. | CR03, CR04, TF11, CR05 |
| 7 Modeling | Overconstrained planning and graded lateness for repair. | CR02, TF06 |
| 8 Benchmarks | Termination policy, then the matched screens and confirmation at production budgets. | CR06, TF07 |
| 9 Later | Enterprise evaluation, shadow variable remodel, expanded booking bounds, diagnostics isolation. | CR10, CR11, TF14, TF09 |

Each stage changes one class of behavior and preserves production defaults until its gate is met, consistent with the audit's implementation sequence.

## Code and documentation reference register

Every R reference resolves to the immutable source the audit reviewed. Local guide citations refer to docs/timefold-documentation/Timefold-Solver-Docs.md.

| Source | Reviewed implementation |
| --- | --- |
| R01 | [RouteTimingSearch deadline checkpoints and leg keys](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteTimingSearch.java#L29-L89) |
| R02 | [SearchDeadline exploration reserve and checkpoint](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/SearchDeadline.java#L56-L90) |
| R03 | [OptimizationService preview admission deadline](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L64-L69) |
| R04 | [TechRoute list variable](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/TechRoute.java#L25-L26) |
| R05 | [DayConstraintProvider constraints](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayConstraintProvider.java#L14-L34) |
| R06 | [RouteTimeline infeasible fallback](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteTimeline.java#L35-L114) |
| R07 | [OptimizationService reference, fairness, and repair phases](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L105-L197) |
| R08 | [SchedulingPolicy.compare overtime prohibition](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SchedulingPolicy.java#L111-L125) |
| R09 | [RouteScoringFacts target violation](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteScoringFacts.java#L40-L46) |
| R10 | [SolverExperiment configuration and solve](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SolverExperiment.java#L17-L86) and [DailySolver factory reuse](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DailySolver.java#L14-L19) |
| R11 | [OptimizationService overnight batch](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L476-L483) |
| R12 | [Legacy solverConfig termination](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/resources/solverConfig.xml#L10) |
| R13 | [RouteTimeline evaluate and leg lookups](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteTimeline.java#L19-L33) |
| R14 | [RouteScoringFacts per-route evaluation](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteScoringFacts.java#L19-L24) |
| R15 | [DayPlan score declaration](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayPlan.java#L29-L32) |
| R16 | [SchedulingPolicy fairness variance](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SchedulingPolicy.java#L52-L73) |
| R17 | [OptimizationService location and matrix preparation](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L531-L538) |
| R18 | [RoadClient all-pairs matrix](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/RoadClient.java#L226-L232) |
| R19 | [Constraint Streams route map node](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayConstraintProvider.java#L16-L18) |
| Guide | Local sections 10.8 shadow variables, 11.3 overconstrained planning, 12.1.10 floating point, 16.2 and 16.3 score performance, 25 library integration, 31 benchmarking, 37.2.4 termination, 46 Assignment Recommendation API, 62.2 Enterprise features. |
