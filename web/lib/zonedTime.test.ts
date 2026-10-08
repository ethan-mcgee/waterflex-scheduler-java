import { test } from "node:test";
import assert from "node:assert/strict";
import { localMinute } from "./zonedTime";

const CHICAGO = "America/Chicago";
const at = (date: string, minute: number, end: boolean) => localMinute(date, minute, end, CHICAGO).toISOString();

test("ordinary days convert with the day's offset, including the end of the day", () => {
  assert.equal(at("2026-10-12", 480, false), "2026-10-12T13:00:00.000Z");
  assert.equal(at("2026-12-14", 480, false), "2026-12-14T14:00:00.000Z");
  assert.equal(at("2026-10-12", 1440, true), "2026-10-13T05:00:00.000Z");
});

test("a repeated hour gives a start the earlier instant and an end the later one, like the scheduler", () => {
  // 2026-11-01 01:30 happens twice in Chicago: 06:30Z (CDT) and 07:30Z (CST).
  assert.equal(at("2026-11-01", 90, false), "2026-11-01T06:30:00.000Z");
  assert.equal(at("2026-11-01", 90, true), "2026-11-01T07:30:00.000Z");
});

test("a skipped hour moves forward by the gap, like ZonedDateTime", () => {
  // 2026-03-08 02:30 does not exist in Chicago; it becomes 03:30 CDT.
  assert.equal(at("2026-03-08", 150, false), "2026-03-08T08:30:00.000Z");
  assert.equal(at("2026-03-08", 150, true), "2026-03-08T08:30:00.000Z");
  assert.equal(at("2026-03-08", 180, false), "2026-03-08T08:00:00.000Z");
});

test("invalid minutes and dates are rejected", () => {
  assert.throws(() => localMinute("2026-10-12", -1, false, CHICAGO));
  assert.throws(() => localMinute("2026-10-12", 1441, true, CHICAGO));
  assert.throws(() => localMinute("2026-10-12", 1.5, false, CHICAGO));
  assert.throws(() => localMinute("2026-13-01", 0, false, CHICAGO));
});
