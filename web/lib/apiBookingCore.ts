import type { z } from "zod";
import { addCalendarDays } from "./date";
import { date as dateContract, type appointmentSearch, type offer } from "./contracts";
import type { CommitReceipt, OfferSet } from "./schedulerApi";
import { dayOfWeek } from "./technicianAvailability";

/** The pure rules of booking through the public API: the horizon, exact timestamps, receipts and search outcomes. */

/** How many times a search or confirm is sent while the scheduler answers STALE: other desks' writes moved the records. */
export const STALE_ATTEMPTS = 3;

/**
 * Runs `call` with a new requestId, building its snapshot from records read at that moment, and again while the
 * scheduler answers 409 STALE (the snapshot was older than records it already used), as the integration guide says.
 * Any other failure, or STALE on the last attempt, is thrown.
 */
export async function sentAgainWhileStale<T>(call: (requestId: string) => Promise<T>, isStale: (error: unknown) => boolean,
  newRequestId: () => string): Promise<{ requestId: string; result: T }> {
  for (let attempt = 1; ; attempt++) {
    const requestId = newRequestId();
    try { return { requestId, result: await call(requestId) }; }
    catch (error) {
      if (!isStale(error) || attempt === STALE_ATTEMPTS) throw error;
    }
  }
}

/** Every date from tomorrow through the `weekdays`-th weekday, weekends between included (the scheduler's calendar). */
export function bookingHorizon(today: string, weekdays: number): string[] {
  dateContract.parse(today);
  if (!Number.isInteger(weekdays) || weekdays < 1 || weekdays > 15) throw new Error(`Invalid booking horizon of ${weekdays} weekdays`);
  const dates: string[] = [];
  let counted = 0;
  for (let date = addCalendarDays(today, 1); counted < weekdays; date = addCalendarDays(date, 1)) {
    dates.push(date);
    const weekday = dayOfWeek(date);
    if (weekday >= 1 && weekday <= 5) counted++;
  }
  return dates;
}

/** An ISO-8601 instant as exact microseconds since 1970; finer digits must be zero, since the database keeps microseconds. */
export function instantMicros(value: string): bigint {
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/.exec(value);
  if (!match) throw new Error(`Not an ISO-8601 instant: ${value}`);
  const [, wholeSeconds, fraction = "", zone] = match;
  const seconds = Date.parse(`${wholeSeconds}${zone}`);
  if (!Number.isFinite(seconds)) throw new Error(`Not an ISO-8601 instant: ${value}`);
  const digits = fraction.padEnd(9, "0");
  if (digits.slice(6) !== "000") throw new Error(`An instant finer than a microsecond cannot match a database timestamp: ${value}`);
  return BigInt(seconds) * 1000n + BigInt(digits.slice(0, 6));
}

export function sameInstant(a: string, b: string): boolean {
  return instantMicros(a) === instantMicros(b);
}

export type TechnicianDayKey = { technicianId: string; serviceDate: string };

/**
 * Checks a booking receipt before anything is written: it is for one date, names the new job exactly once, lists
 * each technician-day once, and every assignment is on a listed technician-day. Returns the technician-days, sorted.
 */
export function checkReceipt(receipt: CommitReceipt, jobId: string, serviceDate: string): TechnicianDayKey[] {
  const days = new Map<string, TechnicianDayKey>();
  for (const day of receipt.technicianDays) {
    if (day.serviceDate !== serviceDate) throw new Error(`The receipt lists ${day.serviceDate}, but the booking is on ${serviceDate}`);
    const key = `${day.technicianId}|${day.serviceDate}`;
    if (days.has(key)) throw new Error(`The receipt lists technician-day ${key} twice`);
    days.set(key, { technicianId: day.technicianId, serviceDate: day.serviceDate });
  }
  const seen = new Set<string>();
  for (const assignment of receipt.assignments) {
    if (!days.has(`${assignment.technicianId}|${assignment.serviceDate}`))
      throw new Error(`Appointment ${assignment.appointmentId} is on a technician-day the receipt does not list`);
    if (seen.has(assignment.appointmentId)) throw new Error(`The receipt assigns appointment ${assignment.appointmentId} twice`);
    seen.add(assignment.appointmentId);
    if (instantMicros(assignment.plannedEnd) <= instantMicros(assignment.plannedStart))
      throw new Error(`Appointment ${assignment.appointmentId} ends before it starts`);
  }
  if (!seen.has(jobId)) throw new Error(`The receipt does not assign the booked job ${jobId}`);
  return [...days.values()].sort((a, b) => a.technicianId.localeCompare(b.technicianId) || a.serviceDate.localeCompare(b.serviceDate));
}

type Search = z.infer<typeof appointmentSearch>;
type PortalOffer = z.infer<typeof offer>;

/** The booking page's view of an offer set: AVAILABLE with offers, otherwise a completed or an incomplete empty search. */
export function offersView(jobId: string, set: OfferSet, elapsedMs: number): { jobId: string; offers: PortalOffer[]; search: Search } {
  const offers = set.offers.map(item => ({ offerId: item.offerId, date: item.serviceDate,
    windowStart: new Date(item.window.start).toISOString(), windowEnd: new Date(item.window.end).toISOString(),
    expiresAt: new Date(set.expiresAt).toISOString() }));
  if (offers.length > 0)
    return { jobId, offers, search: { outcome: "AVAILABLE", prescribedSearchCompleted: set.searchComplete, elapsedMs, retryable: false } };
  // An empty list proves there is no capacity only when the search finished.
  return { jobId, offers, search: set.searchComplete
    ? { outcome: "NO_CANDIDATE_FOUND", prescribedSearchCompleted: true, elapsedMs, retryable: false }
    : { outcome: "SEARCH_INCOMPLETE", prescribedSearchCompleted: false, elapsedMs, retryable: true } };
}

/** A search that could not run: busy and routing outages are retryable and say nothing about capacity. */
export function failedSearch(jobId: string, outcome: "SERVICE_BUSY" | "ROUTING_UNAVAILABLE" | "SCHEDULE_CONFLICT", elapsedMs: number) {
  return { jobId, offers: [], search: { outcome, prescribedSearchCompleted: false, elapsedMs, retryable: true } };
}
