import type { PublicSnapshot } from "./publicApi";
import type { RouteGeometry } from "./schedulerApi";
import type { DriveAnchor, ScheduleDrive } from "./scheduleDrive";

/**
 * The pure server side of the schedule's drive times: the scheduler draws the day's routes in their current order
 * (POST /api/v1/routes/geometry), and each leg's road seconds become the minutes the schedule allows for it.
 */

/**
 * The minutes the scheduler allows for a leg, as Monetary.bufferedMinutes computes them: the road seconds raised by the
 * travel buffer percentage, rounded up to a whole minute, plus the buffer's fixed minutes. `pct` is the snapshot's
 * decimal text ("0.15"), worked in exact integers so the rounding matches the scheduler's.
 */
export function bufferedMinutes(seconds: number, pct: string, extraMinutes: number): number {
  if (!Number.isSafeInteger(seconds) || seconds < 0 || !Number.isSafeInteger(extraMinutes) || extraMinutes < 0)
    throw new Error("Invalid travel buffer inputs");
  const match = /^(0|[1-9][0-9]*)(?:\.([0-9]+))?$/.exec(pct);
  if (match === null) throw new Error(`Invalid travel buffer percentage ${pct}`);
  const whole = match[1] ?? "0", fraction = match[2] ?? "";
  const scale = 10n ** BigInt(fraction.length);
  const raised = BigInt(seconds) * (BigInt(whole + fraction) + scale), perMinute = 60n * scale;
  return Number((raised + perMinute - 1n) / perMinute) + extraMinutes;
}

/**
 * The schedule's drive times for one day from the scheduler's drawing of it: every technician working the day with the
 * start and end points a placed visit would use, and the stretches of those the scheduler drew. Stops are taken in
 * sequence order and shared out to the stretches in turn, each taking one stop fewer than it has legs. `drawn` is null
 * for a day with no visits, which has nothing to draw. A drawing that does not match the snapshot is a fault, never
 * something to guess around.
 */
export function scheduleDriveOf(serviceDate: string, snapshot: PublicSnapshot, drawn: RouteGeometry | null,
  anchors: (technicianId: string) => { departure: DriveAnchor; returnTo: DriveAnchor }): ScheduleDrive {
  const { travelBufferPct, travelBufferMinutes } = snapshot.rates;
  const working = snapshot.technicianDays.filter(day => day.serviceDate === serviceDate).map(day => day.technicianId);
  const stretches = new Map<string, ScheduleDrive["technicians"][number]["segments"]>();
  for (const route of drawn?.routes ?? []) {
    const { technicianId } = route;
    if (route.serviceDate !== serviceDate) throw new Error(`The scheduler drew ${technicianId} on ${route.serviceDate}, not ${serviceDate}`);
    if (!working.includes(technicianId)) throw new Error(`The scheduler drew ${technicianId}, who does not work on ${serviceDate} in the snapshot`);
    if (route.stops.length === 0) continue;
    const stops = [...route.stops].sort((a, b) => a.sequence - b.sequence).map(stop => stop.appointmentId);
    const bySegment = new Map<number, RouteGeometry["routes"][number]["legs"]>();
    for (const leg of route.legs) bySegment.set(leg.segment, [...(bySegment.get(leg.segment) ?? []), leg]);
    let taken = 0;
    const segments = [...bySegment.entries()].sort(([a], [b]) => a - b).map(([segment, legs]) => {
      const ordered = [...legs].sort((a, b) => a.legIndex - b.legIndex);
      if (ordered.some((leg, index) => leg.legIndex !== index))
        throw new Error(`The scheduler drew ${technicianId}'s stretch ${segment} with missing or repeated legs`);
      const appointmentIds = stops.slice(taken, taken + ordered.length - 1);
      if (ordered.length < 2 || appointmentIds.length !== ordered.length - 1)
        throw new Error(`The scheduler drew ${technicianId}'s stretch ${segment} with more legs than stops`);
      taken += appointmentIds.length;
      return { appointmentIds, legs: ordered.map(leg => ({ seconds: leg.seconds, meters: leg.meters,
        minutes: bufferedMinutes(leg.seconds, travelBufferPct, travelBufferMinutes) })) };
    });
    if (taken !== stops.length) throw new Error(`The scheduler drew ${technicianId} with stops outside every stretch`);
    stretches.set(technicianId, segments);
  }
  return { serviceDate, technicians: working.map(technicianId => ({ technicianId, ...anchors(technicianId), segments: stretches.get(technicianId) ?? [] })),
    skipped: (drawn?.skippedTechnicianDays ?? []).filter(day => day.serviceDate === serviceDate)
      .map(day => ({ technicianId: day.technicianId, message: day.message })) };
}
