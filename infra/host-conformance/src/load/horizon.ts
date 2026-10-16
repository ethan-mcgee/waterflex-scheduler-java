// The dynamic booking horizon, as the portal chooses it (web/lib/bookingHorizonCore.ts): from the first bookable date
// through `leadWeekdays` weekdays past the furthest booked-out weekday, never past the cap. A weekday is booked out when
// its technicians' route spans (shift start to the end of the last planned visit) fill `fullPercent` of their open
// minutes. The kit has no dependency on the portal, so this is a copy; test/horizon.test.ts holds the same cases.
import type { Snapshot } from "../snapshot.ts";
import { addDays } from "../time.ts";

export interface HorizonRule { readonly capWeekdays: number; readonly leadWeekdays: number; readonly fullPercent: number }

export type DayFullness = { state: "NO_CAPACITY" } | { state: "MEASURED"; busyMinutes: number; availableMinutes: number };

export interface HorizonDecision { dates: string[]; lastFullDate: string | null; capped: boolean }

const MINUTE = 60_000;

function instant(value: string): number {
  const ms = Date.parse(value);
  if (!Number.isFinite(ms)) throw new Error(`Not an instant: ${value}`);
  return ms;
}

export function isWeekday(date: string): boolean {
  const day = new Date(`${date}T00:00:00Z`).getUTCDay();
  return day >= 1 && day <= 5;
}

/** Every date from `first` through the `weekdays`-th weekday, weekends between included. */
export function weekdayWindow(first: string, weekdays: number): string[] {
  if (!Number.isInteger(weekdays) || weekdays < 1 || weekdays > 15) throw new Error(`Invalid horizon of ${weekdays} weekdays`);
  const dates: string[] = [];
  for (let date = first, counted = 0; counted < weekdays; date = addDays(date, 1)) {
    dates.push(date);
    if (isWeekday(date)) counted++;
  }
  return dates;
}

/**
 * The dates the host keeps records for: the search window, or through the last booked weekday when the client is booked
 * out past it, as a dealership with a fixed horizon can be. Searches still cover only `weekdayWindow(first, weekdays)`.
 */
export function recordDates(first: string, weekdays: number, bookedWeekdays: number): string[] {
  return weekdayWindow(first, Math.max(weekdays, bookedWeekdays));
}

function coveredMinutes(start: number, end: number, windows: ReadonlyArray<{ start: string; end: string }>): number {
  const clipped = windows.map(window => [Math.max(start, instant(window.start)), Math.min(end, instant(window.end))] as const)
    .filter(([from, to]) => from < to).sort((a, b) => a[0] - b[0]);
  let total = 0, reached = start;
  for (const [from, to] of clipped) {
    const begin = Math.max(from, reached);
    if (to > begin) { total += to - begin; reached = to; }
  }
  return total / MINUTE;
}

export function dayFullness(snapshot: Snapshot, date: string): DayFullness {
  const days = snapshot.technicianDays.filter(day => day.serviceDate === date);
  if (days.length === 0) return { state: "NO_CAPACITY" };
  let busy = 0, available = 0;
  for (const day of days) {
    const shiftStart = instant(day.shift.start), shiftEnd = instant(day.shift.end);
    if (shiftEnd <= shiftStart) throw new Error(`Technician ${day.technicianId} has an empty shift on ${date}`);
    const open = Math.min(day.maxPaidMinutes, (shiftEnd - shiftStart) / MINUTE - coveredMinutes(shiftStart, shiftEnd, day.absences));
    if (open <= 0) continue;
    let spanEnd = shiftStart;
    for (const appointment of snapshot.appointments)
      if (appointment.technicianId === day.technicianId && appointment.serviceDate === date)
        spanEnd = Math.max(spanEnd, instant(appointment.plannedStart) + appointment.durationMinutes * MINUTE);
    busy += Math.min(open, Math.max(0, (spanEnd - shiftStart) / MINUTE - coveredMinutes(shiftStart, spanEnd, day.absences)));
    available += open;
  }
  return available === 0 ? { state: "NO_CAPACITY" } : { state: "MEASURED", busyMinutes: busy, availableMinutes: available };
}

export function checkRule(rule: HorizonRule): void {
  const whole = (value: number, min: number, max: number, name: string) => {
    if (!Number.isInteger(value) || value < min || value > max) throw new Error(`Invalid ${name} ${value}`);
  };
  whole(rule.capWeekdays, 1, 15, "horizon cap");
  whole(rule.leadWeekdays, 1, 5, "lead weekdays");
  whole(rule.fullPercent, 50, 100, "full percent");
}

/** Chooses the horizon from a snapshot of `weekdayWindow(first, rule.capWeekdays)`; lead counts only weekdays with capacity. */
export function dynamicHorizon(first: string, scan: Snapshot, rule: HorizonRule): HorizonDecision {
  checkRule(rule);
  const window = weekdayWindow(first, rule.capWeekdays);
  const inWindow = new Set(window);
  for (const day of scan.technicianDays)
    if (!inWindow.has(day.serviceDate)) throw new Error(`The scan has ${day.serviceDate}, outside the window ${window[0]} to ${window.at(-1)}`);
  const capacity: string[] = [];
  let lastFull = 0;
  for (const date of window) {
    if (!isWeekday(date)) continue;
    const fill = dayFullness(scan, date);
    if (fill.state === "NO_CAPACITY") continue;
    capacity.push(date);
    if (fill.busyMinutes * 100 >= rule.fullPercent * fill.availableMinutes) lastFull = capacity.length;
  }
  const lastFullDate = lastFull === 0 ? null : capacity[lastFull - 1] ?? null;
  const end = capacity[lastFull + rule.leadWeekdays - 1];
  if (end === undefined) return { dates: window, lastFullDate, capped: true };
  return { dates: window.filter(date => date <= end), lastFullDate, capped: false };
}
