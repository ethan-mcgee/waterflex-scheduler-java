import { test } from "node:test";
import assert from "node:assert/strict";
import { Prisma } from "@prisma/client";
import { canonicalDecimal, publicPolicy, publicRates, saveSolverSettings, solverSettingsInput, storedHorizon, toStored, toView } from "./clientSettings";

const input = { regularHourly: "30", overtimeHourly: "45.50", mileagePerMile: "0.67", travelBufferPercent: "20",
  travelBufferMinutes: 5, arrivalBufferPercent: 25, arrivalBufferMinMinutes: 15, arrivalBufferMaxMinutes: 60, fairnessBudgetPercent: "2.5", offerLimit: 4, windowHours: 4, bookingHorizonWeekdays: 10, horizonMode: "FIXED", horizonLeadWeekdays: 2, horizonFullPercent: 85, dailySearch: "TABU_KOPT", costSearchSeconds: 10, fairnessSearchSeconds: 5, unimprovedSearchSeconds: null, bookingDeadlineMs: 5000, regularWindowThreshold: 2 };

function stored() {
  return { ...toStored(solverSettingsInput.parse(input)), version: 3, updatedAt: new Date("2026-10-08T12:00:00Z") };
}

test("percentages become exact API fractions and money stays canonical", () => {
  assert.deepEqual(publicRates(stored()), { regularHourly: "30", overtimeHourly: "45.5", mileagePerMile: "0.67",
    travelBufferPct: "0.2", travelBufferMinutes: 5, arrivalBuffer: { percent: 25, minMinutes: 15, maxMinutes: 60 } });
  assert.deepEqual(publicPolicy(stored()), { fairnessBudget: "0.025", dailySearch: "TABU_KOPT", costSearchSeconds: 10, fairnessSearchSeconds: 5, bookingDeadlineMs: 5000, regularWindowThreshold: 2 });
});

test("cost rates at their caps are accepted (spec 1.7.0)", () => {
  const atCaps = solverSettingsInput.parse({ ...input, regularHourly: "500", overtimeHourly: "500.0000", mileagePerMile: "25" });
  assert.equal(atCaps.overtimeHourly, "500.0000");
  assert.deepEqual(publicRates({ ...toStored(atCaps), version: 0, updatedAt: new Date("2026-10-20T12:00:00Z") }),
    { regularHourly: "500", overtimeHourly: "500", mileagePerMile: "25", travelBufferPct: "0.2", travelBufferMinutes: input.travelBufferMinutes,
      arrivalBuffer: { percent: 25, minMinutes: 15, maxMinutes: 60 } });
});

test("the view round-trips what was entered", () => {
  const view = toView(stored());
  assert.equal(view.travelBufferPercent, "20");
  assert.equal(view.fairnessBudgetPercent, "2.5");
  assert.equal(view.overtimeHourly, "45.5");
  assert.equal(view.version, 3);
  assert.equal(view.windowHours, 4);
  const { version: _version, updatedAt: _updatedAt, ...edited } = view;
  const again = toStored(solverSettingsInput.parse(edited));
  for (const key of ["regularHourly", "overtimeHourly", "mileagePerMile", "travelBufferPct", "fairnessBudget"] as const)
    assert.ok(again[key].equals(stored()[key]), key);
  assert.equal(again.windowHours, 4);
  assert.equal(again.dailySearch, "TABU_KOPT");
  assert.equal(again.costSearchSeconds, 10);
  assert.equal(again.fairnessSearchSeconds, 5);
  assert.equal(again.bookingDeadlineMs, 5000);
  assert.equal(again.regularWindowThreshold, 2);
});

test("the horizon mode round-trips, and the cap, lead and full share form its rule", () => {
  const dynamic = { ...toStored(solverSettingsInput.parse({ ...input, horizonMode: "DYNAMIC", bookingHorizonWeekdays: 15, horizonLeadWeekdays: 3, horizonFullPercent: 90 })),
    version: 1, updatedAt: new Date("2026-10-08T12:00:00Z") };
  assert.deepEqual(storedHorizon(dynamic), { mode: "DYNAMIC", rule: { capWeekdays: 15, leadWeekdays: 3, fullPercent: 90 } });
  assert.equal(toView(dynamic).horizonMode, "DYNAMIC");
  assert.deepEqual(Object.keys(publicPolicy(dynamic)).filter(key => key.startsWith("horizon")), [], "The horizon is the portal's choice, never sent as policy");
  const corrupted = { ...dynamic, horizonMode: "ROLLING" };
  assert.throws(() => storedHorizon(corrupted));
  assert.throws(() => toView(corrupted));
});

test("a stored search method outside the allowed set is never shown or sent", () => {
  const corrupted = { ...stored(), dailySearch: "KOPT" };
  assert.throws(() => publicPolicy(corrupted));
  assert.throws(() => toView(corrupted));
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
    { offerLimit: 0 }, { offerLimit: 5 }, { offerLimit: 2.5 }, { windowHours: 0 }, { windowHours: 9 }, { windowHours: 2.5 },
    { windowHours: "4" }, { windowHours: undefined }, { windowHours: null }, { bookingHorizonWeekdays: 0 }, { bookingHorizonWeekdays: 16 },
    { travelBufferMinutes: 121 }, { travelBufferMinutes: -1 }, { travelBufferPercent: "100.01" }, { fairnessBudgetPercent: "2.555" },
    { regularHourly: "-1" }, { regularHourly: "30.12345" }, { regularHourly: "1e3" }, { regularHourly: "" }, { mileagePerMile: "abc" },
    { regularHourly: "500.0001" }, { overtimeHourly: "750" }, { mileagePerMile: "25.0001" }, { mileagePerMile: "30" },
    { regularHourly: undefined }, { extra: true },
    { dailySearch: "KOPT" }, { dailySearch: "tabu_kopt" }, { dailySearch: "" }, { dailySearch: undefined }, { dailySearch: null },
    { costSearchSeconds: 4 }, { costSearchSeconds: 241 }, { costSearchSeconds: 2.5 }, { costSearchSeconds: "10" }, { costSearchSeconds: null },
    { fairnessSearchSeconds: -1 }, { fairnessSearchSeconds: 241 }, { fairnessSearchSeconds: undefined },
    { costSearchSeconds: 200, fairnessSearchSeconds: 41 },
    { bookingDeadlineMs: 999 }, { bookingDeadlineMs: 5001 }, { bookingDeadlineMs: 4000.5 }, { bookingDeadlineMs: "5000" },
    { bookingDeadlineMs: undefined }, { bookingDeadlineMs: null },
    { regularWindowThreshold: -1 }, { regularWindowThreshold: 1001 }, { regularWindowThreshold: 2.5 }, { regularWindowThreshold: undefined },
    { regularWindowThreshold: null }, { utilizationThreshold: "0.9" },
    { horizonMode: "dynamic" }, { horizonMode: undefined }, { horizonMode: null },
    { horizonLeadWeekdays: 0 }, { horizonLeadWeekdays: 6 }, { horizonLeadWeekdays: 1.5 }, { horizonLeadWeekdays: undefined },
    { horizonFullPercent: 49 }, { horizonFullPercent: 101 }, { horizonFullPercent: 85.5 }, { horizonFullPercent: "85" }, { horizonFullPercent: null },
    { unimprovedSearchSeconds: undefined }, { unimprovedSearchSeconds: 0 }, { unimprovedSearchSeconds: -1 }, { unimprovedSearchSeconds: 2.5 },
    { unimprovedSearchSeconds: "5" }, { unimprovedSearchSeconds: 11 },
    { arrivalBufferPercent: 51 }, { arrivalBufferPercent: -1 }, { arrivalBufferPercent: 2.5 }, { arrivalBufferPercent: "25" }, { arrivalBufferPercent: null },
    { arrivalBufferMinMinutes: 61 }, { arrivalBufferMinMinutes: undefined }, { arrivalBufferMaxMinutes: 241 }, { arrivalBufferMaxMinutes: null },
  ];
  for (const change of bad) assert.equal(solverSettingsInput.safeParse({ ...input, ...change }).success, false, JSON.stringify(change));
  assert.equal(solverSettingsInput.safeParse({ ...input, travelBufferPercent: "100", fairnessBudgetPercent: "0" }).success, true);
  assert.equal(solverSettingsInput.safeParse({ ...input, dailySearch: "TABU", costSearchSeconds: 240, fairnessSearchSeconds: 0 }).success, true);
  assert.equal(solverSettingsInput.safeParse({ ...input, costSearchSeconds: 200, fairnessSearchSeconds: 40 }).success, true);
  assert.equal(solverSettingsInput.safeParse({ ...input, bookingDeadlineMs: 1000, regularWindowThreshold: 0 }).success, true);
  assert.equal(solverSettingsInput.safeParse({ ...input, bookingDeadlineMs: 5000, regularWindowThreshold: 1000 }).success, true);
  assert.equal(solverSettingsInput.safeParse({ ...input, dailySearch: "TABU_KOPT_SIZE_3" }).success, true);
  assert.equal(solverSettingsInput.safeParse({ ...input, unimprovedSearchSeconds: 1 }).success, true);
  assert.equal(solverSettingsInput.safeParse({ ...input, unimprovedSearchSeconds: 10 }).success, true, "up to the cost search time");
});

test("an unimproved limit is sent only when the client set one, and round-trips through the view", () => {
  const limited = { ...toStored(solverSettingsInput.parse({ ...input, unimprovedSearchSeconds: 6 })), version: 1, updatedAt: new Date("2026-10-20T12:00:00Z") };
  assert.equal(publicPolicy(limited).unimprovedSearchSeconds, 6);
  assert.equal(toView(limited).unimprovedSearchSeconds, 6);
  assert.equal("unimprovedSearchSeconds" in publicPolicy(stored()), false, "no limit: the field is left out, so every search spends its whole time");
  assert.equal(toView(stored()).unimprovedSearchSeconds, null);
});

test("the late-arrival buffer round-trips and is always sent, a zero maximum being the client's own choice of none", () => {
  assert.deepEqual(publicRates(stored()).arrivalBuffer, { percent: 25, minMinutes: 15, maxMinutes: 60 });
  assert.equal(toView(stored()).arrivalBufferMaxMinutes, 60);
  const off = { ...toStored(solverSettingsInput.parse({ ...input, arrivalBufferPercent: 0, arrivalBufferMinMinutes: 0, arrivalBufferMaxMinutes: 0 })),
    version: 1, updatedAt: new Date("2026-10-27T12:00:00Z") };
  assert.deepEqual(publicRates(off).arrivalBuffer, { percent: 0, minMinutes: 0, maxMinutes: 0 });
  assert.equal(solverSettingsInput.safeParse({ ...input, arrivalBufferMinMinutes: 60, arrivalBufferMaxMinutes: 60 }).success, true, "min may equal max");
});

test("a save must say which version it edits, or null for a client without settings", () => {
  assert.equal(saveSolverSettings.safeParse({ settings: input }).success, false);
  assert.equal(saveSolverSettings.safeParse({ expectedVersion: null, settings: input }).success, true);
  assert.equal(saveSolverSettings.safeParse({ expectedVersion: -1, settings: input }).success, false);
});
