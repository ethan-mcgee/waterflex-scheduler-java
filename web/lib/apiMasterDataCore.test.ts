import { test } from "node:test";
import assert from "node:assert/strict";
import { ChangeRefused, policyEffectiveDate, RouteRefused, retimedWrite, withAssignment, withDepotLocation, withPolicy, type TimedDay } from "./apiMasterDataCore";
import type { DepotFacts, TechnicianFacts } from "./clientSnapshot";
import type { RouteEvaluation } from "./schedulerApi";

const depot = (id: string, lat: number): DepotFacts => ({ id, metroId: "metro", lat, lng: -95.9,
  policies: [{ effectiveDate: "1900-01-01", departure: "DEPOT", returnTo: "DEPOT" }] });

function facts(): { technicians: TechnicianFacts[] } {
  const technician = (id: string, assigned: DepotFacts): TechnicianFacts => ({ id, active: true, homeLat: 41.3, homeLng: -96, maxDailyMinutes: 540, qualifications: [],
    assignments: [{ effectiveDate: "1900-01-01", depot: assigned }], versions: [], overrides: [], absences: [] });
  return { technicians: [technician("a", depot("north", 41.2)), technician("b", depot("north", 41.2)), technician("c", depot("south", 41.1))] };
}

test("a depot change reaches every technician working from that depot, and only them", () => {
  const before = facts();
  const policy = withPolicy(before, "north", { effectiveDate: "2026-10-14", departure: "HOME", returnTo: "DEPOT" });
  for (const id of ["a", "b"]) {
    const policies = policy.technicians.find(item => item.id === id)?.assignments[0]?.depot.policies;
    assert.deepEqual(policies?.map(item => item.effectiveDate), ["1900-01-01", "2026-10-14"]);
    assert.equal(policies?.[1]?.departure, "HOME");
  }
  assert.equal(policy.technicians.find(item => item.id === "c")?.assignments[0]?.depot.policies.length, 1);
  assert.equal(before.technicians[0]?.assignments[0]?.depot.policies.length, 1, "The loaded facts are not changed");
  const replaced = withPolicy(policy, "north", { effectiveDate: "2026-10-14", departure: "DEPOT", returnTo: "HOME" });
  assert.equal(replaced.technicians[0]?.assignments[0]?.depot.policies.length, 2, "A policy starting the same day is replaced");
  assert.equal(replaced.technicians[0]?.assignments[0]?.depot.policies[1]?.returnTo, "HOME");

  const moved = withDepotLocation(before, "south", { lat: 41.05, lng: -95.8 });
  assert.equal(moved.technicians.find(item => item.id === "c")?.assignments[0]?.depot.lat, 41.05);
  assert.equal(moved.technicians.find(item => item.id === "a")?.assignments[0]?.depot.lat, 41.2);

  const reassigned = withAssignment(before, "a", "2026-10-15", depot("south", 41.1));
  assert.deepEqual(reassigned.technicians.find(item => item.id === "a")?.assignments.map(item => [item.effectiveDate, item.depot.id]),
    [["1900-01-01", "north"], ["2026-10-15", "south"]]);
  assert.equal(reassigned.technicians.find(item => item.id === "b")?.assignments.length, 1);
});

test("new route endpoints start today, tomorrow once today is frozen, or replace the next planned change", () => {
  assert.equal(policyEffectiveDate("2026-10-14", false, null), "2026-10-14");
  assert.equal(policyEffectiveDate("2026-10-14", true, null), "2026-10-15");
  assert.equal(policyEffectiveDate("2026-10-14", true, "2026-10-20"), "2026-10-20");
});

const DATE = "2026-10-14";
const changed: TimedDay = { technicianDays: [{ technicianId: "a", serviceDate: DATE, lastModified: "2026-10-01T00:00:00.000001Z" }],
  appointments: [{ id: "x", technicianId: "a", serviceDate: DATE }, { id: "y", technicianId: "a", serviceDate: DATE }] };
const stop = (id: string, sequence: number, start: string) => ({ appointmentId: id, sequence, plannedStart: `${DATE}T${start}:00Z`, plannedEnd: `${DATE}T${start}:45Z` });
const timed = (overrides: Partial<RouteEvaluation> = {}): RouteEvaluation => ({ feasible: true, costCents: 100, overtimeMinutes: 0, skippedTechnicianDays: [],
  routes: [{ technicianId: "a", serviceDate: DATE, stops: [stop("x", 0, "13:10"), stop("y", 1, "14:10")] }], ...overrides });

test("timed routes become receipt writes compared against the technician-days they were timed from", () => {
  const write = retimedWrite(DATE, ["a"], changed, timed());
  assert.deepEqual(write.receipt.technicianDays, [{ technicianId: "a", serviceDate: DATE, lastModified: "2026-10-01T00:00:00.000001Z" }]);
  assert.deepEqual(write.receipt.assignments.map(item => [item.appointmentId, item.sequence, item.plannedStart]), [["x", 0, `${DATE}T13:10:00Z`], ["y", 1, `${DATE}T14:10:00Z`]]);
  assert.deepEqual(write.complete, [{ technicianId: "a", serviceDate: DATE }]);
});

test("a change the booked routes cannot absorb is refused, and a timing that does not match the day is an error", () => {
  const refused = (evaluation: RouteEvaluation, snapshot = changed) => assert.throws(() => retimedWrite(DATE, ["a"], snapshot, evaluation),
    (error: unknown) => error instanceof RouteRefused && error instanceof ChangeRefused && error.status === 409);
  refused(timed({ feasible: false, routes: [] }));
  refused(timed({ routes: [], skippedTechnicianDays: [{ technicianId: "a", serviceDate: DATE, reason: "LOCATION_UNRESOLVED", message: "No coordinates" }] }));
  refused(timed(), { ...changed, technicianDays: [] });
  assert.throws(() => retimedWrite(DATE, ["a"], changed, timed({ routes: [] })), /did not time/);
  assert.throws(() => retimedWrite(DATE, ["a"], changed, timed({ routes: [{ technicianId: "a", serviceDate: DATE, stops: [stop("x", 0, "13:10")] }] })),
    /different appointments/);
});
