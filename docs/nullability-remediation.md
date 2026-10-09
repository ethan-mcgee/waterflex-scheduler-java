# Nullability remediation and prevention

## Scope and warning inventory

This change covers both Java services, portal APIs and components, shared TypeScript libraries, persisted scheduling JSON, JDBC mappings, and scripts. It does not deploy services, audit live customer data, backfill coordinates, or repair live schedules.

The supplied pre-implementation audit reported 60 scheduler and one routing ECJ warnings. These were missing contracts and possible dereferences, not 61 confirmed runtime bugs. The warning families and their dispositions are:

| Original diagnostic family | Resolution | Verification |
| --- | --- | --- |
| Unannotated application packages and external generic values | Explicit JSpecify dependency, `@NullMarked` package defaults, checked required-value boundaries | Clean ECJ compilation of main and test sources in both modules |
| Optional request values, candidate/cache misses, unavailable graph | Narrow `@Nullable` declarations and validation before use | HTTP, SQL-null, cache, and routing regressions |
| Timefold no-argument construction and shadow state | Nullable lifecycle fields, checked required fact getters, retained mutable planning fields | Initialization tests and independent scoring/evaluator parity tests |
| Correlated route timing variables | A segment record with required timing values in each independent evaluator | Empty, normal, and absence-separated route tests |
| Positional query arrays, unchecked report casts, nullable lookups | Named records, SQL-null readers, persisted JSON decoders, checked arrivals and versions | Unit and transactional integration regressions |
| Legacy method references and generic inference | Explicit types and contracts, checked library results | Actual pinned ECJ checker, with warnings fatal |

The final clean ECJ run has zero unsuppressed source nullability, raw-type, or unchecked-conversion diagnostics. `.vscode/settings.json` and both Maven child modules use `.settings/org.eclipse.jdt.core.prefs` and the same JSpecify annotation names. The Maven nullability profile uses ECJ 3.46.100, matching the compiler embedded in VS Code Java 1.56.0. The remaining editor diagnostics involved method references passed through unannotated Java functional interfaces; explicitly typed lambdas preserve the same comparator, predicate, and mapping behavior while giving ECJ the application null contracts at those boundaries.

## Confirmed failures and behavior changes

* JSON `null`, arrays, scalar values, malformed JSON, missing fields, invalid dates, and incorrectly typed required values are rejected before writes or downstream calls. Java also rejects scalar coercion and missing routing coordinates. Empty-body action endpoints accept their existing `{}` object wire format.
* The scheduler client decodes successful responses before returning typed values. Upstream errors retain their HTTP status with a safe message; malformed successful responses return 502 and transport failures return 503.
* Geocoder coordinates must be nonblank decimal numeric strings, finite, and within latitude/longitude bounds. Null coordinates no longer become a rooftop result at `(0, 0)`. Actual zero coordinates remain valid. The manual-follow-up flow remains available.
* JDBC readers distinguish SQL null from valid zero and false. A booking without a usable location returns 422; incomplete existing scheduling state returns 409. Required counts, timestamps, schedule versions, assignments, and arrival entries are checked before use. Transactional failures retain reservations and appointments.
* A routable cache row with missing or invalid metrics is fetched again and replaced. Explicit unreachable rows keep null metrics. A routing outage does not create an unreachable cache entry.
* Saved previews validate assignments, versions, routing/configuration provenance, and summaries. Ready time-off reports validate required daily and total metrics, intervals, and changes before approval. Invalid saved state returns 409 and existing repair retry/reconciliation behavior is retained.
* The booking-test runner decodes persisted input, configuration, offers, selection, attempts, and preview results. A malformed journal pauses the run without discarding its raw journal or selected offer.
* Browser response decoders prevent malformed responses from replacing valid state. Dependent actions are disabled after invalid responses. Appointments with missing coordinates remain visible with a location error and are excluded from map plotting. Empty service/technician lists have controlled disabled states.
* Offline simulations reject incomplete metrics before recommendations, and scripts use checked lookups instead of unsupported assertions.

The schema-contract script now compares scalar column types and nullability with Prisma, as well as the Java table/column inventory. Four existing PostgreSQL `timestamptz` mappings are now explicitly marked `@db.Timestamptz(6)` in Prisma: `BookingOptimization.createdAt` and `OptimizationRun.serviceDate`, `createdAt`, and `appliedAt`. This aligns metadata with existing migrations and requires no data migration.

## Intentional absence

* Cancellation/release/applied timestamps, unselected offers, candidate misses, and optional preview keys remain nullable.
* Unavailable technician overrides may have null shift hours. Their absence is retained in configuration fingerprints; available shifts require valid hours.
* Address coordinates remain nullable in persistence. They are displayed as incomplete and rejected only when a routing operation requires them.
* Cache misses and explicitly unreachable road legs remain absent. Only unreachable routes may have intentionally absent metrics.
* The routing graph may be unavailable until a valid graph can be loaded.
* Timefold score, shadow assignments, and uninitialized facts remain nullable during framework construction. Required facts are checked when accessed, preserving no-argument constructors.
* Missing settings retain established documented configuration defaults; missing scheduling facts never receive fabricated zero, false, or empty replacements.

Mockito matcher methods return null sentinels that Mockito consumes while configuring a mock. `MockArguments` contains method-scoped `@SuppressWarnings("null")` only for these framework sentinels, with an explanation. No production class or package disables null analysis. The shared ECJ option permits those narrow suppressions; all unsuppressed diagnostics remain fatal.

## Required gates

Both child modules inherit Spring Boot, so each explicitly configures the `nullability` profile: Maven Compiler 3.15.0, Plexus Eclipse compiler 2.16.2, ECJ 3.46.100, Java 25, and JSpecify 1.0.0. The profile compiles main and test sources with null contracts, raw types, and unchecked conversions enforced. The normal `clean verify` command validates the javac build and tests. The `-Pnullability clean verify` command is the strict ECJ gate that must report zero source nullability, raw-type, and unchecked-conversion diagnostics.

TypeScript retains `strict` and `noUncheckedIndexedAccess`. ESLint explicitly pins parser/plugin 8.70.0 and enforces typed unsafe assignment/call/member-access/return/argument/type-assertion rules and non-null assertions. Application code, libraries, scripts, seeds, browser tests, and test configuration are covered. Generated output and dependencies are excluded.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25'
./mvnw.cmd clean verify --batch-mode --no-transfer-progress
./mvnw.cmd -Pnullability clean verify --batch-mode --no-transfer-progress
cd web
npm ci
npm run prisma:generate
npm run lint
npm run typecheck
npm run test:geocode
npm run test:fake-data
npm run test:optimization
npm run test:dispatch-geometry
npm run test:booking-tests
npm run test:nullability
npm run build
```

After the command-line gates pass, refresh VS Code Java 1.56.0 so its Problems panel reflects the same compiler state:

1. Run `Java: Clean Java Language Server Workspace`.
2. Select `Restart and delete`.
3. Run `Java: Reload Projects`.
4. Close any stale `nullability.log` editor.
5. Confirm that the Problems panel contains no application nullability or type-safety errors.

A missing log tab or a prior `package-info.java` event is stale local editor state unless it recurs after this clean import.

Integration verification uses PostgreSQL 16 with an isolated `waterflex_test` database and `infra/fixture-routing.mjs`. Local verification used PostgreSQL on port 15432, the scheduler on 18000, and fixture routing on 18001. Set `DATABASE_URL`, `JDBC_DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `ROUTING_URL`, `SERVER_PORT`, `SCHEDULER_TEST_URL`, and `ENGINE_URL` to that isolated stack, apply migrations, seed it, and start the services before running:

```powershell
npm run test:schema-contract
npm run test:api-booking:integration
npm run test:optimizer:integration
npm run test:time-off:integration
npm run test:booking-tests:integration
```

The browser suite requires a separate `nullability_ui` schema and refuses to start without it. Fixtures are scoped to test-created IDs and removed after each test:

```powershell
$env:DATABASE_URL='postgresql://waterflex:waterflex@127.0.0.1:15432/waterflex_test?schema=nullability_ui'
npx prisma migrate deploy
npx playwright install chromium
npm run test:ui
```

CI executes the same gates. Its browser step provisions the isolated schema and Chromium with Linux dependencies. The integration job separately checks reservations, valid zero values, malformed saved data, rollback, optimization apply, absence repair, and interrupted sequential-booking reconciliation.

## Verification record

All 28 Java tests pass. Local Java 25 verification passes with both standard javac and the strict ECJ profile. Portal lint, typecheck, all 35 unit tests, the production build, two Chromium browser tests, schema validation, and all four integration suites pass. The tests include no-downstream-call assertions for malformed input, invalid location with retained holds, null-cache refresh, invalid-preview/report rollback, malformed-journal preservation, and interrupted-run recovery. Fixture routing verifies contracts and scheduling protections; it does not establish performance or road quality against a live Omaha graph.
