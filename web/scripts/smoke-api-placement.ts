// Placing and moving on the schedule through the public scheduling API, end to end against a running scheduler and the
// fixture router: a waiting job is previewed and saved onto an empty route, a visit moved to another technician inside
// its window keeps its promise, a move to another window is refused without the acknowledgement and recorded with it,
// and a start the fresh answer does not offer is refused. The scheduler must route this smoke's metro to the fixture
// router:
//   ROUTING_METRO_URLS=api-placement-smoke=http://127.0.0.1:18001   SCHEDULER_TEST_URL=http://127.0.0.1:18000
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { prisma } from "../lib/prisma";
import { issueToken } from "./smokeTokens";
import { storeSolverSettings } from "../lib/clientSettingsStore";
import { addCalendarDays, todayInTz } from "../lib/date";
import { localMinute } from "../lib/zonedTime";
import { tokenVariable } from "../lib/schedulerApi";
import { previewPlacement, savePlacement } from "../lib/apiPlacement";
import { PlacementRefused } from "../lib/apiPlacementCore";
import { findGap, gapSpot, type Spot } from "../lib/placementRules";
import type { InsertionStart } from "../lib/insertionContract";

const METRO = "api-placement-smoke";
const ZONE = "America/Chicago";
const run = randomUUID().slice(0, 8);
const clientId = `api-placement-${run}`;
const otherClientId = `api-placement-${run}-other`;
const id = (name: string) => `${clientId}-${name}`;
const stamp = (date: string) => new Date(`${date}T00:00:00Z`);
// Two days ahead, so the day is never frozen by the 6 a.m. cutoff while the smoke runs.
const DATE = addCalendarDays(todayInTz(ZONE), 2);
const CUSTOMER = { lat: 43.748, lng: 7.438 };

const base = process.env.SCHEDULER_TEST_URL;
assert.ok(base, "SCHEDULER_TEST_URL must point at a running scheduler");

async function removeMetro() {
  const like = "api-placement-%";
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
  await prisma.technician.create({ data: { id: id(name), clientId, name: `Placement smoke ${name}`, color: "#000000", homeLat: 43.735, homeLng: 7.42,
    shiftStartMin: 480, shiftEndMin: 1020, maxDailyMinutes: 540, qualifications: { create: { serviceId: id("service") } },
    depotAssignments: { create: { depotId: id("depot"), effectiveDate: stamp("1900-01-01") } },
    availabilityVersions: { create: { effectiveDate: stamp("1900-01-01"), days: { create: Array.from({ length: 7 }, (_, dayOfWeek) => ({
      dayOfWeek, available: true, shiftStartMin: 480, shiftEndMin: 1020 })) } } } } });
}

async function customer(name: string, owner = clientId) {
  await prisma.customer.create({ data: { id: id(`customer-${name}`), clientId: owner, firstName: "Placement", lastName: name, email: `${name}@example.invalid`, phone: "0000000000" } });
  await prisma.address.create({ data: { id: id(`address-${name}`), customerId: id(`customer-${name}`), line1: "Fixture address", city: "Monaco", state: "MC",
    postalCode: "98000", ...CUSTOMER, geocodePrecision: "ROOFTOP" } });
}

/** A 45-minute visit on technician a, promised 9 to 11 a.m. and planned at 9. */
async function visit(name: string) {
  await customer(name);
  await prisma.job.create({ data: { id: id(`job-${name}`), customerId: id(`customer-${name}`), addressId: id(`address-${name}`), serviceId: id("service"), durationMin: 45, status: "SCHEDULED" } });
  await prisma.appointment.create({ data: { id: id(`appt-${name}`), jobId: id(`job-${name}`), technicianId: id("a"), serviceDate: stamp(DATE), sequence: 0,
    windowStart: localMinute(DATE, 540, false, ZONE), windowEnd: localMinute(DATE, 660, true, ZONE),
    plannedStart: localMinute(DATE, 540, false, ZONE), plannedEnd: localMinute(DATE, 585, false, ZONE) } });
  return id(`appt-${name}`);
}

/** A 45-minute job waiting for the calendar, with its follow-up open. */
async function waitingJob(name: string) {
  await customer(name);
  await prisma.job.create({ data: { id: id(`job-${name}`), customerId: id(`customer-${name}`), addressId: id(`address-${name}`), serviceId: id("service"),
    durationMin: 45, status: "PENDING", manualFollowUpStatus: "CONTACTED", manualFollowUpReason: "ADDRESS_UNVERIFIED" } });
  return id(`job-${name}`);
}

async function setUp() {
  await removeMetro();
  await prisma.metro.create({ data: { id: METRO, name: "API placement smoke", timezone: ZONE } });
  await prisma.serviceCatalog.create({ data: { id: id("service"), code: id("service"), name: "API placement service", estDurationMin: 45 } });
  for (const client of [clientId, otherClientId]) await prisma.client.create({ data: { id: client, name: client } });
  await prisma.dealership.create({ data: { id: id("dealer"), clientId, name: "API placement dealer" } });
  await prisma.depot.create({ data: { id: id("depot"), metroId: METRO, dealershipId: id("dealer"), name: "API placement depot", lat: 43.735, lng: 7.42,
    endpointPolicies: { create: { effectiveDate: stamp("1900-01-01"), departure: "DEPOT", returnTo: "DEPOT" } } } });
  await technician("a");
  await technician("b");
  const saved = await storeSolverSettings(clientId, null, { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67",
    travelBufferPercent: "10", travelBufferMinutes: 2, arrivalBufferPercent: 25, arrivalBufferMinMinutes: 15, arrivalBufferMaxMinutes: 60,
    fairnessBudgetPercent: "2", offerLimit: 2, windowHours: 2, bookingHorizonWeekdays: 10, horizonMode: "FIXED", horizonLeadWeekdays: 2,
    horizonFullPercent: 85, dailySearch: "TABU_KOPT", costSearchSeconds: 10, fairnessSearchSeconds: 5, unimprovedSearchSeconds: null,
    bookingDeadlineMs: 5000, regularWindowThreshold: 2 });
  assert.ok("saved" in saved, "The smoke's solver settings are stored");
  const { token } = await issueToken(clientId, clientId, "API placement smoke");
  process.env.SCHEDULER_API_URL = base;
  process.env[tokenVariable(clientId)] = token;
}

const refusedWith = (status: number, message: RegExp) => (error: unknown) =>
  error instanceof PlacementRefused && error.status === status && message.test(error.message);

/** A start of the spot's gap in a fresh preview, picked by `pick`. */
async function startAt(input: { jobId?: string; appointmentId?: string }, technician: string, pick: (start: InsertionStart) => boolean) {
  const preview = await previewPlacement(clientId, METRO, input, [DATE], [DATE], true, []);
  const day = preview.answer.technicianDays.find(item => item.technicianId === id(technician) && item.serviceDate === DATE);
  assert.ok(day !== undefined, `Technician ${technician} is in the answer`);
  for (const gap of day.gaps) {
    const start = gap.starts.find(pick);
    if (start !== undefined) return { preview, spot: gapSpot(day, gap), start };
  }
  throw new Error(`No start on technician ${technician} matches`);
}

async function main() {
  await setUp();
  try {
    const x = await visit("x");
    const job = await waitingJob("j");

    // One preview covers every gap of both routes: a has a stop, so two gaps; b is empty, so one.
    const preview = await previewPlacement(clientId, METRO, { jobId: job }, [DATE], [DATE], true, []);
    const days = new Map(preview.answer.technicianDays.map(day => [day.technicianId, day]));
    assert.equal(days.get(id("a"))?.gaps.length, 2);
    assert.equal(days.get(id("b"))?.gaps.length, 1);
    assert.equal(preview.original, null);
    for (const day of preview.answer.technicianDays) for (const gap of day.gaps) {
      assert.equal(gap.routable, true, "Every fixture leg has a road route");
      assert.ok(gap.starts.every(start => Date.parse(start.plannedStart) % (30 * 60_000) === 0), "Starts fall on the half hour");
    }

    // Saving the job on b creates its appointment with the promised window, and closes its follow-up.
    const placed = await startAt({ jobId: job }, "b", () => true);
    const saved = await savePlacement(clientId, METRO, { jobId: job }, placed.spot, placed.start.plannedStart, false, []);
    assert.deepEqual(saved, { saved: true, appointmentId: job, kind: "NEW" });
    const created = await prisma.appointment.findUniqueOrThrow({ where: { id: job }, include: { job: true } });
    assert.equal(created.technicianId, id("b"));
    assert.equal(created.windowStart.toISOString(), new Date(placed.start.window.start).toISOString());
    assert.equal(created.plannedStart.toISOString(), new Date(placed.start.plannedStart).toISOString());
    assert.equal(created.job.status, "SCHEDULED");
    assert.equal(created.job.manualFollowUpStatus, "RESOLVED");
    await assert.rejects(savePlacement(clientId, METRO, { jobId: job }, placed.spot, placed.start.plannedStart, false, []),
      refusedWith(409, /not waiting/), "A placed job cannot be placed again");

    // Moving x to b inside its window keeps the promise: both days are rewritten, nothing is recorded.
    const kept = await startAt({ appointmentId: x }, "b", start => start.keepsWindow === true);
    assert.equal(kept.preview.original?.technicianId, id("a"));
    const keptSave = await savePlacement(clientId, METRO, { appointmentId: x }, kept.spot, kept.start.plannedStart, false, []);
    assert.equal(keptSave.kind, "KEEPS_PROMISE");
    const movedKept = await prisma.appointment.findUniqueOrThrow({ where: { id: x } });
    assert.equal(movedKept.technicianId, id("b"));
    assert.equal(movedKept.windowStart.toISOString(), localMinute(DATE, 540, false, ZONE).toISOString(), "The promised window is unchanged");
    assert.equal(await prisma.appointmentWindowChange.count({ where: { appointmentId: x } }), 0);

    // Moving it to another window needs the acknowledgement, then records the change with the move.
    const changed = await startAt({ appointmentId: x }, "a", start => start.keepsWindow === false);
    await assert.rejects(savePlacement(clientId, METRO, { appointmentId: x }, changed.spot, changed.start.plannedStart, false, []),
      refusedWith(400, /Acknowledge the policy violation/));
    assert.equal((await prisma.appointment.findUniqueOrThrow({ where: { id: x } })).technicianId, id("b"), "A refused save writes nothing");
    const changedSave = await savePlacement(clientId, METRO, { appointmentId: x }, changed.spot, changed.start.plannedStart, true, []);
    assert.equal(changedSave.kind, "CHANGES_PROMISE");
    const movedChanged = await prisma.appointment.findUniqueOrThrow({ where: { id: x } });
    assert.equal(movedChanged.technicianId, id("a"));
    assert.equal(movedChanged.windowStart.toISOString(), new Date(changed.start.window.start).toISOString());
    const record = await prisma.appointmentWindowChange.findFirstOrThrow({ where: { appointmentId: x } });
    assert.equal(record.fromWindowStart.toISOString(), localMinute(DATE, 540, false, ZONE).toISOString());
    assert.equal(record.toWindowStart.toISOString(), new Date(changed.start.window.start).toISOString());

    // A start the fresh answer does not offer is refused, and another client's appointment is not found.
    const nowhere: Spot = { technicianId: id("a"), serviceDate: DATE };
    const odd = localMinute(DATE, 487, false, ZONE).toISOString();
    await assert.rejects(savePlacement(clientId, METRO, { appointmentId: x }, nowhere, odd, true, []), refusedWith(409, /no longer fits/));
    assert.equal(findGap(preview.answer, nowhere), undefined);
    await assert.rejects(previewPlacement(otherClientId, METRO, { appointmentId: x }, [DATE], [DATE], true, []), refusedWith(404, /Appointment not found/));
    console.log("Placement smoke passed: preview, save, kept and changed promises, refusals");
  } finally {
    await removeMetro();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
