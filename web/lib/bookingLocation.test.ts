import assert from "node:assert/strict";
import { test, afterEach } from "node:test";
import { searchAddress, GeocoderError } from "./geocode";
import { bookingRequest, bookingLocationResponse } from "./contracts";
import { bookingFingerprint } from "./bookingIdentity";
import { coverageBounds } from "./coverage";
import { haversineMiles } from "./geo";

const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; });
const bounds = { south: 40, north: 43, west: -98, east: -94 };
test("a valid locality-only response is not malformed address evidence", async () => {
  globalThis.fetch = async () => Response.json([{ lat: "41.2", lon: "-96.1", address: { city: "Omaha" } }]);
  assert.deepEqual(await searchAddress({ line1: "123 Valley Street", city: "Omaha", state: "NE", postalCode: "68130" }), []);
});
test("North 169th Street's Jefferson Precinct is approximate evidence for postal Bennington", async () => {
  globalThis.fetch = async () => Response.json([{ lat: "41.3378806", lon: "-96.1792532", address: {
    road: "North 169th Street", municipality: "Jefferson Precinct", postcode: "68007", country_code: "us",
  }, boundingbox: ["41.3361939", "41.3391880", "-96.1800051", "-96.1782502"] }]);
  const results = await searchAddress({ line1: "123 N 169th St", city: "Bennington", state: "NE", postalCode: "68007" });
  assert.equal(results.length, 1); assert.equal(results[0]?.precision, "APPROXIMATE");
});
for (const road of ["North 169th Street", "Reflection Circle", "Appaloosa Drive", "Valley Street", "Grandview Avenue", "Cascio Drive"]) {
  test(`${road}: incomplete street evidence is a manual starting point, never an exact house`, async () => {
    const queries: URL[] = []; const signals: Array<AbortSignal | null | undefined> = [];
    globalThis.fetch = async (input, init) => {
      const url = new URL(input.toString()); queries.push(url); signals.push(init?.signal);
      return Response.json(url.searchParams.has("city") ? [] : [{ lat: "41.2", lon: "-96.1",
        boundingbox: ["41.19", "41.21", "-96.11", "-96.09"], address: { road, postcode: "68130", country_code: "us" } }]);
    };
    const results = await searchAddress({ line1: `123 ${road}`, city: "Omaha", state: "NE", postalCode: "68130" }, bounds);
    assert.equal(results.length, 1); assert.equal(results[0]?.precision, "APPROXIMATE");
    assert.equal(queries.length, 3); assert.equal(new Set(signals).size, 1);
    assert.ok(queries.every(q => q.searchParams.get("bounded") === "1"));
    assert.equal(queries[2]?.searchParams.get("street"), road);
  });
}
test("Elkhorn suburb and rural locality categories can identify an exact address", async () => {
  for (const locality of ["suburb", "hamlet", "municipality"]) {
    globalThis.fetch = async () => Response.json([{ lat: "41.2", lon: "-96.1", address: {
      road: "Appaloosa Drive", house_number: "123", city: "Omaha", [locality]: "Elkhorn", postcode: "68022", country_code: "us",
    } }]);
    assert.equal((await searchAddress({ line1: "123 Appaloosa Dr", city: "Elkhorn", state: "NE", postalCode: "68022" }))[0]?.precision, "ROOFTOP");
  }
});
test("multiple street segments are retained, repeats are deduplicated, malformed mixtures fail", async () => {
  const segment = { lat: "41.2", lon: "-96.1", boundingbox: ["41.1", "41.3", "-96.2", "-96"], address: { road: "Valley Street", postcode: "68130" } };
  const input = { line1: "123 Valley St", city: "Omaha", state: "NE", postalCode: "68130" };
  globalThis.fetch = async () => Response.json([segment, { ...segment, lat: "41.21" }, segment]);
  assert.equal((await searchAddress(input)).length, 2);
  globalThis.fetch = async () => Response.json([segment, { ...segment, lat: null }]);
  await assert.rejects(searchAddress(input), e => e instanceof GeocoderError && e.kind === "malformed");
});
test("manual request identity covers address, contact, service, pin and intent", () => {
  const raw = { requestId: "booking-request-123", firstName: "Test", lastName: "User", email: "test@example.invalid", phone: "4025550100",
    line1: "123 Valley St", city: "Omaha", state: "NE", postalCode: "68130", serviceCode: "SALT",
    confirmedPin: { lat: 41.2, lng: -96.1, manuallyConfirmed: true } };
  const value = bookingRequest.parse(raw), fingerprint = bookingFingerprint(value);
  assert.equal(fingerprint, bookingFingerprint(bookingRequest.parse({ ...raw, requestId: "different-request" })));
  for (const change of [{ line1: "125 Valley St" }, { email: "other@example.invalid" }, { serviceCode: "OTHER" },
    { confirmedPin: { ...raw.confirmedPin, lat: 41.21 } }, { confirmedPin: undefined, followUp: true }]) {
    assert.notEqual(fingerprint, bookingFingerprint(bookingRequest.parse({ ...raw, ...change })));
  }
  for (const change of [{ confirmedPin: null }, { confirmedPin: { lat: null, lng: -96 } }, { city: "" }, { followUp: true }])
    assert.equal(bookingRequest.safeParse({ ...raw, ...change }).success, false);
});
test("coverage includes all bearings plus margin and rejects missing persisted configuration", () => {
  const circles = [{ lat: 41.2, lng: -96, radiusMi: 65 }, { lat: 41.3, lng: -96.2, radiusMi: 65 }];
  const b = coverageBounds(circles, 10);
  assert.ok(b.south < 40.2 && b.east > -94.7);
  for (const circle of circles) {
    for (let bearing = 0; bearing < 360; bearing += 5) {
      const angle = bearing * Math.PI / 180;
      const lat = circle.lat + 64 / 69.2 * Math.cos(angle), lng = circle.lng + 64 / (69.2 * Math.cos(circle.lat * Math.PI / 180)) * Math.sin(angle);
      assert.ok(lat > b.south && lat < b.north && lng > b.west && lng < b.east);
      assert.ok(haversineMiles(circle.lat, circle.lng, lat, lng) < 65);
    }
  }
  for (const raw of [[], null, [{ lat: null, lng: -96, radiusMi: 65 }], [{ lat: 41, lng: -96, radiusMi: 0 }]])
    assert.throws(() => coverageBounds(raw));
  assert.equal(bookingLocationResponse.safeParse({ status: "NO_MATCH", candidates: [], serviceArea: null }).success, false);
});
