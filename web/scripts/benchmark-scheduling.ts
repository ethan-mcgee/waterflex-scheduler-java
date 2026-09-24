import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { open, readFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import { cpus, totalmem } from "node:os";
import { PrismaClient } from "@prisma/client";
import { z } from "zod";
import { required, errorMessage, appointmentSearch } from "../lib/contracts";
import { requestSlots, selectOffer, releaseOffers } from "../lib/engineClient";
import { bookingHorizon, chooseTestOffer } from "../lib/bookingTestCore";
import { initialAvailability } from "../lib/technicianAvailability";
import { localMidnightUtc } from "../lib/date";
import { technicianColor } from "../lib/technicianColor";
import { createSeededRandom, OMAHA_FAKE_LOCATIONS } from "../lib/fakeDataCore";
import { legacyBenchmarkServer, legacyOffers, LegacyRequestUncertain } from "./benchmarkLegacy";
import { browserBenchmark, BrowserSearchError } from "./benchmarkBrowser";
import { benchmarkDiagnostics } from "./benchmarkDiagnostics";

async function main() {
const database = new URL(required(process.env.DATABASE_URL));
assert.equal(database.pathname, "/waterflex_test");
assert.ok(required(database.searchParams.get("schema")).startsWith("benchmark_"), "Use a dedicated benchmark_ schema");
const revision = required(process.env.BENCHMARK_REVISION, "Exact server revision");
const variant = required(process.env.BENCHMARK_VARIANT, "Named implementation/configuration stage");
const harnessRevision = execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8" }).trim();
const sourcePaths = execFileSync("git", ["ls-files", "-z", "--", "scripts/benchmark-scheduling.ts", "scripts/benchmarkLegacy.ts", "scripts/benchmarkBrowser.ts", "scripts/benchmarkDiagnostics.ts", "lib", "prisma/schema.prisma"], { encoding: "utf8" }).split("\0").filter(Boolean).sort();
execFileSync("git", ["diff", "--exit-code", "HEAD", "--", ...sourcePaths]);
const harnessSources = await Promise.all(sourcePaths.map(async path => ({ path, sha256: createHash("sha256").update(await readFile(path)).digest("hex") })));
const artifactSha256 = process.env.BENCHMARK_ARTIFACT_SHA256 == null ? null : z.string().regex(/^[a-f0-9]{64}$/i).parse(process.env.BENCHMARK_ARTIFACT_SHA256);
const ablationManifest = process.env.BENCHMARK_ABLATION_MANIFEST == null ? null : z.object({
  revision: z.string().regex(/^[a-f0-9]{40}$/), stage: z.enum(["policy-insertion", "snapshot-insertion", "bounded-early"]),
  patchSha256: z.string().regex(/^[a-f0-9]{64}$/), guardSha256: z.string().regex(/^[a-f0-9]{64}$/),
  generatorSha256: z.string().regex(/^[a-f0-9]{64}$/), timingTestSha256: z.string().regex(/^[a-f0-9]{64}$/),
  boundedSearch: z.boolean(), flexibleDeparture: z.literal(false), fullMatrixAndFullEvaluation: z.boolean(),
  retainedSafetyInfrastructure: z.array(z.string().min(1)).nonempty(), limitation: z.string().min(1),
}).parse(JSON.parse(await readFile(process.env.BENCHMARK_ABLATION_MANIFEST, "utf8")));
if (ablationManifest != null) assert.equal(ablationManifest.revision, revision, "Ablation source must match server revision");
const engine = required(process.env.ENGINE_URL);
const legacy = z.enum(["current", "legacy"]).parse(process.env.BENCHMARK_SERVER_MODE ?? "current") === "legacy";
const auditEngine = legacy ? required(process.env.BENCHMARK_AUDIT_URL) : engine;
if (legacy) assert.notEqual(auditEngine, engine, "Legacy audits require a separate current read-only evaluator");
let legacyServer: Awaited<ReturnType<typeof legacyBenchmarkServer>> | null = null;
let browser: Awaited<ReturnType<typeof browserBenchmark>> | null = null;
const prisma = new PrismaClient();
const sizes = (process.env.BENCHMARK_SIZES ?? "20,30,50").split(",").map(value => z.union([z.literal(20), z.literal(30), z.literal(50)]).parse(Number(value)));
const workloadSchema = z.enum(["SPARSE", "CLUSTERED", "DISPERSED", "MIXED_SKILL", "TIGHT_WINDOW", "ABSENCE", "NEAR_CAPACITY"]);
const workloads = (process.env.BENCHMARK_WORKLOADS ?? workloadSchema.options.join(",")).split(",").map(value => workloadSchema.parse(value));
const concurrencyValues = (process.env.BENCHMARK_CONCURRENCY ?? "1,5,10").split(",").map(value => z.union([z.literal(1), z.literal(5), z.literal(10)]).parse(Number(value)));
const caches = (process.env.BENCHMARK_CACHES ?? "cold,warm").split(",").map(value => z.enum(["cold", "warm"]).parse(value));
const requests = z.int().min(10).max(200).parse(Number(process.env.BENCHMARK_REQUESTS ?? "30"));
const seed = z.int().nonnegative().parse(Number(process.env.BENCHMARK_SEED ?? "17"));
const dates = bookingHorizon();
const points = OMAHA_FAKE_LOCATIONS.filter(point => point.state === "NE").slice(0, 10).map(({ lat, lng }) => ({ lat, lng }));
assert.equal(points.length, 10);
const output = await open(required(process.env.BENCHMARK_OUTPUT), "wx");
async function record(value: unknown) { await output.write(JSON.stringify(value) + "\n"); }
async function post(path: string, body: unknown): Promise<unknown> {
  const response = await fetch(auditEngine + path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), signal: AbortSignal.timeout(120000) });
  const value: unknown = await response.json(); assert.equal(response.status, 200, JSON.stringify(value)); return value;
}
const policy = z.object({ overtimeMinutes: z.int().nonnegative(), costCents: z.int().nonnegative(), fairness: z.object({ variance: z.number().nonnegative(), maximumUtilization: z.number().nonnegative(),
  workloads: z.array(z.object({ technicianId: z.string(), paidMinutes: z.int().nonnegative(), regularCapacityMinutes: z.int().positive(), utilization: z.number().nonnegative() })) }) });
const auditContract = z.object({ routingIdentity: z.string().min(1), configurationFingerprint: z.string().min(1), independentlyValidated: z.literal(true),
  policyRules: z.object({ regularWindowThreshold: z.int().nonnegative(), utilizationThreshold: z.number().min(0).max(1),
    fairnessAllowance: z.number().min(0).max(1), bookingDeadlineMs: z.int().min(1000).max(5000) }).optional(),
  operatingRates: z.object({ regularHourly: z.number().nonnegative(), overtimeHourly: z.number().nonnegative(), mileagePerMile: z.number().nonnegative(),
    travelBufferPct: z.number().nonnegative(), travelBufferMinutes: z.int().nonnegative() }).optional(),
  days: z.array(z.object({ date: z.iso.date(), policy, waitingMinutes: z.int().nonnegative(), roadSeconds: z.int().nonnegative(),
    configuredBufferSeconds: z.number().nonnegative(), roundingSeconds: z.number().nonnegative(), confirmedAppointments: z.int().nonnegative(), reservedStops: z.int().nonnegative() })) });
async function audit(metroId: string) { return auditContract.parse(await post("/internal/benchmark/audit", { metroId, dates })); }
const statisticsContract = z.object({ routing: z.object({ legRequests: z.int().nonnegative(), requestedPairs: z.int().nonnegative(),
  identityRequests: z.int().nonnegative(), geometryRequests: z.int().nonnegative() }), processCpuNanos: z.int().nonnegative().nullable(),
  heapUsedBytes: z.int().nonnegative(), peakHeapUsedBytes: z.int().nonnegative(), uptimeMs: z.int().nonnegative(), configuration: z.record(z.string(), z.string()) });
async function statistics(resetPeak: boolean) {
  if (legacy) return null; // Audit-process counters cannot be attributed to the original booking process.
  const response = await fetch(`${engine}/internal/benchmark/statistics`, { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ resetPeak }), signal: AbortSignal.timeout(5000) });
  if (response.status === 404) return null; // Earlier frozen artifacts explicitly lack this instrumentation.
  const body: unknown = await response.json(); assert.equal(response.status, 200, JSON.stringify(body));
  const measured = statisticsContract.parse(body);
  if (ablationManifest != null) {
    assert.equal(measured.configuration["booking.reservations.enabled"], "true");
    assert.equal(measured.configuration["booking.search.bounded"], String(ablationManifest.boundedSearch));
  }
  return measured;
}
function at(date: string, minute: number) { return new Date(localMidnightUtc(date, "America/Chicago").getTime() + minute * 60000); }
type Workload = z.infer<typeof workloadSchema>;

async function dataset(size: number, workload: Workload, caseId: string) {
  const random = createSeededRandom(seed);
  const metro = await prisma.metro.create({ data: { id: caseId, name: caseId, timezone: "America/Chicago", stateCode: "NE", serviceRadiusMi: 65 } });
  const dealer = await prisma.dealership.create({ data: { id: `${caseId}-dealer`, name: caseId } });
  const first = required(points[0]);
  const depot = await prisma.depot.create({ data: { id: `${caseId}-depot`, name: caseId, metroId: metro.id, dealershipId: dealer.id, ...first,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01"), departure: "HOME", returnTo: "HOME" } } } });
  const common = await prisma.serviceCatalog.create({ data: { id: `${caseId}-common`, code: `${caseId}-common`, name: "Benchmark common", estDurationMin: 30 } });
  const scarce = await prisma.serviceCatalog.create({ data: { id: `${caseId}-scarce`, code: `${caseId}-scarce`, name: "Benchmark scarce", estDurationMin: 30 } });
  const customer = await prisma.customer.create({ data: { id: `${caseId}-customer`, firstName: "Benchmark", lastName: "Fixture", email: "benchmark@example.invalid", phone: "0000000000" } });
  const addresses: string[] = [];
  for (let index = 0; index < points.length; index++) {
    const address = await prisma.address.create({ data: { id: `${caseId}-address-${index}`, customerId: customer.id, line1: `Benchmark pin ${index}`, city: "Omaha", state: "NE", postalCode: "68102", ...required(points[index]) } });
    addresses.push(address.id);
  }
  const manifest: unknown[] = [];
  for (let technician = 0; technician < size; technician++) {
    const techId = `${caseId}-tech-${technician}`;
    const homeIndex = workload === "CLUSTERED" ? technician % 2 : technician % points.length;
    const home = required(points[homeIndex]);
    const shiftEnd = workload === "TIGHT_WINDOW" ? 720 : workload === "NEAR_CAPACITY" ? 990 : 1020;
    const canScarce = workload !== "MIXED_SKILL" || technician % 4 === 0;
    await prisma.technician.create({ data: { id: techId, name: `Benchmark ${technician}`, color: technicianColor(techId), homeLat: home.lat, homeLng: home.lng,
      shiftStartMin: 480, shiftEndMin: shiftEnd, maxDailyMinutes: 540, maxOvertimeMinutes: 60, availabilityVersions: initialAvailability(480, shiftEnd),
      depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01") } },
      qualifications: { create: [{ serviceId: common.id }, ...(canScarce ? [{ serviceId: scarce.id }] : [])] } } });
    const count = workload === "SPARSE" ? 0 : workload === "NEAR_CAPACITY" ? 8 : technician < size / 2 ? 3 : 1;
    for (const date of dates) {
      await prisma.scheduleDay.create({ data: { id: `${techId}-${date}`, technicianId: techId, serviceDate: new Date(`${date}T00:00:00Z`) } });
      if (workload === "ABSENCE" && technician % 2 === 0) await prisma.timeOffRequest.create({ data: {
        id: `${techId}-${date}-absence`, technicianId: techId, status: "APPROVED", category: "PTO", reason: "Benchmark approved absence",
        intervals: { create: { serviceDate: new Date(`${date}T00:00:00Z`), startMin: 720, endMin: 780 } },
      } });
      for (let index = 0; index < count; index++) {
        const duration = workload === "NEAR_CAPACITY" ? 55 : 30;
        const arrival = 485 + index * (duration + 5);
        const window = 480 + Math.floor(index / 2) * 120;
        const jobId = `${techId}-${date}-job-${index}`;
        const serviceId = canScarce && workload === "MIXED_SKILL" && index % 2 === 0 ? scarce.id : common.id;
        await prisma.job.create({ data: { id: jobId, customerId: customer.id, addressId: required(addresses[homeIndex]), serviceId, durationMin: duration, status: "SCHEDULED",
          appointment: { create: { id: `${jobId}-appointment`, technicianId: techId, serviceDate: new Date(`${date}T00:00:00Z`), windowStart: at(date, window), windowEnd: at(date, window + 120), plannedStart: at(date, arrival), plannedEnd: at(date, arrival + duration), sequence: index } } } });
        manifest.push({ technician, date, index, duration, window, location: homeIndex, service: serviceId === scarce.id ? "scarce" : "common" });
      }
    }
  }
  const jobIds: string[] = [];
  for (let index = 0; index < requests; index++) {
    const location = workload === "CLUSTERED" ? index % 2 : Math.floor(random() * points.length);
    const service = workload === "MIXED_SKILL" && index % 3 === 0 ? scarce : common;
    const job = await prisma.job.create({ data: { id: `${caseId}-request-${index}`, customerId: customer.id, addressId: required(addresses[location]), serviceId: service.id, durationMin: 30 } });
    jobIds.push(job.id); manifest.push({ request: index, location, service: service.id === scarce.id ? "scarce" : "common", duration: 30 });
  }
  return { metroId: metro.id, jobIds, fingerprint: createHash("sha256").update(JSON.stringify({ size, workload, seed, dates, points, manifest })).digest("hex") };
}

type Attempt = { index: number; elapsedMs: number; outcome: string; completed: boolean | null; offers: number; served: boolean; serviceDate?: string; error?: string; search?: z.infer<typeof appointmentSearch>; selectionElapsedMs?: number };
async function removeSuccessfulCase(caseId: string) {
  const jobs = { id: { startsWith: `${caseId}-` } };
  const technicians = { id: { startsWith: `${caseId}-tech-` } };
  await prisma.$transaction(async tx => {
    await tx.reservationArrangement.deleteMany({ where: { metroId: caseId } });
    await tx.appointment.deleteMany({ where: { job: jobs } });
    await tx.slotHold.deleteMany({ where: { job: jobs } });
    await tx.bookingOptimization.deleteMany({ where: { job: jobs } });
    await tx.bookingOffer.deleteMany({ where: { job: jobs } });
    await tx.bookingOfferSet.deleteMany({ where: { job: jobs } });
    await tx.job.deleteMany({ where: jobs });
    await tx.address.deleteMany({ where: { customerId: `${caseId}-customer` } });
    await tx.customer.deleteMany({ where: { id: `${caseId}-customer` } });
    await tx.timeOffRequest.deleteMany({ where: { technician: technicians } });
    await tx.scheduleDay.deleteMany({ where: { technician: technicians } });
    await tx.technicianQualification.deleteMany({ where: { technician: technicians } });
    await tx.technicianShiftOverride.deleteMany({ where: { technician: technicians } });
    await tx.technician.deleteMany({ where: technicians });
    await tx.serviceCatalog.deleteMany({ where: { id: { in: [`${caseId}-common`, `${caseId}-scarce`] } } });
    await tx.depot.deleteMany({ where: { id: `${caseId}-depot` } });
    await tx.dealership.deleteMany({ where: { id: `${caseId}-dealer` } });
    await tx.metro.deleteMany({ where: { id: caseId } });
  }, { timeout: 60000 });
}
try {
  assert.equal(await prisma.metro.count(), 0, "Start with a freshly migrated, unseeded benchmark schema; failed datasets are retained for inspection");
  if (process.env.BENCHMARK_PORTAL_URL != null) {
    assert.equal(legacy, false, "Original server comparison uses its unchanged API directly");
    browser = await browserBenchmark(process.env.BENCHMARK_PORTAL_URL);
  }
  await record({ type: "provenance", revision, artifactSha256, ablationManifest, harnessRevision, harnessSources, variant, seed, startedAt: new Date().toISOString(), dates, sizes, workloads, concurrencyValues, caches, requests,
    browser: browser == null ? null : { version: browser.version, portal: process.env.BENCHMARK_PORTAL_URL, flow: "validated-job refresh" },
    serverMode: legacy ? "legacy" : "current", auditRevision: legacy ? required(process.env.BENCHMARK_AUDIT_REVISION) : revision,
    legacyLimitations: legacy ? "Original unchanged server: 120-second measurement timeout, no completion/deadline metadata, fresh process per case; separate current evaluator reports canonical modeled metrics." : null,
    scope: browser == null ? "Customer scheduling client HTTP including cancellation and acknowledgement; excludes address entry/geocoding and browser transport"
      : "Browser HTTP through the portal including scheduler cancellation/acknowledgement; starts with validated job/address; excludes address entry/geocoding and rendering", hardware: { cpu: required(cpus()[0]).model, logicalProcessors: cpus().length, memoryBytes: totalmem() } });
  for (const size of sizes) for (const workload of workloads) for (const concurrency of concurrencyValues) for (const cache of caches) {
    const caseId = `benchmark-${randomUUID()}`;
    const data = await dataset(size, workload, caseId);
    const before = await audit(data.metroId);
    if (workload === "NEAR_CAPACITY") for (const day of before.days) {
      const paid = day.policy.fairness.workloads.reduce((sum, item) => sum + item.paidMinutes, 0);
      const capacity = day.policy.fairness.workloads.reduce((sum, item) => sum + item.regularCapacityMinutes, 0);
      assert.ok(paid >= .9 * capacity, "Near-capacity case must be independently measured above 90 percent");
    }
    const originals = await prisma.appointment.findMany({ where: { technician: { id: { startsWith: `${caseId}-tech-` } } }, orderBy: { id: "asc" } });
    await post("/internal/benchmark/cache", { warm: cache === "warm", points });
    if (legacy) legacyServer = await legacyBenchmarkServer(database, engine, required(artifactSha256));
    const processBefore = await statistics(true);
    const attempts: Attempt[] = [];
    for (let offset = 0; offset < data.jobIds.length; offset += concurrency) {
      const group = await Promise.all(data.jobIds.slice(offset, offset + concurrency).map(async (jobId, localIndex): Promise<Attempt> => {
        const started = performance.now(); let offered: string | null = null;
        let searched: Attempt | null = null; let selectionStarted: number | null = null;
        try {
          const browserResult = browser == null ? null : await browser.search(jobId);
          const response = browserResult?.response ?? (legacy ? { ...await legacyOffers(engine, jobId), search: undefined } : await requestSlots(jobId));
          const elapsedMs = browserResult?.elapsedMs ?? performance.now() - started;
          const selected = chooseTestOffer(response.offers, "earliest", 0);
          const attempt: Attempt = { index: offset + localIndex, elapsedMs, search: response.search,
            outcome: response.search?.outcome ?? (response.offers.length ? "LEGACY_AVAILABLE" : "LEGACY_NO_OFFER"), completed: response.search?.prescribedSearchCompleted ?? null,
            offers: response.offers.length, served: false };
          searched = attempt;
          if (selected) {
            offered = selected.offerId; selectionStarted = performance.now(); await selectOffer(jobId, selected.offerId);
            attempt.selectionElapsedMs = performance.now() - selectionStarted; attempt.served = true; attempt.serviceDate = selected.date;
          }
          return attempt;
        } catch (error) {
          // A legacy timeout cannot safely imply that server work stopped. Stop the case and
          // its dedicated process, retain the dataset, and never race cleanup against that work.
          if (error instanceof LegacyRequestUncertain) throw error;
          if (offered) await releaseOffers(jobId, offered).catch(() => undefined);
          if (searched && selectionStarted != null) return { ...searched, outcome: "SELECTION_CONFLICT", served: false,
            selectionElapsedMs: performance.now() - selectionStarted, error: errorMessage(error) };
          return { index: offset + localIndex, elapsedMs: error instanceof BrowserSearchError ? error.elapsedMs : performance.now() - started, outcome: offered ? "SELECTION_CONFLICT" : "SEARCH_ERROR", completed: legacy ? null : false, offers: offered ? 1 : 0, served: false, error: errorMessage(error) };
        }
      })); attempts.push(...group);
    }
    if (legacyServer) { await legacyServer.stop(); legacyServer = null; }
    const processAfter = await statistics(false);
    const diagnostics = await benchmarkDiagnostics(process.env.BENCHMARK_LOG_PATH, data.jobIds);
    const after = await audit(data.metroId);
    const final = await prisma.appointment.findMany({ where: { id: { in: originals.map(item => item.id) } }, orderBy: { id: "asc" } });
    assert.deepEqual(final.map(item => [item.id, item.serviceDate, item.windowStart, item.windowEnd]), originals.map(item => [item.id, item.serviceDate, item.windowStart, item.windowEnd]));
    const ordered = attempts.map(item => item.elapsedMs).sort((a, b) => a - b);
    const percentile = (p: number) => required(ordered[Math.min(ordered.length - 1, Math.ceil(p * ordered.length) - 1)]);
    await record({ type: "case", revision, variant, caseId, size, workload, concurrency, cache, datasetFingerprint: data.fingerprint, before, after, attempts, processBefore, processAfter, diagnostics,
      served: attempts.filter(item => item.served).length, incomplete: attempts.filter(item => item.completed === false).length,
      unknownSearchCompletion: attempts.filter(item => item.completed === null).length,
      p50Ms: percentile(.5), p95Ms: percentile(.95), p99Ms: percentile(.99),
      changedAssignments: final.filter((item, index) => item.technicianId !== required(originals[index]).technicianId).length,
      retimedAppointments: final.filter((item, index) => item.plannedStart.getTime() !== required(originals[index]).plannedStart.getTime()).length,
      independentlyValidated: true, promiseViolations: 0, finishedAt: new Date().toISOString() });
    console.log(`${size}/${workload}/${concurrency}/${cache}: ${attempts.filter(item => item.served).length}/${requests} served, p95 ${percentile(.95).toFixed(1)} ms`);
    await removeSuccessfulCase(caseId);
  }
} catch (error) { await record({ type: "failure", message: errorMessage(error), at: new Date().toISOString() }); throw error; }
finally { if (legacyServer) await legacyServer.stop(); if (browser) await browser.close(); await output.close(); await prisma.$disconnect(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
