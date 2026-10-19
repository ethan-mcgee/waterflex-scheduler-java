import { test } from "node:test";
import assert from "node:assert/strict";
import { offerSet } from "./schedulerApi";

const set = { offerSetId: "set-1", expiresAt: "2026-10-12T15:10:00-05:00", searchComplete: true, skippedTechnicianDays: [], offers: [] };

test("an offer set names how its refinement ended, or leaves it out only when replayed from before spec 1.2.0", () => {
  for (const refinement of ["COMPLETE", "SHORTENED", "SKIPPED", "UNAVAILABLE", "SHED"])
    assert.equal(offerSet.safeParse({ ...set, refinement }).success, true, refinement);
  assert.equal(offerSet.safeParse(set).success, true, "absent");
  assert.equal(offerSet.safeParse({ ...set, refinement: null }).success, false, "never null");
  assert.equal(offerSet.safeParse({ ...set, refinement: "PARTIAL" }).success, false, "unknown outcome");
});

test("an offer set may say what stopped a shortened refinement and how many windows the first pass found (spec 1.5.0)", () => {
  assert.equal(offerSet.safeParse({ ...set, refinement: "SHORTENED", refinementStop: "CAP", regularWindowsFound: 7 }).success, true);
  assert.equal(offerSet.safeParse({ ...set, refinement: "SHORTENED", refinementStop: "DEADLINE" }).success, true);
  assert.equal(offerSet.safeParse({ ...set, regularWindowsFound: 0 }).success, true, "a first pass that found no window");
  assert.equal(offerSet.safeParse({ ...set, refinementStop: "BUDGET" }).success, false, "unknown stop");
  assert.equal(offerSet.safeParse({ ...set, regularWindowsFound: -1 }).success, false, "a count is never negative");
  assert.equal(offerSet.safeParse({ ...set, regularWindowsFound: null }).success, false, "never null");
});

test("an offer set lists the dates its search did not finish, or leaves them out only when replayed from before spec 1.3.0", () => {
  assert.equal(offerSet.safeParse({ ...set, incompleteDates: [] }).success, true);
  assert.equal(offerSet.safeParse({ ...set, searchComplete: false, incompleteDates: ["2026-10-12", "2026-10-13"] }).success, true);
  assert.equal(offerSet.safeParse({ ...set, incompleteDates: ["12 October"] }).success, false, "not a service date");
  assert.equal(offerSet.safeParse({ ...set, incompleteDates: null }).success, false, "never null");
});
