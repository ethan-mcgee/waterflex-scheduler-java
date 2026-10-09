// Appointment cancellation and dispatch road geometry through the public scheduling API, end to end against a running
// scheduler and the fixture router. Two clients share the metro: each sees and cancels only its own appointments. A
// cancellation before the day's cutoff re-times and renumbers the technician's remaining visits in the same write; a
// frozen day keeps its planned times. The fixture router drives 10 minutes between its two points and none within one.
// The scheduler must route this smoke's metro to the fixture router:
//   ROUTING_METRO_URLS=api-cancel-smoke=http://127.0.0.1:18001   SCHEDULER_TEST_URL=http://127.0.0.1:18000
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { prisma } from "../lib/prisma";
import { generateTenantToken, tenantTokenSha256 } from "../lib/tenantTokens";
import { storeSolverSettings } from "../lib/clientSettingsStore";
import { addCalendarDays, todayInTz } from "../lib/date";
import { localMinute } from "../lib/zonedTime";
import { cancelApiAppointment } from "../lib/apiCancel";
import { ChangeRefused } from "../lib/apiMasterDataCore";
import { apiDispatchGeometry } from "../lib/apiGeometry";
import { isDispatchGeometry } from "../lib/dispatchGeometry";
import { currentLastModified } from "../lib/apiReceipt";
import { tokenVariable } from "../lib/schedulerApi";

const METRO = "api-cancel-smoke";
const ZONE = "America/Chicago";
const run = randomUUID().slice(0, 8);
const A = `api-cancel-${run}-a`;
const B = `api-cancel-${run}-b`;
const of = (client: string, name: string) => `${client}-${name}`;
const stamp = (date: string) => new Date(`${date}T00:00:00Z`);
// Two days ahead, so the day is never frozen by the 6 a.m. cutoff while the smoke runs; yesterday is always frozen.
const DATE = addCalendarDays(todayInTz(ZONE), 2);
const PAST = addCalendarDays(todayInTz(ZONE), -1);
const DEPOT_POINT = { lat: 43.735, lng: 7.42 };
const VISIT_POINT = { lat: 43.748, lng: 7.438 };
const base = process.env.SCHEDULER_TEST_URL;
assert.ok(base, "SCHEDULER_TEST_URL must point at a running scheduler");

async function removeMetro() {
  const like = "api-cancel-%";
  await prisma.$executeRaw`DELETE FROM tenant_api_token WHERE "tenantId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM tenant WHERE id LIKE ${like}`;
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

/** A visit of the client's technician; `name` names its job and appointment alike. */
async function visit(client: string, name: string, date: string, point: { lat: number; lng: number }, sequence: number, plannedMin: number) {
  await prisma.address.create({ data: { id: of(client, `${name}-address`), customerId: of(client, "customer"), line1: "Fixture address", city: "Monaco", state: "MC", postalCode: "98000", ...point } });
  await prisma.job.create({ data: { id: of(client, name), customerId: of(client, "customer"), addressId: of(client, `${name}-address`), serviceId: `api-cancel-${run}-service`,
    durationMin: 45, status: "SCHEDULED" } });
  await prisma.appointment.create({ data: { id: of(client, name), jobId: of(client, name), technicianId: of(client, "tech"), serviceDate: stamp(date), sequence,
    windowStart: localMinute(date, 480, false, ZONE), windowEnd: localMinute(date, 720, true, ZONE),
    plannedStart: localMinute(date, plannedMin, false, ZONE), plannedEnd: localMinute(date, plannedMin + 45, false, ZONE) } });
}

/** A client with one technician working from a depot at DEPOT_POINT, starting and ending there, connected to the scheduler. */
async function client(clientId: string) {
  await prisma.client.create({ data: { id: clientId, name: clientId } });
  await prisma.dealership.create({ data: { id: of(clientId, "dealer"), clientId, name: `${clientId} dealer` } });
  await prisma.depot.create({ data: { id: of(clientId, "depot"), metroId: METRO, dealershipId: of(clientId, "dealer"), name: `${clientId} depot`, ...DEPOT_POINT,
    endpointPolicies: { create: { effectiveDate: stamp("1900-01-01"), departure: "DEPOT", returnTo: "DEPOT" } } } });
  await prisma.technician.create({ data: { id: of(clientId, "tech"), clientId, name: `${clientId} tech`, color: "#000000", homeLat: DEPOT_POINT.lat, homeLng: DEPOT_POINT.lng,
    shiftStartMin: 480, shiftEndMin: 1020, maxDailyMinutes: 540, qualifications: { create: { serviceId: `api-cancel-${run}-service` } },
    depotAssignments: { create: { depotId: of(clientId, "depot"), effectiveDate: stamp("1900-01-01") } },
    availabilityVersions: { create: { effectiveDate: stamp("1900-01-01"), days: { create: Array.from({ length: 7 }, (_, dayOfWeek) => ({
      dayOfWeek, available: true, shiftStartMin: 480, shiftEndMin: 1020 })) } } } } });
  await prisma.customer.create({ data: { id: of(clientId, "customer"), clientId, firstName: "Cancel", lastName: "Smoke", email: "cancel@example.invalid", phone: "0000000000" } });
  const saved = await storeSolverSettings(clientId, null, { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67",
    travelBufferPercent: "0", travelBufferMinutes: 0, fairnessBudgetPercent: "2", offerLimit: 2, bookingHorizonWeekdays: 2 });
  assert.ok("saved" in saved);
  const token = generateTenantToken();
  await prisma.tenant.create({ data: { id: clientId, name: clientId } });
  await prisma.tenantApiToken.create({ data: { id: randomUUID(), tenantId: clientId, tokenSha256: tenantTokenSha256(token), label: "API cancel smoke" } });
  process.env[tokenVariable(clientId)] = token;
}

async function setUp() {
  await removeMetro();
  await prisma.metro.create({ data: { id: METRO, name: "API cancel smoke", timezone: ZONE } });
  await prisma.serviceCatalog.create({ data: { id: `api-cancel-${run}-service`, code: `api-cancel-${run}-service`, name: "API cancel service", estDurationMin: 45 } });
  process.env.SCHEDULER_API_URL = base;
  await client(A);
  await client(B);
  // A's day: out to the visit point and back to the depot point. Without the first visit, the second starts at once.
  await visit(A, "far", DATE, VISIT_POINT, 0, 490);
  await visit(A, "near", DATE, DEPOT_POINT, 1, 545);
  await visit(A, "past-1", PAST, VISIT_POINT, 0, 490);
  await visit(A, "past-2", PAST, DEPOT_POINT, 1, 545);
  await visit(B, "own", DATE, VISIT_POINT, 0, 490);
}

const refused = (status: number) => (error: unknown) => error instanceof ChangeRefused && error.status === status;
const appointment = (appointmentId: string) => prisma.appointment.findUniqueOrThrow({ where: { id: appointmentId },
  select: { sequence: true, plannedStart: true, plannedEnd: true, cancelledAt: true, cancellationReason: true, job: { select: { status: true } } } });
const versionOf = async (date: string) => (await currentLastModified(prisma, [{ technicianId: of(A, "tech"), serviceDate: date }])).get(`${of(A, "tech")}|${date}`);

async function geometry(clientId: string) {
  const drawn = await apiDispatchGeometry(clientId, METRO, DATE);
  assert.ok(isDispatchGeometry(drawn, DATE, "current"), "The board accepts the drawing");
  return drawn;
}

async function main() {
  await setUp();
  try {
    // Each client's board draws only its own technician and stops, though both work in the metro from the same point.
    const before = await geometry(A);
    assert.deepEqual(before.stops.map(stop => [stop.id, stop.sequence]), [[of(A, "far"), 0], [of(A, "near"), 1]]);
    assert.equal(before.features.length, 3, "Depot, far visit, near visit and back to the depot are three legs");
    assert.deepEqual(before.endpoints?.map(endpoint => endpoint.technicianId), [of(A, "tech")]);
    const other = await geometry(B);
    assert.deepEqual(other.stops.map(stop => stop.id), [of(B, "own")], "Another client's board shows none of this client's stops");
    assert.ok(other.features.every(feature => feature.properties.technicianId === of(B, "tech")));

    // A cancellation needs a reason, and another client cannot cancel this client's appointment.
    await assert.rejects(cancelApiAppointment(A, of(A, "far"), "   "), refused(400));
    await assert.rejects(cancelApiAppointment(B, of(A, "far"), "Not mine"), refused(404));
    assert.equal((await appointment(of(A, "far"))).cancelledAt, null);

    // Cancelling the far visit re-times the near one, now first and at the depot point, to the start of its window.
    const open = await versionOf(DATE);
    assert.deepEqual(await cancelApiAppointment(A, of(A, "far"), "  Customer rescheduled  "), { success: true, appointmentId: of(A, "far"), alreadyCancelled: false });
    const cancelled = await appointment(of(A, "far"));
    assert.ok(cancelled.cancelledAt !== null);
    assert.equal(cancelled.cancellationReason, "Customer rescheduled");
    assert.equal(cancelled.job.status, "CANCELLED");
    const near = await appointment(of(A, "near"));
    assert.equal(near.sequence, 0, "The remaining visit is renumbered");
    assert.equal(near.plannedStart.toISOString(), localMinute(DATE, 480, false, ZONE).toISOString(), "The remaining visit is re-timed");
    assert.equal(near.plannedEnd.toISOString(), localMinute(DATE, 525, false, ZONE).toISOString());
    assert.notEqual(await versionOf(DATE), open, "The day's lastModified moves");
    assert.deepEqual(await cancelApiAppointment(A, of(A, "far"), "Again"), { success: true, appointmentId: of(A, "far"), alreadyCancelled: true });
    assert.equal((await appointment(of(A, "far"))).cancellationReason, "Customer rescheduled", "A repeated cancellation changes nothing");

    const after = await geometry(A);
    assert.deepEqual(after.stops.map(stop => [stop.id, stop.sequence, stop.plannedStart]), [[of(A, "near"), 0, localMinute(DATE, 480, false, ZONE).toISOString()]]);
    assert.deepEqual((await geometry(B)).stops.map(stop => stop.id), [of(B, "own")], "Another client's day is untouched");

    // Cancelling the last visit leaves a worked day with nothing to draw.
    await cancelApiAppointment(A, of(A, "near"), "Customer cancelled");
    const empty = await geometry(A);
    assert.deepEqual([empty.stops.length, empty.features.length], [0, 0]);
    assert.deepEqual(empty.endpoints?.map(endpoint => endpoint.technicianId), [of(A, "tech")]);

    // A frozen day keeps its planned times: only the cancelled visit changes.
    const frozenBefore = await appointment(of(A, "past-2"));
    await cancelApiAppointment(A, of(A, "past-1"), "Customer was away");
    assert.equal((await appointment(of(A, "past-1"))).job.status, "CANCELLED");
    assert.deepEqual(await appointment(of(A, "past-2")), frozenBefore, "A frozen day's remaining visit is not re-timed");
    console.log("API cancel and geometry smoke passed");
  } finally {
    await removeMetro();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
