// Fake data through the public scheduling API, end to end against a running scheduler and the fixture router: each
// call is booked by the generator's step (search the chosen date, select an offer, confirm its hold), and
// cleared by deleting the generated jobs while the visits left on an open day are re-timed in the same write. The
// fixture router knows only the Omaha sample locations, not the generator's randomized addresses, so the calls are
// placed there; smoke-fake-data covers the full generator against real routing. The Omaha metro must route to it:
//   ROUTING_METRO_URLS=metro-omaha=http://127.0.0.1:18001   SCHEDULER_TEST_URL=http://127.0.0.1:18000
import assert from "node:assert/strict";
import { prisma } from "../lib/prisma";
import { required } from "../lib/contracts";
import { connectSmokeClient } from "./smokeApiClient";
import { DEFAULT_CLIENT_ID } from "../lib/clients";
import { bookFakeCall, clearFakeData } from "../lib/fakeData";
import { createSeededRandom, dateFromFakeExternalId, fakeExternalId, FAKE_DATA_PREFIX, OMAHA_FAKE_LOCATIONS } from "../lib/fakeDataCore";
import { purgeApiJobs } from "../lib/apiPurge";
import { ChangeRefused } from "../lib/apiMasterDataCore";
import { ensureOmahaConfiguration } from "../lib/omahaConfiguration";
import { currentLastModified } from "../lib/apiReceipt";

// Far ahead and outside smoke-fake-data's dates, so neither the 6 a.m. cutoff nor that smoke interferes.
const RANGE_START = "2037-05-04";
const RANGE_END = "2037-05-04";
const CLIENT = DEFAULT_CLIENT_ID;
const base = required(process.env.SCHEDULER_TEST_URL, "SCHEDULER_TEST_URL (a running scheduler)");

const inRange = (externalId: string | null) => { const date = dateFromFakeExternalId(externalId); return date !== null && date >= RANGE_START && date <= RANGE_END; };
const fakeJobs = async () => (await prisma.job.findMany({ where: { externalId: { startsWith: FAKE_DATA_PREFIX } },
  select: { id: true, externalId: true, status: true, customerId: true, appointment: { select: { id: true, externalId: true, technicianId: true, serviceDate: true, windowStart: true, windowEnd: true } } } }))
  .filter(job => inRange(job.externalId));

async function main() {
  await ensureOmahaConfiguration(prisma);
  const disconnect = await connectSmokeClient(CLIENT, base, "API fake data smoke");
  let manualJobId: string | null = null;
  try {
    await clearFakeData(RANGE_START, RANGE_END, CLIENT);
    // Generated calls land at the Omaha sample locations the fixture router knows, booked by the generator's own step.
    const service = await prisma.serviceCatalog.findUniqueOrThrow({ where: { code: "FILTER_SWAP" } });
    const random = createSeededRandom(515151);
    for (const [ordinal, location] of OMAHA_FAKE_LOCATIONS.slice(0, 3).entries()) {
      const externalId = fakeExternalId(RANGE_START, 515151, ordinal, 0);
      const customer = await prisma.customer.create({ data: { clientId: CLIENT, externalId, firstName: "Fake", lastName: `Call ${ordinal}`, email: `fake${ordinal}@example.invalid`, phone: "0000000000" } });
      const address = await prisma.address.create({ data: { customerId: customer.id, line1: location.line1, city: location.city, state: location.state, postalCode: location.postalCode,
        lat: location.lat, lng: location.lng, geocodePrecision: "SYNTHETIC", geocodedAt: new Date() } });
      const job = await prisma.job.create({ data: { externalId, customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: 50, status: "PENDING" } });
      assert.equal(await bookFakeCall(CLIENT, job.id, RANGE_START, random), "BOOKED", `Call ${ordinal} is booked on the chosen date`);
      await prisma.appointment.update({ where: { jobId: job.id }, data: { externalId } });
    }
    let jobs = await fakeJobs();
    assert.equal(jobs.length, 3);
    assert.ok(jobs.every(job => job.status === "SCHEDULED" && job.appointment?.serviceDate.toISOString().startsWith(RANGE_START)));
    assert.ok(jobs.every(job => job.appointment !== null && job.appointment.windowEnd > job.appointment.windowStart), "Generated calls keep an offered window");
    assert.equal(await prisma.portalApiOfferSet.count({ where: { jobId: { in: jobs.map(job => job.id) }, receiptId: { not: null } } }), 3,
      "Each call was booked through a confirmed API hold");

    // Another client cannot remove these calls.
    await assert.rejects(purgeApiJobs("api-fake-other-client", "metro-omaha", jobs.map(job => job.id)),
      (error: unknown) => error instanceof ChangeRefused && error.status === 404);

    // A call that stops being fake data survives the purge, and its day is re-timed without the purged visits.
    const busiest = [...Map.groupBy(jobs.flatMap(job => job.appointment ? [job.appointment] : []), item => `${item.technicianId}|${item.serviceDate.toISOString()}`).values()]
      .sort((a, b) => b.length - a.length)[0];
    const kept = busiest?.at(-1);
    assert.ok(busiest !== undefined && kept !== undefined);
    const keptJob = jobs.find(job => job.appointment?.id === kept.id);
    assert.ok(keptJob);
    manualJobId = keptJob.id;
    await prisma.$transaction([
      prisma.customer.update({ where: { id: keptJob.customerId }, data: { externalId: `manual-api-smoke:${keptJob.id}` } }),
      prisma.job.update({ where: { id: keptJob.id }, data: { externalId: `manual-api-smoke:${keptJob.id}` } }),
      prisma.appointment.update({ where: { id: kept.id }, data: { externalId: `manual-api-smoke:${kept.id}` } }),
    ]);
    const day = { technicianId: kept.technicianId, serviceDate: RANGE_START };
    const before = (await currentLastModified(prisma, [day])).get(`${day.technicianId}|${day.serviceDate}`);

    const cleared = await clearFakeData(RANGE_START, RANGE_END, CLIENT);
    assert.equal(cleared.removed, 2);
    jobs = await fakeJobs();
    assert.equal(jobs.length, 0);
    const survivors = await prisma.appointment.findMany({ where: { technicianId: kept.technicianId, serviceDate: kept.serviceDate, cancelledAt: null }, orderBy: { sequence: "asc" } });
    assert.deepEqual(survivors.map(item => [item.id, item.sequence]), [[kept.id, 0]], "The kept visit is the day's only one, renumbered");
    if (busiest.length > 1)
      assert.notEqual((await currentLastModified(prisma, [day])).get(`${day.technicianId}|${day.serviceDate}`), before, "A re-timed day's lastModified moves");
    assert.equal(await prisma.customer.count({ where: { externalId: { startsWith: FAKE_DATA_PREFIX } } }), 0, "No fake customer is left");
    console.log("API fake data smoke passed");
  } finally {
    await clearFakeData(RANGE_START, RANGE_END, CLIENT).catch(error => console.error("Fake data cleanup failed", error));
    if (manualJobId !== null) {
      const job = await prisma.job.findUnique({ where: { id: manualJobId }, select: { id: true, customerId: true, addressId: true } });
      if (job !== null) {
        await prisma.appointment.deleteMany({ where: { jobId: job.id } });
        await prisma.job.delete({ where: { id: job.id } });
        await prisma.address.delete({ where: { id: job.addressId } });
        await prisma.customer.delete({ where: { id: job.customerId } });
      }
    }
    await disconnect();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
