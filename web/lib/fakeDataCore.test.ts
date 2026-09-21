import { required } from "./contracts";
import assert from "node:assert/strict";
import test from "node:test";
import {
  buildCallPlans,
  createSeededRandom,
  dateFromFakeExternalId,
  fakeExternalId,
  serviceCodeForUnit,
  randomLocationForCall,
  validateFakeDataInput,
  OMAHA_FAKE_LOCATIONS,
} from "./fakeDataCore";

test("seeded call planning is deterministic and uses weekdays only", () => {
  const input = validateFakeDataInput({
    startDate: "2026-09-07",
    endDate: "2026-09-13",
    totalCalls: 20,
    seed: 123456,
  });
  const first = buildCallPlans(input, createSeededRandom(123456));
  const second = buildCallPlans(input, createSeededRandom(123456));

  assert.deepEqual(first, second);
  assert.equal(first.length, 20);
  assert.ok(first.every((call) => input.weekdays.includes(call.preferredDate)));
  assert.ok(first.every((call) => call.email.endsWith(".invalid")));
  assert.ok(first.every((call) => call.phone.startsWith("402-555-")));
});

test("service weighting boundaries match the requested mix", () => {
  assert.equal(serviceCodeForUnit(0), "FILTER_SWAP");
  assert.equal(serviceCodeForUnit(0.449999), "FILTER_SWAP");
  assert.equal(serviceCodeForUnit(0.45), "SYSTEM_INSPECTION");
  assert.equal(serviceCodeForUnit(0.749999), "SYSTEM_INSPECTION");
  assert.equal(serviceCodeForUnit(0.75), "REPAIR_DIAGNOSTIC");
  assert.equal(serviceCodeForUnit(0.949999), "REPAIR_DIAGNOSTIC");
  assert.equal(serviceCodeForUnit(0.95), "SOFTENER_INSTALL");
  assert.equal(serviceCodeForUnit(0.999999), "SOFTENER_INSTALL");
});

test("each fake call gets a random street address and geocode in the Omaha metro", () => {
  const random = createSeededRandom(987654321);
  const locations = Array.from({ length: 100 }, (_, index) =>
    randomLocationForCall(required(OMAHA_FAKE_LOCATIONS[index % OMAHA_FAKE_LOCATIONS.length]), random)
  );

  assert.equal(new Set(locations.map((location) => location.line1)).size, locations.length);
  assert.equal(new Set(locations.map((location) => `${location.lat}|${location.lng}`)).size, locations.length);
  assert.ok(locations.every((location) => location.line2 === undefined));
  assert.ok(locations.every((location) => /^\d{3,5} .+ (Ave|Blvd|Cir|Ct|Dr|Ln|Rd|St|Way)$/.test(location.line1)));
  assert.ok(
    locations.every((location, index) => {
      const base = required(OMAHA_FAKE_LOCATIONS[index % OMAHA_FAKE_LOCATIONS.length]);
      const latitudeMiles = (location.lat - base.lat) * 69;
      const longitudeMiles =
        (location.lng - base.lng) * 69 * Math.cos((base.lat * Math.PI) / 180);
      const distanceMiles = Math.hypot(latitudeMiles, longitudeMiles);
      return distanceMiles >= 0 && distanceMiles <= 3.51;
    })
  );
});

test("different generated seeds produce different Omaha addresses", () => {
  const firstRandom = createSeededRandom(111);
  const secondRandom = createSeededRandom(222);
  const first = Array.from({ length: 20 }, (_, index) =>
    randomLocationForCall(required(OMAHA_FAKE_LOCATIONS[index % OMAHA_FAKE_LOCATIONS.length]), firstRandom)
  );
  const second = Array.from({ length: 20 }, (_, index) =>
    randomLocationForCall(required(OMAHA_FAKE_LOCATIONS[index % OMAHA_FAKE_LOCATIONS.length]), secondRandom)
  );

  assert.notDeepEqual(first.map((location) => location.line1), second.map((location) => location.line1));
});

test("input validation accepts 31 days and rejects invalid ranges", () => {
  assert.equal(
    validateFakeDataInput({ startDate: "2026-09-01", endDate: "2026-10-01", totalCalls: 100, seed: 0xffffffff }).weekdays.length,
    23
  );
  assert.throws(
    () => validateFakeDataInput({ startDate: "2026-09-08", endDate: "2026-09-07", totalCalls: 10 }),
    /on or after/
  );
  assert.throws(
    () => validateFakeDataInput({ startDate: "2026-09-01", endDate: "2026-10-02", totalCalls: 10 }),
    /31 calendar days/
  );
  assert.throws(
    () => validateFakeDataInput({ startDate: "2026-09-12", endDate: "2026-09-13", totalCalls: 10 }),
    /weekday/
  );
  assert.throws(
    () => validateFakeDataInput({ startDate: "2026-02-30", endDate: "2026-03-01", totalCalls: 10 }),
    /valid YYYY-MM-DD/
  );
  assert.throws(
    () => validateFakeDataInput({ startDate: "2026-09-08", endDate: "2026-09-08", totalCalls: 101 }),
    /1 to 100/
  );
  assert.throws(
    () => validateFakeDataInput({ startDate: "2026-09-08", endDate: "2026-09-08", totalCalls: 1, seed: 0x100000000 }),
    /unsigned 32-bit/
  );
});

test("versioned external IDs retain the service date for scoped cleanup", () => {
  const externalId = fakeExternalId("2026-09-08", 42, 3, 1);
  assert.match(externalId, /^fake-data:v1:/);
  assert.equal(dateFromFakeExternalId(externalId), "2026-09-08");
  assert.equal(dateFromFakeExternalId("manual:2026-09-08"), null);
});
