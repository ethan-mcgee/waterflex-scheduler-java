// Client snapshots against a migrated database: two clients in the same metro each get only their own technicians and
// appointments, every snapshot matches the OpenAPI `Snapshot` schema, and a technician-day's lastModified changes
// exactly when something affecting that day changes. Uses throwaway rows and removes them afterwards.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { load } from "js-yaml";
import Ajv2020 from "ajv/dist/2020";
import addFormats from "ajv-formats";
import { prisma } from "../lib/prisma";
import { buildClientSnapshot, SnapshotError } from "../lib/clientSnapshot";
import { initialAvailability } from "../lib/technicianAvailability";
import { storeSolverSettings } from "../lib/clientSettingsStore";

const run = randomUUID().slice(0, 8);
const id = (name: string) => `snap-${run}-${name}`;
const metroId = id("metro");
const clients = { a: id("client-a"), b: id("client-b") };
const DATE = "2026-11-16"; // a Monday
const NEXT = "2026-11-17";
const stamp = (date: string) => new Date(`${date}T00:00:00Z`);
const spec = load(readFileSync(join("..", "docs", "api", "openapi-v1.yaml"), "utf8"));
assert.ok(spec !== null && typeof spec === "object" && "components" in spec);
const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
ajv.addSchema({ $id: "spec", components: spec.components }, "spec");
const validateSnapshot = ajv.compile({ $ref: "spec#/components/schemas/Snapshot" });

async function setUp() {
  await prisma.metro.create({ data: { id: metroId, name: "Snapshot metro", timezone: "America/Chicago" } });
  await prisma.serviceCatalog.create({ data: { id: id("service"), code: id("service"), name: "Snapshot service", estDurationMin: 60 } });
  for (const [side, clientId] of Object.entries(clients)) {
    await prisma.client.create({ data: { id: clientId, name: `Snapshot ${side}` } });
    await prisma.dealership.create({ data: { id: id(`dealer-${side}`), clientId, name: `Dealer ${side}` } });
    // A second client in the same metro is the real requirement; the temporary one-client-per-metro rule (dropped in
    // S6 P5) is lifted only for this insert so the builder's own isolation can be proven now.
    if (side === "b") await prisma.$executeRawUnsafe("ALTER TABLE depot DISABLE TRIGGER depot_metro_one_client");
    try {
      await prisma.depot.create({ data: { id: id(`depot-${side}`), metroId, dealershipId: id(`dealer-${side}`), name: `Depot ${side}`,
        lat: side === "a" ? 41.25 : 41.2, lng: side === "a" ? -95.93 : -96.0,
        endpointPolicies: { create: { effectiveDate: stamp("1900-01-01"), departure: "DEPOT", returnTo: "HOME" } } } });
    } finally {
      if (side === "b") await prisma.$executeRawUnsafe("ALTER TABLE depot ENABLE TRIGGER depot_metro_one_client");
    }
    await prisma.technician.create({ data: { id: id(`tech-${side}`), clientId, name: `Tech ${side}`, color: "#000000",
      homeLat: 41.3, homeLng: -96.05, shiftStartMin: 480, shiftEndMin: 1020, maxDailyMinutes: 540,
      qualifications: { create: { serviceId: id("service") } },
      depotAssignments: { create: { depotId: id(`depot-${side}`), effectiveDate: stamp("1900-01-01") } },
      availabilityVersions: initialAvailability(480, 1020) } });
    await prisma.customer.create({ data: { id: id(`customer-${side}`), clientId, firstName: "Snap", lastName: side, email: `${side}@example.invalid`, phone: "0000000000" } });
    await prisma.address.create({ data: { id: id(`address-${side}`), customerId: id(`customer-${side}`), line1: "1 Main St", city: "Omaha", state: "NE", postalCode: "68102", lat: 41.26, lng: -95.94 } });
    await prisma.job.create({ data: { id: id(`job-${side}`), customerId: id(`customer-${side}`), addressId: id(`address-${side}`), serviceId: id("service"), durationMin: 60, status: "SCHEDULED" } });
    await prisma.appointment.create({ data: { id: id(`appt-${side}`), jobId: id(`job-${side}`), technicianId: id(`tech-${side}`), serviceDate: stamp(DATE),
      windowStart: new Date(`${DATE}T14:00:00Z`), windowEnd: new Date(`${DATE}T18:00:00Z`), plannedStart: new Date(`${DATE}T14:30:00Z`),
      plannedEnd: new Date(`${DATE}T15:30:00Z`), sequence: 0 } });
  }
  const saved = await storeSolverSettings(clients.a, null, { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67",
    travelBufferPercent: "20", travelBufferMinutes: 5, fairnessBudgetPercent: "2", offerLimit: 4, bookingHorizonWeekdays: 10 });
  assert.ok("saved" in saved);
}

async function tearDown() {
  const like = `snap-${run}-%`;
  await prisma.$executeRaw`DELETE FROM time_off_interval WHERE "requestId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM time_off_request WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM appointment WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM job WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM address WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM customer WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM technician_shift_override WHERE "technicianId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM schedule_day WHERE "technicianId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM technician_day_change WHERE "technicianId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM technician_availability_day WHERE "versionId" IN (SELECT id FROM technician_availability_version WHERE "technicianId" LIKE ${like})`;
  await prisma.$executeRaw`DELETE FROM technician_availability_version WHERE "technicianId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM technician_depot_assignment WHERE "technicianId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM technician_qualification WHERE "technicianId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM technician WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM depot_endpoint_policy WHERE "depotId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM depot WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM dealership WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM client_solver_settings WHERE "clientId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM client WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM service_catalog WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM metro WHERE id LIKE ${like}`;
}

async function snapshot(clientId = clients.a) {
  const built = await buildClientSnapshot(clientId, metroId, [DATE, NEXT]);
  assert.ok(validateSnapshot(built), ajv.errorsText(validateSnapshot.errors));
  return built;
}

async function changeTimes() {
  const built = await snapshot();
  return Object.fromEntries(built.technicianDays.map(day => [day.serviceDate, day.lastModified]));
}

/** Runs a change and reports which of client A's days got a new lastModified; every new value is later than the old. */
async function changedDays(change: () => Promise<unknown>): Promise<string[]> {
  const before = await changeTimes();
  await change();
  const after = await changeTimes();
  const moved = Object.keys(before).filter(date => before[date] !== after[date]).sort();
  for (const date of moved) assert.ok((after[date] ?? "") > (before[date] ?? ""), `lastModified moved backwards on ${date}`);
  return moved;
}

async function main() {
  await setUp();
  try {
    const own = await snapshot();
    assert.deepEqual(own.technicians.map(technician => technician.id), [id("tech-a")], "Only client A's technician, though B works in the same metro");
    assert.deepEqual(own.appointments.map(appointment => appointment.id), [id("appt-a")], "Only client A's appointment");
    assert.deepEqual(own.technicianDays.map(day => day.serviceDate), [DATE, NEXT]);
    assert.deepEqual(own.technicianDays[0]?.start, { lat: 41.25, lng: -95.93 }, "Departs from its own depot");
    assert.deepEqual(own.technicianDays[0]?.end, { lat: 41.3, lng: -96.05 }, "Returns home");
    assert.deepEqual(own.technicianDays[0]?.shift, { start: `${DATE}T14:00:00.000Z`, end: `${DATE}T23:00:00.000Z` });
    for (const day of own.technicianDays) assert.match(day.lastModified, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/);

    await assert.rejects(buildClientSnapshot(clients.b, metroId, [DATE]), (error: unknown) => error instanceof SnapshotError && error.code === "NOT_CONFIGURED");
    await assert.rejects(buildClientSnapshot(clients.a, id("no-metro"), [DATE]), (error: unknown) => error instanceof SnapshotError && error.code === "METRO_NOT_FOUND");

    assert.deepEqual(await changedDays(async () => undefined), [], "Reading twice changes nothing");
    assert.deepEqual(await changedDays(() => prisma.technician.update({ where: { id: id("tech-a") }, data: { name: "Renamed", email: "a@example.invalid" } })), [],
      "A name or email is not a scheduling fact");
    assert.deepEqual(await changedDays(() => prisma.technician.update({ where: { id: id("tech-b") }, data: { homeLat: 41.31 } })), [],
      "Another client's technician never moves client A's days");
    assert.deepEqual(await changedDays(() => prisma.appointment.update({ where: { id: id("appt-a") }, data: { plannedStart: new Date(`${DATE}T15:00:00Z`) } })), [DATE],
      "Moving an appointment changes only its day");
    assert.deepEqual(await changedDays(() => prisma.address.update({ where: { id: id("address-a") }, data: { lat: 41.27 } })), [DATE],
      "Moving the customer's pin changes the appointment's day");
    assert.deepEqual(await changedDays(() => prisma.technicianShiftOverride.create({ data: { technicianId: id("tech-a"), serviceDate: stamp(NEXT), available: true, shiftStartMin: 540, shiftEndMin: 900 } })), [NEXT],
      "A date exception changes only its day");
    assert.deepEqual(await changedDays(async () => {
      await prisma.timeOffRequest.create({ data: { id: id("timeoff"), technicianId: id("tech-a"), category: "Other", reason: "Snapshot", status: "PENDING" } });
      await prisma.timeOffInterval.create({ data: { requestId: id("timeoff"), serviceDate: stamp(NEXT), startMin: 600, endMin: 660 } });
    }), [NEXT], "Pending time off still changes its day's facts");
    assert.deepEqual(await changedDays(() => prisma.timeOffRequest.update({ where: { id: id("timeoff") }, data: { status: "APPROVED" } })), [NEXT],
      "Approving time off changes its days");
    assert.deepEqual((await snapshot()).technicianDays.find(day => day.serviceDate === NEXT)?.absences,
      [{ start: `${NEXT}T16:00:00.000Z`, end: `${NEXT}T17:00:00.000Z` }]);
    assert.deepEqual(await changedDays(() => prisma.technician.update({ where: { id: id("tech-a") }, data: { homeLat: 41.32 } })), [DATE, NEXT],
      "Moving the technician's home changes every day");
    assert.deepEqual(await changedDays(() => prisma.depotEndpointPolicy.create({ data: { depotId: id("depot-a"), effectiveDate: stamp("2026-01-01"), departure: "HOME", returnTo: "HOME" } })), [DATE, NEXT],
      "A depot's endpoint policy changes every day of its technicians");
    assert.deepEqual(await changedDays(() => prisma.depot.update({ where: { id: id("depot-b") }, data: { lat: 41.21 } })), [],
      "Another client's depot never moves client A's days");
    assert.deepEqual(await changedDays(() => prisma.technicianQualification.deleteMany({ where: { technicianId: id("tech-a") } })), [DATE, NEXT],
      "Qualifications are facts of every day");
    console.log("Client snapshot smoke passed");
  } finally {
    await tearDown();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
