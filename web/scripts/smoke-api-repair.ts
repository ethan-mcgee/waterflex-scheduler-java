// Time off, availability and qualifications through the public scheduling API, end to end against a running scheduler
// and the fixture router. Technician a has one visit on the first day and none on the second; b is free. Time off for a
// is analyzed day by day (/api/v1/repairs/proposals): the visit moves to b, and the second day has nothing to move.
// A change after the analysis sends the request back without writing anything; a fresh analysis is approved, which
// writes the repair and the approval together. Edits the portal now makes itself refuse days with appointments. The
// scheduler must route this smoke's metro to the fixture router:
//   ROUTING_METRO_URLS=api-repair-smoke=http://127.0.0.1:18001   SCHEDULER_TEST_URL=http://127.0.0.1:18000
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { prisma } from "../lib/prisma";
import { generateTenantToken, tenantTokenSha256 } from "../lib/tenantTokens";
import { storeSolverSettings } from "../lib/clientSettingsStore";
import { addCalendarDays, todayInTz } from "../lib/date";
import { localMinute } from "../lib/zonedTime";
import { analyzeApiTimeOff, approveApiTimeOff, deleteApiAvailability, denyApiTimeOff, setApiAvailability, setApiQualification, submitApiTimeOff,
  TimeOffRefused } from "../lib/apiTimeOff";
import { apiTimeOffReport } from "../lib/apiTimeOffCore";
import { tokenVariable } from "../lib/schedulerApi";

const METRO = "api-repair-smoke";
const ZONE = "America/Chicago";
const run = randomUUID().slice(0, 8);
const clientId = `api-repair-${run}`;
const otherClientId = `api-repair-${run}-other`;
const id = (name: string) => `${clientId}-${name}`;
const stamp = (date: string) => new Date(`${date}T00:00:00Z`);
// Two days ahead, so no day is frozen by the 6 a.m. cutoff while the smoke runs.
const DATE = addCalendarDays(todayInTz(ZONE), 2);
const NEXT = addCalendarDays(DATE, 1);
const LATER = addCalendarDays(DATE, 2);
const base = process.env.SCHEDULER_TEST_URL;
assert.ok(base, "SCHEDULER_TEST_URL must point at a running scheduler");

async function removeMetro() {
  const like = "api-repair-%";
  for (const table of ["api_commit_receipt", "api_proposal_technician_day", "api_daily_proposal", "api_request"])
    await prisma.$executeRawUnsafe(`DELETE FROM ${table} WHERE "tenantId" LIKE $1`, like);
  await prisma.$executeRaw`DELETE FROM tenant_api_token WHERE "tenantId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM tenant WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM portal_api_daily_proposal WHERE "clientId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM time_off_request WHERE "technicianId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM technician_shift_override WHERE "technicianId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM appointment WHERE "technicianId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM job WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM address WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM customer WHERE "clientId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM schedule_day WHERE "technicianId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM technician_availability_day WHERE "versionId" IN (SELECT id FROM technician_availability_version WHERE "technicianId" LIKE ${like})`;
  for (const table of ["technician_availability_version", "technician_depot_assignment", "technician_qualification"])
    await prisma.$executeRawUnsafe(`DELETE FROM ${table} WHERE "technicianId" LIKE $1`, like);
  await prisma.$executeRaw`DELETE FROM technician WHERE "clientId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM depot_endpoint_policy WHERE "depotId" IN (SELECT id FROM depot WHERE "metroId" = ${METRO})`;
  await prisma.$executeRaw`DELETE FROM depot WHERE "metroId" = ${METRO}`;
  await prisma.$executeRaw`DELETE FROM dealership WHERE "clientId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM client_solver_settings WHERE "clientId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM client WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM service_catalog WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM metro WHERE id = ${METRO}`;
}

async function technician(name: string) {
  await prisma.technician.create({ data: { id: id(name), clientId, name: `Repair smoke ${name}`, color: "#000000", homeLat: 43.738, homeLng: 7.424,
    shiftStartMin: 480, shiftEndMin: 1020, maxDailyMinutes: 540, qualifications: { create: { serviceId: id("service") } },
    depotAssignments: { create: { depotId: id("depot"), effectiveDate: stamp("1900-01-01") } },
    availabilityVersions: { create: { effectiveDate: stamp("1900-01-01"), days: { create: Array.from({ length: 7 }, (_, dayOfWeek) => ({
      dayOfWeek, available: true, shiftStartMin: 480, shiftEndMin: 1020 })) } } } } });
}

async function setUp() {
  await removeMetro();
  await prisma.metro.create({ data: { id: METRO, name: "API repair smoke", timezone: ZONE } });
  await prisma.serviceCatalog.create({ data: { id: id("service"), code: id("service"), name: "API repair service", estDurationMin: 45 } });
  for (const client of [clientId, otherClientId]) await prisma.client.create({ data: { id: client, name: client } });
  await prisma.dealership.create({ data: { id: id("dealer"), clientId, name: "API repair dealer" } });
  await prisma.depot.create({ data: { id: id("depot"), metroId: METRO, dealershipId: id("dealer"), name: "API repair depot", lat: 43.735, lng: 7.420,
    endpointPolicies: { create: { effectiveDate: stamp("1900-01-01"), departure: "DEPOT", returnTo: "DEPOT" } } } });
  await technician("a");
  await technician("b");
  const saved = await storeSolverSettings(clientId, null, { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67",
    travelBufferPercent: "10", travelBufferMinutes: 2, fairnessBudgetPercent: "2", offerLimit: 2, bookingHorizonWeekdays: 2 });
  assert.ok("saved" in saved);
  const token = generateTenantToken();
  await prisma.tenant.create({ data: { id: clientId, name: clientId } });
  await prisma.tenantApiToken.create({ data: { id: randomUUID(), tenantId: clientId, tokenSha256: tenantTokenSha256(token), label: "API repair smoke" } });
  process.env.SCHEDULER_API_URL = base;
  process.env[tokenVariable(clientId)] = token;
  await prisma.customer.create({ data: { id: id("customer"), clientId, firstName: "Repair", lastName: "Smoke", email: "repair@example.invalid", phone: "0000000000" } });
  await prisma.address.create({ data: { id: id("address"), customerId: id("customer"), line1: "Fixture address", city: "Monaco", state: "MC", postalCode: "98000", lat: 43.748, lng: 7.438 } });
  await prisma.job.create({ data: { id: id("job"), customerId: id("customer"), addressId: id("address"), serviceId: id("service"), durationMin: 45, status: "SCHEDULED" } });
  await prisma.appointment.create({ data: { id: id("visit"), jobId: id("job"), technicianId: id("a"), serviceDate: stamp(DATE), sequence: 0,
    windowStart: localMinute(DATE, 480, false, ZONE), windowEnd: localMinute(DATE, 1020, true, ZONE),
    plannedStart: localMinute(DATE, 540, false, ZONE), plannedEnd: localMinute(DATE, 585, false, ZONE) } });
}

const refusedWith = (status: number) => (error: unknown) => error instanceof TimeOffRefused && error.status === status;

async function analyzeFully(requestId: string) {
  for (let step = 0; step < 10; step++) {
    const result = await analyzeApiTimeOff(clientId, requestId);
    if (result.done) return result;
  }
  throw new Error("Analysis did not finish");
}

async function report(requestId: string) {
  const row = await prisma.timeOffReport.findUniqueOrThrow({ where: { requestId } });
  return { status: row.status, report: apiTimeOffReport.parse(row.data) };
}

async function main() {
  await setUp();
  try {
    // Edits the portal now makes itself.
    await assert.rejects(setApiAvailability(clientId, { technicianId: id("a"), date: DATE, available: false }), refusedWith(409), "A day with appointments needs repair first");
    await assert.rejects(setApiQualification(clientId, { technicianId: id("a"), serviceId: id("service"), qualified: false }), refusedWith(409));
    await assert.rejects(setApiAvailability(otherClientId, { technicianId: id("b"), date: LATER, available: false }), refusedWith(404), "Another client's technician");
    await setApiAvailability(clientId, { technicianId: id("b"), date: LATER, available: false });
    assert.equal((await prisma.technicianShiftOverride.findUniqueOrThrow({ where: { technicianId_serviceDate: { technicianId: id("b"), serviceDate: stamp(LATER) } } })).available, false);

    // A day off with no shift is approvable as it is; denying it ends it.
    const free = await submitApiTimeOff(clientId, { technicianId: id("b"), firstDate: LATER, lastDate: LATER, startMin: 480, endMin: 1020, category: "Other", reason: "Smoke" });
    assert.equal((await analyzeFully(free.requestId)).status, "READY");
    assert.equal((await report(free.requestId)).report.days[0]?.status, "NO_SHIFT");
    assert.deepEqual(await denyApiTimeOff(clientId, free.requestId), { requestId: free.requestId, status: "DENIED" });
    await deleteApiAvailability(clientId, { technicianId: id("b"), date: LATER });
    assert.equal(await prisma.technicianShiftOverride.count({ where: { technicianId: id("b") } }), 0);

    const request = await submitApiTimeOff(clientId, { technicianId: id("a"), firstDate: DATE, lastDate: NEXT, startMin: 480, endMin: 1020,
      category: "Vacation / personal travel", reason: "Repair smoke" });
    await assert.rejects(submitApiTimeOff(clientId, { technicianId: id("a"), firstDate: NEXT, lastDate: NEXT, startMin: 600, endMin: 700, category: "Other", reason: "Overlap" }),
      refusedWith(409), "Overlapping requests are refused");
    const first = await analyzeApiTimeOff(clientId, request.requestId);
    assert.deepEqual({ done: first.done, progress: first.progress }, { done: false, progress: 50 }, "One day per analysis step");
    assert.equal((await analyzeFully(request.requestId)).status, "READY");
    const analyzed = await report(request.requestId);
    assert.deepEqual(analyzed.report.days.map(day => day.status), ["REPAIR_PREVIEW", "NO_APPOINTMENTS"], JSON.stringify(analyzed.report));
    assert.equal(analyzed.report.days[0]?.reassigned_jobs, 1, "The visit moves to the other technician");
    await assert.rejects(approveApiTimeOff(otherClientId, request.requestId), refusedWith(404), "Another client cannot approve it");

    // A change after the analysis writes nothing and sends the request back.
    await prisma.appointment.update({ where: { id: id("visit") }, data: { plannedStart: localMinute(DATE, 540, false, ZONE) } });
    await assert.rejects(approveApiTimeOff(clientId, request.requestId), refusedWith(409));
    assert.equal((await prisma.appointment.findUniqueOrThrow({ where: { id: id("visit") } })).technicianId, id("a"), "Nothing was written");
    assert.equal((await prisma.timeOffRequest.findUniqueOrThrow({ where: { id: request.requestId } })).status, "PENDING");
    assert.equal((await report(request.requestId)).status, "AWAITING_ANALYSIS");

    assert.equal((await analyzeFully(request.requestId)).status, "READY");
    assert.deepEqual(await approveApiTimeOff(clientId, request.requestId), { requestId: request.requestId, status: "APPROVED" });
    const moved = await prisma.appointment.findUniqueOrThrow({ where: { id: id("visit") } });
    assert.equal(moved.technicianId, id("b"), "The repair moved the visit to the technician who is working");
    assert.equal((await report(request.requestId)).status, "APPLIED");
    const repair = (await report(request.requestId)).report.days[0]?.proposal_id;
    assert.ok((await prisma.portalApiDailyProposal.findUniqueOrThrow({ where: { id: repair } })).receiptId, "The repair's receipt is recorded");
    assert.deepEqual(await approveApiTimeOff(clientId, request.requestId), { requestId: request.requestId, status: "APPROVED" }, "Approving again changes nothing");
    console.log("API repair smoke passed");
  } finally {
    await removeMetro();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
