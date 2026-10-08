import { test } from "node:test";
import assert from "node:assert/strict";
import { dueSlot, minuteOf, overnightDates, runTimes, SCHEDULE_GRACE_MS, timeOf } from "./overnightCore";

test("run times are 24-hour local times, each listed once, and none means manual only", () => {
  assert.equal(minuteOf("02:00"), 120);
  assert.equal(minuteOf("23:59"), 1439);
  assert.equal(timeOf(0), "00:00");
  assert.equal(timeOf(1439), "23:59");
  for (const bad of ["24:00", "2:00", "02:60", "", "02:00 "]) assert.throws(() => minuteOf(bad), bad);
  assert.throws(() => timeOf(1440));
  assert.throws(() => timeOf(1.5));
  assert.equal(runTimes.safeParse({ times: [] }).success, true);
  assert.equal(runTimes.safeParse({ times: ["01:00", "04:00"] }).success, true);
  assert.equal(runTimes.safeParse({ times: ["01:00", "01:00"] }).success, false);
  assert.equal(runTimes.safeParse({ times: ["25:00"] }).success, false);
  assert.equal(runTimes.safeParse({ times: Array.from({ length: 9 }, (_, hour) => timeOf(hour * 60)) }).success, false);
  assert.equal(runTimes.safeParse({}).success, false, "A missing list is not read as manual only");
  assert.equal(runTimes.safeParse({ times: [], extra: true }).success, false);
});

test("a scheduled run is due from its time until the grace period ends", () => {
  // 2026-10-14 is a Wednesday; Chicago is UTC-5 in October.
  const twoAm = new Date("2026-10-14T07:00:00Z");
  assert.equal(dueSlot([120], new Date("2026-10-14T06:59:59Z")), null, "Not before its time");
  assert.equal(dueSlot([120], twoAm)?.toISOString(), twoAm.toISOString());
  assert.equal(dueSlot([120], new Date(twoAm.getTime() + SCHEDULE_GRACE_MS))?.toISOString(), twoAm.toISOString());
  assert.equal(dueSlot([120], new Date(twoAm.getTime() + SCHEDULE_GRACE_MS + 1)), null, "A missed run is skipped, not run late");
  assert.equal(dueSlot([], twoAm), null, "Manual only");
  assert.equal(dueSlot([60, 120], new Date("2026-10-14T07:10:00Z"))?.toISOString(), twoAm.toISOString(), "The latest due time wins");
  assert.equal(dueSlot([1430], new Date("2026-10-14T05:00:00Z"))?.toISOString(), "2026-10-14T04:50:00.000Z", "Yesterday's 23:50 is due just after midnight");
});

test("run times follow daylight saving time in Chicago", () => {
  // 2026-11-01: 01:00 to 02:00 repeats. The run is due once, at the first 01:30.
  assert.equal(dueSlot([90], new Date("2026-11-01T06:30:00Z"))?.toISOString(), "2026-11-01T06:30:00.000Z");
  assert.equal(dueSlot([90], new Date("2026-11-01T07:30:00Z"), 0), null, "The second 01:30 is not a new run time");
  // 2026-03-08: 02:00 to 03:00 is skipped, so 02:30 runs at 03:30 CDT.
  assert.equal(dueSlot([150], new Date("2026-03-08T08:30:00Z"))?.toISOString(), "2026-03-08T08:30:00.000Z");
});

test("a run proposes the scheduler's overnight days: from today until ten weekdays, frozen days left out", () => {
  // Wednesday 2026-10-14 at 02:00: today is not frozen yet.
  const night = overnightDates(new Date("2026-10-14T07:00:00Z"));
  assert.equal(night[0], "2026-10-14");
  assert.equal(night.at(-1), "2026-10-27", "Ten weekdays from Wednesday end on the second Tuesday");
  assert.equal(night.length, 14, "Both weekends in between are included");
  assert.ok(night.includes("2026-10-17") && night.includes("2026-10-18"));
  // After 06:00 today is frozen and the window moves one weekday further.
  const morning = overnightDates(new Date("2026-10-14T11:00:00Z"));
  assert.equal(morning[0], "2026-10-15");
  assert.equal(morning.at(-1), "2026-10-28");
  // Saturday at 02:00: the weekend days come first and are not counted as weekdays.
  const saturday = overnightDates(new Date("2026-10-17T07:00:00Z"));
  assert.deepEqual(saturday.slice(0, 3), ["2026-10-17", "2026-10-18", "2026-10-19"]);
  assert.equal(saturday.at(-1), "2026-10-30");
});
