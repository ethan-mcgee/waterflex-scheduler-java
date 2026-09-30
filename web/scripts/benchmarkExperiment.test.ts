import { test } from "node:test";
import assert from "node:assert/strict";
import { experimentDates, verifyExperimentSettings } from "./benchmarkExperiment";

test("frozen horizons reject missing/null/malformed dates and expired comparisons", () => {
  const now = new Date("2026-09-29T18:00:00Z");
  const dates = experimentDates(undefined, now);
  assert.equal(dates.length, 10);
  assert.deepEqual(experimentDates(JSON.stringify(dates), now), dates);
  for (const value of ["null", "[]", '["2026-02-30"]']) assert.throws(() => experimentDates(value, now));
  assert.throws(() => experimentDates(JSON.stringify(dates), new Date("2026-09-30T18:00:00Z")));
});

test("labels cannot substitute for actual strategy and feature settings", () => {
  const settings = { "booking.search.variant": "INSERTION", "booking.search.bounded": "true", "booking.reservations.enabled": "true" };
  verifyExperimentSettings(settings, "INSERTION");
  assert.throws(() => verifyExperimentSettings(null, "BOUNDED"));
  assert.throws(() => verifyExperimentSettings({}, "INSERTION"));
  assert.throws(() => verifyExperimentSettings(settings, "BOUNDED"));
  assert.throws(() => verifyExperimentSettings({ ...settings, "booking.search.bounded": "false" }, "INSERTION"));
});
