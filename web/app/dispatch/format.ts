/** Times on the dispatch board, in the metro's time zone. */
export function clock(iso: string, timezone: string): string {
  return new Intl.DateTimeFormat("en-US", { timeZone: timezone, hour: "numeric", minute: "2-digit" }).format(new Date(iso));
}

export function clockRange(start: string, end: string, timezone: string): string {
  return `${clock(start, timezone)}-${clock(end, timezone)}`;
}

/** "Tuesday, October 13, 2026" for a service date; the date is a calendar day, so it is formatted in UTC. */
export function longDate(date: string): string {
  return new Intl.DateTimeFormat("en-US", { timeZone: "UTC", weekday: "long", month: "long", day: "numeric", year: "numeric" }).format(new Date(`${date}T00:00:00Z`));
}

export function shortDate(date: string): string {
  return new Intl.DateTimeFormat("en-US", { timeZone: "UTC", weekday: "short", month: "short", day: "numeric" }).format(new Date(`${date}T00:00:00Z`));
}

export function shiftDate(date: string, days: number): string {
  const next = new Date(`${date}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + days);
  return next.toISOString().slice(0, 10);
}
