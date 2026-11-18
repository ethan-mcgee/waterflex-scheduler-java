import { z } from "zod";
import type { RouteInsertions } from "./insertionContract";
import { findGap, type Original, type Spot } from "./placementRules";

/**
 * The drive times the schedule draws around each visit (GET /api/schedule/drive), browser-safe so the page checks the
 * answer before it draws from it. Minutes are the scheduler's buffered minutes: the road time plus the client's travel
 * buffer, which is the time the schedule allows between one visit and the next.
 */

const id = z.string().min(1).max(128);
const anchor = z.enum(["HOME", "DEPOT"]);
const leg = z.object({ seconds: z.int().min(0), meters: z.int().min(0), minutes: z.int().min(0) }).strict();
// A working stretch (a day, or the part of it between approved absences): the start point, its stops, the end point.
const segment = z.object({ appointmentIds: z.array(id).min(1), legs: z.array(leg) }).strict()
  .refine(value => value.legs.length === value.appointmentIds.length + 1, "A working stretch has one leg more than it has stops");
// Every technician working the day, with the start and end points a placed visit would drive from and to; a day with no
// visits, or one the scheduler could not draw, has no stretches.
const technicianDrive = z.object({ technicianId: id, departure: anchor, returnTo: anchor, segments: z.array(segment) }).strict();

export const scheduleDrive = z.object({
  serviceDate: z.iso.date(),
  technicians: z.array(technicianDrive),
  /** Technician-days the scheduler could not draw, with its reason; their visits are shown without drive times. */
  skipped: z.array(z.object({ technicianId: id, message: z.string().min(1) }).strict()),
}).strict();

export type ScheduleDrive = z.infer<typeof scheduleDrive>;
export type DriveAnchor = z.infer<typeof anchor>;

/** The driving drawn as part of one visit's block. */
export interface VisitDrive {
  /** The drive from the start point, on the first visit of a working stretch only; null on every other visit. */
  before: { minutes: number; from: DriveAnchor } | null;
  /** The drive after the visit: to the next stop, or to the end point after the stretch's last visit. */
  after: { minutes: number; to: DriveAnchor | "NEXT" };
}

/**
 * The drive before and after every visit in the answer. A technician leaves as soon as a visit ends, so each leg
 * belongs to the visit it leaves from; only the first leg of a stretch, from the start point, belongs to the visit it
 * leads to. Waiting for a window to open is the time left between one visit's block and the next.
 */
export function visitDrives(drive: ScheduleDrive): Map<string, VisitDrive> {
  const drives = new Map<string, VisitDrive>();
  for (const technician of drive.technicians) {
    for (const stretch of technician.segments) {
      stretch.appointmentIds.forEach((appointmentId, index) => {
        const first = stretch.legs[0], after = stretch.legs[index + 1];
        if (first === undefined || after === undefined) throw new Error(`Stop ${appointmentId} has no drive after it`);
        const last = index === stretch.appointmentIds.length - 1;
        drives.set(appointmentId, {
          before: index === 0 ? { minutes: first.minutes, from: technician.departure } : null,
          after: { minutes: after.minutes, to: last ? technician.returnTo : "NEXT" },
        });
      });
    }
  }
  return drives;
}

/** Each drawn technician's buffered drive minutes for the day, every leg counted once; a day with no visits has none. */
export function driveMinutes(drive: ScheduleDrive): Map<string, number> {
  return new Map(drive.technicians.filter(technician => technician.segments.length > 0).map(technician => [technician.technicianId,
    technician.segments.reduce((total, stretch) => total + stretch.legs.reduce((sum, item) => sum + item.minutes, 0), 0)]));
}

/** Where each technician working the day starts and ends. */
export type Endpoints = ReadonlyMap<string, { departure: DriveAnchor; returnTo: DriveAnchor }>;

export function driveEndpoints(drive: ScheduleDrive): Endpoints {
  return new Map(drive.technicians.map(technician => [technician.technicianId, { departure: technician.departure, returnTo: technician.returnTo }]));
}

/** The drive drawn while a visit is being placed: the day's visits with their legs as they would be, and the placed visit's own. */
export interface DrivePreview { visits: Map<string, VisitDrive>; placed: VisitDrive | null }

/**
 * The drive around every visit as it would be with the subject at `spot`, from the cached insertion answer alone, so a
 * dispatcher can move it again and again without another request. A moved appointment leaves its place: the visit
 * before it now drives straight to the one after it, the answer's `replaced` leg, since the answer was computed without
 * it. At `spot`, the visit before drives to the subject (`toJob`) and the subject drives on (`fromJob`); a first or last
 * place drives from or to the technician's start or end point. A leg the answer cannot give is not drawn at all: the
 * neighbor is shown without drive rather than with a leg that no longer exists. `spot` null previews only the removal.
 */
export function previewDrives(visits: ReadonlyMap<string, VisitDrive>, endpoints: Endpoints, answer: RouteInsertions,
  original: Original | null, spot: Spot | null): DrivePreview {
  const next = new Map(visits);
  const update = (id: string | undefined, change: (drive: VisitDrive) => VisitDrive) => {
    if (id === undefined) return;
    const drive = next.get(id);
    if (drive !== undefined) next.set(id, change(drive));
  };
  if (original !== null) {
    next.delete(original.appointmentId);
    const { afterAppointmentId: after, beforeAppointmentId: before } = original;
    const closed = findGap(answer, { technicianId: original.technicianId, serviceDate: original.serviceDate,
      ...(after === undefined ? {} : { afterAppointmentId: after }), ...(before === undefined ? {} : { beforeAppointmentId: before }) });
    const leg = closed?.gap.replaced?.bufferedMinutes, ends = endpoints.get(original.technicianId);
    if (leg === undefined || ends === undefined) {
      if (after !== undefined) next.delete(after);
      else if (before !== undefined) next.delete(before);
    } else if (after !== undefined) update(after, drive => ({ ...drive, after: { minutes: leg, to: before === undefined ? ends.returnTo : "NEXT" } }));
    else update(before, drive => ({ ...drive, before: { minutes: leg, from: ends.departure } }));
  }
  if (spot === null) return { visits: next, placed: null };
  const found = findGap(answer, spot), ends = endpoints.get(spot.technicianId);
  const toJob = found?.gap.toJob?.bufferedMinutes, fromJob = found?.gap.fromJob?.bufferedMinutes;
  const { afterAppointmentId: after, beforeAppointmentId: before } = spot;
  if (toJob === undefined || fromJob === undefined || ends === undefined) {
    // No road route here: the visit before can no longer be said to drive to the visit after.
    if (after !== undefined) next.delete(after);
    return { visits: next, placed: null };
  }
  update(after, drive => ({ ...drive, after: { minutes: toJob, to: "NEXT" } }));
  if (after === undefined) update(before, drive => ({ ...drive, before: null }));
  return { visits: next, placed: { before: after === undefined ? { minutes: toJob, from: ends.departure } : null,
    after: { minutes: fromJob, to: before === undefined ? ends.returnTo : "NEXT" } } };
}

/** What a drive band says: "17 min from depot", "12 min to next stop", "15 min to home". */
export function driveLabel(minutes: number, place: { from: DriveAnchor } | { to: DriveAnchor | "NEXT" }): string {
  const name = (value: DriveAnchor) => value === "HOME" ? "home" : "depot";
  if ("from" in place) return `${minutes} min from ${name(place.from)}`;
  return place.to === "NEXT" ? `${minutes} min to next stop` : `${minutes} min to ${name(place.to)}`;
}
