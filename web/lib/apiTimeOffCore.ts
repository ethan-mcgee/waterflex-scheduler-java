import { z } from "zod";
import { date, instant, minute, text } from "./contracts";
import { addCalendarDays, todayInTz } from "./date";
import { localMinute } from "./zonedTime";

/**
 * The pure rules of time-off repair through the public scheduling API, safe for the browser: the analysis report the
 * portal keeps per request, the scheduling cutoff, and the submission checks the scheduler's own time-off service
 * applies.
 */

/** Statuses of an API analysis; the scheduler's own queue picks up only QUEUED, which this never uses. */
export const AWAITING_ANALYSIS = "AWAITING_ANALYSIS";

const day = z.object({
  service_date: date, start_min: minute, end_min: minute,
  status: z.enum(["PENDING", "REPAIR_PREVIEW", "NO_SHIFT", "NO_APPOINTMENTS", "FROZEN_CSR_COORDINATION", "NEEDS_COORDINATION"]),
  proposal_id: text.optional(), reason: text.optional(), last_modified: instant.optional(), reassigned_jobs: z.int().nonnegative().optional(),
}).strict().superRefine((value, context) => {
  const fail = (message: string) => context.addIssue({ code: "custom", message });
  if (value.start_min >= value.end_min) fail("An absence ends after it starts");
  if (value.status === "REPAIR_PREVIEW" && (value.proposal_id === undefined || value.reassigned_jobs === undefined)) fail("A repair names its proposal and reassignments");
  if (unchangedDay(value.status) !== (value.last_modified !== undefined)) fail("Only a day left as it is keeps the timestamp it was analyzed at");
  if ((value.status === "NEEDS_COORDINATION" || value.status === "FROZEN_CSR_COORDINATION") && value.reason === undefined) fail("A day needing coordination says why");
  if (value.status === "PENDING" && (value.proposal_id ?? value.reason ?? value.last_modified ?? value.reassigned_jobs) !== undefined) fail("A pending day has no result");
});

/** A day the absence leaves as it is: no shift, or a shift with no appointments to move. Approval checks it is unchanged. */
export function unchangedDay(status: string): boolean {
  return status === "NO_SHIFT" || status === "NO_APPOINTMENTS";
}

export const apiTimeOffReport = z.object({ source: z.literal("PUBLIC_API"), technician_id: text, days: z.array(day).min(1), failure: text.optional() }).strict();
export type ApiTimeOffReport = z.infer<typeof apiTimeOffReport>;
export type ApiTimeOffDay = ApiTimeOffReport["days"][number];
export type Interval = { date: string; startMin: number; endMin: number };

export function initialApiReport(technicianId: string, intervals: readonly Interval[]): ApiTimeOffReport {
  if (intervals.length === 0) throw new Error("A time-off request has at least one day");
  return { source: "PUBLIC_API", technician_id: technicianId,
    days: intervals.map(item => ({ service_date: item.date, start_min: item.startMin, end_min: item.endMin, status: "PENDING" as const })) };
}

/** Whether the report is for exactly these intervals, in order. */
export function reportMatches(report: ApiTimeOffReport, technicianId: string, intervals: readonly Interval[]): boolean {
  return report.technician_id === technicianId && report.days.length === intervals.length && report.days.every((item, index) => {
    const interval = intervals[index];
    return interval !== undefined && item.service_date === interval.date && item.start_min === interval.startMin && item.end_min === interval.endMin;
  });
}

export function nextPendingDay(report: ApiTimeOffReport): number | null {
  const index = report.days.findIndex(item => item.status === "PENDING");
  return index < 0 ? null : index;
}

export function analysisProgress(report: ApiTimeOffReport): number {
  return Math.floor(100 * report.days.filter(item => item.status !== "PENDING").length / report.days.length);
}

/** Every day can be approved: repaired, or left as it is. */
export function feasible(report: ApiTimeOffReport): boolean {
  return report.days.every(item => item.status === "REPAIR_PREVIEW" || unchangedDay(item.status));
}

/** A service day freezes at 6 a.m. local time, as the scheduler's ScheduleCutoff does. */
export function frozen(serviceDate: string, now: Date, timeZone: string): boolean {
  return now.getTime() >= localMinute(serviceDate, 360, false, timeZone).getTime();
}

/** The absence as instants: stored minutes are local times on the service date, 1440 being the next midnight. */
export function absenceWindow(serviceDate: string, startMin: number, endMin: number, timeZone: string): { start: string; end: string } {
  return { start: localMinute(serviceDate, startMin, false, timeZone).toISOString(), end: localMinute(serviceDate, endMin, true, timeZone).toISOString() };
}

/** A request starting two weeks out or later is approved as soon as its analysis finds every day repairable. */
export function automaticallyApproved(firstDate: string, today: string): boolean {
  return firstDate >= addCalendarDays(today, 14);
}

/** The scheduler's own submission checks; null when the request may be submitted. */
export function submissionProblem(input: { firstDate: string; lastDate: string; startMin: number; endMin: number; reason: string }, now: Date, timeZone: string): string | null {
  const reason = input.reason.trim();
  if (reason.length === 0 || reason.length > 500 || input.startMin < 0 || input.endMin > 1440 || input.startMin >= input.endMin) return "Invalid time-off request";
  if (input.lastDate < input.firstDate || input.firstDate < todayInTz(timeZone, now) || input.lastDate > addCalendarDays(input.firstDate, 30)
    || localMinute(input.firstDate, input.startMin, false, timeZone).getTime() <= now.getTime()) return "Invalid or past date range";
  return null;
}

/** The days of a request, first through last. */
export function requestDates(firstDate: string, lastDate: string): string[] {
  const dates: string[] = [];
  for (let current = firstDate; current <= lastDate; current = addCalendarDays(current, 1)) dates.push(current);
  return dates;
}
