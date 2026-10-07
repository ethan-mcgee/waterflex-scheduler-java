import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve, join } from "node:path";
import { PrismaClient } from "@prisma/client";
import { z } from "zod";
import { required } from "../lib/contracts";
import { initialAvailability } from "../lib/technicianAvailability";
import { technicianColor } from "../lib/technicianColor";
import { removeSuccessfulCase } from "./benchmarkCleanup";

// Acceptance smoke only. Every case uses a fresh JVM and the real caller/database/routing path.
const prisma = new PrismaClient();
const root = resolve("..");
const base = required(process.env.SCHEDULER_TEST_URL);
const deployment = z.enum(["embedded", "remote"]).parse(process.env.CAMPAIGN_DEPLOYMENT);
const java = join(required(process.env.JAVA_HOME), "bin", "java");
const jar = join(root, "campaign-benchmark/target/campaign-benchmark-0.1.0-SNAPSHOT.jar");
const caseId = `campaign-${randomUUID()}`;
const directory = join(root, "experiments/runs", caseId);
mkdirSync(join(root, "experiments/runs"), { recursive: true });
mkdirSync(directory, { recursive: false });
const policySchema = z.object({ regularWindowThreshold: z.number().int().nonnegative(), utilizationThreshold: z.string(), fairnessAllowance: z.string(), bookingDeadlineMs: z.number().int().min(1000).max(5000) }).strict();
const snapshotSchema = z.object({ dataset: z.string(), policy: policySchema }).strict();
const inventorySchema = z.object({ outcomes: z.object({ SUCCEEDED: z.number().int(), FAILED: z.number().int(), INTERRUPTED: z.number().int(), ABANDONED: z.number().int() }), independentlyValidCases: z.number().int().nullable(), inference: z.null() }).passthrough();
const identitySchema = z.object({ versions: z.object({ score: z.string(), cost: z.string() }).passthrough(), routing: z.object({ identity: z.string() }).passthrough() }).passthrough();
const sha = (path: string) => createHash("sha256").update(readFileSync(path)).digest("hex");
function save(path: string, value: unknown) { writeFileSync(path, JSON.stringify(value, null, 2), { flag: "wx" }); }
async function post(path: string, body: unknown): Promise<unknown> {
  const response = await fetch(base + path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), signal: AbortSignal.timeout(30_000) });
  const raw: unknown = await response.json(); assert.equal(response.status, 200, JSON.stringify(raw)); return raw;
}
const date = new Date(); date.setUTCDate(date.getUTCDate() + 7); date.setUTCDate(date.getUTCDate() + ((8 - date.getUTCDay()) % 7));
const day = date.toISOString().slice(0, 10), serviceDate = new Date(`${day}T00:00:00Z`);
const call = (id: string, path: string, body: unknown) => ({ id, method: "POST", path, body, expectedStatus: 200 });

async function main() {
  let successful = false;
  try {
    // The existing cleanup helper is scoped to these fixture identities and retains failed fixtures.
    await prisma.metro.create({ data: { id: caseId, name: "Campaign acceptance fixture", timezone: "America/Chicago" } });
    await prisma.dealership.create({ data: { id: `${caseId}-dealer`, name: "Campaign fixture" } });
    await prisma.depot.create({ data: { id: `${caseId}-depot`, metroId: caseId, dealershipId: `${caseId}-dealer`, name: "Campaign fixture", lat: 43.735, lng: 7.42,
      endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
    await prisma.serviceCatalog.create({ data: { id: `${caseId}-common`, code: caseId, name: "Campaign fixture", estDurationMin: 30 } });
    await prisma.customer.create({ data: { id: `${caseId}-customer`, firstName: "Campaign", lastName: "Fixture", email: `${caseId}@example.invalid`, phone: "0000000000" } });
    await prisma.address.create({ data: { id: `${caseId}-address`, customerId: `${caseId}-customer`, line1: "Fixture", city: "Monaco", state: "MC", postalCode: "98000", lat: 43.748, lng: 7.438 } });
    for (let index = 0; index < 2; index++) {
      const id = `${caseId}-tech-${index}`;
      await prisma.technician.create({ data: { id, name: "Campaign fixture", color: technicianColor(id), availabilityVersions: initialAvailability(480, 1020),
        depotAssignments: { create: { depotId: `${caseId}-depot`, effectiveDate: new Date("1900-01-01T00:00:00Z") } },
        homeLat: index === 0 ? 43.735 : 43.748, homeLng: index === 0 ? 7.42 : 7.438, shiftStartMin: 480, shiftEndMin: 1020,
        maxDailyMinutes: 540, maxOvertimeMinutes: 0, qualifications: { create: { serviceId: `${caseId}-common` } } } });
    }
    for (let index = 0; index < 4; index++) await prisma.job.create({ data: { id: `${caseId}-job-${index}`, customerId: `${caseId}-customer`, addressId: `${caseId}-address`, serviceId: `${caseId}-common`, durationMin: 30, status: index === 3 ? "SCHEDULED" : "PENDING" } });
    await prisma.appointment.create({ data: { id: `${caseId}-appointment`, jobId: `${caseId}-job-3`, technicianId: `${caseId}-tech-0`, serviceDate,
      windowStart: new Date(`${day}T16:00:00Z`), windowEnd: new Date(`${day}T20:00:00Z`), plannedStart: new Date(`${day}T16:20:00Z`), plannedEnd: new Date(`${day}T16:50:00Z`), sequence: 0 } });
    const rawSnapshot = await post("/internal/benchmark/dataset", { metro_id: caseId, date: day, request_key: `${caseId}-capture` });
    save(join(directory, "caller-snapshot-response.json"), rawSnapshot);
    const snapshot = snapshotSchema.parse(rawSnapshot);
    const identity = identitySchema.parse(JSON.parse(snapshot.dataset) as unknown);
    save(join(directory, "caller-snapshot.json"), snapshot);
    for (const operation of ["booking-offer", "daily-preview"] as const) {
      const requests = operation === "booking-offer" ? [0, 1].map(index => {
        const body = { jobId: `${caseId}-job-${index}`, refresh: true, deadlineEpochMs: null, searchRequestId: `${caseId}-search-${index}` };
        const cancellation = call(`cancel-${index}`, "/v1/offers/cancel-search", { jobId: body.jobId, searchRequestId: body.searchRequestId });
        return { call: call(`booking-${index}`, "/v1/offers", body), cancelAfterMs: index === 0 ? 20 : null, cancellation: index === 0 ? cancellation : null, cleanup: [call(`cleanup-${index}`, "/v1/offers/cancel-search", cancellation.body)] };
      }) : [{ call: call("daily", "/v1/optimize/day/preview", { metro_id: caseId, date: day, request_key: `${caseId}-daily` }), cancelAfterMs: null, cancellation: null, cleanup: [] }];
      const warmBody = { jobId: `${caseId}-job-2`, refresh: true, deadlineEpochMs: null, searchRequestId: `${caseId}-warm-search` };
      const warm = operation === "booking-offer" ? { call: call("warm-booking", "/v1/offers", warmBody), cancelAfterMs: null, cancellation: null,
        cleanup: [call("warm-cleanup", "/v1/offers/cancel-search", { jobId: warmBody.jobId, searchRequestId: warmBody.searchRequestId })] }
        : { call: call("warm-daily", "/v1/optimize/day/preview", { metro_id: caseId, date: day, request_key: `${caseId}-warm-daily` }), cancelAfterMs: null, cancellation: null, cleanup: [] };
      const envelopePath = join(directory, `${operation}-input.json`);
      save(envelopePath, { version: 1, datasetJson: snapshot.dataset, baseUrl: base, endpointIdentity: `acceptance-${deployment}`, expectedConfiguration: { "scheduler.calculation.mode": deployment.toUpperCase() },
        preparation: [call("cache", "/internal/benchmark/cache", { warm: false, points: [{ lat: 43.735, lng: 7.42 }, { lat: 43.748, lng: 7.438 }] })],
        warmupRequests: { [operation]: [warm] }, requests, verification: [call("independent-audit", "/internal/benchmark/audit", { metroId: caseId, dates: [day] })], cleanupObservationMs: 10000, cleanupPollMs: 100 });
      const configuration = z.record(z.string(), z.unknown()).parse(JSON.parse(readFileSync(join(root, "experiments/configs/campaign-v2-template.json"), "utf8")) as unknown);
      configuration.name = `acceptance-${deployment}-${operation}`; configuration.purpose = "Real caller acceptance smoke, no comparative performance claim"; configuration.layer = "workflow"; configuration.policy = snapshot.policy;
      configuration.datasets = [{ id: "caller", input: { path: envelopePath, sha256: sha(envelopePath) }, family: "caller-acceptance", role: "tuning", cohort: "assigned", origin: "legacy-fixture", datasetSeed: null,
        scoreVersion: identity.versions.score, modelVersion: identity.versions.cost, routingIdentity: identity.routing.identity, target: null }];
      configuration.configurations = [{ id: "tabu", acceptor: "TABU", acceptorSize: 7, acceptedCountLimit: 1000, selectedCountLimit: 10000, moves: [{ family: "listChange", weight: 45 }, { family: "listSwap", weight: 45 }], environmentMode: "NO_ASSERT", moveThreads: "NONE", nativeParallelBenchmarkCount: 1,
        termination: { kind: "fixed", scope: "local-search", spentCap: true, stepCap: null, windowMs: null, minimumImprovementRatio: null, unimprovedMs: null } }];
      configuration.solverSeeds = [17]; configuration.forks = 1;
      configuration.budgets = [operation === "booking-offer" ? { id: "booking", purpose: "production", phase: "booking", operationMs: snapshot.policy.bookingDeadlineMs, searchMs: snapshot.policy.bookingDeadlineMs - 1000, referenceMs: 0, fairnessMs: 0, repairMs: 0, validationReserveMs: 1000, transferUnusedToFairness: false }
        : { id: "daily", purpose: "production", phase: "pipeline", operationMs: 20000, searchMs: 15000, referenceMs: 10000, fairnessMs: 5000, repairMs: 0, validationReserveMs: 1000, transferUnusedToFairness: true }];
      configuration.warmup = { millisecondsPerFreshJvm: 1, paths: [operation], disposableInputs: true, calibrationMs: [200, 30000, 60000], calibrationRepetitions: 3, stabilityTolerancePercent: 5 };
      configuration.runtime = { java: { path: java, sha256: sha(java) }, jdkMajor: 25, heapMinMiB: 128, heapMaxMiB: 512, gc: "G1GC", activeProcessorCount: 1, flags: ["-Dfile.encoding=UTF-8"], environment: { TZ: "UTC" }, processLifetime: "one-case-per-jvm" };
      configuration.resources = { parallelCases: 1, totalCpuAllocation: 1, cpuPerCase: 1, affinityPolicy: "disjoint-physical-cores", concurrencyCalibration: [1, 2], memoryLimitMiB: 1024 };
      configuration.applicationLoad = { operation, mode: "paced-arrival", requestsPerCase: requests.length, requestsPerSecond: 1, concurrency: 2, schedulerCache: "cold", providerCache: "cold", timeoutMs: 30000, observeCancellation: true, deployment, endpointIdentity: `acceptance-${deployment}` };
      configuration.estimation = { startupMsPerJvm: 5000, preparationMsPerCase: 5000, validationMsPerCase: 5000, reportingMsPerCase: 25000, referenceSetupMsPerDataset: 0, serialAnalysisMs: 1000 };
      configuration.outputLocation = directory; configuration.adapter = { protocol: "waterflex-campaign-jvm-v1", jar: { path: jar, sha256: sha(jar) } };
      const configPath = join(directory, `${operation}-campaign.json`); save(configPath, configuration);
      let output: string;
      try {
        output = execFileSync("python3", [join(root, "infra/experiments.py"), "run", configPath], { encoding: "utf8", timeout: 120000 });
      } catch (error) {
        const streams = z.object({ stdout: z.string(), stderr: z.string() }).passthrough().safeParse(error);
        if (streams.success) {
          writeFileSync(join(directory, `${operation}-failed-stdout.log`), streams.data.stdout, { flag: "wx" });
          writeFileSync(join(directory, `${operation}-failed-stderr.log`), streams.data.stderr, { flag: "wx" });
        } else save(join(directory, `${operation}-launch-failure.json`), { error: String(error), streamsUnavailableReason: "Process launch did not return string streams" });
        throw error;
      }
      writeFileSync(join(directory, `${operation}-process.log`), output, { flag: "wx" });
      const start = output.indexOf("{\n"); assert.ok(start >= 0, output);
      const inventory = inventorySchema.parse(JSON.parse(output.slice(start)) as unknown);
      assert.equal(inventory.outcomes.SUCCEEDED, 1); assert.equal(inventory.outcomes.FAILED, 0);
      assert.equal(inventory.outcomes.INTERRUPTED, 0); assert.equal(inventory.outcomes.ABANDONED, 0);
      assert.equal(inventory.independentlyValidCases, 1, "Caller transport, cleanup and independent audit must all pass");
      assert.equal(inventory.inference, null);
    }
    successful = true; console.log(`Embedded/remote caller dataset export, booking/daily pacing, persistence, reservations, routing and cancellation smoke passed: ${directory}`);
  } finally {
    if (successful) await removeSuccessfulCase(prisma, caseId);
    await prisma.$disconnect();
  }
}
main().catch(error => {
  save(join(directory, "failure.json"), { error: String(error), stack: error instanceof Error ? error.stack ?? null : null });
  console.error(error); process.exitCode = 1;
});
