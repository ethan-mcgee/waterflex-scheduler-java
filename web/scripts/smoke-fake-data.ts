import assert from "node:assert/strict";
import { clearFakeData, generateFakeData } from "../lib/fakeData";
import { dateFromFakeExternalId, FAKE_DATA_PREFIX } from "../lib/fakeDataCore";
import { prisma } from "../lib/prisma";

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
      appointment: { include: { technician: true } },
      slotHolds: true,
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
      await tx.outboundEvent.deleteMany({ where: { aggregateId: job.appointment.id } });
      await tx.appointment.delete({ where: { id: job.appointment.id } });
    }
    await tx.slotHold.deleteMany({ where: { jobId } });
    await tx.job.delete({ where: { id: jobId } });
    await tx.address.delete({ where: { id: job.addressId } });
    await tx.customer.delete({ where: { id: job.customerId } });
  });
}

async function main() {
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
  const scheduleDayBefore = await prisma.scheduleDay.findUnique({
    where: {
      technicianId_serviceDate: {
        technicianId: source.appointment.technicianId,
        serviceDate: source.appointment.serviceDate,
      },
    },
  });

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
    assert.ok(inside.every((job) => job.appointment?.technician.metroId === "metro-omaha"));
    assert.ok(
      inside.every(
        (job) =>
          job.appointment &&
          job.appointment.windowEnd.getTime() - job.appointment.windowStart.getTime() === 2 * 60 * 60 * 1_000
      )
    );
    assert.ok(inside.every((job) => job.slotHolds.every((hold) => hold.releasedAt !== null)));
    assert.equal(new Set(inside.map(addressKey)).size, inside.length);
    assert.equal(new Set(inside.map(coordinateKey)).size, inside.length);
    const outsideAddresses = new Set(
      jobs.filter((job) => !inside.includes(job)).map(addressKey)
    );
    assert.ok(inside.every((job) => !outsideAddresses.has(addressKey(job))));
    assert.equal(await prisma.job.count({ where: { externalId: { startsWith: FAKE_DATA_PREFIX }, status: "PENDING" } }), 0);
    assert.ok(await prisma.appointment.findUnique({ where: { id: manualAppointmentId } }));
    const removedAppointmentIds = inside.flatMap((job) => job.appointment ? [job.appointment.id] : []);
    const removedHoldIds = inside.flatMap((job) => job.slotHolds.map((hold) => hold.id));

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
    assert.equal(await prisma.slotHold.count({ where: { id: { in: removedHoldIds } } }), 0);
    assert.equal(await prisma.outboundEvent.count({ where: { aggregateId: { in: removedAppointmentIds } } }), 0);
    assert.ok(await prisma.appointment.findUnique({ where: { id: manualAppointmentId } }));
    const secondAppointmentIds = secondInside.flatMap((job) => job.appointment ? [job.appointment.id] : []);
    const secondHoldIds = secondInside.flatMap((job) => job.slotHolds.map((hold) => hold.id));

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
    assert.equal(await prisma.slotHold.count({ where: { id: { in: secondHoldIds } } }), 0);
    assert.equal(await prisma.outboundEvent.count({ where: { aggregateId: { in: secondAppointmentIds } } }), 0);
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
      where: { technicianId: manual.technicianId, serviceDate: manual.serviceDate },
      orderBy: { plannedStart: "asc" },
    });
    assert.deepEqual(survivors.map((appointment) => appointment.sequence), survivors.map((_, index) => index));
    const scheduleDayAfter = await prisma.scheduleDay.findUnique({
      where: {
        technicianId_serviceDate: {
          technicianId: manual.technicianId,
          serviceDate: manual.serviceDate,
        },
      },
    });
    assert.ok(scheduleDayAfter && scheduleDayBefore && scheduleDayAfter.version > scheduleDayBefore.version);

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
