import { z } from "zod";
import { date, minute, text } from "./contracts";

export const timeOffIntervalView = z.object({ date, startMin: minute, endMin: minute }).refine(value => value.startMin < value.endMin);
const metrics = z.object({
  route_minutes: z.number().finite(), overtime_minutes: z.number().finite(), drive_minutes: z.number().finite(),
  waiting_minutes: z.number().finite(), distance_meters: z.number().finite(), modeled_cost_cents: z.number().finite(),
});
const reportDay = z.object({
  service_date: date, start_min: minute, end_min: minute, status: text, run_id: text.optional(), reason: z.string().nullable().optional(),
  reassigned_jobs: z.number().int().nonnegative().optional(), daily_before: metrics.optional(), daily_after: metrics.optional(),
}).refine(value => value.start_min < value.end_min);
const completedReport = z.object({ technician_id: text, days: z.array(reportDay), total_before: metrics.nullable().optional(),
  total_after: metrics.nullable().optional(), reassigned_jobs: z.number().int().nonnegative().optional() });
const failureReport = z.object({ reason: text });

export type TimeOffReportSummary = z.infer<typeof completedReport>;
export type TimeOffIntervalView = z.infer<typeof timeOffIntervalView>;
export type ParsedTimeOffReport =
  | { kind: "missing" }
  | { kind: "malformed" }
  | { kind: "failure"; reason: string }
  | { kind: "complete"; summary: TimeOffReportSummary };

export function parseTimeOffReport(value: unknown): ParsedTimeOffReport {
  if (value == null) return { kind: "missing" };
  const completed = completedReport.safeParse(value);
  if (completed.success) return { kind: "complete", summary: completed.data };
  const failure = failureReport.safeParse(value);
  return failure.success ? { kind: "failure", reason: failure.data.reason } : { kind: "malformed" };
}
