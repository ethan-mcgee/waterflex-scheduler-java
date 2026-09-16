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

export function requestSlots(jobId: string): Promise<{ jobId: string; offers: SlotOffer[] }> {
  return engineFetch("/v1/offers", { jobId });
}

export function selectOffer(jobId: string, offerId: string): Promise<{ holdId: string; expiresAt: string }> {
  return engineFetch("/v1/offers/select", { jobId, offerId });
}

export function confirmHold(holdId: string): Promise<{ appointmentId: string; windowStart: string; windowEnd: string }> {
  return engineFetch("/v1/holds/confirm", { holdId });
}

export function optimizeDay(params: {
  metro_id: string;
  date: string;
}): Promise<{
  optimized: boolean;
  appointments_moved: number;
  reason: string | null;
  status: string;
  solve_ms: number;
  baseline_route_seconds: number;
  proposal_route_seconds: number;
  baseline_drive_seconds: number;
  proposal_drive_seconds: number;
  gross_savings_minutes: number;
  net_savings_minutes: number;
  churn_penalty_minutes: number;
  appointments_reassigned: number;
  appointments_retimed: number;
  used_haversine_fallback: boolean;
  baseline_objective_cost: number;
  proposal_objective_cost: number;
  objective_improvement: number;
  balance_target_minutes: number;
  optimizer_weights: { drive: number; balance: number; overtime: number };
  baseline_max_workload_minutes: number;
  proposal_max_workload_minutes: number;
  baseline_overtime_minutes: number;
  proposal_overtime_minutes: number;
  verification: string[];
  warnings: string[];
  route_summary_before: Array<{
    technician_id: string;
    stop_count: number;
    route_minutes: number;
    drive_minutes: number;
    return_drive_minutes: number;
    service_minutes: number;
    wait_minutes: number;
    workload_minutes: number;
    overtime_minutes: number;
    departure_min: number;
    return_min: number;
    appointment_ids: string[];
  }>;
  route_summary_after: Array<{
    technician_id: string;
    stop_count: number;
    route_minutes: number;
    drive_minutes: number;
    return_drive_minutes: number;
    service_minutes: number;
    wait_minutes: number;
    workload_minutes: number;
    overtime_minutes: number;
    departure_min: number;
    return_min: number;
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
}> {
  return engineFetch("/v1/optimize/day", params);
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
    workload_minutes: number;
    overtime_minutes: number;
    appointment_ids: string[];
  }>;
  route_summary_after: Array<{
    technician_id: string;
    stop_count: number;
    route_minutes: number;
    drive_minutes: number;
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

export function reassignAppointment(params: {
  appointment_id: string;
  new_technician_id: string;
}): Promise<{ success: boolean; reason: string | null }> {
  return engineFetch("/v1/appointments/reassign", params);
}

export function cancelAppointment(params: { appointment_id: string }): Promise<{ success: boolean }> {
  return engineFetch("/v1/appointments/cancel", params);
}

export interface SimulationMetrics {
  requested_jobs: number;
  booked_jobs: number;
  rejected_jobs: number;
  acceptance_rate: number;
  drive_minutes: number;
  drive_minutes_per_appointment: number;
  total_paid_route_minutes: number;
  overtime_minutes: number;
  mean_days_to_service: number;
  mean_remaining_slack_minutes: number;
  dispatch_drive_savings_minutes: number;
  dispatch_paid_time_savings_minutes: number;
  appointments_reassigned: number;
  appointments_materially_retimed: number;
  window_violations: number;
  feasibility_violations: number;
  simulation_runtime_ms: number;
  solver_runtime_ms: number;
  solver_fallbacks: number;
}

export interface OptimizationSimulationReport {
  algorithm_version: string;
  config: {
    job_count: number;
    technician_count: number;
    horizon_days: number;
    seed: number;
  };
  results: Array<{
    strategy: "legacy" | "enhanced_scoring" | "lookahead";
    customer_profile: "soonest" | "route_friendly" | "mixed";
    metrics: SimulationMetrics;
    deltas_from_legacy: Record<
      keyof SimulationMetrics,
      { absolute: number; percent: number | null }
    >;
    warnings: string[];
  }>;
}

export function runOptimizationSimulation(params: {
  job_count: number;
  technician_count: number;
  horizon_days: number;
  seed: number;
}): Promise<OptimizationSimulationReport> {
  return engineFetch("/v1/internal/optimization-simulation", params);
}
