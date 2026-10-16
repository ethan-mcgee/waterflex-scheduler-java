import { bookingHorizon } from "./apiBookingCore";
import type { PublicSnapshot } from "./publicApi";
import { dayOfWeek } from "./technicianAvailability";

/**
 * The dynamic booking horizon: from tomorrow through `leadWeekdays` weekdays past the furthest booked-out weekday,
 * never past the client's cap. A weekday is booked out when its technicians' route spans (shift start to the end of the
 * last planned visit) fill `fullPercent` of their available minutes. Pure, so the rule is tested without a database.
 */

export const HORIZON_MODES = ["FIXED", "DYNAMIC"] as const;
export type HorizonMode = (typeof HORIZON_MODES)[number];
export const MIN_LEAD_WEEKDAYS = 1, MAX_LEAD_WEEKDAYS = 5;
export const MIN_FULL_PERCENT = 50, MAX_FULL_PERCENT = 100;

export interface HorizonRule { capWeekdays: number; leadWeekdays: number; fullPercent: number }

/** A date's fill: no working technician-day at all, or the summed busy and available minutes of those that work. */
export type DayFullness = { state: "NO_CAPACITY" } | { state: "MEASURED"; busyMinutes: number; availableMinutes: number };

export interface HorizonDecision {
  dates: string[];
  /** The furthest booked-out weekday in the scan, or null when none is. */
  lastFullDate: string | null;
  /** The rule reached past the cap (or found no weekday with capacity), so the horizon is the whole cap window. */
  capped: boolean;
}

const MINUTE = 60_000;

function instant(value: string): number {
  const ms = Date.parse(value);
  if (!Number.isFinite(ms)) throw new Error(`Not an instant: ${value}`);
  return ms;
}

/** Minutes of [start, end) covered by the windows, which may overlap each other. */
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

export function dayFullness(snapshot: PublicSnapshot, date: string): DayFullness {
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
    const span = (spanEnd - shiftStart) / MINUTE - coveredMinutes(shiftStart, spanEnd, day.absences);
    busy += Math.min(open, Math.max(0, span));
    available += open;
  }
  // Every working technician is absent all day: there is nothing to book, which is not the same as an empty day.
  return available === 0 ? { state: "NO_CAPACITY" } : { state: "MEASURED", busyMinutes: busy, availableMinutes: available };
}

function checkRule(rule: HorizonRule): void {
  const whole = (value: number, min: number, max: number, name: string) => {
    if (!Number.isInteger(value) || value < min || value > max) throw new Error(`Invalid ${name} ${value}`);
  };
  whole(rule.capWeekdays, 1, 15, "horizon cap");
  whole(rule.leadWeekdays, MIN_LEAD_WEEKDAYS, MAX_LEAD_WEEKDAYS, "lead weekdays");
  whole(rule.fullPercent, MIN_FULL_PERCENT, MAX_FULL_PERCENT, "full percent");
}

/** The cap window a dynamic horizon is chosen from: the snapshot to scan must cover exactly these dates. */
export function scanWindow(today: string, rule: HorizonRule): string[] {
  checkRule(rule);
  return bookingHorizon(today, rule.capWeekdays);
}

const weekday = (date: string) => { const day = dayOfWeek(date); return day >= 1 && day <= 5; };

/** Chooses the horizon from a snapshot of the scan window. Lead counts only weekdays with capacity, so a holiday never uses it up. */
export function dynamicHorizon(today: string, scan: PublicSnapshot, rule: HorizonRule): HorizonDecision {
  const window = scanWindow(today, rule);
  const inWindow = new Set(window);
  for (const day of scan.technicianDays)
    if (!inWindow.has(day.serviceDate)) throw new Error(`The scan has ${day.serviceDate}, outside the window ${window[0]} to ${window.at(-1)}`);
  const capacity: string[] = [];
  let lastFull = 0;
  for (const date of window) {
    if (!weekday(date)) continue;
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

/** The last date of the snapshot with any appointment, or null when it has none. */
export function lastBookedDate(snapshot: PublicSnapshot): string | null {
  let last: string | null = null;
  for (const appointment of snapshot.appointments) if (last === null || appointment.serviceDate > last) last = appointment.serviceDate;
  return last;
}
