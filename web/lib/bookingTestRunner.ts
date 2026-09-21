import { Prisma } from "@prisma/client";
import { prisma } from "./prisma";
import { bookingHorizon, chooseTestOffer, generateTestInputs, validateTestConfig, type TestConfig } from "./bookingTestCore";
import { OMAHA_METRO_ID, OMAHA_TIMEZONE } from "./fakeDataCore";
import { EngineError, previewOptimization, requestSlots, selectOffer, type SlotOffer } from "./engineClient";

const json = (value: unknown) => JSON.parse(JSON.stringify(value)) as Prisma.InputJsonValue;
const include = { requests: { orderBy: { ordinal: "asc" as const } }, previews: { orderBy: { serviceDate: "asc" as const } } };
export class TestRunError extends Error {
  constructor(message: string, public status = 409) { super(message); }
}
export const testEngine = {
  offers: (jobId: string) => requestSlots(jobId, false, 45_000),
  select: (jobId: string, offerId: string) => selectOffer(jobId, offerId, 45_000),
  preview: (date: string, key: string) => previewOptimization({ metro_id: OMAHA_METRO_ID, date, request_key: key }, 45_000),
};
export async function createTestRun(id: string, value: unknown) {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) throw new TestRunError("A UUID run identity is required.", 400);
  let config: TestConfig;
  try { config = validateTestConfig(value); } catch (error) { throw new TestRunError((error as Error).message, 400); }
  const existing = await prisma.bookingTestRun.findUnique({ where: { id }, include });
  if (existing) {
    if (JSON.stringify(validateTestConfig(existing.config)) !== JSON.stringify(config)) throw new TestRunError("Run identity already used with different settings.");
    return existing;
  }
  const metro = await prisma.metro.findUnique({ where: { id: OMAHA_METRO_ID } });
  if (metro?.timezone !== OMAHA_TIMEZONE) throw new TestRunError("Configure the existing Omaha metro first.", 422);
  // No seeding, configuration writes, cleanup, or appointments here.
  const saved = await prisma.bookingTestRun.upsert({ where: { id }, update: {}, create: {
    id, config: json(config), horizon: bookingHorizon(),
    requests: { create: generateTestInputs(config).map(input => ({ id: `booking-test:${id}:${input.ordinal}`, ordinal: input.ordinal, input: json(input) })) },
  }, include }).catch(async error => {
    // Prisma's nested upsert may lose a concurrent create race; read its winner.
    if (!(error instanceof Prisma.PrismaClientKnownRequestError) || error.code !== "P2002") throw error;
    return prisma.bookingTestRun.findUniqueOrThrow({ where: { id }, include });
  });
  if (JSON.stringify(validateTestConfig(saved.config)) !== JSON.stringify(config)) throw new TestRunError("Run identity already used with different settings.");
  return saved;
}
export async function readTestRun(id: string) {
  const run = await prisma.bookingTestRun.findUnique({ where: { id }, include });
  if (!run) throw new TestRunError("Test run not found.", 404);
  // Report current applied status separately from the saved proposal metrics.
  const applied = await prisma.optimizationRun.findMany({ where: { id: { in: run.previews.flatMap(p => p.optimizationId ? [p.optimizationId] : []) } }, select: { id: true, status: true, appliedAt: true } });
  return { ...run, applied, currentHorizon: bookingHorizon() };
}
export async function listTestRuns() {
  return prisma.bookingTestRun.findMany({ orderBy: { createdAt: "desc" }, select: { id: true, status: true, config: true, createdAt: true } });
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

async function ensureJob(request: Awaited<ReturnType<typeof readTestRun>>["requests"][number]) {
  const input = request.input as unknown as ReturnType<typeof generateTestInputs>[number];
  return prisma.$transaction(async tx => {
    const existing = await tx.job.findUnique({ where: { id: request.id } });
    if (existing) return existing;
    const service = await tx.serviceCatalog.findUnique({ where: { code: input.serviceCode } });
    if (!service?.active) throw new Error(`Service ${input.serviceCode} is not active.`);
    const customer = await tx.customer.create({ data: { id: request.id, externalId: request.id,
      firstName: "Synthetic", lastName: `Test ${request.ordinal + 1}`, email: `${request.runId}.${request.ordinal}@test.waterflex.invalid`, phone: "402-555-0100" } });
    const { line1, city, state, postalCode, lat, lng } = input.location;
    await tx.address.create({ data: { id: request.id, customerId: customer.id, line1, city, state, postalCode, lat, lng, geocodePrecision: "SYNTHETIC", geocodedAt: new Date() } });
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

export async function advanceTestRun(id: string, revision: number, engine = testEngine) {
  return exclusively(async () => {
    const run = await readTestRun(id);
    if (run.status !== "RUNNING" || run.revision !== revision) return run;
    const claim = await prisma.bookingTestRun.updateMany({ where: { id, revision, status: "RUNNING" }, data: { revision: { increment: 1 }, error: null } });
    if (!claim.count) return readTestRun(id);
    const request = run.requests.find(r => !["BOOKED", "NO_OFFER"].includes(r.status));
    if (request) {
      const started = Date.now();
      let offers = request.offers as unknown as SlotOffer[];
      let selected = request.selected as unknown as SlotOffer | null;
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
            const input = request.input as unknown as ReturnType<typeof generateTestInputs>[number];
            selected = chooseTestOffer(offers, (run.config as unknown as TestConfig).policy, input.selectionUnit);
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
          attempts: json([...(request.attempts as Prisma.JsonArray), { at: new Date(started).toISOString(), horizon: bookingHorizon(new Date(started)), elapsedMs, offers, selected, outcome, error: errorMessage }]) } });
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
