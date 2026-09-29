import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { NextRequest } from "next/server";
import { prisma } from "../lib/prisma";
import { ensureOmahaConfiguration } from "../lib/omahaConfiguration";
import { POST as lookup } from "../app/api/book/location/route";
import { POST as book } from "../app/api/book/route";
import { bookingLocationResponse, bookingResponse, required } from "../lib/contracts";
import { releaseOffers, selectOffer } from "../lib/engineClient";

const url = new URL(required(process.env.DATABASE_URL));
assert.equal(url.pathname, "/waterflex_test");
assert.equal(url.searchParams.get("schema"), "booking_location_it", "Use the isolated booking_location_it schema");
const originalFetch = globalThis.fetch;
const request = (body: unknown) => new NextRequest("http://localhost/api/book", { method: "POST", body: JSON.stringify(body) });
const base = { firstName: "Location", lastName: "Fixture", email: "location@example.invalid", phone: "4025550100", city: "Omaha", state: "NE", postalCode: "68130", serviceCode: "FILTER_SWAP" };
async function counts() { return Promise.all([prisma.customer.count(), prisma.address.count(), prisma.job.count(), prisma.bookingOffer.count(), prisma.slotHold.count()]); }

async function main() {
  assert.equal(await prisma.job.count(), 0, "Start with an empty isolated fixture schema");
  await ensureOmahaConfiguration(prisma);
  await prisma.depot.update({ where: { id: "depot-omaha-main" }, data: { lat: 41.21479191716162, lng: -96.11773549999936 } });
  await prisma.depot.create({ data: { id: "location-second-depot", name: "Second Omaha depot", dealershipId: "dealership-omaha-main", metroId: "metro-omaha",
    lat: 41.1592165, lng: -95.9361545, endpointPolicies: { create: { effectiveDate: new Date("1900-01-01Z"), departure: "HOME", returnTo: "HOME" } } } });
  const before = await counts();
  globalThis.fetch = async (input, init) => input.toString().includes("/search?") ? Response.json([]) : originalFetch(input, init);
  const located = await lookup(request({ ...base, line1: "123 Valley Street" }));
  assert.equal(located.status, 200);
  assert.equal(bookingLocationResponse.parse(await located.json()).status, "NO_MATCH");
  assert.deepEqual(await counts(), before, "Lookup must be read-only");
  const absent = await book(request({ ...base, line1: "123 Valley Street", requestId: randomUUID() }));
  assert.equal(absent.status, 422); assert.deepEqual(await counts(), before);
  globalThis.fetch = async (input, init) => {
    if (input.toString().includes("/search?")) throw new Error("Manual booking must not geocode");
    return originalFetch(input, init);
  };
  const invalid = await book(request({ ...base, line1: "123 Valley Street", requestId: randomUUID(), confirmedPin: { lat: 0, lng: 0, manuallyConfirmed: true } }));
  assert.equal(invalid.status, 422); assert.deepEqual(await counts(), before);
  for (const status of ["UNROUTABLE", "ROUTING_UNAVAILABLE"] as const) {
    globalThis.fetch = async (input, init) => input.toString().endsWith("/v1/book/location/validate") ? Response.json({ status }) : originalFetch(input, init);
    const failed = await book(request({ ...base, line1: "123 Valley Street", requestId: randomUUID(), confirmedPin: { lat: 41.25855, lng: -95.92929, manuallyConfirmed: true } }));
    assert.equal(failed.status, status === "UNROUTABLE" ? 422 : 503); assert.deepEqual(await counts(), before);
  }
  globalThis.fetch = async (input, init) => {
    if (input.toString().includes("/search?")) throw new Error("Manual booking must not geocode");
    return originalFetch(input, init);
  };
  const streets = ["North 169th Street", "Reflection Circle", "Appaloosa Drive", "Valley Street", "Grandview Avenue", "Cascio Drive"];
  for (const [index, street] of streets.entries()) {
    // Synthetic address text tests the explicit manual contract; coordinates are a real accessible Omaha road.
    const input = { ...base, line1: `123 ${street}`, requestId: randomUUID(), confirmedPin: { lat: 41.25855, lng: -95.92929, manuallyConfirmed: true } };
    let response = await book(request(input));
    assert.equal(response.status, 200, await response.clone().text());
    let data = bookingResponse.parse(await response.json());
    for (let attempt = 0; data.search?.retryable && attempt < 2; attempt++) {
      response = await book(request(input)); assert.equal(response.status, 200, await response.clone().text()); data = bookingResponse.parse(await response.json());
    }
    assert.equal(data.search?.outcome, "AVAILABLE", JSON.stringify(data));
    const saved = await prisma.job.findUniqueOrThrow({ where: { id: data.jobId }, include: { address: true } });
    assert.equal(saved.address.line1, input.line1); assert.equal(saved.address.geocodePrecision, "MANUALLY_CONFIRMED");
    assert.ok(saved.address.pinConfirmedAt); assert.equal(saved.address.geocodedAt, null);
    const changed = await book(request({ ...input, postalCode: "68102" })); assert.equal(changed.status, 409);
    const retries = await Promise.all([book(request(input)), book(request(input))]);
    assert.ok(retries.every(reply => reply.status === 200 || reply.status === 503 || reply.status === 504));
    assert.equal(await prisma.job.count({ where: { bookingRequestId: input.requestId } }), 1);
    const offer = required(required(data.offers)[0]);
    if (index === 0) {
      const booked = await selectOffer(data.jobId, offer.offerId); assert.ok(booked.appointmentId);
      assert.equal((await book(request(input))).status, 409);
    } else await releaseOffers(data.jobId, offer.offerId);
    console.log(`${street}: manual location reached search, feasible offer, ${index === 0 ? "confirmed" : "released"}`);
  }
  const followUp = await book(request({ ...base, line1: "123 Valley Street", requestId: randomUUID(), followUp: true }));
  assert.equal(followUp.status, 200);
  const pending = bookingResponse.parse(await followUp.json()); assert.ok(pending.pendingReference);
  const saved = await prisma.job.findUniqueOrThrow({ where: { id: pending.jobId }, include: { address: true } });
  assert.equal(saved.address.lat, null); assert.equal(saved.address.pinConfirmedAt, null);
  console.log("Read-only lookup, typed failures, provenance, retry conflicts, concurrent retries, follow-up and booking passed");
}
main().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => {
  globalThis.fetch = originalFetch; await prisma.$disconnect();
});
