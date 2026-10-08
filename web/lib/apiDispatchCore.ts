import { z } from "zod";
import { instantMicros, type TechnicianDayKey } from "./apiBookingCore";
import { dailyProposal, type CommitReceipt, type DailyProposal } from "./schedulerApi";
import type { PublicSnapshot } from "./publicApi";

/** The pure rules of daily optimization through the public API: checking a proposal and its receipt, and what it changes. */

const keyOf = (item: TechnicianDayKey) => `${item.technicianId}|${item.serviceDate}`;
const sorted = (days: Iterable<TechnicianDayKey>) => [...days].sort((a, b) => a.technicianId.localeCompare(b.technicianId) || a.serviceDate.localeCompare(b.serviceDate));

/**
 * Checks a proposal against the snapshot it was computed from: every route and skipped technician-day is one of the
 * snapshot's on the requested date, listed once; every appointment is the snapshot's and appears once; stops are in
 * sequence order; and an IMPROVED proposal places every appointment, since a partial schedule is never applied.
 */
export function checkProposal(proposal: DailyProposal, snapshot: PublicSnapshot, serviceDate: string): void {
  const snapshotDays = new Set(snapshot.technicianDays.map(keyOf));
  const appointments = new Set(snapshot.appointments.map(item => item.id));
  const days = new Set<string>();
  const listed = (item: TechnicianDayKey, what: string) => {
    if (item.serviceDate !== serviceDate) throw new Error(`The proposal ${what} ${item.technicianId} on ${item.serviceDate}, but it is for ${serviceDate}`);
    const key = keyOf(item);
    if (!snapshotDays.has(key)) throw new Error(`The proposal ${what} technician-day ${key}, which is not in the snapshot`);
    if (days.has(key)) throw new Error(`The proposal lists technician-day ${key} twice`);
    days.add(key);
  };
  const seen = new Set<string>();
  const once = (appointmentId: string) => {
    if (!appointments.has(appointmentId)) throw new Error(`The proposal names appointment ${appointmentId}, which is not in the snapshot`);
    if (seen.has(appointmentId)) throw new Error(`The proposal places appointment ${appointmentId} twice`);
    seen.add(appointmentId);
  };
  for (const route of proposal.routes) {
    listed(route, "routes");
    route.stops.forEach((stop, index) => {
      once(stop.appointmentId);
      if (stop.sequence !== index) throw new Error(`Stop ${stop.appointmentId} has sequence ${stop.sequence} at position ${index}`);
      if (instantMicros(stop.plannedEnd) <= instantMicros(stop.plannedStart)) throw new Error(`Stop ${stop.appointmentId} ends before it starts`);
    });
  }
  for (const skipped of proposal.skippedTechnicianDays) listed(skipped, "skips");
  for (const appointmentId of proposal.unresolvedAppointmentIds) once(appointmentId);
  if (proposal.decision === "IMPROVED" && proposal.unresolvedAppointmentIds.length > 0)
    throw new Error("An IMPROVED proposal leaves appointments unresolved");
}

/**
 * Checks a daily commit receipt: it lists exactly the technician-days the proposal covers, and its assignments are
 * exactly the proposal's stops. Returns the technician-days to lock, sorted, and the routed ones, whose every
 * appointment the receipt places.
 */
export function checkDailyReceipt(receipt: CommitReceipt, proposal: DailyProposal, covered: readonly TechnicianDayKey[]): { days: TechnicianDayKey[]; routed: TechnicianDayKey[] } {
  const expected = new Set(covered.map(keyOf));
  const listed = new Set<string>();
  for (const day of receipt.technicianDays) {
    const key = keyOf(day);
    if (!expected.has(key)) throw new Error(`The receipt lists technician-day ${key}, which the proposal does not cover`);
    if (listed.has(key)) throw new Error(`The receipt lists technician-day ${key} twice`);
    listed.add(key);
  }
  if (listed.size !== expected.size) throw new Error("The receipt does not list every technician-day the proposal covers");
  const stops = new Map(proposal.routes.flatMap(route => route.stops.map(stop => [stop.appointmentId,
    { technicianId: route.technicianId, serviceDate: route.serviceDate, sequence: stop.sequence, plannedStart: stop.plannedStart, plannedEnd: stop.plannedEnd }] as const)));
  if (receipt.assignments.length !== stops.size) throw new Error("The receipt's assignments are not the proposal's stops");
  for (const assignment of receipt.assignments) {
    const stop = stops.get(assignment.appointmentId);
    if (stop === undefined || stop.technicianId !== assignment.technicianId || stop.serviceDate !== assignment.serviceDate || stop.sequence !== assignment.sequence
      || instantMicros(stop.plannedStart) !== instantMicros(assignment.plannedStart) || instantMicros(stop.plannedEnd) !== instantMicros(assignment.plannedEnd))
      throw new Error(`The receipt places appointment ${assignment.appointmentId} differently from the proposal`);
    stops.delete(assignment.appointmentId);
  }
  return { days: sorted(receipt.technicianDays.map(day => ({ technicianId: day.technicianId, serviceDate: day.serviceDate }))),
    routed: sorted(proposal.routes.map(route => ({ technicianId: route.technicianId, serviceDate: route.serviceDate }))) };
}

export const placement = z.object({ appointmentId: z.string().min(1), technicianId: z.string().min(1), sequence: z.int().min(0), plannedStart: z.iso.datetime() }).strict();
export type Placement = z.infer<typeof placement>;
export const technicianDayKey = z.object({ technicianId: z.string().min(1), serviceDate: z.iso.date() }).strict();

/** A stored proposal row's JSON, validated on every read. */
export const storedProposal = z.object({ proposal: dailyProposal, technicianDays: z.array(technicianDayKey).min(1), baseline: z.array(placement) });

export type ProposalChange = { appointmentId: string; fromTechnicianId: string; toTechnicianId: string; fromSequence: number; toSequence: number;
  fromStart: string; toStart: string };

/** Every routed appointment whose technician, order or planned start the proposal changes from the baseline. */
export function proposalChanges(baseline: readonly Placement[], proposal: DailyProposal): ProposalChange[] {
  const before = new Map(baseline.map(item => [item.appointmentId, item]));
  const changes: ProposalChange[] = [];
  for (const route of proposal.routes)
    for (const stop of route.stops) {
      const was = before.get(stop.appointmentId);
      if (was === undefined) throw new Error(`Appointment ${stop.appointmentId} has no baseline placement`);
      if (was.technicianId !== route.technicianId || was.sequence !== stop.sequence || instantMicros(was.plannedStart) !== instantMicros(stop.plannedStart))
        changes.push({ appointmentId: stop.appointmentId, fromTechnicianId: was.technicianId, toTechnicianId: route.technicianId,
          fromSequence: was.sequence, toSequence: stop.sequence, fromStart: was.plannedStart, toStart: new Date(stop.plannedStart).toISOString() });
    }
  return changes;
}
