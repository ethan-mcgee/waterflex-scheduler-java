import { z } from "zod";

/**
 * The public scheduling API's snapshot, as the portal sends it (docs/api/openapi-v1.yaml, `Snapshot`). Every object is
 * strict, as the API's `additionalProperties: false` is. The smoke test checks built snapshots against the OpenAPI
 * schema itself, so this copy cannot quietly drift from it.
 */

const id = z.string().min(1).max(128).regex(/^\S(.*\S)?$/);
const instant = z.iso.datetime();
const serviceDate = z.iso.date();
const decimal = z.string().regex(/^(0|[1-9][0-9]*)(\.[0-9]*[1-9])?$/);
const window = z.object({ start: instant, end: instant }).strict();

export const publicAddress = z.object({ line1: z.string().min(1), line2: z.string().optional(), city: z.string().min(1),
  state: z.string().length(2), postalCode: z.string().min(5) }).strict();
export const publicLocation = z.union([
  z.object({ lat: z.number().min(-90).max(90), lng: z.number().min(-180).max(180), address: publicAddress.optional() }).strict(),
  z.object({ address: publicAddress }).strict(),
]);

export const publicSnapshot = z.object({
  metroId: id,
  timeZone: z.string().min(1),
  rates: z.object({ regularHourly: decimal, overtimeHourly: decimal, mileagePerMile: decimal, travelBufferPct: decimal,
    travelBufferMinutes: z.int().min(0).max(120) }).strict(),
  policy: z.object({ fairnessBudget: decimal, dailySearch: z.enum(["TABU", "TABU_KOPT", "TABU_KOPT_SIZE_3"]), costSearchSeconds: z.int().min(5).max(240),
    fairnessSearchSeconds: z.int().min(0).max(240), bookingDeadlineMs: z.int().min(1000).max(5000),
    regularWindowThreshold: z.int().min(0).max(1000),
    // Optional since spec 1.3.0 (at most 3000 since 1.5.0); the portal leaves it out, so the scheduler operator's default applies.
    bookingRefinementMs: z.int().min(0).max(3000).optional(),
    // Optional since spec 1.6.0: sent only when the client set a limit; absent, every daily search spends its whole time.
    unimprovedSearchSeconds: z.int().min(1).max(240).optional() }).strict()
    .refine(policy => policy.costSearchSeconds + policy.fairnessSearchSeconds <= 240, "search times together exceed 240 seconds")
    .refine(policy => policy.unimprovedSearchSeconds === undefined || policy.unimprovedSearchSeconds <= policy.costSearchSeconds,
      "the unimproved search time exceeds the cost search time"),
  technicians: z.array(z.object({ id, qualifications: z.array(id) }).strict()),
  technicianDays: z.array(z.object({ technicianId: id, serviceDate, lastModified: instant, shift: window,
    absences: z.array(window), start: publicLocation, end: publicLocation, maxPaidMinutes: z.int().min(1).max(1440) }).strict()),
  appointments: z.array(z.object({ id, technicianId: id, serviceDate, serviceId: id, durationMinutes: z.int().min(1).max(720),
    window, location: publicLocation, sequence: z.int().min(0), plannedStart: instant }).strict()),
}).strict();

export type PublicSnapshot = z.infer<typeof publicSnapshot>;
export type PublicLocation = z.infer<typeof publicLocation>;
