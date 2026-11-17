import type { InsertionGap, InsertionStart, InsertionTechnicianDay, RouteInsertions, RouteTotals } from "./insertionContract";

/**
 * The rules of placing a job, or moving an appointment, on the schedule from one route insertion answer. They are
 * browser-safe and pure, so the page works out every hover, drop and comparison locally and the server applies the
 * same rules again before it saves.
 */

/** Where a scheduled appointment is now: its day, its neighbours on that day and what its customer was promised. */
export interface Original {
  appointmentId: string;
  technicianId: string;
  serviceDate: string;
  window: { start: string; end: string };
  plannedStart: string;
  /** The appointment before it on the day, absent when it is the first. */
  afterAppointmentId?: string;
  /** The appointment after it on the day, absent when it is the last. */
  beforeAppointmentId?: string;
}

/** One place on the calendar: a gap of one technician-day. */
export interface Spot { technicianId: string; serviceDate: string; afterAppointmentId?: string; beforeAppointmentId?: string }

/** A stable name for a gap, used to match a drop target, a preview and a save to the same gap. */
export function spotKey(spot: Spot): string {
  return `${spot.technicianId}|${spot.serviceDate}|${spot.afterAppointmentId ?? "start"}|${spot.beforeAppointmentId ?? "end"}`;
}

export function gapSpot(day: InsertionTechnicianDay, gap: InsertionGap): Spot {
  return { technicianId: day.technicianId, serviceDate: day.serviceDate,
    ...(gap.afterAppointmentId === undefined ? {} : { afterAppointmentId: gap.afterAppointmentId }),
    ...(gap.beforeAppointmentId === undefined ? {} : { beforeAppointmentId: gap.beforeAppointmentId }) };
}

/** The technician-day and gap the answer has for a spot, or undefined when the answer does not list it. */
export function findGap(answer: RouteInsertions, spot: Spot): { day: InsertionTechnicianDay; gap: InsertionGap } | undefined {
  const day = answer.technicianDays.find(item => item.technicianId === spot.technicianId && item.serviceDate === spot.serviceDate);
  const gap = day?.gaps.find(item => item.afterAppointmentId === spot.afterAppointmentId && item.beforeAppointmentId === spot.beforeAppointmentId);
  return day === undefined || gap === undefined ? undefined : { day, gap };
}

/** The start at a planned instant, compared as instants so the text's offset does not matter. */
export function findStart(gap: InsertionGap, plannedStart: string): InsertionStart | undefined {
  const at = Date.parse(plannedStart);
  return gap.starts.find(start => Date.parse(start.plannedStart) === at);
}

/** A gap that can take the job, with the drive it adds. */
export interface Fit { spot: Spot; key: string; gap: InsertionGap; addedBufferedMinutes: number; firstStart: InsertionStart }

/**
 * Every gap with at least one start, cheapest added drive first. Ties go to the earlier date, then the earlier first
 * start, then the technician, so the order never depends on the answer's order.
 */
export function rankedFits(answer: RouteInsertions): Fit[] {
  const fits: Fit[] = [];
  for (const day of answer.technicianDays) {
    for (const gap of day.gaps) {
      const firstStart = gap.starts[0];
      // Starts exist only on a routable gap, which always carries its added drive.
      if (firstStart === undefined || gap.addedBufferedMinutes === undefined) continue;
      const spot = gapSpot(day, gap);
      fits.push({ spot, key: spotKey(spot), gap, addedBufferedMinutes: gap.addedBufferedMinutes, firstStart });
    }
  }
  return fits.sort((a, b) => a.addedBufferedMinutes - b.addedBufferedMinutes || a.spot.serviceDate.localeCompare(b.spot.serviceDate)
    || Date.parse(a.firstStart.plannedStart) - Date.parse(b.firstStart.plannedStart) || a.spot.technicianId.localeCompare(b.spot.technicianId));
}

/**
 * The place to recommend: the best fit, starting at its first start. A move recommends only places that keep the
 * customer's promised day and window, from the first start that keeps it, because a changed promise breaks the
 * dealership's policy; with no such place it recommends nothing. Null when nothing fits.
 */
export function recommendation(answer: RouteInsertions, moving: boolean): Fit | null {
  for (const fit of rankedFits(answer)) {
    if (!moving) return fit;
    const keeping = fit.gap.starts.find(start => start.keepsWindow === true);
    if (keeping !== undefined) return { ...fit, firstStart: keeping };
  }
  return null;
}

/** A stop's planned visit, as an instant pair. */
export interface PlannedTimes { plannedStart: string; plannedEnd: string }

/**
 * Every stop's planned visit on the days a placement touches, as the scheduler timed them with the subject at
 * `plannedStart` in `spot`: a moved appointment's own day without it, then the chosen day with it, which wins when they
 * are the same day. The subject itself is listed too. Stops the answer did not time (asked without routes, or a day
 * that does not hold as given) are left out, so they keep their booked times rather than an invented one.
 */
export function previewTimes(answer: RouteInsertions, original: Original | null, spot: Spot | null, plannedStart: string | null): Map<string, PlannedTimes> {
  const times = new Map<string, PlannedTimes>();
  const put = (stops: ReadonlyArray<{ appointmentId: string } & PlannedTimes> | undefined) => {
    for (const stop of stops ?? []) times.set(stop.appointmentId, { plannedStart: stop.plannedStart, plannedEnd: stop.plannedEnd });
  };
  if (original !== null)
    put(answer.technicianDays.find(day => day.technicianId === original.technicianId && day.serviceDate === original.serviceDate)?.stops);
  if (spot !== null && plannedStart !== null) {
    const found = findGap(answer, spot);
    put(found === undefined ? undefined : findStart(found.gap, plannedStart)?.stops);
  }
  return times;
}

/** How a gap's added drive compares with the recommendation's: "+4 min vs best", "3 min less than best" or "Same as best". */
export function relativeToBest(addedBufferedMinutes: number, best: Fit): string {
  const difference = addedBufferedMinutes - best.addedBufferedMinutes;
  return difference === 0 ? "Same as best" : difference > 0 ? `+${difference} min vs best` : `${-difference} min less than best`;
}

/**
 * Why nothing can be placed at a spot, or null when it has starts. A drop is never refused: the reason is shown and
 * Save stays off. `gap` is absent when the drop was on a technician-day rather than a gap.
 */
export function whyNot(answer: RouteInsertions, technicianId: string, serviceDate: string, gap?: InsertionGap): string | null {
  const skipped = answer.skippedTechnicianDays.find(item => item.technicianId === technicianId && item.serviceDate === serviceDate);
  if (skipped !== undefined) return `This route could not be placed on the map: ${skipped.message}`;
  const day = answer.technicianDays.find(item => item.technicianId === technicianId && item.serviceDate === serviceDate);
  if (day === undefined) return "This technician is not working that day.";
  if (!day.qualified) return "This technician is not qualified for this service.";
  if (!day.holdsAsGiven) return "This route already misses an arrival window, the shift or the paid limit, so nothing can be added to it.";
  if (gap === undefined) return day.gaps.some(item => item.starts.length > 0) ? null
    : "No place on this route keeps every customer's arrival window, the shift and the paid limit.";
  if (!gap.routable) return "There is no road route to or from this stop, so its drive time is unknown.";
  if (gap.starts.length === 0)
    return "No start here keeps every customer's arrival window, the shift and the paid limit, and arrives before the late-arrival buffer.";
  return null;
}

/** Totals summed over technician-days. */
export function sumTotals(...items: readonly RouteTotals[]): RouteTotals {
  return items.reduce((sum, item) => ({ driveMinutes: sum.driveMinutes + item.driveMinutes, meters: sum.meters + item.meters,
    paidMinutes: sum.paidMinutes + item.paidMinutes, costCents: sum.costCents + item.costCents, bufferSeconds: sum.bufferSeconds + item.bufferSeconds }),
  { driveMinutes: 0, meters: 0, paidMinutes: 0, costCents: 0, bufferSeconds: 0 });
}

/** The previous assignment against the new one, over every technician-day the move touches. */
export interface Comparison { previous: RouteTotals; next: RouteTotals; better: "NEW" | "PREVIOUS" | "SAME"; sentence: string }

const METERS_PER_MILE = 1609.344;

const minutes = (amount: number) => `${amount} minute${amount === 1 ? "" : "s"}`;
const miles = (amount: number) => `${(amount / METERS_PER_MILE).toFixed(1)} miles`;
const dollars = (amount: number) => `$${(amount / 100).toFixed(2)}`;

/**
 * "drives 12 minutes less", "covers 7.4 miles more", or null when the difference rounds to nothing in its unit, so a
 * sentence never states a change of "0.0 miles".
 */
function change(verb: string, next: number, previous: number, unit: (amount: number) => string, tail = ""): string | null {
  const delta = next - previous;
  if (delta === 0 || unit(Math.abs(delta)) === unit(0)) return null;
  return `${verb} ${unit(Math.abs(delta))} ${delta < 0 ? "less" : "more"}${tail}`;
}

/** One sentence saying which assignment is better and why, from the totals alone: cost decides, then drive time. */
export function comparisonSentence(previous: RouteTotals, next: RouteTotals): { better: Comparison["better"]; sentence: string } {
  const better = next.costCents < previous.costCents ? "NEW" : next.costCents > previous.costCents ? "PREVIOUS"
    : next.driveMinutes < previous.driveMinutes ? "NEW" : next.driveMinutes > previous.driveMinutes ? "PREVIOUS" : "SAME";
  const parts = [
    change("drive", next.driveMinutes, previous.driveMinutes, minutes),
    change("cover", next.meters, previous.meters, miles),
    change("cost", next.costCents, previous.costCents, dollars),
    change("use", Math.round(next.bufferSeconds / 60), Math.round(previous.bufferSeconds / 60), minutes, " of late-arrival buffer"),
  ].filter((part): part is string => part !== null);
  const lead = better === "NEW" ? "The new assignment is better" : better === "PREVIOUS" ? "The previous assignment was better" : "Both assignments cost the same";
  if (parts.length === 0) return { better, sentence: `${lead}: the routes drive, cost and use buffer the same either way.` };
  const last = parts.pop();
  return { better, sentence: `${lead}: with the move, the routes ${parts.length === 0 ? last : `${parts.join(", ")} and ${last}`}.` };
}

/** The original's own gap in the answer: the appointment's place, timed without it as the job. */
function originalGap(answer: RouteInsertions, original: Original) {
  return findGap(answer, { technicianId: original.technicianId, serviceDate: original.serviceDate,
    ...(original.afterAppointmentId === undefined ? {} : { afterAppointmentId: original.afterAppointmentId }),
    ...(original.beforeAppointmentId === undefined ? {} : { beforeAppointmentId: original.beforeAppointmentId }) });
}

/** The start in the original gap nearest the stored planned start, the earlier on a tie. */
function nearestStart(gap: InsertionGap, plannedStart: string): InsertionStart | undefined {
  const at = Date.parse(plannedStart);
  return [...gap.starts].sort((a, b) => Math.abs(Date.parse(a.plannedStart) - at) - Math.abs(Date.parse(b.plannedStart) - at)
    || Date.parse(a.plannedStart) - Date.parse(b.plannedStart))[0];
}

/**
 * The previous assignment against the new one. The answer was computed with the appointment taken out, so its own gap
 * times the previous assignment (at the start nearest its stored one) and the other technician-day's totals as given
 * complete the sum. Null when the previous assignment cannot be timed from this answer, for example when that route
 * no longer holds.
 */
export function compare(answer: RouteInsertions, original: Original, spot: Spot, start: InsertionStart): Comparison | null {
  const before = originalGap(answer, original);
  if (before === undefined) return null;
  const previousStart = nearestStart(before.gap, original.plannedStart);
  if (previousStart === undefined) return null;
  const sameDay = spot.technicianId === original.technicianId && spot.serviceDate === original.serviceDate;
  let previous: RouteTotals, next: RouteTotals;
  if (sameDay) {
    previous = previousStart.timed;
    next = start.timed;
  } else {
    const target = findGap(answer, spot)?.day.asGiven;
    const source = before.day.asGiven;
    if (target === undefined || source === undefined) return null;
    previous = sumTotals(previousStart.timed, target);
    next = sumTotals(source, start.timed);
  }
  return { previous, next, ...comparisonSentence(previous, next) };
}

/** What choosing a start at a spot means for the customer and the dealership's policy. */
export type Verdict =
  | { kind: "CANNOT_HOLD"; reason: string }
  | { kind: "NEW"; start: InsertionStart }
  | { kind: "KEEPS_PROMISE"; start: InsertionStart; samePlace: boolean; comparison: Comparison | null }
  | { kind: "CHANGES_PROMISE"; start: InsertionStart; previous: { serviceDate: string; window: Original["window"] };
      comparison: Comparison | null };

/**
 * The verdict for a start at a spot. A move keeps its promise only on the day it was promised and inside the window
 * the scheduler confirms it keeps; any other day or window changes what the customer was told, which the dealership's
 * policy does not allow without the dispatcher acknowledging it. `original` is null for a job that is not scheduled yet.
 */
export function verdict(answer: RouteInsertions, original: Original | null, spot: Spot, plannedStart: string | null): Verdict {
  const found = findGap(answer, spot);
  const reason = whyNot(answer, spot.technicianId, spot.serviceDate, found?.gap);
  if (found === undefined || reason !== null) return { kind: "CANNOT_HOLD", reason: reason ?? "This place is not in the latest answer." };
  const start = plannedStart === null ? found.gap.starts[0] : findStart(found.gap, plannedStart);
  if (start === undefined) return { kind: "CANNOT_HOLD", reason: "That start does not keep every window, the shift and the paid limit here." };
  if (original === null) return { kind: "NEW", start };
  const comparison = compare(answer, original, spot, start);
  if (spot.serviceDate === original.serviceDate && start.keepsWindow === true) {
    const samePlace = spot.technicianId === original.technicianId && spot.afterAppointmentId === original.afterAppointmentId
      && spot.beforeAppointmentId === original.beforeAppointmentId;
    return { kind: "KEEPS_PROMISE", start, samePlace, comparison };
  }
  return { kind: "CHANGES_PROMISE", start, previous: { serviceDate: original.serviceDate, window: original.window }, comparison };
}

/**
 * The spot and start a drop lands on: in the technician-day's route, the gap after every stop that starts before the
 * dropped minute, and in that gap the start nearest the minute. A drop on a day the answer does not list (not working,
 * or skipped) is still a spot, with no start, so the reason can be shown. `stopMinute` gives a stop's planned start as
 * a minute of the day in the metro's time zone, or undefined when the page does not know it.
 */
export function spotAt(answer: RouteInsertions, technicianId: string, serviceDate: string, minute: number | null,
  stopMinute: (appointmentId: string) => number | undefined, startMinute: (start: InsertionStart) => number): { spot: Spot; plannedStart: string | null } {
  const day = answer.technicianDays.find(item => item.technicianId === technicianId && item.serviceDate === serviceDate);
  if (day === undefined) return { spot: { technicianId, serviceDate }, plannedStart: null };
  const gap = minute === null ? day.gaps[0] : day.gaps.find(item => {
    if (item.beforeAppointmentId === undefined) return true;
    const next = stopMinute(item.beforeAppointmentId);
    return next === undefined || next >= minute;
  });
  // Every listed day has at least one gap; the last has no stop after it, so a gap is always found.
  const chosen = gap ?? day.gaps[day.gaps.length - 1];
  if (chosen === undefined) return { spot: { technicianId, serviceDate }, plannedStart: null };
  const nearest = minute === null ? chosen.starts[0] : [...chosen.starts]
    .sort((a, b) => Math.abs(startMinute(a) - minute) - Math.abs(startMinute(b) - minute) || startMinute(a) - startMinute(b))[0];
  return { spot: gapSpot(day, chosen), plannedStart: nearest?.plannedStart ?? null };
}
