// The same cases as web/lib/bookingHorizonCore.test.ts, so the load tool's copy of the rule cannot drift from the portal's.
import assert from "node:assert/strict";
import { test } from "node:test";
import { dayFullness, dynamicHorizon, isWeekday, weekdayWindow, type HorizonRule } from "../src/load/horizon.ts";
import { HostStore, type MetroShape } from "../src/load/host.ts";
import { horizonLines } from "../src/load/report.ts";
import { KIT_POLICY, KIT_RATES, type Snapshot } from "../src/snapshot.ts";

const SATURDAY = "2026-10-10";
const RULE: HorizonRule = { capWeekdays: 15, leadWeekdays: 2, fullPercent: 85 };
const at = (date: string, localHour: number) => `${date}T${String(localHour + 5).padStart(2, "0")}:00:00.000Z`;
const point = { lat: 41.25, lng: -96 };

type Day = { date: string; visits: number; absences?: Array<[number, number]>; technicianId?: string };

function snapshot(days: Day[]): Snapshot {
  const technicianDays: Snapshot["technicianDays"] = [], appointments: Snapshot["appointments"] = [];
  for (const day of days) {
    const technicianId = day.technicianId ?? "tech-a";
    technicianDays.push({ technicianId, serviceDate: day.date, lastModified: "2026-10-01T00:00:00.000Z", shift: { start: at(day.date, 8), end: at(day.date, 17) },
      absences: (day.absences ?? []).map(([from, to]) => ({ start: at(day.date, from), end: at(day.date, to) })), start: point, end: point, maxPaidMinutes: 540 });
    for (let n = 0; n < day.visits; n++)
      appointments.push({ id: `${technicianId}-${day.date}-${n}`, technicianId, serviceDate: day.date, serviceId: "svc", durationMinutes: 45,
        window: { start: at(day.date, 8), end: at(day.date, 17) }, location: point, sequence: n, plannedStart: at(day.date, 9 + 2 * n) });
  }
  return { metroId: "metro", timeZone: "America/Chicago", rates: KIT_RATES, policy: KIT_POLICY,
    technicians: [{ id: "tech-a", qualifications: ["svc"] }, { id: "tech-b", qualifications: ["svc"] }], technicianDays, appointments };
}

const working = (visits: (date: string) => number, overrides: Partial<Record<string, Day>> = {}) =>
  weekdayWindow(SATURDAY, 15).filter(isWeekday).map(date => overrides[date] ?? { date, visits: visits(date) });

test("weekday windows include the weekends between and stay within 21 dates", () => {
  assert.deepEqual(weekdayWindow(SATURDAY, 2), ["2026-10-10", "2026-10-11", "2026-10-12", "2026-10-13"]);
  assert.equal(weekdayWindow("2026-10-12", 10).length, 12);
  assert.ok(weekdayWindow(SATURDAY, 15).length <= 21);
  for (const weekdays of [0, 16, 2.5]) assert.throws(() => weekdayWindow(SATURDAY, weekdays));
});

test("route spans and absences are measured as the portal measures them", () => {
  assert.deepEqual(dayFullness(snapshot([{ date: "2026-10-12", visits: 4 }]), "2026-10-12"), { state: "MEASURED", busyMinutes: 465, availableMinutes: 540 });
  assert.deepEqual(dayFullness(snapshot([]), "2026-10-12"), { state: "NO_CAPACITY" });
  assert.deepEqual(dayFullness(snapshot([{ date: "2026-10-12", visits: 2, absences: [[13, 17]] }]), "2026-10-12"), { state: "MEASURED", busyMinutes: 225, availableMinutes: 300 });
  assert.deepEqual(dayFullness(snapshot([{ date: "2026-10-12", visits: 4, absences: [[10, 12], [11, 12]] }]), "2026-10-12"), { state: "MEASURED", busyMinutes: 345, availableMinutes: 420 });
  assert.deepEqual(dayFullness(snapshot([{ date: "2026-10-12", visits: 0, absences: [[8, 17]] }]), "2026-10-12"), { state: "NO_CAPACITY" });
});

test("the horizon follows the furthest full weekday, skips days nobody works and stops at the cap", () => {
  assert.deepEqual(dynamicHorizon(SATURDAY, snapshot(working(() => 1)), RULE), { dates: weekdayWindow(SATURDAY, 2), lastFullDate: null, capped: false });
  const full = new Set(["2026-10-12", "2026-10-14"]);
  assert.equal(dynamicHorizon(SATURDAY, snapshot(working(date => full.has(date) ? 4 : 3)), RULE).dates.at(-1), "2026-10-16");
  const holiday = { date: "2026-10-15", visits: 0, absences: [[8, 17]] as Array<[number, number]> };
  assert.equal(dynamicHorizon(SATURDAY, snapshot(working(date => date <= "2026-10-14" ? 4 : 0, { "2026-10-15": holiday })), RULE).dates.at(-1), "2026-10-19");
  assert.deepEqual(dynamicHorizon(SATURDAY, snapshot(working(() => 4)), RULE), { dates: weekdayWindow(SATURDAY, 15), lastFullDate: "2026-10-30", capped: true });
  assert.throws(() => dynamicHorizon(SATURDAY, snapshot([{ date: "2026-11-30", visits: 0 }]), RULE), /outside the window/);
  assert.throws(() => dynamicHorizon(SATURDAY, snapshot([]), { ...RULE, fullPercent: 49 }));
});

test("a store with booked-out weekdays reads as full there, and open after them", () => {
  const dates = weekdayWindow(SATURDAY, 15);
  const booked = new Set(dates.filter(isWeekday).slice(0, 12));
  const shape: MetroShape = { metroId: "metro-omaha", timeZone: "America/Chicago", serviceId: "svc", prefix: "t", technicians: 3,
    appointmentsPerDay: 2, bookedDates: booked, bookedAppointments: 4, dates, rates: KIT_RATES, policy: KIT_POLICY };
  const host = new HostStore(shape, () => point, () => point, Date.parse("2026-10-09T12:00:00Z"));
  const decision = dynamicHorizon(SATURDAY, host.snapshot(dates), RULE);
  assert.equal(decision.lastFullDate, [...booked].at(-1));
  assert.equal(decision.dates.filter(isWeekday).length, 14, "Twelve booked-out weekdays and two more");
});

test("the report lists searches by horizon length, with their refinement outcomes and incomplete dates", () => {
  const bucket = (searches: number, refinements: Record<string, number>, ms: number[]) =>
    ({ searches, failed: 0, searchIncomplete: 1, incompleteDates: 2, offers: 4 * ms.length, noOffers: 0, refinements, offersMs: ms });
  const tally = { searches: 5, searchIncomplete: 2, offers: 20, noOffers: 0, selected: 0, holdUnavailable: 0, confirmed: 0, hostWriteConflicts: 0,
    released: 0, failedSearches: 0, stale: 0, refinements: {}, byHorizon: { "19": bucket(2, { SHORTENED: 2 }, [900, 1100]), "4": bucket(3, { SHED: 1, COMPLETE: 2 }, [100, 200, 300]) } };
  const settings = { horizonDates: 4, horizon: { mode: "dynamic", weekdays: 15, leadWeekdays: 2, fullPercent: 85, bookedWeekdays: 12, bookedAppointments: 4 } } as const;
  const lines = horizonLines(settings as unknown as Parameters<typeof horizonLines>[0], tally);
  assert.equal(lines[2], "- Dynamic: 2 weekdays past the furthest weekday at least 85 percent full, at most 15 weekdays");
  assert.deepEqual(lines.slice(-2), ["| 4 | 3 | 0 | 1 | 2 | 4.00 | 0 | SHED 1, COMPLETE 2 | 200 | 300 |", "| 19 | 2 | 0 | 1 | 2 | 4.00 | 0 | SHORTENED 2 | 900 | 1100 |"],
    "Rows run from the shortest horizon to the longest");
});
