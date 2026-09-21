import { optimization } from "../lib/contracts";
import { required } from "../lib/contracts";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { PrismaClient } from "@prisma/client";
import { isDispatchGeometry } from "../lib/dispatchGeometry";

const prisma = new PrismaClient();
const suffix = randomUUID();
const base = process.env.SCHEDULER_TEST_URL ?? "http://127.0.0.1:18000";
// Choose a Monday at least a week out so preview and apply never hit the freeze window.
const future = new Date();
future.setUTCDate(future.getUTCDate() + 7);
future.setUTCDate(future.getUTCDate() + ((8 - future.getUTCDay()) % 7));
const day = future.toISOString().slice(0, 10);
const windowStart = new Date(`${day}T16:00:00Z`);
const windowEnd = new Date(`${day}T18:00:00Z`);
const serviceDate = new Date(`${day}T00:00:00Z`);

async function post(path: string, body: unknown) {
  const response = await fetch(base + path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), signal: AbortSignal.timeout(30_000) });
  const data = optimization.parse(await response.json());
  assert.equal(response.status, 200, `${path}: ${JSON.stringify(data)}`);
  return data;
}

async function geometry(path: string, phase: string, expectedHome: [number, number]) {
  const response = await fetch(base + path, { signal: AbortSignal.timeout(30_000) });
  const data: unknown = await response.json();
  assert.equal(response.status, 200, `${path}: ${JSON.stringify(data)}`);
  assert.ok(isDispatchGeometry(data, day, phase), JSON.stringify(data));
  assert.equal(data.type, "FeatureCollection");
  assert.equal(data.routingIdentity, "ci-monaco-omaha-car-v2");
  assert.equal(data.features.length, 2);
  assert.equal(data.stops.length, 1);
  assert.deepEqual(required(data.features[0]).geometry.coordinates[0], expectedHome);
  assert.deepEqual(required(data.features[0]).geometry.coordinates.at(-1), [7.438, 43.748]);
  assert.deepEqual(required(data.features[1]).geometry.coordinates[0], [7.438, 43.748]);
  assert.deepEqual(required(data.features[1]).geometry.coordinates.at(-1), expectedHome);
  for (const [index, feature] of data.features.entries()) {
    assert.equal(feature.properties.technicianId, required(data.stops[0]).technicianId);
    assert.equal(feature.properties.legIndex, index);
    assert.equal(feature.geometry.coordinates.length, phase === "after" ? 2 : 3);
  }
  return data;
}

async function main() {
  const metro = await prisma.metro.create({ data: { id: `opt-metro-${suffix}`, name: "Optimizer fixture", timezone: "America/Chicago" } });
  const service = await prisma.serviceCatalog.create({ data: { code: `OPT_${suffix}`, name: "Optimizer fixture", estDurationMin: 50 } });
  const techIds = [`opt-a-${suffix}`, `opt-b-${suffix}`] as const;
  const customerIds = [`opt-c1-${suffix}`, `opt-c2-${suffix}`] as const;
  const addressIds = [`opt-ad1-${suffix}`, `opt-ad2-${suffix}`] as const;
  const jobIds = [`opt-j1-${suffix}`, `opt-j2-${suffix}`] as const;
  const appointmentIds = [`opt-ap1-${suffix}`, `opt-ap2-${suffix}`] as const;
  let runId: string | null = null;
  try {
    for (const [index, techId] of techIds.entries()) {
      await prisma.technician.create({ data: {
        id: techId, metroId: metro.id, name: `Optimizer fixture ${index}`,
        homeLat: index === 0 ? 43.735 : 43.748, homeLng: index === 0 ? 7.420 : 7.438,
        shiftStartMin: 480, shiftEndMin: 1020, maxDailyMinutes: 600, maxOvertimeMinutes: 60,
        qualifications: { create: { serviceId: service.id } },
      } });
    }
    for (let index = 0; index < 1; index++) {
      await prisma.customer.create({ data: { id: required(customerIds[index]), firstName: "Optimize", lastName: "Fixture", email: `opt${index}@example.invalid`, phone: "0000000000" } });
      await prisma.address.create({ data: { id: required(addressIds[index]), customerId: required(customerIds[index]), line1: "Fixture", city: "Monaco", state: "MC", postalCode: "98000", lat: 43.748, lng: 7.438 } });
      await prisma.job.create({ data: { id: required(jobIds[index]), customerId: required(customerIds[index]), addressId: required(addressIds[index]), serviceId: service.id, durationMin: 50, status: "SCHEDULED" } });
      const plannedStart = new Date(windowStart.getTime() + (index === 0 ? 20 : 75) * 60_000);
      await prisma.appointment.create({ data: {
        id: required(appointmentIds[index]), jobId: required(jobIds[index]), technicianId: techIds[0], serviceDate,
        windowStart, windowEnd, plannedStart,
        plannedEnd: new Date(plannedStart.getTime() + 50 * 60_000), sequence: index,
      } });
    }
    const preview = await post("/v1/optimize/day/preview", { metro_id: metro.id, date: day });
    runId = preview.run_id;
    assert.equal(preview.status, "PREVIEW", JSON.stringify(preview));
    assert.ok(preview.objective_improvement > 0);
    await geometry(`/v1/dispatch/geometry?metro_id=${encodeURIComponent(metro.id)}&date=${day}`, "current", [7.420, 43.735]);
    await geometry(`/v1/dispatch/geometry?metro_id=${encodeURIComponent(metro.id)}&date=${day}&run_id=${runId}&phase=before`, "before", [7.420, 43.735]);
    await geometry(`/v1/dispatch/geometry?metro_id=${encodeURIComponent(metro.id)}&date=${day}&run_id=${runId}&phase=after`, "after", [7.438, 43.748]);
    const savedRun = await prisma.optimizationRun.findUniqueOrThrow({ where: { id: required(runId) } });
    const beforeInvalidApply = await prisma.appointment.findMany({ where: { id: { in: [...appointmentIds] } }, orderBy: { id: "asc" } });
    await prisma.optimizationRun.update({ where: { id: required(runId) }, data: { scheduleVersions: [] } });
    const invalidApply = await fetch(`${base}/v1/optimize/runs/${runId}/apply`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
    assert.equal(invalidApply.status, 409);
    assert.deepEqual(await prisma.appointment.findMany({ where: { id: { in: [...appointmentIds] } }, orderBy: { id: "asc" } }), beforeInvalidApply);
    await prisma.optimizationRun.update({ where: { id: required(runId) }, data: { scheduleVersions: required(savedRun.scheduleVersions) } });
    const applied = await post(`/v1/optimize/runs/${runId}/apply`, {});
    assert.equal(applied.status, "APPLIED");
    const appointments = await prisma.appointment.findMany({ where: { id: { in: [...appointmentIds] } } });
    assert.equal(appointments.length, 1);
    for (const appointment of appointments) {
      assert.equal(appointment.windowStart.toISOString(), windowStart.toISOString());
      assert.equal(appointment.windowEnd.toISOString(), windowEnd.toISOString());
    }
    console.log("Timefold preview, current/before/proposed GeoJSON road routes, guarded apply, and promised windows passed");
  } finally {
    await prisma.optimizationRun.deleteMany({ where: { metroId: metro.id } });
    await prisma.appointment.deleteMany({ where: { id: { in: [...appointmentIds] } } });
    await prisma.job.deleteMany({ where: { id: { in: [...jobIds] } } });
    await prisma.address.deleteMany({ where: { id: { in: [...addressIds] } } });
    await prisma.customer.deleteMany({ where: { id: { in: [...customerIds] } } });
    await prisma.technicianQualification.deleteMany({ where: { technicianId: { in: [...techIds] } } });
    await prisma.scheduleDay.deleteMany({ where: { technicianId: { in: [...techIds] } } });
    await prisma.technician.deleteMany({ where: { id: { in: [...techIds] } } });
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
    await prisma.$disconnect();
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
