import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { PrismaClient } from "@prisma/client";
import { z } from "zod";
import { initialAvailability } from "../lib/technicianAvailability";
import { DEFAULT_CLIENT_ID } from "../lib/clients";

const prisma = new PrismaClient();
const base = process.env.SCHEDULER_TEST_URL ?? "http://127.0.0.1:18000";
const suffix = randomUUID();
const epoch = new Date("1900-01-01T00:00:00Z");
function dateAfter(days: number) {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: "America/Chicago", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
  const date = new Date(`${parts}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  while (date.getUTCDay() === 0 || date.getUTCDay() === 6) date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}
async function post(path: string, body: unknown) {
  const response = await fetch(`${base}${path}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const payload: unknown = await response.json();
  return { status: response.status, payload };
}
async function endpoint(metroId: string, date: string, techId: string) {
  const response = await fetch(`${base}/v1/dispatch/geometry?metro_id=${encodeURIComponent(metroId)}&date=${date}`);
  assert.equal(response.status, 200);
  const payload = z.object({ endpoints: z.array(z.object({ technicianId: z.string(), departureLat: z.number(), returnLat: z.number() })) }).parse(await response.json());
  return payload.endpoints.find(item => item.technicianId === techId);
}

async function main() {
  const database = process.env.DATABASE_URL ? new URL(process.env.DATABASE_URL).pathname.slice(1) : "";
  if (!new Set(["waterflex_test", "waterflex_dealership_test"]).has(database)) throw new Error("Requires an isolated test database");
  const dealer = await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, name: `Multi metro fixture ${suffix}` } });
  const lincoln = await prisma.metro.create({ data: { name: "Lincoln fixture", timezone: "America/Chicago" } });
  const omaha = await prisma.metro.create({ data: { name: "Omaha fixture", timezone: "America/Chicago" } });
  const first = await prisma.depot.create({ data: { dealershipId: dealer.id, metroId: lincoln.id, name: "Lincoln first", lat: 43.735, lng: 7.420,
    endpointPolicies: { create: { effectiveDate: epoch, departure: "DEPOT", returnTo: "DEPOT" } } } });
  const second = await prisma.depot.create({ data: { dealershipId: dealer.id, metroId: lincoln.id, name: "Lincoln second", lat: 43.748, lng: 7.438,
    endpointPolicies: { create: { effectiveDate: epoch, departure: "DEPOT", returnTo: "HOME" } } } });
  const third = await prisma.depot.create({ data: { dealershipId: dealer.id, metroId: omaha.id, name: "Omaha", lat: 43.748, lng: 7.438,
    endpointPolicies: { create: { effectiveDate: epoch, departure: "DEPOT", returnTo: "HOME" } } } });
  const service = await prisma.serviceCatalog.create({ data: { code: `MULTI_${suffix}`, name: "Multi depot fixture", estDurationMin: 50 } });
  const tech = await prisma.technician.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Multi depot fixture", color: "#2563eb", homeLat: 43.735, homeLng: 7.420,
    shiftStartMin: 480, shiftEndMin: 1020, availabilityVersions: initialAvailability(480, 1020),
    depotAssignments: { create: { effectiveDate: epoch, depotId: first.id } }, qualifications: { create: { serviceId: service.id } } } });
  const customer = await prisma.customer.create({ data: { clientId: DEFAULT_CLIENT_ID, firstName: "Multi", lastName: "Depot", email: `${suffix}@example.invalid`, phone: "0000000000" } });
  const address = await prisma.address.create({ data: { customerId: customer.id, line1: "Fixture", city: "Monaco", state: "MC", postalCode: "98000", lat: 43.748, lng: 7.438 } });
  const job = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: 50, status: "SCHEDULED" } });
  const holdJob = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: 50 } });
  try {
    const sameDay = dateAfter(5), crossDay = dateAfter(25), beforeDay = dateAfter(2);
    assert.equal((await post(`/v1/depots/${first.id}/details`, { name: "Renamed Lincoln" })).status, 200);
    assert.equal((await prisma.depot.findUniqueOrThrow({ where: { id: first.id } })).lat, 43.735);
    const updatedLocation = { name: "Renamed Lincoln", address: { line1: "1 Fixture St", city: "Monaco", state: "MC", postalCode: "98000" },
      confirmedPin: { lat: 43.735, lng: 7.420 }, candidate: { lat: 43.735, lng: 7.420, precision: "ROOFTOP" } };
    assert.equal((await post(`/v1/depots/${first.id}/details`, updatedLocation)).status, 200);
    assert.equal((await prisma.depot.findUniqueOrThrow({ where: { id: first.id } })).addressLine1, "1 Fixture St");
    assert.equal((await post(`/v1/depots/${first.id}/details`, { ...updatedLocation, confirmedPin: { lat: null, lng: 7.420 } })).status, 400);
    const futurePolicyDate = dateAfter(35);
    await prisma.depotEndpointPolicy.create({ data: { depotId: first.id, effectiveDate: new Date(`${futurePolicyDate}T00:00:00Z`), departure: "HOME", returnTo: "HOME" } });
    const policyEdit = await post(`/v1/depots/${first.id}/policy`, { departure: "DEPOT", returnTo: "HOME" });
    assert.equal(policyEdit.status, 200, JSON.stringify(policyEdit.payload));
    assert.equal(z.object({ success: z.literal(true), effectiveDate: z.string() }).parse(policyEdit.payload).effectiveDate, futurePolicyDate);
    assert.equal(await prisma.depotEndpointPolicy.count({ where: { depotId: first.id } }), 2);
    assert.equal((await prisma.depotEndpointPolicy.findUniqueOrThrow({ where: { depotId_effectiveDate: {
      depotId: first.id, effectiveDate: new Date(`${futurePolicyDate}T00:00:00Z`) } } })).returnTo, "HOME");
    const chicagoNow = new Date();
    const chicagoDate = new Intl.DateTimeFormat("en-CA", { timeZone: "America/Chicago", year: "numeric", month: "2-digit", day: "2-digit" }).format(chicagoNow);
    const chicagoHour = Number(new Intl.DateTimeFormat("en-US", { timeZone: "America/Chicago", hour: "2-digit", hourCycle: "h23" }).format(chicagoNow));
    const ordinaryEdit = await post(`/v1/depots/${second.id}/policy`, { departure: "DEPOT", returnTo: "HOME" });
    assert.equal(ordinaryEdit.status, 200, JSON.stringify(ordinaryEdit.payload));
    const savedDate = z.object({ effectiveDate: z.string() }).parse(ordinaryEdit.payload).effectiveDate;
    const expectedDate = new Date(`${chicagoDate}T00:00:00Z`);
    if (chicagoHour >= 6) expectedDate.setUTCDate(expectedDate.getUTCDate() + 1);
    assert.equal(savedDate, expectedDate.toISOString().slice(0, 10));
    assert.equal((await endpoint(lincoln.id, beforeDay, tech.id))?.departureLat, first.lat);
    const sameStart = new Date(`${sameDay}T15:00:00Z`), sameEnd = new Date(`${sameDay}T19:00:00Z`);
    const sameAppointment = await prisma.appointment.create({ data: { jobId: job.id, technicianId: tech.id, serviceDate: new Date(`${sameDay}T00:00:00Z`),
      windowStart: sameStart, windowEnd: sameEnd, plannedStart: sameStart, plannedEnd: new Date(sameStart.getTime() + 50 * 60_000), sequence: 0 } });
    const preview = await post("/v1/optimize/day/preview", { metro_id: lincoln.id, date: sameDay });
    assert.equal(preview.status, 200, JSON.stringify(preview.payload));
    const run = z.object({ run_id: z.string(), configuration_version: z.string() }).parse(preview.payload);
    const sameHold = await prisma.slotHold.create({ data: { jobId: holdJob.id, technicianId: tech.id, serviceDate: new Date(`${sameDay}T00:00:00Z`),
      offerToken: `same-${suffix}`, windowStart: sameStart, windowEnd: sameEnd, plannedStart: new Date(sameStart.getTime() + 60 * 60_000),
      plannedEnd: new Date(sameStart.getTime() + 110 * 60_000), insertPosition: 1, expiresAt: new Date(Date.now() + 600_000),
      locationLat: 43.748, locationLng: 7.438 } });
    const same = await post(`/v1/technicians/${tech.id}/depot-assignments`, { depotId: second.id, effectiveDate: sameDay });
    assert.equal(same.status, 200, JSON.stringify(same.payload));
    const replanned = await prisma.appointment.findUniqueOrThrow({ where: { id: sameAppointment.id } });
    assert.deepEqual([replanned.windowStart, replanned.windowEnd], [sameStart, sameEnd]);
    assert.ok(replanned.plannedStart >= sameStart && replanned.plannedStart < sameEnd);
    const retainedHold = await prisma.slotHold.findUniqueOrThrow({ where: { id: sameHold.id } });
    assert.equal(retainedHold.releasedAt, null);
    assert.ok(retainedHold.plannedStart >= sameStart && retainedHold.plannedStart < sameEnd);
    assert.equal((await endpoint(lincoln.id, sameDay, tech.id))?.departureLat, second.lat);
    const oldGeometry = await fetch(`${base}/v1/dispatch/geometry?metro_id=${lincoln.id}&date=${sameDay}&run_id=${run.run_id}&phase=before`);
    assert.equal(oldGeometry.status, 200);
    const oldEndpoints = z.object({ endpoints: z.array(z.object({ technicianId: z.string(), departureLat: z.number() })) }).parse(await oldGeometry.json());
    assert.equal(oldEndpoints.endpoints.find(item => item.technicianId === tech.id)?.departureLat, first.lat);
    const refreshed = await post("/v1/optimize/day/preview", { metro_id: lincoln.id, date: sameDay });
    assert.equal(refreshed.status, 200, JSON.stringify(refreshed.payload));
    assert.notEqual(z.object({ configuration_version: z.string() }).parse(refreshed.payload).configuration_version, run.configuration_version);
    assert.equal((await post(`/v1/optimize/runs/${run.run_id}/apply`, {})).status, 409);
    await prisma.slotHold.delete({ where: { id: sameHold.id } });
    await prisma.appointment.delete({ where: { id: sameAppointment.id } });
    assert.equal((await endpoint(lincoln.id, beforeDay, tech.id))?.returnLat, first.lat);
    assert.equal((await post(`/v1/technicians/${tech.id}/depot-assignments`, { depotId: third.id, effectiveDate: dateAfter(5) })).status, 409);
    assert.equal((await post(`/v1/technicians/${tech.id}/depot-assignments`, { depotId: third.id, effectiveDate: crossDay })).status, 200);
    assert.equal(await endpoint(lincoln.id, crossDay, tech.id), undefined);
    assert.equal((await endpoint(omaha.id, crossDay, tech.id))?.departureLat, third.lat);
    assert.equal((await endpoint(omaha.id, crossDay, tech.id))?.returnLat, tech.homeLat);
    assert.equal((await endpoint(lincoln.id, sameDay, tech.id))?.departureLat, second.lat);

    await prisma.technicianDepotAssignment.delete({ where: { technicianId_effectiveDate: { technicianId: tech.id, effectiveDate: new Date(`${crossDay}T00:00:00Z`) } } });
    const start = new Date(`${crossDay}T15:00:00Z`), end = new Date(`${crossDay}T17:00:00Z`);
    const appointment = await prisma.appointment.create({ data: { jobId: job.id, technicianId: tech.id, serviceDate: new Date(`${crossDay}T00:00:00Z`),
      windowStart: start, windowEnd: end, plannedStart: start, plannedEnd: new Date(start.getTime() + 50 * 60_000), sequence: 0 } });
    assert.equal((await post(`/v1/technicians/${tech.id}/depot-assignments`, { depotId: third.id, effectiveDate: crossDay })).status, 409);
    await prisma.appointment.delete({ where: { id: appointment.id } });
    const hold = await prisma.slotHold.create({ data: { jobId: holdJob.id, technicianId: tech.id, serviceDate: new Date(`${crossDay}T00:00:00Z`),
      offerToken: suffix, windowStart: start, windowEnd: end, plannedStart: start, plannedEnd: new Date(start.getTime() + 50 * 60_000),
      insertPosition: 0, expiresAt: new Date(Date.now() + 600_000) } });
    assert.equal((await post(`/v1/depots/${second.id}/details`, { ...updatedLocation, name: "Blocked depot" })).status, 409);
    assert.equal((await prisma.depot.findUniqueOrThrow({ where: { id: second.id } })).name, "Lincoln second");
    assert.equal((await post(`/v1/technicians/${tech.id}/depot-assignments`, { depotId: third.id, effectiveDate: crossDay })).status, 409);
    await prisma.slotHold.delete({ where: { id: hold.id } });
    assert.equal((await post(`/v1/technicians/${tech.id}/depot-assignments`, { depotId: third.id, effectiveDate: crossDay })).status, 200);
    console.log("Lincoln and Omaha depots, booked routes, active holds, dated moves, horizon, endpoint history, and stale previews passed");
  } finally {
    await prisma.optimizationRun.deleteMany({ where: { metroId: { in: [lincoln.id, omaha.id] } } });
    await prisma.slotHold.deleteMany({ where: { technicianId: tech.id } });
    await prisma.appointment.deleteMany({ where: { technicianId: tech.id } });
    await prisma.scheduleDay.deleteMany({ where: { technicianId: tech.id } });
    await prisma.job.deleteMany({ where: { id: { in: [job.id, holdJob.id] } } });
    await prisma.technicianQualification.deleteMany({ where: { technicianId: tech.id } });
    await prisma.technician.delete({ where: { id: tech.id } });
    await prisma.depot.deleteMany({ where: { id: { in: [first.id, second.id, third.id] } } });
    await prisma.dealership.delete({ where: { id: dealer.id } });
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
    await prisma.address.delete({ where: { id: address.id } });
    await prisma.customer.delete({ where: { id: customer.id } });
    await prisma.metro.deleteMany({ where: { id: { in: [lincoln.id, omaha.id] } } });
    await prisma.$disconnect();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
