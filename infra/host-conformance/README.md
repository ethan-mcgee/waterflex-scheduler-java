# Scheduler host conformance kit

Proves, against a running scheduler and over its public API only, that the scheduler answers every call the way
[`docs/api/openapi-v1.yaml`](../../docs/api/openapi-v1.yaml) says, and shows a host's engineers working examples of
each call sequence: snapshots, booking (offers, select, release, confirm), daily and repair proposals, commits,
idempotent replays, cancellation, and the refusals a host must handle. Run it before go-live and after every scheduler
upgrade. It needs nothing but the scheduler's URL and a token: no database access, no repository code, no portal.

## Requirements

- Node 22 or newer.
- A scheduler reachable at its API address, with a routing service for the metro you name.
- A token for a **dedicated tenant**, for example `conformance`. The kit creates and confirms bookings and commits
  proposals under its own technician IDs on dates two to five days ahead; each booking search replaces the tenant's
  booking state for its metro and date, so never run it with a tenant that serves real customers. Issue the token on the
  scheduler host and store it in a file readable only by you:

  ```sh
  java -jar scheduler-service.jar --command=issue-token conformance "Conformance kit" "go-live check" \
    | sed -n 's/^token: //p' > conformance.token
  ```

  On the scheduler VM, run the same command through the `scheduler-admin` service (see `deploy/vm/README.md`).
  Revoke it with `--command=revoke-token <id>` when you are done.

## Running

```sh
cd infra/host-conformance
npm ci
npx tsx conformance.ts --url https://scheduler.internal.example --token-file ./conformance.token \
  --metro metro-omaha --time-zone America/Chicago --area 41.22,-96.05,41.28,-95.95
```

| Option | Meaning |
| --- | --- |
| `--url` | The scheduler's base URL, without `/api/v1`. |
| `--token-file` | File holding the dedicated tenant's token (`wfs_` and 43 characters). |
| `--metro` | A metro the scheduler routes (one of its `ROUTING_METRO_URLS`). |
| `--time-zone` | The metro's IANA time zone; dates and shifts are local to it. |
| `--area south,west,north,east` | A box of streets inside the metro, at most one degree each way. The kit places its technicians, visits and jobs at fixed points spread over it, the same on every run. Keep it to an area where any two points are well under an hour's drive apart. |
| `--points fixture` | Instead of `--area`: the fixed points of `infra/fixture-routing.mjs`, for a scheduler routed by that fixture (CI). |
| `--out` | Report directory, default `./conformance-report`. |
| `--only` | Comma-separated groups to run, for example `Booking,Daily`. |

A run takes about half a minute: four daily or repair proposals use the shortest daily search the contract allows
(5 seconds). The exit status is 0 when every case passed, 1 when any failed and 2 for a usage error.
`conformance-report.json` and `conformance-report.md` in the report directory list each case, what it proves, its time
and, for a failure, the request that answered unexpectedly with the scheduler's reply.

## What it checks

| Group | Cases |
| --- | --- |
| Integration | 401 with a `Bearer` challenge without a valid token; `whoami` |
| Strict parsing | an unknown field, a duplicate key, money as a JSON number, a missing `plannedStart`, missing `absences`, a `tenantId` in the body: each a 400 `INVALID_REQUEST` |
| Routes | evaluate times the host's order; geometry draws every leg and marks each stop `source: HOST`; an unlocatable technician-day is skipped as `LOCATION_UNRESOLVED` |
| Booking | offers honour `offerLimit` and `windowHours`, start on the hour and hold about 10 minutes; a `requestId` replays its answer and refuses a different body; select, select again, a second offer of the set (409), release, release again, select after release (409), an unknown offer (404); a new search ends earlier offers; confirm against a fresh snapshot returns the job under its own ID inside the offered window, and replays the same receipt; 22 horizon dates (400); a job ID equal to an appointment ID (400); a frozen date (422) |
| Daily | an improvable day is proposed `IMPROVED`, replays, commits with the proposal's assignments, replays the commit, and refuses a second commit (409 `NOT_COMMITTABLE`); a commit missing a technician-day (400); a changed `lastModified` (409 `STALE` listing the change); an unknown proposal (404); a frozen date (422) |
| Repair | a day-long absence moves the absent technician's visit to another technician |
| Cancellation | an unknown `requestId` (404) or a malformed one (400); a running daily proposal is cancelled (202), answers 409 `CANCELLED`, and its `requestId` runs again on retry |

Every response body is checked against the spec's schema exactly: an unknown or missing field fails the case.
Problem `message` text is for people and never compared; cases check the `error` code. `test/spec.test.ts` keeps the
kit's schemas equal to the spec, so the kit cannot drift from the contract it proves.

## Reading the snapshots as examples

`src/snapshot.ts` builds snapshots as a host should: every required field present, `absences: []` sent explicitly,
money as decimal strings, `sequence` and `plannedStart` in the same order, one `lastModified` per technician-day, and
the client's policy and rates on every call (the kit sends its own test values). `src/cases.ts` shows each call
sequence with the checks a host's integration should make on the answers.

## Development

```sh
npm run typecheck
npm test
```

CI runs both in "Portal checks" and runs the kit against the CI scheduler and fixture router in "Database and core
workflows".
