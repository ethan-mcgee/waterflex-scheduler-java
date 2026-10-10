// The full fake-data generator end to end: randomized Omaha addresses booked through the public API for the default
// client, then cleared with the days they leave re-timed. The addresses are random, so this needs a scheduler with real
// road routing for metro-omaha; smoke-api-fake-data covers the same booking and purge against the fixture router.
//   SCHEDULER_TEST_URL=http://127.0.0.1:18000
import assert from "node:assert/strict";
import { clearFakeData, generateFakeData } from "../lib/fakeData";
import { dateFromFakeExternalId, FAKE_DATA_PREFIX } from "../lib/fakeDataCore";
import { prisma } from "../lib/prisma";
import { required } from "../lib/contracts";
import { DEFAULT_CLIENT_ID } from "../lib/clients";
import { currentLastModified } from "../lib/apiReceipt";
import { connectSmokeClient } from "./smokeApiClient";

const RANGE_START = "2037-04-06";
const RANGE_END = "2037-04-10";
const OUTSIDE_DATE = "2037-04-13";
const CAPACITY_DATE = "2037-04-20";

async function fakeJobs() {
  return prisma.job.findMany({
    where: { externalId: { startsWith: FAKE_DATA_PREFIX } },
    include: {
      customer: true,
      address: true,
      service: true,
      appointment: { include: { technician: { include: { depotAssignments: { include: { depot: true }, orderBy: { effectiveDate: "asc" } } } } } },
      apiOfferSets: { select: { receiptId: true } },
    },
  });
}

function addressKey(job: Awaited<ReturnType<typeof fakeJobs>>[number]): string {
  return [job.address.line1, job.address.line2 ?? "", job.address.city, job.address.state, job.address.postalCode]
    .map((part) => part.toLowerCase())
    .join("|");
}

function coordinateKey(job: Awaited<ReturnType<typeof fakeJobs>>[number]): string {
  return `${job.address.lat?.toFixed(6)}|${job.address.lng?.toFixed(6)}`;
}

async function removeManualFixture(jobId: string) {
  const job = await prisma.job.findUnique({
    where: { id: jobId },
    select: { id: true, customerId: true, addressId: true, appointment: { select: { id: true } } },
  });
  if (!job) return;
  await prisma.$transaction(async (tx) => {
    if (job.appointment) {
      await tx.appointment.delete({ where: { id: job.appointment.id } });
    }
    await tx.job.delete({ where: { id: jobId } });
    await tx.address.delete({ where: { id: job.addressId } });
    await tx.customer.delete({ where: { id: job.customerId } });
  });
}

async function main() {
  const disconnect = await connectSmokeClient(DEFAULT_CLIENT_ID, required(process.env.SCHEDULER_TEST_URL, "SCHEDULER_TEST_URL (a running scheduler)"), "Fake data smoke");
  try { await generated(); } finally { await disconnect(); }
}

async function generated() {
  await clearFakeData(RANGE_START, RANGE_END);
  await clearFakeData(OUTSIDE_DATE, OUTSIDE_DATE);
  await clearFakeData(CAPACITY_DATE, CAPACITY_DATE);

  const outside = await generateFakeData({
    startDate: OUTSIDE_DATE,
    endDate: OUTSIDE_DATE,
    totalCalls: 1,
    seed: 100,
  });
  assert.equal(outside.created, 1);

  const manualSource = await generateFakeData({
    startDate: RANGE_START,
    endDate: RANGE_START,
    totalCalls: 1,
    seed: 200,
  });
  assert.equal(manualSource.created, 1);
  const source = (await fakeJobs()).find(
    (job) => dateFromFakeExternalId(job.externalId) === RANGE_START
  );
  assert.ok(source?.appointment);
  const manualPrefix = `manual-smoke:${Date.now()}`;
  await prisma.$transaction([
    prisma.customer.update({ where: { id: source.customerId }, data: { externalId: `${manualPrefix}:customer` } }),
    prisma.job.update({ where: { id: source.id }, data: { externalId: `${manualPrefix}:job` } }),
    prisma.appointment.update({ where: { id: source.appointment.id }, data: { externalId: `${manualPrefix}:appointment` } }),
  ]);
  const manualJobId = source.id;
  const manualAppointmentId = source.appointment.id;
  const manualDay = { technicianId: source.appointment.technicianId, serviceDate: source.appointment.serviceDate.toISOString().slice(0, 10) };
  const dayKey = `${manualDay.technicianId}|${manualDay.serviceDate}`;

  try {
    const first = await generateFakeData({
      startDate: RANGE_START,
      endDate: RANGE_END,
      totalCalls: 10,
      seed: 424242,
    });
    assert.equal(first.requested, 10);
    assert.equal(first.created + first.skipped, first.requested);
    assert.equal(Object.values(first.byDate).reduce((sum, count) => sum + count, 0), first.created);
    assert.equal(Object.values(first.byService).reduce((sum, count) => sum + count, 0), first.created);

    let jobs = await fakeJobs();
    const inside = jobs.filter((job) => {
      const date = dateFromFakeExternalId(job.externalId);
      return date !== null && date >= RANGE_START && date <= RANGE_END;
    });
    assert.equal(inside.length, first.created);
    assert.equal(jobs.filter((job) => dateFromFakeExternalId(job.externalId) === OUTSIDE_DATE).length, 1);
    assert.ok(inside.every((job) => job.status === "SCHEDULED" && job.appointment));
    assert.ok(inside.every((job) => job.customer.externalId?.startsWith(FAKE_DATA_PREFIX)));
    assert.ok(inside.every((job) => job.appointment?.externalId?.startsWith(FAKE_DATA_PREFIX)));
    assert.ok(inside.every((job) => {
      const appointment = job.appointment;
      return appointment?.technician.depotAssignments
        .filter(assignment => assignment.effectiveDate <= appointment.serviceDate).at(-1)?.depot.metroId === "metro-omaha";
    }));
    assert.ok(inside.every((job) => job.appointment && job.appointment.windowEnd > job.appointment.windowStart), "Each call keeps its offered window");
    assert.ok(inside.every((job) => job.apiOfferSets.filter((set) => set.receiptId !== null).length === 1), "Each call was booked through one confirmed API hold");
    assert.equal(new Set(inside.map(addressKey)).size, inside.length);
    assert.equal(new Set(inside.map(coordinateKey)).size, inside.length);
    const outsideAddresses = new Set(
      jobs.filter((job) => !inside.includes(job)).map(addressKey)
    );
    assert.ok(inside.every((job) => !outsideAddresses.has(addressKey(job))));
    assert.equal(await prisma.job.count({ where: { externalId: { startsWith: FAKE_DATA_PREFIX }, status: "PENDING" } }), 0);
    assert.ok(await prisma.appointment.findUnique({ where: { id: manualAppointmentId } }));
    const removedAppointmentIds = inside.flatMap((job) => job.appointment ? [job.appointment.id] : []);
    const removedJobIds = inside.map((job) => job.id);

    const second = await generateFakeData({
      startDate: RANGE_START,
      endDate: RANGE_END,
      totalCalls: 10,
      seed: 424242,
    });
    assert.equal(second.created + second.skipped, second.requested);
    jobs = await fakeJobs();
    const secondInside = jobs.filter((job) => {
      const date = dateFromFakeExternalId(job.externalId);
      return date !== null && date >= RANGE_START && date <= RANGE_END;
    });
    assert.equal(secondInside.length, second.created);
    assert.equal(jobs.filter((job) => dateFromFakeExternalId(job.externalId) === OUTSIDE_DATE).length, 1);
    assert.equal(await prisma.portalApiOfferSet.count({ where: { jobId: { in: removedJobIds } } }), 0);
    assert.ok(await prisma.appointment.findUnique({ where: { id: manualAppointmentId } }));
    const secondAppointmentIds = secondInside.flatMap((job) => job.appointment ? [job.appointment.id] : []);
    const secondJobIds = secondInside.map((job) => job.id);
    const lastModifiedBefore = (await currentLastModified(prisma, [manualDay])).get(dayKey);

    const cleared = await clearFakeData(RANGE_START, RANGE_END);
    assert.equal(cleared.removed, second.created);
    jobs = await fakeJobs();
    assert.equal(
      jobs.filter((job) => {
        const date = dateFromFakeExternalId(job.externalId);
        return date !== null && date >= RANGE_START && date <= RANGE_END;
      }).length,
      0
    );
    assert.equal(jobs.filter((job) => dateFromFakeExternalId(job.externalId) === OUTSIDE_DATE).length, 1);
    assert.equal(await prisma.portalApiOfferSet.count({ where: { jobId: { in: secondJobIds } } }), 0);
    const remainingFakeCustomers = await prisma.customer.findMany({
      where: { externalId: { startsWith: FAKE_DATA_PREFIX } },
      select: { externalId: true },
    });
    assert.equal(
      remainingFakeCustomers.filter((customer) => {
        const date = dateFromFakeExternalId(customer.externalId);
        return date !== null && date >= RANGE_START && date <= RANGE_END;
      }).length,
      0
    );
    const manual = await prisma.appointment.findUnique({ where: { id: manualAppointmentId } });
    assert.ok(manual);
    const survivors = await prisma.appointment.findMany({
      where: { technicianId: manual.technicianId, serviceDate: manual.serviceDate, cancelledAt: null },
      orderBy: { plannedStart: "asc" },
    });
    assert.deepEqual(survivors.map((appointment) => appointment.sequence), survivors.map((_, index) => index));
    if (secondInside.some((job) => job.appointment?.technicianId === manual.technicianId && job.appointment.serviceDate.getTime() === manual.serviceDate.getTime()))
      assert.notEqual((await currentLastModified(prisma, [manualDay])).get(dayKey), lastModifiedBefore, "The manual visit's day is re-timed without the purged calls");

    const capacity = await generateFakeData({
      startDate: CAPACITY_DATE,
      endDate: CAPACITY_DATE,
      totalCalls: 100,
      seed: 987654321,
    });
    assert.equal(capacity.created + capacity.skipped, capacity.requested);
    assert.ok(capacity.skipped > 0);
    assert.ok(capacity.warnings.length > 0);
    const capacityJobs = (await fakeJobs())
      .filter((job) => dateFromFakeExternalId(job.externalId) === CAPACITY_DATE)
      .sort((left, right) => left.createdAt.getTime() - right.createdAt.getTime());
    assert.equal(new Set(capacityJobs.map(addressKey)).size, capacityJobs.length);
    assert.equal(new Set(capacityJobs.map(coordinateKey)).size, capacityJobs.length);
    console.log(JSON.stringify({ first, second, cleared, capacity }, null, 2));
  } finally {
    await clearFakeData(RANGE_START, RANGE_END);
    await clearFakeData(OUTSIDE_DATE, OUTSIDE_DATE);
    await clearFakeData(CAPACITY_DATE, CAPACITY_DATE);
    await removeManualFixture(manualJobId);
  }
}

main()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
