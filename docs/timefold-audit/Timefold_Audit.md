# WaterFlex Timefold Audit and Stateless Service Readiness

Prepared 2 October 2026 for the WaterFlex scheduler owner and implementing engineer.

## Executive assessment

The scheduler is a useful experimental application with several strong correctness safeguards. It is not yet a dataset-in, results-out solver service. Preserve its booking and daily experiments, but extract and harden the solver boundary before deployment.

This audit identifies eleven findings: four high-priority service readiness gaps, six medium-priority defects or engineering gaps, and one low-priority consistency risk. No critical defect that bypasses the current public booking or daily apply safeguards was demonstrated. That is an evidence limit, not a production certification. The highest-risk reproduced behaviors occur when core Java objects are used directly, which is precisely the boundary a future dataset API would expose.

Fix the cold-start and input-integrity gaps before exposing a dataset endpoint. Correct monetary arithmetic and stale policy documentation before using further experiments to make fine-grained cost decisions. Establish one end-to-end deadline and separate business persistence from solver execution before deploying the service.

| ID | Priority | Finding | Evidence class |
| --- | --- | --- | --- |
| TF01 | High | Solver API still owns business persistence | Confirmed architecture gap |
| TF02 | High | Unassigned daily dataset can return empty zero-score result | Reproduced core defect |
| TF03 | High | Raw core inputs can lose identity or fabricate zero cost | Reproduced boundary defects |
| TF04 | Medium | Binary monetary arithmetic misrounds a half-cent case | Reproduced numeric defect |
| TF05 | High | Daily request deadline does not bound the whole operation | Confirmed lifecycle gap |
| TF06 | Medium | Hard penalties hide degrees of window infeasibility | Reproduced score trap |
| TF07 | Medium | Benchmark warmup is too short to assume a warmed service | Confirmed method gap; bias unmeasured |
| TF08 | Medium | Assertion and isolated constraint coverage need expansion | Confirmed test gap |
| TF09 | Medium | Production diagnostics depend on implementation classes | Confirmed upgrade risk |
| TF10 | Low | DayPlan can hold two conflicting copies of scoring facts | Reproduced consistency risk |
| TF11 | Medium | README contradicts implemented booking policy | Confirmed documentation error |

Critical means a demonstrated severe failure of the current supported workflow, such as silently applying an invalid schedule. High means a deployment blocker or a plausible major service failure with a concrete trigger. Medium means a bounded correctness issue, experimental reliability gap, or maintainability risk. Low means a localized risk with an existing mitigation. These are engineering priorities, not security vulnerability ratings.

## Baseline and audit boundaries

The audited production baseline is commit 164a1e111370d21ea93f634e674c5fdee4edc90f. The original checkout had an unrelated AGENTS.md modification. Audit work used a separate worktree and branch. No production source, settings, dependency versions, database records, or retained experiment results were changed.

The scheduler pins Timefold Solver 2.6.0, Spring Boot 4.1.1 and Java 25. The requested latest documentation displayed 2.7.0 when reviewed. Recommendations were compared to installed behavior, not assumed to be available unchanged in 2.6.0. The broad quickstart comparison calls the service approach stable, while its detailed service quickstart explicitly labels the generated framework Preview. The latter caution should govern a framework adoption decision. See [S01 Introduction](https://docs.timefold.ai/timefold-solver/latest/introduction), [S02 Quickstart overview](https://docs.timefold.ai/timefold-solver/latest/quickstart/overview) and [S03 Service quickstart](https://docs.timefold.ai/timefold-solver/latest/quickstart/service/getting-started).

The intended service accepts a complete problem dataset and returns proposals and diagnostics. The calling application owns persistence, customer promises, reservations, version checks and applying results. This report evaluates both the present experimental application and that target. It does not treat a UI, database, custom booking algorithm or custom benchmark harness as inherently wrong.

Review covered the relevant linked documentation families: domain modeling, scoring, Constraint Streams, fairness, solver lifecycle, phases, termination, moves, assertions, benchmarking, Spring integration, service architecture, repeated planning and version migration. It did not crawl every documentation URL. Managed Field Service Routing models, Timefold Platform APIs, unrelated domain quickstarts, Enterprise-only optimization features, and a wholesale Quarkus migration were outside scope. The coverage register records the actual pages and their application.

## Current workflows and strengths

Booking currently follows this path: job ID, database snapshot, sparse directed road legs, insertion search, optional bounded rearrangement, independent candidate validation, common reservation construction, and transactional publication. Confirmation rechecks the business state. The public controller always routes policy-v2 offers through the common reservation coordinator; the legacy reservation flag is not a reliable indicator of the active architecture. See [BookingController.java:20](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/BookingController.java#L20), [BookingSearchPipeline.java:49](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/BookingSearchPipeline.java#L49) and [BookingCoordinator.java:29](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/BookingCoordinator.java#L29).

The bounded booking engine is custom Java search, not a Timefold solver. Its default neighborhood limits are six routes, depth two, beam eight and 500 arrangements per window. Completion means completion of that prescribed search. It is not proof that no feasible arrangement exists in the full mathematical search space. Booking's cost-first ordering and fairness tie-break are explicit. Ordinary horizon search precedes overflow, and routing failure, incomplete search, conflict and busy outcomes are distinguished. See [BoundedBookingSearch.java:15](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/BoundedBookingSearch.java#L15), [BoundedBookingSearch.java:260](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/BoundedBookingSearch.java#L260) and [BookingController.java:43](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/BookingController.java#L43).

Daily optimization loads an already assigned schedule, validates it, runs a cost reference phase, then uses the remaining portion of a nominal fifteen-second solve budget for fairness under the recorded cost ceiling. Preview and locked apply both perform additional validation. Ordinary preview skips an infeasible baseline; the repair workflow has a separate path. A stateless extraction must preserve these distinctions. See [OptimizationService.java:90](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L90) and [OptimizationService.java:299](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L299).

The daily list model fits ordered technician routes. Inverse technician shadows are declared, solver phases use new Solver instances, and PlanCopies isolates mutable entities and reconstructs inverse references. No database or road-service calls appear in the daily score computation path. Existing FULL_ASSERT and independent timing-oracle tests are substantial safeguards. They should be retained, not replaced by a framework migration. See [S05 Modeling building blocks](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems), [S07 Score calculation](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/score-calculation) and [S14 Library integration](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/library-integration).

BookingSnapshot validates rates, visit promises, road leg signs and arrangement membership. Roads distinguish missing data from explicit unreachable pairs. Current persistence loaders therefore mitigate several raw DayPlan defects described below. Independent RouteEvaluator coverage checks also protect today's daily preview/apply workflow against missing appointments.

## Findings and required fixes

### TF01 Separate solving from business persistence

Priority High. Confidence High. Classification Architecture gap for the intended service.

The public booking request contains a job ID; the daily request contains a metro and date. Neither carries the complete scheduling dataset. Booking publishes holds and offers, and daily preview persists optimization runs. The application also contains confirmation, apply and scheduled work. A new instance cannot perform the intended solve solely from the request body. Evidence: [BookingController.java:24](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/BookingController.java#L24), [BookingCoordinator.java:61](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/BookingCoordinator.java#L61), [OptimizationService.java:39](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L39) and [OptimizationService.java:64](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L64). Reference: [S02 Quickstart overview](https://docs.timefold.ai/timefold-solver/latest/quickstart/overview).

Extract pure booking and daily problem-to-result entry points. Keep the current loaders, reservation machinery and apply workflow in the experimental caller. Return proposed assignments or offer candidates, validated metrics, coverage, stop reason and provenance without writing holds or schedules. Continue using Spring Boot; the preview service framework is an optional alternative, not a prerequisite.

Acceptance: both APIs solve a serialized fixture with database access disabled, produce no business writes, and run overlapping requests without mutable entity leakage. The caller applies a proposal only after checking its own current versions and reservations. A result is not itself a reservation or a commit guarantee.

### TF02 Initialize or reject unassigned daily input

Priority High. Confidence High. Classification Reproduced core defect; current database flow normally supplies assigned visits.

A one-technician dataset with one unassigned visit returned an empty route, score 0hard/0medium/0soft, and PHASE_COMPLETED. The solver log reported that Local Search had no entities or values to move. RouteEvaluator rejected the returned plan, but DailySolver.solve returned it without that validation. The first audit hypothesis expected an exception; execution disproved it, and the retained evidence records both attempts.

Both the XML and generated variants configure only local search. That is reasonable for the current reoptimization workload, but is insufficient as a general dataset contract. Evidence: [SolverExperiment.java:51](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SolverExperiment.java#L51), [SolverExperiment.java:65](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SolverExperiment.java#L65) and audit test unassignedDatasetReturnsZeroScoreWithoutServingVisit. Reference: [S12 Local search](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/local-search).

Define the input mode explicitly. For the intended general dataset service, initialize unassigned visits through a construction phase or validated initializer before local search. Reject malformed partial assignments. Never return a successful result based only on the Timefold score; require exact coverage and independent feasibility.

Acceptance: empty datasets are explicitly valid empty results; one-visit cold starts are served; partial seeds are completed; zero technicians with work and infeasible cold starts receive truthful non-success outcomes. No appointment disappears.

### TF03 Validate identities and numeric facts before copying or solving

Priority High. Confidence High. Classification Reproduced core boundary defects; deployment blocker.

Three direct Java reproductions demonstrate missing boundary checks. Two TechRoute objects with the same technician ID can each serve a different visit at the same time and pass RouteEvaluator; their segment map collapses to one key. Two input PlanVisit facts with the same ID collapse into one during PlanCopies.copy, turning an invalid source into a valid-looking smaller problem. A NaN regular rate yields a feasible result with zero cost because Math.round(NaN) returns zero.

Evidence: [RouteEvaluator.java:23](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteEvaluator.java#L23), [PlanCopies.java:11](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/PlanCopies.java#L11) and [DayPlan.java:34](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayPlan.java#L34). Audit tests duplicateTechnicianIdentityPassesIndependentValidation, copySilentlyCollapsesDuplicateVisitFacts and nonFiniteRateBecomesFeasibleZeroCostAtCoreBoundary. Reference: [S05 Modeling building blocks](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems). Database primary keys and BookingSnapshot.Rates currently reduce external exposure; the tests do not prove these inputs pass today's HTTP handlers.

Introduce a validated request DTO and one core problem validator before any ID-based map construction. Require unique technician and visit identities, canonical visit membership, finite nonnegative rates, valid durations/windows/limits and well-formed routing facts. Reject missing data instead of substituting zero. Use typed directed pair keys or validate reserved key delimiters before accepting arbitrary external IDs.

Acceptance: duplicate IDs, dangling assignments, mismatched visit objects, invalid numeric values, negative legs and missing required fields fail explicitly before copying. Validation preserves the original task count and returns field-level errors.

### TF04 Use exact monetary arithmetic throughout

Priority Medium. Confidence High. Classification Reproduced numeric error affecting modeled cost.

For a valid route with 255 paid minutes, no mileage or overtime, and a $20.02 hourly rate, both evaluators return 8,508 cents. Exact decimal half-up rounding yields 8,509 cents. BigDecimal score objects do not fix an amount already computed using double. The validation comparison agrees because both implementations share the same arithmetic pattern.

Evidence: [RouteEvaluator.java:42](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteEvaluator.java#L42), [DayScoreCalculator.java:26](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayScoreCalculator.java#L26), [RouteScoringFacts.java:29](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteScoringFacts.java#L29) and audit test binaryRateRoundsHalfCentDownInBothEvaluators. Reference: [S06 Score overview](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/overview). This is a one-cent reproduction, not evidence of large financial loss or observed production misranking.

Specify decimal input rates, exact unit conversions and one rounding rule. Carry BigDecimal or scaled integer/rational amounts through route comparisons and fleet aggregation, rounding only at the agreed output boundary. Give the independent numeric oracle an exact reference implementation.

Acceptance: half-cent cases, mileage conversions, different route partitions and orderings produce the same exact fleet total; booking rank ties and the daily fairness ceiling use that same monetary contract. Preserve previous evidence and version the changed cost model.

### TF05 Bound the complete daily operation and release resources on cancellation

Priority High. Confidence High on control flow; latency impact not load-tested. Classification Service lifecycle gap.

Daily preview uses a new twenty-second SearchDeadline only for admission. It never installs that deadline with within around the remaining work. It then performs build, routing, validation, solving and persistence inside previewTransactions. The ten-second reference solve and remaining fifteen-second phase allocation start after problem construction. They do not bound total request time or transaction lifetime. Daily endpoints have no corresponding explicit cancellation contract.

Evidence: [OptimizationService.java:64](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L64), [OptimizationService.java:106](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L106), [OptimizationService.java:124](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L124) and [SearchDeadline.java:82](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/SearchDeadline.java#L82). References: [S04 Spring Boot guide](https://docs.timefold.ai/timefold-solver/latest/quickstart/spring-boot/spring-boot-quickstart), [S11 Algorithms overview](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/overview) and [S14 Library integration](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/library-integration). SearchAdmission already bounds active expensive work and queue size; this is not an unbounded-thread finding.

For the extracted service, establish one monotonic deadline covering queueing, preparation, solving and validation. Run solving in a bounded worker lifecycle using SolverManager or an equivalent adapter, propagate cancellation, and reserve validation time. Keep database transactions in the caller. Merely calling getFinalBestSolution on the request thread would still leave the HTTP wait problem.

Acceptance: queue delay consumes the budget; slow routing and validation cannot silently start a fresh budget; cancellation releases capacity; timeout returns validated partial results only when the contract allows them. Measure complete-request latency and cleanup, not just solveMs.

### TF06 Give hard constraints a useful infeasibility gradient

Priority Medium. Confidence High on plateau; effect on solve quality unmeasured. Classification Reproduced score trap.

A route whose outbound travel reaches the exclusive arrival-window boundary and a route that arrives an hour later both receive the same 1,000,000 hard penalty. The infeasible fallback penalizes an unplaced visit categorically. This gives search no hard-score improvement for approaching the window until it becomes feasible. Repair search is particularly exposed because it starts with a newly added absence.

Evidence: [RouteTimeline.java:45](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteTimeline.java#L45), [RouteTimeline.java:90](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteTimeline.java#L90) and audit test hardPenaltyPlateauHidesSizeOfWindowViolation. Reference: [S08 Score performance](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/performance).

Represent degrees of lateness and capacity excess while preserving strict hard feasibility. Keep categorical qualification violations separate from quantitative violations, and retain the independent validator. Consider larger moves only after measuring whether the gradient change helps.

Acceptance: reducing a timed violation improves the relevant hard penalty without weakening the actual window rule. Compare repair success and time to feasibility on fixed infeasible seeds. Do not claim that this plateau already caused any particular failed booking or repair.

### TF07 Calibrate warmup before interpreting steady service performance

Priority Medium. Confidence High on setup; direction and magnitude of bias unknown. Classification Experimental method gap.

The daily benchmark gives the selected strategy a 200 ms SPARSE/20 warmup in each fresh JVM. That measures behavior close to process startup and may include substantial compilation effects during measured solving. It does not by itself establish warmed, long-lived API performance.

Evidence: [scheduler-service/src/test/java/dev/waterflex/scheduler/optimizer/SolverBenchmark.java:45](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/test/java/dev/waterflex/scheduler/optimizer/SolverBenchmark.java#L45) and [infra/experiment_runtime.py:222](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/infra/experiment_runtime.py#L222). Reference: [S16 Benchmarking](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/benchmarking-and-tweaking). The existing harness already retains hashes, source versions, failures, seeds and rotated treatments. Those are strengths.

Keep the existing archives unchanged and label their process/warmup semantics. Add a separate warm-service study after observing move-evaluation stabilization; Timefold's benchmarker uses a much longer default warmup. Keep cold-start latency as its own useful measurement. Use production-scale inputs and independent repeated runs.

Acceptance: record JVM lifetime, warmup policy, CPU contention and concurrency; retain failures; compare identical workloads and served customer identities. Fixed seed plus elapsed-time termination does not guarantee identical routes. No production savings or capacity claim follows from these audit tests.

### TF08 Expand constraint level and assertion testing

Priority Medium. Confidence High. Classification Test coverage gap.

DayConstraintProviderTest runs FULL_ASSERT and checks solved metrics against independent evaluation. SolverExperimentTest builds all eight variants with assertions. However, constraints are grouped into broad route/fleet penalties, there are no ConstraintVerifier tests, and the inspected CI workflows do not schedule extended FULL_ASSERT plus NON_INTRUSIVE_FULL_ASSERT runs. Runtime configurations explicitly select NO_ASSERT.

Evidence: [scheduler-service/src/test/java/dev/waterflex/scheduler/optimizer/DayConstraintProviderTest.java:33](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/test/java/dev/waterflex/scheduler/optimizer/DayConstraintProviderTest.java#L33), [DayConstraintProvider.java:19](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayConstraintProvider.java#L19), [SolverExperiment.java:51](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SolverExperiment.java#L51) and [.github/workflows/maintenance.yml:1](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/.github/workflows/maintenance.yml#L1). References: [S07 Score calculation](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/score-calculation) and [S15 Solver diagnostics](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/solver-diagnostics).

Add focused constraint tests for each business rule and phase target, with both positive and negative cases. Enable a development assertion profile and periodic bounded assertion runs. Keep independent exhaustive timing tests. Timefold 2.x supplies constraint testing APIs in core; do not add the removed 1.x timefold-solver-test artifact. See [S18 Upgrade from version one](https://docs.timefold.ai/timefold-solver/latest/upgrading-timefold-solver/upgrade-from-v1).

Acceptance: every rule has an isolated expected score, shadow values are initialized correctly, move/undo and cloning checks cover absence and infeasible cases, and new nullability diagnostics remain failures where configured. A zero hard score must never substitute for coverage validation.

### TF09 Isolate version sensitive diagnostics

Priority Medium. Confidence High. Classification Upgrade and maintenance risk.

Production DailySolver delegates to SolverExperiment, which imports DefaultSolver and internal phase/scope classes. Solving fails if the implementation is not that expected class. Engine version text is hard-coded, and termination reason is inferred from elapsed time and counters rather than an authoritative termination event.

Evidence: [SolverExperiment.java:6](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SolverExperiment.java#L6), [SolverExperiment.java:71](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SolverExperiment.java#L71), [SolverExperiment.java:77](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SolverExperiment.java#L77) and [DailySolver.java:19](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DailySolver.java#L19). References: [S14 Library integration](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/library-integration) and [S18 Upgrade from version one](https://docs.timefold.ai/timefold-solver/latest/upgrading-timefold-solver/upgrade-from-v1). No failure on the pinned 2.6.0 dependency was observed; being one minor version behind is not itself a defect.

Separate the solver engine adapter from experimental measurement code. Use public metrics and lifecycle APIs where sufficient. Isolate any unavoidable implementation dependency, verify it against the exact supported version, and derive version provenance from the loaded artifact. Label inferred stop reasons explicitly.

Acceptance: upgrade tests exercise every variant, score equivalence and diagnostics. Metadata identifies actual runtime/configuration/input versions. A diagnostic incompatibility has a defined failure policy and cannot silently fabricate measurements.

### TF10 Keep one authoritative set of problem facts

Priority Low. Confidence High. Classification Reproduced consistency risk with current mitigation.

DayPlan stores the matrix and rates alongside a RouteScoringFacts snapshot. Replacing the matrix with setMatrix leaves scoringFacts pointing to the old matrix. A reproduction makes RouteEvaluator reject missing legs while scoringFacts still gives zero hard penalty using the original legs. setVisits can similarly leave the cached demanded services stale.

Evidence: [DayPlan.java:36](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayPlan.java#L36), [DayPlan.java:50](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayPlan.java#L50) and audit test routeFactsCanDisagreeAfterReplacingMatrix. Reference: [S05 Modeling building blocks](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems). PlanCopies reconstructs scoring facts before the current daily solve, limiting exposure. No corruption of today's copied solve was demonstrated.

Build an immutable problem-facts container once and reference it consistently. Remove independent mutation paths or rebuild derived facts atomically. Deserialize public DTOs into validated domain objects rather than binding arbitrary JSON directly into planning objects.

Acceptance: all scoring and validation paths use the same fact revision; updates cannot create inconsistent matrices or demanded-service sets; copies preserve that revision.

### TF11 Correct the experimental application policy description

Priority Medium. Confidence High. Classification Confirmed documentation error.

README says booking and daily optimization share a two-percent fairness allowance and that scarcity plus utilization can authorize new overtime. Current booking ranking is strict cost-first with fairness as a tie-break, and SchedulingPolicy.authorizeOvertime always returns false. Daily fairness has its own allowance. These statements can cause an experiment reader to expect behavior the application deliberately prohibits.

Evidence: [README.md:79](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/README.md#L79), [README.md:81](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/README.md#L81), [BoundedBookingSearch.java:260](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/BoundedBookingSearch.java#L260) and [SchedulingPolicy.java:31](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SchedulingPolicy.java#L31). Reference: [S06 Score overview](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/overview) for the need to formalize business constraints; the actual booking policy is WaterFlex-specific.

Update the canonical policy explanation and linked experiment documentation together. State that booking does not authorize new overtime, that return travel counts, and that daily fairness and booking ties are different policies. Describe bounded-search completion precisely.

Acceptance: an engineer can derive the implemented score/acceptance priorities from the documentation without contradiction. Existing policy tests remain authoritative. This audit reports the discrepancy without rewriting production policy.

## Improvements to measure rather than assume

Constraint Streams currently cache changed-route evaluations, but each affected route still runs a timing search and allocates a temporary DayPlan. Fleet cost and fairness gather a list and iterate across routes. This is a plausible hot path, not a measured performance defect. Profile representative long routes and absence-heavy cases before considering incremental aggregate collectors, cached immutable demand facts or timing shadows. Measure allocations, move evaluations per second and best feasible result under the same budget. Reference: [S08 Score performance](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/performance).

The grouped hard constraint also limits explanations: qualifications, windows, absences and limits share one constraint ID and route-level result. Add typed violation records and stable reason codes to help the experimental user understand rejected proposals. The solver can retain aggregate scoring while the independent validator produces detailed explanations. Reference: [S09 Understanding the score](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/understanding-the-score).

Do not replace custom booking with Timefold solely for consistency. Benchmark any Timefold booking prototype on the same snapshots, promises, served identities, budgets and reservation assumptions. Do not enable unassigned-value planning to silently drop promised appointments. Explicit infeasible or incomplete outcomes are appropriate when all booked work must be served.

Do not add move threads merely because request volume may grow. Independent request concurrency, move evaluation threads and customer isolation solve different problems. Existing SearchAdmission is useful, but pool sizes must be measured together. The future pure service should derive each problem only from its submitted dataset. Today's metro selection queries all depots, so a shared business database is not an implied client boundary. See [BookingService.java:503](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/164a1e111370d21ea93f634e674c5fdee4edc90f/scheduler-service/src/main/java/dev/waterflex/scheduler/BookingService.java#L503).

## Recommended stateless interface and ownership

Retain the current web/database application as the experiment driver. Extract a solver module and a thin Spring transport adapter. The solver module should have no JdbcTemplate dependency, no scheduled business jobs and no reservation side effects. The caller assembles a snapshot, sends it, inspects the result, and applies it using the caller's transaction and version checks.

| Concern | Caller responsibility | Solver responsibility |
| --- | --- | --- |
| Persistence | Jobs, appointments, holds, versions and history | No business persistence |
| Input | Complete snapshot, promises, policy and directed road data | Validate before copying or scoring |
| Search | Requested mode, budget and experiment identity | Bounded execution and request isolation |
| Result | Decide whether to reserve or apply | Proposals, independent validation and diagnostics |
| Concurrency | Detect stale business state when applying | Admission, cancellation and no shared mutable plans |
| Failure | Retry policy and durable job ownership | Truthful incomplete, invalid, busy and routing outcomes |

Proposed input contents are a schema version, request ID, snapshot/version token, service date and time zone, technicians and availability, required visits and optional seed assignments, existing commitments, rates and policy, a directed travel matrix with explicit unreachable pairs, and a requested budget. Use decimal money fields and typed IDs/pairs. Make missing and empty meaningfully different.

Proposed output contents are an echoed request/snapshot identity, engine and configuration provenance, assignments or ranked candidates, independent feasibility and coverage, modeled cost and policy metrics, stop reason, search-completeness meaning, and queue/preparation/solve/validation timings. Include errors with field paths. Candidates remain proposals until the caller reserves or applies them.

For an initial dataset-in/results-out service, use bounded request/response execution with explicit timeout and cancellation behavior. If required budgets outgrow HTTP limits, the caller can own durable job orchestration around stateless workers. Adopting SolverManager does not require storing customer schedules in the solver process. These are proposed contracts, not newly implemented endpoints.

The six a.m. America/Chicago cutoff and hourly four-hour arrival promises remain business constraints. The caller must recheck any time-sensitive rule when applying. Independent output validation must reject coverage loss, qualification violations, unavailable work, invalid return travel and prohibited overtime regardless of the search score.

## Remediation order and acceptance gates

1. Fix experimental truth now: TF04 exact arithmetic and TF11 documentation. Version any changed cost calculations and preserve old archives.
2. Establish the service boundary: TF01 extraction, TF03 request validation, TF02 initialization and mandatory result validation, then TF05 lifecycle and cancellation.
3. Strengthen solver feedback: TF06 gradients, TF08 isolated rule tests and assertion runs, TF10 immutable facts. Benchmark before changing algorithm defaults.
4. Make upgrades and conclusions reviewable: TF09 adapter isolation and TF07 calibrated warm-service measurements. Profile the identified hot paths only after correctness gates pass.

Before deployment, run round-trip JSON tests for complete, empty, partial and malformed inputs. Cover duplicate IDs, unknown assignment references, absent versus unreachable roads, invalid numbers, missing fields, qualification mismatches, absences, return travel, zero-overtime enforcement, date/DST/cutoff handling, stale snapshots, deadlines, cancellation and concurrent request isolation. Include public HTTP error mapping and caller-side apply tests. Keep unknown measurements null or explicitly absent; never manufacture zero metrics for failed evaluation.

## Verification evidence and limits

The audit added seven characterization tests in TimefoldAuditEvidenceTest. They intentionally assert observed unsafe or inconsistent behavior so the findings are reproducible. They are evidence tests, not desired production contracts. When a finding is fixed, replace its characterization with a regression test for the required safe behavior.

| Check | Observed result |
| --- | --- |
| Baseline Maven verify with nullability | Scheduler 174 tests and routing 2 passed |
| Clean Maven verify with first six audit cases | Scheduler 180 tests and routing 2 passed |
| Final targeted audit suite with decimal case | Seven tests passed under the nullability profile |
| Portal lint and typecheck | Both passed |
| Portal unit tests | 96 passed, zero skipped |
| Experiment toolkit unit tests | 27 passed using the existing system Python |
| Database and browser integration suites | Not run locally for this audit |
| Full benchmark campaign and API load test | Not run; no performance or savings claim |

The first Maven attempt lacked JAVA_HOME; rerunning with the installed JDK 25 resolved that environment issue. The bundled document Python lacked matplotlib for one experiment-toolkit test; the existing system Python completed all 27 tests. The Python failure is retained in evidence. Two initial cold-start assertions failed and led to the corrected zero-score reproduction; these were investigative hypotheses, not product fixes, and their logs are also retained.

The Java profile passes while emitting existing ECJ informational null-annotation diagnostics in other test files. No new diagnostic was attributed to the audit test. This report does not relabel the entire codebase warning-free. Maven verify does not execute the database IT suites that CI invokes separately.

Raw command logs and machine-readable test counts accompany the reports in evidence. Production source links are pinned to the audited commit. Documentation links are mutable latest pages, reviewed on 2 October 2026. The reports make no claim of exhaustive production validation, tenant-security certification, mathematical optimality, or measured scalability improvement.

## Documentation coverage register

Each entry identifies a reviewed page and the part applied to this audit. Source-derived recommendations are distinguished from WaterFlex-specific findings.

| Source | Reviewed subject | Application to this scheduler |
| --- | --- | --- |
| [S01 Introduction](https://docs.timefold.ai/timefold-solver/latest/introduction) | Version and JVM/framework context | Java 25 and Spring are compatible architectural choices; installed Solver is 2.6.0. |
| [S02 Quickstart overview](https://docs.timefold.ai/timefold-solver/latest/quickstart/overview) | Dataset service ownership | Caller-owned persistence matches the intended target; current application owns reservations and schedules. |
| [S03 Service quickstart](https://docs.timefold.ai/timefold-solver/latest/quickstart/service/getting-started) | Preview framework option | Detailed guide explicitly says Preview; adoption is optional, not required for statelessness. |
| [S04 Spring Boot guide](https://docs.timefold.ai/timefold-solver/latest/quickstart/spring-boot/spring-boot-quickstart) | REST execution and integration | Keep Spring; use bounded workers and cancellation. Its simple blocking example is not a production latency guarantee. |
| [S05 Modeling building blocks](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems) | Lists, IDs, facts, shadows and clones | List routes and inverse shadows fit. Raw input integrity and fact consistency need stronger boundaries. |
| [S06 Score overview](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/overview) | Score priority and exact arithmetic | Hard/medium/soft priorities fit; double arithmetic remains beneath BigDecimal scores. |
| [S07 Score calculation](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/score-calculation) | Constraint Streams and constraint tests | Streams are present; add per-rule ConstraintVerifier coverage alongside existing solver tests. |
| [S08 Score performance](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/performance) | Hot paths, profiling and score traps | No routing I/O in daily scoring. Flat infeasibility penalties are reproduced; route/fleet work needs profiling. |
| [S09 Understanding the score](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/understanding-the-score) | Explanations and justifications | Grouped hard constraint has limited explanation detail; add rule-specific output. |
| [S10 Load balancing and fairness](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/load-balancing-and-fairness) | Fairness as an objective | Capacity-weighted custom variance is intentional. Built-in fairness is an option, not an obligatory replacement. |
| [S11 Algorithms overview](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/overview) | Phases, termination and reproducibility | Finite budgets exist. A time budget plus seed does not ensure identical output. |
| [S12 Local search](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/local-search) | Initialization, acceptor and forager | Tabu 7 with accepted count 1000 matches an example; it is not evidence of optimal tuning. |
| [S13 Move selector reference](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/move-selector-reference) | List move neighborhoods | Change, swap, sublist, k-opt and ruin/recreate variants exist and build on 2.6.0. |
| [S14 Library integration](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/library-integration) | Solver lifecycle and concurrency | Fresh solver instances are good; synchronous request execution needs an explicit service lifecycle. |
| [S15 Solver diagnostics](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/solver-diagnostics) | Assertions and reproducibility | FULL_ASSERT tests exist, but runtime is NO_ASSERT and scheduled extended assertion coverage is absent. |
| [S16 Benchmarking](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/benchmarking-and-tweaking) | Warmup, repeated runs and failure retention | Custom evidence harness has strong provenance; 200 ms warmup needs calibration for warm-service conclusions. |
| [S17 Real time planning](https://docs.timefold.ai/timefold-solver/latest/responding-to-change/real-time-planning) | Changing data during a solve | Snapshot replacement is appropriate. Continuous ProblemChange processing is not required for this target. |
| [S18 Upgrade from version one](https://docs.timefold.ai/timefold-solver/latest/upgrading-timefold-solver/upgrade-from-v1) | Version-sensitive APIs and test packages | Already on 2.x. Do not apply a 1.x migration wholesale; diagnostics implementation imports need upgrade tests. |

## Reading the terminology

A planning entity is an object whose assignment or ordering the solver can change. A problem fact is input that stays fixed during a solve. A shadow variable is derived from assignments, such as the technician owning a visit. A hard score describes constraint violations; it does not replace complete input and output validation.

A construction phase creates initial assignments. Local search improves assignments through moves. A score trap is a flat penalty that hides progress toward feasibility. A solver termination limit stops search; an API deadline must also cover queueing, preparation and validation.

Stateless here means that the caller supplies the problem and owns durable business state. The solver can still use temporary request-local objects, bounded workers and immutable configuration. A bounded search result is the best candidate found within its prescribed work, not proof of global optimality.
