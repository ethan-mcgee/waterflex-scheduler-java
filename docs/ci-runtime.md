# CI runtime and coverage

`.github/workflows/ci.yml` runs on pull requests to `main` and pushes to `main`. Markdown-only changes skip it. Independent work runs in parallel jobs to shorten push-to-result time; more concurrent runners and repeated service setup cost more total runner minutes.

Two other workflows run outside pull requests: `solver-assertions.yml` (scheduled Timefold `FULL_ASSERT`, `NON_INTRUSIVE_FULL_ASSERT` and `PHASE_ASSERT` campaigns) and `maintenance.yml` (weekly image builds and npm audit).

## Jobs

- `java-javac`: the complete Maven reactor, including the opt-in `campaign-benchmark` module, under javac.
- `java-nullability`: the same reactor under ECJ with the strict nullability settings (`-Pnullability`, reading `.settings/org.eclipse.jdt.core.prefs`).
- `portal-checks`: Prisma generation, lint, typecheck, unit tests and production build of `web/`, plus the host conformance kit's typecheck and unit tests.
- `infrastructure-evidence`: actionlint and ShellCheck on the workflows, Compose configuration and the experiment and campaign toolkit tests. The field evidence, generated reports and Timefold audit live in the local `research/` folder and are checked there, not in CI. Java 25 is required by the real-JVM lifecycle fixture.
- `integration-core`: owns a PostgreSQL service, migrates and seeds `waterflex_test`, starts the fixture routing service and the scheduler, and runs the database contracts and the business smokes: booking location, depot pin setup, client solver settings, client snapshot, public API booking, dispatch, repair, depot and assignment changes, cancellation and road geometry, fake data, two clients in one metro, portal overnight optimization, shared address matching, technician home pin and the sequential booking test runner. It then runs the host conformance kit and the API load tool against the scheduler.
- `integration-remote`: builds the scheduler and solver service and runs the remote-calculation business workflows in an isolated schema.
- `browser-regressions`: Playwright Chromium against an isolated schema.

**Build and unit** (needs `java-javac`, `java-nullability`, `portal-checks`, `infrastructure-evidence`) and **Booking and optimizer integration** (needs `integration-core`, `integration-remote`) are aggregate checks. They run with `always()` and succeed only when every dependency's result is `success`, so a failure, cancellation or unexpected skip cannot produce a green aggregate. Cancelling the whole workflow can cancel an aggregate too, which is also not success.

`infra/test_ci_workflow_contract.py` pins this structure: it checks the jobs, the aggregates' dependencies and that retired jobs and settings stay gone.

## Secrets

The PostgreSQL service containers of the integration and browser jobs pull from Docker Hub with `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN`, so pulls are not rate limited. GitHub gives a workflow run that Dependabot started only the Dependabot secrets, not the repository's Actions secrets. The owner therefore keeps a copy of both under Settings, Secrets and variables, Dependabot. Without them, every Dependabot pull request fails while its service containers start ("Initialize containers"), before any build or test runs, while the same workflow passes on other branches. When the Docker Hub token is rotated, update both copies.

## Measuring runtime

The pre-split baseline (five successful runs on 2026-10-07, job and step timestamps, queue time excluded) is kept locally in `research/evidence/ci-runtime-baseline.json`. When comparing against it, use normal successful runs of equivalent revisions and event types, report queue delay separately from job execution, and name the slowest job. Runner hardware varies between hosted runs.
