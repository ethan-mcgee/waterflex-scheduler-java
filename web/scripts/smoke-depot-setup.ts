import assert from "node:assert/strict";
import { createServer } from "node:http";
import { NextRequest } from "next/server";
import { PrismaClient } from "@prisma/client";
import { z } from "zod";
import { POST } from "../app/api/depots/route";
import { DEFAULT_CLIENT_ID } from "../lib/clients";

const prisma = new PrismaClient();
const database = process.env.DATABASE_URL ? new URL(process.env.DATABASE_URL).pathname.slice(1) : "";
if (database !== "waterflex_test") throw new Error("Requires waterflex_test");

const geocoder = createServer((_, response) => {
  response.setHeader("Content-Type", "application/json");
  response.end(JSON.stringify([{ lat: "43.735", lon: "7.420", address: {
    house_number: "1", road: "Test Street", city: "Monaco", postcode: "98000", country_code: "us",
  } }]));
});

async function main() {
  await new Promise<void>(resolve => geocoder.listen(0, "127.0.0.1", resolve));
  const address = geocoder.address();
  if (!address || typeof address === "string") throw new Error("Missing geocoder port");
  process.env.NOMINATIM_URL = `http://127.0.0.1:${address.port}`;
  const dealer = await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Depot pin setup fixture" } });
  const metro = await prisma.metro.create({ data: { name: "Depot pin metro", timezone: "America/Chicago" } });
  let createdId: string | null = null;
  try {
    const setup = { dealershipId: dealer.id, metroId: metro.id, name: "Adjusted depot", departure: "HOME", returnTo: "DEPOT",
      address: { line1: "1 Test St", city: "Monaco", state: "MC", postalCode: "98000" } };
    const rejected = await POST(new NextRequest("http://localhost/api/depots", { method: "POST", body: JSON.stringify({
      ...setup, confirmedPin: { lat: 43.739, lng: 7.420 },
    }) }));
    assert.equal(rejected.status, 422);
    assert.equal(await prisma.depot.count({ where: { dealershipId: dealer.id } }), 0);
    const accepted = await POST(new NextRequest("http://localhost/api/depots", { method: "POST", body: JSON.stringify({
      ...setup, confirmedPin: { lat: 43.736, lng: 7.420 },
    }) }));
    assert.equal(accepted.status, 201, await accepted.clone().text());
    const result = z.object({ id: z.string() }).parse(await accepted.json() as unknown);
    createdId = result.id;
    const depot = await prisma.depot.findUniqueOrThrow({ where: { id: createdId } });
    assert.equal(depot.lat, 43.736);
    assert.equal(depot.geocodeLat, 43.735);
    assert.equal(depot.geocodePrecision, "ROOFTOP");
    assert.equal(depot.addressLine1, "1 Test St");
    assert.ok(depot.pinConfirmedAt instanceof Date);
    console.log("Adjusted depot pin, distant pin rejection, and provenance persistence passed");
  } finally {
    if (createdId) await prisma.depot.delete({ where: { id: createdId } });
    await prisma.dealership.delete({ where: { id: dealer.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
    await prisma.$disconnect();
    geocoder.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; geocoder.close(); void prisma.$disconnect(); });
