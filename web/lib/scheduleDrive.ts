import { z } from "zod";

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
const technicianDrive = z.object({ technicianId: id, departure: anchor, returnTo: anchor, segments: z.array(segment).min(1) }).strict();

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

/** Each drawn technician's buffered drive minutes for the day, every leg counted once. */
export function driveMinutes(drive: ScheduleDrive): Map<string, number> {
  return new Map(drive.technicians.map(technician => [technician.technicianId,
    technician.segments.reduce((total, stretch) => total + stretch.legs.reduce((sum, item) => sum + item.minutes, 0), 0)]));
}

/** What a drive band says: "17 min from depot", "12 min to next stop", "15 min to home". */
export function driveLabel(minutes: number, place: { from: DriveAnchor } | { to: DriveAnchor | "NEXT" }): string {
  const name = (value: DriveAnchor) => value === "HOME" ? "home" : "depot";
  if ("from" in place) return `${minutes} min from ${name(place.from)}`;
  return place.to === "NEXT" ? `${minutes} min to next stop` : `${minutes} min to ${name(place.to)}`;
}
