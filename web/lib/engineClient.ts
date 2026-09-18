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

async function engineFetch<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${ENGINE_URL}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-internal-secret": INTERNAL_API_SECRET,
    },
    body: JSON.stringify(body),
    cache: "no-store",
  });

  if (!res.ok) {
    const detail = await res.json().catch(() => ({ detail: res.statusText }));
    throw new EngineError(res.status, detail.detail ?? "engine request failed");
  }
  return res.json() as Promise<T>;
}

async function engineGet<T>(path: string): Promise<T> {
  const res = await fetch(`${ENGINE_URL}${path}`, {
    headers: { "x-internal-secret": INTERNAL_API_SECRET },
    cache: "no-store",
  });
  if (!res.ok) {
    const detail = await res.json().catch(() => ({ detail: res.statusText }));
    throw new EngineError(res.status, detail.detail ?? "engine request failed");
  }
  return res.json() as Promise<T>;
}

export interface SlotOffer {
  offerId: string;
  date: string;
  windowStart: string;
  windowEnd: string;
  expiresAt: string;
}

export function requestSlots(jobId: string, refresh = false): Promise<{ jobId: string; offers: SlotOffer[] }> {
  return engineFetch("/v1/offers", { jobId, refresh });
}

export function selectOffer(jobId: string, offerId: string): Promise<{ holdId: string; expiresAt: string; appointmentId: string; windowStart: string; windowEnd: string }> {
  return engineFetch("/v1/offers/select", { jobId, offerId });
}

export function confirmHold(holdId: string): Promise<{ appointmentId: string; windowStart: string; windowEnd: string }> {
  return engineFetch("/v1/holds/confirm", { holdId });
}

export interface OptimizationRun {
  run_id: string;
  metro_id: string;
  service_date: string;
  status: string;
  reason: string | null;
  solver_status: string;
  solve_ms: number;
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
    waiting_minutes: number;
    distance_meters: number;
    modeled_cost_cents: number;
    workload_minutes: number;
    overtime_minutes: number;
    appointment_ids: string[];
  }>;
  route_summary_after: Array<{
    technician_id: string;
    stop_count: number;
    route_minutes: number;
    drive_minutes: number;
    waiting_minutes: number;
    distance_meters: number;
    modeled_cost_cents: number;
    workload_minutes: number;
    overtime_minutes: number;
    appointment_ids: string[];
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
}): Promise<OptimizationRun> {
  return engineFetch("/v1/optimize/day/preview", params);
}

export function applyOptimization(runId: string): Promise<OptimizationRun> {
  return engineFetch(`/v1/optimize/runs/${encodeURIComponent(runId)}/apply`, {});
}

export function optimizationHistory(metroId: string, date: string): Promise<{ runs: OptimizationRun[] }> {
  const query = new URLSearchParams({ metro_id: metroId, date });
  return engineGet(`/v1/optimize/runs?${query}`);
}

export function cancelAppointment(params: { appointment_id: string; reason: string }): Promise<{ success: boolean }> {
  return engineFetch("/v1/appointments/cancel", params);
}

export function submitTimeOff(request: { technicianId: string; firstDate: string; lastDate: string; startMin: number; endMin: number; reason: string }): Promise<{ requestId: string; status: string }> {
  return engineFetch("/v1/time-off/request", request);
}

export function approveTimeOff(id: string): Promise<{ requestId: string; status: string }> {
  return engineFetch(`/v1/time-off/${encodeURIComponent(id)}/approve`, {});
}

export function updateAvailability(request: { technicianId: string; date: string; available: boolean; shiftStartMin?: number | null; shiftEndMin?: number | null }): Promise<{ success: boolean }> {
  return engineFetch("/v1/dispatch/availability", request);
}

export function updateQualification(request: { technicianId: string; serviceId: string; qualified: boolean }): Promise<{ success: boolean }> {
  return engineFetch("/v1/dispatch/qualification", request);
}
