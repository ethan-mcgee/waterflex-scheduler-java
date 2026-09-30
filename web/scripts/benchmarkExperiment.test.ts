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
  const reference = "2026-09-29T18:00:00Z";
  const settings = { "scheduler.optimizer.cron": "-", "routing.cache.cleanup-cron": "-", "routing.prewarm.enabled": "false", "time-off.analysis.enabled": "false", "benchmark.calendar-reference": reference, "booking.search.variant": "INSERTION", "booking.search.bounded": "true", "booking.reservations.enabled": "true" };
  verifyExperimentSettings(settings, "INSERTION", reference);
  assert.throws(() => verifyExperimentSettings(null, "BOUNDED"));
  assert.throws(() => verifyExperimentSettings({}, "INSERTION"));
  assert.throws(() => verifyExperimentSettings(settings, "BOUNDED"));
  assert.throws(() => verifyExperimentSettings({ ...settings, "booking.search.bounded": "false" }, "INSERTION"));
});

for (const reference of ["2026-09-30T04:59:59Z", "2026-09-30T10:59:59Z", "2026-10-03T04:59:59Z",
  "2026-03-08T07:59:59Z", "2026-11-01T06:59:59Z"]) {
  test(`frozen calendar survives midnight, cutoff, weekend and DST: ${reference}`, () => {
    const dates = experimentDates(undefined, new Date(reference));
    for (const hours of [1, 6, 24, 72]) {
      const later = new Date(Date.parse(reference) + hours * 3600000);
      assert.deepEqual(experimentDates(JSON.stringify(dates), later, reference), dates);
    }
    assert.throws(() => experimentDates(JSON.stringify(dates), new Date(), "invalid"));
  });
}

test("effective isolation and calendar are mandatory before and after measurement", () => {
  const reference = "2026-09-30T04:59:59.000Z";
  const settings = { "booking.search.variant": "BOUNDED", "booking.search.bounded": "true", "booking.reservations.enabled": "true",
    "scheduler.optimizer.cron": "-", "routing.cache.cleanup-cron": "-", "routing.prewarm.enabled": "false",
    "time-off.analysis.enabled": "false", "benchmark.calendar-reference": reference };
  verifyExperimentSettings(settings, "BOUNDED", reference);
  for (const key of ["scheduler.optimizer.cron", "routing.cache.cleanup-cron", "routing.prewarm.enabled", "time-off.analysis.enabled"])
    assert.throws(() => verifyExperimentSettings({ ...settings, [key]: "unexpected" }, "BOUNDED", reference));
  assert.throws(() => verifyExperimentSettings(settings, "BOUNDED", "2026-10-01T04:59:59.000Z"));
  assert.throws(() => verifyExperimentSettings(settings, "BOUNDED"));
});
