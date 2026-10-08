import { test } from "node:test";
import assert from "node:assert/strict";
import { Prisma } from "@prisma/client";
import { canonicalDecimal, publicPolicy, publicRates, saveSolverSettings, solverSettingsInput, toStored, toView } from "./clientSettings";

const input = { regularHourly: "30", overtimeHourly: "45.50", mileagePerMile: "0.67", travelBufferPercent: "20",
  travelBufferMinutes: 5, fairnessBudgetPercent: "2.5", offerLimit: 4, bookingHorizonWeekdays: 10 };

function stored() {
  return { ...toStored(solverSettingsInput.parse(input)), version: 3, updatedAt: new Date("2026-10-08T12:00:00Z") };
}

test("percentages become exact API fractions and money stays canonical", () => {
  assert.deepEqual(publicRates(stored()), { regularHourly: "30", overtimeHourly: "45.5", mileagePerMile: "0.67",
    travelBufferPct: "0.2", travelBufferMinutes: 5 });
  assert.deepEqual(publicPolicy(stored()), { fairnessBudget: "0.025" });
});

test("the view round-trips what was entered", () => {
  const view = toView(stored());
  assert.equal(view.travelBufferPercent, "20");
  assert.equal(view.fairnessBudgetPercent, "2.5");
  assert.equal(view.overtimeHourly, "45.5");
  assert.equal(view.version, 3);
  const { version: _version, updatedAt: _updatedAt, ...edited } = view;
  const again = toStored(solverSettingsInput.parse(edited));
  for (const key of ["regularHourly", "overtimeHourly", "mileagePerMile", "travelBufferPct", "fairnessBudget"] as const)
    assert.ok(again[key].equals(stored()[key]), key);
});

test("canonical decimals match the public API pattern, including database scale padding", () => {
  const pattern = /^(0|[1-9][0-9]*)(\.[0-9]*[1-9])?$/;
  for (const [raw, expected] of [["30.0000", "30"], ["0.2000", "0.2"], ["0", "0"], ["0.0000", "0"], ["1e-7", "0.0000001"], ["120", "120"]] as const) {
    const value = canonicalDecimal(new Prisma.Decimal(raw));
    assert.equal(value, expected);
    assert.match(value, pattern);
  }
  assert.throws(() => canonicalDecimal(new Prisma.Decimal("-1")));
});

test("out-of-range or malformed settings are rejected, never clamped or defaulted", () => {
  const bad: Array<Record<string, unknown>> = [
    { offerLimit: 0 }, { offerLimit: 5 }, { offerLimit: 2.5 }, { bookingHorizonWeekdays: 0 }, { bookingHorizonWeekdays: 16 },
    { travelBufferMinutes: 121 }, { travelBufferMinutes: -1 }, { travelBufferPercent: "100.01" }, { fairnessBudgetPercent: "2.555" },
    { regularHourly: "-1" }, { regularHourly: "30.12345" }, { regularHourly: "1e3" }, { regularHourly: "" }, { mileagePerMile: "abc" },
    { regularHourly: undefined }, { extra: true },
  ];
  for (const change of bad) assert.equal(solverSettingsInput.safeParse({ ...input, ...change }).success, false, JSON.stringify(change));
  assert.equal(solverSettingsInput.safeParse({ ...input, travelBufferPercent: "100", fairnessBudgetPercent: "0" }).success, true);
});

test("a save must say which version it edits, or null for a client without settings", () => {
  assert.equal(saveSolverSettings.safeParse({ settings: input }).success, false);
  assert.equal(saveSolverSettings.safeParse({ expectedVersion: null, settings: input }).success, true);
  assert.equal(saveSolverSettings.safeParse({ expectedVersion: -1, settings: input }).success, false);
});
