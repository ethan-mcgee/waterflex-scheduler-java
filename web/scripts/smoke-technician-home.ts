import assert from "node:assert/strict";
import { createServer } from "node:http";
import { NextRequest } from "next/server";
import { PrismaClient } from "@prisma/client";
import { POST } from "../app/api/technicians/route";
import { PATCH } from "../app/api/technicians/[id]/route";
import { DEFAULT_CLIENT_ID } from "../lib/clients";

const prisma = new PrismaClient();
const database = process.env.DATABASE_URL ? new URL(process.env.DATABASE_URL).pathname.slice(1) : "";
if (database !== "waterflex_test") throw new Error("Requires waterflex_test");
const street = { lat: "41.1637462", lon: "-96.0079032", address: {
  road: "Crest Ridge Drive", town: "Papillion", postcode: "68133", country_code: "us",
}, boundingbox: ["41.1623576", "41.1654397", "-96.0109311", "-96.0046578"] };
let exact = false;
const geocoder = createServer((request, response) => {
  const url = new URL(request.url ?? "/", "http://localhost");
  response.setHeader("Content-Type", "application/json");
  response.end(JSON.stringify(url.searchParams.has("state") ? [] :
    [{ ...street, address: { ...street.address, ...(exact ? { house_number: "2125" } : {}) } }]));
});

async function main() {
  // Clean a prior interrupted smoke run in the isolated test database.
  const previous = await prisma.technician.findMany({ where: { email: { in: ["home-pin@example.com", "house-pin@example.com", "moved-house-pin@example.com"] },
    homeAddressLine1: "2125 Crest Ridge Dr" }, select: { id: true } });
  if (previous.length) {
    const ids = previous.map(item => item.id);
    await prisma.technicianQualification.deleteMany({ where: { technicianId: { in: ids } } });
    await prisma.technician.deleteMany({ where: { id: { in: ids } } });
  }
  await prisma.depot.deleteMany({ where: { name: "Home pin smoke depot" } });
  await prisma.dealership.deleteMany({ where: { name: "Home pin smoke dealer" } });
  await prisma.metro.deleteMany({ where: { name: "Home pin smoke metro" } });
  await prisma.serviceCatalog.deleteMany({ where: { code: { startsWith: "home-pin-" }, name: "Home pin smoke service" } });
  await new Promise<void>(resolve => geocoder.listen(0, "127.0.0.1", resolve));
  const location = geocoder.address();
  if (!location || typeof location === "string") throw new Error("Missing geocoder port");
  process.env.NOMINATIM_URL = `http://127.0.0.1:${location.port}`;
  const dealer = await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Home pin smoke dealer" } });
  const metro = await prisma.metro.create({ data: { name: "Home pin smoke metro", timezone: "America/Chicago" } });
  const depot = await prisma.depot.create({ data: { name: "Home pin smoke depot", dealershipId: dealer.id, metroId: metro.id, lat: 41.16, lng: -96.01 } });
  const service = await prisma.serviceCatalog.create({ data: { code: `home-pin-${Date.now()}`, name: "Home pin smoke service", estDurationMin: 60 } });
  const created: string[] = [];
  const input = { name: "Home pin smoke tech", email: "home-pin@example.com", phone: "4025550100", color: "#2563eb",
    depotId: depot.id, address: { line1: "2125 Crest Ridge Dr", city: "Papillion", state: "NE", postalCode: "68133" },
    confirmedPin: { lat: 41.164, lng: -96.008 }, manuallyConfirmed: true,
    days: Array.from({ length: 7 }, (_, dayOfWeek) => ({ dayOfWeek, available: dayOfWeek === 1,
      shiftStartMin: dayOfWeek === 1 ? 480 : null, shiftEndMin: dayOfWeek === 1 ? 1020 : null })),
    qualifications: [service.id] };
  const submit = (body: unknown) => POST(new NextRequest("http://localhost/api/technicians", { method: "POST", body: JSON.stringify(body) }));
  try {
    assert.equal((await submit({ ...input, manuallyConfirmed: false })).status, 422);
    assert.equal((await submit({ ...input, confirmedPin: { lat: 41.17, lng: -96.008 } })).status, 422);
    const response = await submit(input);
    assert.equal(response.status, 201);
    const saved = await prisma.technician.findFirstOrThrow({ where: { email: input.email } });
    created.push(saved.id);
    assert.equal(saved.homePinProvenance, "MANUALLY_CONFIRMED");
    assert.equal(saved.homeLat, input.confirmedPin.lat);
    exact = true;
    const house = await submit({ ...input, email: "house-pin@example.com", confirmedPin: { lat: 41.1637462, lng: -96.0079032 }, manuallyConfirmed: false });
    assert.equal(house.status, 201);
    const houseSaved = await prisma.technician.findFirstOrThrow({ where: { email: "house-pin@example.com" } });
    created.push(houseSaved.id);
    assert.equal(houseSaved.homePinProvenance, "GEOCODER_HOUSE");
    const movedPin = { lat: 41.1659, lng: -96.0079032 };
    assert.equal((await submit({ ...input, email: "moved-house-pin@example.com", confirmedPin: movedPin, manuallyConfirmed: false })).status, 422);
    assert.equal((await submit({ ...input, email: "moved-house-pin@example.com", confirmedPin: { lat: 41.17, lng: -96.0079032 }, manuallyConfirmed: true })).status, 422);
    const moved = await submit({ ...input, email: "moved-house-pin@example.com", confirmedPin: movedPin, manuallyConfirmed: true });
    assert.equal(moved.status, 201);
    const movedSaved = await prisma.technician.findFirstOrThrow({ where: { email: "moved-house-pin@example.com" } });
    created.push(movedSaved.id);
    assert.equal(movedSaved.homePinProvenance, "MANUALLY_CONFIRMED");
    console.log("Street pin bounds, confirmation, exact house creation, and manually adjusted house pin limits passed");
    const patch = (id: string, body: unknown) => PATCH(new NextRequest(`http://localhost/api/technicians/${id}`, { method: "PATCH", body: JSON.stringify(body) }), { params: { id } });
    const profile = { name: "Home pin smoke tech", color: "#2563eb" };
    const address = { line1: "2125 Crest Ridge Dr", city: "Papillion", state: "NE", postalCode: "68133" };
    const houseBefore = await prisma.technician.findUniqueOrThrow({ where: { id: houseSaved.id } });
    // Partial address groups and short phone numbers are rejected before any write.
    assert.equal((await patch(houseSaved.id, { ...profile, address })).status, 400);
    assert.equal((await patch(houseSaved.id, { ...profile, phone: "(402) 55" })).status, 400);
    // An unconfirmed pin away from the geocoded house is rejected and leaves the technician unchanged.
    assert.equal((await patch(houseSaved.id, { ...profile, name: "Rejected rename", address, confirmedPin: movedPin, manuallyConfirmed: false })).status, 422);
    const unchanged = await prisma.technician.findUniqueOrThrow({ where: { id: houseSaved.id } });
    assert.equal(unchanged.name, houseBefore.name);
    assert.equal(unchanged.homeLat, houseBefore.homeLat);
    // A profile edit without an address keeps the saved home.
    assert.equal((await patch(houseSaved.id, { ...profile, phone: "(402) 555-0111" })).status, 200);
    const phoneOnly = await prisma.technician.findUniqueOrThrow({ where: { id: houseSaved.id } });
    assert.equal(phoneOnly.phone, "(402) 555-0111");
    assert.equal(phoneOnly.homeLat, houseBefore.homeLat);
    // A manually confirmed pin moves the home and records its provenance.
    assert.equal((await patch(houseSaved.id, { ...profile, address, confirmedPin: movedPin, manuallyConfirmed: true })).status, 200);
    const movedHome = await prisma.technician.findUniqueOrThrow({ where: { id: houseSaved.id } });
    assert.equal(movedHome.homeLat, movedPin.lat);
    assert.equal(movedHome.homePinProvenance, "MANUALLY_CONFIRMED");
    // The exact geocoded house pin restores GEOCODER_HOUSE.
    assert.equal((await patch(houseSaved.id, { ...profile, address, confirmedPin: { lat: 41.1637462, lng: -96.0079032 }, manuallyConfirmed: false })).status, 200);
    assert.equal((await prisma.technician.findUniqueOrThrow({ where: { id: houseSaved.id } })).homePinProvenance, "GEOCODER_HOUSE");
    // Without a depot assignment in effect there is no metro to check the address against.
    const unassigned = await prisma.technician.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Unassigned smoke tech", color: "#2563eb", homeLat: 41.16, homeLng: -96.01, shiftStartMin: 480, shiftEndMin: 1020 } });
    created.push(unassigned.id);
    assert.equal((await patch(unassigned.id, { ...profile, name: "Unassigned smoke tech", address, confirmedPin: { lat: 41.1637462, lng: -96.0079032 }, manuallyConfirmed: false })).status, 409);
    assert.equal((await patch("missing-technician", profile)).status, 404);
    console.log("Technician profile address edits, rejected pins, phone validation, and missing depot checks passed");
  } finally {
    await prisma.technicianQualification.deleteMany({ where: { technicianId: { in: created } } });
    await prisma.technician.deleteMany({ where: { id: { in: created } } });
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealer.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
    geocoder.close();
    await prisma.$disconnect();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; geocoder.close(); void prisma.$disconnect(); });
