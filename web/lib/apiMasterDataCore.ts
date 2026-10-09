import type { DepotFacts, TechnicianFacts } from "./clientSnapshot";
import type { TechnicianDayKey } from "./apiBookingCore";
import type { RouteEvaluation } from "./schedulerApi";
import { addCalendarDays } from "./date";

/**
 * The pure rules of changing a client's own depot and technician facts through the public API: the change applied to
 * snapshot facts, so the scheduler can time the booked days as they would be, and the timed routes as receipt writes.
 */

export type Anchor = "HOME" | "DEPOT";

/** A change that cannot be made, with the HTTP status the portal answers. */
export class MasterDataRefused extends Error {
  constructor(readonly status: number, message: string) { super(message); this.name = "MasterDataRefused"; }
}

/** Snapshot facts, or any part of them that carries the technicians. */
type WithTechnicians = { technicians: TechnicianFacts[] };

function withDepot<F extends WithTechnicians>(facts: F, depotId: string, change: (depot: DepotFacts) => DepotFacts): F {
  return { ...facts, technicians: facts.technicians.map(technician => ({ ...technician,
    assignments: technician.assignments.map(assignment => assignment.depot.id === depotId ? { ...assignment, depot: change(assignment.depot) } : assignment) })) };
}

/** The facts with the depot's route endpoints from `effectiveDate` on, replacing a policy already starting that day. */
export function withPolicy<F extends WithTechnicians>(facts: F, depotId: string, policy: { effectiveDate: string; departure: Anchor; returnTo: Anchor }): F {
  return withDepot(facts, depotId, depot => ({ ...depot,
    policies: [...depot.policies.filter(item => item.effectiveDate !== policy.effectiveDate), policy].sort((a, b) => a.effectiveDate.localeCompare(b.effectiveDate)) }));
}

/** The facts with the depot at a new location. */
export function withDepotLocation<F extends WithTechnicians>(facts: F, depotId: string, point: { lat: number; lng: number }): F {
  return withDepot(facts, depotId, depot => ({ ...depot, lat: point.lat, lng: point.lng }));
}

/** The facts with the technician working from `depot` from `effectiveDate` on. */
export function withAssignment<F extends WithTechnicians>(facts: F, technicianId: string, effectiveDate: string, depot: DepotFacts): F {
  return { ...facts, technicians: facts.technicians.map(technician => technician.id !== technicianId ? technician : { ...technician,
    assignments: [...technician.assignments.filter(item => item.effectiveDate !== effectiveDate), { effectiveDate, depot }]
      .sort((a, b) => a.effectiveDate.localeCompare(b.effectiveDate)) }) };
}

/**
 * The day a depot's new route endpoints start, as the scheduler decides it: the next policy already planned after
 * today is replaced, otherwise today, or tomorrow once today's routes are frozen.
 */
export function policyEffectiveDate(today: string, todayFrozen: boolean, nextPlanned: string | null): string {
  if (nextPlanned !== null) return nextPlanned;
  return todayFrozen ? addCalendarDays(today, 1) : today;
}

export interface RetimedWrite {
  receipt: { assignments: Array<{ appointmentId: string; technicianId: string; serviceDate: string; sequence: number; plannedStart: string; plannedEnd: string }>;
    technicianDays: Array<{ technicianId: string; serviceDate: string; lastModified: string }> };
  complete: TechnicianDayKey[];
}

/**
 * The writes that re-time the affected technician-days of one date to the scheduler's evaluation of the changed
 * snapshot. Every affected day must be timed, keep exactly its appointments, and hold; otherwise the change is refused.
 */
/** The parts of the changed snapshot a re-timing is checked against. */
export interface TimedDay {
  technicianDays: ReadonlyArray<{ technicianId: string; serviceDate: string; lastModified: string }>;
  appointments: ReadonlyArray<{ id: string; technicianId: string; serviceDate: string }>;
}

export function retimedWrite(serviceDate: string, technicianIds: readonly string[], changed: TimedDay, evaluation: RouteEvaluation): RetimedWrite {
  if (!evaluation.feasible) throw new MasterDataRefused(409, `The change would make the booked routes on ${serviceDate} infeasible`);
  const skipped = evaluation.skippedTechnicianDays.find(day => technicianIds.includes(day.technicianId));
  if (skipped !== undefined) throw new MasterDataRefused(409, `The booked route of ${skipped.technicianId} on ${serviceDate} could not be located: ${skipped.message}`);
  const receipt: RetimedWrite["receipt"] = { assignments: [], technicianDays: [] };
  for (const technicianId of [...technicianIds].sort()) {
    const day = changed.technicianDays.find(item => item.technicianId === technicianId && item.serviceDate === serviceDate);
    if (day === undefined) throw new MasterDataRefused(409, `Technician ${technicianId} would no longer work on ${serviceDate}, where they have booked appointments`);
    const route = evaluation.routes.find(item => item.technicianId === technicianId && item.serviceDate === serviceDate);
    if (route === undefined) throw new Error(`The scheduler did not time the route of ${technicianId} on ${serviceDate}`);
    const booked = changed.appointments.filter(item => item.technicianId === technicianId && item.serviceDate === serviceDate).map(item => item.id).sort();
    const timed = route.stops.map(stop => stop.appointmentId).sort();
    if (booked.length !== timed.length || booked.some((id, index) => id !== timed[index]))
      throw new Error(`The scheduler timed different appointments than ${technicianId} has on ${serviceDate}`);
    receipt.technicianDays.push({ technicianId, serviceDate, lastModified: day.lastModified });
    for (const stop of route.stops)
      receipt.assignments.push({ appointmentId: stop.appointmentId, technicianId, serviceDate, sequence: stop.sequence, plannedStart: stop.plannedStart, plannedEnd: stop.plannedEnd });
  }
  return { receipt, complete: [...technicianIds].sort().map(technicianId => ({ technicianId, serviceDate })) };
}
