import { instantMicros, type TechnicianDayKey } from "./apiBookingCore";
import type { Placement } from "./apiDispatchCore";
import type { PublicSnapshot } from "./publicApi";
import type { DailyProposal } from "./schedulerApi";

/** The pure rules for drawing a stored daily proposal: is the live day still the one it was made from, and the day as it would be. */

const keyOf = (item: TechnicianDayKey) => `${item.technicianId}|${item.serviceDate}`;

/**
 * True when the live snapshot still has exactly the proposal's technician-days and every appointment where the
 * proposal found it. Anything else means the day changed, and the proposal's routes no longer describe it.
 */
export function baselineMatches(snapshot: PublicSnapshot, baseline: readonly Placement[], technicianDays: readonly TechnicianDayKey[]): boolean {
  const live = new Set(snapshot.technicianDays.map(keyOf));
  const stored = new Set(technicianDays.map(keyOf));
  if (live.size !== stored.size || [...stored].some(key => !live.has(key))) return false;
  if (snapshot.appointments.length !== baseline.length) return false;
  const placed = new Map(baseline.map(item => [item.appointmentId, item]));
  if (placed.size !== baseline.length) return false;
  return snapshot.appointments.every(item => {
    const was = placed.get(item.id);
    return was !== undefined && was.technicianId === item.technicianId && was.sequence === item.sequence &&
      instantMicros(was.plannedStart) === instantMicros(item.plannedStart);
  });
}

/**
 * The snapshot with every appointment moved to the technician, order and planned start the proposal gives it. An
 * appointment on a technician-day the proposal skipped keeps its place; any other appointment the proposal does not
 * place is an error, since a partial day is never drawn as the optimized plan.
 */
export function overlayProposal(snapshot: PublicSnapshot, proposal: DailyProposal): PublicSnapshot {
  const skipped = new Set(proposal.skippedTechnicianDays.map(keyOf));
  const days = new Set(snapshot.technicianDays.map(keyOf));
  const placed = new Map<string, { technicianId: string; sequence: number; plannedStart: string }>();
  for (const route of proposal.routes) {
    if (!days.has(keyOf(route))) throw new Error(`The proposal routes ${keyOf(route)}, which is not in the snapshot`);
    for (const stop of route.stops) {
      if (placed.has(stop.appointmentId)) throw new Error(`The proposal places appointment ${stop.appointmentId} twice`);
      placed.set(stop.appointmentId, { technicianId: route.technicianId, sequence: stop.sequence, plannedStart: new Date(stop.plannedStart).toISOString() });
    }
  }
  const known = new Set(snapshot.appointments.map(item => item.id));
  for (const appointmentId of placed.keys())
    if (!known.has(appointmentId)) throw new Error(`The proposal places appointment ${appointmentId}, which is not in the snapshot`);
  const appointments = snapshot.appointments.map(item => {
    const place = placed.get(item.id);
    if (place !== undefined) {
      if (place.technicianId !== item.technicianId && skipped.has(keyOf({ technicianId: place.technicianId, serviceDate: item.serviceDate })))
        throw new Error(`The proposal moves appointment ${item.id} onto a skipped technician-day`);
      return { ...item, ...place };
    }
    if (!skipped.has(keyOf(item))) throw new Error(`The proposal does not place appointment ${item.id}`);
    return item;
  });
  appointments.sort((a, b) => a.serviceDate.localeCompare(b.serviceDate) || a.technicianId.localeCompare(b.technicianId) || a.sequence - b.sequence);
  return { ...snapshot, appointments };
}
