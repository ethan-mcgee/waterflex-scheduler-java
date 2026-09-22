import assert from "node:assert/strict";
import test from "node:test";
import { bookingHorizon, chooseTestOffer, DEFAULT_TEST_CONFIG, generateTestInputPlans, TEST_RADIUS_PRESETS, validateTestConfig, validateTestConfigInput } from "./bookingTestCore";
import { localTestRequestAllowed } from "./bookingTestAccess";

const config = { count: 20, seed: 42, policy: "earliest", weights: [1, 1, 1, 1], radiusMi: 30 };
test("seeded generation uses weights and stable order", () => {
  const validated = validateTestConfig(config);
  const first = generateTestInputPlans(validated);
  assert.deepEqual(first, generateTestInputPlans(validated));
  assert.notDeepEqual(first, generateTestInputPlans({ ...validated, seed: 43 }));
  assert.deepEqual(first.map(r => r.ordinal), Array.from({ length: 20 }, (_, i) => i));
  assert.ok(generateTestInputPlans({ ...validated, weights: [0, 0, 1, 0] }).every(r => r.serviceCode === "REPAIR_DIAGNOSTIC"));
  for (const patch of [{ count: 0 }, { count: 101 }, { seed: -1 }, { policy: "invalid" }, { weights: [0, 0, 0, 0] }, { weights: [NaN, 1, 1, 1] },
    { radiusMi: 0 }, { radiusMi: -10 }, { radiusMi: 31 }, { radiusMi: 66 }]) assert.throws(() => validateTestConfig({ ...config, ...patch }));
  for (const radiusMi of [-10, 0, 31, 66]) assert.throws(() => validateTestConfigInput({ ...config, radiusMi }));
  assert.equal(validateTestConfigInput({ ...config, seed: null }).seed, null);
  assert.equal(validateTestConfigInput({ ...config, seed: undefined }).seed, undefined);
  assert.equal(DEFAULT_TEST_CONFIG.radiusMi, 30);
  for (const radiusMi of TEST_RADIUS_PRESETS) assert.equal(validateTestConfigInput({ ...config, radiusMi }).radiusMi, radiusMi);
  assert.equal(validateTestConfig({ count: 1, seed: 1, policy: "first", weights: [1, 0, 0, 0] }).radiusMi, 65);
  assert.throws(() => validateTestConfigInput({ count: 1, seed: 1, policy: "first", weights: [1, 0, 0, 0] }));
});
test("offer policies choose only returned windows without mutating scheduler order", () => {
  const offers = ["2026-09-24T15:00:00Z", "2026-09-23T16:00:00Z", "2026-09-23T14:00:00Z"].map((windowStart, i) => ({ offerId: String(i), date: windowStart.slice(0, 10), windowStart, windowEnd: windowStart, expiresAt: windowStart }));
  assert.equal(chooseTestOffer(offers, "first", .5)?.offerId, "0");
  assert.equal(chooseTestOffer(offers, "earliest", .5)?.offerId, "2");
  assert.equal(chooseTestOffer(offers, "random", .5)?.offerId, "1");
  assert.equal(chooseTestOffer(offers, "random", .999)?.offerId, "2");
  assert.equal(offers[0]?.offerId, "0");
  assert.equal(chooseTestOffer([], "earliest", 0), null);
});
test("horizon follows Chicago tomorrow plus ten weekdays across weekends and DST", () => {
  const horizon = bookingHorizon(new Date("2026-10-31T02:00:00Z"));
  assert.equal(horizon[0], "2026-11-02");
  assert.equal(horizon[9], "2026-11-13");
  assert.equal(bookingHorizon(new Date("2026-09-22T03:00:00Z"))[0], "2026-09-22");
});
test("test APIs require explicit enablement and local same-origin requests", () => {
  const before = process.env.LOCAL_BOOKING_TESTS;
  try {
    process.env.LOCAL_BOOKING_TESTS = "false";
    assert.equal(localTestRequestAllowed(new Request("http://localhost:3001/api/dispatch/testing")), false);
    process.env.LOCAL_BOOKING_TESTS = "true";
    assert.equal(localTestRequestAllowed(new Request("http://localhost:3001/api/dispatch/testing")), true);
    for (const externalOrigin of ["http://localhost:3001", "http://127.0.0.1:3001"]) {
      const host = new URL(externalOrigin).host;
      assert.equal(localTestRequestAllowed(new Request("http://localhost:3000/api/dispatch/testing", {
        headers: { host, origin: externalOrigin, "sec-fetch-site": "same-origin" },
      })), true);
    }
    assert.equal(localTestRequestAllowed(new Request("http://example.com/api/dispatch/testing")), false);
    assert.equal(localTestRequestAllowed(new Request("http://localhost:3000/api/dispatch/testing", {
      headers: { host: "example.com", origin: "http://example.com" },
    })), false);
    assert.equal(localTestRequestAllowed(new Request("http://localhost:3001/api/dispatch/testing", { headers: { origin: "https://example.com" } })), false);
    assert.equal(localTestRequestAllowed(new Request("http://localhost:3000/api/dispatch/testing", {
      headers: { host: "localhost:3001", origin: "http://localhost:3000" },
    })), false);
    assert.equal(localTestRequestAllowed(new Request("http://localhost:3001/api/dispatch/testing", { headers: { "sec-fetch-site": "cross-site" } })), false);
  } finally { if (before === undefined) delete process.env.LOCAL_BOOKING_TESTS; else process.env.LOCAL_BOOKING_TESTS = before; }
});
