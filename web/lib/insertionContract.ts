import { z } from "zod";
import type { PublicSnapshot } from "./publicApi";

/**
 * The route insertion contract (POST /api/v1/routes/insertions, spec 1.12.0), browser-safe so the schedule page can
 * check the answer its own server relays and work out every hover and drop from it without another call.
 */

const id = z.string().min(1).max(128);
const instant = z.iso.datetime({ offset: true });
const serviceDate = z.iso.date();
const window = z.object({ start: instant, end: instant }).strict();
const skippedTechnicianDay = z.object({ technicianId: id, serviceDate, reason: z.enum(["LOCATION_UNRESOLVED"]), message: z.string().min(1) }).strict();
// arrivalBufferStart comes only when the snapshot carried a late-arrival buffer.
const plannedStop = z.object({ appointmentId: id, sequence: z.int().min(0), plannedStart: instant, plannedEnd: instant,
  arrivalBufferStart: instant.optional() }).strict();

const routeTotals = z.object({ driveMinutes: z.int().min(0), meters: z.int().min(0), paidMinutes: z.int().min(0),
  costCents: z.int().min(0), bufferSeconds: z.int().min(0) }).strict();
const insertionLeg = z.object({ seconds: z.int().min(0), meters: z.int().min(0), bufferedMinutes: z.int().min(0) }).strict();
const insertionStart = z.object({ plannedStart: instant, window, keepsWindow: z.boolean().optional(), timed: routeTotals,
  stops: z.array(plannedStop).optional() }).strict();
const insertionGap = z.object({ afterAppointmentId: id.optional(), beforeAppointmentId: id.optional(), routable: z.boolean(),
  toJob: insertionLeg.optional(), fromJob: insertionLeg.optional(), replaced: insertionLeg.optional(),
  addedSeconds: z.int().optional(), addedMeters: z.int().optional(), addedBufferedMinutes: z.int().optional(),
  starts: z.array(insertionStart),
}).strict()
  .refine(gap => [gap.toJob, gap.fromJob, gap.addedSeconds, gap.addedMeters, gap.addedBufferedMinutes]
    .every(value => (value !== undefined) === gap.routable), "A gap has legs and added values exactly when it is routable")
  // `replaced` is also absent on a day with no appointments, where the job breaks no leg.
  .refine(gap => gap.routable || gap.replaced === undefined, "A gap with no road route has no replaced leg")
  .refine(gap => gap.routable || gap.starts.length === 0, "A gap with no road route has no starts");
const insertionTechnicianDay = z.object({ technicianId: id, serviceDate, holdsAsGiven: z.boolean(), qualified: z.boolean(),
  asGiven: routeTotals.optional(), stops: z.array(plannedStop).optional(), gaps: z.array(insertionGap).min(1),
}).strict()
  .refine(day => (day.asGiven !== undefined) === day.holdsAsGiven, "A day has totals as given exactly when it holds as given")
  .refine(day => (day.holdsAsGiven && day.qualified) || day.gaps.every(gap => gap.starts.length === 0),
    "A day that does not hold, or a technician not qualified for the job, has no starts");
/**
 * Every place one job could go in each technician-day sent (spec 1.12.0): per gap, the drive to and from the job, the
 * leg it replaces, and the planned starts at which the day still holds. A gap with no road route carries no numbers.
 */
export const routeInsertions = z.object({ routingIdentity: z.string().min(1), technicianDays: z.array(insertionTechnicianDay),
  skippedTechnicianDays: z.array(skippedTechnicianDay) }).strict();

export type RouteInsertions = z.infer<typeof routeInsertions>;
export type InsertionTechnicianDay = RouteInsertions["technicianDays"][number];
export type InsertionGap = InsertionTechnicianDay["gaps"][number];
export type InsertionStart = InsertionGap["starts"][number];
export type RouteTotals = z.infer<typeof routeTotals>;
export type PlannedStop = z.infer<typeof plannedStop>;

/** The job a route insertion places: a new or unscheduled job, or a scheduled appointment with the window it was promised. */
export interface InsertionJob {
  id: string; serviceId: string; durationMinutes: number; location: { lat: number; lng: number };
  window?: { start: string; end: string };
}
export interface RouteInsertionRequest {
  snapshot: PublicSnapshot; job: InsertionJob; windowMinutes: number; startStepMinutes: 15 | 30 | 60; routes: boolean;
}
