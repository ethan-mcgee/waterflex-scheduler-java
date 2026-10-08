// The portal's overnight optimization, end to end against a running scheduler and the fixture router: run times
// queue one run per time however many processes fire them, "Run now" queues at most one run per client, a run
// proposes each day through the public API and applies nothing, and a dispatcher applies an overnight improvement
// from the dispatch board's proposals. Runs whose worker stops are abandoned and keep no half-recorded day. The
// scheduler must route this smoke's metro to the fixture router:
//   ROUTING_METRO_URLS=api-overnight-smoke=http://127.0.0.1:18001   SCHEDULER_TEST_URL=http://127.0.0.1:18000
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { prisma } from "../lib/prisma";
import { generateTenantToken, tenantTokenSha256 } from "../lib/tenantTokens";
import { storeSolverSettings } from "../lib/clientSettingsStore";
import { addCalendarDays, todayInTz } from "../lib/date";
import { localMinute } from "../lib/zonedTime";
import { apiProposalHistory, commitApiProposal } from "../lib/apiDispatch";
import { abandonStaleRuns, claimRun, enqueueDueRuns, executeRun, loadRunMinutes, recentRuns, requestRun, saveRunMinutes } from "../lib/overnight";
import { tokenVariable } from "../lib/schedulerApi";

const METRO = "api-overnight-smoke";
const ZONE = "America/Chicago";
const run = randomUUID().slice(0, 8);
const clientId = `api-overnight-${run}`;
const otherClientId = `api-overnight-${run}-other`;
const id = (name: string) => `${clientId}-${name}`;
const stamp = (date: string) => new Date(`${date}T00:00:00Z`);
const TODAY = todayInTz(ZONE);
// Two days ahead, so the day is never frozen by the 6 a.m. cutoff while the smoke runs.
const DATE = addCalendarDays(TODAY, 2);
const EMPTY_DATE = addCalendarDays(TODAY, 3);
const FROZEN_DATE = addCalendarDays(TODAY, -1);
const base = process.env.SCHEDULER_TEST_URL;
assert.ok(base, "SCHEDULER_TEST_URL must point at a running scheduler");

async function removeMetro() {
  const like = "api-overnight-%";
  await prisma.$executeRaw`DELETE FROM portal_overnight_run WHERE "clientId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM client_overnight_time WHERE "clientId" LIKE ${like}`;
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
  await prisma.technician.create({ data: { id: id(name), clientId, name: `Overnight smoke ${name}`, color: "#000000", homeLat: 43.738, homeLng: 7.424,
    shiftStartMin: 480, shiftEndMin: 1020, maxDailyMinutes: 540, qualifications: { create: { serviceId: id("service") } },
    depotAssignments: { create: { depotId: id("depot"), effectiveDate: stamp("1900-01-01") } },
    availabilityVersions: { create: { effectiveDate: stamp("1900-01-01"), days: { create: Array.from({ length: 7 }, (_, dayOfWeek) => ({
      dayOfWeek, available: true, shiftStartMin: 480, shiftEndMin: 1020 })) } } } } });
}

/** A scheduled 45-minute visit at the fixture router's customer point, first on its technician's day. */
async function visit(name: string, technicianId: string) {
  await prisma.customer.create({ data: { id: id(`customer-${name}`), clientId, firstName: "Overnight", lastName: name, email: `${name}@example.invalid`, phone: "0000000000" } });
  await prisma.address.create({ data: { id: id(`address-${name}`), customerId: id(`customer-${name}`), line1: "Fixture address", city: "Monaco", state: "MC", postalCode: "98000", lat: 43.748, lng: 7.438 } });
  await prisma.job.create({ data: { id: id(`job-${name}`), customerId: id(`customer-${name}`), addressId: id(`address-${name}`), serviceId: id("service"), durationMin: 45, status: "SCHEDULED" } });
  await prisma.appointment.create({ data: { id: id(`appt-${name}`), jobId: id(`job-${name}`), technicianId, serviceDate: stamp(DATE), sequence: 0,
    windowStart: localMinute(DATE, 480, false, ZONE), windowEnd: localMinute(DATE, 1020, true, ZONE),
    plannedStart: localMinute(DATE, 540, false, ZONE), plannedEnd: localMinute(DATE, 585, false, ZONE) } });
  return id(`appt-${name}`);
}

async function setUp() {
  await removeMetro();
  await prisma.metro.create({ data: { id: METRO, name: "API overnight smoke", timezone: ZONE } });
  await prisma.serviceCatalog.create({ data: { id: id("service"), code: id("service"), name: "API overnight service", estDurationMin: 45 } });
  for (const client of [clientId, otherClientId]) await prisma.client.create({ data: { id: client, name: client } });
  await prisma.dealership.create({ data: { id: id("dealer"), clientId, name: "API overnight dealer" } });
  await prisma.depot.create({ data: { id: id("depot"), metroId: METRO, dealershipId: id("dealer"), name: "API overnight depot", lat: 43.735, lng: 7.420,
    endpointPolicies: { create: { effectiveDate: stamp("1900-01-01"), departure: "DEPOT", returnTo: "DEPOT" } } } });
  await technician("a");
  await technician("b");
  const saved = await storeSolverSettings(clientId, null, { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67",
    travelBufferPercent: "10", travelBufferMinutes: 2, fairnessBudgetPercent: "2", offerLimit: 2, bookingHorizonWeekdays: 2 });
  assert.ok("saved" in saved);
  const token = generateTenantToken();
  await prisma.tenant.create({ data: { id: clientId, name: clientId } });
  await prisma.tenantApiToken.create({ data: { id: randomUUID(), tenantId: clientId, tokenSha256: tenantTokenSha256(token), label: "API overnight smoke" } });
  process.env.SCHEDULER_API_URL = base;
  process.env[tokenVariable(clientId)] = token;
}

const ownRuns = () => prisma.portalOvernightRun.findMany({ where: { clientId }, orderBy: { createdAt: "asc" } });

async function claimOwn(): Promise<string> {
  const claimed = await claimRun();
  assert.ok(claimed, "A queued run is claimed");
  assert.equal(claimed.clientId, clientId, "Only this smoke's runs are queued");
  return claimed.runId;
}

async function main() {
  await setUp();
  try {
    const x = await visit("x", id("a"));
    const y = await visit("y", id("b"));

    // Run times: none means manual only.
    assert.deepEqual(await saveRunMinutes(clientId, [240, 120]), [120, 240]);
    assert.deepEqual(await saveRunMinutes(clientId, []), []);
    assert.deepEqual(await loadRunMinutes(clientId), []);
    assert.equal(await enqueueDueRuns(localMinute(TODAY, 125, false, ZONE)), 0, "A manual-only client is never queued");
    await saveRunMinutes(clientId, [120]);

    // Every process may fire the run time; one run is queued for it, and it never runs again once done.
    const due = localMinute(TODAY, 125, false, ZONE);
    const fired = await Promise.all([enqueueDueRuns(due), enqueueDueRuns(due), enqueueDueRuns(due)]);
    assert.equal(fired.reduce((sum, count) => sum + count, 0), 1, "One run per run time");
    const [scheduled] = await ownRuns();
    assert.ok(scheduled);
    assert.equal(scheduled.trigger, "SCHEDULED");
    assert.equal(scheduled.scheduledFor?.toISOString(), localMinute(TODAY, 120, false, ZONE).toISOString());

    // "Run now" while a run is queued returns that run instead of queuing another.
    const asked = await requestRun(clientId);
    assert.equal(asked.alreadyActive, true);
    assert.equal(asked.run.runId, scheduled.id);
    assert.equal((await recentRuns(otherClientId)).length, 0, "Another client sees none of this client's runs");

    const runId = await claimOwn();
    assert.equal(runId, scheduled.id);
    assert.equal(await claimRun(), null, "A running run is not claimed twice");
    assert.equal(await executeRun(runId, clientId, { dates: () => [FROZEN_DATE, DATE, EMPTY_DATE] }), "FINISHED");

    // The run kept a proposal per day and applied nothing.
    const [finished] = await recentRuns(clientId);
    assert.ok(finished);
    assert.equal(finished.status, "FINISHED");
    const byDate = new Map(finished.days.map(day => [day.serviceDate, day]));
    assert.equal(byDate.get(FROZEN_DATE)?.outcome, "SKIPPED", "A frozen day is left alone");
    const improved = byDate.get(DATE);
    assert.equal(improved?.outcome, "PROPOSED");
    assert.equal(improved.proposal?.decision, "IMPROVED", "One technician doing both visits saves a round trip");
    assert.equal(improved.proposal.state, "WAITING", "An improvement waits for a dispatcher");
    assert.equal(byDate.get(EMPTY_DATE)?.proposal?.state, "NOTHING_TO_APPLY", "A day with nothing to move has nothing to apply");
    assert.deepEqual((await prisma.appointment.findMany({ where: { id: { in: [x, y] } }, orderBy: { id: "asc" } })).map(item => item.technicianId),
      [id("a"), id("b")], "An overnight run never applies a proposal");

    // The dispatcher sees it on the board for that day, marked as overnight, and applies it.
    const onBoard = (await apiProposalHistory(clientId, METRO, DATE)).find(item => item.proposalId === improved.proposal?.proposalId);
    assert.equal(onBoard?.overnightRunId, runId);
    assert.equal(onBoard.state, "OPEN");
    const applied = await commitApiProposal(clientId, onBoard.proposalId);
    assert.equal(applied.state, "COMMITTED");
    assert.equal(applied.overnightRunId, runId);
    assert.equal(new Set((await prisma.appointment.findMany({ where: { id: { in: [x, y] } } })).map(item => item.technicianId)).size, 1);
    assert.equal((await recentRuns(clientId))[0]?.days.find(day => day.serviceDate === DATE)?.proposal?.state, "APPLIED");
    assert.equal(await enqueueDueRuns(due), 0, "A run time that already ran is not queued again");

    // "Run now" queues a manual run; two workers claiming at once get it once. A stopping worker abandons it.
    const manual = await requestRun(clientId);
    assert.equal(manual.alreadyActive, false);
    assert.equal(manual.run.trigger, "MANUAL");
    assert.equal(manual.run.status, "QUEUED");
    const claims = await Promise.all([claimRun(), claimRun(), claimRun()]);
    assert.equal(claims.filter(claim => claim !== null).length, 1, "Concurrent workers claim a run once");
    assert.equal(await executeRun(manual.run.runId, clientId, { dates: () => [DATE], stopping: () => true }), "ABANDONED");
    assert.equal((await recentRuns(clientId))[0]?.days.length, 0);

    // A run whose worker stopped reporting is abandoned, and a day it was still proposing keeps nothing.
    const stalled = (await requestRun(clientId)).run.runId;
    assert.equal(await claimOwn(), stalled);
    await prisma.$executeRaw`UPDATE portal_overnight_run SET "heartbeatAt" = clock_timestamp() - interval '11 minutes' WHERE id = ${stalled}::uuid`;
    assert.ok(await abandonStaleRuns() >= 1);
    const proposalsBefore = await prisma.portalApiDailyProposal.count({ where: { clientId } });
    assert.equal(await executeRun(stalled, clientId, { dates: () => [DATE] }), "ABANDONED");
    assert.equal(await prisma.portalApiDailyProposal.count({ where: { clientId } }), proposalsBefore, "No proposal is kept without its run's record");
    const [abandoned] = await recentRuns(clientId);
    assert.equal(abandoned?.status, "ABANDONED");
    assert.equal(abandoned.days.length, 0);
    assert.equal((await requestRun(clientId)).alreadyActive, false, "An abandoned run no longer blocks a new one");
    console.log("Portal overnight smoke passed");
  } finally {
    await removeMetro();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
