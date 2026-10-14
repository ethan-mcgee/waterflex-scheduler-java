import { test } from "node:test";
import assert from "node:assert/strict";
import { awaitDailyProposal, pollAfterMs, requestStatus, SchedulerApiError, type DailyProposal, type QueueDependencies, type RequestStatus } from "./schedulerApi";
import type { PublicSnapshot } from "./publicApi";

const requestId = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
/** An empty metro day: the queue is scripted, so the snapshot is only carried. */
const snapshot: PublicSnapshot = { metroId: "metro", timeZone: "America/Chicago",
  rates: { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67", travelBufferPct: "0", travelBufferMinutes: 0 },
  policy: { fairnessBudget: "0", dailySearch: "TABU", costSearchSeconds: 5, fairnessSearchSeconds: 0, bookingDeadlineMs: 5000, regularWindowThreshold: 2 },
  technicians: [], technicianDays: [], appointments: [] };
const request = { requestId, serviceDate: "2026-10-12", snapshot };
const proposal: DailyProposal = { proposalId: "prop-1", inputRevision: "a".repeat(64), decision: "IMPROVED", reason: "COST_REDUCTION: lower cost",
  routes: [], unresolvedAppointmentIds: [], skippedTechnicianDays: [], costCents: 100, overtimeMinutes: 0 };
const status = (state: RequestStatus["state"], result?: RequestStatus["result"]): RequestStatus =>
  ({ requestId, operation: "DAILY_PROPOSAL", state, queuedAt: "2026-10-11T22:00:00Z", ...(result === undefined ? {} : { result }) });

/** A queue that answers 202 and then reads the given statuses in turn, on a clock that the waits advance. */
function scripted(statuses: RequestStatus[]) {
  let clock = 0;
  const waits: number[] = [];
  const dependencies: QueueDependencies = {
    queue: async () => ({ status: status("QUEUED"), pollAfterMs: 3000 }),
    read: async () => {
      const next = statuses.shift();
      if (next === undefined) throw new Error("polled too often");
      return { status: next, pollAfterMs: 3000 };
    },
    sleep: async ms => { waits.push(ms); clock += ms; },
    now: () => clock,
  };
  return { dependencies, waits };
}

test("a queued proposal is polled as Retry-After asks until it completes with its proposal", async () => {
  const { dependencies, waits } = scripted([status("QUEUED"), status("RUNNING"), status("COMPLETED", { status: 201, body: proposal })]);
  assert.deepEqual(await awaitDailyProposal("acme", request, 60_000, dependencies), proposal);
  assert.deepEqual(waits, [3000, 3000, 3000]);
});

test("a finished requestId answers 201 when queued and is not polled", async () => {
  const proposed = await awaitDailyProposal("acme", request, 60_000, { ...scripted([]).dependencies, queue: async () => ({ proposal }) });
  assert.deepEqual(proposed, proposal);
});

test("a queued proposal that ended refused throws its problem, a 429 keeping its wait", async () => {
  const busy = scripted([status("COMPLETED", { status: 429, body: { error: "BUSY", message: "waited too long" }, retryAfterSeconds: 30 })]);
  await assert.rejects(awaitDailyProposal("acme", request, 60_000, busy.dependencies),
    (error: unknown) => error instanceof SchedulerApiError && error.code === "BUSY" && error.status === 429 && error.retryAfterMs === 30_000);
  const cancelled = scripted([status("COMPLETED", { status: 409, body: { error: "CANCELLED", message: "cancelled" } })]);
  await assert.rejects(awaitDailyProposal("acme", request, 60_000, cancelled.dependencies),
    (error: unknown) => error instanceof SchedulerApiError && error.code === "CANCELLED" && error.retryAfterMs === null);
});

test("a result that does not match its status is malformed, never trusted", async () => {
  const wrongBody = scripted([status("COMPLETED", { status: 201, body: { error: "BUSY", message: "m" } })]);
  await assert.rejects(awaitDailyProposal("acme", request, 60_000, wrongBody.dependencies), (error: unknown) => error instanceof SchedulerApiError && error.code === "MALFORMED");
  const other = scripted([{ ...status("RUNNING"), requestId: "1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d" }]);
  await assert.rejects(awaitDailyProposal("acme", request, 60_000, other.dependencies), (error: unknown) => error instanceof SchedulerApiError && error.code === "MALFORMED");
});

test("a proposal still waiting at the limit is given up as a timeout", async () => {
  const { dependencies } = scripted(Array.from({ length: 10 }, () => status("QUEUED")));
  await assert.rejects(awaitDailyProposal("acme", request, 10_000, dependencies),
    (error: unknown) => error instanceof SchedulerApiError && error.code === "TRANSPORT" && error.status === 504);
});

test("the status schema refuses a result outside COMPLETED and an unknown field", () => {
  assert.ok(requestStatus.safeParse(status("COMPLETED", { status: 201, body: proposal })).success);
  assert.equal(requestStatus.safeParse({ ...status("RUNNING"), result: { status: 201, body: proposal } }).success, false);
  assert.equal(requestStatus.safeParse(status("COMPLETED")).success, false);
  assert.equal(requestStatus.safeParse({ ...status("QUEUED"), position: 3 }).success, false);
});

test("a poll waits Retry-After seconds, or three when it is absent or malformed", () => {
  assert.equal(pollAfterMs({ headers: new Headers({ "Retry-After": "5" }) }), 5000);
  assert.equal(pollAfterMs({ headers: new Headers() }), 3000);
  assert.equal(pollAfterMs({ headers: new Headers({ "Retry-After": "soon" }) }), 3000);
});
