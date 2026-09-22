import assert from "node:assert/strict";
import test from "node:test";
import { createTechnicianRequest, standardWeek, updateStandardWeekRequest } from "./contracts";
import { initialAvailability, nextTemplateEffectiveDate, resolveWeeklyDay } from "./technicianAvailability";

const week = initialAvailability(480, 1020).create.days.create;

test("weekly versions select by calendar date and preserve weekend hours", () => {
  const replacement = week.map(day => day.dayOfWeek === 0 ? { ...day, available: true, shiftStartMin: 600, shiftEndMin: 840 } : day);
  const versions = [
    { effectiveDate: new Date("1900-01-01T00:00:00Z"), days: week },
    { effectiveDate: new Date("2026-11-01T00:00:00Z"), days: replacement },
  ];
  assert.equal(resolveWeeklyDay(versions, "2026-10-25").available, false);
  assert.deepEqual(resolveWeeklyDay(versions, "2026-11-01"), replacement[0]);
  assert.equal(resolveWeeklyDay(versions, "2026-11-02").shiftStartMin, 480);
  assert.throws(() => resolveWeeklyDay([], "2026-11-02"), /missing/);
  assert.throws(() => resolveWeeklyDay([{ effectiveDate: new Date("1900-01-01T00:00:00Z"), days: week.slice(1) }], "2026-11-02"));
});

test("tenth weekday horizon counts intervening weekends and daylight saving dates", () => {
  assert.equal(nextTemplateEffectiveDate(new Date("2026-10-23T17:00:00Z")), "2026-11-07");
  assert.equal(nextTemplateEffectiveDate(new Date("2026-10-30T17:00:00Z")), "2026-11-14");
  assert.equal(nextTemplateEffectiveDate(new Date("2026-03-06T18:00:00Z")), "2026-03-21");
});

test("malformed weeks and technician profiles fail validation", () => {
  assert.equal(standardWeek.safeParse(week).success, true);
  assert.equal(standardWeek.safeParse(week.slice(1)).success, false);
  assert.equal(standardWeek.safeParse([...week.slice(1), week[1]]).success, false);
  assert.equal(updateStandardWeekRequest.safeParse({ days: week.map(day => day.dayOfWeek === 1 ? { ...day, shiftStartMin: null } : day) }).success, false);
  assert.equal(createTechnicianRequest.safeParse({ name: "A", email: "bad", phone: "1234567", color: "blue" }).success, false);
});
