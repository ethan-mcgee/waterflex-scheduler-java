import { offer, required } from "../lib/contracts";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { prisma } from "../lib/prisma";
import { advanceTestRun, controlTestRun, createTestRun, readTestRun, testEngine } from "../lib/bookingTestRunner";
import { EngineError } from "../lib/engineClient";
import type { SlotOffer } from "../lib/engineClient";

// Intentionally retains run history in the disposable test database for inspection.
if (!new URL(process.env.DATABASE_URL ?? "").pathname.endsWith("/waterflex_test")) throw new Error("Use isolated waterflex_test database only.");
const config = { count: 3, seed: 42, policy: "earliest", weights: [1, 0, 0, 0] };
async function resumed(id: string) { return controlTestRun(id, "resume"); }
async function main() {
  const configuration = () => Promise.all([
    prisma.technician.findMany({ orderBy: { id: "asc" } }),
    prisma.technicianQualification.findMany({ orderBy: [{ technicianId: "asc" }, { serviceId: "asc" }] }),
    prisma.technicianShiftOverride.findMany({ orderBy: { id: "asc" } }),
    prisma.omahaSetting.findMany({ orderBy: { key: "asc" } }),
  ]);
  const configurationBefore = await configuration();
  const prior = await createTestRun(randomUUID(), { ...config, count: 1 });
  const priorStarted = await resumed(prior.id);
  const priorBooked = await advanceTestRun(prior.id, priorStarted.revision);
  assert.equal(required(priorBooked.requests[0]).status, "BOOKED");
  await controlTestRun(prior.id, "stop");
  const id = randomUUID();
  const created = await createTestRun(id, config);
  assert.equal(created.status, "PAUSED");
  assert.equal((await createTestRun(id, config)).id, id);
  const duplicateId = randomUUID();
  const duplicates = await Promise.all([createTestRun(duplicateId, config), createTestRun(duplicateId, config)]);
  assert.equal(required(duplicates[0]).id, required(duplicates[1]).id);
  assert.equal(await prisma.bookingTestRequest.count({ where: { runId: duplicateId } }), config.count);
  await assert.rejects(createTestRun(id, { ...config, seed: 43 }));
  assert.equal(await prisma.job.count({ where: { id: { in: created.requests.map(r => r.id) } } }), 0);
  let run = await resumed(id);
  const revision = run.revision;
  run = await advanceTestRun(id, revision);
  assert.deepEqual(run.requests.map(r => r.status), ["BOOKED", "PENDING", "PENDING"]);
  const corrupt = await createTestRun(randomUUID(), { ...config, count: 1 });
  const corruptRequest = required(corrupt.requests[0]);
  const retainedSelection = offer.parse(required(run.requests[0]).selected);
  await prisma.bookingTestRequest.update({ where: { id: corruptRequest.id }, data: { selected: retainedSelection, attempts: { malformed: true } } });
  const corruptRunning = await resumed(corrupt.id);
  const halted = await advanceTestRun(corrupt.id, corruptRunning.revision, { ...testEngine, offers: async () => { throw new Error("Invalid journal must not request offers"); } });
  assert.equal(halted.status, "PAUSED"); assert.match(required(halted.error), /Malformed booking-test journal/);
  assert.deepEqual(required(halted.requests[0]).selected, retainedSelection);
  assert.deepEqual(required(halted.requests[0]).attempts, { malformed: true });
  assert.equal(await prisma.job.count({ where: { id: corruptRequest.id } }), 0);
  const first = await prisma.appointment.findUniqueOrThrow({ where: { jobId: required(run.requests[0]).id } });
  const selection = offer.parse(required(run.requests[0]).selected);
  assert.equal(first.windowStart.toISOString(), new Date(selection.windowStart).toISOString());
  assert.ok(await prisma.bookingOfferSet.findFirst({ where: { jobId: first.jobId, selectedOfferId: selection.offerId } }), "Appointment came through scheduler selection");
  assert.deepEqual((await advanceTestRun(id, revision)).requests.map(r => r.status), run.requests.map(r => r.status), "Duplicate advance does not move to the next job");
  await controlTestRun(id, "pause");
  run = await readTestRun(id);
  assert.equal(required((await advanceTestRun(id, run.revision)).requests[1]).status, "PENDING");
  run = await resumed(id);
  // A response lost after the booking commit must pause and later reconcile the same appointment.
  run = await advanceTestRun(id, run.revision, { ...testEngine, select: async (job, offer) => {
    await testEngine.select(job, offer); throw new Error("Injected lost confirmation response");
  } });
  assert.equal(run.status, "PAUSED"); assert.equal(required(run.requests[1]).status, "ERROR");
  const recoveredJob = required(run.requests[1]).id;
  assert.equal(await prisma.appointment.count({ where: { jobId: recoveredJob } }), 1);
  run = await resumed(id);
  run = await advanceTestRun(id, run.revision, { ...testEngine, offers: async () => { throw new Error("Must reconcile before offers"); } });
  assert.equal(required(run.requests[1]).status, "BOOKED");
  assert.equal(await prisma.appointment.count({ where: { jobId: recoveredJob } }), 1);
  // No capacity is a completed outcome and does not stop progression.
  run = await advanceTestRun(id, run.revision, { ...testEngine, offers: async jobId => {
    // A deliberately oversized isolated fixture job has no feasible shift.
    await prisma.job.update({ where: { id: jobId }, data: { durationMin: 10000 } });
    return testEngine.offers(jobId);
  } });
  assert.equal(required(run.requests[2]).status, "NO_OFFER"); assert.equal(run.status, "RUNNING");
  const appointments = () => prisma.appointment.findMany({ orderBy: { id: "asc" } });
  const before = await appointments();
  const nextPreview = await advanceTestRun(id, run.revision, { ...testEngine, preview: async (date, key) => {
    await testEngine.preview(date, key); throw new Error("Injected lost preview response");
  } });
  assert.equal(nextPreview.status, "PAUSED");
  run = await resumed(id);
  for (let i = 0; i < 12 && run.status === "RUNNING"; i++) run = await advanceTestRun(id, run.revision);
  assert.equal(run.status, "COMPLETED");
  assert.deepEqual(await appointments(), before, "Previews preserve every promise and assignment, including pre-existing appointments");
  for (const p of run.previews) {
    assert.ok(p.optimizationId);
    assert.equal(await prisma.optimizationRun.count({ where: { requestKey: p.id } }), 1, "Ambiguous preview retry is idempotent");
  }
  const priorAppointment = await prisma.appointment.findUniqueOrThrow({ where: { jobId: required(priorBooked.requests[0]).id } });
  assert.ok(run.previews.some(p => p.serviceDate === priorAppointment.serviceDate.toISOString().slice(0, 10)), "Preview includes the pre-existing appointment's day");
  const dayPreview = await prisma.optimizationRun.findUniqueOrThrow({ where: { id: required(required(run.previews.find(p => p.serviceDate === priorAppointment.serviceDate.toISOString().slice(0, 10))).optimizationId) } });
  assert.ok(JSON.stringify(dayPreview.baselineAssignments).includes(priorAppointment.id), "Entire-day preview contains appointments from earlier runs");

  const errors = await createTestRun(randomUUID(), { ...config, count: 2 });
  run = await resumed(errors.id);
  run = await advanceTestRun(run.id, run.revision, { ...testEngine, offers: async () => { throw new EngineError(503, "Routing unavailable"); } });
  assert.equal(run.status, "PAUSED"); assert.equal(required(run.requests[0]).status, "ERROR");
  assert.equal(run.requests.filter(r => r.status === "NO_OFFER").length, 0);
  run = await resumed(errors.id);
  run = await advanceTestRun(run.id, run.revision, { ...testEngine, select: async () => { throw new EngineError(409, "Injected confirmation conflict"); } });
  assert.equal(run.status, "PAUSED"); assert.equal(required(run.requests[0]).selected, null);
  run = await resumed(errors.id);
  // Hold an operation while a second caller attempts to advance or resume.
  let entered!: () => void; let release!: () => void;
  const inOffers = new Promise<void>(resolve => { entered = resolve; });
  const gate = new Promise<void>(resolve => { release = resolve; });
  const operation = advanceTestRun(run.id, run.revision, { ...testEngine, offers: async jobId => { entered(); await gate; return { jobId, offers: [] }; } });
  await inOffers;
  try {
    await assert.rejects(advanceTestRun(run.id, run.revision), /Another test operation/);
    await assert.rejects(resumed(run.id), /Another test operation/);
    const paused = await controlTestRun(run.id, "pause"); assert.equal(paused.status, "PAUSED");
  } finally { release(); }
  run = await operation;
  assert.equal(run.status, "PAUSED"); assert.equal(required(run.requests[1]).status, "PENDING");
  run = await resumed(run.id);
  let stopping!: () => void; let finish!: () => void;
  const inFlight = new Promise<void>(resolve => { stopping = resolve; });
  const finishGate = new Promise<void>(resolve => { finish = resolve; });
  const stoppedOperation = advanceTestRun(run.id, run.revision, { ...testEngine, offers: async jobId => { stopping(); await finishGate; return { jobId, offers: [] }; } });
  await inFlight;
  try { await controlTestRun(run.id, "stop"); } finally { finish(); }
  run = await stoppedOperation;
  assert.equal(run.status, "STOPPED");
  assert.equal((await resumed(run.id)).status, "STOPPED");
  // Simulate process loss after journal status was saved and booking committed.
  await prisma.bookingTestRequest.update({ where: { id: recoveredJob }, data: { status: "PROCESSING", appointmentId: null } });
  await prisma.bookingTestRun.update({ where: { id }, data: { status: "RUNNING" } });
  run = await resumed(id); run = await advanceTestRun(id, run.revision);
  assert.equal(required(run.requests[1]).status, "BOOKED");
  assert.equal(await prisma.job.count({ where: { id: recoveredJob } }), 1);
  assert.equal(await prisma.appointment.count({ where: { jobId: recoveredJob } }), 1);
  assert.deepEqual(await configuration(), configurationBefore, "Runs never rewrite Omaha configuration");
  console.log("Sequential runner: ordering, policies, idempotency, pause/stop, concurrency, routing/conflict recovery, lost responses, and preview immutability passed.");
}
main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
