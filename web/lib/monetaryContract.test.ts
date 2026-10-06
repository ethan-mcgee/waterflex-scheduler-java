import assert from "node:assert/strict";
import test from "node:test";
import { monetaryDecimal, operatingRates, costModelVersion } from "./contracts";
import { formatCents } from "./money";

test("monetary strings retain exact digits and reject binary-number or noncanonical inputs", () => {
  for (const value of ["0", "20.02", "0.67", "0.000000000000000000000000000001", "12345678901234567890123456789012345"])
    assert.equal(monetaryDecimal.parse(value), value);
  for (const value of [null, undefined, 20.02, NaN, Infinity, "-1", "+1", "01", "1e2", "20.0200", "0.0", "1.", " 1", "0.0000000000000000000000000000001", "123456789012345678901234567890123456"])
    assert.equal(monetaryDecimal.safeParse(value).success, false, String(value));
});
test("cent formatting retains cents at safe integer bounds and negative deltas", () => {
  assert.equal(formatCents(8509), "85.09");
  assert.equal(formatCents(0), "0.00");
  assert.equal(formatCents(-1), "-0.01");
  assert.equal(formatCents(Number.MAX_SAFE_INTEGER), "90071992547409.91");
  assert.equal(formatCents(-Number.MAX_SAFE_INTEGER), "-90071992547409.91");
  for (const value of [NaN, Infinity, 1.5, Number.MAX_SAFE_INTEGER + 1]) assert.throws(() => formatCents(value));
});
test("operating rates and cost provenance preserve the exact wire contract", () => {
  const rates = { regularHourly: "20.02", overtimeHourly: "30.03", mileagePerMile: "0.67", travelBufferPct: 0.2, travelBufferMinutes: 5 };
  assert.deepEqual(operatingRates.parse(rates), rates);
  assert.equal(operatingRates.safeParse({ ...rates, regularHourly: 20.02 }).success, false);
  assert.equal(operatingRates.safeParse({ ...rates, mileagePerMile: null }).success, false);
  assert.equal(costModelVersion.parse("legacy-double-v1"), "legacy-double-v1");
  assert.equal(costModelVersion.parse("exact-fleet-half-up-v2"), "exact-fleet-half-up-v2");
  assert.equal(costModelVersion.safeParse("unknown").success, false);
});
