# Step 6: one daily deadline and cancellation ownership

Starting revision: `30136b22f7f88b1bceb05517d8b397fca4e3678e`, the reviewed PR #55 merge. This phase changes local daily preview and absence-repair execution. It does not change scheduling policy, enable a remote solver, deploy an application, or claim a measured latency benefit.

## Execution contract

`DailyOperation` creates one monotonic 20-second allowance before worker launch and admission. The worker acquires the existing bounded background admission lease and owns it through database preparation, routing, calculation, independent validation, proposal persistence, response preparation and transaction completion. Booking priority and the one-active-background rule remain intact. A nested daily operation is rejected instead of starting another clock.

Reference receives at most 10 seconds. All phases share at most 15 seconds of search allowance; unused reference time can transfer to fairness. Each allocation is also capped by the original operation's remaining time minus the initial one-second validation/response reserve. The charged phase wall time includes engine preparation and the engine's independent outcome assessment, conservatively consuming search allowance. Targets and caller validation also consume the original operation clock. Duration allocation floors to milliseconds because Timefold 2.6.0 rejects finer spent limits.

The existing request scope now bounds routing timeouts, JDBC admission and transport, and PostgreSQL statement/lock timeouts during daily preparation. Preview and repair use the worker's transaction. A deadline guard runs after response preparation and immediately before transaction commit. Repair persistence now rolls back as one unit if validation or response preparation expires. Long solve transactions remain intentionally present until step 7 introduces the caller's short claim/snapshot/persist phases.

The HTTP preview endpoint encodes its JSON body before the transaction commits, inside the operation allowance. The existing JSON object contract remains unchanged, including ISO instants, Unicode and intentional nulls. The framework writes the prepared bytes. Socket delivery after the controller returns is not an acknowledgement that the caller received the response in time. Cross-process remaining-allowance propagation, authenticated cancellation endpoints and remote transport fault injection belong to the service boundary in step 8. No retry or second solve is added.

## Cancellation and scoring

The caller waits only for the original remaining allowance. Timeout or interruption cancels the shared deadline and requests public `Solver.terminateEarly()` on the active solver. Caller interruption is restored. The worker is not forcibly interrupted or marked stopped through `Future.cancel`; only actual worker unwinding closes admission. A bounded shared watchdog repeats public termination requests at a phase cap or operation expiry, covering the race where public `solve()` initializes its early-termination state after cancellation was requested.

Termination is cooperative. An expensive score calculation, outcome assessment, database cleanup or commit acknowledgement can outlive the requested cap. The caller rejects a late result, the commit guard rejects an expired proposal before commit, and capacity remains occupied until cleanup actually finishes. This is not a hard real-time execution guarantee. An acknowledgement failure after a database commit may leave a committed preview without a successful caller response; step 7 must provide durable attempt/reuse semantics for that boundary.

`RouteTimingSearch` no longer reads `SearchDeadline` while calculating scores. Daily engine execution temporarily removes the caller request clock and restores it afterward. Cancellation uses the public solver lifecycle rather than throwing expiry from scoring. Caller preparation, custom booking orchestration and independent caller validation retain deadline checkpoints. No cached score or route result depends on a request's elapsed time.

Every stopped worker emits a receipt with a generated request ID, outcome, elapsed time, queue time, charged search time, cancellation time, cleanup overshoot, phase/search overshoot and late-result flag. Queue and cancellation fields are explicitly null when not observed. Receipts contain no SQL, customer details or fabricated solver counters. These logs are local execution observations, not the durable business attempt records or overnight summaries planned for steps 7 and 9.

## Verification and remaining gates

See [verification.json](verification.json) for final gates, source hashes, raw evidence hashes and all retained prior attempts. Regression coverage includes:

- A 100-millisecond caller timeout with a blocked worker: no late result, lease retained until release, final cancellation/cleanup receipt.
- A one-second queued request: expired work never executes and its queue entry is removed.
- Normal allowance allocation, reference-to-fairness transfer, slow preparation, millisecond precision and nested-clock rejection.
- Public phase termination without worker interruption, caller interruption with restored status, and distinct transport-failure observation.
- A real daily solver constrained by the remaining allowance after preparation, with independently assessed demand coverage.
- Split-shift score calculation and Timefold score updates under a cancelled request scope.
- Delayed independent validation inside a transaction: caller timeout retains capacity, late completion rolls back, no commit occurs.
- Prepared JSON preserving ISO instants, Unicode and explicit null values.

The required local gates are Java 25 clean verification, Java nullability clean verification, portal lint/typecheck/nullability/unit/build, experiment toolkit regressions, audit verifier regressions and immutable baseline/dependency preservation. Hosted database, schema and browser integration and the scheduled assertion workflow must pass on the PR head. Owner review remains a delivery gate. The one-second reserve and cleanup tail require later workload calibration; fixture timings are not production latency or savings evidence. TF05 remains open for its step 7 and step 15 requirements.
