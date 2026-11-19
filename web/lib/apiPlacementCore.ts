import { z } from "zod";
import type { AppointmentFacts, SnapshotFacts } from "./clientSnapshot";
import type { InsertionJob, PlannedStop, RouteInsertions } from "./insertionContract";
import { findGap, findStart, verdict, type Original, type Spot, type Verdict } from "./placementRules";
import type { TechnicianDayVersion } from "./schedulerApi";

/**
 * The pure server rules of placing a job or moving an appointment on the schedule: what is sent to the scheduler, and
 * what a chosen start writes. The scheduler answers for the snapshot without the job; the rules shared with the page
 * (placementRules) decide what the answer means.
 */

/** A placement that cannot be previewed or saved, with the HTTP status the portal answers. */
export class PlacementRefused extends Error {
  constructor(readonly status: number, message: string) { super(message); this.name = "PlacementRefused"; }
}

/** The job's row as the portal reads it. Coordinates are null until the address is located. */
export interface JobRow {
  id: string; serviceId: string; durationMin: number; status: "PENDING" | "SCHEDULED" | "COMPLETED" | "CANCELLED";
  hasAppointment: boolean; lat: number | null; lng: number | null;
}

export type Subject = { kind: "job"; job: JobRow } | { kind: "appointment"; appointmentId: string };

/** What is sent for a subject: the facts without it, the job, and where it is now when it is already scheduled. */
export interface PlacementPlan { facts: SnapshotFacts; job: InsertionJob; original: Original | null }

const NEEDS_PIN = "The job's address has no confirmed location yet. Confirm its pin before placing it.";

/** Numbers a day's appointments from 0 in their current order. */
function closedUp(appointments: readonly AppointmentFacts[], removed: AppointmentFacts): AppointmentFacts[] {
  const day = appointments.filter(item => item.technicianId === removed.technicianId && item.serviceDate === removed.serviceDate && item.id !== removed.id)
    .sort((a, b) => a.sequence - b.sequence);
  const sequence = new Map(day.map((item, index) => [item.id, index]));
  return appointments.filter(item => item.id !== removed.id).map(item => {
    const next = sequence.get(item.id);
    return next === undefined ? item : { ...item, sequence: next };
  });
}

/**
 * The plan for a subject. An unscheduled job must be PENDING with no appointment and a located address. A scheduled
 * appointment must be in the loaded facts; it leaves its day, which closes up, and is sent as the job with the window
 * its customer was promised, so the scheduler can say which starts keep that promise.
 */
export function placementPlan(facts: SnapshotFacts, subject: Subject): PlacementPlan {
  if (subject.kind === "job") {
    const { job } = subject;
    if (job.status !== "PENDING" || job.hasAppointment) throw new PlacementRefused(409, `Job ${job.id} is not waiting to be scheduled`);
    if (job.lat === null || job.lng === null) throw new PlacementRefused(409, NEEDS_PIN);
    return { facts, original: null,
      job: { id: job.id, serviceId: job.serviceId, durationMinutes: job.durationMin, location: { lat: job.lat, lng: job.lng } } };
  }
  const moving = facts.appointments.find(item => item.id === subject.appointmentId);
  if (moving === undefined) throw new PlacementRefused(404, `Appointment ${subject.appointmentId} is not booked on the dates shown`);
  const { lat, lng } = moving.address;
  if (lat === null || lng === null) throw new PlacementRefused(409, NEEDS_PIN);
  const day = facts.appointments.filter(item => item.technicianId === moving.technicianId && item.serviceDate === moving.serviceDate)
    .sort((a, b) => a.sequence - b.sequence);
  const at = day.findIndex(item => item.id === moving.id);
  const after = day[at - 1], before = day[at + 1];
  const window = { start: moving.windowStart.toISOString(), end: moving.windowEnd.toISOString() };
  return {
    facts: { ...facts, appointments: closedUp(facts.appointments, moving) },
    job: { id: moving.id, serviceId: moving.serviceId, durationMinutes: moving.durationMin, location: { lat, lng }, window },
    original: { appointmentId: moving.id, technicianId: moving.technicianId, serviceDate: moving.serviceDate, window,
      plannedStart: moving.plannedStart.toISOString(),
      ...(after === undefined ? {} : { afterAppointmentId: after.id }), ...(before === undefined ? {} : { beforeAppointmentId: before.id }) },
  };
}

/** The days of a week a placement may use: today and later, `today` being the metro's own date. */
export function placeableDates(week: readonly string[], today: string): string[] {
  return week.filter(day => day >= today);
}

/**
 * The answer without what is already past: no day before today, and on today no start at or before `now`. A gap
 * left with no start stays listed, so the page can still say that nothing fits there.
 */
export function withoutPast(answer: RouteInsertions, today: string, now: Date): RouteInsertions {
  const at = now.getTime();
  return { ...answer, technicianDays: answer.technicianDays.filter(day => day.serviceDate >= today).map(day => day.serviceDate !== today ? day
    : { ...day, gaps: day.gaps.map(gap => ({ ...gap, starts: gap.starts.filter(start => Date.parse(start.plannedStart) > at) })) }) };
}

/**
 * Refuses a save on a day that is over or at a start that has passed. A save onto today needs the dispatcher's
 * acknowledgement that the technician will be told, since today's route is already out; the booking service never
 * offers today.
 */
export function checkWhen(spot: Spot, plannedStart: string, today: string, now: Date, sameDayAcknowledged: boolean): void {
  if (spot.serviceDate < today) throw new PlacementRefused(422, `${spot.serviceDate} is over. Pick today or a later day.`);
  if (spot.serviceDate !== today) return;
  if (Date.parse(plannedStart) <= now.getTime()) throw new PlacementRefused(422, "That start has already passed. Pick a later start.");
  if (!sameDayAcknowledged)
    throw new PlacementRefused(400, "This places it today, on a route that is already out. Acknowledge that you will contact the technician to save it.");
}

/** The dates to send per call so that no call has more technician-days than the scheduler accepts. */
export function dateBatches(facts: SnapshotFacts, workingDays: (date: string) => number, limit: number): string[][] {
  return batchDates(facts.dates, workingDays, limit);
}

function batchDates(dates: readonly string[], workingDays: (date: string) => number, limit: number): string[][] {
  const batches: string[][] = [];
  let current: string[] = [], count = 0;
  for (const date of [...new Set(dates)].sort()) {
    const days = workingDays(date);
    if (days > limit) throw new PlacementRefused(413, `${date} has ${days} technician-days, more than the ${limit} one call can place`);
    if (count + days > limit && current.length > 0) { batches.push(current); current = []; count = 0; }
    current.push(date); count += days;
  }
  if (current.length > 0) batches.push(current);
  return batches;
}

/** One route insertion call: the dates it sends, and whether it asks for timed stops. */
export interface InsertionCall { dates: string[]; routes: boolean }

/**
 * The calls a preview makes for the `sent` dates. The timed dates (the days on screen) ask for timed stops and the
 * others are asked without them, since the page re-times only what it shows. Each group is split by date under the
 * technician-day limit, as `dateBatches` splits a week.
 */
export function insertionCalls(sent: readonly string[], timedDates: readonly string[], workingDays: (date: string) => number,
  limit: number): InsertionCall[] {
  const timed = new Set(timedDates);
  const group = (dates: string[], routes: boolean) => batchDates(dates, workingDays, limit).map(batch => ({ dates: batch, routes }));
  return [...group(sent.filter(day => timed.has(day)), true), ...group(sent.filter(day => !timed.has(day)), false)];
}

/** Answers for date batches joined into one; every batch must come from the same road graph. */
export function joined(answers: readonly RouteInsertions[]): RouteInsertions {
  const first = answers[0];
  if (first === undefined) throw new Error("No route insertion answer to join");
  if (answers.some(answer => answer.routingIdentity !== first.routingIdentity))
    throw new PlacementRefused(503, "The road map changed while the week was being placed. Try again.");
  return { routingIdentity: first.routingIdentity, technicianDays: answers.flatMap(answer => answer.technicianDays),
    skippedTechnicianDays: answers.flatMap(answer => answer.skippedTechnicianDays) };
}

/** What a save writes: the receipt for each changed technician-day, and for a move, any change to its promise. */
export interface PlacementWrite {
  assignments: Array<{ appointmentId: string; technicianId: string; serviceDate: string; sequence: number; plannedStart: string; plannedEnd: string }>;
  technicianDays: TechnicianDayVersion[];
  /** A new job's appointment, created with the window the chosen start promises. */
  create?: { jobId: string; windowStart: Date; windowEnd: Date };
  /** A move to another day or window: the appointment's promise before and after, recorded with the acknowledgement. */
  promiseChange?: { appointmentId: string; from: { serviceDate: string; windowStart: Date; windowEnd: Date };
    to: { serviceDate: string; windowStart: Date; windowEnd: Date } };
  verdict: Exclude<Verdict, { kind: "CANNOT_HOLD" }>;
}

const assignment = (technicianId: string, serviceDate: string) => (stop: PlannedStop) => ({ appointmentId: stop.appointmentId, technicianId,
  serviceDate, sequence: stop.sequence, plannedStart: stop.plannedStart, plannedEnd: stop.plannedEnd });

/**
 * What saving a start at a spot writes, from a fresh answer computed with `routes: true`. The same start must still be
 * offered there (409 otherwise), a promise change needs the acknowledgement (400 otherwise), and a move to another
 * technician-day also rewrites the day it leaves, which must still hold without it.
 */
export function placementWrite(plan: PlacementPlan, answer: RouteInsertions, spot: Spot, plannedStart: string, acknowledged: boolean): PlacementWrite {
  const found = findGap(answer, spot);
  const start = found === undefined ? undefined : findStart(found.gap, plannedStart);
  const decided = verdict(answer, plan.original, spot, plannedStart);
  if (found === undefined || start === undefined || decided.kind === "CANNOT_HOLD")
    throw new PlacementRefused(409, "That place no longer fits. The schedule changed since the preview; preview it again.");
  if (decided.kind === "CHANGES_PROMISE" && !acknowledged)
    throw new PlacementRefused(400, "This move changes the day or arrival window the customer was promised. Acknowledge the policy violation to save it.");
  if (start.stops === undefined) throw new Error("The route insertion answer has no timed stops; it must be requested with routes");
  const lastModified = (technicianId: string, serviceDate: string): TechnicianDayVersion => {
    const value = plan.facts.lastModified.get(`${technicianId}|${serviceDate}`);
    if (value === undefined) throw new Error(`Technician ${technicianId} has no change time for ${serviceDate}`);
    return { technicianId, serviceDate, lastModified: value };
  };
  const assignments = start.stops.map(assignment(spot.technicianId, spot.serviceDate));
  const technicianDays = [lastModified(spot.technicianId, spot.serviceDate)];
  const original = plan.original;
  if (original !== null && (original.technicianId !== spot.technicianId || original.serviceDate !== spot.serviceDate)) {
    const left = answer.technicianDays.find(day => day.technicianId === original.technicianId && day.serviceDate === original.serviceDate);
    if (left?.stops === undefined)
      throw new PlacementRefused(409, `Without this appointment, the route it leaves on ${original.serviceDate} could not be timed, so the move cannot be saved.`);
    assignments.push(...left.stops.map(assignment(original.technicianId, original.serviceDate)));
    technicianDays.push(lastModified(original.technicianId, original.serviceDate));
  }
  const window = { windowStart: new Date(start.window.start), windowEnd: new Date(start.window.end) };
  return {
    assignments, technicianDays, verdict: decided,
    ...(original === null ? { create: { jobId: plan.job.id, ...window } } : {}),
    ...(original !== null && decided.kind === "CHANGES_PROMISE" ? { promiseChange: { appointmentId: original.appointmentId,
      from: { serviceDate: original.serviceDate, windowStart: new Date(original.window.start), windowEnd: new Date(original.window.end) },
      to: { serviceDate: spot.serviceDate, ...window } } } : {}),
  };
}

const undoInstant = z.iso.datetime({ offset: true });
const undoDate = z.iso.date();
const undoId = z.string().min(1);
const undoPromise = z.object({ serviceDate: undoDate, windowStart: undoInstant, windowEnd: undoInstant }).strict();
/**
 * What a save replaced, stored so it can be undone, and checked when written and when read back. `assignments` are
 * the earlier planned visits of every appointment that was on the technician-days the save wrote; `technicianDays`
 * are those days' lastModified right after the save, which an undo must still find. `promise` is present when the
 * save changed a promise (`from` is the earlier one), `created` when it placed a waiting job.
 */
export const placementUndoPayload = z.object({
  kind: z.enum(["NEW", "KEEPS_PROMISE", "CHANGES_PROMISE"]),
  assignments: z.array(z.object({ appointmentId: undoId, technicianId: undoId, serviceDate: undoDate, sequence: z.int().min(0),
    plannedStart: undoInstant, plannedEnd: undoInstant }).strict()),
  technicianDays: z.array(z.object({ technicianId: undoId, serviceDate: undoDate, lastModified: z.string().min(1) }).strict()).min(1),
  promise: z.object({ appointmentId: undoId, from: undoPromise, to: undoPromise }).strict().optional(),
  created: z.object({ jobId: undoId, followUp: z.string().nullable() }).strict().optional(),
}).strict()
  .refine(value => (value.kind === "NEW") === (value.created !== undefined), "A placed job, and only one, records the job it placed")
  .refine(value => (value.kind === "CHANGES_PROMISE") === (value.promise !== undefined), "A changed promise, and only one, records the earlier promise");
export type PlacementUndoPayload = z.infer<typeof placementUndoPayload>;

/** A visit as it was booked before a save, read under the save's locks. */
export interface PriorVisit { id: string; technicianId: string; serviceDate: Date; sequence: number; plannedStart: Date; plannedEnd: Date }

/**
 * The undo record of a save: the visits as they were before it, and the saved days as they are after it. `followUp`
 * is the placed job's follow-up status before the save; it is asked for only when the save placed a job.
 */
export function undoPayload(write: PlacementWrite, prior: readonly PriorVisit[], after: readonly TechnicianDayVersion[],
  followUp: () => string | null): PlacementUndoPayload {
  const change = write.promiseChange;
  const promise = (value: { serviceDate: string; windowStart: Date; windowEnd: Date }) =>
    ({ serviceDate: value.serviceDate, windowStart: value.windowStart.toISOString(), windowEnd: value.windowEnd.toISOString() });
  return placementUndoPayload.parse({
    kind: write.verdict.kind,
    assignments: prior.map(visit => ({ appointmentId: visit.id, technicianId: visit.technicianId, serviceDate: visit.serviceDate.toISOString().slice(0, 10),
      sequence: visit.sequence, plannedStart: visit.plannedStart.toISOString(), plannedEnd: visit.plannedEnd.toISOString() })),
    technicianDays: after,
    ...(change === undefined ? {} : { promise: { appointmentId: change.appointmentId, from: promise(change.from), to: promise(change.to) } }),
    ...(write.create === undefined ? {} : { created: { jobId: write.create.jobId, followUp: followUp() } }),
  });
}
