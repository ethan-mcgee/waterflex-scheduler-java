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
    deltas_from_legacy: Record<keyof SimulationMetrics, { absolute: number; percent: number | null }>;
    warnings: string[];
  }>;
}
