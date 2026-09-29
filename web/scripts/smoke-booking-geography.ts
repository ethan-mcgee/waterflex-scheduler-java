import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { NextRequest } from "next/server";
import { prisma } from "../lib/prisma";
import { bookingResponse, required } from "../lib/contracts";
import { POST as book } from "../app/api/book/route";
import { validateBookingLocation, releaseOffers, selectOffer } from "../lib/engineClient";
import { haversineMiles } from "../lib/geo";

const database = new URL(required(process.env.DATABASE_URL));
assert.equal(database.pathname, "/waterflex_test");
assert.equal(database.searchParams.get("schema"), "booking_location_it");
const samples = [
  { city: "Omaha", state: "NE", postalCode: "68102", lat: 41.25855, lng: -95.92929 },
  { city: "Lincoln", state: "NE", postalCode: "68508", lat: 40.81362, lng: -96.70260 },
  { city: "Fremont", state: "NE", postalCode: "68025", lat: 41.4333, lng: -96.498 },
  { city: "Tekamah", state: "NE", postalCode: "68061", lat: 41.7783, lng: -96.2211 },
  { city: "West Point", state: "NE", postalCode: "68788", lat: 41.8417, lng: -96.7086 },
  { city: "Oakland", state: "IA", postalCode: "51560", lat: 41.309, lng: -95.3967 },
  { city: "Atlantic", state: "IA", postalCode: "50022", lat: 41.4036, lng: -95.0139 },
  { city: "Rock Port", state: "MO", postalCode: "64482", lat: 40.4111, lng: -95.5169 },
  { city: "Tarkio", state: "MO", postalCode: "64491", lat: 40.4403, lng: -95.3775 },
  { city: "Auburn", state: "NE", postalCode: "68305", lat: 40.3931, lng: -95.838 },
];
async function main() {
  for (const sample of samples) {
    assert.equal((await validateBookingLocation(sample)).status, "VALID", sample.city);
    const body = { ...sample, firstName: "Geographic", lastName: "Fixture", email: "geography@example.invalid", phone: "4025550100",
      line1: `Manual road fixture in ${sample.city}`, serviceCode: "FILTER_SWAP", requestId: randomUUID(),
      confirmedPin: { lat: sample.lat, lng: sample.lng, manuallyConfirmed: true } };
    const request = () => new NextRequest("http://localhost/api/book", { method: "POST", body: JSON.stringify(body) });
    let response = await book(request());
    // A cold real road graph can exhaust the bounded search before routes are cached.
    for (let retry = 0; [503, 504].includes(response.status) && retry < 3; retry++) response = await book(request());
    assert.equal(response.status, 200, await response.clone().text());
    let data = bookingResponse.parse(await response.json());
    for (let retry = 0; data.search?.retryable && retry < 3; retry++) {
      response = await book(request()); assert.equal(response.status, 200, await response.clone().text()); data = bookingResponse.parse(await response.json());
    }
    assert.equal(data.search?.outcome, "AVAILABLE", `${sample.city}: ${JSON.stringify(data)}`);
    const offer = required(required(data.offers)[0]);
    if (sample.city === "Rock Port") assert.ok((await selectOffer(data.jobId, offer.offerId)).appointmentId);
    else await releaseOffers(data.jobId, offer.offerId);
    console.log(`${sample.city}, ${sample.state}: VALID, AVAILABLE, ${sample.city === "Rock Port" ? "BOOKED" : "RELEASED"}`);
  }
  const depots = await prisma.depot.findMany({ select: { lat: true, lng: true, metro: { select: { serviceRadiusMi: true } } } });
  for (const depot of depots) for (const miles of [64.99, 65.01]) for (let bearing = 0; bearing < 360; bearing += 45) {
    const angle = miles / 3958.8, heading = bearing * Math.PI / 180, lat = depot.lat * Math.PI / 180, lng = depot.lng * Math.PI / 180;
    const nextLat = Math.asin(Math.sin(lat) * Math.cos(angle) + Math.cos(lat) * Math.sin(angle) * Math.cos(heading));
    const nextLng = lng + Math.atan2(Math.sin(heading) * Math.sin(angle) * Math.cos(lat), Math.cos(angle) - Math.sin(lat) * Math.sin(nextLat));
    const point = { lat: nextLat * 180 / Math.PI, lng: nextLng * 180 / Math.PI };
    const covered = depots.some(d => haversineMiles(point.lat, point.lng, d.lat, d.lng) <= d.metro.serviceRadiusMi);
    const result = await validateBookingLocation(point);
    assert.ok(covered ? ["VALID", "UNROUTABLE"].includes(result.status) : result.status === "OUTSIDE_COVERAGE", JSON.stringify({ point, covered, result }));
  }
  console.log("32 boundary probes matched the union of depot circles; interior off-road points were not mistaken for outages");
}
main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
