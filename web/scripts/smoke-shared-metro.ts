// Two clients in one metro, end to end against a running scheduler and the fixture router. Each client has its own
// dealership, depot, technicians, customers and appointments in the same metro. Booking, daily optimization and
// time-off repair through the public API read and write only the acting client's rows, the other client's
// technician-days keep their timestamps, and the scheduler's own metro-wide paths refuse the shared metro. The
// scheduler must route this smoke's metro to the fixture router:
//   ROUTING_METRO_URLS=api-shared-smoke=http://127.0.0.1:18001   SCHEDULER_TEST_URL=http://127.0.0.1:18000
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { z } from "zod";
import { prisma } from "../lib/prisma";
import { generateTenantToken, tenantTokenSha256 } from "../lib/tenantTokens";
import { storeSolverSettings } from "../lib/clientSettingsStore";
import { addCalendarDays, todayInTz } from "../lib/date";
import { localMinute } from "../lib/zonedTime";
import { buildClientSnapshot } from "../lib/clientSnapshot";
import { bookApiOffer, searchApiOffers } from "../lib/apiBooking";
import { proposeApiDay } from "../lib/apiDispatch";
import { analyzeApiTimeOff, approveApiTimeOff, submitApiTimeOff } from "../lib/apiTimeOff";
import { currentLastModified } from "../lib/apiReceipt";
import { servesMetro, servesMetroAlone } from "../lib/clientScope";
import { metroClientId } from "../lib/metroClient";
import { tokenVariable } from "../lib/schedulerApi";

const METRO = "api-shared-smoke";
const ZONE = "America/Chicago";
const run = randomUUID().slice(0, 8);
const A = `api-shared-${run}-a`, B = `api-shared-${run}-b`;
const id = (client: string, name: string) => `${client}-${name}`;
const stamp = (date: string) => new Date(`${date}T00:00:00Z`);
const DATE = addCalendarDays(todayInTz(ZONE), 2);
const base = process.env.SCHEDULER_TEST_URL;
assert.ok(base, "SCHEDULER_TEST_URL must point at a running scheduler");

async function removeMetro() {
  const like = "api-shared-%";
  for (const table of ["api_booking_receipt", "api_booking_offer", "api_booking_offer_set", "api_booking_day", "api_commit_receipt",
    "api_proposal_technician_day", "api_daily_proposal", "api_request"])
    await prisma.$executeRawUnsafe(`DELETE FROM ${table} WHERE "tenantId" LIKE $1`, like);
  await prisma.$executeRaw`DELETE FROM tenant_api_token WHERE "tenantId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM tenant WHERE id LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM portal_api_daily_proposal WHERE "clientId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM time_off_request WHERE "technicianId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM appointment WHERE "technicianId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM portal_api_offer_set WHERE "clientId" LIKE ${like}`;
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

/** One client's dealership, depot, two technicians and a scheduled visit on DATE for its first technician, all in the shared metro. */
async function client(clientId: string) {
  await prisma.client.create({ data: { id: clientId, name: clientId } });
  await prisma.dealership.create({ data: { id: id(clientId, "dealer"), clientId, name: `${clientId} dealer` } });
  // Both depots stand at the same point, so the two clients compete for exactly the same roads and customers.
  await prisma.depot.create({ data: { id: id(clientId, "depot"), metroId: METRO, dealershipId: id(clientId, "dealer"), name: `${clientId} depot`, lat: 43.735, lng: 7.420,
    endpointPolicies: { create: { effectiveDate: stamp("1900-01-01"), departure: "DEPOT", returnTo: "DEPOT" } } } });
  for (const name of ["one", "two"])
    await prisma.technician.create({ data: { id: id(clientId, name), clientId, name: `${clientId} ${name}`, color: "#000000", homeLat: 43.738, homeLng: 7.424,
      shiftStartMin: 480, shiftEndMin: 1020, maxDailyMinutes: 540, qualifications: { create: { serviceId: `api-shared-${run}-service` } },
      depotAssignments: { create: { depotId: id(clientId, "depot"), effectiveDate: stamp("1900-01-01") } },
      availabilityVersions: { create: { effectiveDate: stamp("1900-01-01"), days: { create: Array.from({ length: 7 }, (_, dayOfWeek) => ({
        dayOfWeek, available: true, shiftStartMin: 480, shiftEndMin: 1020 })) } } } } });
  const saved = await storeSolverSettings(clientId, null, { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67",
    travelBufferPercent: "10", travelBufferMinutes: 2, fairnessBudgetPercent: "2", offerLimit: 2, bookingHorizonWeekdays: 2 });
  assert.ok("saved" in saved);
  const token = generateTenantToken();
  await prisma.tenant.create({ data: { id: clientId, name: clientId } });
  await prisma.tenantApiToken.create({ data: { id: randomUUID(), tenantId: clientId, tokenSha256: tenantTokenSha256(token), label: "Shared metro smoke" } });
  process.env[tokenVariable(clientId)] = token;
  await job(clientId, "visit", "SCHEDULED");
  await prisma.appointment.create({ data: { id: id(clientId, "visit"), jobId: id(clientId, "visit"), technicianId: id(clientId, "one"), serviceDate: stamp(DATE), sequence: 0,
    windowStart: localMinute(DATE, 480, false, ZONE), windowEnd: localMinute(DATE, 1020, true, ZONE),
    plannedStart: localMinute(DATE, 540, false, ZONE), plannedEnd: localMinute(DATE, 585, false, ZONE) } });
}

async function job(clientId: string, name: string, status: "PENDING" | "SCHEDULED") {
  await prisma.customer.create({ data: { id: id(clientId, `customer-${name}`), clientId, firstName: "Shared", lastName: name, email: `${name}@example.invalid`, phone: "0000000000" } });
  await prisma.address.create({ data: { id: id(clientId, `address-${name}`), customerId: id(clientId, `customer-${name}`), line1: "Fixture address", city: "Monaco", state: "MC", postalCode: "98000", lat: 43.748, lng: 7.438 } });
  await prisma.job.create({ data: { id: id(clientId, name), customerId: id(clientId, `customer-${name}`), addressId: id(clientId, `address-${name}`), serviceId: `api-shared-${run}-service`, durationMin: 45, status } });
  return id(clientId, name);
}

/** The client's appointment rows and technician-day timestamps, to prove another client's work left them untouched. */
async function state(clientId: string) {
  const technicians = [id(clientId, "one"), id(clientId, "two")];
  const appointments = await prisma.appointment.findMany({ where: { technicianId: { in: technicians } }, orderBy: { id: "asc" } });
  const stamps = await currentLastModified(prisma, technicians.map(technicianId => ({ technicianId, serviceDate: DATE })));
  return { appointments, stamps: [...stamps.entries()].sort() };
}

async function main() {
  await removeMetro();
  await prisma.metro.create({ data: { id: METRO, name: "Shared metro smoke", timezone: ZONE } });
  await prisma.serviceCatalog.create({ data: { id: `api-shared-${run}-service`, code: `api-shared-${run}-service`, name: "Shared metro service", estDurationMin: 45 } });
  process.env.SCHEDULER_API_URL = base;
  try {
    await client(A);
    await client(B);
    for (const owner of [A, B]) {
      assert.equal(await servesMetro(owner, METRO), true);
      assert.equal(await servesMetroAlone(owner, METRO), false, "Neither client owns the metro alone");
    }
    await assert.rejects(prisma.$transaction(tx => metroClientId(tx, METRO)), /more than one client/, "No single owner is guessed");

    // Each snapshot holds only its own client's technicians and appointments.
    for (const [owner, other] of [[A, B], [B, A]] as const) {
      const snapshot = await buildClientSnapshot(owner, METRO, [DATE]);
      assert.deepEqual(snapshot.technicianDays.map(day => day.technicianId).sort(), [id(owner, "one"), id(owner, "two")]);
      assert.deepEqual(snapshot.appointments.map(item => item.id), [id(owner, "visit")]);
      assert.ok(!JSON.stringify(snapshot).includes(other), "Nothing of the other client is sent");
    }

    // Booking for A writes only A's rows.
    let untouched = await state(B);
    const booked = await job(A, "booking", "PENDING");
    const offers = await searchApiOffers(A, booked);
    assert.equal(offers.search.outcome, "AVAILABLE", JSON.stringify(offers.search));
    const chosen = offers.offers[0];
    assert.ok(chosen);
    await bookApiOffer(A, booked, chosen.offerId);
    const appointment = await prisma.appointment.findUniqueOrThrow({ where: { id: booked } });
    assert.ok([id(A, "one"), id(A, "two")].includes(appointment.technicianId), "A's booking goes to one of A's technicians");
    assert.deepEqual(await state(B), untouched, "A's booking leaves B's appointments and timestamps as they were");

    // Daily optimization for B routes only B's technicians.
    untouched = await state(A);
    const proposal = await proposeApiDay(B, METRO, DATE);
    assert.deepEqual(proposal.routes.map(route => route.technicianId).sort(), [id(B, "one"), id(B, "two")], proposal.reason);
    assert.deepEqual(await state(A), untouched);

    // B's time off is repaired with B's other technician, never with A's.
    const request = await submitApiTimeOff(B, { technicianId: id(B, "one"), firstDate: DATE, lastDate: DATE, startMin: 480, endMin: 1020, category: "Other", reason: "Shared metro smoke" });
    for (let step = 0; step < 5 && !(await analyzeApiTimeOff(B, request.requestId)).done; step++);
    assert.deepEqual(await approveApiTimeOff(B, request.requestId), { requestId: request.requestId, status: "APPROVED" });
    assert.equal((await prisma.appointment.findUniqueOrThrow({ where: { id: id(B, "visit") } })).technicianId, id(B, "two"));
    assert.deepEqual(await state(A), untouched, "B's repair leaves A's appointments and timestamps as they were");
    await assert.rejects(approveApiTimeOff(A, request.requestId), /not found/, "A cannot see B's time off");

    // The scheduler's own paths read whole metros, so they refuse this one; current routes can be read per client.
    const engine = (path: string, init?: RequestInit) => fetch(`${base}${path}`, { ...init,
      headers: { "Content-Type": "application/json", "x-internal-secret": process.env.INTERNAL_API_SECRET ?? "dev-only-change-me" } });
    const preview = await engine("/v1/optimize/day/preview", { method: "POST", body: JSON.stringify({ metro_id: METRO, date: DATE }) });
    assert.equal(preview.status, 409, await preview.text());
    assert.equal((await engine(`/v1/dispatch/geometry?metro_id=${METRO}&date=${DATE}`)).status, 409);
    const scoped = await engine(`/v1/dispatch/geometry?metro_id=${METRO}&date=${DATE}&client_id=${A}`);
    assert.equal(scoped.status, 200, await scoped.clone().text());
    const { stops } = z.object({ stops: z.array(z.object({ technicianId: z.string() })) }).parse(await scoped.json());
    assert.ok(stops.length > 0 && stops.every(stop => stop.technicianId.startsWith(A)), "Only A's stops are drawn for A");
    console.log("Shared metro smoke passed");
  } finally {
    await removeMetro();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
