import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import { geocodeAddress, searchAddress } from "./geocode";

const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; });
const address = { line1: "2825 S 170th Plaza", city: "Omaha", state: "NE", postalCode: "68130" };

test("accepts one Nominatim house-number match", async () => {
  globalThis.fetch = async (input) => {
    const url = new URL(input.toString());
    assert.equal(url.pathname, "/search");
    assert.equal(url.searchParams.get("street"), address.line1);
    return Response.json([{ lat: "41.2327", lon: "-96.1796", address: { house_number: "2825" } }]);
  };
  assert.deepEqual(await geocodeAddress(address), { lat: 41.2327, lng: -96.1796, precision: "ROOFTOP" });
});

test("requires pin confirmation for multiple or approximate matches", async () => {
  globalThis.fetch = async () => Response.json([
    { lat: "41.2327", lon: "-96.1796", address: { house_number: "2825" } },
    { lat: "41.2330", lon: "-96.1800" },
  ]);
  const candidates = await searchAddress(address);
  assert.equal(candidates.length, 2);
  assert.equal(candidates[0]?.precision, "APPROXIMATE");
});

test("treats unavailable Nominatim as an unverified address", async () => {
  globalThis.fetch = async () => { throw new Error("offline"); };
  assert.equal(await geocodeAddress(address), null);
});
