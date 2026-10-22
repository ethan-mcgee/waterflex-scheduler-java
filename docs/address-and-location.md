# Address matching and booking location

Booking, technician creation, depot creation and depot location edits share one geocoder client, `web/lib/geocode.ts`, backed by the active Nominatim. This page describes how addresses are matched, how booking confirms a location before it searches for appointments, and how map coverage is prepared. The map commands themselves are in the README.

## Address matching

`web/lib/streetNormalization.ts` holds all 206 primary street suffix names and their standard abbreviations from [USPS Publication 28, Appendix C1](https://pe.usps.com/text/pub28/28apc_002.htm) (retrieved 2026-09-24). The table's middle column of additional common variants is deliberately excluded.

Street comparison ignores case, repeated or surrounding whitespace and abbreviation periods. A suffix is recognized only at the end, or before a trailing direction, with a street-name token before it. All eight compass directions normalize at street-name boundaries while keeping a nonempty name. Internal words stay literal: `St Charles Rd` does not become `Saint Charles Road`, and `Circle Dr` does not match `Cir Dr`. Directions are never discarded. Street rules do not apply to city, state, country, ZIP or house-number comparisons, and the submitted spelling is what gets stored.

So `2825 S 170th Plz`, `2825 S 170th Plaza` and `2825 South 170th Plaza` in Omaha, NE 68130 match the same house, while ZIP 68103 conflicts with the returned 68130 and is rejected without a correction suggestion.

Nominatim's [address categories depend on OSM tagging](https://nominatim.org/release-docs/latest/api/Output/), so absent locality fields are treated as incomplete evidence, not as a mismatch. City, town, village, hamlet, suburb and municipality can each match the entered locality. Known street, direction, house, state, country and ZIP conflicts are rejected. Incomplete evidence is approximate and needs manual pin confirmation. Malformed coordinates, structures and inverted bounds are errors.

Technician and depot forms show timeout, unavailable-service and malformed-response messages separately from save errors. Address edits clear candidates, confirmations and stale lookup errors; request versions stop an old response from replacing newer input, and failures keep the draft.

## Booking location review

Booking confirms the location before searching appointments. Lookup creates no customer, address, job, offer or reservation. The customer can correct an exact match, place a pin after no match, retry lookup or maps, edit the address, or ask for follow-up. Map load and tile failures block pin confirmation.

Lookup is bounded by the current depot-circle envelope and runs three stages (full address; without state; street plus ZIP without city and state) with deduplication and one eight-second deadline (`AbortSignal.timeout(8000)` in `web/lib/geocode.ts`). An exact result ends lookup; approximate segments accumulate across stages.

### Contracts

- `POST /api/book/location`: takes a complete address and returns a typed `MATCHED`, `NEEDS_PLACEMENT` or `NO_MATCH` with candidates, bounds and the validated service-area circles. Lookup failures return retryable HTTP errors, never an empty success.
- `POST /api/book`: `confirmedPin: { lat, lng, manuallyConfirmed: true }` allows a manual location without geocoding; other callers still need an exact matching house. A missing location returns `LOCATION_REQUIRED` with no writes. `followUp: true` creates an address follow-up request with null location and provenance.
- `MANUALLY_CONFIRMED` provenance and nullable `address.pinConfirmedAt` keep the entered address text; manual confirmation never fabricates a geocoder timestamp.
- Nullable `job.bookingRequestFingerprint` is a SHA-256 of the normalized request fields, contact details, service, pin and intent. A retry with the same request ID reuses the job; changed details, or a legacy request with no fingerprint, return 409.

Migration `20260929180000_booking_location_confirmation` added the two nullable columns.

## Map coverage

Coverage is the union of each metro's depot circles (`serviceRadiusMi`); maps are prepared for that envelope plus `marginMi`. The import merges the Nebraska, Iowa and [Missouri](https://download.geofabrik.de/north-america/us/missouri.html) extracts and TIGER address data. Its manifest records source timestamps, SHA-256 checksums, the prepared circles and bounds, the merged PBF, tile and graph checksums and tool versions. Each new source snapshot gets a new immutable version.

Validation checks current depot coverage, preview service readiness, routing identity and version, tile bounds, and real geocoder, road and tile responses for points spread across the covered states. It writes a manifest-bound `validated.json` only on success, and activation refuses a version without one. Revalidate when depot coordinates, radii, data or service versions change. The first prepared version's manifest and validation report are kept in `docs/validation/`.

Prepare, validate and activate with `infra/prepare-map.ps1`, `infra/validate-map.ps1` and `infra/activate-map.ps1` (see the README). For rollback, restore the `.env.before-<version>` backup that activation printed and restart `nominatim`, `routing-service` and `tiles`. Never use `docker compose down -v` or delete the application or previous Nominatim volumes.

## Tests

- `npm run test:geocode`: all 206 suffix pairs, common pairs, the eight directions, strict conflicts, null and invalid responses, the approximate fallback and distinct API outcomes.
- `npm run test:address-matching:integration`: a deterministic Nominatim fixture through the real API handlers against `waterflex_test`; checks preview and save agreement, stored spelling, strict ZIP, booking follow-up and failed-write guards. It needs `DATABASE_URL` pointing at `waterflex_test` and `ENGINE_URL` matching `SCHEDULER_TEST_URL` (default `http://127.0.0.1:18000`).
- `web/scripts/smoke-booking-location.ts` and `smoke-booking-geography.ts`: read-only lookup, manual search, booking, release, concurrent retry, changed-ID conflicts, provenance and typed failures, the second against a prepared road graph.
- Browser regressions cover no-match placement, exact-match correction, explicit confirmation, stale requests, tile outage, follow-up and search failures.
