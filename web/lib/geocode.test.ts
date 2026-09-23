import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import { geocodeAddress, GeocoderError, searchAddress } from "./geocode";
import { confirmedHomePin } from "./technicianHomePin";
import { NextRequest } from "next/server";
import { POST as lookupRoute } from "../app/api/technicians/geocode/route";
import { z } from "zod";

const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; });
const address = { line1: "2125 Crest Ridge Dr", city: "Papillion", state: "NE", postalCode: "68133" };
const street = { lat: "41.1637462", lon: "-96.0079032", address: {
  road: "Crest Ridge Drive", town: "Papillion", postcode: "68133", country_code: "us",
}, boundingbox: ["41.1623576", "41.1654397", "-96.0109311", "-96.0046578"] };

test("retries without state and labels the Papillion street result approximate", async () => {
  const queries: URL[] = [];
  globalThis.fetch = async input => {
    const url = new URL(input.toString()); queries.push(url);
    return Response.json(url.searchParams.has("state") ? [] : [street]);
  };
  const results = await searchAddress(address);
  assert.equal(queries.length, 2);
  assert.equal(queries[0]?.searchParams.get("state"), "NE");
  assert.equal(queries[1]?.searchParams.has("state"), false);
  assert.deepEqual(results, [{ lat: 41.1637462, lng: -96.0079032, precision: "APPROXIMATE",
    bounds: { south: 41.1623576, north: 41.1654397, west: -96.0109311, east: -96.0046578 } }]);
  assert.equal(confirmedHomePin({ lat: 41.164, lng: -96.008 }, results, false), null);
  assert.equal(confirmedHomePin({ lat: 41.164, lng: -96.008 }, results, true)?.precision, "APPROXIMATE");
  assert.equal(confirmedHomePin({ lat: 41.17, lng: -96.008 }, results, true), null);
});

test("rejects conflicting address details", async () => {
  for (const changed of [
    { address: { ...street.address, postcode: "68130" } },
    { address: { ...street.address, town: "Omaha" } },
    { address: { ...street.address, state: "Iowa" } },
    { address: { ...street.address, country_code: "ca" } },
    { address: { ...street.address, house_number: "2126" } },
    { address: { ...street.address, road: "Other Drive" } },
  ]) {
    globalThis.fetch = async () => Response.json([{ ...street, ...changed }]);
    assert.deepEqual(await searchAddress(address), []);
  }
});

test("rejects a street response without usable bounds as malformed", async () => {
  globalThis.fetch = async () => Response.json([{ ...street, boundingbox: undefined }]);
  await assert.rejects(searchAddress(address), error => error instanceof GeocoderError && error.kind === "malformed");
});

test("accepts an exact matching house without manual confirmation", async () => {
  globalThis.fetch = async () => Response.json([{ ...street, address: { ...street.address, house_number: "2125", state: "Nebraska" } }]);
  const result = await geocodeAddress(address);
  assert.deepEqual(result, { lat: 41.1637462, lng: -96.0079032, precision: "ROOFTOP",
    bounds: { south: 41.1623576, north: 41.1654397, west: -96.0109311, east: -96.0046578 } });
  assert.equal(confirmedHomePin({ lat: 41.1637462, lng: -96.0079032 }, result ? [result] : [], false)?.precision, "ROOFTOP");
  assert.equal(confirmedHomePin({ lat: 41.164, lng: -96.008 }, result ? [result] : [], false), null);
});

test("separates transport, timeout, malformed response, and genuine miss", async () => {
  globalThis.fetch = async () => { throw new Error("offline"); };
  await assert.rejects(searchAddress(address), error => error instanceof GeocoderError && error.kind === "unavailable");
  globalThis.fetch = async () => { throw new DOMException("timeout", "TimeoutError"); };
  await assert.rejects(searchAddress(address), error => error instanceof GeocoderError && error.kind === "timeout");
  globalThis.fetch = async () => Response.json({ results: null });
  await assert.rejects(searchAddress(address), error => error instanceof GeocoderError && error.kind === "malformed");
  globalThis.fetch = async () => Response.json([]);
  assert.deepEqual(await searchAddress(address), []);
});

test("technician lookup API reports misses and geocoder failures separately", async () => {
  const request = () => new NextRequest("http://localhost/api/technicians/geocode", { method: "POST", body: JSON.stringify(address) });
  for (const [reply, expectedStatus] of [
    [async () => Response.json([]), 404],
    [async () => { throw new DOMException("timeout", "TimeoutError"); }, 504],
    [async () => { throw new Error("offline"); }, 503],
    [async () => Response.json({ candidates: null }), 502],
  ] as const) {
    globalThis.fetch = reply;
    const response = await lookupRoute(request());
    assert.equal(response.status, expectedStatus);
    const body: unknown = await response.json();
    assert.equal(z.object({ error: z.string() }).safeParse(body).success, true);
  }
});
