# Phase 11: executable benchmark layers

This phase implements B07-B14 using Timefold Community 2.6.0. It adds an opt-in benchmark executable, complete-policy and paced caller adapters, independently seeded synthetic datasets, validated historical snapshot tools and frozen Layer A fairness targets. Production solver settings and caller-owned persistence/application authority remain unchanged. Calibration, equal-dataset inference and selection are phase 12 and later gates.

## Build and run

Build with Java 25: `./mvnw -Pcampaign-benchmark package`. The executable is `campaign-benchmark/target/campaign-benchmark-0.1.0-SNAPSHOT.jar`. Its native benchmark dependency is confined to this optional module. Default service builds do not include it.

Use `experiments/configs/campaign-v2-template.json` as an explicit schema template. Replace every placeholder artifact path/hash, dataset/model/routing identity and declared setting. Real adapters additionally require `policy` with `regularWindowThreshold`, canonical decimal strings `utilizationThreshold` and `fairnessAllowance`, and `bookingDeadlineMs`. For example: `{"regularWindowThreshold":2,"utilizationThreshold":"0.9","fairnessAllowance":"0.02","bookingDeadlineMs":5000}`. No CLI experiment-parameter overrides are accepted.

Run `python infra/experiments.py validate CONFIG.json`, then `dry-run CONFIG.json`, then `run CONFIG.json`. The existing runner owns fresh JVMs, affinity, TCP 47983 serialization, original/frozen artifacts, recursive hashes, failure retention and no-retry dispatch. Resume only undispatched identities through the archived toolkit. Changing code, settings or inputs requires a separately registered campaign.

The adapter accepts the runner's immutable request and create-new receipt paths. It validates request/case/settings/artifact hashes, actual JVM flags and affinity before execution. Missing or unsupported measurements stay null with an explicit reason. Required unavailable internal diagnostics fail the experiment. Outer receipt success describes adapter execution; independent schedule eligibility is counted separately. Invalid inputs are contract-only outcomes and never contribute scheduling validity or cost comparisons.

## Layer A: native solver

`layer: solver` uses the real pinned `PlannerBenchmarkFactory`. Each case specifies one complete solver configuration, native parallelism one, sub-single count one, move threads NONE and the effective seed. No inherited solver phase list is used. Release tests demonstrate that inherited phase lists append, while the adapter has exactly one local-search phase or construction followed by local search.

Shared `CampaignSolverConfiguration` constructs TABU/LA, weighted move selectors, forager limits, fixed/diminished-returns/unimproved-time termination and enclosing spent/step caps. Public XML is retained and parsed independently from native result XML. Import reads successful sub-single values, rather than negative aggregate sentinel counters, and verifies effective phases, seed and caps. Solver solve time is separate from benchmark/report wall time. Termination cause is not inferred from elapsed time.

The generated caller XML now spells its existing 15-second limit as ISO duration `PT15S`. The pinned JAXB duration adapter uses `Duration.parse`; the older `15s` spelling was silently left null by XML loading. The caller's existing execution override and shared search guard already enforce the same 15-second allowance. The corrected spelling makes the declaration inspectable without changing that allowance. Historical resource-based experiment controls remain preserved.

`SnapshotFileIO` is stateless and produces fresh entities for each read. It parses sealed indexed facts and imports proposals against the original immutable facts, exact demand coverage and pins. Score text is retained as reporting evidence and never trusted for independent validation. HTML, XML, requested statistic CSV files, input and solved proposal snapshots, effective XML and failure diagnostics live in the attempt archive outside Maven targets. The native API finishes failed batch reports before propagating failure to the outer no-retry runner.

Public 2.6.0 periodic statistics use a fixed 1000 ms sampling interval. BEST_SCORE is event-driven. A requested different cadence is reported unavailable, rather than silently substituted. Complete-policy and workflow layers explicitly report native statistics unavailable. JFR remains a separately declared diagnostic cohort.

## Layer B: complete policy

`layer: policy` measures a complete `DailyCalculation` operation, including its own reference, target creation, fairness and independent acceptance. Every treatment computes its own reference. Raw output contains the operation/cleanup receipt, baseline/reference/candidate outcomes, decision diagnostics and the independently assessed retained proposal. A rejected candidate retains the caller baseline, including an explicit ineligible baseline when repair did not succeed.

The default production operation remains 20 seconds with 15 seconds shared search, 10 seconds reference, unused reference time transferred to fairness, and the existing validation reserve. Benchmark overloads accept explicitly declared shorter contract or longer offline allocations without changing production defaults. `SolverEngine` preserves early-stop criteria when overriding the remaining spent allowance. Repair cohorts use their own repair phase and never produce mixed feasible cost rankings.

Repair receipts report unresolved candidate and retained demand and their terminal eligibility. Independently validated intermediate time-to-feasibility remains null with a reason: the public adapter validates final proposals, while intermediate BEST_SCORE CSV points alone are scoring evidence. It never substitutes final solve time or zero for that missing measurement.

## Layer C: paced caller workflow

`layer: workflow` uses a separately configured embedded or remote caller with the benchmark Spring profile and isolated `waterflex_test` database. The source input is a version 1 workflow envelope containing `datasetJson`, caller `baseUrl`/`endpointIdentity`, `expectedConfiguration`, preparation calls, disposable `warmupRequests` keyed by operation, measured `requests`, independent `verification` calls and cleanup observation/poll milliseconds. Calls have explicit `id`, `method`, `path`, JSON `body` and `expectedStatus`. Requests have `call`, nullable paired `cancelAfterMs`/`cancellation`, and explicit cleanup calls.

Before measured calls, the adapter verifies actual caller XML, variant, seed, phases, move/forager settings, assertion mode and deployment. Daily workflows describe unchanged production allowances. Booking uses its separate explicit policy deadline and `phase: booking`, with inapplicable daily allocations zero. The pacing mode is `paced-arrival`.

Every intended arrival is retained. Client concurrency exhaustion produces a GENERATOR_CAPACITY observation instead of hiding queue delay or silently reducing load. Actual start, HTTP response completion and completion after cancellation/cleanup are separate times. Timeout is an observed transport failure, never an invented exact server duration. Response bodies retain actual queue, routing, persistence and reservation diagnostics; before/after statistics retain admission state, solver XML, routing measurements, CPU/heap and database counts. Independent caller audit runs after admission cleanup observation. A non-successful response or incomplete cleanup cannot become a valid workflow result.

Warmup invokes the actual selected caller operation with distinct disposable request identities. Declared scheduler/provider cache preparations are recorded separately from JVM warmup; provider cache state is an experiment declaration, not a fabricated observation. Daily cancellation has no new public endpoint; its undeclared per-request cancellation remains null, while actual worker/admission cleanup is observed. Booking uses the existing explicit search cancellation endpoint.

`web/scripts/smoke-campaign-workflow.ts` and the hosted paced-caller CI job exercise real PostgreSQL, routing, reservations, dataset export and booking/daily workflows in both deployments. These are acceptance fixtures, not historical inputs or performance comparisons. Successful fixture cleanup is scoped to its generated IDs; failed fixtures and all campaign artifacts remain inspectable. Local Docker unavailability means hosted acceptance is a required delivery gate.

## Corpus and setup jobs

The same executable accepts explicit version 1 JSON setup jobs: `java -jar JAR JOB.json CREATE_NEW_RECEIPT.json`. Setup work is outside measured campaign solves. Preserve its configuration, receipt and output hashes alongside campaign evidence.

`experiments/configs/corpus-v1.json` declares seven families, two independent dataset seeds and assigned/cold/partial/repair/invalid-input cohorts, producing 70 inputs and a manifest. Geometry, skills, windows, durations, absences and assignments use independent family-specific random streams, unaffected by solver seed. Utilization comes from explicit technician/visit counts, durations and capacity. Directed road durations use the declared synthetic geometry/speed/bias model. Manifest initial eligibility is recorded, not forced to true. Synthetic corpus results do not establish historical or production savings.

Run the corpus job once into its create-new output directory. To generate another corpus, save a new JSON with a distinct output directory. Earlier generated data must not be overwritten. The invalid-input cohort has intentionally missing rates, null scheduling outcome and an expected input-rejection marker.

Historical capture starts with POST `/internal/benchmark/dataset`, using an explicit nonblank `request_key` and actual metro/date. The guarded caller captures and hydrates its complete indexed immutable snapshot; it may initialize existing revision bookkeeping, and does not solve or apply schedules. It returns `{dataset, policy}`. Save the dataset string and its SHA-256 as the source artifact.

The export response uses explicit canonical decimal-string policy fields across the Jackson 2 calculation and Jackson 3 HTTP boundaries. Hosted acceptance saves the raw snapshot response before schema validation and requires independently valid transport, cleanup and post-cleanup audit results, as well as a successful adapter process. The retained export correction records the initial numeric-ratio response failure without accepting coercion.

`export`, `import` and `anonymize` setup jobs require exactly `version`, `kind`, pinned `input` (`path`, `sha256`), create-new `output`, `dateShiftDays`, `latitude`, `longitude`. Export/import use day shift zero and null coordinates. Anonymization shifts all timestamps/absences by the explicit nonnegative day offset, translates coordinates to the supplied anchor, pseudonymizes IDs in original lexical order, removes revision/provider identities and reseals. Index relationships, promised windows, exact road matrix, rates and independent metrics remain preserved. This transformation is pseudonymization, not a formal privacy guarantee. Real historical inputs remain pending until supplied and approved for the studies.

A `freeze-target` job requires `version`, `kind`, pinned `input`, create-new `output`, complete `configuration`, `seed`, `budgetMs` and `policy`. It performs standalone reference setup and requires a complete independently valid zero-overtime reference. The target saves dataset/model/routing identity, reference proposal, policy ceiling, configuration XML/hash, seed and actual setup elapsed time. Every Layer A fairness treatment for that dataset pins this same target artifact. Mismatched facts/models/routing or incomplete reference are rejected. Layer B cannot consume a frozen target because its own reference is part of the measured policy.

## Review evidence

The verification receipt records local checks, exact source hashes, preserved failed checks and raw smoke run identities. Regression tests cover native phase inheritance, enclosing caps, seed/counters, CSV/report/proposal import, complete-policy acceptance, corpus seed independence, exact anonymized metrics, frozen-target mismatch, malformed/null inputs and paced generator misses/cancellation. Hosted acceptance and owner review remain required before phase 12. No adapter acceptance result selects a faster or cheaper algorithm.
