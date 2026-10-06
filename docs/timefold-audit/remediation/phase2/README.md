# Step 2: validated datasets and immutable facts

Step 2 starts at merged phase 1 revision `e197204ee72ab6b6aae0c2943b0e42633fd344b0`. The original [baseline](../baseline.json), audit reports and evidence remain preserved. [verification.json](verification.json) records local checks and limits; database/workflow gates run in the associated PR's normal CI.

## Runtime boundary

`PlanFacts` is the authoritative immutable revision for technician identity, shifts, absences, limits, qualifications, visit/service/window/duration/original-assignment facts, road reachability, rates and buffers. `DayPlan` holds mutable route assignments and inverse shadows plus a scoring view of that revision. Matrix/rate/demand replacement setters were removed. A scoring target can change only while retaining the same revision. Technician facts freeze at plan initialization.

`PlanCopies.copy` validates before constructing its ID map, preserves demand order, shares the facts revision, and allocates independent entities, route lists and inverse shadows. Duplicate identities, duplicate assignments, distinct visit objects masquerading as the same canonical identity, invalid windows/capacities/absences/rates and ambiguous legacy road keys fail explicitly. Evaluator and score-report boundaries verify entity facts against their revision.

Daily engine entry requires complete assigned demand and every directed road its declared current neighborhoods may read. The current selectors can propose unqualified assignments, so completeness includes all technician-to-visit departures, visit-to-technician returns and distinct visit-to-visit directions, not just initially used or qualified pairs. Missing and explicit unreachable roads are separate. The production caller records an omitted road as unreachable only after `RoadClient.matrix` completes its full requested pair set; request/transport failures still throw.

Absence repair and locked repair application use `PlanCopies.withAbsence` to create a new fact revision, clear old fairness targets and bind caller visit metadata to the replacement entities. Ordinary apply retains its independent locked validation. Single-route booking/timing evaluations can still use a caller's larger road pool and report infeasibility on unavailable travel; the daily search completeness check applies at daily engine entry.

Five audit characterizations are now desired-behavior regressions in `TimefoldAuditEvidenceTest`: duplicate technician rejection, duplicate visit rejection before copy, nonfinite rate rejection, unassigned daily rejection and immutable matrix/scoring revision sharing. The exact-money and categorical-score-plateau characterizations remain for steps 3 and 4, with their historical identities preserved.

## Indexed wire contract

`DailyDataset` provides `parse`, explicit draft `seal`, `json(DENSE|SPARSE)`, `contentHash`, immutable facts and fresh `toDayPlan` replay. This is a library contract for offline inputs and future adapters, not a new business endpoint. The fixture at `scheduler-service/src/test/resources/daily-dataset-v1.json` is an unsealed synthetic contract example.

Every object below rejects unknown and missing fields. Required fields reject null. Indices are zero-based into the declared arrays, integers are checked before conversion, and duplicate identities/assignments/pairs/JSON keys are rejected before constructing planning entities. Every demand identity occurs once across assigned and unassigned arrays. Nonempty cold/partial or unassigned daily demand and nonzero pinned prefixes fail until step 4 enables those capabilities. Empty demand is supported, including zero technicians.

| Object | Required fields and semantics |
| --- | --- |
| Root | `schemaVersion=1`, `operation=DAILY`, `mode=ASSIGNED|REPAIR|COLD|PARTIAL`, snapshot ID, content hash, versions, routing, revisions, search, rates, locations, services, technicians, visits, unassigned indices and roads. |
| Versions | Policy `zero-overtime-four-hour-v2`, cost `legacy-double-v1`, score `hard-medium-soft-decimal-v1`. Other versions fail. |
| Routing | Nonblank provider, profile and identity. Caller-owned provenance, with no routing callback. |
| Revisions | Schedule token per technician in array order, configuration token and reservation token. Tokens are nonblank strings, not commit authority. |
| Search | A supported `SolverExperiment.Variant`, signed 64-bit seed and remaining request allowance of 1-20,000 ms. It records the allowance; step 6 supplies complete deadline ownership. |
| Rates | Canonical nonnegative decimal **strings** for regular/overtime hourly rates, mileage and buffer percentage; integer buffer minutes in `[0, 2147483647]`. This stage preserves wire decimals and tags the existing double cost model. Exact monetary computation/migration remains step 3. |
| Locations | Unique stable ID and finite numeric latitude/longitude in geographic range. Technician departure/return roles and visit locations reference these indices. Locations may be shared. |
| Services | Unique stable service ID. Qualifications and visit service reference service indices. |
| Technicians | Unique ID, departure/return location indices, UTC-parseable shift start/end, nonnegative signed-32-bit daily/overtime minute limits, qualification indices, explicit absence start/end intervals, assigned visit indices and `pinnedPrefix=0`. Start precedes end. |
| Visits | Unique ID, service/location indices, window start/end, positive signed-32-bit duration minutes, original technician index and original planned instant. Start precedes end. Original assignments are required until the new-demand model lands. |
| Road state | `REACHABLE` with integer seconds/meters in `[0, 2147483647]`, or `UNREACHABLE` with both numeric fields explicitly null. A self-location road is reachable with zero seconds/meters. Unreachable is a fact, not evidence that the overall problem is infeasible. |
| Sparse roads | `encoding=SPARSE`, size equal to location count, entries with from/to indices and state/seconds/meters. Duplicate pairs fail. Missing required pairs fail. |
| Dense roads | `encoding=DENSE`, matching size and square cells with state/seconds/meters. `NOT_REQUIRED` with null numeric fields represents an omitted unused pair; using it for a required pair fails. Both encodings currently cap location count at 10,000. |

The compatibility bridge keeps existing stable technician and visit IDs while mapping each indexed departure/return/customer pair to its legacy endpoint role. Technician/visit IDs must be nonblank and cannot contain `>` or end in reserved `:return`; colliding technician/visit/derived-return identities fail. Location IDs are independent of those aliases. No production road encoding has been selected and no indexed hot-path performance benefit is claimed.

Canonical JSON orders object keys, normalizes instant strings and decimal rate strings, sorts qualification indices and directed sparse entries, preserves indexed array/route order, and excludes the content-hash field from hashing. Dense `NOT_REQUIRED` cells are omitted from the canonical sparse facts. SHA-256 covers facts **and** snapshot/version/routing/revision/search provenance. Dense and sparse representations of the same declared facts therefore share one hash. `seal` explicitly accepts a draft null hash and returns validated canonical sparse JSON; normal `parse` requires the matching hash. Hashes detect content drift and do not authorize apply. The stored canonical JSON is private and exports defensive copies; each replay owns fresh planning entities and shares immutable facts.

Transport encoding selection, preparation/decode p95 measurements, booking envelopes, shared-module/service packaging, construction/pinning and runtime deadline integration remain with their later ledger owners. Production cost/policy/algorithm defaults remain as recorded in the baseline. This phase runs correctness regressions and preserves their failures; it makes no performance, savings or capacity claim.

## Regression evidence

- `DailyDatasetTest`: dense/sparse independent replay and hash equivalence, canonical entry order, strict object/null/duplicate/nonfinite parsing, IDs/indices/assignments/version/pinning rejection, malformed windows/shifts/absences, complete directed neighborhoods, missing/unreachable/self-state rules, altered provenance/hash rejection and empty demand.
- `PlanFactsTest`: copy/shadow isolation, shared immutable facts, input-collection alias isolation, target/revision mismatch, absence revision/target invalidation, duplicate/noncanonical assignments and missing versus explicit unreachable daily roads.
- Existing `DayConstraintProviderTest` and `SolverExperimentTest`: clone/move/undo, independent timing/policy checks and all eight Community variants under FULL_ASSERT.
- Existing booking/reservation/timing/oracle tests remain part of full Maven verification. PR CI additionally runs isolated PostgreSQL, optimizer/time-off/reservation/cancellation and browser workflow gates.

Rollback of this phase is a code rollback, not a database migration or adapter switch. No persisted schema or business-response field changes are introduced. Old reports remain readable and their raw archives are unchanged.
