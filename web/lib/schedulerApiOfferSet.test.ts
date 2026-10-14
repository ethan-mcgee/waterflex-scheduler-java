import { test } from "node:test";
import assert from "node:assert/strict";
import { offerSet } from "./schedulerApi";

const set = { offerSetId: "set-1", expiresAt: "2026-10-12T15:10:00-05:00", searchComplete: true, skippedTechnicianDays: [], offers: [] };

test("an offer set names how its refinement ended, or leaves it out only when replayed from before spec 1.2.0", () => {
  for (const refinement of ["COMPLETE", "SHORTENED", "SKIPPED", "UNAVAILABLE"])
    assert.equal(offerSet.safeParse({ ...set, refinement }).success, true, refinement);
  assert.equal(offerSet.safeParse(set).success, true, "absent");
  assert.equal(offerSet.safeParse({ ...set, refinement: null }).success, false, "never null");
  assert.equal(offerSet.safeParse({ ...set, refinement: "PARTIAL" }).success, false, "unknown outcome");
});
