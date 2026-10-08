import { test } from "node:test";
import assert from "node:assert/strict";
import { absenceWindow, analysisProgress, apiTimeOffReport, automaticallyApproved, feasible, frozen, initialApiReport, nextPendingDay,
  reportMatches, requestDates, submissionProblem } from "./apiTimeOffCore";
import { parseTimeOffReport, repairOvertimeMinutes } from "./timeOffView";

const ZONE = "America/Chicago";
const intervals: [{ date: string; startMin: number; endMin: number }, { date: string; startMin: number; endMin: number }] =
  [{ date: "2026-10-12", startMin: 480, endMin: 1020 }, { date: "2026-10-13", startMin: 480, endMin: 1020 }];

test("an analysis starts with every requested day pending and matches only those days", () => {
  const report = initialApiReport("tech", intervals);
  assert.equal(apiTimeOffReport.safeParse(report).success, true);
  assert.equal(nextPendingDay(report), 0);
  assert.equal(analysisProgress(report), 0);
  assert.equal(feasible(report), false, "Nothing is approvable before it is analyzed");
  assert.equal(reportMatches(report, "tech", intervals), true);
  assert.equal(reportMatches(report, "other", intervals), false);
  assert.equal(reportMatches(report, "tech", intervals.slice(0, 1)), false);
  assert.equal(reportMatches(report, "tech", [intervals[0], { ...intervals[1], endMin: 900 }]), false);
  assert.throws(() => initialApiReport("tech", []));
});

test("each day's result carries exactly what approval needs, and nothing is assumed", () => {
  const [first, second] = initialApiReport("tech", intervals).days;
  assert.ok(first && second);
  const repaired = { ...first, status: "REPAIR_PREVIEW" as const, proposal_id: "prop-1", reassigned_jobs: 1 };
  const unchanged = { ...second, status: "NO_APPOINTMENTS" as const, last_modified: "2026-10-11T21:04:17.123456Z" };
  const report = { source: "PUBLIC_API" as const, technician_id: "tech", days: [repaired, unchanged] };
  assert.equal(apiTimeOffReport.safeParse(report).success, true);
  assert.equal(feasible(report), true);
  assert.equal(analysisProgress(report), 100);
  assert.equal(nextPendingDay(report), null);
  const invalid = (days: unknown[]) => apiTimeOffReport.safeParse({ ...report, days }).success;
  assert.equal(invalid([{ ...repaired, proposal_id: undefined }, unchanged]), false, "A repair names its proposal");
  assert.equal(invalid([repaired, { ...unchanged, last_modified: undefined }]), false, "A day left as it is keeps its timestamp");
  assert.equal(invalid([{ ...repaired, last_modified: "2026-10-11T21:04:17Z" }, unchanged]), false, "A repaired day is compared through its receipt instead");
  assert.equal(invalid([{ ...first, status: "NEEDS_COORDINATION" }, unchanged]), false, "Coordination says why");
  assert.equal(invalid([{ ...first, reason: "early" }, unchanged]), false, "A pending day has no result");
  assert.equal(feasible({ ...report, days: [repaired, { ...second, status: "NEEDS_COORDINATION", reason: "Repair infeasible" }] }), false);
});

test("the time-off page reads an API analysis, and only a fully repairable one adds zero overtime", () => {
  const report = { source: "PUBLIC_API", technician_id: "tech", days: [{ service_date: "2026-10-12", start_min: 480, end_min: 1020, status: "NO_SHIFT", last_modified: "2026-10-11T21:04:17Z" }] };
  const parsed = parseTimeOffReport(report);
  assert.equal(parsed.kind, "api");
  assert.equal(repairOvertimeMinutes(parsed), 0);
  const pending = parseTimeOffReport(initialApiReport("tech", intervals));
  assert.equal(repairOvertimeMinutes(pending), null, "An unfinished analysis is not zero overtime");
  assert.equal(parseTimeOffReport({ ...report, source: "ENGINE" }).kind, "malformed");
  assert.equal(parseTimeOffReport({ ...report, days: [{ ...report.days[0], last_modified: undefined }] }).kind, "malformed",
    "A broken API analysis is malformed, not read as an engine report");
});

test("days freeze at 6 a.m. local time and absences become local instants", () => {
  assert.equal(frozen("2026-10-12", new Date("2026-10-12T10:59:59Z"), ZONE), false, "5:59:59 a.m. CDT");
  assert.equal(frozen("2026-10-12", new Date("2026-10-12T11:00:00Z"), ZONE), true, "6 a.m. CDT");
  assert.deepEqual(absenceWindow("2026-10-12", 480, 1020, ZONE), { start: "2026-10-12T13:00:00.000Z", end: "2026-10-12T22:00:00.000Z" });
  assert.deepEqual(absenceWindow("2026-12-01", 0, 1440, ZONE), { start: "2026-12-01T06:00:00.000Z", end: "2026-12-02T06:00:00.000Z" }, "1440 is the next midnight");
});

test("submission follows the scheduler's own checks", () => {
  const now = new Date("2026-10-08T15:00:00Z");
  const valid = { firstDate: "2026-10-12", lastDate: "2026-10-13", startMin: 480, endMin: 1020, reason: "Family trip" };
  assert.equal(submissionProblem(valid, now, ZONE), null);
  assert.equal(submissionProblem({ ...valid, reason: "   " }, now, ZONE), "Invalid time-off request");
  assert.equal(submissionProblem({ ...valid, startMin: 1020, endMin: 480 }, now, ZONE), "Invalid time-off request");
  assert.equal(submissionProblem({ ...valid, firstDate: "2026-10-07", lastDate: "2026-10-07" }, now, ZONE), "Invalid or past date range");
  assert.equal(submissionProblem({ ...valid, lastDate: "2026-11-12" }, now, ZONE), "Invalid or past date range", "At most 31 days");
  assert.equal(submissionProblem({ ...valid, firstDate: "2026-10-08", lastDate: "2026-10-08", startMin: 540 }, now, ZONE), "Invalid or past date range", "Today's absence must start later");
  assert.deepEqual(requestDates("2026-10-30", "2026-11-02"), ["2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02"]);
  assert.equal(automaticallyApproved("2026-10-22", "2026-10-08"), true);
  assert.equal(automaticallyApproved("2026-10-21", "2026-10-08"), false);
});
