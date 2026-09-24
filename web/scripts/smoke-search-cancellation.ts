import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { PrismaClient } from "@prisma/client";
import { offersResponse, required, selection, success } from "../lib/contracts";
import { initialAvailability } from "../lib/technicianAvailability";
import { technicianColor } from "../lib/technicianColor";

const prisma = new PrismaClient();
if (new URL(process.env.DATABASE_URL ?? "").pathname !== "/waterflex_test") throw new Error("Use isolated waterflex_test only.");
const base = process.env.SCHEDULER_TEST_URL ?? "http://127.0.0.1:18000";
const peer = process.env.SCHEDULER_CANCEL_URL ?? base;
const id = `cancel-test-${randomUUID()}`;
const jobId = `${id}-job`;
const otherJobId = `${id}-other-job`;
async function post(url: string, path: string, body: unknown) {
  const response = await fetch(`${url}${path}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), signal: AbortSignal.timeout(6000) });
  const value: unknown = await response.json();
  assert.equal(response.status, 200, JSON.stringify(value)); return value;
}
async function until(condition: () => Promise<boolean>, milliseconds = 8000) {
  const end = performance.now() + milliseconds;
  while (!await condition()) {
    assert.ok(performance.now() < end, "Lifecycle condition exceeded deadline");
    await new Promise(resolve => setTimeout(resolve, 30));
  }
}
async function expireJob(targetJobId: string) {
  const holds = await prisma.slotHold.findMany({ where: { jobId: targetJobId, releasedAt: null } });
  assert.ok(holds.length > 0);
  const expiresAt = new Date(Date.now() + 700);
  await prisma.$transaction(async tx => {
    await tx.bookingOfferSet.updateMany({ where: { jobId: targetJobId, supersededAt: null }, data: { expiresAt } });
    await tx.bookingOffer.updateMany({ where: { id: { in: holds.map(hold => hold.offerToken) } }, data: { expiresAt } });
    await tx.slotHold.updateMany({ where: { id: { in: holds.map(hold => hold.id) } }, data: { expiresAt } });
    for (const hold of holds) await tx.$executeRaw`UPDATE reservation_arrangement SET state=jsonb_set(state, ARRAY['holds',${hold.id},'expiresAt'],to_jsonb(${expiresAt.toISOString()}::text)) WHERE "metroId"=${id} AND "serviceDate"=${hold.serviceDate} AND state->'holds' ? ${hold.id}`;
  });
  await until(async () => await prisma.slotHold.count({ where: { id: { in: holds.map(hold => hold.id) }, releasedAt: null } }) === 0, 15000);
  const arrangements = await prisma.reservationArrangement.findMany({ where: { metroId: id } });
  for (const arrangement of arrangements) for (const hold of holds)
    assert.ok(!JSON.stringify(arrangement.state).includes(JSON.stringify(hold.id)), "Every sibling date is revalidated and expired placeholders are removed");
}
async function main() {
  await prisma.metro.create({ data: { id, name: id, timezone: "America/Chicago" } });
  await prisma.dealership.create({ data: { id, name: id } });
  await prisma.depot.create({ data: { id, metroId: id, dealershipId: id, name: id, lat: 43.735, lng: 7.420,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  await prisma.serviceCatalog.create({ data: { id, code: id, name: id, estDurationMin: 30 } });
  await prisma.technician.create({ data: { id, name: id, color: technicianColor(id), homeLat: 43.735, homeLng: 7.420,
    shiftStartMin: 480, shiftEndMin: 1020, maxDailyMinutes: 540, maxOvertimeMinutes: 0,
    availabilityVersions: initialAvailability(480, 1020), qualifications: { create: { serviceId: id } },
    depotAssignments: { create: { depotId: id, effectiveDate: new Date("1900-01-01T00:00:00Z") } } } });
  await prisma.customer.create({ data: { id, firstName: "Cancellation", lastName: "Fixture", email: "cancel@example.invalid", phone: "0000000000" } });
  await prisma.address.create({ data: { id, customerId: id, line1: "Fixture", city: "Monaco", state: "MC", postalCode: "98000", lat: 43.748, lng: 7.438 } });
  await prisma.job.create({ data: { id: jobId, customerId: id, addressId: id, serviceId: id, durationMin: 30 } });
  const search = (searchRequestId: string) => post(base, "/v1/offers", { jobId, searchRequestId, refresh: true, deadlineEpochMs: Date.now() + 5000 }).then(value => offersResponse.parse(value));
  const cancel = (searchRequestId: string) => post(peer, "/v1/offers/cancel-search", { jobId, searchRequestId }).then(value => success.parse(value));
  const early = randomUUID(); await cancel(early);
  assert.equal((await search(early)).search.outcome, "SEARCH_INCOMPLETE");
  assert.equal(await prisma.slotHold.count({ where: { jobId } }), 0);

  // Block publication with a real database job lock, cancel through the peer, then release the lock.
  const during = randomUUID();
  let releaseLock: () => void = () => { throw new Error("Lock not ready"); };
  let ready: () => void = () => { throw new Error("Readiness not initialized"); };
  const acquired = new Promise<void>(resolve => { ready = resolve; });
  const release = new Promise<void>(resolve => { releaseLock = resolve; });
  const blocking = prisma.$transaction(async tx => { await tx.$queryRaw`SELECT id FROM job WHERE id=${jobId} FOR NO KEY UPDATE`; ready(); await release; }, { timeout: 10000 });
  await acquired;
  const pending = search(during);
  try {
    await until(async () => await prisma.bookingSearchRequest.count({ where: { id: during } }) === 1, 2000);
    await cancel(during);
  } finally { releaseLock(); await blocking; }
  assert.equal((await pending).offers.length, 0);
  assert.equal(await prisma.slotHold.count({ where: { jobId } }), 0, "Cancelled work cannot publish after lock release");

  // No acknowledgement models a lost gateway response or gateway crash.
  const lost = randomUUID();
  assert.ok((await search(lost)).offers.length > 0);
  await until(async () => (await prisma.bookingSearchRequest.findUniqueOrThrow({ where: { id: lost } })).cleanedAt !== null);
  assert.equal(await prisma.slotHold.count({ where: { jobId, releasedAt: null } }), 0);

  const delivered = randomUUID(); const offered = await search(delivered);
  const selected = required(offered.offers[0]);
  success.parse(await post(peer, "/v1/offers/acknowledge-search", { jobId, searchRequestId: delivered }));
  await until(async () => (await prisma.bookingSearchRequest.findUniqueOrThrow({ where: { id: delivered } })).deadlineAt < new Date());
  assert.equal((await prisma.bookingSearchRequest.findUniqueOrThrow({ where: { id: delivered } })).cancelledAt, null);
  assert.ok(await prisma.slotHold.count({ where: { jobId, releasedAt: null } }) > 0, "Acknowledged offers retain their ten-minute reservation");
  await cancel(delivered);
  await until(async () => (await prisma.bookingSearchRequest.findUniqueOrThrow({ where: { id: delivered } })).cleanedAt !== null);
  assert.equal(await prisma.slotHold.count({ where: { offerToken: selected.offerId, releasedAt: null } }), 0);

  // Two independent customers share a date. Refresh and expiry of one must preserve the other.
  await prisma.customer.create({ data: { id: otherJobId, firstName: "Other", lastName: "Fixture", email: "other@example.invalid", phone: "0000000000" } });
  await prisma.address.create({ data: { id: otherJobId, customerId: otherJobId, line1: "Other fixture", city: "Monaco", state: "MC", postalCode: "98000", lat: 43.748, lng: 7.438 } });
  await prisma.job.create({ data: { id: otherJobId, customerId: otherJobId, addressId: otherJobId, serviceId: id, durationMin: 30 } });
  const firstCustomer = offersResponse.parse(await post(base, "/v1/offers", { jobId, refresh: true }));
  const secondCustomer = offersResponse.parse(await post(base, "/v1/offers", { jobId: otherJobId }));
  assert.ok(secondCustomer.offers.some(offer => firstCustomer.offers.some(first => first.date === offer.date)), "Customers reserve a common service date");
  const otherHolds = await prisma.slotHold.findMany({ where: { jobId: otherJobId, releasedAt: null } });
  assert.ok(otherHolds.length > 0);
  const refreshed = offersResponse.parse(await post(base, "/v1/offers", { jobId, refresh: true }));
  assert.ok(refreshed.offers.length > 0);
  assert.equal(await prisma.slotHold.count({ where: { id: { in: otherHolds.map(hold => hold.id) }, releasedAt: null } }), otherHolds.length);
  await expireJob(jobId);
  assert.equal(await prisma.slotHold.count({ where: { id: { in: otherHolds.map(hold => hold.id) }, releasedAt: null } }), otherHolds.length);
  const chosen = selection.parse(await post(peer, "/v1/offers/select", { jobId: otherJobId, offerId: required(secondCustomer.offers[0]).offerId }));
  const repeated = selection.parse(await post(peer, "/v1/offers/select", { jobId: otherJobId, offerId: required(secondCustomer.offers[0]).offerId }));
  assert.equal(repeated.appointmentId, chosen.appointmentId);
  await post(peer, "/v1/appointments/cancel", { appointment_id: chosen.appointmentId, reason: "Completed overlapping-reservation fixture" });
  // Force conservative sibling alternatives onto different dates, then shorten the fixture expiry
  // consistently in the database and its durable arrangement snapshot instead of waiting ten minutes.
  const previousOffer = await prisma.bookingOffer.findUniqueOrThrow({ where: { id: selected.offerId } });
  const singleVisitMinutes = required(previousOffer.incrementalRegularMinutes, "Fixture paid route minutes");
  assert.ok(Number.isInteger(singleVisitMinutes) && singleVisitMinutes > 0);
  await prisma.technician.update({ where: { id }, data: { maxDailyMinutes: singleVisitMinutes } });
  await prisma.technicianAvailabilityDay.updateMany({ where: { version: { technicianId: id }, available: true }, data: { shiftEndMin: 480 + Math.max(120, singleVisitMinutes) } });
  const expiring = offersResponse.parse(await post(base, "/v1/offers", { jobId, refresh: true }));
  assert.ok(new Set(expiring.offers.map(offer => offer.date)).size > 1, `Expiry covers multiple reserved dates: ${JSON.stringify(expiring)}`);
  await expireJob(jobId);
  console.log("Cancellation before admission, during locked publication, lost response cleanup, cross-instance acknowledgement and validated release passed.");
  console.log("Expiry independently revalidates every affected sibling date and removes durable placeholders.");
  console.log("Overlapping customers survive refresh and expiry, then confirm idempotently through the peer instance.");
}
main().finally(async () => {
  await prisma.reservationArrangement.deleteMany({ where: { metroId: id } });
  const jobs = { in: [jobId, otherJobId] };
  await prisma.appointment.deleteMany({ where: { jobId: jobs } });
  await prisma.slotHold.deleteMany({ where: { jobId: jobs } });
  await prisma.bookingOffer.deleteMany({ where: { jobId: jobs } });
  await prisma.bookingOfferSet.deleteMany({ where: { jobId: jobs } });
  await prisma.job.deleteMany({ where: { id: jobs } });
  await prisma.address.deleteMany({ where: { id: { in: [id, otherJobId] } } });
  await prisma.customer.deleteMany({ where: { id: { in: [id, otherJobId] } } });
  await prisma.scheduleDay.deleteMany({ where: { technicianId: id } });
  await prisma.technicianQualification.deleteMany({ where: { technicianId: id } });
  await prisma.technician.deleteMany({ where: { id } }); await prisma.serviceCatalog.deleteMany({ where: { id } });
  await prisma.depot.deleteMany({ where: { id } }); await prisma.dealership.deleteMany({ where: { id } }); await prisma.metro.deleteMany({ where: { id } });
  await prisma.$disconnect();
}).catch(error => { console.error(error); process.exitCode = 1; });
