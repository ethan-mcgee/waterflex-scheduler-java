import { z } from "zod";

// Browser-safe decoders. Keep network and database values unknown until parsed.
export const text = z.string().trim().min(1);
export const date = z.iso.date();
export const instant = z.iso.datetime({ offset: true });
export const finite = z.number().finite();
export const minute = z.int().min(0).max(1440);
export const point = z.object({ lat: finite.min(-90).max(90), lng: finite.min(-180).max(180) });
export const offer = z.object({ offerId: text, date, windowStart: instant, windowEnd: instant, expiresAt: instant });
export const offersResponse = z.object({ jobId: text, offers: z.array(offer) });
export const confirmation = z.object({ appointmentId: text, windowStart: instant, windowEnd: instant });
export const selection = confirmation.extend({ holdId: text, expiresAt: instant });
export const success = z.object({ success: z.boolean() });
export const timeOffResult = z.object({ requestId: text, status: text });
export const routabilityResponse = z.object({ results: z.array(z.object({ id: text, routable: z.boolean() })) });
const routeSummary = z.object({
  technician_id: text, stop_count: z.int().nonnegative(), route_minutes: finite, drive_minutes: finite,
  waiting_minutes: finite, distance_meters: finite, modeled_cost_cents: finite, workload_minutes: finite,
  overtime_minutes: finite, appointment_ids: z.array(text),
});
export const optimization = z.object({
  run_id: text, metro_id: text, service_date: date, status: text, reason: z.string().nullable(),
  solver_status: text, solve_ms: finite.nonnegative(), routing_identity: text, configuration_version: text,
  objective_improvement: finite, churn_penalty_minutes: finite, optimized: z.boolean(),
  appointments_moved: z.int().nonnegative(), created_at: instant, applied_at: instant.nullable(), warnings: z.array(z.string()),
  route_summary_before: z.array(routeSummary), route_summary_after: z.array(routeSummary),
  changes: z.array(z.object({ appointment_id: text, from_technician_id: text, to_technician_id: text,
    from_sequence: z.int(), to_sequence: z.int(), from_planned_arrival_min: finite, to_planned_arrival_min: finite })),
});
export const optimizationRuns = z.object({ runs: z.array(optimization) });
export const bookingRequest = z.object({ requestId: text.min(16), firstName: text, lastName: text, email: text,
  phone: text, line1: text, line2: z.string().optional(), city: text, state: text, postalCode: text, serviceCode: text,
  confirmedPin: point.optional() });
export const jobRequest = z.object({ jobId: text });
export const selectRequest = jobRequest.extend({ offerId: text });
export const confirmRequest = z.object({ holdId: text });
export const previewRequest = z.object({ metroId: text, date });
export const applyRequest = z.object({ runId: text });
export const qualificationRequest = z.object({ technicianId: text, serviceId: text, qualified: z.boolean() });
export const availabilityRequest = z.object({ technicianId: text, date, available: z.boolean(),
  shiftStartMin: minute.nullish(), shiftEndMin: minute.nullish() }).refine(v => !v.available ||
    (v.shiftStartMin != null && v.shiftEndMin != null && v.shiftStartMin < v.shiftEndMin), "Invalid shift hours");
export const timeOffRequest = z.object({ technicianId: text, firstDate: date, lastDate: date,
  startMin: minute, endMin: minute, reason: text }).refine(v => v.firstDate <= v.lastDate && v.startMin < v.endMin, "Invalid absence interval");
export const testConfig = z.object({ count: z.int().min(1).max(100), seed: z.int().min(0).max(0xffffffff),
  policy: z.enum(["earliest", "first", "random"]), weights: z.array(finite.min(0).max(100)).length(4).refine(v => v.some(w => w > 0)) });
export const testConfigInput = testConfig.extend({ seed: z.int().min(0).max(0xffffffff).nullish() });
export const testOperation = z.discriminatedUnion("action", [
  z.object({ id: text, action: z.literal("create"), config: testConfigInput }),
  z.object({ id: text, action: z.enum(["resume", "pause", "stop"]) }),
  z.object({ id: text, action: z.literal("purge") }),
  z.object({ id: text, action: z.literal("advance"), revision: z.int().nonnegative() }),
]);
export const testInput = z.object({ ordinal: z.int().nonnegative(), serviceCode: text, selectionUnit: finite.min(0).lt(1),
  location: point.extend({ slug: text, neighborhood: text, line2: z.string().optional(), line1: text, city: text, state: text, postalCode: text }) });
export function errorMessage(value: unknown, fallback = "Request failed"): string {
  if (value instanceof Error) return value.message;
  const parsed = z.object({ error: text.optional(), detail: text.optional() }).safeParse(value);
  return parsed.success ? parsed.data.error ?? parsed.data.detail ?? fallback : fallback;
}
export async function readBody<T>(request: { json(): Promise<unknown> }, schema: z.ZodType<T>) {
  const value: unknown = await request.json().catch(() => undefined);
  return schema.safeParse(value);
}
export function required<T>(value: T | undefined | null, label = "Required value"): T {
  if (value == null) throw new Error(`${label} is missing`);
  return value;
}

export const bookingResponse = z.object({ jobId: text, offers: z.array(offer).optional(), pendingReference: text.optional(),
  pinRequired: z.boolean().optional(), candidates: z.array(point.extend({ precision: z.enum(["ROOFTOP", "APPROXIMATE"]) })).optional()
}).refine(v => v.pinRequired ? !!v.candidates?.length : v.pendingReference !== undefined || v.offers !== undefined, "Incomplete booking response");
export const bookingFailure = z.object({ error: text.optional(), pendingReference: text.optional(), offers: z.array(offer).optional() });
export const testAttempt = z.object({ at: instant, horizon: z.array(date), elapsedMs: finite.nonnegative(), offers: z.array(offer),
  selected: offer.nullable(), outcome: text, error: z.string().nullable() });
export const runSummary = z.object({ id: text, status: text, createdAt: instant, config: testConfig,
  purgedAt: instant.nullable(), purgedCount: z.int().nonnegative().nullable() });
export const testRun = runSummary.extend({ revision: z.int().nonnegative(), error: z.string().nullable(), horizon: z.array(date), currentHorizon: z.array(date),
  requests: z.array(z.object({ id: text, ordinal: z.int().nonnegative(), input: testInput, status: text, offers: z.array(offer),
    selected: offer.nullable(), elapsedMs: finite.nonnegative(), serviceDate: date.nullable(), startedAt: instant.nullable(),
    error: z.string().nullable(), attempts: z.array(testAttempt) })),
  previews: z.array(z.object({ id: text, serviceDate: date, optimizationId: text.nullable(), result: optimization.nullable(), error: z.string().nullable() })),
  applied: z.array(z.object({ id: text, status: text, appliedAt: instant.nullable() })),
});
export const testHistory = z.object({ runs: z.array(runSummary), horizon: z.array(date) });
export async function readResponse<T>(response: Response, schema: z.ZodType<T>): Promise<T> {
  let raw: unknown;
  try {
    raw = await response.json();
  } catch {
    throw new Error(response.ok ? "Invalid response. Reload before continuing." : `Request failed (${response.status})`);
  }
  if (!response.ok) throw new Error(errorMessage(raw, `Request failed (${response.status})`));
  const parsed = schema.safeParse(raw);
  if (!parsed.success) throw new Error("Invalid response. Reload before continuing.");
  return parsed.data;
}
