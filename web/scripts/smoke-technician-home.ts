import assert from "node:assert/strict";
import { createServer } from "node:http";
import { NextRequest } from "next/server";
import { PrismaClient } from "@prisma/client";
import { POST } from "../app/api/technicians/route";

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
  const dealer = await prisma.dealership.create({ data: { name: "Home pin smoke dealer" } });
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
