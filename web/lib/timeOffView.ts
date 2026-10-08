import { z } from "zod";
import { date, minute, text, travelBreakdown, dailyOutcome, solverAnalysis } from "./contracts";
import { apiTimeOffReport, feasible, type ApiTimeOffReport } from "./apiTimeOffCore";

export const timeOffIntervalView = z.object({ date, startMin: minute, endMin: minute }).refine(value => value.startMin < value.endMin);
const metrics = z.object({
  route_minutes: z.number().finite().nonnegative(), overtime_minutes: z.int().nonnegative(), drive_minutes: z.number().finite().nonnegative(),
  waiting_minutes: z.number().finite().nonnegative(), distance_meters: z.number().finite().nonnegative(), modeled_cost_cents: z.number().finite().nonnegative(),
});
const reportDay = z.object({
  service_date: date, start_min: minute, end_min: minute, status: text, run_id: text.optional(), reason: z.string().nullable().optional(),
  reassigned_jobs: z.number().int().nonnegative().optional(), daily_before: metrics.optional(), daily_after: metrics.optional(),
  travel_before: travelBreakdown.nullish(), travel_after: travelBreakdown.nullish(),
  calculation_outcome: dailyOutcome.nullish(), solver_analysis: solverAnalysis.nullish(),
  score_model_version: z.enum(["hard-medium-soft-decimal-v1", "bendable-decimal-repair-v2"]).nullish(),
}).refine(value => value.start_min < value.end_min);
const completedReport = z.object({ technician_id: text, days: z.array(reportDay), total_before: metrics.nullable().optional(),
  total_after: metrics.nullable().optional(), reassigned_jobs: z.number().int().nonnegative().optional(),
  travel_before: travelBreakdown.nullish(), travel_after: travelBreakdown.nullish() });
const failureReport = z.object({ reason: text });

export type TimeOffReportSummary = z.infer<typeof completedReport>;
export type TimeOffIntervalView = z.infer<typeof timeOffIntervalView>;
export type ParsedTimeOffReport =
  | { kind: "missing" }
  | { kind: "malformed" }
  | { kind: "failure"; reason: string }
  | { kind: "complete"; summary: TimeOffReportSummary }
  | { kind: "api"; summary: ApiTimeOffReport };

/** Approval requires absolute zero overtime, including unchanged historical overtime. */
export function repairOvertimeMinutes(report: ParsedTimeOffReport): number | null {
  // The scheduling API never proposes a repair with overtime, so a fully repairable API report adds none.
  if (report.kind === "api") return feasible(report.summary) ? 0 : null;
  if (report.kind !== "complete") return null;
  let overtime = 0;
  for (const day of report.summary.days) {
    if (day.status === "NO_SHIFT") continue;
    if (day.status !== "REPAIR_PREVIEW" || !day.daily_after) return null;
    overtime += day.daily_after.overtime_minutes;
  }
  return overtime;
}

export function additionalRepairOvertime(report: ParsedTimeOffReport): number | null {
  if (report.kind !== "complete") return null;
  let additional = 0;
  for (const day of report.summary.days) {
    if (day.status === "NO_SHIFT") continue;
    if (day.status !== "REPAIR_PREVIEW" || !day.daily_before || !day.daily_after) return null;
    additional += Math.max(0, day.daily_after.overtime_minutes - day.daily_before.overtime_minutes);
  }
  return additional;
}

export function parseTimeOffReport(value: unknown): ParsedTimeOffReport {
  if (value == null) return { kind: "missing" };
  // A report that names its source is an API analysis, and never falls back to the engine's looser shape.
  if (typeof value === "object" && "source" in value) {
    const api = apiTimeOffReport.safeParse(value);
    return api.success ? { kind: "api", summary: api.data } : { kind: "malformed" };
  }
  const completed = completedReport.safeParse(value);
  if (completed.success) return { kind: "complete", summary: completed.data };
  const failure = failureReport.safeParse(value);
  return failure.success ? { kind: "failure", reason: failure.data.reason } : { kind: "malformed" };
}
