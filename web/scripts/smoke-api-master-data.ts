// Depot and technician depot changes through the public scheduling API, end to end against a running scheduler and
// the fixture router. A change that moves a booked route's start or end re-times that day from the scheduler's route
// evaluation, written with a compare-and-set; one that would make a booked day infeasible is refused and writes nothing.
// The fixture router drives 10 minutes between its two points, so a technician starting at home next to the visit
// reaches it at once, and one starting at the depot ten minutes later. The scheduler must route this smoke's metro to
// the fixture router:
//   ROUTING_METRO_URLS=api-master-smoke=http://127.0.0.1:18001   SCHEDULER_TEST_URL=http://127.0.0.1:18000
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { prisma } from "../lib/prisma";
import { generateTenantToken, tenantTokenSha256 } from "../lib/tenantTokens";
import { storeSolverSettings } from "../lib/clientSettingsStore";
import { addCalendarDays, todayInTz } from "../lib/date";
import { localMinute } from "../lib/zonedTime";
import { assignApiTechnicianDepot, MasterDataRefused, setApiDepotDetails, setApiDepotPolicy } from "../lib/apiMasterData";
import { currentLastModified } from "../lib/apiReceipt";
import { tokenVariable } from "../lib/schedulerApi";

const METRO = "api-master-smoke";
const OTHER_METRO = "api-master-smoke-far";
const ZONE = "America/Chicago";
const run = randomUUID().slice(0, 8);
const clientId = `api-master-${run}`;
const otherClientId = `api-master-${run}-other`;
const id = (name: string) => `${clientId}-${name}`;
const stamp = (date: string) => new Date(`${date}T00:00:00Z`);
// Two days ahead, so the day is never frozen by the 6 a.m. cutoff while the smoke runs.
const DATE = addCalendarDays(todayInTz(ZONE), 2);
const DEPOT_POINT = { lat: 43.735, lng: 7.42 };
const VISIT_POINT = { lat: 43.748, lng: 7.438 };
const base = process.env.SCHEDULER_TEST_URL;
assert.ok(base, "SCHEDULER_TEST_URL must point at a running scheduler");

async function removeMetro() {
  const like = "api-master-%";
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
  await prisma.$executeRaw`DELETE FROM depot_endpoint_policy WHERE "depotId" IN (SELECT id FROM depot WHERE "metroId" IN (${METRO}, ${OTHER_METRO}))`;
  await prisma.$executeRaw`DELETE FROM depot WHERE "metroId" IN (${METRO}, ${OTHER_METRO})`;
  await prisma.$executeRaw`DELETE FROM dealership WHERE "clientId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM client_solver_settings WHERE "clientId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM client WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM service_catalog WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM metro WHERE id IN (${METRO}, ${OTHER_METRO})`;
}

async function depot(name: string, metroId: string, point: { lat: number; lng: number }, departure: "HOME" | "DEPOT") {
  await prisma.depot.create({ data: { id: id(name), metroId, dealershipId: id("dealer"), name: `Master smoke ${name}`, lat: point.lat, lng: point.lng,
    endpointPolicies: { create: { effectiveDate: stamp("1900-01-01"), departure, returnTo: "DEPOT" } } } });
}

async function setUp() {
  await removeMetro();
  for (const [metro, name] of [[METRO, "API master smoke"], [OTHER_METRO, "API master smoke far"]] as const)
    await prisma.metro.create({ data: { id: metro, name, timezone: ZONE } });
  await prisma.serviceCatalog.create({ data: { id: id("service"), code: id("service"), name: "API master service", estDurationMin: 45 } });
  for (const client of [clientId, otherClientId]) await prisma.client.create({ data: { id: client, name: client } });
  await prisma.dealership.create({ data: { id: id("dealer"), clientId, name: "API master dealer" } });
  // The home depot starts routes at home, next to the visit; the other depots start at the depot, ten minutes away.
  await depot("home-start", METRO, DEPOT_POINT, "HOME");
  await depot("depot-start", METRO, DEPOT_POINT, "DEPOT");
  await depot("far", OTHER_METRO, DEPOT_POINT, "DEPOT");
  await prisma.technician.create({ data: { id: id("tech"), clientId, name: "Master smoke tech", color: "#000000", homeLat: VISIT_POINT.lat, homeLng: VISIT_POINT.lng,
    shiftStartMin: 480, shiftEndMin: 1020, maxDailyMinutes: 540, qualifications: { create: { serviceId: id("service") } },
    depotAssignments: { create: { depotId: id("home-start"), effectiveDate: stamp("1900-01-01") } },
    availabilityVersions: { create: { effectiveDate: stamp("1900-01-01"), days: { create: Array.from({ length: 7 }, (_, dayOfWeek) => ({
      dayOfWeek, available: true, shiftStartMin: 480, shiftEndMin: 1020 })) } } } } });
  // A visit that must start by 08:05: reachable from home at 08:00, not from the depot at 08:10.
  await prisma.customer.create({ data: { id: id("customer"), clientId, firstName: "Master", lastName: "Smoke", email: "master@example.invalid", phone: "0000000000" } });
  await prisma.address.create({ data: { id: id("address"), customerId: id("customer"), line1: "Fixture address", city: "Monaco", state: "MC", postalCode: "98000", ...VISIT_POINT } });
  await prisma.job.create({ data: { id: id("job"), customerId: id("customer"), addressId: id("address"), serviceId: id("service"), durationMin: 45, status: "SCHEDULED" } });
  await prisma.appointment.create({ data: { id: id("appt"), jobId: id("job"), technicianId: id("tech"), serviceDate: stamp(DATE), sequence: 0,
    windowStart: localMinute(DATE, 480, false, ZONE), windowEnd: localMinute(DATE, 485, true, ZONE),
    plannedStart: localMinute(DATE, 485, false, ZONE), plannedEnd: localMinute(DATE, 530, false, ZONE) } });
  const saved = await storeSolverSettings(clientId, null, { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67",
    travelBufferPercent: "0", travelBufferMinutes: 0, fairnessBudgetPercent: "2", offerLimit: 2, bookingHorizonWeekdays: 2 });
  assert.ok("saved" in saved);
  const token = generateTenantToken();
  await prisma.tenant.create({ data: { id: clientId, name: clientId } });
  await prisma.tenantApiToken.create({ data: { id: randomUUID(), tenantId: clientId, tokenSha256: tenantTokenSha256(token), label: "API master smoke" } });
  process.env.SCHEDULER_API_URL = base;
  process.env[tokenVariable(clientId)] = token;
}

const refused = (status: number) => (error: unknown) => error instanceof MasterDataRefused && error.status === status;
const appointment = () => prisma.appointment.findUniqueOrThrow({ where: { id: id("appt") }, select: { plannedStart: true, plannedEnd: true, sequence: true } });
const policies = (depotId: string) => prisma.depotEndpointPolicy.findMany({ where: { depotId }, orderBy: { effectiveDate: "asc" }, select: { departure: true, returnTo: true } });
const versionOf = async () => (await currentLastModified(prisma, [{ technicianId: id("tech"), serviceDate: DATE }])).get(`${id("tech")}|${DATE}`);

async function main() {
  await setUp();
  try {
    // A change that would make the booked 08:05 visit unreachable is refused, and nothing is written.
    const before = await appointment();
    const unchanged = await versionOf();
    await assert.rejects(setApiDepotPolicy(clientId, id("home-start"), "DEPOT", "DEPOT"), refused(409));
    assert.deepEqual(await policies(id("home-start")), [{ departure: "HOME", returnTo: "DEPOT" }], "A refused policy is not saved");
    assert.deepEqual(await appointment(), before, "A refused change moves no appointment");
    assert.equal(await versionOf(), unchanged);
    await assert.rejects(assignApiTechnicianDepot(clientId, id("tech"), id("depot-start"), DATE), refused(409), "Moving to a depot that starts too far away is refused");
    assert.equal(await prisma.technicianDepotAssignment.count({ where: { technicianId: id("tech") } }), 1);
    // A move to another metro cannot start on a booked day, or inside the booking horizon.
    await assert.rejects(assignApiTechnicianDepot(clientId, id("tech"), id("far"), DATE), refused(409));
    assert.equal(await prisma.technicianDepotAssignment.count({ where: { technicianId: id("tech") } }), 1);

    // A change the day can absorb is written with the booked visit re-timed: home and visit share a point, so 08:00.
    await setApiDepotPolicy(clientId, id("home-start"), "HOME", "HOME");
    const retimed = await appointment();
    assert.equal(retimed.plannedStart.toISOString(), localMinute(DATE, 480, false, ZONE).toISOString(), "The visit is re-timed from the new start");
    assert.equal(retimed.plannedEnd.toISOString(), localMinute(DATE, 525, false, ZONE).toISOString());
    assert.notEqual(await versionOf(), unchanged, "The re-timed day's lastModified moves");
    assert.equal((await policies(id("home-start"))).at(-1)?.returnTo, "HOME");

    // Moving the technician's depot to the visit's point keeps the day reachable from a depot start.
    await setApiDepotDetails(clientId, id("depot-start"), "Master smoke depot-start", { address: { line1: "1 Fixture Way", city: "Monaco", state: "MC", postalCode: "98000" },
      confirmedPin: VISIT_POINT, candidate: { ...VISIT_POINT, precision: "ROOFTOP" } });
    await assignApiTechnicianDepot(clientId, id("tech"), id("depot-start"), DATE);
    assert.equal((await prisma.technicianDepotAssignment.findFirst({ where: { technicianId: id("tech"), effectiveDate: stamp(DATE) } }))?.depotId, id("depot-start"));
    assert.equal((await appointment()).plannedStart.toISOString(), localMinute(DATE, 480, false, ZONE).toISOString());
    // Moving that depot back out of reach is refused while the visit is booked.
    await assert.rejects(setApiDepotDetails(clientId, id("depot-start"), "Master smoke depot-start", { address: { line1: "2 Fixture Way", city: "Monaco", state: "MC", postalCode: "98000" },
      confirmedPin: DEPOT_POINT, candidate: { ...DEPOT_POINT, precision: "ROOFTOP" } }), refused(409));
    assert.equal((await prisma.depot.findUniqueOrThrow({ where: { id: id("depot-start") } })).lat, VISIT_POINT.lat, "A refused move leaves the depot where it was");

    // After the horizon and the technician's last booked day, a move to another metro is made, and no earlier one after it.
    await assignApiTechnicianDepot(clientId, id("tech"), id("far"), addCalendarDays(DATE, 30));
    await assert.rejects(assignApiTechnicianDepot(clientId, id("tech"), id("home-start"), addCalendarDays(DATE, 20)), refused(409), "A later assignment already exists");

    // Another client cannot change this client's depots or technicians; a rename alone touches no route.
    await assert.rejects(setApiDepotPolicy(otherClientId, id("home-start"), "HOME", "HOME"), refused(404));
    await assert.rejects(assignApiTechnicianDepot(otherClientId, id("tech"), id("depot-start"), addCalendarDays(DATE, 3)), refused(404));
    const renamedFrom = await versionOf();
    await setApiDepotDetails(clientId, id("home-start"), "Renamed depot", null);
    assert.equal((await prisma.depot.findUniqueOrThrow({ where: { id: id("home-start") } })).name, "Renamed depot");
    assert.equal(await versionOf(), renamedFrom, "A rename changes no technician-day");
    console.log("API master data smoke passed");
  } finally {
    await removeMetro();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
