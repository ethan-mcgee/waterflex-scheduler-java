# Booking location review and Omaha map coverage

Booking now reviews location before searching appointments. Lookup does not create a customer, address, job, offer, or reservation. The customer can correct an exact match, place a pin after no match, retry lookup or maps, edit the address, or explicitly request follow-up. Map load and tile failures block pin confirmation.

## Reproduced causes and acceptance

The supplied investigation identified city-filtered misses on North 169th Street, missing locality evidence on Reflection Circle, and Elkhorn represented as an Omaha suburb on Appaloosa Drive. Valley Street and Grandview Avenue had no acceptable lookup result; Cascio Drive had only a street point. The previous API created `ADDRESS_UNVERIFIED` jobs before any scheduling search and accepted only an unchanged geocoder point.

Nominatim's [address categories depend on OSM tagging](https://nominatim.org/release-docs/latest/api/Output/). Parsing now treats absent locality fields as incomplete evidence. City, town, village, hamlet, suburb, and municipality can match the entered locality. Known street direction, house, state, country, and ZIP conflicts remain rejected. Incomplete evidence is approximate and requires manual confirmation. Malformed coordinates, structures, and inverted bounds remain errors.

Booking lookup uses the current depot-circle envelope, three bounded stages (full address, no state, street plus ZIP without city/state), deduplication, and one eight-second deadline. An exact result ends lookup; approximate segments are accumulated across the stages. Shared technician and depot matching inherits parser/locality improvements, while their existing pin-adjustment bounds remain enforced.

## Contracts and persistence

- `POST /api/book/location`: complete address input, typed `MATCHED`, `NEEDS_PLACEMENT`, or `NO_MATCH`, candidates and bounds, and validated service-area circles. Lookup failures return retryable HTTP errors rather than empty successful results.
- `POST /api/book`: `confirmedPin: { lat, lng, manuallyConfirmed: true }` permits a manual location without geocoding. Non-manual callers still require an exact matching house. Missing location returns `LOCATION_REQUIRED` without writes. `followUp: true` explicitly creates an address-follow-up request, with null location/provenance.
- `POST /v1/book/location/validate`: `VALID`, `OUTSIDE_COVERAGE`, `UNROUTABLE`, or `ROUTING_UNAVAILABLE`. This checks depot coverage and a road self-route using the existing 1,000-meter routing snap limit. Directed technician travel, qualifications, capacity, reservations, and time windows remain appointment-search constraints.
- `MANUALLY_CONFIRMED` provenance and nullable `address.pinConfirmedAt` preserve entered address text. Manual confirmation does not fabricate a geocoder timestamp. Existing records are unchanged.
- Nullable `job.bookingRequestFingerprint` stores a SHA-256 identity of normalized request fields, contact details, service, pin, and intent. Same-ID retries reuse a job; changed details or legacy requests with unknown identity return 409. Start over releases existing offers before generating another request ID.

Migration: `20260929180000_booking_location_confirmation` adds two nullable columns. Deploy the migration before the updated portal. Existing scheduled jobs and reservations are not rewritten. Rollback can leave the additive columns in place; do not remove provenance during rollback.

## Prepare and validate maps without cutover

Coverage remains 65 straight-line miles from either configured Omaha depot. Preparation includes another 10 miles. The live depot snapshot on 2026-09-29 produces west/south/east/north bounds `-97.5607844313063,40.0737402285336,-94.49433010934285,42.30026818862802`; these are derived at execution, not hard-coded tile limits.

The import merges Nebraska, Iowa, and [Missouri](https://download.geofabrik.de/north-america/us/missouri.html). Its manifest records source timestamps, SHA-256 checksums, prepared circles and bounds, merged PBF checksum, tile and graph file checksums, and tool versions. Downloads use temporary files before checksum verification. Use a new immutable version for a new source snapshot.

From the repository root, set `DATABASE_URL` to the application database for read-only depot discovery, then run:

```powershell
./infra/prepare-map.ps1 -Version 2026-09-29-booking-coverage
```

The script prepares tiles and the graph, then starts `nominatim-preview`, `routing-preview`, and `tiles-preview`. Nominatim imports into `waterflex-nominatim-preview-2026-09-29-booking-coverage`, a separate versioned volume. Application data and active map services are untouched. Preview ports are 18082, 18001, and 18083. Wait for the Nominatim import and indexing to finish, then run:

```powershell
./infra/validate-map.ps1 -Version 2026-09-29-booking-coverage
```

Validation checks current depot coverage, preview service readiness, routing identity/version, tile bounds, and real geocoder, road, and tile responses for ten geographically selected points across Nebraska, Iowa, and northwest Missouri. It writes a manifest-bound `validated.json` only after success. Failure does not authorize activation. Revalidate if depot coordinates/radii, data, or service versions change.

If interrupted after PBF preparation, retain the version folder and resume the remaining tile/graph/preview commands in `prepare-map.ps1`; do not overwrite active files. `map-import finalize VERSION` refreshes artifact checksums after completed preparation. The legacy symlink activation command also requires a matching validation report, but the versioned Compose override below is preferred because it switches the geocoder volume too.

## Reviewed cutover and rollback

Do not run this section before PR review and an agreed cutover. Save the prior Compose invocation and map version, and keep the old Nominatim volume and map files. Stop accepting booking searches during the switch. Stop the preview Nominatim container before attaching its volume to the active service; two PostgreSQL instances must never share a writable data directory.

```powershell
$env:PREVIEW_MAP_VERSION = '2026-09-29-booking-coverage'
docker compose -f docker-compose.yml -f docker-compose.map-preview.yml stop nominatim-preview routing-preview tiles-preview
$env:ACTIVE_MAP_VERSION = '2026-09-29-booking-coverage'
docker compose -f docker-compose.yml -f docker-compose.map-active.yml up -d --no-deps nominatim routing-service tiles
```

Verify all three active endpoints, rerun service-area booking acceptance, and then reopen booking. Deploy the new application separately with its migration. The five selected reservation/search settings require no change. The existing local Compose edits must remain intact and are intentionally excluded from this PR.

For rollback to the pre-change base configuration, stop the three active map services and run the base Compose invocation with `up -d --no-deps nominatim routing-service tiles`. For a previously versioned deployment, use its saved `ACTIVE_MAP_VERSION` and versioned override instead. Never use `down -v` or delete the application or old Nominatim volumes.

## Verification scope

Unit regressions cover all six street names, rural localities, suburb names, missing locality evidence, strict conflicts, multiple segments, shared timeout signal, malformed response mixtures, invalid request coordinates, retry identity, and depot-circle bounds. Java tests exercise null coordinates and points just inside/outside the 65-mile boundary at 72 bearings, including Iowa and northwest Missouri.

Browser tests cover no-match placement, exact-match correction, explicit confirmation, address edits and stale requests, tile outage, follow-up, search failures, offer limits, selection, and release retry. `smoke-booking-location.ts` requires an initially empty `waterflex_test?schema=booking_location_it`, qualified technicians, and an isolated engine with reservations=true, offer limit=1, bounded search=true, capacity=2, and prewarm=true. It checks read-only lookup, manual search, successful booking, release, concurrent retry, changed-ID conflicts, provenance, and typed failure handling. Street text in this fixture is synthetic and its road point is explicitly supplied; it does not claim those six street addresses were independently geocoded.

Geographic data acceptance is separate from deterministic fixtures. Keep the generated map validation report with the prepared manifest. No live map cutover or production application migration is part of this implementation session.
