import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { PrismaClient } from "@prisma/client";
import { z } from "zod";

const prisma = new PrismaClient();
const base = process.env.SCHEDULER_TEST_URL ?? "http://127.0.0.1:8004";
const suffix = randomUUID();
const depotId = "depot-omaha-main";
const techId = "tech-1";
const tomorrow = new Date();
tomorrow.setUTCDate(tomorrow.getUTCDate() + 8);
const day = tomorrow.toISOString().slice(0, 10);
const serviceDate = new Date(`${day}T00:00:00Z`);
const windowStart = new Date(`${day}T15:00:00Z`);
const windowEnd = new Date(`${day}T19:00:00Z`);
const today = new Intl.DateTimeFormat("en-CA", { timeZone: "America/Chicago", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
const saved = z.object({ success: z.literal(true) });

async function policy(departure: "HOME" | "DEPOT", returnTo: "HOME" | "DEPOT", expected = 200) {
  const response = await fetch(`${base}/v1/depots/${depotId}/policy`, { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ departure, returnTo }), signal: AbortSignal.timeout(30_000) });
  const body: unknown = await response.json();
  assert.equal(response.status, expected, JSON.stringify(body));
  if (expected === 200) saved.parse(body);
}

async function main() {
  const original = await prisma.depotEndpointPolicy.findFirstOrThrow({ where: { depotId }, orderBy: { effectiveDate: "desc" } });
  const technician = await prisma.technician.findUniqueOrThrow({ where: { id: techId } });
  const service = await prisma.serviceCatalog.findFirstOrThrow();
  const customer = await prisma.customer.create({ data: { firstName: "Route", lastName: "Fixture", email: `${suffix}@example.invalid`, phone: "4025550100" } });
  const address = await prisma.address.create({ data: { customerId: customer.id, line1: "Route fixture", city: "Omaha", state: "NE", postalCode: "68102", lat: 41.25, lng: -95.94 } });
  const job = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: 45, status: "SCHEDULED" } });
  const heldJob = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: 45 } });
  const todayJob = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: 45, status: "SCHEDULED" } });
  const appointment = await prisma.appointment.create({ data: { jobId: job.id, technicianId: techId, serviceDate,
    windowStart, windowEnd, plannedStart: windowStart, plannedEnd: new Date(windowStart.getTime() + 45 * 60_000), sequence: 0 } });
  const todayStart = new Date(`${today}T15:00:00Z`);
  const todayAppointment = await prisma.appointment.create({ data: { jobId: todayJob.id, technicianId: techId, serviceDate: new Date(`${today}T00:00:00Z`),
    windowStart: todayStart, windowEnd: new Date(todayStart.getTime() + 4 * 60 * 60_000), plannedStart: todayStart,
    plannedEnd: new Date(todayStart.getTime() + 45 * 60_000), sequence: 0 } });
  try {
    const previewBefore = await fetch(`${base}/v1/optimize/day/preview`, { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ metro_id: "metro-omaha", date: day }) });
    const beforeVersion = z.object({ run_id: z.string(), configuration_version: z.string() }).parse(await previewBefore.json());
    assert.equal(previewBefore.status, 200);
    for (const departure of ["HOME", "DEPOT"] as const) for (const returnTo of ["HOME", "DEPOT"] as const) {
      await policy(departure, returnTo);
      const updated = await prisma.appointment.findUniqueOrThrow({ where: { id: appointment.id } });
      assert.deepEqual([updated.windowStart, updated.windowEnd], [windowStart, windowEnd]);
      assert.ok(updated.plannedStart >= windowStart && updated.plannedStart < windowEnd);
      const response = await fetch(`${base}/v1/dispatch/geometry?metro_id=metro-omaha&date=${day}`);
      const geometry = z.object({ endpoints: z.array(z.object({ technicianId: z.string(), departureLat: z.number(), returnLat: z.number() })) }).parse(await response.json());
      assert.equal(response.status, 200);
      const endpoint = geometry.endpoints.find(item => item.technicianId === techId);
      assert.ok(endpoint);
      assert.equal(endpoint.departureLat, departure === "HOME" ? technician.homeLat : 41.2565);
      assert.equal(endpoint.returnLat, returnTo === "HOME" ? technician.homeLat : 41.2565);
    }
    const previewAfter = await fetch(`${base}/v1/optimize/day/preview`, { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ metro_id: "metro-omaha", date: day }) });
    const afterVersion = z.object({ configuration_version: z.string() }).parse(await previewAfter.json());
    assert.equal(previewAfter.status, 200);
    assert.notEqual(beforeVersion.configuration_version, afterVersion.configuration_version);
    const historical = await fetch(`${base}/v1/dispatch/geometry?metro_id=metro-omaha&date=${day}&run_id=${beforeVersion.run_id}&phase=before`);
    const savedGeometry = z.object({ endpoints: z.array(z.object({ technicianId: z.string(), departureLat: z.number(), returnLat: z.number() })) }).parse(await historical.json());
    assert.equal(historical.status, 200);
    assert.equal(savedGeometry.endpoints.find(item => item.technicianId === techId)?.departureLat, technician.homeLat);
    assert.equal((await prisma.appointment.findUniqueOrThrow({ where: { id: todayAppointment.id } })).plannedStart.toISOString(), todayStart.toISOString());
    const todayGeometry = await fetch(`${base}/v1/dispatch/geometry?metro_id=metro-omaha&date=${today}`);
    const frozen = z.object({ endpoints: z.array(z.object({ technicianId: z.string(), departureLat: z.number(), returnLat: z.number() })) }).parse(await todayGeometry.json());
    assert.equal(todayGeometry.status, 200);
    assert.equal(frozen.endpoints.find(item => item.technicianId === techId)?.departureLat, technician.homeLat);
    const hold = await prisma.slotHold.create({ data: { jobId: heldJob.id, technicianId: techId, offerToken: `dealership-${suffix}`,
      serviceDate, windowStart, windowEnd, plannedStart: windowStart, plannedEnd: windowEnd, insertPosition: 1,
      expiresAt: new Date(Date.now() + 600_000) } });
    try { await policy("DEPOT", "DEPOT", 409); }
    finally { await prisma.slotHold.delete({ where: { id: hold.id } }); }
    await policy("DEPOT", "DEPOT");
    await prisma.technician.update({ where: { id: techId }, data: { maxDailyMinutes: 70 } });
    const before = await prisma.appointment.findUniqueOrThrow({ where: { id: appointment.id } });
    await policy("HOME", "HOME", 409);
    const after = await prisma.appointment.findUniqueOrThrow({ where: { id: appointment.id } });
    assert.deepEqual(after, before);
    const current = await prisma.depotEndpointPolicy.findFirstOrThrow({ where: { depotId }, orderBy: { effectiveDate: "desc" } });
    assert.equal(current.departure, "DEPOT"); assert.equal(current.returnTo, "DEPOT");
    console.log("Four endpoint pairs, directed geometry, held and infeasible edits, cutoff, stale fingerprint, and preserved promises passed");
  } finally {
    await prisma.technician.update({ where: { id: techId }, data: { maxDailyMinutes: technician.maxDailyMinutes } });
    await policy(original.departure, original.returnTo);
    await prisma.appointment.delete({ where: { id: appointment.id } });
    await prisma.appointment.delete({ where: { id: todayAppointment.id } });
    await prisma.job.deleteMany({ where: { id: { in: [job.id, heldJob.id, todayJob.id] } } });
    await prisma.address.delete({ where: { id: address.id } });
    await prisma.customer.delete({ where: { id: customer.id } });
    await prisma.$disconnect();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
