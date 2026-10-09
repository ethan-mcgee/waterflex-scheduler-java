// The sequential booking test runner through the public scheduling API, end to end against a running scheduler and the
// fixture router: a run books for the client active when it started, each request is searched, held and confirmed as a
// customer booking, each booked day is previewed as the client's daily proposal, and purging deletes the run's jobs with
// the days they leave re-timed. Addresses come from the Omaha sample locations the fixture router knows.
//   ROUTING_METRO_URLS=metro-omaha=http://127.0.0.1:18001   SCHEDULER_TEST_URL=http://127.0.0.1:18000
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { prisma } from "../lib/prisma";
import { connectSmokeClient } from "./smokeApiClient";
import { required, testRun } from "../lib/contracts";
import { DEFAULT_CLIENT_ID } from "../lib/clients";
import { OMAHA_FAKE_LOCATIONS } from "../lib/fakeDataCore";
import { ensureOmahaConfiguration } from "../lib/omahaConfiguration";
import { advanceTestRun, apiTestEngine, controlTestRun, createTestRun, purgeTestRun, readTestRun, TestRunError } from "../lib/bookingTestRunner";
import type { AddressGenerationDependencies } from "../lib/bookingTestAddresses";

const CLIENT = DEFAULT_CLIENT_ID;
const base = required(process.env.SCHEDULER_TEST_URL, "SCHEDULER_TEST_URL (a running scheduler)");
const config = { count: 3, seed: 4242, policy: "earliest", weights: [1, 0, 0, 0], radiusMi: 30 };
let fixtureLocation = 0;
// The Omaha sample locations, which the fixture router routes; every one is accepted, as through the public API.
const fixtureGeneration: AddressGenerationDependencies = {
  reverse: async () => required(OMAHA_FAKE_LOCATIONS.filter(location => location.state === "NE")[fixtureLocation++ % 10]),
  routable: async candidates => new Set(candidates.map(candidate => candidate.id)),
};

/** The run as the testing page receives it, checked against the page's contract. */
const wire = (run: unknown) => testRun.parse(JSON.parse(JSON.stringify(run)));

async function main() {
  process.env.SCHEDULER_PUBLIC_API = "true";
  await ensureOmahaConfiguration(prisma);
  const disconnect = await connectSmokeClient(CLIENT, base, "API booking tests smoke");
  const id = randomUUID();
  try {
    await assert.rejects(createTestRun(randomUUID(), config, null), (error: unknown) => error instanceof TestRunError && error.status === 400,
      "A public-API run must name its client");
    let run = await createTestRun(id, config, CLIENT);
    assert.equal((await prisma.bookingTestRun.findUniqueOrThrow({ where: { id } })).clientId, CLIENT, "The run books for the active client");
    run = await controlTestRun(id, "resume");
    const engine = { ...apiTestEngine(CLIENT), generation: fixtureGeneration };
    for (let step = 0; step < 40 && run.status === "RUNNING"; step++) run = await advanceTestRun(id, run.revision, engine);
    assert.equal(run.status, "COMPLETED", `The run finishes: ${run.error ?? ""}`);
    assert.equal(run.requests.length, 3);
    assert.ok(run.requests.every(request => request.status === "BOOKED" || request.status === "NO_OFFER"), "Every request is answered");
    const booked = run.requests.filter(request => request.status === "BOOKED");
    assert.ok(booked.length > 0, "At least one request is booked");
    const jobIds = booked.map(request => request.id);
    assert.equal(await prisma.portalApiOfferSet.count({ where: { clientId: CLIENT, jobId: { in: jobIds }, receiptId: { not: null } } }), booked.length,
      "Each booking was confirmed through an API hold");
    assert.equal(await prisma.customer.count({ where: { id: { in: jobIds }, clientId: CLIENT } }), booked.length);

    // Each booked day is previewed as the client's daily proposal, shown as it is now.
    const shown = wire(run);
    const days = [...new Set(booked.map(request => required(request.serviceDate)))].sort();
    assert.deepEqual(shown.previews.map(preview => preview.serviceDate).sort(), days);
    for (const preview of shown.previews) {
      const result = required(preview.result, "preview result");
      assert.ok("proposalId" in result, "A public-API preview is a daily proposal");
      assert.equal(preview.optimizationId, result.proposalId);
      assert.equal((await prisma.portalApiDailyProposal.findUniqueOrThrow({ where: { id: result.proposalId } })).clientId, CLIENT);
    }
    assert.ok(wire(await readTestRun(id)).previews.every(preview => preview.result !== null && "state" in preview.result));

    // Purging deletes only this run's generated bookings.
    run = await purgeTestRun(id);
    assert.equal(run.status, "PURGED");
    assert.equal(run.purgedCount, booked.length);
    assert.equal(await prisma.job.count({ where: { id: { in: run.requests.map(request => request.id) } } }), 0);
    assert.equal(await prisma.customer.count({ where: { id: { in: jobIds } } }), 0);
    assert.equal((await purgeTestRun(id)).status, "PURGED", "Purging again changes nothing");
    console.log("API booking test runner smoke passed");
  } finally {
    const run = await prisma.bookingTestRun.findUnique({ where: { id } });
    if (run !== null && run.status !== "PURGED") {
      if (!["PAUSED", "STOPPED", "COMPLETED"].includes(run.status)) await controlTestRun(id, "stop");
      await purgeTestRun(id).catch(error => console.error("Purge failed", error));
    }
    await disconnect();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
