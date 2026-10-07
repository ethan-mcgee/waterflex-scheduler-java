import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { dailyPolicyDiagnostics } from "./contracts";
const candidate = { mode: "ASSIGNED", scoreModelVersion: "bendable-decimal-repair-v2", assignedVisitIds: ["a"],
  unassignedVisitIds: [], complete: true, assignedWorkFeasible: true, scoringMatchesValidation: true, policyEligible: false };
describe("Daily policy diagnostic boundaries", () => {
  it("preserves a rejected overtime improvement separately from apply coverage", () => {
    const result = dailyPolicyDiagnostics.parse({ schemaVersion: 1, fairness: "SKIPPED_REFERENCE_OVERTIME", decision: "REJECTED_OVERTIME",
      referenceOvertimeMinutes: 20, candidateOvertimeMinutes: 20, candidate });
    assert.equal(result.candidateOvertimeMinutes,20);
  });
  it("rejects fabricated or missing reference targets and duplicate unresolved identities", () => {
    for (const referenceOvertimeMinutes of [null, 0, -1, undefined]) assert.equal(dailyPolicyDiagnostics.safeParse({ schemaVersion: 1,
      fairness: "SKIPPED_REFERENCE_OVERTIME", decision: "REJECTED_OVERTIME", referenceOvertimeMinutes, candidateOvertimeMinutes: 20, candidate }).success,false);
    assert.equal(dailyPolicyDiagnostics.safeParse({ schemaVersion: 1, fairness: "SKIPPED_INVALID_REFERENCE", decision: "REJECTED_UNRESOLVED_DEMAND",
      referenceOvertimeMinutes: null, candidateOvertimeMinutes: null, candidate: { ...candidate, complete: false, unassignedVisitIds: ["a"] } }).success,false);
  });
});
