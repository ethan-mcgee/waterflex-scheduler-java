/** How the dispatch board compares the current plan with an optimized one. Pure, so the board's numbers are tested. */

export type PlanChange = { appointmentId: string; fromTechnicianId: string; toTechnicianId: string; fromSequence: number; toSequence: number };
export type ChangeKind = "Reassigned" | "Resequenced" | "Retimed";
export type PlanTotals = { seconds: number; meters: number; costCents: number | null };
/** Each part is null when either plan lacks it; a missing number is never compared as zero. */
export type TotalsDelta = { seconds: number | null; meters: number | null; costCents: number | null };

/** The strongest change to a visit: another technician, then another place in the route, else only a new time. */
export function changeKind(change: PlanChange): ChangeKind {
  if (change.fromTechnicianId !== change.toTechnicianId) return "Reassigned";
  if (change.fromSequence !== change.toSequence) return "Resequenced";
  return "Retimed";
}

export function changedAppointmentIds(changes: readonly PlanChange[]): Set<string> {
  return new Set(changes.map(change => change.appointmentId));
}

/** Every technician whose route gains, loses or reorders a visit; a retimed visit leaves the route's line unchanged. */
export function changedRouteTechnicianIds(changes: readonly PlanChange[]): Set<string> {
  const ids = new Set<string>();
  for (const change of changes) {
    if (changeKind(change) === "Retimed") continue;
    ids.add(change.fromTechnicianId);
    ids.add(change.toTechnicianId);
  }
  return ids;
}

/** Technician-days the commit writes new versions of: every technician a change touches. */
export function touchedTechnicianIds(changes: readonly PlanChange[]): Set<string> {
  return new Set(changes.flatMap(change => [change.fromTechnicianId, change.toTechnicianId]));
}

export function totalsDelta(before: PlanTotals | null, after: PlanTotals | null): TotalsDelta {
  if (before === null || after === null) return { seconds: null, meters: null, costCents: null };
  return { seconds: after.seconds - before.seconds, meters: after.meters - before.meters,
    costCents: before.costCents === null || after.costCents === null ? null : after.costCents - before.costCents };
}

const MINUS = "−";

/** Drive time as "6h 42m", or "42 min" under an hour. */
export function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) throw new Error(`Invalid duration ${seconds}`);
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min`;
  return `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, "0")}m`;
}

/** Distance in miles: one decimal under ten miles, whole miles above. */
export function formatMiles(meters: number): string {
  if (!Number.isFinite(meters) || meters < 0) throw new Error(`Invalid distance ${meters}`);
  const miles = meters / 1609.344;
  return `${miles < 10 ? miles.toFixed(1) : Math.round(miles).toLocaleString("en-US")} mi`;
}

/** Whole dollars, as the board's tiles show cost. */
export function formatDollars(cents: number): string {
  if (!Number.isSafeInteger(cents)) throw new Error(`Invalid cents ${cents}`);
  const dollars = Math.round(Math.abs(cents) / 100).toLocaleString("en-US");
  return `${cents < 0 ? MINUS : ""}$${dollars}`;
}

/** A signed change for a delta pill, or "No change" when it rounds to nothing. */
export function formatDelta(kind: "seconds" | "meters" | "costCents", value: number): string {
  const sign = value < 0 ? MINUS : "+";
  const size = Math.abs(value);
  const text = kind === "seconds" ? formatDuration(size) : kind === "meters" ? formatMiles(size) : formatDollars(size);
  if (text === "0 min" || text === "0.0 mi" || text === "$0") return "No change";
  return `${sign}${text}`;
}
