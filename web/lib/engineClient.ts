// Server-only client for the Java scheduling service's internal portal endpoints. Never import this from a Client
// Component; it carries the shared internal secret. The portal schedules only through the public API (schedulerApi.ts);
// what is left here is used by the benchmark harness and by smokes of those Java endpoints, which go with them in P7c.
import { z } from "zod";
import { durableSearchStatus, errorMessage, locationValidation, offersResponse, selection, success, dailyOutcome, policyAnalysis, solverAnalysis } from "./contracts";

export function validateBookingLocation(point: { lat: number; lng: number }, signal?: AbortSignal) {
  return request("/v1/book/location/validate", locationValidation, point, 8000, signal);
}

const ENGINE_URL = process.env.ENGINE_URL ?? "http://localhost:8000";
const INTERNAL_API_SECRET = process.env.INTERNAL_API_SECRET ?? "dev-only-change-me";

export class EngineError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T>(path: string, schema: z.ZodType<T>, body?: unknown, timeoutMs = 30000, externalSignal?: AbortSignal, method?: "GET" | "POST" | "DELETE"): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${ENGINE_URL}${path}`, {
      method: method ?? (body === undefined ? "GET" : "POST"),
      headers: { "Content-Type": "application/json", "x-internal-secret": INTERNAL_API_SECRET },
      body: body === undefined ? undefined : JSON.stringify(body), cache: "no-store",
      signal: externalSignal ? AbortSignal.any([externalSignal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs),
    });
  } catch { throw new EngineError(503, "Scheduling service unavailable"); }
  const value: unknown = await res.json().catch(() => undefined);
  if (!res.ok) throw new EngineError(res.status, errorMessage(value, res.statusText || "Scheduling request failed"));
  const parsed = schema.safeParse(value);
  if (!parsed.success) throw new EngineError(502, "Malformed scheduling response");
  return parsed.data;
}

export interface SlotOffer {
  offerId: string;
  date: string;
  windowStart: string;
  windowEnd: string;
  expiresAt: string;
}

export async function requestSlots(jobId: string, refresh = false, timeoutMs = 5000, signal?: AbortSignal, deadlineEpochMs?: number): Promise<z.infer<typeof offersResponse>> {
  const started = performance.now();
  if (deadlineEpochMs != null && (!Number.isSafeInteger(deadlineEpochMs) || deadlineEpochMs <= 0))
    throw new EngineError(400, "Invalid appointment search deadline");
  const remaining = deadlineEpochMs == null ? 5000 : deadlineEpochMs - Date.now();
  if (remaining <= 0) throw new EngineError(504, "Appointment search deadline exhausted. Please retry.");
  const budgetMs = Math.min(5000, timeoutMs, remaining);
  if (!Number.isInteger(budgetMs) || budgetMs <= 0) throw new EngineError(400, "Invalid appointment search budget");
  const searchRequestId = crypto.randomUUID();
  let cancellation: Promise<void> | undefined;
  const cancel = () => {
    cancellation ??= (async () => {
      for (let attempt = 0; attempt < 2; attempt++) {
        try { await request("/v1/offers/cancel-search", success, { jobId, searchRequestId }, 2000); return; }
        catch (error) { if (attempt === 1) console.error("Search cancellation could not be acknowledged", { searchRequestId, error }); }
      }
    })();
  };
  signal?.addEventListener("abort", cancel, { once: true });
  try {
    if (signal?.aborted) { cancel(); throw new EngineError(499, "Appointment search cancelled"); }
    const result = await request("/v1/offers", offersResponse, { jobId, refresh, searchRequestId,
      deadlineEpochMs: Math.min(deadlineEpochMs ?? Number.MAX_SAFE_INTEGER, Date.now() + budgetMs) }, budgetMs, signal);
    if (result.offers.length) {
      const remaining = Math.floor(budgetMs - (performance.now() - started));
      if (remaining <= 0) throw new EngineError(504, "Appointment search deadline exhausted");
      await request("/v1/offers/acknowledge-search", success, { jobId, searchRequestId }, remaining, signal);
    }
    return { ...result, search: { ...result.search, apiElapsedMs: Math.ceil(performance.now() - started) } };
  } catch (error) {
    cancel();
    if (!signal?.aborted && performance.now() - started >= budgetMs)
      throw new EngineError(504, "Appointment search deadline exhausted. Please retry.");
    throw error;
  }
  finally { signal?.removeEventListener("abort", cancel); }
}

export function selectOffer(jobId: string, offerId: string, timeoutMs?: number): Promise<{ holdId: string; expiresAt: string; appointmentId: string; windowStart: string; windowEnd: string }> {
  return request("/v1/offers/select", selection, { jobId, offerId }, timeoutMs);
}

export function releaseOffers(jobId: string, offerId: string): Promise<{ success: boolean }> {
  return request("/v1/offers/release", success, { jobId, offerId });
}

const purgeRoute = z.object({ technicianId: z.string().min(1), serviceDate: z.iso.date(), version: z.int().nonnegative(),
  routingIdentity: z.string().min(1).nullable(),
  stops: z.array(z.object({ id: z.string().min(1), plannedStart: z.iso.datetime(), plannedEnd: z.iso.datetime() })),
  segments: z.array(z.object({ departure: z.iso.datetime(), returnedAt: z.iso.datetime(), appointmentIds: z.array(z.string().min(1)).min(1) })),
});
export function validatePurgeRoutes(jobIds: string[], days: Array<{ technicianId: string; serviceDate: string }>) {
  return request("/v1/purge/validate-routes", z.array(purgeRoute), { jobIds, days });
}

/** An engine optimization run, as saved in booking test runs made before the public API. */
export interface OptimizationRun {
  score_model_version?: "hard-medium-soft-decimal-v1" | "bendable-decimal-repair-v2" | null;
  calculation_outcome?: z.infer<typeof dailyOutcome> | null;
  policy_analysis?: z.infer<typeof policyAnalysis> | null;
  solver_analysis?: z.infer<typeof solverAnalysis> | null;
  run_id: string;
  metro_id: string;
  service_date: string;
  status: string;
  reason: string | null;
  solver_status: string;
  solve_ms: number;
  routing_identity: string;
  configuration_version: string;
  cost_model_version?: "legacy-double-v1" | "exact-fleet-half-up-v2" | "exact-fleet-half-up-v3" | null;
  fleet_cost_before_cents?: number | null;
  fleet_cost_after_cents?: number | null;
  objective_improvement: number;
  churn_penalty_minutes: number;
  optimized: boolean;
  appointments_moved: number;
  created_at: string;
  applied_at: string | null;
  warnings: string[];
  route_summary_before: Array<{
    technician_id: string;
    stop_count: number;
    route_minutes: number;
    drive_minutes: number;
    travel_breakdown?: { road_seconds: number; configured_buffer_seconds: number; rounding_seconds: number; modeled_travel_minutes: number; leg_count: number } | null;
    waiting_minutes: number;
    distance_meters: number;
    modeled_cost_cents: number;
    workload_minutes: number;
    overtime_minutes: number;
    appointment_ids: string[];
    segments?: Array<{ departure: string; returned_at: string; appointment_ids: string[] }> | null;
  }>;
  route_summary_after: Array<{
    technician_id: string;
    stop_count: number;
    route_minutes: number;
    drive_minutes: number;
    travel_breakdown?: { road_seconds: number; configured_buffer_seconds: number; rounding_seconds: number; modeled_travel_minutes: number; leg_count: number } | null;
    waiting_minutes: number;
    distance_meters: number;
    modeled_cost_cents: number;
    workload_minutes: number;
    overtime_minutes: number;
    appointment_ids: string[];
    segments?: Array<{ departure: string; returned_at: string; appointment_ids: string[] }> | null;
  }>;
  changes: Array<{
    appointment_id: string;
    from_technician_id: string;
    to_technician_id: string;
    from_sequence: number;
    to_sequence: number;
    from_planned_arrival_min: number;
    to_planned_arrival_min: number;
  }>;
}

export function startBookingSearch(jobId: string, requestId: string, refresh: boolean) {
  return request("/v1/booking-searches", durableSearchStatus, { jobId, requestId, refresh }, 10000);
}
export function bookingSearchStatus(id: string, jobId: string, cancel = false) {
  return request(`/v1/booking-searches/${encodeURIComponent(id)}?jobId=${encodeURIComponent(jobId)}`,
    durableSearchStatus, undefined, 10000, undefined, cancel ? "DELETE" : "GET");
}
