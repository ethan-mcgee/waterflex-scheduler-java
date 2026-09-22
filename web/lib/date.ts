import { date as dateContract, required } from "./contracts";
// Mirrors engine/app/scheduling/timeutil.py's local_midnight_utc /
// tomorrow_in_tz: converts a metro's local calendar day to the UTC instant
// the engine uses as `appointment.serviceDate`, without a date library.

export function localMidnightUtc(dateStr: string, tz: string): Date {
  dateContract.parse(dateStr);
  const parts = dateStr.split("-").map(Number);
  const [y, m, d] = [required(parts[0]), required(parts[1]), required(parts[2])];
  // The target, encoded naively as if it were already a UTC instant —
  // this is what we compare each guess's local wall-clock reading
  // against. It must stay fixed across iterations (not be re-derived from
  // the current guess), or each pass just re-applies the same offset.
  const targetAsUtc = Date.UTC(y, m - 1, d, 0, 0, 0);

  let guess = new Date(targetAsUtc);

  // Two-pass convergence: format the guess in `tz`, see what local
  // wall-clock time it produced, and correct by the difference between
  // that and the fixed target. One pass is enough unless the correction
  // crosses a DST boundary; two is safe.
  for (let i = 0; i < 2; i++) {
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone: tz,
      hour12: false,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    }).formatToParts(guess);
    const get = (type: string) => Number(required(parts.find((p) => p.type === type)?.value, `Calendar ${type}`));
    const shownAsUtc = Date.UTC(
      get("year"),
      get("month") - 1,
      get("day"),
      get("hour") % 24,
      get("minute"),
      get("second")
    );
    const diff = shownAsUtc - targetAsUtc;
    guess = new Date(guess.getTime() - diff);
  }
  return guess;
}

export function tomorrowInTz(tz: string): string {
  const now = new Date();
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: tz,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(now);
  const get = (type: string) => Number(required(parts.find((p) => p.type === type)?.value, `Calendar ${type}`));
  const todayUtcMidnight = Date.UTC(get("year"), get("month") - 1, get("day"));
  const tomorrow = new Date(todayUtcMidnight + 24 * 60 * 60 * 1000);
  return tomorrow.toISOString().slice(0, 10);
}

/** Add calendar days to a YYYY-MM-DD value without applying a timezone offset. */
export function addCalendarDays(dateStr: string, days: number): string {
  if (!Number.isInteger(days)) throw new Error("Calendar day offset must be an integer");
  const value = new Date(`${dateContract.parse(dateStr)}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

/** Return the Monday that contains the supplied local calendar date. */
export function mondayOfWeek(dateStr: string): string {
  const value = new Date(`${dateContract.parse(dateStr)}T00:00:00Z`);
  const daysSinceMonday = (value.getUTCDay() + 6) % 7;
  value.setUTCDate(value.getUTCDate() - daysSinceMonday);
  return value.toISOString().slice(0, 10);
}

export function weekDates(monday: string): string[] {
  return Array.from({ length: 7 }, (_, index) => addCalendarDays(monday, index));
}

export function calendarDateInTz(value: Date, tz: string): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: tz,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(value);
  const get = (type: string) => required(parts.find((part) => part.type === type)?.value, `Calendar ${type}`);
  return `${get("year")}-${get("month")}-${get("day")}`;
}

export function todayInTz(tz: string, now = new Date()): string {
  return calendarDateInTz(now, tz);
}
