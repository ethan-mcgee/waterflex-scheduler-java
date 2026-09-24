import { timeOffResult, required } from "../lib/contracts";
import { PrismaClient } from "@prisma/client";
import { initialAvailability } from "../lib/technicianAvailability";
import { technicianColor } from "../lib/technicianColor";
import { parseTimeOffReport } from "../lib/timeOffView";
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
  const payload: unknown = await response.json();
  assert.equal(response.status, 200, `${path}: ${JSON.stringify(payload)}`);
  return timeOffResult.parse(payload);
}

async function main() {
  const day = localToday();
  day.setUTCDate(day.getUTCDate() + 21);
  while (day.getUTCDay() !== 1) day.setUTCDate(day.getUTCDate() + 1);
  const date = day.toISOString().slice(0, 10);
  const metro = await prisma.metro.create({ data: { id: `timeoff-metro-${suffix}`, name: "Time off fixture", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { name: "Time off dealership" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Time off depot", lat: 43.735, lng: 7.420,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  const service = await prisma.serviceCatalog.create({ data: { code: `TIMEOFF_${suffix}`, name: "Time off fixture", estDurationMin: 60 } });
  const techA = `timeoff-tech-a-${suffix}`, techB = `timeoff-tech-b-${suffix}`, techC = `timeoff-tech-c-${suffix}`;
  const customerId = `timeoff-customer-${suffix}`, addressId = `timeoff-address-${suffix}`, jobId = `timeoff-job-${suffix}`;
  const heldJobId = `timeoff-held-job-${suffix}`, impossibleJobId = `timeoff-impossible-job-${suffix}`;
  const overtimeJobId = `timeoff-overtime-job-${suffix}`;
  const requestIds: string[] = [];
  try {
    for (const id of [techA, techB]) await prisma.technician.create({ data: {
      id, name: id, color: technicianColor(id), availabilityVersions: initialAvailability(480, 1020), homeLat: 43.735, homeLng: 7.420, shiftStartMin: 480, shiftEndMin: 1020,
      depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } },
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
    // Even with enough notice for automatic approval, additional overtime needs review.
    const overtimeDay = new Date(day); overtimeDay.setUTCDate(overtimeDay.getUTCDate() + 7);
    const overtimeDate = overtimeDay.toISOString().slice(0, 10);
    await prisma.technician.updateMany({ where: { id: { in: [techA, techB] } }, data: { maxOvertimeMinutes: 120 } });
    await prisma.technicianShiftOverride.create({ data: { technicianId: techB, serviceDate: overtimeDay, available: true, shiftStartMin: 480, shiftEndMin: 960 } });
    await prisma.job.create({ data: { id: overtimeJobId, customerId, addressId, serviceId: service.id, durationMin: 90, status: "SCHEDULED" } });
    const overtimeAppointment = await prisma.appointment.create({ data: { jobId: overtimeJobId, technicianId: techA, serviceDate: overtimeDay,
      windowStart: new Date(`${overtimeDate}T21:00:00Z`), windowEnd: new Date(`${overtimeDate}T23:00:00Z`),
      plannedStart: new Date(`${overtimeDate}T21:00:00Z`), plannedEnd: new Date(`${overtimeDate}T22:30:00Z`), sequence: 0 } });
    const overtimeRequest = await post("/v1/time-off/request", { technicianId: techA, firstDate: overtimeDate, lastDate: overtimeDate,
      startMin: 480, endMin: 1020, category: "Other", reason: "Fixture overtime approval" });
    requestIds.push(overtimeRequest.requestId);
    let overtimeReview = await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: overtimeRequest.requestId }, include: { report: true } });
    for (let attempt = 0; attempt < 45 && overtimeReview.status === "PENDING"; attempt++) {
      await new Promise(resolve => setTimeout(resolve, 2000));
      overtimeReview = await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: overtimeRequest.requestId }, include: { report: true } });
    }
    assert.equal(overtimeReview.status, "READY", JSON.stringify(overtimeReview.report?.data));
    const parsedOvertime = parseTimeOffReport(overtimeReview.report?.data);
    assert.equal(parsedOvertime.kind, "complete");
    if (parsedOvertime.kind !== "complete") throw new Error("Overtime report missing");
    const overtimeRepair = required(parsedOvertime.summary.days[0]);
    assert.ok(required(overtimeRepair.daily_after).overtime_minutes > required(overtimeRepair.daily_before).overtime_minutes);
    const noApproval = await fetch(`${base}/v1/time-off/${overtimeRequest.requestId}/approve`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
    assert.equal(noApproval.status, 409);
    assert.equal((await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: overtimeRequest.requestId } })).status, "READY");
    assert.deepEqual(await prisma.appointment.findUniqueOrThrow({ where: { id: overtimeAppointment.id } }), overtimeAppointment);
    await post(`/v1/time-off/${overtimeRequest.requestId}/approve`, { allowAdditionalOvertime: true, approvedRepairIds: [required(overtimeRepair.run_id)] });
    assert.equal((await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: overtimeRequest.requestId } })).additionalOvertimeApproved, true);
    assert.equal((await prisma.appointment.findUniqueOrThrow({ where: { id: overtimeAppointment.id } })).technicianId, techB);
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
    const friday = new Date(day);
    friday.setUTCDate(friday.getUTCDate() + 4);
    const sunday = new Date(day);
    sunday.setUTCDate(sunday.getUTCDate() + 6);
    const offDays = await post("/v1/time-off/request", { technicianId: techB, firstDate: friday.toISOString().slice(0, 10), lastDate: sunday.toISOString().slice(0, 10),
      startMin: 480, endMin: 1020, category: "Other", reason: "Fixture weekend range" });
    requestIds.push(offDays.requestId);
    let weekend = await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: offDays.requestId }, include: { report: true } });
    for (let attempt = 0; attempt < 45 && weekend.status !== "APPROVED" && !["NEEDS_COORDINATION", "ANALYSIS_FAILURE"].includes(weekend.report?.status ?? ""); attempt++) {
      await new Promise(resolve => setTimeout(resolve, 2000));
      weekend = await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: offDays.requestId }, include: { report: true } });
    }
    assert.equal(weekend.status, "APPROVED");
    assert.equal(weekend.report?.status, "APPLIED");
    const weekendSummary = parseTimeOffReport(weekend.report?.data);
    assert.equal(weekendSummary.kind, "complete");
    if (weekendSummary.kind !== "complete") throw new Error("Missing weekend analysis");
    assert.equal(weekendSummary.summary.days.filter(item => item.status === "NO_SHIFT").length, 2);

    const wednesday = new Date(day);
    wednesday.setUTCDate(wednesday.getUTCDate() + 2);
    const impossibleDate = wednesday.toISOString().slice(0, 10);
    await prisma.technicianQualification.delete({ where: { technicianId_serviceId: { technicianId: techB, serviceId: service.id } } });
    await prisma.job.create({ data: { id: impossibleJobId, customerId, addressId, serviceId: service.id, durationMin: 60, status: "SCHEDULED" } });
    await prisma.appointment.create({ data: { jobId: impossibleJobId, technicianId: techA, serviceDate: wednesday,
      windowStart: new Date(`${impossibleDate}T15:00:00Z`), windowEnd: new Date(`${impossibleDate}T17:00:00Z`),
      plannedStart: new Date(`${impossibleDate}T15:00:00Z`), plannedEnd: new Date(`${impossibleDate}T16:00:00Z`), sequence: 0 } });
    const impossible = await post("/v1/time-off/request", { technicianId: techA, firstDate: impossibleDate, lastDate: impossibleDate,
      startMin: 480, endMin: 1020, category: "Other", reason: "Fixture impossible repair" });
    requestIds.push(impossible.requestId);
    let impossibleReport = await prisma.timeOffReport.findUniqueOrThrow({ where: { requestId: impossible.requestId } });
    for (let attempt = 0; attempt < 45 && ["QUEUED", "ANALYZING"].includes(impossibleReport.status); attempt++) {
      await new Promise(resolve => setTimeout(resolve, 2000));
      impossibleReport = await prisma.timeOffReport.findUniqueOrThrow({ where: { requestId: impossible.requestId } });
    }
    assert.equal(impossibleReport.status, "NEEDS_COORDINATION");
    assert.match(JSON.stringify(impossibleReport.data), /VALIDATED_CONSTRAINT_CONFLICT|SEARCH_BUDGET_EXHAUSTED/);
    assert.equal((await prisma.appointment.findUniqueOrThrow({ where: { jobId: impossibleJobId } })).technicianId, techA);
    assert.equal((await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: impossible.requestId } })).status, "PENDING");
    await prisma.technicianQualification.create({ data: { technicianId: techB, serviceId: service.id } });

    await prisma.technician.create({ data: { id: techC, name: techC, color: technicianColor(techC), homeLat: 43.735, homeLng: 7.420,
      depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } },
      shiftStartMin: 480, shiftEndMin: 1020, qualifications: { create: { serviceId: service.id } } } });
    const tuesday = new Date(day);
    tuesday.setUTCDate(tuesday.getUTCDate() + 1);
    const nextDate = tuesday.toISOString().slice(0, 10);
    const failed = await post("/v1/time-off/request", { technicianId: techC, firstDate: nextDate, lastDate: nextDate,
      startMin: 480, endMin: 1020, category: "Other", reason: "Fixture retry" });
    requestIds.push(failed.requestId);
    let failedReport = await prisma.timeOffReport.findUniqueOrThrow({ where: { requestId: failed.requestId } });
    for (let attempt = 0; attempt < 45 && ["QUEUED", "ANALYZING"].includes(failedReport.status); attempt++) {
      await new Promise(resolve => setTimeout(resolve, 2000));
      failedReport = await prisma.timeOffReport.findUniqueOrThrow({ where: { requestId: failed.requestId } });
    }
    assert.equal(failedReport.status, "ANALYSIS_FAILURE");
    assert.match(JSON.stringify(failedReport.data), new RegExp(nextDate));
    assert.match(JSON.stringify(failedReport.data), /weekly availability is missing or invalid/);
    assert.equal((await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: failed.requestId } })).status, "PENDING");
    await prisma.technician.update({ where: { id: techC }, data: { availabilityVersions: initialAvailability(480, 1020) } });
    await post(`/v1/time-off/${failed.requestId}/retry`, {});
    assert.equal((await prisma.timeOffReport.findUniqueOrThrow({ where: { requestId: failed.requestId } })).status, "QUEUED");
    const denied = await post("/v1/time-off/request", { technicianId: techB, firstDate: nextDate, lastDate: nextDate,
      startMin: 480, endMin: 1020, category: "Other", reason: "Fixture denial" });
    requestIds.push(denied.requestId);
    await prisma.timeOffReport.update({ where: { requestId: denied.requestId }, data: { status: "ANALYZING" } });
    await post(`/v1/time-off/${denied.requestId}/deny`, {});
    const deniedRow = await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: denied.requestId }, include: { report: true } });
    assert.equal(deniedRow.status, "DENIED");
    assert.ok(deniedRow.decidedAt);
    assert.equal(deniedRow.report?.status, "DENIED");
    const replacement = await post("/v1/time-off/request", { technicianId: techB, firstDate: nextDate, lastDate: nextDate,
      startMin: 480, endMin: 1020, category: "Other", reason: "Fixture replacement" });
    requestIds.push(replacement.requestId);
    await post(`/v1/time-off/${replacement.requestId}/deny`, {});
    const nearSaturday = localToday();
    nearSaturday.setUTCDate(nearSaturday.getUTCDate() + 7);
    while (nearSaturday.getUTCDay() !== 6) nearSaturday.setUTCDate(nearSaturday.getUTCDate() + 1);
    const saturdayKey = nearSaturday.toISOString().slice(0, 10);
    const offDayReview = await post("/v1/time-off/request", { technicianId: techB, firstDate: saturdayKey, lastDate: saturdayKey,
      startMin: 480, endMin: 1020, category: "Other", reason: "Fixture availability change" });
    requestIds.push(offDayReview.requestId);
    let review = await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: offDayReview.requestId }, include: { report: true } });
    for (let attempt = 0; attempt < 45 && review.status === "PENDING" && ["QUEUED", "ANALYZING"].includes(review.report?.status ?? ""); attempt++) {
      await new Promise(resolve => setTimeout(resolve, 2000));
      review = await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: offDayReview.requestId }, include: { report: true } });
    }
    assert.equal(review.status, "READY");
    await prisma.technicianShiftOverride.create({ data: { technicianId: techB, serviceDate: nearSaturday, available: true, shiftStartMin: 480, shiftEndMin: 1020 } });
    const staleOffDay = await fetch(`${base}/v1/time-off/${offDayReview.requestId}/approve`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
    assert.equal(staleOffDay.status, 409);
    assert.equal((await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: offDayReview.requestId } })).status, "PENDING");
    await prisma.technicianShiftOverride.deleteMany({ where: { technicianId: techB, serviceDate: nearSaturday } });
    const cannotDeny = await fetch(`${base}/v1/time-off/${offDays.requestId}/deny`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
    assert.equal(cannotDeny.status, 409);
    console.log("Time-off repair, explicit overtime approval, weekend range, failure detail, retry, denial, and overlap eligibility passed");
  } finally {
    await prisma.slotHold.deleteMany({ where: { jobId: heldJobId } });
    await prisma.technicianShiftOverride.deleteMany({ where: { technicianId: { in: [techA, techB, techC] } } });
    await prisma.job.deleteMany({ where: { id: heldJobId } });
    if (requestIds.length) {
      await prisma.timeOffReport.deleteMany({ where: { requestId: { in: requestIds } } });
      await prisma.timeOffInterval.deleteMany({ where: { requestId: { in: requestIds } } });
      await prisma.timeOffRequest.deleteMany({ where: { id: { in: requestIds } } });
    }
    await prisma.optimizationChange.deleteMany({ where: { run: { metroId: metro.id } } });
    await prisma.optimizationRun.deleteMany({ where: { metroId: metro.id } });
    await prisma.appointment.deleteMany({ where: { jobId: { in: [jobId, impossibleJobId, overtimeJobId] } } });
    await prisma.job.deleteMany({ where: { id: { in: [jobId, impossibleJobId, overtimeJobId] } } });
    await prisma.address.deleteMany({ where: { id: addressId } });
    await prisma.customer.deleteMany({ where: { id: customerId } });
    await prisma.scheduleDay.deleteMany({ where: { technicianId: { in: [techA, techB, techC] } } });
    await prisma.technicianQualification.deleteMany({ where: { technicianId: { in: [techA, techB, techC] } } });
    await prisma.technician.deleteMany({ where: { id: { in: [techA, techB, techC] } } });
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
    await prisma.$disconnect();
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
