import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { PrismaClient } from "@prisma/client";
import { initialAvailability } from "../lib/technicianAvailability";
import { technicianColor } from "../lib/technicianColor";
import { purgeHasReservations } from "../lib/reservationGuards";
import { DEFAULT_CLIENT_ID } from "../lib/clients";

const prisma = new PrismaClient();
const base = process.env.SCHEDULER_TEST_URL ?? "http://127.0.0.1:18000";
const suffix = randomUUID();
const day = new Date("2099-10-05T00:00:00Z");
const start = new Date("2099-10-05T14:00:00Z");
const holdStart = new Date("2099-10-05T16:00:00Z");
const ids = { metro: `reservation-metro-${suffix}`, dealership: `reservation-dealer-${suffix}`,
  depot: `reservation-depot-${suffix}`, target: `reservation-target-${suffix}`, a: `reservation-a-${suffix}`, b: `reservation-b-${suffix}`,
  service: `reservation-service-${suffix}`, movedService: `reservation-moved-service-${suffix}`, customer: `reservation-customer-${suffix}`,
  address: `reservation-address-${suffix}`, job: `reservation-job-${suffix}`, existingJob: `reservation-existing-${suffix}`,
  appointment: `reservation-appointment-${suffix}`, offer: `reservation-offer-${suffix}`, hold: `reservation-hold-${suffix}`, arrangement: `reservation-arrangement-${suffix}` };

async function post(path: string, body: unknown, expected: number) {
  const response = await fetch(`${base}${path}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const detail = await response.text();
  assert.equal(response.status, expected, `${path}: ${detail}`);
}

async function main() {
  const database = new URL(process.env.DATABASE_URL ?? "");
  assert.equal(database.pathname, "/waterflex_test", "Use isolated waterflex_test data");
  try {
    await prisma.metro.create({ data: { id: ids.metro, name: "Reservation guards", timezone: "America/Chicago" } });
    await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, id: ids.dealership, name: "Reservation guards" } });
    for (const id of [ids.depot, ids.target]) await prisma.depot.create({ data: { id, metroId: ids.metro, dealershipId: ids.dealership,
      name: id, lat: 43.735, lng: 7.420, endpointPolicies: { create: { effectiveDate: new Date("1900-01-01"), departure: "HOME", returnTo: "HOME" } } } });
    for (const id of [ids.service, ids.movedService]) await prisma.serviceCatalog.create({ data: { id, code: id, name: id, estDurationMin: 50 } });
    for (const id of [ids.a, ids.b]) await prisma.technician.create({ data: { clientId: DEFAULT_CLIENT_ID, id, name: id, color: technicianColor(id), homeLat: 43.735, homeLng: 7.420,
      shiftStartMin: 480, shiftEndMin: 1020, maxDailyMinutes: 600, maxOvertimeMinutes: 60, availabilityVersions: initialAvailability(480, 1020),
      depotAssignments: { create: { depotId: ids.depot, effectiveDate: new Date("1900-01-01") } },
      qualifications: { create: (id === ids.a ? [ids.service, ids.movedService] : [ids.movedService]).map(serviceId => ({ serviceId })) } } });
    await prisma.customer.create({ data: { clientId: DEFAULT_CLIENT_ID, id: ids.customer, firstName: "Reservation", lastName: "Test", email: "reservation@example.invalid", phone: "0000000000" } });
    await prisma.address.create({ data: { id: ids.address, customerId: ids.customer, line1: "Fixture", city: "Monaco", state: "MC", postalCode: "98000", lat: 43.748, lng: 7.438 } });
    for (const id of [ids.job, ids.existingJob]) await prisma.job.create({ data: { id, customerId: ids.customer, addressId: ids.address,
      serviceId: id === ids.job ? ids.service : ids.movedService, durationMin: 50, bookingRequestId: id, status: id === ids.job ? "PENDING" : "SCHEDULED" } });
    await prisma.appointment.create({ data: { id: ids.appointment, jobId: ids.existingJob, technicianId: ids.a, serviceDate: day,
      windowStart: start, windowEnd: new Date(start.getTime() + 7200000), plannedStart: start, plannedEnd: new Date(start.getTime() + 3000000), sequence: 0 } });
    const expiresAt = new Date(Date.now() + 600000);
    await prisma.bookingOffer.create({ data: { id: ids.offer, jobId: ids.job, serviceDate: day, windowStart: holdStart, windowEnd: new Date(holdStart.getTime() + 7200000), expiresAt } });
    await prisma.slotHold.create({ data: { id: ids.hold, offerToken: ids.offer, jobId: ids.job, technicianId: ids.a, serviceDate: day,
      windowStart: holdStart, windowEnd: new Date(holdStart.getTime() + 7200000), plannedStart: holdStart, plannedEnd: new Date(holdStart.getTime() + 3000000),
      insertPosition: 1, locationLat: 43.748, locationLng: 7.438, expiresAt } });
    await prisma.reservationArrangement.create({ data: { id: ids.arrangement, metroId: ids.metro, serviceDate: day, version: 1,
      state: { format: 1, configurationFingerprint: "fixture", routingIdentity: "ci-monaco-omaha-car-v2", scheduleVersions: { [ids.a]: 0, [ids.b]: 0 },
        routes: { [ids.a]: [ids.hold], [ids.b]: [ids.appointment] }, holds: { [ids.hold]: { jobId: ids.job, offerId: ids.offer, expiresAt: expiresAt.toISOString(), overtimeAuthorized: false } },
        segments: { [ids.a]: [{ departure: holdStart.toISOString(), returnedAt: new Date(holdStart.getTime() + 3600000).toISOString(), visitIds: [ids.hold] }],
          [ids.b]: [{ departure: start.toISOString(), returnedAt: new Date(start.getTime() + 3600000).toISOString(), visitIds: [ids.appointment] }] } } } });
    await prisma.reservationDependency.create({ data: { arrangementId: ids.arrangement, holdId: ids.hold, technicianId: ids.b, serviceId: ids.movedService, serviceDate: day } });
    assert.equal(await prisma.slotHold.count({ where: { technicianId: ids.b } }), 0, "The dependency must protect a technician without a directly assigned hold");
    await post("/v1/dispatch/qualification", { technicianId: ids.b, serviceId: ids.movedService, qualified: false }, 409);
    await post("/v1/dispatch/availability", { technicianId: ids.b, date: "2099-10-05", available: false }, 409);
    await post(`/v1/technicians/${ids.b}/depot-assignments`, { depotId: ids.target, effectiveDate: "2099-10-05" }, 409);
    assert.equal(await prisma.$transaction(tx => purgeHasReservations(tx, [ids.existingJob], [{ technicianId: ids.b, serviceDate: day }])), true);
    assert.equal(await prisma.technicianQualification.count({ where: { technicianId: ids.b, serviceId: ids.movedService } }), 1);
    await prisma.slotHold.update({ where: { id: ids.hold }, data: { releasedAt: new Date() } });
    await post("/v1/dispatch/qualification", { technicianId: ids.b, serviceId: ids.movedService, qualified: false }, 200);
    await post("/v1/dispatch/availability", { technicianId: ids.b, date: "2099-10-05", available: false }, 200);
    assert.equal(await prisma.$transaction(tx => purgeHasReservations(tx, [ids.existingJob], [{ technicianId: ids.b, serviceDate: day }])), false);
    console.log("Pending reservation reassignment guards and released-hold cleanup passed");
  } finally {
    await prisma.reservationArrangement.deleteMany({ where: { id: ids.arrangement } });
    await prisma.appointment.deleteMany({ where: { id: ids.appointment } });
    await prisma.slotHold.deleteMany({ where: { id: ids.hold } });
    await prisma.bookingOffer.deleteMany({ where: { id: ids.offer } });
    await prisma.job.deleteMany({ where: { id: { in: [ids.job, ids.existingJob] } } });
    await prisma.address.deleteMany({ where: { id: ids.address } });
    await prisma.customer.deleteMany({ where: { id: ids.customer } });
    await prisma.technicianShiftOverride.deleteMany({ where: { technicianId: { in: [ids.a, ids.b] } } });
    await prisma.technicianQualification.deleteMany({ where: { technicianId: { in: [ids.a, ids.b] } } });
    await prisma.scheduleDay.deleteMany({ where: { technicianId: { in: [ids.a, ids.b] } } });
    await prisma.technician.deleteMany({ where: { id: { in: [ids.a, ids.b] } } });
    await prisma.serviceCatalog.deleteMany({ where: { id: { in: [ids.service, ids.movedService] } } });
    await prisma.depot.deleteMany({ where: { id: { in: [ids.depot, ids.target] } } });
    await prisma.dealership.deleteMany({ where: { id: ids.dealership } });
    await prisma.metro.deleteMany({ where: { id: ids.metro } });
    await prisma.$disconnect();
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
