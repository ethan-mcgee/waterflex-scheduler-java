import type { z } from "zod";
import type { dailyOutcome, policyAnalysis, solverAnalysis } from "./contracts";

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
