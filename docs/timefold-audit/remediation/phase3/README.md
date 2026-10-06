# Step 3: exact monetary arithmetic

This phase starts at merged [PR #52](https://github.com/ethan-mcgee/waterflex-scheduler-java/pull/52), revision `076966698ebf8fa54127ec8d592666616969833b`. It implements TF04 and the exact-money prerequisite for CR08/TF12. It supersedes the monetary portion of the phase 2 contract; historical reports, raw archives and earlier receipts remain unchanged. Steps 4-16 and all performance studies remain open.

## Calculation contract

The cost model is `exact-fleet-half-up-v2`. Regular hourly dollars, overtime hourly dollars and dollars per mile are immutable `BigDecimal` facts. New wire values are canonical nonnegative plain decimal strings, with at most 35 integer and 30 fractional digits. Normalization removes insignificant trailing zeros. Missing, negative, nonfinite and unrepresentable rates fail. Explicit double constructors remain only as legacy fixture bridges using `BigDecimal.valueOf`; production loaders read decimals directly from JDBC.

For fleet paid minutes P, overtime minutes O and road meters M, the exact dollar amount is `(P-O)*regular/60 + O*overtime/60 + M*mileage/1609.344`. The implementation keeps a decimal numerator over one common denominator, `60 * 1609.344`, without dividing or rounding either component. Timing search compares these exact numerators. Resources are summed across the fleet, then the total is rounded half up once to cents. Route costs are diagnostic amounts and must not be summed to obtain fleet cost.

Booking cost deltas subtract independently rounded before/after fleet totals. Unchanged technicians contribute to both totals, including in the legacy insertion path. Cost ranking remains strict; fairness and stable ordering retain their existing tie semantics. The daily ceiling remains `floor(reference cents * (1 + fairness allowance))`.

Integer cents and ceilings must fit the exact JSON integer range, `0..9007199254740991`. Signed deltas fit its symmetric range. Invalid resources, aggregate overflow and out-of-range results fail explicitly; no saturation or fabricated zero is used. Optimizer improvement storage is widened to BIGINT so it no longer clamps monetary changes to an INT.

Fleet totals and monetary provenance are persisted with each preview. The dispatch portal uses recorded fleet costs, with historical policy totals when available; otherwise it shows unavailable. Formatting uses integer cents, preserving the last cent even at the safe integer bound. Time-off reports use each day's fleet total, then sum those separately rounded daily amounts for their period totals.

## Migration and compatibility

`20261006194000_exact_monetary_contract` is an atomic SQL migration. It converts `omaha_setting.value` and `booking_offer.incrementalCostDollars` to NUMERIC(65,30). Before conversion, `monetary_migration_receipt` retains the source identity, column, legacy decimal rendering, original float8 bits, converted decimal and migration/model identity. The chosen legacy rendering is PostgreSQL's shortest precise round-trip text with `extra_float_digits=3`, rather than a direct float-to-numeric cast. [PostgreSQL 16 numeric documentation](https://www.postgresql.org/docs/16/datatype-numeric.html) specifies that output mode and numeric scale behavior. Migration rejects nonfinite amounts, negative monetary rates, overflows and any conversion that would round away digits. Receipt evidence is retained after proposal invalidation.

All Java monetary inputs are validated against storage bounds before use or binding. New callers must validate before database writes; direct SQL writers must observe the same bounds because a declared NUMERIC scale can round an excessive-scale write. Nonmonetary settings now travel from JDBC as decimals; travel timing retains its existing percentage calculation. Scoring weights and coordinates retain their existing numeric contracts.

New booking offers and sets carry the new cost model. Old pending offers are superseded/expired and their holds and obligations released. Pending daily/repair previews become STALE with COST_MODEL_CHANGED. Interrupted queued/running booking searches fail with that reason and retain their durable evidence. Confirmed appointments, customer windows, applied runs, and confirmed-set idempotency remain intact.

Application also checks model compatibility independently: both booking offer and set versions must match; daily and repair apply require current saved provenance before scheduling mutations. Configuration fingerprints include the cost model. Indexed daily input rejects the old model instead of silently rehashing it. New benchmark outputs carry cost-model identity; old reports remain readable and must not be paired with new monetary outcomes as though the models matched.

## Rollout and rollback

Stop all scheduler writers/workers and the portal before migration. Back up the database and retain that backup identity. Apply the migration, verify its receipts/schema, generate the Prisma client, and start the matching Java/portal versions together. Refresh unconfirmed offers and daily/repair previews; old time-off reports referring to retired previews require fresh analysis. This PR does not deploy or migrate a live database.

Rollback of a dependency, algorithm or adapter must retain the corrected monetary contract. Do not run the old application against the migrated schema or reverse-cast decimal rates to float. A complete rollback requires the coordinated prior application and pre-migration backup while writers remain stopped; preserve the migration receipts separately first. Never restore a backup over newer confirmed bookings. Confirmed customer promises retain their independent locked validation and the 6 a.m. America/Chicago cutoff.

## Verification

The local [verification receipt](verification.json) retains command outcomes, hashes and intermediate compilation failures. Required PostgreSQL, workflow and browser checks also run in the implementation PR CI; a local receipt is not a certification of those remote checks.

- `MonetaryTest`: 255 minutes at 20.02 = 8,509 cents; half-cent neighbors; overtime; exact mileage; 1,000 independently seeded generic BigInteger fraction comparisons; repartition/summation; overflow and exact JSON bounds; canonical wire strings; stale provenance.
- `BookingMoneyTest`: cached booking insertion, full independent evaluation and Timefold scoring agree on fleet rounding; an unchanged half-cent route changes the booking delta; repartition preserves cost and fairness.
- `MonetaryMigrationDatabaseIT`: the actual SQL migration in disposable test schemas; decimal rendering and original bits, receipts, stale previews/searches, pending release, confirmed promises, new defaults, invalid legacy rollback and numeric constraints. CI invokes this explicitly with the existing snapshot integration gate.
- The audit money characterization is replaced by its desired-behavior regression. Only the hard-penalty plateau characterization remains for step 4. The route timing and small-case numeric oracles no longer compute cost through double.
- Portal monetary-string/cent-format regressions, full Java 25/nullability, portal lint/typecheck/nullability/unit/build, schema contracts, booking/reservation/cancellation, optimizer/time-off and browser gates remain acceptance requirements.

No latency or production savings claim is made. Decimal arithmetic and legacy fleet preparation need matched profiling and resource studies in their later owner steps before performance promotion.
