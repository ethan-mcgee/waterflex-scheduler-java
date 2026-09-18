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
    assert.equal(await prisma.slotHold.count({ where: { jobId, releasedAt: null } }), offered.offers.length, "Every offer reserves capacity");
    const reused = await post("/v1/offers", { jobId });
    assert.deepEqual(reused.offers.map((offer: { offerId: string }) => offer.offerId).sort(), offered.offers.map((offer: { offerId: string }) => offer.offerId).sort());
    const refreshed = await post("/v1/offers", { jobId, refresh: true });
    assert.ok(refreshed.offers.length > 0);
    assert.notEqual(refreshed.offers[0].offerId, offered.offers[0].offerId);
    assert.equal(await prisma.slotHold.count({ where: { jobId, releasedAt: null } }), refreshed.offers.length);
    const staleSelection = await fetch(`${base}/v1/offers/select`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ jobId, offerId: offered.offers[0].offerId }) });
    assert.equal(staleSelection.status, 409);
    const selected = await post("/v1/offers/select", { jobId, offerId: refreshed.offers[0].offerId });
    assert.ok(selected.holdId);
    assert.ok(selected.appointmentId, "Selection commits the appointment");
    assert.equal(await prisma.slotHold.count({ where: { jobId, releasedAt: null } }), 0);
    const confirmed = await post("/v1/holds/confirm", { holdId: selected.holdId });
    assert.equal(confirmed.appointmentId, selected.appointmentId);
    assert.equal(confirmed.windowStart, refreshed.offers[0].windowStart);
    assert.equal(confirmed.windowEnd, refreshed.offers[0].windowEnd);
    const retry = await post("/v1/holds/confirm", { holdId: selected.holdId });
    assert.equal(retry.appointmentId, confirmed.appointmentId);
    assert.equal(await prisma.appointment.count({ where: { jobId } }), 1);
    const repeatedSelection = await post("/v1/offers/select", { jobId, offerId: refreshed.offers[0].offerId });
    assert.equal(repeatedSelection.appointmentId, selected.appointmentId);
    if (refreshed.offers.length > 1) {
      const differentSelection = await fetch(`${base}/v1/offers/select`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ jobId, offerId: refreshed.offers[1].offerId }) });
      assert.equal(differentSelection.status, 409);
    }
    const cancelled = await post("/v1/appointments/cancel", { appointment_id: selected.appointmentId, reason: "Fixture cancellation" });
    assert.equal(cancelled.success, true);
    const cancelledAgain = await post("/v1/appointments/cancel", { appointment_id: selected.appointmentId, reason: "Fixture cancellation" });
    assert.equal(cancelledAgain.alreadyCancelled, true);
    assert.ok((await prisma.appointment.findUniqueOrThrow({ where: { id: selected.appointmentId } })).cancelledAt);
    console.log("Java reservations, refresh, selection, legacy confirmation, and cancellation passed");
  } finally {
    await prisma.appointment.deleteMany({ where: { jobId } });
    await prisma.slotHold.deleteMany({ where: { jobId } });
    await prisma.bookingOffer.deleteMany({ where: { jobId } });
    await prisma.bookingOfferSet.deleteMany({ where: { jobId } });
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
