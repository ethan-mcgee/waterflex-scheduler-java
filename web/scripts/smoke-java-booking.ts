import { PrismaClient } from "@prisma/client";
import { randomUUID } from "node:crypto";
import assert from "node:assert/strict";

const prisma = new PrismaClient();
const base = process.env.SCHEDULER_TEST_URL ?? "http://127.0.0.1:18000";
const suffix = randomUUID();

async function post(path: string, body: unknown) {
  const response = await fetch(`${base}${path}`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
  const payload = await response.json();
  assert.equal(response.status, 200, `${path}: ${JSON.stringify(payload)}`);
  return payload;
}

async function main() {
  // Only the fixture technician can qualify, so seeded Omaha routes cannot affect offers.
  const metro = await prisma.metro.create({ data: { id: `smoke-metro-${suffix}`, name: "Booking fixture", timezone: "America/Chicago" } });
  const service = await prisma.serviceCatalog.create({ data: { code: `BOOK_${suffix}`, name: "Booking fixture", estDurationMin: 50 } });
  const techId = `smoke-tech-${suffix}`;
  const customerId = `smoke-customer-${suffix}`;
  const addressId = `smoke-address-${suffix}`;
  const jobId = `smoke-job-${suffix}`;
  const collisionJobs = [`smoke-collision-a-${suffix}`, `smoke-collision-b-${suffix}`];
  try {
    await prisma.technician.create({ data: {
      id: techId, metroId: metro.id, name: "Monaco fixture technician",
      homeLat: 43.735, homeLng: 7.420, shiftStartMin: 480, shiftEndMin: 1020,
      maxDailyMinutes: 600, maxOvertimeMinutes: 60,
      qualifications: { create: { serviceId: service.id } },
    } });
    await prisma.customer.create({ data: { id: customerId, firstName: "Smoke", lastName: "Test", email: "smoke@example.invalid", phone: "0000000000" } });
    await prisma.address.create({ data: { id: addressId, customerId, line1: "Fixture address", city: "Monaco", state: "MC", postalCode: "98000", lat: 43.748, lng: 7.438 } });
    await prisma.job.create({ data: { id: jobId, customerId, addressId, serviceId: service.id, durationMin: 50, bookingRequestId: suffix } });

    const offered = await post("/v1/offers", { jobId });
    assert.equal(offered.jobId, jobId);
    assert.ok(offered.offers.length > 0 && offered.offers.length <= 4);
    assert.equal(await prisma.slotHold.count({ where: { jobId } }), 0, "Offers must not reserve capacity");
    const selected = await post("/v1/offers/select", { jobId, offerId: offered.offers[0].offerId });
    assert.ok(selected.holdId);
    assert.equal(await prisma.slotHold.count({ where: { jobId } }), 1);
    const confirmed = await post("/v1/holds/confirm", { holdId: selected.holdId });
    assert.ok(confirmed.appointmentId);
    assert.equal(confirmed.windowStart, offered.offers[0].windowStart);
    assert.equal(confirmed.windowEnd, offered.offers[0].windowEnd);
    const retry = await post("/v1/holds/confirm", { holdId: selected.holdId });
    assert.equal(retry.appointmentId, confirmed.appointmentId);
    assert.equal(await prisma.appointment.count({ where: { jobId } }), 1);
    for (const [index, collisionJob] of collisionJobs.entries()) {
      await prisma.job.create({ data: { id: collisionJob, customerId, addressId, serviceId: service.id,
        durationMin: 350, bookingRequestId: `${suffix}-${index}` } });
    }
    const [first, second] = await Promise.all(collisionJobs.map((id) => post("/v1/offers", { jobId: id })));
    assert.ok(first.offers.length > 0 && second.offers.length > 0);
    assert.equal(first.offers[0].windowStart, second.offers[0].windowStart);
    const selections = await Promise.all([first, second].map((offer, index) => fetch(`${base}/v1/offers/select`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ jobId: collisionJobs[index], offerId: offer.offers[0].offerId }),
    })));
    assert.deepEqual(selections.map((r) => r.status).sort(), [200, 409], "Concurrent selections must reserve capacity once");
    console.log("Java offer, select, confirm, and idempotent retry passed");
    console.log("Concurrent competing selections passed");
  } finally {
    await prisma.slotHold.deleteMany({ where: { jobId: { in: collisionJobs } } });
    await prisma.bookingOffer.deleteMany({ where: { jobId: { in: collisionJobs } } });
    await prisma.job.deleteMany({ where: { id: { in: collisionJobs } } });
    await prisma.appointment.deleteMany({ where: { jobId } });
    await prisma.slotHold.deleteMany({ where: { jobId } });
    await prisma.bookingOffer.deleteMany({ where: { jobId } });
    await prisma.job.deleteMany({ where: { id: jobId } });
    await prisma.address.deleteMany({ where: { id: addressId } });
    await prisma.customer.deleteMany({ where: { id: customerId } });
    await prisma.technicianQualification.deleteMany({ where: { technicianId: techId } });
    await prisma.scheduleDay.deleteMany({ where: { technicianId: techId } });
    await prisma.technician.deleteMany({ where: { id: techId } });
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
    await prisma.$disconnect();
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
