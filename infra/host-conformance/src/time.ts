// Calendar dates and wall-clock times in the metro's time zone, as a host computes them for its snapshots. Uses only
// Intl, so the kit runs on any Node 22 without a time zone library.

const DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/** Throws for a zone Intl does not know, so a typo fails before any request is sent. */
export function requireTimeZone(zone: string): string {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: zone });
  } catch {
    throw new Error(`Unknown time zone "${zone}"; pass an IANA name such as America/Chicago`);
  }
  return zone;
}

function parts(instant: number, zone: string): { year: number; month: number; day: number; hour: number; minute: number; second: number } {
  const format = new Intl.DateTimeFormat("en-US", { timeZone: zone, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit" });
  const values = new Map(format.formatToParts(new Date(instant)).map(part => [part.type, part.value]));
  const read = (type: Intl.DateTimeFormatPartTypes): number => {
    const value = values.get(type);
    if (value === undefined) throw new Error(`Intl gave no ${type} for ${zone}`);
    return Number(value);
  };
  return { year: read("year"), month: read("month"), day: read("day"), hour: read("hour"), minute: read("minute"), second: read("second") };
}

function pad(value: number, width = 2): string {
  return String(value).padStart(width, "0");
}

function dateParts(date: string): [number, number, number] {
  const match = DATE.exec(date);
  if (match === null) throw new Error(`Not a calendar date: ${date}`);
  return [Number(match[1]), Number(match[2]), Number(match[3])];
}

/** The calendar date in the zone at the given instant (now by default). */
export function todayIn(zone: string, now: number = Date.now()): string {
  const local = parts(now, zone);
  return `${pad(local.year, 4)}-${pad(local.month)}-${pad(local.day)}`;
}

/** The date `days` calendar days after `date` (negative goes back). */
export function addDays(date: string, days: number): string {
  const [year, month, day] = dateParts(date);
  const moved = new Date(Date.UTC(year, month - 1, day + days));
  return `${pad(moved.getUTCFullYear(), 4)}-${pad(moved.getUTCMonth() + 1)}-${pad(moved.getUTCDate())}`;
}

/** The number of dates from `first` to `last`, both included. */
export function datesBetween(first: string, last: string): number {
  const [a, b, c] = dateParts(first);
  const [d, e, f] = dateParts(last);
  return Math.round((Date.UTC(d, e - 1, f) - Date.UTC(a, b - 1, c)) / 86_400_000) + 1;
}

/** Milliseconds the zone's wall clock is ahead of UTC at the instant. */
function offsetAt(instant: number, zone: string): number {
  const local = parts(instant, zone);
  const wall = Date.UTC(local.year, local.month - 1, local.day, local.hour, local.minute, local.second);
  return wall - Math.floor(instant / 1000) * 1000;
}

/**
 * The instant, as an RFC 3339 UTC string, at which the zone's wall clock reads `minute` minutes after midnight on
 * `date`. A wall time skipped by a daylight saving change resolves to the instant just after the gap.
 */
export function localInstant(date: string, minute: number, zone: string): string {
  if (!Number.isInteger(minute) || minute < 0 || minute > 1440) throw new Error(`Minute of day out of range: ${minute}`);
  const [year, month, day] = dateParts(date);
  const wall = Date.UTC(year, month - 1, day, 0, minute);
  const first = wall - offsetAt(wall, zone);
  const second = wall - offsetAt(first, zone);
  // In a gap the second guess lands before the change with a wall time that never occurs; the first is after it.
  return new Date(wall - offsetAt(second, zone) === second ? second : first).toISOString();
}

/** The zone's calendar date and wall-clock minute and second of an RFC 3339 instant. */
export function wallClock(instant: string, zone: string): { date: string; minute: number; second: number } {
  const millis = Date.parse(instant);
  if (!Number.isFinite(millis)) throw new Error(`Not an instant: ${instant}`);
  const local = parts(millis, zone);
  return { date: `${pad(local.year, 4)}-${pad(local.month)}-${pad(local.day)}`, minute: local.hour * 60 + local.minute, second: local.second };
}
