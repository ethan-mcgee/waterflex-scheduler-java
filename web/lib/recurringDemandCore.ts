/**
 * Recurring demand: when a customer's recurring service is next due, which branch (depot) serves it, how much free
 * technician time a branch has in a week, and where reserved times fit. Pure rules, unit tested; the database read is
 * in recurringDemand.ts.
 *
 * Only weekdays count: the portal never schedules weekend work. Free time is shift minus booked visits, approved time
 * off and live reservations; drive time is not counted, so it is an upper bound on what the scheduler could fit.
 */

import { addCalendarDays, mondayOfWeek } from "./date";
import { haversineMiles } from "./geo";

export type RecurrenceUnit = "WEEK" | "MONTH";

export interface Recurrence {
  intervalUnit: RecurrenceUnit;
  intervalCount: number;
  /** "YYYY-MM-DD": the first due date, before any visit. */
  startDate: string;
  lastVisitDate: string | null;
}

/** The weeks the demand grid shows. */
export const DEMAND_WEEKS = 4;
/** How near a booked visit must be to a due customer for its technician-day to count as already in the area. */
export const IN_AREA_MILES = 10;
/** Reserved times start on the quarter hour. */
export const RESERVE_STEP_MIN = 15;

function addMonths(date: string, months: number): string {
  const [year, month, day] = date.split("-").map(Number);
  if (year === undefined || month === undefined || day === undefined) throw new Error(`Not a date: ${date}`);
  const index = year * 12 + (month - 1) + months;
  const targetYear = Math.floor(index / 12), targetMonth = index % 12;
  const lastDay = new Date(Date.UTC(targetYear, targetMonth + 1, 0)).getUTCDate();
  return `${String(targetYear).padStart(4, "0")}-${String(targetMonth + 1).padStart(2, "0")}-${String(Math.min(day, lastDay)).padStart(2, "0")}`;
}

/** The next due date: the last visit plus the interval (a month keeps its day, or the month's last), or the start date. */
export function nextDue(series: Recurrence): string {
  if (series.lastVisitDate === null) return series.startDate;
  return series.intervalUnit === "WEEK" ? addCalendarDays(series.lastVisitDate, 7 * series.intervalCount)
    : addMonths(series.lastVisitDate, series.intervalCount);
}

/** The Mondays of the weeks the grid shows: this week's, or next week's on a weekend, and the three after. */
export function demandWeeks(today: string): string[] {
  const day = new Date(`${today}T00:00:00Z`).getUTCDay();
  const first = day === 0 || day === 6 ? addCalendarDays(mondayOfWeek(today), 7) : mondayOfWeek(today);
  return Array.from({ length: DEMAND_WEEKS }, (_, index) => addCalendarDays(first, 7 * index));
}

/** The week a due date falls in. An overdue one is due now, so it falls in the first week; one beyond the grid is null. */
export function weekOfDue(due: string, weeks: readonly string[]): string | null {
  const first = weeks[0];
  if (first === undefined) return null;
  if (due < first) return first;
  const monday = mondayOfWeek(due);
  return weeks.includes(monday) ? monday : null;
}

/** The weekdays of a week. */
export function weekdays(monday: string): string[] {
  return Array.from({ length: 5 }, (_, index) => addCalendarDays(monday, index));
}

export interface DepotPlace { id: string; lat: number; lng: number; radiusMi: number }

/**
 * The branch that serves a place: the nearest depot whose metro's service radius reaches it, the rule booking uses to
 * accept a location. Null for a place no depot reaches.
 */
export function nearestDepot(point: { lat: number; lng: number }, depots: readonly DepotPlace[]): string | null {
  let best: { id: string; distance: number } | null = null;
  for (const depot of depots) {
    const distance = haversineMiles(point.lat, point.lng, depot.lat, depot.lng);
    if (distance <= depot.radiusMi && (best === null || distance < best.distance)) best = { id: depot.id, distance };
  }
  return best?.id ?? null;
}

/** A span of local minutes in a day, start included, end not. */
export interface Interval { start: number; end: number }

/** The shift's free time: the shift minus every busy span, in order. No shift (a day off) is no free time. */
export function freeIntervals(shift: Interval | null, busy: readonly Interval[]): Interval[] {
  if (shift === null || shift.end <= shift.start) return [];
  const sorted = busy.filter(item => item.end > item.start).map(item => ({ start: Math.max(item.start, shift.start), end: Math.min(item.end, shift.end) }))
    .filter(item => item.end > item.start).sort((a, b) => a.start - b.start);
  const free: Interval[] = [];
  let cursor = shift.start;
  for (const item of sorted) {
    if (item.start > cursor) free.push({ start: cursor, end: item.start });
    cursor = Math.max(cursor, item.end);
  }
  if (cursor < shift.end) free.push({ start: cursor, end: shift.end });
  return free;
}

export const totalMinutes = (intervals: readonly Interval[]) => intervals.reduce((sum, item) => sum + item.end - item.start, 0);

/** The earliest quarter-hour start where `duration` minutes fit inside one free span, or null. */
export function firstFit(free: readonly Interval[], duration: number): Interval | null {
  for (const span of free) {
    const start = Math.ceil(span.start / RESERVE_STEP_MIN) * RESERVE_STEP_MIN;
    if (start + duration <= span.end) return { start, end: start + duration };
  }
  return null;
}

/** Takes a reserved span out of a day's free time. */
export function withoutSpan(free: readonly Interval[], taken: Interval): Interval[] {
  return free.flatMap(span => {
    if (taken.end <= span.start || taken.start >= span.end) return [span];
    return [{ start: span.start, end: taken.start }, { start: taken.end, end: span.end }].filter(part => part.end > part.start);
  });
}

export interface DueCustomer { seriesId: string; dueDate: string; durationMin: number }
export interface TechnicianDay { technicianId: string; date: string; free: Interval[] }
export interface PlannedReservation { seriesId: string; dueDate: string; technicianId: string; date: string; startMin: number; endMin: number }

/**
 * Reserves a time for each customer, earliest due first: the first offered technician-day, in the order given, with a
 * free span the visit fits, at its earliest quarter hour. Each reservation leaves that time taken for the next, so no
 * two overlap and none overlaps booked work. Customers that fit nowhere are returned unplaced, never squeezed in.
 */
export function planReservations(customers: readonly DueCustomer[], days: readonly TechnicianDay[]): { planned: PlannedReservation[]; unplaced: string[] } {
  const remaining = days.map(day => ({ ...day, free: [...day.free] }));
  const planned: PlannedReservation[] = [], unplaced: string[] = [];
  const order = [...customers].sort((a, b) => a.dueDate.localeCompare(b.dueDate) || a.seriesId.localeCompare(b.seriesId));
  for (const customer of order) {
    let placed = false;
    for (const day of remaining) {
      const fit = firstFit(day.free, customer.durationMin);
      if (fit === null) continue;
      day.free = withoutSpan(day.free, fit);
      planned.push({ seriesId: customer.seriesId, dueDate: customer.dueDate, technicianId: day.technicianId, date: day.date, startMin: fit.start, endMin: fit.end });
      placed = true;
      break;
    }
    if (!placed) unplaced.push(customer.seriesId);
  }
  return { planned, unplaced };
}

/** Whether a technician-day is already in the area: one of its booked visits lies near one of the due customers. */
export function inArea(visits: readonly { lat: number; lng: number }[], customers: readonly { lat: number; lng: number }[]): boolean {
  return visits.some(visit => customers.some(customer => haversineMiles(visit.lat, visit.lng, customer.lat, customer.lng) <= IN_AREA_MILES));
}

/**
 * "4.5 h": time as the grid shows it, to the half hour. Free time rounds down and a shortfall rounds up, so neither is
 * shown as more favorable than it is.
 */
export function hoursLabel(minutes: number, round: "down" | "up" = "down"): string {
  const halfHours = (round === "down" ? Math.floor(minutes / 30) : Math.ceil(minutes / 30)) / 2;
  return `${Number.isInteger(halfHours) ? halfHours.toFixed(0) : halfHours.toFixed(1)} h`;
}

/** "Nov 16 - 20", or "Nov 30 - Dec 4" across a month: the weekdays of a grid week. */
export function weekLabel(monday: string): string {
  const month = (date: string) => new Date(`${date}T00:00:00Z`).toLocaleDateString("en-US", { month: "short", timeZone: "UTC" });
  const friday = addCalendarDays(monday, 4);
  const dayOf = (date: string) => Number(date.slice(8, 10));
  return month(monday) === month(friday) ? `${month(monday)} ${dayOf(monday)} - ${dayOf(friday)}`
    : `${month(monday)} ${dayOf(monday)} - ${month(friday)} ${dayOf(friday)}`;
}

export interface CellSeries { branchId: string | null; week: string | null; durationMin: number | null; reservation: { status: "RESERVED" | "BOOKED" } | null }
export interface CellDay { branchId: string; date: string; freeMin: number }

/** One grid cell: a branch's due customers in a week, and the free technician time left from today on. */
export interface DemandCell {
  /** Due and without a work order: reserved ones included, since the customer has not booked yet. */
  notBooked: number;
  booked: number;
  reserved: number;
  /** Due, not reserved, and timed: what still needs free time. */
  open: number;
  /** Due customers whose reason codes give no usable minutes; they cannot be reserved. */
  untimed: number;
  openMin: number;
  freeMin: number;
  /** Minutes the open customers need beyond the free time; 0 when it covers them. */
  shortMin: number;
}

export function demandCell(series: readonly CellSeries[], days: readonly CellDay[], branchId: string, week: string, today: string): DemandCell {
  const due = series.filter(item => item.branchId === branchId && item.week === week);
  const booked = due.filter(item => item.reservation?.status === "BOOKED").length;
  const reserved = due.filter(item => item.reservation?.status === "RESERVED").length;
  const unreserved = due.filter(item => item.reservation === null);
  const timed = unreserved.flatMap(item => item.durationMin === null ? [] : [item.durationMin]);
  const openMin = timed.reduce((sum, minutes) => sum + minutes, 0);
  const freeMin = days.filter(item => item.branchId === branchId && mondayOfWeek(item.date) === week && item.date >= today)
    .reduce((sum, item) => sum + item.freeMin, 0);
  return { notBooked: due.length - booked, booked, reserved, open: timed.length, untimed: unreserved.length - timed.length,
    openMin, freeMin, shortMin: Math.max(0, openMin - freeMin) };
}
