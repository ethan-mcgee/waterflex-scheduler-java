# Sequential booking and optimization tests

`/dispatch/testing` is a local workload runner. It adds synthetic requests to the current Omaha schedule through real booking offers and selection, then creates review-only optimization previews. It never invokes the old generator's configuration changes or cleanup, inserts appointments directly, or applies an optimization.

## Enable locally

Run `docker compose up -d`. Local Compose enables the route by default, forwards the flag to the portal, runs the additive migration, and rebuilds the application images. Open `http://localhost:3001/dispatch/testing`. To opt out, set `LOCAL_BOOKING_TESTS=false` in the root ignored `.env` and recreate the web container. Remove that override or set it to `true` to enable the route again.

The Compose default does not change a host-run portal or production deployment. Those environments remain disabled unless they explicitly set `LOCAL_BOOKING_TESTS=true`. For a host-run portal, also set `DATABASE_URL` and `ENGINE_URL`, apply `npx prisma migrate deploy`, and start Next normally. Use the updated Java scheduler because preview retry keys require the new migration and implementation. The page and APIs require a localhost or loopback host; API requests also reject cross-origin browser calls. Keep the local Compose ports bound to loopback. This is a local test tool, not a staff authentication system.

## Run and review

1. Choose 1-100 requests, an unsigned 32-bit seed, service weights, and an offer policy. Defaults are 20 requests, seed 1, equal weights, and earliest returned offer. Zero excludes a service. Weights are relative probabilities, not exact quotas.
2. Start a run. Known Omaha sample coordinates and the shared seeded random utility generate its inputs. Existing technicians, qualifications, shifts, appointments, service durations, and self-hosted routing determine the results.
3. Each operation creates or recovers one pending job, requests reserved offers, saves the selected window, and calls `/v1/offers/select`. Selection commits the appointment. The next job begins only after that operation finishes. Earliest means the earliest returned window, not an exhaustive search for the earliest possible date.
4. Pause or Stop finishes the active request or preview. Pause allows Resume; Stop is final. Both retain bookings and history. Closing or navigating away ends browser progression after the current operation. Reopen from history or the `?run=` URL and explicitly Resume. A persisted RUNNING status does not itself start a worker.
5. After all requests have BOOKED or NO_OFFER outcomes, the browser advances through one preview per affected service date. If the page closes after the last booking, Resume finishes previews.
6. Review before/proposed drive minutes, paid route minutes, overtime, modeled cost, and changed appointments. Previews cover the entire day, including earlier appointments and bookings from other runs. Skipped and non-improving previews are valid results. The comparison link loads that exact run in Dispatch, where the existing guarded Apply control remains the manual approval path. The test page has no Apply action.

The booking horizon is the next ten weekdays after today in America/Chicago, matching the scheduler. There is no date override. The page shows the current horizon; the run saves its initial horizon and each completed attempt saves its own horizon. A long pause can move subsequent requests into a later horizon.

## Recovery and persistence

The additive `booking_test_run`, `booking_test_request`, and `booking_test_preview` tables retain configuration, ordered inputs, offers, selections, outcomes, durations, attempt history, appointment IDs, and optimization IDs. Each request uses `booking-test:<run UUID>:<ordinal>` as its job ID, booking retry key, and customer/job external tag. Appointments are associated through their unique job IDs. Creation retries use the same run UUID and reject conflicting settings.

`GET /api/dispatch/testing` lists runs; `?id=<UUID>` reads one. POST accepts `{id, action}` with `create` (plus `config`), `resume`, `pause`, `stop`, or `advance` (plus the last returned `revision`). A PostgreSQL advisory transaction lock permits only one advancing or resuming operation across local runs. An atomic revision claim prevents replayed advance requests from booking the next job. Control writes remain available while the operation finishes. PostgreSQL releases the lock on connection loss; independently committed journal writes permit recovery without browser memory.

Engine calls time out after 45 seconds. Before retrying a job, the runner waits behind the scheduler's job transaction and checks for an existing appointment. Lost selection responses retain their saved choice for idempotent reconciliation. Explicit confirmation conflicts pause and clear only the current choice, preserving it in attempt history; Resume can obtain current offers for the same job. Routing, configuration, and ambiguous network errors pause instead of becoming NO_OFFER results. Only a successful empty offer response counts as no capacity, and that outcome continues the run. Page network failures halt progression without an automatic retry.

Day previews use an optional `request_key` on the existing preview endpoint. PostgreSQL serializes callers with the same key, atomically saves the preview and key, and returns the saved run on retry. Reusing a key for another metro or date conflicts. This prevents duplicate history after a lost response. Customer booking and guarded apply rules are unchanged.

## Interpretation and limitations

- Offer availability is BOOKED divided by BOOKED plus NO_OFFER. Pending and failed requests are excluded. Mean offer count uses completed requests. Current error counts exclude recovered errors; attempt history retains those failures.
- Booking latency totals recorded processing attempts for booked requests. It excludes pauses and cannot recover elapsed time from a process killed before saving its timing journal. Days until service is a Chicago calendar-day difference from initial request processing.
- A seed reproduces inputs and random-selection fractions. Schedule state, date, routing data, offer order, and solver execution can change results. Repeated runs add workload and are not identical benchmark snapshots.
- Stop does not undo appointments or release offers from an earlier failed operation. Remaining scheduler reservations expire under the existing ten-minute rule. Bulk cleanup is outside this version.
- A preview is a snapshot, not realized savings. Applied status and timestamp come from the actual optimization run separately. Later changes can invalidate a proposal; Dispatch apply retains its validation and cutoff checks. A frozen date still returns a conflict.
- The runner progresses only from an open browser. It is not a background queue or throughput benchmark. Resume may temporarily conflict while an earlier bounded operation finishes. Resolve routing or database outages before retrying.

## Verification

`npm run test:booking-tests` covers deterministic generation, weights, selection policies, horizon boundaries, and access gates. `npm run test:booking-tests:integration` refuses databases other than `waterflex_test` and requires seeded Omaha configuration plus the deterministic routing fixture and Java scheduler. Set `DATABASE_URL` and `ENGINE_URL` explicitly. It retains tagged test history in that disposable database for inspection.

Integration checks cover sequential order, duplicate creation and advances, pause/resume/stop, concurrent callers, no-offer continuation, routing failures, confirmation conflicts, process interruption, lost confirmation and preview responses, and unchanged promises and assignments before apply. Successful bookings must have a selected scheduler offer set. Existing booking, optimizer/apply, and time-off checks also run in CI. The fixture accepts only fixed Monaco and Omaha sample/home coordinates; it is not a production routing fallback.

For real-road verification, start a small run against the local Omaha graph, inspect saved windows and preview results, reload the run, and follow its Dispatch comparison link. Leave proposals unapplied unless explicitly testing manual approval.
