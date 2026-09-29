import { z } from "zod";
import { locationValidation } from "./contracts";

export function validateBookingLocation(point: { lat: number; lng: number }, signal?: AbortSignal) {
  return request("/v1/book/location/validate", locationValidation, point, 8000, signal);
}
import { offersResponse, selection, confirmation, optimization, optimizationRuns, success, timeOffResult, errorMessage, routabilityResponse, depotPolicyResult, policyAnalysis, solverAnalysis } from "./contracts";
import { isDispatchGeometry, type GeometryResponse } from "./dispatchGeometry";
// Server-only client for the Java scheduling service. Never import
// this from a Client Component; it carries the shared internal secret.

const ENGINE_URL = process.env.ENGINE_URL ?? "http://localhost:8000";
const INTERNAL_API_SECRET = process.env.INTERNAL_API_SECRET ?? "dev-only-change-me";

export class EngineError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export function updateDepotPolicy(id: string, departure: "HOME" | "DEPOT", returnTo: "HOME" | "DEPOT"): Promise<{ success: true; effectiveDate: string }> {
  return request(`/v1/depots/${encodeURIComponent(id)}/policy`, depotPolicyResult, { departure, returnTo });
}

export function updateDepotDetails(id: string, details: { name: string; address?: { line1: string; city: string; state: string; postalCode: string }; confirmedPin?: { lat: number; lng: number }; candidate?: { lat: number; lng: number; precision: string } }): Promise<{ success: boolean }> {
  return request(`/v1/depots/${encodeURIComponent(id)}/details`, success, details);
}

export function assignTechnicianDepot(id: string, depotId: string, effectiveDate: string): Promise<{ success: boolean }> {
  return request(`/v1/technicians/${encodeURIComponent(id)}/depot-assignments`, success, { depotId, effectiveDate });
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

export function confirmHold(holdId: string): Promise<{ appointmentId: string; windowStart: string; windowEnd: string }> {
  return request("/v1/holds/confirm", confirmation, { holdId });
}

const purgeRoute = z.object({ technicianId: z.string().min(1), serviceDate: z.iso.date(), version: z.int().nonnegative(),
  routingIdentity: z.string().min(1).nullable(),
  stops: z.array(z.object({ id: z.string().min(1), plannedStart: z.iso.datetime(), plannedEnd: z.iso.datetime() })),
  segments: z.array(z.object({ departure: z.iso.datetime(), returnedAt: z.iso.datetime(), appointmentIds: z.array(z.string().min(1)).min(1) })),
});
export function validatePurgeRoutes(jobIds: string[], days: Array<{ technicianId: string; serviceDate: string }>) {
  return request("/v1/purge/validate-routes", z.array(purgeRoute), { jobIds, days });
}

export interface OptimizationRun {
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

export function previewOptimization(params: {
  metro_id: string;
  date: string;
  request_key?: string;
}, timeoutMs?: number): Promise<OptimizationRun> {
  return request("/v1/optimize/day/preview", optimization, params, timeoutMs);
}

export function applyOptimization(runId: string): Promise<OptimizationRun> {
  return request(`/v1/optimize/runs/${encodeURIComponent(runId)}/apply`, optimization, {});
}

export function optimizationHistory(metroId: string, date: string): Promise<{ runs: OptimizationRun[] }> {
  const query = new URLSearchParams({ metro_id: metroId, date });
  return request(`/v1/optimize/runs?${query}`, optimizationRuns);
}

export function optimizationRun(runId: string): Promise<OptimizationRun> {
  return request(`/v1/optimize/runs/${encodeURIComponent(runId)}`, optimization);
}

export async function checkTestAddressRoutability(candidates: Array<{ id: string; serviceCode: string; lat: number; lng: number }>, timeoutMs?: number, signal?: AbortSignal): Promise<Set<string>> {
  const response = await request("/internal/test-address-routability", routabilityResponse, { candidates }, timeoutMs, signal);
  return new Set(response.results.filter(result => result.routable).map(result => result.id));
}

export function cancelAppointment(params: { appointment_id: string; reason: string }): Promise<{ success: boolean }> {
  return request("/v1/appointments/cancel", success, params);
}

export function dispatchGeometry(metroId: string, date: string, runId?: string, phase = "current") {
  const query = new URLSearchParams({ metro_id: metroId, date, phase });
  if (runId) query.set("run_id", runId);
  return request(`/v1/dispatch/geometry?${query}`, z.custom<GeometryResponse>(v => isDispatchGeometry(v, date, phase)));
}

export function submitTimeOff(request: { technicianId: string; firstDate: string; lastDate: string; startMin: number; endMin: number; category: string; reason: string }): Promise<{ requestId: string; status: string }> {
  return requestEngine("/v1/time-off/request", timeOffResult, request);
}

export function approveTimeOff(id: string, allowAdditionalOvertime = false, approvedRepairIds: string[] = []): Promise<{ requestId: string; status: string }> {
  return request(`/v1/time-off/${encodeURIComponent(id)}/approve`, timeOffResult, { allowAdditionalOvertime, approvedRepairIds });
}

export function retryTimeOff(id: string): Promise<{ requestId: string; status: string }> {
  return request(`/v1/time-off/${encodeURIComponent(id)}/retry`, timeOffResult, {});
}

export function denyTimeOff(id: string): Promise<{ requestId: string; status: string }> {
  return request(`/v1/time-off/${encodeURIComponent(id)}/deny`, timeOffResult, {});
}

export function updateAvailability(request: { technicianId: string; date: string; available: boolean; shiftStartMin?: number | null; shiftEndMin?: number | null }): Promise<{ success: boolean }> {
  return requestEngine("/v1/dispatch/availability", success, request);
}

export function deleteAvailabilityOverride(request: { technicianId: string; date: string }): Promise<{ success: boolean }> {
  return requestEngine("/v1/dispatch/availability", success, request, 30000, undefined, "DELETE");
}

export function updateQualification(request: { technicianId: string; serviceId: string; qualified: boolean }): Promise<{ success: boolean }> {
  return requestEngine("/v1/dispatch/qualification", success, request);
}

const requestEngine = request;
