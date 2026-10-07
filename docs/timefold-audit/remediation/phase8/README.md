# Phase 8: shared calculation engine and private solver service

This phase packages the calculation boundary and integrates booking stages. It covers the required service work in TF01, CR09 and CR10. The starting main revision is `5552fabb1308c0e0ead8d1faeb6cdec86657f1c2`, the reviewed merge of PR #57. Earlier receipts and raw evidence are preserved.

## Ownership and packaging

`calculation-engine` contains immutable facts, indexed daily and booking datasets, exact monetary and policy calculations, route evaluation, Timefold configuration, invocation-local daily orchestration, booking search and calendar utilities. Package names stay compatible with existing tests and experiment code. `RoadPoint` replaces the database-owning routing client's nested point type. JDBC extraction and statement timeouts live in caller-only `DatabaseFacts` and `DatabaseDeadline`.

`solver-service` is a separate Spring Boot executable. Its runtime dependency graph has no JDBC driver, datasource, scheduling database, routing client, reservation persistence or apply operation. The service does not scan the caller's components. WaterFlex owns snapshot transactions, roads, overflow orchestration, durable attempts, reservation witnesses, confirmation and locked application. Daily reference search, target creation, fairness and independent validation stay within one calculation operation. The phase preserves the current fairness gate; skipping impossible fairness targets remains step 9.

The standalone service exposes:

- `POST /v1/solve/daily`
- `POST /v1/solve/booking`
- `DELETE /v1/solves/{requestId}`
- `GET /health`, a liveness response without scheduling data.

Calculation and cancellation require a bearer service token containing at least 32 non-whitespace characters. The default bind address is loopback. The optional `remote-solver` Compose profile exposes the service only to the internal Compose network, without a published host port. Operators must keep private connectivity and use HTTPS for deployments beyond the provided loopback/internal Compose transports. Tokens have no source-controlled production default.

## Replay contract and result validation

The version 1 envelope carries a UUID request identity, operation, remaining allowance, SHA-256 of the exact payload and the payload itself. The payload is a JSON string inside the envelope so its bytes and content identity survive transport exactly. Unknown fields, duplicate keys, trailing content, missing creator fields, null primitive values and scalar coercion are rejected. Decimal monetary and fairness values use canonical plain strings. Both requests and responses have an 8 MiB byte limit; the HTTP response subscriber enforces its limit while reading.

Daily payloads contain the existing sealed indexed dataset and explicit policy rules. Caller capture includes real endpoint/customer coordinates, schedule revisions, configuration and reservation/snapshot revisions, provider identity, permitted variant/seed and remaining allowance. No missing locations or versions are invented. Booking payloads contain a sealed versioned dataset with immutable captured days, requests, explicit stages and indexed directed roads. Dense and sparse booking encodings canonicalize to the same directed facts and content hash. Missing roads and explicit unreachable roads remain distinct. Payload size and decode performance comparisons are still step 15 work.

Booking stages are insertion, refinement and one declared overflow date. Each stage includes all required roads and can replay without callbacks. WaterFlex fetches insertion roads, obtains the deterministic neighborhood shortlist, fetches those neighborhood roads, and explicitly submits refinement. Overflow remains caller orchestration with a separately captured complete extended horizon.

Results include proposals, unresolved daily demand, independent validation, policy eligibility, completeness, termination/coverage diagnostics, elapsed timing, input identity and policy/cost/score versions. Loaded Timefold artifact version and SHA-256 accompany every response. The HTTP adapter requires matching request identity, input hash and loaded engine artifact. Transport failures never retry or fall back to another solve.

Daily proposals are rebuilt exclusively from caller facts. Duplicate, missing and foreign demand, changed route coverage and changed pinned prefixes fail validation. The caller recomputes coverage, scoring agreement and policy eligibility, then retains the phase 7 revision fences and independent validation under locks at apply. A matching remote hash or score never grants application authority.

Every returned booking candidate is independently evaluated against the caller's snapshot before reservation witnesses are prepared. Validation checks its allowed window, qualification, exact coverage, insertion position, route resources/arrivals/segments, fleet cost and overtime deltas, fairness delta and assignment changes. Coverage/resource counts and the zero-overtime authorization prohibition are checked separately. Reservation preparation and confirmation keep their existing independent validation.

Validated insertion candidates survive optional refinement deadline, routing, transport or invalid-response failures. The result records `DEADLINE`, `ROUTING_UNAVAILABLE`, `REFINEMENT_UNAVAILABLE` or `REFINEMENT_INVALID` and explicit incompleteness. This is preservation of an already computed insertion result, never a fallback calculation. Primary insertion failures do not manufacture offers.

## Admission, deadlines and cancellation

Daily caller deadlines still start before caller admission, snapshot/routing preparation and persistence. The adapter sends only the remaining exploration allowance. Service parsing, admission, decoding, calculation, result encoding and response preparation consume that allowance. Service receipt time is subtracted before launching a worker. Daily search remains capped at 15 seconds within the original maximum 20 seconds. The existing request reserve remains, and remote execution has a conservative additional local reserve; neither is a demonstrated tail calibration.

Booking retains its existing foreground policy clock and explicit durable maximum of two minutes. A stage cannot extend the original caller deadline. Optional refinement receives the remaining explicit refinement cap after routing preparation.

Service admission defaults to two active calculations and at most sixteen waiters, with at most one background daily operation. Booking has admission priority. At most eighteen request identities exist in transient cancellation state. Duplicate active identities return 409 and exhausted transient capacity returns 429. Completed entries are removed; durable retries and attempt outcomes belong to WaterFlex.

Queued daily cancellation is published before admission. Active daily cancellation cooperatively terminates the public solver. Booking checkpoints observe cancellation. The HTTP adapter checks the caller clock while awaiting its single request and sends best effort DELETE on cancellation or transport failure. DELETE requests are cleanup only. A cancellation response acknowledges the request to stop, not worker completion. Capacity and transient ownership remain until actual execution and cleanup stop.

## Configuration and rollout

`scheduler.calculation.mode` is explicitly `EMBEDDED` or `REMOTE`; the default remains `EMBEDDED`. Remote mode requires `scheduler.calculation.url` and `scheduler.calculation.auth-token`. Spring environment equivalents are `SCHEDULER_CALCULATION_MODE`, `SCHEDULER_CALCULATION_URL` and `SCHEDULER_CALCULATION_AUTH_TOKEN`. The solver requires `SOLVER_AUTH_TOKEN`; it supports explicit capacity, queue, variant and seed settings. Caller and service variant/seed must match.

For an explicit local Compose trial, provide a token, select `SCHEDULER_CALCULATION_MODE=REMOTE` and start the `remote-solver` profile. Removing remote selection restores embedded execution for new operations. There is no automatic per-request fallback. Do not change production defaults until step 15 application reliability, latency and matched-resource gates pass and the owner reviews a promotion PR.

## Verification and limits

The phase adds real HTTP daily and booking replay, fixed-work proposal/policy/coverage equivalence, dense/sparse booking equivalence with asymmetric roads, malformed/null/version/provenance rejection, missing-road and forged-candidate checks, bounded transient/duplicate admission, queued and active cancellation, retained worker capacity, caller-driven DELETE without retries, and insertion preservation on transport or validation failure. Cold-demand coverage and caller rejection of moved pinned prefixes are included. The new cold HTTP regression found a populated construction result being reused as an empty cold fairness input. The corrected fairness seed uses the valid populated mode internally and restores the requested operation mode in results; the failed run is retained.

CI retains all embedded booking, reservation, optimizer, time-off, cancellation and browser gates. It also runs remote booking, optimizer, time-off, bounded-reservation and cancellation workflows in a separate PostgreSQL schema. Local PostgreSQL acceptance remains unavailable because Docker Desktop did not start; hosted results must pass before delivery is complete. Compose configuration and workflow validation do not claim Docker image execution.

The initial hosted remote run passed booking, optimizer and time-off but rejected required booking refinement. A real HTTP rearrangement regression reproduced the cause: a negative fairness improvement was serialized using monetary-rate restrictions. The calculation protocol now uses canonical signed decimal strings for calculation quantities; monetary rate DTOs retain their nonnegative, precision and scale validation. The initial hosted failure and local reproduction logs remain inspectable in the correction receipt.

The verification receipt records exact commands, raw evidence hashes, source identities and which checks passed at recording time. Failed intermediate checks remain inspectable. Replay fixtures establish contract behavior only. They do not establish production savings, service capacity or the allowed 5% p95 transport tolerance. Steps 9 through 16 remain pending owner review and their own evidence gates.
