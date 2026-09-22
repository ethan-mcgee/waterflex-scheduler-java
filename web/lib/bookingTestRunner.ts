import { z } from "zod";
import { randomBytes } from "node:crypto";
import { testInput, testConfig, offer, testAttempt, errorMessage, optimization, date } from "./contracts";
import { Prisma, type BookingTestGeneration } from "@prisma/client";
import { prisma } from "./prisma";
import { bookingHorizon, chooseTestOffer, generateTestInputPlans, validateTestConfig, validateTestConfigInput, type TestConfig, type TestConfigInput } from "./bookingTestCore";
import { OMAHA_METRO_ID, OMAHA_TIMEZONE } from "./fakeDataCore";
import { EngineError, previewOptimization, requestSlots, selectOffer, checkTestAddressRoutability } from "./engineClient";
import { addressKey, coordinateKey, createAddressCandidateBatch, evaluateAddressCandidateBatch, initialAddressRandomState,
  reverseTestAddress, type AddressGenerationDependencies, type PendingAddressCandidate, type TestServiceArea } from "./bookingTestAddresses";

function json(value: unknown): Prisma.InputJsonValue {
  const raw: unknown = JSON.parse(JSON.stringify(value));
  const result = z.json().parse(raw);
  if (result === null) throw new Error("Expected a non-null JSON journal value");
  return result;
}
const include = { requests: { orderBy: { ordinal: "asc" as const } }, previews: { orderBy: { serviceDate: "asc" as const } }, generation: true };
export class TestRunError extends Error {
  constructor(message: string, public status = 409) { super(message); }
}
export const testEngine = {
  offers: (jobId: string) => requestSlots(jobId, false, 45_000),
  select: (jobId: string, offerId: string) => selectOffer(jobId, offerId, 45_000),
  preview: (date: string, key: string) => previewOptimization({ metro_id: OMAHA_METRO_ID, date, request_key: key }, 45_000),
};
const generationDependencies: AddressGenerationDependencies = {
  reverse: reverseTestAddress,
  routable: (candidates, signal) => checkTestAddressRoutability(candidates, 30_000, signal),
};
function resolvedSeed(): number { return randomBytes(4).readUInt32BE(0); }
function sameRequestedConfig(saved: TestConfig, requested: TestConfigInput): boolean {
  return saved.count === requested.count && saved.policy === requested.policy && JSON.stringify(saved.weights) === JSON.stringify(requested.weights)
    && saved.radiusMi === requested.radiusMi && (requested.seed == null || saved.seed === requested.seed);
}
export async function createTestRun(id: string, value: unknown) {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) throw new TestRunError("A UUID run identity is required.", 400);
  let requested: TestConfigInput;
  try { requested = validateTestConfigInput(value); } catch (error) { throw new TestRunError(errorMessage(error), 400); }
  const existing = await prisma.bookingTestRun.findUnique({ where: { id }, include });
  if (existing) {
    if (!sameRequestedConfig(validateTestConfig(existing.config), requested)) throw new TestRunError("Run identity already used with different settings.");
    return readTestRun(id);
  }
  const config = validateTestConfig({ ...requested, seed: requested.seed ?? resolvedSeed() });
  const metro = await prisma.metro.findUnique({ where: { id: OMAHA_METRO_ID }, include: { depots: { select: { lat: true, lng: true } } } });
  if (metro?.timezone !== OMAHA_TIMEZONE) throw new TestRunError("Configure the existing Omaha metro first.", 422);
  if (!Number.isFinite(metro.serviceRadiusMi) || metro.serviceRadiusMi <= 0 || !metro.depots.length || !metro.stateCode) throw new TestRunError("Configure the Omaha service area and state code first.", 422);
  if (config.radiusMi > metro.serviceRadiusMi) throw new TestRunError("Test radius cannot exceed the configured Omaha service radius.", 422);
  const saved = await prisma.bookingTestRun.upsert({ where: { id }, update: {}, create: {
    id, config: json(config), horizon: bookingHorizon(),
    generation: { create: { acceptedInputs: [], randomState: BigInt(initialAddressRandomState(config)) } },
  }, include }).catch(async error => {
    // Prisma's nested upsert may lose a concurrent create race; read its winner.
    if (!(error instanceof Prisma.PrismaClientKnownRequestError) || error.code !== "P2002") throw error;
    return prisma.bookingTestRun.findUniqueOrThrow({ where: { id }, include });
  });
  if (!sameRequestedConfig(validateTestConfig(saved.config), requested)) throw new TestRunError("Run identity already used with different settings.");
  return readTestRun(id);
}
export async function readTestRun(id: string) {
  const run = await prisma.bookingTestRun.findUnique({ where: { id }, include });
  if (!run) throw new TestRunError("Test run not found.", 404);
  // Report current applied status separately from the saved proposal metrics.
  const applied = await prisma.optimizationRun.findMany({ where: { id: { in: run.previews.flatMap(p => p.optimizationId ? [p.optimizationId] : []) } }, select: { id: true, status: true, appliedAt: true } });
  const { generation, ...visible } = run;
  return { ...visible, config: validateTestConfig(run.config), generation: publicGeneration(generation, run.config), applied, currentHorizon: bookingHorizon() };
}
export async function listTestRuns() {
  const runs = await prisma.bookingTestRun.findMany({ orderBy: { createdAt: "desc" }, select: { id: true, status: true, config: true, createdAt: true,
    purgedAt: true, purgedCount: true, generation: { select: { acceptedInputs: true, candidatesTried: true, batches: true, elapsedMs: true,
      completedAt: true, consecutiveNoProgressBatches: true } } } });
  return runs.map(run => { const { generation, ...visible } = run;
    return { ...visible, config: validateTestConfig(run.config), generation: publicGeneration(generation, run.config) };
  });
}

function publicGeneration(generation: { acceptedInputs: Prisma.JsonValue; candidatesTried: bigint; batches: bigint; elapsedMs: bigint;
  completedAt: Date | null; consecutiveNoProgressBatches: bigint } | null, configValue: Prisma.JsonValue) {
  if (!generation) return null;
  const config = testConfig.safeParse(configValue);
  const counters = [generation.candidatesTried, generation.batches, generation.elapsedMs, generation.consecutiveNoProgressBatches].map(Number);
  if (!config.success || !Array.isArray(generation.acceptedInputs) || counters.some(value => !Number.isSafeInteger(value) || value < 0)) return null;
  return { acceptedCount: Array.isArray(generation.acceptedInputs) ? generation.acceptedInputs.length : 0,
    targetCount: config.data.count, candidatesTried: counters[0], batches: counters[1],
    elapsedMs: counters[2], completedAt: generation.completedAt, consecutiveNoProgressBatches: counters[3] };
}

// The transaction owns only the advisory lock. Journal writes commit independently,
// surviving process loss, while PostgreSQL releases the lock if its connection dies.
async function exclusively<T>(operation: () => Promise<T>): Promise<T> {
  return prisma.$transaction(async tx => {
    const [lock] = await tx.$queryRaw<Array<{ locked: boolean }>>`SELECT pg_try_advisory_xact_lock(731902, 1) AS locked`;
    if (!lock?.locked) throw new TestRunError("Another test operation is finishing. Reload and resume when it completes.");
    return operation();
  }, { timeout: 180_000, maxWait: 5_000 });
}
export async function controlTestRun(id: string, action: "resume" | "pause" | "stop") {
  if (action === "resume") return exclusively(async () => {
    await prisma.bookingTestRun.updateMany({ where: { id, status: { in: ["PAUSED", "RUNNING"] } }, data: { status: "RUNNING", error: null, revision: { increment: 1 } } });
    return readTestRun(id);
  });
  await prisma.bookingTestRun.updateMany({ where: { id, status: { in: ["PAUSED", "RUNNING"] } }, data: { status: action === "stop" ? "STOPPED" : "PAUSED", revision: { increment: 1 } } });
  return readTestRun(id);
}

export async function purgeTestRun(id: string) {
  return exclusively(async () => {
    const run = await prisma.bookingTestRun.findUnique({ where: { id } });
    if (!run) throw new TestRunError("Test run not found.", 404);
    if (run.status === "PURGED") return readTestRun(id);
    if (!["PAUSED", "STOPPED", "COMPLETED"].includes(run.status)) throw new TestRunError("Pause or stop the run before deleting generated appointments.");
    const prefix = `booking-test:${id}:`;
    const candidates = await prisma.job.findMany({
      where: { id: { startsWith: prefix } },
      select: { id: true, customerId: true, addressId: true, appointment: { select: { id: true, technicianId: true, serviceDate: true } } },
    });
    const jobs = candidates.filter(job => /^\d+$/.test(job.id.slice(prefix.length)));
    const jobIds = jobs.map(job => job.id);
    const appointmentIds = jobs.flatMap(job => job.appointment ? [job.appointment.id] : []);
    const customerIds = [...new Set(jobs.map(job => job.customerId))];
    const addressIds = [...new Set(jobs.map(job => job.addressId))];
    const affected = [...new Map(jobs.flatMap(job => job.appointment ? [[`${job.appointment.technicianId}|${job.appointment.serviceDate.toISOString()}`, {
      technicianId: job.appointment.technicianId, serviceDate: job.appointment.serviceDate,
    }] as const] : [])).values()].sort((left, right) => left.technicianId.localeCompare(right.technicianId) || left.serviceDate.getTime() - right.serviceDate.getTime());
    await prisma.$transaction(async tx => {
      for (const day of affected) {
        await tx.scheduleDay.upsert({ where: { technicianId_serviceDate: day }, update: {}, create: { ...day } });
        await tx.$queryRaw`SELECT version FROM schedule_day WHERE "technicianId"=${day.technicianId} AND "serviceDate"=${day.serviceDate} FOR UPDATE`;
      }
      if (jobIds.length) {
        await tx.outboundEvent.deleteMany({ where: { aggregateId: { in: [...jobIds, ...appointmentIds, ...customerIds] } } });
        await tx.slotHold.deleteMany({ where: { jobId: { in: jobIds } } });
        await tx.bookingOffer.deleteMany({ where: { jobId: { in: jobIds } } });
        await tx.bookingOfferSet.deleteMany({ where: { jobId: { in: jobIds } } });
        await tx.bookingOptimization.deleteMany({ where: { jobId: { in: jobIds } } });
        await tx.appointment.deleteMany({ where: { jobId: { in: jobIds } } });
        await tx.job.deleteMany({ where: { id: { in: jobIds } } });
        await tx.address.deleteMany({ where: { id: { in: addressIds } } });
        await tx.customer.deleteMany({ where: { id: { in: customerIds } } });
      }
      for (const day of affected) {
        const survivors = await tx.appointment.findMany({ where: { ...day, cancelledAt: null }, orderBy: [{ plannedStart: "asc" }, { id: "asc" }], select: { id: true } });
        for (const [sequence, survivor] of survivors.entries()) await tx.appointment.update({ where: { id: survivor.id }, data: { sequence } });
        await tx.scheduleDay.update({ where: { technicianId_serviceDate: day }, data: { version: { increment: 1 } } });
      }
      await tx.bookingTestRun.update({ where: { id }, data: { status: "PURGED", purgedAt: new Date(), purgedCount: appointmentIds.length, error: null, revision: { increment: 1 } } });
    }, { timeout: 180_000, maxWait: 5_000 });
    return readTestRun(id);
  });
}

async function ensureJob(request: Awaited<ReturnType<typeof readTestRun>>["requests"][number]) {
  const input = testInput.parse(request.input);
  return prisma.$transaction(async tx => {
    const existing = await tx.job.findUnique({ where: { id: request.id } });
    if (existing) return existing;
    const service = await tx.serviceCatalog.findUnique({ where: { code: input.serviceCode } });
    if (!service?.active) throw new Error(`Service ${input.serviceCode} is not active.`);
    const customer = await tx.customer.create({ data: { id: request.id, externalId: request.id,
      firstName: "Synthetic", lastName: `Test ${request.ordinal + 1}`, email: `${request.runId}.${request.ordinal}@test.waterflex.invalid`, phone: "402-555-0100" } });
    const { line1, city, state, postalCode, lat, lng } = input.location;
    await tx.address.create({ data: { id: request.id, customerId: customer.id, line1, city, state, postalCode, lat, lng, geocodePrecision: "OSM_HOUSE", geocodedAt: new Date() } });
    return tx.job.create({ data: { id: request.id, customerId: customer.id, addressId: request.id, serviceId: service.id,
      durationMin: Math.round(service.estDurationMin * (1 + service.bufferPct)), bookingRequestId: request.id, externalId: request.id } });
  });
}

async function reconcile(jobId: string) {
  // Wait behind any scheduler transaction whose HTTP response was lost before inspecting its commit.
  return prisma.$transaction(async tx => {
    await tx.$executeRawUnsafe("SET LOCAL statement_timeout = '50s'");
    await tx.$queryRaw`SELECT id FROM job WHERE id = ${jobId} FOR UPDATE`;
    return tx.appointment.findUnique({ where: { jobId } });
  }, { timeout: 55_000 });
}

const pendingCandidate = z.object({ id: z.string().min(1), ordinal: z.int().nonnegative(), serviceCode: z.string().min(1),
  selectionUnit: z.number().finite().min(0).lt(1), lat: z.number().finite().min(-90).max(90), lng: z.number().finite().min(-180).max(180) });

async function activeAddressCollisions() {
  const active = await prisma.address.findMany({ where: { jobs: { some: { appointment: { is: { cancelledAt: null } } } } },
    select: { line1: true, city: true, state: true, postalCode: true, lat: true, lng: true } });
  return { addresses: new Set(active.map(addressKey)), coordinates: new Set(active.flatMap(address =>
    address.lat == null || address.lng == null ? [] : [coordinateKey({ lat: address.lat, lng: address.lng })])) };
}

function parseGeneration(generation: BookingTestGeneration, config: TestConfig) {
  const accepted = z.array(testInput).max(config.count).parse(generation.acceptedInputs);
  const plans = generateTestInputPlans(config);
  if (new Set(accepted.map(input => input.ordinal)).size !== accepted.length || accepted.some(input => input.ordinal >= config.count))
    throw new Error("Invalid accepted address ordinals.");
  const pending = generation.pendingCandidates == null ? null : z.array(pendingCandidate).min(1).max(12).parse(generation.pendingCandidates);
  const validPlan = (value: { ordinal: number; serviceCode: string; selectionUnit: number }) => {
    const plan = plans[value.ordinal];
    return plan?.serviceCode === value.serviceCode && Math.abs(plan.selectionUnit - value.selectionUnit) <= Number.EPSILON;
  };
  if (accepted.some(input => !validPlan(input))) throw new Error("Invalid accepted address plan.");
  if (pending?.some(candidate => !validPlan(candidate) || accepted.some(input => input.ordinal === candidate.ordinal)))
    throw new Error("Invalid pending address plan.");
  if (new Set(pending?.map(candidate => candidate.ordinal)).size !== (pending?.length ?? 0)) throw new Error("Duplicate pending address ordinal.");
  if (new Set(accepted.map(input => addressKey(input.location))).size !== accepted.length) throw new Error("Duplicate accepted address.");
  if (new Set(accepted.map(input => coordinateKey(input.location))).size !== accepted.length) throw new Error("Duplicate accepted coordinate.");
  const state = Number(generation.randomState);
  const counters = [generation.candidatesTried, generation.batches, generation.elapsedMs, generation.consecutiveNoProgressBatches].map(Number);
  if (!Number.isSafeInteger(state) || state < 0 || state > 0xffffffff || !Number.isInteger(generation.roundRobinCursor) ||
      generation.roundRobinCursor < 0 || generation.roundRobinCursor >= config.count ||
      counters.some(value => !Number.isSafeInteger(value) || value < 0))
    throw new Error("Invalid address generation counters.");
  if (generation.completedAt && (accepted.length !== config.count || pending)) throw new Error("Invalid completed address generation journal.");
  return { accepted, pending, state, cursor: generation.roundRobinCursor };
}

type AdvanceDependencies = typeof testEngine & { generation?: AddressGenerationDependencies };

async function advanceGeneration(id: string, config: TestConfig, generation: ReturnType<typeof parseGeneration>, dependencies: AddressGenerationDependencies) {
  const metro = await prisma.metro.findUnique({ where: { id: OMAHA_METRO_ID }, include: { depots: { select: { lat: true, lng: true } } } });
  if (metro?.timezone !== OMAHA_TIMEZONE || !metro.stateCode) throw new Error("Configure the existing Omaha metro first.");
  const area: TestServiceArea = { radiusMi: config.radiusMi, stateCode: metro.stateCode, depots: metro.depots };
  let pending: PendingAddressCandidate[];
  if (generation.pending) pending = generation.pending;
  else if (generation.accepted.length < config.count) {
    const batch = createAddressCandidateBatch(config, area, new Set(generation.accepted.map(input => input.ordinal)), generation.state, generation.cursor);
    pending = batch.candidates;
    await prisma.bookingTestGeneration.update({ where: { runId: id }, data: { randomState: BigInt(batch.randomState),
      roundRobinCursor: batch.roundRobinCursor, pendingCandidates: json(pending) } });
  } else pending = [];

  const started = Date.now();
  let found: z.infer<typeof testInput>[] = [];
  try {
    if (pending.length) {
      const active = await activeAddressCollisions();
      for (const input of generation.accepted) { active.addresses.add(addressKey(input.location)); active.coordinates.add(coordinateKey(input.location)); }
      found = await evaluateAddressCandidateBatch(pending, area, active, dependencies);
    }
  } catch (error) {
    const elapsedMs = Date.now() - started;
    await prisma.bookingTestGeneration.update({ where: { runId: id }, data: { elapsedMs: { increment: BigInt(elapsedMs) } } });
    await prisma.bookingTestRun.updateMany({ where: { id, status: "RUNNING" }, data: { status: "PAUSED", error: errorMessage(error) } });
    return;
  }
  const elapsedMs = Date.now() - started;
  await prisma.$transaction(async tx => {
    const currentRun = await tx.bookingTestRun.findUniqueOrThrow({ where: { id }, include: { generation: true } });
    if (!currentRun.generation) throw new Error("Address generation journal disappeared.");
    const current = parseGeneration(currentRun.generation, config);
    const accepted = new Map(current.accepted.map(input => [input.ordinal, input]));
    for (const input of found) if (!accepted.has(input.ordinal)) accepted.set(input.ordinal, input);
    let values = [...accepted.values()].sort((left, right) => left.ordinal - right.ordinal);
    if (values.length === config.count) {
      const active = await tx.address.findMany({ where: { jobs: { some: { appointment: { is: { cancelledAt: null } } } } },
        select: { line1: true, city: true, state: true, postalCode: true, lat: true, lng: true } });
      const addresses = new Set(active.map(addressKey));
      const coordinates = new Set(active.flatMap(address => address.lat == null || address.lng == null ? [] : [coordinateKey({ lat: address.lat, lng: address.lng })]));
      values = values.filter(input => !addresses.has(addressKey(input.location)) && !coordinates.has(coordinateKey(input.location)));
    }
    const processedBatch = pending.length > 0;
    const newlyAccepted = Math.max(0, values.length - current.accepted.length);
    const complete = values.length === config.count && currentRun.status === "RUNNING";
    if (complete) await tx.bookingTestRequest.createMany({ data: values.map(input => ({ id: `booking-test:${id}:${input.ordinal}`,
      runId: id, ordinal: input.ordinal, input: json(input) })) });
    await tx.bookingTestGeneration.update({ where: { runId: id }, data: { acceptedInputs: json(values), pendingCandidates: Prisma.DbNull,
      candidatesTried: { increment: BigInt(processedBatch ? pending.length : 0) }, batches: { increment: BigInt(processedBatch ? 1 : 0) }, elapsedMs: { increment: BigInt(elapsedMs) },
      consecutiveNoProgressBatches: processedBatch ? (newlyAccepted ? 0n : currentRun.generation.consecutiveNoProgressBatches + 1n) : currentRun.generation.consecutiveNoProgressBatches,
      completedAt: complete ? new Date() : null } });
  });
}

export async function advanceTestRun(id: string, revision: number, engine: AdvanceDependencies = testEngine) {
  return exclusively(async () => {
    const run = await readTestRun(id);
    if (run.status !== "RUNNING" || run.revision !== revision) return run;
    const raw = await prisma.bookingTestRun.findUnique({ where: { id }, include });
    if (!raw) throw new TestRunError("Test run not found.", 404);
    let generation: ReturnType<typeof parseGeneration> | null = null;
    try {
      const config = validateTestConfig(run.config);
      if (raw.generation) generation = parseGeneration(raw.generation, config);
      if (raw.generation && ((raw.generation.completedAt == null && raw.requests.length !== 0) ||
          (raw.generation.completedAt != null && raw.requests.length !== config.count))) throw new Error("Invalid generated request journal.");
      z.array(date).parse(run.horizon);
      for (const preview of run.previews) optimization.nullable().parse(preview.result);
      for (const request of run.requests) {
        testInput.parse(request.input); z.array(offer).parse(request.offers);
        offer.nullable().parse(request.selected); z.array(testAttempt).parse(request.attempts);
      }
    } catch {
      await prisma.bookingTestRun.updateMany({ where: { id, revision, status: "RUNNING" },
        data: { status: "PAUSED", error: "Malformed booking-test journal. Raw journal and selected offer retained for reconciliation.", revision: { increment: 1 } } });
      return readTestRun(id);
    }
    const claim = await prisma.bookingTestRun.updateMany({ where: { id, revision, status: "RUNNING" }, data: { revision: { increment: 1 }, error: null } });
    if (!claim.count) return readTestRun(id);
    if (raw.generation && !raw.generation.completedAt) {
      if (!generation) throw new Error("Validated generation journal is missing.");
      await advanceGeneration(id, validateTestConfig(run.config), generation, engine.generation ?? generationDependencies);
      return readTestRun(id);
    }
    const request = run.requests.find(r => !["BOOKED", "NO_OFFER"].includes(r.status));
    if (request) {
      const started = Date.now();
      let offers = z.array(offer).parse(request.offers);
      let selected = offer.nullable().parse(request.selected);
      let errorMessage: string | null = null;
      let outcome = "ERROR";
      try {
        await prisma.bookingTestRequest.update({ where: { id: request.id }, data: { status: "PROCESSING", startedAt: request.startedAt ?? new Date(), error: null } });
        await ensureJob(request);
        let appointment = await reconcile(request.id);
        if (appointment?.cancelledAt) throw new Error("The test appointment was cancelled. Review the schedule before continuing.");
        if (!appointment) {
          if (!selected) {
            offers = (await engine.offers(request.id)).offers;
            const input = testInput.parse(request.input);
            selected = chooseTestOffer(offers, validateTestConfig(run.config).policy, input.selectionUnit);
            await prisma.bookingTestRequest.update({ where: { id: request.id }, data: { offers: json(offers), selected: selected ? json(selected) : Prisma.DbNull } });
          }
          if (selected) {
            await engine.select(request.id, selected.offerId);
            appointment = await prisma.appointment.findUnique({ where: { jobId: request.id } });
            if (!appointment || appointment.cancelledAt) throw new Error("Selection response has no active persisted appointment. Resume to reconcile.");
          }
        }
        outcome = appointment ? "BOOKED" : "NO_OFFER";
        await prisma.bookingTestRequest.update({ where: { id: request.id }, data: { status: outcome, appointmentId: appointment?.id,
          serviceDate: appointment?.serviceDate.toISOString().slice(0, 10), completedAt: new Date() } });
      } catch (error) {
        errorMessage = error instanceof Error ? error.message : "Unknown booking failure";
        // Only an explicit conflict discards a choice. Ambiguous failures retain it for an idempotent retry.
        await prisma.bookingTestRequest.update({ where: { id: request.id }, data: { status: "ERROR", error: errorMessage,
          ...(error instanceof EngineError && error.status === 409 ? { selected: Prisma.DbNull } : {}) } });
        await prisma.bookingTestRun.updateMany({ where: { id, status: "RUNNING" }, data: { status: "PAUSED", error: errorMessage } });
      } finally {
        const elapsedMs = Date.now() - started;
        await prisma.bookingTestRequest.update({ where: { id: request.id }, data: { elapsedMs: { increment: elapsedMs },
          attempts: json([...z.array(testAttempt).parse(request.attempts), { at: new Date(started).toISOString(), horizon: bookingHorizon(new Date(started)), elapsedMs, offers, selected, outcome, error: errorMessage }]) } });
      }
    } else {
      const dates = [...new Set(run.requests.flatMap(r => r.serviceDate ? [r.serviceDate] : []))].sort();
      const date = dates.find(date => !run.previews.some(p => p.serviceDate === date && p.optimizationId));
      if (!date) await prisma.bookingTestRun.updateMany({ where: { id, status: "RUNNING" }, data: { status: "COMPLETED" } });
      else {
        const previewId = `${id}:${date}`;
        await prisma.bookingTestPreview.upsert({ where: { id: previewId }, create: { id: previewId, runId: id, serviceDate: date }, update: {} });
        try {
          const result = await engine.preview(date, previewId);
          await prisma.bookingTestPreview.update({ where: { id: previewId }, data: { optimizationId: result.run_id, result: json(result), error: null } });
        } catch (error) {
          const message = error instanceof Error ? error.message : "Preview failed";
          await prisma.bookingTestPreview.update({ where: { id: previewId }, data: { error: message } });
          await prisma.bookingTestRun.updateMany({ where: { id, status: "RUNNING" }, data: { status: "PAUSED", error: message } });
        }
      }
    }
    return readTestRun(id);
  });
}
