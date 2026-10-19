import { test } from "node:test";
import assert from "node:assert/strict";
import { bookingHorizon } from "./apiBookingCore";
import { dayFullness, dynamicHorizon, lastBookedDate, scanWindow, type HorizonRule } from "./bookingHorizonCore";
import type { PublicSnapshot } from "./publicApi";

// Chicago in October is UTC-5: an 08:00 to 17:00 shift is 13:00Z to 22:00Z, 540 minutes.
const FRIDAY = "2026-10-09";
const RULE: HorizonRule = { capWeekdays: 15, leadWeekdays: 2, fullPercent: 85 };
const at = (date: string, localHour: number, minute = 0) =>
  `${date}T${String(localHour + 5).padStart(2, "0")}:${String(minute).padStart(2, "0")}:00.000Z`;
const point = { lat: 41.25, lng: -96 };

type Day = { date: string; visits: number; absences?: Array<[number, number]>; technicianId?: string };

/** Each day's visits start at 09:00 local, two hours apart, 45 minutes each: four span 86 percent, three 64 percent. */
function snapshot(days: Day[]): PublicSnapshot {
  const technicianDays: PublicSnapshot["technicianDays"] = [], appointments: PublicSnapshot["appointments"] = [];
  for (const day of days) {
    const technicianId = day.technicianId ?? "tech-a";
    technicianDays.push({ technicianId, serviceDate: day.date, lastModified: "2026-10-01T00:00:00.000Z",
      shift: { start: at(day.date, 8), end: at(day.date, 17) },
      absences: (day.absences ?? []).map(([from, to]) => ({ start: at(day.date, from), end: at(day.date, to) })),
      start: point, end: point, maxPaidMinutes: 540 });
    for (let n = 0; n < day.visits; n++)
      appointments.push({ id: `${technicianId}-${day.date}-${n}`, technicianId, serviceDate: day.date, serviceId: "svc", durationMinutes: 45,
        window: { start: at(day.date, 8), end: at(day.date, 17) }, location: point, sequence: n, plannedStart: at(day.date, 9 + 2 * n) });
  }
  return { metroId: "metro", timeZone: "America/Chicago",
    rates: { regularHourly: "20", overtimeHourly: "30", mileagePerMile: "0.5", travelBufferPct: "0", travelBufferMinutes: 0 },
    policy: { fairnessBudget: "0.02", dailySearch: "TABU_KOPT", costSearchSeconds: 10, fairnessSearchSeconds: 5, bookingDeadlineMs: 5000, regularWindowThreshold: 2 },
    technicians: [{ id: "tech-a", qualifications: ["svc"] }, { id: "tech-b", qualifications: ["svc"] }], technicianDays, appointments };
}

const weekdaysOf = (dates: string[]) => dates.filter(date => { const day = new Date(`${date}T00:00:00Z`).getUTCDay(); return day >= 1 && day <= 5; });
const working = (visits: (date: string) => number, overrides: Partial<Record<string, Day>> = {}) =>
  weekdaysOf(scanWindow(FRIDAY, RULE)).map(date => overrides[date] ?? { date, visits: visits(date) });

test("a route span is shift start to the end of the last planned visit, against the shift's open minutes", () => {
  assert.deepEqual(dayFullness(snapshot([{ date: "2026-10-12", visits: 4 }]), "2026-10-12"), { state: "MEASURED", busyMinutes: 465, availableMinutes: 540 });
  assert.deepEqual(dayFullness(snapshot([{ date: "2026-10-12", visits: 0 }]), "2026-10-12"), { state: "MEASURED", busyMinutes: 0, availableMinutes: 540 },
    "An empty working day is measured as empty, not as missing");
  assert.deepEqual(dayFullness(snapshot([]), "2026-10-12"), { state: "NO_CAPACITY" }, "Nobody working is no capacity, never a zero share");
});

test("absences leave the open minutes and the part of the span they cover", () => {
  // Off 13:00 to 17:00 with visits at 09:00 and 11:00: span 08:00 to 11:45 is 225 of 300 open minutes.
  assert.deepEqual(dayFullness(snapshot([{ date: "2026-10-12", visits: 2, absences: [[13, 17]] }]), "2026-10-12"),
    { state: "MEASURED", busyMinutes: 225, availableMinutes: 300 });
  // Off 10:00 to 12:00 inside a four-visit span: 465 - 120 busy of 420 open.
  assert.deepEqual(dayFullness(snapshot([{ date: "2026-10-12", visits: 4, absences: [[10, 12], [11, 12]] }]), "2026-10-12"),
    { state: "MEASURED", busyMinutes: 345, availableMinutes: 420 }, "Overlapping absences count once");
  assert.deepEqual(dayFullness(snapshot([{ date: "2026-10-12", visits: 0, absences: [[8, 17]] }]), "2026-10-12"), { state: "NO_CAPACITY" },
    "A technician absent all day gives no capacity");
});

test("a low-volume schedule books only the lead weekdays, as a fixed two-weekday horizon would", () => {
  const decision = dynamicHorizon(FRIDAY, snapshot(working(() => 1)), RULE);
  assert.deepEqual(decision, { dates: bookingHorizon(FRIDAY, 2), lastFullDate: null, capped: false });
  assert.deepEqual(decision.dates, ["2026-10-10", "2026-10-11", "2026-10-12", "2026-10-13"], "Friday: the weekend, then Monday and Tuesday");
});

test("the horizon ends two weekdays past the furthest full weekday, across an open gap before it", () => {
  const full = new Set(["2026-10-12", "2026-10-14"]);
  const decision = dynamicHorizon(FRIDAY, snapshot(working(date => full.has(date) ? 4 : 3)), RULE);
  assert.equal(decision.lastFullDate, "2026-10-14", "Tuesday's 64 percent does not end the booked-out run");
  assert.equal(decision.dates.at(-1), "2026-10-16");
  assert.equal(decision.capped, false);
});

test("a weekday nobody works does not use up the lead", () => {
  const holiday = { date: "2026-10-15", visits: 0, absences: [[8, 17]] as Array<[number, number]> };
  const decision = dynamicHorizon(FRIDAY, snapshot(working(date => date <= "2026-10-14" ? 4 : 0, { "2026-10-15": holiday })), RULE);
  assert.equal(decision.lastFullDate, "2026-10-14");
  assert.equal(decision.dates.at(-1), "2026-10-19", "Friday and the next Monday are the two lead weekdays");
});

test("a dealership booked out past ten weekdays books further, and never past the cap", () => {
  const twelve = new Set(weekdaysOf(scanWindow(FRIDAY, RULE)).slice(0, 12));
  const busy = dynamicHorizon(FRIDAY, snapshot(working(date => twelve.has(date) ? 4 : 1)), RULE);
  assert.equal(weekdaysOf(busy.dates).length, 14);
  assert.ok(busy.dates.length > bookingHorizon(FRIDAY, 10).length);
  const solid = dynamicHorizon(FRIDAY, snapshot(working(() => 4)), RULE);
  assert.deepEqual(solid, { dates: scanWindow(FRIDAY, RULE), lastFullDate: "2026-10-30", capped: true });
  assert.ok(solid.dates.length <= 21, "Within the API's 21 dates");
});

test("full is measured across every technician working that weekday", () => {
  const days = working(() => 0).flatMap(day => [{ ...day, visits: 4 }, { ...day, visits: 3, technicianId: "tech-b" }]);
  // (465 + 345) / 1080 is 75 percent: below 85, at 75.
  assert.equal(dynamicHorizon(FRIDAY, snapshot(days), RULE).lastFullDate, null);
  assert.equal(dynamicHorizon(FRIDAY, snapshot(days), { ...RULE, fullPercent: 75 }).capped, true);
});

test("a scan outside the window and an invalid rule are refused", () => {
  assert.throws(() => dynamicHorizon(FRIDAY, snapshot([{ date: "2026-11-30", visits: 0 }]), RULE), /outside the window/);
  for (const rule of [{ ...RULE, capWeekdays: 16 }, { ...RULE, leadWeekdays: 0 }, { ...RULE, fullPercent: 49 }, { ...RULE, fullPercent: 85.5 }])
    assert.throws(() => scanWindow(FRIDAY, rule));
});

test("the last booked date is the latest appointment's, or null for none", () => {
  assert.equal(lastBookedDate(snapshot([{ date: "2026-10-12", visits: 1 }, { date: "2026-10-20", visits: 2 }, { date: "2026-10-21", visits: 0 }])), "2026-10-20");
  assert.equal(lastBookedDate(snapshot([{ date: "2026-10-12", visits: 0 }])), null);
});
