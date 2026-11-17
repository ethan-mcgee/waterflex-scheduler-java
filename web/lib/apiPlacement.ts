import { prisma } from "./prisma";
import { assembleSnapshot, loadSnapshotFacts } from "./clientSnapshot";
import { onlyTechnicians } from "./apiMoveCheckCore";
import { timed, type Phases } from "./apiMoveCheck";
import { writeReceipt } from "./apiReceipt";
import { insertIntoRoutes } from "./schedulerApi";
import type { RouteInsertions } from "./insertionContract";
import type { PlacementPreview, PlacementSaved } from "./contracts";
import type { Spot } from "./placementRules";
import { insertionCalls, joined, placementPlan, placementWrite, PlacementRefused, type PlacementPlan, type Subject } from "./apiPlacementCore";

/**
 * Placing a job or moving an appointment on the schedule: one preview call for the week, answered by the scheduler's
 * route insertions, and a save that asks again with fresh facts and writes only if the chosen start still fits.
 */

/** The scheduler's limit on technician-days in one route insertion call. */
const MAX_TECHNICIAN_DAYS = 200;
/** Planned starts are offered on the half hour, as the dealership asked. */
const START_STEP_MINUTES = 30;

export interface SubjectInput { jobId?: string; appointmentId?: string }

/** The client's job or live appointment named by the input, and the date a moved appointment is on now. */
async function subjectFor(clientId: string, input: SubjectInput): Promise<{ subject: Subject; serviceDate: string | null }> {
  if (input.jobId !== undefined) {
    const job = await prisma.job.findFirst({ where: { id: input.jobId, customer: { clientId } }, select: { id: true, serviceId: true,
      durationMin: true, status: true, appointment: { select: { id: true } }, address: { select: { lat: true, lng: true } } } });
    if (job === null) throw new PlacementRefused(404, "Job not found");
    return { serviceDate: null, subject: { kind: "job", job: { id: job.id, serviceId: job.serviceId, durationMin: job.durationMin,
      status: job.status, hasAppointment: job.appointment !== null, lat: job.address.lat, lng: job.address.lng } } };
  }
  if (input.appointmentId === undefined) throw new PlacementRefused(400, "Name a job or an appointment");
  const appointment = await prisma.appointment.findFirst({ where: { id: input.appointmentId, cancelledAt: null, job: { customer: { clientId } } },
    select: { id: true, serviceDate: true } });
  if (appointment === null) throw new PlacementRefused(404, "Appointment not found");
  return { serviceDate: appointment.serviceDate.toISOString().slice(0, 10), subject: { kind: "appointment", appointmentId: appointment.id } };
}

/**
 * The scheduler's answer for a plan on the `sent` dates: the timed ones asked with timed stops, the others without.
 * The two groups run side by side, each split by date under the technician-day limit and sent in turn, and the
 * answers are joined. Every call is assembled from the same loaded facts, so they describe one moment; `joined`
 * refuses answers from two road maps.
 */
async function insertions(clientId: string, plan: PlacementPlan, sent: readonly string[], timedDates: readonly string[]): Promise<RouteInsertions> {
  const whole = assembleSnapshot(plan.facts);
  const perDate = new Map<string, number>();
  for (const day of whole.technicianDays) perDate.set(day.serviceDate, (perDate.get(day.serviceDate) ?? 0) + 1);
  const calls = insertionCalls(sent, timedDates, date => perDate.get(date) ?? 0, MAX_TECHNICIAN_DAYS);
  if (calls.length === 0) throw new PlacementRefused(400, "None of the dates asked for are in the week shown");
  const windowMinutes = plan.facts.settings.windowHours * 60;
  const send = async (routes: boolean) => {
    const answers: RouteInsertions[] = [];
    for (const call of calls.filter(item => item.routes === routes)) {
      const snapshot = call.dates.length === plan.facts.dates.length ? whole : assembleSnapshot({ ...plan.facts, dates: call.dates });
      answers.push(await insertIntoRoutes(clientId, { snapshot, job: plan.job, windowMinutes, startStepMinutes: START_STEP_MINUTES, routes }));
    }
    return answers;
  };
  const [timed, untimed] = await Promise.all([send(true), send(false)]);
  return joined([...timed, ...untimed]);
}

/**
 * Every place on the week's routes where the job or appointment could go. Nothing is saved. The days on screen
 * (`timedDates`) carry timed stops, so the page can show every later visit moving as the subject is placed and moved
 * without asking again; the rest of the week, sent with `withRest`, carries the places and their drive but not the
 * stops, which keeps the answer small. A day paged to later is asked for alone, without the rest.
 */
export async function previewPlacement(clientId: string, metroId: string, input: SubjectInput, week: readonly string[],
  timedDates: readonly string[], withRest: boolean, phases: Phases): Promise<PlacementPreview> {
  const { subject, serviceDate } = await timed(phases, "subject", () => subjectFor(clientId, input));
  const sent = withRest ? week : week.filter(day => timedDates.includes(day));
  // A moved appointment's day in this week is loaded even when it is not sent, because the plan takes the appointment
  // out of that day. One booked in another week is refused, as before.
  const own = serviceDate !== null && week.includes(serviceDate) ? [serviceDate] : [];
  const facts = await timed(phases, "facts", () => loadSnapshotFacts(clientId, metroId, [...new Set([...sent, ...own])]));
  const plan = placementPlan(facts, subject);
  const answer = await timed(phases, "scheduler", () => insertions(clientId, plan, sent, timedDates));
  return { answer, original: plan.original, job: { id: plan.job.id, durationMinutes: plan.job.durationMinutes } };
}

/**
 * Saves a start at a spot. The days involved are loaded fresh and asked again with timed stops; the write then goes
 * through the receipt path, so it lands only while those technician-days are as they were read. A move that changes
 * the promise updates the appointment's day and window and records the acknowledged change in the same transaction.
 * A new job's open follow-up, if any, is resolved with it.
 */
export async function savePlacement(clientId: string, metroId: string, input: SubjectInput, spot: Spot, plannedStart: string,
  acknowledged: boolean, phases: Phases): Promise<PlacementSaved> {
  const { subject, serviceDate } = await timed(phases, "subject", () => subjectFor(clientId, input));
  const dates = [...new Set([spot.serviceDate, ...(serviceDate === null ? [] : [serviceDate])])];
  const facts = await timed(phases, "facts", () => loadSnapshotFacts(clientId, metroId, dates));
  const full = placementPlan(facts, subject);
  const technicians = [spot.technicianId, ...(full.original === null ? [] : [full.original.technicianId])];
  const plan = { ...full, facts: onlyTechnicians(full.facts, technicians) };
  const answer = await timed(phases, "scheduler", () => insertions(clientId, plan, plan.facts.dates, plan.facts.dates));
  const write = placementWrite(plan, answer, spot, plannedStart, acknowledged);
  const change = write.promiseChange;
  await timed(phases, "write", () => writeReceipt(clientId, {
    receipt: { assignments: write.assignments, technicianDays: write.technicianDays },
    complete: write.technicianDays.map(day => ({ technicianId: day.technicianId, serviceDate: day.serviceDate })),
    ...(write.create === undefined ? {} : { create: write.create }),
  }, {
    // The receipt places an appointment only on its current day, so a new day and window are set first.
    ...(change === undefined ? {} : { before: async tx => {
      const moved = await tx.appointment.updateMany({ where: { id: change.appointmentId, cancelledAt: null,
        serviceDate: new Date(`${change.from.serviceDate}T00:00:00Z`), windowStart: change.from.windowStart, windowEnd: change.from.windowEnd },
        data: { serviceDate: new Date(`${change.to.serviceDate}T00:00:00Z`), windowStart: change.to.windowStart, windowEnd: change.to.windowEnd } });
      if (moved.count !== 1) throw new PlacementRefused(409, "The appointment changed since the preview; preview it again.");
      await tx.appointmentWindowChange.create({ data: { appointmentId: change.appointmentId,
        fromServiceDate: new Date(`${change.from.serviceDate}T00:00:00Z`), fromWindowStart: change.from.windowStart, fromWindowEnd: change.from.windowEnd,
        toServiceDate: new Date(`${change.to.serviceDate}T00:00:00Z`), toWindowStart: change.to.windowStart, toWindowEnd: change.to.windowEnd } });
    } }),
    ...(write.create === undefined ? {} : { record: async tx => {
      await tx.job.updateMany({ where: { id: plan.job.id, manualFollowUpStatus: { in: ["PENDING", "CONTACTED"] } }, data: { manualFollowUpStatus: "RESOLVED" } });
    } }),
  }));
  return { saved: true, appointmentId: plan.job.id, kind: write.verdict.kind };
}
