// The sequential booking test runner end to end against a running scheduler and the fixture router: each request is
// searched, held and confirmed through the public API as a customer booking for the run's client, each booked day is
// previewed as the client's daily proposal, and purging deletes the run's jobs with the days they leave re-timed.
// Addresses come from the Omaha sample locations the fixture router knows.
//   ROUTING_METRO_URLS=metro-omaha=http://127.0.0.1:18001   SCHEDULER_TEST_URL=http://127.0.0.1:18000
import { apiProposal, offer, required, testAttempt, testRun } from "../lib/contracts";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { prisma } from "../lib/prisma";
import { advanceTestRun, apiTestEngine, controlTestRun, createTestRun, purgeTestRun, readTestRun, TestRunError } from "../lib/bookingTestRunner";
import { BookingRefused } from "../lib/apiBooking";
import { validateTestConfig } from "../lib/bookingTestCore";
import { OMAHA_FAKE_LOCATIONS } from "../lib/fakeDataCore";
import { ensureOmahaConfiguration } from "../lib/omahaConfiguration";
import type { AddressGenerationDependencies } from "../lib/bookingTestAddresses";
import { DEFAULT_CLIENT_ID } from "../lib/clients";
import { connectSmokeClient } from "./smokeApiClient";

// Intentionally retains run history in the disposable test database for inspection. Earlier runs' bookings are purged
// first: the fixture has only ten addresses, and an address with an active appointment is never generated again.
if (!new URL(process.env.DATABASE_URL ?? "").pathname.endsWith("/waterflex_test")) throw new Error("Use isolated waterflex_test database only.");
const CLIENT = DEFAULT_CLIENT_ID;
const base = required(process.env.SCHEDULER_TEST_URL, "SCHEDULER_TEST_URL (a running scheduler)");
const engine = apiTestEngine(CLIENT);
/** The run as the testing page receives it, checked against the page's contract. */
const wire = (run: unknown) => testRun.parse(JSON.parse(JSON.stringify(run)));
const config = { count: 3, seed: 42, policy: "earliest", weights: [1, 0, 0, 0], radiusMi: 30 as const };
let fixtureLocation = 0;
const fixtureGeneration: AddressGenerationDependencies = {
  reverse: async () => required(OMAHA_FAKE_LOCATIONS.filter(location => location.state === "NE")[fixtureLocation++ % 10]),
  routable: async candidates => new Set(candidates.map(candidate => candidate.id)),
};
async function createRun(id: string, value: unknown) {
  let run = await createTestRun(id, value, CLIENT);
  if (!run.generation?.completedAt) {
    run = await resumed(id);
    for (let index = 0; index < 20 && !run.generation?.completedAt; index++)
      run = await advanceTestRun(id, run.revision, { ...engine, generation: fixtureGeneration });
    assert.ok(run.generation?.completedAt, "Fixture address generation completed");
    run = await controlTestRun(id, "pause");
  }
  return run;
}
async function resumed(id: string) { return controlTestRun(id, "resume"); }
async function main() {
  await ensureOmahaConfiguration(prisma);
  const disconnect = await connectSmokeClient(CLIENT, base, "Booking tests smoke");
  try {
    for (const earlier of await prisma.bookingTestRun.findMany({ where: { purgedAt: null }, select: { id: true, status: true } })) {
      if (earlier.status === "RUNNING") await controlTestRun(earlier.id, "stop");
      await purgeTestRun(earlier.id);
    }
    await runs();
  } finally { await disconnect(); }
}
async function runs() {
  await assert.rejects(createTestRun(randomUUID(), config, `missing-${randomUUID()}`),
    (error: unknown) => error instanceof TestRunError && error.status === 422, "A run books only for a client that serves Omaha");
  const configuration = () => Promise.all([
    prisma.technician.findMany({ orderBy: { id: "asc" } }),
    prisma.technicianQualification.findMany({ orderBy: [{ technicianId: "asc" }, { serviceId: "asc" }] }),
    prisma.technicianShiftOverride.findMany({ orderBy: { id: "asc" } }),
    prisma.omahaSetting.findMany({ orderBy: { key: "asc" } }),
  ]);
  const configurationBefore = await configuration();
  const legacyId = randomUUID();
  const legacyConfig = { count: config.count, seed: config.seed, policy: config.policy, weights: config.weights };
  await prisma.bookingTestRun.create({ data: { id: legacyId, config: legacyConfig, horizon: [] } });
  // A run saved before runs named their client books for the Omaha metro's only client.
  let legacy = await readTestRun(legacyId);
  assert.equal(legacy.config.radiusMi, 65, "A legacy saved run is normalized to the original 65-mile radius");
  assert.equal(legacy.generation, null, "A run without a generation journal remains legacy-complete");
  legacy = await resumed(legacyId); legacy = await advanceTestRun(legacyId, legacy.revision);
  assert.equal(legacy.status, "COMPLETED");
  for (const radiusMi of [10, 20, 30, 45, 65] as const) {
    const preset = await createTestRun(randomUUID(), { ...config, count: 1, radiusMi }, CLIENT);
    assert.equal(preset.config.radiusMi, radiusMi);
  }
  await assert.rejects(createTestRun(randomUUID(), { ...config, radiusMi: 31 }, CLIENT), /Invalid input/);
  const metro = await prisma.metro.findUniqueOrThrow({ where: { id: "metro-omaha" }, select: { serviceRadiusMi: true } });
  try {
    await prisma.metro.update({ where: { id: "metro-omaha" }, data: { serviceRadiusMi: 45 } });
    await assert.rejects(createTestRun(randomUUID(), { ...config, radiusMi: 65 }, CLIENT), /cannot exceed/);
  } finally {
    await prisma.metro.update({ where: { id: "metro-omaha" }, data: { serviceRadiusMi: metro.serviceRadiusMi } });
  }
  const randomSeedRun = await createRun(randomUUID(), { ...config, count: 1, seed: null });
  const randomSeedConfig = validateTestConfig(randomSeedRun.config);
  assert.ok(Number.isInteger(randomSeedConfig.seed) && randomSeedConfig.seed >= 0 && randomSeedConfig.seed <= 0xffffffff);
  await purgeTestRun(randomSeedRun.id);
  const missingId = randomUUID();
  let missing = await createTestRun(missingId, { ...config, count: 1 }, CLIENT);
  assert.equal(missing.requests.length, 0, "Draft creation does not create booking requests");
  missing = await resumed(missingId);
  missing = await advanceTestRun(missingId, missing.revision, { ...engine, generation: { reverse: async () => null, routable: async () => new Set() } });
  assert.equal(missing.generation?.acceptedCount, 0); assert.equal(missing.generation?.consecutiveNoProgressBatches, 1);
  for (let index = 0; index < 2; index++) missing = await advanceTestRun(missingId, missing.revision, { ...engine,
    generation: { reverse: async () => null, routable: async () => new Set() } });
  assert.equal(missing.generation?.consecutiveNoProgressBatches, 3, "No-progress batches remain active and visible");
  await controlTestRun(missingId, "stop");
  const interruptedId = randomUUID();
  let interrupted = await createTestRun(interruptedId, { ...config, count: 1 }, CLIENT);
  interrupted = await resumed(interruptedId);
  interrupted = await advanceTestRun(interruptedId, interrupted.revision, { ...engine, generation: {
    reverse: async () => { throw new Error("Injected generation interruption"); }, routable: async () => new Set(),
  } });
  assert.equal(interrupted.status, "PAUSED");
  const savedPending = await prisma.bookingTestGeneration.findUniqueOrThrow({ where: { runId: interruptedId } });
  assert.ok(Array.isArray(savedPending.pendingCandidates) && savedPending.pendingCandidates.length === 1);
  interrupted = await resumed(interruptedId);
  interrupted = await advanceTestRun(interruptedId, interrupted.revision, { ...engine, generation: fixtureGeneration });
  assert.equal(interrupted.generation?.acceptedCount, 1); assert.equal(interrupted.generation?.candidatesTried, 1);
  assert.equal((await prisma.bookingTestGeneration.findUniqueOrThrow({ where: { runId: interruptedId } })).pendingCandidates, null);
  await controlTestRun(interruptedId, "stop");
  const malformedId = randomUUID();
  await createTestRun(malformedId, { ...config, count: 1 }, CLIENT);
  await prisma.bookingTestGeneration.update({ where: { runId: malformedId }, data: { pendingCandidates: { malformed: true } } });
  let malformed = await resumed(malformedId);
  malformed = await advanceTestRun(malformedId, malformed.revision, { ...engine, generation: fixtureGeneration });
  assert.equal(malformed.status, "PAUSED"); assert.match(required(malformed.error), /Malformed booking-test journal/);
  assert.equal(await prisma.bookingTestRequest.count({ where: { runId: malformedId } }), 0);
  const collisionId = randomUUID(), collisionRecordId = randomUUID();
  let collision = await createTestRun(collisionId, { ...config, count: 1 }, CLIENT);
  collision = await resumed(collisionId);
  const collisionLocation = required(OMAHA_FAKE_LOCATIONS.filter(location => location.state === "NE")[9]);
  collision = await advanceTestRun(collisionId, collision.revision, { ...engine, generation: {
    reverse: async () => collisionLocation,
    routable: async candidates => {
      const customer = await prisma.customer.create({ data: { clientId: DEFAULT_CLIENT_ID, id: collisionRecordId, firstName: "Collision", lastName: "Test",
        email: `${collisionRecordId}@example.invalid`, phone: "4025550111" } });
      const address = await prisma.address.create({ data: { id: collisionRecordId, customerId: customer.id, line1: collisionLocation.line1,
        city: collisionLocation.city, state: collisionLocation.state, postalCode: collisionLocation.postalCode, lat: collisionLocation.lat, lng: collisionLocation.lng } });
      const service = await prisma.serviceCatalog.findUniqueOrThrow({ where: { code: "FILTER_SWAP" } });
      const job = await prisma.job.create({ data: { id: collisionRecordId, customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: 30 } });
      const technician = await prisma.technician.findFirstOrThrow({ where: { active: true } });
      const start = new Date(`${required(collision.currentHorizon[0])}T14:00:00Z`);
      await prisma.appointment.create({ data: { id: collisionRecordId, jobId: job.id, technicianId: technician.id, serviceDate: new Date(`${required(collision.currentHorizon[0])}T00:00:00Z`),
        windowStart: start, windowEnd: new Date(start.getTime() + 7_200_000), plannedStart: start, plannedEnd: new Date(start.getTime() + 1_800_000), sequence: 0 } });
      return new Set(candidates.map(candidate => candidate.id));
    },
  } });
  assert.equal(collision.generation?.acceptedCount, 0, "A candidate claimed before finalization is discarded");
  assert.equal(collision.requests.length, 0, "Collision does not create a partial request set");
  await controlTestRun(collisionId, "stop");
  await prisma.appointment.delete({ where: { id: collisionRecordId } }); await prisma.job.delete({ where: { id: collisionRecordId } });
  await prisma.address.delete({ where: { id: collisionRecordId } }); await prisma.customer.delete({ where: { id: collisionRecordId } });
  const prior = await createRun(randomUUID(), { ...config, count: 1 });
  const priorStarted = await resumed(prior.id);
  const priorBooked = await advanceTestRun(prior.id, priorStarted.revision);
  assert.equal(required(priorBooked.requests[0]).status, "BOOKED");
  await controlTestRun(prior.id, "stop");
  const id = randomUUID();
  const created = await createRun(id, config);
  assert.equal(created.status, "PAUSED");
  assert.equal((await createRun(id, config)).id, id);
  const duplicateId = randomUUID();
  const duplicates = await Promise.all([createTestRun(duplicateId, config, CLIENT), createTestRun(duplicateId, config, CLIENT)]);
  assert.equal(required(duplicates[0]).id, required(duplicates[1]).id);
  assert.equal(await prisma.bookingTestRequest.count({ where: { runId: duplicateId } }), 0);
  await assert.rejects(createRun(id, { ...config, seed: 43 }));
  assert.equal(await prisma.job.count({ where: { id: { in: created.requests.map(r => r.id) } } }), 0);
  let run = await resumed(id);
  const revision = run.revision;
  run = await advanceTestRun(id, revision);
  assert.deepEqual(run.requests.map(r => r.status), ["BOOKED", "PENDING", "PENDING"]);
  const corrupt = await createRun(randomUUID(), { ...config, count: 1 });
  const corruptRequest = required(corrupt.requests[0]);
  const retainedSelection = offer.parse(required(run.requests[0]).selected);
  await prisma.bookingTestRequest.update({ where: { id: corruptRequest.id }, data: { selected: retainedSelection, attempts: { malformed: true } } });
  const corruptRunning = await resumed(corrupt.id);
  const halted = await advanceTestRun(corrupt.id, corruptRunning.revision, { ...engine, offers: async () => { throw new Error("Invalid journal must not request offers"); } });
  assert.equal(halted.status, "PAUSED"); assert.match(required(halted.error), /Malformed booking-test journal/);
  assert.deepEqual(required(halted.requests[0]).selected, retainedSelection);
  assert.deepEqual(required(halted.requests[0]).attempts, { malformed: true });
  assert.equal(await prisma.job.count({ where: { id: corruptRequest.id } }), 0);
  const first = await prisma.appointment.findUniqueOrThrow({ where: { jobId: required(run.requests[0]).id } });
  const selection = offer.parse(required(run.requests[0]).selected);
  assert.equal(first.windowStart.toISOString(), new Date(selection.windowStart).toISOString());
  assert.ok(await prisma.portalApiOfferSet.findFirst({ where: { clientId: CLIENT, jobId: first.jobId, selectedOfferId: selection.offerId, receiptId: { not: null } } }),
    "Appointment came through a confirmed public-API hold");
  assert.equal(await prisma.customer.count({ where: { id: first.jobId, clientId: CLIENT } }), 1, "The booking belongs to the run's client");
  assert.deepEqual((await advanceTestRun(id, revision)).requests.map(r => r.status), run.requests.map(r => r.status), "Duplicate advance does not move to the next job");
  await controlTestRun(id, "pause");
  run = await readTestRun(id);
  assert.equal(required((await advanceTestRun(id, run.revision)).requests[1]).status, "PENDING");
  run = await resumed(id);
  // A response lost after the booking commit must pause and later reconcile the same appointment.
  run = await advanceTestRun(id, run.revision, { ...engine, select: async (job, offer) => {
    await engine.select(job, offer); throw new Error("Injected lost confirmation response");
  } });
  assert.equal(run.status, "PAUSED"); assert.equal(required(run.requests[1]).status, "ERROR");
  const recoveredJob = required(run.requests[1]).id;
  assert.equal(await prisma.appointment.count({ where: { jobId: recoveredJob } }), 1);
  run = await resumed(id);
  run = await advanceTestRun(id, run.revision, { ...engine, offers: async () => { throw new Error("Must reconcile before offers"); } });
  assert.equal(required(run.requests[1]).status, "BOOKED");
  assert.equal(await prisma.appointment.count({ where: { jobId: recoveredJob } }), 1);
  // Exercise retry independently of whether this server enables bounded search.
  run = await advanceTestRun(id, run.revision, { ...engine, offers: async jobId => {
    // A deliberately oversized isolated fixture job (the API's longest visit) fits no shift.
    await prisma.job.update({ where: { id: jobId }, data: { durationMin: 720 } });
    const result = await engine.offers(jobId);
    assert.equal(result.offers.length, 0);
    const search = required(result.search);
    assert.ok(["SEARCH_INCOMPLETE", "NO_CANDIDATE_FOUND"].includes(search.outcome));
    assert.equal(search.prescribedSearchCompleted, search.outcome === "NO_CANDIDATE_FOUND");
    return { ...result, search: { outcome: "SEARCH_INCOMPLETE", prescribedSearchCompleted: false, elapsedMs: 1, retryable: true } };
  } });
  assert.equal(required(run.requests[2]).status, "ERROR"); assert.equal(run.status, "PAUSED");
  assert.equal(required(testAttempt.array().parse(required(run.requests[2]).attempts).at(-1)).search?.outcome, "SEARCH_INCOMPLETE");
  run = await resumed(id);
  // Inject a completed bounded-search result to exercise the eventual no-candidate contract.
  run = await advanceTestRun(id, run.revision, { ...engine, offers: async jobId => ({ jobId, offers: [],
    search: { outcome: "NO_CANDIDATE_FOUND", prescribedSearchCompleted: true, elapsedMs: 1, retryable: false } }) });
  assert.equal(required(run.requests[2]).status, "NO_OFFER"); assert.equal(run.status, "RUNNING");
  const appointments = () => prisma.appointment.findMany({ orderBy: { id: "asc" } });
  const before = await appointments();
  const nextPreview = await advanceTestRun(id, run.revision, { ...engine, preview: async (date, key) => {
    await engine.preview(date, key); throw new Error("Injected lost preview response");
  } });
  assert.equal(nextPreview.status, "PAUSED");
  run = await resumed(id);
  for (let i = 0; i < 12 && run.status === "RUNNING"; i++) run = await advanceTestRun(id, run.revision);
  assert.equal(run.status, "COMPLETED");
  assert.deepEqual(await appointments(), before, "Previews preserve every promise and assignment, including pre-existing appointments");
  // A retried preview replaces the lost one: one preview per day, each the client's daily proposal.
  assert.equal(new Set(run.previews.map(p => p.serviceDate)).size, run.previews.length, "Ambiguous preview retry keeps one preview per day");
  const proposals = new Map<string, ReturnType<typeof apiProposal.parse>>();
  for (const p of wire(run).previews) {
    const proposal = apiProposal.parse(p.result);
    assert.equal(p.optimizationId, proposal.proposalId); assert.ok(proposal.reason.length > 0);
    assert.equal((await prisma.portalApiDailyProposal.findUniqueOrThrow({ where: { id: proposal.proposalId } })).clientId, CLIENT);
    proposals.set(p.serviceDate, proposal);
  }
  const stopsOf = (date: Date) => required(proposals.get(date.toISOString().slice(0, 10)), "preview for the day").routes.flatMap(route => route.stops.map(stop => stop.appointmentId));
  const priorAppointment = await prisma.appointment.findUniqueOrThrow({ where: { jobId: required(priorBooked.requests[0]).id } });
  assert.ok(stopsOf(priorAppointment.serviceDate).includes(priorAppointment.id), "Entire-day preview contains appointments from earlier runs");
  assert.ok(stopsOf(first.serviceDate).includes(first.id), "The day's preview routes the run's own booking");

  const errors = await createRun(randomUUID(), { ...config, count: 2 });
  run = await resumed(errors.id);
  run = await advanceTestRun(run.id, run.revision, { ...engine, offers: async () => { throw new Error("Routing unavailable"); } });
  assert.equal(run.status, "PAUSED"); assert.equal(required(run.requests[0]).status, "ERROR");
  assert.equal(run.requests.filter(r => r.status === "NO_OFFER").length, 0);
  run = await resumed(errors.id);
  run = await advanceTestRun(run.id, run.revision, { ...engine, select: async () => { throw new BookingRefused(409, "Injected confirmation conflict"); } });
  assert.equal(run.status, "PAUSED"); assert.equal(required(run.requests[0]).selected, null, "A conflict discards the chosen offer");
  // The offers stay held until the next search ends them.
  run = await resumed(errors.id);
  // Hold an operation while a second caller attempts to advance or resume.
  let entered!: () => void; let release!: () => void;
  const inOffers = new Promise<void>(resolve => { entered = resolve; });
  const gate = new Promise<void>(resolve => { release = resolve; });
  const operation = advanceTestRun(run.id, run.revision, { ...engine, offers: async jobId => { entered(); await gate; return { jobId, offers: [], search: { outcome: "NO_CANDIDATE_FOUND", prescribedSearchCompleted: true, elapsedMs: 1, retryable: false } }; } });
  await inOffers;
  try {
    await assert.rejects(advanceTestRun(run.id, run.revision), /Another test operation/);
    await assert.rejects(resumed(run.id), /Another test operation/);
    const paused = await controlTestRun(run.id, "pause"); assert.equal(paused.status, "PAUSED");
    await assert.rejects(purgeTestRun(run.id), /Another test operation/);
  } finally { release(); }
  run = await operation;
  assert.equal(run.status, "PAUSED"); assert.equal(required(run.requests[1]).status, "PENDING");
  run = await resumed(run.id);
  let stopping!: () => void; let finish!: () => void;
  const inFlight = new Promise<void>(resolve => { stopping = resolve; });
  const finishGate = new Promise<void>(resolve => { finish = resolve; });
  const stoppedOperation = advanceTestRun(run.id, run.revision, { ...engine, offers: async jobId => { stopping(); await finishGate; return { jobId, offers: [], search: { outcome: "NO_CANDIDATE_FOUND", prescribedSearchCompleted: true, elapsedMs: 1, retryable: false } }; } });
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
  await controlTestRun(id, "pause");
  const manualCustomer = await prisma.customer.create({ data: { clientId: DEFAULT_CLIENT_ID, firstName: "Manual", lastName: "Survivor", email: `${randomUUID()}@example.invalid`, phone: "4025550199" } });
  const manualAddress = await prisma.address.create({ data: { customerId: manualCustomer.id, line1: "Manual survivor", city: "Omaha", state: "NE", postalCode: "68102", lat: 41.2524, lng: -95.9980 } });
  const service = await prisma.serviceCatalog.findFirstOrThrow({ where: { active: true } });
  const manualJob = await prisma.job.create({ data: { customerId: manualCustomer.id, addressId: manualAddress.id, serviceId: service.id, durationMin: 30, status: "SCHEDULED" } });
  // A manual appointment added after the technician's last visit of the day, which the purge must re-time, not drop.
  const last = await prisma.appointment.findFirstOrThrow({ where: { technicianId: first.technicianId, serviceDate: first.serviceDate, cancelledAt: null },
    orderBy: { sequence: "desc" } });
  const survivorStart = new Date(last.plannedEnd.getTime() + 1_800_000);
  const manualAppointment = await prisma.appointment.create({ data: { jobId: manualJob.id, technicianId: first.technicianId,
    serviceDate: first.serviceDate, windowStart: last.plannedEnd, windowEnd: new Date(last.plannedEnd.getTime() + 14_400_000),
    plannedStart: survivorStart, plannedEnd: new Date(survivorStart.getTime() + 1_800_000), sequence: last.sequence + 1 } });
  const proposalCount = await prisma.portalApiDailyProposal.count();
  const mainAppointmentCount = await prisma.appointment.count({ where: { jobId: { in: created.requests.map(request => request.id) } } });
  const purged = await purgeTestRun(id);
  assert.equal(purged.status, "PURGED"); assert.equal(purged.purgedCount, mainAppointmentCount); assert.ok(purged.purgedAt);
  assert.equal(await prisma.job.count({ where: { id: { in: created.requests.map(request => request.id) } } }), 0);
  assert.equal(await prisma.portalApiOfferSet.count({ where: { jobId: { in: created.requests.map(request => request.id) } } }), 0);
  assert.equal(await prisma.customer.count({ where: { id: { in: created.requests.map(request => request.id) } } }), 0);
  assert.ok(await prisma.appointment.findUnique({ where: { id: priorAppointment.id } }), "Another run survives purge");
  assert.ok(await prisma.appointment.findUnique({ where: { id: manualAppointment.id } }), "Manual appointment survives purge");
  assert.equal(await prisma.portalApiDailyProposal.count(), proposalCount, "Proposal history survives purge");
  assert.ok(stopsOf(first.serviceDate).includes(first.id), "A saved proposal keeps the purged appointment it routed");
  assert.ok(wire(await readTestRun(id)).previews.every(p => p.result !== null && "proposalId" in p.result), "Saved proposals still read after purge");
  const survivors = await prisma.appointment.findMany({ where: { technicianId: first.technicianId, serviceDate: first.serviceDate, cancelledAt: null }, orderBy: [{ plannedStart: "asc" }, { id: "asc" }] });
  assert.deepEqual(survivors.map(item => item.sequence), survivors.map((_, index) => index), "Surviving appointments are resequenced");
  for (const survivor of survivors)
    assert.ok(survivor.plannedStart >= survivor.windowStart && survivor.plannedStart <= survivor.windowEnd, "Re-timed survivors arrive within their windows");
  for (let index = 1; index < survivors.length; index++)
    assert.ok(required(survivors[index - 1]).plannedEnd <= required(survivors[index]).plannedStart, "Re-timed survivors do not overlap");
  const purgedAgain = await purgeTestRun(id);
  assert.equal(purgedAgain.purgedAt?.toISOString(), purged.purgedAt?.toISOString()); assert.equal(purgedAgain.purgedCount, purged.purgedCount);
  assert.deepEqual(await configuration(), configurationBefore, "Runs never rewrite Omaha configuration");
  console.log("Sequential runner through the public API: ordering, policies, idempotency, pause/stop, concurrency, routing/conflict recovery, preview immutability, and run-scoped purge passed.");
}
main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
