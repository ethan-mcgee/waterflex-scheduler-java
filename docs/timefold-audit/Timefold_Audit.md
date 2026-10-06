# WaterFlex Timefold Audit and Benchmark Design

Prepared 6 October 2026 for the WaterFlex scheduler owner and implementing engineer.

## Executive assessment

WaterFlex has a useful scheduling foundation: booking preserves promises and validates offers, daily optimization separates reference cost from fairness, and experiments preserve unusually detailed provenance. The next step is to make those safeguards explicit at the solver boundary and make the experiments answer narrower, comparable questions. Replacing the application with a Timefold example would discard working policy without proving better outcomes.

The most immediate deployment risks remain unvalidated core datasets, incomplete initialization, and a daily deadline that does not cover the whole request. Monetary arithmetic also needs an exact contract before we use small cost differences to select algorithms. These are different problems from choosing Tabu Search or Late Acceptance. A stronger search algorithm cannot repair a dataset whose visits disappeared during copying or a result whose score omitted unassigned work.

Our benchmarking is inadequate for some decisions we want to make, especially warmed-service performance, general algorithm selection across customer workloads, and production capacity. It is still useful evidence about the specific archived synthetic cases. The remedy is to preserve the evidence system and add controlled solver experiments, representative datasets, explicit warmup and process repetitions, and complete workflow measurements. More runs of the same geometry do not create more independent operating scenarios.

This report retains TF01 through TF14, integrates CR01 through CR11 from the supplied second review into the relevant chapters, and specifies a configurable Community Edition benchmark design. Correctness, benchmark foundations, and allocation profiling precede performance changes. Campaign coverage, per-solve allowances, and execution resources are separate decisions. It proposes runtime changes but implements none. No new performance campaign, production savings estimate, or capacity certification accompanies this revision.

### Decisions to make first

| Decision | Recommended direction and reason |
| --- | --- |
| Solver service boundary | Keep persistence and commit authority in the caller; pass complete validated datasets into calculation. This enables reproducible offline solving without weakening reservation safety. |
| Daily algorithm | Retain current TABU as the control. Compare acceptance methods with matched moves before attributing results to an algorithm family. |
| Booking algorithm | Retain insertion and optional bounded refinement as the control. A Timefold alternative must reproduce promises, completion semantics, and independent validation before timing comparisons matter. |
| Benchmark architecture | Add native Benchmarker for fixed daily subproblems, retain the policy pipeline harness, and measure application latency separately. Each layer answers a different question. |
| Production promotion | Require correctness, held-out quality, and complete-request performance gates. A lower pooled synthetic cost is insufficient. |
| Edition and configuration | Use Community Edition only. One versioned JSON file declares all adjustable campaign parameters; the CLI selects the file and operation. Examples are design guidance, not implemented runner interfaces. |

### How to read the evidence

High priority means a blocker for the proposed dataset service or a substantial lifecycle gap. Medium means a bounded defect, method gap, or improvement needing engineering work. Low means a localized consistency risk with an existing mitigation. These are engineering priorities, not security ratings.

Reproduced means an executable characterization demonstrates the behavior. Source-confirmed means the control flow or configuration establishes the finding, without measuring its operational impact. Opportunity means the mechanism is plausible but its speed or quality benefit has not been measured. None of these labels establishes that a current HTTP endpoint accepted malformed input or applied an invalid schedule.

## Current system and review boundaries

### Sources and version applicability

The reviewed repository baseline is 499fde2cc3fe79a8b7413a942a66cfc97b5d0e94. Runtime and benchmark sources under scheduler-service/src, infra, and web/scripts have no differences from the previous expanded review baseline afa035ddf2101d26c12508067d2d9016601db6c3. Findings were traced again against this checkout; historical measurements keep their original revisions.

The primary documentation is the supplied docs/timefold-documentation/Timefold-Solver-Docs.md and its PDF. This Markdown edition has a different hash and section layout from the conversion used in the previous review. The new source register records its hashes and exact section ranges. This revision reads the relevant local sections, not every page of the PDF; PDF examples must be checked before copying visually wrapped code. The previous 66-chapter coverage inventory remains historical and is not presented as a new exhaustive crawl.

The installed solver is 2.6.0. The local guide describes 2.7-era functionality. Earlier official checks on 6 October returned a 2.7.0 benchmarking page and 2.7.1 performance and local-search pages. This revision also checked the official algorithm and score overview pages, labeled 2.7.1. Latest URLs are therefore navigation aids, not immutable version evidence. Proposed APIs and XML must be compiled and exercised against the selected installed version. An upgrade is a separate treatment, not an invisible prerequisite for this report.

Source references below combine explained guidance with the guide chapter/section. The code reference register links to immutable repository files. Review receipts, test logs, and source hashes are recorded separately so that a future documentation change cannot silently change what was reviewed.

### Booking and daily optimization solve different problems

Booking must return useful appointment offers within a short customer-facing deadline. BookingSearchPipeline first attempts insertion into the existing plan. Bounded refinement is disabled by default even though the configured variant name defaults to BOUNDED; a variant name alone does not activate the gate. When enabled, the custom search explores relocations, swaps, and reversals. Its ordinary limits include six shortlisted routes, depth two, beam eight, and 500 arrangements per window. Expanded experiments increase these limits and change reconstruction and shared-window behavior. These are WaterFlex Java algorithms, not Timefold acceptors. See C01 and C02.

Booking compares cost strictly, uses fairness to break cost ties, preserves customer windows, and prohibits new overtime. Offers require routing completeness and independent validation, then reservations and guarded confirmation. A completed bounded search means the prescribed bounded search finished; it does not prove that no feasible arrangement exists outside its neighborhood.

Daily optimization starts from an assigned, validated schedule. DailySolver reuses its SolverFactory and builds a fresh solver and copied plan for each phase. Preserve one factory per effective configuration and independent planning state per solve. The reference phase minimizes overtime before cost. The fairness phase fixes the reference overtime target and permits cost up to the policy ceiling, normally two percent above reference cost. The application independently checks the result before preview and again before guarded application. Ordinary preview allocates ten seconds to reference and the remainder of a nominal fifteen seconds to fairness. Absence repair is a separate workload. See C03 through C06.

The current daily control is TABU with seed 17, entity tabu size seven, accepted-count limit 1,000, selected-count limit 10,000, and list change/swap weights 45/45. Existing LA experiments use history 400 and accepted-count one. SUBLIST, KOPT, and RUIN_RECREATE add progressively larger moves under LA. Consequently, comparing an advanced variant with TABU changes both moves and acceptance settings.

### What should be preserved

Keep independent feasibility and policy validation, copied planning state, immutable booking snapshots, explicit missing-road failures, reservations, the 6 a.m. America/Chicago cutoff, and no-new-overtime booking policy. Keep reference and fairness in one policy workflow even when measuring their calculations separately. Preserve archive hashes, frozen artifacts, saved case order, isolated booking schemas, failed attempts, and explicit incomplete outcomes. These mechanisms make later changes reviewable.

## Architecture and correctness

### TF01 Separate calculation from business persistence

Current behavior: The core DailySolver accepts a DayPlan, but OptimizationService builds it through database and routing work and persists the preview. Booking similarly surrounds its calculation with durable search and reservation workflows. These are reasonable application responsibilities, but the current endpoints are not a standalone dataset-in, results-out calculation boundary. Evidence: C01, C03, and C04. Classification: High, source-confirmed architecture gap relative to the intended service.

Consequence: A benchmark of these endpoints combines database, routing, queueing, and solving. A core benchmark bypasses those costs. Without an explicit boundary, the same phrase such as solver latency can describe two different operations. Extracting the service without identifying commit authority would also leave uncertainty about who checks that a result is still applicable when another booking changes capacity.

Guidance and change: The guide distinguishes running a solver as a service from embedding it as a library. Its service model motivates a complete input dataset and a result contract, but does not require WaterFlex to abandon Spring Boot. Define validated input snapshots containing facts, policy, routing identity, and versions. Return proposals and diagnostics. The caller retains persistence, reservations, idempotency, and the atomic decision to apply a proposal. [Guide chapter 17](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/overview)

Why this helps: The same snapshot can drive production calculation and offline comparison. Failures become attributable to preparation, solving, validation, or commit rather than being hidden inside one endpoint. The additional cost is snapshot serialization, versioning, and a network boundary if deployed remotely. Remote queueing and transport must consume the existing deadline; this design does not establish a latency improvement.

Acceptance: A snapshot reproduces the same problem facts without database access during solving. A stale proposal cannot commit after a relevant schedule, hold, policy, or routing change. Concurrent requests never share mutable planning entities. Keep caller-side locked revalidation even when the solver returns a feasible score.

### TF02 Initialize or explicitly reject unassigned work

Current behavior: The daily configurations contain local search but no construction phase. That fits ordinary preview, which supplies assigned routes. A direct core test supplies one visit with an empty route and receives an empty zero-score result with PHASE_COMPLETED; the independent evaluator rejects it. Evidence: C05 and C07. Classification: High, reproduced core defect for the proposed broader input contract.

Consequence: Zero hard score is meaningful only for the facts and assignments actually represented in scoring. It does not independently prove that every requested job was served. Treating this core method as a general dataset solver would allow an apparently successful result that omits work, even though the current application starts from validated assignments.

Guidance and change: Construction heuristics create an initial assignment; local search improves an existing one. For v1 of the extracted boundary, explicitly declare whether the endpoint accepts assigned plans only. Reject unassigned or partially assigned input until a supported construction path is implemented. If cold planning is required, implement the CR02 overconstrained model, construction, and coverage validation, then benchmark cold, partial, feasible warm, and infeasible repair inputs separately. A construction phase alone does not define the treatment of work that cannot be placed. [Guide chapter 38](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/construction-heuristics)

Why this helps: The solver contract stops confusing initialization with optimization. Rejecting unsupported input is an immediate safe boundary improvement; construction expands capability but changes both runtime and search behavior. Do not penalize a local-search variant for failing a capability it never promised, or promote it based on a zero score for an empty plan.

Acceptance: Empty demand returns a documented empty result. Nonempty demand is either fully covered or receives an explicit unsupported/infeasible/incomplete outcome. Test zero technicians, one visit, partial assignments, pinned work, and infeasible windows. No input visit may disappear.

### CR02 Model unplaceable work without losing demand

Current behavior: TechRoute does not allow unassigned list values; visits outside routes have no coverage penalty, while timing failures receive categorical hard penalties. Repair returns SKIPPED when no eligible full schedule is found. Evidence: R04 through R06 and TF02/TF06. Classification: Medium, source-confirmed modeling gap; the TF02 core omission is separately reproduced.

Consequence: A cold input or absence can need a useful partial diagnostic even when no complete schedule exists. Search must not improve its score by dropping work or trade a broken customer promise for one more assignment.

Guidance and change: The guide documents overconstrained planning: allow unassigned list values with allowsUnassignedValues and score them explicitly, including unassigned values in the relevant Constraint Streams selection. Define a versioned lexicographic ordering of hard feasibility, assignment coverage, then the phase-specific overtime, fairness, and cost priorities. This requires more levels than the current three-level model, such as a compatible Bendable score, or a separately proven equivalent ordering. Specify pinning, construction, and unassignment moves against 2.6.0 before accepting cold inputs. [Guide section 11.3](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems)

Why this helps: Repair can identify unresolved demand while preserving feasibility of assigned work. It changes score and fairness-reference semantics, so old score traces cannot be merged with new ones. Partial results are diagnostic proposals only; ordinary preview and guarded application still require complete independently validated coverage and all policy conditions.

Acceptance: An unplaceable visit appears exactly once in the explicit unassigned set and contributes a nonzero coverage penalty. Assigned plus unassigned identities equal input demand with no duplicates. Zero hard score is insufficient for application. Test feasible cold construction, impossible pinned work, partial repair, and reference/target compatibility; reject partial proposals on the apply path.

### CR09 Define the indexed dataset and directed road contract

Current behavior: Daily preparation requests ordered road pairs for two endpoints per technician plus every visit and stores them under concatenated string keys. With T technicians and V visits the candidate matrix grows as (2T + V) squared; T=50 and V=300 gives 160,000 candidate entries, before any provider-specific omissions. Evidence: R17 and R18. Classification: High, source-confirmed boundary design input; serialized size and parsing costs are unmeasured.

Consequence: Many directions, such as entering a departure endpoint, cannot be used. String keys also couple the external payload to the scoring lookup representation. A sparse payload is unsafe unless it includes every leg that the configured move families may request, not just legs in the initial routes.

Guidance and change: Decide the versioned wire contract before TF03. Serialize stable technician, visit, and location IDs with integer indices, units, routing provider/profile/version identity, and a content hash. Use either a dense directed matrix with explicit dimensions and unusable entries, or sparse directed records with an explicit completeness rule. Origin-to-destination and reverse legs are distinct; departure, return, self-leg, missing and unreachable semantics must be declared. Derive required pairs from eligibility and allowed moves. Decode validated external arrays or records into efficient internal primitive arrays or typed indexed maps. JSON cannot promise primitive storage or zero-copy parsing. [Guide chapter 10](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems)

Why this helps: Indices remove delimiter ambiguity and support later efficient lookup without prescribing a wasteful full matrix. Dense storage has predictable access and memory cost; sparse storage saves bytes but needs missing-pair checks and can cost more per lookup. Measure payload bytes, decode time, memory, and lookup allocation before selecting a format.

Acceptance: Reject duplicate IDs, out-of-range indices, inconsistent dimensions, duplicate directed pairs, nonfinite or negative values, and absent required legs before copying. Never substitute zero travel for missing roads. Round trips preserve ordering, units, identity, facts, and the canonical hash. Layer C measures serialization, validation, and preparation at the intended fleet sizes within a declared request budget.

### TF03 Validate the problem before building maps or copies

Current behavior: Direct core tests show duplicate technician IDs passing route validation while overwriting one segment-map key, duplicate visit IDs collapsing during PlanCopies.copy, and a NaN rate producing zero cost. Database keys and booking rate validation reduce exposure in current workflows; these tests do not establish HTTP exploitability. Evidence: C07 through C09. Classification: High, reproduced core boundary defects.

Consequence: Identity corruption changes the problem before the solver sees it. Once two jobs become one map entry, successful optimization of the smaller problem cannot recover the lost job. A fabricated zero cost can also dominate a valid candidate in ranking. Null checks alone cannot establish uniqueness, numeric validity, or membership consistency.

Guidance and change: Timefold modeling depends on stable identities and a consistent relationship between facts, entities, and planning values. Add a validated input DTO and one problem validator before any ID-indexed copying. Validate the CR09 wire contract first, then require unique IDs, canonical visit membership, consistent assignments, finite nonnegative rates, valid durations and limits, valid windows, and complete required directed legs. External serialization and internal lookup structures are separate layers; index validation precedes construction of either. Retained legacy string-key inputs still need delimiter validation on an explicit compatibility path. [Guide chapter 10](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems)

Why this helps: Validation preserves the requested problem and produces actionable errors before expensive solving. It also makes benchmark fixtures trustworthy. It costs preparation time, which belongs outside isolated solve timing but inside end-to-end latency. Missing rates, legs, or capacities must remain explicit errors, never zero, false, or empty defaults.

Acceptance: Exercise duplicate IDs, dangling references, mismatched objects, NaN/infinity, negative legs, and missing required fields. Rejection must precede copying, preserve the original task count in diagnostics, and identify the invalid field. Declare intentional nulls explicitly in request, result, and persisted evidence types.

### TF04 Carry exact money through the whole calculation

Current behavior: BigDecimal score objects wrap monetary amounts that were already computed with double. For 255 paid minutes at $20.02 per hour with no mileage or overtime, both evaluators return 8,508 cents. Exact decimal half-up rounding of $85.085 returns 8,509 cents. Agreement between two evaluators does not catch their shared arithmetic pattern. Evidence: C07, C09, and C10. Classification: Medium, reproduced one-cent numeric error.

Consequence: Small ranking differences and a fairness cost ceiling can be affected at rounding boundaries. The example does not establish material production loss. It establishes that the current arithmetic cannot support claims requiring exact cents in every case.

Guidance and change: The score guidance warns against floating-point arithmetic because rounding can break score comparisons and incremental consistency. Define decimal input units and one fleet-level rounding rule. Carry BigDecimal or scaled integer/rational values through labor and mileage conversion; round only at the agreed boundary. Use an independently written exact numeric oracle for tests. [Guide chapter 12](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/overview)

Why this helps: Exact arithmetic gives cost ranking and the two-percent ceiling a stable meaning. Rounding each route first can differ from rounding the total, so merely replacing double with BigDecimal in one method is insufficient. Decimal computation may cost more CPU; measure that after specifying the correct result. Version the cost model so old experiments are not silently compared with a new monetary definition.

Acceptance: Test half-cent values, mileage conversion, overtime, route repartitioning, and summation order. Booking ties, daily targets, independent validation, and reporting must agree on the same exact monetary contract.

### TF05 Give the complete operation one deadline

Current behavior: Daily preview creates a twenty-second SearchDeadline for admission but does not install it around all subsequent work. Problem preparation occurs before the fifteen-second phase clock. Preview construction and solving run inside a transaction template. Evidence: C03 and C11. Classification: High, source-confirmed lifecycle gap; impact has not been load-tested.

Consequence: A fifteen-second solve allowance is not a fifteen-second request bound. Queueing, roads, validation, and persistence can extend the operation, while a database transaction remains open during expensive calculation. A timeout at the HTTP layer would not by itself prove that the solver stopped or released capacity.

Guidance and change: Timefold lifecycle integration supports managed solving and early termination. Establish one monotonic deadline at admission. Check its remaining budget during preparation and caller work; derive each solver allowance from remaining time minus a measured validation/response reserve. Use solver termination and cancellation cleanup for search, resolving CR01 before installing request state around daily solving. Do not propagate request-clock checks into scoring. SolverManager is an available building block; a bounded equivalent adapter is also viable. Move durable transactions to the caller and keep their critical sections short. [Guide chapter 25](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/library-integration)

Why this helps: The caller can reason about resource occupancy and response limits. Cancellation has observable cleanup and resource-release ownership. Termination is cooperative: long moves, score evaluations, scheduling, or GC can delay observation, so a spent limit or terminateEarly is not an exact wall-clock bound. Reserve time and measure overruns and cleanup instead of promising exact expiry. Asynchronous submission alone does not solve this: waiting synchronously for the final result or ignoring cancellation preserves the original problem.

Acceptance: Inject queue delay, slow routing, validation delay, cancellation, and deadline expiry. Confirm that these consume the original budget, cleanup releases capacity, and only independently validated partial results can be returned where the contract permits them. Measure complete latency and cleanup, not just solveMs.

### CR01 Keep request clock checks outside scoring

Current behavior: RouteTimingSearch calls SearchDeadline.checkpoint within interval, option, and segment loops. The thread-local exploration budget reserves one second and throws Expired on exhaustion. Current daily solves do not install that deadline, so these calls do nothing there. Evidence: R01 through R03. Classification: High, source-confirmed interaction with the TF05 proposal, not a reproduced current daily failure.

Consequence: Wrapping daily solving in SearchDeadline.within would allow request expiry to throw from score calculation. Moving the solve to another thread can instead lose the thread-local checks. Both make score behavior depend on execution context and undermine predictable best-solution retrieval.

Guidance and change: Keep scoring deterministic for a given solution and fact revision. Remove these checkpoints from the scoring path, or use an explicit non-scoring evaluation mode, while retaining appropriate request checks in booking, routing, and caller preparation. Set a solver spent allowance after reserving validation time; wire cancellation to terminateEarly or the supported SolverManager termination method. Solver and phase termination are cooperative, not forced interruption of an arbitrary score evaluation. [Guide chapter 25](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/library-integration)

Why this helps: Search can stop normally with a best-so-far solution rather than fail inside the score director. It also keeps future worker threads free of hidden request state. A best solution may still be partial or infeasible; caller validation decides whether it can be returned or applied.

Acceptance: Test 100 ms, one-second, and normal allowances, cancellation, delayed evaluations, and caller versus worker-thread execution. No Expired originates from daily scoring. Record actual duration and cleanup overshoot, returning a validated eligible proposal or explicit incomplete/cancelled outcome; expiration never bypasses validation.

### TF10 Keep one authoritative problem-facts revision

Current behavior: DayPlan stores rates and a matrix alongside a RouteScoringFacts snapshot. Replacing the matrix can leave the snapshot using the old legs. A test makes independent validation reject missing legs while scoring still reports zero hard penalty. PlanCopies reconstructs facts before current daily solving, mitigating this path. Evidence: C07, C08, and C10. Classification: Low, reproduced consistency risk with a current mitigation.

Consequence: Derived facts are caches. If their dependencies can change independently, two correct consumers can evaluate different problems. This risk grows when adding JSON import, precomputed availability, or real-time updates.

Guidance and change: Keep planning facts stable for a solve and use supported problem-change mechanisms when changes are necessary. Construct an immutable validated fact container and reference it consistently from scoring and independent validation. Remove separate mutation paths or rebuild all derived data atomically. [Guide chapter 10](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems)

Why this helps: A single fact revision makes invalidation testable and snapshot hashes meaningful. Immutability may require rebuilding a context when policy or routing changes, but that cost is preferable to a score computed from mixed revisions.

Acceptance: Matrix, rates, demand services, shifts, absences, and derived capacities share one revision. Copies and serialization round trips preserve it; changing a dependency cannot leave old derived values active.

## Algorithm choices and scoring behavior

### TF06 Give infeasible plans a useful direction of improvement

Current behavior: The infeasible timing fallback applies a categorical penalty to an unplaced visit. Reaching the exclusive window boundary and arriving an hour later both receive 1,000,000 hard penalty in the characterization test. Evidence: C07 and C12. Classification: Medium, reproduced score plateau; its effect on practical search quality is unmeasured.

Consequence: Imagine two infeasible moves, one reducing lateness from sixty minutes to one minute, the other leaving lateness unchanged. If both have the same hard score, search cannot use that score to recognize progress toward feasibility. Larger moves may jump across the plateau, but they also cost more and do not remove its cause.

Guidance and change: The guide calls this a score trap and recommends reflecting the degree of violation where meaningful. Use CR02 to distinguish explicitly unassigned demand from infeasible assigned work. Add quantitative lateness and capacity-excess penalties while retaining strict feasibility. Keep categorical violations such as missing qualifications explicit. Do not turn a hard customer promise into a soft preference. [Guide section 16.14](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/performance)

Why this helps: A gradient supplies intermediate information during repair. Its benefit is most plausible for absence repair and deliberately infeasible seeds, not already feasible ordinary previews. Penalty magnitudes can change which infeasibility is repaired first, so the revised score requires a versioned policy and bounded numeric analysis.

Acceptance: Reducing a quantitative violation improves its penalty; crossing into feasibility removes it exactly at the correct boundary. Independent validation remains authoritative. Compare time to first feasible solution and repair success on identical infeasible seeds before claiming improved quality.

### Compare moves and acceptance as separate choices

Current behavior: TABU discourages revisiting recently changed entities and considers many accepted candidates per step. LA allows a candidate according to a historical score threshold and currently accepts only one candidate per step. Advanced variants also alter the neighborhood. A result for KOPT versus TABU therefore describes a configuration package, not the isolated effect of k-opt. Evidence: C05.

Guidance and change: Timefold separates the move selector, acceptor, and forager. First compare TABU and LA using the same list change/swap neighborhood and selected-count cap, retaining each method's explicitly stated forager settings as part of its package. Then vary one neighborhood family under a fixed acceptance package. Finally test interactions if the first experiments show a reason. Simulated Annealing is a later candidate requiring temperature calibration on this score scale. [Guide chapter 39](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/local-search) [Guide chapter 42](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/move-selector-reference)

Why this helps: The experiment answers which change caused a difference. Forcing every method to use the same accepted-count limit can unfairly cripple an algorithm; calling different forager settings the same treatment is equally misleading. Record the entire effective configuration and state precisely what the comparison isolates.

Acceptance: Compare matched snapshots, budgets, seeds, scoring, and resources. Keep the production control and current experiment variants for historical reference. Do not adopt an advanced move because its name suggests sophistication or because it evaluates more moves. Measure accepted quality and time to useful solutions.

### Preserve the booking control while testing a Timefold alternative

Current behavior: Booking's custom search already represents hourly four-hour promises, strict cost ranking, overflow, route feasibility, and incomplete search. An unrestricted daily Timefold solve is not a drop-in replacement for a short offer-generation request.

Guidance and change: If a Timefold booking prototype is pursued, use a small copied technician/day/window problem with existing commitments pinned or equivalently constrained. Define candidate offer enumeration and completion outside the solver, and preserve independent validation and reservation witnesses. List variables can represent visit order, but assigning routes does not automatically implement WaterFlex's booking protocol. See C01, C02, and the [planning list variable guidance](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems).

Why this helps: A constrained prototype tests whether Timefold improves the actual booking decision under the same deadline. The tradeoff is model and integration complexity. Community booking recommendations can enumerate feasible insertions on copied schedules, rank independently validated candidates, and explain the business deltas. This report does not require a managed product or an Enterprise recommendation API.

Acceptance: Validate promises, no-new-overtime, directed routing, completeness flags, cancellation, and offered arrangements before measuring. Compare identical served demand and report incomplete search separately from proven infeasibility. A faster response with fewer useful offers is not automatically better.

### TF08 Test business rules independently of final solve quality

Current behavior: DayConstraintProviderTest and SolverExperimentTest use FULL_ASSERT in short runs, but broad route/fleet penalties combine many rules and there are no ConstraintVerifier tests. The inspected maintenance workflow does not schedule extended runs in both full assertion modes. Runtime configurations explicitly use NO_ASSERT. Evidence: C05, C13, and C14. Classification: Medium, source-confirmed coverage gap.

Consequence: A final feasible route may never exercise an incorrect penalty, move undo, or shadow update. FULL_ASSERT checks internal consistency, but it cannot establish that the business formula itself is right. The monetary example demonstrates why agreement alone is insufficient.

Guidance and change: Add focused constraint tests with known expected scores, preserve independent exhaustive timing tests, and schedule bounded FULL_ASSERT and NON_INTRUSIVE_FULL_ASSERT campaigns. The guide explains that the modes catch different errors. Evaluate PHASE_ASSERT as the production/development default recommended by the newer guide through a separate version-compatible change; use a named NO_ASSERT profile for matched performance experiments rather than silently changing production. [Guide section 32.1](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/solver-diagnostics)

Why this helps: Tests distinguish business semantics from incremental engine correctness. Assertions are intentionally expensive and must not be enabled in one timed treatment but disabled in another. In Timefold 2.x, use the constraint testing APIs supplied with core rather than adding the removed 1.x test artifact.

Acceptance: Cover qualifications, exclusive window boundaries, absences, return travel, capacities, target violations, and weighted fairness with positive and negative cases. Include clone isolation and move/undo on infeasible inputs. A zero hard score never replaces demand-coverage validation.

### TF09 Isolate diagnostics that depend on Timefold internals

Current behavior: Production DailySolver delegates to SolverExperiment, which casts to DefaultSolver and uses internal phase/scope types. The engine label is hard-coded and stop reasons are inferred from elapsed time and counters. Evidence: C04 and C05. Classification: Medium, source-confirmed upgrade risk; no pinned-version failure was observed.

Consequence: An internal API change can break ordinary solving because diagnostic access and engine execution are coupled. An inferred PHASE_COMPLETED result also says neither that the optimum was found nor that every visit was served.

Guidance and change: Separate a public engine adapter from experiment diagnostics. Prefer public metrics and lifecycle hooks, isolate unavoidable internal access behind an exact-version adapter, and derive provenance from loaded artifacts. Label inferred termination explicitly and preserve unknown reasons rather than inventing certainty. [Guide chapter 25](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/library-integration) [Guide chapter 32](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/solver-diagnostics)

Why this helps: Upgrades have a smaller compatibility boundary and diagnostic failures have a deliberate policy. For performance campaigns, missing required diagnostics should invalidate the case with retained evidence. For production, optional telemetry failure should follow an explicit policy rather than accidentally aborting a valid solve.

Acceptance: Upgrade checks exercise all variants, loaded version provenance, score equivalence, unknown metrics, and termination labels. Nullability remains explicit; unavailable measurements are never replaced with zero. Public, preview, and internal dependencies must be recorded separately under CR10; public placement does not imply stability for a preview API.

### TF11 Document the policy the application actually enforces

Current behavior: README describes a shared two-percent booking/daily fairness allowance and scarcity/utilization-based authorization of new overtime. Current booking is strict cost-first with fairness ties, and SchedulingPolicy.authorizeOvertime returns false. Evidence: C02, C06, and C15. Classification: Medium, source-confirmed documentation error.

Consequence: An engineer could benchmark the wrong acceptance rule or interpret deliberately rejected overtime as a solver defect. Algorithm quality only has meaning relative to the real policy.

Guidance and change: Timefold's score model formalizes priorities, but the business choices belong to WaterFlex. Correct the canonical policy and linked experiment explanations together when implementing remediation. Distinguish booking ties from daily fairness headroom, state that return travel counts, and explain bounded completion. Document CR04: a reduction from ninety overtime minutes to twenty is still rejected because current policy requires zero candidate overtime. A separate business decision would be needed to expose such a proposal. [Guide chapter 12](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/overview)

Why this helps: Readers can derive expected acceptance from the documentation, and experiments can preserve policy instead of optimizing an accidental interpretation. This audit records the discrepancy; it does not authorize a policy change or claim the README was corrected here.

Acceptance: Documentation examples agree with policy tests for overtime, cost ties, fairness ceilings, and frozen days. Review configuration gates as well as variant names.

### CR03 Skip fairness work that acceptance policy must reject

Current behavior: The fairness phase runs when its reference validates, even if the reference overtime target is nonzero. SchedulingPolicy.compare prohibits any nonzero candidate overtime. The target violation adds overtime deviation in minutes to excess cents on one hard level. Evidence: R07 through R09. Classification: Medium, source-confirmed policy interaction and mixed-unit weighting; CPU savings are unmeasured.

Consequence: A fairness solve preserving a nonzero overtime target cannot produce an accepted schedule under current policy. Combining cents and minutes assigns one cent the same numerical weight as one minute while repairing target violations. Splitting them into named constraints alone preserves that summed weighting and does not solve the ordering problem.

Guidance and change: Evaluate policy eligibility before fairness. If the validated reference retains nonzero overtime and fairness must preserve it, skip fairness with a recorded policy reason, retaining the reference diagnostic and unchanged accepted baseline. If a future fairness model can reduce overtime, this skip rule needs reevaluation. Use separate named constraints for explanation and explicitly define either normalized weights or separate lexicographic levels for the repair direction. Preserve zero feasibility boundaries and version changed score semantics. [Guide chapter 12](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/overview)

Why this helps: Skipping impossible acceptance saves allowance without relaxing policy; explicit weighting makes repair priorities reviewable. Extra score levels increase model and migration complexity. Measure skipped-phase CPU and outcomes instead of assuming the full five seconds is saved on every request.

Acceptance: A validated nonzero-overtime reference records a fairness skip and starts no fairness solver under the preserved-target model. Zero-overtime references still run eligible fairness. Isolate minute and cent constraints and prove the chosen weights or level ordering with tradeoff cases; policy acceptance tests remain unchanged.

### CR04 Explain the consequence of zero overtime acceptance

Current behavior: The reference minimizes overtime first, but SchedulingPolicy.compare still rejects a candidate with any overtime. Evidence: R07 and R08. Classification: Low, source-confirmed policy consequence.

Consequence: A substantial partial overtime reduction can produce no applicable preview. This is the current business rule, not proof that the solver found no improvement.

Guidance and change: Include this consequence in TF11 policy documentation and outcome examples. Keep partial reductions as diagnostics if useful, with explicit rejection reasons. Offering them for application requires a separate policy decision and validation contract. [Guide chapter 12](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/overview)

Why this helps: Dispatchers and benchmark analysts can distinguish useful search progress from policy eligibility without silently relaxing promises. Diagnostic presentation adds maintenance work and must not imply an applicable schedule.

Acceptance: A baseline with ninety overtime minutes and candidate with twenty records an improvement diagnostic and policy rejection, with the accepted baseline unchanged. The zero-overtime candidate follows ordinary acceptance checks.

### CR05 Make overnight failures observable

Current behavior: OptimizationService.overnight catches and discards every exception for each metro/day. Evidence: R11. Classification: Low, source-confirmed observability gap.

Consequence: Routing, schema, solver, and future transport failures leave the day unchanged without an actionable failure record. Aggregate success counts can therefore hide missing optimization work.

Guidance and change: Preserve isolation of days while recording metro, service date, attempt identity, failure class, elapsed time, and safe error context. Publish attempted/succeeded/skipped/failed counts with alertable failure rates. Distinguish policy skips from execution failures and protect customer data in logs. This follows lifecycle diagnostics in [Guide chapter 32](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/solver-diagnostics).

Why this helps: Operators can investigate and explicitly retry failed days without blocking unaffected days. Telemetry adds storage and operational ownership; it must not introduce hidden automatic retries or new apply authority.

Acceptance: An injected failure creates a failed-day receipt and increments telemetry, leaves that day unchanged, and allows remaining days to run. Correlation IDs connect caller/service failures without disclosing credentials.

## Computational improvements worth measuring

### CR07 Profile and separate the route timing changes

Current behavior: Scoring constructs directed-leg string keys, uses Instant/Duration arithmetic, and builds arrival maps and segment copies that scalar score consumers do not read. With absences, candidate return-point exploration copies maps repeatedly. TF13 describes separate invariant preparation. Evidence: R01, R13, and R14. Classification: Medium, source-confirmed opportunity; dominance and performance effects are unmeasured.

Consequence: Allocation, hashing, and object copying may constrain move throughput, but source inspection cannot rank them against fleet aggregation or exact decimal arithmetic. Removing explanation data without preserving the independent validator would weaken correctness evidence.

Guidance and change: Establish a warmed throughput baseline and use JFR allocation/CPU/GC sampling or a compatible external profiler in separate diagnostic runs. First measure indexed road lookup, then score-only scalar evaluation versus an explanation path, and primitive time arithmetic as independent treatments. Retain the current independent RouteEvaluator as an oracle. Time offsets need declared epoch, units, overflow bounds, exclusive windows, absences, cross-midnight behavior, and America/Chicago DST conversion at the boundary. [Guide sections 16.2 and 16.3](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/performance)

Why this helps: Measured allocation and CPU shares identify worthwhile work. A score-only path can avoid arrival maps while the selected result still gets full explanations and validation. Primitive arithmetic can reduce objects but introduces unit and boundary risks; no speedup or unchanged search quality is guaranteed.

Acceptance: Each treatment independently passes randomized move/undo and exhaustive fixture equivalence for feasibility, penalty, overtime, paid time, meters, exact cents, and selected-result arrivals. Include DST, return travel, absence blocks, and missing legs. Report preparation and explanation cost, bytes allocated, GC, score throughput, and equal-budget accepted quality with identical instrumentation.

### TF13 Prepare invariant route facts once

Current behavior: RouteScoringFacts.evaluate constructs a temporary DayPlan for each route evaluation. That constructor prepares demanded-service data that the timing path does not need. Capacity and eligibility are recomputed, and RouteTimeline sorts absences and rebuilds availability, including work repeated in its infeasible fallback. Evidence: C10 and C12. Classification: Medium, source-confirmed redundant preparation; speed benefit unmeasured.

Consequence: Local search evaluates many tentative moves. Work that is small once can become expensive when repeated for every changed route. These preparations depend mainly on fixed facts such as shifts and absences rather than on the proposed visit order.

Guidance and change: The performance guide recommends removing unnecessary score work and using incremental calculation. Prepare immutable per-technician availability, capacity, eligibility, and routing context before solving. Pass a compact timing context instead of constructing a temporary planning solution. Precompute only values independent of assignment; paid time and route feasibility still depend on the move. [Guide sections 16.2 and 16.3](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/performance)

Why this helps: Fewer allocations and repeated sorts leave more of the same time allowance for useful evaluation. The tradeoff is invalidation complexity: rates, buffers, absences, shifts, skills, and routing identity must rebuild the appropriate facts. Precomputed dense road matrices may waste memory when the problem needs only sparse directed pairs.

Acceptance: Differentially compare feasible and infeasible timing, overlapping absences, return travel, qualifications, missing legs, changed buffers, and clone isolation. Measure preparation time, allocations, move throughput, and accepted quality separately. Profile CR07 and this preparation separately before choosing their order; their likely costs are not established by source inspection. Keep separate equivalence checks for each change.

### CR08 Choose score representation after the numeric contract

Current behavior: DayPlan uses HardMediumSoftBigDecimalScore and fairness computes DECIMAL128 weighted variance. Evidence: R15 and R16. Classification: Medium, unmeasured opportunity after TF04.

Consequence: Decimal calculation may allocate and consume CPU on every score update, but changing representation can alter ties, fairness ceilings, and overflow behavior. Allocation profiling must establish its contribution first.

Guidance and change: Define exact cents and required fairness precision before proposing scaled integer scoring. In Timefold 2.x, HardMediumSoftScore is long-based; HardMediumSoftLongScore is a removed 1.x name. Inspect the pinned 2.6.0 API and migration guidance rather than copying that name from the second review. If CR02 adds assignment coverage, select a compatible multi-level representation, such as BendableScore or BendableBigDecimalScore, instead of squeezing four priorities into three. Measure score representation independently of TF12 reversible aggregation. [Guide chapter 12](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/overview)

Why this helps: A measured representation change may reduce cost without replacing business semantics. Scaling introduces explicit quantization and overflow bounds; any changed tie tolerance is a policy revision. Algebraically equivalent formulas need not produce identical decimal rounding.

Acceptance: Verify pairwise ranking against an exact decimal oracle, ceiling boundaries, negative scores, aggregate bounds and overflow, and declared tie precision. Representation-only and aggregate-only treatments each pass equivalence before combination; compare allocation and throughput with accepted quality separately.

### TF12 Replace repeated fleet scans with equivalent incremental totals

Current behavior: Constraint Streams reuse unchanged route tuples, which is already valuable. The fleet group collects all route results into a list, then cost, target violations, and fairness repeatedly scan that list. Fairness also builds diagnostic workload objects. Evidence: C10 and C13. Classification: Medium, source-confirmed optimization opportunity.

Consequence: Moving one visit can require fleet-wide aggregation even when only one or two route timelines changed. Incremental route scoring therefore does not make the whole score calculation incremental. Larger fleets can spend more time aggregating unchanged values.

Guidance and change: Use reversible scalar collectors for the totals needed by the exact cost and fairness definitions. Maintain paid minutes, overtime, mileage, and policy-equivalent fairness components on add/remove. Keep diagnostic object construction outside the hot score path. Timefold's standard load-balancing collector is worth evaluating only if it represents our policy; it is not automatically equivalent to capacity-weighted utilization variance. [Guide section 16.3](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/performance)

Why this helps: Updating sufficient totals can reduce work from rescanning the fleet to updating changed contributions. However, different decimal rounding order can alter a weighted variance even when formulas are algebraically equivalent. First specify exact money and required fairness precision. Do not substitute an easier equal-load objective for the current capacity-aware policy.

Acceptance: Add/remove and move/undo sequences must agree with the independent oracle for cents, target violations, and fairness precision. Include unequal capacities, no eligible capacity, empty routes, one-technician fleets, and absences. Demonstrate throughput and equal-budget quality improvements across fleet sizes before adopting the extra collector complexity.

### TF14 Bound expanded booking work before allocating arrangements

Current behavior: Above six shortlisted routes, expanded neighbor generation materializes relocation, swap, and reversal descriptors. moveTravel applies moves and constructs arrangements before sorting and truncating them. The ordinary six-route BOUNDED path already limits and interleaves move families. Evidence: C02. Classification: Medium, source-confirmed opportunity concentrated in expanded variants.

Consequence: Work spent allocating discarded candidates consumes the same deadline that could evaluate retained candidates. This explains a plausible bottleneck; it does not establish a measured speedup or show that the default path has the same problem.

Guidance and change: Timefold's move-selector guidance explains why just-in-time generation avoids storing huge neighborhoods. Apply that principle to the custom booking algorithm through bounded top-k selection or lazy generation, using cheap directed-edge deltas before constructing full arrangements. Preserve stable tie order and the intended candidate families. [Guide section 42.1](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/move-selector-reference)

Why this helps: The search can spend less time preparing candidates it will never inspect. But changing generation order changes which candidates receive time before cancellation. A geometric approximation is not automatically a safe pruning bound on road cost or feasibility. Treat allocation reduction and search-quality change as separate claims.

Acceptance: Compare candidate order and coverage on exhaustive small cases with asymmetric roads and ties. Validate interruption, required-leg collection, returned offers, and incomplete flags. Measure generated descriptors, materialized arrangements, allocation, offer quality, and full-request latency.

### Scale independent work before splitting a coupled problem

Current behavior: Requests already use separate solver instances, and admission bounds expensive work. Road matrix preparation contains sequential cold-pair work. Fleet cost rounding and fairness couple routes, so partitioning technicians would change the optimization problem. See C03, C10, C11, and C16.

Guidance and change: Begin with bounded parallelism across independent metro/day snapshots or requests, counting all CPU consumers. Separately evaluate a small routing worker pool after confirming GraphHopper thread safety, single-flight cache behavior, cancellation, and routing identity. Use separate single-threaded Community solver instances and bounded independent JVM cases. Enterprise move evaluation and partitioned search are outside this Community design; custom multithreaded move evaluation and a solver fork remain deferred. [Multithreaded solving guidance](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/multithreaded-solving)

Why this helps: Independent jobs retain the full optimization objective. More threads can reduce individual latency or improve throughput, but can also slow every request through shared caches, memory bandwidth, GC, and queueing. A dedicated client process does not fix an unscoped database query. Framework and native-image changes are separate deployment experiments. Community parallel independent solves preserve the complete objective but cannot promise Enterprise-equivalent throughput or diagnostics.

Acceptance: Use the same binary and snapshots for serial versus concurrent studies, record total CPU and memory limits, and measure throughput together with p95 latency and quality. No thread count or framework switch should be selected from vendor charts or a run on a different revision.

### CR10 Keep every proposed configuration on Community Edition

Current behavior: SolverExperiment enables no Enterprise features but uses internal diagnostics; the guide lists score analysis, Recommendation API, nearby selection, multithreaded solving, partitioned search, constraint profiling, and multistage moves as Enterprise features. Evidence: R10 and local guide section 62.2. Classification: Low, source-confirmed edition gap in the previous recommendations.

Consequence: A design assuming those capabilities could fail to build with the current edition or silently add a commercial dependency. Feature names and public packages alone do not establish Community availability or stable compatibility.

Guidance and change: Use Community Edition as a fixed constraint, with no purchase evaluation in the sequence. Retain custom booking insertion/refinement and independent candidate validation. For ordering, evaluate application-owned directed-road estimates and bounded top-k generation, with exhaustive ordering/coverage checks; do not configure Enterprise nearby selection. Produce business explanations from independent violations, coverage, overtime, cents, and policy reasons. Use available Community benchmark statistics, ConstraintVerifier, JFR, and external allocation profiling. Supported public solution/score facilities require version and edition checks; do not assume Enterprise Score Analysis or constraint profiling exists. [Guide chapter 32](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/solver-diagnostics)

Why this helps: The plan remains implementable without a license purchase. Community application explanations and external profilers provide useful evidence but have their own maintenance costs and do not reproduce all Enterprise diagnostics. Parallel independent solves remain available without custom parallel scoring.

Acceptance: Record artifact, version, edition, and API status for every dependency. Public solver/factory/configuration and supported benchmark APIs form the engine boundary; preview APIs require an opt-in compatibility gate, and DefaultSolver, phase scopes, and internal score-director access remain exact-version diagnostic adapters under TF09. Verify advanced move selectors against the pinned release rather than treating all XML settings as equally stable. A Community-only smoke build exercises every proposed treatment; reject Enterprise properties. Defer custom multithreaded move evaluation and forks until evidence and a separate design justify them.

### CR11 Defer shadow variable remodeling until timing remains dominant

Current behavior: A route-changing move causes the Constraint Streams map node to recompute route timing, including a dynamic program over availability blocks. Evidence: R19 and R01. Classification: Low, unmeasured modeling opportunity.

Consequence: A late-route move may recompute earlier timing, but routes can be short and the dynamic program couples return choices across a route. Incremental per-visit arrivals are not automatically equivalent to this policy.

Guidance and change: Reconsider shadow variables only after profiling CR07, TF13, and TF12 changes. Timefold 2.x uses declarative custom shadow variables; the legacy VariableListener API was removed and is not a remodeling option for 2.6.0. Check public/preview status in the selected release. Preserve global block/return decisions and supported list-variable lifecycle behavior. [Guide section 10.8](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems)

Why this helps: Incremental arrivals may reduce repeated work on longer routes; the cost is a new model with more invalidation, pinning, and undo invariants. Profiled timing dominance is a prerequisite, not evidence of eventual speedup.

Acceptance: Record the remaining timing CPU share and route-length sensitivity before starting. Prove equivalence for moves, undo, absences, return points, pinning, clone isolation, and infeasible repair under assertion modes. Adopt only after matched quality and performance evidence.

## Benchmark redesign

### TF07 Measure the question we intend to answer

Current behavior: Each daily case launches a fresh JVM, warms the selected strategy for 200 ms on SPARSE/20, then measures a synthetic case. Solver seeds change search on fixed geography. The daily-budget archive ran ten cases concurrently, with each process assigned a physical core and configured with ActiveProcessorCount=2 and SerialGC. The current analysis preserves pairings, missing values, and seed ranges, but those ranges are descriptive rather than confidence intervals. Evidence: C17 through C20. Classification: Medium, confirmed method gap; direction and size of warmup bias remain unmeasured.

Consequence: Compilation, processor boost, memory contention, and workload choice can influence the apparent winner. A long case may eventually warm itself, but the amount of search lost during early compilation can differ by configuration. Affinity prevents sharing one core; it does not isolate shared cache, memory bandwidth, or thermal limits. Ten seeds on one geometry measure search variability on that geometry, not ten different customers or service regions.

Guidance and change: The local guide's chapter 31 describes explicit solver comparisons, reusable input solutions, warmup, repeated sub-runs, and score/statistic histories. Adopt these capabilities in a dedicated native benchmark layer, while preserving our archive and policy evaluation. The official benchmarker defaults to thirty seconds of warmup and one sequential benchmark; repeated sub-runs must be configured. These defaults are starting points, not proof that WaterFlex is warmed or statistically representative. [Guide chapter 31](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/benchmarking-and-tweaking)

Why this helps: A layered protocol makes a measured difference interpretable. It separates faster score calculation from better final decisions, and isolated solver quality from customer-visible service behavior. The cost is a richer dataset contract and more disciplined experiment management. It is smaller and safer than replacing a provenance system that already works.

Acceptance: A campaign must identify its question, baseline, independent datasets, repeated seeds/processes, time/resource envelope, outcome metrics, exclusions, and decision rule before measurement. Smoke runs demonstrate harness operation only. Neither a native report nor a large case count automatically establishes production value.

### Three layers with explicit responsibilities

| Layer | What it measures | Proposed mechanism |
| --- | --- | --- |
| A Fixed solver problem | Which configuration finds a better valid solution to the same reference or fairness problem at the same allowance? | Native Timefold Benchmarker on frozen DayPlan inputs with independent validation of outputs. No database or live routing in the measured solve. |
| B Complete daily policy | Does reference search followed by fairness produce a better accepted schedule under the actual combined budget? | Retain and harden SolverBenchmark's orchestration, with production-equivalent policy and validation. Measure the whole pipeline and its phases. |
| C Application workflow | What latency, throughput, useful outcomes, and cleanup does the caller observe under load? | Dedicated booking and daily API tests with controlled routing caches, queueing, database work, reservations, and cancellation. |

Layer A is appropriate for TF12/TF13 and algorithm tuning. Layer B establishes whether a local gain survives policy acceptance and phase allocation. Layer C tests architecture, admission, routing parallelism, or remote deployment. A better Layer A score cannot establish a Layer C latency improvement.

Native Benchmarker consumes one planning solution and its configured phases. Our daily pipeline sets the fairness target only after evaluating the reference result. Do not model that by blindly appending two local-search phases: the target transition and independent policy checks are application behavior. Keep that orchestration in Layer B.

### Use native Benchmarker without losing archive integrity

Current gap: The repository has a custom Java entry point and Python orchestration, but no native benchmark dependency. We manually collect selected totals and time-to-best; we do not retain the full native score/statistic histories. That makes search progression harder to diagnose and creates maintenance work for features the library already provides.

Proposed integration: Add timefold-solver-benchmark only to a benchmark/test dependency scope, pinned by the same BOM as solver core. Build explicit configurations from a shared configuration builder so benchmark and production settings cannot drift silently. Inherited native phases are appended rather than simply overridden, so inspect the effective phase list and avoid accidentally running duplicate local-search phases. Use a custom thread-safe SolutionFileIO adapter that reads a validated WaterFlex snapshot into fresh domain objects and writes replayable output. These are future implementation changes, not dependencies added by this report.

Configuration example: A Layer A reference case may use parallelBenchmarkCount=1, explicit seeds, environmentMode=NO_ASSERT, spentLimit=10s, and TABU change/swap, paired with LA change/swap. The campaign JSON declares warmup and repetitions; these example values are not mandatory budgets. Each fresh JVM warms its own measured paths, even when multiple independent JVM cases run in parallel. These are native property names described in guide sections 31.2 and 31.7; a full XML file must be validated against 2.6.0 before use. The warmup allowance applies to the benchmark invocation, not automatically to every treatment path or dataset. Separate batches and explicit warmup coverage prevent that assumption.

Keep SolverExperiment's existing cap-preservation regression when sharing configuration. Native output should be written outside build-clean directories, inside a new attempt directory, with raw result XML, statistic CSVs, report HTML, solved snapshots, effective config, and hashes. Keep generated bulk output out of source control while retaining compact receipts and summaries. Native HTML is useful for exploration; normalized WaterFlex results remain the comparison authority for business metrics.

Failure semantics need an adapter. The native benchmarker normally continues its remaining single benchmarks, writes its report, then fails if any case failed. Our outer runner stops dispatching after the first failed case. Define a native batch as the outer scheduling unit: allow native diagnostic completion inside that batch, retain each failed sub-run, then stop launching new batches. Never accept an incomplete report as a successful batch. Do not add automatic retries; an explicit resume creates a new attempt and retains the prior failure. See guide section 31.2.3 and C19.

Verification: Test effective configuration, phase count, native output import, unavailable statistics, solution read/write, failure propagation, interruption, and resume. Record the actual effective seed for every sub-run: native subSingleCount changes random seeds, and a sub-run index alone is not a reliable cross-engine pairing key. If a required seed schedule cannot be expressed faithfully, generate explicit single-run configurations instead.

### Dataset design separates coverage from repetition

Current gap: SolverBenchmarkData.create accepts fleet size and workload but no geography seed. Repeating seeds therefore reruns one deterministic directed-leg fixture per fleet/workload combination. These fixtures are valuable regression cases, but the eight geometries in the retained daily-budget study cannot represent the variety of actual operating days. Evidence: C18.

Proposed corpus: Keep legacy fixtures as a named regression cohort. Create a versioned synthetic generator with independent dataset seeds that vary positions or directed matrices, durations, skills, windows, absence placement, and initial assignments while enforcing declared feasibility. Add anonymized historical snapshots when available, with frozen routing-provider identity and all facts needed for offline replay. Until such data is available, explicitly label the corpus synthetic and withhold production claims.

Separate four initial-state classes: valid assigned warm plans, unassigned/partial cold plans, infeasible repair plans, and invalid inputs. Invalid inputs belong in contract tests, not score rankings. Cold planning is eligible only after TF02 is resolved. Repair reports time to feasibility and unresolved demand; it must not be merged with feasible-start cost comparisons.

One worked corpus example contains twelve independent datasets: two fleet sizes, 20 and 50; three scenario families, clustered, dispersed, and absence; and two dataset seeds per family/size. In that example another twelve with different dataset seeds form a sealed confirmation corpus. Dataset families, fleet sizes, and all seed lists are explicit configuration, not required counts. Each generator output must demonstrate its declared structure, not merely have a different hash. This is a tractable first design, not a claim of population coverage. Add mixed-skill, tight-window, near-capacity, and real-day cohorts before deployment decisions beyond the covered families.

Hold out whole datasets, or whole date/metro groups for historical data. Do not split near-identical copies of one operating day across tuning and confirmation. Freeze assignment counts, route facts, and control snapshots before running candidates. Use the same immutable input for every treatment; warmup must never mutate a measured input or seed a later budget from an earlier result.

Why this helps: Dataset variation tests generalization, search seeds test stochastic sensitivity, and independent JVM forks test process variation. These are different sources of uncertainty. Counting every seed/fork result as an independent operating day would exaggerate confidence.

### Calibrate warmup and distinguish cold states

Current gap: The 200 ms warmup is too brief to assume all relevant JIT compilation and paths have stabilized. Booking's cold/warm labels concern scheduler caches; they do not reset the shared road provider cache. A warm routing cache and a warm JVM are separate conditions. Evidence: C17 and C19.

Worked example: Compare 0.2, 30, and 60 seconds of warmup on two representative fixed datasets, each for TABU and LA and both reference and fairness paths, with three fresh JVM repetitions and a ten-second measured probe. This gives 72 observations. Use an explicit seed schedule and balanced execution order. Keep warmup output separate, record compilation/GC activity and interval throughput, and inspect whether later warmup changes the measured distribution.

The decision rule for this pilot is an illustrative configurable analysis rule, not a production default or Timefold requirement: provisionally accept sixty seconds only when the 30-versus-60-second probe medians differ by no more than five percent in move throughput within each tested path and interval traces show no persistent startup trend. Three forks are a diagnostic screen, not statistical proof of stationarity. If the screen fails or is ambiguous, explicitly configure another calibration attempt, for example 120 seconds with more repetitions, and retain the failed attempt. Do not silently select experiments or auto-retry. A changed algorithm, JVM, heap, or dataset scale can require recalibration.

For main batches, warm the same code paths and phase as the measurements with disposable copies, then reload clean inputs. Fix and record process lifetime and ordering. Keep parallel independent JVM cases configurable. For every fresh JVM require path-specific warmup, bounded heap/CPU allocation, and move threads disabled; set native internal parallelism to one to avoid nested benchmark parallelism. Calibrate outer concurrency, comparing only matching concurrency/runtime configurations. Record CPU model, topology, affinity, heap, GC, JDK, loaded dependencies, OS, and competing activity. Existing SerialGC/two-logical-processor results form a separate cohort; do not call those settings inherently wrong, but do not equate them with a different deployment runtime.

Record the deployment model explicitly. A long-lived warmed-service study requires warmup; a scale-to-zero Layer C cold-start cohort deliberately includes startup and the first request without warmup. Do not substitute cold-start measurements for warmed solver comparisons. Warm-service tests reuse a service process after the accepted warmup. Scheduler-cache-cold tests explicitly clear only the documented scheduler caches and record provider state as unchanged/unknown unless a separate controlled provider instance is reset. Complete latency must state whether process startup, dataset loading, and network transport are included.

Why this helps: It prevents compilation cost or an already warmed provider from being misattributed to algorithm quality. The tradeoff is calibration time and more runtime metadata. A fixed seed with elapsed-time termination still does not guarantee identical routes across repetitions because the amount of work completed before the clock expires can differ.

### Keep reference and fairness comparisons meaningful

Current gap: SolverBenchmark first computes its own reference, then sets that result's overtime and cost ceiling as the fairness target. Different configurations can enter fairness with different assignments, reference cost, and headroom. The existing custom comparison primarily pairs reference cost; that is useful, but it does not isolate fairness algorithm quality. Evidence: C17 and C20.

Worked example: Suppose configuration A finds a $1,000 reference and B finds $1,020, both with the same overtime. Their two-percent fairness ceilings are $1,020 and $1,040.40. If B returns a more balanced schedule costing $1,035, that solution is permitted for B but prohibited for A. Concluding that B's fairness search is better from variance alone would compare different feasible regions. These are illustrative amounts, not measured WaterFlex outcomes.

Layer A reference experiments start from identical assigned snapshots with no fairness target. Layer A fairness experiments start from one shared, independently validated reference snapshot and the same frozen target for every candidate. Create that reference once per dataset with the named control protocol before the study, record its provenance, and never regenerate it per treatment. Stratify results by phase; a reference medium score represents overtime while a fairness medium score represents variance, so even the score levels have different meanings.

Layer B intentionally lets each configuration produce its own reference, because that is the real workflow. Compare the resulting accepted schedule using independently measured overtime, reference and accepted cents, fairness, and acceptance reason. Report cost and fairness tradeoffs jointly. Preserve the current transfer of unused reference time to fairness and record actual phase durations. A rejected candidate counts as the unchanged accepted baseline, with the rejected proposal and reason retained.

Why this helps: Isolated fairness experiments compare search ability on the same problem, while the pipeline experiment measures the consequences of each configuration's reference quality. Neither substitutes for the other. Score percentages or benchmarker ROI labels must not be presented as payroll savings, especially with negative or multi-level scores.

### Choose measurements that explain outcomes

| Measurement | Interpretation and required boundary |
| --- | --- |
| Best score over time | Show when improvements occur within one phase and target. Store individual traces; do not mix reference and fairness score semantics. |
| Accepted cost and fairness | Independently calculate the final business outcome after policy acceptance. Report reference and accepted cost separately. |
| Time to first feasible and time to best | Useful for cold/repair behavior and convergence. Unreached feasibility is censored or explicitly unresolved, never zero seconds. |
| Move and score throughput | Diagnostic work rates. Ruin/recreate can perform many score calculations per move, so higher score throughput is not automatically more search or better quality. |
| Phase and full-request duration | Distinguish solver time, copying, preparation, validation, queueing, persistence, and network time. A fixed time limit makes equal solve duration largely uninformative. |
| CPU allocation and memory | Record process CPU, allocation/GC diagnostics, heap and resident memory where measured. Heap-pool peaks are not resident memory and need explicit units. |
| Outcome and completeness | Preserve available, incomplete, conflict, infeasible, rejected, failed, and cancelled outcomes. Some properties overlap, so avoid misleading stacked totals. |

Use native BEST_SCORE, MOVE_EVALUATION_SPEED, and SCORE_CALCULATION_SPEED statistics where supported by the pinned benchmark artifact. Store effective enabled statistics because defaults can change. Add only pinned-version Community diagnostics in separate batches. Per-move-type data or internal constraint-match access requires an exact-version adapter; Enterprise constraint profiling and Score Analysis are excluded. Detailed tracing and constraint profiling can perturb timing; compare with the same instrumentation or keep the diagnostic cohort separate. The local guide explains these distinctions in sections 31.4 through 31.6 and 16.11.

At fixed elapsed-time budgets, select on valid accepted quality and useful completion, not on who reports approximately the configured number of seconds. For an implementation-equivalence optimization, also use a fixed-work replay or step-limited diagnostic to compare identical work and expose score divergence. A step is not equal computational work across Tabu, LA, or different neighborhoods, so step limits are not the primary algorithm-quality budget.

### Analyze uncertainty without inflating the sample size

Current gap: The existing equal-seed summaries and min/max bands honestly describe the retained runs, but cannot quantify generalization to new datasets. Pooled averages can hide workload reversals. The retained 50-technician dispersed group is a concrete reason to keep subgroup results visible.

Proposed analysis: Pair treatment and control on dataset hash, phase/target, budget, effective seed, fork block, scoring version, routing identity, and runtime cohort. Pairing a common seed does not make two algorithms consume randomness identically; it is a blocking convention. Reject duplicate logical cases and preserve unmatched observations with an exclusion reason. Report requested, completed, valid, and paired counts separately.

Compute per-dataset paired effects after averaging the repeated seeds and forks within that dataset. Report each dataset, scenario-family summaries, and an equal-dataset overall summary. For the sealed confirmation, calculate a 95 percent paired cluster-bootstrap interval by resampling whole datasets, retaining all their paired repetitions, with draw count, interval level, and bootstrap seed declared in the JSON. A worked analysis example uses 10,000 draws, 95 percent confidence, and seed 20261006. This is a proposed WaterFlex analysis method, not a statistical guarantee supplied by native Benchmarker. With a small synthetic corpus, such as twelve datasets, inference remains exploratory and applies to that corpus; more seeds do not increase independent dataset coverage.

For multiple candidates, use tuning data to choose one finalist, then compare only that finalist with the control on untouched confirmation data. Do not keep looking at confirmation results while changing configuration. Report subgroup regressions and practical effect sizes even when an aggregate interval looks favorable. An interval containing no improvement means the study did not resolve the choice; it does not prove equality.

Booking cost comparisons additionally require the same served customer identities and initial schedule. If concurrency changes which customers are served, compare coverage and outcome rates and mark aggregate cost differences as noncomparable for savings. Report missing metrics as null with a reason. Failed cases remain in reliability denominators even when no quality score exists.

For application latency, retain individual request durations, client submission times, queue times, and timeout thresholds. Report p50/p95 and sample counts by load/cache/outcome, plus failure rates over all requests. Do not drop timed-out requests from the denominator or substitute the timeout threshold as their exact completion time. An arrival-paced load generator should record intended and actual submission time so server stalls cannot hide demand by slowing the generator itself.

### One versioned JSON file owns campaign parameters

The proposed schemaVersion=2 interface is design guidance; today's version 1 runner does not implement it. Preserve existing configurations and archives through an explicit version dispatch and migration/import path. Never reinterpret an old solver seed as a dataset seed or historical warmup as calibrated behavior. The companion evidence/fourth-review/campaign-example.json demonstrates a complete parameter inventory, not a file runnable by run_experiment.py.

The CLI selects the configuration file and operation, such as validate, dry-run, run, resume, or analyze. It does not override seed, budget, heap, CPU, load, or analysis settings. One JSON file must contain every adjustable parameter, including fully expanded profile values; no mutable external profile alias may silently change a campaign. Immutable dataset files can be referenced by path and hash. Credentials stay in the existing secret mechanism or environment and never enter this JSON or its archived resolution.

| Contract | Required information and behavior |
| --- | --- |
| Campaign identity | Schema version, campaign ID, purpose, Community edition, layer, deployment model, control/treatment IDs, scoring version, dataset paths/hashes or generator version and dataset seeds, scenario families, initial-state classes and tuning/held-out roles. |
| Search and allowance | Algorithms, acceptor/forager settings, move families/weights/bounds, effective solver seed list, process repetitions, phase/total budgets, phase allocation and unused-time transfer, validation reserve, fixed-budget and phase termination policies with every window/ratio/threshold. |
| Execution resources | Outer parallel cases, CPU allocation and affinity policy, warmup/calibration policy and coverage, JVM path/version, heap min/max, GC, ActiveProcessorCount and flags, process lifetime and environment mode. No move threads or nested native parallelism. |
| Measurement and analysis | Instrumentation and sampling settings, metric availability, diagnostic cohort, pair keys, outcome rules, uncertainty method/draws/seed/level, scenario groups, promotion tolerances, execution-order seed and explicit failure/resume behavior. |
| Application and output | Load mode, request count/rate/concurrency, cache state, endpoint operation, timeout and cancellation observations, output location, optional campaign cutoff declared null when disabled. Layer-inapplicable fields use explicit applicability rules rather than fabricated data. |
| Dataset and result | Indexed directed-road contract from CR09; canonical input/config/target hashes; attempt/case identity, effective seed, outcome and reason, validation, reference/accepted metrics, actual phase/CPU durations, raw output and trace hashes. Unknown measurements are null with a reason. |

Archive the original JSON bytes and a fully resolved configuration before dispatch, plus canonical configuration hashes and a deterministic expanded case order. In a separate runtime receipt record discovered hardware/topology, actual affinity/CPU limits, effective JVM flags, loaded dependency/JDK versions, artifact hashes, OS and competing activity. The configuration states requested resources; receipts state what actually ran. Unsupported settings fail explicitly instead of being ignored.

Validate all required objects, arrays, finite numbers, units, positive budgets, nonnegative overhead estimates, unique identities, seed types, phase allocation, directed-leg completeness, and target compatibility before copying. Reject unknown fields, duplicate JSON keys, nonfinite JSON numbers, unsupported versions, and missing required values. Declare optional cutoff and genuinely unavailable metrics nullable with reasons; missing scheduling facts never become zero or empty data. Community edition and nativeParallelBenchmarkCount=1 with moveThreads=NONE are acceptance conditions, not upgrade hints.

Resume may reuse only complete hash-verified comparison blocks under identical resolved configuration, artifacts, inputs, runtime cohort, and calibration. A restart warms its fresh JVM again. Retain partial native batches and failures, and put explicit reruns in new attempts without double counting. Preserve the measurement lock and do not overlap campaigns with unrelated heavy work.

### CR06 Evaluate termination by algorithm and phase

Current behavior: Production TABU uses spent allowances; CURRENT_CAPPED also preserves a step cap. No current configuration applies diminished returns or unimproved time. Evidence: R10 and R12. Classification: Medium, unmeasured opportunity.

Consequence: Some solves may spend allowance after useful progress stalls, but a plateau does not prove convergence. Early stopping can lose a later improvement, change fairness targets, and alter complete-pipeline quality. Step counts and CPU seconds are different measures.

Guidance and change: Retain a fixed-budget control for each algorithm and phase. Cross termination policy with TABU and LA and with reference, fairness, and repair where eligible. Test a local-search diminished-returns window/ratio and an unimproved-time threshold inside an overall spent allowance. Configure windows relative to the allowance; the guide's thirty-second diminished-returns default is longer than a fifteen-second pipeline. The guide shows OR composition of diminished returns with a spent limit and also documents local-search phase configuration. Do not claim that any step cap universally disables diminished returns, or that every combination and scope is valid: distinguish explicit core composition from framework auto-configuration and compile/check effective settings against 2.6.0. A solver-level spent cap can bound the enclosing solve while local search uses its own termination; preserve existing caps when applying overrides. Pinned core factory bytecode was inspected for configuration construction, without executing proposed treatments. [Guide section 37.2.4](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/overview)

Why this helps: An eligible policy may reduce resource occupancy on particular workloads. It cannot guarantee convergence or unchanged quality, and a policy selected on TABU cannot be assumed best for LA, a new neighborhood, or another phase. Keep policy parameters in the JSON and treat selection as a measured interaction.

Acceptance: Community-only configuration smoke checks prove solver/phase scope, preserved caps, applicable termination reason and unknown-reason handling. Compare valid accepted cost, fairness, coverage, incomplete/failure rates, CPU seconds, elapsed time, and p95 request cleanup with the corresponding fixed-budget control. Measure cooperative overshoot and keep late-improvement cases visible. Choose parameters on tuning data and confirm on held-out datasets before changing defaults.

### Coverage allowance and resources are separate decisions

Coverage is the selected datasets, algorithms, moves, termination policies, seeds, repetitions, phases and budgets. Per-solve allowance is the time each selected solve may consume; phase allocation and validation reserve define its usable search portion. Execution resources are CPU, heap and concurrency. Raising parallel cases does not increase dataset coverage or shorten the configured solve allowance, and longer solving is a different comparison from more repetitions.

Short daytime studies can explicitly choose fewer tuning datasets or seeds and production allowances, with calibrated outer concurrency. Longer overnight studies can explicitly expand coverage or include longer allowances. Both use the same schema and operations; no required preset, automatic campaign selection, mandatory stage count, or fixed total duration is imposed. Production-budget evidence remains primary for production decisions. Longer-budget results answer separate sensitivity or overnight questions and cannot establish fifteen-second endpoint quality.

Dry-run expands the JSON into a saved matrix without solving. Report cases, fresh JVMs, comparison blocks, required fairness-reference setup, budget cohorts, warmup coverage, resource demand, and both nominal allowances and overhead estimates. For one rectangular phase cohort, N = A x M x T x D x S x R x B, where A is algorithm configurations, M is move packages, T termination policies, D datasets, S solver seeds, R process repetitions, and B budget values. For uneven matrices, sum valid expanded cases instead of multiplying incompatible factors. Do not count a move package twice if it is already part of A.

Nominal solve allowance is the sum of configured per-case budgets, counting the combined pipeline once rather than twice for its phases. Warmup allowance is the sum over fresh JVMs of their path-specific warmup. Add preparation, startup, validation, export/reporting, reference setup, and analysis overhead separately. Serial allowance is their sum. Dividing parallelizable case work by P gives an optimistic estimate, not a completion guarantee; serial overhead, uneven cases, contention, GC, cooperative overshoot, and early termination change observed wall time. Use a calibration pilot to refine estimates and retain both requested and observed times.

| Stage | Worked example only | Calculated nominal allowances |
| --- | --- | --- |
| Production reference | 2 algorithms x 3 termination policies x 4 datasets x 2 solver seeds x 2 repetitions x one 10-second budget; one fresh JVM per case, 30-second warmup and 4 seconds estimated startup/preparation/validation/reporting per case. | 96 observations; 960 seconds solving + 2,880 seconds warmup + 384 seconds overhead = 4,224 seconds serial; optimistic 2-worker estimate 2,112 seconds or 35.2 minutes. |
| Separate longer reference | Same coverage and runtime, explicitly replace the budget list with one 60-second allowance in another registered configuration. | 96 observations; 5,760 seconds solving + 2,880 seconds warmup + 384 seconds overhead = 9,024 seconds serial; optimistic 2-worker estimate 4,512 seconds or 75.2 minutes. |

These worked examples are checked in campaign-design.json; they are not measured results or prescribed studies. A batch may amortize warmup across cases in one JVM, but its case grouping and process lifetime must be explicit and matched. Fairness experiments additionally freeze one independently validated control reference per dataset; their setup solves, warmup, and validation are separate estimated work. Booking duration depends on its explicit request count, load mode, startup, cache and timeout settings and needs an observed pilot rather than a fictitious linear concurrency formula.

A campaign cutoff is separate from solve termination. It stops dispatching new comparison blocks, with a block containing matched control/treatment cases and their required warmup. Finish an already dispatched block at its configured allowances; do not silently reduce a solve because the cutoff is near. The cutoff is therefore a dispatch boundary, not an exact stop time. User cancellation or failure may interrupt a block; preserve attempted, missing, failed, cancelled and incomplete rows. Resume completes eligible missing blocks in a new attempt while retaining original evidence and preventing duplicate paired observations.

### Order the studies by the question they answer

Begin with contract, independent-validation, assertion, configuration and failure/resume checks, followed by a small non-ranking smoke. Build the dataset, source/configuration receipts, shared-target fairness, deterministic matrix, importer, and uncertainty foundations before timing changes. Next calibrate warmup and outer concurrency for the declared deployment model and establish an allocation/throughput baseline. These are dependencies, not compulsory numbered campaign durations.

Use that baseline to evaluate termination by algorithm and phase under production allowances, with fixed-budget controls retained. Compare acceptance packages on matched moves; vary one move family when justified. Measure CR07 indexed lookup, score-only evaluation and primitive time arithmetic, TF13 invariant preparation, CR08 score representation, and TF12 reversible aggregates independently after their correctness contracts. Keep diagnostic profiling separate from quality runs when instrumentation differs. Hold out whole datasets, freeze the finalist, then confirm the complete policy pipeline and application behavior at the intended CPU/concurrency envelope.

Parallel independent JVM cases are preserved in all eligible solver studies. Calibrate P under bounded resources; serial and concurrent cohorts remain separate. Every JVM warms its measured paths, and native internal parallelism stays one. Application studies additionally cover controlled cold starts or warmed services, routing state, paced arrival load, queueing, cancellation, and observable overnight failures. Shadow-variable remodeling remains a later evidence-dependent option. Custom multithreaded move evaluation, solver forks, and Enterprise evaluation are not part of this sequence.

### Promotion gates and implementation order

Correctness gate: No candidate may drop demand, weaken windows or cutoff policy, fabricate missing facts, or bypass independent validation. A single unexplained invalid accepted result blocks promotion. Contract and assertion tests are prerequisites; timed runs do not replace them.

Quality gate: Select one finalist on tuning data. On the untouched production-budget pipeline confirmation, require no additional failures or incomplete results and no overtime increase on any matched valid dataset. Report paired accepted-cost and fairness effects separately. For a cost-led default change, the upper bound of the 95 percent interval for candidate-minus-control accepted cost must be below zero, with no worse mean fairness in any predefined scenario family and no higher scenario-family mean cost. This is a conservative proposed project gate, not a vendor rule or proof of production benefit. A fairness-led change with higher accepted cost requires a separately specified business tradeoff before running that study.

Performance gate: A computation-only refactor must preserve exact policy outcomes under fixed-work equivalence checks, then improve throughput or latency in a matched runtime cohort. A service change must satisfy the existing request contract and show queue, cancellation, and resource behavior under the registered load. Predeclare a noninferiority tolerance for latency based on the deployment SLO before an application promotion campaign; this report does not invent a new daily endpoint SLO where none is defined.

Implementation sequence: Define CR09 indexed datasets before TF03 validation; resolve TF02/CR02 coverage, TF04 exact money, TF05/CR01 cooperative lifecycle, and independent acceptance. Align policy skips, weighting, overtime consequences, and overnight observability under CR03 through CR05 and TF11. Build the versioned Community configuration, datasets, native adapter, shared targets, import/resume checks, and uncertainty framework. Calibrate warmup/concurrency and profile a baseline before any hot-path optimization. Evaluate independently proven changes and algorithm/phase termination interactions, confirm one locked finalist on held-out production-budget inputs, then measure application load and cleanup. Preserve production defaults until the relevant gates pass and change one behavior class per implementation PR.

## Retained evidence and limits

The earlier archives remain valuable records of the code and settings that produced them. This rewrite preserves their manifests and prior validation receipts; it does not claim a new full traversal of every raw archive. The delivery verifier checks the retained review artifacts and historical Git identities. A new campaign must create new evidence rather than relabeling an old run as warmed or production-representative.

| Evidence | What it supports and what it cannot establish |
| --- | --- |
| Booking a752ef8e | The retained review validated 360 cases at revision e9c01d1. Only 60 sequential treatment pairs match served customers; 120 concurrent pairs do not. This is warm scheduler-cache evidence, not controlled cold-provider latency or general savings. |
| Daily 331df1f1 | The retained review validated 1,280 cases over four variants, four fleets, two workload families, ten seeds, and 60/90/120/240-second budgets. Eight fixed synthetic geometries and ten parallel cases limit generalization. Zero recorded violations is a useful fixture result, not a production guarantee. |
| Daily subgroup reversal | In the retained 50-technician dispersed group, all 40 cases per alternative had higher accepted cost than matched TABU, despite some fairness improvements. Pooled means cannot select one global winner. |
| Contention 931421d0 and 912c78be | Two 24-case studies compare six versus one parallel cases on different source revisions. Their observations cannot isolate the causal effect of concurrency. |
| Interrupted 616d31e1 | Retained as partial historical evidence and excluded from completed-campaign comparisons. A later run does not repair or complete this archive. |

The seven audit characterization tests assert existing unsafe behavior. Their passing result reproduces the findings, not their remediation. The previous edition ran Java nullability and report/experiment/web gates; its third-review receipts remain historical. This documentation revision records fresh helper, evidence, and relevant acceptance checks in fourth-review verification receipts, separately from prior findings. No new performance measurements are used to rank algorithms in this rewrite.

### Finding traceability and priorities

| Finding | Priority and disposition |
| --- | --- |
| TF01 | High: extract calculation and retain caller commit authority. Architecture chapter. |
| TF02 | High: reject unsupported unassigned input or implement construction and coverage. Architecture chapter. |
| TF03 | High: validate identities and numeric facts before copying. Architecture chapter. |
| TF04 | Medium: exact money before fine-grained cost decisions. Architecture chapter. |
| TF05 | High: one complete-operation deadline and bounded cleanup. Architecture chapter. |
| TF06 | Medium: quantitative infeasibility gradients, then repair experiments. Algorithms chapter. |
| TF07 | Medium: expanded into configurable benchmark foundations and worked examples. |
| TF08 | Medium: isolated rule tests and complementary assertion modes. Algorithms chapter. |
| TF09 | Medium: isolate internal diagnostics and version provenance. Algorithms chapter. |
| TF10 | Low: one authoritative immutable fact revision. Architecture chapter. |
| TF11 | Medium: align canonical policy documentation with implementation. Algorithms chapter. |
| TF12 | Medium opportunity: policy-equivalent fleet aggregates after numeric contracts. Computation chapter. |
| TF13 | Medium opportunity: profile invariant preparation separately from timing allocation. Computation chapter. |
| TF14 | Medium opportunity: bounded expanded generation with search-order validation. Computation chapter. |

### Second review traceability

The supplied review is preserved unchanged. CR identifiers identify its source-confirmed observations or unmeasured opportunities, not new reproduced fixes. Recommendations below amend unsupported guarantees and commercial assumptions while retaining their traceability.

| Finding | Integrated recommendation |
| --- | --- |
| CR01 | TF05 lifecycle and score purity. See the corresponding CR section for current behavior, consequences, guidance, benefits, tradeoffs, and acceptance. |
| CR02 | TF02 and TF06 coverage and repair. See the corresponding CR section for current behavior, consequences, guidance, benefits, tradeoffs, and acceptance. |
| CR03 | TF11 policy skips and explicit target weighting. See the corresponding CR section for current behavior, consequences, guidance, benefits, tradeoffs, and acceptance. |
| CR04 | TF11 zero-overtime consequences. See the corresponding CR section for current behavior, consequences, guidance, benefits, tradeoffs, and acceptance. |
| CR05 | TF05 observable overnight failures. See the corresponding CR section for current behavior, consequences, guidance, benefits, tradeoffs, and acceptance. |
| CR06 | TF07 algorithm and phase termination studies. See the corresponding CR section for current behavior, consequences, guidance, benefits, tradeoffs, and acceptance. |
| CR07 | TF13 profiling and separate timing treatments. See the corresponding CR section for current behavior, consequences, guidance, benefits, tradeoffs, and acceptance. |
| CR08 | TF04 and TF12 score representation after numeric contract. See the corresponding CR section for current behavior, consequences, guidance, benefits, tradeoffs, and acceptance. |
| CR09 | TF01 and TF03 indexed payload before validation. See the corresponding CR section for current behavior, consequences, guidance, benefits, tradeoffs, and acceptance. |
| CR10 | TF09 and TF14 Community alternatives and API status. See the corresponding CR section for current behavior, consequences, guidance, benefits, tradeoffs, and acceptance. |
| CR11 | TF13 later shadow variable remodeling. See the corresponding CR section for current behavior, consequences, guidance, benefits, tradeoffs, and acceptance. |

### Terms used in the benchmark design

| Term | Meaning in this report |
| --- | --- |
| Dataset seed | Input to dataset generation; changes the problem instance. |
| Solver seed | Input to the search random generator; changes search on the same problem. |
| Fork | A fresh JVM repetition with recorded startup and warmup. |
| Treatment | One complete configuration or implementation being compared with a control. |
| Paired effect | Candidate minus control on matching inputs and conditions. For cost, a negative effect is an improvement. |
| Held-out corpus | Datasets excluded from tuning and used only after the finalist is fixed. |
| Score plateau | Different violations receive the same score, hiding progress toward feasibility. |
| Incremental aggregate | A total updated from changed contributions instead of rescanning every route. |
| Censored latency | The request did not finish within observation; its exact completion duration is unknown. |
| Confidence interval | Uncertainty under the stated sampling/resampling assumptions, not a guarantee about unrepresented production days. |

## Code and documentation reference register

Every C reference resolves to the immutable reviewed source. The companion source-register.json records the exact local documentation hash, section ranges, applicability, and live-page version observations. Guide citations in the body identify the principle used; proposed corpus sizes, calibration tolerances, statistical methods, and promotion gates are WaterFlex design choices.

| Source | Reviewed implementation |
| --- | --- |
| C01 | [BookingSearchPipeline](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/BookingSearchPipeline.java#L20) |
| C02 | [BoundedBookingSearch](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/BoundedBookingSearch.java#L15) |
| C03 | [OptimizationService](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L64) |
| C04 | [DailySolver](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DailySolver.java#L14) |
| C05 | [SolverExperiment](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SolverExperiment.java#L19) |
| C06 | [SchedulingPolicy](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SchedulingPolicy.java#L31) |
| C07 | [TimefoldAuditEvidenceTest](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/test/java/dev/waterflex/scheduler/optimizer/TimefoldAuditEvidenceTest.java#L12) |
| C08 | [PlanCopies](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/PlanCopies.java#L11) and [DayPlan](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayPlan.java#L34) |
| C09 | [RouteEvaluator](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteEvaluator.java#L23) |
| C10 | [RouteScoringFacts](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteScoringFacts.java#L17) |
| C11 | [SearchDeadline](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/SearchDeadline.java#L82) and [SearchAdmission](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/SearchAdmission.java#L1) |
| C12 | [RouteTimeline](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteTimeline.java#L45) |
| C13 | [DayConstraintProvider](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayConstraintProvider.java#L19) |
| C14 | [DayConstraintProviderTest](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/test/java/dev/waterflex/scheduler/optimizer/DayConstraintProviderTest.java#L33) and [maintenance workflow](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/.github/workflows/maintenance.yml#L1) |
| C15 | [README policy](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/README.md#L79) |
| C16 | [MatrixController](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/routing-service/src/main/java/dev/waterflex/routing/MatrixController.java#L122) |
| C17 | [SolverBenchmark](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/test/java/dev/waterflex/scheduler/optimizer/SolverBenchmark.java#L21) |
| C18 | [SolverBenchmarkData](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/scheduler-service/src/test/java/dev/waterflex/scheduler/optimizer/SolverBenchmarkData.java#L9) |
| C19 | [Experiment runtime](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/infra/experiment_runtime.py#L374) and [configuration contract](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/infra/experiment_config.py#L48) |
| C20 | [Experiment analysis](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/499fde2cc3fe79a8b7413a942a66cfc97b5d0e94/infra/experiment_analysis.py#L199) |

### Second review code references

These references retain the same immutable production baseline as C01 through C20.

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

### Delivery evidence

The fourth-review directory contains new source, campaign-example, campaign-design, verification, visual-QA and manifest receipts. The example JSON is a checked proposed Community parameter inventory and the design file checks worked arithmetic; neither is accepted by the current runner. The manifest excludes itself and the final verification output to avoid circular hashes.

Original, second-review and third-review evidence remains byte-identical in Git. Historical report/helper identities are verified against their retained commits. The supplied Timefold_Second_Review.md and .docx are preserved byte for byte and hashed separately. Fresh report and helper hashes belong to fourth-review receipts. Source Markdown, PDF and 113 figures remain included with their original provenance. No historical failure is overwritten and no old measurement is relabeled as new verification.
