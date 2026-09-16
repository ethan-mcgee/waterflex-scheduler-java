import type { OptimizationSimulationReport, SimulationMetrics } from "./optimizationSimulationTypes";
import type { ValidationAggregate } from "./optimizationAnalysis";

export const EXPORT_METRIC_KEYS: Array<keyof SimulationMetrics> = [
  "requested_jobs",
  "booked_jobs",
  "rejected_jobs",
  "acceptance_rate",
  "drive_minutes",
  "drive_minutes_per_appointment",
  "total_paid_route_minutes",
  "overtime_minutes",
  "mean_days_to_service",
  "mean_remaining_slack_minutes",
  "dispatch_drive_savings_minutes",
  "dispatch_paid_time_savings_minutes",
  "appointments_reassigned",
  "appointments_materially_retimed",
  "window_violations",
  "feasibility_violations",
  "simulation_runtime_ms",
  "solver_runtime_ms",
  "solver_fallbacks",
];

function csvCell(value: unknown) {
  return `"${String(value).replaceAll('"', '""')}"`;
}

export function serializeOptimizationCsv(report: OptimizationSimulationReport): string {
  const headers = [
    "algorithm_version",
    "seed",
    "job_count",
    "technician_count",
    "horizon_days",
    "customer_profile",
    "strategy",
    ...EXPORT_METRIC_KEYS.flatMap((key) => [key, `${key}_delta`, `${key}_delta_percent`]),
  ];
  const rows = report.results.map((result) => [
    report.algorithm_version,
    report.config.seed,
    report.config.job_count,
    report.config.technician_count,
    report.config.horizon_days,
    result.customer_profile,
    result.strategy,
    ...EXPORT_METRIC_KEYS.flatMap((key) => [
      result.metrics[key],
      result.deltas_from_legacy[key].absolute,
      result.deltas_from_legacy[key].percent ?? "",
    ]),
  ]);
  return [headers, ...rows].map((row) => row.map(csvCell).join(",")).join("\n");
}

export function serializeOptimizationJson(report: OptimizationSimulationReport): string {
  return JSON.stringify(report, null, 2);
}

export function serializeValidationJson(aggregate: ValidationAggregate, runs: OptimizationSimulationReport[]): string {
  return JSON.stringify({ mode: "validation", aggregate, raw_runs: runs }, null, 2);
}

export function serializeValidationCsv(aggregate: ValidationAggregate, runs: OptimizationSimulationReport[]): string {
  const headers = [
    "validation_recommendation",
    "validation_explanation",
    "algorithm_version",
    "seed",
    "job_count",
    "technician_count",
    "horizon_days",
    "customer_profile",
    "strategy",
    ...EXPORT_METRIC_KEYS.flatMap((key) => [key, `${key}_delta`, `${key}_delta_percent`]),
  ];
  const rows = runs.flatMap((report) => report.results.map((result) => [
    aggregate.recommendation.label,
    aggregate.recommendation.explanation,
    report.algorithm_version,
    report.config.seed,
    report.config.job_count,
    report.config.technician_count,
    report.config.horizon_days,
    result.customer_profile,
    result.strategy,
    ...EXPORT_METRIC_KEYS.flatMap((key) => [
      result.metrics[key],
      result.deltas_from_legacy[key].absolute,
      result.deltas_from_legacy[key].percent ?? "",
    ]),
  ]));
  return [headers, ...rows].map((row) => row.map(csvCell).join(",")).join("\n");
}
