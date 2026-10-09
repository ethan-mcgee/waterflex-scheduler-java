import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { NextRequest } from "next/server";
import { PrismaClient } from "@prisma/client";
import { POST as preview } from "../app/api/technicians/geocode/route";
import { POST as createDepot } from "../app/api/depots/route";
import { PATCH as editDepot } from "../app/api/depots/[id]/route";
import { POST as createTechnician } from "../app/api/technicians/route";
import { POST as book } from "../app/api/book/route";
import { DEFAULT_CLIENT_ID } from "../lib/clients";
import { connectSmokeClient } from "./smokeApiClient";
import { required } from "../lib/contracts";

if (!process.env.DATABASE_URL || new URL(process.env.DATABASE_URL).pathname !== "/waterflex_test") throw new Error("Requires waterflex_test");
if (process.env.ENGINE_URL !== (process.env.SCHEDULER_TEST_URL ?? "http://127.0.0.1:18000")) throw new Error("Requires isolated test engine");
const prisma = new PrismaClient();
let unavailable = false;
const geocoder = createServer((_, response) => {
  response.setHeader("Content-Type", "application/json");
  response.statusCode = unavailable ? 503 : 200;
  response.end(JSON.stringify([{ lat: "41.26088", lon: "-96.18435", address: {
    house_number: "2825", road: "South 170th Plaza", city: "Omaha", state: "Nebraska", postcode: "68130", country_code: "us",
  } }]));
});
const request = (body: unknown) => new NextRequest("http://localhost/api/test", { method: "POST", body: JSON.stringify(body) });
// Booking searches through the public API, which routes only listed metros: CI lists this one with the fixture router.
const METRO = "address-matching-smoke";
async function main() {
  await new Promise<void>(resolve => geocoder.listen(0, "127.0.0.1", resolve));
  const location = geocoder.address();
  if (!location || typeof location === "string") throw new Error("Missing fixture port");
  process.env.NOMINATIM_URL = `http://127.0.0.1:${location.port}`;
  const suffix = randomUUID();
  const dealer = await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, name: `Address fixture ${suffix}` } });
  await prisma.metro.deleteMany({ where: { id: METRO, depots: { none: {} } } });
  const metro = await prisma.metro.create({ data: { id: METRO, name: `Address fixture ${suffix}`, timezone: "America/Chicago" } });
  const disconnect = await connectSmokeClient(DEFAULT_CLIENT_ID, required(process.env.ENGINE_URL), "Address matching smoke");
  const service = await prisma.serviceCatalog.create({ data: { code: suffix, name: "Address fixture", estDurationMin: 60 } });
  const address = { line1: "2825 S 170th Plz", city: "Omaha", state: "NE", postalCode: "68130" };
  const confirmedPin = { lat: 41.26088, lng: -96.18435 };
  const depotInput = { dealershipId: dealer.id, metroId: metro.id, name: "Address fixture depot", departure: "HOME", returnTo: "HOME", address, confirmedPin };
  const email = `${suffix}@example.invalid`;
  try {
    for (const line1 of ["2825 S 170th Plz", "2825 S 170th Plaza", "2825 South 170th Plaza"]) {
      assert.equal((await preview(request({ ...address, line1 }))).status, 200);
    }
    assert.equal((await createDepot(request(depotInput))).status, 201);
    const depot = await prisma.depot.findFirstOrThrow({ where: { dealershipId: dealer.id } });
    assert.equal(depot.addressLine1, address.line1);
    const techInput = { name: "Address fixture technician", email, phone: "4025550100", color: "#2563eb", depotId: depot.id,
      address, confirmedPin, manuallyConfirmed: false, qualifications: [service.id],
      days: Array.from({ length: 7 }, (_, dayOfWeek) => ({ dayOfWeek, available: dayOfWeek === 1, shiftStartMin: dayOfWeek === 1 ? 480 : null, shiftEndMin: dayOfWeek === 1 ? 1020 : null })) };
    assert.equal((await createTechnician(request(techInput))).status, 201);
    assert.equal((await prisma.technician.findFirstOrThrow({ where: { email } })).homeAddressLine1, address.line1);
    const edit = (changed: typeof address) => editDepot(request({ name: depot.name, address: changed, confirmedPin }), { params: Promise.resolve({ id: depot.id }) });
    assert.equal((await edit({ ...address, line1: "2825 South 170th Plaza" })).status, 200);
    assert.equal((await prisma.depot.findUniqueOrThrow({ where: { id: depot.id } })).addressLine1, "2825 South 170th Plaza");
    const booking = { ...address, requestId: randomUUID(), firstName: "Address", lastName: "Fixture", email, phone: "4025550100", serviceCode: service.code };
    const booked = await book(request(booking));
    assert.equal(booked.status, 200, await booked.clone().text());
    const job = await prisma.job.findUniqueOrThrow({ where: { bookingRequestId: booking.requestId }, include: { address: true } });
    assert.equal(job.address.geocodePrecision, "ROOFTOP");
    assert.equal(job.address.line1, address.line1);
    for (const line1 of ["2825 S 170th Plz", "2825 South 170th Plaza"]) {
      const bad = { ...address, line1, postalCode: "68103" };
      assert.equal((await preview(request(bad))).status, 404);
      assert.equal((await createDepot(request({ ...depotInput, address: bad }))).status, 422);
      assert.equal((await createTechnician(request({ ...techInput, address: bad }))).status, 422);
      assert.equal((await edit(bad)).status, 422);
      assert.equal((await book(request({ ...booking, ...bad, requestId: randomUUID(), confirmedPin }))).status, 422);
      const requestId = randomUUID();
      assert.equal((await book(request({ ...booking, ...bad, requestId }))).status, 422);
      assert.equal(await prisma.job.count({ where: { bookingRequestId: requestId } }), 0);
      assert.equal((await book(request({ ...booking, ...bad, requestId, followUp: true }))).status, 200);
      const pending = await prisma.job.findUniqueOrThrow({ where: { bookingRequestId: requestId }, include: { address: true } });
      assert.equal(pending.manualFollowUpReason, "ADDRESS_UNVERIFIED");
      assert.equal(pending.address.geocodePrecision, null);
      assert.equal(pending.address.lat, null);
    }
    unavailable = true;
    assert.equal((await preview(request(address))).status, 503);
    assert.equal((await createDepot(request(depotInput))).status, 503);
    assert.equal((await createTechnician(request(techInput))).status, 503);
    assert.equal((await edit(address)).status, 503);
    assert.equal((await book(request({ ...booking, requestId: randomUUID() }))).status, 503);
    assert.equal(await prisma.depot.count({ where: { dealershipId: dealer.id } }), 1);
    assert.equal(await prisma.technician.count({ where: { email } }), 1);
    assert.equal(await prisma.job.count({ where: { serviceId: service.id } }), 3);
    assert.equal((await prisma.depot.findUniqueOrThrow({ where: { id: depot.id } })).addressLine1, "2825 South 170th Plaza");
    console.log("Shared preview, booking, technician, depot creation/edit, strict ZIP, manual follow-up, and unavailable save guards passed");
  } finally {
    const jobs = await prisma.job.findMany({ where: { serviceId: service.id } });
    const ids = jobs.map(job => job.id);
    await prisma.portalApiOfferSet.deleteMany({ where: { jobId: { in: ids } } });
    await prisma.slotHold.deleteMany({ where: { jobId: { in: ids } } });
    await prisma.reservationArrangement.deleteMany({ where: { metroId: metro.id } });
    await prisma.bookingOffer.deleteMany({ where: { jobId: { in: ids } } });
    await prisma.bookingOfferSet.deleteMany({ where: { jobId: { in: ids } } });
    await prisma.job.deleteMany({ where: { id: { in: ids } } });
    await prisma.address.deleteMany({ where: { customerId: { in: jobs.map(job => job.customerId) } } });
    await prisma.customer.deleteMany({ where: { id: { in: jobs.map(job => job.customerId) } } });
    const technicians = await prisma.technician.findMany({ where: { email } });
    await prisma.technicianQualification.deleteMany({ where: { technicianId: { in: technicians.map(tech => tech.id) } } });
    await prisma.scheduleDay.deleteMany({ where: { technicianId: { in: technicians.map(tech => tech.id) } } });
    await prisma.technician.deleteMany({ where: { email } });
    await prisma.depot.deleteMany({ where: { dealershipId: dealer.id } });
    await prisma.dealership.delete({ where: { id: dealer.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
    await disconnect();
    geocoder.close(); await prisma.$disconnect();
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; geocoder.close(); void prisma.$disconnect(); });
