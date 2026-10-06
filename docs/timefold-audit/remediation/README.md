# Timefold remediation program

This directory tracks implementation of the [audit](../Timefold_Audit.md) and the accepted 16-step plan. Phase 1 established the baseline in merged [PR #51](https://github.com/ethan-mcgee/waterflex-scheduler-java/pull/51). [Step 2](phase2/README.md), merged in PR #52, introduces validated indexed daily inputs and immutable runtime fact revisions. [Step 3](phase3/README.md), merged in PR #53, adds exact money, migration receipts and compatible reporting. [Step 4](phase4/README.md), merged in PR #54, adds construction, explicit unresolved demand, pinned prefixes and lexicographic repair scoring. [Step 5](phase5/README.md), merged in PR #55, separates public execution from optional diagnostics and adds assertion campaigns, including an explicit pinned ruin/recreate compatibility restriction. [Step 6](phase6/README.md) adds one local daily operation deadline, cooperative cancellation and worker-owned capacity. The ledger records delivered fixes and remaining review/verification gates; performance studies are still pending.

- [Remediation ledger](ledger.md): all TF01-TF14, CR01-CR11, benchmark requirements, owner steps, tests, and evidence gates.
- [Community compatibility inventory](compatibility.md): existing coverage and required version-specific probes.
- [Baseline receipt](baseline.json): starting Git revision, source identities, dependency and configuration hashes, and retained evidence identities.
- [Verification receipt](verification.json): commands, outcomes, limits, and raw logs for this phase.

Each implementation phase ends with a focused, detailed PR and a pause for owner review. No automatic merge or promotion. Later steps remain pending until their prerequisites and their own gates pass. An evidence-backed decision to retain the current implementation can close an experimental recommendation; an unrun experiment cannot.

## Agreed contracts

Use Timefold Community 2.6.0 and Java 25 initially. Dependency upgrades are separate treatments. Preserve the 6 a.m. America/Chicago cutoff, promises, strict booking cost order with fairness ties, daily fairness headroom, and zero-overtime acceptance.

WaterFlex owns the database, reservations, idempotency, durable attempts, and atomic application. A shared calculation module and Spring Boot service accept the same validated versioned dataset and return proposals. Calculation has no database, live routing, or commit authority. Reference, target creation, fairness, and independent calculation validation stay in one daily operation. The caller validates again before persistence and under locks at apply.

The daily operation receives 20 seconds starting before admission, with at most 15 seconds solving. Reference normally receives up to 10 seconds; unused search time may transfer to fairness. Start with a one-second validation/response reserve and calibrate tail overhead. Queueing, preparation, serialization, transport, validation, and persistence consume the original allowance. No automatic remote retry or fallback starts a second solve. Capacity is released only when the worker stops.

Cold, partial, and absence-repair calculations preserve every demand identity as assigned or explicitly unassigned. Only complete, independently valid, policy-eligible proposals can apply. Termination, feasibility, coverage, and applicability are separate fields.

Daily and booking envelopes carry stable technician/visit/service/location identities, assignments and explicit unassigned demand, supported pinning, shifts, absences, windows, durations, qualifications, capacities, exact rates, policy/cost/score versions, routing identity, indexed directed seconds/meters/reachability, snapshot identity and canonical hash, and caller-owned schedule/configuration/reservation revision tokens. Operation mode, permitted search configuration and remaining request allowance are explicit. Validate before constructing identity maps or planning entities; every demand identity appears exactly once.

The private authenticated service exposes `POST /v1/solve/daily`, `POST /v1/solve/booking`, and `DELETE /v1/solves/{requestId}` with bounded transient execution state. Results include proposals, unassigned identities, validation findings, policy eligibility, completeness, termination information, elapsed timings and input/configuration/version provenance. Business endpoints remain caller-owned. Booking snapshot capture, routing, overflow orchestration, reservation preparation and confirmation stay in WaterFlex; each declared calculation stage is replayable without callbacks. Validated insertion offers survive optional refinement failure/expiry with accurate incompleteness.

Caller attempt records contain request key/fingerprint, ownership token, state, expiry and result reference. Identical repeated keys reuse the attempt/result; conflicting inputs fail; concurrent owners cannot persist competing proposals. Capture scheduling facts consistently, acquire routing outside a transaction and recheck revision tokens before calculation. Before persistence recheck schedule, holds, policy, routing and endpoint identities, marking stale results explicitly. Failed or abandoned attempts do not automatically rerun. A hash or remote score never authorizes application.

Decimal wire values use canonical strings. Exact labor and mileage arithmetic uses 1609.344 meters per mile; sum fleet cost before one HALF_UP cents rounding. Booking deltas subtract independently rounded fleet totals. The fairness ceiling is floor(reference cents * (1 + allowance)). Policy, cost, score, routing, configuration, and fact versions accompany proposals; incompatible unapplied proposals need fresh previews.

Dense and sparse road encodings represent the same canonical directed facts. Prefer smaller payloads if both meet preparation/request budgets, otherwise the qualifying encoding with lower decode-and-validation p95. If neither qualifies, block remote promotion. Missing roads and explicit unreachable roads remain distinct.

## Promotion and evidence rules

Any unexplained invalid accepted result blocks promotion. Service changes must meet request budgets and cancellation ownership, with no extra failures, timeouts, or incomplete outcomes and at most 5% higher p95 latency at matched load. Cost-led selection requires the upper bound of the 95% interval for candidate-minus-control accepted cost below zero, with no worse scenario-family mean cost or fairness and no overtime increase. A fairness-led cost increase needs a separately agreed business tradeoff before study.

Equivalent refactors require fixed-work policy equivalence followed by measured throughput or latency benefit. Tune on one corpus, freeze one finalist, and confirm on untouched datasets. Default inference uses 10,000 whole-dataset bootstrap draws, 95% intervals, and an explicit seed. Synthetic results remain synthetic, never production savings or capacity certification. Inconclusive evidence preserves current defaults.

Historical reports and raw archives are immutable evidence. New receipts must not refresh their hashes to conceal drift or relabel old measurements. Failed attempts remain inspectable. This phase records retained archive identities from existing review receipts; it does not claim a fresh traversal of external archives. Production configuration means repository defaults and tracked Compose inputs here, not observed deployed overrides or persisted customer settings. Credentials are not captured.

## Reproduce phase 1

From the repository root, with Git history containing the baseline commit:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-25'
python docs/timefold-audit/remediation/verify_baseline.py
.\mvnw.cmd verify --batch-mode --no-transfer-progress
.\mvnw.cmd -Pnullability clean verify --batch-mode --no-transfer-progress
```

The verifier uses Git blob bytes, independent of checkout line endings, to check the frozen baseline. It also rejects modifications to retained historical files in the current checkout, using Git's declared text filters. It does not regenerate receipts. `--maven-repository` optionally checks the recorded local dependency artifact bytes. Missing Git history or dependency files fail explicitly. Later implementation commits may change runtime sources without rewriting this starting receipt.

The historical audit verifier is a separate check. The Word report was edited after the reviewed report commit, so the baseline records its current byte identity separately. The existing audit verifier still passes text/link parity and historical source/evidence checks on this baseline. Any future discrepancy must be reported and retained, not repaired by rewriting historical evidence in an unrelated implementation PR.
