import { test } from "node:test";
import assert from "node:assert/strict";
import {
  demandCell, demandWeeks, firstFit, freeIntervals, hoursLabel, inArea, nearestDepot, nextDue, planReservations, totalMinutes, weekLabel, weekOfDue, weekdays, withoutSpan,
} from "./recurringDemandCore";

test("the next due date is the last visit plus the interval, or the start date before any visit", () => {
  assert.equal(nextDue({ intervalUnit: "WEEK", intervalCount: 6, startDate: "2026-01-05", lastVisitDate: null }), "2026-01-05");
  assert.equal(nextDue({ intervalUnit: "WEEK", intervalCount: 6, startDate: "2026-01-05", lastVisitDate: "2026-10-20" }), "2026-12-01");
  assert.equal(nextDue({ intervalUnit: "MONTH", intervalCount: 3, startDate: "2026-01-05", lastVisitDate: "2026-09-15" }), "2026-12-15");
  // A month keeps its day, or the month's last when it has no such day, across a year's end too.
  assert.equal(nextDue({ intervalUnit: "MONTH", intervalCount: 1, startDate: "2026-01-05", lastVisitDate: "2026-01-31" }), "2026-02-28");
  assert.equal(nextDue({ intervalUnit: "MONTH", intervalCount: 1, startDate: "2026-01-05", lastVisitDate: "2028-01-31" }), "2028-02-29");
  assert.equal(nextDue({ intervalUnit: "MONTH", intervalCount: 2, startDate: "2026-01-05", lastVisitDate: "2026-11-30" }), "2027-01-30");
});

test("the grid shows four weeks from this one, or from next week on a weekend, and overdue work falls in the first", () => {
  assert.deepEqual(demandWeeks("2026-11-18"), ["2026-11-16", "2026-11-23", "2026-11-30", "2026-12-07"]);
  assert.deepEqual(demandWeeks("2026-11-21"), ["2026-11-23", "2026-11-30", "2026-12-07", "2026-12-14"]);
  const weeks = demandWeeks("2026-11-18");
  assert.equal(weekOfDue("2026-10-01", weeks), "2026-11-16");
  assert.equal(weekOfDue("2026-11-26", weeks), "2026-11-23");
  assert.equal(weekOfDue("2026-12-13", weeks), "2026-12-07");
  assert.equal(weekOfDue("2026-12-14", weeks), null);
  assert.deepEqual(weekdays("2026-11-16"), ["2026-11-16", "2026-11-17", "2026-11-18", "2026-11-19", "2026-11-20"]);
});

test("a series belongs to the nearest depot whose service radius reaches it, and to none when none does", () => {
  const depots = [{ id: "near", lat: 41.25, lng: -95.93, radiusMi: 10 }, { id: "far", lat: 41.6, lng: -96.3, radiusMi: 65 }];
  assert.equal(nearestDepot({ lat: 41.26, lng: -95.94 }, depots), "near");
  // Outside the near depot's radius, the far one still reaches.
  assert.equal(nearestDepot({ lat: 41.5, lng: -96.2 }, depots), "far");
  assert.equal(nearestDepot({ lat: 45, lng: -100 }, depots), null);
});

test("free time is the shift minus busy spans, which may overlap or reach past the shift", () => {
  const shift = { start: 480, end: 1020 };
  assert.deepEqual(freeIntervals(shift, [{ start: 600, end: 660 }, { start: 640, end: 700 }, { start: 1000, end: 1100 }, { start: 300, end: 500 }]),
    [{ start: 500, end: 600 }, { start: 700, end: 1000 }]);
  assert.equal(totalMinutes(freeIntervals(shift, [])), 540);
  assert.deepEqual(freeIntervals(null, []), []);
});

test("a reservation takes the earliest quarter hour it fits in, and the time it takes is no longer free", () => {
  assert.deepEqual(firstFit([{ start: 485, end: 530 }, { start: 600, end: 700 }], 45), { start: 600, end: 645 });
  assert.deepEqual(firstFit([{ start: 485, end: 545 }], 45), { start: 495, end: 540 });
  assert.equal(firstFit([{ start: 480, end: 500 }], 30), null);
  assert.deepEqual(withoutSpan([{ start: 480, end: 600 }], { start: 495, end: 540 }), [{ start: 480, end: 495 }, { start: 540, end: 600 }]);
});

test("customers are reserved earliest due first, never overlapping, and those that fit nowhere stay unplaced", () => {
  const days = [{ technicianId: "t1", date: "2026-11-17", free: [{ start: 480, end: 600 }] }, { technicianId: "t2", date: "2026-11-18", free: [{ start: 540, end: 600 }] }];
  const { planned, unplaced } = planReservations([
    { seriesId: "late", dueDate: "2026-11-20", durationMin: 60 },
    { seriesId: "early", dueDate: "2026-11-16", durationMin: 60 },
    { seriesId: "second", dueDate: "2026-11-17", durationMin: 60 },
    { seriesId: "big", dueDate: "2026-11-19", durationMin: 120 },
  ], days);
  assert.deepEqual(planned, [
    { seriesId: "early", dueDate: "2026-11-16", technicianId: "t1", date: "2026-11-17", startMin: 480, endMin: 540 },
    { seriesId: "second", dueDate: "2026-11-17", technicianId: "t1", date: "2026-11-17", startMin: 540, endMin: 600 },
    { seriesId: "late", dueDate: "2026-11-20", technicianId: "t2", date: "2026-11-18", startMin: 540, endMin: 600 },
  ]);
  assert.deepEqual(unplaced, ["big"]);
});

test("a technician-day is in the area when one of its visits is near a due customer", () => {
  assert.equal(inArea([{ lat: 41.25, lng: -95.93 }], [{ lat: 41.3, lng: -95.95 }]), true);
  assert.equal(inArea([{ lat: 41.25, lng: -95.93 }], [{ lat: 41.6, lng: -96.6 }]), false);
  assert.equal(inArea([], [{ lat: 41.3, lng: -95.95 }]), false);
});

test("hours are shown to the half hour below", () => {
  assert.equal(hoursLabel(0), "0 h");
  assert.equal(hoursLabel(89), "1 h");
  assert.equal(hoursLabel(90), "1.5 h");
  assert.equal(hoursLabel(540), "9 h");
  // A shortfall rounds up, so ten minutes short is never shown as none.
  assert.equal(hoursLabel(10, "up"), "0.5 h");
  assert.equal(hoursLabel(90, "up"), "1.5 h");
});

test("a week is labeled by its weekdays, across a month end too", () => {
  assert.equal(weekLabel("2026-11-16"), "Nov 16 - 20");
  assert.equal(weekLabel("2026-11-30"), "Nov 30 - Dec 4");
});

test("a cell counts a branch week's due customers and the free time left from today on", () => {
  const series = [
    { branchId: "b1", week: "2026-11-16", durationMin: 60, reservation: null },
    { branchId: "b1", week: "2026-11-16", durationMin: 90, reservation: null },
    { branchId: "b1", week: "2026-11-16", durationMin: null, reservation: null },
    { branchId: "b1", week: "2026-11-16", durationMin: 60, reservation: { status: "RESERVED" as const } },
    { branchId: "b1", week: "2026-11-16", durationMin: 60, reservation: { status: "BOOKED" as const } },
    { branchId: "b2", week: "2026-11-16", durationMin: 60, reservation: null },
    { branchId: "b1", week: "2026-11-23", durationMin: 60, reservation: null },
    { branchId: null, week: "2026-11-16", durationMin: 60, reservation: null },
  ];
  const days = [
    { branchId: "b1", date: "2026-11-17", freeMin: 600 },
    { branchId: "b1", date: "2026-11-18", freeMin: 100 },
    { branchId: "b1", date: "2026-11-23", freeMin: 400 },
    { branchId: "b2", date: "2026-11-18", freeMin: 400 },
  ];
  // Tuesday has passed, so only Wednesday's 100 minutes are left for 150 minutes of open work.
  assert.deepEqual(demandCell(series, days, "b1", "2026-11-16", "2026-11-18"),
    { notBooked: 4, booked: 1, reserved: 1, open: 2, untimed: 1, openMin: 150, freeMin: 100, shortMin: 50 });
  assert.deepEqual(demandCell(series, days, "b1", "2026-11-23", "2026-11-18"),
    { notBooked: 1, booked: 0, reserved: 0, open: 1, untimed: 0, openMin: 60, freeMin: 400, shortMin: 0 });
});
