# WaterFlex Timefold Audit and Stateless Service Readiness

Prepared 2 October 2026 for the WaterFlex scheduler owner and implementing engineer.

## Executive assessment

The scheduler is a useful experimental application with several strong correctness safeguards. It is not yet a dataset-in, results-out solver service. Preserve its booking and daily experiments, but extract and harden the solver boundary before deployment.

This expanded audit identifies fourteen findings: four high-priority service readiness gaps, nine medium-priority defects, engineering gaps or optimization opportunities, and one low-priority consistency risk. No critical defect that bypasses the current public booking or daily apply safeguards was demonstrated. That is an evidence limit, not a production certification. The highest-risk reproduced behaviors occur when core Java objects are used directly, which is precisely the boundary a future dataset API would expose.

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
| TF12 | Medium | Fleet scoring repeats non-incremental aggregates | Optimization opportunity |
| TF13 | Medium | Route scoring rebuilds invariant setup | Optimization opportunity |
| TF14 | Medium | Expanded booking materializes neighborhoods before truncation | Optimization opportunity |

Critical means a demonstrated severe failure of the current supported workflow, such as silently applying an invalid schedule. High means a deployment blocker or a plausible major service failure with a concrete trigger. Medium means a bounded correctness issue, experimental reliability gap, or maintainability risk. Low means a localized risk with an existing mitigation. These are engineering priorities, not security vulnerability ratings.

## Expanded review: decisions for booking and daily

Second review baseline: afa035ddf2101d26c12508067d2d9016601db6c3, the refreshed main branch used by codex/timefold-audit-expanded. The original baseline remains 164a1e111370d21ea93f634e674c5fdee4edc90f. The supplied local guide is the primary source: 66 chapters, 122 image references and 113 unique images. All assets exist and their hashes are recorded. The reconciled inventory contains 60 official Solver guide URLs, all retrieved. Two linked pages add material beyond the local chapters: multithreaded solving and Framework integrations. This is complete coverage of the inventoried guide corpus, not every API class, historical version, external blog, managed-model manual or Platform manual.

Retain booking insertion and the daily TABU baseline while fixing the previously reported boundary defects. Evaluate alternatives on matched workloads after improving measurement and preserving policy. Documentation cannot select a winning configuration for this application. Three additional findings identify repeated computation: TF12 fleet aggregation, TF13 invariant route setup, and TF14 expanded booking neighborhood materialization. Their performance benefits remain unmeasured hypotheses.

A pure scheduling core can sit behind the existing Spring application. Booking can use bounded synchronous requests; longer daily optimization can use asynchronous jobs with cancellation, admission, expiry and explicit ownership. Stateless business semantics still require an owner for in-flight work. SolverManager manages local jobs, not distributed ownership or transactional publication across replicas. The preview service framework is an alternative integration, not a prerequisite for extraction. [Guide ch. 18](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/service/overview) [Guide ch. 24](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/service/consumer-guide) [Guide ch. 25](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/library-integration)

### Source provenance and version boundaries

Primary document: C:/Users/mcgee/Documents/Codex/2026-10-02/c-users-mcgee-downloads-timefold-solver/outputs/Timefold-Solver-Docs.md. SHA-256: 832e78aecbdce7a57497953029fdc5efcb7879e7e256b35377829db4d274da7d. Examples and official pages identify 2.7.0. The export date is not independently established. The application uses 2.6.0. The source and assets were not modified. Chapter, section, image and official-page receipts are in evidence/second-review/coverage.json.

Official spot checks cover list moves, LA/Tabu acceptance and foragers, SA temperature, diminished returns, recommendation prerequisites, Enterprise licensing, preview compatibility and migration. Converted examples, broken table words and PDF references are not executable specifications. Regret insertion has a documentation section but is explicitly unimplemented. Current structural-score and preview move-factory changes do not justify an unplanned upgrade. [Guide ch. 38](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/construction-heuristics) [Guide ch. 56](https://docs.timefold.ai/timefold-solver/latest/upgrading-timefold-solver/upgrade-to-latest) [Guide ch. 58](https://docs.timefold.ai/timefold-solver/latest/upgrading-timefold-solver/backwards-compatibility)

Eight diagrams were inspected: figure-p499-01 (selector composition), p506-01 (just-in-time generation), p507-01 (cached shuffle), p535-01 (k-opt), p551-01 (cloning), p571-01 (routing shadows), p593-01 (CPU/memory/I/O), and p643-01 (node sharing). The vendor performance chart is not WaterFlex evidence. Other images were inventoried and surrounding guidance reviewed; they are not needed for these conclusions. [Guide ch. 42](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/move-selector-reference) [Guide ch. 44](https://docs.timefold.ai/timefold-solver/latest/responding-to-change/real-time-planning) [Guide ch. 47](https://docs.timefold.ai/timefold-solver/latest/quickstart/quarkus-vehicle-routing/quarkus-vehicle-routing-quickstart) [Guide ch. 52](https://docs.timefold.ai/timefold-solver/latest/frequently-asked-questions) [Guide ch. 64](https://docs.timefold.ai/timefold-solver/latest/commercial-editions/performance-improvements)

Production changes since 164a1e1 concern service-calendar and benchmark-maintenance isolation, including frozen dates propagated into snapshots and overflow. Twelve production source/resource files differ, with 95 added and 15 removed lines. They do not change the daily score model or booking neighborhood algorithm. They reduce calendar drift but do not make old experiments measurements of afa035d. [ServiceCalendar.java:1](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/ServiceCalendar.java#L1) [BookingSearchPipeline.java:45](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/BookingSearchPipeline.java#L45) [OptimizationService.java:90](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L90)

### Current algorithms, budgets and validation

| Workflow or variant | Actual configuration and interpretation |
| --- | --- |
| Booking defaults | Bounded refinement defaults to false in BookingSearchPipeline and docker-compose.yml. Variant defaults to BOUNDED, but the name does not enable the gate. Effective default is insertion. Retained comparisons explicitly enabled the gate. Record overrides before describing a deployed default. |
| Booking INSERTION | Always attempted first, using existing assignments as seed. Positions and hourly four-hour promises use independent route evaluation. Cost is strictly ordered, with fairness breaking cost ties. Completion concerns the prescribed search, not global feasibility. |
| Booking BOUNDED | When enabled: relocation, swap and reversal within six shortlisted routes, depth two, beam eight, 500 arrangements per window. Optional refinement normally gets 250 ms when enough regular offers already exist; otherwise the enclosing deadline applies. No Timefold acceptor is involved. |
| Booking EXPANDED / RUIN_RECREATE / SHARED | Expanded limits: 12 routes, depth three, beam 16, 2,000 arrangements. RUIN_RECREATE/SHARED add custom reconstruction; EXPANDED/RUIN_RECREATE disable shared-window evaluation. These are custom Java experiments, not Timefold algorithms. |
| Daily production | TABU, seed 17; entity tabu seven, accepted count 1,000, selected count 10,000, list change/swap weights 45/45. Factory created once per configuration; fresh solvers and planning copies per phase. |
| Daily CURRENT_CAPPED / CURRENT_UNCAPPED | Names refer to saved XML, not current production. XML has list change/swap, selected/accepted counts 100; CAPPED retains 1,000 steps. Implicit acceptance is LA in installed 2.6. Per-call duration replaces XML time while preserving the cap. |
| Daily LA variants | LATE_ACCEPTANCE_CHANGE uses change only; LATE_ACCEPTANCE uses change/swap. History 400, accepted one, selected 10,000. These match a guide example, not an established optimum. |
| Daily advanced variants | SUBLIST adds reversing sublist change, size 2 to 4, weight five. KOPT adds k-opt 2 to 3, weight five. RUIN_RECREATE adds list ruin/recreate 2 to 3, weight one. All use LA 400/accepted one, changing moves and acceptance together versus TABU. |
| Daily phases | Ordinary preview begins assigned and validated. Reference gets 10 seconds; fairness gets the remainder of nominal 15 seconds, retaining reference overtime and cost ceiling. Absence repair separately gets 15 seconds. Independent checks precede accepted previews and locked application. |

Evidence: [BookingSearchPipeline.java:20](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/BookingSearchPipeline.java#L20), [BoundedBookingSearch.java:15](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/BoundedBookingSearch.java#L15), [DailySolver.java:14](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DailySolver.java#L14), [SolverExperiment.java:31](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SolverExperiment.java#L31), [OptimizationService.java:101](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L101). Seven characterization tests passed again with the nullability profile at afa035d, meaning their unsafe-behavior assertions still hold. Experiment total budgets differ from production phase budgets.

### Booking recommendations

| Disposition | Prerequisites, tradeoffs and evidence |
| --- | --- |
| Retain insertion plus optional bounded refinement | Already expresses promises, cutoff, no-new-overtime, strict cost, overflow, routing completeness and validation. Low migration effort. Restricted search can miss feasible arrangements; preserve incomplete outcomes. [BookingSearchPipeline.java:49](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/BookingSearchPipeline.java#L49) [Guide ch. 37](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/overview) |
| Evaluate Timefold construction separately | List construction can insert an unassigned visit, but the model needs new visits, alternative windows, eligibility, commitments and booking cost-first scoring. Daily fairness scoring is different. Conversion, initialization and extraction consume the deadline. Greedy construction is not exhaustive or guaranteed feasible. [Guide ch. 10](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems) [Guide ch. 38](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/construction-heuristics) |
| Evaluate Enterprise Recommendation API | Incremental greedy insertion requires construction first and at most one unassigned entity when used that way. Verify list-value support and proposition extraction on installed 2.6 before a trial. Return immutable technician/position values. It does not supply horizon policy, reservations or publication. Vendor millisecond claims are not WaterFlex estimates. [Guide ch. 46](https://docs.timefold.ai/timefold-solver/latest/responding-to-change/recommendation-api) |
| Evaluate short local search after a valid seed | Prove promise-preserving scoring and independent validation first. Allow rearrangement within promises; pin truly fixed assignments/prefixes. Pinning all incumbents may overconstrain; soft disruption taxes may permit forbidden changes. Preserve the insertion fallback and validation time. High migration effort and risk. [Guide ch. 39](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/local-search) [Guide ch. 45](https://docs.timefold.ai/timefold-solver/latest/responding-to-change/non-disruptive-replanning) |
| Defer exhaustive and living real-time solvers | Exhaustive search is a tiny-fixture oracle candidate. ProblemChange/daemon solving requires persistent ownership and clone discipline unnecessary for isolated requests. [Guide ch. 40](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/exhaustive-search) [Guide ch. 44](https://docs.timefold.ai/timefold-solver/latest/responding-to-change/real-time-planning) |

Future booking acceptance: identical facts and routing identity, identical served customers for cost comparisons, no promise/cutoff/overtime/coverage violations, independent offers, distinct timeout/routing-failure/incomplete outcomes, and whole-request latency including cold routing. Soft scores cannot authorize invalid offers.

### Daily recommendations by input type

| Disposition | Prerequisites, tradeoffs and evidence |
| --- | --- |
| Retain TABU for seeded reoptimization | Production baseline remains competitive on large dispersed fixtures. Assigned input avoids construction. Fixed tabu seven and accepted count 1,000 need fleet-sensitive evaluation before ratios or breadth changes. [Guide ch. 39](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/local-search) [SolverExperiment.java:46](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SolverExperiment.java#L46) |
| Define safe cold input first | TF02 requires rejection or initialization plus independent coverage checks. Evaluate default construction and appropriate first/best-fit/decreasing variants, checking list support and sorting on 2.6. Construction consumes the deadline; do not erase seeds to force it. Regret insertion is unimplemented. [Guide ch. 38](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/construction-heuristics) |
| Evaluate LA and controlled moves next | Compare identical moves across acceptors, then add sublist/k-opt under one acceptor. Existing KOPT/SUBLIST versus TABU cannot isolate move benefits. Record reference and accepted fairness-stage cost separately. [Guide ch. 39](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/local-search) [Guide ch. 42](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/move-selector-reference) |
| Evaluate SA later | Calibrate temperature from score deltas with correct hard/medium/soft units and time gradient, separately for reference and fairness. Low accepted counts suit SA; short remaining time can prevent useful cooling. No retained SA benefit established. [Guide ch. 39](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/local-search) |
| Evaluate absence repair separately | It starts infeasible and must restore feasibility without breaking commitments. TF06 plateaus can impede recovery. Compare repair/service before cost. Timeout does not prove infeasibility. Ordinary preview skips infeasible baselines. [OptimizationService.java:161](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L161) [Guide ch. 43](https://docs.timefold.ai/timefold-solver/latest/responding-to-change/continuous-planning) [Guide ch. 45](https://docs.timefold.ai/timefold-solver/latest/responding-to-change/non-disruptive-replanning) |
| Defer lower-evidence alternatives | Hill climbing is a local-optimum-prone diagnostic. Great Deluge and variable-neighborhood descent need evaluation. Diversified LA and Neighborhoods are preview. Installed 2.6 Neighborhoods rejects Tabu; current guidance also lacks equivalent weighted selection. Do not migrate merely for novelty. [Guide ch. 39](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/local-search) [Guide ch. 41](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/neighborhoods) [Guide ch. 58](https://docs.timefold.ai/timefold-solver/latest/upgrading-timefold-solver/backwards-compatibility) |

### Move selection is separate from acceptance

| Mechanism | Suitability and restrictions |
| --- | --- |
| List change/swap | Retain both. Swaps cross barriers that sequential single changes may not. List selectors work in installed 2.6 Community. Basic-variable pillar moves do not directly apply to TechRoute.visits. |
| Sublist change/swap | Move contiguous sequences, optionally reversing. Current SUBLIST adds change only; swap is unevaluated. Larger blocks may escape optima but increase evaluation cost. Bound sizes and retain fine moves. |
| K-opt | Reconnects edges and may reverse segments; current k is 2 to 3. Directed roads, windows, absences and depots require complete scoring and validation. Geometric improvement alone is insufficient. |
| List ruin/recreate | Reconstruction performs multiple score calculations per move. Keep low frequency and evaluate counts. Guide advises against combining nearby selection because reconstruction does not use it. |
| Nearby selection | Enterprise bias, not an acceptor or hard partition. Supports list change/swap/k-opt. Needs cheap deterministic distance, licensing/version checks, prepared road data and no remote calls. Preserve broader reach and measure startup/memory overhead. |
| Multistage/custom neighborhoods | Enterprise multistage and preview Neighborhoods need implementation and move-undo checks. Custom booking reconstruction is not equivalent merely because it shares a name. |

Sources: [Guide ch. 42](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/move-selector-reference), [Guide ch. 65](https://docs.timefold.ai/timefold-solver/latest/commercial-editions/multistage-moves), [SolverExperiment.java:40](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SolverExperiment.java#L40). No upgrade or Enterprise installation occurred. Community selector parsing does not establish Enterprise API availability.

### Termination and phase allocation

Use an absolute request deadline alongside solver termination. Booking includes admission, snapshot, routing, search, validation and publication. Daily's separate admission/solve budgets are TF05. Solver limits are checked at work boundaries and may overrun by an expensive unit; reserve validation and serialization time. [Guide ch. 37](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/overview) [OptimizationService.java:65](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L65)

Diminished returns uses the softest score and a grace window, currently 30 seconds by default. That exceeds normal daily solving and booking refinement. Fairness is medium-level while cost is soft-level, so an unmodified rule may stop for the wrong objective. Evaluate a shorter rule only inside an absolute cap, separately by phase. Preserve the reference result and its validated ceiling. This is not an XML-change recommendation. [Guide ch. 37](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/overview)

The 1,000-step cap is CURRENT_CAPPED, not production TABU. Steps support deterministic characterization but do not scale with fleet or move expense. Compare phase splits with the same total request budget, accepted outcomes, feasibility and completed work. No new budget experiments were run.

## Computation review and additional findings

### Booking computation trace

Snapshot loading captures business state/calendar; sparse routing supplies directed legs with routing identity. Insertion enumerates windows and positions; bounds reject impossible work before independent evaluation. Day contexts reuse baselines, shortlists and caches. Scalar ranking precedes timeline materialization, validation and reservation publication. Confirmation checks live business state. [BookingSnapshotLoader.java:1](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/BookingSnapshotLoader.java#L1) [SnapshotRouting.java:1](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/SnapshotRouting.java#L1) [BookingEvaluation.java:39](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/BookingEvaluation.java#L39) [ReservationOffers.java:1](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/ReservationOffers.java#L1) [BookingCoordinator.java:29](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/BookingCoordinator.java#L29)

Existing optimizations follow the guide: no I/O in scoring, request-local keys include visit facts, route/fairness caches cap at 2,048 entries, insertion updates changed routes against validated parents, ranking avoids timeline maps, structural insertion facts avoid whole-map copies, and lazy Move.apply delays route copies. Cross-request caches would need snapshot, rates, promises, absences, calendar and road-version identity. A shared mutable search cache would be unsafe. [BookingEvaluation.java:17](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/BookingEvaluation.java#L17) [BoundedBookingSearch.java:48](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/BoundedBookingSearch.java#L48) [Guide ch. 16](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/performance)

### Daily computation trace

Construction loads assigned routes and road facts before solving. PlanCopies separates entities and inverse shadows. DailySolver retains a factory and builds solvers per phase. Constraint Streams updates changed route tuples; unchanged routes do not all need new timelines per move. The fleet list collector then triggers fleet-wide cost, target and fairness loops. Independent validation deliberately recalculates routes outside solving for a different correctness purpose. [PlanCopies.java:9](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/PlanCopies.java#L9) [DailySolver.java:14](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DailySolver.java#L14) [DayConstraintProvider.java:15](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayConstraintProvider.java#L15)

RouteTimeline builds availability; RouteTimingSearch uses dynamic programming over contiguous visit groups and absence-separated intervals. It already reuses forward prefixes and has a single-interval fast path. It still copies maps for segment alternatives and combined states. A simple earliest-arrival shadow chain is not equivalent to this flexible-departure, absence and paid-time policy. Preserve the independent evaluator when optimizing timing. [RouteTimeline.java:17](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteTimeline.java#L17) [RouteTimingSearch.java:17](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteTimingSearch.java#L17) [Guide ch. 47](https://docs.timefold.ai/timefold-solver/latest/quickstart/quarkus-vehicle-routing/quarkus-vehicle-routing-quickstart)

### TF12 Replace fleet rescans with policy-equivalent incremental aggregates

Classification: Medium optimization opportunity. Confirmed repeated work; runtime impact unmeasured. groupBy(toList()) feeds methods that scan all routes. Fairness-stage cost runs for the soft score and again inside targetViolation; fairness creates workload and normalized-output records. The guide warns that collection-producing collectors lose incrementality downstream. [DayConstraintProvider.java:18](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayConstraintProvider.java#L18) [RouteScoringFacts.java:23](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteScoringFacts.java#L23) [Guide ch. 13](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/score-calculation) [Guide ch. 16](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/performance)

Recommendation: incrementally aggregate paid minutes, overtime and meters, then round money once at fleet level. Evaluate a fairness accumulator or score-only computation without diagnostic workloads. Capacity-weighted variance differs from standard equal-load balancing. Algebra and add/remove ordering can change finite-precision BigDecimal results; do not silently substitute a similar formula.

Acceptance: move/undo sequences must agree with independent validation for feasibility, cents, overtime and defined fairness precision. Include unequal capacities, no eligible capacity, absences, empty routes, one-technician fleets and swaps. Later measure allocation, score cost and accepted quality at fixed budgets. Speedup remains a hypothesis.

### TF13 Hoist invariant route setup and reduce score-only allocation

Classification: Medium optimization opportunity. Each RouteScoringFacts.evaluate constructs a temporary DayPlan whose constructor builds a demand-service set unnecessary to its timeline path. Capacity and eligibility are recomputed. RouteTimeline sorts absences and rebuilds availability each evaluation, repeating availability in its infeasible fallback. These operations depend on facts fixed for the solve. [RouteScoringFacts.java:17](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteScoringFacts.java#L17) [DayPlan.java:36](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayPlan.java#L36) [SchedulingPolicy.java:76](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SchedulingPolicy.java#L76) [RouteTimeline.java:119](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteTimeline.java#L119) [Guide ch. 16](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/performance)

Recommendation: prepare immutable validated capacity, availability and eligibility facts, and pass a compact timing context. Consider indexed road legs and pre-buffered minutes when scale justifies memory. Precompute only assignment-independent facts; invalidate for changed rates, buffers, absences, shifts or routing identity. Dense matrices can waste memory on sparse problems.

Acceptance: canonical/infeasible timing must match independent evaluation, including overlapping absences, return travel, missing legs, qualifications and changed buffers. Preserve missing-data errors and clone isolation. Future ProblemChange support must refresh derived facts. Measure setup/allocation separately from timing DP. Moderate effort and invalidation risk; benefit unmeasured.

### TF14 Bound expanded booking generation before materialization

Classification: Medium optimization opportunity concentrated in expanded experiments. Above six shortlisted routes, neighbors generates all eligible relocation/swap/reversal descriptors. moveTravel invokes Move.apply, constructs arrangements and computes travel before sorting/truncation. This defeats lazy allocation for expanded ranking. Six-route BOUNDED already caps each family and interleaves bounded output. [BoundedBookingSearch.java:481](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/BoundedBookingSearch.java#L481) [BoundedBookingSearch.java:529](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/BoundedBookingSearch.java#L529) [Guide ch. 42](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/move-selector-reference)

Recommendation: evaluate stable bounded top-k selection or lazy generation with cheap edge-delta ranking before arrangement construction. Preserve tie order, required directed legs and cancellation. Geometric estimates are not automatically safe feasibility bounds. Generation changes affect candidates receiving the deadline budget; evaluate search behavior as well as allocation.

Acceptance: small-fixture order/candidates must match the intended variant, including asymmetric roads and tied estimates. Interruption must preserve validated offers and incomplete status. Later compare descriptors, materialized arrangements, completion, allocation and request latency. No speedup or additional offers are claimed.

### Ranked computation opportunities

| Priority and evidence | Benefit mechanism, risk, effort and future measurement |
| --- | --- |
| 1. TF13, source | Remove invariant preparation/context allocation. Moderate effort and invalidation risk. Hypothesis: less setup per changed route. Prove equivalence, then measure phase allocation and throughput. |
| 2. TF12, source plus guide | Avoid fleet sums and diagnostic fairness objects. Moderate-to-high effort; high rounding/variance risk. Hypothesis: better technician-count scaling. Prove policy/undo equivalence before measuring. |
| 3. TF14, source | Avoid expanded move/arrangement allocation. Moderate effort and search-order risk. Hypothesis: more useful search per deadline; default BOUNDED benefit may be small. |
| 4. Road lookup preparation, source | Repeated string edge keys and buffer arithmetic in RouteTimingSearch. Moderate effort/memory cost. Hypothesis: faster immutable lookup. Compare sparse/dense storage; retain missing-leg/direction semantics. |
| 5. Compact timing states, source | Reduce arrival-map copies without altering dominance or departures. High effort/risk. Hypothesis: lower absence-heavy allocation. Require differential timing/policy tests before profiling. |
| 6. Bounded cold routing, source | MatrixController processes cold pairs sequentially with a bounded synchronized TTL cache. A small executor and single-flight misses may help. Verify GraphHopper lifecycle/thread safety, order, cancellation and CPU limits first. Benefit unmeasured. |
| 7. Runtime/Enterprise tuning, guide | Heap, GC, move threads and node sharing need evidence. Automatic node sharing outside Quarkus requires a non-final provider; current DayConstraintProvider is final. Explicit shared streams already reuse work. Include licensing and CPU cost. |

Code: [RouteTimingSearch.java:61](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteTimingSearch.java#L61), [MatrixController.java:122](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/routing-service/src/main/java/dev/waterflex/routing/MatrixController.java#L122), [DayConstraintProvider.java:13](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayConstraintProvider.java#L13). Guidance: [Guide ch. 16](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/performance), [Guide ch. 42](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/move-selector-reference), [Guide ch. 52](https://docs.timefold.ai/timefold-solver/latest/frequently-asked-questions), [Guide ch. 64](https://docs.timefold.ai/timefold-solver/latest/commercial-editions/performance-improvements). Independent validation must not be removed as a shortcut.

### Concurrency and stateless ownership

| Mechanism | Recommendation and constraints |
| --- | --- |
| Independent requests or metro/day jobs | Preferred initial scaling unit when problems share no mutable planning objects. Bound active work and queue time; distinguish interactive/background work. Caller-owned versions arbitrate shared business state. Dedicated client stacks do not repair unscoped queries. |
| Parallel road preparation | Separate from solver multithreading. Evaluate bounded cold legs after routing thread-safety checks. Preserve failures, cancellation and one deadline; count worker CPU. |
| Multithreaded incremental solving | Enterprise. Stable PlanningIds and safe rebasing required. Isolated Solver instances; fixed workers for repeatability; admission counts solver plus workers. Evaluate after eliminating redundant work. |
| Partitioned search | Enterprise. Constraints cannot cross partitions. Reassignment, fleet rounding and weighted fairness currently cross route partitions. Splitting technicians is not equivalent. Independent metro/day jobs may already be separate problems. |
| Multi-bet solving | Whole solvers on one dataset consume substantial CPU; the guide discourages routine use. Distinct from offline comparison. No evidence justifies adoption. |
| Parallel booking loops | Contexts, counters and LRU caches are mutable. Parallel streams require worker-local state, deterministic reduction, coordinated cancellation and resource admission first. |

See [multithreaded solving](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/multithreaded-solving), [Guide ch. 44](https://docs.timefold.ai/timefold-solver/latest/responding-to-change/real-time-planning), [Guide ch. 52](https://docs.timefold.ai/timefold-solver/latest/frequently-asked-questions), [SearchAdmission.java:1](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/SearchAdmission.java#L1), [BoundedBookingSearch.java:83](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/BoundedBookingSearch.java#L83). Vendor CPU examples are not scheduler capacity guarantees.

## Retained evidence and evaluation roadmap

The second review reruns archive validation, not measurements: raw SHA-256 receipts, frozen runtime/source manifests, canonical case/configuration hashes and selected raw-to-CSV reconciliation. No archive is rewritten. Compact recalculated results and full input receipts are in evidence/second-review/retained-evidence.json. The verifier reuses the existing report validators and reconciles contention results to retained CSVs.

| Evidence | Supported interpretation and limits |
| --- | --- |
| Booking a752ef8e, revision e9c01d1 | 360 cases, INSERTION/BOUNDED. Only 60 sequential pairs match served customers. The 120 concurrent pairs have different served identities, so costs cannot establish savings. The archive uses warm caches only, five seeds, fleets 5/10/20/50, three workloads, concurrency 1/5/10 and ten requests per case. Preserve outcomes and concurrency groups; it does not measure cold routing. |
| Daily budget 331df1f1, revision bfa0cfe | 1,280 cases: four variants, four fleets, two workloads, ten seeds, budgets 60/90/120/240 seconds, ten simultaneous cases. Eight fixed synthetic geometries and deterministic directed fixture routing. Zero recorded violations. Mean accepted modeled dollars: TABU 1,848.54; KOPT 1,842.39; LA 1,842.85; SUBLIST 1,842.13. Descriptive fixture means, not production savings. |
| Daily group reversals | In the 50-technician dispersed group all 40 cases per alternative cost more than matched TABU, though some fairness outcomes improve. Pooled means cannot choose a global winner. Reference and accepted fairness-stage costs differ. |
| Contention 931421d0 and 912c78be | Each has 24 cases: clustered fleets 20/50, seeds 17/23/41, four variants, 120 seconds. Parallel cases six versus one, but revisions differ. Separate throughput/outcome tables provide context, not a same-revision causal estimate of concurrency. |
| Earlier incomplete 616d31e1 | Excluded from aggregate comparisons. Its interrupted/failure evidence remains in its archive. Later completion does not make it complete. No new count is asserted for this campaign. |

Daily benchmark warmup is 200 ms per process. Retained JVM settings include ActiveProcessorCount=2 and SerialGC; concurrent processes may contend. These limit inference about warmed service behavior. Move count is not accepted quality, and ruin/recreate can perform multiple score calculations per move. No SA, Enterprise evaluation, new workload matrix, profiling or load test was run. [Guide ch. 31](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/benchmarking-and-tweaking) [Guide ch. 32](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/solver-diagnostics) [SolverBenchmark.java:1](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/test/java/dev/waterflex/scheduler/optimizer/SolverBenchmark.java#L1)

### Retained contention observations

Each row averages three search seeds at 120 seconds on clustered fixtures. Cells show reference moves/second, fairness moves/second, and accepted modeled dollars. Six-case run revision: 4291ef2e3673052e00d2815cd9597ef9278edeb1. One-case run revision: bfa0cfec45ce98c770796d8aecdc35c9692b8357. Both recorded zero violations. Revisions and execution conditions differ, so these are descriptive observations, not a causal concurrency speedup.

| Fleet and variant | Six parallel cases | One case at a time |
| --- | --- | --- |
| 20 / TABU | 163,518; 70,695; $1,682.14 | 204,324; 89,248; $1,680.54 |
| 20 / KOPT | 149,957; 69,572; $1,677.42 | 189,411; 87,591; $1,677.42 |
| 20 / LATE_ACCEPTANCE | 154,506; 72,179; $1,678.69 | 195,707; 87,777; $1,678.69 |
| 20 / SUBLIST | 155,582; 74,431; $1,677.56 | 191,171; 87,985; $1,677.41 |
| 50 / TABU | 129,587; 34,608; $4,183.25 | 165,381; 46,446; $4,181.69 |
| 50 / KOPT | 123,010; 35,162; $4,164.39 | 148,601; 47,099; $4,164.54 |
| 50 / LATE_ACCEPTANCE | 126,872; 35,189; $4,169.13 | 156,490; 47,021; $4,169.18 |
| 50 / SUBLIST | 132,393; 35,723; $4,163.66 | 153,542; 45,828; $4,164.46 |

The booking archive records 3,600 request observations: 2,707 AVAILABLE, 878 SEARCH_INCOMPLETE and 15 SELECTION_CONFLICT. These mixed-cohort counts describe retained outcomes, not a single deployment success rate. Incomplete searches and conflicts are preserved, not converted to infeasible or silently retried.

### Prioritized evaluation roadmap

1. Resolve TF02/TF03 input and coverage contracts, TF04 exact money, TF10 authoritative facts, and TF01/TF05 ownership/deadlines. Preserve promises, the 6 a.m. America/Chicago cutoff and no-new-overtime. Accept safe rejection of malformed/incomplete inputs and independent validation of every applied result.

2. Differentially validate one computation change at a time: immutable route facts (TF13), fleet scalar aggregates (TF12), expanded booking generation (TF14). Keep policy fixed and independent evaluators as oracles. This report implements no runtime changes.

3. Future measurements separate warmed service and cold requests. Record hardware/JVM, snapshot/road identity, configuration/seed, total and phase budgets, queue time, work counts, allocations, latency and failures. Match served customer identities for cost. Include short production budgets, longer daily budgets and multiple geography samples.

4. Compare TABU/LA with identical list change/swap; then add sublist change/swap/k-opt under one acceptor. Evaluate cold construction and absence repair separately. SA follows temperature calibration. Booking retains insertion as control, testing a faithful Timefold alternative after proving initialization and promises.

5. Compare admission levels on the same binary before routing workers or Enterprise move threads. Avoid route partitions under fleet constraints. Evaluate license expiry, total CPU cost and latency. No algorithm or thread count becomes default from this review alone.

## Reassessment of TF01 to TF11

Original identifiers and detailed acceptance criteria below remain. Current inspection leaves all eleven open. Reconfirmed means the existing characterization ran again, not that every historical suite was rerun.

| Finding | Status at afa035d |
| --- | --- |
| TF01 | Open architecture gap; loading/publication remain application-coupled. [OptimizationService.java:298](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L298) |
| TF02 | Reconfirmed unassigned-input gap; database loaders mitigate supported seeded workflows. [SolverExperiment.java:62](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SolverExperiment.java#L62) |
| TF03 | Reconfirmed duplicate identity/non-finite numeric gaps at the core boundary. [PlanCopies.java:9](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/PlanCopies.java#L9) |
| TF04 | Reconfirmed money boundary issue; timing agreement does not independently check shared arithmetic. [RouteScoringFacts.java:23](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteScoringFacts.java#L23) |
| TF05 | Open; admission, preparation, phases and application lack one daily deadline. [OptimizationService.java:65](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/OptimizationService.java#L65) |
| TF06 | Reconfirmed penalty plateau; improve repair gradients without weakening final feasibility. [RouteTimeline.java:33](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/RouteTimeline.java#L33) |
| TF07 | Open; warmup bias unmeasured. Archive validation does not warm production. [SolverBenchmark.java:1](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/test/java/dev/waterflex/scheduler/optimizer/SolverBenchmark.java#L1) |
| TF08 | Open; seven audit cases rerun, prior FULL_ASSERT/timing suites remain historical. [DayConstraintProvider.java:13](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayConstraintProvider.java#L13) |
| TF09 | Open; DefaultSolver/phase internals and hardcoded version remain. [SolverExperiment.java:6](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/SolverExperiment.java#L6) [DailySolver.java:20](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DailySolver.java#L20) |
| TF10 | Reconfirmed stale facts; copies rebuild facts but setters can desynchronize them. [DayPlan.java:48](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/scheduler-service/src/main/java/dev/waterflex/scheduler/optimizer/DayPlan.java#L48) |
| TF11 | Open README policy contradiction; later correction must not alter policy. [README.md:1](https://github.com/ethan-mcgee/waterflex-scheduler-java/blob/afa035ddf2101d26c12508067d2d9016601db6c3/README.md#L1) |


## Original baseline and audit boundaries

The original audited production baseline was commit 164a1e111370d21ea93f634e674c5fdee4edc90f. The original checkout had an unrelated AGENTS.md modification. Audit work used a separate worktree and branch. No production source, settings, dependency versions, database records, or retained experiment results were changed.

The scheduler pins Timefold Solver 2.6.0, Spring Boot 4.1.1 and Java 25. The requested latest documentation displayed 2.7.0 when reviewed. Recommendations were compared to installed behavior, not assumed to be available unchanged in 2.6.0. The broad quickstart comparison calls the service approach stable, while its detailed service quickstart explicitly labels the generated framework Preview. The latter caution should govern a framework adoption decision. See [S01 Introduction](https://docs.timefold.ai/timefold-solver/latest/introduction), [S02 Quickstart overview](https://docs.timefold.ai/timefold-solver/latest/quickstart/overview) and [S03 Service quickstart](https://docs.timefold.ai/timefold-solver/latest/quickstart/service/getting-started).

The intended service accepts a complete problem dataset and returns proposals and diagnostics. The calling application owns persistence, customer promises, reservations, version checks and applying results. This report evaluates both the present experimental application and that target. It does not treat a UI, database, custom booking algorithm or custom benchmark harness as inherently wrong.

The first review covered the relevant linked documentation families: domain modeling, scoring, Constraint Streams, fairness, solver lifecycle, phases, termination, moves, assertions, benchmarking, Spring integration, service architecture, repeated planning and version migration. It did not crawl every documentation URL. The expanded review supersedes that coverage limit for the inventoried current Solver guide. Managed Field Service Routing models, Timefold Platform APIs, unrelated domain quickstarts, Enterprise-only optimization features, and a wholesale Quarkus migration were outside scope. The coverage register records the actual pages and their application.

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

## Original verification evidence and limits

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

## Original documentation reference register

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

## Expanded documentation coverage register

Every local chapter is listed with its official source and applicability. Section lines, image references/hashes, retrieval receipts and boundaries are in evidence/second-review/coverage.json. R means reviewed, not that every example was compiled or every external link followed.

| Local chapter and official source | Status and application |
| --- | --- |
| 1. [Planning problems](https://docs.timefold.ai/timefold-solver/latest/introduction) (line 17) | R. Bounded time gives a best found plan, not an optimality proof. |
| 2. [Constraints](https://docs.timefold.ai/timefold-solver/latest/introduction) (line 27) | R. Keep feasibility above business preferences. |
| 3. [Technology](https://docs.timefold.ai/timefold-solver/latest/introduction) (line 37) | R. Retain the Java library; no language or framework change needed. |
| 4. [Getting started](https://docs.timefold.ai/timefold-solver/latest/quickstart/overview) (line 49) | R. Choose integration by ownership and maturity, not quickstart convenience. |
| 5. [Getting started: building a service (Preview)](https://docs.timefold.ai/timefold-solver/latest/quickstart/service/getting-started) (line 101) | R. Service framework is preview; assess separately from pure request contracts. |
| 6. [Hello World Quick Start Guide](https://docs.timefold.ai/timefold-solver/latest/quickstart/hello-world/hello-world-quickstart) (line 766) | R. Initialization examples inform TF02; tutorial defaults are not production policy. |
| 7. [Quarkus Quick Start Guide](https://docs.timefold.ai/timefold-solver/latest/quickstart/quarkus/quarkus-quickstart) (line 1825) | R. Quarkus is an alternative, not a required migration. |
| 8. [Spring Boot Quick Start Guide](https://docs.timefold.ai/timefold-solver/latest/quickstart/spring-boot/spring-boot-quickstart) (line 2568) | R. Spring aligns with the application; manage asynchronous solve lifecycle. |
| 9. [Domain modeling guide](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/domain-modeling) (line 3295) | R. Model ordered visits and immutable input facts explicitly. |
| 10. [Modeling building blocks](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems) (line 3353) | R. Retain list variables, canonical identity and safe clone boundaries. |
| 11. [Common patterns](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/common-patterns) (line 5830) | R. Variable durations and absences make time-grain replacement unsuitable without equivalence proof. |
| 12. [Constraints and Score: Overview](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/overview) (line 6023) | R. Preserve hard/medium/soft priorities; money precision is TF04. |
| 13. [Score calculation](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/score-calculation) (line 6344) | R. Keep pure deterministic scoring and incremental collectors; test constraints individually. |
| 14. [Understanding the score (Score Analysis)](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/understanding-the-score) (line 8035) | R. Score analysis is Enterprise in 2.x; retain independent domain diagnostics. |
| 15. [Load balancing and fairness](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/load-balancing-and-fairness) (line 8247) | R. Built-in load balancing is not automatically equivalent to capacity-weighted variance. |
| 16. [Performance tips and tricks](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/performance) (line 8331) | R. Remove repeated invariant work before adding threads; list aggregation loses downstream incrementality. |
| 17. [Using Timefold Solver: Overview](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/overview) (line 8716) | R. Service and library are alternative integration paths. |
| 18. [Building a service](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/service/overview) (line 8750) | R. Pure model ownership is useful even without adopting the preview framework. |
| 19. [REST API](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/service/rest-api) (line 8786) | R. Bounded sync booking and async daily jobs need distinct lifecycle contracts. |
| 20. [Model configuration overrides](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/service/model-config-overrides) (line 9192) | R. Do not expose arbitrary hard-constraint or policy overrides. |
| 21. [Model enrichment](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/service/model-enrichment) (line 9468) | R. Enrichment must complete before solving and be thread-safe. |
| 22. [Demo data](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/service/demo-data) (line 9589) | R. Demo fixtures support teaching, not production capacity claims. |
| 23. [Exposing metrics](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/service/exposing-metrics) (line 9687) | R. Expose queue, routing, solve and validation metrics separately. |
| 24. [Service consumer guide](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/service/consumer-guide) (line 9848) | R. Validate schema, statuses and output ownership at the caller boundary. |
| 25. [Using Timefold Solver as a Library](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/library-integration) (line 10239) | R. Reuse SolverFactory; create isolated Solver instances and close managed pools. |
| 26. [Configuring Timefold Solver](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/configuration) (line 10538) | R. Termination, environment mode and reproducibility are explicit contracts. |
| 27. [Adjusting constraints at runtime](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/constraint-overrides) (line 10686) | R. Runtime weights require validated allowed ranges and cannot weaken customer promises. |
| 28. [Quarkus integration](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/quarkus) (line 10880) | R. Framework-specific optional alternative; no migration benefit established. |
| 29. [Spring Boot integration](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/spring-boot) (line 11148) | R. Retain Spring integration; avoid long work on request threads without lifecycle controls. |
| 30. [JPA/JAXB/JSON integration](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/jpa-jaxb-json-integration) (line 11418) | R. Use DTO boundaries and canonical identities rather than exposing persistence entities. |
| 31. [Benchmarking](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/benchmarking-and-tweaking) (line 11622) | R. Paired datasets, multiple seeds and adequate warmup are required for evaluation. |
| 32. [Solver diagnostics](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/solver-diagnostics) (line 12711) | R. Use assertions and independent checks; profiling is a future activity, not executed here. |
| 33. [Deploying to the Timefold Platform (Preview)](https://docs.timefold.ai/timefold-solver/latest/deploying-to-platform/introduction) (line 13147) | R. Solver-guide deployment alternative reviewed; external Platform corpus excluded. |
| 34. [Deploying to the platform guide (Preview)](https://docs.timefold.ai/timefold-solver/latest/deploying-to-platform/guide) (line 13224) | R. Deployment packaging does not remove ownership, versioning or license duties. |
| 35. [Platform model metadata](https://docs.timefold.ai/timefold-solver/latest/deploying-to-platform/model-metadata) (line 13371) | R. Metadata and schemas are useful contracts; no Platform adoption proposed. |
| 36. [Using metrics](https://docs.timefold.ai/timefold-solver/latest/deploying-to-platform/metrics) (line 13488) | R. Deployment metrics complement application outcomes, not cost or feasibility evidence. |
| 37. [Optimization Algorithms: Overview](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/overview) (line 13510) | R. Compare construction plus local search; no algorithm is universally best. |
| 38. [Construction heuristics](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/construction-heuristics) (line 14247) | R. Initialize cold lists; seeded reoptimization can retain assignments; regret insertion is documented but unimplemented. |
| 39. [Local search](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/local-search) (line 14934) | R. Compare LA and Tabu first; SA needs temperature and time-gradient calibration. |
| 40. [Exhaustive search](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/exhaustive-search) (line 15494) | R. Exhaustive search is a tiny-fixture oracle candidate, not a production booking replacement. |
| 41. [Neighborhoods: A new way to define custom moves](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/neighborhoods) (line 15644) | R. Neighborhoods is preview with compatibility and weighting limitations. |
| 42. [Move Selector reference](https://docs.timefold.ai/timefold-solver/latest/optimization-algorithms/move-selector-reference) (line 16542) | R. Separate moves from acceptors; retain JIT selection and verify list-specific support. |
| 43. [Continuous planning](https://docs.timefold.ai/timefold-solver/latest/responding-to-change/continuous-planning) (line 18167) | R. Keep history, published promises and draft planning separate. |
| 44. [Real-time planning](https://docs.timefold.ai/timefold-solver/latest/responding-to-change/real-time-planning) (line 18216) | R. ProblemChange is for a living solver; immutable per-request snapshots fit the stated target. |
| 45. [Non-disruptive replanning](https://docs.timefold.ai/timefold-solver/latest/responding-to-change/non-disruptive-replanning) (line 18395) | R. Hard promise preservation cannot be replaced by a soft disruption tax. |
| 46. [Assignment Recommendation API](https://docs.timefold.ai/timefold-solver/latest/responding-to-change/recommendation-api) (line 18440) | R. Enterprise recommendation API is an insertion candidate, not an end-to-end booking engine. |
| 47. [Vehicle Routing Quick Start Guide](https://docs.timefold.ai/timefold-solver/latest/quickstart/quarkus-vehicle-routing/quarkus-vehicle-routing-quickstart) (line 18510) | R. List modeling and cascading timing are useful references; preserve exact absence-aware timing. |
| 48. [How is Timefold Solver Licensed?](https://docs.timefold.ai/timefold-solver/latest/frequently-asked-questions) (line 19630) | R. Community and Enterprise have different license boundaries. |
| 49. [Does Timefold offer pre-built models?](https://docs.timefold.ai/timefold-solver/latest/frequently-asked-questions) (line 19636) | R. Managed models are an optional separate product evaluation. |
| 50. [Which versions of Java, Quarkus, and Spring Boot are supported?](https://docs.timefold.ai/timefold-solver/latest/frequently-asked-questions) (line 19642) | R. Check framework/Java matrix against installed 2.6.0, not just latest examples. |
| 51. [Will Timefold Solver replace our human planners?](https://docs.timefold.ai/timefold-solver/latest/frequently-asked-questions) (line 19653) | R. Human review remains a useful experimental control. |
| 52. [Infrastructure requirements](https://docs.timefold.ai/timefold-solver/latest/frequently-asked-questions) (line 19670) | R. Bound active CPU work; more heap or cores needs workload evidence. |
| 53. [Can Timefold Solver be included in a (GraalVM) native application?](https://docs.timefold.ai/timefold-solver/latest/frequently-asked-questions) (line 19734) | R. Native startup savings can trade away JIT solve throughput. |
| 54. [Why is Timefold Solver so much slower in Quarkus Dev mode?](https://docs.timefold.ai/timefold-solver/latest/frequently-asked-questions) (line 19740) | R. Do not use dev-mode performance as deployment evidence. |
| 55. [Upgrading Timefold Solver: Overview](https://docs.timefold.ai/timefold-solver/latest/upgrading-timefold-solver/overview) (line 19752) | R. Review upgrades separately; none performed in this audit. |
| 56. [Upgrade Timefold Solver to the latest version](https://docs.timefold.ai/timefold-solver/latest/upgrading-timefold-solver/upgrade-to-latest) (line 19761) | R. 2.7 preview move API changes caution against copying current syntax into 2.6. |
| 57. [Upgrade from Timefold Solver 1.x to 2.x](https://docs.timefold.ai/timefold-solver/latest/upgrading-timefold-solver/upgrade-from-v1) (line 19852) | R. Removed 1.x APIs and commercialized features inform compatibility checks. |
| 58. [Backwards compatibility](https://docs.timefold.ai/timefold-solver/latest/upgrading-timefold-solver/backwards-compatibility) (line 20488) | R. Public api/config compatibility differs from impl and preview packages. |
| 59. [Variable Listeners to Custom Shadow Variables](https://docs.timefold.ai/timefold-solver/latest/upgrading-timefold-solver/migration-guides/variable-listeners-to-custom-shadow-variables) (line 20526) | R. Declarative shadow suppliers are candidates only with complete dependency and purity tests. |
| 60. [Chained planning variable to planning list variable](https://docs.timefold.ai/timefold-solver/latest/upgrading-timefold-solver/migration-guides/chained-variables-to-planning-list-variable) (line 20900) | R. Current list model already follows the migration direction. |
| 61. [@ShadowVariablesInconsistent to structural scores](https://docs.timefold.ai/timefold-solver/latest/upgrading-timefold-solver/migration-guides/shadow-variables-inconsistent-to-structural-score) (line 21058) | R. Structural-score migration is not applicable to the current simple inverse-shadow model. |
| 62. [Enterprise Edition](https://docs.timefold.ai/timefold-solver/latest/commercial-editions/commercial-editions) (line 21139) | R. Enterprise feature availability is not evidence of speedup for this workload. |
| 63. [Installing Timefold Solver Enterprise](https://docs.timefold.ai/timefold-solver/latest/commercial-editions/installation) (line 21165) | R. Artifacts and license must both be configured before evaluating Enterprise. |
| 64. [Performance improvements](https://docs.timefold.ai/timefold-solver/latest/commercial-editions/performance-improvements) (line 21224) | R. Automatic node sharing has restrictions; it does not repair fleet-list rescans. |
| 65. [Multistage moves](https://docs.timefold.ai/timefold-solver/latest/commercial-editions/multistage-moves) (line 21296) | R. Custom multistage moves are Enterprise and require invariant-preserving tests. |
| 66. [License management](https://docs.timefold.ai/timefold-solver/latest/commercial-editions/license-management) (line 21310) | R. License expiration is an operational dependency if Enterprise is adopted. |

Additional pages reviewed: [Multithreaded solving](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/multithreaded-solving) covers workers, partitioning and independent jobs; [Framework integrations](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/integration) reinforces planning/persistence separation. Solver-guide deployment chapters 33 to 36 are included. Separate managed-model and Platform manuals are excluded; Field Service Routing is an alternative product evaluation, not an implementation recommendation.

No inventoried official guide page remained inaccessible. Initial Python HTTP requests returned 403; PowerShell HTTPS retrieved all 60 URLs. API references were followed only where needed; converted examples were not all compiled. These limits prevent a claim of exhaustive API/ecosystem coverage.

## Second-review verification and delivery limits

Fresh checks at afa035d: seven characterization tests passed with the Maven nullability profile; four supporting-helper boundary tests passed. Retained validation checked 360 booking, 1,280 daily-budget and two sets of 24 contention raw inputs, as well as their frozen manifests and configuration/case hashes. Earlier full Java, portal and experiment-toolkit results remain explicitly historical. No production Java or portal source changed, so those full local suites were not rerun for this document update.

The verifier checks all 66 chapter records, 60 retrieved official URLs, pinned source locations, artifact hashes, the primary document and 113 unchanged assets, and exact ordered Markdown-to-Word paragraph/cell text and hyperlink parity. Initial evidence-helper schema/field mistakes were corrected, with both failed verification logs retained separately from the successful run. They are authoring-helper failures, not benchmark failures.

The packaged Word renderer could not locate LibreOffice. Its failure is retained; Microsoft Word PDF export and bundled Poppler are used for page inspection. The final page count and inspection receipt are recorded in evidence/second-review/visual-qa.json. No benchmark, profiling campaign, new dependency, public API change or runtime fix was performed. CI status belongs to the review PR and must be checked there; historical local results are not presented as current CI.

Historical hash reconciliation: all 14 original logs are stored with LF line endings in Git, while their original manifest hashes describe CRLF output. Reconstructing CRLF exactly reproduces every original hash and byte count. The second-review receipt records both byte identities; original files and manifest remain unchanged. This is a storage-format discrepancy, not newly rerun historical evidence.
