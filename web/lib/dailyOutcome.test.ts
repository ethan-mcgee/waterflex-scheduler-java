import assert from "node:assert/strict";
import test from "node:test";
import { dailyOutcome } from "./contracts";
import { parseTimeOffReport, repairOvertimeMinutes } from "./timeOffView";

const partial = { mode: "REPAIR", scoreModelVersion: "bendable-decimal-repair-v2", assignedVisitIds: ["a"],
  unassignedVisitIds: ["b"], complete: false, assignedWorkFeasible: true, scoringMatchesValidation: true, policyEligible: false };
test("partial diagnostic coverage remains explicit and cannot become policy eligible", () => {
  assert.deepEqual(dailyOutcome.parse(partial).unassignedVisitIds, ["b"]);
  for (const change of [{ complete: true }, { policyEligible: true }, { assignedWorkFeasible: false },
    { unassignedVisitIds: ["a"] }, { unassignedVisitIds: ["b", "b"] }, { complete: null }, { unassignedVisitIds: null },
    { scoreModelVersion: "hard-medium-soft-decimal-v1" }])
    assert.equal(dailyOutcome.safeParse({ ...partial, ...change }).success, false);
});
test("time-off reports retain unresolved demand and remain ineligible for approval", () => {
  const report = parseTimeOffReport({ technician_id: "t", days: [{ service_date: "2030-01-01", start_min: 480, end_min: 1020,
    status: "SKIPPED", reason: "UNRESOLVED_DEMAND", calculation_outcome: partial, score_model_version: "bendable-decimal-repair-v2" }] });
  assert.equal(report.kind, "complete");
  if (report.kind !== "complete") throw new Error("Expected complete diagnostic report");
  assert.deepEqual(report.summary.days[0]?.calculation_outcome?.unassignedVisitIds, ["b"]);
  assert.equal(repairOvertimeMinutes(report), null);
});
