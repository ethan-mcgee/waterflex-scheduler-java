// Booking through the public scheduling API, end to end against a running scheduler and the fixture router: search,
// book (select, confirm with a fresh snapshot, write the receipt), book a second job on the same day, release, and a
// stale receipt that writes nothing. The scheduler must route this smoke's metro to the fixture router:
//   ROUTING_METRO_URLS=api-booking-smoke=http://127.0.0.1:18001   SCHEDULER_TEST_URL=http://127.0.0.1:18000
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { prisma } from "../lib/prisma";
import { generateTenantToken, tenantTokenSha256 } from "../lib/tenantTokens";
import { storeSolverSettings } from "../lib/clientSettingsStore";
import { buildClientSnapshot } from "../lib/clientSnapshot";
import { bookApiOffer, BookingRefused, releaseApiOffers, searchApiOffers, StaleReceipt, writeBookingReceipt } from "../lib/apiBooking";
import { tokenVariable } from "../lib/schedulerApi";

const METRO = "api-booking-smoke";
const run = randomUUID().slice(0, 8);
const clientId = `api-smoke-${run}`;
const otherClientId = `api-smoke-${run}-other`;
const id = (name: string) => `${clientId}-${name}`;
// The fixture router knows only fixed points, so every job is at its known customer location.
const stamp = (date: string) => new Date(`${date}T00:00:00Z`);
const base = process.env.SCHEDULER_TEST_URL;
assert.ok(base, "SCHEDULER_TEST_URL must point at a running scheduler");

async function removeMetro() {
  const like = "api-smoke-%";
  for (const table of ["api_booking_receipt", "api_booking_offer", "api_booking_offer_set", "api_booking_day", "api_request"])
    await prisma.$executeRawUnsafe(`DELETE FROM ${table} WHERE "tenantId" LIKE $1`, like);
  await prisma.$executeRaw`DELETE FROM tenant_api_token WHERE "tenantId" LIKE ${like}`;
  await prisma.$executeRaw`DELETE FROM tenant WHERE id LIKE ${like}`;
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

async function setUp() {
  await removeMetro();
  await prisma.metro.create({ data: { id: METRO, name: "API booking smoke", timezone: "America/Chicago" } });
  await prisma.serviceCatalog.create({ data: { id: id("service"), code: id("service"), name: "API smoke service", estDurationMin: 45 } });
  for (const client of [clientId, otherClientId]) await prisma.client.create({ data: { id: client, name: client } });
  await prisma.dealership.create({ data: { id: id("dealer"), clientId, name: "API smoke dealer" } });
  await prisma.depot.create({ data: { id: id("depot"), metroId: METRO, dealershipId: id("dealer"), name: "API smoke depot", lat: 43.735, lng: 7.420,
    endpointPolicies: { create: { effectiveDate: stamp("1900-01-01"), departure: "DEPOT", returnTo: "DEPOT" } } } });
  await prisma.technician.create({ data: { id: id("tech"), clientId, name: "API smoke tech", color: "#000000", homeLat: 43.738, homeLng: 7.424,
    shiftStartMin: 480, shiftEndMin: 1020, maxDailyMinutes: 540, qualifications: { create: { serviceId: id("service") } },
    depotAssignments: { create: { depotId: id("depot"), effectiveDate: stamp("1900-01-01") } },
    availabilityVersions: { create: { effectiveDate: stamp("1900-01-01"), days: { create: Array.from({ length: 7 }, (_, dayOfWeek) => ({
      dayOfWeek, available: true, shiftStartMin: 480, shiftEndMin: 1020 })) } } } } });
  const saved = await storeSolverSettings(clientId, null, { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67",
    travelBufferPercent: "10", travelBufferMinutes: 2, fairnessBudgetPercent: "2", offerLimit: 2, bookingHorizonWeekdays: 2 });
  assert.ok("saved" in saved);
  const token = generateTenantToken();
  await prisma.tenant.create({ data: { id: clientId, name: clientId } });
  await prisma.tenantApiToken.create({ data: { id: randomUUID(), tenantId: clientId, tokenSha256: tenantTokenSha256(token), label: "API booking smoke" } });
  process.env.SCHEDULER_API_URL = base;
  process.env[tokenVariable(clientId)] = token;
}

async function job(name: string, lat: number, lng: number): Promise<string> {
  await prisma.customer.create({ data: { id: id(`customer-${name}`), clientId, firstName: "API", lastName: name, email: `${name}@example.invalid`, phone: "0000000000" } });
  await prisma.address.create({ data: { id: id(`address-${name}`), customerId: id(`customer-${name}`), line1: "Fixture address", city: "Monaco", state: "MC", postalCode: "98000", lat, lng } });
  await prisma.job.create({ data: { id: id(`job-${name}`), customerId: id(`customer-${name}`), addressId: id(`address-${name}`), serviceId: id("service"), durationMin: 45 } });
  return id(`job-${name}`);
}

async function main() {
  await setUp();
  try {
    const first = await job("first", 43.748, 7.438);
    const offers = await searchApiOffers(clientId, first);
    assert.equal(offers.search.outcome, "AVAILABLE", JSON.stringify(offers.search));
    assert.ok(offers.offers.length >= 1 && offers.offers.length <= 2, "No more offers than the client's limit");
    const chosen = offers.offers[0];
    assert.ok(chosen);
    const booked = await bookApiOffer(clientId, first, chosen.offerId);
    assert.equal(booked.appointmentId, first, "The appointment takes the job's ID, as the API does");
    const appointment = await prisma.appointment.findUniqueOrThrow({ where: { id: first } });
    assert.equal(appointment.technicianId, id("tech"));
    assert.equal(appointment.windowStart.toISOString(), chosen.windowStart, "The promised window is the offer's window");
    assert.equal((await prisma.job.findUniqueOrThrow({ where: { id: first } })).status, "SCHEDULED");
    assert.ok((await prisma.portalApiOfferSet.findFirstOrThrow({ where: { jobId: first, receiptId: { not: null } } })).endedAt);
    assert.deepEqual(await bookApiOffer(clientId, first, chosen.offerId), booked, "Booking the same offer again returns the same booking");

    const second = await job("second", 43.748, 7.438);
    const secondSearch = await searchApiOffers(clientId, second);
    console.log("Second search", JSON.stringify(secondSearch.search));
    const secondOffers = secondSearch.offers;
    const sameDay = secondOffers.find(item => item.date === chosen.date);
    await bookApiOffer(clientId, second, required(sameDay ?? secondOffers[0]).offerId);
    console.log(sameDay ? "Second booking shares the first booking's day" : "Second booking is on another day");
    if (sameDay) {
      const day = await prisma.appointment.findMany({ where: { technicianId: id("tech"), serviceDate: stamp(chosen.date), cancelledAt: null }, orderBy: { sequence: "asc" } });
      assert.equal(day.length, 2);
      assert.deepEqual(day.map(item => item.sequence), [0, 1], "The receipt's order is written for every appointment on the day");
      assert.ok(required(day[0]).plannedEnd <= required(day[1]).plannedStart, "Visits do not overlap");
    }

    const third = await job("third", 43.748, 7.438);
    const released = await searchApiOffers(clientId, third);
    const releasedOffer = required(released.offers[0]);
    {
      assert.deepEqual(await releaseApiOffers(clientId, third, releasedOffer.offerId), { success: true });
      await assert.rejects(bookApiOffer(clientId, third, releasedOffer.offerId), (error: unknown) => error instanceof BookingRefused && error.status === 409);
      assert.equal(await prisma.appointment.count({ where: { jobId: third } }), 0);
    }

    // A receipt whose technician-day changed after its snapshot writes nothing.
    const snapshot = await buildClientSnapshot(clientId, METRO, [chosen.date]);
    const listed = snapshot.technicianDays.find(day => day.technicianId === id("tech"));
    assert.ok(listed);
    // Any write to an appointment on the day moves its lastModified, even one that rewrites the same value.
    await prisma.appointment.update({ where: { id: first }, data: { plannedStart: appointment.plannedStart } });
    await assert.rejects(writeBookingReceipt(clientId, third, { serviceDate: chosen.date, windowStart: appointment.windowStart, windowEnd: appointment.windowEnd }, {
      receiptId: "stale", technicianDays: [{ technicianId: listed.technicianId, serviceDate: listed.serviceDate, lastModified: listed.lastModified }],
      assignments: [{ appointmentId: third, technicianId: listed.technicianId, serviceDate: chosen.date, sequence: 5,
        plannedStart: appointment.plannedStart.toISOString(), plannedEnd: appointment.plannedEnd.toISOString() }] }), StaleReceipt);
    assert.equal(await prisma.appointment.count({ where: { jobId: third } }), 0, "A stale receipt writes nothing");
    assert.equal((await prisma.job.findUniqueOrThrow({ where: { id: third } })).status, "PENDING");

    const otherJob = await prisma.job.findFirst({ where: { id: first, customer: { clientId: otherClientId } } });
    assert.equal(otherJob, null);
    await assert.rejects(searchApiOffers(otherClientId, first), (error: unknown) => error instanceof BookingRefused && error.status === 404,
      "Another client cannot search for this client's job");
    console.log("API booking smoke passed");
  } finally {
    await removeMetro();
  }
}

function required<T>(value: T | undefined): T {
  assert.ok(value !== undefined);
  return value;
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
