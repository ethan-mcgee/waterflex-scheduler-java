import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();
const suffix = randomUUID();
const base = process.env.SCHEDULER_TEST_URL ?? "http://127.0.0.1:18000";
const day = "2026-09-21";
const windowStart = new Date(`${day}T13:00:00Z`);
const windowEnd = new Date(`${day}T15:00:00Z`);
const serviceDate = new Date(`${day}T00:00:00Z`);

async function post(path: string, body: unknown) {
  const response = await fetch(base + path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), signal: AbortSignal.timeout(30_000) });
  const data = await response.json();
  assert.equal(response.status, 200, `${path}: ${JSON.stringify(data)}`);
  return data;
}

async function main() {
  const service = await prisma.serviceCatalog.findUniqueOrThrow({ where: { code: "FILTER_SWAP" } });
  const metro = await prisma.metro.findFirstOrThrow();
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
      await prisma.customer.create({ data: { id: customerIds[index]!, firstName: "Optimize", lastName: "Fixture", email: `opt${index}@example.invalid`, phone: "0000000000" } });
      await prisma.address.create({ data: { id: addressIds[index]!, customerId: customerIds[index]!, line1: "Fixture", city: "Monaco", state: "MC", postalCode: "98000", lat: 43.748, lng: 7.438 } });
      await prisma.job.create({ data: { id: jobIds[index]!, customerId: customerIds[index]!, addressId: addressIds[index]!, serviceId: service.id, durationMin: 50, status: "SCHEDULED" } });
      const plannedStart = new Date(windowStart.getTime() + (index === 0 ? 20 : 75) * 60_000);
      await prisma.appointment.create({ data: {
        id: appointmentIds[index]!, jobId: jobIds[index]!, technicianId: techIds[0], serviceDate,
        windowStart, windowEnd, plannedStart,
        plannedEnd: new Date(plannedStart.getTime() + 50 * 60_000), sequence: index,
      } });
    }
    const preview = await post("/v1/optimize/day/preview", { metro_id: metro.id, date: day });
    runId = preview.run_id;
    assert.equal(preview.status, "PREVIEW", JSON.stringify(preview));
    assert.ok(preview.objective_improvement > 0);
    const applied = await post(`/v1/optimize/runs/${runId}/apply`, {});
    assert.equal(applied.status, "APPLIED");
    const appointments = await prisma.appointment.findMany({ where: { id: { in: [...appointmentIds] } } });
    assert.equal(appointments.length, 1);
    for (const appointment of appointments) {
      assert.equal(appointment.windowStart.toISOString(), windowStart.toISOString());
      assert.equal(appointment.windowEnd.toISOString(), windowEnd.toISOString());
    }
    console.log("Timefold preview, guarded apply, and promised windows passed");
  } finally {
    if (runId) await prisma.optimizationRun.deleteMany({ where: { id: runId } });
    await prisma.appointment.deleteMany({ where: { id: { in: [...appointmentIds] } } });
    await prisma.job.deleteMany({ where: { id: { in: [...jobIds] } } });
    await prisma.address.deleteMany({ where: { id: { in: [...addressIds] } } });
    await prisma.customer.deleteMany({ where: { id: { in: [...customerIds] } } });
    await prisma.technicianQualification.deleteMany({ where: { technicianId: { in: [...techIds] } } });
    await prisma.scheduleDay.deleteMany({ where: { technicianId: { in: [...techIds] } } });
    await prisma.technician.deleteMany({ where: { id: { in: [...techIds] } } });
    await prisma.$disconnect();
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
