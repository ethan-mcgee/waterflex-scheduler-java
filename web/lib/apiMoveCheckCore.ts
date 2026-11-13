import { shiftOn, type AppointmentFacts, type SnapshotFacts, type TechnicianFacts } from "./clientSnapshot";
import type { MoveCheckResult } from "./contracts";
import type { RouteEvaluation } from "./schedulerApi";

/**
 * The pure rules of checking a dispatcher's move: one appointment dragged to another technician on the same day. The
 * move is applied to snapshot facts, only the two technicians' days are sent to the scheduler, and its timing of those
 * days becomes the answer. Nothing here is saved.
 */

/** A move that cannot be checked, with the HTTP status the portal answers. */
export class MoveRefused extends Error {
  constructor(readonly status: number, message: string) { super(message); this.name = "MoveRefused"; }
}

export interface Move { appointmentId: string; serviceDate: string; targetTechnicianId: string }

/** The facts with the move applied, and the two technicians whose days it changes. */
export interface MovedFacts { facts: SnapshotFacts; sourceTechnicianId: string; targetTechnicianId: string }

/** The latest row effective on or before a date, as the snapshot applies it. */
function effective<T extends { effectiveDate: string }>(rows: readonly T[], date: string): T | undefined {
  return rows.filter(row => row.effectiveDate <= date).sort((a, b) => a.effectiveDate.localeCompare(b.effectiveDate)).at(-1);
}

/** Orders appointments by their promised arrival window: start, then end, then id, so ties never depend on load order. */
function byWindow(a: AppointmentFacts, b: AppointmentFacts): number {
  return a.windowStart.getTime() - b.windowStart.getTime() || a.windowEnd.getTime() - b.windowEnd.getTime() || a.id.localeCompare(b.id);
}

/** Numbers a day's appointments from 0 in the given order. */
function renumbered(appointments: readonly AppointmentFacts[]): Map<string, number> {
  return new Map(appointments.map((appointment, index) => [appointment.id, index]));
}

/**
 * The facts as they would be after the move, or the reason the target cannot take it without asking the scheduler.
 * The source day keeps its order without the moved appointment. On the target day the moved appointment goes before
 * the first appointment whose promised window starts later, so the target's own order is kept. Both days are numbered
 * again from 0. `plannedStart` is left as stored: the scheduler times a day from its order, not from planned starts.
 */
export function withMove(facts: SnapshotFacts, move: Move): MovedFacts | { refusal: MoveCheckResult } {
  const moving = facts.appointments.find(item => item.id === move.appointmentId && item.serviceDate === move.serviceDate);
  if (moving === undefined) throw new MoveRefused(404, `Appointment ${move.appointmentId} is not booked on ${move.serviceDate}`);
  if (moving.technicianId === move.targetTechnicianId) throw new MoveRefused(409, "The appointment is already on that technician's route");
  const target: TechnicianFacts | undefined = facts.technicians.find(item => item.id === move.targetTechnicianId);
  if (target === undefined) throw new MoveRefused(404, `Technician ${move.targetTechnicianId} not found`);
  const assignment = effective(target.assignments, move.serviceDate);
  if (assignment === undefined || assignment.depot.metroId !== facts.metro.id)
    throw new MoveRefused(409, `Technician ${target.id} does not work in this metro on ${move.serviceDate}`);
  if (!target.active || shiftOn(target, move.serviceDate) === null)
    return { refusal: { outcome: "NOT_FEASIBLE", message: `Technician ${target.id} is not working on ${move.serviceDate}.`, overtimeMinutes: null, saved: false } };

  const sameDay = (technicianId: string) => facts.appointments
    .filter(item => item.technicianId === technicianId && item.serviceDate === move.serviceDate && item.id !== moving.id)
    .sort((a, b) => a.sequence - b.sequence);
  const source = sameDay(moving.technicianId);
  const kept = sameDay(target.id);
  const at = kept.findIndex(item => byWindow(item, moving) > 0);
  const targetOrder = at < 0 ? [...kept, moving] : [...kept.slice(0, at), moving, ...kept.slice(at)];
  const sequence = new Map([...renumbered(source), ...renumbered(targetOrder)]);
  const appointments = facts.appointments.map(item => {
    const next = sequence.get(item.id);
    if (next === undefined || item.serviceDate !== move.serviceDate) return item;
    return { ...item, sequence: next, technicianId: item.id === moving.id ? target.id : item.technicianId };
  });
  return { facts: { ...facts, appointments }, sourceTechnicianId: moving.technicianId, targetTechnicianId: target.id };
}

/**
 * The facts of only these technicians. The scheduler answers for every day it is sent, so another technician's route
 * that already runs late must not make this move look infeasible, and fewer stops mean fewer drive times to look up.
 */
export function onlyTechnicians(facts: SnapshotFacts, technicianIds: readonly string[]): SnapshotFacts {
  const keep = new Set(technicianIds);
  return { ...facts, technicians: facts.technicians.filter(item => keep.has(item.id)),
    appointments: facts.appointments.filter(item => keep.has(item.technicianId)) };
}

/** The dispatcher's answer from the scheduler's timing of the two days. */
export function moveResult(evaluation: RouteEvaluation, moved: MovedFacts, serviceDate: string): MoveCheckResult {
  const involved = new Set([moved.sourceTechnicianId, moved.targetTechnicianId]);
  const skipped = evaluation.skippedTechnicianDays.filter(day => involved.has(day.technicianId) && day.serviceDate === serviceDate);
  if (skipped.length > 0)
    return { outcome: "NOT_CHECKED", message: `The scheduler could not place every stop on the map: ${skipped.map(day => day.message).join(" ")}`,
      overtimeMinutes: null, saved: false };
  if (!evaluation.feasible)
    return { outcome: "NOT_FEASIBLE", message: "With this move a customer's arrival window, a shift or a paid limit would be missed.",
      overtimeMinutes: null, saved: false };
  return { outcome: "FEASIBLE", message: "Every arrival window, shift and paid limit still holds.", overtimeMinutes: evaluation.overtimeMinutes, saved: false };
}
