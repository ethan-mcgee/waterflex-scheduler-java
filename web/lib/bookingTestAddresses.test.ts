import assert from "node:assert/strict";
import test from "node:test";
import { generateRealTestInputs, reverseTestAddress, type GeneratedTestLocation } from "./bookingTestAddresses";

const config = { count: 3, seed: 42, policy: "earliest" as const, weights: [1, 1, 1, 1] };
const area = { radiusMi: 65, stateCode: "NE", depots: [{ lat: 41.2565, lng: -95.9345 }] };
const location = (lat: number, lng: number): GeneratedTestLocation => ({ slug: `${lat}:${lng}`, neighborhood: "Omaha", line1: `${Math.abs(Math.round(lat * 100000))} Test Road`, city: "Omaha", state: "Nebraska", postalCode: "68102", lat, lng });

test("explicit seeds produce deterministic unique routable real-address inputs", async () => {
  const generate = () => generateRealTestInputs(config, area, { addresses: new Set(), coordinates: new Set() }, {
    reverse: async (lat, lng) => location(lat, lng), routable: async candidates => new Set(candidates.map(candidate => candidate.id)),
  });
  const first = await generate(), second = await generate();
  assert.deepEqual(first, second);
  assert.equal(new Set(first.map(input => input.location.line1)).size, config.count);
  assert.ok(first.every(input => input.location.line1.match(/^\d+ /) && input.location.city && input.location.state && input.location.postalCode));
});

test("collisions and unroutable candidates are rejected before later candidates succeed", async () => {
  let calls = 0;
  const inputs = await generateRealTestInputs({ ...config, count: 1 }, area, { addresses: new Set(["1 blocked road|omaha|nebraska|68102"]), coordinates: new Set() }, {
    reverse: async (lat, lng) => ++calls === 1 ? { ...location(lat, lng), line1: "1 Blocked Road" } : location(lat, lng),
    routable: async candidates => calls === 2 ? new Set<string>() : new Set(candidates.map(candidate => candidate.id)),
  });
  assert.equal(inputs.length, 1); assert.ok(calls >= 3); assert.notEqual(inputs[0]?.location.line1, "1 Blocked Road");
});

test("attempt exhaustion reports an exact shortfall and returns no partial inputs", async () => {
  let calls = 0;
  await assert.rejects(generateRealTestInputs({ ...config, count: 2 }, area, { addresses: new Set(), coordinates: new Set() }, {
    reverse: async () => { calls++; return null; }, routable: async () => new Set(),
  }), /generated 0 of 2.*shortfall 2.*No run was saved/);
  assert.equal(calls, 200);
});

test("malformed or incomplete Nominatim responses are rejected", async () => {
  const original = globalThis.fetch;
  try {
    for (const body of [null, {}, { lat: "NaN", lon: "-95", address: {} }, { lat: "41", lon: "-95", address: { house_number: "1", road: "Main", state: "Nebraska", postcode: "68102", country_code: "us" } }]) {
      globalThis.fetch = async () => new Response(JSON.stringify(body), { status: 200 });
      assert.equal(await reverseTestAddress(41, -95, new AbortController().signal, "NE"), null);
    }
  } finally { globalThis.fetch = original; }
});

test("configured state completes bounded Nominatim results and rejects conflicts", async () => {
  const original = globalThis.fetch;
  const base = { lat: "41.2526", lon: "-95.9605", address: { house_number: "3206", road: "Leavenworth Street", city: "Omaha", postcode: "68105", country_code: "us" } };
  try {
    globalThis.fetch = async () => new Response(JSON.stringify(base), { status: 200 });
    assert.equal((await reverseTestAddress(41.25, -95.96, new AbortController().signal, "NE"))?.state, "NE");
    globalThis.fetch = async () => new Response(JSON.stringify({ ...base, address: { ...base.address, state: "Iowa" } }), { status: 200 });
    assert.equal(await reverseTestAddress(41.25, -95.96, new AbortController().signal, "NE"), null);
  } finally { globalThis.fetch = original; }
});
