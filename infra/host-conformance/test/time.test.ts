import assert from "node:assert/strict";
import { test } from "node:test";
import { addDays, datesBetween, localInstant, requireTimeZone, todayIn, wallClock } from "../src/time.ts";

const CHICAGO = "America/Chicago";

test("local wall times resolve to the zone's offset in summer and winter", () => {
  assert.equal(localInstant("2026-10-15", 480, CHICAGO), "2026-10-15T13:00:00.000Z");
  assert.equal(localInstant("2026-12-01", 480, CHICAGO), "2026-12-01T14:00:00.000Z");
  assert.equal(localInstant("2026-10-15", 1440, CHICAGO), "2026-10-16T05:00:00.000Z");
  assert.equal(localInstant("2026-10-15", 330, "Asia/Kolkata"), "2026-10-15T00:00:00.000Z");
});

test("a wall time skipped by daylight saving resolves after the gap; a repeated one to its first occurrence", () => {
  assert.equal(localInstant("2026-03-08", 150, CHICAGO), "2026-03-08T08:30:00.000Z");
  assert.equal(localInstant("2026-11-01", 90, CHICAGO), "2026-11-01T06:30:00.000Z");
});

test("wall clock reads an instant back in the zone", () => {
  assert.deepEqual(wallClock("2026-10-15T13:00:00Z", CHICAGO), { date: "2026-10-15", minute: 480, second: 0 });
  assert.deepEqual(wallClock("2026-10-15T08:00:00-05:00", CHICAGO), { date: "2026-10-15", minute: 480, second: 0 });
  assert.deepEqual(wallClock("2026-10-15T04:59:30Z", CHICAGO), { date: "2026-10-14", minute: 1439, second: 30 });
  assert.throws(() => wallClock("yesterday", CHICAGO), /Not an instant/);
});

test("today follows the zone, not UTC", () => {
  const lateEvening = Date.parse("2026-10-16T03:00:00Z");
  assert.equal(todayIn(CHICAGO, lateEvening), "2026-10-15");
  assert.equal(todayIn("UTC", lateEvening), "2026-10-16");
});

test("calendar arithmetic crosses months, years and leap days", () => {
  assert.equal(addDays("2026-10-31", 1), "2026-11-01");
  assert.equal(addDays("2026-01-01", -1), "2025-12-31");
  assert.equal(addDays("2028-02-28", 1), "2028-02-29");
  assert.equal(datesBetween("2026-10-15", "2026-11-05"), 22);
  assert.equal(datesBetween("2026-10-15", "2026-10-15"), 1);
  assert.throws(() => addDays("2026-10-15T00:00", 1), /Not a calendar date/);
});

test("unknown zones and out-of-range minutes are refused", () => {
  assert.throws(() => requireTimeZone("America/Omaha"), /Unknown time zone/);
  assert.equal(requireTimeZone(CHICAGO), CHICAGO);
  assert.throws(() => localInstant("2026-10-15", 1441, CHICAGO), /out of range/);
  assert.throws(() => localInstant("2026-10-15", 1.5, CHICAGO), /out of range/);
});
