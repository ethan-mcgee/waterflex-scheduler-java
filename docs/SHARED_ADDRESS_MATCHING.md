# Shared address matching

Booking, technician creation, depot creation, and depot location editing share `web/lib/geocode.ts`.

`web/lib/streetNormalization.ts` contains all 206 primary street suffix names and their standard abbreviations from [USPS Publication 28, Appendix C1](https://pe.usps.com/text/pub28/28apc_002.htm), retrieved September 24, 2026. The source table's middle column of additional common variants is intentionally excluded. Some primary names share a standard abbreviation, as specified by USPS.

Street comparison ignores case, repeated/surrounding whitespace, and abbreviation periods. It recognizes a suffix only at the end, or before a trailing direction, with a street-name token before it. All eight compass directions normalize at street-name boundaries while retaining a nonempty name. Internal words remain literal: `St Charles Rd` does not become `Saint Charles Road`, and `Circle Dr` does not match `Cir Dr`. Directions are never discarded. Street rules do not apply to city, state, country, ZIP, or house-number comparisons. Submitted address spelling remains in storage.

## Reproduction

Before this change, a returned road of `South 170th Plaza` did not match `2825 S 170th Plz` or `2825 S 170th Plaza`. The regression was run before implementation and failed with an absent result instead of `ROOFTOP`.

After the change, these inputs with Omaha, NE and ZIP `68130` match the same returned house:

- `2825 S 170th Plz`
- `2825 S 170th Plaza`
- `2825 South 170th Plaza`

ZIP `68103` still conflicts with returned ZIP `68130` and is rejected without a correction suggestion. Booking retains its existing unverified-address manual follow-up path when no pin is supplied; conflicting pins cannot become verified locations.

Structured Nominatim requests and retry without state remain unchanged. Coordinate validation, approximate bounds, manual pin confirmation, technician provenance, service-area checks, and depot relocation checks remain in force. The Papillion `2125 Crest Ridge Dr, Papillion, NE 68133` street-only result remains approximate and requires manual confirmation.

Technician and depot forms display timeout, unavailable-service, and malformed-response messages separately from save errors. Address edits clear candidates, confirmations, and stale lookup errors. Cancellation and request versions prevent old responses from replacing newer input; failures retain drafts.

## Verification

- `npm.cmd run test:geocode`: all 206 suffix pairs, explicit common pairs, eight directions, strict conflicts, null/invalid responses, Papillion fallback, and distinct API outcomes.
- `npm.cmd run test:address-matching:integration`: deterministic Nominatim fixture with actual API handlers, isolated Java scheduler, and `waterflex_test`; verifies preview/save agreement, stored spelling, strict ZIP, booking follow-up, and failed-write guards across all consumers.
- Existing depot setup, technician home, multi-depot, and booking integration regressions retain pin and relocation/lifecycle safeguards.
- Browser regressions use `waterflex_test?schema=nullability_ui`, mocked map metadata/tiles, and cover specific errors, draft retention, stale responses, and invalidated confirmations. Booking error rendering requires no implementation change.
- Portal lint, typecheck, production build, nullability tests, and Java 25 `mvnw.cmd -Pnullability clean verify` are required gates.

The integration command requires `DATABASE_URL` targeting `waterflex_test`, and `ENGINE_URL` matching `SCHEDULER_TEST_URL` (default `http://127.0.0.1:18000`). CI runs it with the deterministic routing fixture. Run the existing booking cache-repair test before warming that scheduler with other integration tests.

## Local acceptance evidence, September 24, 2026

All required gates passed: portal lint/typecheck/build, 10 geocoder tests, 17 nullability tests, 13 affected browser regressions, shared-address/depot-setup/technician-home/multi-depot/booking integration commands, and Java nullability verification (133 scheduler tests plus 2 routing tests).

Only `web` was rebuilt with `docker compose up -d --no-deps --build web`. The other five containers retained their uptime; database and map volumes were preserved. Read-only lookups on port 3001 confirmed:

| Input | ZIP | HTTP | Precision |
| --- | --- | --- | --- |
| 2825 S 170th Plz | 68130 | 200 | ROOFTOP |
| 2825 S 170th Plaza | 68130 | 200 | ROOFTOP |
| 2825 South 170th Plaza | 68130 | 200 | ROOFTOP |
| All three Omaha variants above | 68103 | 404 | No match |
| 2125 Crest Ridge Dr, Papillion | 68133 | 200 | APPROXIMATE with bounds |

All accepted Omaha variants returned the same point, `41.2324334, -96.1794909`. Record creation and location-update tests were confined to the isolated test database.
