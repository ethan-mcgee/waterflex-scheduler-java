import { timeOffResult, required } from "../lib/contracts";
import { PrismaClient } from "@prisma/client";
import { randomUUID } from "node:crypto";
import assert from "node:assert/strict";

const prisma = new PrismaClient();
const base = process.env.SCHEDULER_TEST_URL ?? "http://127.0.0.1:18000";
const suffix = randomUUID();

function localToday(): Date {
  const parts = new Intl.DateTimeFormat("en-US", { timeZone: "America/Chicago", year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date());
  const value = (kind: string) => parts.find((part) => part.type === kind)?.value;
  return new Date(`${value("year")}-${value("month")}-${value("day")}T00:00:00Z`);
}

async function post(path: string, body: unknown) {
  const response = await fetch(`${base}${path}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const payload = timeOffResult.parse(await response.json());
  assert.equal(response.status, 200, `${path}: ${JSON.stringify(payload)}`);
  return payload;
}

async function main() {
  const day = localToday();
  day.setUTCDate(day.getUTCDate() + 21);
  while (day.getUTCDay() !== 1) day.setUTCDate(day.getUTCDate() + 1);
  const date = day.toISOString().slice(0, 10);
  const metro = await prisma.metro.create({ data: { id: `timeoff-metro-${suffix}`, name: "Time off fixture", timezone: "America/Chicago" } });
  const service = await prisma.serviceCatalog.create({ data: { code: `TIMEOFF_${suffix}`, name: "Time off fixture", estDurationMin: 60 } });
  const techA = `timeoff-tech-a-${suffix}`, techB = `timeoff-tech-b-${suffix}`;
  const customerId = `timeoff-customer-${suffix}`, addressId = `timeoff-address-${suffix}`, jobId = `timeoff-job-${suffix}`;
  const heldJobId = `timeoff-held-job-${suffix}`;
  const requestIds: string[] = [];
  try {
    for (const id of [techA, techB]) await prisma.technician.create({ data: {
      id, metroId: metro.id, name: id, homeLat: 43.735, homeLng: 7.420, shiftStartMin: 480, shiftEndMin: 1020,
      qualifications: { create: { serviceId: service.id } },
    } });
    await prisma.customer.create({ data: { id: customerId, firstName: "Time", lastName: "Off", email: "timeoff@example.invalid", phone: "0000000000" } });
    await prisma.address.create({ data: { id: addressId, customerId, line1: "Fixture", city: "Monaco", state: "MC", postalCode: "98000", lat: 43.748, lng: 7.438 } });
    await prisma.job.create({ data: { id: jobId, customerId, addressId, serviceId: service.id, durationMin: 60, status: "SCHEDULED" } });
    await prisma.appointment.create({ data: { jobId, technicianId: techA, serviceDate: day,
      windowStart: new Date(`${date}T15:00:00Z`), windowEnd: new Date(`${date}T17:00:00Z`),
      plannedStart: new Date(`${date}T15:00:00Z`), plannedEnd: new Date(`${date}T16:00:00Z`), sequence: 0 } });
    const request = await post("/v1/time-off/request", { technicianId: techA, firstDate: date, lastDate: date, startMin: 480, endMin: 1020, category: "Other", reason: "Fixture leave" });
    const requestId: string = request.requestId;
    requestIds.push(requestId);
    let status = "";
    for (let attempt = 0; attempt < 45; attempt++) {
      const row = await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: requestId }, include: { report: true } });
      status = row.status;
      if (status === "APPROVED" || ["NEEDS_COORDINATION", "ANALYSIS_FAILURE", "ROUTING_FAILURE"].includes(row.report?.status ?? "")) break;
      await new Promise((resolve) => setTimeout(resolve, 2000));
    }
    assert.equal(status, "APPROVED", `Time off status: ${status}`);
    const appointment = await prisma.appointment.findUniqueOrThrow({ where: { jobId } });
    assert.equal(appointment.technicianId, techB, "Repair must reassign the affected visit");
    const report = await prisma.timeOffReport.findUniqueOrThrow({ where: { requestId } });
    assert.equal(report.status, "APPLIED");
    const shortDate = localToday();
    shortDate.setUTCDate(shortDate.getUTCDate() + 13);
    const shortKey = shortDate.toISOString().slice(0, 10);
    const short = await post("/v1/time-off/request", { technicianId: techA, firstDate: shortKey, lastDate: shortKey,
      startMin: 480, endMin: 1020, category: "Other", reason: "Fixture short notice" });
    requestIds.push(short.requestId);
    let shortStatus = "";
    for (let attempt = 0; attempt < 45; attempt++) {
      const row = await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: short.requestId }, include: { report: true } });
      shortStatus = row.status;
      if (shortStatus === "READY" || ["NEEDS_COORDINATION", "ANALYSIS_FAILURE", "ROUTING_FAILURE"].includes(row.report?.status ?? "")) break;
      await new Promise((resolve) => setTimeout(resolve, 2000));
    }
    assert.equal(shortStatus, "READY", `Short-notice status: ${shortStatus}`);
    const readyReport = await prisma.timeOffReport.findUniqueOrThrow({ where: { requestId: short.requestId } });
    const preservedAppointment = await prisma.appointment.findUniqueOrThrow({ where: { jobId } });
    await prisma.timeOffReport.update({ where: { requestId: short.requestId }, data: { data: { technician_id: techA, days: [] } } });
    const invalidReport = await fetch(`${base}/v1/time-off/${short.requestId}/approve`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
    assert.equal(invalidReport.status, 409);
    assert.deepEqual(await prisma.appointment.findUniqueOrThrow({ where: { jobId } }), preservedAppointment);
    assert.equal((await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: short.requestId } })).status, "PENDING");
    await prisma.$transaction([
      prisma.timeOffReport.update({ where: { requestId: short.requestId }, data: { status: "READY", data: required(readyReport.data) } }),
      prisma.timeOffRequest.update({ where: { id: short.requestId }, data: { status: "READY" } }),
    ]);
    await post(`/v1/time-off/${short.requestId}/approve`, {});
    assert.equal((await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: short.requestId } })).status, "APPROVED");
    await prisma.job.create({ data: { id: heldJobId, customerId, addressId, serviceId: service.id, durationMin: 60 } });
    await prisma.slotHold.create({ data: { offerToken: `active-${suffix}`, jobId: heldJobId, technicianId: techB,
      serviceDate: day, windowStart: new Date(`${date}T18:00:00Z`), windowEnd: new Date(`${date}T20:00:00Z`),
      plannedStart: new Date(`${date}T18:00:00Z`), plannedEnd: new Date(`${date}T19:00:00Z`), insertPosition: 1,
      locationLat: 43.748, locationLng: 7.438, expiresAt: new Date(Date.now() + 10 * 60_000) } });
    const blocked = await post("/v1/time-off/request", { technicianId: techB, firstDate: date, lastDate: date,
      startMin: 480, endMin: 1020, category: "Other", reason: "Fixture active reservation" });
    requestIds.push(blocked.requestId);
    let blockedReport = await prisma.timeOffReport.findUniqueOrThrow({ where: { requestId: blocked.requestId } });
    for (let attempt = 0; attempt < 45 && ["QUEUED", "ANALYZING"].includes(blockedReport.status); attempt++) {
      await new Promise((resolve) => setTimeout(resolve, 2000));
      blockedReport = await prisma.timeOffReport.findUniqueOrThrow({ where: { requestId: blocked.requestId } });
    }
    assert.equal(blockedReport.status, "NEEDS_COORDINATION");
    assert.match(JSON.stringify(blockedReport.data), /ACTIVE_RESERVATIONS/);
    console.log("Automatic repair, invalid-report rollback, staff review, and active-reservation deferral passed");
  } finally {
    await prisma.slotHold.deleteMany({ where: { jobId: heldJobId } });
    await prisma.job.deleteMany({ where: { id: heldJobId } });
    if (requestIds.length) {
      await prisma.timeOffReport.deleteMany({ where: { requestId: { in: requestIds } } });
      await prisma.timeOffInterval.deleteMany({ where: { requestId: { in: requestIds } } });
      await prisma.timeOffRequest.deleteMany({ where: { id: { in: requestIds } } });
    }
    await prisma.optimizationChange.deleteMany({ where: { run: { metroId: metro.id } } });
    await prisma.optimizationRun.deleteMany({ where: { metroId: metro.id } });
    await prisma.appointment.deleteMany({ where: { jobId } });
    await prisma.job.deleteMany({ where: { id: jobId } });
    await prisma.address.deleteMany({ where: { id: addressId } });
    await prisma.customer.deleteMany({ where: { id: customerId } });
    await prisma.scheduleDay.deleteMany({ where: { technicianId: { in: [techA, techB] } } });
    await prisma.technicianQualification.deleteMany({ where: { technicianId: { in: [techA, techB] } } });
    await prisma.technician.deleteMany({ where: { id: { in: [techA, techB] } } });
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
    await prisma.$disconnect();
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
