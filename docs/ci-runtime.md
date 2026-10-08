# CI runtime and coverage

The CI workflow runs independent work in parallel to shorten push-to-result time.
The coverage inventory was taken from main at `3690f16` before restructuring.
PR/push triggers, Markdown exclusions, schedules, solver budgets, browser checks,
and caller benchmark acceptance remain unchanged. The target is 5-7 minutes for
comparable successful hosted runs; this is not a measured post-change result.
More concurrent runners and repeated service setup can increase total runner minutes.

## Completed-run baseline

Baseline source: GitHub job and step timestamps for five successful runs on
2026-10-07, before this change. Durations exclude queue time and are not billing
records. No workflow was dispatched to collect this baseline.

[Retained job timings and five slowest steps per job](evidence/ci-runtime-baseline.json).

| Run | Build and unit | Integration | Browser | Caller acceptance |
| --- | ---: | ---: | ---: | ---: |
| [37687456196](https://github.com/ethan-mcgee/waterflex-scheduler-java/actions/runs/37687456196) | 6:37 | 8:02 | 3:58 | 3:42 |
| [37687424374](https://github.com/ethan-mcgee/waterflex-scheduler-java/actions/runs/37687424374) | 6:46 | 11:33 | 3:47 | 3:02 |
| [37687179935](https://github.com/ethan-mcgee/waterflex-scheduler-java/actions/runs/37687179935) | 6:55 | 11:18 | 3:41 | 3:30 |
| [37686206321](https://github.com/ethan-mcgee/waterflex-scheduler-java/actions/runs/37686206321) | 5:23 | 10:16 | 4:17 | 3:29 |
| [37685706081](https://github.com/ethan-mcgee/waterflex-scheduler-java/actions/runs/37685706081) | 6:29 | 9:33 | 4:09 | 3:24 |

Integration was the longest job in all five samples. Remote scenarios took
129-138 seconds, and time-off repetitions took 56-169 seconds. The baseline
revision ran time-off once on PRs and three times on main, which explains much
of that difference. The approved implementation runs all three repetitions on
both event types, sequentially in their dedicated job.

## Parallel jobs

- `java-javac`: complete reactor, including the opt-in benchmark module, under javac.
- `java-nullability`: the same reactor under ECJ with existing strict nullability settings.
- `portal-checks`: Prisma generation, lint, typecheck, unit tests, and production build.
- `infrastructure-evidence`: workflow/Compose checks, generated reports, all infra Python tests, and both audit helper suites. Java 25 is required by the real-JVM lifecycle fixture.
- `integration-core`: database contracts and core business scenarios.
- `integration-reservations`: offer limits, reservation lifecycle, and cross-instance cancellation.
- `integration-time-off`: three sequential repair smoke repetitions.
- `integration-remote`: all remote-calculation business scenarios.

Each integration job owns its PostgreSQL service and builds its required services.
Core retains the original database-test build; the other groups package without
unit tests because both complete compiler passes run independently. No job waits
for build artifacts. Remote starts only routing and the remote scheduler/solver.
Browser and caller-acceptance jobs are unchanged.

The original **Build and unit** and **Booking and optimizer integration** names
remain aggregate checks. They execute with `always()` and succeed only when every
expected dependency has result `success`. Failure, cancellation, or unexpected
skipping cannot produce a green aggregate. Whole-workflow cancellation may cancel
the aggregate itself; that is also not success.

## Coverage inventory

The four original Java verification commands become two complete-reactor passes,
removing duplicate calculation-engine work without removing either compiler mode.
Portal commands move unchanged. All workflow, Compose, report generation, and audit
checks move to the evidence job. The explicit baseline-assembly and algorithm-report
Python invocations are removed because `test_*.py` discovery already executes both.
Java/portal summaries and failure artifacts are owned by their respective jobs.

Original integration step destinations follow. Setup/diagnostic steps are repeated
where needed; scenario commands and environments are unchanged except that remote
packaging moves to setup and time-off uses three repetitions on PRs as well.

| Original step | Destination jobs |
| --- | --- |
| Install portal dependencies and generate Prisma client | `integration-core`, `integration-reservations`, `integration-time-off`, `integration-remote` |
| Migrate, seed, and check schema | `integration-core`, `integration-reservations`, `integration-time-off` |
| Benchmark cleanup PostgreSQL regression | `integration-core` |
| Immutable snapshot, reservation and database admission gates | `integration-core` |
| Start fixture routing and scheduler | `integration-core`, `integration-reservations`, `integration-time-off` |
| Durable search and snapshot database contracts | `integration-core` |
| Frozen service calendar database contracts | `integration-core` |
| Booking smoke | `integration-core` |
| Booking location contracts with reservations and isolated data | `integration-core` |
| Configurable offer limits across both booking paths | `integration-reservations` |
| Pending reservation dependency guards | `integration-core` |
| Optimizer smoke | `integration-core` |
| Multi-depot dealership smoke | `integration-core` |
| Depot pin setup smoke | `integration-core` |
| Shared address matching smoke | `integration-core` |
| Technician home pin smoke | `integration-core` |
| Time-off repair smoke | `integration-time-off` |
| Sequential booking and preview smoke | `integration-core` |
| Start common-reservation rollout test instance | `integration-reservations` |
| Common reservation booking lifecycle | `integration-reservations` |
| Cross-instance search cancellation and lost response cleanup | `integration-reservations` |
| Bounded rearrangement and concurrency with rollout disabled at confirmation | `integration-reservations` |
| Cancellation preserves a pending rearranged reservation | `integration-reservations` |
| Remote calculation business workflows in an isolated database schema | `integration-remote` |
| Print service logs on failure | `integration-core`, `integration-reservations`, `integration-time-off`, `integration-remote` |
| Summarize database tests | `integration-core` |
| Upload integration diagnostics | `integration-core`, `integration-reservations`, `integration-time-off`, `integration-remote` |

## Local validation results

- Actionlint 1.7.12 with ShellCheck 0.11.0, Compose configuration, and offer-limit interpolation passed.
- Both complete Java reactor passes: 283 tests each, zero failures, one opt-in assertion skip per pass. The dedicated assertion workflow is unchanged.
- Portal: Prisma generation, lint, typecheck, 110 unit tests, 18 explicit nullability tests, and production build passed.
- Infra: 218 tests at the full-suite run, one Windows-inapplicable skip; the final five workflow contract tests also passed after the last test addition.
- Audit helpers: 10 plus 3 tests passed. Generated field evidence verified without tracked changes.
- Coverage inventories and the aggregate shell truth-table check passed.

| Isolated local integration group | Result | Elapsed seconds |
| --- | --- | ---: |
| `integration-core` | passed | 149.82 |
| `integration-reservations` | passed | 131.34 |
| `integration-time-off` | passed | 190.45 |
| `integration-remote` | passed | 126.27 |

These groups ran sequentially locally. Their elapsed times include local setup and
are not a hosted speedup measurement. Initial local attempts exposed sandbox access
restrictions, a Java launcher without adjacent JDK tools, timezone-sensitive fixtures,
and the Linux-only npm timing wrapper. Those attempts were retained under `.scratch/`;
only the final four-group run passed in full. No production code was changed to make
those attempts pass.

## Validation and measurement

Local verification results are recorded in the implementation PR. The Windows
integration harness uses the existing installed portal dependencies, Git Bash for
scenario commands, managed Java/Node startup, and a fresh PostgreSQL container per
group. The harness sets UTC to match the hosted runner and bypasses the existing
Linux-only npm timing wrapper while retaining its underlying smoke commands.
It exercises scenario isolation but is not an Ubuntu-hosted runtime measurement.

After hosted checks are available, compare normal successful PR runs against the
linked baseline. Report queue delay separately from job execution, identify the
slowest job, and compare equivalent revisions/event types. Runner hardware and the
extra PR time-off repetitions affect comparability. Do not claim the 5-7 minute
target is achieved until hosted results support it. No additional manual workflow
runs are needed for baseline collection.
