// Daily optimization through the public scheduling API, end to end against a running scheduler and the fixture router:
// two technicians each make the same round trip for one visit, so the scheduler proposes one technician doing both.
// A proposal whose day changes before it is applied writes nothing; a fresh one is applied with a compare-and-set,
// and applying it again changes nothing. Another client can neither see nor apply it. The scheduler must route this
// smoke's metro to the fixture router:
//   ROUTING_METRO_URLS=api-dispatch-smoke=http://127.0.0.1:18001   SCHEDULER_TEST_URL=http://127.0.0.1:18000
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { prisma } from "../lib/prisma";
import { generateTenantToken, tenantTokenSha256 } from "../lib/tenantTokens";
import { storeSolverSettings } from "../lib/clientSettingsStore";
import { addCalendarDays, todayInTz } from "../lib/date";
import { localMinute } from "../lib/zonedTime";
import { apiProposalHistory, commitApiProposal, DispatchRefused, proposeApiDay } from "../lib/apiDispatch";
import { tokenVariable } from "../lib/schedulerApi";

const METRO = "api-dispatch-smoke";
const ZONE = "America/Chicago";
const run = randomUUID().slice(0, 8);
const clientId = `api-dispatch-${run}`;
const otherClientId = `api-dispatch-${run}-other`;
const id = (name: string) => `${clientId}-${name}`;
const stamp = (date: string) => new Date(`${date}T00:00:00Z`);
// Two days ahead, so the day is never frozen by the 6 a.m. cutoff while the smoke runs.
const DATE = addCalendarDays(todayInTz(ZONE), 2);
const base = process.env.SCHEDULER_TEST_URL;
assert.ok(base, "SCHEDULER_TEST_URL must point at a running scheduler");

async function removeMetro() {
  const like = "api-dispatch-%";
  for (const table of ["api_commit_receipt", "api_proposal_technician_day", "api_daily_proposal", "api_request"])
    await prisma.$executeRawUnsafe(`DELETE FROM ${table} WHERE "tenantId" LIKE $1`, like);
  await prisma.$executeRaw`DELETE FROM tenant_api_token WHERE "tenantId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM tenant WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM portal_api_daily_proposal WHERE "clientId" LIKE ${like}`;
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
  await prisma.technician.create({ data: { id: id(name), clientId, name: `Dispatch smoke ${name}`, color: "#000000", homeLat: 43.738, homeLng: 7.424,
    shiftStartMin: 480, shiftEndMin: 1020, maxDailyMinutes: 540, qualifications: { create: { serviceId: id("service") } },
    depotAssignments: { create: { depotId: id("depot"), effectiveDate: stamp("1900-01-01") } },
    availabilityVersions: { create: { effectiveDate: stamp("1900-01-01"), days: { create: Array.from({ length: 7 }, (_, dayOfWeek) => ({
      dayOfWeek, available: true, shiftStartMin: 480, shiftEndMin: 1020 })) } } } } });
}

/** A scheduled 45-minute visit at the fixture router's customer point, first on its technician's day. */
async function visit(name: string, technicianId: string) {
  await prisma.customer.create({ data: { id: id(`customer-${name}`), clientId, firstName: "Dispatch", lastName: name, email: `${name}@example.invalid`, phone: "0000000000" } });
  await prisma.address.create({ data: { id: id(`address-${name}`), customerId: id(`customer-${name}`), line1: "Fixture address", city: "Monaco", state: "MC", postalCode: "98000", lat: 43.748, lng: 7.438 } });
  await prisma.job.create({ data: { id: id(`job-${name}`), customerId: id(`customer-${name}`), addressId: id(`address-${name}`), serviceId: id("service"), durationMin: 45, status: "SCHEDULED" } });
  await prisma.appointment.create({ data: { id: id(`appt-${name}`), jobId: id(`job-${name}`), technicianId, serviceDate: stamp(DATE), sequence: 0,
    windowStart: localMinute(DATE, 480, false, ZONE), windowEnd: localMinute(DATE, 1020, true, ZONE),
    plannedStart: localMinute(DATE, 540, false, ZONE), plannedEnd: localMinute(DATE, 585, false, ZONE) } });
  return id(`appt-${name}`);
}

async function setUp() {
  await removeMetro();
  await prisma.metro.create({ data: { id: METRO, name: "API dispatch smoke", timezone: ZONE } });
  await prisma.serviceCatalog.create({ data: { id: id("service"), code: id("service"), name: "API dispatch service", estDurationMin: 45 } });
  for (const client of [clientId, otherClientId]) await prisma.client.create({ data: { id: client, name: client } });
  await prisma.dealership.create({ data: { id: id("dealer"), clientId, name: "API dispatch dealer" } });
  await prisma.depot.create({ data: { id: id("depot"), metroId: METRO, dealershipId: id("dealer"), name: "API dispatch depot", lat: 43.735, lng: 7.420,
    endpointPolicies: { create: { effectiveDate: stamp("1900-01-01"), departure: "DEPOT", returnTo: "DEPOT" } } } });
  await technician("a");
  await technician("b");
  const saved = await storeSolverSettings(clientId, null, { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67",
    travelBufferPercent: "10", travelBufferMinutes: 2, fairnessBudgetPercent: "2", offerLimit: 2, bookingHorizonWeekdays: 2 });
  assert.ok("saved" in saved);
  const token = generateTenantToken();
  await prisma.tenant.create({ data: { id: clientId, name: clientId } });
  await prisma.tenantApiToken.create({ data: { id: randomUUID(), tenantId: clientId, tokenSha256: tenantTokenSha256(token), label: "API dispatch smoke" } });
  process.env.SCHEDULER_API_URL = base;
  process.env[tokenVariable(clientId)] = token;
}

const refusedWith = (status: number) => (error: unknown) => error instanceof DispatchRefused && error.status === status;

async function main() {
  await setUp();
  try {
    const x = await visit("x", id("a"));
    const y = await visit("y", id("b"));

    // A day that changes after its proposal is never written.
    const stale = await proposeApiDay(clientId, METRO, DATE);
    assert.equal(stale.decision, "IMPROVED", `One technician doing both visits saves a round trip: ${stale.reason}`);
    assert.equal(stale.state, "OPEN");
    assert.ok(stale.changes.length >= 1, "The proposal moves a visit");
    await prisma.appointment.update({ where: { id: y }, data: { plannedStart: localMinute(DATE, 540, false, ZONE) } });
    await assert.rejects(commitApiProposal(clientId, stale.proposalId), refusedWith(409));
    assert.deepEqual((await prisma.appointment.findMany({ where: { id: { in: [x, y] } }, orderBy: { id: "asc" } })).map(item => item.technicianId),
      [id("a"), id("b")], "A refused commit writes nothing");
    await assert.rejects(commitApiProposal(clientId, stale.proposalId), refusedWith(409), "A refused proposal stays refused");
    assert.equal((await apiProposalHistory(clientId, METRO, DATE)).find(item => item.proposalId === stale.proposalId)?.state, "REFUSED");

    const fresh = await proposeApiDay(clientId, METRO, DATE);
    assert.equal(fresh.decision, "IMPROVED", fresh.reason);
    await assert.rejects(commitApiProposal(otherClientId, fresh.proposalId), refusedWith(404), "Another client cannot apply this client's proposal");
    assert.equal((await apiProposalHistory(otherClientId, METRO, DATE)).length, 0, "Another client does not see this client's proposals");
    const versionsBefore = await prisma.scheduleDay.findMany({ where: { technicianId: { in: [id("a"), id("b")] }, serviceDate: stamp(DATE) } });
    const applied = await commitApiProposal(clientId, fresh.proposalId);
    assert.equal(applied.state, "COMMITTED");
    const day = await prisma.appointment.findMany({ where: { id: { in: [x, y] } }, orderBy: [{ technicianId: "asc" }, { sequence: "asc" }] });
    for (const route of fresh.routes)
      for (const stop of route.stops) {
        const written = day.find(item => item.id === stop.appointmentId);
        assert.ok(written, `${stop.appointmentId} is still scheduled`);
        assert.equal(written.technicianId, route.technicianId);
        assert.equal(written.sequence, stop.sequence);
        assert.equal(written.plannedStart.toISOString(), new Date(stop.plannedStart).toISOString(), "Planned times are the proposal's");
      }
    assert.equal(new Set(day.map(item => item.technicianId)).size, 1, "One technician now makes both visits");
    const versionsAfter = await prisma.scheduleDay.findMany({ where: { technicianId: { in: [id("a"), id("b")] }, serviceDate: stamp(DATE) } });
    for (const after of versionsAfter)
      assert.ok(after.version > (versionsBefore.find(item => item.technicianId === after.technicianId)?.version ?? -1), "Every written day's version moves");
    assert.deepEqual(await commitApiProposal(clientId, fresh.proposalId), applied, "Applying again returns the same result and writes nothing");

    const after = await proposeApiDay(clientId, METRO, DATE);
    assert.notEqual(after.state, "OPEN", `An optimized day has nothing more to apply: ${after.decision} ${after.reason}`);
    console.log("API dispatch smoke passed");
  } finally {
    await removeMetro();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
